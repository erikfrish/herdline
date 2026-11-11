use super::*;
use std::{
    fs,
    io::{BufRead, BufReader, Write},
    os::unix::{fs::PermissionsExt, net::UnixListener},
    path::{Path, PathBuf},
    process::Command,
    thread,
};

const SESSION_FILE: &str = "/home/u/.omp/agent/sessions/-work/s.jsonl";
const PID: u32 = 42;
const ID: &str = "0b5d3c1e-8f2a-4c6d-9e7b-1a2b3c4d5e6f";
const NONCE: &str = "n0nce-0123456789abcdef";

fn pane_json() -> String {
    json!({"id": "cli:pane:get", "result": {"pane": {
        "agent": "omp",
        "agent_session": {"agent": "omp", "kind": "path", "source": "herdr:omp", "value": SESSION_FILE},
        "agent_status": "working",
        "pane_id": "w1:p1",
        "terminal_id": "term_1",
    }, "type": "pane_info"}})
    .to_string()
}

const PROCESS: &str = r#"{"result":{"process_info":{"foreground_processes":[{"name":"omp","pid":42}],"pane_id":"w1:p1","shell_pid":7}}}"#;

fn expected() -> Identity {
    Identity {
        pane_id: "w1:p1".into(),
        terminal_id: "term_1".into(),
        pid: Some(PID),
        path: SESSION_FILE.into(),
        ..Identity::default()
    }
}

/// A scratch host: a fake Herdr, a fake `/proc/<pid>/environ` for the
/// agent, and its runtime directory.
struct Host {
    home: tempfile::TempDir,
}

impl Host {
    fn new() -> Self {
        let home = tempfile::tempdir().unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        let bin = home.path().join(".local/bin");
        fs::create_dir_all(&bin).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        fs::write(home.path().join("pane.json"), pane_json()).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        fs::write(home.path().join("proc.json"), PROCESS).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        let herdr = bin.join("herdr");
        fs::write(
            &herdr,
            "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n",
        ).unwrap_or_else(|error| panic!("{}: {:?}", "valid test fixture or expected ingress reply", error));
        fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        let host = Self { home };
        host.environ(&format!(
            "HOME=/home/u\0XDG_RUNTIME_DIR={}\0",
            host.runtime().display()
        ));
        fs::create_dir_all(host.runtime()).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        host
    }

    fn runtime(&self) -> PathBuf {
        self.home.path().join("run")
    }

    fn dir(&self) -> PathBuf {
        self.runtime().join(DIR_NAME)
    }

    fn environ(&self, content: &str) {
        let proc = self.home.path().join(format!("proc/{PID}"));
        fs::create_dir_all(&proc).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        fs::write(proc.join("environ"), content).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
    }

    /// The directory the extension makes, private.
    fn publish_dir(&self) -> PathBuf {
        let dir = self.dir();
        fs::create_dir_all(&dir).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        fs::set_permissions(&dir, fs::Permissions::from_mode(0o700)).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        dir
    }

    /// The sidecar the extension writes for session `sid`.
    fn publish(&self, sid: &str, sidecar: &Value) -> PathBuf {
        let path = self.publish_dir().join(format!("{sid}-{PID}.json"));
        fs::write(&path, sidecar.to_string()).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        fs::set_permissions(&path, fs::Permissions::from_mode(0o600)).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        path
    }

    fn sh(&self, script: &str) -> (Option<u32>, Vec<u8>) {
        let bin = self.home.path().join(".local/bin");
        let output = Command::new("/bin/sh")
            .arg("-c")
            .arg(script)
            .env("HOME", self.home.path())
            .env("PATH", format!("{}:/usr/bin:/bin", bin.display()))
            .output()
            .unwrap_or_else(|error| {
                panic!(
                    "{}: {:?}",
                    "valid test fixture or expected ingress reply", error
                )
            });
        let status = output
            .status
            .code()
            .and_then(|code| u32::try_from(code).ok());
        (status, output.stdout)
    }

    fn discover(&self, expected: &Identity) -> Discovery {
        let proc = self.home.path().join("proc");
        let (status, bytes) = self.sh(&discovery_script(
            "lab",
            "w1:p1",
            PID,
            proc.to_str()
                .unwrap_or_else(|| panic!("{}", "valid test fixture or expected ingress reply")),
        ));
        parse_discovery(status, &bytes, expected)
    }
}

fn sidecar_json(sid: &str) -> Value {
    json!({"v": 1, "sessionId": sid, "sessionFile": SESSION_FILE, "pid": PID, "nonce": NONCE})
}

#[test]
fn an_agent_without_a_runtime_directory_has_no_endpoint() {
    let host = Host::new();
    host.environ("HOME=/home/u\0");
    assert_eq!(host.discover(&expected()), Discovery::Absent);
}

#[test]
fn no_extension_directory_or_no_sidecar_for_this_pid_is_absent() {
    let host = Host::new();
    assert_eq!(host.discover(&expected()), Discovery::Absent);
    // Another OMP's endpoint in the same directory is not this one's.
    let other = host.publish_dir().join("s1-77.json");
    fs::write(&other, "{}").unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(host.discover(&expected()), Discovery::Absent);
}

#[test]
fn an_endpoint_that_is_there_but_not_private_refuses_instead_of_falling_back() {
    let host = Host::new();
    let sidecar = host.publish("s1", &sidecar_json("s1"));
    fs::set_permissions(host.dir(), fs::Permissions::from_mode(0o755)).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(host.discover(&expected()), Discovery::Unusable("dir"));

    fs::set_permissions(host.dir(), fs::Permissions::from_mode(0o700)).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    fs::set_permissions(&sidecar, fs::Permissions::from_mode(0o644)).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(host.discover(&expected()), Discovery::Unusable("sidecar"));
}

#[test]
fn a_linked_sidecar_is_not_trusted() {
    let host = Host::new();
    let real = host.home.path().join("elsewhere.json");
    fs::write(&real, sidecar_json("s1").to_string()).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    fs::set_permissions(&real, fs::Permissions::from_mode(0o600)).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    std::os::unix::fs::symlink(&real, host.publish_dir().join(format!("s1-{PID}.json")))
        .unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
    assert_eq!(host.discover(&expected()), Discovery::Unusable("sidecar"));
}

#[test]
fn two_sidecars_for_one_pid_are_ambiguous() {
    let host = Host::new();
    host.publish("s1", &sidecar_json("s1"));
    host.publish("s2", &sidecar_json("s2"));
    assert_eq!(host.discover(&expected()), Discovery::Unusable("many"));
}

#[test]
fn a_private_sidecar_for_this_instance_names_its_socket() {
    let host = Host::new();
    let path = host.publish("s1", &sidecar_json("s1"));
    let Discovery::Found(endpoint) = host.discover(&expected()) else {
        panic!("endpoint not found");
    };
    assert_eq!(
        endpoint.sidecar,
        path.to_str()
            .unwrap_or_else(|| panic!("test path is UTF-8"))
    );
    assert_eq!(
        endpoint.socket,
        host.dir()
            .join(format!("s1-{PID}.sock"))
            .to_str()
            .unwrap_or_else(|| panic!("test path is UTF-8"))
    );
    assert_eq!(endpoint.nonce, NONCE);
    assert_eq!(endpoint.session_id, "s1");
}

#[test]
fn another_instance_in_the_pane_is_refused_before_any_endpoint_is_used() {
    let host = Host::new();
    host.publish("s1", &sidecar_json("s1"));
    let mut draft_for = expected();
    draft_for.terminal_id = "term_0".into();
    assert_eq!(
        host.discover(&draft_for),
        Discovery::Refused(Refusal::AgentRestarted)
    );
}

#[test]
fn a_sidecar_from_another_version_session_or_process_is_unusable() {
    let mut newer = sidecar_json("s1");
    newer["v"] = json!(2);
    assert_eq!(sidecar_at("s1", &newer), Discovery::Unusable("version"));

    let mut switched = sidecar_json("s1");
    switched["sessionFile"] = json!("/home/u/.omp/agent/sessions/-work/t.jsonl");
    assert_eq!(
        sidecar_at("s1", &switched),
        Discovery::Unusable("session_file")
    );

    let mut other = sidecar_json("s1");
    other["pid"] = json!(43);
    assert_eq!(sidecar_at("s1", &other), Discovery::Unusable("sidecar"));

    // The file's name and the session it claims must agree.
    assert_eq!(
        sidecar_at("s2", &sidecar_json("s1")),
        Discovery::Unusable("sidecar")
    );
}

#[test]
fn an_extension_on_an_omp_without_its_hooks_says_so() {
    let mut sidecar = sidecar_json("s1");
    sidecar["unsupported"] = json!(["getQueuedMessages", "bad name\n"]);
    assert_eq!(
        sidecar_at("s1", &sidecar),
        Discovery::Unsupported(vec!["getQueuedMessages".into()])
    );
}

fn sidecar_at(name: &str, value: &Value) -> Discovery {
    sidecar(
        &format!("/run/user/1/{DIR_NAME}/{name}-{PID}.json"),
        &value.to_string(),
        PID,
        SESSION_FILE,
    )
}

/// An endpoint in `host` with a real socket served by `serve`, which gets
/// the one line the client sent.
fn served(
    host: &Host,
    serve: impl FnOnce(String, &mut std::os::unix::net::UnixStream) + Send + 'static,
) -> (Endpoint, thread::JoinHandle<()>) {
    let sidecar = host.publish("s1", &sidecar_json("s1"));
    let socket = host.dir().join(format!("s1-{PID}.sock"));
    let listener = UnixListener::bind(&socket).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    fs::set_permissions(&socket, fs::Permissions::from_mode(0o600)).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    let server = thread::spawn(move || {
        let (mut stream, _) = listener.accept().unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        let mut line = String::new();
        BufReader::new(
            stream
                .try_clone()
                .unwrap_or_else(|error| panic!("{}: {:?}", "clone the test socket", error)),
        )
        .read_line(&mut line)
        .unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        serve(line, &mut stream);
    });
    (endpoint(&sidecar, &socket), server)
}

fn endpoint(sidecar: &Path, socket: &Path) -> Endpoint {
    Endpoint {
        sidecar: sidecar
            .to_str()
            .unwrap_or_else(|| panic!("{}", "valid test fixture or expected ingress reply"))
            .into(),
        socket: socket
            .to_str()
            .unwrap_or_else(|| panic!("{}", "valid test fixture or expected ingress reply"))
            .into(),
        content: fs::read_to_string(sidecar).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        }),
        session_id: "s1".into(),
        session_file: SESSION_FILE.into(),
        pid: PID,
        nonce: NONCE.into(),
    }
}

fn exchange(host: &Host, endpoint: &Endpoint, line: &str) -> Exchange {
    let (status, bytes) = host.sh(&exchange_script(endpoint, line, ID));
    parse_exchange(status, &bytes, ID)
}

#[test]
fn the_answer_is_the_line_naming_the_request_after_any_pushes() {
    let host = Host::new();
    let (tx, rx) = std::sync::mpsc::channel();
    let (endpoint, server) = served(&host, move |line, stream| {
        tx.send(line).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        // A push and another request's answer come first; the client keeps
        // its side open until its own answer arrives.
        writeln!(stream, r#"{{"queue":[],"liveSteered":[]}}"#).unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        writeln!(
            stream,
            r#"{{"id":"11111111-2222-3333-4444-555555555555","outcome":"accepted"}}"#
        )
        .unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        writeln!(
            stream,
            r#"{{"id":"{ID}","outcome":"accepted","state":{{"idle":false,"queued":[]}}}}"#
        )
        .unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
    });
    let line = json!({"v": 1, "id": ID, "op": "status"}).to_string();
    let answer = exchange(&host, &endpoint, &line);
    server.join().unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(
        rx.recv()
            .unwrap_or_else(|error| panic!("receive test request: {error}")),
        format!("{line}\n")
    );
    let Exchange::Answer(answer) = answer else {
        panic!("no answer: {answer:?}");
    };
    assert_eq!(answer["outcome"], "accepted");
}

#[test]
fn a_connection_closed_without_an_answer_is_no_answer() {
    let host = Host::new();
    let (endpoint, server) = served(&host, |_, _| {});
    let answer = exchange(&host, &endpoint, &json!({"v": 1, "id": ID}).to_string());
    server.join().unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(answer, Exchange::NoAnswer);
}

#[test]
fn a_sidecar_replaced_since_discovery_sends_nothing() {
    let host = Host::new();
    let sidecar = host.publish("s1", &sidecar_json("s1"));
    let socket = host.dir().join(format!("s1-{PID}.sock"));
    let listener = UnixListener::bind(&socket).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    fs::set_permissions(&socket, fs::Permissions::from_mode(0o600)).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    let endpoint = endpoint(&sidecar, &socket);
    let mut restarted = sidecar_json("s1");
    restarted["nonce"] = json!("another-nonce-0123456789");
    host.publish("s1", &restarted);
    assert_eq!(exchange(&host, &endpoint, "{}"), Exchange::Stale);
    listener.set_nonblocking(true).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert!(listener.accept().is_err(), "nothing may connect");
}

#[test]
fn a_socket_nobody_listens_on_sends_nothing() {
    let host = Host::new();
    let sidecar = host.publish("s1", &sidecar_json("s1"));
    let socket = host.dir().join(format!("s1-{PID}.sock"));
    // A crashed extension leaves its socket file behind.
    drop(UnixListener::bind(&socket).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    }));
    fs::set_permissions(&socket, fs::Permissions::from_mode(0o600)).unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(
        exchange(&host, &endpoint(&sidecar, &socket), "{}"),
        Exchange::Unreachable
    );
}

#[test]
fn only_a_refusal_with_its_reason_says_nothing_was_queued() {
    let reply = |answer: Value| -> Value {
        serde_json::from_str(
            &answer_reply(Op::Send, ID, None, &answer).unwrap_or_else(|| {
                panic!("{}", "ingress reply satisfies the expected protocol shape")
            }),
        )
        .unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        })
    };
    let refused = reply(json!({"id": ID, "outcome": "refused", "reason": "dialog_open"}));
    assert_eq!(refused["outcome"], "refused");
    assert_eq!(refused["reason"], "dialog_open");
    assert_eq!(
        reply(json!({"id": ID, "outcome": "refused"}))["outcome"],
        "uncertain"
    );
    assert_eq!(
        reply(json!({"id": ID, "outcome": "queued?"}))["outcome"],
        "uncertain"
    );
    assert_eq!(
        reply(json!({"id": ID, "outcome": "replayed"}))["outcome"],
        "replayed"
    );
    let admitted = reply(json!({"id": ID, "outcome": "admitted"}));
    assert_eq!(admitted["outcome"], "admitted");
    assert!(admitted.get("reason").is_none());
}

#[test]
fn the_queue_keeps_only_entries_of_the_documented_shape() {
    let answer = json!({"id": ID, "outcome": "uncertain", "state": {"idle": false, "queued": [
        {"requestId": ID, "text": "a", "queue": "steering"},
        {"requestId": null, "text": "from the desktop", "queue": "followUp"},
        {"requestId": ID, "text": "b", "queue": "elsewhere"},
        {"text": 3, "queue": "steering"},
    ]}});
    let reply: Value = serde_json::from_str(
        &answer_reply(Op::State, ID, None, &answer)
            .unwrap_or_else(|| panic!("{}", "ingress reply satisfies the expected protocol shape")),
    )
    .unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(
        reply,
        json!({"endpoint": "live", "state": {"idle": false, "queued": [
            {"requestId": ID, "text": "a", "queue": "steering"},
            {"requestId": null, "text": "from the desktop", "queue": "followUp"},
        ]}})
    );
    assert!(answer_reply(Op::State, ID, None, &json!({"id": ID})).is_none());
}

#[test]
fn a_withdraw_answer_must_say_whether_it_withdrew() {
    let reply: Value = serde_json::from_str(
        &answer_reply(
            Op::Withdraw,
            ID,
            None,
            &json!({"id": ID, "withdrawn": true}),
        )
        .unwrap_or_else(|| panic!("{}", "ingress reply satisfies the expected protocol shape")),
    )
    .unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(reply["withdrawn"], true);
    assert!(answer_reply(Op::Withdraw, ID, None, &json!({"id": ID})).is_none());
}

#[test]
fn the_dialog_snapshot_passes_through_as_omp_gave_it() {
    let dialogs = json!([{"dialogId": "d1", "kind": "form", "source": "ask", "form": {"fields": []}, "createdAt": 5}]);
    let reply: Value = serde_json::from_str(
        &answer_reply(
            Op::Dialogs,
            ID,
            None,
            &json!({"id": ID, "dialogs": dialogs}),
        )
        .unwrap_or_else(|| panic!("{}", "ingress reply satisfies the expected protocol shape")),
    )
    .unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(reply, json!({"endpoint": "live", "dialogs": dialogs}));
    assert!(answer_reply(Op::Dialogs, ID, None, &json!({"id": ID})).is_none());
}

#[test]
fn a_dialog_submit_counts_only_for_the_dialog_it_named() {
    let answer =
        |dialog: &str, result: &str| json!({"id": ID, "dialogId": dialog, "result": result});
    for result in ["submitted", "not_open", "invalid", "kind_mismatch"] {
        let reply: Value = serde_json::from_str(
            &answer_reply(Op::DialogSubmit, ID, Some("d1"), &answer("d1", result)).unwrap_or_else(
                || panic!("{}", "ingress reply satisfies the expected protocol shape"),
            ),
        )
        .unwrap_or_else(|error| {
            panic!(
                "{}: {:?}",
                "valid test fixture or expected ingress reply", error
            )
        });
        assert_eq!(
            reply,
            json!({"endpoint": "live", "dialogId": "d1", "result": result})
        );
    }
    // Another dialog's answer, or a result OMP does not give, says nothing about this submit.
    assert!(answer_reply(Op::DialogSubmit, ID, Some("d1"), &answer("d2", "submitted")).is_none());
    assert!(answer_reply(Op::DialogSubmit, ID, Some("d1"), &answer("d1", "approved")).is_none());
}

fn attachment(kind: Kind, name: &str) -> Attachment {
    Attachment {
        kind,
        name: name.into(),
        path: format!("/home/u/.cache/herdline/attachments/x/{name}"),
        size: 1,
        sha256: String::new(),
        node: "n".into(),
        session: "lab".into(),
        boot: "b".into(),
        pane: "w1:p1".into(),
        instance: Some(expected()),
    }
}

fn request(text: &str, attachments: Vec<Attachment>, deliver_as: Option<DeliverAs>) -> Request {
    Request {
        op: Op::Send,
        node: "n".into(),
        session: "lab".into(),
        boot: "b".into(),
        pane: "w1:p1".into(),
        instance: expected(),
        id: ID.into(),
        text: text.into(),
        attachments,
        deliver_as,
        dialog_id: None,
        answer: None,
    }
}

#[test]
fn images_go_as_images_and_files_as_mentions() {
    let host_endpoint = endpoint_for_line();
    let line: Value = serde_json::from_str(&send_line(
        &request(
            "look",
            vec![
                attachment(Kind::Image, "a.jpg"),
                attachment(Kind::File, "notes.txt"),
            ],
            Some(DeliverAs::Steer),
        ),
        &host_endpoint,
    ))
    .unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(
        line,
        json!({
            "v": 1,
            "id": ID,
            "instance": {"sessionId": "s1", "sessionFile": SESSION_FILE, "pid": PID, "nonce": NONCE},
            "text": "look @\"/home/u/.cache/herdline/attachments/x/notes.txt\"",
            "attachments": [{"path": "/home/u/.cache/herdline/attachments/x/a.jpg", "mimeType": "image/jpeg"}],
            "deliverAs": "steer",
        })
    );
}

#[test]
fn a_photo_alone_is_sent_with_empty_text() {
    let line: Value = serde_json::from_str(&send_line(
        &request("", vec![attachment(Kind::Image, "a.png")], None),
        &endpoint_for_line(),
    ))
    .unwrap_or_else(|error| {
        panic!(
            "{}: {:?}",
            "valid test fixture or expected ingress reply", error
        )
    });
    assert_eq!(line["text"], "");
    assert_eq!(line["deliverAs"], "auto");
    assert_eq!(line["attachments"][0]["mimeType"], "image/png");
}

fn endpoint_for_line() -> Endpoint {
    Endpoint {
        sidecar: String::new(),
        socket: String::new(),
        content: String::new(),
        session_id: "s1".into(),
        session_file: SESSION_FILE.into(),
        pid: PID,
        nonce: NONCE.into(),
    }
}
