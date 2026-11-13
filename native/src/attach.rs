//! Files the user attaches in the composer, staged on the Herdr host over
//! the bridge's own SSH session and nowhere else (docs/attachments.md).
//!
//! An upload is one command with its stdin open: it makes a fresh 0700
//! directory inside a 0700 per-user root and writes the bytes there as a
//! `.part` file, then reports its size and SHA-256. Only when both equal
//! what the phone sent does a second command give the file its name.
//! Every other end, a cancel or a failed check, removes the directory, and
//! the next upload sweeps what a lost link left behind. The host is
//! untrusted input like any terminal: every path it reports is checked
//! again here before the composer pastes it.

#[cfg(test)]
mod tests;

use crate::{
    Result,
    composer::Refusal as SendRefusal,
    ssh::{Exec, Output, Upload, Writer},
    transcript::Identity,
};
use herdr_client::{protocol::ClientPaneInputEvent, shell_quote};
use serde::{Deserialize, Serialize};
use std::{
    collections::HashMap,
    sync::{
        Arc, LazyLock, Mutex, PoisonError,
        atomic::{AtomicI64, AtomicU64, Ordering},
    },
    thread,
    time::Duration,
};

/// OMP's own limit for a pasted image (`MAX_IMAGE_INPUT_BYTES`, 18.18.8).
pub const IMAGE_LIMIT: u64 = 20 * 1024 * 1024;
pub const FILE_LIMIT: u64 = 64 * 1024 * 1024;
/// OpenCode 2.2.7's own cap on any one prompt file, image or not
/// (`MAX_ATTACHMENT_BYTES`, packages/core/src/session/prompt.ts).
pub const OPENCODE_LIMIT: u64 = 20 * 1024 * 1024;
/// The files one delivery carries, as the composer offers them.
pub const MAX_FILES: usize = 10;
pub const NAME_MAX: usize = 80;

/// Each wait of an upload, for a chunk or for the host, may last this long.
const IDLE: Duration = Duration::from_secs(60);
const SHORT: Duration = Duration::from_secs(20);
/// What the staging commands print is a few short lines.
const OUTPUT: usize = 4096;

/// The image types OMP turns into `[Image #N]` when one path is pasted
/// (`BRACKETED_IMAGE_PATH_REGEX` in pi-tui's custom editor).
const IMAGE_EXTENSIONS: [&str; 5] = ["png", "jpg", "jpeg", "gif", "webp"];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Kind {
    Image,
    File,
}

impl Kind {
    pub fn limit(self) -> u64 {
        match self {
            Self::Image => IMAGE_LIMIT,
            Self::File => FILE_LIMIT,
        }
    }
}

/// Why an upload stopped. The codes are stable: Kotlin maps them to words.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Refusal {
    /// The bridge has no SSH session to run commands on.
    NoExec,
    /// The target is not the one this link is connected to.
    TargetChanged,
    /// Attachments go to an agent instance the draft was written for.
    NeedsAgent,
    /// Past the kind's limit, or past [`OPENCODE_LIMIT`] for an OpenCode
    /// target.
    TooLarge,
    BadName,
    /// The staging root is not a private directory of this user's.
    StagingUnsafe,
    /// The host has no `mktemp`, `head` or SHA-256 tool.
    NoTools,
    /// The host's size or SHA-256 is not what the phone sent.
    Mismatch,
    Cancelled,
    /// The host's answer could not be read.
    Failed,
}

/// An upload as Kotlin asks for it.
#[derive(Debug, Clone, Deserialize)]
pub struct Begin {
    pub node: String,
    pub session: String,
    pub boot: String,
    pub pane: String,
    pub instance: Option<Identity>,
    pub name: String,
    pub kind: Kind,
    /// What the content provider reported, when it did.
    #[serde(default)]
    pub size: Option<u64>,
}

impl Begin {
    /// The most this upload may carry: its kind's limit, or less where the
    /// target's own server takes less.
    pub fn limit(&self) -> u64 {
        match &self.instance {
            Some(instance) if instance.agent == "opencode" => self.kind.limit().min(OPENCODE_LIMIT),
            _ => self.kind.limit(),
        }
    }
}

/// A file staged on the host for exactly one composer target.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Attachment {
    pub kind: Kind,
    pub name: String,
    pub path: String,
    pub size: u64,
    pub sha256: String,
    pub node: String,
    pub session: String,
    pub boot: String,
    pub pane: String,
    pub instance: Option<Identity>,
}

/// The target a delivery names, as attachments are bound to it.
#[derive(Debug, Clone, Copy)]
pub struct Bound<'a> {
    pub node: &'a str,
    pub session: &'a str,
    pub boot: &'a str,
    pub pane: &'a str,
    pub instance: Option<&'a Identity>,
}

/// A name that pastes and mentions as it is: `[A-Za-z0-9._-]`, no leading
/// dot, at most [`NAME_MAX`] bytes; an image's ends in an extension OMP
/// recognises.
pub fn valid_name(name: &str, kind: Kind) -> bool {
    let plain = !name.is_empty()
        && name.len() <= NAME_MAX
        && !name.starts_with('.')
        && name
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'));
    plain
        && match kind {
            Kind::Image => name.rsplit_once('.').is_some_and(|(stem, extension)| {
                !stem.is_empty() && IMAGE_EXTENSIONS.contains(&extension)
            }),
            Kind::File => true,
        }
}

/// An absolute path of plain components only, made of `[A-Za-z0-9._/-]`,
/// so pasting or mentioning it needs no quoting and goes nowhere else.
pub fn valid_path(path: &str) -> bool {
    path.len() <= 4096
        && path
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-' | b'/'))
        && path.strip_prefix('/').is_some_and(|rest| {
            rest.split('/')
                .all(|part| !part.is_empty() && part != "." && part != "..")
        })
}

/// Whether `path` is `<dir>/<name>` for a staging directory `dir`.
fn staged_in(path: &str, dir: &str, name: &str) -> bool {
    valid_path(path)
        && valid_path(dir)
        && path
            .strip_prefix(dir)
            .and_then(|rest| rest.strip_prefix('/'))
            == Some(name)
}

/// Refuse `attachments` unless every one was staged for `bound`, an OMP
/// instance, and names a path that is safe to paste.
pub fn admit(attachments: &[Attachment], bound: Bound<'_>) -> std::result::Result<(), SendRefusal> {
    if attachments.is_empty() {
        return Ok(());
    }
    if bound.instance.is_none() {
        return Err(SendRefusal::AttachmentNeedsAgent);
    }
    let fits = |a: &Attachment| {
        a.node == bound.node
            && a.session == bound.session
            && a.boot == bound.boot
            && a.pane == bound.pane
            && a.instance.as_ref() == bound.instance
            && valid_name(&a.name, a.kind)
            && a.path
                .rsplit_once('/')
                .is_some_and(|(dir, name)| staged_in(&a.path, dir, name) && name == a.name)
            && a.size <= a.kind.limit()
    };
    if attachments.iter().all(fits) {
        Ok(())
    } else {
        Err(SendRefusal::AttachmentTarget)
    }
}

/// The pastes one delivery makes: each image alone, so OMP reads it as an
/// image path, then the text with each file mentioned after it.
pub fn pastes(text: &str, attachments: &[Attachment]) -> Vec<ClientPaneInputEvent> {
    let mut events: Vec<ClientPaneInputEvent> = attachments
        .iter()
        .filter(|a| a.kind == Kind::Image)
        .map(|a| ClientPaneInputEvent::Paste(a.path.clone()))
        .collect();
    let body = with_files(text, attachments);
    if !body.is_empty() {
        events.push(ClientPaneInputEvent::Paste(body));
    }
    events
}

/// `text` with each staged file mentioned after it as OMP's `@"path"`; an
/// image is never mentioned, it goes as an image.
pub fn with_files(text: &str, attachments: &[Attachment]) -> String {
    let mut body = text.to_owned();
    for file in attachments.iter().filter(|a| a.kind == Kind::File) {
        if !body.is_empty() && !body.ends_with(char::is_whitespace) {
            body.push(' ');
        }
        body.push_str(&format!("@\"{}\"", file.path));
    }
    body
}

/// The staging root under `$HOME`; the scripts refuse a root that is not
/// a plain path, a real directory, this user's, and 0700.
const ROOT: &str = r#"root="$HOME/.cache/herdline/attachments"
case "$root" in /*) ;; *) exit 10 ;; esac
case "$root" in *[!A-Za-z0-9._/-]*|*//*|*/./*|*/../*) exit 10 ;; esac"#;

/// Exit statuses the scripts use for refusals.
const EXIT_UNSAFE: u32 = 10;
const EXIT_NO_TOOLS: u32 = 11;
const EXIT_MISMATCH: u32 = 12;

/// The SHA-256 of `$1`'s file, as 64 hex digits.
const SHA: &str = r#"sha() {
    if command -v sha256sum >/dev/null; then sha256sum < "$1"
    elif command -v shasum >/dev/null; then shasum -a 256 < "$1"
    else return 1; fi | cut -c1-64
}"#;

fn begin_script(name: &str, limit: u64) -> String {
    let name = shell_quote(name);
    let over = limit.saturating_add(1);
    format!(
        r#"exec 2>/dev/null
umask 077
{ROOT}
{SHA}
for tool in mktemp head wc cut find; do command -v "$tool" >/dev/null || exit {EXIT_NO_TOOLS}; done
mkdir -p "$root" || exit {EXIT_UNSAFE}
[ -d "$root" ] && [ ! -L "$root" ] && [ -O "$root" ] || exit {EXIT_UNSAFE}
chmod 700 "$root" || exit {EXIT_UNSAFE}
find "$root" -mindepth 1 -maxdepth 1 -type d -mtime +7 -exec rm -rf {{}} +
find "$root" -mindepth 2 -maxdepth 2 -name '*.part' -mmin +60 -exec sh -c 'rm -rf "${{1%/*}}"' _ {{}} \;
dir=$(mktemp -d "$root/XXXXXXXXXX") || exit {EXIT_UNSAFE}
printf '@@dir %s\n' "$dir"
set -C
head -c {over} > "$dir/"{name}.part || exit {EXIT_UNSAFE}
size=$(wc -c < "$dir/"{name}.part) || exit {EXIT_UNSAFE}
sum=$(sha "$dir/"{name}.part) || exit {EXIT_NO_TOOLS}
printf '@@part %s %s\n' $size "$sum"
"#
    )
}

fn commit_script(dir: &str, name: &str, size: u64) -> String {
    let dir = shell_quote(dir);
    let name = shell_quote(name);
    format!(
        r#"exec 2>/dev/null
umask 077
{ROOT}
dir={dir}
case "$dir" in "$root"/*) ;; *) exit {EXIT_UNSAFE} ;; esac
case "${{dir#"$root"/}}" in */*|'') exit {EXIT_UNSAFE} ;; esac
[ -d "$dir" ] && [ ! -L "$dir" ] && [ -O "$dir" ] || exit {EXIT_UNSAFE}
part="$dir/"{name}.part
file="$dir/"{name}
[ -f "$part" ] && [ ! -L "$part" ] && [ ! -e "$file" ] || exit {EXIT_UNSAFE}
n=$(wc -c < "$part") || exit {EXIT_UNSAFE}
[ $n -eq {size} ] || {{ rm -rf "$dir"; exit {EXIT_MISMATCH}; }}
mv "$part" "$file" || exit {EXIT_UNSAFE}
printf '@@staged %s\n' "$file"
"#
    )
}

fn discard_script(dir: &str) -> String {
    let dir = shell_quote(dir);
    format!(
        r#"exec 2>/dev/null
{ROOT}
dir={dir}
case "$dir" in "$root"/*) ;; *) exit {EXIT_UNSAFE} ;; esac
case "${{dir#"$root"/}}" in */*|'') exit {EXIT_UNSAFE} ;; esac
[ -d "$dir" ] && [ ! -L "$dir" ] && [ -O "$dir" ] || exit 0
rm -rf "$dir"
"#
    )
}

fn sh(script: &str) -> String {
    format!("/bin/sh -c {}", shell_quote(script))
}

/// What the upload command reported: its directory, then what it wrote.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Part {
    dir: Option<String>,
    written: Option<(u64, String)>,
}

fn parse_part(output: &Output) -> Part {
    let text = String::from_utf8_lossy(&output.tail);
    let mut part = Part {
        dir: None,
        written: None,
    };
    for line in text.lines() {
        if let Some(dir) = line.strip_prefix("@@dir ") {
            part.dir = valid_path(dir).then(|| dir.to_owned());
        } else if let Some(rest) = line.strip_prefix("@@part ") {
            let mut fields = rest.split(' ');
            let size = fields.next().and_then(|s| s.parse().ok());
            let sum = fields.next().filter(|s| is_sha256(s));
            part.written = size.zip(sum.map(str::to_owned));
        }
    }
    part
}

fn is_sha256(text: &str) -> bool {
    text.len() == 64 && text.bytes().all(|b| matches!(b, b'0'..=b'9' | b'a'..=b'f'))
}

fn refusal(exit: Option<u32>) -> Refusal {
    match exit {
        Some(EXIT_UNSAFE) => Refusal::StagingUnsafe,
        Some(EXIT_NO_TOOLS) => Refusal::NoTools,
        Some(EXIT_MISMATCH) => Refusal::Mismatch,
        _ => Refusal::Failed,
    }
}

/// What the phone sent against what the host wrote.
fn verdict(
    limit: u64,
    sent: u64,
    phone_sha: &str,
    written: &(u64, String),
) -> std::result::Result<(), Refusal> {
    if written.0 > limit || sent > limit {
        return Err(Refusal::TooLarge);
    }
    if written.0 != sent || !is_sha256(phone_sha) || written.1 != phone_sha {
        return Err(Refusal::Mismatch);
    }
    Ok(())
}

/// One upload in flight.
#[derive(Debug)]
struct Staging {
    begin: Begin,
    exec: Exec,
    writer: Mutex<Option<Writer>>,
    upload: Mutex<Option<Upload>>,
    sent: AtomicU64,
}

static UPLOADS: LazyLock<Mutex<HashMap<i64, Arc<Staging>>>> = LazyLock::new(Mutex::default);
static NEXT_UPLOAD: AtomicI64 = AtomicI64::new(1);

fn uploads() -> std::sync::MutexGuard<'static, HashMap<i64, Arc<Staging>>> {
    UPLOADS.lock().unwrap_or_else(PoisonError::into_inner)
}

fn refused(reason: Refusal) -> String {
    serde_json::json!({"outcome": "refused", "reason": reason}).to_string()
}

/// Start staging `begin` on `exec`; the caller has checked it names this
/// link's session and daemon run. Answers `open` with an upload id, or a
/// refusal.
pub fn start(exec: Exec, begin: Begin) -> Result<String> {
    if begin.instance.is_none() {
        return Ok(refused(Refusal::NeedsAgent));
    }
    if !valid_name(&begin.name, begin.kind) {
        return Ok(refused(Refusal::BadName));
    }
    if begin.size.is_some_and(|size| size > begin.limit()) {
        return Ok(refused(Refusal::TooLarge));
    }
    let upload = exec.upload(sh(&begin_script(&begin.name, begin.limit())), IDLE, OUTPUT)?;
    let staging = Staging {
        writer: Mutex::new(upload.writer()),
        upload: Mutex::new(Some(upload)),
        begin,
        exec,
        sent: AtomicU64::new(0),
    };
    let id = NEXT_UPLOAD.fetch_add(1, Ordering::Relaxed);
    uploads().insert(id, Arc::new(staging));
    Ok(serde_json::json!({"outcome": "open", "upload": id}).to_string())
}

/// Stream `chunk` to upload `id`. Fails once the upload ended, was
/// cancelled, or ran past its limit; blocks while the host is slower.
pub fn write(id: i64, chunk: Vec<u8>) -> Result<()> {
    let staging = uploads().get(&id).cloned().ok_or(crate::Error::SshClosed)?;
    let length = u64::try_from(chunk.len()).unwrap_or(u64::MAX);
    let sent = staging
        .sent
        .fetch_add(length, Ordering::AcqRel)
        .saturating_add(length);
    if sent > staging.begin.limit() {
        cancel(id);
        return Err(crate::Error::SshClosed);
    }
    let writer = lock(&staging.writer)
        .clone()
        .ok_or(crate::Error::SshClosed)?;
    writer.write(chunk)
}

/// End upload `id`: close its stdin, check what the host wrote against
/// `sha256`, and name the file. Answers `staged` with the attachment, or a
/// refusal that left nothing on the host. Blocks.
pub fn finish(id: i64, sha256: &str) -> Result<String> {
    let Some(staging) = uploads().remove(&id) else {
        return Ok(refused(Refusal::Cancelled));
    };
    lock(&staging.writer).take();
    let Some(upload) = lock(&staging.upload).take() else {
        return Ok(refused(Refusal::Cancelled));
    };
    let output = upload.finish()?;
    let part = parse_part(&output);
    let begin = &staging.begin;
    let written = match (&part.dir, &part.written, output.exit_status) {
        (Some(_), Some(written), Some(0)) => written.clone(),
        (dir, _, exit) => {
            if let Some(dir) = dir {
                discard_dir(&staging.exec, dir);
            }
            return Ok(refused(refusal(exit)));
        }
    };
    let Some(dir) = part.dir else {
        return Ok(refused(Refusal::Failed));
    };
    let sent = staging.sent.load(Ordering::Acquire);
    if let Err(reason) = verdict(begin.limit(), sent, sha256, &written) {
        discard_dir(&staging.exec, &dir);
        return Ok(refused(reason));
    }
    let committed = staging.exec.run(
        sh(&commit_script(&dir, &begin.name, written.0)),
        SHORT,
        OUTPUT,
    )?;
    let path = String::from_utf8_lossy(&committed.tail)
        .lines()
        .find_map(|line| line.strip_prefix("@@staged ").map(str::to_owned));
    let path = match (committed.exit_status, path) {
        (Some(0), Some(path)) if staged_in(&path, &dir, &begin.name) => path,
        (exit, _) => {
            discard_dir(&staging.exec, &dir);
            return Ok(refused(refusal(exit)));
        }
    };
    let attachment = Attachment {
        kind: begin.kind,
        name: begin.name.clone(),
        path,
        size: written.0,
        sha256: written.1,
        node: begin.node.clone(),
        session: begin.session.clone(),
        boot: begin.boot.clone(),
        pane: begin.pane.clone(),
        instance: begin.instance.clone(),
    };
    Ok(serde_json::json!({"outcome": "staged", "attachment": attachment}).to_string())
}

/// Abandon upload `id` without waiting: stdin closes, and once the host
/// has answered, its directory is removed. Unknown ids are ignored.
pub fn cancel(id: i64) {
    let Some(staging) = uploads().remove(&id) else {
        return;
    };
    lock(&staging.writer).take();
    let Some(upload) = lock(&staging.upload).take() else {
        return;
    };
    let spawned = thread::Builder::new()
        .name("herdline-upload-cancel".into())
        .spawn(move || match upload.finish() {
            Ok(output) => {
                if let Some(dir) = parse_part(&output).dir {
                    discard_dir(&staging.exec, &dir);
                }
            }
            Err(error) => tracing::info!(%error, "cancelled upload ended without an answer"),
        });
    if let Err(error) = spawned {
        tracing::warn!(%error, "cancelled upload left for the next sweep");
    }
}

/// Remove `attachment`'s directory on the host. Blocks.
pub fn discard(exec: &Exec, attachment: &Attachment) -> Result<()> {
    let Some((dir, name)) = attachment.path.rsplit_once('/') else {
        return Err(crate::Error::BadReply);
    };
    if !staged_in(&attachment.path, dir, name) {
        return Err(crate::Error::BadReply);
    }
    let output = exec.run(sh(&discard_script(dir)), SHORT, OUTPUT)?;
    match output.exit_status {
        Some(0) => Ok(()),
        _ => Err(crate::Error::BadReply),
    }
}

fn discard_dir(exec: &Exec, dir: &str) {
    match exec.run(sh(&discard_script(dir)), SHORT, OUTPUT) {
        Ok(output) if output.exit_status == Some(0) => {}
        Ok(output) => tracing::warn!(exit = ?output.exit_status, "staged upload not removed"),
        Err(error) => tracing::warn!(%error, "staged upload not removed"),
    }
}

/// Whether every attachment is still on the host as staged: a regular
/// file, not a symlink, of its size. Blocks for one short command.
pub fn present(exec: &Exec, attachments: &[Attachment]) -> Result<bool> {
    if attachments.is_empty() {
        return Ok(true);
    }
    let mut script = String::from("exec 2>/dev/null\n");
    for a in attachments {
        let path = shell_quote(&a.path);
        script.push_str(&format!(
            "[ -f {path} ] && [ ! -L {path} ] && n=$(wc -c < {path}) && [ $n -eq {} ] || exit 1\n",
            a.size
        ));
    }
    let output = exec.run(sh(&script), SHORT, OUTPUT)?;
    Ok(output.exit_status == Some(0))
}

fn lock<T>(mutex: &Mutex<T>) -> std::sync::MutexGuard<'_, T> {
    mutex.lock().unwrap_or_else(PoisonError::into_inner)
}
