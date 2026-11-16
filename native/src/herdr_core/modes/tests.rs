use super::*;

#[test]
fn private_modes_are_followed_across_split_chunks() {
    let mut tracker = Tracker::default();
    tracker.feed(b"\x1b[?10");
    tracker.feed(b"02;1006h\x1b[?1h\x1b[?2004h");
    let modes = tracker.modes();
    assert_eq!(modes.tracking, Tracking::Button);
    assert_eq!(modes.encoding, Encoding::Sgr);
    assert!(modes.app_cursor && modes.bracketed && !modes.win32);
    tracker.feed(b"\x1b[?1000l");
    assert_eq!(tracker.modes().tracking, Tracking::Off);
}

#[test]
fn win32_input_mode_turns_on_and_off() {
    let mut tracker = Tracker::default();
    tracker.feed(b"\x1b[?9001h");
    assert!(tracker.modes().win32);
    tracker.feed(b"\x1b[?9001l");
    assert!(!tracker.modes().win32);
}

#[test]
fn non_private_or_intermediate_sequences_change_nothing() {
    let mut tracker = Tracker::default();
    tracker.feed(b"\x1b[1000h\x1b[?1000$h\x1b[?1000\x18h");
    assert_eq!(tracker.modes(), Modes::default());
}

#[test]
fn a_full_reset_clears_every_mode() {
    let mut tracker = Tracker::default();
    tracker.feed(b"\x1b[?1003;1015;9001h\x1bc");
    assert_eq!(tracker.modes(), Modes::default());
}
