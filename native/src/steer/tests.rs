use super::*;
use crate::composer::tests::{
    ASK_OVER_EMPTY, EMPTY, MULTILINE, ONE_LINE, acked, frame, phone, registry, rows, scroll, whole,
};
use crate::composer::{
    Phone, SteerRequest, Surface, admit_steer, admit_steer_enter, check, omp_box_text,
};
use crate::render::RenderFrame;
use crate::visible::{Bind, Viewport};
use herdr_client::protocol::AgentStatus;

// The steer's text drawn in OMP's box, wrapped, with the box's right border.
const STEER_SHOWN: &str = "\
╭── π  model  …/work ────15%──────200K───╮
│  Also end with the      │
╰─ word TANGO              ─╯
";

const TEXT: &str = "Also end with the word TANGO";

fn instance() -> Identity {
    Identity {
        pane_id: "w1:p1".into(),
        terminal_id: "term_1".into(),
        pid: Some(42),
        path: "/home/u/.omp/agent/sessions/x/a.jsonl".into(),
        ..Identity::default()
    }
}

fn steer_request() -> SteerRequest {
    SteerRequest {
        node: "n1".into(),
        session: "lab".into(),
        boot: "boot-1".into(),
        pane: "w1:p1".into(),
        agent: Some("omp".into()),
        instance: Some(instance()),
        text: TEXT.into(),
        viewport: None,
        attachments: Vec::new(),
    }
}

/// What the phone drew of `frame` for the steer's target and instance.
fn steer_view(frame: &RenderFrame) -> Viewport {
    Viewport {
        instance: Some(instance()),
        ..whole(frame)
    }
}

/// `steer_request()` tapped with `frame` drawn whole.
fn seen_steer(frame: &RenderFrame) -> SteerRequest {
    SteerRequest {
        viewport: Some(steer_view(frame)),
        ..steer_request()
    }
}

/// The Enter tap's phone, bound as the session binds it to the staged steer.
fn enter_phone<'a>(viewport: Option<&'a Viewport>, staged: &'a Staged) -> Phone<'a> {
    Phone {
        viewport,
        bind: Bind {
            node: Some("n1"),
            session: "lab",
            boot: Some(&staged.boot),
            pane: "w1:p1",
            instance: Some(Some(&staged.instance)),
        },
    }
}

fn staged() -> Staged {
    Staged {
        boot: "boot-1".into(),
        link: 1,
        receipt: "gpui-7".into(),
        agent: Some("omp".into()),
        instance: instance(),
        text: TEXT.into(),
    }
}

fn target() -> Target {
    Target {
        node: "n1".into(),
        session: "lab".into(),
        pane: "w1:p1".into(),
    }
}

/// The Enter gate for the staged steer, OMP in `status`, its pane showing
/// `screen` drawn whole on the phone.
fn enter_gate(status: AgentStatus, screen: &str) -> Result<(), Refusal> {
    let shown = frame("w1:p1", screen);
    let roomy = scroll(false, 40);
    let grid = acked(&shown);
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&roomy),
        grid: Some(&grid),
        ..Surface::default()
    };
    let steer = staged();
    let viewport = steer_view(&shown);
    admit_steer_enter(
        &registry(Some("omp"), Some(status)),
        &surface,
        &steer.boot,
        "w1:p1",
        steer.agent.as_deref(),
        &steer.text,
        &enter_phone(Some(&viewport), &steer),
    )
}

#[test]
fn box_text_reads_the_words_in_omps_box() {
    assert_eq!(omp_box_text(&rows(EMPTY)).as_deref(), Some(""));
    assert_eq!(
        omp_box_text(&rows(ONE_LINE)).as_deref(),
        Some("draft line one")
    );
    assert_eq!(
        omp_box_text(&rows(MULTILINE)).as_deref(),
        Some("probe line one probe line two probe line three")
    );
    assert_eq!(omp_box_text(&rows(STEER_SHOWN)).as_deref(), Some(TEXT));
    assert_eq!(omp_box_text(&rows("$ ls\nfile\n$")), None);
}

#[test]
fn steer_is_offered_only_while_omp_works_and_send_never_is() {
    let empty = frame("w1:p1", EMPTY);
    let roomy = scroll(false, 40);
    let grid = acked(&empty);
    let surface = Surface {
        frame: Some(&empty),
        scroll: Some(&roomy),
        grid: Some(&grid),
        ..Surface::default()
    };
    let viewport = whole(&empty);
    let working = check(
        &registry(Some("omp"), Some(AgentStatus::Working)),
        &surface,
        "w1:p1",
        &phone(Some(&viewport)),
    );
    assert!(working.steer.ok);
    assert_eq!(working.send.reason, Some(Refusal::AgentWorking));

    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let at_rest = check(&idle, &surface, "w1:p1", &phone(Some(&viewport)));
    assert!(at_rest.send.ok);
    assert_eq!(at_rest.steer.reason, Some(Refusal::AgentIdle));
    assert_eq!(
        admit_steer(&idle, &surface, &steer_request()),
        Err(Refusal::AgentIdle)
    );
}

#[test]
fn a_working_turn_that_ends_before_enter_gets_no_enter() {
    // Idle when tapped: the user must Send instead.
    let empty = frame("w1:p1", EMPTY);
    let roomy = scroll(false, 40);
    let surface = Surface {
        frame: Some(&empty),
        scroll: Some(&roomy),
        ..Surface::default()
    };
    let done = registry(Some("omp"), Some(AgentStatus::Done));
    assert_eq!(
        admit_steer(&done, &surface, &steer_request()),
        Err(Refusal::AgentIdle)
    );
    // Working at the paste, idle at Enter: an Enter now would start a turn.
    let gate = enter_gate(AgentStatus::Idle, STEER_SHOWN);
    assert_eq!(gate, Err(Refusal::AgentIdle));
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
        Next::End(Refusal::AgentIdle)
    );
    // Still working with the paste drawn: Enter.
    let gate = enter_gate(AgentStatus::Working, STEER_SHOWN);
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
        Next::Enter
    );
}

#[test]
fn an_ask_takes_no_steer() {
    let empty = frame("w1:p1", EMPTY);
    let roomy = scroll(false, 40);
    let surface = Surface {
        frame: Some(&empty),
        scroll: Some(&roomy),
        ..Surface::default()
    };
    let blocked = registry(Some("omp"), Some(AgentStatus::Blocked));
    assert_eq!(
        admit_steer(&blocked, &surface, &steer_request()),
        Err(Refusal::AgentBlocked)
    );
    // An ask opened after the paste: Enter would answer it.
    let gate = enter_gate(AgentStatus::Blocked, STEER_SHOWN);
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
        Next::End(Refusal::AgentBlocked)
    );
    let popup = Surface {
        frame: Some(&empty),
        scroll: Some(&roomy),
        popup_open: true,
        grid: None,
    };
    let working = registry(Some("omp"), Some(AgentStatus::Working));
    assert_eq!(
        admit_steer(&working, &popup, &steer_request()),
        Err(Refusal::PopupOpen)
    );
}

#[test]
fn an_ask_opened_between_paste_and_enter_takes_no_enter() {
    let empty = frame("w1:p1", EMPTY);
    let roomy = scroll(false, 40);
    let grid = acked(&empty);
    let surface = Surface {
        frame: Some(&empty),
        scroll: Some(&roomy),
        grid: Some(&grid),
        ..Surface::default()
    };
    let working = registry(Some("omp"), Some(AgentStatus::Working));
    assert_eq!(admit_steer(&working, &surface, &seen_steer(&empty)), Ok(()));
    // Then OMP mounts its ask over the box holding the paste, while the
    // reported status still says Working.
    let ask = rows(ASK_OVER_EMPTY);
    let screen = format!("{}\n{STEER_SHOWN}", ask[..ask.len() - 2].join("\n"));
    let gate = enter_gate(AgentStatus::Working, &screen);
    assert_eq!(gate, Err(Refusal::EditorDialogOpen));
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
        Next::End(Refusal::EditorDialogOpen)
    );
}

#[test]
fn a_remote_draft_or_a_short_pane_takes_no_steer() {
    let working = registry(Some("omp"), Some(AgentStatus::Working));
    let draft = frame("w1:p1", ONE_LINE);
    let roomy = scroll(false, 40);
    let surface = Surface {
        frame: Some(&draft),
        scroll: Some(&roomy),
        ..Surface::default()
    };
    assert_eq!(
        admit_steer(&working, &surface, &steer_request()),
        Err(Refusal::RemoteDraft)
    );
    let empty = frame("w1:p1", EMPTY);
    let short = scroll(false, crate::composer::MIN_SEND_ROWS - 1);
    let surface = Surface {
        frame: Some(&empty),
        scroll: Some(&short),
        ..Surface::default()
    };
    assert_eq!(
        admit_steer(&working, &surface, &steer_request()),
        Err(Refusal::TooShort)
    );
}

#[test]
fn enter_waits_for_the_paste_and_never_follows_other_text() {
    // The receipt is unanswered, or OMP has not drawn the paste: wait.
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", true, Ok(()), Ok(())),
        Next::Wait(Refusal::Unsettled)
    );
    let gate = enter_gate(AgentStatus::Working, EMPTY);
    assert_eq!(gate, Err(Refusal::SteerNotShown));
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
        Next::Wait(Refusal::SteerNotShown)
    );
    // The box holds something else: never press Enter on it.
    let gate = enter_gate(AgentStatus::Working, MULTILINE);
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
        Next::End(Refusal::RemoteDraft)
    );
}

#[test]
fn a_reconnect_or_new_daemon_run_expires_the_steer() {
    let gate = enter_gate(AgentStatus::Working, STEER_SHOWN);
    // A later link: its request numbers prove nothing about this paste.
    assert_eq!(
        next(Some(&staged()), 2, "gpui-7", false, Ok(()), gate),
        Next::End(Refusal::SteerExpired)
    );
    assert_eq!(
        next(Some(&staged()), 1, "gpui-8", false, Ok(()), gate),
        Next::End(Refusal::SteerExpired)
    );
    let shown = frame("w1:p1", STEER_SHOWN);
    let roomy = scroll(false, 40);
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&roomy),
        ..Surface::default()
    };
    let mut restarted = registry(Some("omp"), Some(AgentStatus::Working));
    restarted.boot = Some("boot-2".into());
    let steer = staged();
    let gate = admit_steer_enter(
        &restarted,
        &surface,
        "boot-1",
        "w1:p1",
        Some("omp"),
        TEXT,
        &enter_phone(None, &steer),
    );
    assert_eq!(gate, Err(Refusal::BootChanged));
    assert_eq!(
        next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
        Next::End(Refusal::BootChanged)
    );
    // Another agent process in the pane.
    assert_eq!(
        next(
            Some(&staged()),
            1,
            "gpui-7",
            false,
            Err(Refusal::AgentRestarted),
            Ok(())
        ),
        Next::End(Refusal::AgentRestarted)
    );
}

#[test]
fn enter_goes_only_with_the_whole_box_on_the_phone_at_the_tap() {
    let shown = frame("w1:p1", STEER_SHOWN);
    let roomy = scroll(false, 40);
    let grid = acked(&shown);
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&roomy),
        grid: Some(&grid),
        ..Surface::default()
    };
    let working = registry(Some("omp"), Some(AgentStatus::Working));
    let steer = staged();
    let enter = |viewport: Option<&Viewport>| {
        admit_steer_enter(
            &working,
            &surface,
            &steer.boot,
            "w1:p1",
            steer.agent.as_deref(),
            &steer.text,
            &enter_phone(viewport, &steer),
        )
    };
    assert_eq!(enter(Some(&steer_view(&shown))), Ok(()));
    // No viewport, the top border cut by a pixel, or one captured for the
    // instance before a restart: no Enter, and the steer ends.
    let mut cut = steer_view(&shown);
    cut.rect.height -= 1;
    cut.offset_y_px = -1.0;
    cut.first = 1;
    let mut before = steer_view(&shown);
    before.instance = None;
    for viewport in [None, Some(&cut), Some(&before)] {
        let gate = enter(viewport);
        assert_eq!(gate, Err(Refusal::EditorHidden));
        assert_eq!(
            next(Some(&staged()), 1, "gpui-7", false, Ok(()), gate),
            Next::End(Refusal::EditorHidden)
        );
    }
    // A resize asked for after the tap and not drawn yet.
    let mut pending = acked(&shown);
    pending.request(shown.cols, shown.rows + 1, Some((shown.cols, shown.rows)));
    let resizing = Surface {
        grid: Some(&pending),
        ..surface
    };
    let gate = admit_steer_enter(
        &working,
        &resizing,
        &steer.boot,
        "w1:p1",
        steer.agent.as_deref(),
        &steer.text,
        &enter_phone(Some(&steer_view(&shown)), &steer),
    );
    assert_eq!(gate, Err(Refusal::Resizing));
}

#[test]
fn one_tap_stages_one_steer_and_its_enter_goes_once() {
    let mut steers = Steers::default();
    steers.stage(target(), staged());
    // A second tap, from Chat or Terminal alike, finds the target taken.
    assert!(steers.takes(&target(), 1));
    let mut other = staged();
    other.receipt = "gpui-9".into();
    steers.stage(target(), other);
    assert_eq!(steers.get(&target()), Some(&staged()));

    let gate = enter_gate(AgentStatus::Working, STEER_SHOWN);
    assert_eq!(
        next(steers.get(&target()), 1, "gpui-7", false, Ok(()), gate),
        Next::Enter
    );
    assert_eq!(steers.end(&target()), Some(staged()));
    // The same Enter asked again, as after a retry: nothing left to press.
    assert_eq!(
        next(steers.get(&target()), 1, "gpui-7", false, Ok(()), gate),
        Next::End(Refusal::SteerExpired)
    );
}

#[test]
fn a_steer_from_a_lost_link_frees_its_target_after_a_reconnect() {
    let mut steers = Steers::default();
    steers.stage(target(), staged());
    assert!(!steers.takes(&target(), 2));
    assert_eq!(steers.get(&target()), None);
    // Gone for good: the old link's Enter step finds nothing to press.
    assert!(!steers.takes(&target(), 1));
}
