//! Which agent instance runs in a core terminal, from the labels the opt-in
//! Herdline hook sets there (`team/handoff/herdr-app-identity-labels.md`):
//! `herdline.agent`, `herdline.session_file`, `herdline.session_id`,
//! `herdline.pid`. The identity is never read from argv, the title or the
//! cwd; argv only picks the session roots the host confines the file to.
//!
//! Labels are a claim, never enough on their own: the host then confines
//! the session file to the agent's configured roots and checks its header
//! carries the labelled session id ([`verify_command`]).
//!
//! The core samples a terminal's foreground about twice a second, so a fresh
//! terminal may list no foreground yet: the claim is recomputed on every
//! `labels`, `foreground`, `lost`, `finished`, `restarted` and `missed`, and
//! an early miss is never final. A wrapper that does not exec its agent keeps
//! another foreground pid and so stays unknown.

use crate::transcript::{self, Identity};
use herdr_client::shell_quote;
use serde_json::Value;

/// Why a terminal names no agent instance. The code is the one reason the
/// UI shows: `identity_unknown`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Unknown {
    /// Not live, or no foreground process sampled yet.
    NotLive,
    /// No hook labels, or labels of an agent this app does not read.
    NoLabels,
    /// The labelled pid is not the terminal's foreground process.
    OtherProcess,
    /// The labels do not have the frozen shape.
    Malformed,
}

fn label<'a>(record: &'a Value, key: &str) -> Option<&'a str> {
    record
        .get("labels")
        .and_then(|labels| labels.get(key))
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty())
}

/// A decimal pid: no sign, no leading zero, above 0, within u32.
fn pid(text: &str) -> Option<u32> {
    if text.starts_with('0') || !text.bytes().all(|b| b.is_ascii_digit()) {
        return None;
    }
    text.parse().ok().filter(|pid| *pid > 0)
}

/// The instance the terminal `record` (a core `Terminal`) names, shown in
/// block `pane`, or why it names none. The host still has to confirm it
/// before Chat uses it.
pub fn of(record: &Value, pane: &str) -> Result<Identity, Unknown> {
    let address = record.get("address").and_then(Value::as_str).unwrap_or_default();
    let live = record.get("state").and_then(Value::as_str) == Some("live");
    let foreground = record
        .get("foreground")
        .and_then(|foreground| foreground.get("pid"))
        .and_then(Value::as_u64)
        .and_then(|pid| u32::try_from(pid).ok());
    let agent = label(record, "herdline.agent");
    let file = label(record, "herdline.session_file");
    let id = label(record, "herdline.session_id");
    let labelled = label(record, "herdline.pid");
    let (Some(agent), Some(file), Some(id), Some(labelled)) = (agent, file, id, labelled) else {
        return Err(Unknown::NoLabels);
    };
    if !matches!(agent, "omp" | "pi") {
        return Err(Unknown::NoLabels);
    }
    if !transcript::confined(agent, file) || !transcript::session_id(id) {
        return Err(Unknown::Malformed);
    }
    let labelled = pid(labelled).ok_or(Unknown::Malformed)?;
    let Some(foreground) = foreground.filter(|_| live && !address.is_empty()) else {
        return Err(Unknown::NotLive);
    };
    if foreground != labelled {
        return Err(Unknown::OtherProcess);
    }
    Ok(Identity {
        pane_id: pane.to_owned(),
        terminal_id: address.to_owned(),
        pid: Some(labelled),
        path: file.to_owned(),
        agent: agent.to_owned(),
        source: format!("herdline-core:{agent}"),
        kind: "path".to_owned(),
        start: None,
    })
}

/// What a terminal's labels claim, to be confirmed on the host before Chat
/// uses it: labels alone never suffice.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Claim {
    pub identity: Identity,
    /// The id the session file's header must carry.
    pub session_id: String,
    /// The foreground process's argv as the core reports it, which picks
    /// OMP's profile and so its session roots.
    pub argv: Vec<String>,
}

/// The claim the terminal `record` makes for block `pane`.
pub fn claim(record: &Value, pane: &str) -> Result<Claim, Unknown> {
    let identity = of(record, pane)?;
    let session_id = label(record, "herdline.session_id")
        .ok_or(Unknown::NoLabels)?
        .to_owned();
    let argv = record
        .get("foreground")
        .and_then(|foreground| foreground.get("argv"))
        .and_then(Value::as_array)
        .map(|argv| {
            argv.iter()
                .filter_map(Value::as_str)
                .map(str::to_owned)
                .collect()
        })
        .unwrap_or_default();
    Ok(Claim {
        identity,
        session_id,
        argv,
    })
}

/// What the host found for a claim.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Verdict {
    /// Inside the agent's session roots, with a header naming the session.
    Confirmed,
    /// OMP has not written the file, or its header, yet.
    Pending,
    /// The header names another session.
    Mismatch,
    /// Outside the agent's roots, missing, or unreadable.
    Unconfined,
    /// A root or the file lies outside the user's home.
    OutsideHome,
}

/// How long the host check may take.
pub const VERIFY_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(20);
/// Output kept from the check: one canonical path.
pub const VERIFY_LIMIT: usize = 8 * 1024;

/// The host command that confines `claim`'s session file to the agent's
/// configured roots and checks its header id, with the same resolver the
/// Herdr route uses.
pub fn verify_command(claim: &Claim) -> String {
    let argv = serde_json::to_string(&claim.argv).unwrap_or_else(|_| "[]".to_owned());
    let script = format!(
        "exec 2>/dev/null\nhome=$(realpath -- \"$HOME\") || exit 3\npython3 - --core-verify {agent} {path} {id} {argv} \"$home\" <<'HERDLINE_RESOLVE'\n{resolver}\nHERDLINE_RESOLVE\n",
        agent = shell_quote(&claim.identity.agent),
        path = shell_quote(&claim.identity.path),
        id = shell_quote(&claim.session_id),
        argv = shell_quote(&argv),
        resolver = transcript::RESOLVER,
    );
    format!("/bin/sh -c {}", shell_quote(&script))
}

/// The verdict a [`verify_command`] run reports.
pub fn verdict(exit_status: Option<u32>) -> Verdict {
    match exit_status {
        Some(0) => Verdict::Confirmed,
        Some(12) => Verdict::Pending,
        Some(13) => Verdict::Mismatch,
        Some(11) => Verdict::OutsideHome,
        _ => Verdict::Unconfined,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
