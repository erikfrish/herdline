use super::*;
use crate::composer::tests::rows;
use crate::transcript::Kind;

const W: Option<AgentStatus> = Some(AgentStatus::Working);

// OMP 18.18.3's empty box (composer fixture) under a working status row.
const OMP_WORKING: &str = "\
⠋ Working… (esc to interrupt)

╭── π  dia-proxy/claude-opus-5-5  bellterm-lab/work ────8%──╮
╰─                                                          ─╯
";

const OMP_DRAFT: &str = "\
╭── π  dia-proxy/claude-opus-5-5  bellterm-lab/work ────8%──╮
╰─ draft line one                                           ─╯
";

const PI_WORKING: &str = "\
─────────────────────────────────────────────

─────────────────────────────────────────────
/tmp/pi-obs/work
0.0%/0 (auto)                                                                            unknown
";

// Claude's mode row under its empty box, as Herdr's working rule reads it.
const CLAUDE_WORKING: &str = "\
✻ Thinking… (3s)
────────────────────────────────────────
❯
────────────────────────────────────────
  ⏵⏵ accept edits on (shift+tab to cycle) · esc to interrupt
";

// A permission dialog in place of the box: `esc to cancel` is its own.
const CLAUDE_DIALOG: &str = "\
────────────────────────────────────────
❯  Do you want to proceed? › Yes
────────────────────────────────────────
  ⏵⏵ accept edits on · esc to interrupt
";

const CODEX_WORKING: &str = "\
• Working (1m 02s • esc to interrupt)

› Ask Codex to do anything
";

const CODEX_REMAPPED: &str = "\
• Working (12s • ctrl+g to interrupt)

› Ask Codex to do anything
";

fn entry(role: &str, stop: Option<&str>) -> Entry {
    Entry {
        id: None,
        parent_id: None,
        offset: 0,
        end: 1,
        kind: Kind::Message,
        role: Some(role.to_owned()),
        blocks: Vec::new(),
        timestamp: None,
        usage: None,
        stop_reason: stop.map(str::to_owned),
        steer: None,
        subagent_ends: Vec::new(),
    }
}

#[test]
fn only_a_working_agent_is_stopped() {
    for status in [None, Some(AgentStatus::Idle), Some(AgentStatus::Blocked), Some(AgentStatus::Done), Some(AgentStatus::Unknown)] {
        assert_eq!(decide("omp", status, &rows(OMP_WORKING)), Err(Refusal::NotWorking));
    }
    assert_eq!(decide("omp", W, &rows(OMP_WORKING)), Ok(Key::Esc));
}

#[test]
fn pinned_esc_needs_the_runtime_own_empty_box() {
    assert_eq!(decide("pi", W, &rows(PI_WORKING)), Ok(Key::Esc));
    // Typed text or another view would take Esc first.
    assert_eq!(decide("omp", W, &rows(OMP_DRAFT)), Err(Refusal::ScreenNotStoppable));
    assert_eq!(decide("omp", W, &rows("Select a model\n> one\n")), Err(Refusal::ScreenNotStoppable));
}

#[test]
fn a_hint_presses_esc_only_as_the_runtime_draws_it_while_working() {
    assert_eq!(decide("claude", W, &rows(CLAUDE_WORKING)), Ok(Key::Esc));
    assert_eq!(decide("claude", W, &rows(CLAUDE_DIALOG)), Err(Refusal::NoStopKey));
    assert_eq!(decide("codex", W, &rows(CODEX_WORKING)), Ok(Key::Esc));
    // A remapped key is not Esc; this core never presses another one.
    assert_eq!(decide("codex", W, &rows(CODEX_REMAPPED)), Err(Refusal::NoStopKey));
    // The words in a transcript row are not the live timer.
    assert_eq!(decide("codex", W, &rows("press esc to interrupt)\n")), Err(Refusal::NoStopKey));
}

#[test]
fn runtimes_without_a_read_screen_or_ref_get_no_key() {
    for agent in ["kimi", "hermes", "gemini", "amp", "opencode", ""] {
        assert_eq!(decide(agent, W, &rows(CLAUDE_WORKING)), Err(Refusal::NoStopKey), "{agent}");
    }
}

#[test]
fn only_an_aborted_assistant_turn_records_the_stop() {
    assert!(shows_stop(&[entry("user", None), entry("assistant", Some("aborted"))]));
    assert!(!shows_stop(&[entry("assistant", Some("stop")), entry("assistant", Some("error"))]));
    assert!(!shows_stop(&[entry("user", Some("aborted"))]));
    assert!(!shows_stop(&[]));
    assert!(confirms("omp") && confirms("pi"));
    assert!(!confirms("claude") && !confirms("codex"));
}

// QA X1 case 2: Chat reads OMP in w1:p1 while Herdr's shared surface (the
// desktop's tab) holds another pane; the stop must name why, never press.
#[test]
fn a_chat_stop_presses_only_on_the_pane_herdr_shows_and_names_why_not() {
    use crate::composer::Surface;
    use crate::composer::tests::{frame, registry, scroll};
    let working = registry(Some("omp"), W);
    let live = scroll(false, 40);
    let elsewhere = frame("w1:p2", OMP_WORKING);
    let off_tab = Surface { frame: Some(&elsewhere), scroll: Some(&live), ..Surface::default() };
    assert_eq!(admit(&working, &off_tab, "boot-1", "w1:p1", "omp"), Err(Refusal::NotVisible));
    assert_eq!(admit(&working, &Surface::default(), "boot-1", "w1:p1", "omp"), Err(Refusal::NotVisible));
    // A crop of a pane sized for another client: OMP's box is off it.
    let crop = frame("w1:p1", &"output one\noutput two\n".repeat(6));
    let cropped = Surface { frame: Some(&crop), scroll: Some(&live), ..Surface::default() };
    assert_eq!(admit(&working, &cropped, "boot-1", "w1:p1", "omp"), Err(Refusal::ScreenNotStoppable));
    // The same pane on the surface, its empty box drawn: one Esc.
    let shown = frame("w1:p1", OMP_WORKING);
    let here = || Surface { frame: Some(&shown), scroll: Some(&live), ..Surface::default() };
    assert_eq!(admit(&working, &here(), "boot-1", "w1:p1", "omp"), Ok(Key::Esc));
    assert_eq!(admit(&working, &here(), "boot-2", "w1:p1", "omp"), Err(Refusal::BootChanged));
    assert_eq!(admit(&working, &Surface { popup_open: true, ..here() }, "boot-1", "w1:p1", "omp"), Err(Refusal::PopupOpen));
    let back = scroll(true, 40);
    assert_eq!(admit(&working, &Surface { scroll: Some(&back), ..here() }, "boot-1", "w1:p1", "omp"), Err(Refusal::Scrolled));
}
