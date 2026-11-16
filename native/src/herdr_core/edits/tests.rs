use super::*;
use std::collections::HashMap;

fn spaces() -> HashMap<&'static str, &'static str> {
    HashMap::from([("s1", "/src")])
}

fn ask(params: Value) -> std::result::Result<SpaceAsk, (String, String)> {
    let spaces = spaces();
    space_ask(&params, |source| spaces.get(source).map(|folder| (*folder).to_owned()))
}

#[test]
fn a_label_and_a_cwd_are_taken_verbatim_and_the_cwd_wins_over_the_source() {
    let asked = ask(json!({"label": "  qa ws ", "cwd": " /tmp/x ", "source_workspace_id": "s1"})).unwrap();
    assert_eq!(asked.name, "  qa ws ");
    assert_eq!(asked.folder, " /tmp/x ");
}

#[test]
fn with_no_cwd_the_folder_is_the_sources_and_a_cwd_skips_an_unknown_source() {
    assert_eq!(ask(json!({"source_workspace_id": "s1"})).unwrap().folder, "/src");
    // As in Herdr: with a cwd the source is never looked up.
    assert_eq!(ask(json!({"cwd": "/w", "source_workspace_id": "gone"})).unwrap().folder, "/w");
    assert_eq!(ask(json!({})).unwrap().folder, "");
}

#[test]
fn an_unknown_source_is_refused_before_any_terminal() {
    assert_eq!(
        ask(json!({"source_workspace_id": "gone"})),
        Err(("workspace_not_found".to_owned(), "workspace gone not found".to_owned()))
    );
}

#[test]
fn a_bad_env_is_refused_as_herdr_refuses_it() {
    let refused = |env: Value| ask(json!({"env": env})).unwrap_err();
    assert_eq!(refused(json!({"": "x"})), ("invalid_env".to_owned(), "env key must not be empty".to_owned()));
    assert_eq!(refused(json!({"A=B": "x"})), ("invalid_env".to_owned(), "env key A=B must not contain '='".to_owned()));
    assert_eq!(
        refused(json!({"A": "x\u{0}y"})),
        ("invalid_env".to_owned(), "env value for A must not contain NUL bytes".to_owned())
    );
    assert_eq!(refused(json!({"A": 1})), ("invalid".to_owned(), "invalid_params".to_owned()));
}

#[test]
fn the_launch_env_wins_over_the_base_env_and_the_cwd_reaches_the_run() {
    let env = launch_env(&json!({"TERM": "dumb", "FOO": "bar"})).unwrap();
    let run = run_params("s", "t", Some("/tmp/x"), &env);
    assert_eq!(run["env"]["TERM"], "dumb");
    assert_eq!(run["env"]["FOO"], "bar");
    assert_eq!(run["env"]["COLORTERM"], "truecolor");
    assert_eq!(run["cwd"], "/tmp/x");
    assert!(run_params("s", "t", None, &[]).get("cwd").is_none());
}

#[test]
fn a_created_workspace_answers_its_ids_label_and_focus() {
    let edit = Edit::CreateSpace {
        space: "s2".into(),
        name: "qa".into(),
        folder: "/w".into(),
        tab: "t2".into(),
        pane: "p2".into(),
        terminal: "x2".into(),
    };
    assert_eq!(
        created_reply(&edit, false),
        json!({
            "workspace": {"workspace_id": "s2", "label": "qa", "focused": false},
            "tab": {"tab_id": "t2"},
            "root_pane": {"pane_id": "p2", "terminal_id": "x2"},
        })
    );
}
