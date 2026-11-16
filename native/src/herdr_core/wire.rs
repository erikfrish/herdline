//! herdr-core's framed mode (core `schema/README.md` "Framed mode", 0.1.21):
//! every frame is `[channel u32 LE][kind u8][len u32 LE][payload]`, payload
//! at most 16 MiB. Channel 0 carries one JSON-RPC message per DATA frame;
//! other channels carry attach or event streams.

use crate::{Error, Result};
use std::io::{Read, Write};

pub const HEADER: usize = 9;
pub const MAX_PAYLOAD: usize = 16 << 20;

/// The control channel: JSON-RPC both ways.
pub const CONTROL: u32 = 0;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum Kind {
    Data = 0,
    Credit = 1,
    Close = 2,
    Resize = 3,
    Focus = 4,
    Facts = 5,
    AutomaticInput = 6,
}

impl Kind {
    fn from_byte(byte: u8) -> Option<Self> {
        Some(match byte {
            0 => Self::Data,
            1 => Self::Credit,
            2 => Self::Close,
            3 => Self::Resize,
            4 => Self::Focus,
            5 => Self::Facts,
            6 => Self::AutomaticInput,
            _ => return None,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub channel: u32,
    /// `None` for a kind this build does not know; it is skipped, as old
    /// cores skip unknown local kinds.
    pub kind: Option<Kind>,
    pub payload: Vec<u8>,
}

pub fn encode(channel: u32, kind: Kind, payload: &[u8], out: &mut Vec<u8>) -> Result<()> {
    let len = u32::try_from(payload.len())
        .ok()
        .filter(|len| *len as usize <= MAX_PAYLOAD)
        .ok_or(Error::CoreProtocol("frame payload over 16 MiB"))?;
    out.extend_from_slice(&channel.to_le_bytes());
    out.push(kind as u8);
    out.extend_from_slice(&len.to_le_bytes());
    out.extend_from_slice(payload);
    Ok(())
}

pub fn write(to: &mut impl Write, channel: u32, kind: Kind, payload: &[u8]) -> Result<()> {
    let mut out = Vec::with_capacity(HEADER + payload.len());
    encode(channel, kind, payload, &mut out)?;
    to.write_all(&out)?;
    Ok(())
}

/// One whole frame, or `None` at a clean end between frames.
pub fn read(from: &mut impl Read) -> Result<Option<Frame>> {
    let mut header = [0u8; HEADER];
    let mut filled = 0;
    while filled < HEADER {
        match from.read(&mut header[filled..]) {
            Ok(0) if filled == 0 => return Ok(None),
            Ok(0) => return Err(Error::CoreProtocol("stream ended inside a frame")),
            Ok(n) => filled += n,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => {}
            Err(error) => return Err(error.into()),
        }
    }
    let channel = u32::from_le_bytes([header[0], header[1], header[2], header[3]]);
    let len = u32::from_le_bytes([header[5], header[6], header[7], header[8]]) as usize;
    if len > MAX_PAYLOAD {
        return Err(Error::CoreProtocol("frame payload over 16 MiB"));
    }
    let mut payload = vec![0u8; len];
    from.read_exact(&mut payload)?;
    Ok(Some(Frame {
        channel,
        kind: Kind::from_byte(header[4]),
        payload,
    }))
}

/// `[cols u16 LE][rows u16 LE]`, as RESIZE carries it.
pub fn size(cols: u16, rows: u16) -> [u8; 4] {
    let [c0, c1] = cols.to_le_bytes();
    let [r0, r1] = rows.to_le_bytes();
    [c0, c1, r0, r1]
}

pub fn parse_size(payload: &[u8]) -> Option<(u16, u16)> {
    match payload {
        [c0, c1, r0, r1] => Some((u16::from_le_bytes([*c0, *c1]), u16::from_le_bytes([*r0, *r1]))),
        _ => None,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
