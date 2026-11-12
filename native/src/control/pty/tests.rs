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
