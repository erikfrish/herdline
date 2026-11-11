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

    fn run(&self, fetch: Option<Fetch>) -> (Option<u32>, Vec<u8>) {
        // The fake Herdr leads PATH: `command -v herdr` comes first in the
        // host's candidates, and a machine with Herdr in /usr/bin would run it.
        let bin = self.home.path().join(".local/bin");
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(script("lab", "w1:p1", fetch))
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

fn tail(path: &str) -> Option<Fetch<'_>> {
    Some(Fetch::Lines(Target {
        path,
        read: Read::Tail,
        window: 1024,
    }))
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
            ..Identity::default()
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
    seen.extend(entries("omp", newest, start));
    while start > 0 {
        match read(&content, Read::Before { offset: start }, window) {
            Plan::Lines {
                start: older,
                lines,
                ..
            } => {
                seen.extend(entries("omp", lines, older));
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
    assert_eq!(entries("omp", lines, end)[0].id.as_deref(), Some("half"));
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
    let entries = entries("omp", text.as_bytes(), 100);

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
    let entries = entries("omp", text.as_bytes(), 0);

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

/// The smallest bytes Android takes for a PNG: the signature and some IHDR.
const PNG: &[u8] = b"\x89PNG\r\n\x1a\n\0\0\0\rIHDR";
const HASH: &str = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

/// The OMP process in the pane: the host reads its arguments and
/// environment to find its blob store. A lone shell blocked in the `read`
/// builtin, so it starts no child; stopped and reaped when dropped.
struct Omp(std::process::Child);

impl Drop for Omp {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

impl Host {
    /// A host whose pane shows the session file `s.jsonl` holding `lines`,
    /// run by an OMP started with `args` and, besides HOME, `env`.
    fn with_session(lines: &str, args: &[&str], env: &[(&str, &str)]) -> (Self, Identity, Omp) {
        let host = Host::new("unused");
        let file = host.sessions().join("s.jsonl");
        fs::write(&file, lines).unwrap();
        let path = file.to_str().unwrap().to_owned();
        fs::write(host.home.path().join("pane.json"), pane_json(&path)).unwrap();
        let omp = Command::new("/bin/sh")
            .args(["-c", "read -r line", "omp"])
            .args(args)
            .env_clear()
            .env("HOME", host.home.path())
            .envs(env.iter().copied())
            .stdin(std::process::Stdio::piped())
            .spawn()
            .unwrap();
        let pid = omp.id();
        fs::write(
            host.home.path().join("proc.json"),
            PROCESS.replace("\"pid\":42", &format!("\"pid\":{pid}")),
        )
        .unwrap();
        let identity = Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: Some(pid),
            path,
            ..Identity::default()
        };
        (host, identity, Omp(omp))
    }

    fn store(&self, dir: &str) -> std::path::PathBuf {
        let dir = self.home.path().join(dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// What a media read of `media` answers for the instance `expect`.
    fn media(&self, expect: &Identity, media: &Media) -> MediaOutcome {
        let fetch = Fetch::Image {
            path: &expect.path,
            offset: media.offset,
            len: media.end - media.offset,
            blob: media
                .blob
                .as_deref()
                .map(|hash| (hash, expect.pid.unwrap())),
        };
        let (status, out) = self.run(Some(fetch));
        media_outcome(status, &out, expect, media).unwrap()
    }
}

/// A user message with a text part and, as part 1, an image of `data`.
fn image_line(id: &str, data: &str) -> String {
    format!(
        r#"{{"type":"message","id":"{id}","message":{{"role":"user","content":[{{"type":"text","text":"look"}},{{"type":"image","data":"{data}","mimeType":"image/jpeg"}}]}}}}"#
    ) + "\n"
}

/// A media read of image `part` of `entries[index]`.
fn at(entries: &[Entry], index: usize, part: usize, blob: Option<&str>) -> Media {
    Media {
        v: MEDIA_VERSION,
        entry: entries[index].id.clone(),
        offset: entries[index].offset,
        end: entries[index].end,
        part,
        blob: blob.map(str::to_owned),
    }
}

fn sha(bytes: &[u8]) -> String {
    hex(&HashAlg::Sha256.digest(bytes))
}

fn refused(error: Refusal) -> MediaOutcome {
    MediaOutcome::Refused { error }
}

fn png() -> MediaOutcome {
    MediaOutcome::Image {
        mime: "image/png",
        data: STANDARD.encode(PNG),
    }
}

#[test]
fn a_blob_is_served_only_as_its_entry_names_it_and_only_whole() {
    let names = [
        sha(PNG),
        sha(b"corrupt"),
        sha(b"linked"),
        sha(b"missing"),
        sha(b"big"),
        sha(b"plain text"),
    ];
    let text: String = names
        .iter()
        .enumerate()
        .map(|(index, hash)| image_line(&format!("e{index}"), &format!("blob:sha256:{hash}")))
        .collect();
    let (host, identity, _omp) = Host::with_session(&text, &[], &[]);
    let parsed = entries("omp", text.as_bytes(), 0);
    assert_eq!(
        parsed[0].blocks[1],
        Block::Image(Image {
            part: 1,
            source: Source::Blob {
                hash: names[0].clone()
            }
        })
    );
    let store = host.store(".omp/agent/blobs");
    fs::write(store.join(&names[0]), PNG).unwrap();
    fs::write(store.join(&names[1]), PNG).unwrap();
    let outside = host.home.path().join("secret.png");
    fs::write(&outside, PNG).unwrap();
    std::os::unix::fs::symlink(&outside, store.join(&names[2])).unwrap();
    fs::File::create(store.join(&names[4]))
        .unwrap()
        .set_len(MAX_IMAGE + 1)
        .unwrap();
    fs::write(store.join(&names[5]), "plain text").unwrap();
    let read = |index: usize| host.media(&identity, &at(&parsed, index, 1, Some(&names[index])));

    assert_eq!(read(0), png());
    assert_eq!(read(1), refused(Refusal::Corrupt));
    assert_eq!(read(2), refused(Refusal::Unconfined));
    assert_eq!(read(3), refused(Refusal::Missing));
    assert_eq!(read(4), refused(Refusal::TooLarge));
    assert_eq!(read(5), refused(Refusal::Unsupported));

    // The store holds it, but this entry names another blob.
    let unnamed = at(&parsed, 5, 1, Some(&names[0]));
    assert_eq!(host.media(&identity, &unnamed), refused(Refusal::Moved));
    let other_entry = Media {
        entry: Some("e1".into()),
        ..at(&parsed, 0, 1, Some(&names[0]))
    };
    assert_eq!(host.media(&identity, &other_entry), refused(Refusal::Moved));
    assert_eq!(
        host.media(&identity, &at(&parsed, 0, 0, Some(&names[0]))),
        refused(Refusal::Moved)
    );
    // A blob reference is not read as inline data.
    assert_eq!(
        host.media(&identity, &at(&parsed, 0, 1, None)),
        refused(Refusal::Moved)
    );
}

#[test]
fn the_blob_store_is_the_one_the_omp_process_uses() {
    let hash = sha(PNG);
    let text = image_line("u", &format!("blob:sha256:{hash}"));
    let parsed = entries("omp", text.as_bytes(), 0);
    let media = at(&parsed, 0, 1, Some(&hash));

    // A profile in its arguments: that profile's store, not the default one.
    let (host, identity, _omp) =
        Host::with_session(&text, &["--profile", "qa"], &[("OMP_PROFILE", "other")]);
    fs::write(host.store(".omp/agent/blobs").join(&hash), PNG).unwrap();
    assert_eq!(host.media(&identity, &media), refused(Refusal::Missing));
    fs::write(host.store(".omp/profiles/qa/agent/blobs").join(&hash), PNG).unwrap();
    assert_eq!(host.media(&identity, &media), png());

    let (host, identity, _omp) = Host::with_session(&text, &[], &[("OMP_PROFILE", "qa")]);
    fs::write(host.store(".omp/profiles/qa/agent/blobs").join(&hash), PNG).unwrap();
    assert_eq!(host.media(&identity, &media), png());

    // A layout it cannot pin down is refused, not read from the default store.
    let (host, identity, _omp) = Host::with_session(&text, &[], &[("PI_CONFIG_DIR", ".elsewhere")]);
    fs::write(host.store(".omp/agent/blobs").join(&hash), PNG).unwrap();
    assert_eq!(
        host.media(&identity, &media),
        refused(Refusal::UnknownStore)
    );
}

#[test]
fn an_image_of_another_instance_is_refused() {
    let hash = sha(PNG);
    let text = image_line("u", &format!("blob:sha256:{hash}"));
    let parsed = entries("omp", text.as_bytes(), 0);
    let (host, identity, _omp) = Host::with_session(&text, &[], &[]);
    fs::write(host.store(".omp/agent/blobs").join(&hash), PNG).unwrap();
    let replaced = Identity {
        pid: identity.pid.map(|pid| pid + 1),
        ..identity.clone()
    };
    assert_eq!(
        host.media(&replaced, &at(&parsed, 0, 1, Some(&hash))),
        refused(Refusal::InstanceChanged)
    );
    let other_file = Identity {
        path: identity.path.replace("s.jsonl", "t.jsonl"),
        ..identity
    };
    assert_eq!(
        host.media(&other_file, &at(&parsed, 0, 1, None)),
        refused(Refusal::InstanceChanged)
    );
}

#[test]
fn only_canonical_blob_names_are_kept() {
    for bad in [
        HASH[1..].to_owned(),
        HASH.to_uppercase(),
        "../../../../etc/passwd".to_owned(),
        format!("{}/..", &HASH[..61]),
    ] {
        assert!(!blob_hash(&bad), "{bad}");
        let line = format!(
            r#"{{"type":"message","id":"u","message":{{"role":"user","content":[{{"type":"image","data":"blob:sha256:{bad}"}}]}}}}"#
        );
        let entries = entries("omp", format!("{line}\n").as_bytes(), 0);
        assert_eq!(
            entries[0].blocks,
            vec![Block::Image(Image {
                part: 0,
                source: Source::Unreadable
            })]
        );
    }
}

#[test]
fn inline_images_are_read_from_their_own_line_only() {
    let data = STANDARD.encode(PNG);
    let text = image_line("x", "")
        + &image_line("u", &data)
        + &image_line("bad", "!!!!")
        + &image_line("txt", &STANDARD.encode("plain text"));
    let (host, identity, _omp) = Host::with_session(&text, &[], &[]);
    let parsed = entries("omp", text.as_bytes(), 0);
    let at = |index: usize, part: usize| at(&parsed, index, part, None);

    assert_eq!(
        parsed[1].blocks[1],
        Block::Image(Image {
            part: 1,
            source: Source::Inline {
                bytes: PNG.len() as u64
            }
        })
    );
    assert_eq!(
        parsed[0].blocks[1],
        Block::Image(Image {
            part: 1,
            source: Source::Unreadable
        })
    );
    // The MIME comes from the bytes, not the transcript's claim.
    assert_eq!(host.media(&identity, &at(1, 1)), png());
    assert_eq!(host.media(&identity, &at(1, 0)), refused(Refusal::Moved));
    let wrong_id = Media {
        entry: Some("x".into()),
        ..at(1, 1)
    };
    assert_eq!(host.media(&identity, &wrong_id), refused(Refusal::Moved));
    assert_eq!(
        host.media(&identity, &at(2, 1)),
        refused(Refusal::Unsupported)
    );
    assert_eq!(
        host.media(&identity, &at(3, 1)),
        refused(Refusal::Unsupported)
    );

    // A rewritten file no longer holds the entry where it was.
    fs::write(&identity.path, format!("{{}}\n{text}")).unwrap();
    assert_eq!(host.media(&identity, &at(1, 1)), refused(Refusal::Moved));
}

#[test]
fn entries_record_their_end_and_how_a_user_message_was_steered() {
    let lines = [
        r#"{"type":"message","id":"q","message":{"role":"user","content":"later","steering":true}}"#,
        r#"{"type":"message","id":"l","message":{"role":"user","content":"now","steering":true,"liveSteered":true}}"#,
        r#"{"type":"message","id":"p","message":{"role":"user","content":"plain"}}"#,
        r#"{"type":"message","id":"a","message":{"role":"assistant","content":[],"liveSteered":true}}"#,
    ];
    let text: String = lines.iter().map(|line| format!("{line}\n")).collect();
    let entries = entries("omp", text.as_bytes(), 10);
    assert_eq!(
        (entries[0].offset, entries[0].end),
        (10, 10 + lines[0].len() as u64 + 1)
    );
    assert_eq!(entries[1].offset, entries[0].end);
    assert_eq!(
        entries.iter().map(|entry| entry.steer).collect::<Vec<_>>(),
        [Some(Steer::Steering), Some(Steer::Live), None, None]
    );
    assert_eq!(serde_json::to_value(&entries[1]).unwrap()["steer"], "live");
}

#[test]
fn recorded_form_answers_keep_variants_and_timeout_without_guessing() {
    let line = r#"{"type":"message","id":"r","message":{"role":"toolResult","toolCallId":"ask-1","toolName":"ask","content":[],"details":{"timedOut":true,"answers":[{"name":"db","question":"Database?","answer":"Redis","options":[{"label":"SQLite","selected":false},{"label":"Redis","selected":true}]},{"name":"bad","answer":"missing question"}]}}}"#;
    let parsed = entries("omp", format!("{line}\n").as_bytes(), 0);
    let Block::ToolResult {
        answers, timed_out, ..
    } = &parsed[0].blocks[0]
    else {
        panic!("not a result");
    };
    assert!(*timed_out);
    assert_eq!(answers.len(), 1);
    assert_eq!(answers[0].answer, "Redis");
    assert_eq!(
        answers[0].options,
        vec![
            FormAnswerOption {
                label: "SQLite".into(),
                selected: false
            },
            FormAnswerOption {
                label: "Redis".into(),
                selected: true
            },
        ]
    );
    let other = entries(
        "omp",
        format!(
            "{}\n",
            line.replace("\"toolName\":\"ask\"", "\"toolName\":\"bash\"")
        )
        .as_bytes(),
        0,
    );
    let Block::ToolResult {
        answers, timed_out, ..
    } = &other[0].blocks[0]
    else {
        panic!("not a result");
    };
    assert!(answers.is_empty());
    assert!(!timed_out);
}

#[test]
fn each_engine_reads_only_its_reported_session_and_reopens_its_inline_image() {
    let id = "11111111-2222-4333-8444-555555555555";
    for agent in ["pi", "claude", "codex"] {
        let host = Host::new("unused");
        let (relative, body) = match agent {
            "pi" => (
                ".pi/agent/sessions/work/s.jsonl".to_owned(),
                serde_json::json!({"type":"message","id":"m","message":{"role":"user","content":[{"type":"image","data":STANDARD.encode(PNG)}]}}),
            ),
            "claude" => (
                format!(".claude/projects/work/{id}.jsonl"),
                serde_json::json!({"type":"user","uuid":"m","message":{"role":"user","content":[{"type":"image","source":{"type":"base64","data":STANDARD.encode(PNG)}}]}}),
            ),
            _ => (
                format!(".codex/sessions/2026/11/12/rollout-test-{id}.jsonl"),
                serde_json::json!({"type":"response_item","payload":{"type":"message","id":"m","role":"user","content":[{"type":"input_image","image_url":format!("data:image/png;base64,{}",STANDARD.encode(PNG))}]}}),
            ),
        };
        let path = host.home.path().join(relative);
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let line = format!("{body}\n");
        fs::write(&path, &line).unwrap();
        let reference = if agent == "pi" {
            path.to_str().unwrap()
        } else {
            id
        };
        let mut pane: Value = serde_json::from_str(&pane_json(reference)).unwrap();
        pane["result"]["pane"]["agent"] = agent.into();
        pane["result"]["pane"]["agent_session"] = serde_json::json!({"agent":agent,"source":format!("herdr:{agent}"),"kind":if agent=="pi" {"path"} else {"id"},"value":reference});
        fs::write(host.home.path().join("pane.json"), pane.to_string()).unwrap();
        fs::write(
            host.home.path().join("proc.json"),
            PROCESS.replace("omp", agent),
        )
        .unwrap();
        let (exit, bytes) = host.run(tail(reference));
        let reply = parse_reply(exit, &bytes).unwrap();
        let identity = identity(&reply.pane, &reply.process).unwrap().0;
        let file = reply.file.unwrap();
        assert_eq!(file.data, line.as_bytes(), "{agent}");
        let parsed = entries(agent, file.data, 0);
        let media = Media {
            v: MEDIA_VERSION,
            entry: parsed[0].id.clone(),
            offset: 0,
            end: line.len() as u64,
            part: 0,
            blob: None,
        };
        let (exit, bytes) = host.run(Some(Fetch::Image {
            path: reference,
            offset: 0,
            len: line.len() as u64,
            blob: None,
        }));
        assert!(
            matches!(
                media_outcome(exit, &bytes, &identity, &media).unwrap(),
                MediaOutcome::Image {
                    mime: "image/png",
                    ..
                }
            ),
            "{agent}"
        );
    }
}

#[test]
fn rfc3339_dates_use_real_calendar_zones_and_fractional_seconds() {
    assert_eq!(iso_timestamp("1970-01-01T00:00:00Z"), Some(0));
    assert_eq!(
        iso_timestamp("2026-11-12T03:00:00.1+03:00"),
        Some(1_794_441_600_100)
    );
    for invalid in ["", "short", "2026-02-30T00:00:00Z", "2026-11-12T25:00:00Z"] {
        assert_eq!(iso_timestamp(invalid), None);
    }
}
