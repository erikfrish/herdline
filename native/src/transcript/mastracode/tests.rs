//! FIXTURE-ONLY: every store here is built by the script below from the
//! `mastra_threads`/`mastra_messages` columns of mastra-ai/mastra d432f1e
//! (`packages/core/src/storage/constants.ts:933-949`), with synthetic rows,
//! not captured from a real Mastra Code. Nothing here claims how a live
//! Mastra Code writes; it pins what the reader does with such rows.

use super::*;
use std::{
    fs,
    os::unix::fs::PermissionsExt,
    path::Path,
    path::PathBuf,
    process::Command,
    time::{Duration, Instant},
};

const TID: &str = "thread-0a1b2c3d";
const OTHER: &str = "thread-ffee0011";

/// Builds a store from a JSON spec: thread ids, message rows as column maps,
/// `bulk` [thread, n] filler rows, and `drop` tables to leave out. WAL, as
/// Mastra Code opens it; closing the writer removes both sidecars.
const FIXTURE: &str = r#"import json, sqlite3, sys
db, spec = sys.argv[1], json.loads(sys.argv[2])
conn = sqlite3.connect(db)
conn.execute('PRAGMA journal_mode=WAL')
conn.executescript('''
CREATE TABLE mastra_threads (id TEXT PRIMARY KEY, resourceId TEXT, title TEXT, metadata TEXT, createdAt TEXT, updatedAt TEXT);
CREATE TABLE mastra_messages (id TEXT PRIMARY KEY, thread_id TEXT NOT NULL, content TEXT NOT NULL, role TEXT NOT NULL,
  type TEXT NOT NULL DEFAULT 'v2', createdAt TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP, resourceId TEXT);
''')
for table in spec.get('drop', []): conn.execute('DROP TABLE %s' % table)
for tid in spec.get('threads', []): conn.execute('INSERT INTO mastra_threads (id, resourceId) VALUES (?, ?)', (tid, 'res'))
for row in spec.get('messages', []):
    cols = list(row)
    conn.execute('INSERT INTO mastra_messages (%s) VALUES (%s)' % (','.join(cols), ','.join('?' * len(cols))), [row[c] for c in cols])
if 'bulk' in spec:
    tid, n = spec['bulk']
    conn.executemany('INSERT INTO mastra_messages (id, thread_id, content, role) VALUES (?, ?, ?, ?)',
        (('b%07d' % i, tid, 'x', 'user') for i in range(n)))
conn.commit()
conn.close()
"#;

/// The storage variables the host script takes from its shell, unset for
/// every run so only the ones a test names are seen.
const WANTED: [&str; 10] = [
    "MASTRA_STORAGE_BACKEND",
    "MASTRA_DB_URL",
    "MASTRA_DB_PATH",
    "MASTRA_APP_DATA_DIR",
    "XDG_DATA_HOME",
    "GIT_DIR",
    "GIT_WORK_TREE",
    "GIT_COMMON_DIR",
    "GIT_CEILING_DIRECTORIES",
    "GIT_DISCOVERY_ACROSS_FILESYSTEM",
];

struct Host {
    home: tempfile::TempDir,
    /// The exec shell's own variables beyond HOME and PATH.
    env: std::cell::RefCell<Vec<(String, String)>>,
}

impl Host {
    /// A pane whose Herdr report names `agent` on `tid` with its foreground
    /// process working in `~/work`.
    fn new(tid: &str, agent: &str) -> Self {
        let home = tempfile::tempdir().unwrap();
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap();
        let pane = json!({"id": "cli:pane:get", "result": {"pane": {
            "agent": agent,
            "agent_session": {"agent": agent, "kind": "id", "source": format!("herdr:{agent}"), "value": tid},
            "agent_status": "idle",
            "pane_id": "w1:p1",
            "terminal_id": "term_1",
        }, "type": "pane_info"}});
        fs::write(home.path().join("pane.json"), pane.to_string()).unwrap();
        let host = Self {
            home,
            env: Default::default(),
        };
        host.process(json!({"name": agent, "pid": 42}));
        let herdr = bin.join("herdr");
        fs::write(
            &herdr,
            "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\"; [ ! -f \"$HOME/proc.next\" ] || mv \"$HOME/proc.next\" \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n",
        )
        .unwrap();
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
        host.environ(&[]);
        host.cwd(&host.home.path().join("work"));
        host
    }

    fn process(&self, process: Value) {
        let info = json!({"result": {"process_info": {"foreground_processes": [process], "pane_id": "w1:p1", "shell_pid": 7}}});
        fs::write(self.home.path().join("proc.json"), info.to_string()).unwrap();
    }

    /// The pane's process becomes `process` once the first report is read,
    /// as when the agent is replaced mid-read.
    fn process_then(&self, process: Value) {
        let info = json!({"result": {"process_info": {"foreground_processes": [process], "pane_id": "w1:p1", "shell_pid": 7}}});
        fs::write(self.home.path().join("proc.next"), info.to_string()).unwrap();
    }

    /// The shell's environment: its HOME and these variables.
    fn environ(&self, vars: &[(&str, &str)]) {
        self.env.replace(
            vars.iter()
                .map(|(key, value)| (key.to_string(), value.to_string()))
                .collect(),
        );
    }

    /// Herdr's `foreground_cwd` for the pane, created unless it is meant
    /// to be missing.
    fn cwd(&self, dir: &Path) {
        let file = self.home.path().join("pane.json");
        let mut pane: Value = serde_json::from_str(&fs::read_to_string(&file).unwrap()).unwrap();
        pane["result"]["pane"]["foreground_cwd"] = json!(dir);
        fs::write(file, pane.to_string()).unwrap();
        let _ = fs::create_dir_all(dir);
    }

    /// Mastra Code's default app data dir, `~/.local/share/mastracode`.
    fn data(&self) -> PathBuf {
        self.home.path().join(".local/share/mastracode")
    }

    fn store(&self, db: &Path, spec: Value) -> PathBuf {
        fs::create_dir_all(db.parent().unwrap()).unwrap();
        let status = Command::new("python3")
            .args(["-c", FIXTURE])
            .arg(db)
            .arg(spec.to_string())
            .status()
            .unwrap();
        assert!(status.success());
        db.to_owned()
    }

    fn default_store(&self, spec: Value) -> PathBuf {
        self.store(&self.data().join("mastra.db"), spec)
    }

    fn expect(&self, tid: &str) -> Identity {
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: Some(42),
            path: tid.into(),
            agent: "mastracode".into(),
            source: "herdr:mastracode".into(),
            kind: "id".into(),
        }
    }

    fn page(&self, tid: &str, read: Read) -> Result<Response> {
        let bin = self.home.path().join(".local/bin");
        let mut command = Command::new("/bin/sh");
        for key in WANTED {
            command.env_remove(key);
        }
        let output = command
            .arg("-c")
            .arg(query_script(
                "lab",
                "w1:p1",
                &self.expect(tid),
                &options(read),
            ))
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
            &self.expect(tid),
            false,
        )
    }
}

fn message(thread: &str, id: &str, role: &str, created: &str, content: Value) -> Value {
    json!({"id": id, "thread_id": thread, "role": role, "createdAt": created, "content": content.to_string()})
}

fn said(thread: &str, id: &str, role: &str, created: &str, words: &str) -> Value {
    message(
        thread,
        id,
        role,
        created,
        json!({"format": 2, "parts": [{"type": "text", "text": words}]}),
    )
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

fn refused(result: Result<Response>) -> &'static str {
    match result {
        Err(Error::UnsupportedFormat(format)) => format,
        other => panic!("{other:?}"),
    }
}

/// Runs `script` against the store as Mastra Code's writer would.
fn write(db: &Path, script: &str) {
    let status = Command::new("python3")
        .args(["-c", &format!("import json,sqlite3,sys\nc=sqlite3.connect(sys.argv[1])\n{script}\nc.commit()\nc.close()")])
        .arg(db)
        .arg(TID)
        .status()
        .unwrap();
    assert!(status.success());
}

#[test]
fn reads_only_the_reported_thread_in_created_order_with_ties_broken_by_id() {
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"threads": [TID, OTHER], "messages": [
        said(TID, "m-c", "assistant", "2026-01-01T00:00:03.000Z", "third"),
        // SQLite's CURRENT_TIMESTAMP form: as text it would sort first.
        said(TID, "m-z", "user", "2026-01-01 00:00:02", "second"),
        said(OTHER, "m-o", "user", "2026-01-01T00:00:00.000Z", "other"),
        // Same createdAt: id decides.
        said(TID, "m-b", "user", "2026-01-01T00:00:01.000Z", "first-b"),
        said(TID, "m-a", "user", "2026-01-01T00:00:01.000Z", "first-a"),
        // Not JSON: Mastra Code keeps it as plain text.
        json!({"id": "m-d", "thread_id": TID, "role": "assistant", "createdAt": "2026-01-01T00:00:04.000Z", "content": "plain"}),
    ]}));
    let tail = host.page(TID, Read::Tail).unwrap();
    assert_eq!(
        texts(&tail),
        ["first-a", "first-b", "second", "third", "plain"]
    );
    assert_eq!(tail.entries[0].timestamp, Some(1_767_225_601_000));
    assert_eq!(tail.entries[3].role.as_deref(), Some("assistant"));
    assert!(!tail.has_older);
}

#[test]
fn an_edited_message_keeps_its_place_and_the_next_page_rereads_it() {
    let host = Host::new(TID, "mastracode");
    let db = host.default_store(json!({"threads": [TID], "messages": [
        said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "ask"),
        said(TID, "m-2", "assistant", "2026-01-01T00:00:02.000Z", "part"),
    ]}));
    let tail = host.page(TID, Read::Tail).unwrap();
    // The upsert rewrites content, never createdAt or the row.
    write(
        &db,
        "c.execute(\"UPDATE mastra_messages SET content=? WHERE id='m-2'\",(json.dumps({'format':2,'parts':[{'type':'text','text':'part and more'}]}),))",
    );
    let next = host.page(TID, Read::After { offset: tail.end }).unwrap();
    assert!(!next.reset);
    assert_eq!(texts(&next), ["part and more"]);
    assert_eq!(next.entries[0].id.as_deref(), Some("m-2"));
}

#[test]
fn a_deleted_or_late_older_message_starts_the_next_page_over() {
    let host = Host::new(TID, "mastracode");
    let db = host.default_store(json!({"threads": [TID], "messages": [
        said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "one"),
        said(TID, "m-2", "assistant", "2026-01-01T00:00:02.000Z", "two"),
        said(TID, "m-3", "user", "2026-01-01T00:00:03.000Z", "three"),
    ]}));
    let tail = host.page(TID, Read::Tail).unwrap();
    // A revert deletes the newest, and a new message takes its place.
    write(
        &db,
        "c.execute(\"DELETE FROM mastra_messages WHERE id IN ('m-2','m-3')\")\n\
        c.execute(\"INSERT INTO mastra_messages (id,thread_id,content,role,createdAt) VALUES ('m-4',?,'four','user','2026-01-01T00:00:04.000Z')\",(sys.argv[2],))",
    );
    let next = host.page(TID, Read::After { offset: tail.end }).unwrap();
    assert!(next.reset);
    assert_eq!(texts(&next), ["one", "four"]);
    // A row written late with an older createdAt shifts every later place.
    write(
        &db,
        "c.execute(\"INSERT INTO mastra_messages (id,thread_id,content,role,createdAt) VALUES ('m-0',?,'zero','user','2026-01-01T00:00:00.000Z')\",(sys.argv[2],))",
    );
    let shifted = host.page(TID, Read::After { offset: next.end }).unwrap();
    assert!(shifted.reset);
    assert_eq!(texts(&shifted), ["zero", "one", "four"]);
    let older = host.page(TID, Read::Before { offset: next.start });
    assert!(older.unwrap().reset);
}

#[test]
fn a_page_before_the_first_shown_reads_the_older_messages() {
    let host = Host::new(TID, "mastracode");
    let messages: Vec<Value> = (0..205)
        .map(|i| {
            said(
                TID,
                &format!("m-{i:03}"),
                "user",
                &format!("2026-01-01T00:{:02}:{:02}.000Z", i / 60, i % 60),
                &format!("w{i}"),
            )
        })
        .collect();
    host.default_store(json!({"threads": [TID], "messages": messages}));
    let tail = host.page(TID, Read::Tail).unwrap();
    assert_eq!(tail.entries.len(), 200);
    assert!(tail.has_older);
    let older = host.page(TID, Read::Before { offset: tail.start }).unwrap();
    assert!(!older.reset && !older.has_older);
    assert_eq!(texts(&older), ["w0", "w1", "w2", "w3", "w4"]);
}

#[test]
fn session_init_and_an_unknown_thread_are_not_found_never_the_latest_thread() {
    let host = Host::new("session-init", "mastracode");
    host.default_store(json!({"threads": [OTHER], "messages": [said(OTHER, "m-1", "user", "2026-01-01T00:00:01.000Z", "latest")]}));
    assert!(matches!(
        host.page("session-init", Read::Tail),
        Err(Error::NoTranscript)
    ));
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"threads": [OTHER], "messages": [said(OTHER, "m-1", "user", "2026-01-01T00:00:01.000Z", "latest")]}));
    assert!(matches!(
        host.page(TID, Read::Tail),
        Err(Error::NoTranscript)
    ));
}

#[test]
fn only_user_signals_are_the_users_words() {
    let host = Host::new(TID, "mastracode");
    let signal = |id: &str, kind: &str, words: &str| {
        message(
            TID,
            id,
            "signal",
            "2026-01-01T00:00:01.000Z",
            json!({"format": 2, "parts": [{"type": "text", "text": words}], "metadata": {"signal": {"type": kind}}}),
        )
    };
    let mut typed = message(
        TID,
        "m-4",
        "signal",
        "2026-01-01T00:00:04.000Z",
        json!({"format": 2, "parts": [{"type": "text", "text": "by type"}]}),
    );
    typed["type"] = json!("user-message");
    host.default_store(json!({"threads": [TID], "messages": [
        signal("m-1", "user", "typed by the user"),
        signal("m-2", "reactive", "<system-reminder>"),
        signal("m-3", "notification", "a job finished"),
        typed,
    ]}));
    let page = host.page(TID, Read::Tail).unwrap();
    let roles: Vec<_> = page
        .entries
        .iter()
        .map(|entry| entry.role.as_deref().unwrap())
        .collect();
    assert_eq!(roles, ["user", "system", "system", "user"]);
    // The harness's own signals show their kind, never their content.
    assert_eq!(
        texts(&page),
        [
            "typed by the user",
            "[Mastra Code reactive signal]",
            "[Mastra Code notification signal]",
            "by type"
        ]
    );
}

#[test]
fn a_message_with_nothing_to_show_is_no_entry() {
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"threads": [TID], "messages": [
        said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "ask"),
        message(TID, "m-2", "assistant", "2026-01-01T00:00:02.000Z",
            json!({"format": 2, "parts": [{"type": "step-start"}, {"type": "text", "text": ""}]})),
        message(TID, "m-3", "assistant", "2026-01-01T00:00:03.000Z",
            json!({"format": 2, "parts": [{"type": "text", "text": ""}, {"type": "text", "text": "answer"}]})),
    ]}));
    let page = host.page(TID, Read::Tail).unwrap();
    assert_eq!(page.entries.len(), 2);
    assert_eq!(texts(&page), ["ask", "answer"]);
}

#[test]
fn tool_calls_pair_with_their_result_error_or_denial() {
    let host = Host::new(TID, "mastracode");
    let tool = |call: &str, state: &str, extra: Value| {
        let mut invocation = json!({"toolCallId": call, "toolName": "bash", "args": {"command": "ls"}, "state": state});
        invocation
            .as_object_mut()
            .unwrap()
            .extend(extra.as_object().unwrap().clone());
        json!({"type": "tool-invocation", "toolInvocation": invocation})
    };
    host.default_store(json!({"threads": [TID], "messages": [message(TID, "m-1", "assistant", "2026-01-01T00:00:01.000Z",
        json!({"format": 2, "parts": [
            {"type": "step-start"},
            tool("c1", "result", json!({"result": "a\nb"})),
            tool("c2", "output-error", json!({"errorText": "boom"})),
            tool("c3", "call", json!({})),
            tool("c4", "output-denied", json!({})),
        ]}))]}));
    let page = host.page(TID, Read::Tail).unwrap();
    let [call, result, _, failed, pending, _, denied] = &page.entries[0].blocks[..] else {
        panic!("{page:?}")
    };
    assert!(
        matches!(call, Block::ToolCall { call_id, name, args, .. } if call_id == "c1" && name == "bash" && args.contains("ls"))
    );
    assert!(
        matches!(result, Block::ToolResult { call_id, text, is_error: false, .. } if call_id == "c1" && text == "a\nb")
    );
    assert!(
        matches!(failed, Block::ToolResult { call_id, text, is_error: true, .. } if call_id == "c2" && text == "boom")
    );
    assert!(matches!(pending, Block::ToolCall { call_id, .. } if call_id == "c3"));
    assert!(matches!(denied, Block::ToolResult { call_id, is_error: true, .. } if call_id == "c4"));
}

#[test]
fn images_are_refused_and_file_data_is_never_shipped() {
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"threads": [TID], "messages": [message(TID, "m-1", "signal", "2026-01-01T00:00:01.000Z",
        json!({"format": 2, "metadata": {"signal": {"type": "user"}}, "parts": [
            {"type": "text", "text": "look"},
            {"type": "file", "mediaType": "image/png", "data": "iVBORw0KGgoAAAANSUhEUg=="},
            {"type": "file", "mimeType": "text/plain", "filename": "notes.txt", "data": "VE9QLVNFQ1JFVA=="},
        ], "experimental_attachments": [{"contentType": "image/jpeg", "url": "data:image/jpeg;base64,/9j/4AAQ"}]}))]}));
    let page = host.page(TID, Read::Tail).unwrap();
    assert!(matches!(&page.entries[0].blocks[..], [
        Block::Text { .. },
        Block::Image(Image { source: Source::Unreadable, part: 0 }),
        Block::Text { text, .. },
        Block::Image(Image { source: Source::Unreadable, part: 1 }),
    ] if text == "[file: notes.txt]"));
    let shipped = serde_json::to_string(&page).unwrap();
    assert!(
        !shipped.contains("iVBOR")
            && !shipped.contains("VE9QLVNFQ1JFVA")
            && !shipped.contains("/9j/"),
        "{shipped}"
    );
}

#[test]
fn a_remote_store_is_refused_wherever_it_is_chosen() {
    let host = Host::new(TID, "mastracode");
    // A local store at the default path that must not stand in for any of them.
    host.default_store(json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "default")]}));
    for vars in [
        vec![("MASTRA_STORAGE_BACKEND", "pg")],
        vec![("MASTRA_DB_URL", "libsql://db.example.turso.io")],
        vec![
            ("MASTRA_STORAGE_BACKEND", "libsql"),
            ("MASTRA_DB_URL", "https://db.example"),
        ],
    ] {
        host.environ(&vars);
        assert_eq!(refused(host.page(TID, Read::Tail)), REMOTE, "{vars:?}");
    }
    host.environ(&[]);
    let settings = host.data().join("settings.json");
    fs::write(
        &settings,
        r#"{"storage":{"backend":"pg","pg":{"host":"db"}}}"#,
    )
    .unwrap();
    assert_eq!(refused(host.page(TID, Read::Tail)), REMOTE);
    fs::write(
        &settings,
        r#"{"storage":{"libsql":{"url":"libsql://db.example"}}}"#,
    )
    .unwrap();
    assert_eq!(refused(host.page(TID, Read::Tail)), REMOTE);
    fs::remove_file(&settings).unwrap();
    let global = host.home.path().join(".mastracode/database.json");
    fs::create_dir_all(global.parent().unwrap()).unwrap();
    fs::write(&global, r#"{"url":"libsql://db.example","authToken":"t"}"#).unwrap();
    assert_eq!(refused(host.page(TID, Read::Tail)), REMOTE);
    host.environ(&[("MASTRA_DB_URL", ":memory:")]);
    assert_eq!(refused(host.page(TID, Read::Tail)), IN_MEMORY);
}

#[test]
fn a_process_replaced_mid_read_gives_no_answer_not_even_a_refusal() {
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "hi")]}));
    let replaced = json!({"name": "mastracode", "pid": 43});
    host.process_then(replaced.clone());
    assert!(matches!(
        host.page(TID, Read::Tail),
        Err(Error::NoTranscript)
    ));
    host.process(json!({"name": "mastracode", "pid": 42}));
    host.environ(&[("MASTRA_STORAGE_BACKEND", "pg")]);
    assert_eq!(refused(host.page(TID, Read::Tail)), REMOTE);
    host.process_then(replaced);
    assert!(matches!(
        host.page(TID, Read::Tail),
        Err(Error::NoTranscript)
    ));
}

#[test]
fn a_local_store_named_by_settings_or_database_json_is_followed() {
    let host = Host::new(TID, "mastracode");
    let spec = |words: &str| json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", words)]});
    host.default_store(spec("default"));
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["default"]);
    let picked = host.store(&host.home.path().join("picked/m.db"), spec("settings"));
    fs::write(host.data().join("settings.json"), json!({"storage": {"backend": "libsql", "libsql": {"url": format!("file:{}", picked.display())}}}).to_string()).unwrap();
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["settings"]);
    // Unparseable settings fall back to the defaults, as Mastra Code's do.
    fs::write(host.data().join("settings.json"), "{not json").unwrap();
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["default"]);
    let alt = host.home.path().join("alt");
    host.store(&alt.join("mastracode/mastra.db"), spec("xdg"));
    host.environ(&[("XDG_DATA_HOME", alt.to_str().unwrap())]);
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["xdg"]);
    let path = host.store(&host.home.path().join("db-path.db"), spec("db path"));
    host.environ(&[("MASTRA_DB_PATH", path.to_str().unwrap())]);
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["db path"]);
}

#[test]
fn the_project_database_json_is_found_from_the_agents_own_cwd_and_git_root() {
    let host = Host::new(TID, "mastracode");
    let spec = |words: &str| json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", words)]});
    host.default_store(spec("default"));
    let repo = host.home.path().join("repo");
    let project = host.store(&host.home.path().join("project.db"), spec("project"));
    fs::create_dir_all(repo.join(".mastracode")).unwrap();
    fs::write(
        repo.join(".mastracode/database.json"),
        json!({"url": format!("file:{}", project.display())}).to_string(),
    )
    .unwrap();
    // Below the folder but outside any git work tree, the root is the cwd itself.
    host.cwd(&repo.join("sub"));
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["default"]);
    // In a work tree, the root is its top level (`git rev-parse --show-toplevel`).
    fs::create_dir_all(repo.join(".git")).unwrap();
    fs::write(repo.join(".git/HEAD"), "ref: refs/heads/main\n").unwrap();
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["project"]);
    // extensions.worktreeConfig alone keeps the work tree where it is; a
    // core.worktree in config.worktree moves it.
    fs::write(
        repo.join(".git/config"),
        "[extensions]\n\tworktreeConfig = true\n",
    )
    .unwrap();
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["project"]);
    fs::write(
        repo.join(".git/config.worktree"),
        "[core]\n\tworktree = /elsewhere\n",
    )
    .unwrap();
    assert_eq!(refused(host.page(TID, Read::Tail)), UNKNOWN);
    fs::remove_file(repo.join(".git/config.worktree")).unwrap();
    // Git reads a true core.bare in any of its spellings: no work tree.
    for bare in ["\tbare = yes", "\tBare=On", "\tbare = 1", "\tbare"] {
        fs::write(repo.join(".git/config"), format!("[core]\n{bare}\n")).unwrap();
        assert_eq!(refused(host.page(TID, Read::Tail)), UNKNOWN, "{bare}");
    }
    fs::write(repo.join(".git/config"), "[core]\n\tbare = false\n").unwrap();
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["project"]);
    // An include can set either from a file this does not read.
    for include in [
        "[include]\n\tpath = more",
        "[includeIf \"gitdir:~/\"]\n\tpath = more",
    ] {
        fs::write(repo.join(".git/config"), format!("{include}\n")).unwrap();
        assert_eq!(refused(host.page(TID, Read::Tail)), UNKNOWN, "{include}");
    }
    fs::remove_file(repo.join(".git/config")).unwrap();
    // Git env can move the work tree anywhere: refused, never guessed.
    host.environ(&[("GIT_DIR", "/elsewhere")]);
    assert_eq!(refused(host.page(TID, Read::Tail)), UNKNOWN);
}

#[test]
fn a_work_tree_moved_off_the_cwds_ancestors_is_refused_never_the_default_store() {
    let host = Host::new(TID, "mastracode");
    let spec = |words: &str| json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", words)]});
    host.default_store(spec("default"));
    // The work tree git env names holds the project database.json; nothing
    // above the cwd does.
    let elsewhere = host.home.path().join("elsewhere");
    let other = host.store(&host.home.path().join("other.db"), spec("other"));
    fs::create_dir_all(elsewhere.join(".mastracode")).unwrap();
    fs::write(
        elsewhere.join(".mastracode/database.json"),
        json!({"url": format!("file:{}", other.display())}).to_string(),
    )
    .unwrap();
    host.environ(&[("GIT_WORK_TREE", elsewhere.to_str().unwrap())]);
    assert_eq!(refused(host.page(TID, Read::Tail)), UNKNOWN);
    // A repository's own core.worktree is refused the same way.
    host.environ(&[]);
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["default"]);
    let repo = host.home.path().join("repo");
    fs::create_dir_all(repo.join(".git")).unwrap();
    fs::write(repo.join(".git/HEAD"), "ref: refs/heads/main\n").unwrap();
    fs::write(
        repo.join(".git/config"),
        format!("[core]\n\tworktree = {}\n", elsewhere.display()),
    )
    .unwrap();
    host.cwd(&repo);
    assert_eq!(refused(host.page(TID, Read::Tail)), UNKNOWN);
}

#[test]
fn an_unreadable_agent_cwd_is_refused_never_the_default_store() {
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "default")]}));
    let gone = host.home.path().join("gone");
    host.cwd(&gone);
    fs::remove_dir(&gone).unwrap();
    assert_eq!(refused(host.page(TID, Read::Tail)), UNKNOWN);
}

#[test]
fn a_store_outside_home_is_refused_without_being_opened() {
    let outside = tempfile::tempdir().unwrap();
    let host = Host::new(TID, "mastracode");
    let real = host.store(
        &outside.path().join("mastra.db"),
        json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "outside")]}),
    );
    // Unreadable: opening it would answer "unreadable", not the refusal.
    fs::set_permissions(&real, fs::Permissions::from_mode(0o000)).unwrap();
    host.environ(&[("MASTRA_DB_PATH", real.to_str().unwrap())]);
    assert_eq!(refused(host.page(TID, Read::Tail)), OUTSIDE_HOME);
    let link = host.home.path().join("linked.db");
    std::os::unix::fs::symlink(&real, &link).unwrap();
    let url = format!("file:{}", link.display());
    host.environ(&[("MASTRA_DB_URL", url.as_str())]);
    assert_eq!(refused(host.page(TID, Read::Tail)), OUTSIDE_HOME);
    host.environ(&[("MASTRA_DB_PATH", "relative.db")]);
    assert_eq!(refused(host.page(TID, Read::Tail)), OUTSIDE_HOME);
    fs::set_permissions(&real, fs::Permissions::from_mode(0o600)).unwrap();
}

#[test]
fn a_fifo_at_the_store_path_is_refused_without_waiting() {
    let host = Host::new(TID, "mastracode");
    fs::create_dir_all(host.data()).unwrap();
    assert!(
        Command::new("mkfifo")
            .arg(host.data().join("mastra.db"))
            .status()
            .unwrap()
            .success()
    );
    let started = Instant::now();
    assert_eq!(refused(host.page(TID, Read::Tail)), UNREADABLE);
    assert!(started.elapsed() < Duration::from_secs(10));
}

#[test]
fn a_symlinked_wal_sidecar_is_refused() {
    let host = Host::new(TID, "mastracode");
    let db = host.default_store(json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "hi")]}));
    let elsewhere = host.home.path().join("elsewhere-wal");
    fs::write(&elsewhere, b"").unwrap();
    std::os::unix::fs::symlink(&elsewhere, db.with_file_name("mastra.db-wal")).unwrap();
    assert_eq!(refused(host.page(TID, Read::Tail)), UNREADABLE);
}

#[test]
fn a_wal_store_sqlite_cannot_open_read_only_is_refused_and_left_untouched() {
    let host = Host::new(TID, "mastracode");
    let db = host.default_store(json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "hi")]}));
    let before = fs::read(&db).unwrap();
    // No -wal or -shm, and none can be created: read-only SQLite cannot open
    // the WAL store, and the reader never falls back to an immutable read.
    fs::set_permissions(host.data(), fs::Permissions::from_mode(0o555)).unwrap();
    let result = host.page(TID, Read::Tail);
    fs::set_permissions(host.data(), fs::Permissions::from_mode(0o755)).unwrap();
    assert_eq!(refused(result), UNREADABLE);
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["hi"]);
    assert_eq!(before, fs::read(&db).unwrap());
}

#[test]
fn a_store_without_the_message_table_is_refused() {
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"drop": ["mastra_messages"], "threads": [TID]}));
    assert_eq!(refused(host.page(TID, Read::Tail)), STORE_LAYOUT);
}

#[test]
fn a_thread_past_the_scan_bound_is_refused_before_any_content() {
    let host = Host::new(TID, "mastracode");
    host.default_store(json!({"threads": [TID, OTHER], "bulk": [TID, 100_001]}));
    assert_eq!(refused(host.page(TID, Read::Tail)), TOO_LARGE);
}

#[test]
fn another_agent_reporting_a_mastracode_id_is_never_read_as_mastracode() {
    let host = Host::new(TID, "opencode");
    host.default_store(json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "hi")]}));
    assert!(matches!(
        host.page(TID, Read::Tail),
        Err(Error::NoTranscript)
    ));
}

#[test]
fn a_mastracode_pane_needs_its_own_node_wrapped_process() {
    let report = |process: Value| {
        let pane = json!({"result": {"pane": {"pane_id": "w1:p1", "terminal_id": "term_1", "agent_status": "idle",
            "agent_session": {"agent": "mastracode", "kind": "id", "source": "herdr:mastracode", "value": TID}}}});
        let process = json!({"result": {"process_info": {"foreground_processes": [process]}}});
        super::super::identity(&pane, &process)
            .map(|(identity, _)| identity.pid)
            .unwrap()
    };
    assert_eq!(
        report(
            json!({"name": "node", "pid": 42, "argv": ["node", "/usr/lib/node_modules/mastracode/dist/cli.js"]})
        ),
        Some(42)
    );
    assert_eq!(
        report(
            json!({"name": "node", "pid": 42, "argv": ["node", "/home/u/.local/bin/mastracode"]})
        ),
        Some(42)
    );
    assert_eq!(
        report(json!({"name": "node", "pid": 42, "argv": ["node", "/srv/other/cli.js"]})),
        None
    );
    // The host script must find the same process, or every read is refused.
    let host = Host::new(TID, "mastracode");
    host.process(json!({"name": "node", "pid": 42, "argv": ["node", "/usr/lib/node_modules/mastracode/dist/cli.js"]}));
    host.default_store(json!({"threads": [TID], "messages": [said(TID, "m-1", "user", "2026-01-01T00:00:01.000Z", "hi")]}));
    assert_eq!(texts(&host.page(TID, Read::Tail).unwrap()), ["hi"]);
}
