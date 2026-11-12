//! A chat view of a pane's agent transcript, for exactly the pane Herdr
//! reports. Herdr knows which session each agent in a pane writes
//! (`agent_session`, kind `path` or `id`); the client endpoint does not
//! carry it, so each read runs Herdr's own CLI on the host over the bridge's
//! SSH session, asks it for that pane and its foreground process, and reads
//! a byte range of the file it names (or, for OpenCode, a bounded one-based
//! `seq` window of its own SQLite store). Nothing is started, imported or
//! resumed: the agent keeps running in its terminal, and chat only reads
//! what it wrote.
//!
//! Everything from the host is untrusted. The reference must be the
//! agent_session Herdr reports, resolved inside that agent's own session
//! root (for `path`) or its recorded file (for `id`), which the host checks
//! again after resolving links; only complete lines are parsed, text is
//! capped, and an identity that changed between reads (another session, a
//! fork, a replaced pane or process) starts over from the end of the new
//! transcript instead of mixing two.
//!
//! Images stay on the host until chat shows one: an entry names each image
//! (its part and where its bytes are) and a media read fetches that one,
//! inline base64 from its line or a `blob:sha256:` file from OMP's blob
//! store beside the session folder, for the same agent instance only.

/// Per-engine transcript row parsers: one module per agent core speaks.
/// `pi`/`claude`/`codex`/`opencode`/`qwen`/`hermes`/`kilo`/`grok`/`copilot`/`mastracode`/`kimi` own their line/row formats; the
/// shared OMP record parser below handles `omp` and serves as the base for `pi`.
mod claude;
mod codex;
mod copilot;
mod grok;
mod hermes;
mod kilo;
mod mastracode;
mod kimi;
mod opencode;
mod pi;
mod qwen;
mod subagents;

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;

use crate::{Error, Result, herdr_setup::plain_text, ssh::Exec};
use base64::{
    Engine,
    engine::{
        DecodePaddingMode,
        general_purpose::{GeneralPurpose, GeneralPurposeConfig, STANDARD},
    },
};
use herdr_client::{HERDR_CANDIDATES, shell_quote};
use russh::keys::HashAlg;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::time::Duration;

/// A read over a slow mobile link, plus a second for each further 512 KiB.
const TIMEOUT: Duration = Duration::from_secs(20);
/// The newest part of a transcript, enough for a first screen.
const FIRST_WINDOW: u64 = 256 * 1024;
/// A window doubles up to this to fit one long line; a longer one is refused.
/// It holds a line carrying one inline image at [`MAX_IMAGE`] (base64 of
/// 20 MiB is 27 962 028 bytes) with room for its text and JSON.
const MAX_WINDOW: u64 = 32 * 1024 * 1024;
/// Room for Herdr's pane and process reports around the data.
const HEADER_ROOM: usize = 64 * 1024;
pub(super) const TEXT_LIMIT: usize = 64 * 1024;
pub(super) const RESULT_LIMIT: usize = 16 * 1024;
pub(super) const ARGS_LIMIT: usize = 8 * 1024;
const PATH_LIMIT: usize = 4096;
pub(super) const BLOB_PREFIX: &str = "blob:sha256:";
/// The most image bytes one media read decodes and hands to the app.
pub const MAX_IMAGE: u64 = 20 * 1024 * 1024;
/// The media request and answer shape this core speaks.
const MEDIA_VERSION: u32 = 1;
/// Reads that may chain within one fetch: identity, a reset, and window
/// growth from [`FIRST_WINDOW`] to [`MAX_WINDOW`].
const MAX_READS: usize = 12;

#[derive(Debug, Clone, Deserialize)]
pub struct Request {
    pub pane: String,
    /// What the caller already shows; `None` when it shows nothing yet.
    /// A media read needs it: the image must come from that instance.
    #[serde(default)]
    pub expect: Option<Identity>,
    #[serde(default)]
    pub read: Read,
    /// Read one image instead of transcript lines.
    #[serde(default)]
    pub media: Option<Media>,
}

/// One image an [`Entry`] named: the entry's line `[offset, end)`, the
/// image's `part` in its content, and, for a blob, its hash.
#[derive(Debug, Clone, PartialEq, Eq, Deserialize)]
pub struct Media {
    pub v: u32,
    /// The entry's ID, checked against the line; `None` for entries without one.
    #[serde(default)]
    pub entry: Option<String>,
    pub offset: u64,
    pub end: u64,
    pub part: usize,
    #[serde(default)]
    pub blob: Option<String>,
}

/// Which bytes to read. Offsets are line boundaries a previous [`Response`]
/// returned.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(tag = "mode", rename_all = "snake_case")]
pub enum Read {
    /// The newest complete lines.
    #[default]
    Tail,
    /// Lines appended since `offset`.
    After { offset: u64 },
    /// Older lines ending at `offset`.
    Before { offset: u64 },
}

/// Which agent the transcript belongs to; any change starts over.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Identity {
    pub pane_id: String,
    pub terminal_id: String,
    /// The agent process in the pane's foreground, when Herdr reports one.
    pub pid: Option<u32>,
    /// The exact `agent_session.value` Herdr reports: the session file for
    /// `path` refs (OMP, Pi), the session ID for `id` refs (the rest). Never
    /// rewritten to a resolved path, so identity cannot drift between reads.
    pub path: String,
    /// The canonical agent name: `omp`, `pi`, `claude`, `codex`, `opencode`,
    /// `qwen`, `hermes`, `kilo`, `copilot`, `mastracode`, `kimi`.
    #[serde(default = "omp")]
    pub agent: String,
    /// Where Herdr got the ref, such as `herdr:omp` or `herdr:claude`.
    #[serde(default = "herdr_omp")]
    pub source: String,
    /// The ref Herdr stores: `path` or `id`.
    #[serde(default = "path_kind")]
    pub kind: String,
}

fn omp() -> String {
    "omp".to_owned()
}
fn herdr_omp() -> String {
    "herdr:omp".to_owned()
}
fn path_kind() -> String {
    "path".to_owned()
}

/// The migration default for old serialized OMP drafts, not a public shim.
impl Default for Identity {
    fn default() -> Self {
        Self {
            pane_id: String::new(),
            terminal_id: String::new(),
            pid: None,
            path: String::new(),
            agent: omp(),
            source: herdr_omp(),
            kind: path_kind(),
        }
    }
}

/// What [`fetch`] answers: transcript lines, the one image asked for, or a
/// typed refusal that Chat says in its own words.
#[derive(Debug, Serialize)]
#[serde(untagged)]
pub enum Answer {
    Page(Response),
    Media { media: MediaAnswer },
    Refused { refused: Refused },
}

/// Why Chat will never read this session, as opposed to an error that may
/// clear. Each one has its own text in the app; none is retried by itself.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Refused {
    /// The session is stored in a format Chat does not read.
    UnsupportedFormat { format: &'static str },
    /// The agent keeps its sessions outside the SSH user's home folder.
    OutsideHome,
    /// The agent's store is in a schema format the reader was not written
    /// for; `found` is null when the store names no single format.
    UnsupportedVersion { agent: &'static str, found: Option<u32>, supported: u32 },
}

#[derive(Debug, Serialize)]
pub struct Response {
    pub identity: Identity,
    /// Herdr's agent status for the pane, such as `working` or `blocked`.
    pub status: String,
    /// Discard everything shown before: these entries replace it.
    pub reset: bool,
    pub size: u64,
    /// The byte range these entries came from; `[start, end)`.
    pub start: u64,
    pub end: u64,
    /// Older entries than `start` exist, stated explicitly: byte engines
    /// infer it from `start > 0`, sequence engines (OpenCode) name it.
    pub has_older: bool,
    pub entries: Vec<Entry>,
}

/// One image's bytes as base64, or why there are none.
#[derive(Debug, PartialEq, Eq, Serialize)]
pub struct MediaAnswer {
    pub v: u32,
    #[serde(flatten)]
    pub outcome: MediaOutcome,
}

#[derive(Debug, PartialEq, Eq, Serialize)]
#[serde(untagged)]
pub enum MediaOutcome {
    /// `mime` is read from the bytes, never taken from the transcript.
    Image {
        mime: &'static str,
        data: String,
    },
    Refused {
        error: Refusal,
    },
}

/// Why an image is not shown. None of these is retried by the core.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Refusal {
    /// Another agent, process or session file is in the pane now.
    InstanceChanged,
    /// The line no longer holds that entry or that image.
    Moved,
    /// No such blob in the store, or the request named no valid image.
    Missing,
    /// The blob resolves outside OMP's blob store.
    Unconfined,
    /// Which blob store the OMP process uses could not be told exactly.
    UnknownStore,
    /// The blob's bytes do not hash to its name.
    Corrupt,
    TooLarge,
    /// Not valid base64, or not a PNG, JPEG, GIF or WebP.
    Unsupported,
    /// A media version this core does not speak.
    Version,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct Entry {
    pub id: Option<String>,
    pub parent_id: Option<String>,
    /// Where the line starts in the file: a stable key for entries without an ID.
    pub offset: u64,
    /// Where the line ends, after its newline.
    pub end: u64,
    pub kind: Kind,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub role: Option<String>,
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub blocks: Vec<Block>,
    /// When OMP wrote the message, epoch milliseconds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub timestamp: Option<u64>,
    /// What an assistant message cost, as OMP counted it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub usage: Option<Usage>,
    /// Why an assistant message ended, such as `stop`, `toolUse` or `error`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stop_reason: Option<String>,
    /// How a user message reached a running agent, as OMP recorded it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub steer: Option<Steer>,
    /// Async subagent runs this entry settles, as the runtime recorded them.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub subagent_ends: Vec<subagents::Completion>,
}

/// How OMP recorded a user message that reached a running agent:
/// `steering: true` marks one injected mid-turn, and `liveSteered: true` one
/// the active response took in. Either is already in the history; neither
/// says anything about messages still waiting.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Steer {
    Steering,
    Live,
}

/// An assistant message's token counts and OMP's own cost estimate in USD.
#[derive(Debug, Clone, Copy, PartialEq, Serialize)]
pub struct Usage {
    pub input: u64,
    pub output: u64,
    pub total_tokens: u64,
    /// Absent when OMP has no price for the model.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub cost: Option<f64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Message,
    Compaction,
    /// Where the history shown stops at another session's turns; no content.
    Boundary,
    /// Kept without content so the branch can be walked through it.
    Other,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum Block {
    Text {
        text: String,
        truncated: bool,
    },
    Thinking {
        text: String,
        truncated: bool,
    },
    ToolCall {
        call_id: String,
        name: String,
        args: String,
        intent: Option<String>,
        truncated: bool,
    },
    ToolResult {
        call_id: String,
        name: String,
        text: String,
        is_error: bool,
        images: Vec<Image>,
        truncated: bool,
        /// The command's exit code, for tools that report one.
        #[serde(skip_serializing_if = "Option::is_none")]
        exit_code: Option<i64>,
        #[serde(skip_serializing_if = "Vec::is_empty")]
        answers: Vec<FormAnswer>,
        timed_out: bool,
        /// Subagent children this tool call reported in its details.
        #[serde(skip_serializing_if = "Vec::is_empty")]
        subagents: Vec<subagents::Subagent>,
    },
    Image(Image),
    /// A turn's stop, or one plan item, which the app words in its own language.
    Marker {
        marker: Marker,
        /// The plan item's text; empty for a stop.
        text: String,
        truncated: bool,
    },
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Marker {
    /// The person stopped the turn.
    StoppedByYou,
    /// The turn was stopped, no one says by whom.
    Stopped,
    Done,
    ToDo,
}

/// Display-only question answers emitted by OMP's form tool.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct FormAnswer {
    pub name: String,
    pub question: String,
    pub answer: String,
    pub options: Vec<FormAnswerOption>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct FormAnswerOption {
    pub label: String,
    pub selected: bool,
}

/// An image part of a message: its index in the content, and where its bytes are.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Image {
    pub part: usize,
    pub source: Source,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(tag = "kind", rename_all = "snake_case")]
pub enum Source {
    /// Base64 in the line; `bytes` is the decoded size it claims.
    Inline { bytes: u64 },
    /// A file in OMP's blob store, named by this SHA-256.
    Blob { hash: String },
    /// Neither: no data, or a reference that is not a valid blob hash.
    Unreadable,
}

/// Read the pane's transcript, or with [`Request::media`] one image of it.
/// Blocks on the host for at most a few reads.
pub fn fetch(exec: &Exec, session: &str, request: &Request) -> Result<Answer> {
    if let Some(media) = &request.media {
        let outcome = match &request.expect {
            Some(expect) => image(exec, session, &request.pane, expect, media)?,
            None => MediaOutcome::Refused {
                error: Refusal::InstanceChanged,
            },
        };
        return Ok(Answer::Media {
            media: MediaAnswer {
                v: MEDIA_VERSION,
                outcome,
            },
        });
    }
    match page(exec, session, request) {
        Ok(page) => Ok(Answer::Page(page)),
        Err(Error::UnsupportedFormat(format)) => Ok(Answer::Refused {
            refused: Refused::UnsupportedFormat { format },
        }),
        Err(Error::SessionRootOutsideHome) => Ok(Answer::Refused {
            refused: Refused::OutsideHome,
        }),
        Err(Error::UnsupportedHermes { found, supported }) => Ok(Answer::Refused {
            refused: Refused::UnsupportedVersion { agent: "hermes", found, supported },
        }),
        Err(error) => Err(error),
    }
}

fn page(exec: &Exec, session: &str, request: &Request) -> Result<Response> {
    let mut expect = request.expect.clone();
    let mut read = request.read;
    let mut window = FIRST_WINDOW;
    let mut reset = false;
    for _ in 0..MAX_READS {
        let (script, limit) = match &expect {
            // OpenCode pages its own `seq` sequence, Qwen its whole active
            // chain (a tree, not a byte range), Hermes its SQLite `messages`
            // ids, Kilo its messages' places in (time_created, id) order,
            // Grok its rewind-filtered update rows, Copilot its checked event
            // log, Mastra Code its thread's (createdAt, id) order and Kimi its
            // whole active wire chain, read only, each via its module.
            Some(identity)
                if matches!(
                    identity.agent.as_str(),
                    "opencode" | "qwen" | "hermes" | "kilo" | "grok" | "copilot" | "mastracode" | "kimi"
                ) =>
            {
                let page = match identity.agent.as_str() {
                    "qwen" => qwen::page(exec, session, &request.pane, identity, read, reset),
                    "hermes" => hermes::page(exec, session, &request.pane, identity, read, reset),
                    "kilo" => kilo::page(exec, session, &request.pane, identity, read, reset),
                    "grok" => grok::page(exec, session, &request.pane, identity, read, reset),
                    "copilot" => copilot::page(exec, session, &request.pane, identity, read, reset),
                    "mastracode" => mastracode::page(exec, session, &request.pane, identity, read, reset),
                    "kimi" => kimi::page(exec, session, &request.pane, identity, read, reset),
                    _ => opencode::page(exec, session, &request.pane, identity, read, reset),
                };
                match page {
                    Ok(page) => return Ok(page),
                    Err(error @ Error::NoTranscript) => {
                        let current =
                            instance(exec, session, &request.pane)?.ok_or(Error::NoTranscript)?;
                        if expect.as_ref() == Some(&current) {
                            return Err(error);
                        }
                        expect = Some(current);
                        read = Read::Tail;
                        window = FIRST_WINDOW;
                        reset = true;
                        continue;
                    }
                    Err(error) => return Err(error),
                }
            }
            Some(identity) => (
                script(
                    session,
                    &request.pane,
                    Some(Fetch::Lines(Target {
                        path: &identity.path,
                        read,
                        window,
                    })),
                ),
                usize::try_from(window).unwrap_or(usize::MAX) + 1 + HEADER_ROOM,
            ),
            // Nothing is known yet: only the pane report runs.
            None => (script(session, &request.pane, None), HEADER_ROOM),
        };
        let output = exec.run(
            format!("/bin/sh -c {}", shell_quote(&script)),
            read_timeout(window),
            limit,
        )?;
        let reply = match parse_reply(output.exit_status, &output.tail) {
            Ok(reply) => reply,
            Err(error) => {
                // File resolution can refuse the old reference after a fork or
                // restart. The already-emitted pane report still identifies the
                // replacement; adopt it rather than polling the old path forever.
                if let Ok(Some(current)) = parse_instance(Some(0), &output.tail) {
                    if expect.as_ref() != Some(&current) {
                        expect = Some(current);
                        read = Read::Tail;
                        window = FIRST_WINDOW;
                        reset = true;
                        continue;
                    }
                }
                return Err(error);
            }
        };
        let (identity, status) = identity(&reply.pane, &reply.process)?;
        if expect.as_ref() != Some(&identity) {
            expect = Some(identity);
            read = Read::Tail;
            window = FIRST_WINDOW;
            reset = true;
            continue;
        }
        let file = reply.file.ok_or(Error::BadReply)?;
        match plan(read, &file, window) {
            Plan::Lines { start, end, lines } => {
                // Computed before `identity` is moved into the response.
                let entries = entries(&identity.agent, lines, start);
                return Ok(Response {
                    identity,
                    status,
                    reset: reset || read == Read::Tail,
                    size: file.size,
                    start,
                    end,
                    // A byte window at a nonzero start still has older lines.
                    has_older: start > 0,
                    entries,
                });
            }
            Plan::Rewritten => {
                read = Read::Tail;
                window = FIRST_WINDOW;
                reset = true;
            }
            Plan::Grow if window < MAX_WINDOW => window *= 2,
            Plan::Grow => return Err(Error::EntryTooLarge),
        }
    }
    Err(Error::BadReply)
}

/// One image of the instance `expect`: its entry's line is read again and
/// must still name that image, and the pane must hold `expect` both before
/// and after the read, all in one exec on the host.
fn image(
    exec: &Exec,
    session: &str,
    pane: &str,
    expect: &Identity,
    media: &Media,
) -> Result<MediaOutcome> {
    let refused = |error| Ok(MediaOutcome::Refused { error });
    if media.v != MEDIA_VERSION {
        return refused(Refusal::Version);
    }
    // OpenCode's rows are a keyed sequence, not byte ranges: its own module
    // fetches by `seq` and rechecks the instance before and after.
    if expect.agent == "opencode" {
        return opencode::image(exec, session, pane, expect, media);
    }
    // Qwen's images are shown as references only, and Hermes, Kilo, Grok,
    // Copilot, Mastra Code and Kimi history is read without its media: chat
    // never fetches any, so every image is Unreadable.
    if matches!(expect.agent.as_str(), "qwen" | "hermes" | "kilo" | "grok" | "copilot" | "mastracode" | "kimi") {
        return refused(Refusal::Unsupported);
    }
    let len = match media.end.checked_sub(media.offset) {
        Some(len) if len > 0 && len <= MAX_WINDOW => len,
        // A line that long holds more than chat reads at once.
        Some(len) if len > MAX_WINDOW => return refused(Refusal::TooLarge),
        _ => return refused(Refusal::Missing),
    };
    let blob = match media.blob.as_deref() {
        None => None,
        Some(hash) if !blob_hash(hash) => return refused(Refusal::Missing),
        Some(_) if expect.agent != "omp" => return refused(Refusal::Unsupported),
        Some(hash) => match expect.pid {
            Some(pid) => Some((hash, pid)),
            // Without the process its store cannot be told.
            None => return refused(Refusal::UnknownStore),
        },
    };
    let fetch = Fetch::Image {
        path: &expect.path,
        offset: media.offset,
        len,
        blob,
    };
    let bytes = len + if blob.is_some() { MAX_IMAGE } else { 0 };
    let output = exec.run(
        format!(
            "/bin/sh -c {}",
            shell_quote(&script(session, pane, Some(fetch)))
        ),
        read_timeout(bytes),
        usize::try_from(bytes).unwrap_or(usize::MAX) + 1 + 2 * HEADER_ROOM,
    )?;
    media_outcome(output.exit_status, &output.tail, expect, media)
}

/// How long a read of `bytes` may take: [`TIMEOUT`], plus a second for each 512 KiB.
fn read_timeout(bytes: u64) -> Duration {
    TIMEOUT + Duration::from_secs(bytes / (512 * 1024))
}

/// The image in a media script's reply, once the pane held `expect` before
/// and after it and the line still names that image.
fn media_outcome(
    exit_status: Option<u32>,
    bytes: &[u8],
    expect: &Identity,
    media: &Media,
) -> Result<MediaOutcome> {
    // The pane is reported before anything else is looked at, so a refusal
    // still says whose pane it was.
    let file_refusal = match exit_status {
        Some(EXIT_NO_MEDIA | EXIT_NO_FILE) => Some(Refusal::Missing),
        Some(EXIT_TOO_LARGE) => Some(Refusal::TooLarge),
        // Media keeps its own vocabulary: outside home is unconfined there.
        Some(EXIT_UNCONFINED | EXIT_OUTSIDE_HOME) => Some(Refusal::Unconfined),
        Some(EXIT_NO_STORE) => Some(Refusal::UnknownStore),
        Some(EXIT_MOVED) => Some(Refusal::Moved),
        _ => None,
    };
    let reply = parse_reply(file_refusal.map_or(exit_status, |_| Some(0)), bytes)?;
    let refused = |error| Ok(MediaOutcome::Refused { error });
    if !same_instance(&reply.pane, &reply.process, expect)? {
        return refused(Refusal::InstanceChanged);
    }
    if let Some(error) = file_refusal {
        return refused(error);
    }
    let file = reply.file.ok_or(Error::BadReply)?;
    // Cut by the lengths the host reported, never by a marker the session
    // file's own bytes could hold.
    fn take(bytes: &[u8], len: u64) -> Result<(&[u8], &[u8])> {
        let len = usize::try_from(len).map_err(|_| Error::BadReply)?;
        (bytes.len() >= len)
            .then(|| bytes.split_at(len))
            .ok_or(Error::BadReply)
    }
    let (line, rest) = take(
        file.data,
        file.size
            .saturating_sub(file.start)
            .min(media.end - media.offset),
    )?;
    let (blob, rest) = match &media.blob {
        Some(_) => {
            let rest = rest.strip_prefix(b"\n@@blob ").ok_or(Error::BadReply)?;
            let (size, rest) = split(rest, b"\n@@data\n").ok_or(Error::BadReply)?;
            let size = std::str::from_utf8(size)
                .ok()
                .and_then(|size| size.parse::<u64>().ok())
                .ok_or(Error::BadReply)?;
            let (blob, rest) = take(rest, size)?;
            (Some(blob), rest)
        }
        None => (None, rest),
    };
    let after = rest.strip_prefix(b"\n@@after\n").ok_or(Error::BadReply)?;
    let (pane, process) = split(after, b"\n@@proc\n").ok_or(Error::BadReply)?;
    let pane = serde_json::from_slice(pane)?;
    let process = serde_json::from_slice(process).unwrap_or(Value::Null);
    if !same_instance(&pane, &process, expect)? {
        return refused(Refusal::InstanceChanged);
    }
    let decoded = match image_part_bytes(&expect.agent, &file, line, blob, media) {
        Ok(decoded) => decoded,
        Err(error) => return refused(error),
    };
    match image_mime(&decoded) {
        Some(mime) => Ok(MediaOutcome::Image {
            mime,
            data: STANDARD.encode(&decoded),
        }),
        None => refused(Refusal::Unsupported),
    }
}

/// Whether Herdr's reports name the instance `expect`.
fn same_instance(pane: &Value, process: &Value, expect: &Identity) -> Result<bool> {
    match identity(pane, process) {
        Ok((identity, _)) => Ok(identity == *expect),
        Err(Error::NoTranscript | Error::TranscriptUnconfined) => Ok(false),
        Err(error) => Err(error),
    }
}

/// The image bytes `media` names, once `line` (read from `file.start`) is
/// still that whole entry and its part is still that image: inline base64,
/// or a reference to exactly the blob read, whose bytes hash to its name.
fn image_part_bytes(
    agent: &str,
    file: &File,
    line: &[u8],
    blob: Option<&[u8]>,
    media: &Media,
) -> std::result::Result<Vec<u8>, Refusal> {
    if file.start != media.offset
        || file.size < media.end
        || line.len() as u64 != media.end - media.offset
        || line.last() != Some(&b'\n')
    {
        return Err(Refusal::Moved);
    }
    let value: Value = serde_json::from_slice(line).map_err(|_| Refusal::Moved)?;
    let id = match agent {
        "claude" => value["uuid"].as_str().map(str::to_owned),
        "codex" => codex::message_id(&value),
        _ => value["id"].as_str().map(str::to_owned),
    };
    if id.as_deref() != media.entry.as_deref() {
        return Err(Refusal::Moved);
    }
    if agent != "omp" {
        if media.blob.is_some() || blob.is_some() {
            return Err(Refusal::Unsupported);
        }
        let data = match agent {
            "pi" => pi::image_data(&value, media.part),
            "claude" => claude::image_data(&value, media.part),
            "codex" => codex::image_data(&value, media.part),
            _ => None,
        }
        .ok_or(Refusal::Moved)?;
        if inline_bytes(data) > MAX_IMAGE {
            return Err(Refusal::TooLarge);
        }
        return LENIENT.decode(data).map_err(|_| Refusal::Unsupported);
    }
    let part = &content_parts(&value)
        .and_then(|parts| parts.get(media.part))
        .ok_or(Refusal::Moved)?;
    if part["type"] != "image" {
        return Err(Refusal::Moved);
    }
    let data = part["data"].as_str().ok_or(Refusal::Moved)?;
    match (data.strip_prefix(BLOB_PREFIX), media.blob.as_deref(), blob) {
        (Some(named), Some(hash), Some(bytes)) if named == hash => {
            let digest = HashAlg::Sha256.digest(bytes);
            if hex(&digest) == hash {
                Ok(bytes.to_vec())
            } else {
                Err(Refusal::Corrupt)
            }
        }
        (None, None, None) if inline_bytes(data) > MAX_IMAGE => Err(Refusal::TooLarge),
        (None, None, None) => LENIENT.decode(data).map_err(|_| Refusal::Unsupported),
        _ => Err(Refusal::Moved),
    }
}

fn hex(bytes: &[u8]) -> String {
    use std::fmt::Write;
    let mut hex = String::with_capacity(bytes.len() * 2);
    for byte in bytes {
        let _ = write!(hex, "{byte:02x}");
    }
    hex
}

/// Base64 as OMP's `Buffer.from(…, "base64")` writes it, padded or not.
const LENIENT: GeneralPurpose = GeneralPurpose::new(
    &base64::alphabet::STANDARD,
    GeneralPurposeConfig::new().with_decode_padding_mode(DecodePaddingMode::Indifferent),
);

/// The decoded size base64 `data` claims, without decoding it.
fn inline_bytes(data: &str) -> u64 {
    let padding = data.bytes().rev().take_while(|&byte| byte == b'=').count();
    ((data.len() - padding) as u64 * 3) / 4
}

/// A canonical OMP blob name: exactly 64 lowercase hex digits.
fn blob_hash(hash: &str) -> bool {
    hash.len() == 64
        && hash
            .bytes()
            .all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

/// The image type the bytes start with; only those Android decodes.
fn image_mime(bytes: &[u8]) -> Option<&'static str> {
    match bytes {
        [0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a, ..] => Some("image/png"),
        [0xff, 0xd8, 0xff, ..] => Some("image/jpeg"),
        [b'G', b'I', b'F', b'8', b'7' | b'9', b'a', ..] => Some("image/gif"),
        [
            b'R',
            b'I',
            b'F',
            b'F',
            _,
            _,
            _,
            _,
            b'W',
            b'E',
            b'B',
            b'P',
            ..,
        ] => Some("image/webp"),
        _ => None,
    }
}

/// The OMP instance in the pane now, read on the host without touching its
/// session file; `None` when the pane names no OMP session. Blocks on the
/// host for one short command.
pub fn instance(exec: &Exec, session: &str, pane: &str) -> Result<Option<Identity>> {
    let output = exec.run(
        format!(
            "/bin/sh -c {}",
            shell_quote(&instance_script(session, pane))
        ),
        TIMEOUT,
        HEADER_ROOM,
    )?;
    parse_instance(output.exit_status, &output.tail)
}

/// The pane report [`instance`] runs, for a caller that appends its own
/// steps after it in the same command; they run only once the pane is read.
pub(crate) fn instance_script(session: &str, pane: &str) -> String {
    script(session, pane, None)
}

/// The instance an [`instance_script`] report names; `bytes` ends where the
/// caller's own output begins.
pub(crate) fn parse_instance(exit_status: Option<u32>, bytes: &[u8]) -> Result<Option<Identity>> {
    let reply = parse_reply(exit_status, bytes)?;
    match identity(&reply.pane, &reply.process) {
        Ok((identity, _)) => Ok(Some(identity)),
        Err(Error::NoTranscript) => Ok(None),
        Err(error) => Err(error),
    }
}

#[derive(Debug, Clone, Copy)]
struct Target<'a> {
    path: &'a str,
    read: Read,
    window: u64,
}

/// What a script reads after the pane: a range of the session file, or the
/// line of one image's entry and, for a blob reference, that blob from the
/// store OMP process `pid` writes to.
#[derive(Debug, Clone, Copy)]
enum Fetch<'a> {
    Lines(Target<'a>),
    Image {
        path: &'a str,
        offset: u64,
        len: u64,
        /// The blob's hash, checked as canonical, and the OMP process.
        blob: Option<(&'a str, u32)>,
    },
}

/// Exit statuses the script uses for refusals.
const EXIT_UNCONFINED: u32 = 3;
const EXIT_NO_HERDR: u32 = 4;
const EXIT_NO_PANE: u32 = 5;
const EXIT_NO_FILE: u32 = 6;
const EXIT_NO_MEDIA: u32 = 7;
const EXIT_TOO_LARGE: u32 = 8;
const EXIT_NO_STORE: u32 = 9;
const EXIT_MOVED: u32 = 10;
/// The resolver found the agent's HOME, a root or the file outside this
/// user's home: a lasting refusal with its own words, not a missing file.
const EXIT_OUTSIDE_HOME: u32 = 11;

/// Report the pane and its processes, then, given what to fetch, those
/// bytes of the session file once it resolves inside OMP's session folder.
/// An image read adds the blob its line names, once it resolves inside OMP's
/// blob store, and reports the pane again after it. Errors are silenced so
/// they never mix with the data.
fn script(session: &str, pane: &str, fetch: Option<Fetch>) -> String {
    let session = shell_quote(session);
    let pane = shell_quote(pane);
    let report = format!(
        r#""$herdr" --session {session} pane get {pane} </dev/null || exit {EXIT_NO_PANE}
printf '\n@@proc\n'
"$herdr" --session {session} pane process-info --pane {pane} </dev/null || printf '{{}}'
"#
    );
    let mut script = format!(
        r#"exec 2>/dev/null
herdr=
{HERDR_CANDIDATES}
    if [ -n "$path" ] && [ -x "$path" ]; then herdr=$path; break; fi
done
[ -n "$herdr" ] || exit {EXIT_NO_HERDR}
printf '@@pane\n'
{report}"#
    );
    let (path, range, blob) = match fetch {
        None => return script,
        Some(Fetch::Image {
            path,
            offset,
            len,
            blob,
        }) => (path, format!("start={offset}\nlen={len}"), Some(blob)),
        Some(Fetch::Lines(target)) => (
            target.path,
            match target.read {
                Read::Tail => format!(
                    "start=$(( size > {w} ? size - {w} : 0 ))\nlen={w}",
                    w = target.window
                ),
                // One byte early: the newline before `offset` shows the file was
                // only appended to.
                Read::After { offset } if offset > 0 => {
                    format!("start={}\nlen={}", offset - 1, target.window + 1)
                }
                Read::After { offset } => format!("start={offset}\nlen={}", target.window),
                Read::Before { offset } => {
                    let start = offset.saturating_sub(target.window);
                    format!("start={start}\nlen={}", offset - start)
                }
            },
            None,
        ),
    };
    script.push_str(&format!(
        r#"home=$(realpath -- "$HOME") || exit {EXIT_UNCONFINED}
real=$(python3 - "$herdr" {session} {pane} {path} "$home" <<'HERDLINE_RESOLVE'
{resolver}
HERDLINE_RESOLVE
) || {{ status=$?; [ "$status" -eq {EXIT_OUTSIDE_HOME} ] && exit {EXIT_OUTSIDE_HOME}; exit {EXIT_UNCONFINED}; }}
[ -f "$real" ] || exit {EXIT_NO_FILE}
size=$(wc -c < "$real") || exit {EXIT_NO_FILE}
size=$((size))
{range}
printf '\n@@file %s %s\n@@data\n' "$size" "$start"
tail -c +$((start + 1)) "$real" | head -c "$len"
"#,
        path = shell_quote(path),
        resolver = RESOLVER,
    ));
    let Some(blob) = blob else {
        return script;
    };
    if let Some((hash, pid)) = blob {
        // Only a blob the entry names, and only the store's own file by that
        // name: no link out of it. The core checks the line exactly.
        script.push_str(&format!(
            r#"tail -c +$((start + 1)) "$real" | head -c "$len" | grep -qF -- '"{BLOB_PREFIX}{hash}"' || exit {EXIT_MOVED}
{store}blob=$(realpath -- "$store"/{hash}) || exit {EXIT_NO_MEDIA}
[ "$blob" = "$store"/{hash} ] || exit {EXIT_UNCONFINED}
[ -f "$blob" ] || exit {EXIT_NO_MEDIA}
bytes=$(wc -c < "$blob") || exit {EXIT_NO_MEDIA}
bytes=$((bytes))
[ "$bytes" -le {MAX_IMAGE} ] || exit {EXIT_TOO_LARGE}
printf '\n@@blob %s\n@@data\n' "$bytes"
head -c "$bytes" "$blob"
"#,
            store = blob_store(pid),
        ));
    }
    script.push_str("printf '\\n@@after\\n'\n");
    script.push_str(&report);
    script
}

/// The session resolver, run on the host. It also resolves the blob store,
/// so a session root and the store follow the same profile rules.
const RESOLVER: &str = include_str!("transcript/resolve_session.py");

/// Sets `$store`: the blob store OMP process `pid` writes to, resolved by
/// [`RESOLVER`]'s `--blob-store` from the same profile and agent dir as the
/// session roots. Exit 3 is a store outside `$HOME`; any other failure,
/// python missing or nothing printed included, is an unknown store, never
/// a guessed one.
fn blob_store(pid: u32) -> String {
    format!(
        r#"store=$(python3 - --blob-store {pid} "$home" <<'HERDLINE_RESOLVE'
{RESOLVER}
HERDLINE_RESOLVE
) || {{ status=$?; [ "$status" -eq {EXIT_UNCONFINED} ] && exit {EXIT_UNCONFINED}; exit {EXIT_NO_STORE}; }}
[ -n "$store" ] || exit {EXIT_NO_STORE}
"#
    )
}

#[derive(Debug)]
struct Reply<'a> {
    pane: Value,
    process: Value,
    file: Option<File<'a>>,
}

#[derive(Debug, PartialEq, Eq)]
struct File<'a> {
    size: u64,
    start: u64,
    data: &'a [u8],
}

fn parse_reply(exit_status: Option<u32>, bytes: &[u8]) -> Result<Reply<'_>> {
    match exit_status {
        Some(0) => {}
        Some(EXIT_UNCONFINED) => return Err(Error::TranscriptUnconfined),
        Some(EXIT_OUTSIDE_HOME) => return Err(Error::SessionRootOutsideHome),
        Some(EXIT_NO_HERDR) => return Err(Error::HerdrNotFound),
        Some(EXIT_NO_PANE) => return Err(Error::PaneGone),
        Some(EXIT_NO_FILE) => return Err(Error::NoTranscript),
        _ => return Err(Error::BadReply),
    }
    let rest = bytes.strip_prefix(b"@@pane\n").ok_or(Error::BadReply)?;
    let (pane, rest) = split(rest, b"\n@@proc\n").ok_or(Error::BadReply)?;
    let (process, file) = match split(rest, b"\n@@file ") {
        Some((process, file)) => (process, Some(file)),
        None => (rest, None),
    };
    let file = file.map(parse_file).transpose()?;
    Ok(Reply {
        pane: serde_json::from_slice(pane)?,
        // A Herdr without process info leaves the PID unknown.
        process: serde_json::from_slice(process).unwrap_or(Value::Null),
        file,
    })
}

fn parse_file(bytes: &[u8]) -> Result<File<'_>> {
    let (header, data) = split(bytes, b"\n@@data\n").ok_or(Error::BadReply)?;
    let header = std::str::from_utf8(header).map_err(|_| Error::BadReply)?;
    let mut numbers = header.split(' ').map(str::parse::<u64>);
    match (numbers.next(), numbers.next(), numbers.next()) {
        (Some(Ok(size)), Some(Ok(start)), None) => Ok(File { size, start, data }),
        _ => Err(Error::BadReply),
    }
}

fn split<'a>(bytes: &'a [u8], marker: &[u8]) -> Option<(&'a [u8], &'a [u8])> {
    let at = bytes
        .windows(marker.len())
        .position(|window| window == marker)?;
    Some((&bytes[..at], &bytes[at + marker.len()..]))
}

/// The pane's transcript identity and agent status, from `pane get` and
/// `pane process-info`.
fn identity(pane: &Value, process: &Value) -> Result<(Identity, String)> {
    let pane = &pane["result"]["pane"];
    let session = &pane["agent_session"];
    let agent = session["agent"]
        .as_str()
        .filter(|agent| {
            matches!(
                *agent,
                "omp" | "pi" | "claude" | "codex" | "opencode" | "qwen" | "hermes" | "kilo" | "grok" | "copilot" | "mastracode" | "kimi"
            )
        })
        .ok_or(Error::NoTranscript)?;
    // The agent's own reported reference, held exactly as Herdr gives it:
    // the session file for `path` (OMP, Pi), the session id for `id` (the
    // rest). Resolution stays in the host script, so identity cannot drift.
    let (kind, value) = match (session["kind"].as_str(), session["value"].as_str()) {
        (Some("path"), Some(value)) if agent == "omp" || agent == "pi" => ("path", value),
        (Some("id"), Some(value))
            if session_id(value)
                && (agent != "qwen" || qwen::session_id(value))
                && (agent != "kilo" || kilo::session_id(value))
                && (agent != "copilot" || copilot::session_id(value))
                && (agent != "kimi" || kimi::session_id(value)) =>
        {
            ("id", value)
        }
        _ => return Err(Error::NoTranscript),
    };
    if kind == "path" && !confined(agent, value) {
        return Err(Error::TranscriptUnconfined);
    }
    let text = |value: &Value| value.as_str().map(str::to_owned).ok_or(Error::BadReply);
    let identity = Identity {
        pane_id: text(&pane["pane_id"])?,
        terminal_id: text(&pane["terminal_id"])?,
        // The pane's foreground process must be that agent's own name; Hermes
        // and Kilo each run as either of two, Mastra Code usually as node,
        // Kimi Code as either of two or as node running its npm entry.
        pid: match agent {
            "hermes" => hermes::PROCESS_NAMES.iter().find_map(|name| foreground_pid(process, name)),
            "kilo" => kilo::PROCESS_NAMES.iter().find_map(|name| foreground_pid(process, name)),
            "mastracode" => mastracode::pid(process),
            "kimi" => kimi::foreground_pid(process),
            _ => foreground_pid(process, agent),
        },
        path: value.to_owned(),
        agent: agent.to_owned(),
        source: text(&session["source"]).unwrap_or_else(|_| format!("herdr:{agent}")),
        kind: kind.to_owned(),
    };
    let status = pane["agent_status"]
        .as_str()
        .filter(|status| {
            status.len() <= 32
                && status
                    .bytes()
                    .all(|byte| byte.is_ascii_lowercase() || byte == b'_')
        })
        .unwrap_or("unknown")
        .to_owned();
    Ok((identity, status))
}

/// A session id (`[A-Za-z0-9._-]+`, no `/`, `..`, leading `.`) exactly as
/// Herdr records it; anything else is not an id any engine can resolve.
fn session_id(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= 512
        && !value.starts_with('.')
        && value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// The pane's foreground process that answers to `name`; OMP stays the only
/// agent whose blob store a media read may ever touch.
fn foreground_pid(process: &Value, name: &str) -> Option<u32> {
    process["result"]["process_info"]["foreground_processes"]
        .as_array()?
        .iter()
        .find(|process| process["name"] == name)?["pid"]
        .as_u64()
        .and_then(|pid| u32::try_from(pid).ok())
}

/// Syntax admission only: the host resolves the actual process-configured
/// session root and checks canonical containment before reading any bytes.
fn confined(agent: &str, path: &str) -> bool {
    if !matches!(agent, "omp" | "pi") {
        return false;
    }
    path.len() <= PATH_LIMIT
        && path.starts_with('/')
        && path.ends_with(".jsonl")
        && !path.chars().any(char::is_control)
        && path
            .split('/')
            .all(|segment| segment != ".." && segment != ".")
}

#[derive(Debug, PartialEq, Eq)]
enum Plan<'a> {
    Lines {
        start: u64,
        end: u64,
        lines: &'a [u8],
    },
    /// The file is shorter, or the bytes at a known boundary moved.
    Rewritten,
    /// No complete line fits the window.
    Grow,
}

/// The complete lines in what was read; a partial line at either edge is
/// left for a later read.
fn plan<'a>(read: Read, file: &File<'a>, window: u64) -> Plan<'a> {
    let data = file.data;
    match read {
        Read::Tail => match complete(data, file.start, file.start > 0) {
            Some((start, end, lines)) => Plan::Lines { start, end, lines },
            None if file.start > 0 => Plan::Grow,
            None => Plan::Lines {
                start: 0,
                end: 0,
                lines: &[],
            },
        },
        Read::After { offset } => {
            if file.size < offset {
                return Plan::Rewritten;
            }
            let data = if offset > 0 {
                match data.split_first() {
                    Some((b'\n', rest)) => rest,
                    _ => return Plan::Rewritten,
                }
            } else {
                data
            };
            match complete(data, offset, false) {
                Some((start, end, lines)) => Plan::Lines { start, end, lines },
                // A line longer than the window, or one still being written.
                None if data.len() as u64 >= window => Plan::Grow,
                None => Plan::Lines {
                    start: offset,
                    end: offset,
                    lines: &[],
                },
            }
        }
        Read::Before { offset } => {
            if file.size < offset {
                return Plan::Rewritten;
            }
            if offset == 0 {
                return Plan::Lines {
                    start: 0,
                    end: 0,
                    lines: &[],
                };
            }
            if data.last() != Some(&b'\n') {
                return Plan::Rewritten;
            }
            match complete(data, file.start, file.start > 0) {
                Some((start, end, lines)) => Plan::Lines { start, end, lines },
                None => Plan::Grow,
            }
        }
    }
}

/// `data` read from `start`, without a leading partial line when
/// `skip_partial` and up to its last newline; `None` without a whole line.
fn complete(data: &[u8], start: u64, skip_partial: bool) -> Option<(u64, u64, &[u8])> {
    let skip = if skip_partial {
        data.iter().position(|&byte| byte == b'\n')? + 1
    } else {
        0
    };
    let rest = &data[skip..];
    let last = rest.iter().rposition(|&byte| byte == b'\n')?;
    let first = start + skip as u64;
    Some((first, first + last as u64 + 1, &rest[..=last]))
}

/// The entries the complete lines `lines` hold, parsed by `agent`'s own
/// record format. Claude splits one message across lines with a shared
/// message id: every part shows, its `usage` is counted once per id.
fn entries(agent: &str, lines: &[u8], start: u64) -> Vec<Entry> {
    let mut offset = start;
    let mut entries = Vec::new();
    let mut used = std::collections::HashSet::new();
    for line in lines.split_inclusive(|&byte| byte == b'\n') {
        let at = offset;
        offset += line.len() as u64;
        let value: Value = match serde_json::from_slice(line) {
            Ok(value) => value,
            Err(error) => {
                tracing::debug!(%error, offset = at, "transcript line skipped");
                continue;
            }
        };
        let mut entry = match agent {
            "pi" => pi::normalize(&value, at, offset),
            "claude" => claude::normalize(&value, at, offset),
            "codex" => match codex::entry(&value, at, offset, &mut used) {
                Some(entry) => entry,
                None => continue,
            },
            _ => entry(&value, at, offset),
        };
        // One `usage` per message id, however many content lines share it.
        if let Some(key) = message_key(agent, &value) {
            if !used.insert(key) {
                entry.usage = None;
            }
        }
        entries.push(entry);
    }
    entries
}

fn message_key(agent: &str, value: &Value) -> Option<String> {
    match agent {
        "pi" => pi::message_key(value),
        "claude" => claude::message_key(value),
        _ => None,
    }
}

pub(super) fn entry(value: &Value, offset: u64, end: u64) -> Entry {
    let id = value["id"].as_str().map(str::to_owned);
    let parent_id = value["parentId"].as_str().map(str::to_owned);
    let (kind, role, blocks) = match value["type"].as_str() {
        Some("message") => message(&value["message"]),
        Some("compaction") => (
            Kind::Compaction,
            None,
            value["summary"]
                .as_str()
                .map(|summary| vec![text_block(summary, false)])
                .unwrap_or_default(),
        ),
        Some("custom_message") if value["display"] == true => (
            Kind::Message,
            Some("custom".to_owned()),
            content(&value["content"]),
        ),
        _ => (Kind::Other, None, Vec::new()),
    };
    // Only a shown message carries its time, usage and stop reason.
    let none = Value::Null;
    let message = if kind == Kind::Message && value["type"] == "message" {
        &value["message"]
    } else {
        &none
    };
    let steer = if message["role"] == "user" {
        if message["liveSteered"] == true {
            Some(Steer::Live)
        } else if message["steering"] == true {
            Some(Steer::Steering)
        } else {
            None
        }
    } else {
        None
    };
    Entry {
        id,
        parent_id,
        offset,
        end,
        kind,
        role,
        blocks,
        timestamp: message["timestamp"].as_u64(),
        usage: usage(&message["usage"]),
        stop_reason: message["stopReason"]
            .as_str()
            .map(|reason| cap(reason, 32).0),
        steer,
        subagent_ends: subagents::completions(if value["type"] == "custom_message" {
            value
        } else {
            message
        }),
    }
}

/// The content parts of a line, where [`content`] and [`tool_result`] read
/// them; a media read finds an image's `part` in the same array.
fn content_parts(value: &Value) -> Option<&Vec<Value>> {
    match value["type"].as_str()? {
        "message" => value["message"]["content"].as_array(),
        "custom_message" => value["content"].as_array(),
        _ => None,
    }
}

/// Usage with every count OMP writes; a cost that is not a finite,
/// non-negative number is absent rather than guessed.
pub(super) fn usage(usage: &Value) -> Option<Usage> {
    Some(Usage {
        input: usage["input"].as_u64()?,
        output: usage["output"].as_u64()?,
        total_tokens: usage["totalTokens"].as_u64()?,
        cost: usage["cost"]["total"]
            .as_f64()
            .filter(|cost| cost.is_finite() && *cost >= 0.0),
    })
}

fn message(message: &Value) -> (Kind, Option<String>, Vec<Block>) {
    let role = message["role"].as_str().unwrap_or_default();
    if role == "custom" && message["display"] == false {
        return (Kind::Other, None, Vec::new());
    }
    let blocks = if role == "toolResult" {
        vec![tool_result(message)]
    } else {
        content(&message["content"])
    };
    (Kind::Message, Some(cap(role, 32).0), blocks)
}

fn tool_result(message: &Value) -> Block {
    let parts = message["content"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let text = parts
        .iter()
        .filter_map(|part| part["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n");
    let (text, truncated) = cap(&plain_text(text.as_bytes()), RESULT_LIMIT);
    Block::ToolResult {
        call_id: string(&message["toolCallId"]),
        name: string(&message["toolName"]),
        text,
        is_error: message["isError"] == true,
        images: parts
            .iter()
            .enumerate()
            .filter(|(_, part)| part["type"] == "image")
            .map(|(part, value)| image_part(part, value))
            .collect(),
        truncated,
        exit_code: message["details"]["exitCode"].as_i64(),
        answers: if message["toolName"] == "ask" {
            message["details"]["answers"]
                .as_array()
                .map(|answers| {
                    answers
                        .iter()
                        .take(128)
                        .filter_map(|answer| {
                            Some(FormAnswer {
                                name: cap(answer["name"].as_str().unwrap_or_default(), 256).0,
                                question: cap(answer["question"].as_str()?, TEXT_LIMIT).0,
                                answer: cap(answer["answer"].as_str()?, TEXT_LIMIT).0,
                                options: answer["options"]
                                    .as_array()
                                    .map(|options| {
                                        options
                                            .iter()
                                            .take(256)
                                            .filter_map(|option| {
                                                Some(FormAnswerOption {
                                                    label: cap(
                                                        option["label"].as_str()?,
                                                        TEXT_LIMIT,
                                                    )
                                                    .0,
                                                    selected: option["selected"].as_bool()?,
                                                })
                                            })
                                            .collect()
                                    })
                                    .unwrap_or_default(),
                            })
                        })
                        .collect()
                })
                .unwrap_or_default()
        } else {
            Vec::new()
        },
        timed_out: message["toolName"] == "ask" && message["details"]["timedOut"] == true,
        subagents: if message["toolName"] == "task" {
            subagents::snapshot(&message["details"])
        } else {
            Vec::new()
        },
    }
}

/// Message content: a string, or an array of typed parts.
fn content(content: &Value) -> Vec<Block> {
    if let Some(text) = content.as_str() {
        return vec![text_block(text, false)];
    }
    let Some(parts) = content.as_array() else {
        return Vec::new();
    };
    parts
        .iter()
        .enumerate()
        .filter_map(|(index, part)| match part["type"].as_str()? {
            "text" => Some(text_block(part["text"].as_str()?, false)),
            "thinking" => Some(text_block(part["thinking"].as_str()?, true)),
            "toolCall" => Some(tool_call(part)),
            "image" => Some(Block::Image(image_part(index, part))),
            _ => None,
        })
        .collect()
}

/// Where image part `part` keeps its bytes. The data itself never leaves
/// the host on a poll.
pub(super) fn image_part(part: usize, value: &Value) -> Image {
    let source = match value["data"].as_str() {
        Some(data) => match data.strip_prefix(BLOB_PREFIX) {
            Some(hash) if blob_hash(hash) => Source::Blob {
                hash: hash.to_owned(),
            },
            Some(_) => Source::Unreadable,
            None if !data.is_empty() => Source::Inline {
                bytes: inline_bytes(data),
            },
            None => Source::Unreadable,
        },
        None => Source::Unreadable,
    };
    Image { part, source }
}

pub(super) fn text_block(text: &str, thinking: bool) -> Block {
    let (text, truncated) = cap(text, TEXT_LIMIT);
    if thinking {
        Block::Thinking { text, truncated }
    } else {
        Block::Text { text, truncated }
    }
}

pub(super) fn tool_call(part: &Value) -> Block {
    let mut arguments = part["arguments"].clone();
    // OMP repeats the intent as `i` among the arguments.
    if let Some(arguments) = arguments.as_object_mut() {
        arguments.remove("i");
    }
    let args = match &arguments {
        Value::Null => String::new(),
        arguments => serde_json::to_string_pretty(arguments).unwrap_or_default(),
    };
    let (args, truncated) = cap(&args, ARGS_LIMIT);
    Block::ToolCall {
        call_id: string(&part["id"]),
        name: cap(&string(&part["name"]), 128).0,
        args,
        intent: part["intent"].as_str().map(|intent| cap(intent, 512).0),
        truncated,
    }
}

pub(super) fn string(value: &Value) -> String {
    value.as_str().unwrap_or_default().to_owned()
}

/// At most `limit` bytes of `text`, cut at a character boundary.
pub(super) fn cap(text: &str, limit: usize) -> (String, bool) {
    if text.len() <= limit {
        return (text.to_owned(), false);
    }
    let mut end = limit;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    (text[..end].to_owned(), true)
}

/// RFC 3339 (`2026-11-09T13:23:53Z`, `…+00:00`, `…-05:00`) to epoch ms, as
/// any engine's timestamp field is written. Short or malformed text parses
/// to nothing rather than panicking; the zone and fractional seconds are
/// taken exactly as written.
pub(super) fn iso_timestamp(text: &str) -> Option<u64> {
    let at = chrono::DateTime::parse_from_rfc3339(text).ok()?;
    u64::try_from(at.timestamp_millis()).ok()
}
