//! The `herdline-core` thread: connects, then owns the link's writer, the
//! document, the terminal records and every libghostty terminal.

use super::{
    Command, Shared,
    doc::{self, Edit},
    identity::{Claim, Verdict},
    link::{self, Incoming, Link},
    lock,
    modes::Tracker,
    wire::{self, Frame, Kind},
};
use crate::{
    Error, Result,
    composer::Registry,
    screen::Reader,
    session::{UiEvent, connector_refusal},
    ssh::{self, Route},
    theme::Theme,
};
use crossbeam_channel::{Receiver, Sender, bounded, select};
use herdr_client::ConnectOptions;
use libghostty_vt::{Terminal, terminal::Options};
use serde_json::{Value, json};
use std::{
    collections::{HashMap, HashSet},
    sync::{Arc, atomic::Ordering},
    time::{Duration, Instant},
};

/// Frames the reader may run ahead of the pump.
const INCOMING_CAPACITY: usize = 256;
/// Host checks waiting to be applied.
const VERIFIED_CAPACITY: usize = 64;
/// How often deadlines are looked at with nothing else to do.
const TICK: Duration = Duration::from_millis(500);
/// How long `server.status` may take to prove the link alive.
const LIVENESS: Duration = Duration::from_secs(8);
/// Longest event line kept while its newline has not arrived.
const LINE_LIMIT: usize = 4 << 20;
/// Frames for a channel whose attach is not answered yet.
const EARLY_FRAMES: usize = 64;
/// Channels with such frames.
const EARLY_CHANNELS: usize = 32;
/// Scrollback rows each phone-side terminal keeps.
const SCROLLBACK: usize = 10_000;
/// Scrollback the core repaints on attach.
const HISTORY: usize = 1000;

/// One terminal this phone draws.
pub(super) struct Term {
    pub vt: Terminal<'static, 'static>,
    pub tracker: Tracker,
    pub channel: Option<u32>,
    pub observe: bool,
    /// The core ended its stream; it is attached again only after
    /// `restarted`, `created` or a fresh snapshot.
    pub ended: bool,
    /// Rows scrolled back from the live bottom, locally.
    pub offset: usize,
    /// The size last sent while interactive.
    pub sent: (u16, u16),
}

/// One edit the phone makes, from the UI's request to its answer.
#[derive(Debug, Clone)]
pub(super) struct Flow {
    pub ui: String,
    pub edit: Edit,
    /// The terminal `terminal.run` made for it, once answered.
    pub created: Option<String>,
    /// What the edit acts on in the document it was first applied to.
    pub target: Option<Value>,
    pub retried: bool,
    /// The explicit refusal waiting on a fresh document before it is
    /// answered: cleanup is decided against that document only.
    pub refusal: Option<(String, String)>,
    pub focus: bool,
}

pub(super) enum Pending {
    Subscribe,
    Status { startup: bool },
    Doc(Option<Flow>),
    Run(Flow),
    Set {
        flow: Flow,
        value: Value,
        removed: Vec<String>,
    },
    Attach {
        terminal: String,
        observe: bool,
        /// For an interactive attach: the pane and the UI request.
        ui: Option<(String, String)>,
    },
    Send { receipt: String },
    /// Composer DATA written before this call: any answer means the core
    /// took it, not that the program read it.
    Typed { receipt: String },
    /// A core call answered as is under the UI's `id`.
    Plain { ui: String },
    Kill,
}

/// Phone-private focus and zoom.
#[derive(Debug, Default)]
pub(super) struct Focus {
    pub space: Option<String>,
    /// Active tab by space.
    pub tabs: HashMap<String, String>,
    /// Focused pane by tab.
    pub panes: HashMap<String, String>,
    /// Zoomed pane by tab.
    pub zoomed: HashMap<String, String>,
}

/// Agent claims and what the host found for them, by terminal.
#[derive(Debug, Default)]
pub(super) struct Claims {
    pub current: HashMap<String, (String, Claim)>,
    pub verdicts: HashMap<String, Verdict>,
    pub inflight: HashSet<String>,
    pub again: HashSet<String>,
}

pub(super) type Verified = (String, Claim, Option<Verdict>);

pub(super) struct Pump {
    pub shared: Arc<Shared>,
    pub link: Link,
    pub exec: ssh::Exec,
    pub theme: Theme,
    pub reader: Reader,
    pub server_version: String,
    pub boot: String,
    /// The core run: its id, pid and uptime at the last `server.status`.
    pub server: Option<(String, u64, u64)>,
    pub doc: Value,
    pub version: u64,
    pub fetching: bool,
    pub records: HashMap<String, Value>,
    pub terms: HashMap<String, Term>,
    pub channels: HashMap<u32, String>,
    pub closing: HashSet<u32>,
    pub attaching: HashSet<String>,
    pub events_channel: Option<u32>,
    pub line: Vec<u8>,
    pub early: HashMap<u32, Vec<Frame>>,
    pub pending: HashMap<u64, Pending>,
    pub liveness: Option<(u64, Instant)>,
    pub focus: Focus,
    /// Interactive terminal by pane.
    pub interactive: HashMap<String, String>,
    /// The pane each terminal shows, from the document.
    pub pane_of: HashMap<String, String>,
    pub terminal_of: HashMap<String, String>,
    pub claims: Claims,
    pub verified: Sender<Verified>,
    pub size: (u16, u16),
    pub cell: (u32, u32),
    pub revision: u64,
    pub dirty_view: bool,
    pub dirty_frame: bool,
    pub dirty_sync: bool,
    pub ended: bool,
}

/// What a connect failure tells the UI.
fn failure(error: &Error) -> UiEvent {
    match error {
        Error::CoreMissing => UiEvent::CoreMissing,
        Error::CoreGeneration(generation) => UiEvent::VersionMismatch {
            daemon_outdated: *generation < 1,
            server_version: None,
        },
        Error::CoreLacks(_) => UiEvent::VersionMismatch {
            daemon_outdated: true,
            server_version: None,
        },
        _ => connector_refusal(error).unwrap_or_else(|| UiEvent::Disconnected {
            reason: error.to_string(),
        }),
    }
}

fn open(
    shared: &Shared,
    route: Route,
) -> Result<(Link, std::os::unix::net::UnixStream, ssh::Exec, link::Hello)> {
    let (mut stream, exec) = ssh::open_command(route, link::command(&shared.session), &shared.stop)?;
    let hello = link::hello(&mut stream)?;
    let reader = stream.try_clone()?;
    *lock(&shared.closer) = Some(stream.try_clone()?);
    Ok((Link::new(stream)?, reader, exec, hello))
}

pub(super) fn run(
    shared: Arc<Shared>,
    route: Route,
    options: ConnectOptions,
    theme: Theme,
    commands: Receiver<Command>,
) {
    let (link, stream, exec, hello) = match open(&shared, route) {
        Ok(opened) => opened,
        Err(error) => {
            shared.emit(failure(&error));
            return;
        }
    };
    tracing::debug!(version = %hello.version, features = ?hello.features, "herdr-core hello");
    let (incoming_tx, incoming) = bounded(INCOMING_CAPACITY);
    let (verified, verified_rx) = bounded(VERIFIED_CAPACITY);
    let reader = match Reader::new() {
        Ok(reader) => reader,
        Err(error) => {
            link.close();
            shared.emit(failure(&error));
            return;
        }
    };
    if let Err(error) = link::reader(stream, incoming_tx) {
        link.close();
        shared.emit(failure(&error));
        return;
    }
    *lock(&shared.exec) = Some(exec.clone());
    let mut pump = Pump {
        boot: String::new(),
        shared,
        link,
        exec,
        theme,
        reader,
        server_version: hello.version,
        server: None,
        doc: Value::Null,
        version: 0,
        fetching: false,
        records: HashMap::new(),
        terms: HashMap::new(),
        channels: HashMap::new(),
        closing: HashSet::new(),
        attaching: HashSet::new(),
        events_channel: None,
        line: Vec::new(),
        early: HashMap::new(),
        pending: HashMap::new(),
        liveness: None,
        focus: Focus::default(),
        interactive: HashMap::new(),
        pane_of: HashMap::new(),
        terminal_of: HashMap::new(),
        claims: Claims::default(),
        verified,
        size: (
            options.surface_size.cols.max(1),
            options.surface_size.rows.max(1),
        ),
        cell: (options.cell_width_px, options.cell_height_px),
        revision: 0,
        dirty_view: false,
        dirty_frame: false,
        dirty_sync: false,
        ended: false,
    };
    if let Err(error) = pump.call("events.subscribe", json!({}), Pending::Subscribe) {
        pump.end(error.to_string());
        return;
    }
    pump.run(incoming, commands, verified_rx);
}

impl Pump {
    fn run(
        &mut self,
        incoming: Receiver<Incoming>,
        commands: Receiver<Command>,
        verified: Receiver<Verified>,
    ) {
        while !self.ended {
            if self.shared.stop.load(Ordering::Acquire) {
                self.end("disconnected".to_owned());
                break;
            }
            let step = select! {
                recv(incoming) -> message => match message {
                    Ok(Incoming::Frame(frame)) => self.frame(frame),
                    Ok(Incoming::Ended(reason)) => Err(Error::CoreError {
                        kind: "link".to_owned(),
                        message: reason.unwrap_or_else(|| "disconnected".to_owned()),
                    }),
                    Err(_) => Err(Error::CoreProtocol("the reader stopped")),
                },
                recv(commands) -> command => match command {
                    Ok(command) => self.command(command),
                    // The UI dropped its Core.
                    Err(_) => Err(Error::CoreError {
                        kind: "link".to_owned(),
                        message: "disconnected".to_owned(),
                    }),
                },
                recv(verified) -> result => {
                    if let Ok(result) = result {
                        self.verified(result);
                    }
                    Ok(())
                },
                default(TICK) => self.tick(),
            };
            // Whatever else already arrived is applied before one render.
            let step = step.and_then(|()| {
                for _ in 0..INCOMING_CAPACITY {
                    match incoming.try_recv() {
                        Ok(Incoming::Frame(frame)) => self.frame(frame)?,
                        Ok(Incoming::Ended(reason)) => {
                            return Err(Error::CoreError {
                                kind: "link".to_owned(),
                                message: reason.unwrap_or_else(|| "disconnected".to_owned()),
                            });
                        }
                        Err(_) => break,
                    }
                }
                self.flush()
            });
            if let Err(error) = step {
                let reason = match error {
                    Error::CoreError { kind, message } if kind == "link" => message,
                    error => error.to_string(),
                };
                self.end(reason);
            }
        }
    }

    pub(super) fn call(&mut self, method: &str, params: Value, pending: Pending) -> Result<()> {
        let id = self.link.id();
        self.pending.insert(id, pending);
        self.link.call(id, method, params)
    }

    pub(super) fn emit(&self, event: UiEvent) {
        self.shared.emit(event);
    }

    pub(super) fn answer(&self, id: &str, result: Value) {
        self.shared
            .respond(id, json!({"id": id, "result": result}));
    }

    pub(super) fn refuse(&self, id: &str, kind: &str, message: &str) {
        self.shared.respond(
            id,
            json!({"id": id, "error": {"kind": kind, "reason": kind, "message": message}}),
        );
    }

    fn tick(&mut self) -> Result<()> {
        if let Some((_, deadline)) = self.liveness
            && Instant::now() > deadline
        {
            return Err(Error::CoreError {
                kind: "link".to_owned(),
                message: "timeout".to_owned(),
            });
        }
        Ok(())
    }

    /// The link is gone. Every unanswered edit is uncertain: nothing is
    /// retried or cleaned up, since any of it may have taken effect.
    pub(super) fn end(&mut self, reason: String) {
        if self.ended {
            return;
        }
        self.ended = true;
        for (_, pending) in self.pending.drain().collect::<Vec<_>>() {
            let ui = match pending {
                Pending::Run(flow) | Pending::Set { flow, .. } | Pending::Doc(Some(flow)) => {
                    flow.ui
                }
                Pending::Attach {
                    ui: Some((_, id)), ..
                }
                | Pending::Plain { ui: id } => id,
                // A send left unanswered stays held until the user settles it.
                _ => continue,
            };
            self.refuse(&ui, "uncertain", "uncertain");
        }
        self.link.close();
        *lock(&self.shared.exec) = None;
        *lock(&self.shared.closer) = None;
        *lock(&self.shared.registry) = Registry::default();
        lock(&self.shared.instances).clear();
        lock(&self.shared.interactive).clear();
        self.shared.emit(UiEvent::Disconnected { reason });
    }

    fn command(&mut self, command: Command) -> Result<()> {
        match command {
            Command::Request { id, method, params } => self.request(id, &method, &params),
            Command::Input { pane, events } => self.input(pane, &events),
            Command::Touch {
                pane,
                kind,
                column,
                row,
                lines,
            } => self.touch(&pane, kind, column, row, lines),
            Command::Resize(options) => {
                self.size = (
                    options.surface_size.cols.max(1),
                    options.surface_size.rows.max(1),
                );
                self.cell = (options.cell_width_px, options.cell_height_px);
                self.dirty_frame = true;
                self.dirty_sync = true;
                Ok(())
            }
            Command::Theme(theme) => {
                self.theme = theme;
                for term in self.terms.values_mut() {
                    if let Err(error) = theme.apply(&mut term.vt) {
                        tracing::warn!(%error, "theme not applied");
                    }
                }
                self.dirty_frame = true;
                Ok(())
            }
            Command::Send {
                receipt,
                pane,
                text,
            } => {
                let Some(terminal) = self.terminal_of.get(&pane).cloned() else {
                    // Nothing went: the answer releases the hold.
                    crate::hold::holds().answered(self.shared.link, &receipt);
                    self.refuse(&receipt, "missing", "pane_missing");
                    return Ok(());
                };
                self.call(
                    "terminal.send",
                    json!({"terminal": terminal, "text": text}),
                    Pending::Send { receipt },
                )
            }
            Command::Typed {
                receipt,
                target,
                pane,
                events,
            } => self.typed(receipt, &target, &pane, &events),
            Command::Call {
                id,
                method,
                params,
                fence,
            } => {
                if let Some((pane, terminal)) = fence
                    && self.terminal_of.get(&pane) != Some(&terminal)
                {
                    self.refuse(&id, "pane_changed", "pane_changed");
                    return Ok(());
                }
                self.call(method, params, Pending::Plain { ui: id })
            }
            Command::Liveness => {
                if self.liveness.is_some() {
                    return Ok(());
                }
                let id = self.link.id();
                self.pending.insert(id, Pending::Status { startup: false });
                self.liveness = Some((id, Instant::now() + LIVENESS));
                self.link.call(id, "server.status", json!({}))
            }
            Command::Disconnect => {
                self.end("disconnected".to_owned());
                Ok(())
            }
        }
    }

    fn frame(&mut self, frame: Frame) -> Result<()> {
        let channel = frame.channel;
        if channel == wire::CONTROL {
            if frame.kind == Some(Kind::Data) {
                self.control(&frame.payload)?;
            }
            return Ok(());
        }
        if Some(channel) == self.events_channel {
            return match frame.kind {
                Some(Kind::Data) => {
                    self.credit(channel, frame.payload.len())?;
                    self.lines(&frame.payload)
                }
                Some(Kind::Close) => Err(Error::CoreProtocol("the core ended the event stream")),
                _ => Ok(()),
            };
        }
        if self.closing.contains(&channel) {
            if frame.kind == Some(Kind::Close) {
                self.closing.remove(&channel);
            } else if frame.kind == Some(Kind::Data) {
                self.credit(channel, frame.payload.len())?;
            }
            return Ok(());
        }
        let Some(terminal) = self.channels.get(&channel).cloned() else {
            if self.early.len() < EARLY_CHANNELS || self.early.contains_key(&channel) {
                let frames = self.early.entry(channel).or_default();
                if frames.len() < EARLY_FRAMES {
                    frames.push(frame);
                    return Ok(());
                }
            }
            tracing::warn!(channel, "frame for an unknown channel dropped");
            if frame.kind == Some(Kind::Data) {
                self.credit(channel, frame.payload.len())?;
            }
            return Ok(());
        };
        self.stream(&terminal, frame)
    }

    pub(super) fn credit(&self, channel: u32, len: usize) -> Result<()> {
        let len = u32::try_from(len).unwrap_or(u32::MAX);
        self.link.frame(channel, Kind::Credit, &len.to_le_bytes())
    }

    /// One frame of `terminal`'s attach stream.
    fn stream(&mut self, terminal: &str, frame: Frame) -> Result<()> {
        let channel = frame.channel;
        match frame.kind {
            Some(Kind::Data) => {
                if let Some(term) = self.terms.get_mut(terminal) {
                    if frame.payload.first() == Some(&0x18) {
                        // A first paint: start over from a blank screen.
                        term.vt.reset();
                        term.tracker.reset();
                        term.offset = 0;
                    }
                    term.vt.vt_write(&frame.payload);
                    term.tracker.feed(&frame.payload);
                    self.dirty_frame = true;
                }
                self.credit(channel, frame.payload.len())
            }
            Some(Kind::Resize) => {
                if let Some((cols, rows)) = wire::parse_size(&frame.payload) {
                    self.resize_vt(terminal, cols, rows);
                }
                Ok(())
            }
            Some(Kind::Close) => {
                self.channels.remove(&channel);
                if let Some(term) = self.terms.get_mut(terminal) {
                    term.channel = None;
                    term.ended = true;
                }
                self.drop_interactive(terminal);
                self.dirty_view = true;
                self.dirty_frame = true;
                Ok(())
            }
            _ => Ok(()),
        }
    }

    pub(super) fn resize_vt(&mut self, terminal: &str, cols: u16, rows: u16) {
        let (cell_width, cell_height) = self.cell;
        if cols == 0 || rows == 0 {
            return;
        }
        if let Some(term) = self.terms.get_mut(terminal) {
            let now = (term.vt.cols().unwrap_or(0), term.vt.rows().unwrap_or(0));
            if now != (cols, rows) {
                if let Err(error) = term.vt.resize(cols, rows, cell_width, cell_height) {
                    tracing::warn!(%error, "terminal not resized");
                }
                self.dirty_frame = true;
            }
        }
    }

    /// No pane holds `terminal` interactively any more.
    pub(super) fn drop_interactive(&mut self, terminal: &str) {
        let panes: Vec<String> = self
            .interactive
            .iter()
            .filter(|(_, held)| held.as_str() == terminal)
            .map(|(pane, _)| pane.clone())
            .collect();
        if panes.is_empty() {
            return;
        }
        let mut shared = lock(&self.shared.interactive);
        for pane in panes {
            self.interactive.remove(&pane);
            shared.remove(&pane);
        }
        self.dirty_view = true;
    }

    /// Stop drawing `terminal`: its stream is closed and its screen dropped.
    pub(super) fn close_term(&mut self, terminal: &str) -> Result<()> {
        self.drop_interactive(terminal);
        if let Some(term) = self.terms.remove(terminal)
            && let Some(channel) = term.channel
        {
            self.channels.remove(&channel);
            self.closing.insert(channel);
            self.link.frame(channel, Kind::Close, &[])?;
        }
        self.dirty_frame = true;
        Ok(())
    }

    pub(super) fn new_vt(&self, cols: u16, rows: u16) -> Result<Terminal<'static, 'static>> {
        let mut vt = Terminal::new(Options {
            cols: cols.max(1),
            rows: rows.max(1),
            max_scrollback: SCROLLBACK,
        })?;
        self.theme.apply(&mut vt)?;
        Ok(vt)
    }

    /// Attach to `terminal` at `cols`×`rows`: observing, or interactive for
    /// the UI request `ui` of pane `ui.0`.
    pub(super) fn attach(
        &mut self,
        terminal: &str,
        observe: bool,
        (cols, rows): (u16, u16),
        ui: Option<(String, String)>,
    ) -> Result<()> {
        self.attaching.insert(terminal.to_owned());
        self.call(
            "terminal.attach",
            json!({
                "terminal": terminal, "observe": observe, "resize_notifications": true,
                "history": HISTORY, "cols": cols.max(1), "rows": rows.max(1),
            }),
            Pending::Attach {
                terminal: terminal.to_owned(),
                observe,
                ui,
            },
        )
    }

    fn attached(
        &mut self,
        terminal: String,
        observe: bool,
        ui: Option<(String, String)>,
        reply: std::result::Result<Value, (String, String)>,
    ) -> Result<()> {
        self.attaching.remove(&terminal);
        let result = match reply {
            Ok(result) => result,
            Err((kind, message)) => {
                tracing::warn!(%kind, %message, "attach refused");
                if let Some(term) = self.terms.get_mut(&terminal) {
                    term.ended = true;
                } else if let Ok(vt) = self.new_vt(1, 1) {
                    // Remember the refusal, so it is not asked again until
                    // the terminal changes.
                    self.terms.insert(
                        terminal.clone(),
                        Term {
                            vt,
                            tracker: Tracker::default(),
                            channel: None,
                            observe: true,
                            ended: true,
                            offset: 0,
                            sent: (0, 0),
                        },
                    );
                }
                if let Some((_, id)) = ui {
                    self.refuse(&id, &kind, &message);
                }
                return Ok(());
            }
        };
        let Some(channel) = result
            .get("channel")
            .and_then(Value::as_u64)
            .and_then(|channel| u32::try_from(channel).ok())
        else {
            return Err(Error::CoreProtocol("attach answered no channel"));
        };
        // An observing attach answered after an interactive one took the
        // terminal: the interactive stream stays.
        if observe
            && self
                .terms
                .get(&terminal)
                .is_some_and(|term| !term.observe && term.channel.is_some())
        {
            self.closing.insert(channel);
            return self.link.frame(channel, Kind::Close, &[]);
        }
        let size = |key: &str| {
            result["terminal"][key]
                .as_u64()
                .and_then(|n| u16::try_from(n).ok())
                .unwrap_or(1)
        };
        let (cols, rows) = (size("cols"), size("rows"));
        // An interactive stream replaces the observing one.
        if let Some(old) = self.terms.get(&terminal).and_then(|term| term.channel) {
            self.channels.remove(&old);
            self.closing.insert(old);
            self.link.frame(old, Kind::Close, &[])?;
        }
        let vt = self.new_vt(cols, rows)?;
        self.terms.insert(
            terminal.clone(),
            Term {
                vt,
                tracker: Tracker::default(),
                channel: Some(channel),
                observe,
                ended: false,
                offset: 0,
                sent: if observe { (0, 0) } else { (cols, rows) },
            },
        );
        self.channels.insert(channel, terminal.clone());
        if let Some((pane, id)) = ui {
            self.interactive.insert(pane.clone(), terminal.clone());
            lock(&self.shared.interactive).insert(pane);
            self.answer(&id, json!({}));
            self.dirty_view = true;
        }
        if let Some(frames) = self.early.remove(&channel) {
            for frame in frames {
                self.stream(&terminal, frame)?;
            }
        }
        self.dirty_frame = true;
        Ok(())
    }

    /// One JSON-RPC message on the control channel.
    fn control(&mut self, payload: &[u8]) -> Result<()> {
        let message: Value = match serde_json::from_slice(payload) {
            Ok(message) => message,
            Err(_) => return Err(Error::CoreProtocol("a control message is not JSON")),
        };
        let Some(id) = message.get("id").and_then(Value::as_u64) else {
            return Ok(());
        };
        let Some(pending) = self.pending.remove(&id) else {
            return Ok(());
        };
        let reply = match message.get("error") {
            Some(error) => Err((
                error["data"]["kind"]
                    .as_str()
                    .unwrap_or("error")
                    .to_owned(),
                error["message"].as_str().unwrap_or_default().to_owned(),
            )),
            None => Ok(message.get("result").cloned().unwrap_or(Value::Null)),
        };
        self.replied(pending, reply)
    }

    fn replied(
        &mut self,
        pending: Pending,
        reply: std::result::Result<Value, (String, String)>,
    ) -> Result<()> {
        match pending {
            Pending::Subscribe => {
                let result = reply.map_err(|_| Error::CoreProtocol("events.subscribe refused"))?;
                self.events_channel = result
                    .get("channel")
                    .and_then(Value::as_u64)
                    .and_then(|channel| u32::try_from(channel).ok());
                if self.events_channel.is_none() {
                    return Err(Error::CoreProtocol("events.subscribe opened no stream"));
                }
                self.replace_records(&result["terminals"]);
                self.call("server.status", json!({}), Pending::Status { startup: true })
            }
            Pending::Status { startup } => self.status(startup, reply),
            Pending::Doc(flow) => self.doc_fetched(flow, reply),
            Pending::Run(flow) => self.ran(flow, reply),
            Pending::Set {
                flow,
                value,
                removed,
            } => self.set(flow, value, removed, reply),
            Pending::Attach {
                terminal,
                observe,
                ui,
            } => self.attached(terminal, observe, ui, reply),
            Pending::Send { receipt } => {
                crate::hold::holds().answered(self.shared.link, &receipt);
                match reply {
                    Ok(result) => self.answer(&receipt, result),
                    Err((kind, message)) => self.refuse(&receipt, &kind, &message),
                }
                Ok(())
            }
            Pending::Typed { receipt } => {
                crate::hold::holds().answered(self.shared.link, &receipt);
                self.answer(&receipt, json!({}));
                Ok(())
            }
            Pending::Plain { ui } => {
                match reply {
                    Ok(result) => self.answer(&ui, result),
                    Err((kind, message)) => self.refuse(&ui, &kind, &message),
                }
                Ok(())
            }
            Pending::Kill => {
                if let Err((kind, message)) = reply {
                    tracing::warn!(%kind, %message, "terminal not killed");
                }
                Ok(())
            }
        }
    }

    /// The core run this link reaches: its id, pid and uptime. Another pid,
    /// or an uptime that went back, is another run: the link ends.
    fn status(
        &mut self,
        startup: bool,
        reply: std::result::Result<Value, (String, String)>,
    ) -> Result<()> {
        if !startup {
            self.liveness = None;
        }
        let result = reply.map_err(|_| Error::CoreProtocol("server.status refused"))?;
        let id = result["core"]["id"].as_str().unwrap_or_default().to_owned();
        let pid = result["pid"].as_u64().unwrap_or(0);
        let uptime = result["uptime_ms"].as_u64().unwrap_or(0);
        if let Some((was_id, was_pid, was_uptime)) = &self.server
            && (*was_id != id || *was_pid != pid || uptime < *was_uptime)
        {
            return Err(Error::CoreError {
                kind: "link".to_owned(),
                message: "core_restarted".to_owned(),
            });
        }
        self.server = Some((id.clone(), pid, uptime));
        if startup {
            self.boot = format!("core-{id}-{pid}-{}", self.shared.link);
            *lock(&self.shared.boot) = Some(self.boot.clone());
            lock(&self.shared.registry).boot = Some(self.boot.clone());
            self.emit(UiEvent::Connected {
                server_version: self.server_version.clone(),
                backend: "herdr_core",
                capabilities: super::capabilities(true),
            });
            self.fetch_doc(None)?;
        }
        Ok(())
    }

    pub(super) fn fetch_doc(&mut self, flow: Option<Flow>) -> Result<()> {
        if flow.is_none() {
            if self.fetching {
                return Ok(());
            }
            self.fetching = true;
        }
        self.call("doc.get", json!({"name": doc::NAME}), Pending::Doc(flow))
    }

    /// Take a fresh document; with `flow`, continue that edit against it.
    fn doc_fetched(
        &mut self,
        flow: Option<Flow>,
        reply: std::result::Result<Value, (String, String)>,
    ) -> Result<()> {
        if flow.is_none() {
            self.fetching = false;
        }
        match reply {
            Ok(result) => {
                let version = result["version"].as_u64().unwrap_or(0);
                if flow.is_some() || version >= self.version {
                    self.doc = result.get("value").cloned().unwrap_or(Value::Null);
                    self.version = version;
                    self.document_changed();
                }
                match flow {
                    Some(flow) => self.continue_flow(flow),
                    None => Ok(()),
                }
            }
            Err((kind, message)) => {
                tracing::warn!(%kind, %message, "document not read");
                // The refusal stands, but nothing proves the created
                // terminal unreferenced: it is left running.
                if let Some(flow) = flow {
                    let (kind, message) = flow
                        .refusal
                        .clone()
                        .unwrap_or_else(|| ("conflict".to_owned(), "conflict".to_owned()));
                    self.refuse(&flow.ui, &kind, &message);
                }
                Ok(())
            }
        }
    }

    /// The document changed: indexes, claims, attachments and the view
    /// follow.
    pub(super) fn document_changed(&mut self) {
        self.pane_of.clear();
        self.terminal_of.clear();
        for space in doc::space_list(&self.doc) {
            for tab in doc::tabs(space) {
                let Some(root) = tab.get("root") else {
                    continue;
                };
                for block in doc::blocks(root) {
                    if let doc::Content::Terminal(terminal) = doc::content(block) {
                        let pane = doc::id(block).to_owned();
                        self.pane_of
                            .entry(terminal.to_owned())
                            .or_insert_with(|| pane.clone());
                        self.terminal_of.insert(pane, terminal.to_owned());
                    }
                }
            }
        }
        // A pane that left the document is no longer held.
        let gone: Vec<String> = self
            .interactive
            .iter()
            .filter(|(pane, terminal)| self.terminal_of.get(*pane) != Some(*terminal))
            .map(|(_, terminal)| terminal.clone())
            .collect();
        for terminal in gone {
            if let Err(error) = self.close_term(&terminal) {
                tracing::warn!(%error, "stream not closed");
            }
        }
        self.reclaim_all();
        self.dirty_view = true;
        self.dirty_frame = true;
        self.dirty_sync = true;
    }

    fn replace_records(&mut self, terminals: &Value) {
        self.records.clear();
        for record in terminals.as_array().map(Vec::as_slice).unwrap_or_default() {
            if let Some(address) = record.get("address").and_then(Value::as_str) {
                self.records.insert(address.to_owned(), record.clone());
            }
        }
    }

    /// Event lines from the event stream, reassembled across frames.
    fn lines(&mut self, payload: &[u8]) -> Result<()> {
        self.line.extend_from_slice(payload);
        while let Some(end) = self.line.iter().position(|byte| *byte == b'\n') {
            let line: Vec<u8> = self.line.drain(..=end).collect();
            match serde_json::from_slice::<Value>(&line[..end]) {
                Ok(message) => self.notification(&message)?,
                Err(_) => tracing::warn!("event line is not JSON"),
            }
        }
        if self.line.len() > LINE_LIMIT {
            return Err(Error::CoreProtocol("an event line is too long"));
        }
        Ok(())
    }

    fn notification(&mut self, message: &Value) -> Result<()> {
        let params = &message["params"];
        match message["method"].as_str() {
            Some("event") => self.event(params),
            Some("missed") => {
                // A fresh snapshot: every stream starts over with it.
                self.replace_records(&params["terminals"]);
                let terminals: Vec<String> = self.terms.keys().cloned().collect();
                for terminal in terminals {
                    self.close_term(&terminal)?;
                }
                self.reclaim_all();
                self.dirty_sync = true;
                self.dirty_view = true;
                self.fetch_doc(None)
            }
            _ => Ok(()),
        }
    }

    fn event(&mut self, params: &Value) -> Result<()> {
        let kind = params["type"].as_str().unwrap_or_default();
        let data = &params["data"];
        // Terminals and documents of other machines are not herdr-app's here.
        if params["machine"].is_string()
            || params["terminal"].as_str().is_some_and(|address| address.contains('/'))
        {
            return Ok(());
        }
        if kind == "doc" {
            if data["name"].as_str() == Some(doc::NAME)
                && data["version"].as_u64().unwrap_or(0) > self.version
            {
                self.fetch_doc(None)?;
            }
            return Ok(());
        }
        let Some(terminal) = params["terminal"].as_str().map(str::to_owned) else {
            return Ok(());
        };
        let before = self.records.get(&terminal).and_then(|record| state(record).map(str::to_owned));
        match kind {
            "created" | "restarted" => {
                self.records.insert(terminal.clone(), data.clone());
                // The old stream ended with the old program.
                self.close_term(&terminal)?;
                self.dirty_sync = true;
            }
            "lost" => {
                if let Some(record) = self.records.get_mut(&terminal) {
                    merge(record, data);
                    record["pid"] = Value::Null;
                    if let Some(object) = record.as_object_mut() {
                        object.remove("foreground");
                    }
                }
                self.drop_interactive(&terminal);
            }
            "finished" => {
                if let Some(record) = self.records.get_mut(&terminal) {
                    merge(record, data);
                    record["state"] = json!("finished");
                    if let Some(object) = record.as_object_mut() {
                        object.remove("foreground");
                    }
                }
                self.drop_interactive(&terminal);
            }
            "removed" => {
                self.records.remove(&terminal);
                self.close_term(&terminal)?;
            }
            "foreground" => {
                if let Some(record) = self.records.get_mut(&terminal) {
                    record["foreground"] = data.clone();
                }
            }
            "labels" => match self.records.get_mut(&terminal) {
                Some(record) => record["labels"] = data["labels"].clone(),
                None => {
                    if data.get("record").is_some_and(Value::is_object) {
                        self.records.insert(terminal.clone(), data["record"].clone());
                    }
                }
            },
            "size" => {
                if let Some(record) = self.records.get_mut(&terminal) {
                    merge(record, data);
                }
                let size = |key: &str| data[key].as_u64().and_then(|n| u16::try_from(n).ok());
                if let (Some(cols), Some(rows)) = (size("cols"), size("rows")) {
                    self.resize_vt(&terminal, cols, rows);
                }
            }
            "turn" => {
                if let Some(record) = self.records.get_mut(&terminal) {
                    apply_metadata(record, kind, data);
                }
            }
            "status" | "title" | "keep" => {
                if let Some(record) = self.records.get_mut(&terminal) {
                    apply_metadata(record, kind, data);
                }
            }
            _ => return Ok(()),
        }
        let after = self.records.get(&terminal).and_then(|record| state(record).map(str::to_owned));
        if kind == "status" && before != after {
            self.status_changed(&terminal, after.as_deref());
        }
        if matches!(
            kind,
            "created" | "restarted" | "lost" | "finished" | "removed" | "foreground" | "labels" | "status"
        ) {
            self.reclaim(&terminal, matches!(kind, "status" | "labels" | "foreground"));
        }
        self.dirty_view = true;
        Ok(())
    }

    /// A pane's status moved: notify for a finish, a block or an error.
    fn status_changed(&mut self, terminal: &str, now: Option<&str>) {
        if let Some(pane) = self.pane_of.get(terminal)
            && let Some(core) = lock(&self.shared.instances).get_mut(pane)
        {
            core.status = super::view::status(now).to_owned();
        }
        let Some(now) = now.filter(|now| matches!(*now, "done" | "blocked" | "error")) else {
            return;
        };
        let title = self
            .records
            .get(terminal)
            .and_then(|record| record["title"].as_str())
            .filter(|title| !title.is_empty())
            .unwrap_or("Terminal")
            .to_owned();
        self.emit(UiEvent::Notification {
            title,
            body: Some(now.to_owned()),
            attention: matches!(now, "blocked" | "error"),
            pane: self.pane_of.get(terminal).cloned(),
        });
    }

    /// Ask the core to end `terminal`'s program.
    pub(super) fn kill(&mut self, terminal: &str) -> Result<()> {
        self.call("terminal.kill", json!({"terminal": terminal}), Pending::Kill)
    }

    /// Attach what the focused tab shows, close what it no longer shows,
    /// then publish whatever changed.
    fn flush(&mut self) -> Result<()> {
        if self.ended {
            return Ok(());
        }
        if self.dirty_sync {
            self.dirty_sync = false;
            self.sync()?;
        }
        if self.dirty_view {
            self.dirty_view = false;
            self.publish_view();
        }
        if self.dirty_frame {
            self.dirty_frame = false;
            self.render()?;
        }
        Ok(())
    }

    fn sync(&mut self) -> Result<()> {
        let placed = self.placed();
        let mut wanted: HashMap<String, (u16, u16)> = placed
            .iter()
            .filter_map(|placed| {
                placed
                    .terminal
                    .clone()
                    .map(|terminal| (terminal, (placed.rect.width, placed.rect.height)))
            })
            .collect();
        for terminal in self.interactive.values() {
            wanted.entry(terminal.clone()).or_insert(self.size);
        }
        let unwanted: Vec<String> = self
            .terms
            .keys()
            .filter(|terminal| !wanted.contains_key(*terminal))
            .cloned()
            .collect();
        for terminal in unwanted {
            self.close_term(&terminal)?;
        }
        for (terminal, size) in wanted {
            if self.terms.contains_key(&terminal) || self.attaching.contains(&terminal) {
                continue;
            }
            let attachable = self
                .records
                .get(&terminal)
                .is_some_and(|record| record["state"].as_str() == Some("live") && !record["pid"].is_null());
            if attachable {
                self.attach(&terminal, true, size, None)?;
            }
        }
        Ok(())
    }
}

pub(super) fn state(record: &Value) -> Option<&str> {
    record
        .get("status")
        .and_then(|status| status.get("state"))
        .and_then(Value::as_str)
}

fn merge(record: &mut Value, data: &Value) {
    if let (Some(record), Some(data)) = (record.as_object_mut(), data.as_object()) {
        for (key, value) in data {
            record.insert(key.clone(), value.clone());
        }
    }
}

/// Core event fields belong to their event, not the whole terminal record.
fn apply_metadata(record: &mut Value, kind: &str, data: &Value) {
    let fields: &[&str] = match kind {
        "turn" => &["turn"],
        "status" => &["status", "records", "reported"],
        "title" => &["title"],
        "keep" => &["keep"],
        _ => &[],
    };
    for field in fields { record[*field] = data[*field].clone(); }
}

#[cfg(test)]
mod event_tests {
    use super::*;

    #[test]
    fn completed_turn_keeps_live_agent_identity() {
        let mut record = json!({"state":"live", "turn":2, "address":"t7", "pid":970,
            "foreground":{"pid":970}, "labels":{"herdline.pid":"970"}});
        let identity = record.clone();
        apply_metadata(&mut record, "turn", &json!({"turn":3,"state":"done"}));
        assert_eq!(record["turn"], 3);
        for key in ["state", "address", "pid", "foreground", "labels"] {
            assert_eq!(record[key], identity[key], "turn event corrupted {key}");
        }
    }
}
