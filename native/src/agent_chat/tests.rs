use super::*;

const ID: &str = "0b5d3c1e-8f2a-4c6d-9e7b-1a2b3c4d5e6f";

fn codex() -> Identity {
    Identity {
        pane_id: "w1:p1".to_owned(),
        terminal_id: "term_1".to_owned(),
        pid: Some(42),
        path: "0b5d3c1e-8f2a-4c6d-9e7b-1a2b3c4d5e6f".to_owned(),
        agent: "codex".to_owned(),
        source: "herdr:codex".to_owned(),
        kind: "id".to_owned(),
    }
}

fn opencode() -> Identity {
    Identity {
        path: "ses_a1b2c3d4e5f6GhI7JkL8MnO9Pq".to_owned(),
        agent: "opencode".to_owned(),
        source: "herdr:opencode".to_owned(),
        ..codex()
    }
}

#[test]
fn request_ids_are_the_ones_the_app_makes() {
    assert!(valid_request_id(ID));
    assert!(!valid_request_id(""));
    // Uppercase, a short or long run, or a bad separator stay refused: a
    // reply names a request the app never made.
    assert!(!valid_request_id("0B5D3C1E-8f2a-4c6d-9e7b-1a2b3c4d5e6f"));
    assert!(!valid_request_id("0b5d3c1e-8f2a-4c6d-9e7b-1a2b3c4d5e6"));
    assert!(!valid_request_id("0b5d3c1e_8f2a-4c6d-9e7b-1a2b3c4d5e6f"));
}

#[test]
fn engines_route_by_the_agent_the_instance_names() {
    assert_eq!(Engine::Codex, engine(&codex()));
    assert_eq!(Engine::OpenCode, engine(&opencode()));
    // Anything else goes to the caller's validated PTY route, as a bare
    // report of absence, never a guessed call.
    let mut instance = codex();
    instance.agent = "claude".to_owned();
    assert_eq!(Engine::Pty, engine(&instance));
    instance.agent = "kilo".to_owned();
    assert_eq!(Engine::Kilo, engine(&instance));
}

#[test]
fn only_session_id_refs_are_speakable() {
    assert!(sendable_session(&codex()));
    assert!(sendable_session(&opencode()));
    // A path ref, an empty or dotted name, or one with shell-relevant bytes
    // is drafted for something this route cannot name.
    let mut path = codex();
    path.kind = "path".to_owned();
    assert!(!sendable_session(&path));
    let mut dot = codex();
    dot.path = ".sse".to_owned();
    assert!(!sendable_session(&dot));
    let mut odd = codex();
    odd.path = "ses_a/b".to_owned();
    assert!(!sendable_session(&odd));
    let mut background = codex();
    background.pid = None;
    assert!(!sendable_session(&background));
    background.pid = Some(0);
    assert!(!sendable_session(&background));
}

#[test]
fn replies_stay_in_the_shapes_kotlin_reads() {
    assert_eq!(r#"{"endpoint":"absent"}"#, absent().to_string());
    assert_eq!(
        r#"{"endpoint":"unusable","reason":"status"}"#,
        unavailable("status").to_string()
    );
    assert_eq!(
        format!(r#"{{"endpoint":"live","id":"{ID}","outcome":"accepted"}}"#),
        accepted(ID).to_string()
    );
    assert_eq!(
        format!(r#"{{"endpoint":"live","id":"{ID}","outcome":"uncertain"}}"#),
        uncertain(ID).to_string()
    );
    // A refusal always carries its reason; only that says nothing was
    // queued.
    assert_eq!(
        r#"{"outcome":"refused","reason":"agent_restarted"}"#,
        refused(composer::Refusal::AgentRestarted).to_string()
    );
    assert_eq!(
        r#"{"outcome":"refused","reason":"empty_text"}"#,
        refused(composer::Refusal::EmptyText).to_string()
    );
}

#[test]
fn only_proved_words_say_a_definite_verdict() {
    // The words proved before the agent could hold anything: the session
    // gone, the login refused, no server, or the pane not this instance.
    assert_eq!(refused(composer::Refusal::AgentRestarted), control_reply("gone", Op::Send, ID, "").unwrap());
    assert_eq!(unavailable("server_auth"), control_reply("auth", Op::Send, ID, "").unwrap());
    assert_eq!(unavailable("local"), control_reply("local", Op::Send, ID, "").unwrap());
    assert_eq!(refused(composer::Refusal::AgentRestarted), control_reply("refused_changed", Op::Send, ID, "").unwrap());
    assert_eq!(refused(composer::Refusal::InstanceUnknown), control_reply("refused_unknown", Op::Send, ID, "").unwrap());
    assert_eq!(unavailable("instance_unknown"), control_reply("refused_unknown", Op::State, ID, "").unwrap());
    assert_eq!(Some(refused(composer::Refusal::AgentRestarted)), gate_reply("refused_changed", Op::Send));
    // opencode_media refused the staged files before the prompt went: each
    // code its own words; an unknown one keeps the send uncertain.
    for (code, reason) in [
        ("attachment_target", composer::Refusal::AttachmentTarget),
        ("attachment_missing", composer::Refusal::AttachmentMissing),
        ("attachment_needs_agent", composer::Refusal::AttachmentNeedsAgent),
        ("unsupported_type", composer::Refusal::AttachmentUnsupported),
        ("too_large", composer::Refusal::AttachmentTooLarge),
    ] {
        assert_eq!(refused(reason), control_reply(&format!("media\t{code}"), Op::Send, ID, "").unwrap(), "{code}");
    }
    assert_eq!(uncertain(ID), control_reply("media\tother", Op::Send, ID, "").unwrap());
    // Unknown words, and any answer after an invocation whose fate is
    // unreadable, keep a send uncertain: never a refusal, never an unusable
    // endpoint Kotlin could read as nothing left. A check fails.
    for word in ["doubt", "", "answer", "500", "\u{0}", "accepted", "withdrawn\ttrue"] {
        assert_eq!(uncertain(ID), control_reply(word, Op::Send, ID, "").unwrap(), "word {word:?} must keep a send uncertain");
        assert!(control_reply(word, Op::State, ID, "").is_err());
        assert_eq!(None, gate_reply(word, Op::Send));
    }
}

#[test]
fn opencode_inbox_answers_read_as_the_live_endpoint_kotlin_parses() {
    assert_eq!(accepted(ID), control_reply("queued", Op::Send, ID, "").unwrap());
    // A send with no verb to a running session: refused before anything
    // was queued, so the phone can offer Queue or Steer by name.
    assert_eq!(refused(composer::Refusal::AgentWorking), control_reply("busy", Op::Send, ID, "").unwrap());
    assert!(control_reply("busy", Op::State, ID, "").is_err());
    // Kilo's steer for a session no longer at work: refused, never a turn of its own.
    assert_eq!(refused(composer::Refusal::AgentIdle), control_reply("idle", Op::Send, ID, "").unwrap());
    assert!(control_reply("idle", Op::Withdraw, ID, "").is_err());
    // No server is an unusable route with its reason, never an absence the
    // pane route could take a send over on.
    for op in [Op::Send, Op::State, Op::Status, Op::Withdraw, Op::Promote, Op::Dialogs, Op::Abort, Op::Children] {
        assert_eq!(unavailable("needs_server"), control_reply("no_server", op, ID, "").unwrap());
    }
    let state = format!(r#"state	{{"idle":false,"queued":[{{"requestId":"{ID}","text":"next","queue":"followUp"}},{{"requestId":null,"text":"desktop","queue":"steering"}},{{"text":"x","queue":"later"}}]}}"#);
    assert_eq!(
        control_reply(&state, Op::State, ID, "").unwrap(),
        json!({"endpoint": "live", "state": {"idle": false, "queued": [
            {"requestId": ID, "text": "next", "queue": "followUp"},
            {"requestId": null, "text": "desktop", "queue": "steering"},
        ]}})
    );
    assert!(control_reply("state\t{\"queued\":[]}", Op::State, ID, "").is_err(), "no idle flag is no state");
    assert_eq!(control_reply("unknown", Op::Status, ID, "").unwrap(), json!({"endpoint": "live", "id": ID, "outcome": "unknown"}));
    assert_eq!(control_reply("withdrawn\tfalse", Op::Withdraw, ID, "").unwrap(), json!({"endpoint": "live", "id": ID, "withdrawn": false}));
    assert_eq!(control_reply("promoted\ttrue", Op::Promote, ID, "").unwrap(), json!({"endpoint": "live", "id": ID, "promoted": true}));
    // A cancel or a move whose fate could not be read is never settled.
    assert!(control_reply("doubt", Op::Withdraw, ID, "").is_err());
    assert!(control_reply("promoted\tmaybe", Op::Promote, ID, "").is_err());
}

#[test]
fn children_are_named_by_id_and_title_or_not_at_all() {
    let list = r#"children	[{"id":"ses_kid","title":"Explore"},{"id":"ses_two","title":""}]"#;
    assert_eq!(
        control_reply(list, Op::Children, ID, "").unwrap(),
        json!({"endpoint": "live", "children": [{"id": "ses_kid", "title": "Explore"}, {"id": "ses_two", "title": ""}]})
    );
    assert_eq!(control_reply("children\t[]", Op::Children, ID, "").unwrap(), json!({"endpoint": "live", "children": []}));
    // A child claiming more than its name, an id that is a path, or no list
    // is no answer: Chat shows nothing rather than a guess.
    for bad in [
        r#"children	[{"id":"ses_kid","title":"x","status":"running"}]"#,
        r#"children	[{"id":"../x","title":"x"}]"#,
        r#"children	[{"id":"ses_kid"}]"#,
        r#"children	{"id":"ses_kid"}"#,
        "children",
        "doubt",
    ] {
        assert!(control_reply(bad, Op::Children, ID, "").is_err(), "{bad:?}");
    }
    assert_eq!(unavailable("server_discovery"), control_reply("discovery", Op::Children, ID, "").unwrap());
    let many = format!("children\t[{}]", vec![r#"{"id":"ses_a","title":"a"}"#; MAX_CHILDREN + 1].join(","));
    assert!(control_reply(&many, Op::Children, ID, "").is_err());
    // OpenCode's list with others beyond it says so; a short one cannot.
    let first = format!("children_more\t[{}]", vec![r#"{"id":"ses_a","title":"a"}"#; MAX_CHILDREN].join(","));
    assert_eq!(control_reply(&first, Op::Children, ID, "").unwrap()["more"], json!(true));
    assert!(control_reply(r#"children_more	[{"id":"ses_a","title":"a"}]"#, Op::Children, ID, "").is_err());
}

#[test]
fn refusals_map_to_the_words_the_app_shows() {
    assert_eq!(
        "agent_restarted",
        refusal_name(composer::Refusal::AgentRestarted)
    );
    assert_eq!(
        "instance_unknown",
        refusal_name(composer::Refusal::InstanceUnknown)
    );
}

#[test]
fn the_unsupported_answers_name_what_is_missing() {
    assert_eq!(
        r#"{"endpoint":"unsupported","missing":["dialogs"]}"#,
        unsupported_op(Op::Dialogs).to_string()
    );
    assert_eq!(
        r#"{"endpoint":"unsupported","missing":["abort"]}"#,
        unsupported_op(Op::Abort).to_string()
    );
    assert_eq!(
        r#"{"endpoint":"unsupported","missing":["attachments"]}"#,
        unsupported_type().to_string()
    );
}

#[test]
fn codex_daemon_words_say_nothing_sent_only_before_any_change() {
    // No daemon, no CLI, or a thread the daemon has not loaded: no endpoint,
    // so the validated PTY route takes the send.
    for word in ["nodaemon", "cli", "notloaded"] {
        for op in [Op::Send, Op::Status, Op::State, Op::Withdraw] {
            assert_eq!(absent(), codex_reply(word, "null", op, ID).unwrap());
        }
    }
    // A daemon that would not speak or cannot serve the thread: the client
    // stopped before asking the thread anything.
    for (word, reason) in [("daemon", "codex_daemon"), ("error", "codex_error")] {
        for op in [Op::Send, Op::Status, Op::State, Op::Withdraw] {
            assert_eq!(unavailable(reason), codex_reply(word, "null", op, ID).unwrap());
        }
    }
    assert_eq!(
        refused(composer::Refusal::AgentBlocked),
        codex_reply("blocked", "null", Op::Send, ID).unwrap()
    );
    assert_eq!(
        refused(composer::Refusal::AgentIdle),
        codex_reply("not_working", "null", Op::Send, ID).unwrap()
    );
    // Past a change asked of the thread, an unreadable answer is a doubt:
    // a send stays uncertain and is never sent again; a check fails.
    for word in ["doubt", "", "queued", "gone", "withdrawn", "state"] {
        assert_eq!(uncertain(ID), codex_reply(word, "null", Op::Send, ID).unwrap(), "{word:?}");
    }
    assert!(codex_reply("doubt", "null", Op::Withdraw, ID).is_err());
    assert!(codex_reply("accepted", "null", Op::Withdraw, ID).is_err());
    assert!(codex_reply("accepted", "not json", Op::Send, ID).is_err());
}

#[test]
fn codex_daemon_answers_read_as_the_live_endpoint_kotlin_parses() {
    let state = r#"{"idle":false,"queued":[{"requestId":"r","text":"next","queue":"followUp"}]}"#;
    let sent = codex_reply("accepted", state, Op::Send, ID).unwrap();
    assert_eq!("live", sent["endpoint"]);
    assert_eq!("accepted", sent["outcome"]);
    assert_eq!(ID, sent["id"]);
    assert_eq!("followUp", sent["state"]["queued"][0]["queue"]);
    let probe = codex_reply("state", state, Op::State, ID).unwrap();
    assert_eq!(json!({"endpoint": "live", "state": serde_json::from_str::<Value>(state).unwrap()}), probe);
    // A state that is not an object is no probe.
    assert!(codex_reply("state", "null", Op::State, ID).is_err());
    // Status with no trace of the id is unknown, which Kotlin keeps as is.
    let checked = codex_reply("unknown", state, Op::Status, ID).unwrap();
    assert_eq!("unknown", checked["outcome"]);
    assert!(codex_reply("unknown", state, Op::Send, ID).unwrap() == uncertain(ID));
    let gone = codex_reply("withdrawn", r#"{"withdrawn":false,"state":null}"#, Op::Withdraw, ID).unwrap();
    assert_eq!(json!({"endpoint": "live", "withdrawn": false, "state": null}), gone);
    // Withdrawn needs the daemon's own boolean.
    assert!(codex_reply("withdrawn", r#"{"withdrawn":"yes"}"#, Op::Withdraw, ID).is_err());
}

#[test]
fn the_codex_step_runs_the_daemon_client_with_quoted_arguments() {
    let request: Request = serde_json::from_value(json!({
        "op": "send",
        "node": "n",
        "session": "s",
        "boot": "b",
        "pane": "w1:p1",
        "instance": codex(),
        "id": ID,
        "text": "it's $(rm -rf ~)",
        "deliverAs": "steer",
    }))
    .unwrap();
    let step = codex_step(&request, &request.text);
    // The proxy only, never a daemon start or a resume.
    assert!(step.contains(r#"[codex, "app-server", "proxy"]"#));
    assert!(!step.contains("daemon start"));
    assert!(!step.contains("thread/resume") && !step.contains("exec resume"));
    assert!(step.contains(&format!(
        r#"python3 - "$codex" send {} {} steer {} {} <<'HERDLINE_CODEX_CTL'"#,
        shell_quote(&codex().path),
        shell_quote(ID),
        shell_quote("it's $(rm -rf ~)"),
        shell_quote("[]"),
    )));
}

#[test]
fn control_replies_keep_the_omp_dialog_shapes_and_fail_closed() {
    let dialog = r#"[{"dialogId":"per_1","kind":"permission"}]"#;
    assert_eq!(
        control_reply(&format!("dialogs\t{dialog}"), Op::Dialogs, ID, "").unwrap(),
        json!({"endpoint": "live", "dialogs": [{"dialogId": "per_1", "kind": "permission"}]})
    );
    // A dialog the phone could not answer by name is no dialog snapshot.
    assert!(control_reply(r#"dialogs	[{"dialogId":"../x","kind":"permission"}]"#, Op::Dialogs, ID, "").is_err());
    assert!(control_reply(r#"dialogs	[{"dialogId":"per_1","kind":"question"}]"#, Op::Dialogs, ID, "").is_err());
    assert_eq!(
        control_reply("not_open", Op::DialogSubmit, ID, "per_1").unwrap(),
        json!({"endpoint": "live", "dialogId": "per_1", "result": "not_open"})
    );
    assert_eq!(control_reply("aborted\tfalse", Op::Abort, ID, "").unwrap(), json!({"endpoint": "live", "aborted": false}));
    assert_eq!(control_reply("gone", Op::DialogSubmit, ID, "per_1").unwrap(), unavailable("agent_restarted"));
    // An answer that may have been admitted is never read as a result.
    assert!(control_reply("doubt", Op::DialogSubmit, ID, "per_1").is_err());
    // A word from another op is not this op's answer.
    assert!(control_reply("submitted", Op::Abort, ID, "").is_err());
}

// More open than one snapshot shows: the first ones, said as cut, never as the whole.
#[test]
fn a_cut_dialog_snapshot_says_more_and_holds_exactly_the_first_ones() {
    let five = format!("[{}]", vec![r#"{"dialogId":"per_1","kind":"permission"}"#; 5].join(","));
    let reply = control_reply(&format!("dialogs_more\t{five}"), Op::Dialogs, ID, "").unwrap();
    assert_eq!((reply["endpoint"].as_str(), reply["more"].as_bool(), reply["dialogs"].as_array().map(Vec::len)), (Some("live"), Some(true), Some(5)));
    assert_eq!(control_reply(&format!("dialogs\t{five}"), Op::Dialogs, ID, "").unwrap().get("more"), None);
    assert!(control_reply(r#"dialogs_more	[{"dialogId":"per_1","kind":"permission"}]"#, Op::Dialogs, ID, "").is_err());
    assert!(control_reply(&format!("dialogs_more\t{}", five.replace("per_1", "../x")), Op::Dialogs, ID, "").is_err());
    assert!(control_reply(&format!("dialogs_more\t{five}"), Op::Children, ID, "").is_err());
}

#[test]
fn question_replies_name_their_own_kind_and_settle_only_on_the_servers_word() {
    let dialogs = r#"[{"dialogId":"per_1","kind":"permission"},{"dialogId":"frm_1","kind":"form"},{"dialogId":"que_1","kind":"form"}]"#;
    assert_eq!(control_reply(&format!("dialogs\t{dialogs}"), Op::Dialogs, ID, "").unwrap()["dialogs"].as_array().unwrap().len(), 3);
    // A form is never answered as a permission, nor one under another kind's id.
    assert!(control_reply(r#"dialogs	[{"dialogId":"per_1","kind":"form"}]"#, Op::Dialogs, ID, "").is_err());
    assert!(control_reply(r#"dialogs	[{"dialogId":"frm_1","kind":"permission"}]"#, Op::Dialogs, ID, "").is_err());
    assert!(control_reply(r#"dialogs	[{"dialogId":"frm_../x","kind":"form"}]"#, Op::Dialogs, ID, "").is_err());
    assert_eq!(
        control_reply("cancelled", Op::DialogCancel, ID, "frm_1").unwrap(),
        json!({"endpoint": "live", "dialogId": "frm_1", "result": "cancelled"})
    );
    // Answered elsewhere first: not open, and said so for both ops.
    for op in [Op::DialogSubmit, Op::DialogCancel] {
        assert_eq!(
            control_reply("answered", op, ID, "que_1").unwrap(),
            json!({"endpoint": "live", "dialogId": "que_1", "result": "not_open", "settled": "answered"})
        );
        assert!(control_reply("doubt", op, ID, "que_1").is_err());
    }
    // A decline is never read as an answer, nor an answer as a decline.
    assert!(control_reply("cancelled", Op::DialogSubmit, ID, "frm_1").is_err());
    assert!(control_reply("submitted", Op::DialogCancel, ID, "frm_1").is_err());
    assert!(control_reply("invalid", Op::DialogCancel, ID, "frm_1").is_err());
    assert_eq!(
        r#"{"endpoint":"unsupported","missing":["dialog_cancel"]}"#,
        unsupported_op(Op::DialogCancel).to_string()
    );
}

#[test]
fn question_ids_are_the_ones_the_servers_make() {
    assert!(valid_form_id("frm_01JZ") && valid_form_id("que_01JZ"));
    assert!(!valid_form_id("per_1") && !valid_form_id("frm_1/../x") && !valid_form_id("frm_1 x") && !valid_form_id(&format!("frm_{}", "a".repeat(130))));
    assert!(!valid_permission_id("frm_1"));
}

/// The pane root a control step gets ([`PANE_ROOT`]) is the shell_pid of
/// the very report the gate verified, 0 when Herdr names none; a changed
/// instance, or a process report of another pane, never runs the step.
#[test]
fn a_step_gets_the_pane_root_from_the_report_the_gate_verified() {
    let home = tempfile::tempdir().expect("home");
    let bin = home.path().join(".local/bin");
    std::fs::create_dir_all(&bin).expect("bin");
    let herdr = bin.join("herdr");
    std::fs::write(&herdr, "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n").expect("herdr");
    std::fs::set_permissions(&herdr, std::os::unix::fs::PermissionsExt::from_mode(0o755)).expect("mode");
    let instance = codex();
    let pane = json!({"result": {"pane": {"agent": "codex", "pane_id": "w1:p1", "terminal_id": "term_1",
        "agent_session": {"agent": "codex", "kind": "id", "source": "herdr:codex", "value": instance.path}}}});
    std::fs::write(home.path().join("pane.json"), pane.to_string()).expect("pane");
    let exec = Exec::local(vec![
        ("HOME".into(), home.path().display().to_string()),
        ("PATH".into(), format!("{}:/usr/bin:/bin", bin.display())),
    ]);
    let step = format!("{PANE_ROOT} sh -c 'printf \"root=%s\\n\" \"$HERDLINE_PANE_ROOT\"'");
    let gate = |shell: Value, pid: u32, pane: &str| {
        let process = json!({"result": {"process_info": {"foreground_processes": [{"name": "codex", "pid": pid}], "pane_id": pane, "shell_pid": shell}}});
        std::fs::write(home.path().join("proc.json"), process.to_string()).expect("proc");
        host_gate(&exec, "lab", &instance, &step, None).expect("word")
    };
    assert_eq!(gate(json!(7), 42, "w1:p1"), "root=7");
    assert_eq!(gate(Value::Null, 42, "w1:p1"), "root=0");
    assert_eq!(gate(json!(7), 43, "w1:p1"), "refused_changed");
    // Another pane's shell_pid would make another pane's tree this one's.
    assert_eq!(gate(json!(7), 42, "w1:p2"), "refused_changed");
    let process = json!({"result": {"process_info": {"foreground_processes": [{"name": "codex", "pid": 42}], "shell_pid": 7}}});
    std::fs::write(home.path().join("proc.json"), process.to_string()).expect("proc");
    assert_eq!(host_gate(&exec, "lab", &instance, &step, None).expect("word"), "refused_changed");
}
