use super::*;

fn press(code: ClientKeyCode, modifiers: u8) -> ClientPaneInputEvent {
    ClientPaneInputEvent::Key {
        code,
        modifiers,
        kind: ClientKeyKind::Press,
        repeat_count: 1,
        shifted_codepoint: None,
        generated_text: None,
        tracks_release: false,
        physical_key_id: None,
        windows_record: None,
    }
}

fn mouse_event(kind: ClientMouseKind, column: u16, row: u16) -> ClientPaneInputEvent {
    ClientPaneInputEvent::Mouse {
        kind,
        position: ClientMousePosition::Cell { column, row },
        geometry: None,
        modifiers: 0,
        lines: 1,
    }
}

fn bytes(event: &ClientPaneInputEvent, modes: Modes) -> Vec<u8> {
    let mut out = Vec::new();
    encode(event, modes, &mut out);
    out
}

fn tracking(tracking: Tracking, encoding: Encoding) -> Modes {
    Modes {
        tracking,
        encoding,
        ..Modes::default()
    }
}

#[test]
fn arrows_follow_the_cursor_key_mode_and_modifiers() {
    let app = Modes {
        app_cursor: true,
        ..Modes::default()
    };
    assert_eq!(bytes(&press(ClientKeyCode::Up, 0), Modes::default()), b"\x1b[A");
    assert_eq!(bytes(&press(ClientKeyCode::Up, 0), app), b"\x1bOA");
    assert_eq!(bytes(&press(ClientKeyCode::Left, CTRL), app), b"\x1b[1;5D");
}

#[test]
fn ctrl_and_alt_chords_send_xterm_bytes() {
    let modes = Modes::default();
    assert_eq!(bytes(&press(ClientKeyCode::Char('c'), CTRL), modes), [3]);
    assert_eq!(bytes(&press(ClientKeyCode::Char('x'), ALT), modes), b"\x1bx");
    assert_eq!(bytes(&press(ClientKeyCode::Enter, 0), modes), b"\r");
    assert_eq!(bytes(&press(ClientKeyCode::F(5), 0), modes), b"\x1b[15~");
}

#[test]
fn a_paste_is_bracketed_without_enter_and_cannot_end_early() {
    let modes = Modes {
        bracketed: true,
        ..Modes::default()
    };
    let paste = ClientPaneInputEvent::Paste("a\nb\x1b[201~c".into());
    assert_eq!(bytes(&paste, modes), b"\x1b[200~a\rbc\x1b[201~");
}

#[test]
fn win32_input_mode_sends_key_records_not_xterm_bytes() {
    let modes = Modes {
        win32: true,
        ..Modes::default()
    };
    assert_eq!(
        bytes(&press(ClientKeyCode::Char('c'), CTRL), modes),
        b"\x1b[67;0;3;1;8;1_\x1b[67;0;3;0;8;1_"
    );
    assert_eq!(
        bytes(&press(ClientKeyCode::Up, 0), modes),
        b"\x1b[38;0;0;1;256;1_\x1b[38;0;0;0;256;1_"
    );
    // A paste keeps its text, as records, with no xterm brackets.
    let paste = ClientPaneInputEvent::Paste("A\n".into());
    assert_eq!(
        bytes(&paste, Modes { bracketed: true, ..modes }),
        b"\x1b[65;0;65;1;16;1_\x1b[65;0;65;0;16;1_\x1b[13;0;13;1;0;1_\x1b[13;0;13;0;0;1_"
    );
}

#[test]
fn nothing_reaches_a_program_that_tracks_no_mouse() {
    let wheel = mouse_event(ClientMouseKind::ScrollUp, 4, 2);
    assert!(bytes(&wheel, Modes::default()).is_empty());
}

#[test]
fn mouse_reports_follow_the_format_mode() {
    let down = mouse_event(ClientMouseKind::Down(ClientMouseButton::Left), 4, 2);
    let up = mouse_event(ClientMouseKind::Up(ClientMouseButton::Left), 4, 2);
    let sgr = tracking(Tracking::Normal, Encoding::Sgr);
    assert_eq!(bytes(&down, sgr), b"\x1b[<0;5;3M");
    assert_eq!(bytes(&up, sgr), b"\x1b[<0;5;3m");
    let x10 = tracking(Tracking::Normal, Encoding::X10);
    assert_eq!(bytes(&down, x10), b"\x1b[M\x20\x25\x23");
    assert_eq!(bytes(&up, x10), b"\x1b[M\x23\x25\x23");
    let urxvt = tracking(Tracking::Normal, Encoding::Urxvt);
    assert_eq!(bytes(&up, urxvt), b"\x1b[35;5;3M");
    // No X10 byte past column 222.
    let far = mouse_event(ClientMouseKind::Down(ClientMouseButton::Left), 300, 2);
    assert!(bytes(&far, x10).is_empty());
    assert_eq!(bytes(&far, tracking(Tracking::Normal, Encoding::Utf8)), "\x1b[M\u{20}\u{14d}\u{23}".as_bytes());
}

#[test]
fn tracking_modes_report_only_their_events() {
    let drag = mouse_event(ClientMouseKind::Drag(ClientMouseButton::Left), 0, 0);
    let moved = mouse_event(ClientMouseKind::Moved, 0, 0);
    let up = mouse_event(ClientMouseKind::Up(ClientMouseButton::Left), 0, 0);
    assert!(bytes(&drag, tracking(Tracking::Normal, Encoding::Sgr)).is_empty());
    assert_eq!(bytes(&drag, tracking(Tracking::Button, Encoding::Sgr)), b"\x1b[<32;1;1M");
    assert!(bytes(&moved, tracking(Tracking::Button, Encoding::Sgr)).is_empty());
    assert_eq!(bytes(&moved, tracking(Tracking::Any, Encoding::Sgr)), b"\x1b[<35;1;1M");
    assert!(bytes(&up, tracking(Tracking::X10, Encoding::X10)).is_empty());
}
