/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
//! One connection to a daemon, as the Kotlin UI sees it. herdr-client's
//! worker owns the socket; a `herdline-screen` thread drains its events,
//! owns the libghostty screen, and publishes the latest frame and shell view
//! into mailboxes the UI takes from, so a slow view coalesces frames instead
//! of queueing them. Rarer events go through a small bounded queue.

use crate::{
    Error, Result, attach, clipboard,
    composer::{self, Refusal, Registry},
    delivery, hold,
    input::{self, Modifiers, Touch},
    render::RenderFrame,
    screen::Screen,
    scroll,
    shown::Shown,
    ssh::{self, Route},
    steer,
    theme::Theme,
    transcript,
    view::ShellView,
    visible,
};
use crossbeam_channel::{Receiver, Sender, bounded, never, select};
use herdr_client::{
    ClientEvent, ClientHandle, ConnectOptions, ConnectTarget, MIN_HERDR_VERSION, Method, Transport,
    VersionMismatch,
    protocol::{ClientPaneInputEvent, ClientSurfaceSize, PaneSurfaceFrame, ServerMessage},
};
use serde_json::{Value, json};
use std::{
    io,
    path::PathBuf,
    sync::{
        Arc, Mutex, PoisonError,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread,
    time::Duration,
};

/// Rare events waiting for the UI; frames and snapshots never queue here.
const EVENT_CAPACITY: usize = 64;

/// Numbers each link, so a receipt answered on one never releases a
/// delivery queued on another that reused its request ID.
static LINKS: AtomicU64 = AtomicU64::new(1);

#[derive(Debug)]
pub struct Config {
    pub route: Route,
    pub options: ConnectOptions,
    pub theme: Theme,
}

/// Something the UI should react to, as one JSON object.
#[derive(Debug, Clone, PartialEq)]
pub enum UiEvent {
    Connected {
        server_version: String,
    },
    /// A new shell view is waiting in the mailbox.
    Snapshot,
    /// A new frame is waiting in the mailbox.
    Frame,
    Response {
        request_id: String,
        response: Value,
    },
    Rejected {
        request_id: Option<String>,
        reason: String,
    },
    Notification {
        title: String,
        body: Option<String>,
        attention: bool,
        pane: Option<String>,
    },
    Clipboard {
        text: String,
    },
    Bell,
    /// Updating one side fixes this, not retrying: Herdr on the host when
    /// `daemon_outdated`, else this app.
    VersionMismatch {
        daemon_outdated: bool,
        server_version: Option<String>,
    },
    UntrustedHostKey {
        hop: usize,
        host: String,
        fingerprint: String,
        changed: bool,
    },
    /// Hop number `hop` refused the key or password it was given.
    AuthRejected {
        hop: usize,
        host: String,
    },
    /// The host closed the bridge before any Herdr answered: none is
    /// installed where the bridge looks, or none could report its status.
    HerdrMissing,
    Disconnected {
        reason: String,
    },
}

impl From<VersionMismatch> for UiEvent {
    fn from(mismatch: VersionMismatch) -> Self {
        let daemon_outdated = matches!(mismatch, VersionMismatch::DaemonOutdated { .. });
        Self::VersionMismatch {
            daemon_outdated,
            server_version: mismatch.server_version().map(str::to_owned),
        }
    }
}

/// The version mismatch behind a connector failure. herdr-client classifies
/// only its own errors, and the connector hands it this one as an opaque
/// `io::Error`, so it is recognised here before being wrapped.
/// The connector failures the UI answers with its own prompt, not a plain
/// disconnect: a host key to trust, a sign-in to retry, or Herdr to install.
fn connector_refusal(error: &Error) -> Option<UiEvent> {
    match error {
        Error::UntrustedHostKey {
            hop,
            host,
            fingerprint,
            changed,
        } => Some(UiEvent::UntrustedHostKey {
            hop: *hop,
            host: host.clone(),
            fingerprint: fingerprint.clone(),
            changed: *changed,
        }),
        Error::AuthRejected { hop, host } => Some(UiEvent::AuthRejected {
            hop: *hop,
            host: host.clone(),
        }),
        Error::Client(herdr_client::Error::SshClosed) => Some(UiEvent::HerdrMissing),
        _ => None,
    }
}

fn connector_mismatch(error: &Error) -> Option<VersionMismatch> {
    match error {
        Error::Client(error) => error.version_mismatch(),
        _ => None,
    }
}

impl UiEvent {
    pub fn to_json(&self) -> String {
        match self {
            Self::Connected { server_version } => {
                json!({"type": "connected", "server_version": server_version})
            }
            Self::Snapshot => json!({"type": "snapshot"}),
            Self::Frame => json!({"type": "frame"}),
            Self::Response {
                request_id,
                response,
            } => {
                json!({"type": "response", "request_id": request_id, "response": response})
            }
            Self::Rejected { request_id, reason } => {
                json!({"type": "rejected", "request_id": request_id, "reason": reason})
            }
            Self::Notification {
                title,
                body,
                attention,
                pane,
            } => json!({
                "type": "notification", "title": title, "body": body,
                "attention": attention, "pane": pane,
            }),
            Self::Clipboard { text } => json!({"type": "clipboard", "text": text}),
            Self::Bell => json!({"type": "bell"}),
            Self::VersionMismatch {
                daemon_outdated,
                server_version,
            } => json!({
                "type": "version_mismatch",
                "outdated": if *daemon_outdated { "daemon" } else { "app" },
                "server_version": server_version,
                "min_version": MIN_HERDR_VERSION,
            }),
            Self::UntrustedHostKey {
                hop,
                host,
                fingerprint,
                changed,
            } => json!({
                "type": "host_key", "hop": hop, "host": host,
                "fingerprint": fingerprint, "changed": changed,
            }),
            Self::AuthRejected { hop, host } => {
                json!({"type": "auth_rejected", "hop": hop, "host": host})
            }
            Self::HerdrMissing => json!({"type": "herdr_missing"}),
            Self::Disconnected { reason } => json!({"type": "disconnected", "reason": reason}),
        }
        .to_string()
    }
}

/// Where input goes: the open popup, else the given or focused pane.
#[derive(Debug, Default)]
struct Targets {
    popup: Option<String>,
    focused: Option<String>,
    /// Whether the open popup's program reads the mouse.
    popup_mouse: bool,
    /// Where finger scrolls go, by pane.
    scrolls: scroll::Scrolls,
}

#[derive(Debug)]
struct Shared {
    events: Sender<UiEvent>,
    /// The frame, its surface's scroll and the client grid, as one.
    shown: Mutex<Shown>,
    frame_fresh: AtomicBool,
    view: Mutex<Option<String>>,
    view_fresh: AtomicBool,
    boot: Mutex<Option<String>>,
    targets: Mutex<Targets>,
    /// Every pane of the latest snapshot, for the composer's checks.
    registry: Mutex<Registry>,
    /// The read-only method whose answer proves earlier input was read.
    receipt: Mutex<Option<Method>>,
    /// Set by the connector when it fails with a [`connector_refusal`];
    /// reported in place of the disconnect it causes.
    refusal: Mutex<Option<UiEvent>>,
    /// Set by the connector when the host's Herdr cannot serve this app;
    /// reported before the disconnect it causes.
    mismatch: Mutex<Option<VersionMismatch>>,
    /// Commands on the bridge's host, once the bridge is up.
    exec: Mutex<Option<ssh::Exec>>,
    /// The Herdr session the route names.
    session: String,
    /// This link's number among all links of the process.
    link: u64,
}

impl Shared {
    fn emit(&self, event: UiEvent) {
        if self
            .events
            .send_timeout(event, Duration::from_secs(1))
            .is_err()
        {
            tracing::warn!("ui event dropped");
        }
    }
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

enum Control {
    Theme(Theme),
}

pub struct Session {
    handle: ClientHandle,
    shared: Arc<Shared>,
    events: Receiver<UiEvent>,
    control: Sender<Control>,
}

impl std::fmt::Debug for Session {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Session").finish_non_exhaustive()
    }
}

impl std::fmt::Debug for Control {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Theme(theme) => write!(f, "Theme(dark: {})", theme.dark),
        }
    }
}

impl Session {
    /// Start connecting. Returns at once; progress and failure arrive as
    /// [`UiEvent`]s.
    pub fn connect(config: Config) -> Result<Self> {
        let (events_tx, events) = bounded(EVENT_CAPACITY);
        let shared = Arc::new(Shared {
            events: events_tx,
            shown: Mutex::new(Shown::opened(
                config.options.surface_size.cols,
                config.options.surface_size.rows,
            )),
            frame_fresh: AtomicBool::default(),
            view: Mutex::default(),
            view_fresh: AtomicBool::default(),
            boot: Mutex::default(),
            targets: Mutex::default(),
            registry: Mutex::default(),
            receipt: Mutex::default(),
            refusal: Mutex::default(),
            mismatch: Mutex::default(),
            exec: Mutex::default(),
            session: config.route.session.clone(),
            link: LINKS.fetch_add(1, Ordering::Relaxed),
        });
        let Config {
            route,
            options,
            theme,
        } = config;
        let connector_shared = shared.clone();
        // The connector, not the target, decides how to reach the daemon;
        // the socket path is never opened.
        let client = herdr_client::connect_with_connector(
            ConnectTarget::Socket(PathBuf::from("ssh")),
            options,
            true,
            move |_, stop| match ssh::open(route, stop) {
                Ok((stream, exec)) => {
                    *lock(&connector_shared.exec) = Some(exec);
                    Ok(Transport::Remote(stream))
                }
                Err(error) => {
                    if let Some(mismatch) = connector_mismatch(&error) {
                        *lock(&connector_shared.mismatch) = Some(mismatch);
                    }
                    if let Some(refusal) = connector_refusal(&error) {
                        *lock(&connector_shared.refusal) = Some(refusal);
                    }
                    Err(io::Error::other(error))
                }
            },
        )?;
        let (control, control_rx) = bounded(4);
        let screen_shared = shared.clone();
        let handle = client.handle.clone();
        thread::Builder::new()
            .name("herdline-screen".into())
            .spawn(move || {
                Pump {
                    shared: screen_shared,
                    handle,
                    theme,
                    surface: None,
                    theme_sent: false,
                }
                .run(client.events, control_rx);
            })?;
        Ok(Self {
            handle: client.handle,
            shared,
            events,
            control,
        })
    }

    pub fn next_event(&self, timeout: Duration) -> Option<UiEvent> {
        self.events.recv_timeout(timeout).ok()
    }

    pub fn take_frame(&self) -> Option<Arc<RenderFrame>> {
        self.shared.frame_fresh.store(false, Ordering::Release);
        lock(&self.shared.shown).frame().cloned()
    }

    pub fn take_view(&self) -> Option<String> {
        self.shared.view_fresh.store(false, Ordering::Release);
        lock(&self.shared.view).clone()
    }

    /// Text of the last frame between two cells, for copying a selection.
    pub fn text(&self, start: (u16, u16), end: (u16, u16)) -> String {
        lock(&self.shared.shown)
            .frame()
            .as_ref()
            .map(|frame| frame.text(start, end))
            .unwrap_or_default()
    }

    fn boot(&self) -> Result<String> {
        lock(&self.shared.boot).clone().ok_or(Error::NotReady)
    }

    /// A chat view of an OMP pane's transcript, as JSON; see [`transcript`].
    /// Blocks on the host: call it from a background thread.
    pub fn transcript(&self, request: &str) -> Result<String> {
        let exec = lock(&self.shared.exec).clone().ok_or(Error::NotReady)?;
        let request = serde_json::from_str(request)?;
        let response = transcript::fetch(&exec, &self.shared.session, &request)?;
        Ok(serde_json::to_string(&response)?)
    }

    /// The agent instance in `pane` now, as JSON `{"instance": …}`, `null`
    /// when the pane names no OMP session. Blocks on the host: call it from
    /// a background thread.
    pub fn composer_instance(&self, pane: &str) -> Result<String> {
        Ok(json!({"instance": self.instance_now(pane)?}).to_string())
    }

    fn instance_now(&self, pane: &str) -> Result<Option<transcript::Identity>> {
        let exec = lock(&self.shared.exec).clone().ok_or(Error::NotReady)?;
        transcript::instance(&exec, &self.shared.session, pane)
    }

    /// The pane's agent instance as the host reads it now; `None` when it
    /// could not be read, which every caller refuses.
    fn read_instance(&self, pane: &str) -> Option<transcript::Identity> {
        self.instance_now(pane).unwrap_or_else(|error| {
            tracing::warn!(%error, "agent instance not read");
            None
        })
    }

    /// Send input to the open popup, else to `pane` or the focused pane.
    pub fn input(&self, pane: Option<&str>, events: Vec<ClientPaneInputEvent>) -> Result<()> {
        if events.is_empty() {
            return Ok(());
        }
        let boot = self.boot()?;
        let targets = lock(&self.shared.targets);
        if let Some(popup) = &targets.popup {
            return Ok(self.handle.send_popup_input(&boot, popup, events)?);
        }
        let pane = pane.or(targets.focused.as_deref()).ok_or(Error::NotReady)?;
        Ok(self.handle.send_input(&boot, pane, events)?)
    }

    pub fn key(
        &self,
        pane: Option<&str>,
        code: i32,
        meta: i32,
        unicode: Option<char>,
        repeat: bool,
    ) -> Result<bool> {
        let Some(event) = input::key(code, Modifiers::from_meta_state(meta), unicode, repeat)
        else {
            return Ok(false);
        };
        self.input(pane, vec![event])?;
        Ok(true)
    }

    pub fn chord(&self, pane: Option<&str>, ch: char, meta: i32) -> Result<()> {
        let events = input::chord(ch, Modifiers::from_meta_state(meta))
            .into_iter()
            .collect();
        self.input(pane, events)
    }

    pub fn text_input(&self, pane: Option<&str>, text: &str) -> Result<()> {
        self.input(pane, input::text(text))
    }

    pub fn paste(&self, pane: Option<&str>, text: &str) -> Result<()> {
        self.input(pane, vec![ClientPaneInputEvent::Paste(text.to_owned())])
    }

    /// The composer's checks for `pane`, as JSON, against what the phone
    /// drew (`viewport`, JSON or `None`). A check binds no node or agent
    /// instance: it has neither to read; the deliveries do.
    pub fn composer_check(&self, pane: &str, viewport: Option<&str>) -> Result<String> {
        let viewport: Option<visible::Viewport> = match viewport {
            Some(json) => serde_json::from_str(json)?,
            None => None,
        };
        let check = self.with_surface(|registry, surface| {
            let phone = composer::Phone {
                viewport: viewport.as_ref(),
                bind: visible::Bind {
                    node: None,
                    session: &self.shared.session,
                    boot: viewport
                        .as_ref()
                        .and_then(|viewport| viewport.boot.as_ref())
                        .and(registry.boot.as_deref()),
                    pane,
                    instance: None,
                },
            };
            composer::check(registry, surface, pane, &phone)
        });
        Ok(serde_json::to_string(&check)?)
    }

    /// `decide` with the latest registry and what is shown, all locked.
    fn with_surface<T>(&self, decide: impl FnOnce(&Registry, &composer::Surface<'_>) -> T) -> T {
        let registry = lock(&self.shared.registry);
        let shown = lock(&self.shared.shown);
        let popup_open = lock(&self.shared.targets).popup.is_some();
        decide(&registry, &shown.surface(popup_open))
    }

    /// Deliver composer text to exactly the pane it names, as one paste and,
    /// when submitting, one Enter; never to the popup or the focused pane.
    /// A read-only request queued behind it is its receipt: the daemon
    /// answers requests in order, so its response means the input was read.
    /// The target stays held from the paste until that answer (see
    /// [`hold`]). A request naming an agent instance first reads the pane's
    /// instance on the host, so this blocks: call it from a background thread.
    pub fn composer_deliver(&self, request: &str) -> Result<String> {
        let mut request: composer::Request = serde_json::from_str(request)?;
        request.text = composer::clean(&request.text);
        let refused = delivery::refused;
        let Some(receipt) = *lock(&self.shared.receipt) else {
            return Ok(refused(Refusal::NoReceipt));
        };
        if hold::holds().is_held(&request.target()) {
            return Ok(refused(Refusal::Unsettled));
        }
        // Only the host knows which agent process and session run in the
        // pane: the daemon's snapshot names the program alone.
        if let Some(expected) = &request.instance
            && let Err(reason) =
                composer::same_instance(expected, self.read_instance(&request.pane).as_ref())
        {
            return Ok(refused(reason));
        }
        // OMP drops an image path it cannot read and still takes the Enter:
        // the text would go without it. Every staged file must be there.
        if !request.attachments.is_empty() {
            let Some(exec) = lock(&self.shared.exec).clone() else {
                return Ok(refused(Refusal::AttachmentMissing));
            };
            match attach::present(&exec, &request.attachments) {
                Ok(true) => {}
                Ok(false) => return Ok(refused(Refusal::AttachmentMissing)),
                Err(error) => {
                    tracing::warn!(%error, "staged attachments not checked");
                    return Ok(refused(Refusal::AttachmentMissing));
                }
            }
        }
        let mut holds = hold::holds();
        delivery::deliver(
            &self.handle,
            &mut holds,
            &mut steer::steers(),
            self.shared.link,
            receipt,
            request,
            |request| {
                self.with_surface(|registry, surface| composer::admit(registry, surface, request))
            },
        )
    }

    /// Start staging one attachment for the target `request` names, on
    /// this link's host. Answers `open` with an upload id, or a refusal.
    pub fn attachment_begin(&self, request: &str) -> Result<String> {
        let begin: attach::Begin = serde_json::from_str(request)?;
        let refused = |reason| json!({"outcome": "refused", "reason": reason}).to_string();
        let boot = lock(&self.shared.registry).boot.clone();
        if begin.session != self.shared.session || boot.as_deref() != Some(begin.boot.as_str()) {
            return Ok(refused(attach::Refusal::TargetChanged));
        }
        let Some(exec) = lock(&self.shared.exec).clone() else {
            return Ok(refused(attach::Refusal::NoExec));
        };
        attach::start(exec, begin)
    }

    /// Remove a staged attachment from this link's host. Blocks.
    pub fn attachment_discard(&self, attachment: &str) -> Result<()> {
        let attachment: attach::Attachment = serde_json::from_str(attachment)?;
        if attachment.session != self.shared.session {
            return Err(Error::BadReply);
        }
        let exec = lock(&self.shared.exec).clone().ok_or(Error::NotReady)?;
        attach::discard(&exec, &attachment)
    }

    /// Hold `target` until the user unlocks it, as after a restart that lost
    /// the answer to its last delivery.
    pub fn composer_hold(target: &str) -> Result<()> {
        hold::holds().hold(serde_json::from_str(target)?);
        Ok(())
    }

    /// The user settled `target`'s last delivery.
    pub fn composer_unlock(target: &str) -> Result<()> {
        let target = serde_json::from_str(target)?;
        let mut holds = hold::holds();
        holds.unlock(&target);
        steer::steers().end(&target);
        Ok(())
    }

    /// The first step of a steer: its paste alone, held until its receipt
    /// is answered (see [`steer`]). Blocks on the host's instance read.
    pub fn composer_steer(&self, request: &str) -> Result<String> {
        let mut request: composer::SteerRequest = serde_json::from_str(request)?;
        request.text = composer::clean(&request.text);
        let refused = delivery::refused;
        let Some(receipt) = *lock(&self.shared.receipt) else {
            return Ok(refused(Refusal::NoReceipt));
        };
        let target = request.target();
        let Some(expected) = request.instance.clone() else {
            return Ok(refused(Refusal::InstanceUnknown));
        };
        if let Err(reason) =
            composer::same_instance(&expected, self.read_instance(&request.pane).as_ref())
        {
            return Ok(refused(reason));
        }
        let mut holds = hold::holds();
        let mut steers = steer::steers();
        if holds.is_held(&target) || steers.takes(&target, self.shared.link) {
            return Ok(refused(Refusal::Unsettled));
        }
        if let Err(reason) = self
            .with_surface(|registry, surface| composer::admit_steer(registry, surface, &request))
        {
            return Ok(refused(reason));
        }
        delivery::stage_steer(
            &self.handle,
            &mut holds,
            &mut steers,
            self.shared.link,
            receipt,
            request,
            expected,
        )
    }

    /// The second step of the steer staged on `target` with paste receipt
    /// `receipt`: one Enter, or a wait, or the end of the steer. `viewport`
    /// is what the phone drew at the Enter tap. Blocks on the host's
    /// instance read.
    pub fn composer_steer_enter(
        &self,
        target: &str,
        receipt: &str,
        viewport: Option<&str>,
    ) -> Result<String> {
        let target: hold::Target = serde_json::from_str(target)?;
        let viewport: Option<visible::Viewport> = match viewport {
            Some(json) => serde_json::from_str(json)?,
            None => None,
        };
        let Some(confirm) = *lock(&self.shared.receipt) else {
            return Ok(json!({"outcome": "waiting", "reason": Refusal::NoReceipt}).to_string());
        };
        let expected = steer::steers()
            .get(&target)
            .map(|staged| staged.instance.clone());
        let instance = match &expected {
            Some(expected) => {
                composer::same_instance(expected, self.read_instance(&target.pane).as_ref())
            }
            None => Ok(()),
        };
        let mut holds = hold::holds();
        let mut steers = steer::steers();
        let staged = steers.get(&target);
        // The instance read above was for the steer staged then.
        let instance = match staged {
            Some(staged) if Some(&staged.instance) != expected.as_ref() => {
                Err(Refusal::SteerExpired)
            }
            _ => instance,
        };
        let gate = staged.map_or(Ok(()), |staged| {
            let phone = composer::Phone {
                viewport: viewport.as_ref(),
                bind: visible::Bind {
                    node: Some(&target.node),
                    session: &target.session,
                    boot: Some(&staged.boot),
                    pane: &target.pane,
                    instance: Some(Some(&staged.instance)),
                },
            };
            self.with_surface(|registry, surface| {
                composer::admit_steer_enter(
                    registry,
                    surface,
                    &staged.boot,
                    &target.pane,
                    staged.agent.as_deref(),
                    &staged.text,
                    &phone,
                )
            })
        });
        let next = steer::next(
            staged,
            self.shared.link,
            receipt,
            holds.is_held(&target),
            instance,
            gate,
        );
        match next {
            steer::Next::Wait(reason) => {
                Ok(json!({"outcome": "waiting", "reason": reason}).to_string())
            }
            steer::Next::End(reason) => {
                steers.end(&target);
                Ok(delivery::refused(reason))
            }
            steer::Next::Enter => {
                let Some(staged) = steers.end(&target) else {
                    return Ok(delivery::refused(Refusal::SteerExpired));
                };
                delivery::press_steer_enter(
                    &self.handle,
                    &mut holds,
                    target,
                    self.shared.link,
                    staged,
                    confirm,
                )
            }
        }
    }

    /// The user gave up the steer staged on `target`; its paste stays in
    /// OMP's box. No Enter follows.
    pub fn composer_steer_cancel(target: &str) -> Result<()> {
        let target = serde_json::from_str(target)?;
        let _holds = hold::holds();
        steer::steers().end(&target);
        Ok(())
    }

    /// A tap or one finger-scroll step at a cell of `pane` (of the popup,
    /// when one is open). A scroll reaches the program as a wheel only if it
    /// reads the mouse; otherwise it moves Herdr's scrollback (see
    /// [`scroll`]), so it can never arrive as arrow keys.
    pub fn touch(&self, pane: &str, kind: Touch, column: u16, row: u16, lines: u16) -> Result<()> {
        let wheel = || input::touch(kind, column, row, lines);
        let up = match kind {
            Touch::Tap => return self.input(Some(pane), wheel()),
            Touch::ScrollUp => true,
            Touch::ScrollDown => false,
        };
        let boot = self.boot()?;
        let mut targets = lock(&self.shared.targets);
        if targets.popup.is_some() {
            let mouse = targets.popup_mouse;
            drop(targets);
            return if mouse {
                self.input(Some(pane), wheel())
            } else {
                Ok(())
            };
        }
        match targets.scrolls.step(pane, up, lines) {
            scroll::Step::Wheel => {
                drop(targets);
                self.input(Some(pane), wheel())
            }
            // Held under the lock until recorded, so its answer finds it.
            scroll::Step::Request(offset) => {
                match request_scroll(&self.handle, &boot, pane, offset) {
                    Ok(id) => {
                        targets.scrolls.sent(pane, id);
                        Ok(())
                    }
                    Err(error) => {
                        targets.scrolls.unsent(pane);
                        Err(error.into())
                    }
                }
            }
            scroll::Step::Wait | scroll::Step::Nothing => Ok(()),
        }
    }

    /// Ask Herdr for a new grid. It is registered as pending before it is
    /// sent, so the frame answering it can never arrive unregistered; one
    /// that fails to send leaves Herdr's grid as it was. Sending only
    /// queues, so the lock is held across it and no frame lands between.
    pub fn resize(&self, options: ConnectOptions) -> Result<()> {
        let boot = self.boot()?;
        let size = options.surface_size;
        let mut shown = lock(&self.shared.shown);
        let before = shown.request(size.cols, size.rows);
        let sent = self.handle.resize(&boot, options);
        if sent.is_err() {
            shown.unsent(before);
        }
        Ok(sent?)
    }

    pub fn set_theme(&self, theme: Theme) {
        let _ = self.control.try_send(Control::Theme(theme));
    }

    /// Invoke an endpoint method by its wire name; returns the request ID
    /// its response will carry.
    pub fn request(&self, method: &str, params: Value) -> Result<String> {
        Ok(self
            .handle
            .request(&self.boot()?, parse_method(method)?, params)?)
    }

    /// Have the host prove the link alive within a few seconds, as after the
    /// network changed under it; a dead link then ends as a disconnect.
    pub fn check_liveness(&self) {
        self.handle.check_liveness();
    }

    pub fn disconnect(&self) {
        self.handle.disconnect();
    }
}

impl Drop for Session {
    fn drop(&mut self) {
        self.handle.disconnect();
    }
}

/// Ask Herdr to show `pane`'s scrollback `offset` rows above the live bottom.
fn request_scroll(
    handle: &ClientHandle,
    boot: &str,
    pane: &str,
    offset: u64,
) -> herdr_client::Result<String> {
    handle.request(
        boot,
        Method::PaneScroll,
        json!({"pane_id": pane, "offset_from_bottom": offset}),
    )
}

/// The endpoint methods the UI may invoke, parsed once at the JNI boundary.
pub fn parse_method(name: &str) -> Result<Method> {
    Ok(match name {
        // keep-sorted start
        "pane.close" => Method::PaneClose,
        "pane.focus" => Method::PaneFocus,
        "pane.rename" => Method::PaneRename,
        "pane.scroll" => Method::PaneScroll,
        "pane.split" => Method::PaneSplit,
        "pane.zoom" => Method::PaneZoom,
        "tab.close" => Method::TabClose,
        "tab.create" => Method::TabCreate,
        "tab.focus" => Method::TabFocus,
        "tab.rename" => Method::TabRename,
        "workspace.close" => Method::WorkspaceClose,
        "workspace.create" => Method::WorkspaceCreate,
        "workspace.focus" => Method::WorkspaceFocus,
        "workspace.rename" => Method::WorkspaceRename,
        // keep-sorted end
        _ => return Err(Error::UnknownMethod(name.to_owned())),
    })
}

/// The screen thread's state: everything libghostty touches stays here.
struct Pump {
    shared: Arc<Shared>,
    handle: ClientHandle,
    theme: Theme,
    surface: Option<Arc<PaneSurfaceFrame>>,
    theme_sent: bool,
}

impl Pump {
    fn run(mut self, events: Receiver<ClientEvent>, mut control: Receiver<Control>) {
        let mut screen = match Screen::new(self.theme) {
            Ok(screen) => screen,
            Err(error) => {
                self.handle.disconnect();
                self.shared.emit(UiEvent::Disconnected {
                    reason: error.to_string(),
                });
                return;
            }
        };
        loop {
            select! {
                recv(events) -> event => match event {
                    Ok(event) => self.event(&mut screen, event),
                    Err(_) => break,
                },
                recv(control) -> message => match message {
                    Ok(Control::Theme(theme)) => self.theme(&mut screen, theme),
                    // The session is gone; keep draining until the worker stops.
                    Err(_) => control = never(),
                },
            }
        }
    }

    fn theme(&mut self, screen: &mut Screen, theme: Theme) {
        if theme == screen.theme() {
            return;
        }
        self.theme = theme;
        if let Err(error) = screen.set_theme(theme) {
            tracing::warn!(%error, "theme not applied");
        }
        self.theme_sent = false;
        self.send_theme();
        if let Some(surface) = self.surface.clone() {
            self.show(screen, &surface);
        }
    }

    fn send_theme(&mut self) {
        if self.theme_sent {
            return;
        }
        let Some(boot) = lock(&self.shared.boot).clone() else {
            return;
        };
        self.theme_sent = self
            .handle
            .set_host_theme(&boot, &self.theme.host())
            .is_ok();
    }

    /// Settle the answer `id` if it was a scroll request, sending the newest
    /// offset asked while it was on the wire; false when someone else waits.
    fn scroll_answered(&self, id: &str, ok: bool) -> bool {
        let boot = lock(&self.shared.boot).clone();
        let mut targets = lock(&self.shared.targets);
        match targets.scrolls.answered(id, ok) {
            scroll::Answer::NotOurs => false,
            scroll::Answer::Done => true,
            scroll::Answer::Next { pane, offset } => {
                let sent = boot
                    .ok_or(herdr_client::Error::Disconnected)
                    .and_then(|boot| request_scroll(&self.handle, &boot, &pane, offset));
                match sent {
                    Ok(id) => targets.scrolls.sent(&pane, id),
                    Err(error) => {
                        tracing::warn!(%error, "scroll request not sent");
                        targets.scrolls.unsent(&pane);
                    }
                }
                true
            }
        }
    }

    fn show(&mut self, screen: &mut Screen, surface: &PaneSurfaceFrame) {
        match screen.show(surface) {
            Ok(frame) => {
                let scroll = surface
                    .panes
                    .iter()
                    .filter_map(|pane| {
                        let scroll = pane.scroll.as_ref()?;
                        Some((
                            pane.pane_id.clone(),
                            composer::PaneScroll {
                                scrolled: scroll.offset_from_bottom > 0,
                                rows: scroll.viewport_rows,
                            },
                        ))
                    })
                    .collect();
                // The frame and its popup are published under one hold of
                // `shown`, so a composer check sees them as one surface.
                let mut shown = lock(&self.shared.shown);
                {
                    let mut targets = lock(&self.shared.targets);
                    targets.popup = surface.popup.as_ref().map(|p| p.terminal_id.clone());
                    targets.popup_mouse = surface.popup.as_ref().is_some_and(|p| p.mouse_reporting);
                    targets.focused = surface
                        .panes
                        .iter()
                        .find(|pane| pane.focused)
                        .map(|pane| pane.pane_id.clone())
                        .or(targets.focused.take());
                    targets.scrolls.surface(surface.panes.iter().map(|pane| {
                        (
                            pane.pane_id.clone(),
                            scroll::Pane {
                                mouse: pane.mouse_reporting,
                                scroll: pane.scroll,
                            },
                        )
                    }));
                }
                shown.publish(frame, scroll);
                drop(shown);
                if !self.shared.frame_fresh.swap(true, Ordering::AcqRel) {
                    self.shared.emit(UiEvent::Frame);
                }
            }
            Err(error) => tracing::warn!(%error, "surface not shown"),
        }
    }

    fn event(&mut self, screen: &mut Screen, event: ClientEvent) {
        match event {
            ClientEvent::Connected(welcome) => {
                // Read-only methods only: answering one changes nothing.
                *lock(&self.shared.receipt) = [Method::IntegrationList, Method::WorktreeList]
                    .into_iter()
                    .find(|method| method.advertised_in(&welcome.methods));
                self.shared.emit(UiEvent::Connected {
                    server_version: welcome.server_version.clone(),
                });
            }
            ClientEvent::Snapshot(snapshot) => {
                *lock(&self.shared.boot) = Some(snapshot.boot_id.clone());
                *lock(&self.shared.registry) = Registry::from_snapshot(&snapshot);
                {
                    let mut targets = lock(&self.shared.targets);
                    if let Some(focused) = &snapshot.focused_pane_id {
                        targets.focused = Some(focused.clone());
                    }
                }
                self.send_theme();
                match serde_json::to_string(&ShellView::from(&*snapshot)) {
                    Ok(view) => {
                        *lock(&self.shared.view) = Some(view);
                        if !self.shared.view_fresh.swap(true, Ordering::AcqRel) {
                            self.shared.emit(UiEvent::Snapshot);
                        }
                    }
                    Err(error) => tracing::warn!(%error, "shell view not encoded"),
                }
            }
            ClientEvent::Surface(surface) => {
                self.show(screen, &surface);
                self.surface = Some(surface);
            }
            ClientEvent::SurfaceImages(_) => {}
            ClientEvent::Response {
                request_id,
                response,
            } => {
                let ok = response.get("error").is_none();
                if self.scroll_answered(&request_id, ok) {
                    return;
                }
                // Released before the UI hears it, so a confirmed target is
                // free by the time the composer offers Send again.
                hold::holds().answered(self.shared.link, &request_id);
                self.shared.emit(UiEvent::Response {
                    request_id,
                    response,
                });
            }
            ClientEvent::CommandRejected { request_id, reason } => {
                if let Some(id) = &request_id
                    && self.scroll_answered(id, false)
                {
                    tracing::warn!(%reason, "scroll request rejected");
                    return;
                }
                self.shared.emit(UiEvent::Rejected {
                    request_id,
                    reason: reason.to_string(),
                });
            }
            ClientEvent::Message(message) => self.message(message),
            ClientEvent::VersionMismatch(mismatch) => self.shared.emit(mismatch.into()),
            ClientEvent::Disconnected { reason, .. } => {
                *lock(&self.shared.registry) = Registry::default();
                if let Some(mismatch) = lock(&self.shared.mismatch).take() {
                    self.shared.emit(mismatch.into());
                }
                let refusal = lock(&self.shared.refusal).take();
                self.shared
                    .emit(refusal.unwrap_or(UiEvent::Disconnected { reason }));
            }
        }
    }

    fn message(&self, message: ServerMessage) {
        match message {
            ServerMessage::Clipboard { data } => {
                if let Some(text) = clipboard::decode(&data) {
                    self.shared.emit(UiEvent::Clipboard { text });
                }
            }
            ServerMessage::SemanticNotification(note) => {
                use herdr_client::protocol::SemanticNotificationKind as Kind;
                self.shared.emit(UiEvent::Notification {
                    title: note.title,
                    body: note.body,
                    attention: matches!(note.kind, Kind::NeedsAttention),
                    pane: note.pane_id,
                });
            }
            ServerMessage::Notify { message, body, .. } => {
                self.shared.emit(UiEvent::Notification {
                    title: message,
                    body,
                    attention: false,
                    pane: None,
                });
            }
            ServerMessage::TerminalBell { count } => {
                tracing::debug!(count, "terminal bell");
                self.shared.emit(UiEvent::Bell);
            }
            _ => {}
        }
    }
}

/// Connection geometry from the view's size in cells and pixels.
pub fn options(cols: u16, rows: u16, cell_width: u32, cell_height: u32) -> ConnectOptions {
    ConnectOptions {
        surface_size: ClientSurfaceSize {
            cols: cols.max(1),
            rows: rows.max(1),
        },
        cell_width_px: cell_width,
        cell_height_px: cell_height,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
