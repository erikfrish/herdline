//! What the pump publishes: focus resolution, the composed frame, the shell
//! view, the composer registry, and agent identity claims.

use super::{
    doc,
    identity::{self, Claim, Verdict},
    layout::{self, Part, Placed},
    lock,
    pump::{Pump, Verified, state},
    view::{self, Agent, Pane, ShellView, Tab, Workspace},
    wire::{self, Kind},
};
use crate::{
    Result,
    composer::{PaneFacts, PaneScroll},
    render::{Rect, RenderFrame},
    screen, transcript,
    session::UiEvent,
};
use herdr_client::protocol::AgentStatus;
use serde_json::Value;
use std::{
    collections::{HashMap, HashSet},
    sync::atomic::Ordering,
    thread,
};

impl Pump {
    /// The focused space: this phone's choice while it exists, else the first.
    pub(super) fn space(&self) -> Option<&Value> {
        let spaces = doc::space_list(&self.doc);
        self.focus
            .space
            .as_deref()
            .and_then(|focused| spaces.iter().find(|space| doc::id(space) == focused))
            .or_else(|| spaces.first())
    }

    /// The active tab of `space`, the same way.
    pub(super) fn tab_in<'a>(&self, space: &'a Value) -> Option<&'a Value> {
        let tabs = doc::tabs(space);
        self.focus
            .tabs
            .get(doc::id(space))
            .and_then(|focused| tabs.iter().find(|tab| doc::id(tab) == focused))
            .or_else(|| tabs.first())
    }

    pub(super) fn tab(&self) -> Option<&Value> {
        self.space().and_then(|space| self.tab_in(space))
    }

    /// The panes `tab` shows on this phone's surface.
    pub(super) fn placed_in(&self, tab: &Value) -> Vec<Placed> {
        let Some(root) = tab.get("root") else {
            return Vec::new();
        };
        let zoomed = self.focus.zoomed.get(doc::id(tab)).map(String::as_str);
        let (cols, rows) = self.size;
        layout::place(
            root,
            Rect {
                x: 0,
                y: 0,
                width: cols,
                height: rows,
            },
            zoomed,
        )
    }

    pub(super) fn placed(&self) -> Vec<Placed> {
        self.tab().map(|tab| self.placed_in(tab)).unwrap_or_default()
    }

    /// The focused pane of `tab` among `placed`.
    fn pane_in(&self, tab: &Value, placed: &[Placed]) -> Option<String> {
        self.focus
            .panes
            .get(doc::id(tab))
            .filter(|focused| placed.iter().any(|placed| &placed.pane == *focused))
            .cloned()
            .or_else(|| placed.first().map(|placed| placed.pane.clone()))
    }

    pub(super) fn render(&mut self) -> Result<()> {
        let placed = self.placed();
        let focused = self
            .tab()
            .and_then(|tab| self.pane_in(tab, &placed));
        // An interactive terminal takes its pane's size.
        for part in &placed {
            let Some(terminal) = self.interactive.get(&part.pane) else {
                continue;
            };
            let size = (part.rect.width.max(1), part.rect.height.max(1));
            if let Some(term) = self.terms.get_mut(terminal)
                && !term.observe
                && term.sent != size
                && let Some(channel) = term.channel
            {
                term.sent = size;
                self.link
                    .frame(channel, Kind::Resize, &wire::size(size.0, size.1))?;
            }
        }
        let mut screens: HashMap<String, RenderFrame> = HashMap::new();
        let mut scroll: HashMap<String, PaneScroll> = HashMap::new();
        let mut looks = HashMap::new();
        for part in &placed {
            let Some(terminal) = &part.terminal else {
                continue;
            };
            let Some(term) = self.terms.get(terminal) else {
                continue;
            };
            match self.reader.read(&term.vt) {
                Ok(frame) => {
                    screens.insert(part.pane.clone(), frame);
                }
                Err(error) => tracing::warn!(%error, "terminal not read"),
            }
            let modes = term.tracker.modes();
            looks.insert(
                part.pane.clone(),
                (modes.mouse(), modes.alternate, term.offset > 0),
            );
            scroll.insert(
                part.pane.clone(),
                PaneScroll {
                    scrolled: term.offset > 0,
                    rows: u64::from(term.vt.rows().unwrap_or(0)),
                },
            );
        }
        let parts: Vec<Part<'_>> = placed
            .iter()
            .map(|placed| {
                let (mouse_reporting, alternate_screen, scrolled) =
                    looks.get(&placed.pane).copied().unwrap_or_default();
                Part {
                    pane: &placed.pane,
                    rect: placed.rect,
                    screen: screens.get(&placed.pane),
                    focused: focused.as_deref() == Some(placed.pane.as_str()),
                    mouse_reporting,
                    alternate_screen,
                    scrolled,
                }
            })
            .collect();
        let colors = (
            screen::argb(self.theme.foreground),
            screen::argb(self.theme.background),
            screen::argb(self.theme.cursor),
        );
        let frame = layout::compose(self.size.0, self.size.1, colors, &parts);
        lock(&self.shared.shown).publish(frame, scroll);
        if !self.shared.frame_fresh.swap(true, Ordering::AcqRel) {
            self.shared.emit(UiEvent::Frame);
        }
        Ok(())
    }

    fn confirmed(&self, terminal: &str) -> Option<&Claim> {
        matches!(self.claims.verdicts.get(terminal), Some(Verdict::Confirmed | Verdict::Pending))
            .then(|| self.claims.current.get(terminal).map(|(_, claim)| claim))
            .flatten()
    }

    /// The shell view and the composer registry, from the document, the
    /// records and this phone's focus.
    pub(super) fn publish_view(&mut self) {
        self.revision += 1;
        let focused_space = self.space().map(doc::id);
        let focused_tab = self.tab().map(doc::id);
        let placed = self.placed();
        let focused_pane = self.tab().and_then(|tab| self.pane_in(tab, &placed));
        let interactive: HashSet<&str> = self.interactive.keys().map(String::as_str).collect();
        let mut workspaces = Vec::new();
        let mut tabs = Vec::new();
        let mut panes = Vec::new();
        let mut agents = Vec::new();
        let mut hookless = HashMap::new();
        let mut registry = HashMap::new();
        for (s, space) in doc::space_list(&self.doc).iter().enumerate() {
            let space_id = doc::id(space);
            let active_tab = self.tab_in(space).map(doc::id).unwrap_or_default();
            let mut space_statuses = Vec::new();
            for (t, tab) in doc::tabs(space).iter().enumerate() {
                let tab_id = doc::id(tab);
                let mut tab_statuses = Vec::new();
                let tab_placed = if Some(tab_id) == focused_tab {
                    placed.clone()
                } else {
                    self.placed_in(tab)
                };
                let tab_focus = self.pane_in(tab, &tab_placed);
                let blocks = tab.get("root").map(doc::blocks).unwrap_or_default();
                for block in blocks {
                    let pane_id = doc::id(block);
                    let focused = Some(tab_id) == focused_tab
                        && focused_pane.as_deref() == Some(pane_id);
                    let (kind, terminal) = match doc::content(block) {
                        doc::Content::Terminal(terminal) => ("terminal", Some(terminal)),
                        doc::Content::Unsupported => ("mod", None),
                    };
                    let record = terminal.and_then(|terminal| self.records.get(terminal));
                    let claim = terminal.and_then(|terminal| self.confirmed(terminal));
                    let word = record.and_then(state);
                    let status = view::status(word);
                    if word.is_some() {
                        tab_statuses.push(status);
                    }
                    let unsupported = view::unsupported(kind, record);
                    let field = |key: &str| {
                        record
                            .and_then(|record| record[key].as_str())
                            .filter(|text| !text.is_empty())
                    };
                    let cwd = record.and_then(|record| {
                        record["foreground"]["cwd"]
                            .as_str()
                            .or_else(|| record["last_cwd"].as_str())
                            .or_else(|| record["cwd"].as_str())
                            .filter(|cwd| !cwd.is_empty())
                    });
                    panes.push(Pane {
                        id: pane_id,
                        workspace: space_id,
                        tab: tab_id,
                        label: field("name"),
                        cwd,
                        focused,
                        kind,
                        chat: if claim.is_some() {
                            None
                        } else if let Some(terminal) =
                            terminal.filter(|_| record.is_some_and(hookless_omp))
                        {
                            hookless.insert(pane_id.to_owned(), terminal.to_owned());
                            Some(HOOKLESS_OMP)
                        } else {
                            Some("identity_unknown")
                        },
                        unsupported,
                        interactive: interactive.contains(pane_id),
                    });
                    let agent_name = claim.map(|claim| claim.identity.agent.as_str()).or_else(|| {
                        record
                            .and_then(|record| record["status"]["app"].as_str())
                            .filter(|app| !app.is_empty())
                    });
                    if word.is_some() || claim.is_some() {
                        agents.push(Agent {
                            pane: pane_id,
                            workspace: space_id,
                            tab: tab_id,
                            name: field("name"),
                            agent: agent_name,
                            title: field("title"),
                            status,
                            state_seq: record
                                .and_then(|record| record["status_ms"].as_u64())
                                .unwrap_or(0),
                            focused,
                        });
                    }
                    registry.insert(
                        pane_id.to_owned(),
                        PaneFacts {
                            agent: claim.map(|claim| claim.identity.agent.clone()),
                            status: word.map(|word| match word {
                                "idle" => AgentStatus::Idle,
                                "working" => AgentStatus::Working,
                                "blocked" => AgentStatus::Blocked,
                                "done" => AgentStatus::Done,
                                _ => AgentStatus::Unknown,
                            }),
                        },
                    );
                }
                let tab_status = view::aggregate(tab_statuses.iter().copied());
                space_statuses.extend(tab_statuses);
                let name = doc::text(tab, "name");
                tabs.push(Tab {
                    id: tab_id,
                    workspace: space_id,
                    number: t + 1,
                    label: name,
                    zoomed: self.focus.zoomed.contains_key(tab_id)
                        && tab_focus.is_some(),
                    focused: Some(tab_id) == focused_tab,
                    status: tab_status,
                });
            }
            let name = doc::text(space, "name");
            let label = if name.is_empty() {
                let folder = doc::text(space, "folder").trim_end_matches('/');
                folder.rsplit('/').next().unwrap_or(folder)
            } else {
                name
            };
            workspaces.push(Workspace {
                id: space_id,
                number: s + 1,
                label,
                branch: None,
                ahead: None,
                behind: None,
                worktree: None,
                linked_worktree: false,
                active_tab,
                focused: Some(space_id) == focused_space,
                status: view::aggregate(space_statuses.iter().copied()),
            });
        }
        let shell = ShellView {
            revision: self.revision,
            boot: &self.boot,
            focused_workspace: focused_space,
            focused_tab,
            focused_pane: focused_pane.as_deref(),
            update_available: None,
            workspaces,
            tabs,
            panes,
            agents,
        };
        let encoded = serde_json::to_string(&shell);
        drop(shell);
        lock(&self.shared.registry).panes = registry;
        *lock(&self.shared.hookless) = hookless;
        match encoded {
            Ok(json) => {
                *lock(&self.shared.view) = Some(json);
                if !self.shared.view_fresh.swap(true, Ordering::AcqRel) {
                    self.shared.emit(UiEvent::Snapshot);
                }
            }
            Err(error) => tracing::warn!(%error, "shell view not encoded"),
        }
    }

    /// Recompute every terminal's claim.
    pub(super) fn reclaim_all(&mut self) {
        let terminals: HashSet<String> = self
            .claims
            .current
            .keys()
            .chain(self.pane_of.keys())
            .cloned()
            .collect();
        for terminal in terminals {
            self.reclaim(&terminal, false);
        }
    }

    /// Recompute `terminal`'s claim from its labels, foreground and pane. A
    /// new claim is checked on the host; with `reverify`, a pending one is
    /// checked again.
    pub(super) fn reclaim(&mut self, terminal: &str, reverify: bool) {
        let now = match (self.pane_of.get(terminal), self.records.get(terminal)) {
            (Some(pane), Some(record)) => identity::claim(record, pane)
                .ok()
                .map(|claim| (pane.clone(), claim)),
            _ => None,
        };
        let changed = self.claims.current.get(terminal) != now.as_ref();
        if changed {
            tracing::info!(terminal = %terminal, available = now.is_some(), "core identity claim changed");
            self.claims.verdicts.remove(terminal);
            self.unpublish(terminal);
            match now {
                Some(now) => {
                    self.claims.current.insert(terminal.to_owned(), now);
                    self.verify(terminal);
                }
                None => {
                    self.claims.current.remove(terminal);
                }
            }
            self.dirty_view = true;
        } else if reverify
            && self.claims.current.contains_key(terminal)
            && self.claims.verdicts.get(terminal) != Some(&Verdict::Confirmed)
        {
            self.verify(terminal);
        }
    }

    fn unpublish(&self, terminal: &str) {
        lock(&self.shared.instances).retain(|_, core| core.identity.terminal_id != terminal);
    }

    /// Check `terminal`'s current claim on the host, off this thread.
    fn verify(&mut self, terminal: &str) {
        if self.claims.inflight.contains(terminal) {
            self.claims.again.insert(terminal.to_owned());
            return;
        }
        let Some((_, claim)) = self.claims.current.get(terminal).cloned() else {
            return;
        };
        let exec = self.exec.clone();
        let to = self.verified.clone();
        let address = terminal.to_owned();
        let spawned = thread::Builder::new()
            .name("herdline-core-verify".into())
            .spawn(move || {
                let verdict = match exec.run(
                    identity::verify_command(&claim),
                    identity::VERIFY_TIMEOUT,
                    identity::VERIFY_LIMIT,
                ) {
                    Ok(output) => Some(identity::verdict(output.exit_status)),
                    Err(error) => {
                        tracing::warn!(%error, "agent identity not checked");
                        None
                    }
                };
                let _ = to.send((address, claim, verdict));
            });
        match spawned {
            Ok(_) => {
                self.claims.inflight.insert(terminal.to_owned());
            }
            Err(error) => tracing::warn!(%error, "identity check not started"),
        }
    }

    /// A host check came back; it counts only for the claim it checked.
    pub(super) fn verified(&mut self, (terminal, claim, verdict): Verified) {
        self.claims.inflight.remove(&terminal);
        let current = self
            .claims
            .current
            .get(&terminal)
            .filter(|(_, now)| *now == claim)
            .cloned();
        if let Some((pane, claim)) = current {
            tracing::info!(terminal = %terminal, ?verdict, "core identity verification completed");
            match verdict {
                Some(verdict) => {
                    self.claims.verdicts.insert(terminal.clone(), verdict);
                }
                None => {
                    self.claims.verdicts.remove(&terminal);
                }
            }
            self.unpublish(&terminal);
            if matches!(verdict, Some(Verdict::Confirmed | Verdict::Pending)) {
                let status = view::status(self.records.get(&terminal).and_then(state)).to_owned();
                lock(&self.shared.instances).insert(
                    pane,
                    transcript::Core {
                        identity: claim.identity,
                        session_id: claim.session_id,
                        argv: claim.argv,
                        status,
                        session: self.shared.session.clone(),
                        run: self.core_run.clone(),
                    },
                );
            }
            self.dirty_view = true;
        }
        if self.claims.again.remove(&terminal) {
            self.verify(&terminal);
        }
    }
}

/// The reason a pane's Chat is read only: its live terminal runs OMP in
/// the foreground without Herdline's hook labels. Whether that OMP is bound
/// to a session is proved on the host at each read, never here.
const HOOKLESS_OMP: &str = "hookless_omp";

fn hookless_omp(record: &Value) -> bool {
    record["state"] == "live"
        && record["foreground"]["name"] == "omp"
        && record["labels"]
            .as_object()
            .is_none_or(|labels| !labels.keys().any(|key| key.starts_with("herdline.")))
}
