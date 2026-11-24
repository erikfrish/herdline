//! Read-only Chat over a running OMP that has no Herdline hook: which
//! session the foreground OMP of a herdr-core terminal runs, proved from
//! that process alone on every read (`omp_probe.py` collects, this judges).
//!
//! Confirmed only when all hold: the record is live, unlabelled, and its
//! foreground `omp` is a child of the terminal's program (so not started by
//! a launcher, not nested) run by this user; OMP's default profile with
//! this shell's own HOME; its fd0 tty is the shell's; OMP's breadcrumb for
//! that tty names a written session (not `fresh`) inside the default
//! session folder whose header carries the file's id and the breadcrumb's
//! cwd; and THAT pid holds the session's lease in either known form, told by
//! evidence and never by version:
//!
//! - OMP 19.x: a WRITE lock on an fd naming
//!   `$HOME/.omp/run/session-owners/<id>.lock` (OFD or flock,
//!   `session/session-storage.ts:1347-1350` of 19.1.1);
//! - OMP 18.x: an abstract unix socket `@omp-file-lock-<lease_name>`.
//!
//! Lease evidence that cannot be read is a refusal, never a free session.
//! The process start time is part of the identity, so a reused pid is
//! another instance.

mod xxh64;

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;

use super::Identity;
use serde::Serialize;
use serde_json::Value;

/// The [`Identity::source`] of a binding this module confirmed.
pub const SOURCE: &str = "probe:omp";

/// The host collector, run in the same command as each read.
pub(super) const COLLECT: &str = include_str!("omp_probe.py");

/// The collector's exit when the breadcrumb names another file than the one
/// asked for: it printed its facts and the file is not read.
pub(super) const EXIT_OTHER_FILE: u32 = 14;

/// Profile variables that move OMP's folders away from the defaults
/// (`pi-utils/src/dirs.ts:333,401-437` of 19.1.1).
const PROFILE_ENV: [&str; 6] = [
    "OMP_PROFILE",
    "PI_PROFILE",
    "PI_CODING_AGENT_DIR",
    "PI_CONFIG_DIR",
    "XDG_STATE_HOME",
    "XDG_DATA_HOME",
];

/// Herdr's status words; anything else reads as `unknown`.
const STATES: [&str; 4] = ["idle", "working", "blocked", "done"];

/// Why a terminal's OMP is not bound to a session Chat may read. Each has
/// its own compact text in the app.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Unbound {
    /// No live OMP is the terminal's foreground process.
    NoOmp,
    /// OMP is the terminal's own program: started by a launcher, not a shell.
    LauncherStarted,
    /// Another profile, folder override or HOME than this shell's defaults.
    ProfileMismatch,
    /// OMP reads another tty than the terminal's shell.
    TtyMismatch,
    /// OMP has not written its session yet, or its breadcrumb names no
    /// confined session with a matching header.
    FreshOrBadBreadcrumb,
    /// Another process holds the session's lease, or none does.
    LeaseNotHeld,
    /// The lease evidence of the process could not be read.
    LeaseUnreadable,
    /// Hook labels, another parent, another user or an unreadable process:
    /// nothing proves which session it is.
    Ambiguous,
}

/// The name OMP 18.x gives the abstract socket that holds the lease on
/// session `id` under `home` (pi-natives' `lease_name`).
pub fn lease_name(home: &str, id: &str) -> String {
    let path = format!("{home}/.omp/run/session-owners/{id}.lock");
    let high = xxh64::hash(path.as_bytes(), 0x4f4d_502d_4c4f_434b);
    let low = xxh64::hash(path.as_bytes(), 0x5049_2d46_494c_454c);
    format!("{high:016x}{low:016x}")
}

/// A session id as OMP 19.x names its files: a lower-case UUID.
fn uuid(id: &str) -> bool {
    let bytes = id.as_bytes();
    bytes.len() == 36
        && bytes.iter().enumerate().all(|(at, byte)| match at {
            8 | 13 | 18 | 23 => *byte == b'-',
            _ => byte.is_ascii_digit() || (b'a'..=b'f').contains(byte),
        })
}

/// The session id a confirmed binding's file name ends with.
pub(super) fn session_of(path: &str) -> Option<&str> {
    let name = path.rsplit('/').next()?.strip_suffix(".jsonl")?;
    let (_, id) = name.rsplit_once('_')?;
    uuid(id).then_some(id)
}

fn positive(value: &Value) -> Option<u32> {
    value
        .as_u64()
        .and_then(|pid| u32::try_from(pid).ok())
        .filter(|pid| *pid > 0)
}

fn strings(value: &Value) -> Option<Vec<&str>> {
    value.as_array()?.iter().map(Value::as_str).collect()
}

/// The instance [`COLLECT`]'s `facts` prove runs in terminal `address`,
/// shown in block `pane`, and the terminal's status word; or why none is.
pub fn bind(facts: &Value, pane: &str, address: &str) -> Result<(Identity, String), Unbound> {
    use Unbound::*;
    let record = &facts["record"];
    if record["address"].as_str() != Some(address) || record["state"] != "live" {
        return Err(NoOmp);
    }
    let labels = strings(&record["labels"]).ok_or(Ambiguous)?;
    if labels.iter().any(|label| label.starts_with("herdline.")) {
        return Err(Ambiguous);
    }
    let foreground = &record["foreground"];
    if foreground["name"] != "omp" {
        return Err(NoOmp);
    }
    let (Some(pid), Some(program)) = (positive(&foreground["pid"]), positive(&record["pid"]))
    else {
        return Err(NoOmp);
    };
    if pid == program {
        return Err(LauncherStarted);
    }
    let process = &facts["proc"];
    if process["gone"] == true {
        return Err(NoOmp);
    }
    if positive(&process["pid"]) != Some(pid)
        || positive(&process["ppid"]) != Some(program)
        || process["own"] != true
    {
        return Err(Ambiguous);
    }
    let start = process["start"].as_u64().ok_or(Ambiguous)?;
    let argv = strings(&process["argv"]).ok_or(Ambiguous)?;
    let env = process["env"].as_object().ok_or(Ambiguous)?;
    if PROFILE_ENV.iter().any(|key| env.contains_key(*key))
        || argv.iter().any(|arg| {
            ["--profile", "--alias"].iter().any(|flag| {
                *arg == *flag
                    || arg
                        .strip_prefix(flag)
                        .is_some_and(|rest| rest.starts_with('='))
            })
        })
    {
        return Err(ProfileMismatch);
    }
    let home = env
        .get("HOME")
        .and_then(Value::as_str)
        .ok_or(ProfileMismatch)?;
    if !home.starts_with('/')
        || process["home_real"].as_str() != Some(home)
        || facts["home"].as_str() != Some(home)
    {
        return Err(ProfileMismatch);
    }
    let tty = process["tty"].as_str().ok_or(TtyMismatch)?;
    if !tty.starts_with("/dev/pts/") || process["shell_tty"].as_str() != Some(tty) {
        return Err(TtyMismatch);
    }
    let crumb = process["crumb"].as_str().ok_or(FreshOrBadBreadcrumb)?;
    let lines: Vec<&str> = crumb.trim_end_matches('\n').split('\n').collect();
    let [cwd, file, flags @ ..] = lines.as_slice() else {
        return Err(FreshOrBadBreadcrumb);
    };
    if flags.contains(&"fresh")
        || !file.starts_with(&format!("{home}/.omp/agent/sessions/"))
        || process["file_real"].as_str() != Some(file)
        || !super::confined("omp", file)
    {
        return Err(FreshOrBadBreadcrumb);
    }
    let id = session_of(file).ok_or(FreshOrBadBreadcrumb)?;
    let header = &process["header"];
    if header["id"].as_str() != Some(id) || header["cwd"].as_str() != Some(cwd) {
        return Err(FreshOrBadBreadcrumb);
    }
    let unreadable = process["unreadable"].as_array().ok_or(LeaseUnreadable)?;
    if !unreadable.is_empty() {
        return Err(LeaseUnreadable);
    }
    let locks = strings(&process["locks"]).ok_or(LeaseUnreadable)?;
    let names = strings(&process["names"]).ok_or(LeaseUnreadable)?;
    if !locks.contains(&id) && !names.contains(&lease_name(home, id).as_str()) {
        return Err(LeaseNotHeld);
    }
    let status = record["status"]
        .as_str()
        .filter(|status| STATES.contains(status))
        .unwrap_or("unknown");
    Ok((
        Identity {
            pane_id: pane.to_owned(),
            terminal_id: address.to_owned(),
            pid: Some(pid),
            path: (*file).to_owned(),
            agent: "omp".to_owned(),
            source: SOURCE.to_owned(),
            kind: "path".to_owned(),
            start: Some(start),
        },
        status.to_owned(),
    ))
}
