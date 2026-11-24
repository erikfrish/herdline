//! The shell view of a herdr-app document as the phone sees it: the same
//! JSON the Herdr backend publishes (`crate::view`), plus per-pane `kind`,
//! `chat`, `unsupported` and `interactive`. Focus and zoom are this
//! phone's own; the document carries neither.

use serde::Serialize;

#[derive(Debug, Serialize)]
pub struct ShellView<'a> {
    pub revision: u64,
    pub boot: &'a str,
    pub focused_workspace: Option<&'a str>,
    pub focused_tab: Option<&'a str>,
    pub focused_pane: Option<&'a str>,
    pub update_available: Option<&'a str>,
    pub workspaces: Vec<Workspace<'a>>,
    pub tabs: Vec<Tab<'a>>,
    pub panes: Vec<Pane<'a>>,
    pub agents: Vec<Agent<'a>>,
}

#[derive(Debug, Serialize)]
pub struct Workspace<'a> {
    pub id: &'a str,
    pub number: usize,
    pub label: &'a str,
    pub branch: Option<&'a str>,
    pub ahead: Option<usize>,
    pub behind: Option<usize>,
    pub worktree: Option<&'a str>,
    pub linked_worktree: bool,
    pub active_tab: &'a str,
    pub focused: bool,
    pub status: &'static str,
}

#[derive(Debug, Serialize)]
pub struct Tab<'a> {
    pub id: &'a str,
    pub workspace: &'a str,
    pub number: usize,
    pub label: &'a str,
    pub zoomed: bool,
    pub focused: bool,
    pub status: &'static str,
}

#[derive(Debug, Serialize)]
pub struct Pane<'a> {
    pub id: &'a str,
    pub workspace: &'a str,
    pub tab: &'a str,
    pub label: Option<&'a str>,
    pub cwd: Option<&'a str>,
    pub focused: bool,
    /// `terminal`, or `mod` for content the phone cannot draw.
    pub kind: &'static str,
    /// `null` when Chat may read and send to the pane's agent, else why not.
    pub chat: Option<&'static str>,
    /// Why the pane has no running terminal ([`unsupported`]); `null` for one.
    pub unsupported: Option<&'static str>,
    /// The phone holds an interactive stream: it owns the terminal's size.
    pub interactive: bool,
}

#[derive(Debug, Serialize)]
pub struct Agent<'a> {
    pub pane: &'a str,
    pub workspace: &'a str,
    pub tab: &'a str,
    pub name: Option<&'a str>,
    pub agent: Option<&'a str>,
    pub title: Option<&'a str>,
    pub status: &'static str,
    pub state_seq: u64,
    pub focused: bool,
}

/// Why a pane of `kind` whose terminal `record` the pump holds has no
/// running terminal, or `None` when its program runs. A record missing
/// is `terminal_lost`. A `finished` record ended for good. A `live` one
/// with no pid waits for the core to start or restart its kept program.
pub fn unsupported(kind: &str, record: Option<&serde_json::Value>) -> Option<&'static str> {
    if kind == "mod" {
        return Some("mod_panel");
    }
    let Some(record) = record else {
        return Some("terminal_lost");
    };
    if record["state"] != "live" {
        Some("terminal_finished")
    } else if record["pid"].as_u64().is_none() {
        Some("terminal_starting")
    } else {
        None
    }
}

/// The core's OSC 7501 root state in the view's words; `error` has no word
/// there and reads as unknown, never as idle.
pub fn status(state: Option<&str>) -> &'static str {
    match state {
        Some("idle") => "idle",
        Some("working") => "working",
        Some("blocked") => "blocked",
        Some("done") => "done",
        _ => "unknown",
    }
}

/// The status a workspace or tab shows for its panes: the most urgent.
pub fn aggregate<'a>(statuses: impl IntoIterator<Item = &'a str>) -> &'static str {
    let rank = |status: &str| match status {
        "blocked" => 4,
        "working" => 3,
        "done" => 2,
        "idle" => 1,
        _ => 0,
    };
    let best = statuses.into_iter().max_by_key(|s| rank(s)).unwrap_or("unknown");
    status(Some(best))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
