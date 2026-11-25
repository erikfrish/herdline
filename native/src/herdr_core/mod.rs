//! The `herdr_core` backend: one `herdr-core connect` run on the host over
//! the route's SSH session, sharing herdr-app's spaces, tabs, panes and
//! terminals through its `"herdr-app"` document. Focus and zoom stay on
//! this phone and are never written to the document.
//!
//! The UI thread only enqueues: every command goes through a bounded queue
//! with `try_send`, and a full queue is refused, never waited on. One
//! `herdline-core` thread owns the link's writer, the document and every
//! libghostty terminal, and publishes frames and views into mailboxes.

pub mod doc;
mod edits;
pub mod identity;
pub mod keys;
pub mod layout;
pub mod link;
pub mod modes;
pub mod omp_discovery;
mod present;
mod pump;
pub mod view;
pub mod wire;

use crate::{
    Error, Result, attach,
    composer::{self, Refusal, Registry},
    control::pty,
    delivery, hold, ingress,
    input::{self, Modifiers, Touch},
    render::RenderFrame,
    session::{Config, UiEvent},
    shown::Shown,
    ssh, steer,
    theme::Theme,
    transcript, visible,
};
use crossbeam_channel::{Receiver, Sender, TrySendError, bounded};
use herdr_client::{ConnectOptions, protocol::ClientPaneInputEvent};
use serde_json::{Map, Value, json};
use std::{
    collections::{HashMap, HashSet},
    net::Shutdown,
    os::unix::net::UnixStream,
    sync::{
        Arc, Mutex, MutexGuard, PoisonError,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread,
    time::Duration,
};

/// Rare events waiting for the UI; frames and views never queue here.
const EVENT_CAPACITY: usize = 64;
/// Commands from the UI waiting for the pump; past it they are refused.
const COMMAND_CAPACITY: usize = 64;
/// How long the UI's blocking calls wait for the core's answer.
const TEMPLATES_WAIT: Duration = Duration::from_secs(15);
/// `terminal.run` of a template answers once its first message went.
const START_WAIT: Duration = Duration::from_secs(90);
const READ_WAIT: Duration = Duration::from_secs(10);
/// `terminal.interrupt` watches 10 s for an effect before it answers.
const STOP_SECS: u64 = 10;
const STOP_WAIT: Duration = Duration::from_secs(STOP_SECS + 5);
/// Said when a launch is not confirmed, as on Herdr.
const START_UNCONFIRMED: &str =
    "Harness startup was not confirmed; check its terminal before retrying";

/// The methods `request` takes, as the Kotlin app names them.
const METHODS: [&str; 15] = [
    "pane.close",
    "pane.focus",
    "pane.rename",
    "pane.scroll",
    "pane.split",
    "pane.zoom",
    "tab.close",
    "tab.create",
    "tab.focus",
    "tab.rename",
    "terminal.interact",
    "workspace.close",
    "workspace.create",
    "workspace.focus",
    "workspace.rename",
];

/// What each backend can do, by capability key: `null` when it can, else
/// the reason code the UI shows. Chat is further decided per pane in the
/// view (`chat`).
pub fn capabilities(core: bool) -> Value {
    const KEYS: [&str; 22] = [
        "workspace.create",
        "workspace.rename",
        "workspace.close",
        "tab.create",
        "tab.rename",
        "tab.close",
        "pane.split",
        "pane.close",
        "pane.focus",
        "pane.zoom",
        "pane.rename",
        "terminal.view",
        "terminal.input",
        "terminal.resize",
        "terminal.paste",
        "agent.start",
        "chat.transcript",
        "chat.send",
        "chat.steer",
        "chat.attachments",
        "herdr.setup",
        "notifications",
    ];
    let mut out = Map::new();
    for key in KEYS {
        let reason = if !core {
            None
        } else {
            match key {
                "terminal.input" | "terminal.resize" | "terminal.paste" => Some("takes_size"),
                "herdr.setup" => Some("core_managed"),
                "pane.rename" => Some("unsupported_backend"),
                _ => None,
            }
        };
        out.insert(key.to_owned(), reason.map_or(Value::Null, Value::from));
    }
    Value::Object(out)
}

/// What the UI thread hands the pump.
enum Command {
    Request {
        id: String,
        method: String,
        params: Value,
    },
    Input {
        pane: Option<String>,
        events: Vec<ClientPaneInputEvent>,
    },
    Touch {
        pane: String,
        kind: Touch,
        column: u16,
        row: u16,
        lines: u16,
    },
    Resize(ConnectOptions),
    Theme(Theme),
    /// One `terminal.send` (paste, then Enter) answered under `receipt`.
    Send {
        receipt: String,
        pane: String,
        text: String,
    },
    /// Composer input written as DATA on `pane`'s interactive stream, with
    /// `receipt` answered once the core has taken it (not once the program
    /// read it). Refused, with `target`'s hold released, when the pane is not
    /// interactive.
    Typed {
        receipt: String,
        target: hold::Target,
        pane: String,
        events: Vec<ClientPaneInputEvent>,
    },
    /// One core call answered under `id`; with `fence`, only while that
    /// pane still shows that terminal.
    Call {
        id: String,
        method: &'static str,
        params: Value,
        fence: Option<(String, String)>,
    },
    Liveness,
    Disconnect,
}

struct Shared {
    events: Sender<UiEvent>,
    shown: Mutex<Shown>,
    frame_fresh: AtomicBool,
    view: Mutex<Option<String>>,
    view_fresh: AtomicBool,
    boot: Mutex<Option<String>>,
    registry: Mutex<Registry>,
    /// Answers a blocking UI call waits for, by request id.
    waiters: Mutex<HashMap<String, Sender<Value>>>,
    exec: Mutex<Option<ssh::Exec>>,
    /// Confirmed agent instances, by pane: Chat reads and sends only these.
    instances: Mutex<HashMap<String, transcript::Core>>,
    /// Panes whose live terminal runs an OMP without hook labels, by pane:
    /// their terminal address. Chat only reads these, through the probe.
    hookless: Mutex<HashMap<String, String>>,
    /// Panes whose terminal this phone holds interactively.
    interactive: Mutex<HashSet<String>>,
    /// A second handle on the link's socket, so a disconnect from the UI
    /// ends it without waiting for the writer.
    closer: Mutex<Option<UnixStream>>,
    stop: AtomicBool,
    session: String,
    link: u64,
    next: AtomicU64,
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

    /// The answer to request `id`: to the UI call waiting on it, else to the
    /// UI as a `Response`. A waiter that gave up drops it.
    fn respond(&self, id: &str, response: Value) {
        if let Some(waiter) = lock(&self.waiters).remove(id) {
            let _ = waiter.try_send(response);
            return;
        }
        self.emit(UiEvent::Response {
            request_id: id.to_owned(),
            response,
        });
    }

    fn next(&self) -> u64 {
        self.next.fetch_add(1, Ordering::Relaxed)
    }
}

fn lock<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}

fn queue_full() -> Error {
    Error::CoreError {
        kind: "busy".to_owned(),
        message: "queue_full".to_owned(),
    }
}

fn refused(reason: &str) -> String {
    json!({"outcome": "refused", "reason": reason}).to_string()
}

pub struct Core {
    shared: Arc<Shared>,
    events: Receiver<UiEvent>,
    commands: Sender<Command>,
}

impl std::fmt::Debug for Core {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("Core").finish_non_exhaustive()
    }
}

impl Core {
    /// Start connecting. Returns at once; progress and failure arrive as
    /// [`UiEvent`]s.
    pub fn connect(config: Config, link: u64) -> Result<Self> {
        let (events_tx, events) = bounded(EVENT_CAPACITY);
        let (commands, commands_rx) = bounded(COMMAND_CAPACITY);
        let Config {
            route,
            options,
            theme,
            ..
        } = config;
        let session = route.session.trim().to_owned();
        let shared = Arc::new(Shared {
            events: events_tx,
            shown: Mutex::new(Shown::opened(
                options.surface_size.cols,
                options.surface_size.rows,
            )),
            frame_fresh: AtomicBool::default(),
            view: Mutex::default(),
            view_fresh: AtomicBool::default(),
            boot: Mutex::default(),
            registry: Mutex::default(),
            waiters: Mutex::default(),
            exec: Mutex::default(),
            instances: Mutex::default(),
            hookless: Mutex::default(),
            interactive: Mutex::default(),
            closer: Mutex::default(),
            stop: AtomicBool::default(),
            session,
            link,
            next: AtomicU64::new(1),
        });
        if !link::valid_session(&shared.session) {
            shared.emit(UiEvent::Disconnected {
                reason: "invalid_session".to_owned(),
            });
            return Ok(Self {
                shared,
                events,
                commands,
            });
        }
        let pump_shared = shared.clone();
        thread::Builder::new()
            .name("herdline-core".into())
            .spawn(move || pump::run(pump_shared, route, options, theme, commands_rx))?;
        Ok(Self {
            shared,
            events,
            commands,
        })
    }

    fn send(&self, command: Command) -> Result<()> {
        match self.commands.try_send(command) {
            Ok(()) => Ok(()),
            Err(TrySendError::Full(_)) => Err(queue_full()),
            Err(TrySendError::Disconnected(_)) => Err(Error::NotReady),
        }
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

    pub fn text(&self, start: (u16, u16), end: (u16, u16)) -> String {
        lock(&self.shared.shown)
            .frame()
            .as_ref()
            .map(|frame| frame.text(start, end))
            .unwrap_or_default()
    }

    fn exec(&self) -> Result<ssh::Exec> {
        lock(&self.shared.exec).clone().ok_or(Error::NotReady)
    }

    /// Stage and verify the exact extensions that the managed launch loads.
    fn provision_omp(&self) -> Result<Value> {
        use base64::{Engine, engine::general_purpose::STANDARD};
        use herdr_client::shell_quote;
        let script = include_str!("install_omp_runtime.py");
        let identity = STANDARD.encode(include_bytes!("../../../host/omp/herdline-core.ts"));
        let ingress = STANDARD.encode(include_bytes!("../../../host/omp/herdline-ingress.ts"));
        let subagents = STANDARD.encode(include_bytes!("../../../host/omp/live-subagents.ts"));
        let output = self.exec()?.run(
            format!("python3 -c {} '{identity}' '{ingress}' '{subagents}'", shell_quote(script)),
            READ_WAIT,
            8192,
        )?;
        let text = String::from_utf8_lossy(&output.tail);
        if output.exit_status != Some(0) {
            let message = if text.trim().is_empty() {
                "The host did not confirm hook installation.".to_owned()
            } else {
                text.trim().to_owned()
            };
            return Err(std::io::Error::other(message).into());
        }
        serde_json::from_str(text.lines().last().ok_or(Error::BadReply)?).map_err(Into::into)
    }

    /// The Chat integration in the host's OMP extension discovery: its
    /// status, or the install or uninstall the user asked for, as the
    /// contract JSON line of [`omp_discovery`]. Blocks on the host.
    pub fn omp_chat_integration(&self, action: &str) -> Result<String> {
        omp_discovery::run(&self.exec()?, omp_discovery::Action::parse(action)?)
    }

    /// The confirmed instance in `pane`, if the host confirmed one.
    fn instance(&self, pane: &str) -> Option<transcript::Core> {
        lock(&self.shared.instances).get(pane).cloned()
    }

    /// A draft and a delivery both need the current agent, not an earlier
    /// foreground sample with the same numeric PID. On hosts without the
    /// kernel proof, keep the existing claim for this link only.
    fn current_identity(&self, pane: &str) -> Option<transcript::Identity> {
        let core = self.instance(pane)?;
        if core.run.is_none() {
            return Some(core.identity);
        }
        let current = transcript::core_instance(&self.exec().ok()?, &core).ok()??;
        let latest = self.instance(pane)?;
        (current.start.is_some()
            && latest.identity == core.identity
            && latest.session_id == core.session_id
            && latest.run == core.run)
            .then_some(current)
    }

    fn check_instance(
        &self,
        pane: &str,
        expected: &transcript::Identity,
    ) -> std::result::Result<(), Refusal> {
        composer::same_instance(expected, self.current_identity(pane).as_ref())
    }

    /// A chat view of a pane's transcript. Blocks on the host. A confirmed
    /// instance is read through its claim; otherwise a hookless OMP is read
    /// only through the probe: the terminal the view shows now for a first
    /// read, and for a later one exactly the terminal the caller was shown.
    pub fn transcript(&self, request: &str) -> Result<String> {
        let exec = self.exec()?;
        let request: transcript::Request = serde_json::from_str(request)?;
        let response = match self.instance(&request.pane) {
            Some(core) => {
                transcript::fetch(&exec, transcript::BackendSource::Core(&core), &request)?
            }
            None => {
                let terminal = match &request.expect {
                    None => lock(&self.shared.hookless).get(&request.pane).cloned(),
                    Some(expect) if expect.source == transcript::omp_probe::SOURCE => {
                        Some(expect.terminal_id.clone())
                    }
                    Some(_) => None,
                }
                .ok_or(Error::NoTranscript)?;
                let probe = transcript::Probe {
                    terminal,
                    session: self.shared.session.clone(),
                };
                transcript::fetch(&exec, transcript::BackendSource::Probe(&probe), &request)?
            }
        };
        Ok(serde_json::to_string(&response)?)
    }

    /// Chat's own-agent route: OMP's ingress endpoint, found through the
    /// confirmed claim exactly as through Herdr's report, and the guarded
    /// stop through the core's own interrupt. Blocks on the host.
    pub fn ingress(&self, request: &str) -> Result<String> {
        let request: ingress::Request = serde_json::from_str(request)?;
        let boot = lock(&self.shared.registry).boot.clone();
        if request.session != self.shared.session || boot.as_deref() != Some(request.boot.as_str())
        {
            return Ok(delivery::refused(Refusal::BootChanged));
        }
        let send = request.op == ingress::Op::Send;
        if send && hold::holds().is_held(&request.target()) {
            return Ok(delivery::refused(Refusal::Unsettled));
        }
        let Ok(exec) = self.exec() else {
            return if send {
                Ok(refused("not_connected"))
            } else {
                Err(Error::NotReady)
            };
        };
        if request.op == ingress::Op::Abort
            && !matches!(request.instance.agent.as_str(), "opencode" | "kilo")
        {
            return Ok(self.stop(&request));
        }
        // Only OMP has an endpoint of its own; Pi has none, as on Herdr.
        if request.instance.agent != "omp" {
            return Ok(json!({"endpoint": "absent"}).to_string());
        }
        let Some(core) = self.instance(&request.pane) else {
            return Ok(if send {
                delivery::refused(Refusal::InstanceUnknown)
            } else {
                json!({"endpoint": "unusable", "reason": "instance_unknown"}).to_string()
            });
        };
        ingress::run(&exec, transcript::BackendSource::Core(&core), request)
    }

    fn id(&self, kind: &str) -> String {
        format!("core-{kind}{}-{}", self.shared.link, self.shared.next())
    }

    /// Queue `command`, whose answer under `id` goes to the receiver.
    fn post(&self, id: &str, command: Command) -> Result<Receiver<Value>> {
        let (waiter, answer) = bounded(1);
        lock(&self.shared.waiters).insert(id.to_owned(), waiter);
        if let Err(error) = self.send(command) {
            lock(&self.shared.waiters).remove(id);
            return Err(error);
        }
        Ok(answer)
    }

    /// One core call, waited on; `None` when no answer came in time.
    fn ask(
        &self,
        method: &'static str,
        params: Value,
        fence: Option<(String, String)>,
        timeout: Duration,
    ) -> Result<Option<Value>> {
        let id = self.id("w");
        let answer = self.post(
            &id,
            Command::Call {
                id: id.clone(),
                method,
                params,
                fence,
            },
        )?;
        Ok(answer.recv_timeout(timeout).ok())
    }

    /// Stop the working agent in the request's pane with the core's own
    /// interrupt (Esc for a program that reports status), behind the same
    /// instance, hold and screen guards as Herdr's PTY stop. Nothing is
    /// pressed twice; whatever is not observed is uncertain.
    fn stop(&self, request: &ingress::Request) -> String {
        let refused = |reason: pty::Refusal| {
            tracing::info!(?reason, "stop refused");
            json!({"route": "pty", "outcome": "refused", "reason": reason}).to_string()
        };
        let uncertain =
            json!({"route": "pty", "outcome": "uncertain", "reason": Refusal::Unconfirmed})
                .to_string();
        let pane = request.pane.as_str();
        if self.check_instance(pane, &request.instance).is_err() {
            return refused(pty::Refusal::AgentChanged);
        }
        let terminal = request.instance.terminal_id.clone();
        let fence = Some((pane.to_owned(), terminal.clone()));
        // Off the drawn surface, the core's own read of its screen decides.
        let screen = if self.with_surface(|_, surface| pty::on_surface(surface, pane)) {
            None
        } else {
            let read = self.ask(
                "terminal.read",
                json!({"terminal": terminal, "lines": 0}),
                fence.clone(),
                READ_WAIT,
            );
            match read {
                Ok(Some(reply)) => match reply["result"]["text"].as_str() {
                    Some(text) => Some(text.lines().map(str::to_owned).collect::<Vec<_>>()),
                    None => return refused(pty::Refusal::ScreenUnread),
                },
                _ => return refused(pty::Refusal::ScreenUnread),
            }
        };
        if screen.is_some() && self.check_instance(pane, &request.instance).is_err() {
            return refused(pty::Refusal::AgentChanged);
        }
        let target = request.target();
        let holds = hold::holds();
        let mut steers = steer::steers();
        if holds.is_held(&target) || steers.takes(&target, self.shared.link) {
            return refused(pty::Refusal::Unsettled);
        }
        let decided = self.with_surface(|registry, surface| {
            pty::admit(
                registry,
                surface,
                &request.boot,
                pane,
                &request.instance.agent,
                screen.as_deref(),
            )
        });
        let key = match decided {
            Ok(key) => key,
            Err(reason) => return refused(reason),
        };
        let pty::Key::Esc = key;
        let id = self.id("i");
        let answer = self.post(
            &id,
            Command::Call {
                id: id.clone(),
                method: "terminal.interrupt",
                params: json!({"terminal": terminal, "timeout": STOP_SECS}),
                fence,
            },
        );
        // The pump takes the hold lock to settle input: never wait under it.
        drop(steers);
        drop(holds);
        let Ok(answer) = answer else {
            return refused(pty::Refusal::KeyUnsent);
        };
        let Ok(reply) = answer.recv_timeout(STOP_WAIT) else {
            return uncertain;
        };
        match reply["error"]["kind"].as_str() {
            Some("pane_changed") => return refused(pty::Refusal::AgentChanged),
            Some("not_found" | "finished" | "unavailable" | "invalid_params") => {
                return refused(pty::Refusal::KeyUnsent);
            }
            Some(_) => return uncertain,
            None => {}
        }
        if reply["result"]["outcome"].as_str() == Some("stopped") {
            json!({"route": "pty", "outcome": "stopped", "key": key}).to_string()
        } else {
            uncertain
        }
    }

    pub fn composer_instance(&self, pane: &str) -> Result<String> {
        Ok(json!({"instance": self.current_identity(pane)}).to_string())
    }

    fn input(&self, pane: Option<&str>, events: Vec<ClientPaneInputEvent>) -> Result<()> {
        if events.is_empty() {
            return Ok(());
        }
        self.send(Command::Input {
            pane: pane.map(str::to_owned),
            events,
        })
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

    fn with_surface<T>(&self, decide: impl FnOnce(&Registry, &composer::Surface<'_>) -> T) -> T {
        let registry = lock(&self.shared.registry);
        let shown = lock(&self.shared.shown);
        decide(&registry, &shown.surface(false))
    }

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

    /// Submit composer text to exactly the pane it names with one
    /// `terminal.send`, which pastes and presses Enter; Insert alone writes
    /// its bracketed paste on this phone's interactive stream of the pane,
    /// and is refused when the phone does not hold it. The target stays
    /// held from the enqueue until the core answers it.
    pub fn composer_deliver(&self, request: &str) -> Result<String> {
        let mut request: composer::Request = serde_json::from_str(request)?;
        request.text = composer::clean(&request.text);
        if request.session != self.shared.session {
            return Ok(delivery::refused(Refusal::BootChanged));
        }
        let interactive = lock(&self.shared.interactive).contains(&request.pane);
        // Insert alone is a bracketed paste on this phone's own stream.
        if !request.submit && !interactive {
            return Ok(refused("takes_size"));
        }
        // terminal.send has one text paste; it cannot send the separate
        // image pastes OMP needs. Do not submit an incomplete draft.
        if request.submit && request.attachments.iter().any(|file| file.kind == attach::Kind::Image) {
            return Ok(refused("core_image_needs_ingress"));
        }
        if !request.attachments.is_empty() {
            let Ok(exec) = self.exec() else {
                return Ok(delivery::refused(Refusal::AttachmentMissing));
            };
            match attach::present(&exec, &request.attachments) {
                Ok(true) => {}
                Ok(false) => return Ok(delivery::refused(Refusal::AttachmentMissing)),
                Err(error) => {
                    tracing::warn!(%error, "staged attachments not checked");
                    return Ok(delivery::refused(Refusal::AttachmentMissing));
                }
            }
        }
        if let Some(expected) = &request.instance
            && let Err(reason) = self.check_instance(&request.pane, expected)
        {
            return Ok(delivery::refused(reason));
        }
        let target = request.target();
        let mut holds = hold::holds();
        if holds.is_held(&target) || steer::steers().takes(&target, self.shared.link) {
            return Ok(delivery::refused(Refusal::Unsettled));
        }
        if let Err(reason) =
            self.with_surface(|registry, surface| composer::admit(registry, surface, &request))
        {
            return Ok(delivery::refused(reason));
        }
        let receipt = format!("core-{}-{}", self.shared.link, self.shared.next());
        let command = if request.submit {
            Command::Send {
                receipt: receipt.clone(),
                pane: request.pane.clone(),
                text: attach::with_files(&request.text, &request.attachments),
            }
        } else {
            Command::Typed {
                receipt: receipt.clone(),
                target: target.clone(),
                pane: request.pane.clone(),
                events: attach::pastes(&request.text, &request.attachments),
            }
        };
        // Held under the hold lock, so the pump's answer cannot pass it.
        match self.commands.try_send(command) {
            Ok(()) => {
                holds.hold_until(target, self.shared.link, receipt.clone());
                Ok(json!({"outcome": "queued", "receipt": receipt}).to_string())
            }
            // Nothing was queued, so nothing left.
            Err(_) => Ok(refused("queue_full")),
        }
    }

    pub fn attachment_begin(&self, request: &str) -> Result<String> {
        let begin: attach::Begin = serde_json::from_str(request)?;
        let boot = lock(&self.shared.registry).boot.clone();
        if begin.session != self.shared.session || boot.as_deref() != Some(begin.boot.as_str()) {
            return Ok(json!({"outcome": "refused", "reason": attach::Refusal::TargetChanged})
                .to_string());
        }
        let Ok(exec) = self.exec() else {
            return Ok(json!({"outcome": "refused", "reason": attach::Refusal::NoExec}).to_string());
        };
        attach::start(exec, begin)
    }

    pub fn attachment_discard(&self, attachment: &str) -> Result<()> {
        let attachment: attach::Attachment = serde_json::from_str(attachment)?;
        if attachment.session != self.shared.session {
            return Err(Error::BadReply);
        }
        attach::discard(&self.exec()?, &attachment)
    }

    /// A steer's paste alone, on this phone's interactive stream, staged for
    /// its Enter, which goes only once the paste is seen on screen in the
    /// same instance's box. The same gates as Herdr's.
    pub fn composer_steer(&self, request: &str) -> Result<String> {
        let mut request: composer::SteerRequest = serde_json::from_str(request)?;
        request.text = composer::clean(&request.text);
        let refused_as = delivery::refused;
        if request.session != self.shared.session {
            return Ok(refused_as(Refusal::BootChanged));
        }
        let target = request.target();
        let Some(expected) = request.instance.clone() else {
            return Ok(refused_as(Refusal::InstanceUnknown));
        };
        if let Err(reason) = self.check_instance(&request.pane, &expected) {
            return Ok(refused_as(reason));
        }
        if !lock(&self.shared.interactive).contains(&request.pane) {
            return Ok(refused("takes_size"));
        }
        let mut holds = hold::holds();
        let mut steers = steer::steers();
        if holds.is_held(&target) || steers.takes(&target, self.shared.link) {
            return Ok(refused_as(Refusal::Unsettled));
        }
        if let Err(reason) = self
            .with_surface(|registry, surface| composer::admit_steer(registry, surface, &request))
        {
            return Ok(refused_as(reason));
        }
        let receipt = format!("core-{}-{}", self.shared.link, self.shared.next());
        let command = Command::Typed {
            receipt: receipt.clone(),
            target: target.clone(),
            pane: request.pane.clone(),
            events: vec![ClientPaneInputEvent::Paste(request.text.clone())],
        };
        if self.commands.try_send(command).is_err() {
            return Ok(refused("queue_full"));
        }
        holds.hold_until(target.clone(), self.shared.link, receipt.clone());
        steers.stage(
            target,
            steer::Staged {
                boot: request.boot,
                link: self.shared.link,
                receipt: receipt.clone(),
                agent: request.agent,
                instance: expected,
                text: request.text,
            },
        );
        Ok(json!({"outcome": "staged", "receipt": receipt}).to_string())
    }

    /// The steer's one Enter, under the same decision as Herdr's
    /// ([`steer::next`]), on the same interactive stream.
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
        let now = self.current_identity(&target.pane);
        let mut holds = hold::holds();
        let mut steers = steer::steers();
        let staged = steers.get(&target);
        let instance = staged.map_or(Ok(()), |staged| {
            composer::same_instance(&staged.instance, now.as_ref())
        });
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
                if !lock(&self.shared.interactive).contains(&target.pane) {
                    steers.end(&target);
                    return Ok(refused("takes_size"));
                }
                if steers.end(&target).is_none() {
                    return Ok(delivery::refused(Refusal::SteerExpired));
                }
                let enter = format!("core-{}-{}", self.shared.link, self.shared.next());
                let command = Command::Typed {
                    receipt: enter.clone(),
                    target: target.clone(),
                    pane: target.pane.clone(),
                    events: vec![input::enter()],
                };
                // The steer has ended: a refused queue never presses Enter later.
                if self.commands.try_send(command).is_err() {
                    return Ok(refused("queue_full"));
                }
                holds.hold_until(target, self.shared.link, enter.clone());
                Ok(json!({"outcome": "queued", "receipt": enter}).to_string())
            }
        }
    }

    pub fn touch(&self, pane: &str, kind: Touch, column: u16, row: u16, lines: u16) -> Result<()> {
        self.send(Command::Touch {
            pane: pane.to_owned(),
            kind,
            column,
            row,
            lines,
        })
    }

    pub fn resize(&self, options: ConnectOptions) -> Result<()> {
        let size = options.surface_size;
        let mut shown = lock(&self.shared.shown);
        let before = shown.request(size.cols, size.rows);
        let sent = self.send(Command::Resize(options));
        if sent.is_err() {
            shown.unsent(before);
        }
        sent
    }

    pub fn set_theme(&self, theme: Theme) {
        let _ = self.commands.try_send(Command::Theme(theme));
    }

    /// Queue one of the Kotlin app's methods; its answer arrives as a
    /// `Response` carrying the returned ID.
    pub fn request(&self, method: &str, params: Value) -> Result<String> {
        if !METHODS.contains(&method) {
            return Err(Error::UnknownMethod(method.to_owned()));
        }
        let id = format!("core-r{}-{}", self.shared.link, self.shared.next());
        self.send(Command::Request {
            id: id.clone(),
            method: method.to_owned(),
            params,
        })?;
        Ok(id)
    }

    /// The core's templates, as the launcher picker lists them: each usable
    /// template by name, its description beside it.
    pub fn agent_launchers(&self) -> Result<String> {
        let reply = self
            .ask("templates.list", json!({}), None, TEMPLATES_WAIT)?
            .ok_or(Error::NotReady)?;
        if let Some(error) = reply["error"].as_object() {
            return Err(Error::CoreError {
                kind: error
                    .get("kind")
                    .and_then(Value::as_str)
                    .unwrap_or("error")
                    .to_owned(),
                message: error
                    .get("message")
                    .and_then(Value::as_str)
                    .unwrap_or_default()
                    .to_owned(),
            });
        }
        let mut launchers: Vec<Value> = reply["result"]["templates"]
            .as_array()
            .map(Vec::as_slice)
            .unwrap_or_default()
            .iter()
            .filter(|template| template["error"].is_null())
            .filter_map(|template| {
                let name = template["name"].as_str().filter(|name| !name.is_empty())?;
                if name == "herdline-omp" {
                    return None;
                }
                Some(json!({
                    "kind": name,
                    "label": if name == "omp" { "omp (template)" } else { name },
                    "description": template["description"].as_str().unwrap_or_default(),
                }))
            })
            .collect();
        let probe = self.exec()?.run(
            "sh -c 'PATH=$HOME/.bun/bin:$HOME/.local/bin:$PATH; command -v omp'".to_owned(),
            READ_WAIT,
            4096,
        )?;
        if probe.exit_status == Some(0) {
            launchers.insert(0, json!({"kind": "herdline-omp", "label": "OMP with Chat"}));
        }
        Ok(json!({"launchers": launchers}).to_string())
    }

    /// Start template `kind` for the fresh pane the UI just made, through
    /// `terminal.run` and a fenced document write (see the pump's
    /// `launch`). Blocks until the core answers.
    pub fn start_agent(&self, request: &str) -> Result<String> {
        let value: Value = serde_json::from_str(request)?;
        let boot = lock(&self.shared.registry).boot.clone();
        if boot.is_none() || value["boot"].as_str() != boot.as_deref() {
            return Err(Error::BadReply);
        }
        let text = |key: &str| {
            value[key]
                .as_str()
                .filter(|text| !text.is_empty())
                .ok_or(Error::BadReply)
        };
        let kind = text("kind")?;
        let mut params = json!({
            "pane": text("pane")?,
            "tab": text("tab")?,
            "terminal": text("terminal")?,
            "kind": kind,
            "name": value["name"].as_str().unwrap_or_default(),
        });
        if kind == "herdline-omp" {
            params["managed_omp"] = self.provision_omp()?;
        }
        let id = self.id("a");
        let answer = self.post(
            &id,
            Command::Request {
                id: id.clone(),
                method: "agent.start".to_owned(),
                params,
            },
        )?;
        let Ok(reply) = answer.recv_timeout(START_WAIT) else {
            return Ok(json!({"started": false, "reason": START_UNCONFIRMED}).to_string());
        };
        if reply["result"]["started"].as_bool() == Some(true) {
            return Ok(json!({"started": true}).to_string());
        }
        let reason = match reply["error"]["kind"].as_str() {
            Some("uncertain") | None => START_UNCONFIRMED.to_owned(),
            Some(_) => reply["error"]["message"]
                .as_str()
                .unwrap_or(START_UNCONFIRMED)
                .to_owned(),
        };
        Ok(json!({"started": false, "reason": reason}).to_string())
    }

    pub fn check_liveness(&self) {
        let _ = self.commands.try_send(Command::Liveness);
    }

    pub fn disconnect(&self) {
        self.shared.stop.store(true, Ordering::Release);
        if let Some(stream) = lock(&self.shared.closer).as_ref() {
            let _ = stream.shutdown(Shutdown::Both);
        }
        let _ = self.commands.try_send(Command::Disconnect);
    }
}

impl Drop for Core {
    fn drop(&mut self) {
        self.disconnect();
    }
}
