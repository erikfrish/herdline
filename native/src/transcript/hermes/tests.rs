//! FIXTURE-ONLY: every store here is built by the script below from the
//! pinned v0.23.1 DDL (`hermes_state_common.py:457-651`, the columns this
//! reader touches), not captured from a real Hermes. Nothing here claims how
//! a live Hermes writes; it pins what the reader does with such rows.

use super::*;
use std::{fs, os::unix::fs::PermissionsExt, path::PathBuf, process::Command};

const SID: &str = "20261112_010203_aaaa";
const OTHER: &str = "20261112_010203_bbbb";

/// Builds `state.db` from a JSON spec: `version` rows (no table at all when
/// `version` is null), then sessions, messages and `state_meta` rows, each a
/// column map. WAL, as Hermes opens it.
const FIXTURE: &str = r#"import json, sqlite3, sys
db, spec = sys.argv[1], json.loads(sys.argv[2])
conn = sqlite3.connect(db)
conn.execute('PRAGMA journal_mode=WAL')
conn.executescript('''
CREATE TABLE schema_version (version INTEGER NOT NULL);
CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT NOT NULL, model_config TEXT,
  parent_session_id TEXT, started_at REAL NOT NULL, ended_at REAL, end_reason TEXT);
CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL REFERENCES sessions(id),
  role TEXT NOT NULL, content TEXT, tool_call_id TEXT, tool_calls TEXT, tool_name TEXT, effect_disposition TEXT,
  timestamp REAL NOT NULL, token_count INTEGER, finish_reason TEXT, reasoning TEXT, reasoning_content TEXT,
  reasoning_details TEXT, codex_reasoning_items TEXT, codex_message_items TEXT, platform_message_id TEXT,
  observed INTEGER DEFAULT 0, _compressed_summary INTEGER NOT NULL DEFAULT 0, active INTEGER NOT NULL DEFAULT 1,
  compacted INTEGER NOT NULL DEFAULT 0, api_content TEXT, display_kind TEXT, display_metadata TEXT,
  display_identity BLOB, display_order INTEGER, message_uid TEXT, absorbed_message_uids TEXT,
  tool_call_uids TEXT, tool_call_uid TEXT);
CREATE TABLE state_meta (key TEXT PRIMARY KEY, value TEXT);
''')
for v in spec.get('version') or []: conn.execute('INSERT INTO schema_version VALUES (?)', (v,))
if spec.get('version') is None: conn.execute('DROP TABLE schema_version')
for table in ('sessions', 'messages', 'state_meta'):
    for row in spec.get(table, []):
        cols = list(row)
        conn.execute('INSERT INTO %s (%s) VALUES (%s)' % (table, ','.join(cols), ','.join('?' * len(cols))), [row[c] for c in cols])
conn.commit()
conn.close()
"#;

struct Host {
    home: tempfile::TempDir,
    /// The exec shell's own variables beyond HOME and PATH.
    env: std::cell::RefCell<Vec<(String, String)>>,
}

impl Host {
    /// A pane running Hermes on `sid`.
    fn new(sid: &str) -> Self {
        let home = tempfile::tempdir().unwrap();
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap();
        let pane = json!({"id": "cli:pane:get", "result": {"pane": {
            "agent": "hermes",
            "agent_session": {"agent": "hermes", "kind": "id", "source": "herdr:hermes", "value": sid},
            "agent_status": "idle",
            "pane_id": "w1:p1",
            "terminal_id": "term_1",
        }, "type": "pane_info"}});
        fs::write(home.path().join("pane.json"), pane.to_string()).unwrap();
        fs::write(
            home.path().join("proc.json"),
            r#"{"result":{"process_info":{"foreground_processes":[{"name":"hermes","pid":42}],"pane_id":"w1:p1","shell_pid":7}}}"#,
        )
        .unwrap();
        let herdr = bin.join("herdr");
        fs::write(
            &herdr,
            "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n",
        )
        .unwrap();
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
        Self { home, env: Default::default() }
    }

    fn root(&self) -> PathBuf {
        self.home.path().join(".hermes")
    }

    fn store(&self, root: &std::path::Path, spec: Value) -> PathBuf {
        fs::create_dir_all(root).unwrap();
        let db = root.join("state.db");
        let status = Command::new("python3")
            .args(["-c", FIXTURE])
            .arg(&db)
            .arg(spec.to_string())
            .status()
            .unwrap();
        assert!(status.success());
        db
    }

    fn expect(&self, sid: &str) -> Identity {
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: Some(42),
            path: sid.into(),
            agent: "hermes".into(),
            source: "herdr:hermes".into(),
            kind: "id".into(),
        }
    }

    fn reply(&self, sid: &str, read: Read) -> Value {
        let bin = self.home.path().join(".local/bin");
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(query_script("lab", "w1:p1", &self.expect(sid), &options(read)))
            .env_remove("HERMES_HOME")
            .env_remove("HERMES_DATA_DIR_SUFFIX")
            .envs(self.env.borrow().iter().cloned())
            .env("HOME", self.home.path())
            .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
            .output()
            .unwrap();
        assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
        serde_json::from_slice(&output.stdout).unwrap()
    }

    fn page(&self, sid: &str, read: Read) -> Result<Response> {
        response(&self.reply(sid, read), &self.expect(sid), false)
    }
}

fn session(id: &str) -> Value {
    json!({"id": id, "source": "cli", "started_at": 1.0})
}

fn message(session: &str, role: &str, content: &str) -> Value {
    json!({"session_id": session, "role": role, "content": content, "timestamp": 2.0})
}

fn texts(page: &Response) -> Vec<String> {
    page.entries
        .iter()
        .flat_map(|entry| &entry.blocks)
        .filter_map(|block| match block {
            Block::Text { text, .. } => Some(text.clone()),
            _ => None,
        })
        .collect()
}

#[test]
fn reads_exactly_the_reported_session_among_others() {
    let host = Host::new(SID);
    host.store(
        &host.root(),
        json!({"version": [SCHEMA_VERSION], "sessions": [session(SID), session(OTHER)], "messages": [
            message(OTHER, "user", "other before"),
            message(SID, "user", "mine"),
            message(OTHER, "assistant", "other after"),
        ]}),
    );
    let page = host.page(SID, Read::Tail).unwrap();
    assert_eq!(texts(&page), ["mine"]);
}

#[test]
fn after_resumes_across_a_compaction_without_repeating_a_clone() {
    let host = Host::new(SID);
    let parent = json!({"id": OTHER, "source": "cli", "started_at": 1.0, "end_reason": "compression"});
    let child = json!({"id": SID, "source": "cli", "started_at": 2.0, "parent_session_id": OTHER});
    let uid = |mut row: Value, uid: &str| {
        row["message_uid"] = json!(uid);
        row
    };
    let db = host.store(
        &host.root(),
        json!({"version": [SCHEMA_VERSION], "sessions": [parent, child], "messages": [
            uid(message(OTHER, "user", "first"), "u1"),
            uid(message(OTHER, "assistant", "second"), "u2"),
            uid(message(SID, "assistant", "second"), "u2"),
            uid(message(SID, "user", "third"), "u3"),
        ]}),
    );
    let tail = host.page(SID, Read::Tail).unwrap();
    assert_eq!(texts(&tail), ["first", "second", "third"]);
    // A compression continuation is this conversation: no boundary.
    assert!(tail.entries.iter().all(|entry| entry.kind != Kind::Boundary));
    let caught_up = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert!(caught_up.entries.is_empty());
    Command::new("python3")
        .args([
            "-c",
            "import sqlite3,sys;c=sqlite3.connect(sys.argv[1]);c.execute(\"INSERT INTO messages(session_id,role,content,timestamp) VALUES (?,'assistant','fourth',3.0)\",(sys.argv[2],));c.commit()",
        ])
        .arg(&db)
        .arg(SID)
        .status()
        .unwrap();
    let next = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert_eq!(texts(&next), ["fourth"]);
}

#[test]
fn a_branch_child_shows_only_itself_after_a_boundary() {
    let host = Host::new(SID);
    let parent = json!({"id": OTHER, "source": "cli", "started_at": 1.0, "end_reason": "compression"});
    let fork = json!({"id": SID, "source": "cli", "started_at": 2.0, "parent_session_id": OTHER,
        "model_config": json!({"_branched_from": OTHER}).to_string()});
    host.store(
        &host.root(),
        json!({"version": [SCHEMA_VERSION], "sessions": [parent, fork], "messages": [
            message(OTHER, "user", "parent turn"),
            message(SID, "user", "branch turn"),
        ]}),
    );
    let page = host.page(SID, Read::Tail).unwrap();
    assert_eq!(page.entries[0].kind, Kind::Boundary);
    assert_eq!(texts(&page), ["branch turn"]);
    // An `after` page never repeats the boundary below newer rows.
    let after = host.page(SID, Read::After { offset: 0 }).unwrap();
    assert!(after.entries.iter().all(|entry| entry.kind != Kind::Boundary));
}

#[test]
fn another_schema_format_is_refused_with_both_formats() {
    for found in [SCHEMA_VERSION - 1, SCHEMA_VERSION + 1] {
        let host = Host::new(SID);
        host.store(
            &host.root(),
            json!({"version": [found], "sessions": [session(SID)], "messages": [message(SID, "user", "hidden")]}),
        );
        match host.page(SID, Read::Tail) {
            Err(Error::UnsupportedHermes { found: got, supported }) => {
                assert_eq!(got, Some(found));
                assert_eq!(supported, SCHEMA_VERSION);
            }
            other => panic!("{found}: {other:?}"),
        }
    }
}

#[test]
fn a_store_naming_no_single_format_is_refused_without_one() {
    for version in [Value::Null, json!([SCHEMA_VERSION, SCHEMA_VERSION])] {
        let host = Host::new(SID);
        host.store(
            &host.root(),
            json!({"version": version, "sessions": [session(SID)], "messages": [message(SID, "user", "hidden")]}),
        );
        match host.page(SID, Read::Tail) {
            Err(Error::UnsupportedHermes { found, supported }) => {
                assert_eq!(found, None);
                assert_eq!(supported, SCHEMA_VERSION);
            }
            other => panic!("{version}: {other:?}"),
        }
    }
}

#[test]
fn a_store_linked_outside_home_is_refused() {
    let outside = tempfile::tempdir().unwrap();
    let host = Host::new(SID);
    let real = outside.path().join("hermes");
    host.store(
        &real,
        json!({"version": [SCHEMA_VERSION], "sessions": [session(SID)], "messages": [message(SID, "user", "outside")]}),
    );
    let link = host.home.path().join("linked");
    std::os::unix::fs::symlink(&real, &link).unwrap();
    // HERMES_HOME names the in-home link; its target is outside HOME.
    host.env.replace(vec![("HERMES_HOME".into(), link.display().to_string())]);
    assert!(matches!(host.page(SID, Read::Tail), Err(Error::TranscriptUnconfined)));
}

#[test]
fn reading_leaves_the_store_untouched() {
    let host = Host::new(SID);
    let db = host.store(
        &host.root(),
        json!({"version": [SCHEMA_VERSION], "sessions": [session(SID)], "messages": [message(SID, "user", "hi")]}),
    );
    let before = (fs::read(&db).unwrap(), fs::metadata(&db).unwrap().modified().unwrap());
    host.page(SID, Read::Tail).unwrap();
    assert_eq!(before, (fs::read(&db).unwrap(), fs::metadata(&db).unwrap().modified().unwrap()));
    for sidecar in ["-wal", "-shm", "-journal"] {
        assert!(!host.root().join(format!("state.db{sidecar}")).exists(), "{sidecar}");
    }
}

#[test]
fn decodes_parts_and_fails_closed_on_malformed_rows() {
    let host = Host::new(SID);
    let parts = format!(
        "\u{0}json:{}",
        json!([{"type": "text", "text": "look"}, {"type": "image_url", "image_url": {"url": "data:x"}}, {"type": "file"}])
    );
    host.store(
        &host.root(),
        json!({"version": [SCHEMA_VERSION], "sessions": [session(SID)], "messages": [
            message(SID, "user", &parts),
            message(SID, "user", "\u{0}json:{not json"),
            message(SID, "critic", "odd"),
        ]}),
    );
    let page = host.page(SID, Read::Tail).unwrap();
    let [parts, malformed, unknown] = &page.entries[..] else { panic!("{page:?}") };
    assert!(matches!(&parts.blocks[1], Block::Image(Image { source: Source::Unreadable, .. })));
    assert!(matches!(&parts.blocks[2], Block::Text { text, .. } if text == "[file]"));
    assert!(matches!(&malformed.blocks[..], [Block::Text { text, .. }] if text == "\u{0}json:{not json"));
    assert!(matches!(&unknown.blocks[0], Block::Text { text, .. } if text.contains("critic")));
}
