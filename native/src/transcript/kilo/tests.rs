//! FIXTURE-ONLY: every store here is built by the script below from the
//! pinned 7.8.27 DDL (`packages/core/src/session/sql.ts:24-154`, the columns
//! this reader touches), with synthetic rows, not captured from a real Kilo.
//! Nothing here claims how a live Kilo writes; it pins what the reader does
//! with such rows.

use super::*;
use std::{fs, os::unix::fs::PermissionsExt, path::Path, path::PathBuf, process::Command, time::{Duration, Instant}};

const SID: &str = "ses_0a1b2c3d4e5fAbCdEfGhIjKlMn";
const OTHER: &str = "ses_0a1b2c3d4e60ZyXwVuTsRqPoNm";

/// Builds a store from a JSON spec: session, message, part and
/// session_message rows, each a column map; `drop` names tables to leave out.
/// WAL, as Kilo opens it; closing the writer removes both sidecars.
const FIXTURE: &str = r#"import json, sqlite3, sys
db, spec = sys.argv[1], json.loads(sys.argv[2])
conn = sqlite3.connect(db)
conn.execute('PRAGMA journal_mode=WAL')
conn.executescript('''
CREATE TABLE session (id TEXT PRIMARY KEY, project_id TEXT NOT NULL DEFAULT 'prj', slug TEXT NOT NULL DEFAULT 's',
  directory TEXT NOT NULL DEFAULT '/work', title TEXT NOT NULL DEFAULT 't', version TEXT NOT NULL DEFAULT '7.8.27',
  time_created INTEGER NOT NULL DEFAULT 0, time_updated INTEGER NOT NULL DEFAULT 0);
CREATE TABLE message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, time_created INTEGER NOT NULL,
  time_updated INTEGER NOT NULL, data TEXT NOT NULL);
CREATE TABLE part (id TEXT PRIMARY KEY, message_id TEXT NOT NULL, session_id TEXT NOT NULL,
  time_created INTEGER NOT NULL, time_updated INTEGER NOT NULL, data TEXT NOT NULL);
CREATE TABLE session_message (id TEXT PRIMARY KEY, session_id TEXT NOT NULL, type TEXT NOT NULL, seq INTEGER,
  time_created INTEGER NOT NULL, time_updated INTEGER NOT NULL, data TEXT NOT NULL);
''')
for table in spec.get('drop', []): conn.execute('DROP TABLE %s' % table)
for table in ('session', 'message', 'part', 'session_message'):
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
            "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\"; [ ! -f \"$HOME/proc.next\" ] || mv \"$HOME/proc.next\" \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n",
        )
        .unwrap();
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
        Self { home, env: Default::default() }
    }

    /// The pane's foreground Kilo, now (`proc.json`) or once the first report
    /// is read (`proc.next`), as when Kilo is replaced mid-read.
    fn process(&self, file: &str, pid: u32) {
        fs::write(
            self.home.path().join(file),
            format!(r#"{{"result":{{"process_info":{{"foreground_processes":[{{"name":"kilo","pid":{pid}}}],"pane_id":"w1:p1","shell_pid":7}}}}}}"#),
        )
        .unwrap();
    }

    /// The shell's environment: its HOME and these variables.
    fn environ(&self, vars: &[(&str, &str)]) {
        self.env.replace(vars.iter().map(|(key, value)| (key.to_string(), value.to_string())).collect());
    }

    /// Kilo's default data dir, `~/.local/share/kilo`.
    fn data(&self) -> PathBuf {
        self.home.path().join(".local/share/kilo")
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

    fn expect(&self, sid: &str) -> Identity {
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: Some(42),
            path: sid.into(),
            agent: "kilo".into(),
            source: "herdr:kilo".into(),
            kind: "id".into(),
        }
    }

    fn page(&self, sid: &str, read: Read) -> Result<Response> {
        let bin = self.home.path().join(".local/bin");
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(query_script("lab", "w1:p1", &self.expect(sid), &options(read)))
            .env_remove("XDG_DATA_HOME")
            .env_remove("KILO_DB")
            .envs(self.env.borrow().iter().cloned())
            .env("HOME", self.home.path())
            .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
            .output()
            .unwrap();
        assert!(output.status.success(), "{}", String::from_utf8_lossy(&output.stderr));
        response(&serde_json::from_slice(&output.stdout).unwrap(), &self.expect(sid), false)
    }
}

fn session(id: &str) -> Value {
    json!({"id": id})
}

fn message(session: &str, id: &str, data: Value) -> Value {
    json!({"id": id, "session_id": session, "time_created": 1000, "time_updated": 1000, "data": data.to_string()})
}

fn user(session: &str, id: &str) -> Value {
    message(session, id, json!({"role": "user", "time": {"created": 1000}, "agent": "code",
        "model": {"providerID": "p", "modelID": "m"}}))
}

fn assistant(session: &str, id: &str) -> Value {
    message(session, id, json!({"role": "assistant", "time": {"created": 2000}, "parentID": "msg_1",
        "modelID": "m", "providerID": "p", "mode": "code", "agent": "code", "path": {"cwd": "/w", "root": "/w"},
        "cost": 0, "tokens": {"input": 1, "output": 2, "reasoning": 0, "cache": {"read": 0, "write": 0}}}))
}

fn part(session: &str, message: &str, id: &str, data: Value) -> Value {
    json!({"id": id, "message_id": message, "session_id": session, "time_created": 1, "time_updated": 1,
        "data": data.to_string()})
}

fn text(session: &str, message: &str, id: &str, words: &str) -> Value {
    part(session, message, id, json!({"type": "text", "text": words}))
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

#[test]
fn reads_only_the_reported_session_in_order_and_rereads_a_streaming_message() {
    let host = Host::new(SID, "kilo");
    let db = host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID), session(OTHER)],
            "message": [user(SID, "msg_1"), user(OTHER, "msg_o"), assistant(SID, "msg_2")],
            "part": [
                text(SID, "msg_1", "prt_1a", "first"),
                part(SID, "msg_1", "prt_1b", json!({"type": "text", "text": "<context>", "synthetic": true})),
                text(OTHER, "msg_o", "prt_oa", "other"),
                // Inserted out of id order: Kilo lists parts by id.
                text(SID, "msg_2", "prt_2c", "b"),
                part(SID, "msg_2", "prt_2a", json!({"type": "step-start"})),
                text(SID, "msg_2", "prt_2b", "a"),
            ]}),
    );
    let tail = host.page(SID, Read::Tail).unwrap();
    assert_eq!(texts(&tail), ["first", "a", "b"]);
    assert_eq!(tail.entries[1].role.as_deref(), Some("assistant"));
    let append = "import sqlite3,sys;c=sqlite3.connect(sys.argv[1]);c.execute(\"INSERT INTO part VALUES ('prt_2d','msg_2',?,1,1,'{\\\"type\\\":\\\"text\\\",\\\"text\\\":\\\"c\\\"}')\",(sys.argv[2],));c.commit()";
    assert!(Command::new("python3").args(["-c", append]).arg(&db).arg(SID).status().unwrap().success());
    // The last message shown is read again with the part it streamed since.
    let next = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert_eq!(texts(&next), ["a", "b", "c"]);
    assert_eq!(next.entries[0].id.as_deref(), Some("msg_2"));
}

/// Runs `script` against the store as Kilo's writer would, outside the reader.
fn write(db: &Path, script: &str) {
    let status = Command::new("python3")
        .args(["-c", &format!("import json,sqlite3,sys\nc=sqlite3.connect(sys.argv[1])\n{script}\nc.commit()\nc.close()")])
        .arg(db)
        .arg(SID)
        .status()
        .unwrap();
    assert!(status.success());
}

#[test]
fn after_a_revert_the_next_page_starts_over_with_only_current_messages() {
    let host = Host::new(SID, "kilo");
    let db = host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)],
            "message": [user(SID, "msg_1"), assistant(SID, "msg_2"), user(SID, "msg_3")],
            "part": [text(SID, "msg_1", "prt_1", "one"), text(SID, "msg_2", "prt_2", "two"), text(SID, "msg_3", "prt_3", "three")]}),
    );
    let tail = host.page(SID, Read::Tail).unwrap();
    assert_eq!(texts(&tail), ["one", "two", "three"]);
    // Kilo's revert deletes the newest two; two new ones take their places.
    write(
        &db,
        "c.execute(\"DELETE FROM part WHERE message_id IN ('msg_2','msg_3')\")\n\
         c.execute(\"DELETE FROM message WHERE id IN ('msg_2','msg_3')\")\n\
         for mid,words in (('msg_4','four'),('msg_5','five')):\n\
         \x20   c.execute('INSERT INTO message VALUES (?,?,1000,1000,?)',(mid,sys.argv[2],json.dumps({'role':'user'})))\n\
         \x20   c.execute('INSERT INTO part VALUES (?,?,?,1,1,?)',('prt_'+mid,mid,sys.argv[2],json.dumps({'type':'text','text':words})))",
    );
    let next = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert!(next.reset);
    assert_eq!(texts(&next), ["one", "four", "five"]);
    let ids: Vec<_> = next.entries.iter().map(|entry| entry.id.as_deref().unwrap()).collect();
    assert_eq!(ids, ["msg_1", "msg_4", "msg_5"]);
}

fn at(mut message: Value, created: u64) -> Value {
    message["time_created"] = json!(created);
    message
}

#[test]
fn messages_are_read_in_kilos_created_order_and_a_late_older_one_starts_over() {
    let host = Host::new(SID, "kilo");
    // Inserted out of order, as an import or a cloud restore may: Kilo lists
    // by (time_created, id), never by insertion.
    let db = host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)],
            "message": [at(user(SID, "msg_z"), 3000), at(user(SID, "msg_b"), 1000), at(user(SID, "msg_a"), 1000), at(user(SID, "msg_m"), 2000)],
            "part": [text(SID, "msg_z", "prt_z", "late"), text(SID, "msg_b", "prt_b", "b"), text(SID, "msg_a", "prt_a", "a"), text(SID, "msg_m", "prt_m", "mid")]}),
    );
    let tail = host.page(SID, Read::Tail).unwrap();
    assert_eq!(texts(&tail), ["a", "b", "mid", "late"]);
    write(
        &db,
        "c.execute('INSERT INTO message VALUES (?,?,500,500,?)',('msg_0',sys.argv[2],json.dumps({'role':'user'})))\n\
         c.execute('INSERT INTO part VALUES (?,?,?,1,1,?)',('prt_0','msg_0',sys.argv[2],json.dumps({'type':'text','text':'older'})))",
    );
    let next = host.page(SID, Read::After { offset: tail.end }).unwrap();
    assert!(next.reset);
    assert_eq!(texts(&next), ["older", "a", "b", "mid", "late"]);
}

#[test]
fn a_process_replaced_mid_read_gives_no_answer_not_even_a_refusal() {
    let host = Host::new(SID, "kilo");
    host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", "hi")]}),
    );
    host.process("proc.next", 43);
    assert!(matches!(host.page(SID, Read::Tail), Err(Error::NoTranscript)));
    host.process("proc.json", 42);
    host.environ(&[("KILO_DB", ":memory:")]);
    assert_eq!(refused(host.page(SID, Read::Tail)), IN_MEMORY);
    host.process("proc.next", 43);
    assert!(matches!(host.page(SID, Read::Tail), Err(Error::NoTranscript)));
}

#[test]
fn only_the_shells_own_xdg_data_home_places_the_store() {
    let host = Host::new(SID, "kilo");
    let spec = json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", "xdg")]});
    let xdg = host.home.path().join("xdg");
    host.store(&xdg.join("kilo/kilo.db"), spec);
    host.environ(&[("XDG_DATA_HOME", xdg.to_str().unwrap())]);
    assert_eq!(texts(&host.page(SID, Read::Tail).unwrap()), ["xdg"]);
    // Set only for the agent, it is not known here: the default store has
    // no such session, and nothing else is searched.
    host.environ(&[]);
    assert!(matches!(host.page(SID, Read::Tail), Err(Error::NoTranscript)));
}

#[test]
fn a_fifo_at_the_store_path_is_refused_without_waiting() {
    let host = Host::new(SID, "kilo");
    fs::create_dir_all(host.data()).unwrap();
    assert!(Command::new("mkfifo").arg(host.data().join("kilo.db")).status().unwrap().success());
    let started = Instant::now();
    assert_eq!(refused(host.page(SID, Read::Tail)), UNREADABLE);
    assert!(started.elapsed() < Duration::from_secs(10));
}

#[test]
fn a_symlinked_wal_sidecar_is_refused() {
    let host = Host::new(SID, "kilo");
    let db = host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", "hi")]}),
    );
    let elsewhere = host.home.path().join("elsewhere-wal");
    fs::write(&elsewhere, b"").unwrap();
    std::os::unix::fs::symlink(&elsewhere, db.with_file_name("kilo.db-wal")).unwrap();
    assert_eq!(refused(host.page(SID, Read::Tail)), UNREADABLE);
}

#[test]
fn one_message_larger_than_the_page_budget_is_shown_cut_not_failed() {
    let host = Host::new(SID, "kilo");
    let db = host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)], "message": [assistant(SID, "msg_1")]}),
    );
    // 100 KB of CJK text (3 bytes a character) and 150 tool results of 20 KB:
    // far past the 1.5 MiB budget even after each field is clipped.
    write(
        &db,
        "add=lambda pid,data: c.execute('INSERT INTO part VALUES (?,?,?,1,1,?)',(pid,'msg_1',sys.argv[2],json.dumps(data)))\n\
         add('prt_000',{'type':'text','text':'\u{8a9e}'*34000})\n\
         for i in range(150): add('prt_%03d'%(i+1),{'type':'tool','callID':'c%d'%i,'tool':'read','state':{'status':'completed','input':{},'output':'x'*20000}})",
    );
    let page = host.page(SID, Read::Tail).unwrap();
    assert_eq!(page.entries.len(), 1);
    let blocks = &page.entries[0].blocks;
    match &blocks[0] {
        Block::Text { text, truncated } => {
            assert!(*truncated);
            assert!(text.len() <= super::super::TEXT_LIMIT && text.chars().all(|c| c == '\u{8a9e}'));
        }
        other => panic!("{other:?}"),
    }
    let results: Vec<_> = blocks
        .iter()
        .filter_map(|block| match block {
            Block::ToolResult { text, truncated, .. } => Some((text.len(), *truncated)),
            _ => None,
        })
        .collect();
    assert!(!results.is_empty() && results.len() < 150);
    assert!(results.iter().all(|&(len, truncated)| truncated && len <= super::super::RESULT_LIMIT));
    assert!(matches!(blocks.last(), Some(Block::Text { truncated: true, .. })));
}

#[test]
fn a_tool_call_pairs_with_its_result_or_its_error() {
    let host = Host::new(SID, "kilo");
    host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)], "message": [assistant(SID, "msg_1")], "part": [
            part(SID, "msg_1", "prt_1", json!({"type": "tool", "callID": "c1", "tool": "bash", "state":
                {"status": "completed", "input": {"command": "ls"}, "output": "a\nb", "title": "ls", "metadata": {},
                 "time": {"start": 1, "end": 2}}})),
            part(SID, "msg_1", "prt_2", json!({"type": "tool", "callID": "c2", "tool": "read", "state":
                {"status": "error", "input": {"path": "x"}, "error": "boom", "time": {"start": 1, "end": 2}}})),
        ]}),
    );
    let page = host.page(SID, Read::Tail).unwrap();
    let [call, result, failed_call, failed] = &page.entries[0].blocks[..] else { panic!("{page:?}") };
    assert!(matches!(call, Block::ToolCall { call_id, name, args, .. } if call_id == "c1" && name == "bash" && args.contains("ls")));
    assert!(matches!(result, Block::ToolResult { call_id, text, is_error: false, .. } if call_id == "c1" && text == "a\nb"));
    assert!(matches!(failed_call, Block::ToolCall { call_id, .. } if call_id == "c2"));
    assert!(matches!(failed, Block::ToolResult { call_id, text, is_error: true, .. } if call_id == "c2" && text == "boom"));
}

#[test]
fn images_are_refused_and_file_urls_are_never_read() {
    let host = Host::new(SID, "kilo");
    let secret = host.home.path().join("secret.txt");
    fs::write(&secret, "TOP-SECRET").unwrap();
    host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)], "message": [user(SID, "msg_1"), assistant(SID, "msg_2")], "part": [
            part(SID, "msg_1", "prt_1", json!({"type": "file", "mime": "image/png", "filename": "a.png",
                "url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="})),
            part(SID, "msg_1", "prt_2", json!({"type": "file", "mime": "text/plain", "filename": "secret.txt",
                "url": format!("file://{}", secret.display())})),
            part(SID, "msg_2", "prt_3", json!({"type": "tool", "callID": "c1", "tool": "screenshot", "state":
                {"status": "completed", "input": {}, "output": "shot", "title": "s", "metadata": {}, "time": {"start": 1, "end": 2},
                 "attachments": [{"type": "file", "mime": "image/png", "url": "data:image/png;base64,iVBORw0KGgoAAAANSUhEUg=="}]}})),
        ]}),
    );
    let page = host.page(SID, Read::Tail).unwrap();
    assert!(matches!(&page.entries[0].blocks[..], [
        Block::Image(Image { source: Source::Unreadable, .. }),
        Block::Text { text, .. },
    ] if text == "[file: secret.txt]"));
    assert!(matches!(&page.entries[1].blocks[1], Block::ToolResult { images, .. }
        if matches!(&images[..], [Image { source: Source::Unreadable, .. }])));
    let shipped = serde_json::to_string(&page).unwrap();
    assert!(!shipped.contains("TOP-SECRET") && !shipped.contains("iVBOR") && !shipped.contains("file://"), "{shipped}");
}

#[test]
fn a_session_only_in_the_v2_event_store_is_refused_never_empty() {
    let v2 = |sid: &str, seq: Value| {
        json!({"id": format!("smg_{sid}"), "session_id": sid, "type": "user", "seq": seq,
            "time_created": 1, "time_updated": 1, "data": "{\"text\":\"hidden\"}"})
    };
    let host = Host::new(SID, "kilo");
    host.store(&host.data().join("kilo.db"), json!({"session": [session(SID)], "session_message": [v2(SID, json!(1))]}));
    assert_eq!(refused(host.page(SID, Read::Tail)), EVENT_STORE);
    // A row without seq is not the event store's, and a new session is empty.
    let host = Host::new(SID, "kilo");
    host.store(&host.data().join("kilo.db"), json!({"session": [session(SID)], "session_message": [v2(SID, Value::Null)]}));
    assert!(host.page(SID, Read::Tail).unwrap().entries.is_empty());
}

#[test]
fn an_unknown_session_id_is_not_found() {
    let host = Host::new(SID, "kilo");
    host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(OTHER)], "message": [user(OTHER, "msg_1")], "part": [text(OTHER, "msg_1", "prt_1", "other")]}),
    );
    assert!(matches!(host.page(SID, Read::Tail), Err(Error::NoTranscript)));
}

#[test]
fn kilo_db_is_followed_relative_or_absolute_and_memory_is_refused() {
    let spec = |words: &str| {
        json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", words)]})
    };
    let host = Host::new(SID, "kilo");
    host.store(&host.data().join("kilo.db"), spec("default"));
    host.store(&host.data().join("custom.db"), spec("relative"));
    let absolute = host.store(&host.home.path().join("elsewhere/abs.db"), spec("absolute"));
    host.environ(&[("KILO_DB", "custom.db")]);
    assert_eq!(texts(&host.page(SID, Read::Tail).unwrap()), ["relative"]);
    host.environ(&[("KILO_DB", absolute.to_str().unwrap())]);
    assert_eq!(texts(&host.page(SID, Read::Tail).unwrap()), ["absolute"]);
    host.environ(&[("KILO_DB", ":memory:")]);
    assert_eq!(refused(host.page(SID, Read::Tail)), IN_MEMORY);
    let xdg = host.home.path().join("xdg");
    host.store(&xdg.join("kilo/kilo.db"), spec("xdg"));
    host.environ(&[("XDG_DATA_HOME", xdg.to_str().unwrap())]);
    assert_eq!(texts(&host.page(SID, Read::Tail).unwrap()), ["xdg"]);
}

#[test]
fn neither_a_channel_db_nor_an_opencode_db_stands_in_for_kilo_db() {
    let host = Host::new(SID, "kilo");
    let spec = json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", "wrong")]});
    host.store(&host.data().join("kilo-rc.db"), spec.clone());
    host.store(&host.data().join("opencode-local.db"), spec.clone());
    host.store(&host.home.path().join(".local/share/opencode/opencode.db"), spec);
    assert!(matches!(host.page(SID, Read::Tail), Err(Error::NoTranscript)));
}

#[test]
fn a_store_outside_home_is_refused_without_being_opened() {
    let outside = tempfile::tempdir().unwrap();
    let host = Host::new(SID, "kilo");
    let real = host.store(
        &outside.path().join("kilo.db"),
        json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", "outside")]}),
    );
    // Unreadable: opening it would answer "missing", not the refusal.
    fs::set_permissions(&real, fs::Permissions::from_mode(0o000)).unwrap();
    host.environ(&[("KILO_DB", real.to_str().unwrap())]);
    assert_eq!(refused(host.page(SID, Read::Tail)), OUTSIDE_HOME);
    // An in-home name that links outside is refused the same way.
    let link = host.home.path().join("linked.db");
    std::os::unix::fs::symlink(&real, &link).unwrap();
    host.environ(&[("KILO_DB", link.to_str().unwrap())]);
    assert_eq!(refused(host.page(SID, Read::Tail)), OUTSIDE_HOME);
    fs::set_permissions(&real, fs::Permissions::from_mode(0o600)).unwrap();
}

#[test]
fn a_wal_store_sqlite_cannot_open_read_only_is_refused_and_left_untouched() {
    let host = Host::new(SID, "kilo");
    let db = host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", "hi")]}),
    );
    let before = fs::read(&db).unwrap();
    // No -wal or -shm, and none can be created: read-only SQLite cannot open
    // the WAL store, and the reader never falls back to an immutable read.
    fs::set_permissions(host.data(), fs::Permissions::from_mode(0o555)).unwrap();
    let result = host.page(SID, Read::Tail);
    fs::set_permissions(host.data(), fs::Permissions::from_mode(0o755)).unwrap();
    assert_eq!(refused(result), UNREADABLE);
    // A writable dir: SQLite may create the sidecars, but the db bytes stay
    // as they were and no -wal content is ever written.
    assert_eq!(texts(&host.page(SID, Read::Tail).unwrap()), ["hi"]);
    assert_eq!(before, fs::read(&db).unwrap());
    assert!(!fs::metadata(db.with_file_name("kilo.db-wal")).is_ok_and(|wal| wal.len() > 0));
}

#[test]
fn a_store_without_the_v1_tables_is_refused() {
    let host = Host::new(SID, "kilo");
    host.store(&host.data().join("kilo.db"), json!({"drop": ["part"], "session": [session(SID)], "message": [user(SID, "msg_1")]}));
    assert_eq!(refused(host.page(SID, Read::Tail)), STORE_LAYOUT);
}

#[test]
fn another_agent_reporting_a_kilo_id_is_never_read_as_kilo() {
    let host = Host::new(SID, "opencode");
    host.store(
        &host.data().join("kilo.db"),
        json!({"session": [session(SID)], "message": [user(SID, "msg_1")], "part": [text(SID, "msg_1", "prt_1", "kilo")]}),
    );
    assert!(matches!(host.page(SID, Read::Tail), Err(Error::NoTranscript)));
}

#[test]
fn a_kilo_pane_needs_an_exact_session_id_and_its_own_process() {
    let report = |value: &str, process: &str| {
        let pane = json!({"result": {"pane": {"pane_id": "w1:p1", "terminal_id": "term_1", "agent_status": "idle",
            "agent_session": {"agent": "kilo", "kind": "id", "source": "herdr:kilo", "value": value}}}});
        let process = json!({"result": {"process_info": {"foreground_processes": [{"name": process, "pid": 42}]}}});
        super::super::identity(&pane, &process).map(|(identity, _)| identity)
    };
    assert_eq!(report(SID, "kilo-code").unwrap().pid, Some(42));
    assert_eq!(report(SID, "opencode").unwrap().pid, None);
    for value in ["ses_", "sess_abc", "msg_0a1b2c", "ses_a.b", "SES_abc"] {
        assert!(matches!(report(value, "kilo"), Err(Error::NoTranscript)), "{value}");
    }
}

#[test]
fn an_aborted_message_reads_as_a_stop_and_other_errors_keep_their_words() {
    let blocks = |error: Value| {
        entry(&json!({"id": 1, "mid": "msg_1", "info": {"role": "assistant", "error": error}, "parts": []})).unwrap().blocks
    };
    assert_eq!(
        blocks(json!({"name": "MessageAbortedError", "message": "aborted"})),
        [Block::Marker { marker: Marker::Stopped, text: String::new(), truncated: false }]
    );
    assert_eq!(
        blocks(json!({"name": "APIError", "message": "rate limited"})),
        [Block::Text { text: "[rate limited]".to_owned(), truncated: false }]
    );
}
