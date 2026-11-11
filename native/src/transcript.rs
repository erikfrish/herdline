//! A chat view of an OMP agent's transcript, for exactly the pane Herdr
//! reports. Herdr knows which session file the agent in a pane writes
//! (`agent_session`, kind `path`); the client endpoint does not carry it, so
//! each read runs Herdr's own CLI on the host over the bridge's SSH session,
//! asks it for that pane and its foreground process, and reads a byte range
//! of the file it names. Nothing is started, imported or resumed: the agent
//! keeps running in its terminal, and chat only reads what it wrote.
//!
//! Everything from the host is untrusted. The path must be an OMP session
//! file, which the host checks again after resolving links; only complete
//! lines are parsed, text is capped, and an identity that changed between
//! reads (another session, a fork, a replaced pane or process) starts over
//! from the end of the new file instead of mixing two transcripts.

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;

use crate::{Error, Result, herdr_setup::plain_text, ssh::Exec};
use herdr_client::{HERDR_CANDIDATES, shell_quote};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::time::Duration;

/// A read over a slow mobile link.
const TIMEOUT: Duration = Duration::from_secs(20);
/// The newest part of a transcript, enough for a first screen.
const FIRST_WINDOW: u64 = 256 * 1024;
/// A window doubles up to this to fit one long line; a longer one is refused.
const MAX_WINDOW: u64 = 8 * 1024 * 1024;
/// Room for Herdr's pane and process reports around the data.
const HEADER_ROOM: usize = 64 * 1024;
const TEXT_LIMIT: usize = 64 * 1024;
const RESULT_LIMIT: usize = 16 * 1024;
const ARGS_LIMIT: usize = 8 * 1024;
const PATH_LIMIT: usize = 4096;
const SESSIONS_DIR: &str = "/.omp/agent/sessions/";
/// Reads that may chain within one fetch: identity, a reset, window growth.
const MAX_READS: usize = 8;

#[derive(Debug, Clone, Deserialize)]
pub struct Request {
    pub pane: String,
    /// What the caller already shows; `None` when it shows nothing yet.
    #[serde(default)]
    pub expect: Option<Identity>,
    #[serde(default)]
    pub read: Read,
}

/// Which bytes to read. Offsets are line boundaries a previous [`Response`]
/// returned.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum Read {
    /// The newest complete lines.
    #[default]
    Tail,
    /// Lines appended since `offset`.
    After { offset: u64 },
    /// Older lines ending at `offset`.
    Before { offset: u64 },
}

/// Which agent the transcript belongs to; any change starts over.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Identity {
    pub pane_id: String,
    pub terminal_id: String,
    /// The OMP process in the pane's foreground, when Herdr reports one.
    pub pid: Option<u32>,
    pub path: String,
}

#[derive(Debug, Serialize)]
pub struct Response {
    pub identity: Identity,
    /// Herdr's agent status for the pane, such as `working` or `blocked`.
    pub status: String,
    /// Discard everything shown before: these entries replace it.
    pub reset: bool,
    pub size: u64,
    /// The byte range these entries came from.
    pub start: u64,
    pub end: u64,
    pub entries: Vec<Entry>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Entry {
    pub id: Option<String>,
    pub parent_id: Option<String>,
    /// Where the line starts in the file: a stable key for entries without an ID.
    pub offset: u64,
    pub kind: Kind,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub role: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub blocks: Vec<Block>,
    /// When OMP wrote the message, epoch milliseconds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<u64>,
    /// What an assistant message cost, as OMP counted it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<Usage>,
    /// Why an assistant message ended, such as `stop`, `toolUse` or `error`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop_reason: Option<String>,
}

/// An assistant message's token counts and OMP's own cost estimate in USD.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct Usage {
    pub input: u64,
    pub output: u64,
    pub total_tokens: u64,
    /// Absent when OMP has no price for the model.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cost: Option<f64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Message,
    Compaction,
    /// Kept without content so the branch can be walked through it.
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Block {
    Text {
        text: String,
        truncated: bool,
    },
    Thinking {
        text: String,
        truncated: bool,
    },
    ToolCall {
        call_id: String,
        name: String,
        args: String,
        intent: Option<String>,
        truncated: bool,
    },
    ToolResult {
        call_id: String,
        name: String,
        text: String,
        is_error: bool,
        images: usize,
        truncated: bool,
        /// The command's exit code, for tools that report one.
        #[serde(skip_serializing_if = "Option::is_none")]
        exit_code: Option<i64>,
    },
    Image,
}

/// Read the pane's transcript. Blocks on the host for at most a few reads.
pub fn fetch(exec: &Exec, session: &str, request: &Request) -> Result<Response> {
    let mut expect = request.expect.clone();
    let mut read = request.read;
    let mut window = FIRST_WINDOW;
    let mut reset = false;
    for _ in 0..MAX_READS {
        let target = expect.as_ref().map(|identity| Target {
            path: &identity.path,
            read,
            window,
        });
        let script = script(session, &request.pane, target);
        let limit = usize::try_from(window).unwrap_or(usize::MAX) + 1 + HEADER_ROOM;
        let output = exec.run(
            format!("/bin/sh -c {}", shell_quote(&script)),
            TIMEOUT,
            limit,
        )?;
        let reply = parse_reply(output.exit_status, &output.tail)?;
        let (identity, status) = identity(&reply.pane, &reply.process)?;
        if expect.as_ref() != Some(&identity) {
            expect = Some(identity);
            read = Read::Tail;
            window = FIRST_WINDOW;
            reset = true;
            continue;
        }
        let file = reply.file.ok_or(Error::BadReply)?;
        match plan(read, &file, window) {
            Plan::Lines { start, end, lines } => {
                return Ok(Response {
                    identity,
                    status,
                    reset: reset || read == Read::Tail,
                    size: file.size,
                    start,
                    end,
                    entries: entries(lines, start),
                });
            }
            Plan::Rewritten => {
                read = Read::Tail;
                window = FIRST_WINDOW;
                reset = true;
            }
            Plan::Grow if window < MAX_WINDOW => window *= 2,
            Plan::Grow => return Err(Error::EntryTooLarge),
        }
    }
    Err(Error::BadReply)
}

/// The OMP instance in the pane now, read on the host without touching its
/// session file; `None` when the pane names no OMP session. Blocks on the
/// host for one short command.
pub fn instance(exec: &Exec, session: &str, pane: &str) -> Result<Option<Identity>> {
    let output = exec.run(
        format!("/bin/sh -c {}", shell_quote(&script(session, pane, None))),
        TIMEOUT,
        HEADER_ROOM,
    )?;
    let reply = parse_reply(output.exit_status, &output.tail)?;
    match identity(&reply.pane, &reply.process) {
        Ok((identity, _)) => Ok(Some(identity)),
        Err(Error::NoTranscript) => Ok(None),
        Err(error) => Err(error),
    }
}

#[derive(Debug, Clone, Copy)]
struct Target<'a> {
    path: &'a str,
    read: Read,
    window: u64,
}

/// Exit statuses the script uses for refusals.
const EXIT_UNCONFINED: u32 = 3;
const EXIT_NO_HERDR: u32 = 4;
const EXIT_NO_PANE: u32 = 5;
const EXIT_NO_FILE: u32 = 6;

/// Report the pane and its processes, then, given a target, the requested
/// bytes of that file once it resolves inside OMP's session folder. Errors
/// are silenced so they never mix with the data.
fn script(session: &str, pane: &str, target: Option<Target>) -> String {
    let session = shell_quote(session);
    let pane = shell_quote(pane);
    let mut script = format!(
        r#"exec 2>/dev/null
herdr=
{HERDR_CANDIDATES}
    if [ -n "$path" ] && [ -x "$path" ]; then herdr=$path; break; fi
done
[ -n "$herdr" ] || exit {EXIT_NO_HERDR}
printf '@@pane\n'
"$herdr" --session {session} pane get {pane} </dev/null || exit {EXIT_NO_PANE}
printf '\n@@proc\n'
"$herdr" --session {session} pane process-info --pane {pane} </dev/null || printf '{{}}'
"#
    );
    let Some(target) = target else {
        return script;
    };
    let range = match target.read {
        Read::Tail => format!(
            "start=$(( size > {w} ? size - {w} : 0 ))\nlen={w}",
            w = target.window
        ),
        // One byte early: the newline before `offset` shows the file was
        // only appended to.
        Read::After { offset } if offset > 0 => {
            format!("start={}\nlen={}", offset - 1, target.window + 1)
        }
        Read::After { offset } => format!("start={offset}\nlen={}", target.window),
        Read::Before { offset } => {
            let start = offset.saturating_sub(target.window);
            format!("start={start}\nlen={}", offset - start)
        }
    };
    script.push_str(&format!(
        r#"real=$(realpath -- {path}) || exit {EXIT_NO_FILE}
home=$(realpath -- "$HOME") || exit {EXIT_UNCONFINED}
case "$real" in "$home"{SESSIONS_DIR}*.jsonl) ;; *) exit {EXIT_UNCONFINED};; esac
[ -f "$real" ] || exit {EXIT_NO_FILE}
size=$(wc -c < "$real") || exit {EXIT_NO_FILE}
size=$((size))
{range}
printf '\n@@file %s %s\n@@data\n' "$size" "$start"
tail -c +$((start + 1)) "$real" | head -c "$len"
"#,
        path = shell_quote(target.path),
    ));
    script
}

#[derive(Debug)]
struct Reply<'a> {
    pane: Value,
    process: Value,
    file: Option<File<'a>>,
}

#[derive(Debug, PartialEq, Eq)]
struct File<'a> {
    size: u64,
    start: u64,
    data: &'a [u8],
}

fn parse_reply(exit_status: Option<u32>, bytes: &[u8]) -> Result<Reply<'_>> {
    match exit_status {
        Some(0) => {}
        Some(EXIT_UNCONFINED) => return Err(Error::TranscriptUnconfined),
        Some(EXIT_NO_HERDR) => return Err(Error::HerdrNotFound),
        Some(EXIT_NO_PANE) => return Err(Error::PaneGone),
        Some(EXIT_NO_FILE) => return Err(Error::NoTranscript),
        _ => return Err(Error::BadReply),
    }
    let rest = bytes.strip_prefix(b"@@pane\n").ok_or(Error::BadReply)?;
    let (pane, rest) = split(rest, b"\n@@proc\n").ok_or(Error::BadReply)?;
    let (process, file) = match split(rest, b"\n@@file ") {
        Some((process, file)) => (process, Some(file)),
        None => (rest, None),
    };
    let file = file.map(parse_file).transpose()?;
    Ok(Reply {
        pane: serde_json::from_slice(pane)?,
        // A Herdr without process info leaves the PID unknown.
        process: serde_json::from_slice(process).unwrap_or(Value::Null),
        file,
    })
}

fn parse_file(bytes: &[u8]) -> Result<File<'_>> {
    let (header, data) = split(bytes, b"\n@@data\n").ok_or(Error::BadReply)?;
    let header = std::str::from_utf8(header).map_err(|_| Error::BadReply)?;
    let mut numbers = header.split(' ').map(str::parse::<u64>);
    match (numbers.next(), numbers.next(), numbers.next()) {
        (Some(Ok(size)), Some(Ok(start)), None) => Ok(File { size, start, data }),
        _ => Err(Error::BadReply),
    }
}

fn split<'a>(bytes: &'a [u8], marker: &[u8]) -> Option<(&'a [u8], &'a [u8])> {
    let at = bytes
        .windows(marker.len())
        .position(|window| window == marker)?;
    Some((&bytes[..at], &bytes[at + marker.len()..]))
}

/// The pane's transcript identity and agent status, from `pane get` and
/// `pane process-info`.
fn identity(pane: &Value, process: &Value) -> Result<(Identity, String)> {
    let pane = &pane["result"]["pane"];
    let session = &pane["agent_session"];
    if session["agent"] != "omp" || session["kind"] != "path" {
        return Err(Error::NoTranscript);
    }
    let path = session["value"].as_str().ok_or(Error::NoTranscript)?;
    if !confined(path) {
        return Err(Error::TranscriptUnconfined);
    }
    let text = |value: &Value| value.as_str().map(str::to_owned).ok_or(Error::BadReply);
    let identity = Identity {
        pane_id: text(&pane["pane_id"])?,
        terminal_id: text(&pane["terminal_id"])?,
        pid: omp_pid(process),
        path: path.to_owned(),
    };
    let status = pane["agent_status"]
        .as_str()
        .filter(|status| {
            status.len() <= 32
                && status
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte == b'_')
        })
        .unwrap_or("unknown")
        .to_owned();
    Ok((identity, status))
}

fn omp_pid(process: &Value) -> Option<u32> {
    process["result"]["process_info"]["foreground_processes"]
        .as_array()?
        .iter()
        .find(|process| process["name"] == "omp")?["pid"]
        .as_u64()
        .and_then(|pid| u32::try_from(pid).ok())
}

/// An absolute OMP session file path, as the host must also resolve it.
fn confined(path: &str) -> bool {
    path.len() <= PATH_LIMIT
        && path.starts_with('/')
        && path.ends_with(".jsonl")
        && path.contains(SESSIONS_DIR)
        && !path.chars().any(char::is_control)
        && path
            .split('/')
            .all(|segment| segment != ".." && segment != ".")
}

#[derive(Debug, PartialEq, Eq)]
enum Plan<'a> {
    Lines {
        start: u64,
        end: u64,
        lines: &'a [u8],
    },
    /// The file is shorter, or the bytes at a known boundary moved.
    Rewritten,
    /// No complete line fits the window.
    Grow,
}

/// The complete lines in what was read; a partial line at either edge is
/// left for a later read.
fn plan<'a>(read: Read, file: &File<'a>, window: u64) -> Plan<'a> {
    let data = file.data;
    match read {
        Read::Tail => match complete(data, file.start, file.start > 0) {
            Some((start, end, lines)) => Plan::Lines { start, end, lines },
            None if file.start > 0 => Plan::Grow,
            None => Plan::Lines {
                start: 0,
                end: 0,
                lines: &[],
            },
        },
        Read::After { offset } => {
            if file.size < offset {
                return Plan::Rewritten;
            }
            let data = if offset > 0 {
                match data.split_first() {
                    Some((b'\n', rest)) => rest,
                    _ => return Plan::Rewritten,
                }
            } else {
                data
            };
            match complete(data, offset, false) {
                Some((start, end, lines)) => Plan::Lines { start, end, lines },
                // A line longer than the window, or one still being written.
                None if data.len() as u64 >= window => Plan::Grow,
                None => Plan::Lines {
                    start: offset,
                    end: offset,
                    lines: &[],
                },
            }
        }
        Read::Before { offset } => {
            if file.size < offset {
                return Plan::Rewritten;
            }
            if offset == 0 {
                return Plan::Lines {
                    start: 0,
                    end: 0,
                    lines: &[],
                };
            }
            if data.last() != Some(&b'\n') {
                return Plan::Rewritten;
            }
            match complete(data, file.start, file.start > 0) {
                Some((start, end, lines)) => Plan::Lines { start, end, lines },
                None => Plan::Grow,
            }
        }
    }
}

/// `data` read from `start`, without a leading partial line when
/// `skip_partial` and up to its last newline; `None` without a whole line.
fn complete(data: &[u8], start: u64, skip_partial: bool) -> Option<(u64, u64, &[u8])> {
    let skip = if skip_partial {
        data.iter().position(|&byte| byte == b'\n')? + 1
    } else {
        0
    };
    let rest = &data[skip..];
    let last = rest.iter().rposition(|&byte| byte == b'\n')?;
    let first = start + skip as u64;
    Some((first, first + last as u64 + 1, &rest[..=last]))
}

fn entries(lines: &[u8], start: u64) -> Vec<Entry> {
    let mut offset = start;
    let mut entries = Vec::new();
    for line in lines.split_inclusive(|&byte| byte == b'\n') {
        let at = offset;
        offset += line.len() as u64;
        match serde_json::from_slice::<Value>(line) {
            Ok(value) => entries.push(entry(&value, at)),
            Err(error) => tracing::debug!(%error, offset = at, "transcript line skipped"),
        }
    }
    entries
}

fn entry(value: &Value, offset: u64) -> Entry {
    let id = value["id"].as_str().map(str::to_owned);
    let parent_id = value["parentId"].as_str().map(str::to_owned);
    let (kind, role, blocks) = match value["type"].as_str() {
        Some("message") => message(&value["message"]),
        Some("compaction") => (
            Kind::Compaction,
            None,
            value["summary"]
                .as_str()
                .map(|summary| vec![text_block(summary, false)])
                .unwrap_or_default(),
        ),
        Some("custom_message") if value["display"] == true => (
            Kind::Message,
            Some("custom".to_owned()),
            content(&value["content"]),
        ),
        _ => (Kind::Other, None, Vec::new()),
    };
    // Only a shown message carries its time, usage and stop reason.
    let none = Value::Null;
    let message = if kind == Kind::Message && value["type"] == "message" {
        &value["message"]
    } else {
        &none
    };
    Entry {
        id,
        parent_id,
        offset,
        kind,
        role,
        blocks,
        timestamp: message["timestamp"].as_u64(),
        usage: usage(&message["usage"]),
        stop_reason: message["stopReason"]
            .as_str()
            .map(|reason| cap(reason, 32).0),
    }
}

/// Usage with every count OMP writes; a cost that is not a finite,
/// non-negative number is absent rather than guessed.
fn usage(usage: &Value) -> Option<Usage> {
    Some(Usage {
        input: usage["input"].as_u64()?,
        output: usage["output"].as_u64()?,
        total_tokens: usage["totalTokens"].as_u64()?,
        cost: usage["cost"]["total"]
            .as_f64()
            .filter(|cost| cost.is_finite() && *cost >= 0.0),
    })
}

fn message(message: &Value) -> (Kind, Option<String>, Vec<Block>) {
    let role = message["role"].as_str().unwrap_or_default();
    if role == "custom" && message["display"] == false {
        return (Kind::Other, None, Vec::new());
    }
    let blocks = if role == "toolResult" {
        vec![tool_result(message)]
    } else {
        content(&message["content"])
    };
    (Kind::Message, Some(cap(role, 32).0), blocks)
}

fn tool_result(message: &Value) -> Block {
    let parts = message["content"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let text = parts
        .iter()
        .filter_map(|part| part["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n");
    let (text, truncated) = cap(&plain_text(text.as_bytes()), RESULT_LIMIT);
    Block::ToolResult {
        call_id: string(&message["toolCallId"]),
        name: string(&message["toolName"]),
        text,
        is_error: message["isError"] == true,
        images: parts.iter().filter(|part| part["type"] == "image").count(),
        truncated,
        exit_code: message["details"]["exitCode"].as_i64(),
    }
}

/// Message content: a string, or an array of typed parts.
fn content(content: &Value) -> Vec<Block> {
    if let Some(text) = content.as_str() {
        return vec![text_block(text, false)];
    }
    let Some(parts) = content.as_array() else {
        return Vec::new();
    };
    parts
        .iter()
        .filter_map(|part| match part["type"].as_str()? {
            "text" => Some(text_block(part["text"].as_str()?, false)),
            "thinking" => Some(text_block(part["thinking"].as_str()?, true)),
            "toolCall" => Some(tool_call(part)),
            "image" => Some(Block::Image),
            _ => None,
        })
        .collect()
}

fn text_block(text: &str, thinking: bool) -> Block {
    let (text, truncated) = cap(text, TEXT_LIMIT);
    if thinking {
        Block::Thinking { text, truncated }
    } else {
        Block::Text { text, truncated }
    }
}

fn tool_call(part: &Value) -> Block {
    let mut arguments = part["arguments"].clone();
    // OMP repeats the intent as `i` among the arguments.
    if let Some(arguments) = arguments.as_object_mut() {
        arguments.remove("i");
    }
    let args = match &arguments {
        Value::Null => String::new(),
        arguments => serde_json::to_string_pretty(arguments).unwrap_or_default(),
    };
    let (args, truncated) = cap(&args, ARGS_LIMIT);
    Block::ToolCall {
        call_id: string(&part["id"]),
        name: cap(&string(&part["name"]), 128).0,
        args,
        intent: part["intent"].as_str().map(|intent| cap(intent, 512).0),
        truncated,
    }
}

fn string(value: &Value) -> String {
    value.as_str().unwrap_or_default().to_owned()
}

/// At most `limit` bytes of `text`, cut at a character boundary.
fn cap(text: &str, limit: usize) -> (String, bool) {
    if text.len() <= limit {
        return (text.to_owned(), false);
    }
    let mut end = limit;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    (text[..end].to_owned(), true)
}
