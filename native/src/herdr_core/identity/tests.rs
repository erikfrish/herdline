use super::*;
use serde_json::json;

fn record(foreground: Value) -> Value {
    json!({
        "address": "t7",
        "state": "live",
        "foreground": foreground,
        "labels": {
            "herdr.space": "sp1",
            "herdline.agent": "omp",
            "herdline.session_file": "/home/u/.omp/agent/sessions/x/a.jsonl",
            "herdline.session_id": "abc-1",
            "herdline.pid": "4242",
        },
    })
}

#[test]
fn labels_with_their_process_in_the_foreground_name_the_instance() {
    let identity = of(&record(json!({"pid": 4242, "name": "omp"})), "p1").unwrap();
    assert_eq!(identity.pane_id, "p1");
    assert_eq!(identity.terminal_id, "t7");
    assert_eq!(identity.pid, Some(4242));
    assert_eq!(identity.path, "/home/u/.omp/agent/sessions/x/a.jsonl");
    assert_eq!(identity.source, "herdline-core:omp");
    assert_eq!(identity.kind, "path");
}

#[test]
fn a_first_snapshot_without_a_foreground_is_unknown_until_one_is_sampled() {
    let mut value = record(Value::Null);
    assert_eq!(of(&value, "p1"), Err(Unknown::NotLive));
    value["foreground"] = json!({"pid": 4242});
    assert!(of(&value, "p1").is_ok());
}

#[test]
fn a_wrapper_with_another_foreground_pid_stays_unknown() {
    assert_eq!(
        of(&record(json!({"pid": 4241})), "p1"),
        Err(Unknown::OtherProcess)
    );
}

#[test]
fn a_finished_terminal_names_no_instance() {
    let mut value = record(json!({"pid": 4242}));
    value["state"] = json!("finished");
    assert_eq!(of(&value, "p1"), Err(Unknown::NotLive));
}

#[test]
fn cleared_or_partial_labels_name_no_instance() {
    let mut value = record(json!({"pid": 4242}));
    value["labels"]["herdline.session_id"] = json!("");
    assert_eq!(of(&value, "p1"), Err(Unknown::NoLabels));
}

#[test]
fn malformed_labels_are_refused() {
    for (key, bad) in [
        ("herdline.pid", "04242"),
        ("herdline.pid", "+4242"),
        ("herdline.session_file", "relative.jsonl"),
        ("herdline.session_file", "/a/../b.jsonl"),
        ("herdline.session_id", "../x"),
    ] {
        let mut value = record(json!({"pid": 4242}));
        value["labels"][key] = json!(bad);
        assert_eq!(of(&value, "p1"), Err(Unknown::Malformed), "{key}={bad}");
    }
}

/// Runs the host check here with `home` as HOME and an empty environment
/// otherwise, as the phone would on a host.
fn verify_in(home: &std::path::Path, claim: &Claim) -> Verdict {
    let command = verify_command(claim);
    let script = command.strip_prefix("/bin/sh -c ").unwrap();
    let output = std::process::Command::new("/bin/sh")
        .arg("-c")
        .arg(format!("eval {script}"))
        .env_clear()
        .env("HOME", home)
        .env("PATH", std::env::var_os("PATH").unwrap_or_default())
        .output()
        .unwrap();
    verdict(output.status.code().map(|code| u32::try_from(code).unwrap()))
}

#[test]
fn the_host_confirms_only_a_confined_file_whose_header_names_the_session() {
    let home = tempfile::tempdir().unwrap();
    let home = home.path().canonicalize().unwrap();
    let sessions = home.join(".omp/agent/sessions/x");
    std::fs::create_dir_all(&sessions).unwrap();
    let file = sessions.join("a.jsonl");
    let mut value = record(json!({"pid": 4242, "argv": ["omp"]}));
    value["labels"]["herdline.session_file"] = json!(file.to_str().unwrap());
    let claim = claim(&value, "p1").unwrap();

    // Allocated but not written yet.
    assert_eq!(verify_in(&home, &claim), Verdict::Pending);
    std::fs::write(&file, "{\"type\":\"session\"").unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Unconfined);
    std::fs::write(&file, "{\"type\":\"session\",\"id\":\"abc-1\"}\n").unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Confirmed);
    let slot = "{\"type\":\"title\",\"v\":1,\"title\":\"\",\"updatedAt\":\"2026-11-16\",\"pad\":\" \"}\n";
    std::fs::write(&file, format!("{slot}{{\"type\":\"session\",\"id\":\"abc-1\"}}\n")).unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Confirmed);
    std::fs::write(&file, format!("{slot}{slot}")).unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Mismatch);
    std::fs::write(&file, format!("{slot}{{\"type\":\"session\",\"id\":\"other\"}}\n")).unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Mismatch);
    std::fs::write(&file, "{\"type\":\"session\",\"id\":\"other\"}\n").unwrap();
    assert_eq!(verify_in(&home, &claim), Verdict::Mismatch);

    // A file outside the agent's session roots, even with the right header.
    let stray = home.join("a.jsonl");
    std::fs::write(&stray, "{\"type\":\"session\",\"id\":\"abc-1\"}\n").unwrap();
    let mut value = value.clone();
    value["labels"]["herdline.session_file"] = json!(stray.to_str().unwrap());
    assert_eq!(
        verify_in(&home, &super::claim(&value, "p1").unwrap()),
        Verdict::Unconfined
    );
}
