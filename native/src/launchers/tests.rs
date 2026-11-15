use super::*;
use crate::ssh;

fn output(exit_status: Option<u32>, tail: &str) -> ssh::Output {
    ssh::Output {
        exit_status,
        tail: tail.as_bytes().to_vec(),
    }
}

#[test]
fn a_read_probe_lists_the_found_kinds_in_catalogue_order() {
    let answer = parse(&output(Some(0), "HIT claude\nHIT pi\nHIT opencode\nEND\n")).unwrap();
    let launchers = answer["launchers"].as_array().unwrap();
    let kinds: Vec<&str> = launchers
        .iter()
        .map(|entry| entry["kind"].as_str().unwrap())
        .collect();
    assert_eq!(kinds, vec!["pi", "claude", "opencode"]);
    assert_eq!(launchers[1]["label"], "Claude");
    // A listing carries no executable path or environment, only kind and label.
    assert_eq!(launchers[1].as_object().unwrap().len(), 2);
}

#[test]
fn output_that_names_nothing_lists_nothing() {
    let answer = parse(&output(Some(0), "END\n")).unwrap();
    assert!(answer["launchers"].as_array().unwrap().is_empty());
}

#[test]
fn a_truncated_probe_is_an_error_not_a_short_listing() {
    // "END" missing means `HIT kilo` may simply not have been read yet.
    assert!(parse(&output(Some(0), "HIT pi\nHIT kilo\n")).is_err());
    assert!(parse(&output(None, "END\nHIT pi\n")).is_err());
    assert!(parse(&output(Some(127), "END\n")).is_err());
}

#[test]
fn a_line_naming_an_unlisted_kind_is_not_a_launcher() {
    let answer = parse(&output(Some(0), "HIT rm\nHIT claude\nEND\n")).unwrap();
    let launchers = answer["launchers"].as_array().unwrap();
    assert_eq!(launchers.len(), 1);
    assert_eq!(launchers[0]["kind"], "claude");
}

#[test]
fn cli_start_targets_only_the_created_terminal_and_never_retries() {
    use std::{fs, os::unix::fs::PermissionsExt};
    let home = tempfile::tempdir().unwrap();
    let bin = home.path().join("bin");
    fs::create_dir(&bin).unwrap();
    let herdr = bin.join("herdr");
    fs::write(&herdr, r#"#!/bin/sh
case "$*" in
  *'pane get'*) cat "$HOME/pane.json";;
  *'agent start'*) printf '%s\n' "$*" >> "$HOME/starts"; printf '{"result":{"type":"agent_started"}}\n';;
esac
"#).unwrap();
    fs::set_permissions(&herdr, fs::Permissions::from_mode(0o755)).unwrap();
    fs::write(
        home.path().join("pane.json"),
        r#"{"result":{"pane":{"pane_id":"w1:p4","tab_id":"w1:t3","terminal_id":"term-new"}}}"#,
    )
    .unwrap();
    let exec = ssh::Exec::local(vec![
        ("HOME".into(), home.path().display().to_string()),
        ("PATH".into(), format!("{}:/usr/bin:/bin", bin.display())),
    ]);
    assert_eq!(
        serde_json::from_str::<Value>(
            &start(&exec, "qa", "w1:p4", "w1:t3", "term-new", "omp", "omp-1234").unwrap()
        )
        .unwrap()["started"],
        true
    );
    assert!(
        start(
            &exec,
            "qa",
            "w1:p4",
            "w1:t3",
            "term-replaced",
            "omp",
            "omp-5678"
        )
        .is_err()
    );
    let calls = fs::read_to_string(home.path().join("starts")).unwrap();
    assert_eq!(calls.lines().count(), 1);
    assert!(calls.contains("agent start omp-1234 --kind omp --pane w1:p4"));
}
