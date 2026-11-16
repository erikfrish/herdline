//! The terminal modes that change what input bytes a program expects,
//! tracked from the very output the core sends, so the encoder never relies
//! on a mode the screen library does not expose. Mode semantics follow
//! Ghostty's: setting a mouse tracking or format mode replaces the previous
//! one, and resetting any of them turns tracking off or the format back to
//! X10.

/// Which mouse events the program asked for.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Tracking {
    #[default]
    Off,
    /// 9: button presses only.
    X10,
    /// 1000: presses, releases and wheel.
    Normal,
    /// 1002: and drags.
    Button,
    /// 1003: and every motion.
    Any,
}

/// How mouse reports are written.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum Encoding {
    /// `ESC [ M` and three bytes.
    #[default]
    X10,
    /// 1005: those values as UTF-8.
    Utf8,
    /// 1006: `CSI < b;x;y M|m`.
    Sgr,
    /// 1015: `CSI b;x;y M`.
    Urxvt,
    /// 1016: SGR with pixel coordinates.
    SgrPixels,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Modes {
    /// DECCKM (1): arrows as `ESC O x`.
    pub app_cursor: bool,
    /// 2004.
    pub bracketed: bool,
    /// 9001: a Windows ConPTY host reads keys as win32-input-mode records.
    pub win32: bool,
    /// 47, 1047 or 1049: the program draws on the alternate screen.
    pub alternate: bool,
    pub tracking: Tracking,
    pub encoding: Encoding,
}

impl Modes {
    pub fn mouse(&self) -> bool {
        self.tracking != Tracking::Off
    }

    fn set(&mut self, mode: u32, on: bool) {
        match mode {
            1 => self.app_cursor = on,
            2004 => self.bracketed = on,
            9001 => self.win32 = on,
            47 | 1047 | 1049 => self.alternate = on,
            9 | 1000 | 1002 | 1003 => {
                self.tracking = match (on, mode) {
                    (false, _) => Tracking::Off,
                    (true, 9) => Tracking::X10,
                    (true, 1000) => Tracking::Normal,
                    (true, 1002) => Tracking::Button,
                    _ => Tracking::Any,
                }
            }
            1005 | 1006 | 1015 | 1016 => {
                self.encoding = match (on, mode) {
                    (false, _) => Encoding::X10,
                    (true, 1005) => Encoding::Utf8,
                    (true, 1006) => Encoding::Sgr,
                    (true, 1015) => Encoding::Urxvt,
                    _ => Encoding::SgrPixels,
                }
            }
            _ => {}
        }
    }
}

/// Longest parameter string kept; a longer sequence is ignored whole.
const MAX_PARAMS: usize = 64;

#[derive(Debug, Clone, Default)]
enum State {
    #[default]
    Ground,
    Escape,
    /// Inside `CSI`: whether it is private (`?`), whether it carried an
    /// intermediate byte, and its parameters so far.
    Csi {
        private: bool,
        intermediate: bool,
        bang: bool,
        params: Vec<u8>,
        overflow: bool,
    },
}

/// Follows DEC private mode sets and resets across output chunks, however
/// a sequence is split between them.
#[derive(Debug, Clone, Default)]
pub struct Tracker {
    state: State,
    modes: Modes,
}

impl Tracker {
    pub fn modes(&self) -> Modes {
        self.modes
    }

    /// Forget everything, as a terminal reset does.
    pub fn reset(&mut self) {
        *self = Self::default();
    }

    pub fn feed(&mut self, bytes: &[u8]) {
        for &byte in bytes {
            self.step(byte);
        }
    }

    fn step(&mut self, byte: u8) {
        // CAN and SUB cancel a sequence; ESC starts a new one anywhere.
        match byte {
            0x18 | 0x1a => {
                self.state = State::Ground;
                return;
            }
            0x1b => {
                self.state = State::Escape;
                return;
            }
            _ => {}
        }
        match &mut self.state {
            State::Ground => {}
            State::Escape => {
                self.state = match byte {
                    b'[' => State::Csi {
                        private: false,
                        intermediate: false,
                        bang: false,
                        params: Vec::new(),
                        overflow: false,
                    },
                    // RIS.
                    b'c' => {
                        self.modes = Modes::default();
                        State::Ground
                    }
                    _ => State::Ground,
                };
            }
            State::Csi {
                private,
                intermediate,
                bang,
                params,
                overflow,
            } => match byte {
                b'?' if params.is_empty() && !*private => *private = true,
                0x30..=0x3f => {
                    if params.len() < MAX_PARAMS {
                        params.push(byte);
                    } else {
                        *overflow = true;
                    }
                }
                b'!' => *bang = true,
                0x20..=0x2f => *intermediate = true,
                0x40..=0x7e => {
                    let (private, intermediate, bang, overflow) =
                        (*private, *intermediate, *bang, *overflow);
                    let params = std::mem::take(params);
                    self.state = State::Ground;
                    if overflow {
                        return;
                    }
                    if bang && !private && !intermediate && byte == b'p' {
                        // DECSTR resets the cursor key mode.
                        self.modes.app_cursor = false;
                        return;
                    }
                    if !private || intermediate || bang || !matches!(byte, b'h' | b'l') {
                        return;
                    }
                    let on = byte == b'h';
                    for param in params.split(|b| *b == b';') {
                        if let Some(mode) = std::str::from_utf8(param)
                            .ok()
                            .and_then(|text| text.parse::<u32>().ok())
                        {
                            self.modes.set(mode, on);
                        }
                    }
                }
                // C0 controls inside a sequence execute without ending it.
                _ => {}
            },
        }
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
