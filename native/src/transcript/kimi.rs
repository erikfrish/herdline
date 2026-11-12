//! Kimi Code's main-agent wire journal (`<KIMI_CODE_HOME or ~/.kimi-code>/
//! sessions/<bucket>/<id>/agents/main/wire.jsonl`, MoonshotAI kimi-code
//! 482936af), read only. The host script fences the pane's exact identity,
//! finds the session directory as Kimi does, proves it is bound to the
//! running process's working directory and reads the file without following
//! a link. The tree, the active chain and the message fold are rebuilt here.
//! Never sends, resumes, queues or opens a subagent's journal or a media file.

use super::{
    ARGS_LIMIT, Block, Entry, HEADER_ROOM, Identity, Image, Kind, MAX_WINDOW, RESULT_LIMIT, Read,
    Response, Source, Usage, cap, text_block,
};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};

/// What [`Error::UnsupportedFormat`] names when the store is not one this
/// reader may open, or the journal is not one it can read.
const OUTSIDE_HOME: &str = "kimi_store_outside_home";
const AMBIGUOUS: &str = "kimi_session_ambiguous";
const UNREADABLE: &str = "kimi_store_unreadable";
const TOO_LARGE: &str = "kimi_session_too_large";
const VERSION: &str = "kimi_wire_version";
/// The pane's foreground process is none [`foreground_pid`] accepts, so
/// there is no process whose environment and directory bind the store.
const UNNAMED: &str = "kimi_process_unnamed";
const REFUSALS: [&str; 6] = [OUTSIDE_HOME, AMBIGUOUS, UNREADABLE, TOO_LARGE, VERSION, UNNAMED];

/// Herdr's name is the process's comm (herdr platform/linux.rs:385-387), and
/// Kimi Code sets `process.title = 'kimi-code'` first thing (cli/main.ts:181,
/// constant/app.ts:7), so a running Kimi reads `kimi-code`; Herdr knows it as
/// `kimi` or `kimi-code` (detect/mod.rs:213).
const PROCESS_NAMES: [&str; 2] = ["kimi", "kimi-code"];
/// The npm package's entry, as Herdr matches a `node` launch (detect/mod.rs:723-731).
const NODE_ENTRY: [&str; 5] = ["node_modules", "@moonshot-ai", "kimi-code", "dist", "main.mjs"];

/// The pane's Kimi Code process: the first foreground process named
/// [`PROCESS_NAMES`], else the first `node` whose script (its first argument
/// after argv\[0\] that is no option) ends in [`NODE_ENTRY`], ASCII case
/// folded as Herdr compares. The host script's `identity()` picks the same.
pub(super) fn foreground_pid(process: &Value) -> Option<u32> {
    let all = process["result"]["process_info"]["foreground_processes"].as_array()?;
    let pid = |process: &Value| process["pid"].as_u64().and_then(|pid| u32::try_from(pid).ok());
    let named = all.iter().find(|process| PROCESS_NAMES.iter().any(|name| process["name"] == *name));
    named.and_then(pid).or_else(|| all.iter().find(|process| process["name"] == "node" && node_entry(&process["argv"])).and_then(pid))
}

fn node_entry(argv: &Value) -> bool {
    let Some(script) = argv.as_array().and_then(|argv| argv.iter().skip(1).filter_map(Value::as_str).find(|arg| !arg.starts_with('-'))) else {
        return false;
    };
    let parts: Vec<&str> = script.split(['/', '\\']).filter(|part| !part.is_empty()).collect();
    parts.len() >= NODE_ENTRY.len()
        && parts[parts.len() - NODE_ENTRY.len()..].iter().zip(NODE_ENTRY).all(|(part, entry)| part.eq_ignore_ascii_case(entry))
}

/// The protocol this reader is pinned to (migration.ts:19-20).
const WIRE: &str = "1.5";
/// migration.ts:45-51: each step's source version, matched exactly.
const MIGRATIONS: [(&str, &str); 5] = [
    ("1.0", "1.1"),
    ("1.1", "1.2"),
    ("1.2", "1.3"),
    ("1.3", "1.4"),
    ("1.4", "1.5"),
];
const MAIN: &str = "main";
const SWITCHED: &str = "agent.switched";

/// Every record type the pinned writer persists (docs/wire-manifest.d.ts
/// index, 67 types) plus the journal header and the tree's own records
/// (tree.ts:4, fork.ts:43). Any other type is shown as an unsupported row.
const KNOWN: [&str; 70] = [
    "metadata", SWITCHED, "context.undone",
    "changeNotifier.snapshot", "config.update", "context.append_loop_event",
    "context.append_message", "context.apply_compaction", "context.clear", "context.undo",
    "cron.add", "cron.cursor", "cron.delete", "environment.set_binding",
    "file_history.checkpoint", "file_history.tracked", "forked", "full_compaction.begin",
    "full_compaction.cancel", "full_compaction.complete", "goal.clear", "goal.create",
    "goal.update", "interaction.request", "interaction.resolved",
    "interruptionReminder.recorded", "interruptionReminder.requested", "llm.request",
    "llm.tools_snapshot", "mcp.server_failed", "mcp.server_failure_resolved",
    "mcp.tools_discovered", "permission.record_approval_result", "permission.set_mode",
    "plan_mode.cancel", "plan_mode.enter", "plan_mode.exit", "plan.revision",
    "plugin.session_start", "profile.bind", "prompt.aborted", "prompt.completed",
    "prompt.steered", "subagent.cancelled", "subagent.completed", "subagent.failed",
    "subagent.spawned", "subagent.started", "swarm_mode.enter", "swarm_mode.exit",
    "task.started", "task.terminated", "token_counting.measured", "token_counting.rebased",
    "token_counting.truncated", "token_counting.turn_recorded", "tool_select.initialized",
    "tool_select.tools_changed", "tools.register_user_tool", "tools.reset_active_tools",
    "tools.set_active_tools", "tools.unregister_user_tool", "tools.update_store",
    "tower_mode.enter", "tower_mode.exit", "turn.cancel", "turn.ended", "turn.prompt",
    "turn.steer", "usage.record",
];

/// loopEventFold.ts:11-12, the tool message a call without a recorded result
/// gets when its step is settled.
const INTERRUPTED: &str = "Tool execution was interrupted before its result was recorded. Do not assume the tool completed successfully.";

/// sessionLifecycleService.ts:1088 writes `session_<uuid>`; session-store.ts
/// SESSION_ID_RE `^session_[A-Za-z0-9._-]+$` is what a lookup accepts.
pub(super) fn session_id(value: &str) -> bool {
    value.strip_prefix("session_").is_some_and(|rest| {
        !rest.is_empty()
            && rest
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
    })
}

/// The whole active chain of the session, or "not modified" when the
/// journal's device, inode, size and mtime are unchanged since the stamp
/// the app holds in `end`. An undo or a branch switch can drop any earlier
/// turn, so there is no byte cursor: any change reloads and replaces.
pub(super) fn page(
    exec: &Exec,
    session: &str,
    pane: &str,
    expect: &Identity,
    read: Read,
    reset: bool,
) -> Result<Response> {
    let stamp = match read {
        Read::After { offset } => offset,
        Read::Tail | Read::Before { .. } => 0,
    };
    let output = exec.run(
        format!(
            "/bin/sh -c {}",
            shell_quote(&script(session, pane, expect, stamp, None))
        ),
        super::read_timeout(MAX_WINDOW),
        usize::try_from(MAX_WINDOW).unwrap_or(usize::MAX) + HEADER_ROOM,
    )?;
    if output.exit_status != Some(0) {
        return Err(Error::NoTranscript);
    }
    answer(&output.tail, expect, reset)
}

/// `fixture` (tests only): a file read instead of `/proc/<pid>/environ`
/// and a link read instead of `/proc/<pid>/cwd`.
fn script(
    session: &str,
    pane: &str,
    expect: &Identity,
    stamp: u64,
    fixture: Option<(&str, &str)>,
) -> String {
    format!(
        "python3 - {} {} {}{} <<'HERDLINE_KIMI_PY'\n{PYTHON}\nHERDLINE_KIMI_PY",
        shell_quote(&serde_json::to_string(expect).unwrap_or_default()),
        shell_quote(&json!({ "stamp": stamp }).to_string()),
        shell_quote(&super::instance_script(session, pane)),
        fixture
            .map(|(environ, cwd)| format!(" {} {}", shell_quote(environ), shell_quote(cwd)))
            .unwrap_or_default(),
    )
}

/// The host script's reply: one JSON header line, then for a changed file
/// its bytes exactly as read.
fn answer(bytes: &[u8], expect: &Identity, reset: bool) -> Result<Response> {
    let (head, data) = match bytes.iter().position(|&byte| byte == b'\n') {
        Some(at) => (&bytes[..at], &bytes[at + 1..]),
        None => (bytes, &[][..]),
    };
    let head: Value = serde_json::from_slice(head)?;
    if head["changed"] == true {
        return Err(Error::NoTranscript);
    }
    if let Some(format) = head["format"].as_str() {
        return Err(Error::UnsupportedFormat(
            REFUSALS
                .into_iter()
                .find(|known| *known == format)
                .ok_or(Error::BadReply)?,
        ));
    }
    match head["error"].as_str() {
        None => {}
        Some("foreign") => return Err(Error::TranscriptUnconfined),
        Some(_) => return Err(Error::NoTranscript),
    }
    let stamp = head["stamp"].as_u64().ok_or(Error::BadReply)?;
    let not_modified = head["not_modified"] == true;
    let entries = if not_modified {
        Vec::new()
    } else {
        project(data).map_err(Error::UnsupportedFormat)?
    };
    Ok(Response {
        identity: expect.clone(),
        status: head["status"].as_str().unwrap_or("unknown").to_owned(),
        reset: reset || !not_modified,
        size: head["size"].as_u64().ok_or(Error::BadReply)?,
        start: 0,
        end: stamp,
        has_older: false,
        entries,
    })
}

type Refusal = std::result::Result<Vec<Entry>, &'static str>;

/// The journal as Chat entries: records, version, active chain, fold.
fn project(data: &[u8]) -> Refusal {
    let mut lines = wire(data);
    migrate(&mut lines)?;
    let chain = restorable(&lines)?;
    let mut fold = Fold::new(&lines);
    for at in chain {
        fold.add(at);
    }
    Ok(fold.finish())
}

/// One record and its 1-based line as the wire reader counts it.
struct Line {
    line: u64,
    record: Value,
}

/// appendLogStore.ts:95-155 and wireService.ts:376-387. An empty line is
/// no record and no line; a line that is not JSON ends the read (onTruncate
/// "corrupted") unless it is the unterminated last one, which is dropped.
/// A JSON value that is not an object with a string `type` takes its line
/// number and is skipped.
fn wire(data: &[u8]) -> Vec<Line> {
    let mut lines = Vec::new();
    let mut count = 0;
    let mut rest = data;
    loop {
        let (raw, next) = match rest.iter().position(|&byte| byte == b'\n') {
            Some(at) => (&rest[..at], Some(&rest[at + 1..])),
            None => (rest, None),
        };
        let raw = raw.strip_suffix(b"\r").unwrap_or(raw);
        if !raw.is_empty() {
            match serde_json::from_str::<Value>(&String::from_utf8_lossy(raw)) {
                Ok(record) => {
                    count += 1;
                    if record["type"].is_string() {
                        lines.push(Line { line: count, record });
                    }
                }
                Err(_) if next.is_some() => break,
                Err(_) => {}
            }
        }
        match next {
            Some(next) => rest = next,
            None => break,
        }
    }
    lines
}

/// wireService.ts:390-411. No header: only migrateV1_4ToV1_5, which touches
/// goal records alone (v1.5.ts:16-17). A header must be well formed
/// (record.ts:69-78), its `min_protocol_version` at most 1.5
/// (migration.ts:22-38), and an older protocol needs an exact migration
/// chain (migration.ts:63-85); a newer one is read as it is. Of the steps,
/// only 1.0→1.1 changes what Chat shows (v1.1.ts:22-42); 1.2 and 1.4 touch
/// approval and goal records (v1.2.ts:37, v1.4.ts:50), 1.3 nothing shown.
fn migrate(lines: &mut [Line]) -> std::result::Result<(), &'static str> {
    let Some(first) = lines.first() else {
        return Ok(());
    };
    let meta = &first.record;
    if meta["type"] != "metadata" {
        return Ok(());
    }
    let min = match meta.get("min_protocol_version") {
        None => None,
        Some(Value::String(min)) if dotted(min) => Some(min.as_str()),
        Some(_) => return Err(UNREADABLE),
    };
    let Some(protocol) = meta["protocol_version"].as_str() else {
        return Err(UNREADABLE);
    };
    if !meta["created_at"].is_number() {
        return Err(UNREADABLE);
    }
    if min.is_some_and(|min| compare(WIRE, min) < 0.0) {
        return Err(VERSION);
    }
    let mut flatten = false;
    // A NaN comparison (a part `Number` cannot read) is neither newer nor
    // older: no migration runs, as at the pin.
    if compare(protocol, WIRE) <= 0.0 || compare(protocol, WIRE).is_nan() {
        let mut version = protocol;
        while compare(version, WIRE) < 0.0 {
            let Some(at) = MIGRATIONS.iter().position(|(source, _)| *source == version) else {
                return Err(VERSION);
            };
            flatten |= at == 0;
            version = MIGRATIONS[at].1;
        }
    }
    if flatten {
        for line in lines.iter_mut() {
            flatten_calls(&mut line.record);
        }
    }
    Ok(())
}

/// record.ts:76 `/^\d+(\.\d+)*$/`.
fn dotted(text: &str) -> bool {
    text.split('.')
        .all(|part| !part.is_empty() && part.bytes().all(|byte| byte.is_ascii_digit()))
}

/// migration.ts:111-123 compareWireVersions: part by part through JS
/// `Number`, a missing part `0`; NaN when a part is not a number.
fn compare(a: &str, b: &str) -> f64 {
    let a: Vec<&str> = a.split('.').collect();
    let b: Vec<&str> = b.split('.').collect();
    for at in 0..a.len().max(b.len()) {
        let diff = number(a.get(at).copied().unwrap_or("0")) - number(b.get(at).copied().unwrap_or("0"));
        if diff != 0.0 {
            return diff;
        }
    }
    0.0
}

/// JS `Number(string)` for the forms a version part can take.
fn number(text: &str) -> f64 {
    let text = text.trim();
    if text.is_empty() {
        return 0.0;
    }
    match text {
        "Infinity" | "+Infinity" => return f64::INFINITY,
        "-Infinity" => return f64::NEG_INFINITY,
        _ => {}
    }
    for (prefix, radix) in [("0x", 16), ("0X", 16), ("0o", 8), ("0O", 8), ("0b", 2), ("0B", 2)] {
        if let Some(digits) = text.strip_prefix(prefix) {
            return u64::from_str_radix(digits, radix).map_or(f64::NAN, |value| value as f64);
        }
    }
    if !text.bytes().all(|byte| byte.is_ascii_digit() || matches!(byte, b'.' | b'e' | b'E' | b'+' | b'-')) {
        return f64::NAN;
    }
    text.parse().unwrap_or(f64::NAN)
}

/// v1.1.ts:22-42: `toolCalls[].function{name,arguments}` moved to the call.
fn flatten_calls(record: &mut Value) {
    if record["type"] != "context.append_message" {
        return;
    }
    let Some(calls) = record["message"]["toolCalls"].as_array_mut() else {
        return;
    };
    for call in calls.iter_mut().filter_map(Value::as_object_mut) {
        let Some(Value::Object(function)) = call.remove("function") else {
            continue;
        };
        for key in ["name", "arguments"] {
            match function.get(key) {
                Some(value) => call.insert(key.to_owned(), value.clone()),
                None => call.remove(key),
            };
        }
    }
}

struct Edge<'a> {
    line: u64,
    base: &'a str,
    upto: f64,
}

struct Segment {
    edge: Option<usize>,
    to: f64,
}

/// The records Kimi restores, by index into `lines`: tree.ts:68-106
/// parseTree, tree.ts:108-132 activeChain (a cycle or an unknown base is a
/// refusal, where Kimi throws), then fork.ts:38-71 restorableChain.
fn restorable(lines: &[Line]) -> std::result::Result<Vec<usize>, &'static str> {
    let mut edges = Vec::new();
    let mut branches = Vec::new();
    let mut paired = Vec::new();
    for line in lines {
        let record = &line.record;
        if record["type"] != SWITCHED {
            continue;
        }
        // tree.ts:39-57: a malformed switch is no edge.
        let (Some(branch), Some(base), Some(upto)) = (
            record["branch"].as_str(),
            record["base"]["branch"].as_str(),
            record["base"]["line"].as_f64(),
        ) else {
            continue;
        };
        if let Some(undo) = record["legacyUndoLine"].as_f64() {
            paired.push(undo);
        }
        edges.push(Edge { line: line.line, base, upto });
        branches.push(branch);
    }
    let last = lines.last().map_or(0, |line| line.line);
    let mut segments: HashMap<&str, Segment> = HashMap::new();
    let mut branch = MAIN;
    let mut from = 1;
    let mut opening = None;
    for (at, edge) in edges.iter().enumerate() {
        // A branch named twice keeps its last segment (the Map in
        // activeChain).
        segments.insert(branch, Segment { edge: opening, to: edge.line as f64 });
        branch = branches[at];
        opening = Some(at);
        from = edge.line + 1;
    }
    segments.insert(branch, Segment { edge: opening, to: last.max(from - 1) as f64 });

    // The base chain from the active branch to `main`, walked iteratively.
    let mut walk = Vec::new();
    let mut visited = HashSet::new();
    let mut at = (branch, None);
    loop {
        if !visited.insert(at.0) {
            return Err(UNREADABLE);
        }
        let segment = segments.get(at.0).ok_or(UNREADABLE)?;
        walk.push((segment, at.1));
        match segment.edge {
            Some(edge) => at = (edges[edge].base, Some(edges[edge].upto)),
            None => break,
        }
    }
    let mut chain = Vec::new();
    for (segment, upto) in walk.into_iter().rev() {
        let floor = segment.edge.map_or(1, |edge| edges[edge].line + 1);
        let ceil = upto.unwrap_or(segment.to);
        let start = lines.partition_point(|line| line.line < floor);
        for (index, line) in lines.iter().enumerate().skip(start) {
            if line.line as f64 > ceil {
                break;
            }
            if line.record["type"] != SWITCHED {
                chain.push(index);
            }
        }
    }

    chain.retain(|&index| {
        let record = &lines[index].record;
        record["type"] != "context.undone"
            && !(record["type"] == "context.undo" && paired.contains(&(lines[index].line as f64)))
    });
    let on: HashSet<usize> = chain.iter().copied().collect();
    let surviving: HashSet<&str> = chain
        .iter()
        .filter(|&&index| lines[index].record["type"] == "turn.prompt")
        .filter_map(|&index| lines[index].record["promptId"].as_str())
        .collect();
    for (index, line) in lines.iter().enumerate() {
        if on.contains(&index) {
            continue;
        }
        let record = &line.record;
        if record["type"] == "turn.prompt" {
            chain.push(index);
            continue;
        }
        if record["type"] != "context.append_message" {
            continue;
        }
        let message = &record["message"];
        if !message["role"].is_string() || !message["content"].is_array() {
            continue;
        }
        let origin = &message["origin"];
        if origin["kind"] != "injection" {
            continue;
        }
        let owned = match origin.get("ownerPromptId") {
            None => true,
            Some(Value::String(owner)) => surviving.contains(owner.as_str()),
            Some(_) => false,
        };
        if owned {
            chain.push(index);
        }
    }
    chain.sort_by_key(|&index| lines[index].line);
    Ok(chain)
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Role {
    System,
    User,
    Assistant,
    Tool,
}

#[derive(Debug, Clone)]
struct Call {
    id: String,
    name: String,
    args: Option<String>,
}

#[derive(Debug, Clone)]
struct Message {
    line: u64,
    time: Option<u64>,
    role: Role,
    content: Vec<Value>,
    calls: Vec<Call>,
    call_id: Option<String>,
    is_error: bool,
    usage: Option<Usage>,
}

#[derive(Debug, Clone)]
enum Row {
    Message(Message),
    /// The summary a compaction leaves; what precedes it is not shown.
    Summary { line: u64, time: Option<u64>, text: String },
    /// A record or loop event of a type this reader does not know, counted
    /// while the same type repeats.
    Unsupported { line: u64, name: String, count: u64 },
}

/// contextTranscript.ts:37-158 createContextTranscriptReducer with the
/// loop-event fold of loopEventFold.ts:108-232 and the undo cut of
/// turnUndo.ts:57-152. Two display choices go past the pin, which keeps
/// every entry: after `context.clear` only what follows it is shown, behind
/// a boundary; after `context.apply_compaction` its summary is the first
/// row shown.
struct Fold<'a> {
    lines: &'a [Line],
    /// What is folded, by index into `lines`: everything but `context.undo`.
    records: Vec<usize>,
    rows: Vec<Row>,
    floor: usize,
    cleared: bool,
    open: Option<usize>,
    /// `openStepUuid`: `None` is undefined, `Some(Value::Null)` null.
    step: Option<Value>,
    calls: bool,
    vacuous: bool,
    pending: Vec<String>,
    deferred: Vec<Row>,
}

impl<'a> Fold<'a> {
    fn new(lines: &'a [Line]) -> Self {
        Self {
            lines,
            records: Vec::new(),
            rows: Vec::new(),
            floor: 0,
            cleared: false,
            open: None,
            step: None,
            calls: false,
            vacuous: true,
            pending: Vec::new(),
            deferred: Vec::new(),
        }
    }

    /// contextTranscript.ts:127-134.
    fn add(&mut self, at: usize) {
        let lines = self.lines;
        let record = &lines[at].record;
        if record["type"] == "context.undo" {
            self.undo(&record["count"]);
            return;
        }
        self.records.push(at);
        self.apply(at);
    }

    /// contextTranscript.ts:83-114 appendRecord.
    fn apply(&mut self, at: usize) {
        let lines = self.lines;
        let Line { line, record } = &lines[at];
        let line = *line;
        let time = record["time"].as_f64().filter(|time| *time >= 0.0).map(|time| time as u64);
        let kind = record["type"].as_str().unwrap_or_default();
        match kind {
            "context.append_message" => match message(&record["message"], line, time) {
                Some(message) => self.append(Row::Message(message)),
                None => self.unsupported(line, kind),
            },
            "context.append_loop_event" => self.event(&record["event"], line, time),
            "context.apply_compaction" => {
                if record["keptUserMessageCount"].is_number() {
                    self.settle(line, time);
                } else {
                    self.reset();
                }
                self.floor = self.rows.len();
                self.cleared = false;
                self.rows.push(Row::Summary { line, time, text: summary(record) });
            }
            "context.clear" => {
                self.floor = self.rows.len();
                self.cleared = true;
                self.reset();
            }
            _ if KNOWN.contains(&kind) => {}
            _ => self.unsupported(line, kind),
        }
    }

    /// contextTranscript.ts:116-125 applyUndo: cut the folded records at
    /// the anchor `count` turns back and fold again from the start.
    fn undo(&mut self, count: &Value) {
        let Some(count) = undo_count(count) else {
            return;
        };
        let anchors = self.anchors();
        let Some(&cut) = anchors.len().checked_sub(count).and_then(|at| anchors.get(at)) else {
            return;
        };
        self.records.truncate(cut);
        self.rows.clear();
        self.floor = 0;
        self.cleared = false;
        self.reset();
        let records = std::mem::take(&mut self.records);
        for &at in &records {
            self.apply(at);
        }
        self.records = records;
    }

    /// turnUndo.ts:57-133 projectTurnUndo, the cut index of each anchor.
    /// Turn ids are not tracked: they never move a cut. No `context.undo`
    /// is ever among the folded records.
    fn anchors(&self) -> Vec<usize> {
        let mut anchors = Vec::new();
        for (index, &at) in self.records.iter().enumerate() {
            let record = &self.lines[at].record;
            match record["type"].as_str() {
                Some("context.clear" | "context.apply_compaction") => anchors.clear(),
                Some("turn.steer") => {
                    let prompt = record["messageId"].as_str().or_else(|| record["promptIds"][0].as_str());
                    let notify = Value::from("notify");
                    let empty = record["input"].as_array().is_none_or(Vec::is_empty);
                    if anchor(Some(&notify), empty, prompt, record.get("origin")) {
                        anchors.push(self.owned_start(index, prompt));
                    }
                }
                Some("turn.prompt") => {
                    let prompt = record["promptId"].as_str();
                    let empty = record["input"].as_array().is_none_or(Vec::is_empty);
                    if anchor(record.get("trigger"), empty, prompt, record.get("origin")) {
                        anchors.push(self.owned_start(index, prompt));
                    }
                }
                _ => {}
            }
        }
        anchors
    }

    /// turnUndo.ts:171-192 promptOwnedRecordStart: back over the prompt's
    /// own user message and the injections it owns.
    fn owned_start(&self, index: usize, prompt: Option<&str>) -> usize {
        let Some(prompt) = prompt else {
            return index;
        };
        let mut start = index;
        let mut injection = false;
        for cursor in (0..index).rev() {
            let record = &self.lines[self.records[cursor]].record;
            if record["type"] != "context.append_message" {
                continue;
            }
            let message = &record["message"];
            let owned_message = message["id"] == prompt && message["role"] == "user";
            let owned_injection =
                message["origin"]["kind"] == "injection" && message["origin"]["ownerPromptId"] == prompt;
            if owned_message && injection {
                break;
            }
            if !owned_message && !owned_injection {
                break;
            }
            start = cursor;
            injection |= owned_injection;
        }
        start
    }

    /// loopEventFold.ts:163-219.
    fn event(&mut self, event: &Value, line: u64, time: Option<u64>) {
        match event["type"].as_str() {
            Some("step.begin") => {
                self.settle_open(line, time, None);
                self.rows.push(Row::Message(Message {
                    line,
                    time,
                    role: Role::Assistant,
                    content: Vec::new(),
                    calls: Vec::new(),
                    call_id: None,
                    is_error: false,
                    usage: None,
                }));
                self.open = Some(self.rows.len() - 1);
                self.step = event.get("uuid").cloned();
                self.calls = false;
                self.vacuous = true;
            }
            Some("step.end") => {
                self.settle_open(line, time, usage(&event["usage"]));
                self.flush();
            }
            Some("content.part") => {
                if !self.accepts(event.get("stepUuid")) {
                    return;
                }
                let part = &event["part"];
                if let Some(Row::Message(open)) = self.open.and_then(|at| self.rows.get_mut(at)) {
                    open.content.push(part.clone());
                }
                self.vacuous = self.vacuous && vacuous(part);
            }
            Some("tool.call") => {
                if !self.accepts(event.get("stepUuid")) {
                    return;
                }
                let id = key(&event["toolCallId"]);
                let call = Call {
                    id: id.clone(),
                    name: super::string(&event["name"]),
                    args: event.get("args").map(Value::to_string),
                };
                if let Some(Row::Message(open)) = self.open.and_then(|at| self.rows.get_mut(at))
                    && open.role == Role::Assistant
                {
                    open.calls.push(call);
                }
                if !self.pending.contains(&id) {
                    self.pending.push(id);
                }
                self.calls = true;
            }
            Some("tool.result") => {
                let id = key(&event["toolCallId"]);
                let Some(at) = self.pending.iter().position(|pending| *pending == id) else {
                    return;
                };
                self.pending.remove(at);
                let result = &event["result"];
                let content = match &result["output"] {
                    Value::String(text) => vec![json!({"type": "text", "text": text})],
                    Value::Array(parts) => parts.clone(),
                    _ => Vec::new(),
                };
                self.rows.push(Row::Message(tool(line, time, id, content, result["isError"] == true)));
                self.flush();
            }
            other => {
                let name = format!("context.append_loop_event:{}", other.unwrap_or("none"));
                self.unsupported(line, &name);
            }
        }
    }

    /// loopEventFold.ts:146-153 acceptsOpenStep.
    fn accepts(&mut self, step: Option<&Value>) -> bool {
        match &self.step {
            None => false,
            Some(Value::Null) => {
                self.step = step.cloned();
                true
            }
            Some(open) => step == Some(open),
        }
    }

    /// loopEventFold.ts:136-145 settleOpen.
    fn settle_open(&mut self, line: u64, time: Option<u64>, usage: Option<Usage>) {
        if self.step.is_none() {
            return;
        }
        self.close_pending(line, time);
        if let Some(at) = self.open.take() {
            if !self.calls && self.vacuous {
                self.rows.remove(at);
            } else if let Row::Message(open) = &mut self.rows[at]
                && usage.is_some()
            {
                open.usage = usage;
            }
        }
        self.step = None;
    }

    /// loopEventFold.ts:125-135 closePending.
    fn close_pending(&mut self, line: u64, time: Option<u64>) {
        if self.pending.is_empty() {
            return;
        }
        for id in std::mem::take(&mut self.pending) {
            let recorded = self.deferred.iter().any(|row| {
                matches!(row, Row::Message(message) if message.role == Role::Tool && message.call_id.as_deref() == Some(id.as_str()))
            });
            if !recorded {
                let content = vec![json!({"type": "text", "text": INTERRUPTED})];
                self.rows.push(Row::Message(tool(line, time, id, content, true)));
            }
        }
        self.flush();
    }

    /// loopEventFold.ts:220-223 settle.
    fn settle(&mut self, line: u64, time: Option<u64>) {
        self.settle_open(line, time, None);
        self.flush();
    }

    /// loopEventFold.ts:156-162: a message waits while a call is unanswered.
    fn append(&mut self, row: Row) {
        if self.pending.is_empty() {
            self.rows.push(row);
        } else {
            self.deferred.push(row);
        }
    }

    /// loopEventFold.ts:120-124 flushDeferred.
    fn flush(&mut self) {
        if !self.pending.is_empty() {
            return;
        }
        self.rows.append(&mut self.deferred);
    }

    /// loopEventFold.ts:224-230 with contextTranscript.ts:77-80.
    fn reset(&mut self) {
        self.open = None;
        self.step = None;
        self.calls = false;
        self.vacuous = true;
        self.pending.clear();
        self.deferred.clear();
    }

    fn unsupported(&mut self, line: u64, name: &str) {
        let name = cap(name, 64).0;
        if self.rows.len() > self.floor
            && let Some(Row::Unsupported { name: last, count, .. }) = self.rows.last_mut()
            && *last == name
        {
            *count += 1;
            return;
        }
        self.rows.push(Row::Unsupported { line, name, count: 1 });
    }

    /// What is shown: the rows from the last clear or compaction on, an
    /// open step's content so far included, deferred messages not (as
    /// result() at contextTranscript.ts:137-143).
    fn finish(self) -> Vec<Entry> {
        let rows = &self.rows[self.floor.min(self.rows.len())..];
        let names: HashMap<&str, &str> = rows
            .iter()
            .filter_map(|row| match row {
                Row::Message(message) => Some(message.calls.iter()),
                _ => None,
            })
            .flatten()
            .map(|call| (call.id.as_str(), call.name.as_str()))
            .collect();
        let mut entries = Vec::with_capacity(rows.len() + 1);
        if self.cleared {
            entries.push(entry(None, Kind::Boundary, None, Vec::new(), None, None));
        }
        let mut seen: HashMap<u64, u32> = HashMap::new();
        let mut id = |line: u64| {
            let n = seen.entry(line).or_default();
            *n += 1;
            Some(if *n == 1 { line.to_string() } else { format!("{line}.{n}") })
        };
        for row in rows {
            match row {
                Row::Message(message) => {
                    let blocks = blocks(message, &names);
                    if blocks.is_empty() {
                        continue;
                    }
                    let role = match message.role {
                        Role::System => "system",
                        Role::User => "user",
                        Role::Assistant => "assistant",
                        Role::Tool => "tool",
                    };
                    entries.push(entry(id(message.line), Kind::Message, Some(role), blocks, message.time, message.usage));
                }
                Row::Summary { line, time, text } => {
                    let text = if text.trim().is_empty() { "Conversation compacted" } else { text };
                    entries.push(entry(id(*line), Kind::Compaction, Some("user"), vec![text_block(text, false)], *time, None));
                }
                Row::Unsupported { line, name, count } => {
                    let text = if *count > 1 {
                        format!("Unsupported Kimi record: {name} (×{count})")
                    } else {
                        format!("Unsupported Kimi record: {name}")
                    };
                    entries.push(entry(id(*line), Kind::Message, Some("system"), vec![text_block(&text, false)], None, None));
                }
            }
        }
        for (index, entry) in entries.iter_mut().enumerate() {
            entry.offset = index as u64;
            entry.end = index as u64 + 1;
        }
        entries
    }
}

/// turnUndo.ts:49-55 isUndoAnchorTurnPrompt with isUndoAnchorOrigin
/// (41-47). A `null` origin, where Kimi would throw, is no anchor.
fn anchor(trigger: Option<&Value>, empty: bool, prompt: Option<&str>, origin: Option<&Value>) -> bool {
    if trigger.is_some_and(|trigger| trigger == "continue") {
        return false;
    }
    if trigger.is_none() && prompt.is_none() && empty {
        return false;
    }
    let Some(origin) = origin else {
        return true;
    };
    origin["kind"] == "user"
        || (matches!(origin["kind"].as_str(), Some("skill_activation" | "plugin_command"))
            && origin["trigger"] == "user-slash")
}

/// turnUndo.ts:37-39 isValidUndoCount: a safe integer above zero.
fn undo_count(count: &Value) -> Option<usize> {
    let count = count.as_f64()?;
    (count.fract() == 0.0 && count > 0.0 && count <= 9_007_199_254_740_991.0)
        .then(|| usize::try_from(count as u64).unwrap_or(usize::MAX))
}

/// A tool call id as the fold's Map keys it.
fn key(value: &Value) -> String {
    value.as_str().map_or_else(|| value.to_string(), str::to_owned)
}

/// vacuousContent.ts:162-173 isVacuousContentPart; an unknown part is not.
fn vacuous(part: &Value) -> bool {
    match part["type"].as_str() {
        Some("text") => {
            part["meta"].get("thought_signature_b64").is_none()
                && part["text"].as_str().unwrap_or_default().trim().is_empty()
        }
        Some("think") => {
            part.get("encrypted").is_none()
                && part["hidden"] != true
                && part["think"].as_str().unwrap_or_default().trim().is_empty()
        }
        _ => false,
    }
}

/// loopEventFold.ts:421-435 stepEndSealMeta's usage (TokenUsage): cached
/// input counts as input.
fn usage(usage: &Value) -> Option<Usage> {
    let output = usage["output"].as_u64()?;
    let input = usage["inputOther"].as_u64().unwrap_or(0)
        + usage["inputCacheRead"].as_u64().unwrap_or(0)
        + usage["inputCacheCreation"].as_u64().unwrap_or(0);
    Some(Usage { input, output, total_tokens: input + output, cost: None })
}

/// contextTranscript.ts:215-222 readCompactionSummaryText.
fn summary(record: &Value) -> String {
    if let Some(text) = record["summary"].as_str().or_else(|| record["contextSummary"].as_str()) {
        return text.to_owned();
    }
    let summary = &record["summary"];
    if !summary["role"].is_string() {
        return String::new();
    }
    summary["content"]
        .as_array()
        .into_iter()
        .flatten()
        .filter(|part| part["type"] == "text")
        .filter_map(|part| part["text"].as_str())
        .collect()
}

fn tool(line: u64, time: Option<u64>, id: String, content: Vec<Value>, is_error: bool) -> Message {
    Message {
        line,
        time,
        role: Role::Tool,
        content,
        calls: Vec::new(),
        call_id: Some(id),
        is_error,
        usage: None,
    }
}

/// A `context.append_message` message (record.ts PersistentContextMessage):
/// `None` when it has no known role or no content list, where Kimi's fold
/// would throw.
fn message(message: &Value, line: u64, time: Option<u64>) -> Option<Message> {
    let role = match message["role"].as_str()? {
        "system" => Role::System,
        "user" => Role::User,
        "assistant" => Role::Assistant,
        "tool" => Role::Tool,
        _ => return None,
    };
    let content = message["content"].as_array()?.clone();
    let calls = if role == Role::Assistant {
        message["toolCalls"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|call| Call {
                id: key(&call["id"]),
                name: super::string(&call["name"]),
                args: call["arguments"].as_str().map(str::to_owned),
            })
            .collect()
    } else {
        Vec::new()
    };
    Some(Message {
        line,
        time,
        role,
        content,
        calls,
        call_id: (role == Role::Tool).then(|| key(&message["toolCallId"])),
        is_error: message["isError"] == true,
        usage: if role == Role::Assistant { usage(&message["usage"]) } else { None },
    })
}

/// Chat blocks for one message, as kap-server's buildProtocolContent
/// (messageProjection.ts:107-158) shows it: a hidden thought is left out, a
/// tool message is one result of its text parts, an assistant's calls
/// follow its parts. Media (`image_url`, also a `kimi-file://` reference,
/// `audio_url`, `video_url`) is never fetched.
fn blocks(message: &Message, names: &HashMap<&str, &str>) -> Vec<Block> {
    let image = |part: usize| Image { part, source: Source::Unreadable };
    if message.role == Role::Tool {
        let mut text = String::new();
        let mut images = Vec::new();
        for (part, value) in message.content.iter().enumerate() {
            match value["type"].as_str() {
                Some("text") => text.push_str(value["text"].as_str().unwrap_or_default()),
                Some("image_url") => images.push(image(part)),
                _ => {}
            }
        }
        let call_id = message.call_id.clone().unwrap_or_default();
        let name = names.get(call_id.as_str()).copied().unwrap_or_default();
        let (text, truncated) = cap(&text, RESULT_LIMIT);
        return vec![Block::ToolResult {
            name: cap(name, 128).0,
            call_id,
            text,
            is_error: message.is_error,
            images,
            truncated,
            exit_code: None,
            answers: Vec::new(),
            timed_out: false,
            subagents: Vec::new(),
        }];
    }
    let mut blocks = Vec::new();
    for (part, value) in message.content.iter().enumerate() {
        match value["type"].as_str() {
            Some("text") => {
                let text = value["text"].as_str().unwrap_or_default();
                if !text.is_empty() {
                    blocks.push(text_block(text, false));
                }
            }
            Some("think") => {
                let text = value["think"].as_str().unwrap_or_default();
                if value["hidden"] != true && !text.is_empty() {
                    blocks.push(text_block(text, true));
                }
            }
            Some("image_url") => blocks.push(Block::Image(image(part))),
            Some("audio_url") => blocks.push(text_block("[audio]", false)),
            Some("video_url") => blocks.push(text_block("[video]", false)),
            other => {
                let name = cap(other.unwrap_or("unknown"), 64).0;
                blocks.push(text_block(&format!("[unsupported part: {name}]"), false));
            }
        }
    }
    for call in &message.calls {
        let (args, truncated) = cap(call.args.as_deref().unwrap_or_default(), ARGS_LIMIT);
        blocks.push(Block::ToolCall {
            call_id: call.id.clone(),
            name: cap(&call.name, 128).0,
            args,
            intent: None,
            truncated,
        });
    }
    blocks
}

fn entry(
    id: Option<String>,
    kind: Kind,
    role: Option<&str>,
    blocks: Vec<Block>,
    timestamp: Option<u64>,
    usage: Option<Usage>,
) -> Entry {
    Entry {
        id,
        // The chain is already the branch: no parent for the app to walk.
        parent_id: None,
        offset: 0,
        end: 0,
        kind,
        role: role.map(str::to_owned),
        blocks,
        timestamp,
        usage,
        stop_reason: None,
        steer: None,
        subagent_ends: Vec::new(),
    }
}

/// The host side. Reads of `/proc/<pid>/environ` keep only HOME and
/// KIMI_CODE_HOME; nothing else from the agent's environment is returned,
/// logged or stored. Only the session's `state.json` and its main agent's
/// `wire.jsonl` are opened, each without following a link.
const PYTHON: &str = r#"import hashlib, json, os, re, stat, subprocess, sys
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
environ_path = sys.argv[4] if len(sys.argv) > 4 else None
cwd_link = sys.argv[5] if len(sys.argv) > 5 else None
MAX_FILE=33554432
SID=re.compile(r'session_[A-Za-z0-9._-]{1,500}')

class Refused(Exception): pass

# Herdr's name is the process's comm (platform/linux.rs:385-387). Kimi Code
# sets process.title to kimi-code first thing (cli/main.ts:181,
# constant/app.ts:7), so comm reads kimi-code once main() runs; Herdr knows
# it as kimi or kimi-code (detect/mod.rs:213), or as node running the npm
# package's entry (:723-731). Anything else is refused.
NAMES=('kimi','kimi-code')
ENTRY=(b'node_modules',b'@moonshot-ai',b'kimi-code',b'dist',b'main.mjs')

# kimi.rs foreground_pid: the first argument after argv[0] that is no option,
# its last five path parts, ASCII case folded as Herdr compares them.
def node_kimi(argv):
    if not isinstance(argv,list): return False
    script=next((a for a in argv[1:] if isinstance(a,str) and not a.startswith('-')),None)
    if script is None: return False
    parts=[part.encode('utf-8','surrogatepass').lower() for part in re.split(r'[/\\]',script) if part]
    return tuple(parts[-len(ENTRY):])==ENTRY

def identity():
    result=subprocess.run(['/bin/sh','-c',report],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20)
    raw=result.stdout
    if result.returncode or len(raw)>65536 or not raw.startswith(b'@@pane\n'): raise ValueError('report')
    first,proc=raw[len(b'@@pane\n'):].split(b'\n@@proc\n',1)
    pane=json.loads(first)['result']['pane']
    try: process=json.loads(proc).get('result',{}).get('process_info',{})
    except ValueError: process={}
    ref=pane['agent_session']
    fg=process.get('foreground_processes',[])
    pid=next((p.get('pid') for p in fg if p.get('name') in NAMES),None) or next((p.get('pid') for p in fg if p.get('name')=='node' and node_kimi(p.get('argv'))),None)
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:kimi'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown')

def inside(root,path): return path!=root and os.path.commonpath([root,path])==root

def environment(pid):
    env={}
    with open(environ_path or '/proc/%d/environ'%pid,'rb') as handle:
        for field in handle.read(262144).split(b'\0'):
            key,sep,value=field.partition(b'=')
            if sep and key in (b'HOME',b'KIMI_CODE_HOME') and key not in env: env[key]=value.decode('utf-8','surrogateescape')
    return env

# paths.ts:38-43: KIMI_CODE_HOME verbatim when non-empty, no ~ expansion,
# else the agent's home's .kimi-code. A relative value would resolve against
# Kimi's cwd, which this does not guess: refused, never the default store.
def store(env):
    home=os.path.realpath(os.environ['HOME'])
    root=env.get(b'KIMI_CODE_HOME','')
    if root:
        if not root.startswith('/'): raise Refused('kimi_store_outside_home')
    else:
        own=env.get(b'HOME','')
        if not own.startswith('/'): raise Refused('kimi_store_outside_home')
        root=os.path.join(own,'.kimi-code')
    root=os.path.realpath(root)
    if not inside(home,root): raise Refused('kimi_store_outside_home')
    return root

# session-store.ts:340-367 findSessionDir: index entries for the id that
# resolve under sessions/ with the id as their name, and the bucket scan
# sessions/*/<id>. Kimi takes the first hit; here every hit is resolved and
# more than one distinct directory is refused. A tombstone has no
# sessionDir. Realpath, where Kimi resolves lexically.
def find(root,sid):
    sessions=os.path.realpath(os.path.join(root,'sessions'))
    if not inside(root,sessions): raise Refused('kimi_store_outside_home')
    found=set()
    try:
        with open(os.path.join(root,'session_index.jsonl'),'rb') as handle: index=handle.read(MAX_FILE)
    except FileNotFoundError: index=b''
    except OSError: raise Refused('kimi_store_unreadable')
    for line in index.splitlines():
        try: entry=json.loads(line)
        except ValueError: continue
        if not isinstance(entry,dict) or entry.get('sessionId')!=sid: continue
        path=entry.get('sessionDir')
        if not isinstance(path,str) or not path.startswith('/'): continue
        real=os.path.realpath(path)
        if inside(sessions,real) and os.path.basename(real)==sid and os.path.isdir(real): found.add(real)
    try: buckets=list(os.scandir(sessions))
    except FileNotFoundError: buckets=[]
    except OSError: raise Refused('kimi_store_unreadable')
    for bucket in buckets:
        if not bucket.is_dir(follow_symlinks=False): continue
        path=os.path.join(sessions,bucket.name,sid)
        if not os.path.isdir(path): continue
        real=os.path.realpath(path)
        if not inside(sessions,real): raise Refused('kimi_store_outside_home')
        found.add(real)
    if not found: raise LookupError()
    if len(found)>1: raise Refused('kimi_session_ambiguous')
    return found.pop()

def regular(path,limit):
    try: st=os.lstat(path)
    except FileNotFoundError: raise LookupError()
    except OSError: raise Refused('kimi_store_unreadable')
    if not stat.S_ISREG(st.st_mode): raise Refused('kimi_store_unreadable')
    try: fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except OSError: raise Refused('kimi_store_unreadable')
    opened=os.fstat(fd)
    if (opened.st_dev,opened.st_ino)!=(st.st_dev,st.st_ino) or not stat.S_ISREG(opened.st_mode) or os.path.realpath('/proc/self/fd/%d'%fd)!=path:
        os.close(fd); raise Refused('kimi_store_unreadable')
    if opened.st_size>limit:
        os.close(fd); raise Refused('kimi_session_too_large' if limit==MAX_FILE else 'kimi_store_unreadable')
    return fd,opened

def read_all(fd,size):
    chunks=[]; left=size
    while left>0:
        chunk=os.read(fd,min(left,1048576))
        if not chunk: break
        chunks.append(chunk); left-=len(chunk)
    return b''.join(chunks)

# session-store.ts:324-338 readState (state.json, then the legacy
# session-meta/state.json) and sessionMetadataService.ts:317-320: cwd ??
# workDir, so only a missing or null cwd falls back.
def bound_cwd(sdir):
    for path in (os.path.join(sdir,'state.json'),os.path.join(sdir,'session-meta','state.json')):
        try: fd,st=regular(path,1048576)
        except LookupError: continue
        try: raw=read_all(fd,st.st_size)
        finally: os.close(fd)
        try: state=json.loads(raw)
        except ValueError: continue
        if not isinstance(state,dict): continue
        value=state['cwd'] if state.get('cwd') is not None else state.get('workDir')
        if isinstance(value,str) and value: return value
        break
    raise Refused('kimi_store_unreadable')

def main():
    got,status=identity()
    if got!=expect or got['agent']!='kimi' or got['kind']!='id': return {'changed':True},b''
    sid=got['path']
    if not isinstance(sid,str) or not SID.fullmatch(sid): return {'changed':True},b''
    if not got['pid']: raise Refused('kimi_process_unnamed')
    pid=int(got['pid'])
    try: env=environment(pid)
    except OSError: raise Refused('kimi_store_unreadable')
    root=store(env)
    try: sdir=find(root,sid)
    except LookupError: return {'error':'missing'},b''
    cwd=bound_cwd(sdir)
    # The session must be the one the running process works in: its state
    # cwd against the process's own cwd, never the pane's report.
    try: running=os.path.realpath(os.readlink(cwd_link or '/proc/%d/cwd'%pid))
    except OSError: return {'error':'foreign'},b''
    if not cwd.startswith('/') or os.path.realpath(cwd)!=running: return {'error':'foreign'},b''
    path=os.path.join(sdir,'agents','main','wire.jsonl')
    try: fd,st=regular(path,MAX_FILE)
    except LookupError: return {'error':'missing'},b''
    try:
        stamp=int(hashlib.sha256(('%d:%d:%d:%d:%s'%(st.st_dev,st.st_ino,st.st_size,st.st_mtime_ns,path)).encode()).hexdigest()[:13],16) or 1
        head={'stamp':stamp,'size':st.st_size,'status':status}
        if opts.get('stamp')==stamp:
            head['not_modified']=True; data=b''
        else: data=read_all(fd,st.st_size)
    finally: os.close(fd)
    after,_=identity()
    if after!=expect: return {'changed':True},b''
    return head,data

try:
    head,data=main()
    sys.stdout.buffer.write(json.dumps(head).encode()+b'\n'+data)
except Refused as refusal:
    sys.stdout.buffer.write(json.dumps({'format':str(refusal)}).encode()+b'\n')
except Exception:
    sys.exit(1)
"#;

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests;
