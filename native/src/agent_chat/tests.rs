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
    // The four words proved before the agent could hold anything: the CLI
    // is absent, the login refused, the session gone, or nothing ran.
    assert_eq!(
        refused(composer::Refusal::AgentRestarted),
        reply("gone", Op::Send, Engine::Codex, ID).unwrap()
    );
    assert_eq!(
        unavailable("codex_cli"),
        reply("cli", Op::Send, Engine::Codex, ID).unwrap()
    );
    assert_eq!(
        unavailable("server_auth"),
        reply("auth", Op::Send, Engine::OpenCode, ID).unwrap()
    );
    assert_eq!(
        unavailable("local"),
        reply("local", Op::Send, Engine::Codex, ID).unwrap()
    );
    // Unknown words and any answer after an invocation whose fate is
    // unreadable keep a send uncertain: never a refusal, never an unusable
    // endpoint Kotlin could read as nothing left.
    for word in ["doubt", "", "answer", "500", "\u{0}"] {
        let out = reply(word, Op::Send, Engine::Codex, ID).unwrap();
        assert_eq!(
            uncertain(ID),
            out,
            "word {word:?} must keep a send uncertain"
        );
        assert!(reply(word, Op::Status, Engine::Codex, ID).is_err());
    }
    // The check ops answer only after the same binding stands.
    for (op, reason) in [
        ("status", "status"),
        ("queue", "queue"),
        ("withdraw", "withdraw"),
    ] {
        let op = match op {
            "status" => Op::Status,
            "queue" => Op::State,
            _ => Op::Withdraw,
        };
        assert_eq!(
            unavailable(reason),
            reply("bound", op, Engine::Codex, ID).unwrap()
        );
    }
    // The verifier's words fail closed for sends and checks alike.
    assert_eq!(
        refused(composer::Refusal::AgentRestarted),
        reply("refused_changed", Op::Send, Engine::OpenCode, ID).unwrap()
    );
    assert_eq!(
        refused(composer::Refusal::InstanceUnknown),
        reply("refused_unknown", Op::Send, Engine::OpenCode, ID).unwrap()
    );
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
        r#"{"endpoint":"unsupported","missing":["attachments"]}"#,
        unsupported_type().to_string()
    );
    // The words the engine reasons use stay short and readable.
    assert_eq!("codex_cli", cli_reason(Engine::Codex));
    assert_eq!("opencode_cli", cli_reason(Engine::OpenCode));
    assert_eq!("codex_auth", server(Engine::Codex));
    assert_eq!("server_auth", server(Engine::OpenCode));
}
