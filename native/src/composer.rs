//! Safe delivery of text written in the app's own editor to one Herdr pane.
//!
//! The composer never types: text reaches the pane as one bracketed paste, so
//! newlines stay inside the paste instead of becoming Enter presses. Each
//! delivery names its exact target (daemon boot, pane, and the program seen
//! there) and is refused when any of them no longer holds. Sending, a paste
//! followed by Enter, is offered only where the result was verified on the
//! program itself: OMP at rest with an empty input box on screen. Steering,
//! the same paste and Enter while OMP works, is a separate action in two
//! steps (see [`crate::steer`]). Everything else fails closed and leaves
//! the text with the user.

use crate::render::RenderFrame;
use crate::transcript::Identity;
use crate::visible::{self, Bind, Grid, Viewport};
use crate::{attach, hold};
use herdr_client::protocol::{AgentStatus, ClientShellSnapshot};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Largest text one delivery carries. Far below the frame limit, and more
/// than any prompt a phone keyboard produces.
pub const MAX_TEXT_BYTES: usize = 64 * 1024;

/// Why a delivery is not allowed. The codes are stable: Kotlin maps them to
/// the words the user reads.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Refusal {
    NotConnected,
    BootChanged,
    PaneMissing,
    AgentChanged,
    PopupOpen,
    AgentBlocked,
    AgentWorking,
    UnsupportedProgram,
    NotVisible,
    Scrolled,
    /// The pane is too short for OMP to show more than the cursor line of its
    /// box, so a remote draft could hide above it.
    TooShort,
    RemoteDraft,
    /// OMP's box could not be read, for a cause not measured below.
    EditorUnrecognized,
    /// Rows are drawn under OMP's whole box: a list or menu.
    EditorListBelow,
    /// OMP's box reaches the bottom, but its top border is not on screen.
    EditorTopMissing,
    /// Something is drawn across OMP's box, between its top and bottom.
    EditorCovered,
    /// The pane is blank or being resized.
    EditorResizing,
    /// This client's grid is drawn, yet Herdr lays the pane out for another
    /// client's size: the phone shows a crop of it, and OMP's box can be off
    /// that crop. It stays so until a client takes the pane's size.
    PaneSizedElsewhere,
    /// No OMP box anywhere in the pane.
    EditorNone,
    /// A whole OMP dialog (an ask, selector, input or confirm) is open above
    /// or instead of the box: text goes to it, and Enter would answer it.
    EditorDialogOpen,
    EmptyText,
    TooLarge,
    NoReceipt,
    /// The last delivery to this target was never confirmed; only the user
    /// may release it.
    Unsettled,
    /// Another agent process or session runs in the pane than the one the
    /// draft was written for.
    AgentRestarted,
    /// The draft names an agent instance, and the one in the pane now could
    /// not be read.
    InstanceUnknown,
    /// Steer is for a working agent; an idle one takes an ordinary Send.
    AgentIdle,
    /// The steer's paste was queued on another link or daemon run, or was
    /// already settled: its Enter is never sent.
    SteerExpired,
    /// OMP's input box does not yet show the steer's text: no Enter.
    SteerNotShown,
    /// The input was queued, but the request that would confirm it could not
    /// be: it may have reached the pane.
    Unconfirmed,
    /// OMP's box, its borders or its cursor row are not wholly drawn on the
    /// phone, or what the phone drew is not this target's frame.
    EditorHidden,
    /// Not OMP: the cursor row is not drawn inside the target on the phone,
    /// or the cursor is unknown.
    TargetNotVisible,
    /// A resize this client asked for is not drawn yet, or the frame changed
    /// size since the tap.
    Resizing,
    /// An attachment was staged for another target, agent or daemon run,
    /// or names a path that is not safe to paste.
    AttachmentTarget,
    /// Attachments go only to an OMP instance the draft was written for.
    AttachmentNeedsAgent,
    /// A steer carries text only.
    AttachmentSteer,
    /// A staged file is no longer on the host as it was staged.
    AttachmentMissing,
}

/// What the latest screen shows of a program's own input box.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Editor {
    /// The box is on screen and holds nothing.
    Empty,
    /// The box holds text the user did not write here.
    Draft,
    /// Something else is on screen: a picker, a dialog, a scrolled view.
    Unrecognized,
    /// The program has no box this module knows how to read.
    NotApplicable,
}

/// The daemon facts about one pane, from the latest snapshot.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PaneFacts {
    /// The program Herdr recognised in the pane, in Herdr's own words.
    pub agent: Option<String>,
    pub status: Option<AgentStatus>,
}

/// Every pane of one daemon boot, by pane ID.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct Registry {
    pub boot: Option<String>,
    pub panes: HashMap<String, PaneFacts>,
}

impl Registry {
    pub fn from_snapshot(snapshot: &ClientShellSnapshot) -> Self {
        let mut panes: HashMap<String, PaneFacts> = snapshot
            .panes
            .iter()
            .map(|pane| {
                (
                    pane.pane_id.clone(),
                    PaneFacts {
                        agent: None,
                        status: None,
                    },
                )
            })
            .collect();
        for agent in &snapshot.agents {
            if let Some(facts) = panes.get_mut(&agent.pane_id) {
                facts.agent = agent
                    .agent
                    .as_deref()
                    .or(agent.display_agent.as_deref())
                    .map(str::to_owned);
                facts.status = Some(agent.agent_status);
            }
        }
        Self {
            boot: Some(snapshot.boot_id.clone()),
            panes,
        }
    }
}

/// How a pane's terminal is scrolled, from its surface metrics.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PaneScroll {
    /// Scrolled back from the live bottom.
    pub scrolled: bool,
    /// The terminal's height in rows: the size the program lays out for,
    /// which may differ from this client's view of the pane.
    pub rows: u64,
}

/// Below this many rows OMP (v18.18) shrinks its input box to the cursor
/// line: a draft ending in an empty line then reads as the empty box. Seen
/// at 7 rows and fewer; 12 leaves margin for other versions.
pub const MIN_SEND_ROWS: u64 = 12;

/// What the screen thread knows about the surface right now.
#[derive(Debug, Default)]
pub struct Surface<'a> {
    pub frame: Option<&'a RenderFrame>,
    pub popup_open: bool,
    /// Scroll metrics by pane ID; OMP without them takes no text at all.
    pub scroll: Option<&'a HashMap<String, PaneScroll>>,
    /// The grid this client last asked Herdr for.
    pub grid: Option<&'a Grid>,
}

/// What the phone drew at the tap, and the target it must be bound to.
#[derive(Debug, Clone, Copy)]
pub struct Phone<'a> {
    pub viewport: Option<&'a Viewport>,
    pub bind: Bind<'a>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Gate {
    pub ok: bool,
    pub reason: Option<Refusal>,
}

impl Gate {
    fn from(result: Result<(), Refusal>) -> Self {
        match result {
            Ok(()) => Self {
                ok: true,
                reason: None,
            },
            Err(reason) => Self {
                ok: false,
                reason: Some(reason),
            },
        }
    }
}

/// The composer's view of one pane, recomputed on every frame and snapshot.
#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct Check {
    pub boot: Option<String>,
    pub pane_exists: bool,
    pub visible: bool,
    pub agent: Option<String>,
    pub status: Option<&'static str>,
    pub program: Program,
    pub editor: Editor,
    /// OMP's box in frame rows, top border to `╰─` row, when it was read.
    pub editor_span: Option<[u16; 2]>,
    pub insert: Gate,
    pub send: Gate,
    /// The first step of a steer: its paste.
    pub steer: Gate,
    /// Whether the target is wholly on the phone; folded into the gates
    /// above after every host check.
    pub phone: Gate,
}

/// Programs whose submit behaviour is known.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum Program {
    Omp,
    /// No agent was recognised: a shell or any other program.
    Terminal,
    /// An agent this module has not been verified against.
    OtherAgent,
}

impl Program {
    fn of(agent: Option<&str>) -> Self {
        match agent {
            None => Self::Terminal,
            Some(name) if name.eq_ignore_ascii_case("omp") => Self::Omp,
            Some(_) => Self::OtherAgent,
        }
    }
}

/// A delivery as Kotlin asks for it.
#[derive(Debug, Clone, Deserialize)]
pub struct Request {
    /// The saved node the draft belongs to; with `session` and `pane`, the
    /// target a delivery holds.
    pub node: String,
    pub session: String,
    pub boot: String,
    pub pane: String,
    /// The program the user saw in the pane when choosing it; `None` for a
    /// plain terminal.
    pub agent: Option<String>,
    /// The agent instance the draft was written for, when one was read;
    /// `None` for a plain terminal or a draft from before instances.
    #[serde(default)]
    pub instance: Option<Identity>,
    pub text: String,
    /// Press Enter after the paste.
    pub submit: bool,
    /// What the phone drew at the tap; `None` is refused.
    #[serde(default)]
    pub viewport: Option<Viewport>,
    /// Files staged on the host for this target, pasted before the text.
    #[serde(default)]
    pub attachments: Vec<attach::Attachment>,
}

impl Request {
    pub fn target(&self) -> hold::Target {
        hold::Target {
            node: self.node.clone(),
            session: self.session.clone(),
            pane: self.pane.clone(),
        }
    }

    fn phone(&self) -> Phone<'_> {
        Phone {
            viewport: self.viewport.as_ref(),
            bind: Bind {
                node: Some(&self.node),
                session: &self.session,
                boot: Some(&self.boot),
                pane: &self.pane,
                instance: Some(self.instance.as_ref()),
            },
        }
    }
}

/// Whether the agent in the pane now, as the host reads it, is the instance
/// the draft was written for.
pub fn same_instance(expected: &Identity, now: Option<&Identity>) -> Result<(), Refusal> {
    match now {
        None => Err(Refusal::InstanceUnknown),
        Some(now) if now == expected => Ok(()),
        Some(_) => Err(Refusal::AgentRestarted),
    }
}

/// Whether the text may go, and how it reached that answer. Every host
/// check runs on Herdr's whole frame first; the phone gate only ever adds
/// a refusal after them, never replaces one.
pub fn check(registry: &Registry, surface: &Surface<'_>, pane: &str, phone: &Phone<'_>) -> Check {
    let facts = registry.panes.get(pane);
    let agent = facts.and_then(|facts| facts.agent.clone());
    let status = facts.and_then(|facts| facts.status);
    let program = Program::of(agent.as_deref());
    let rows = surface.frame.and_then(|frame| pane_rows(frame, pane));
    let scroll = surface.scroll.and_then(|scroll| scroll.get(pane)).copied();
    let scrolled = scroll.is_some_and(|scroll| scroll.scrolled);
    let reading = match program {
        Program::Omp => match &rows {
            Some(_) if scrolled => Err(None),
            Some(rows) => omp_editor(rows)
                .map_err(|unseen| Some(measured(unseen, rows, scroll, sized_here(surface)))),
            None => Err(None),
        },
        Program::Terminal | Program::OtherAgent => Ok(Editor::NotApplicable),
    };
    let editor = reading.unwrap_or(Editor::Unrecognized);
    let span = match (program, surface.frame, &rows) {
        (Program::Omp, Some(frame), Some(rows)) if !scrolled => editor_span(frame, pane, rows),
        _ => None,
    };
    let on_phone = phone_gate(surface, pane, program, span.clone(), phone);
    let insert = insert_gate(registry, surface, pane, facts);
    let send = insert.and_then(|()| {
        if program != Program::Omp {
            return Err(Refusal::UnsupportedProgram);
        }
        match status {
            Some(AgentStatus::Idle | AgentStatus::Done) => {}
            Some(AgentStatus::Working) => return Err(Refusal::AgentWorking),
            Some(AgentStatus::Blocked) => return Err(Refusal::AgentBlocked),
            Some(AgentStatus::Unknown) | None => return Err(Refusal::UnsupportedProgram),
        }
        box_on_screen(rows.is_some(), scroll)?;
        empty_box(reading)
    });
    let steer = insert.and_then(|()| {
        steer_status(program, status)?;
        box_on_screen(rows.is_some(), scroll)?;
        empty_box(reading)
    });
    Check {
        boot: registry.boot.clone(),
        pane_exists: facts.is_some(),
        visible: rows.is_some(),
        agent,
        status: status.map(crate::view::status),
        program,
        editor,
        editor_span: span.map(|span| [*span.start(), *span.end()]),
        insert: Gate::from(insert.and(on_phone)),
        send: Gate::from(send.and(on_phone)),
        steer: Gate::from(steer.and(on_phone)),
        phone: Gate::from(on_phone),
    }
}

/// OMP's box in frame rows: from its top border to its `╰─` row.
fn editor_span(
    frame: &RenderFrame,
    pane: &str,
    rows: &[String],
) -> Option<std::ops::RangeInclusive<u16>> {
    let (top, last, _) = omp_box(rows).ok()?;
    let y = frame.panes.iter().find(|area| area.id == pane)?.rect.y;
    let row = |index: usize| {
        u16::try_from(index)
            .ok()
            .and_then(|index| y.checked_add(index))
    };
    Some(row(top)?..=row(last)?)
}

fn phone_gate(
    surface: &Surface<'_>,
    pane: &str,
    program: Program,
    span: Option<std::ops::RangeInclusive<u16>>,
    phone: &Phone<'_>,
) -> Result<(), Refusal> {
    visible::gate(
        program,
        surface.frame,
        pane,
        span,
        surface.grid,
        phone.viewport,
        &phone.bind,
    )
}

/// OMP's whole input box is on screen at the live bottom, tall enough that
/// no draft row can hide above the cursor line.
fn box_on_screen(visible: bool, scroll: Option<PaneScroll>) -> Result<(), Refusal> {
    if !visible {
        return Err(Refusal::NotVisible);
    }
    if scroll.is_some_and(|scroll| scroll.scrolled) {
        return Err(Refusal::Scrolled);
    }
    if scroll.is_none_or(|scroll| scroll.rows < MIN_SEND_ROWS) {
        return Err(Refusal::TooShort);
    }
    Ok(())
}

/// A pane whose rows Herdr counts differently from its viewport is being
/// resized, whatever its rows look like mid-redraw; or, once the grid this
/// client asked for is drawn ([`sized_here`]), it is laid out for another
/// client's size (Herdr 0.9.5 sizes a pane for the client that last typed
/// into or focused it). A whole dialog still names itself: it refuses
/// either way, and says where to answer.
fn measured(
    unseen: Unseen,
    rows: &[String],
    scroll: Option<PaneScroll>,
    sized_here: bool,
) -> Unseen {
    let mismatched =
        scroll.is_some_and(|scroll| u64::try_from(rows.len()).ok() != Some(scroll.rows));
    match unseen {
        Unseen::DialogOpen => unseen,
        _ if mismatched && sized_here => Unseen::SizedElsewhere,
        _ if mismatched => Unseen::Resizing,
        _ => unseen,
    }
}

/// The frame held is the grid this client last asked for, drawn: no resize
/// of its own is pending.
fn sized_here(surface: &Surface<'_>) -> bool {
    surface
        .grid
        .zip(surface.frame)
        .is_some_and(|(grid, frame)| grid.settled(frame))
}

/// Send needs the empty box; an unread box refuses with its measured cause,
/// or `EditorUnrecognized` when none was measured.
fn empty_box(reading: Result<Editor, Option<Unseen>>) -> Result<(), Refusal> {
    match reading {
        Ok(Editor::Empty) => Ok(()),
        Ok(Editor::Draft) => Err(Refusal::RemoteDraft),
        Ok(Editor::Unrecognized | Editor::NotApplicable) | Err(None) => {
            Err(Refusal::EditorUnrecognized)
        }
        Err(Some(unseen)) => Err(unseen.refusal()),
    }
}

/// Steer goes only to OMP while it works: never to an idle agent, whose
/// Enter would start a new turn, nor to an ask.
fn steer_status(program: Program, status: Option<AgentStatus>) -> Result<(), Refusal> {
    if program != Program::Omp {
        return Err(Refusal::UnsupportedProgram);
    }
    match status {
        Some(AgentStatus::Working) => Ok(()),
        Some(AgentStatus::Idle | AgentStatus::Done) => Err(Refusal::AgentIdle),
        Some(AgentStatus::Blocked) => Err(Refusal::AgentBlocked),
        Some(AgentStatus::Unknown) | None => Err(Refusal::UnsupportedProgram),
    }
}

fn insert_gate(
    registry: &Registry,
    surface: &Surface<'_>,
    pane: &str,
    facts: Option<&PaneFacts>,
) -> Result<(), Refusal> {
    if registry.boot.is_none() {
        return Err(Refusal::NotConnected);
    }
    let facts = facts.ok_or(Refusal::PaneMissing)?;
    if surface.popup_open {
        return Err(Refusal::PopupOpen);
    }
    // Text goes into OMP only through its own box, read live now: never
    // past a dialog, never on the strength of a reported status, which may
    // lag the screen, and never from scrollback, which is not the screen.
    if Program::of(facts.agent.as_deref()) == Program::Omp {
        let rows = surface.frame.and_then(|frame| pane_rows(frame, pane));
        let scroll = surface.scroll.and_then(|scroll| scroll.get(pane)).copied();
        live_box(rows.as_deref(), scroll, sized_here(surface))?;
    }
    // A paste into an approval or question would answer it.
    if facts.status == Some(AgentStatus::Blocked) {
        return Err(Refusal::AgentBlocked);
    }
    Ok(())
}

/// OMP's box, whole at the live bottom of the pane: the only place text,
/// even without Enter, may go. A dialog names itself, and a frame that may
/// be one refuses without claiming it. Scrolled-back rows are never read.
fn live_box(
    rows: Option<&[String]>,
    scroll: Option<PaneScroll>,
    sized_here: bool,
) -> Result<(), Refusal> {
    let (Some(rows), Some(scroll)) = (rows, scroll) else {
        return Err(Refusal::NotVisible);
    };
    if scroll.scrolled {
        return Err(Refusal::Scrolled);
    }
    omp_editor(rows)
        .map(|_| ())
        .map_err(|unseen| measured(unseen, rows, Some(scroll), sized_here).refusal())
}

/// The whole decision for one delivery: the check for its pane, plus what
/// the request itself must match.
pub fn admit(registry: &Registry, surface: &Surface<'_>, request: &Request) -> Result<(), Refusal> {
    admit_text(&request.text, !request.attachments.is_empty())?;
    attach::admit(
        &request.attachments,
        attach::Bound {
            node: &request.node,
            session: &request.session,
            boot: &request.boot,
            pane: &request.pane,
            instance: request.instance.as_ref(),
        },
    )?;
    same_boot(registry, &request.boot)?;
    let check = check(registry, surface, &request.pane, &request.phone());
    let gate = if request.submit {
        &check.send
    } else {
        &check.insert
    };
    if let Some(reason) = gate.reason {
        // A missing pane says more than any later check.
        return Err(reason);
    }
    same_agent(request.agent.as_deref(), check.agent.as_deref())
}

/// The text of a delivery; it may be empty only beside attachments.
fn admit_text(text: &str, attached: bool) -> Result<(), Refusal> {
    if text.trim().is_empty() && !attached {
        return Err(Refusal::EmptyText);
    }
    if text.len() > MAX_TEXT_BYTES {
        return Err(Refusal::TooLarge);
    }
    Ok(())
}

fn same_boot(registry: &Registry, boot: &str) -> Result<(), Refusal> {
    match registry.boot.as_deref() {
        Some(now) if now == boot => Ok(()),
        Some(_) => Err(Refusal::BootChanged),
        None => Err(Refusal::NotConnected),
    }
}

fn same_agent(seen: Option<&str>, now: Option<&str>) -> Result<(), Refusal> {
    let seen = seen.map(str::to_ascii_lowercase);
    let now = now.map(str::to_ascii_lowercase);
    if seen == now {
        Ok(())
    } else {
        Err(Refusal::AgentChanged)
    }
}

/// A steer as Kotlin asks for it, from one explicit tap on its own action.
/// It never submits by itself: its Enter is a second, checked step.
#[derive(Debug, Clone, Deserialize)]
pub struct SteerRequest {
    pub node: String,
    pub session: String,
    pub boot: String,
    pub pane: String,
    pub agent: Option<String>,
    /// The agent instance the text is meant for; a steer without one is
    /// refused, since only the instance tells this turn from the next.
    #[serde(default)]
    pub instance: Option<Identity>,
    pub text: String,
    /// What the phone drew at the tap; `None` is refused.
    #[serde(default)]
    pub viewport: Option<Viewport>,
    /// Never carries attachments: a steer that names any is refused.
    #[serde(default)]
    pub attachments: Vec<attach::Attachment>,
}

impl SteerRequest {
    pub fn target(&self) -> hold::Target {
        hold::Target {
            node: self.node.clone(),
            session: self.session.clone(),
            pane: self.pane.clone(),
        }
    }

    fn phone(&self) -> Phone<'_> {
        Phone {
            viewport: self.viewport.as_ref(),
            bind: Bind {
                node: Some(&self.node),
                session: &self.session,
                boot: Some(&self.boot),
                pane: &self.pane,
                instance: Some(self.instance.as_ref()),
            },
        }
    }
}

/// The first step of a steer: its paste alone, into OMP's empty box while
/// the same OMP works.
pub fn admit_steer(
    registry: &Registry,
    surface: &Surface<'_>,
    request: &SteerRequest,
) -> Result<(), Refusal> {
    if !request.attachments.is_empty() {
        return Err(Refusal::AttachmentSteer);
    }
    admit_text(&request.text, false)?;
    same_boot(registry, &request.boot)?;
    let check = check(registry, surface, &request.pane, &request.phone());
    if let Some(reason) = check.steer.reason {
        return Err(reason);
    }
    same_agent(request.agent.as_deref(), check.agent.as_deref())
}

/// The second step: Enter for a staged paste, only while the same OMP still
/// works, its box, whole on screen, shows exactly the staged text, and the
/// phone drew all of it at the Enter tap (`phone`, checked last).
pub fn admit_steer_enter(
    registry: &Registry,
    surface: &Surface<'_>,
    boot: &str,
    pane: &str,
    agent: Option<&str>,
    text: &str,
    phone: &Phone<'_>,
) -> Result<(), Refusal> {
    same_boot(registry, boot)?;
    let facts = registry.panes.get(pane);
    insert_gate(registry, surface, pane, facts)?;
    let now = facts.and_then(|facts| facts.agent.as_deref());
    same_agent(agent, now)?;
    steer_status(Program::of(now), facts.and_then(|facts| facts.status))?;
    let rows = surface.frame.and_then(|frame| pane_rows(frame, pane));
    let scroll = surface.scroll.and_then(|scroll| scroll.get(pane)).copied();
    box_on_screen(rows.is_some(), scroll)?;
    let Some(rows) = rows else {
        return Err(Refusal::EditorUnrecognized);
    };
    match omp_box_text(&rows) {
        Some(shown) if shown == words(text) => {}
        // OMP has not drawn the paste yet.
        Some(shown) if shown.is_empty() => return Err(Refusal::SteerNotShown),
        Some(_) => return Err(Refusal::RemoteDraft),
        None => {
            return Err(omp_editor(&rows)
                .err()
                .map_or(Refusal::EditorUnrecognized, |unseen| {
                    measured(unseen, &rows, scroll, sized_here(surface)).refusal()
                }));
        }
    }
    let span = surface
        .frame
        .and_then(|frame| editor_span(frame, pane, &rows));
    phone_gate(surface, pane, Program::Omp, span, phone)
}

/// Text as OMP's box shows it once wrapped: its words, one space apart.
fn words(text: &str) -> String {
    text.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// Text that may cross into a paste: no escape or other control characters
/// that could end the bracketed paste or drive the terminal. Tabs and
/// newlines stay; carriage returns become newlines.
pub fn clean(text: &str) -> String {
    text.replace("\r\n", "\n")
        .chars()
        .map(|ch| if ch == '\r' { '\n' } else { ch })
        .filter(|ch| *ch == '\n' || *ch == '\t' || !ch.is_control())
        .collect()
}

/// The rows of `pane` in the frame, each trimmed of trailing blanks; `None`
/// when the pane is not on the surface.
pub fn pane_rows(frame: &RenderFrame, pane: &str) -> Option<Vec<String>> {
    let area = frame.panes.iter().find(|area| area.id == pane)?;
    let rect = area.rect;
    if rect.width == 0 || rect.height == 0 {
        return None;
    }
    let last_col = rect.x.saturating_add(rect.width - 1);
    Some(
        (rect.y..rect.y.saturating_add(rect.height))
            .map(|row| frame.text((rect.x, row), (last_col, row)))
            .collect(),
    )
}

/// How far into its row OMP right-aligns a hint in an empty box when the
/// row has no right corner (v18.12): text this far from the box edge is not
/// a draft, which starts at the edge.
const HINT_GAP: usize = 8;

/// Why OMP's input box could not be read, as measured on the pane's rows.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Unseen {
    /// A whole box is on screen with rows drawn under it: OMP's slash or
    /// autocomplete list, or a menu.
    ListBelow,
    /// The last row is the box's bottom, but its `│` rows run to the top of
    /// the pane or end at something other than a top border: the box is
    /// taller than the pane, or its top is cut off.
    TopMissing,
    /// A top border stands above the box's bottom, but another row sits
    /// between them: something is drawn across the box.
    Covered,
    /// The pane's size disagrees with Herdr's viewport: a resize is being
    /// drawn.
    Resizing,
    /// The pane's size disagrees with Herdr's viewport though this client's
    /// grid is drawn: Herdr sized the pane for another client.
    SizedElsewhere,
    /// No box bottom anywhere in the pane: another view, a blank pane, or
    /// no box drawn. A blank pane is a resize only when its size says so.
    None,
    /// A whole OMP dialog (an ask, selector, input or confirm) ends at the
    /// bottom of the pane or right on top of the box: input goes to it, and
    /// Enter would answer it.
    DialogOpen,
    /// A frame's bottom sits right on top of the box, but the frame is not
    /// a whole dialog (cropped or unknown): it may be one, so nothing goes.
    Framed,
}

impl Unseen {
    pub fn refusal(self) -> Refusal {
        match self {
            Self::ListBelow => Refusal::EditorListBelow,
            Self::TopMissing => Refusal::EditorTopMissing,
            Self::Covered => Refusal::EditorCovered,
            Self::Resizing => Refusal::EditorResizing,
            Self::SizedElsewhere => Refusal::PaneSizedElsewhere,
            Self::None => Refusal::EditorNone,
            Self::DialogOpen => Refusal::EditorDialogOpen,
            Self::Framed => Refusal::EditorUnrecognized,
        }
    }
}

/// Read OMP's input box (v18) from the bottom of its pane: a top border
/// `╭──` with the model line, any `│` draft rows, then the last row `╰─`.
/// The box counts as empty only in its exact empty shapes, with nothing
/// below it; anything else is a draft or unseen, never empty.
pub fn omp_editor(rows: &[String]) -> Result<Editor, Unseen> {
    let (top, last, bottom) = omp_box(rows)?;
    if top + 1 < last {
        return Ok(if ime_empty(rows, top, last, bottom) {
            Editor::Empty
        } else {
            Editor::Draft
        });
    }
    Ok(if box_tail(&rows[top], bottom).is_empty() {
        Editor::Empty
    } else {
        Editor::Draft
    })
}

/// The empty box with OMP's `tui.imeSafeCursor` on: the cursor row is a bare
/// `│` with nothing to its right, and the bottom border is a row of its own.
/// A dialog drawn in the same glyphs closes its rows with a right `│`.
fn ime_empty(rows: &[String], top: usize, last: usize, bottom: &str) -> bool {
    last == top + 2
        && rows[top + 1].trim_end() == "│"
        && bottom
            .trim_end()
            .strip_suffix('╯')
            .is_some_and(|border| border.chars().all(|ch| ch == '─'))
}

/// The words OMP's input box shows, one space apart: wrapping and line
/// breaks read alike. Empty for the empty box; `None` without the box.
pub fn omp_box_text(rows: &[String]) -> Option<String> {
    let (top, last, bottom) = omp_box(rows).ok()?;
    let inner = rows[top + 1..last].iter().map(|row| {
        let row = row.strip_prefix('│').unwrap_or(row);
        row.trim_end().trim_end_matches('│')
    });
    let words: Vec<&str> = inner
        .chain([box_tail(&rows[top], bottom)])
        .flat_map(str::split_whitespace)
        .collect();
    Some(words.join(" "))
}

/// OMP's box at the bottom of the rows: its top border row, its last row,
/// and what follows `╰─` there. Nothing but blank rows may follow it, and
/// no frame may stand right on top of it: OMP (18.18.6) mounts an ask
/// dialog there over a still-visible box, and the dialog then owns input.
fn omp_box(rows: &[String]) -> Result<(usize, usize, &str), Unseen> {
    let mut end = rows.len();
    while end > 0 && rows[end - 1].trim().is_empty() {
        end -= 1;
    }
    let last = end.checked_sub(1).ok_or(Unseen::None)?;
    // A dialog that replaced the box ends where the box would.
    if dialog_ending_at(rows, last) {
        return Err(Unseen::DialogOpen);
    }
    let Some(bottom) = rows[last].strip_prefix("╰─") else {
        let boxed = (0..last).any(|row| {
            rows[row].starts_with("╰─")
                && box_top(rows, row).is_some_and(|top| rows[top].starts_with("╭──"))
        });
        return Err(if boxed {
            Unseen::ListBelow
        } else {
            Unseen::None
        });
    };
    match box_top(rows, last) {
        Some(top) if rows[top].starts_with("╭──") => {
            // OMP leaves a blank row between its transcript and the box; a
            // frame's bottom flush on the box is a dialog mounted over it.
            if let Some(above) = top.checked_sub(1)
                && rows[above].starts_with('╰')
            {
                return Err(if dialog_ending_at(rows, above) {
                    Unseen::DialogOpen
                } else {
                    Unseen::Framed
                });
            }
            Ok((top, last, bottom))
        }
        Some(top) if rows[..top].iter().any(|row| row.starts_with("╭──")) => {
            Err(Unseen::Covered)
        }
        _ => Err(Unseen::TopMissing),
    }
}

/// Whether a whole OMP dialog frame ends at row `bottom`: a titled top
/// `╭─ Title ─…╮`, side rows `│`/`├` … `│`/`┤`, and a plain bottom `╰─…╯`
/// as wide as the top, all from the pane's first column. OMP's transcript
/// indents its output, and its own box opens `╭──`, so neither matches.
fn dialog_ending_at(rows: &[String], bottom: usize) -> bool {
    let edge = rows[bottom].trim_end();
    let plain = edge
        .strip_prefix('╰')
        .and_then(|rest| rest.strip_suffix('╯'))
        .is_some_and(|rule| !rule.is_empty() && rule.chars().all(|ch| ch == '─'));
    if !plain {
        return false;
    }
    let Some(top) = (0..bottom).rev().find(|&row| !dialog_side(&rows[row])) else {
        // The sides run off the top of the pane: cropped, not whole.
        return false;
    };
    let head = rows[top].trim_end();
    top + 1 < bottom
        && head.chars().count() == edge.chars().count()
        && head.ends_with("─╮")
        && head
            .strip_prefix("╭─ ")
            .is_some_and(|title| title.starts_with(|ch: char| !ch.is_whitespace()))
}

/// A side row of a dialog frame, or one of its `├…┤` dividers.
fn dialog_side(row: &str) -> bool {
    let row = row.trim_end();
    row.chars().count() >= 2
        && (row.starts_with('│') || row.starts_with('├'))
        && (row.ends_with('│') || row.ends_with('┤'))
}

/// The row above `bottom` where the box's `│` rows stop.
fn box_top(rows: &[String], bottom: usize) -> Option<usize> {
    (0..bottom).rev().find(|&row| !rows[row].starts_with('│'))
}

/// The text on the box's last row, or "" for its border or a right-aligned
/// hint. A draft starts one cell short of the top border's padding (`╭──`
/// pads 2, a draft follows `╰─ `); a hint starts further in and ends one
/// space before the `─╯` corner, however narrow the pane.
fn box_tail<'a>(top: &str, bottom: &'a str) -> &'a str {
    let row = bottom.trim_end();
    let (content, cornered) = match row.strip_suffix('╯') {
        Some(rest) => (rest.trim_end_matches('─'), true),
        None => (row, false),
    };
    if content.trim().is_empty() {
        return "";
    }
    let lead = content.chars().take_while(|ch| *ch == ' ').count();
    let hint = if cornered {
        let padding = top.chars().skip(1).take_while(|ch| *ch == '─').count();
        let trailing = content.len() - content.trim_end().len();
        // `─╯` stands after `padding - 1` spaces; a hint ends right at them.
        lead >= padding.max(2) && trailing < padding.max(2)
    } else {
        lead >= HINT_GAP
    };
    if hint { "" } else { content }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
pub(crate) mod tests;
