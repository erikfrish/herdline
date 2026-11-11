/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
//! SSH without an `ssh` executable, which Android does not have. An
//! in-process client (russh) walks a route of hops: it connects to the first,
//! opens a `direct-tcpip` channel through each jump host to the next, checks
//! every host key against the one the user approved, authenticates each hop,
//! and runs Herdr's remote bridge on the last. The bridge channel is pumped
//! through a Unix socket pair so herdr-client's blocking worker reads it like
//! the bridge it spawns on the desktop. The async runtime is confined to this
//! one thread and owns only the SSH sessions. [`Exec`] runs short commands
//! on the bridge's own SSH session while it is up; [`run`] walks the same
//! route afresh to run one command and collect what it prints.

mod auth;

use crate::{Error, Result};
pub use auth::Credential;
use herdr_client::{Stream, answer_remote_bridge, remote_bridge_command};
use russh::{
    ChannelMsg, Disconnect,
    client::{self, Handle},
    keys::{HashAlg, PublicKey, PublicKeyOrCertificate},
};
use std::{
    os::unix::net::UnixStream,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant},
};

/// Connecting and authenticating every hop together may take this long.
const SETUP_TIMEOUT: Duration = Duration::from_secs(30);
const POLL: Duration = Duration::from_millis(100);

/// One SSH server on the way to Herdr: a jump host, or the host itself.
#[derive(Debug)]
pub struct Hop {
    pub host: String,
    pub port: u16,
    pub user: String,
    pub credential: Credential,
    /// The host key fingerprint the user trusted, if any.
    pub pinned: Option<String>,
}

impl Hop {
    /// How the trust prompt and the known-hosts list name this server.
    pub fn address(&self) -> String {
        format!("{}:{}", self.host, self.port)
    }
}

/// Jump hosts in order, then the host that runs Herdr.
#[derive(Debug)]
pub struct Route {
    pub hops: Vec<Hop>,
    pub session: String,
    /// SSH keepalive; `None` disables it.
    pub keepalive: Option<Duration>,
}

/// Runs commands on the last host over the open bridge's SSH session, so
/// each one costs a channel, not a new sign-in. It stops working, with
/// [`Error::SshClosed`], once the bridge ends.
#[derive(Debug, Clone)]
pub struct Exec {
    jobs: tokio::sync::mpsc::Sender<Job>,
}

#[derive(Debug)]
struct Job {
    command: String,
    timeout: Duration,
    limit: usize,
    /// The command's stdin, for an [`Upload`]; `None` gives it none.
    input: Option<tokio::sync::mpsc::Receiver<Vec<u8>>>,
    reply: tokio::sync::oneshot::Sender<Result<Output>>,
}

/// Chunks an upload holds before [`Writer::write`] blocks.
const UPLOAD_QUEUE: usize = 4;

impl Exec {
    /// Blocks for at most `timeout` after the channel opens: call it from a
    /// background thread, never from async code.
    pub fn run(&self, command: String, timeout: Duration, limit: usize) -> Result<Output> {
        let (reply, outcome) = tokio::sync::oneshot::channel();
        self.jobs
            .blocking_send(Job {
                command,
                timeout,
                limit,
                input: None,
                reply,
            })
            .map_err(|_| Error::SshClosed)?;
        outcome.blocking_recv().map_err(|_| Error::SshClosed)?
    }

    /// Start `command` with its stdin open for [`Writer::write`]. It runs
    /// beside other commands, which never wait for it. It ends, closing
    /// stdin, once the upload is finished or dropped, or once `idle` passes
    /// with no chunk taken by the phone or the host; then its output is
    /// collected for at most `idle`, keeping the last `limit` bytes.
    pub fn upload(&self, command: String, idle: Duration, limit: usize) -> Result<Upload> {
        let (reply, outcome) = tokio::sync::oneshot::channel();
        let (input, chunks) = tokio::sync::mpsc::channel(UPLOAD_QUEUE);
        self.jobs
            .blocking_send(Job {
                command,
                timeout: idle,
                limit,
                input: Some(chunks),
                reply,
            })
            .map_err(|_| Error::SshClosed)?;
        Ok(Upload {
            input: Some(input),
            outcome,
        })
    }
}

/// A command started by [`Exec::upload`], its stdin still open.
#[derive(Debug)]
pub struct Upload {
    input: Option<tokio::sync::mpsc::Sender<Vec<u8>>>,
    outcome: tokio::sync::oneshot::Receiver<Result<Output>>,
}

impl Upload {
    /// A handle that writes to this upload's stdin from another thread.
    pub fn writer(&self) -> Option<Writer> {
        self.input.clone().map(Writer)
    }

    /// Close stdin and wait for the command's output. Blocks.
    pub fn finish(mut self) -> Result<Output> {
        self.input = None;
        self.outcome.blocking_recv().map_err(|_| Error::SshClosed)?
    }
}

/// Writes to an [`Upload`]'s stdin. Stdin closes only once every writer is gone.
#[derive(Debug, Clone)]
pub struct Writer(tokio::sync::mpsc::Sender<Vec<u8>>);

impl Writer {
    /// Blocks while the host is slower than the phone; fails once the
    /// command ended.
    pub fn write(&self, chunk: Vec<u8>) -> Result<()> {
        self.0.blocking_send(chunk).map_err(|_| Error::SshClosed)
    }
}

/// Open the bridge to the route's Herdr and answer its discovery. Blocks, so
/// it runs on herdr-client's I/O worker inside the connector; `stop` cancels.
pub fn open(route: Route, stop: &AtomicBool) -> Result<(Stream, Exec)> {
    if route.hops.is_empty() {
        return Err(Error::EmptyRoute);
    }
    let command = remote_bridge_command(&route.session)?;
    let (mut ours, theirs) = UnixStream::pair()?;
    let (ready, outcome) = mpsc::sync_channel(1);
    let (jobs, queue) = tokio::sync::mpsc::channel(4);
    thread::Builder::new()
        .name("herdline-ssh".into())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(error) => {
                    let _ = ready.send(Err(Error::Io(error)));
                    return;
                }
            };
            runtime.block_on(run_bridge(route, command, theirs, ready, queue));
        })?;
    let started = Instant::now();
    loop {
        if stop.load(Ordering::Acquire) {
            return Err(Error::Cancelled);
        }
        match outcome.recv_timeout(POLL) {
            Ok(result) => break result?,
            Err(mpsc::RecvTimeoutError::Timeout) if started.elapsed() < SETUP_TIMEOUT => {}
            Err(mpsc::RecvTimeoutError::Timeout) => return Err(Error::Timeout),
            Err(mpsc::RecvTimeoutError::Disconnected) => return Err(Error::SshClosed),
        }
    }
    ours.set_read_timeout(Some(POLL))?;
    ours.set_write_timeout(Some(Duration::from_secs(1)))?;
    answer_remote_bridge(&mut ours, stop)?;
    Ok((ours, Exec { jobs }))
}

/// What a command run with [`run`] left behind.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Output {
    /// `None` when the host closed the channel without reporting one.
    pub exit_status: Option<u32>,
    /// The last bytes of stdout and stderr, interleaved as they arrived.
    pub tail: Vec<u8>,
}

/// Run `command` on the route's last host and collect its output, keeping
/// the last `limit` bytes. Blocks for at most `timeout` once every hop is up:
/// call it from a background thread.
pub fn run(route: Route, command: &str, timeout: Duration, limit: usize) -> Result<Output> {
    if route.hops.is_empty() {
        return Err(Error::EmptyRoute);
    }
    let runtime = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async {
        let (handles, channel) =
            tokio::time::timeout(SETUP_TIMEOUT, start(route, command.to_owned()))
                .await
                .map_err(|_| Error::Timeout)??;
        let collected = tokio::time::timeout(timeout, collect(channel, limit)).await;
        for handle in handles.iter().rev() {
            let _ = handle.disconnect(Disconnect::ByApplication, "", "en").await;
        }
        collected.map_err(|_| Error::CommandTimeout)
    })
}

async fn collect(channel: russh::Channel<client::Msg>, limit: usize) -> Output {
    drain(channel.split().0, limit).await
}

async fn drain(mut channel: russh::ChannelReadHalf, limit: usize) -> Output {
    let mut tail = Vec::new();
    let mut exit_status = None;
    while let Some(message) = channel.wait().await {
        match message {
            ChannelMsg::Data { data } | ChannelMsg::ExtendedData { data, .. } => {
                tail.extend_from_slice(&data);
                if tail.len() > limit {
                    tail.drain(..tail.len() - limit);
                }
            }
            ChannelMsg::ExitStatus {
                exit_status: status,
            } => exit_status = Some(status),
            _ => {}
        }
    }
    Output { exit_status, tail }
}

async fn run_bridge(
    route: Route,
    command: String,
    theirs: UnixStream,
    ready: mpsc::SyncSender<Result<()>>,
    mut queue: tokio::sync::mpsc::Receiver<Job>,
) {
    let (handles, channel) = match tokio::time::timeout(SETUP_TIMEOUT, start(route, command)).await
    {
        Ok(Ok(started)) => started,
        Ok(Err(error)) => {
            let _ = ready.send(Err(error));
            return;
        }
        Err(_) => {
            let _ = ready.send(Err(Error::Timeout));
            return;
        }
    };
    let pumped = async {
        theirs.set_nonblocking(true)?;
        let mut theirs = tokio::net::UnixStream::from_std(theirs)?;
        let mut channel = channel.into_stream();
        let _ = ready.send(Ok(()));
        // Ends when either side closes: the worker dropping its end on
        // disconnect, or the remote bridge exiting.
        tokio::io::copy_bidirectional(&mut channel, &mut theirs).await
    };
    let handles = Arc::new(handles);
    // One command at a time; an upload runs beside them, so a long one
    // never delays a check. A job still queued when the pump ends is
    // dropped, which its caller sees as a closed connection.
    let jobs = async {
        while let Some(job) = queue.recv().await {
            let Some(last) = handles.len().checked_sub(1) else {
                let _ = job.reply.send(Err(Error::EmptyRoute));
                continue;
            };
            match job.input {
                Some(input) => {
                    let handles = handles.clone();
                    tokio::spawn(async move {
                        let outcome =
                            upload(&handles[last], job.command, input, job.timeout, job.limit)
                                .await;
                        let _ = job.reply.send(outcome);
                    });
                }
                None => {
                    let outcome = exec(&handles[last], job.command, job.timeout, job.limit).await;
                    let _ = job.reply.send(outcome);
                }
            }
        }
        std::future::pending::<()>().await;
    };
    tokio::select! {
        pumped = pumped => {
            if let Err(error) = pumped {
                tracing::info!(kind = ?error.kind(), "ssh bridge pump ended");
            }
        }
        () = jobs => {}
    }
    // The last hop first: each inner session rides on the one before it.
    for handle in handles.iter().rev() {
        let _ = handle.disconnect(Disconnect::ByApplication, "", "en").await;
    }
}

async fn exec(
    session: &Session,
    command: String,
    timeout: Duration,
    limit: usize,
) -> Result<Output> {
    let channel = session.channel_open_session().await?;
    channel.exec(true, command).await?;
    tokio::time::timeout(timeout, collect(channel, limit))
        .await
        .map_err(|_| Error::CommandTimeout)
}

/// Run `command`, feeding it `input` until that closes, then collect its
/// output. Each wait, for a chunk, for the host to take one, or for the
/// output, may last `idle`.
async fn upload(
    session: &Session,
    command: String,
    mut input: tokio::sync::mpsc::Receiver<Vec<u8>>,
    idle: Duration,
    limit: usize,
) -> Result<Output> {
    let channel = session.channel_open_session().await?;
    channel.exec(true, command).await?;
    let (read, write) = channel.split();
    let output = tokio::spawn(drain(read, limit));
    loop {
        let chunk = tokio::time::timeout(idle, input.recv())
            .await
            .map_err(|_| Error::CommandTimeout)?;
        let Some(chunk) = chunk else { break };
        tokio::time::timeout(idle, write.data_bytes(chunk))
            .await
            .map_err(|_| Error::CommandTimeout)??;
    }
    write.eof().await?;
    tokio::time::timeout(idle, output)
        .await
        .map_err(|_| Error::CommandTimeout)?
        .map_err(|_| Error::SshClosed)
}

type Session = Handle<Verifier>;

/// Connect and authenticate every hop, then start the bridge on the last.
async fn start(
    route: Route,
    command: String,
) -> Result<(Vec<Session>, russh::Channel<client::Msg>)> {
    let config = Arc::new(client::Config {
        keepalive_interval: route.keepalive,
        keepalive_max: 4,
        inactivity_timeout: None,
        nodelay: true,
        ..client::Config::default()
    });
    let mut sessions: Vec<Session> = Vec::with_capacity(route.hops.len());
    let mut previous = String::new();
    for (index, hop) in route.hops.into_iter().enumerate() {
        let seen = Arc::new(Mutex::new(None));
        let verifier = Verifier {
            pinned: hop.pinned.clone(),
            seen: seen.clone(),
        };
        let connected = match sessions.last() {
            None => client::connect(config.clone(), (hop.host.as_str(), hop.port), verifier).await,
            Some(jump) => {
                let tunnel = jump
                    .channel_open_direct_tcpip(
                        hop.host.as_str(),
                        u32::from(hop.port),
                        "127.0.0.1",
                        0,
                    )
                    .await
                    .map_err(|source| Error::Jump {
                        jump: previous.clone(),
                        target: hop.address(),
                        source: Box::new(source),
                    })?;
                client::connect_stream(config.clone(), tunnel.into_stream(), verifier).await
            }
        };
        let mut session = match connected {
            Ok(session) => session,
            Err(russh::Error::UnknownKey) => {
                let fingerprint = seen
                    .lock()
                    .ok()
                    .and_then(|seen| seen.clone())
                    .unwrap_or_default();
                return Err(Error::UntrustedHostKey {
                    hop: index,
                    host: hop.address(),
                    fingerprint,
                    changed: hop.pinned.is_some(),
                });
            }
            Err(error) => return Err(error.into()),
        };
        auth::authenticate(&mut session, &hop, index).await?;
        previous = hop.address();
        sessions.push(session);
    }
    let last = sessions.last().ok_or(Error::EmptyRoute)?;
    let channel = last.channel_open_session().await?;
    channel.exec(true, command).await?;
    Ok((sessions, channel))
}

/// Accepts exactly the pinned key, recording what was offered either way.
struct Verifier {
    pinned: Option<String>,
    seen: Arc<Mutex<Option<String>>>,
}

impl client::Handler for Verifier {
    type Error = russh::Error;

    async fn check_server_key(
        &mut self,
        key: &PublicKeyOrCertificate,
    ) -> std::result::Result<bool, Self::Error> {
        let key = match key {
            PublicKeyOrCertificate::PublicKey { key, .. } => key.clone(),
            PublicKeyOrCertificate::Certificate(certificate) => {
                PublicKey::from(certificate.public_key().clone())
            }
        };
        let fingerprint = fingerprint(&key);
        let trusted = self.pinned.as_deref() == Some(fingerprint.as_str());
        if let Ok(mut seen) = self.seen.lock() {
            *seen = Some(fingerprint);
        }
        Ok(trusted)
    }
}

/// OpenSSH's `SHA256:…` form, as `ssh-keygen -lf` prints it.
pub fn fingerprint(key: &PublicKey) -> String {
    key.fingerprint(HashAlg::Sha256).to_string()
}
