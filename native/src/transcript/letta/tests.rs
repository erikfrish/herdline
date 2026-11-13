//! FIXTURE-ONLY: pinned Letta local-store schema, no Letta runtime or provider.
use super::*;
use serde_json::json;
use std::{
    fs,
    os::unix::fs::{PermissionsExt, symlink},
    process::Command,
};

struct Host {
    home: tempfile::TempDir,
    folder: std::path::PathBuf,
    expect: Identity,
}

impl Host {
    fn new(reference: &str) -> Self {
        use base64::Engine as _;
        let home = tempfile::tempdir().unwrap();
        let (sid, agent, key) = if let Some(agent) = reference.strip_prefix("default:") {
            ("default", Some(agent), reference.to_owned())
        } else {
            (
                reference,
                Some("agent-a"),
                format!("conversation:{reference}"),
            )
        };
        let root = home.path().join(".letta/lc-local-backend/conversations");
        let folder = root.join(base64::engine::general_purpose::URL_SAFE_NO_PAD.encode(key));
        fs::create_dir_all(&folder).unwrap();
        let mut dir = folder.clone();
        while dir != home.path() {
            fs::set_permissions(&dir, fs::Permissions::from_mode(0o700)).unwrap();
            dir = dir.parent().unwrap().to_owned();
        }
        let write = |name: &str, data: Value| {
            let path = folder.join(name);
            fs::write(&path, data.to_string()).unwrap();
            fs::set_permissions(path, fs::Permissions::from_mode(0o600)).unwrap();
        };
        write(
            "manifest.json",
            json!({"schema_version":2,"message_format":"pi-session-entry-jsonl","provider_stack":"pi-ai"}),
        );
        write(
            "conversation.json",
            json!({"id":sid,"agent_id":agent,"in_context_message_ids":[]}),
        );
        let bin = home.path().join("bin");
        fs::create_dir(&bin).unwrap();
        let herdr = bin.join("herdr");
        fs::write(&herdr, "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/process.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n").unwrap();
        fs::set_permissions(herdr, fs::Permissions::from_mode(0o700)).unwrap();
        let expect = Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "t1".into(),
            pid: Some(42),
            agent: "letta".into(),
            kind: "id".into(),
            source: "herdr:letta".into(),
            path: reference.into(),
        };
        fs::write(home.path().join("pane.json"), json!({"result":{"pane":{"pane_id":"w1:p1","terminal_id":"t1","agent_status":"idle","agent_session":{"agent":"letta","kind":"id","source":"herdr:letta","value":reference}}}}).to_string()).unwrap();
        fs::write(
            home.path().join("process.json"),
            json!({"result":{"process_info":{"foreground_processes":[{"pid":42,"name":"letta"}]}}})
                .to_string(),
        )
        .unwrap();
        Self {
            home,
            folder,
            expect,
        }
    }

    fn messages(&self, rows: &[Value]) {
        let sid = if self.expect.path.starts_with("default:") {
            "default"
        } else {
            &self.expect.path
        };
        let mut text = json!({"type":"session","version":3,"id":sid}).to_string() + "\n";
        for row in rows {
            text.push_str(&row.to_string());
            text.push('\n');
        }
        let path = self.folder.join("messages.jsonl");
        fs::write(&path, text).unwrap();
        fs::set_permissions(path, fs::Permissions::from_mode(0o600)).unwrap();
    }

    fn record(&self, agent: &str, active: &[&str]) {
        let sid = if self.expect.path.starts_with("default:") {
            "default"
        } else {
            &self.expect.path
        };
        fs::write(
            self.folder.join("conversation.json"),
            json!({"id":sid,"agent_id":agent,"in_context_message_ids":active}).to_string(),
        )
        .unwrap();
    }

    fn read(&self, read: Read) -> Result<Response> {
        let result = Command::new("/bin/sh")
            .arg("-c")
            .arg(script("qa", "w1:p1", &self.expect))
            .env_clear()
            .env("HOME", self.home.path())
            .env(
                "PATH",
                format!("{}:/usr/bin:/bin", self.home.path().join("bin").display()),
            )
            .output()
            .unwrap();
        assert!(
            result.status.success(),
            "{}",
            String::from_utf8_lossy(&result.stderr)
        );
        response(
            &serde_json::from_slice(&result.stdout).unwrap(),
            &self.expect,
            read,
            false,
        )
    }
}

fn message(id: &str, text: &str) -> Value {
    json!({"type":"message","id":format!("entry-{id}"),"parentId":null,"timestamp":"2026-11-12T00:00:00Z","message":{"id":id,"role":"user","timestamp":1,"content":[{"type":"text","text":text}]}})
}

fn words(page: &Response) -> Vec<String> {
    page.entries
        .iter()
        .flat_map(|entry| &entry.blocks)
        .filter_map(|block| match block {
            super::super::Block::Text { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

#[test]
fn replacements_rewind_and_foreign_metadata_do_not_mix_conversations() {
    let host = Host::new("conv-a");
    host.messages(&[
        message("a", "old"),
        message("b", "removed"),
        message("a", "new"),
    ]);
    host.record("agent-a", &["a"]);
    assert_eq!(words(&host.read(Read::Tail).unwrap()), ["new"]);
    let mut foreign = message("a", "private");
    foreign["message"]["metadata"] = json!({"conversation_id":"conv-b","agent_id":"agent-a"});
    host.messages(&[foreign]);
    assert!(matches!(
        host.read(Read::Tail),
        Err(Error::UnsupportedFormat("letta_local_format"))
    ));
}

#[test]
fn default_sessions_are_scoped_to_the_reported_agent() {
    let host = Host::new("default:agent-a");
    host.messages(&[message("a", "ours")]);
    assert_eq!(words(&host.read(Read::Tail).unwrap()), ["ours"]);
    host.record("agent-b", &[]);
    assert!(matches!(
        host.read(Read::Tail),
        Err(Error::UnsupportedFormat("letta_local_format"))
    ));
}

#[test]
fn file_links_loose_permissions_and_unsupported_manifests_refuse() {
    let host = Host::new("conv-a");
    host.messages(&[message("a", "safe")]);
    let path = host.folder.join("messages.jsonl");
    fs::set_permissions(&path, fs::Permissions::from_mode(0o644)).unwrap();
    assert!(matches!(
        host.read(Read::Tail),
        Err(Error::TranscriptUnconfined)
    ));
    fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).unwrap();
    let moved = host.home.path().join("other.jsonl");
    fs::rename(&path, &moved).unwrap();
    symlink(&moved, &path).unwrap();
    assert!(matches!(
        host.read(Read::Tail),
        Err(Error::TranscriptUnconfined)
    ));
    fs::remove_file(&path).unwrap();
    fs::rename(moved, &path).unwrap();
    fs::write(
        host.folder.join("manifest.json"),
        json!({"schema_version":1,"message_format":"pi-ai-message-jsonl","provider_stack":"pi-ai"})
            .to_string(),
    )
    .unwrap();
    assert!(matches!(
        host.read(Read::Tail),
        Err(Error::UnsupportedFormat("letta_local_format"))
    ));
}

#[test]
fn paging_is_stable_and_a_rewrite_resets_instead_of_appending() {
    let host = Host::new("conv-a");
    let rows: Vec<Value> = (0..405)
        .map(|n| message(&format!("m{n}"), &format!("turn-{n}")))
        .collect();
    host.messages(&rows);
    let tail = host.read(Read::Tail).unwrap();
    assert_eq!(words(&tail)[0], "turn-205");
    assert!(tail.has_older);
    let older = host.read(Read::Before { offset: tail.start }).unwrap();
    assert_eq!(words(&older)[0], "turn-5");
    assert_eq!(words(&older).last().unwrap(), "turn-204");
    let unchanged = host.read(Read::After { offset: tail.end }).unwrap();
    assert!(unchanged.entries.is_empty());
    assert!(!unchanged.reset);
    host.messages(&[message("new", "fresh")]);
    let reset = host.read(Read::After { offset: tail.end }).unwrap();
    assert!(reset.reset);
    assert_eq!(words(&reset), ["fresh"]);
}

#[test]
fn a_changed_process_refuses_before_transcript_data_is_returned() {
    let host = Host::new("conv-a");
    host.messages(&[message("a", "not ours now")]);
    fs::write(
        host.home.path().join("process.json"),
        json!({"result":{"process_info":{"foreground_processes":[{"pid":43,"name":"letta"}]}}})
            .to_string(),
    )
    .unwrap();
    assert!(matches!(host.read(Read::Tail), Err(Error::NoTranscript)));
}

#[test]
fn agent_free_owner_is_explicit_and_multiple_foregrounds_are_not_guessed() {
    let host = Host::new("conv-a");
    let mut row = message("a", "agent-free");
    row["message"]["metadata"] = json!({"agent_id":"agent-free:conv-a","conversation_id":"conv-a"});
    host.messages(&[row]);
    fs::write(
        host.folder.join("conversation.json"),
        json!({"id":"conv-a","agent_id":null,"agent_free":true,"in_context_message_ids":[]})
            .to_string(),
    )
    .unwrap();
    assert_eq!(words(&host.read(Read::Tail).unwrap()), ["agent-free"]);
    let pane: Value =
        serde_json::from_str(&fs::read_to_string(host.home.path().join("pane.json")).unwrap())
            .unwrap();
    let process =
        json!({"result":{"process_info":{"foreground_processes":[{"pid":42,"name":"letta"}]}}});
    assert_eq!(identity(&pane, &process).unwrap(), Some(host.expect));
    let ambiguous = json!({"result":{"process_info":{"foreground_processes":[{"pid":42,"name":"letta"},{"pid":43,"name":"letta"}]}}});
    assert!(matches!(
        identity(&pane, &ambiguous),
        Err(Error::NoTranscript)
    ));
}
