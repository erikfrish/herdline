//! FIXTURE-ONLY: every `updates.jsonl` here is written by these tests in the
//! envelope Grok's own storage tests use (grok-build 2bdd1d6,
//! `xai-grok-shell/src/extensions/session_updates.rs:596-598`) with ACP
//! v0.10.4 update shapes and synthetic content, not captured from a real
//! Grok. Nothing here claims how a live Grok writes; it pins what the reader
//! does with such lines.

use super::*;
use std::{
    fs,
    io::Write as _,
    os::unix::fs::PermissionsExt,
    path::PathBuf,
    process::Command,
    time::{Duration, Instant},
};

const SID: &str = "0195f3a2-7c41-7d2e-9b1a-3f6c2d8e4a10";

struct Host {
    home: tempfile::TempDir,
    /// The exec shell's own variables beyond HOME and PATH.
    env: std::cell::RefCell<Vec<(String, String)>>,
}

impl Host {
    /// A pane whose Herdr report names `agent` on `sid`.
    fn new(sid: &str, agent: &str) -> Self {
        let home = tempfile::tempdir().unwrap();
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap();
        let pane = json!({"id": "cli:pane:get", "result": {"pane": {
            "agent": agent,
            "agent_session": {"agent": agent, "kind": "id", "source": format!("herdr:{agent}"), "value": sid},
            "agent_status": "idle",
            "pane_id": "w1:p1",
            "terminal_id": "term_1",
        }, "type": "pane_info"}});
        fs::write(home.path().join("pane.json"), pane.to_string()).unwrap();
        fs::write(
            home.path().join("proc.json"),
            format!(r#"{{"result":{{"process_info":{{"foreground_processes":[{{"name":"{agent}","pid":42}}],"pane_id":"w1:p1","shell_pid":7}}}}}}"#),
        )
        .unwrap();
        let herdr = bin.join("herdr");
        fs::write(
            &herdr,
            "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n",
        )
        .unwrap();
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
        let host = Self {
            home,
            env: Default::default(),
        };
        host.registry(&json!([{"session_id": sid, "pid": 42, "cwd": "/w", "opened_at": "2026-11-12T10:00:00Z"}]).to_string());
        host
    }

    /// `~/.grok/active_sessions.json` (active-sessions `lib.rs:16-23`).
    fn registry(&self, body: &str) {
        let grok = self.home.path().join(".grok");
        fs::create_dir_all(&grok).unwrap();
        fs::write(grok.join("active_sessions.json"), body).unwrap();
    }

    /// The shell's environment: its HOME and these variables.
    fn environ(&self, vars: &[(&str, &str)]) {
        self.env.replace(
            vars.iter()
                .map(|(key, value)| (key.to_string(), value.to_string()))
                .collect(),
        );
    }

    /// `~/.grok/sessions/<cwd dir>/<sid>/updates.jsonl` (`paths.rs:132-141`).
    fn file(&self, cwd: &str) -> PathBuf {
        let dir = self.home.path().join(".grok/sessions").join(cwd).join(SID);
        fs::create_dir_all(&dir).unwrap();
        dir.join("updates.jsonl")
    }

    fn page(&self, read: Read) -> Result<Response> {
        let bin = self.home.path().join(".local/bin");
        let expect = expect();
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(query_script("lab", "w1:p1", &expect, &options(read)))
            .env_remove("GROK_HOME")
            .envs(self.env.borrow().iter().cloned())
            .env("HOME", self.home.path())
            .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
            .output()
            .unwrap();
        assert!(
            output.status.success(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        response(
            &serde_json::from_slice(&output.stdout).unwrap(),
            &expect,
            false,
        )
    }
}

fn expect() -> Identity {
    Identity {
        pane_id: "w1:p1".into(),
        terminal_id: "term_1".into(),
        pid: Some(42),
        path: SID.into(),
        agent: "grok".into(),
        source: "herdr:grok".into(),
        kind: "id".into(),
    }
}

/// One stored ACP `session/update` line.
fn acp(update: Value) -> String {
    json!({"timestamp": 1, "method": "session/update", "params": {"sessionId": SID, "update": update}}).to_string()
}

/// One stored Grok `_x.ai/session/update` line.
fn xai(update: Value) -> String {
    json!({"timestamp": 1, "method": "_x.ai/session/update", "params": {"sessionId": SID, "update": update}}).to_string()
}

fn chunk(kind: &str, words: &str) -> String {
    acp(json!({"sessionUpdate": kind, "content": {"type": "text", "text": words}}))
}

fn append(path: &PathBuf, lines: &[String]) {
    let mut file = fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(path)
        .unwrap();
    for line in lines {
        writeln!(file, "{line}").unwrap();
    }
}

/// Every Text and Thinking block's text, in order.
fn texts(page: &Response) -> Vec<String> {
    page.entries
        .iter()
        .flat_map(|entry| &entry.blocks)
        .filter_map(|block| match block {
            Block::Text { text, .. } | Block::Thinking { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

fn refused(result: Result<Response>) -> &'static str {
    match result {
        Err(Error::UnsupportedFormat(format)) => format,
        other => panic!("{other:?}"),
    }
}

#[test]
fn chunks_merge_into_one_row_per_turn_and_a_streaming_row_is_read_again() {
    let host = Host::new(SID, "grok");
    let file = host.file("%2Fw");
    append(
        &file,
        &[
            chunk("user_message_chunk", "hel"),
            chunk("user_message_chunk", "lo"),
            chunk("agent_thought_chunk", "th"),
            chunk("agent_thought_chunk", "ink"),
            chunk("agent_message_chunk", "a"),
            chunk("agent_message_chunk", "b"),
        ],
    );
    let tail = host.page(Read::Tail).unwrap();
    assert_eq!(tail.entries.len(), 2);
    assert_eq!(tail.entries[0].role.as_deref(), Some("user"));
    assert_eq!(tail.entries[1].role.as_deref(), Some("assistant"));
    assert!(matches!(&tail.entries[1].blocks[0], Block::Thinking { text, .. } if text == "think"));
    assert_eq!(texts(&tail), ["hello", "think", "ab"]);
    append(&file, &[chunk("agent_message_chunk", "c")]);
    // The last row shown comes again, at its own offset, with what it streamed since.
    let next = host.page(Read::After { offset: tail.end }).unwrap();
    assert!(!next.reset);
    assert_eq!(next.entries.len(), 1);
    assert_eq!(next.entries[0].offset, tail.entries[1].offset);
    assert_eq!(texts(&next), ["think", "abc"]);
    // A line still being written is not read until it ends.
    fs::OpenOptions::new()
        .append(true)
        .open(&file)
        .unwrap()
        .write_all(b"{\"timestamp\":1,")
        .unwrap();
    assert_eq!(
        texts(&host.page(Read::Tail).unwrap()),
        ["hello", "think", "abc"]
    );
}

#[test]
fn a_tool_call_pairs_with_its_final_update_or_its_failure() {
    let host = Host::new(SID, "grok");
    append(
        &host.file("%2Fw"),
        &[
            acp(
                json!({"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "Read a.txt", "kind": "read",
                "status": "pending", "rawInput": {"path": "a.txt"}}),
            ),
            acp(
                json!({"sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "in_progress",
                "content": [{"type": "content", "content": {"type": "text", "text": "partial"}}]}),
            ),
            acp(
                json!({"sessionUpdate": "tool_call_update", "toolCallId": "t1", "status": "completed",
                "content": [{"type": "content", "content": {"type": "text", "text": "done"}}]}),
            ),
            acp(
                json!({"sessionUpdate": "tool_call", "toolCallId": "t2", "title": "Run make", "status": "in_progress"}),
            ),
            acp(
                json!({"sessionUpdate": "tool_call_update", "toolCallId": "t2", "status": "failed", "rawOutput": "boom"}),
            ),
        ],
    );
    let page = host.page(Read::Tail).unwrap();
    let blocks: Vec<_> = page
        .entries
        .iter()
        .flat_map(|entry| entry.blocks.clone())
        .collect();
    match blocks.as_slice() {
        [
            Block::ToolCall {
                call_id: c1,
                name: n1,
                args,
                ..
            },
            Block::ToolResult {
                call_id: r1,
                text: t1,
                is_error: false,
                ..
            },
            Block::ToolCall { call_id: c2, .. },
            Block::ToolResult {
                call_id: r2,
                text: t2,
                is_error: true,
                ..
            },
        ] => {
            assert_eq!(
                (c1.as_str(), n1.as_str(), r1.as_str(), t1.as_str()),
                ("t1", "Read a.txt", "t1", "done")
            );
            assert_eq!(
                serde_json::from_str::<Value>(args).unwrap(),
                json!({"path": "a.txt"})
            );
            assert_eq!(
                (c2.as_str(), r2.as_str(), t2.as_str()),
                ("t2", "t2", "boom")
            );
        }
        other => panic!("{other:?}"),
    }
}

#[test]
fn a_rewound_branch_is_dropped_and_the_next_page_starts_over() {
    let host = Host::new(SID, "grok");
    let file = host.file("%2Fw");
    append(
        &file,
        &[
            chunk("user_message_chunk", "first"),
            chunk("agent_message_chunk", "r1"),
            chunk("user_message_chunk", "dead"),
            chunk("agent_message_chunk", "dead-r"),
        ],
    );
    let tail = host.page(Read::Tail).unwrap();
    assert_eq!(texts(&tail), ["first", "r1", "dead", "dead-r"]);
    // Grok's /rewind to prompt 1 appends a marker and the new branch.
    append(
        &file,
        &[
            xai(
                json!({"sessionUpdate": "rewind_marker", "target_prompt_index": 1, "created_at": "2026-11-12T00:00:00Z"}),
            ),
            chunk("user_message_chunk", "replacement"),
        ],
    );
    let next = host.page(Read::After { offset: tail.end }).unwrap();
    assert!(next.reset);
    assert_eq!(texts(&next), ["first", "r1", "replacement"]);
}

#[test]
fn status_updates_are_skipped_and_unknown_kinds_are_named() {
    let host = Host::new(SID, "grok");
    append(
        &host.file("%2Fw"),
        &[
            acp(json!({"sessionUpdate": "available_commands_update", "availableCommands": []})),
            xai(json!({"sessionUpdate": "turn_usage"})),
            xai(json!({"sessionUpdate": "brand_new_kind"})),
            acp(json!({"sessionUpdate": "future_kind"})),
            "not json".to_owned(),
            chunk("agent_message_chunk", "after"),
        ],
    );
    let page = host.page(Read::Tail).unwrap();
    assert_eq!(
        texts(&page),
        [
            "Unsupported Grok update: brand_new_kind",
            "Unsupported Grok update: future_kind",
            "Unsupported Grok update: unreadable",
            "after",
        ]
    );
    assert_eq!(page.entries[0].role.as_deref(), Some("system"));
}

#[test]
fn images_are_shown_unreadable_and_their_bytes_never_leave_the_host() {
    let host = Host::new(SID, "grok");
    append(
        &host.file("%2Fw"),
        &[
            acp(json!({"sessionUpdate": "user_message_chunk",
                "content": {"type": "image", "data": "SECRETPIXELS", "mimeType": "image/png"}})),
            acp(
                json!({"sessionUpdate": "tool_call", "toolCallId": "t1", "title": "Screenshot", "status": "completed",
                "content": [{"type": "content", "content": {"type": "image", "data": "SECRETPIXELS", "mimeType": "image/png"}}]}),
            ),
        ],
    );
    let page = host.page(Read::Tail).unwrap();
    let unreadable = Image {
        part: 0,
        source: Source::Unreadable,
    };
    assert_eq!(page.entries[0].blocks, [Block::Image(unreadable.clone())]);
    assert!(
        matches!(&page.entries[1].blocks[1], Block::ToolResult { images, .. } if *images == [unreadable])
    );
    assert!(!format!("{page:?}").contains("SECRETPIXELS"));
}

#[test]
fn an_unknown_session_is_not_found_and_two_matches_are_refused() {
    let host = Host::new(SID, "grok");
    assert!(matches!(host.page(Read::Tail), Err(Error::NoTranscript)));
    // Another session in the store is never shown in its place.
    let other = host
        .home
        .path()
        .join(".grok/sessions/%2Fw/0195f3a2-0000-7000-8000-000000000000");
    fs::create_dir_all(&other).unwrap();
    append(
        &other.join("updates.jsonl"),
        &[chunk("user_message_chunk", "other")],
    );
    assert!(matches!(host.page(Read::Tail), Err(Error::NoTranscript)));
    append(&host.file("%2Fa"), &[chunk("user_message_chunk", "one")]);
    append(&host.file("%2Fb"), &[chunk("user_message_chunk", "two")]);
    assert_eq!(refused(host.page(Read::Tail)), AMBIGUOUS);
}

#[test]
fn the_registry_only_refuses_and_never_shows_its_own_session() {
    const OTHER: &str = "0195f3a2-0000-7000-8000-000000000000";
    let entry = |sid: &str, pid: u32| json!({"session_id": sid, "pid": pid, "cwd": "/w", "opened_at": "2026-11-12T10:00:00Z"});
    let host = Host::new(SID, "grok");
    append(
        &host.file("%2Fw"),
        &[chunk("user_message_chunk", "herdr's")],
    );
    let other = host.home.path().join(".grok/sessions/%2Fw").join(OTHER);
    fs::create_dir_all(&other).unwrap();
    append(
        &other.join("updates.jsonl"),
        &[chunk("user_message_chunk", "registry's")],
    );
    // Another process's entries do not count either way.
    host.registry(&json!([entry(OTHER, 7), entry(SID, 42)]).to_string());
    assert_eq!(texts(&host.page(Read::Tail).unwrap()), ["herdr's"]);
    // After /new the pane's process names another session: neither is shown.
    host.registry(&json!([entry(OTHER, 42)]).to_string());
    assert_eq!(refused(host.page(Read::Tail)), STALE);
    host.registry(&json!([entry(SID, 42), entry(OTHER, 42)]).to_string());
    assert_eq!(refused(host.page(Read::Tail)), AMBIGUOUS);
    for body in [
        json!([entry(SID, 7)]).to_string(),
        "[]".into(),
        "{not json".into(),
        json!([{"pid": 42}]).to_string(),
    ] {
        host.registry(&body);
        assert_eq!(refused(host.page(Read::Tail)), UNREGISTERED, "{body}");
    }
    fs::remove_file(host.home.path().join(".grok/active_sessions.json")).unwrap();
    assert_eq!(refused(host.page(Read::Tail)), UNREGISTERED);
}

#[test]
fn a_store_outside_home_or_a_relative_grok_home_is_refused() {
    let outside = tempfile::tempdir().unwrap();
    let host = Host::new(SID, "grok");
    let real = outside.path().join("sessions/%2Fw").join(SID);
    fs::create_dir_all(&real).unwrap();
    append(
        &real.join("updates.jsonl"),
        &[chunk("user_message_chunk", "outside")],
    );
    host.environ(&[("GROK_HOME", outside.path().to_str().unwrap())]);
    assert_eq!(refused(host.page(Read::Tail)), OUTSIDE_HOME);
    // Never resolved against a cwd this cannot know, even when one would match.
    append(&host.file("%2Fw"), &[chunk("user_message_chunk", "home")]);
    host.environ(&[("GROK_HOME", ".grok")]);
    assert_eq!(refused(host.page(Read::Tail)), OUTSIDE_HOME);
    // An in-home session file that links outside is refused the same way.
    host.environ(&[]);
    let link = host.file("%2Fw");
    fs::remove_file(&link).unwrap();
    std::os::unix::fs::symlink(real.join("updates.jsonl"), &link).unwrap();
    assert_eq!(refused(host.page(Read::Tail)), OUTSIDE_HOME);
}

#[test]
fn a_fifo_at_the_session_path_is_refused_without_waiting() {
    let host = Host::new(SID, "grok");
    assert!(
        Command::new("mkfifo")
            .arg(host.file("%2Fw"))
            .status()
            .unwrap()
            .success()
    );
    let started = Instant::now();
    assert_eq!(refused(host.page(Read::Tail)), UNREADABLE);
    assert!(started.elapsed() < Duration::from_secs(10));
}

#[test]
fn a_session_file_past_the_parse_bound_is_refused_promptly() {
    let host = Host::new(SID, "grok");
    let file = host.file("%2Fw");
    append(&file, &[chunk("user_message_chunk", "big")]);
    // Sparse: 32 MiB and one byte, without writing them.
    fs::OpenOptions::new()
        .write(true)
        .open(&file)
        .unwrap()
        .set_len(32 * 1024 * 1024 + 1)
        .unwrap();
    let started = Instant::now();
    assert_eq!(refused(host.page(Read::Tail)), TOO_LARGE);
    assert!(started.elapsed() < Duration::from_secs(10));
}

#[test]
fn one_turn_larger_than_the_page_budget_is_shown_cut_not_failed() {
    let host = Host::new(SID, "grok");
    // 60 alternating thought and text chunks of 40 KB each: separate blocks,
    // each under the field clip, together far past the 1.5 MiB budget.
    let lines: Vec<_> = (0..60)
        .map(|i| {
            chunk(
                if i % 2 == 0 {
                    "agent_thought_chunk"
                } else {
                    "agent_message_chunk"
                },
                &"x".repeat(40_000),
            )
        })
        .collect();
    append(&host.file("%2Fw"), &lines);
    let page = host.page(Read::Tail).unwrap();
    assert_eq!(page.entries.len(), 1);
    let blocks = &page.entries[0].blocks;
    assert!(blocks.len() > 2 && blocks.len() < 60);
    assert!(
        matches!(blocks.last(), Some(Block::Text { text, truncated: true }) if !text.starts_with('x'))
    );
}

#[test]
fn a_grok_pane_needs_its_own_process() {
    let report = |process: &str| {
        let pane = json!({"result": {"pane": {"pane_id": "w1:p1", "terminal_id": "term_1", "agent_status": "idle",
            "agent_session": {"agent": "grok", "kind": "id", "source": "herdr:grok", "value": SID}}}});
        let process = json!({"result": {"process_info": {"foreground_processes": [{"name": process, "pid": 42}]}}});
        super::super::identity(&pane, &process).map(|(identity, _)| identity)
    };
    assert_eq!(report("grok").unwrap(), expect());
    assert_eq!(report("node").unwrap().pid, None);
}

#[test]
fn a_plan_reads_as_one_marker_per_item() {
    let host = Host::new(SID, "grok");
    append(
        &host.file("%2Fw"),
        &[acp(json!({"sessionUpdate": "plan", "entries": [
            {"content": "read the log", "status": "completed", "priority": "high"},
            {"content": "fix the bug", "status": "in_progress", "priority": "high"},
            {"content": "ship", "status": "pending", "priority": "low"},
        ]}))],
    );
    let page = host.page(Read::Tail).unwrap();
    let marker = |marker: Marker, text: &str| Block::Marker {
        marker,
        text: text.to_owned(),
        truncated: false,
    };
    assert_eq!(
        page.entries[0].blocks,
        [
            marker(Marker::Done, "read the log"),
            marker(Marker::ToDo, "fix the bug"),
            marker(Marker::ToDo, "ship")
        ]
    );
}

#[test]
fn a_plan_row_without_items_still_reads_as_its_text() {
    let row = entry(&json!({"id": 3, "kind": "plan", "ts": 1, "text": "[completed] read the log"}))
        .unwrap();
    assert_eq!(
        row.blocks,
        [Block::Text {
            text: "[completed] read the log".to_owned(),
            truncated: false
        }]
    );
    let empty = entry(&json!({"id": 4, "kind": "plan", "text": "[plan]", "entries": []})).unwrap();
    assert_eq!(
        empty.blocks,
        [Block::Text {
            text: "[plan]".to_owned(),
            truncated: false
        }]
    );
}
