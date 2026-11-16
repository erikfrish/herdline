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
