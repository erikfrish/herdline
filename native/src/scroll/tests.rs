use super::*;

fn metrics(offset: u64, max: u64) -> Option<PaneSurfaceScrollMetrics> {
    Some(PaneSurfaceScrollMetrics {
        offset_from_bottom: offset,
        max_offset_from_bottom: max,
        viewport_rows: 30,
    })
}

fn scrolls(pane: Pane) -> Scrolls {
    let mut scrolls = Scrolls::default();
    scrolls.surface([("p1".to_owned(), pane)]);
    scrolls
}

fn text_pane(offset: u64, max: u64) -> Pane {
    Pane {
        mouse: false,
        scroll: metrics(offset, max),
    }
}

#[test]
fn only_a_program_that_reads_the_mouse_gets_the_wheel() {
    let mut mouse = scrolls(Pane {
        mouse: true,
        scroll: metrics(0, 0),
    });
    assert_eq!(mouse.step("p1", true, 3), Step::Wheel);
    assert_eq!(mouse.step("p1", false, 3), Step::Wheel);
    // Alternate screen without the mouse: Herdr would turn a wheel into
    // arrow keys, so nothing goes; it has no scrollback to show either.
    let mut alternate = scrolls(text_pane(0, 0));
    assert_eq!(alternate.step("p1", true, 3), Step::Nothing);
    assert_eq!(alternate.step("p1", false, 3), Step::Nothing);
}

#[test]
fn a_text_pane_scrolls_herdr_scrollback_within_its_bounds() {
    let mut pane = scrolls(text_pane(0, 4));
    assert_eq!(pane.step("p1", false, 3), Step::Nothing, "already live");
    assert_eq!(pane.step("p1", true, 3), Step::Request(3));
    pane.sent("p1", "r1".into());
    assert_eq!(pane.answered("r1", true), Answer::Done);
    // The next step builds on the asked offset before any surface shows it.
    assert_eq!(
        pane.step("p1", true, 3),
        Step::Request(4),
        "capped at the oldest row"
    );
    pane.sent("p1", "r2".into());
    assert_eq!(pane.answered("r2", true), Answer::Done);
    assert_eq!(pane.step("p1", true, 1), Step::Nothing);
    assert_eq!(pane.step("p1", false, 10), Step::Request(0));
}

#[test]
fn unknown_panes_and_panes_without_scrollback_take_nothing() {
    let mut pane = scrolls(Pane {
        mouse: false,
        scroll: None,
    });
    assert_eq!(pane.step("p1", true, 3), Step::Nothing);
    assert_eq!(pane.step("p2", true, 3), Step::Nothing);
}

#[test]
fn one_request_per_pane_is_on_the_wire_and_the_newest_offset_follows() {
    let mut pane = scrolls(text_pane(0, 100));
    assert_eq!(pane.step("p1", true, 2), Step::Request(2));
    pane.sent("p1", "r1".into());
    assert_eq!(pane.step("p1", true, 2), Step::Wait);
    assert_eq!(pane.step("p1", true, 2), Step::Wait);
    assert_eq!(pane.answered("other", true), Answer::NotOurs);
    assert_eq!(
        pane.answered("r1", true),
        Answer::Next {
            pane: "p1".into(),
            offset: 6
        }
    );
    pane.sent("p1", "r2".into());
    assert_eq!(pane.answered("r2", true), Answer::Done);
    assert_eq!(pane.answered("r2", true), Answer::NotOurs, "answered once");
}

#[test]
fn a_rejected_or_unsent_request_falls_back_to_what_herdr_shows() {
    let mut pane = scrolls(text_pane(5, 100));
    assert_eq!(pane.step("p1", true, 2), Step::Request(7));
    pane.sent("p1", "r1".into());
    assert_eq!(pane.step("p1", true, 2), Step::Wait);
    assert_eq!(pane.answered("r1", false), Answer::Done);
    assert_eq!(pane.step("p1", true, 2), Step::Request(7));
    pane.unsent("p1");
    assert_eq!(pane.step("p1", false, 5), Step::Request(0));
}

#[test]
fn a_surface_that_shows_the_goal_or_drops_the_pane_ends_it() {
    let mut pane = scrolls(text_pane(0, 100));
    assert_eq!(pane.step("p1", true, 3), Step::Request(3));
    pane.sent("p1", "r1".into());
    assert_eq!(pane.answered("r1", true), Answer::Done);
    // Output scrolled the pane meanwhile: Herdr's offset is the base again.
    pane.surface([("p1".to_owned(), text_pane(3, 100))]);
    pane.surface([("p1".to_owned(), text_pane(10, 100))]);
    assert_eq!(pane.step("p1", false, 1), Step::Request(9));
    pane.surface([]);
    pane.surface([("p1".to_owned(), text_pane(0, 100))]);
    assert_eq!(pane.step("p1", true, 1), Step::Request(1), "no stale goal");
}
