use super::*;

#[test]
fn aggregate_prefers_the_most_urgent_status() {
    assert_eq!(aggregate(["idle", "blocked", "working"]), "blocked");
    assert_eq!(aggregate(["done", "idle"]), "done");
    assert_eq!(aggregate(["unknown", "idle"]), "idle");
    assert_eq!(aggregate([]), "unknown");
}

#[test]
fn an_error_state_is_never_shown_as_idle() {
    assert_eq!(status(Some("error")), "unknown");
    assert_eq!(status(None), "unknown");
}

#[test]
fn a_pane_says_whether_its_program_runs_waits_or_ended() {
    use serde_json::json;
    let running = json!({"state": "live", "pid": 970});
    let waiting = json!({"state": "live", "pid": null, "reason": "stopped_by_restart"});
    let ended = json!({"state": "finished", "pid": 970, "reason": "stopped_by_restart"});
    assert_eq!(unsupported("terminal", Some(&running)), None);
    assert_eq!(
        unsupported("terminal", Some(&waiting)),
        Some("terminal_starting")
    );
    assert_eq!(
        unsupported("terminal", Some(&ended)),
        Some("terminal_finished")
    );
    assert_eq!(unsupported("terminal", None), Some("terminal_lost"));
    assert_eq!(unsupported("mod", Some(&running)), Some("mod_panel"));
}
