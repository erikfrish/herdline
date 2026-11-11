use super::*;
use std::{fs, os::unix::fs::PermissionsExt, process::Command};

fn pane_json(path: &str) -> String {
    serde_json::json!({"id": "cli:pane:get", "result": {"pane": {
        "agent": "omp",
        "agent_session": {"agent": "omp", "kind": "path", "source": "herdr:omp", "value": path},
        "agent_status": "blocked",
        "pane_id": "w1:p1",
        "terminal_id": "term_1",
    }, "type": "pane_info"}})
    .to_string()
}

const PROCESS: &str = r#"{"result":{"process_info":{"foreground_processes":[{"name":"omp","pid":42}],"pane_id":"w1:p1","shell_pid":7}}}"#;

/// Runs [`script`] against a fake Herdr in a scratch home, as the host would.
struct Host {
    home: tempfile::TempDir,
}

impl Host {
    fn new(reported: &str) -> Self {
        let home = tempfile::tempdir().unwrap();
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap();
        fs::write(home.path().join("pane.json"), pane_json(reported)).unwrap();
        fs::write(home.path().join("proc.json"), PROCESS).unwrap();
        let herdr = bin.join("herdr");
        fs::write(
            &herdr,
            "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n",
        )
        .unwrap();
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
        Self { home }
    }

    fn sessions(&self) -> std::path::PathBuf {
        let dir = self.home.path().join(".omp/agent/sessions/-work");
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn run(&self, target: Option<Target>) -> (Option<u32>, Vec<u8>) {
        // The fake Herdr leads PATH: `command -v herdr` comes first in the
        // host's candidates, and a machine with Herdr in /usr/bin would run it.
        let bin = self.home.path().join(".local/bin");
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(script("lab", "w1:p1", target))
            .env("HOME", self.home.path())
            .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
            .output()
            .unwrap();
        let status = output
            .status
            .code()
            .and_then(|code| u32::try_from(code).ok());
        (status, output.stdout)
    }
}

fn tail(path: &str) -> Option<Target<'_>> {
    Some(Target {
        path,
        read: Read::Tail,
        window: 1024,
    })
}

#[test]
fn host_reports_identity_and_the_file_herdr_names() {
    let host = Host::new("unused");
    let file = host.sessions().join("s.jsonl");
    fs::write(&file, "{\"type\":\"session\",\"id\":\"a\"}\n").unwrap();
    let path = file.to_str().unwrap().to_owned();
    fs::write(host.home.path().join("pane.json"), pane_json(&path)).unwrap();

    let (status, out) = host.run(tail(&path));
    let reply = parse_reply(status, &out).unwrap();
    let (identity, status) = identity(&reply.pane, &reply.process).unwrap();
    assert_eq!(
        identity,
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: Some(42),
            path,
        }
    );
    assert_eq!(status, "blocked");
    let file = reply.file.unwrap();
    assert_eq!((file.size, file.start), (28, 0));
    assert_eq!(file.data, b"{\"type\":\"session\",\"id\":\"a\"}\n");
}

#[test]
fn host_refuses_a_session_file_that_links_outside_the_session_folder() {
    let host = Host::new("unused");
    let secret = host.home.path().join("secret.jsonl");
    fs::write(&secret, "{}\n").unwrap();
    let link = host.sessions().join("evil.jsonl");
    std::os::unix::fs::symlink(&secret, &link).unwrap();

    let (status, out) = host.run(tail(link.to_str().unwrap()));
    assert!(matches!(
        parse_reply(status, &out),
        Err(Error::TranscriptUnconfined)
    ));
}

#[test]
fn identity_refuses_panes_without_an_omp_session_file() {
    let process = Value::Null;
    let mut pane: Value =
        serde_json::from_str(&pane_json("/home/u/.omp/agent/sessions/x/s.jsonl")).unwrap();
    assert_eq!(identity(&pane, &process).unwrap().0.pid, None);

    pane["result"]["pane"]["agent_session"]["value"] =
        "/home/u/.omp/agent/sessions/../../.ssh/k.jsonl".into();
    assert!(matches!(
        identity(&pane, &process),
        Err(Error::TranscriptUnconfined)
    ));
    pane["result"]["pane"]["agent_session"]["value"] = "/etc/passwd".into();
    assert!(matches!(
        identity(&pane, &process),
        Err(Error::TranscriptUnconfined)
    ));
    pane["result"]["pane"]["agent_session"]["agent"] = "claude".into();
    assert!(matches!(
        identity(&pane, &process),
        Err(Error::NoTranscript)
    ));
}

/// Reads `bytes[start..start + len]` of `content` like the host does.
fn file(content: &[u8], start: u64, len: u64) -> File<'_> {
    let from = usize::try_from(start).unwrap().min(content.len());
    let to = usize::try_from(start + len).unwrap().min(content.len());
    File {
        size: content.len() as u64,
        start,
        data: &content[from..to],
    }
}

fn read<'a>(content: &'a [u8], read: Read, window: u64) -> Plan<'a> {
    let (start, len) = match read {
        Read::Tail => (content.len().saturating_sub(window as usize) as u64, window),
        Read::After { offset } if offset > 0 => (offset - 1, window + 1),
        Read::After { offset } => (offset, window),
        Read::Before { offset } => (
            offset.saturating_sub(window),
            offset - offset.saturating_sub(window),
        ),
    };
    plan(read, &file(content, start, len), window)
}

#[test]
fn pages_cover_every_line_once_and_never_a_partial_one() {
    let lines: Vec<String> = (0..40)
        .map(|n| format!("{{\"id\":\"{n}\",\"pad\":\"{}\"}}\n", "x".repeat(n * 3)))
        .collect();
    let mut content = lines.concat().into_bytes();
    // A line still being written when the file is read.
    content.extend_from_slice(b"{\"id\":\"half");
    let window = 300;

    let mut seen = Vec::new();
    let Plan::Lines {
        mut start,
        end,
        lines: newest,
    } = read(&content, Read::Tail, window)
    else {
        panic!("tail found no line");
    };
    seen.extend(entries(newest, start));
    while start > 0 {
        match read(&content, Read::Before { offset: start }, window) {
            Plan::Lines {
                start: older,
                lines,
                ..
            } => {
                seen.extend(entries(lines, older));
                start = older;
            }
            other => panic!("before {start}: {other:?}"),
        }
    }
    seen.sort_by_key(|entry| entry.offset);
    let ids: Vec<_> = seen.iter().map(|entry| entry.id.clone().unwrap()).collect();
    assert_eq!(ids, (0..40).map(|n| n.to_string()).collect::<Vec<_>>());

    // The partial line is not consumed; once finished, it is read after `end`.
    assert_eq!(end as usize, lines.concat().len());
    content.extend_from_slice(b"\"}\n");
    let Plan::Lines { lines, .. } = read(&content, Read::After { offset: end }, window) else {
        panic!("after found nothing");
    };
    assert_eq!(entries(lines, end)[0].id.as_deref(), Some("half"));
}

#[test]
fn a_shorter_or_rewritten_file_is_reread_from_its_end() {
    let content = b"{\"id\":\"a\"}\n{\"id\":\"b\"}\n";
    assert_eq!(
        read(content, Read::After { offset: 40 }, 64),
        Plan::Rewritten
    );
    // `offset` 5 is not a line boundary of this file any more.
    assert_eq!(
        read(content, Read::After { offset: 5 }, 64),
        Plan::Rewritten
    );
    assert_eq!(
        read(content, Read::Before { offset: 5 }, 64),
        Plan::Rewritten
    );
}

#[test]
fn a_line_longer_than_the_window_asks_for_a_larger_one() {
    let content = format!("{{\"id\":\"a\"}}\n{{\"id\":\"{}\"}}\n", "y".repeat(500));
    assert_eq!(
        read(content.as_bytes(), Read::After { offset: 11 }, 64),
        Plan::Grow
    );
    assert_eq!(read(content.as_bytes(), Read::Tail, 64), Plan::Grow);
    assert!(matches!(
        read(content.as_bytes(), Read::Tail, 1024),
        Plan::Lines { start: 0, .. }
    ));
}

#[test]
fn entries_keep_the_branch_links_and_cap_untrusted_text() {
    let long = "z".repeat(RESULT_LIMIT + 10);
    let lines = [
        r#"{"type":"model_change","id":"m","parentId":null}"#.to_owned(),
        r#"{"type":"message","id":"u","parentId":"m","message":{"role":"user","content":"hi"}}"#.to_owned(),
        r#"{"type":"message","id":"a","parentId":"u","message":{"role":"assistant","content":[{"type":"thinking","thinking":"hmm"},{"type":"toolCall","id":"t1","name":"bash","intent":"Listing","arguments":{"command":"ls","i":"Listing"}}]}}"#.to_owned(),
        format!(r#"{{"type":"message","id":"r","parentId":"a","message":{{"role":"toolResult","toolCallId":"t1","toolName":"bash","isError":true,"content":[{{"type":"text","text":"\u001b[31mred\u001b[0m {long}"}}]}}}}"#),
        r#"{"type":"message","id":"c","parentId":"r","message":{"role":"custom","display":false,"content":"hidden"}}"#.to_owned(),
        "not json".to_owned(),
    ];
    let text = lines
        .iter()
        .map(|line| format!("{line}\n"))
        .collect::<String>();
    let entries = entries(text.as_bytes(), 100);

    assert_eq!(entries.len(), 5);
    assert_eq!(entries[0].kind, Kind::Other);
    assert_eq!(
        (entries[1].offset, entries[1].parent_id.as_deref()),
        (100 + lines[0].len() as u64 + 1, Some("m"))
    );
    assert_eq!(
        entries[1].blocks,
        vec![Block::Text {
            text: "hi".into(),
            truncated: false
        }]
    );
    assert_eq!(
        entries[2].blocks,
        vec![
            Block::Thinking {
                text: "hmm".into(),
                truncated: false
            },
            Block::ToolCall {
                call_id: "t1".into(),
                name: "bash".into(),
                args: "{\n  \"command\": \"ls\"\n}".into(),
                intent: Some("Listing".into()),
                truncated: false,
            },
        ]
    );
    let Block::ToolResult {
        call_id,
        text,
        is_error,
        truncated,
        ..
    } = &entries[3].blocks[0]
    else {
        panic!("not a tool result");
    };
    assert_eq!(
        (call_id.as_str(), *is_error, *truncated),
        ("t1", true, true)
    );
    assert!(text.starts_with("red z") && text.len() == RESULT_LIMIT);
    assert_eq!((entries[4].kind, entries[4].blocks.len()), (Kind::Other, 0));
}

#[test]
fn messages_keep_their_time_usage_stop_reason_and_exit_code() {
    let lines = [
        r#"{"type":"message","id":"u","timestamp":"2026-11-09T13:23:53.579Z","message":{"role":"user","content":"hi","timestamp":1794230633466}}"#,
        r#"{"type":"message","id":"a","message":{"role":"assistant","content":[],"timestamp":1794230634000,"stopReason":"toolUse","usage":{"input":10,"output":5,"cacheRead":0,"totalTokens":15,"cost":{"total":0.25}}}}"#,
        r#"{"type":"message","id":"r","message":{"role":"toolResult","toolCallId":"t1","toolName":"bash","content":[],"details":{"exitCode":-1,"wallTimeMs":3.5}}}"#,
        // Untrusted numbers: a negative count drops the usage, a bad cost only the cost.
        r#"{"type":"message","id":"b","message":{"role":"assistant","content":[],"usage":{"input":-1,"output":0,"totalTokens":0}}}"#,
        r#"{"type":"message","id":"c","message":{"role":"assistant","content":[],"usage":{"input":1,"output":1,"totalTokens":2,"cost":{"total":-3}}}}"#,
        r#"{"type":"message","id":"h","message":{"role":"custom","display":false,"timestamp":5,"content":"x"}}"#,
    ];
    let text: String = lines.iter().map(|line| format!("{line}\n")).collect();
    let entries = entries(text.as_bytes(), 0);

    assert_eq!(entries[0].timestamp, Some(1_794_230_633_466));
    assert_eq!(
        (entries[0].usage, entries[0].stop_reason.as_deref()),
        (None, None)
    );
    assert_eq!(entries[1].stop_reason.as_deref(), Some("toolUse"));
    assert_eq!(
        entries[1].usage,
        Some(Usage {
            input: 10,
            output: 5,
            total_tokens: 15,
            cost: Some(0.25)
        })
    );
    let Block::ToolResult { exit_code, .. } = &entries[2].blocks[0] else {
        panic!("not a tool result");
    };
    assert_eq!(*exit_code, Some(-1));
    assert_eq!(entries[2].timestamp, None);
    assert_eq!(entries[3].usage, None);
    assert_eq!(entries[4].usage.map(|usage| usage.cost), Some(None));
    assert_eq!((entries[5].kind, entries[5].timestamp), (Kind::Other, None));

    let json = serde_json::to_value(&entries[1]).unwrap();
    assert_eq!(json["usage"]["total_tokens"], 15);
    assert_eq!(json["stop_reason"], "toolUse");
}
