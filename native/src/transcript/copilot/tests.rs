//! FIXTURE-ONLY: every log here is written by these tests in the shape the
//! shipped 1.0.109 SDK types declare (`session-events.d.ts`), with synthetic
//! events, not captured from a real Copilot CLI. Nothing here claims how a
//! live Copilot writes; it pins what the reader does with such a log.

use super::*;
use std::{
    fs,
    os::unix::fs::PermissionsExt,
    path::PathBuf,
    process::Command,
    time::{Duration, Instant},
};

const SID: &str = "0b8f9c1e-2d3a-4b5c-8d6e-7f8091a2b3c4";
const OTHER: &str = "1c9fad2f-3e4b-4c6d-9e7f-8091a2b3c4d5";

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
        Self {
            home,
            env: Default::default(),
        }
    }

    /// The shell's environment: its HOME and these variables.
    fn environ(&self, vars: &[(&str, &str)]) {
        self.env.replace(
            vars.iter()
                .map(|(key, value)| (key.to_string(), value.to_string()))
                .collect(),
        );
    }

    /// `events.jsonl` of `sid` under the store `root`.
    fn log_in(&self, root: &str, sid: &str) -> PathBuf {
        let dir = self.home.path().join(root).join("session-state").join(sid);
        fs::create_dir_all(&dir).unwrap();
        dir.join("events.jsonl")
    }

    fn log(&self, sid: &str) -> PathBuf {
        self.log_in(".copilot", sid)
    }

    fn write(&self, sid: &str, events: &[Value]) {
        fs::write(self.log(sid), lines(events)).unwrap();
    }

    fn expect(&self, sid: &str) -> Identity {
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: Some(42),
            path: sid.into(),
            agent: "copilot".into(),
            source: "herdr:copilot".into(),
            kind: "id".into(),
        }
    }

    fn page(&self, sid: &str, read: Read) -> Result<Response> {
        let bin = self.home.path().join(".local/bin");
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(query_script(
                "lab",
                "w1:p1",
                &self.expect(sid),
                &options(read),
            ))
            .env_remove("COPILOT_HOME")
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
            &self.expect(sid),
            false,
        )
    }
}

fn lines(events: &[Value]) -> String {
    events.iter().map(|event| format!("{event}\n")).collect()
}

/// One event with the envelope every 1.0.109 event carries.
fn event(id: &str, kind: &str, data: Value) -> Value {
    json!({"id": id, "parentId": null, "timestamp": "2026-11-12T03:00:00.000Z", "type": kind, "data": data})
}

fn start(sid: &str) -> Value {
    event(
        "e-start",
        "session.start",
        json!({"sessionId": sid, "version": 1, "producer": "copilot-agent", "copilotVersion": "1.0.109"}),
    )
}

fn user(id: &str, words: &str) -> Value {
    event(id, "user.message", json!({"content": words}))
}

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
fn events_map_to_chat_and_hidden_secret_or_model_facing_parts_never_show() {
    let host = Host::new(SID, "copilot");
    let mut hidden = event(
        "e-skill",
        "user.message",
        json!({"content": "skill body", "source": "skill-pdf"}),
    );
    hidden["parentId"] = json!("e-1");
    let mut sub = event(
        "e-sub",
        "assistant.message",
        json!({"messageId": "m9", "content": "from a subagent"}),
    );
    sub["agentId"] = json!("agent-1");
    let mut passing = event(
        "e-eph",
        "assistant.message",
        json!({"messageId": "m8", "content": "ephemeral"}),
    );
    passing["ephemeral"] = json!(true);
    host.write(SID, &[
        start(SID),
        event("e-sys", "system.message", json!({"content": "SYSTEM PROMPT", "role": "system"})),
        event("e-1", "user.message", json!({"content": "fix it", "transformedContent": "fix it <reminder>INTERNAL</reminder>",
            "attachments": [
                {"type": "file", "path": "/etc/shadow", "displayName": "shadow"},
                {"type": "blob", "data": "aGVsbG8=", "mimeType": "image/png", "displayName": "shot.png"},
            ]})),
        hidden,
        event("e-r", "assistant.reasoning", json!({"reasoningId": "r1", "content": "thinking out loud"})),
        event("e-2", "assistant.message", json!({"messageId": "m1", "content": "on it", "encryptedContent": "SECRET",
            "reasoningOpaque": "SECRET", "toolRequests": [{"toolCallId": "c1", "name": "bash"}]})),
        event("e-3", "tool.execution_start", json!({"toolCallId": "c1", "toolName": "bash", "arguments": {"command": "ls"}})),
        event("e-4", "tool.execution_complete", json!({"toolCallId": "c1", "success": true,
            "result": {"content": "short", "detailedContent": "a\nb", "contents": [{"type": "image", "data": "eA==", "mimeType": "image/png"}]}})),
        event("e-5", "tool.execution_start", json!({"toolCallId": "c2", "toolName": "view", "arguments": {}})),
        event("e-6", "tool.execution_complete", json!({"toolCallId": "c2", "success": false, "error": {"message": "denied"}})),
        sub,
        passing,
        event("e-7", "abort", json!({"reason": "user_initiated"})),
        event("e-8", "session.error", json!({"errorType": "rate", "message": "rate limited"})),
        event("e-9", "session.compaction_complete", json!({"success": true})),
        event("e-10", "session.model_change", json!({"newModel": "x"})),
        event("e-11", "brand.new_thing", json!({})),
    ]);
    let page = host.page(SID, Read::Tail).unwrap();
    assert_eq!(
        texts(&page),
        [
            "fix it",
            "[file: shadow]",
            "thinking out loud",
            "on it",
            "[rate limited]",
            "Unsupported Copilot event: brand.new_thing"
        ]
    );
    let all = format!("{page:?}");
    for leak in [
        "INTERNAL",
        "SECRET",
        "SYSTEM PROMPT",
        "skill body",
        "from a subagent",
        "ephemeral",
        "/etc/shadow",
        "aGVsbG8",
    ] {
        assert!(!all.contains(leak), "{leak}");
    }
    let user = &page.entries[0];
    assert_eq!(user.role.as_deref(), Some("user"));
    assert_eq!(user.timestamp, Some(1_794_452_400_000));
    assert!(matches!(
        &user.blocks[2],
        Block::Image(Image {
            part: 0,
            source: Source::Unreadable
        })
    ));
    let tools: Vec<_> = page
        .entries
        .iter()
        .flat_map(|entry| &entry.blocks)
        .filter_map(|block| match block {
            Block::ToolCall {
                call_id,
                name,
                args,
                ..
            } => Some(format!("call {call_id} {name} {args}")),
            Block::ToolResult {
                call_id,
                name,
                text,
                is_error,
                images,
                ..
            } => Some(format!(
                "result {call_id} {name} {text:?} {is_error} {}",
                images.len()
            )),
            _ => None,
        })
        .collect();
    assert_eq!(
        tools,
        [
            r#"call c1 bash {"command":"ls"}"#,
            r#"result c1 bash "a\nb" false 1"#,
            "call c2 view {}",
            r#"result c2 view "denied" true 0"#,
        ]
    );
    assert!(
        page.entries
            .iter()
            .any(|entry| entry.kind == Kind::Compaction)
    );
    assert!(!page.has_older);
}

#[test]
fn pages_back_and_forth_by_anchored_cursors_and_any_entry_offset_is_a_cursor() {
    let host = Host::new(SID, "copilot");
    let mut events = vec![start(SID)];
    events.extend((0..250).map(|n| user(&format!("u{n}"), &format!("m{n}"))));
    host.write(SID, &events);
    let tail = host.page(SID, Read::Tail).unwrap();
    assert!(tail.reset && tail.has_older);
    assert_eq!(tail.entries.len(), 200);
    assert_eq!(texts(&tail)[0], "m50");
    let older = host.page(SID, Read::Before { offset: tail.start }).unwrap();
    assert!(!older.reset && !older.has_older);
    assert_eq!(
        texts(&older),
        (0..50).map(|n| format!("m{n}")).collect::<Vec<_>>()
    );
    let mid = host
        .page(
            SID,
            Read::Before {
                offset: tail.entries[10].offset,
            },
        )
        .unwrap();
    assert!(!mid.reset);
    assert_eq!(texts(&mid).last().unwrap(), "m59");
    assert!(
        host.page(SID, Read::After { offset: tail.end })
            .unwrap()
            .entries
            .is_empty()
    );
    events.push(user("u-new", "appended"));
    host.write(SID, &events);
    let newer = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert!(!newer.reset);
    assert_eq!(texts(&newer), ["appended"]);
    assert_eq!(newer.entries[0].end, newer.end);
}

#[test]
fn a_rewind_that_rewrites_the_log_starts_over_from_the_tail() {
    let host = Host::new(SID, "copilot");
    host.write(
        SID,
        &[
            start(SID),
            user("u1", "first"),
            user("u2", "second"),
            user("u3", "third"),
        ],
    );
    let tail = host.page(SID, Read::Tail).unwrap();
    // Same length up to the cursor, other events there.
    host.write(
        SID,
        &[
            start(SID),
            user("u1", "first"),
            user("x2", "SECOND"),
            user("x3", "THIRD"),
            user("u4", "after"),
        ],
    );
    let page = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert!(page.reset);
    assert_eq!(texts(&page), ["first", "SECOND", "THIRD", "after"]);
    // Shorter than the cursor.
    host.write(SID, &[start(SID), user("u1", "first")]);
    let page = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert!(page.reset);
    assert_eq!(texts(&page), ["first"]);
    let page = host
        .page(
            SID,
            Read::Before {
                offset: tail.entries[2].offset,
            },
        )
        .unwrap();
    assert!(page.reset);
}

#[test]
fn a_rotated_head_shows_a_boundary_and_never_its_checkpoint_copies() {
    let host = Host::new(SID, "copilot");
    let mut copy = user("c1", "replayed copy");
    copy["checkpoint"] = json!(true);
    host.write(
        SID,
        &[
            event(
                "e-link",
                "session.journal_link",
                json!({"sessionId": SID, "previousJournal": "events.1.jsonl"}),
            ),
            copy,
            user("u1", "live"),
        ],
    );
    let page = host.page(SID, Read::Tail).unwrap();
    assert_eq!(page.entries[0].kind, Kind::Boundary);
    assert_eq!(texts(&page), ["live"]);
    // An after page never repeats the boundary.
    let mut events = vec![
        event("e-link", "session.journal_link", json!({"sessionId": SID})),
        user("u1", "live"),
    ];
    host.write(SID, &events);
    let tail = host.page(SID, Read::Tail).unwrap();
    events.push(user("u2", "next"));
    host.write(SID, &events);
    let after = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert!(
        after
            .entries
            .iter()
            .all(|entry| entry.kind != Kind::Boundary)
    );
}

#[test]
fn a_head_naming_another_session_is_refused() {
    let host = Host::new(SID, "copilot");
    host.write(
        SID,
        &[
            event(
                "e-link",
                "session.journal_link",
                json!({"sessionId": OTHER}),
            ),
            user("u1", "x"),
        ],
    );
    assert_eq!(refused(host.page(SID, Read::Tail)), MISMATCH);
    host.write(SID, &[start(OTHER), user("u1", "x")]);
    assert_eq!(refused(host.page(SID, Read::Tail)), MISMATCH);
    host.write(SID, &[user("u1", "no start")]);
    assert_eq!(refused(host.page(SID, Read::Tail)), MISMATCH);
}

#[test]
fn another_session_never_leaks_and_an_empty_log_is_an_empty_page() {
    let host = Host::new(SID, "copilot");
    host.write(OTHER, &[start(OTHER), user("o1", "other session")]);
    assert!(matches!(
        host.page(SID, Read::Tail),
        Err(Error::NoTranscript)
    ));
    fs::write(host.log(SID), format!("{}\n{{\"partial", start(SID))).unwrap();
    let page = host.page(SID, Read::Tail).unwrap();
    assert!(page.entries.is_empty() && !page.has_older);
}

#[test]
fn an_unreadable_line_is_named_not_failed() {
    let host = Host::new(SID, "copilot");
    fs::write(host.log(SID), format!("{}\nnot json\n", start(SID))).unwrap();
    assert_eq!(
        texts(&host.page(SID, Read::Tail).unwrap()),
        ["Unsupported Copilot event: unreadable"]
    );
}

#[test]
fn copilot_home_is_followed_only_when_absolute_and_inside_home() {
    let host = Host::new(SID, "copilot");
    let inside = host.log_in("alt", SID);
    fs::write(&inside, lines(&[start(SID), user("u1", "alt store")])).unwrap();
    host.environ(&[(
        "COPILOT_HOME",
        host.home.path().join("alt").to_str().unwrap(),
    )]);
    assert_eq!(texts(&host.page(SID, Read::Tail).unwrap()), ["alt store"]);
    host.environ(&[("COPILOT_HOME", "alt")]);
    assert_eq!(refused(host.page(SID, Read::Tail)), OUTSIDE_HOME);
    let outside = tempfile::tempdir().unwrap();
    let dir = outside.path().join("session-state").join(SID);
    fs::create_dir_all(&dir).unwrap();
    fs::write(
        dir.join("events.jsonl"),
        lines(&[start(SID), user("u1", "outside")]),
    )
    .unwrap();
    host.environ(&[("COPILOT_HOME", outside.path().to_str().unwrap())]);
    assert_eq!(refused(host.page(SID, Read::Tail)), OUTSIDE_HOME);
    // A link from inside home to outside is refused the same way.
    host.environ(&[]);
    std::os::unix::fs::symlink(outside.path(), host.home.path().join(".copilot")).unwrap();
    assert_eq!(refused(host.page(SID, Read::Tail)), OUTSIDE_HOME);
}

#[test]
fn a_fifo_or_a_symlink_at_the_log_is_refused_without_waiting() {
    let host = Host::new(SID, "copilot");
    let log = host.log(SID);
    assert!(Command::new("mkfifo").arg(&log).status().unwrap().success());
    let started = Instant::now();
    assert_eq!(refused(host.page(SID, Read::Tail)), UNREADABLE);
    assert!(started.elapsed() < Duration::from_secs(10));
    fs::remove_file(&log).unwrap();
    let real = log.with_file_name("real.jsonl");
    fs::write(&real, lines(&[start(SID), user("u1", "x")])).unwrap();
    std::os::unix::fs::symlink(&real, &log).unwrap();
    assert_eq!(refused(host.page(SID, Read::Tail)), UNREADABLE);
}

#[test]
fn a_log_over_32_mib_is_refused_before_it_is_read() {
    let host = Host::new(SID, "copilot");
    let file = fs::File::create(host.log(SID)).unwrap();
    // Sparse and all zeros: refused by its size, never parsed.
    file.set_len((32 << 20) + 1).unwrap();
    assert_eq!(refused(host.page(SID, Read::Tail)), TOO_LARGE);
}

#[test]
fn pages_split_at_the_byte_budget_and_none_is_lost_between_them() {
    let host = Host::new(SID, "copilot");
    let big = "x".repeat(60 * 1024);
    let mut events = vec![start(SID)];
    events.extend((0..40).map(|n| user(&format!("u{n}"), &format!("{n} {big}"))));
    host.write(SID, &events);
    let page = host.page(SID, Read::Tail).unwrap();
    assert!(page.has_older && page.entries.len() < 40);
    let older = host.page(SID, Read::Before { offset: page.start }).unwrap();
    assert!(!older.reset && !older.has_older);
    let first = |text: &String| text.split(' ').next().unwrap().parse::<usize>().unwrap();
    let seen: Vec<_> = texts(&older)
        .iter()
        .chain(texts(&page).iter())
        .map(first)
        .collect();
    assert_eq!(seen, (0..40).collect::<Vec<_>>());
}

#[test]
fn another_agent_or_an_id_that_is_not_a_full_uuid_is_never_read() {
    let host = Host::new(SID, "claude");
    host.write(SID, &[start(SID), user("u1", "x")]);
    assert!(matches!(
        host.page(SID, Read::Tail),
        Err(Error::NoTranscript)
    ));
    let report = |value: &str, process: &str| {
        let pane = json!({"result": {"pane": {"pane_id": "w1:p1", "terminal_id": "term_1", "agent_status": "idle",
            "agent_session": {"agent": "copilot", "kind": "id", "source": "herdr:copilot", "value": value}}}});
        let process = json!({"result": {"process_info": {"foreground_processes": [{"name": process, "pid": 42}]}}});
        super::super::identity(&pane, &process).map(|(identity, _)| identity)
    };
    assert_eq!(report(SID, "copilot").unwrap().pid, Some(42));
    assert_eq!(report(SID, "node").unwrap().pid, None);
    for value in [
        "0b8f9c1",
        "0B8F9C1E-2D3A-4B5C-8D6E-7F8091A2B3C4",
        "0b8f9c1e-2d3a-4b5c-8d6e-7f8091a2b3c",
        "0b8f9c1e_2d3a-4b5c-8d6e-7f8091a2b3c4",
    ] {
        assert!(
            matches!(report(value, "copilot"), Err(Error::NoTranscript)),
            "{value}"
        );
    }
}

#[test]
fn only_the_persons_own_abort_reads_as_stopped_by_you() {
    let stop = |reason: &str| {
        let row = entry(&json!({"o": 0, "e": 1, "t": "abort", "reason": reason})).unwrap();
        match row.blocks.as_slice() {
            [Block::Marker { marker, text, .. }] if text.is_empty() => *marker,
            other => panic!("{other:?}"),
        }
    };
    assert_eq!(stop("user_initiated"), Marker::StoppedByYou);
    assert_eq!(stop("user"), Marker::StoppedByYou);
    assert_eq!(stop("timeout"), Marker::Stopped);
    assert_eq!(stop(""), Marker::Stopped);
}
