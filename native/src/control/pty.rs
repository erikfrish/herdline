//! X1: Stop a working agent through its own terminal, with one key.
//!
//! No blind Ctrl-C or Esc. A key goes only where a pinned source names it
//! as that runtime's cancel while it works, or where the runtime's own
//! frame shows its hint for it right now (Herdr's detection manifests name
//! those hints). The frame is the one on the surface at the tap; the
//! instance is fenced by the caller before and checked again after.
//!
//! The key is pressed at most once. It is never a receipt: Stop counts as
//! done only when Herdr's status leaves working **and** the same session's
//! history records the stop (an assistant message ended `aborted`). A
//! runtime whose history this core cannot read for a stop is answered as
//! unconfirmed, never as stopped.

use crate::composer::{Editor, Registry, Surface, claude_blocker, claude_editor, omp_editor, pane_rows, pi_editor};
use crate::transcript::Entry;
use herdr_client::protocol::AgentStatus;
use serde::Serialize;

/// How far up from the pane's last drawn row a hint may stand: Herdr's
/// claude working rule reads `bottom_non_empty_lines(12)`.
const HINT_ROWS: usize = 12;

/// The one key a stop presses.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Key {
    Esc,
}

/// Why no key is pressed. The codes are stable: Kotlin names them.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Refusal {
    BootChanged,
    PaneMissing,
    /// Herdr names another program in the pane than the instance's.
    AgentChanged,
    PopupOpen,
    /// Only a working agent is stopped; idle, blocked or done is not.
    NotWorking,
    /// The pane is not on the surface: its frame cannot be read at the tap.
    NotVisible,
    /// The pane is scrolled back: the frame is not the live bottom.
    Scrolled,
    /// No pinned key for this runtime, and its frame shows no stop hint.
    NoStopKey,
    /// The runtime's own box is not on screen as it is while it works (a
    /// dialog, list or panel takes the key first).
    ScreenNotStoppable,
    /// A delivery or steer to this pane is unsettled.
    Unsettled,
}

#[derive(Clone, Copy)]
enum Rule {
    /// Esc while it works, pinned in its source; its own empty box must be
    /// on screen, so no panel, dialog or typed text takes the key first.
    Pinned(fn(&[String]) -> bool),
    /// Esc only while its frame shows the runtime's own working hint.
    Hint(fn(&[String]) -> bool),
}

fn rule(agent: &str) -> Option<Rule> {
    match agent {
        // omp 18.18.8 input-controller.ts:526-640 `editor.onEscape`: panels,
        // maintenance, loop and a focused subagent take Esc first; only then
        // does it abort the turn, ended `stopReason: "aborted"`.
        "omp" => Some(Rule::Pinned(|rows| omp_editor(rows) == Ok(Editor::Empty))),
        // pi interactive-mode.js:2523-2526: Esc while streaming aborts.
        "pi" => Some(Rule::Pinned(|rows| pi_editor(rows) == Ok(Editor::Empty))),
        // Herdr manifests/claude.toml:20-23 `live_turn_working`: its mode
        // row directly under Claude's own empty prompt box, which a dialog
        // replaces, and no other row a dialog draws.
        "claude" => Some(Rule::Hint(|rows| {
            footer_rows(rows).any(|at| {
                claude_hint(&rows[at])
                    && claude_editor(&rows[..at]) == Ok(Editor::Empty)
                    && !rows[at + 1..].iter().any(|row| claude_blocker(row))
            })
        })),
        // Herdr manifests/codex.toml:112-115: the hint names the key, which
        // the user may remap; only the `(… • esc to interrupt)` timer, as
        // codex draws it while it works, presses Esc.
        "codex" => Some(Rule::Hint(|rows| footer(rows).any(|row| codex_hint(row)))),
        // kimi pins Esc (editor-keyboard.ts:273-290), but this core reads no
        // kimi screen: a btw panel or a compaction would take it unseen.
        _ => None,
    }
}

fn claude_hint(row: &str) -> bool {
    let row = row.trim_start();
    (row.starts_with('⏸') || row.starts_with('⏵'))
        && row
            .match_indices("esc to interrupt")
            .any(|(at, hint)| row[at + hint.len()..].chars().next().is_none_or(|c| c.is_whitespace() || c == '·'))
}

fn codex_hint(row: &str) -> bool {
    let Some(end) = row.find(" • esc to interrupt)") else {
        return false;
    };
    // `(1m 2s • …`: a live timer ending in seconds opens the hint.
    let Some(open) = row[..end].rfind('(') else {
        return false;
    };
    let timer = &row[open + 1..end];
    timer.ends_with('s')
        && timer
            .split(' ')
            .all(|part| part.len() > 1 && part[..part.len() - 1].bytes().all(|b| b.is_ascii_digit()) && part.ends_with(['h', 'm', 's']))
}

/// The key to press for `agent` in `pane_rows`, while Herdr reports
/// `status`; the caller has checked boot, pane, program, popup and scroll.
pub fn decide(agent: &str, status: Option<AgentStatus>, rows: &[String]) -> Result<Key, Refusal> {
    if status != Some(AgentStatus::Working) {
        return Err(Refusal::NotWorking);
    }
    match rule(agent).ok_or(Refusal::NoStopKey)? {
        Rule::Pinned(own_box) if own_box(rows) => Ok(Key::Esc),
        Rule::Pinned(_) => Err(Refusal::ScreenNotStoppable),
        Rule::Hint(shown) if shown(rows) => Ok(Key::Esc),
        Rule::Hint(_) => Err(Refusal::NoStopKey),
    }
}

/// The key a stop of `agent` in `pane` presses on the surface the phone
/// holds now, under the delivery locks. That surface is Herdr's shared view
/// (the tab the desktop shows), not the pane Chat reads: a pane on another
/// tab is not in it, and is `NotVisible` rather than pressed blind.
pub fn admit(registry: &Registry, surface: &Surface<'_>, boot: &str, pane: &str, agent: &str) -> Result<Key, Refusal> {
    if registry.boot.as_deref() != Some(boot) {
        return Err(Refusal::BootChanged);
    }
    let facts = registry.panes.get(pane).ok_or(Refusal::PaneMissing)?;
    if !facts.agent.as_deref().is_some_and(|now| now.eq_ignore_ascii_case(agent)) {
        return Err(Refusal::AgentChanged);
    }
    if surface.popup_open {
        return Err(Refusal::PopupOpen);
    }
    let rows = surface.frame.and_then(|frame| pane_rows(frame, pane)).ok_or(Refusal::NotVisible)?;
    if surface.scroll.and_then(|scroll| scroll.get(pane)).is_some_and(|scroll| scroll.scrolled) {
        return Err(Refusal::Scrolled);
    }
    decide(agent, facts.status, &rows)
}

/// The pane's last [`HINT_ROWS`] drawn rows.
fn footer(rows: &[String]) -> impl Iterator<Item = &String> {
    footer_rows(rows).map(|at| &rows[at])
}

/// Where in `rows` the last [`HINT_ROWS`] drawn rows stand.
fn footer_rows(rows: &[String]) -> impl Iterator<Item = usize> + '_ {
    (0..rows.len()).rev().filter(|&at| !rows[at].trim().is_empty()).take(HINT_ROWS)
}

/// Whether this core reads `agent`'s history for a stop at all.
pub fn confirms(agent: &str) -> bool {
    matches!(agent, "omp" | "pi")
}

/// Whether `entries`, appended after the tap, record a stopped turn.
pub fn shows_stop(entries: &[Entry]) -> bool {
    entries
        .iter()
        .any(|entry| entry.role.as_deref() == Some("assistant") && entry.stop_reason.as_deref() == Some("aborted"))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
