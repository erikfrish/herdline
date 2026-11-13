//! FIXTURE-ONLY: every journal here is written by these tests in the shape
//! kimi-code 482936af persists (`docs/wire-manifest.d.ts`, the vis-server
//! sample fixture), not captured from a real Kimi Code CLI run. Nothing here
//! claims how a live Kimi writes; it pins what the reader does with such a
//! journal.

use super::*;
use std::{fs, os::unix::fs::PermissionsExt, path::PathBuf, process::Command};

const SID: &str = "session_0b8f9c1e-2d3a-4b5c-8d6e-7f8091a2b3c4";

/// The vis-server sample fixture (apps/vis-server/test/fixtures/sessions/
/// sample-main/agents/main/wire.jsonl), verbatim.
const SAMPLE: &str = r#"{"type":"metadata","protocol_version":"1.1","created_at":1779256791085}
{"type":"config.update","cwd":"/tmp/work","profileName":"agent","systemPrompt":"You are Kimi.","time":1779256791100}
{"type":"tools.set_active_tools","names":["Read","Write"],"time":1779256791101}
{"type":"permission.set_mode","mode":"manual","time":1779256791102}
{"type":"turn.prompt","input":[{"type":"text","text":"hi"}],"origin":{"kind":"user"},"time":1779256800000}
{"type":"context.append_message","message":{"role":"user","content":[{"type":"text","text":"hi"}],"toolCalls":[]},"time":1779256800001}
{"type":"context.append_loop_event","event":{"type":"step.begin","uuid":"s1","turnId":"t1","step":0},"time":1779256800100}
{"type":"context.append_loop_event","event":{"type":"content.part","uuid":"c1","turnId":"t1","step":0,"stepUuid":"s1","part":{"type":"text","text":"hello"}},"time":1779256800200}
{"type":"context.append_loop_event","event":{"type":"step.end","uuid":"s1","turnId":"t1","step":0,"usage":{"inputOther":10,"output":5,"inputCacheRead":0,"inputCacheCreation":0},"finishReason":"end_turn"},"time":1779256800300}
{"type":"usage.record","model":"kimi-k2","usage":{"inputOther":10,"output":5,"inputCacheRead":0,"inputCacheCreation":0},"usageScope":"turn","time":1779256800302}
"#;

fn meta(version: &str) -> Value {
    json!({"type": "metadata", "protocol_version": version, "created_at": 1})
}

fn rec(kind: &str, mut body: Value) -> Value {
    body["type"] = json!(kind);
    body["time"] = json!(1000);
    body
}

fn event(body: Value) -> Value {
    rec("context.append_loop_event", json!({ "event": body }))
}

/// A user turn as Kimi writes it: the prompt record, then its message.
fn turn(prompt: &str, words: &str) -> Vec<Value> {
    vec![
        rec(
            "turn.prompt",
            json!({"promptId": prompt, "input": [{"type": "text", "text": words}], "origin": {"kind": "user"}}),
        ),
        rec(
            "context.append_message",
            json!({"message": {"id": prompt, "role": "user", "content": [{"type": "text", "text": words}], "toolCalls": []}}),
        ),
    ]
}

/// One assistant step that says `words`.
fn step(uuid: &str, words: &str) -> Vec<Value> {
    vec![
        event(json!({"type": "step.begin", "uuid": uuid})),
        event(
            json!({"type": "content.part", "stepUuid": uuid, "part": {"type": "text", "text": words}}),
        ),
        event(json!({"type": "step.end", "uuid": uuid})),
    ]
}

fn journal(records: &[Value]) -> String {
    records.iter().map(|record| format!("{record}\n")).collect()
}

fn project_all(records: &[Value]) -> Vec<Entry> {
    project(journal(records).as_bytes()).unwrap()
}

fn texts(entries: &[Entry]) -> Vec<String> {
    entries
        .iter()
        .flat_map(|entry| &entry.blocks)
        .filter_map(|block| match block {
            Block::Text { text, .. } | Block::Thinking { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

/// Two answered turns on `main`: wire lines 2-6 and 7-11.
fn two_turns() -> Vec<Value> {
    let mut records = vec![meta("1.5")];
    records.extend(turn("p1", "one"));
    records.extend(step("a1", "first"));
    records.extend(turn("p2", "two"));
    records.extend(step("a2", "second"));
    records
}

#[test]
fn sample_fixture_shows_the_user_turn_and_the_answer_with_its_usage() {
    let entries = project(SAMPLE.as_bytes()).unwrap();
    assert_eq!(texts(&entries), ["hi", "hello"]);
    assert_eq!(entries[0].role.as_deref(), Some("user"));
    assert_eq!(entries[0].id.as_deref(), Some("6"));
    assert_eq!(entries[0].timestamp, Some(1_779_256_800_001));
    assert_eq!(entries[1].role.as_deref(), Some("assistant"));
    assert_eq!(
        entries[1].usage,
        Some(Usage {
            input: 10,
            output: 5,
            total_tokens: 15,
            cost: None
        })
    );
    assert_eq!((entries[1].offset, entries[1].end), (1, 2));

    let current = SAMPLE.replacen(
        r#""protocol_version":"1.1""#,
        r#""protocol_version":"1.5""#,
        1,
    );
    assert_eq!(
        texts(&project(current.as_bytes()).unwrap()),
        ["hi", "hello"]
    );
}

#[test]
fn tool_results_link_to_their_call_and_an_unanswered_call_is_interrupted() {
    let mut records = vec![meta("1.5")];
    records.extend(turn("p1", "list"));
    records.extend([
        event(json!({"type": "step.begin", "uuid": "s1"})),
        event(json!({"type": "tool.call", "stepUuid": "s1", "toolCallId": "t1", "name": "Bash", "args": {"cmd": "ls"}})),
        event(json!({"type": "tool.result", "toolCallId": "t1", "result": {"output": "a.txt", "isError": false}})),
        event(json!({"type": "step.end", "uuid": "s1"})),
        event(json!({"type": "step.begin", "uuid": "s2"})),
        event(json!({"type": "tool.call", "stepUuid": "s2", "toolCallId": "t2", "name": "Read", "args": {}})),
        event(json!({"type": "step.end", "uuid": "s2"})),
    ]);
    let entries = project_all(&records);
    let blocks: Vec<&Block> = entries.iter().flat_map(|entry| &entry.blocks).collect();
    assert!(
        matches!(blocks[1], Block::ToolCall { call_id, name, args, .. } if call_id == "t1" && name == "Bash" && args == r#"{"cmd":"ls"}"#)
    );
    assert!(
        matches!(blocks[2], Block::ToolResult { call_id, name, text, is_error: false, .. } if call_id == "t1" && name == "Bash" && text == "a.txt")
    );
    assert!(matches!(blocks[3], Block::ToolCall { call_id, .. } if call_id == "t2"));
    assert!(
        matches!(blocks[4], Block::ToolResult { call_id, name, text, is_error: true, .. } if call_id == "t2" && name == "Read" && text == INTERRUPTED)
    );
    assert_eq!(blocks.len(), 5);
}

#[test]
fn an_unpaired_undo_hides_the_abandoned_turn() {
    let mut records = two_turns();
    records.push(rec("context.undo", json!({"count": 1})));
    assert_eq!(texts(&project_all(&records)), ["one", "first"]);
}

#[test]
fn a_branch_switch_shows_its_base_up_to_the_fork_then_the_branch() {
    let mut records = two_turns();
    records.push(rec(
        SWITCHED,
        json!({"branch": "b1", "base": {"branch": "main", "line": 6}}),
    ));
    records.extend(turn("p3", "three"));
    records.extend(step("a3", "third"));
    let entries = project_all(&records);
    assert_eq!(texts(&entries), ["one", "first", "three", "third"]);
    assert_eq!(entries[2].id.as_deref(), Some("14"));
}

#[test]
fn a_paired_legacy_undo_is_applied_once_by_its_switch() {
    let mut records = two_turns();
    records.push(rec(
        SWITCHED,
        json!({"branch": "u1", "base": {"branch": "main", "line": 6}, "legacyUndoLine": 13}),
    ));
    records.push(rec("context.undo", json!({"count": 1})));
    records.push(rec("context.undone", json!({})));
    assert_eq!(texts(&project_all(&records)), ["one", "first"]);
}

#[test]
fn clear_shows_a_boundary_and_only_what_follows() {
    let mut records = two_turns();
    records.push(rec("context.clear", json!({})));
    records.extend(turn("p3", "three"));
    let entries = project_all(&records);
    assert_eq!(entries[0].kind, Kind::Boundary);
    assert_eq!(texts(&entries), ["three"]);
}

#[test]
fn compaction_shows_its_summary_and_hides_what_precedes() {
    let mut records = two_turns();
    records.push(rec(
        "context.apply_compaction",
        json!({"summary": "they counted", "keptUserMessageCount": 0}),
    ));
    records.extend(turn("p3", "three"));
    let entries = project_all(&records);
    assert_eq!(entries[0].kind, Kind::Compaction);
    assert_eq!(texts(&entries), ["they counted", "three"]);

    let mut empty = two_turns();
    empty.push(rec("context.apply_compaction", json!({"summary": ""})));
    assert_eq!(texts(&project_all(&empty)), ["Conversation compacted"]);
}

#[test]
fn protocol_one_zero_tool_calls_are_flattened() {
    let records = [
        meta("1.0"),
        rec(
            "context.append_message",
            json!({"message": {"role": "assistant", "content": [], "toolCalls": [
                {"type": "function", "id": "c1", "function": {"name": "Read", "arguments": "{\"path\":\"a\"}"}}
            ]}}),
        ),
        rec(
            "context.append_message",
            json!({"message": {"role": "tool", "toolCallId": "c1", "content": [{"type": "text", "text": "ok"}]}}),
        ),
    ];
    let entries = project_all(&records);
    assert!(
        matches!(&entries[0].blocks[0], Block::ToolCall { name, args, .. } if name == "Read" && args == r#"{"path":"a"}"#)
    );
    assert!(
        matches!(&entries[1].blocks[0], Block::ToolResult { name, text, .. } if name == "Read" && text == "ok")
    );
}

#[test]
fn versions_without_a_migration_path_or_newer_than_allowed_are_refused() {
    for version in ["0.9", "1.4.0"] {
        assert_eq!(
            project(journal(&[meta(version)]).as_bytes()).err(),
            Some(VERSION),
            "{version}"
        );
    }
    let mut ahead = meta("1.6");
    ahead["min_protocol_version"] = json!("1.6");
    assert_eq!(project(journal(&[ahead]).as_bytes()).err(), Some(VERSION));
    let malformed = json!({"type": "metadata", "created_at": 1});
    assert_eq!(
        project(journal(&[malformed]).as_bytes()).err(),
        Some(UNREADABLE)
    );
}

#[test]
fn a_newer_protocol_it_may_read_counts_unknown_records_in_one_row() {
    let mut header = meta("1.7");
    header["min_protocol_version"] = json!("1.5");
    let mut records = vec![header];
    records.extend(turn("p1", "one"));
    records.push(rec("future.thing", json!({})));
    records.push(rec("future.thing", json!({})));
    records.push(event(json!({"type": "step.future"})));
    let entries = project_all(&records);
    assert_eq!(
        texts(&entries),
        [
            "one",
            "Unsupported Kimi record: future.thing (×2)",
            "Unsupported Kimi record: context.append_loop_event:step.future",
        ]
    );
    assert_eq!(entries[1].role.as_deref(), Some("system"));
}

struct Host {
    home: tempfile::TempDir,
    /// Herdr's process-info report for the pane.
    process: Value,
    /// The exec shell's own variables beyond HOME and PATH.
    env: std::cell::RefCell<Vec<(String, String)>>,
}

impl Host {
    fn new(sid: &str) -> Self {
        Self::with(sid, "kimi")
    }

    /// A pane whose Herdr report names Kimi on `sid` with a process named
    /// `name` in the foreground, working in `$HOME/work`.
    fn with(sid: &str, name: &str) -> Self {
        Self::running(sid, json!({"name": name, "pid": 42}))
    }

    /// As [`Host::with`], with `process` as the one foreground process.
    fn running(sid: &str, process: Value) -> Self {
        let home = tempfile::tempdir().unwrap();
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap();
        fs::create_dir_all(home.path().join("work")).unwrap();
        let pane = json!({"id": "cli:pane:get", "result": {"pane": {
            "agent": "kimi",
            "agent_session": {"agent": "kimi", "kind": "id", "source": "herdr:kimi", "value": sid},
            "agent_status": "idle",
            "pane_id": "w1:p1",
            "terminal_id": "term_1",
            "foreground_cwd": home.path().join("work"),
        }, "type": "pane_info"}});
        fs::write(home.path().join("pane.json"), pane.to_string()).unwrap();
        let process = json!({"result": {"process_info": {"foreground_processes": [process], "pane_id": "w1:p1", "shell_pid": 7}}});
        fs::write(home.path().join("proc.json"), process.to_string()).unwrap();
        let herdr = bin.join("herdr");
        fs::write(
            &herdr,
            "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n",
        )
        .unwrap();
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
        Self {
            home,
            process,
            env: Default::default(),
        }
    }

    /// Herdr's `foreground_cwd` for the pane: `cwd`, or none reported.
    fn cwd(&self, cwd: Option<&std::path::Path>) {
        let file = self.path("pane.json");
        let mut pane: Value = serde_json::from_str(&fs::read_to_string(&file).unwrap()).unwrap();
        pane["result"]["pane"]["foreground_cwd"] = json!(cwd);
        fs::write(file, pane.to_string()).unwrap();
    }

    fn path(&self, rest: &str) -> PathBuf {
        self.home.path().join(rest)
    }

    /// The shell's environment: its HOME and these variables.
    fn environ(&self, vars: &[(&str, &str)]) {
        self.env.replace(
            vars.iter()
                .map(|(key, value)| (key.to_string(), value.to_string()))
                .collect(),
        );
    }

    /// The session directory of `sid` in `bucket` under `sessions`, bound
    /// to `$HOME/work`, with `records` as its main journal.
    fn session_in(
        &self,
        sessions: &std::path::Path,
        bucket: &str,
        sid: &str,
        records: &[Value],
    ) -> PathBuf {
        let dir = sessions.join(bucket).join(sid);
        fs::create_dir_all(dir.join("agents/main")).unwrap();
        fs::write(
            dir.join("state.json"),
            json!({"cwd": self.path("work")}).to_string(),
        )
        .unwrap();
        fs::write(dir.join("agents/main/wire.jsonl"), journal(records)).unwrap();
        dir
    }

    fn session(&self, bucket: &str, records: &[Value]) -> PathBuf {
        self.session_in(&self.path(".kimi-code/sessions"), bucket, SID, records)
    }

    fn expect(&self, sid: &str) -> Identity {
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: foreground_pid(&self.process),
            path: sid.into(),
            agent: "kimi".into(),
            source: "herdr:kimi".into(),
            kind: "id".into(),
        }
    }

    fn page_of(&self, sid: &str, stamp: u64) -> Result<Response> {
        let bin = self.path(".local/bin");
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(script("lab", "w1:p1", &self.expect(sid), stamp))
            .env_remove("KIMI_CODE_HOME")
            .envs(self.env.borrow().iter().cloned())
            .env("HOME", self.home.path())
            .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
            .output()
            .unwrap();
        if !output.status.success() {
            return Err(Error::NoTranscript);
        }
        answer(&output.stdout, &self.expect(sid), false)
    }

    fn page(&self) -> Result<Response> {
        self.page_of(SID, 0)
    }
}

fn refused(result: Result<Response>) -> &'static str {
    match result {
        Err(Error::UnsupportedFormat(format)) => format,
        other => panic!("{other:?}"),
    }
}

#[test]
fn the_host_reads_the_bound_session_and_an_unchanged_journal_is_not_modified() {
    let host = Host::new(SID);
    host.session("bucket1", &two_turns());
    let first = host.page().unwrap();
    assert_eq!(texts(&first.entries), ["one", "first", "two", "second"]);
    assert!(first.reset);
    assert!(first.end > 0);

    let again = host.page_of(SID, first.end).unwrap();
    assert!(again.entries.is_empty());
    assert!(!again.reset);
    assert_eq!(again.end, first.end);
}

#[test]
fn a_store_that_is_relative_or_outside_home_is_refused_never_the_default() {
    let host = Host::new(SID);
    host.session("bucket1", &two_turns());
    for root in ["~/x", "rel/x"] {
        host.environ(&[("KIMI_CODE_HOME", root)]);
        assert_eq!(refused(host.page()), OUTSIDE_HOME, "{root}");
    }
    let elsewhere = tempfile::tempdir().unwrap();
    host.session_in(
        &elsewhere.path().join("sessions"),
        "bucket1",
        SID,
        &two_turns(),
    );
    host.environ(&[("KIMI_CODE_HOME", elsewhere.path().to_str().unwrap())]);
    assert_eq!(refused(host.page()), OUTSIDE_HOME);
}

#[test]
fn the_same_id_in_two_buckets_is_ambiguous() {
    let host = Host::new(SID);
    host.session("bucket1", &two_turns());
    host.session("bucket2", &two_turns());
    assert_eq!(refused(host.page()), AMBIGUOUS);
}

#[test]
fn an_index_entry_outside_sessions_is_not_followed() {
    let host = Host::new(SID);
    let outside = host.session_in(&host.path("elsewhere"), "bucket1", SID, &two_turns());
    fs::create_dir_all(host.path(".kimi-code/sessions")).unwrap();
    fs::write(
        host.path(".kimi-code/session_index.jsonl"),
        format!("{}\n", json!({"sessionId": SID, "sessionDir": outside})),
    )
    .unwrap();
    assert!(matches!(host.page(), Err(Error::NoTranscript)));
}

#[test]
fn a_session_bound_to_another_directory_or_an_unreadable_cwd_is_unconfined() {
    let host = Host::new(SID);
    host.session("bucket1", &two_turns());
    fs::create_dir_all(host.path("other")).unwrap();
    host.cwd(Some(&host.path("other")));
    assert!(matches!(host.page(), Err(Error::TranscriptUnconfined)));

    host.cwd(None);
    assert!(matches!(host.page(), Err(Error::TranscriptUnconfined)));
}

#[test]
fn a_linked_journal_is_unreadable() {
    let host = Host::new(SID);
    let dir = host.session("bucket1", &[]);
    fs::write(host.path("real.jsonl"), journal(&two_turns())).unwrap();
    fs::remove_file(dir.join("agents/main/wire.jsonl")).unwrap();
    std::os::unix::fs::symlink(host.path("real.jsonl"), dir.join("agents/main/wire.jsonl"))
        .unwrap();
    assert_eq!(refused(host.page()), UNREADABLE);
}

#[test]
fn an_id_kimi_would_not_look_up_is_no_transcript() {
    let host = Host::new("session_../x");
    host.session("bucket1", &two_turns());
    assert!(matches!(
        host.page_of("session_../x", 0),
        Err(Error::NoTranscript)
    ));
    assert!(!session_id("session_../x"));
    assert!(session_id(SID));
}

/// Kimi Code retitles itself `kimi-code` (cli/main.ts:181), and Herdr names a
/// process by its comm, so a real run reads as `kimi-code`, or as `node`
/// running the npm entry before the title is set.
#[test]
fn kimi_code_by_either_name_or_as_node_on_its_entry_is_read() {
    let entry = "/home/u/.npm-global/lib/node_modules/@moonshot-ai/kimi-code/dist/main.mjs";
    for process in [
        json!({"name": "kimi", "pid": 42}),
        json!({"name": "kimi-code", "pid": 42}),
        json!({"name": "node", "pid": 42, "argv": ["node", "--no-warnings", entry, "--session", SID]}),
        json!({"name": "node", "pid": 42, "argv": ["/usr/bin/node", "/opt/Node_Modules/@Moonshot-AI/Kimi-Code/dist/MAIN.mjs"]}),
    ] {
        let host = Host::running(SID, process.clone());
        host.session("bucket1", &two_turns());
        assert_eq!(host.expect(SID).pid, Some(42), "{process}");
        assert_eq!(
            texts(&host.page().unwrap().entries),
            ["one", "first", "two", "second"],
            "{process}"
        );
    }
}

#[test]
fn any_other_foreground_process_is_refused() {
    for process in [
        json!({"name": "node", "pid": 42}),
        json!({"name": "node", "pid": 42, "argv": ["node", "/home/u/app/dist/main.mjs"]}),
        json!({"name": "node", "pid": 42, "argv": ["node", "/x/node_modules/@moonshot-ai/kimi-code/dist/main.mjs.bak"]}),
        json!({"name": "kimi code", "pid": 42}),
        json!({"name": "bash", "pid": 42}),
    ] {
        let host = Host::running(SID, process.clone());
        host.session("bucket1", &two_turns());
        assert_eq!(host.expect(SID).pid, None, "{process}");
        assert_eq!(refused(host.page()), UNNAMED, "{process}");
    }
}

/// sessionMetadataService.ts:317-320 `cwd ?? workDir`: only a missing or
/// null cwd falls back to the legacy workDir.
#[test]
fn the_bound_directory_is_cwd_and_only_a_missing_one_falls_back_to_work_dir() {
    let host = Host::new(SID);
    let dir = host.session("bucket1", &two_turns());
    let work = host.path("work");
    for state in [
        json!({"workDir": work}),
        json!({"cwd": null, "workDir": work}),
    ] {
        fs::write(dir.join("state.json"), state.to_string()).unwrap();
        assert_eq!(
            texts(&host.page().unwrap().entries),
            ["one", "first", "two", "second"],
            "{state}"
        );
    }
    for state in [
        json!({"cwd": "", "workDir": work}),
        json!({"cwd": 7, "workDir": work}),
    ] {
        fs::write(dir.join("state.json"), state.to_string()).unwrap();
        assert_eq!(refused(host.page()), UNREADABLE, "{state}");
    }
}
