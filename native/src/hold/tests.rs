use super::*;

fn target(pane: &str) -> Target {
    Target {
        node: "n1".into(),
        session: "lab".into(),
        pane: pane.into(),
    }
}

#[test]
fn only_its_own_receipt_on_its_own_link_releases_a_delivery() {
    let mut holds = Holds::default();
    holds.hold_until(target("w1:p1"), 1, "gpui-7".into());
    holds.answered(1, "gpui-6");
    // A later link reuses request numbers: its answers prove nothing here.
    holds.answered(2, "gpui-7");
    assert!(holds.is_held(&target("w1:p1")));
    holds.answered(1, "gpui-7");
    assert!(!holds.is_held(&target("w1:p1")));
}

#[test]
fn an_uncertain_delivery_stays_held_until_unlocked() {
    let mut holds = Holds::default();
    holds.hold(target("w1:p1"));
    holds.answered(1, "gpui-1");
    assert!(holds.is_held(&target("w1:p1")));
    assert!(!holds.is_held(&target("w1:p2")), "other panes are free");
    holds.unlock(&target("w1:p1"));
    assert!(!holds.is_held(&target("w1:p1")));
}

#[test]
fn re_arming_after_a_restart_keeps_a_receipt_still_awaited() {
    let mut holds = Holds::default();
    holds.hold_until(target("w1:p1"), 1, "gpui-3".into());
    holds.hold(target("w1:p1"));
    holds.answered(1, "gpui-3");
    assert!(!holds.is_held(&target("w1:p1")));
}
