//! Herdr's pane input events as the bytes a terminal program reads, for an
//! interactive core attach. Keys are xterm sequences, or win32-input-mode
//! records while the program (a Windows ConPTY host) has 9001 set; pastes
//! never carry Enter; mouse reports follow the tracking and format modes the
//! program set, and nothing is sent while it tracks no mouse.

use super::modes::{Encoding, Modes, Tracking};
use herdr_client::protocol::{
    ClientKeyCode, ClientKeyKind, ClientMouseButton, ClientMouseKind, ClientMousePosition,
    ClientPaneInputEvent,
};

const SHIFT: u8 = 1;
const CTRL: u8 = 2;
const ALT: u8 = 4;

/// Append `event`'s bytes to `out`. A key release, and keys with no bytes,
/// add nothing.
pub fn encode(event: &ClientPaneInputEvent, modes: Modes, out: &mut Vec<u8>) {
    match event {
        ClientPaneInputEvent::Key {
            code,
            modifiers,
            kind,
            ..
        } => {
            if *kind == ClientKeyKind::Release {
                return;
            }
            if modes.win32 {
                win32::key(code, *modifiers, out);
            } else {
                key(code, *modifiers, modes, out);
            }
        }
        ClientPaneInputEvent::TextCommit(text) => {
            if modes.win32 {
                win32::text(text, out);
            } else {
                out.extend_from_slice(text.as_bytes());
            }
        }
        ClientPaneInputEvent::Paste(text) => paste(text, modes, out),
        ClientPaneInputEvent::Mouse {
            kind,
            position,
            modifiers,
            lines,
            ..
        } => mouse(*kind, *position, *modifiers, *lines, modes, out),
    }
}

/// A paste. Line ends become CR, never a submit of their own: bracketed
/// when the program asked for it, with any bracket end inside the text
/// removed so it cannot end the paste early. Under win32-input-mode each
/// character is a key record instead, and ConPTY brackets it for its own
/// client ([INFERENCE] from ConPTY's input model; unverified here).
pub fn paste(text: &str, modes: Modes, out: &mut Vec<u8>) {
    let text = text
        .replace("\x1b[201~", "")
        .replace("\r\n", "\r")
        .replace('\n', "\r");
    if modes.win32 {
        win32::text(&text, out);
        return;
    }
    if modes.bracketed {
        out.extend_from_slice(b"\x1b[200~");
    }
    out.extend_from_slice(text.as_bytes());
    if modes.bracketed {
        out.extend_from_slice(b"\x1b[201~");
    }
}

/// xterm's modifier parameter: 1 + shift + 2·alt + 4·ctrl.
fn parameter(modifiers: u8) -> u8 {
    1 + u8::from(modifiers & SHIFT != 0)
        + 2 * u8::from(modifiers & ALT != 0)
        + 4 * u8::from(modifiers & CTRL != 0)
}

fn key(code: &ClientKeyCode, modifiers: u8, modes: Modes, out: &mut Vec<u8>) {
    let modified = modifiers & (SHIFT | ALT | CTRL) != 0;
    let m = parameter(modifiers);
    // `CSI 1;m x` with modifiers; `CSI x` or `SS3 x` without.
    let cursor = |final_byte: u8, out: &mut Vec<u8>| {
        if modified {
            out.extend_from_slice(format!("\x1b[1;{m}").as_bytes());
            out.push(final_byte);
        } else if modes.app_cursor {
            out.extend_from_slice(&[0x1b, b'O', final_byte]);
        } else {
            out.extend_from_slice(&[0x1b, b'[', final_byte]);
        }
    };
    let tilde = |number: u8, out: &mut Vec<u8>| {
        if modified {
            out.extend_from_slice(format!("\x1b[{number};{m}~").as_bytes());
        } else {
            out.extend_from_slice(format!("\x1b[{number}~").as_bytes());
        }
    };
    let alt = |out: &mut Vec<u8>| {
        if modifiers & ALT != 0 {
            out.push(0x1b);
        }
    };
    match code {
        ClientKeyCode::Up => cursor(b'A', out),
        ClientKeyCode::Down => cursor(b'B', out),
        ClientKeyCode::Right => cursor(b'C', out),
        ClientKeyCode::Left => cursor(b'D', out),
        ClientKeyCode::Home => cursor(b'H', out),
        ClientKeyCode::End => cursor(b'F', out),
        ClientKeyCode::Insert => tilde(2, out),
        ClientKeyCode::Delete => tilde(3, out),
        ClientKeyCode::PageUp => tilde(5, out),
        ClientKeyCode::PageDown => tilde(6, out),
        ClientKeyCode::F(n @ 1..=4) => {
            let final_byte = b'P' + (n - 1);
            if modified {
                out.extend_from_slice(format!("\x1b[1;{m}").as_bytes());
                out.push(final_byte);
            } else {
                out.extend_from_slice(&[0x1b, b'O', final_byte]);
            }
        }
        ClientKeyCode::F(n @ 5..=12) => {
            const NUMBERS: [u8; 8] = [15, 17, 18, 19, 20, 21, 23, 24];
            tilde(NUMBERS[usize::from(n - 5)], out);
        }
        ClientKeyCode::F(_) => {}
        ClientKeyCode::Enter => {
            alt(out);
            out.push(b'\r');
        }
        ClientKeyCode::Tab => {
            alt(out);
            out.push(b'\t');
        }
        ClientKeyCode::BackTab => out.extend_from_slice(b"\x1b[Z"),
        ClientKeyCode::Backspace => {
            alt(out);
            out.push(if modifiers & CTRL != 0 { 0x08 } else { 0x7f });
        }
        ClientKeyCode::Esc => {
            alt(out);
            out.push(0x1b);
        }
        ClientKeyCode::Null => out.push(0),
        ClientKeyCode::Char(ch) => {
            alt(out);
            match control(*ch).filter(|_| modifiers & CTRL != 0) {
                Some(byte) => out.push(byte),
                None => push_char(*ch, out),
            }
        }
    }
}

fn push_char(ch: char, out: &mut Vec<u8>) {
    let mut buf = [0u8; 4];
    out.extend_from_slice(ch.encode_utf8(&mut buf).as_bytes());
}

/// The C0 byte Ctrl makes of `ch`, as xterm sends it.
fn control(ch: char) -> Option<u8> {
    let byte = u8::try_from(u32::from(ch)).ok()?;
    match byte {
        b'a'..=b'z' => Some(byte - b'a' + 1),
        b'A'..=b'Z' => Some(byte - b'A' + 1),
        b' ' | b'@' | b'2' => Some(0),
        b'[' | b'3' => Some(0x1b),
        b'\\' | b'4' => Some(0x1c),
        b']' | b'5' => Some(0x1d),
        b'^' | b'6' => Some(0x1e),
        b'_' | b'/' | b'7' => Some(0x1f),
        b'?' | b'8' => Some(0x7f),
        _ => None,
    }
}

/// A mouse report in the program's tracking mode and format, or nothing
/// when the mode does not report this event. Coordinates are 0-based cells.
fn mouse(
    kind: ClientMouseKind,
    position: ClientMousePosition,
    modifiers: u8,
    lines: u16,
    modes: Modes,
    out: &mut Vec<u8>,
) {
    let button = |button: ClientMouseButton| match button {
        ClientMouseButton::Left => 0u32,
        ClientMouseButton::Middle => 1,
        ClientMouseButton::Right => 2,
    };
    let wheel = |code: u32| (code, false, lines.max(1));
    let (code, release, repeat) = match (modes.tracking, kind) {
        (Tracking::Off, _) => return,
        (Tracking::X10, ClientMouseKind::Down(b)) => (button(b), false, 1),
        (Tracking::X10, _) => return,
        (_, ClientMouseKind::Down(b)) => (button(b), false, 1),
        (_, ClientMouseKind::Up(b)) => (button(b), true, 1),
        (_, ClientMouseKind::ScrollUp) => wheel(64),
        (_, ClientMouseKind::ScrollDown) => wheel(65),
        (_, ClientMouseKind::ScrollLeft) => wheel(66),
        (_, ClientMouseKind::ScrollRight) => wheel(67),
        (Tracking::Button | Tracking::Any, ClientMouseKind::Drag(b)) => (button(b) + 32, false, 1),
        (Tracking::Any, ClientMouseKind::Moved) => (35, false, 1),
        (_, ClientMouseKind::Drag(_) | ClientMouseKind::Moved) => return,
    };
    // X10 mode reports no modifiers.
    let code = if modes.tracking == Tracking::X10 {
        code
    } else {
        code + 4 * u32::from(modifiers & SHIFT != 0)
            + 8 * u32::from(modifiers & ALT != 0)
            + 16 * u32::from(modifiers & CTRL != 0)
    };
    let (column, row, pixels) = match position {
        ClientMousePosition::Cell { column, row } => (u32::from(column), u32::from(row), None),
        ClientMousePosition::Pixels { x, y, column, row } => {
            (u32::from(column), u32::from(row), Some((x, y)))
        }
    };
    // Formats without their own release report it as button 3.
    let plain = if release { 3 + (code & !3) } else { code };
    let mut report = Vec::new();
    match modes.encoding {
        Encoding::Sgr => {
            let end = if release { 'm' } else { 'M' };
            report.extend_from_slice(format!("\x1b[<{code};{};{}{end}", column + 1, row + 1).as_bytes());
        }
        Encoding::SgrPixels => {
            // Pixel reports need pixel positions; a cell-only event has none.
            let Some((x, y)) = pixels else { return };
            let end = if release { 'm' } else { 'M' };
            report.extend_from_slice(format!("\x1b[<{code};{};{}{end}", x + 1, y + 1).as_bytes());
        }
        Encoding::Urxvt => {
            report.extend_from_slice(format!("\x1b[{};{};{}M", plain + 32, column + 1, row + 1).as_bytes());
        }
        Encoding::X10 => {
            let values = [plain + 32, column + 33, row + 33];
            // A cell past 223 has no X10 byte: no report rather than a wrong one.
            let Ok(bytes) = values.map(u8::try_from).into_iter().collect::<Result<Vec<u8>, _>>() else {
                return;
            };
            report.extend_from_slice(b"\x1b[M");
            report.extend_from_slice(&bytes);
        }
        Encoding::Utf8 => {
            report.extend_from_slice(b"\x1b[M");
            for value in [plain + 32, column + 33, row + 33] {
                // 1005 encodes up to 2047.
                let Some(ch) = char::from_u32(value).filter(|_| value < 2048) else {
                    return;
                };
                push_char(ch, &mut report);
            }
        }
    }
    for _ in 0..repeat {
        out.extend_from_slice(&report);
    }
}

/// win32-input-mode (`CSI ? 9001 h`): each key is a `KEY_EVENT_RECORD`,
/// `CSI Vk;Sc;Uc;Kd;Cs;Rc _`, a down and then an up record.
mod win32 {
    use super::{ALT, CTRL, ClientKeyCode, SHIFT, control};

    const SHIFT_PRESSED: u32 = 0x10;
    const LEFT_CTRL_PRESSED: u32 = 0x08;
    const LEFT_ALT_PRESSED: u32 = 0x02;
    const ENHANCED_KEY: u32 = 0x100;

    fn record(vk: u32, uc: u32, state: u32, out: &mut Vec<u8>) {
        for down in [1, 0] {
            out.extend_from_slice(format!("\x1b[{vk};0;{uc};{down};{state};1_").as_bytes());
        }
    }

    fn state(modifiers: u8) -> u32 {
        let mut state = 0;
        if modifiers & SHIFT != 0 {
            state |= SHIFT_PRESSED;
        }
        if modifiers & CTRL != 0 {
            state |= LEFT_CTRL_PRESSED;
        }
        if modifiers & ALT != 0 {
            state |= LEFT_ALT_PRESSED;
        }
        state
    }

    /// The virtual key of a character on a US layout, and whether Shift
    /// makes it; 0 for characters a layout does not name.
    fn char_key(ch: char) -> (u32, bool) {
        match ch {
            'a'..='z' => (u32::from(ch) - 32, false),
            'A'..='Z' => (u32::from(ch), true),
            '0'..='9' => (u32::from(ch), false),
            ' ' => (0x20, false),
            '\r' => (0x0d, false),
            '\t' => (0x09, false),
            _ => (0, false),
        }
    }

    fn char_record(ch: char, modifiers: u8, out: &mut Vec<u8>) {
        let (vk, shifted) = char_key(ch);
        let mut state = state(modifiers);
        if shifted {
            state |= SHIFT_PRESSED;
        }
        let ctrl = control(ch).filter(|_| modifiers & CTRL != 0);
        if let Some(byte) = ctrl {
            record(vk, u32::from(byte), state, out);
            return;
        }
        let mut units = [0u16; 2];
        for unit in ch.encode_utf16(&mut units) {
            record(vk, u32::from(*unit), state, out);
        }
    }

    pub fn text(text: &str, out: &mut Vec<u8>) {
        for ch in text.chars() {
            char_record(ch, 0, out);
        }
    }

    pub fn key(code: &ClientKeyCode, modifiers: u8, out: &mut Vec<u8>) {
        let s = state(modifiers);
        let (vk, uc, enhanced) = match code {
            ClientKeyCode::Enter => (0x0d, 0x0d, false),
            ClientKeyCode::Tab => (0x09, 0x09, false),
            ClientKeyCode::BackTab => {
                record(0x09, 0x09, s | SHIFT_PRESSED, out);
                return;
            }
            ClientKeyCode::Backspace => (0x08, 0x08, false),
            ClientKeyCode::Esc => (0x1b, 0x1b, false),
            ClientKeyCode::PageUp => (0x21, 0, true),
            ClientKeyCode::PageDown => (0x22, 0, true),
            ClientKeyCode::End => (0x23, 0, true),
            ClientKeyCode::Home => (0x24, 0, true),
            ClientKeyCode::Left => (0x25, 0, true),
            ClientKeyCode::Up => (0x26, 0, true),
            ClientKeyCode::Right => (0x27, 0, true),
            ClientKeyCode::Down => (0x28, 0, true),
            ClientKeyCode::Insert => (0x2d, 0, true),
            ClientKeyCode::Delete => (0x2e, 0, true),
            ClientKeyCode::F(n @ 1..=12) => (0x70 + u32::from(*n) - 1, 0, false),
            ClientKeyCode::F(_) => return,
            ClientKeyCode::Null => {
                record(0x32, 0, s | LEFT_CTRL_PRESSED, out);
                return;
            }
            ClientKeyCode::Char(ch) => {
                char_record(*ch, modifiers, out);
                return;
            }
        };
        record(vk, uc, if enhanced { s | ENHANCED_KEY } else { s }, out);
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
