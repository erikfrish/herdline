//! Where a finger scroll goes.
//!
//! Herdr routes a pane wheel by the program's terminal modes (Herdr
//! `pane/terminal.rs` `wheel_routing`): to the program when it reads the
//! mouse, as Up/Down arrow keys when it is on the alternate screen with
//! alternate scroll on (DECSET 1007), and to Herdr's scrollback otherwise.
//! The arrows recall sent prompts in an agent's input box, and OMP borrows
//! the alternate screen without the mouse for a moment after every resize,
//! which a phone causes often. So a finger scroll sends a wheel only to a
//! program that asked for mouse reports. Anything else moves Herdr's
//! scrollback through `pane.scroll`, as Herdr's own scrollbar does, and
//! never reaches the program; on the alternate screen there is no
//! scrollback, so it does nothing.

use herdr_client::protocol::PaneSurfaceScrollMetrics;
use std::collections::{HashMap, HashSet};

/// How a pane takes a finger scroll, from Herdr's latest surface.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct Pane {
    /// The program asked for mouse reports: the wheel is its own.
    pub mouse: bool,
    /// Herdr's scrollback position, when it reported one.
    pub scroll: Option<PaneSurfaceScrollMetrics>,
}

/// What one scroll step does.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Step {
    /// Send the wheel to the program.
    Wheel,
    /// Ask Herdr to show the scrollback this many rows above the live bottom.
    Request(u64),
    /// A request is on the wire; the newest offset goes when it is answered.
    Wait,
    /// Nothing moves: no scrollback in that direction, or the pane is unknown.
    Nothing,
}

/// What an answer meant to the scrolls.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Answer {
    /// Not a scroll request: someone else waits for it.
    NotOurs,
    Done,
    /// The offset asked while the answered request was on the wire.
    Next {
        pane: String,
        offset: u64,
    },
}

/// Scroll state of every pane on the surface. One request per pane is on
/// the wire at a time, as in Herdr's own client.
#[derive(Debug, Default)]
pub struct Scrolls {
    panes: HashMap<String, Pane>,
    /// The offset last asked for, until a surface shows it.
    goals: HashMap<String, u64>,
    /// The request on the wire, by pane.
    in_flight: HashMap<String, String>,
    /// Panes whose goal is newer than their request on the wire.
    queued: HashSet<String>,
}

impl Scrolls {
    /// Take the panes of a new surface. A goal the surface shows is reached;
    /// a pane that left the surface leaves its goal with it.
    pub fn surface(&mut self, panes: impl IntoIterator<Item = (String, Pane)>) {
        self.panes = panes.into_iter().collect();
        let panes = &self.panes;
        self.goals.retain(|id, goal| {
            panes.get(id).is_some_and(|pane| {
                pane.scroll.map(|scroll| scroll.offset_from_bottom) != Some(*goal)
            })
        });
        let goals = &self.goals;
        self.queued.retain(|id| goals.contains_key(id));
    }

    /// One finger step of `lines` rows over `pane`; `up` reveals older output.
    pub fn step(&mut self, pane: &str, up: bool, lines: u16) -> Step {
        let Some(&Pane { mouse, scroll }) = self.panes.get(pane) else {
            return Step::Nothing;
        };
        if mouse {
            return Step::Wheel;
        }
        let Some(scroll) = scroll else {
            return Step::Nothing;
        };
        let base = self
            .goals
            .get(pane)
            .copied()
            .unwrap_or(scroll.offset_from_bottom);
        let lines = u64::from(lines.max(1));
        let offset = if up {
            base.saturating_add(lines)
                .min(scroll.max_offset_from_bottom)
        } else {
            base.saturating_sub(lines)
        };
        if offset == base {
            return Step::Nothing;
        }
        self.goals.insert(pane.to_owned(), offset);
        if self.in_flight.contains_key(pane) {
            self.queued.insert(pane.to_owned());
            Step::Wait
        } else {
            Step::Request(offset)
        }
    }

    /// The request for `pane`'s goal is on the wire as `id`.
    pub fn sent(&mut self, pane: &str, id: String) {
        self.in_flight.insert(pane.to_owned(), id);
    }

    /// The request for `pane`'s goal could not be queued.
    pub fn unsent(&mut self, pane: &str) {
        self.goals.remove(pane);
        self.queued.remove(pane);
    }

    /// Herdr answered request `id`; `ok` is false for a rejection.
    pub fn answered(&mut self, id: &str, ok: bool) -> Answer {
        let Some(pane) = self
            .in_flight
            .iter()
            .find_map(|(pane, sent)| (sent == id).then(|| pane.clone()))
        else {
            return Answer::NotOurs;
        };
        self.in_flight.remove(&pane);
        let queued = self.queued.remove(&pane);
        if !ok {
            self.goals.remove(&pane);
            return Answer::Done;
        }
        match self.goals.get(&pane) {
            Some(&offset) if queued => Answer::Next { pane, offset },
            _ => Answer::Done,
        }
    }
}

#[cfg(test)]
mod tests;
