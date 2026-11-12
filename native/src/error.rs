/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
use thiserror::Error;

#[derive(Debug, Error)]
pub enum Error {
    // keep-sorted start
    /// `hop` is the rejecting host's place in the route.
    #[error("{host} rejected the sign-in")]
    AuthRejected { hop: usize, host: String },
    #[error("the host's reply could not be read")]
    BadReply,
    #[error("connection setup was cancelled")]
    Cancelled,
    #[error(transparent)]
    Client(#[from] herdr_client::Error),
    #[error("the command on the host did not finish in time")]
    CommandTimeout,
    #[error("{0} is not an ARGB colour")]
    Colour(String),
    #[error("the route has no hosts")]
    EmptyRoute,
    #[error("a transcript entry is larger than chat can load")]
    EntryTooLarge,
    #[error(transparent)]
    Ghostty(#[from] libghostty_vt::error::Error),
    #[error("Herdr's command-line tool was not found on the host")]
    HerdrNotFound,
    #[error(transparent)]
    Io(#[from] std::io::Error),
    #[error(transparent)]
    Jni(#[from] jni::errors::Error),
    #[error(transparent)]
    Json(#[from] serde_json::Error),
    #[error("jump host {jump} could not reach {target}: {source}")]
    Jump {
        jump: String,
        target: String,
        source: Box<russh::Error>,
    },
    #[error(transparent)]
    Key(#[from] russh::keys::ssh_key::Error),
    #[error("the key is protected by a passphrase")]
    KeyEncrypted,
    #[error("no such session")]
    NoSession,
    #[error("the pane is not an OMP agent with a session file")]
    NoTranscript,
    #[error("not connected to a daemon yet")]
    NotReady,
    #[error("the pane is gone")]
    PaneGone,
    #[error("system random source failed: {0}")]
    Random(getrandom::Error),
    #[error("the agent keeps this session outside the home folder")]
    SessionRootOutsideHome,
    #[error(transparent)]
    Ssh(#[from] russh::Error),
    #[error("SSH closed before the bridge started")]
    SshClosed,
    #[error(transparent)]
    SshKey(#[from] russh::keys::Error),
    #[error("timed out connecting")]
    Timeout,
    #[error("the session file is outside OMP's session folder")]
    TranscriptUnconfined,
    #[error("unknown endpoint method `{0}`")]
    UnknownMethod(String),
    #[error("chat cannot read this session's format: {0}")]
    UnsupportedFormat(&'static str),
    /// A Hermes `state.db` in a schema format the reader was not written for;
    /// `found` is None when the store names no single format. Chat shows its
    /// own text per case, never this one.
    #[error("unsupported Hermes history format (this reader reads {supported})")]
    UnsupportedHermes { found: Option<u32>, supported: u32 },
    #[error("host key {fingerprint} of {host} is not trusted")]
    UntrustedHostKey {
        hop: usize,
        host: String,
        fingerprint: String,
        changed: bool,
    },
    // keep-sorted end
}

pub type Result<T> = std::result::Result<T, Error>;
