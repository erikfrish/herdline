//! One `herdr-core connect` run on the host over the route's SSH session:
//! the remote command, the generation-1 `hello` that switches the stream to
//! framed mode, the writer every thread shares, and the reader thread that
//! forwards frames to the pump.

use super::wire::{self, Frame, Kind};
use crate::{Error, Result};
use crossbeam_channel::Sender;
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::{
    io::{ErrorKind, Read},
    net::Shutdown,
    os::unix::net::UnixStream,
    sync::{
        Mutex, PoisonError,
        atomic::{AtomicU64, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

/// The methods this backend calls; a core lacking one cannot serve it.
pub const USES: [&str; 11] = [
    "terminal.run",
    "terminal.list",
    "terminal.kill",
    "terminal.label",
    "terminal.attach",
    "terminal.keep",
    "doc.get",
    "doc.set",
    "events.subscribe",
    "terminal.send",
    "server.status",
];

/// How long the host may take to answer `hello` once the channel is up.
const HELLO_TIMEOUT: Duration = Duration::from_secs(20);
/// Longest line read before the hello reply: banners and the reply itself.
const LINE_LIMIT: usize = 1 << 20;
/// A write that cannot leave for this long means the link is gone.
const WRITE_TIMEOUT: Duration = Duration::from_secs(10);
/// What the remote command prints when the host has no herdr-core.
const NOT_INSTALLED: &str = "herdr-core is not installed here";

/// The core's answer to `hello`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Hello {
    pub version: String,
    pub features: Vec<String>,
}

/// A session name the core itself accepts (core `paths.rs`): no `.`, `..`,
/// control character or path separator. Empty means the core's default.
pub fn valid_session(session: &str) -> bool {
    session.is_empty()
        || (session != "."
            && session != ".."
            && !session
                .chars()
                .any(|c| c.is_control() || "/\\:*?\"<>|".contains(c)))
}

/// The command run on the host: the user's `herdr-core`, found as hc finds
/// its own on remote machines, connected to session `session`.
pub fn command(session: &str) -> String {
    let session = session.trim();
    let env = if session.is_empty() {
        String::new()
    } else {
        format!("HC_SESSION={} ", shell_quote(session))
    };
    let script = format!(
        "p=$(command -v herdr-core) || p=$HOME/.local/bin/herdr-core; [ -x \"$p\" ] || {{ echo {NOT_INSTALLED:?}; exit 127; }}; {env}exec \"$p\" connect"
    );
    format!("sh -c {}", shell_quote(&script))
}

/// The writer half of the link, shared by the pump and the UI thread.
#[derive(Debug)]
pub struct Link {
    stream: Mutex<UnixStream>,
    next: AtomicU64,
}

impl Link {
    pub fn new(stream: UnixStream) -> Result<Self> {
        stream.set_write_timeout(Some(WRITE_TIMEOUT))?;
        Ok(Self {
            stream: Mutex::new(stream),
            // 0 was hello's.
            next: AtomicU64::new(1),
        })
    }

    /// A fresh JSON-RPC id.
    pub fn id(&self) -> u64 {
        self.next.fetch_add(1, Ordering::Relaxed)
    }

    pub fn frame(&self, channel: u32, kind: Kind, payload: &[u8]) -> Result<()> {
        let mut stream = self.stream.lock().unwrap_or_else(PoisonError::into_inner);
        wire::write(&mut *stream, channel, kind, payload)
    }

    /// Call `method` as `id` on the control channel.
    pub fn call(&self, id: u64, method: &str, params: Value) -> Result<()> {
        let message = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        self.frame(wire::CONTROL, Kind::Data, message.to_string().as_bytes())
    }

    /// End the link: the reader sees its end and the core detaches every
    /// stream of this connection. Terminals keep running.
    pub fn close(&self) {
        let stream = self.stream.lock().unwrap_or_else(PoisonError::into_inner);
        let _ = stream.shutdown(Shutdown::Both);
    }
}

fn line(stream: &mut UnixStream, deadline: Instant) -> Result<Option<Vec<u8>>> {
    let mut line = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        if Instant::now() > deadline {
            return Err(Error::Timeout);
        }
        match stream.read(&mut byte) {
            Ok(0) => return Ok((!line.is_empty()).then_some(line)),
            Ok(_) if byte[0] == b'\n' => return Ok(Some(line)),
            Ok(_) if line.len() < LINE_LIMIT => line.push(byte[0]),
            Ok(_) => return Err(Error::CoreProtocol("a line before hello is too long")),
            Err(error) if matches!(error.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {}
            Err(error) if error.kind() == ErrorKind::Interrupted => {}
            Err(error) => return Err(error.into()),
        }
    }
}

/// Say `hello` with generation 1 and framed mode, skipping whatever the
/// host prints before the reply (banners, MOTD). Every byte after the
/// reply's line is a frame.
pub fn hello(stream: &mut UnixStream) -> Result<Hello> {
    use std::io::Write;
    stream.write_all(
        b"{\"jsonrpc\":\"2.0\",\"id\":0,\"method\":\"hello\",\"params\":{\"generation\":1,\"framed\":true,\"features\":[\"resize_notifications\",\"automatic_input\"]}}\n",
    )?;
    let deadline = Instant::now() + HELLO_TIMEOUT;
    let mut answered = false;
    let reply = loop {
        let Some(line) = line(stream, deadline)? else {
            // The channel ended before any JSON: no core ran.
            return Err(if answered {
                Error::CoreProtocol("the link ended before hello was answered")
            } else {
                Error::CoreMissing
            });
        };
        if String::from_utf8_lossy(&line).contains(NOT_INSTALLED) {
            return Err(Error::CoreMissing);
        }
        let Ok(message) = serde_json::from_slice::<Value>(&line) else {
            continue;
        };
        answered = true;
        if message.get("id").and_then(Value::as_u64) == Some(0) {
            break message;
        }
    };
    if let Some(error) = reply.get("error") {
        let kind = error["data"]["kind"].as_str().unwrap_or_default();
        if kind == "unsupported_generation" {
            return Err(Error::CoreGeneration(0));
        }
        return Err(Error::CoreError {
            kind: kind.to_owned(),
            message: error["message"].as_str().unwrap_or_default().to_owned(),
        });
    }
    let result = &reply["result"];
    let generation = result["generation"].as_u64().unwrap_or(0);
    if generation != 1 {
        return Err(Error::CoreGeneration(generation));
    }
    let strings = |key: &str| -> Vec<String> {
        result[key]
            .as_array()
            .map(|items| items.iter().filter_map(Value::as_str).map(str::to_owned).collect())
            .unwrap_or_default()
    };
    let methods = strings("methods");
    if let Some(missing) = USES.iter().find(|m| !methods.iter().any(|have| have == *m)) {
        return Err(Error::CoreLacks((*missing).to_owned()));
    }
    if result["framed"].as_bool() != Some(true) {
        return Err(Error::CoreProtocol("hello did not switch to framed mode"));
    }
    Ok(Hello {
        version: result["version"].as_str().unwrap_or_default().to_owned(),
        features: strings("features"),
    })
}

/// What the reader hands the pump.
#[derive(Debug)]
pub enum Incoming {
    Frame(Frame),
    /// The link ended: cleanly (`None`) or with a broken frame.
    Ended(Option<String>),
}

/// Read frames until the link ends, blocking; each goes to `to`, which
/// bounds how far the reader runs ahead of the pump.
pub fn reader(mut stream: UnixStream, to: Sender<Incoming>) -> Result<()> {
    stream.set_read_timeout(None)?;
    thread::Builder::new()
        .name("herdline-core-read".into())
        .spawn(move || {
            loop {
                match wire::read(&mut stream) {
                    Ok(Some(frame)) => {
                        if to.send(Incoming::Frame(frame)).is_err() {
                            return;
                        }
                    }
                    Ok(None) => {
                        let _ = to.send(Incoming::Ended(None));
                        return;
                    }
                    Err(error) => {
                        let _ = to.send(Incoming::Ended(Some(error.to_string())));
                        return;
                    }
                }
            }
        })?;
    Ok(())
}
