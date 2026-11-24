use super::*;
use crate::transcript::{BackendSource, Fetch, Probe, Read, Target, parse_reply, reported, script};
use serde_json::json;
use std::fs;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

const ID: &str = "0193a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b";
const HOME: &str = "/home/u";

fn file() -> String {
    format!("{HOME}/.omp/agent/sessions/-work/2026-11-24T10-00-00-000Z_{ID}.jsonl")
}

/// What the collector prints for a shell-started OMP 19.x holding its
/// breadcrumb session's lease in terminal `t9`, pane `w1:p1`.
fn facts() -> Value {
    json!({
        "record": {
            "address": "t9", "state": "live", "pid": 40,
            "foreground": {"pid": 42, "name": "omp"},
            "labels": [], "status": "working",
        },
        "home": HOME,
        "proc": {
            "pid": 42, "ppid": 40, "start": 123_456, "own": true,
            "argv": ["omp"], "env": {"HOME": HOME},
            "tty": "/dev/pts/3", "shell_tty": "/dev/pts/3", "home_real": HOME,
            "locks": [ID], "names": [], "unreadable": [],
            "crumb": format!("/work\n{}\n", file()),
            "file_real": file(),
            "header": {"id": ID, "cwd": "/work"},
        },
    })
}

/// `facts` with the value at `pointer` set, its last key added if new.
fn with(mut facts: Value, pointer: &str, value: Value) -> Value {
    let (parent, key) = pointer.rsplit_once('/').unwrap();
    facts.pointer_mut(parent).unwrap()[key] = value;
    facts
}

#[test]
fn xxh64_matches_the_reference_vectors() {
    assert_eq!(xxh64::hash(b"", 0), 0xef46_db37_51d8_e999);
    assert_eq!(xxh64::hash(b"a", 0), 0xd24e_c4f1_a98c_6e5b);
    assert_eq!(
        xxh64::hash(b"abc", 0x4f4d_502d_4c4f_434b),
        0xc015_1025_cd8a_94a4
    );
    assert_eq!(
        xxh64::hash(b"0123456789abcdef0123456789abcdef!", 0x5049_2d46_494c_454c),
        0x7c3b_18b9_abd4_e96b
    );
    let hundred: Vec<u8> = (0..100).collect();
    assert_eq!(xxh64::hash(&hundred, 7), 0x8065_3e7e_9b88_7cdd);
}

#[test]
fn the_lease_name_is_the_one_omp_18_holds() {
    assert_eq!(lease_name(HOME, "s-1"), "82e7ca236b0ebd94917919b8cae8eb2a");
    // Observed on a live OMP 18.18.8: the socket its process held.
    assert_eq!(
        lease_name(
            "/tmp/omp-spike/home",
            "01a20dfe-7915-7729-ba90-d2039d380ee5"
        ),
        "fcac9a9deb350ddfaf13f9135500f799"
    );
}

#[test]
fn either_lease_form_binds_the_breadcrumb_session_with_its_start_time() {
    let (identity, status) = bind(&facts(), "w1:p1", "t9").unwrap();
    assert_eq!(
        identity,
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "t9".into(),
            pid: Some(42),
            path: file(),
            agent: "omp".into(),
            source: SOURCE.into(),
            kind: "path".into(),
            start: Some(123_456),
        }
    );
    assert_eq!(status, "working");
    let socket = with(facts(), "/proc/locks", json!([]));
    let socket = with(socket, "/proc/names", json!([lease_name(HOME, ID)]));
    assert_eq!(bind(&socket, "w1:p1", "t9").unwrap().0, identity);
}

#[test]
fn a_reused_pid_is_another_instance() {
    let first = bind(&facts(), "w1:p1", "t9").unwrap().0;
    let reused = bind(&with(facts(), "/proc/start", json!(999_999)), "w1:p1", "t9")
        .unwrap()
        .0;
    assert_eq!(reused.pid, first.pid);
    assert_ne!(reused, first);
}

#[test]
fn every_unproved_binding_is_refused_by_its_reason() {
    use Unbound::*;
    let other = format!("{HOME}/.omp/agent/sessions/-work/2026-11-24T11-00-00-000Z_{ID}.jsonl");
    let cases: Vec<(Value, Unbound)> = vec![
        (with(facts(), "/record/state", json!("finished")), NoOmp),
        (with(facts(), "/record/address", json!("t8")), NoOmp),
        (
            with(facts(), "/record/foreground/name", json!("zsh")),
            NoOmp,
        ),
        (with(facts(), "/proc", json!({"gone": true})), NoOmp),
        (
            with(facts(), "/record/foreground/pid", json!(40)),
            LauncherStarted,
        ),
        (
            with(facts(), "/record/labels", json!(["herdline.pid"])),
            Ambiguous,
        ),
        (with(facts(), "/proc/ppid", json!(1)), Ambiguous),
        (with(facts(), "/proc/pid", json!(43)), Ambiguous),
        (with(facts(), "/proc/own", json!(false)), Ambiguous),
        (with(facts(), "/proc/start", Value::Null), Ambiguous),
        (with(facts(), "/proc", Value::Null), Ambiguous),
        (
            with(facts(), "/proc/env/OMP_PROFILE", json!("work")),
            ProfileMismatch,
        ),
        (
            with(facts(), "/proc/env/PI_CODING_AGENT_DIR", json!("/x")),
            ProfileMismatch,
        ),
        (
            with(facts(), "/proc/argv", json!(["omp", "--profile=work"])),
            ProfileMismatch,
        ),
        (
            with(facts(), "/proc/argv", json!(["omp", "--alias", "w"])),
            ProfileMismatch,
        ),
        (
            with(facts(), "/proc/env/HOME", json!("/home/other")),
            ProfileMismatch,
        ),
        (
            with(facts(), "/home", json!("/home/other")),
            ProfileMismatch,
        ),
        (
            with(facts(), "/proc/home_real", json!("/data/u")),
            ProfileMismatch,
        ),
        (with(facts(), "/proc/tty", json!("/dev/pts/4")), TtyMismatch),
        (with(facts(), "/proc/tty", json!("/dev/null")), TtyMismatch),
        (with(facts(), "/proc/shell_tty", Value::Null), TtyMismatch),
        (
            with(
                facts(),
                "/proc/crumb",
                json!(format!("/work\n{}\nfresh\n", file())),
            ),
            FreshOrBadBreadcrumb,
        ),
        (
            with(facts(), "/proc/crumb", json!("/work\n")),
            FreshOrBadBreadcrumb,
        ),
        (
            with(
                facts(),
                "/proc/crumb",
                json!("/work\n/tmp/x_0193a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b.jsonl\n"),
            ),
            FreshOrBadBreadcrumb,
        ),
        (
            with(facts(), "/proc/file_real", json!(other)),
            FreshOrBadBreadcrumb,
        ),
        (
            with(facts(), "/proc/header", Value::Null),
            FreshOrBadBreadcrumb,
        ),
        (
            with(
                facts(),
                "/proc/header/id",
                json!("0193a1b2-c3d4-7e5f-8a9b-000000000000"),
            ),
            FreshOrBadBreadcrumb,
        ),
        (
            with(facts(), "/proc/header/cwd", json!("/elsewhere")),
            FreshOrBadBreadcrumb,
        ),
        (
            with(facts(), "/proc/unreadable", json!(["net/unix"])),
            LeaseUnreadable,
        ),
        (with(facts(), "/proc/locks", json!([])), LeaseNotHeld),
        // An 18.x lease on the same id under another HOME is not this one.
        (
            with(
                with(facts(), "/proc/locks", json!([])),
                "/proc/names",
                json!([lease_name("/home/other", ID)]),
            ),
            LeaseNotHeld,
        ),
        // Another session's 19.x lock is not this one.
        (
            with(
                facts(),
                "/proc/locks",
                json!(["0193a1b2-c3d4-7e5f-8a9b-000000000000"]),
            ),
            LeaseNotHeld,
        ),
    ];
    for (facts, reason) in cases {
        assert_eq!(
            bind(&facts, "w1:p1", "t9").map(|_| ()),
            Err(reason),
            "{facts}"
        );
    }
}

#[test]
fn a_refusal_serializes_as_its_wire_reason() {
    let refused = crate::transcript::Answer::Refused {
        refused: crate::transcript::Refused::Unbound {
            reason: Unbound::LeaseNotHeld,
        },
    };
    assert_eq!(
        serde_json::to_value(refused).unwrap(),
        json!({"refused": {"kind": "unbound", "reason": "lease_not_held"}})
    );
    let detached = crate::transcript::Answer::Detached {
        detached: crate::transcript::Detached::Refused {
            reason: Unbound::FreshOrBadBreadcrumb,
        },
    };
    assert_eq!(
        serde_json::to_value(detached).unwrap(),
        json!({"detached": {"kind": "refused", "reason": "fresh_or_bad_breadcrumb"}})
    );
    let identity = bind(&facts(), "w1:p1", "t9").unwrap().0;
    assert_eq!(serde_json::to_value(&identity).unwrap()["start"], 123_456);
    assert!(
        serde_json::to_value(Identity::default())
            .unwrap()
            .get("start")
            .is_none()
    );
}

/// A scratch HOME with OMP's default folders: session files `a` and `b`
/// and a breadcrumb for fd0 `/dev/null` naming `b`.
struct Scratch {
    home: tempfile::TempDir,
    a: String,
    b: String,
}

impl Scratch {
    fn new() -> Self {
        let home = tempfile::tempdir().unwrap();
        let root = home.path().canonicalize().unwrap();
        let sessions = root.join(".omp/agent/sessions/-work");
        fs::create_dir_all(&sessions).unwrap();
        fs::create_dir_all(root.join(".omp/agent/terminal-sessions")).unwrap();
        fs::create_dir_all(root.join(".omp/run/session-owners")).unwrap();
        let session = |stamp: &str, id: &str, secret: &str| {
            let path = sessions.join(format!("{stamp}_{id}.jsonl"));
            fs::write(
                &path,
                format!(
                    "{}\n{}\n",
                    json!({"type":"session","id":id,"cwd":"/work"}),
                    json!({"type":"message","message":{"role":"user","content":secret}})
                ),
            )
            .unwrap();
            path.to_str().unwrap().to_owned()
        };
        let a = session(
            "2026-11-24T09-00-00-000Z",
            "0193a1b2-c3d4-7e5f-8a9b-aaaaaaaaaaaa",
            "SECRET-A",
        );
        let b = session("2026-11-24T10-00-00-000Z", ID, "SECRET-B");
        fs::write(
            root.join(".omp/agent/terminal-sessions/null"),
            format!("/work\n{b}\n"),
        )
        .unwrap();
        Self { home, a, b }
    }

    fn root(&self) -> std::path::PathBuf {
        self.home.path().canonicalize().unwrap()
    }

    /// A shell whose child holds a WRITE flock on `b`'s 19.x lease file;
    /// the child is the "foreground OMP".
    fn hold(&self) -> (Child, u32, u32) {
        let lock = self
            .root()
            .join(format!(".omp/run/session-owners/{ID}.lock"));
        let hold = format!(
            "import fcntl,time\nf=open({:?},'w')\nfcntl.flock(f,fcntl.LOCK_EX)\nprint('held',flush=True)\ntime.sleep(30)",
            lock.to_str().unwrap()
        );
        let mut shell = Command::new("/bin/sh")
            .arg("-c")
            .arg("python3 -c \"$HOLD\"; :")
            .env("HOLD", hold)
            .env("HOME", self.root())
            .env_remove("OMP_PROFILE")
            .env_remove("PI_PROFILE")
            .env_remove("PI_CODING_AGENT_DIR")
            .env_remove("PI_CONFIG_DIR")
            .env_remove("XDG_STATE_HOME")
            .env_remove("XDG_DATA_HOME")
            .stdin(Stdio::null())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let mut line = String::new();
        std::io::BufRead::read_line(
            &mut std::io::BufReader::new(shell.stdout.as_mut().unwrap()),
            &mut line,
        )
        .unwrap();
        assert_eq!(line, "held\n");
        let program = shell.id();
        let until = Instant::now() + Duration::from_secs(5);
        let child = loop {
            let children =
                fs::read_to_string(format!("/proc/{program}/task/{program}/children")).unwrap();
            if let Some(pid) = children.split_whitespace().next() {
                break pid.parse().unwrap();
            }
            assert!(Instant::now() < until, "no child");
            std::thread::sleep(Duration::from_millis(10));
        };
        (shell, program, child)
    }

    fn record(program: u32, child: u32) -> Value {
        json!({"address":"t9","state":"live","pid":program,"status":{"state":"idle"},
            "foreground":{"pid":child,"name":"omp","argv":["omp"]},"labels":{"other":"x"}})
    }

    fn collect(&self, record: &Value, wanted: Option<&str>) -> (Option<i32>, Value) {
        let mut command = Command::new("python3");
        command.arg("-c").arg(COLLECT).args(["w1:p1", "t9"]);
        command.args(wanted);
        let mut child = command
            .env("HOME", self.root())
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        std::io::Write::write_all(child.stdin.as_mut().unwrap(), record.to_string().as_bytes())
            .unwrap();
        drop(child.stdin.take());
        let output = child.wait_with_output().unwrap();
        (
            output.status.code(),
            serde_json::from_slice(&output.stdout).unwrap(),
        )
    }
}

/// The collector against a real process: its parent, start time, profile,
/// tty, breadcrumb, header and lease, as `bind` then judges them.
#[test]
fn the_collector_reports_a_real_process_and_its_lease() {
    let scratch = Scratch::new();
    let (mut shell, program, child) = scratch.hold();
    let record = Scratch::record(program, child);
    let (status, facts) = scratch.collect(&record, None);
    let _ = shell.kill();
    let _ = shell.wait();
    assert_eq!(status, Some(0));
    let process = facts["proc"].clone();
    assert_eq!(process["ppid"], program);
    assert!(process["start"].as_u64().is_some());
    assert_eq!(process["own"], true);
    assert_eq!(process["locks"], json!([ID]));
    assert_eq!(process["unreadable"], json!([]));
    assert_eq!(process["file_real"], scratch.b.as_str());
    assert_eq!(process["header"], json!({"id": ID, "cwd": "/work"}));
    assert_eq!(facts["record"]["labels"], json!(["other"]));
    // fd0 is /dev/null in a test, not the terminal's pty.
    assert_eq!(
        bind(&facts, "w1:p1", "t9").map(|_| ()),
        Err(Unbound::TtyMismatch)
    );
    let facts = with(facts, "/proc/tty", json!("/dev/pts/7"));
    let facts = with(facts, "/proc/shell_tty", json!("/dev/pts/7"));
    let (identity, status) = bind(&facts, "w1:p1", "t9").unwrap();
    assert_eq!(identity.path, scratch.b);
    assert_eq!(identity.pid, Some(child));
    assert_eq!(identity.start, process["start"].as_u64());
    assert_eq!(status, "idle");
}

#[test]
fn the_collector_stops_a_read_of_a_file_the_breadcrumb_does_not_name() {
    let scratch = Scratch::new();
    let (mut shell, program, child) = scratch.hold();
    let record = Scratch::record(program, child);
    let other = scratch.collect(&record, Some(&scratch.a));
    let same = scratch.collect(&record, Some(&scratch.b));
    let _ = shell.kill();
    let _ = shell.wait();
    assert_eq!(other.0, Some(i32::try_from(EXIT_OTHER_FILE).unwrap()));
    assert_eq!(same.0, Some(0));
    // Nothing but a confirmed foreground pid is probed.
    let (status, facts) = scratch.collect(&with(record.clone(), "/address", json!("t8")), None);
    assert_eq!((status, &facts["proc"]), (Some(0), &Value::Null));
    let (_, facts) = scratch.collect(&with(record, "/foreground/pid", json!(program)), None);
    assert_eq!(facts["proc"], Value::Null);
}

/// An existing core socket that answers one `terminal.get` with `record`.
fn core(socket: &std::path::Path, record: Value) -> std::thread::JoinHandle<()> {
    use std::io::{BufRead, BufReader, Write};
    let listener = std::os::unix::net::UnixListener::bind(socket).unwrap();
    std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap();
        let mut line = String::new();
        BufReader::new(stream.try_clone().unwrap())
            .read_line(&mut line)
            .unwrap();
        let request: Value = serde_json::from_str(&line).unwrap();
        assert_eq!(request["params"]["terminal"], "t9");
        writeln!(
            stream,
            "{}",
            json!({"jsonrpc":"2.0","id":request["id"],"result":record})
        )
        .unwrap();
    })
}

/// The whole probe read, as the host runs it: a read of the shown file `a`
/// after OMP moved to `b` stops with the facts and no byte of either file;
/// the facts name `b`, which a bound caller sees as another instance.
#[test]
fn a_probe_read_after_the_session_moved_returns_no_entries_of_either_file() {
    let scratch = Scratch::new();
    let (mut shell, program, child) = scratch.hold();
    let probe = Probe {
        terminal: "t9".into(),
        session: "lab".into(),
    };
    let run = |path: &str, socket: &str| {
        let socket = scratch.root().join(socket);
        let server = core(&socket, Scratch::record(program, child));
        let fetch = Fetch::Lines(Target {
            path,
            read: Read::Tail,
            window: 4096,
        });
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(script(BackendSource::Probe(&probe), "w1:p1", Some(fetch)))
            .env("HOME", scratch.root())
            .env("HC_SOCKET", &socket)
            .output()
            .unwrap();
        server.join().unwrap();
        (
            output
                .status
                .code()
                .and_then(|code| u32::try_from(code).ok()),
            output.stdout,
        )
    };
    let (moved, moved_out) = run(&scratch.a, "moved.sock");
    let (held, held_out) = run(&scratch.b, "held.sock");
    let _ = shell.kill();
    let _ = shell.wait();
    assert_eq!(moved, Some(EXIT_OTHER_FILE));
    let text = String::from_utf8_lossy(&moved_out);
    assert!(
        !text.contains("SECRET-A") && !text.contains("SECRET-B"),
        "{text}"
    );
    let reply = parse_reply(moved, &moved_out).unwrap();
    assert!(reply.file.is_none());
    // fd0 of a test is /dev/null: the probe refuses, which a bound read
    // turns into a detach, never into a page.
    assert!(matches!(
        reported(
            BackendSource::Probe(&probe),
            "w1:p1",
            &reply.pane,
            &reply.process
        ),
        Err(crate::Error::Unbound(Unbound::TtyMismatch))
    ));
    let pane = with(reply.pane.clone(), "/proc/tty", json!("/dev/pts/7"));
    let pane = with(pane, "/proc/shell_tty", json!("/dev/pts/7"));
    let (now, _) = reported(BackendSource::Probe(&probe), "w1:p1", &pane, &reply.process).unwrap();
    assert_eq!(now.path, scratch.b);
    assert_ne!(now.path, scratch.a);
    // The file the breadcrumb names is read in the same command.
    assert_eq!(held, Some(0));
    let reply = parse_reply(held, &held_out).unwrap();
    let file = reply.file.unwrap();
    assert!(String::from_utf8_lossy(file.data).contains("SECRET-B"));
}

#[test]
fn a_probe_identity_is_never_read_as_a_herdr_report() {
    let probe = Probe {
        terminal: "t9".into(),
        session: "lab".into(),
    };
    assert_eq!(
        reported(
            BackendSource::Probe(&probe),
            "w1:p1",
            &facts(),
            &Value::Null
        )
        .unwrap()
        .0
        .source,
        SOURCE
    );
    assert!(reported(BackendSource::Herdr("lab"), "w1:p1", &facts(), &Value::Null).is_err());
}
