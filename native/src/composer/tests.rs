use super::*;
use crate::render::{Cursor, Pane, Rect, Run, flags};
use crate::theme::Theme;
use crate::vt::tests::cell;
use herdr_client::protocol::{
    CellData, CursorState, FrameData, PaneSurfaceFrame, PaneSurfacePane, SurfaceRect,
};

pub(crate) fn rows(screen: &str) -> Vec<String> {
    screen
        .lines()
        .map(|line| line.trim_end().to_owned())
        .collect()
}

// Rows read from OMP 18.18.3 in an isolated Herdr session.
pub(crate) const EMPTY: &str = "\
 Connected to MCP servers: pg, redis.

╭── π  dia-proxy/claude-opus-5-5  bellterm-lab/work ────8%──╮
╰─                                                          ─╯

";

pub(crate) const ONE_LINE: &str = "\
╭── π  dia-proxy/claude-opus-5-5  bellterm-lab/work ────8%──╮
╰─ draft line one                                           ─╯
";

pub(crate) const MULTILINE: &str = "\
╭── π  dia-proxy/claude-opus-5-5  bellterm-lab/work ────8%──╮
│  probe line one
│  probe line two
╰─ probe line three
";

// The last row emptied while earlier draft rows remain.
const EMPTY_LAST_OF_MANY: &str = "\
╭── π  dia-proxy/claude-opus-5-5  bellterm-lab/work ────8%──╮
│  probe line one
╰─
";

// v18.12 drew a right-aligned hint in the empty box.
const HINT: &str = "\
╭── π > Haiku 4.5 > project ▶──5%──────╮
╰─                                    ⇧⇥ to change thinking effort
";

// The model picker's frame also ends in `╰──`.
const PICKER: &str = "\
│   ○ github-copilot         │   openrouter/mistralai/codestral-2508
├────────────────────────────┴─────────────
│ ⏎/→ models · ↑/↓ providers · type to search · ⎋ close
╰──────────────────────────────────────────╯
";

// A list drawn under the box: Enter would pick from it.
const LIST_BELOW: &str = "\
╭── π  model  work ──╮
╰─ /mo               ─╯
  /model   Select a model
";

// Rows read from OMP 18.18.6 in an isolated tmux session, 40 columns: the
// right-aligned hint sits 6 cells in, closer than in a wide box.
const NARROW_HINT: &str = "\
╭── 󰵗   no-model   …p-cwd ────────╮
╰─      󰘶 󰌒 to change thinking effort ─╯
";

// The same width with a draft on the last row.
const NARROW_DRAFT: &str = "\
╭── 󰵗   no-model   …p-cwd ────────╮
╰─ abc                                ─╯
";

// OMP 18.18.6 with `tui.imeSafeCursor`, 60 columns: the cursor row and the
// bottom border are separate rows.
const IME_EMPTY: &str = "\
╭── 󰵗   no-model   …llterm-lab/omp-cwd   8.3K/? 󰁨…───╮
│
╰──────────────────────────────────────────────────────────╯
";

const IME_DRAFT: &str = "\
╭── 󰵗   no-model   …llterm-lab/omp-cwd   8.3K/? 󰁨…───╮
│  abc
╰──────────────────────────────────────────────────────────╯
";

#[test]
fn omp_editor_reads_narrow_and_ime_safe_empty_boxes_as_empty() {
    assert_eq!(omp_editor(&rows(NARROW_HINT)), Ok(Editor::Empty));
    assert_eq!(omp_box_text(&rows(NARROW_HINT)).as_deref(), Some(""));
    assert_eq!(omp_editor(&rows(NARROW_DRAFT)), Ok(Editor::Draft));
    assert_eq!(omp_box_text(&rows(NARROW_DRAFT)).as_deref(), Some("abc"));
    assert_eq!(omp_editor(&rows(IME_EMPTY)), Ok(Editor::Empty));
    assert_eq!(omp_box_text(&rows(IME_EMPTY)).as_deref(), Some(""));
    assert_eq!(omp_editor(&rows(IME_DRAFT)), Ok(Editor::Draft));
    assert_eq!(omp_box_text(&rows(IME_DRAFT)).as_deref(), Some("abc"));
}

#[test]
fn omp_editor_reads_only_the_exact_empty_shape_as_empty() {
    assert_eq!(omp_editor(&rows(EMPTY)), Ok(Editor::Empty));
    assert_eq!(omp_editor(&rows(HINT)), Ok(Editor::Empty));
    assert_eq!(omp_editor(&rows(ONE_LINE)), Ok(Editor::Draft));
    assert_eq!(omp_editor(&rows(MULTILINE)), Ok(Editor::Draft));
    assert_eq!(omp_editor(&rows(EMPTY_LAST_OF_MANY)), Ok(Editor::Draft));
}

// A box taller than the pane: its rows reach the top.
const TALL: &str = "\
│  probe line one
│  probe line two
╰─ probe line three
";

// A row drawn across the box between its top border and bottom.
const COVERED: &str = "\
╭── π  model  work ──╮
│  probe line one
 ↑/↓ select · ⏎ confirm
╰─ probe line two   ─╯
";

#[test]
fn omp_editor_names_why_it_cannot_read_the_box() {
    assert_eq!(omp_editor(&rows(LIST_BELOW)), Err(Unseen::ListBelow));
    assert_eq!(omp_editor(&rows(PICKER)), Err(Unseen::TopMissing));
    assert_eq!(omp_editor(&rows(TALL)), Err(Unseen::TopMissing));
    assert_eq!(omp_editor(&rows(COVERED)), Err(Unseen::Covered));
    assert_eq!(omp_editor(&rows("$ ls\nfile\n$")), Err(Unseen::None));
    // Blank alone is not a resize: only a size that disagrees says so.
    assert_eq!(omp_editor(&rows("\n  \n")), Err(Unseen::None));
    assert_eq!(omp_editor(&[]), Err(Unseen::None));
}

#[test]
fn send_refuses_an_unread_box_with_its_measured_cause() {
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    // Padded to Send's minimum height, the viewport Herdr reports.
    let tall = |screen: &str| format!("{}{screen}", "\n".repeat(20));
    let height = |screen: &str| u64::try_from(rows(screen).len()).unwrap();
    for (screen, reason) in [
        (tall(LIST_BELOW), Refusal::EditorListBelow),
        (
            format!("{}{TALL}", "│  more\n".repeat(20)),
            Refusal::EditorTopMissing,
        ),
        (tall(COVERED), Refusal::EditorCovered),
        (tall("$ ls\nfile\n$"), Refusal::EditorNone),
        (tall("  \n"), Refusal::EditorNone),
    ] {
        let shown = frame("w1:p1", &screen);
        let metrics = scroll(false, height(&screen));
        let surface = Surface {
            frame: Some(&shown),
            scroll: Some(&metrics),
            ..Surface::default()
        };
        assert_eq!(
            check(&idle, &surface, "w1:p1", &unseen()).send.reason,
            Some(reason),
            "{screen}"
        );
        // Herdr's viewport disagrees with the pane: a resize is being drawn.
        let resizing = scroll(false, height(&screen) + 3);
        let surface = Surface {
            scroll: Some(&resizing),
            ..surface
        };
        assert_eq!(
            check(&idle, &surface, "w1:p1", &unseen()).send.reason,
            Some(Refusal::EditorResizing)
        );
    }
}

/// RZ-u-2: another client typed into or focused the pane, so Herdr lays it
/// out for that client's size; this phone's grid is drawn and nothing it
/// asked for is pending. The phone's pane is the top of a taller terminal
/// whose box is below it. Send stays refused, and says why, not "resizing".
#[test]
fn a_pane_sized_for_another_client_is_not_a_resize() {
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    // The top rows of OMP's transcript; its box is off the bottom of the crop.
    let screen = "output one\noutput two\noutput three\n".repeat(5);
    let shown = frame("w1:p1", &screen);
    let pty = scroll(false, u64::try_from(rows(&screen).len()).unwrap() + 20);
    let drawn = acked(&shown);
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&pty),
        grid: Some(&drawn),
        ..Surface::default()
    };
    let seen = check(&idle, &surface, "w1:p1", &unseen());
    assert_eq!(seen.send.reason, Some(Refusal::PaneSizedElsewhere));
    assert_eq!(seen.insert.reason, Some(Refusal::PaneSizedElsewhere));
    // A resize this phone asked for and Herdr has not drawn yet is a resize.
    let mut asked = drawn;
    asked.request(shown.cols, shown.rows + 1, Some((shown.cols, shown.rows)));
    let pending = Surface {
        grid: Some(&asked),
        ..surface
    };
    assert_eq!(
        check(&idle, &pending, "w1:p1", &unseen()).send.reason,
        Some(Refusal::EditorResizing)
    );
}

// Rows read from OMP 18.18.6 in an isolated tmux session, 45 columns, no
// model: a hook's `ctx.ui.askDialog` opened over a draft "abc", which was
// then cleared. The ask stays mounted right on top of an empty box.
pub(crate) const ASK_OVER_EMPTY: &str = "\
─────────────────────────────────────────────

╭─ Ask ─────────────────────────────────────╮
│ Which option should the probe use?        │
├───────────────────────────────────────────┤
│   Alpha (Recommended)                   │
│    Beta                                  │
│    Other (type your own)                 │
│                                           │
│                                           │
│                                           │
├───────────────────────────────────────────┤
│ 󰌑 select · n note · ↑/↓ move · 󱊷 cancel … │
╰───────────────────────────────────────────╯
╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─                                         ─╯
";

// The same ask before the draft was cleared.
const ASK_OVER_DRAFT: &str = "\
─────────────────────────────────────────────

╭─ Ask ─────────────────────────────────────╮
│ Which option should the probe use?        │
├───────────────────────────────────────────┤
│   Alpha (Recommended)                   │
│    Beta                                  │
│    Other (type your own)                 │
│                                           │
│                                           │
│                                           │
├───────────────────────────────────────────┤
│ ↑/↓ answer · typing edits your prompt · … │
╰───────────────────────────────────────────╯
╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─ abc                                     ─╯
";

// A hook's `ctx.ui.select`: the dialog replaces the box.
const SELECTOR: &str = "\
 Then use /model to select a model.

╭─ Probe selector ──────────────────────────╮
│                                           │
│                                           │
│   Alpha                                  │
│    Beta                                   │
│    Gamma                                  │
│                                           │
│ ↑/↓ navigate  󰌑 select  󱊷 cancel          │
│                                           │
╰───────────────────────────────────────────╯

";

// The ask over the empty box in a 10-row pane: its top is cut off.
const ASK_CROPPED: &str = "\
│    Beta                                  │
│    Other (type your own)                 │
│                                           │
│                                           │
│                                           │
├───────────────────────────────────────────┤
│ 󰌑 select · n note · ↑/↓ move · 󱊷 cancel … │
╰───────────────────────────────────────────╯
╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─                                         ─╯
";

// `!printf` of a dialog's frame: OMP indents the output and leaves its
// gap above the box, so it is transcript, not a dialog.
const FRAME_IN_TRANSCRIPT: &str = "\
 ╭─ Ask ──────╮
 │ Pick one   │
 ╰────────────╯

─────────────────────────────────────────────



╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─                                         ─╯
";

// The ask answered (Escape): the box alone, with its hint.
const ASK_CLOSED: &str = "\
─────────────────────────────────────────────

╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─           󰘶 󰌒 to change thinking effort ─╯
";

// Hook `ctx.ui.approve` from the same sandbox: OMP's tool approval, mounted
// over the box like the ask, titled with the call it asks about.
const APPROVAL: &str = "\
─────────────────────────────────────────────

╭─ Run probe command: printf probe ─────────╮
│                                           │
│  Approve                                 │
│   Allow for this session                  │
│   Deny                                    │
├───────────────────────────────────────────┤
│ ↑/↓ choose · 󰌑 select · Ctrl+󰌑/Ctrl+Q ap… │
╰───────────────────────────────────────────╯
╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─           󰘶 󰌒 to change thinking effort ─╯
";

// Hook `ctx.ui.formDialog` from the same sandbox: an HTML form, over the box.
const FORM: &str = "\
─────────────────────────────────────────────

╭─ Ask ─────────────────────────────────────╮
│  Probe name                              │
│   │                                       │
│                                           │
│    Probe flag                            │
│                                           │
│   Submit                                  │
├───────────────────────────────────────────┤
│ 󰌑 next · ↑/↓ move · 󰌒 next field · 󱊷 can… │
╰───────────────────────────────────────────╯
╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─                                         ─╯
";

// pi-tui's `OverlayPanel` with no title (`topBorder` draws a plain rule):
// drawn from that source, not captured. Not a known dialog, but it may be one.
const UNTITLED_PANEL: &str = "\
─────────────────────────────────────────────

╭───────────────────────────────────────────╮
│ Something to choose                       │
├───────────────────────────────────────────┤
│ ↑/↓ move                                  │
╰───────────────────────────────────────────╯
╭── 󰵗   no-model   …p-cwd ─────────────╮
╰─                                         ─╯



";

#[test]
fn omp_editor_sees_a_dialog_only_in_its_whole_frame() {
    assert_eq!(omp_editor(&rows(ASK_OVER_EMPTY)), Err(Unseen::DialogOpen));
    assert_eq!(omp_editor(&rows(ASK_OVER_DRAFT)), Err(Unseen::DialogOpen));
    assert_eq!(omp_editor(&rows(SELECTOR)), Err(Unseen::DialogOpen));
    assert_eq!(omp_editor(&rows(APPROVAL)), Err(Unseen::DialogOpen));
    assert_eq!(omp_editor(&rows(FORM)), Err(Unseen::DialogOpen));
    assert_eq!(omp_editor(&rows(UNTITLED_PANEL)), Err(Unseen::Framed));
    assert_eq!(omp_editor(&rows(ASK_CROPPED)), Err(Unseen::Framed));
    assert_eq!(omp_editor(&rows(FRAME_IN_TRANSCRIPT)), Ok(Editor::Empty));
    assert_eq!(omp_editor(&rows(ASK_CLOSED)), Ok(Editor::Empty));
    // Steer Enter reads the box through the same parser.
    assert_eq!(omp_box_text(&rows(ASK_OVER_DRAFT)), None);
}

#[test]
fn nothing_goes_into_an_open_dialog_whatever_the_status_says() {
    let height = |screen: &str| u64::try_from(rows(screen).len()).unwrap();
    let statuses = [
        AgentStatus::Idle,
        AgentStatus::Done,
        AgentStatus::Working,
        AgentStatus::Blocked,
    ];
    for (screen, reason) in [
        (ASK_OVER_EMPTY, Refusal::EditorDialogOpen),
        (ASK_OVER_DRAFT, Refusal::EditorDialogOpen),
        (SELECTOR, Refusal::EditorDialogOpen),
        (APPROVAL, Refusal::EditorDialogOpen),
        (FORM, Refusal::EditorDialogOpen),
        (UNTITLED_PANEL, Refusal::EditorUnrecognized),
        // It may be a dialog: refused, without claiming one.
        (ASK_CROPPED, Refusal::EditorUnrecognized),
    ] {
        let shown = frame("w1:p1", screen);
        let metrics = scroll(false, height(screen));
        let surface = Surface {
            frame: Some(&shown),
            scroll: Some(&metrics),
            ..Surface::default()
        };
        for status in statuses {
            let omp = registry(Some("omp"), Some(status));
            let checked = check(&omp, &surface, "w1:p1", &unseen());
            assert_eq!(checked.insert.reason, Some(reason), "{status:?}\n{screen}");
            assert_eq!(checked.send.reason, Some(reason), "{status:?}\n{screen}");
            assert_eq!(checked.steer.reason, Some(reason), "{status:?}\n{screen}");
            // Delivery admission decides again, for insert and for Send.
            for submit in [false, true] {
                assert_eq!(
                    admit(&omp, &surface, &request(submit)),
                    Err(reason),
                    "{status:?} submit={submit}\n{screen}"
                );
            }
        }
    }
    // A redraw in progress does not hide a whole dialog.
    let shown = frame("w1:p1", ASK_OVER_EMPTY);
    let resizing = scroll(false, height(ASK_OVER_EMPTY) + 3);
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&resizing),
        ..Surface::default()
    };
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    assert_eq!(
        check(&idle, &surface, "w1:p1", &unseen()).send.reason,
        Some(Refusal::EditorDialogOpen)
    );
}

#[test]
fn steer_enter_refuses_a_dialog_over_the_staged_text() {
    let shown = frame("w1:p1", ASK_OVER_DRAFT);
    let metrics = scroll(false, u64::try_from(rows(ASK_OVER_DRAFT).len()).unwrap());
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&metrics),
        ..Surface::default()
    };
    let working = registry(Some("omp"), Some(AgentStatus::Working));
    assert_eq!(
        admit_steer_enter(
            &working,
            &surface,
            "boot-1",
            "w1:p1",
            Some("omp"),
            "abc",
            &unseen()
        ),
        Err(Refusal::EditorDialogOpen)
    );
}

#[test]
fn send_goes_once_the_dialog_closes_and_past_framed_output() {
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    // Padded to Send's minimum height, the viewport Herdr reports.
    for screen in [ASK_CLOSED, FRAME_IN_TRANSCRIPT] {
        let screen = format!("{}{screen}", "\n".repeat(12));
        let shown = frame("w1:p1", &screen);
        let metrics = scroll(false, u64::try_from(rows(&screen).len()).unwrap());
        let grid = acked(&shown);
        let surface = Surface {
            frame: Some(&shown),
            scroll: Some(&metrics),
            grid: Some(&grid),
            ..Surface::default()
        };
        assert_eq!(
            admit(&idle, &surface, &seen(&shown, request(true))),
            Ok(()),
            "{screen}"
        );
    }
}

pub(crate) fn frame(pane: &str, screen: &str) -> RenderFrame {
    let lines = rows(screen);
    let runs = lines
        .iter()
        .enumerate()
        .filter(|(_, line)| !line.is_empty())
        .map(|(row, line)| Run {
            row: u16::try_from(row).unwrap(),
            col: 0,
            cells: u16::try_from(line.chars().count()).unwrap(),
            flags: if line.is_ascii() { flags::ASCII } else { 0 },
            fg: 0,
            bg: 0,
            text: line.clone(),
        })
        .collect();
    // The cursor stands on the last row drawn: OMP's `╰─` row, or a prompt.
    let cursor_row = lines.iter().rposition(|line| !line.is_empty()).unwrap_or(0);
    RenderFrame {
        cols: 80,
        rows: u16::try_from(lines.len()).unwrap(),
        cursor: Cursor {
            x: 3,
            y: u16::try_from(cursor_row).unwrap(),
            visible: true,
            ..Cursor::default()
        },
        panes: vec![Pane {
            id: pane.into(),
            rect: Rect {
                x: 0,
                y: 0,
                width: 80,
                height: u16::try_from(lines.len()).unwrap(),
            },
            focused: true,
            mouse_reporting: false,
            alternate_screen: false,
        }],
        runs,
        ..RenderFrame::default()
    }
}

pub(crate) fn registry(agent: Option<&str>, status: Option<AgentStatus>) -> Registry {
    Registry {
        boot: Some("boot-1".into()),
        panes: HashMap::from([(
            "w1:p1".to_owned(),
            PaneFacts {
                agent: agent.map(str::to_owned),
                status,
            },
        )]),
    }
}

fn request(submit: bool) -> Request {
    Request {
        node: "n1".into(),
        session: "lab".into(),
        boot: "boot-1".into(),
        pane: "w1:p1".into(),
        agent: Some("omp".into()),
        instance: None,
        text: "first line\nsecond line".into(),
        submit,
        chat: false,
        viewport: None,
        attachments: Vec::new(),
    }
}

/// Pixels per cell in the test viewports.
pub(crate) const CELL_W: f32 = 10.0;
pub(crate) const CELL_H: f32 = 20.0;

/// The grid of `frame`, asked for and drawn.
pub(crate) fn acked(frame: &RenderFrame) -> Grid {
    let mut grid = Grid::opened(frame.cols, frame.rows);
    grid.shown(frame.cols, frame.rows);
    grid
}

/// `frame` drawn whole on the phone, for w1:p1 of n1/lab on boot-1.
pub(crate) fn whole(frame: &RenderFrame) -> Viewport {
    Viewport {
        node: "n1".into(),
        session: "lab".into(),
        boot: Some("boot-1".into()),
        pane: "w1:p1".into(),
        instance: None,
        frame_rows: frame.rows,
        frame_cols: frame.cols,
        grid_rows: frame.rows,
        grid_cols: frame.cols,
        first: 0,
        last: frame.rows - 1,
        rect: visible::PxRect {
            x: 0,
            y: 0,
            width: (f32::from(frame.cols) * CELL_W) as i32,
            height: (f32::from(frame.rows) * CELL_H) as i32,
        },
        offset_y_px: 0.0,
        hidden_rows: 0,
        clip_bottom_px: 0,
        cell_width_px: CELL_W,
        cell_height_px: CELL_H,
    }
}

/// `request` as tapped with `frame` drawn whole.
pub(crate) fn seen(frame: &RenderFrame, mut request: Request) -> Request {
    request.viewport = Some(whole(frame));
    request
}

/// A check's phone for w1:p1 of lab on boot-1, with `viewport`.
pub(crate) fn phone(viewport: Option<&Viewport>) -> Phone<'_> {
    Phone {
        viewport,
        bind: Bind {
            node: None,
            session: "lab",
            boot: Some("boot-1"),
            pane: "w1:p1",
            instance: None,
        },
    }
}

/// No viewport: the phone drew nothing for the target.
pub(crate) fn unseen() -> Phone<'static> {
    phone(None)
}

/// Scroll metrics for w1:p1: at the bottom, `rows` tall.
pub(crate) fn scroll(scrolled: bool, rows: u64) -> HashMap<String, PaneScroll> {
    HashMap::from([("w1:p1".to_owned(), PaneScroll { scrolled, rows })])
}

#[test]
fn send_needs_idle_omp_with_an_empty_box_on_screen() {
    let empty = frame("w1:p1", EMPTY);
    let roomy = scroll(false, 40);
    let grid = acked(&empty);
    let surface = Surface {
        frame: Some(&empty),
        scroll: Some(&roomy),
        grid: Some(&grid),
        ..Surface::default()
    };
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let request = |submit| seen(&empty, request(submit));
    assert_eq!(admit(&idle, &surface, &request(true)), Ok(()));
    let done = registry(Some("omp"), Some(AgentStatus::Done));
    assert_eq!(admit(&done, &surface, &request(true)), Ok(()));

    let working = registry(Some("omp"), Some(AgentStatus::Working));
    assert_eq!(
        admit(&working, &surface, &request(true)),
        Err(Refusal::AgentWorking)
    );
    // Insert still goes in: OMP keeps typed text while it works.
    assert_eq!(admit(&working, &surface, &request(false)), Ok(()));

    let draft = frame("w1:p1", MULTILINE);
    let surface = Surface {
        frame: Some(&draft),
        scroll: Some(&roomy),
        ..Surface::default()
    };
    assert_eq!(
        admit(&idle, &surface, &request(true)),
        Err(Refusal::RemoteDraft)
    );
}

#[test]
fn blocked_agents_and_popups_take_nothing() {
    let empty = frame("w1:p1", EMPTY);
    let roomy = scroll(false, 40);
    let surface = Surface {
        frame: Some(&empty),
        scroll: Some(&roomy),
        ..Surface::default()
    };
    let blocked = registry(Some("omp"), Some(AgentStatus::Blocked));
    for submit in [false, true] {
        assert_eq!(
            admit(&blocked, &surface, &request(submit)),
            Err(Refusal::AgentBlocked)
        );
    }
    let popup = Surface {
        frame: Some(&empty),
        popup_open: true,
        ..Surface::default()
    };
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    assert_eq!(
        admit(&idle, &popup, &request(false)),
        Err(Refusal::PopupOpen)
    );
}

#[test]
fn a_changed_target_is_refused() {
    let empty = frame("w1:p1", EMPTY);
    let grid = acked(&empty);
    let surface = Surface {
        frame: Some(&empty),
        grid: Some(&grid),
        ..Surface::default()
    };
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let mut restarted = request(false);
    restarted.boot = "boot-0".into();
    assert_eq!(
        admit(&idle, &surface, &restarted),
        Err(Refusal::BootChanged)
    );
    let mut gone = request(false);
    gone.pane = "w1:p9".into();
    assert_eq!(admit(&idle, &surface, &gone), Err(Refusal::PaneMissing));
    // The pane now runs a shell where OMP was.
    let shell = registry(None, None);
    assert_eq!(
        admit(&shell, &surface, &seen(&empty, request(false))),
        Err(Refusal::AgentChanged)
    );
}

#[test]
fn send_is_not_offered_where_it_was_never_verified() {
    let empty = frame("w1:p1", EMPTY);
    let grid = acked(&empty);
    let surface = Surface {
        frame: Some(&empty),
        grid: Some(&grid),
        ..Surface::default()
    };
    let shell = registry(None, None);
    let mut to_shell = seen(&empty, request(true));
    to_shell.agent = None;
    assert_eq!(
        admit(&shell, &surface, &to_shell),
        Err(Refusal::UnsupportedProgram)
    );
    to_shell.submit = false;
    assert_eq!(admit(&shell, &surface, &to_shell), Ok(()));

    // OMP in a tab not on screen: its box cannot be read.
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let hidden = Surface::default();
    assert_eq!(
        admit(&idle, &hidden, &request(true)),
        Err(Refusal::NotVisible)
    );
}

#[test]
fn a_scrolled_back_omp_takes_no_text_at_all() {
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let working = registry(Some("omp"), Some(AgentStatus::Working));
    let back = scroll(true, 40);
    // Scrollback is never read as the box: neither an old empty box nor
    // an old ask in it decides anything.
    for screen in [EMPTY, ASK_OVER_EMPTY] {
        let shown = frame("w1:p1", screen);
        let surface = Surface {
            frame: Some(&shown),
            scroll: Some(&back),
            ..Surface::default()
        };
        for omp in [&idle, &working] {
            let checked = check(omp, &surface, "w1:p1", &unseen());
            assert_eq!(checked.insert.reason, Some(Refusal::Scrolled), "{screen}");
            assert_eq!(checked.send.reason, Some(Refusal::Scrolled), "{screen}");
            assert_eq!(checked.steer.reason, Some(Refusal::Scrolled), "{screen}");
            for submit in [false, true] {
                assert_eq!(
                    admit(omp, &surface, &request(submit)),
                    Err(Refusal::Scrolled),
                    "submit={submit}\n{screen}"
                );
            }
        }
        assert_eq!(
            admit_steer_enter(
                &working,
                &surface,
                "boot-1",
                "w1:p1",
                Some("omp"),
                "abc",
                &unseen()
            ),
            Err(Refusal::Scrolled)
        );
    }
    // Without Herdr's scroll position the rows may be scrollback too.
    let shown = frame("w1:p1", EMPTY);
    let unmeasured = Surface {
        frame: Some(&shown),
        ..Surface::default()
    };
    for submit in [false, true] {
        assert_eq!(
            admit(&idle, &unmeasured, &request(submit)),
            Err(Refusal::NotVisible)
        );
    }
    // Not on screen at all: nothing to verify the box with.
    assert_eq!(
        admit(&idle, &Surface::default(), &request(false)),
        Err(Refusal::NotVisible)
    );
}

#[test]
fn omp_takes_text_only_at_the_live_bottom_and_then_by_status_and_box() {
    use AgentStatus::{Blocked, Idle, Working};
    let live = scroll(false, 40);
    let back = scroll(true, 40);
    for (screen, draft) in [(EMPTY, false), (MULTILINE, true)] {
        let shown = frame("w1:p1", screen);
        let grid = acked(&shown);
        let viewport = whole(&shown);
        for status in [Idle, Working, Blocked] {
            let omp = registry(Some("omp"), Some(status));
            let gates = |surface: &Surface<'_>| {
                let checked = check(&omp, surface, "w1:p1", &phone(Some(&viewport)));
                // Delivery admission decides as the check does.
                for (submit, gate) in [(false, &checked.insert), (true, &checked.send)] {
                    assert_eq!(
                        admit(&omp, surface, &seen(&shown, request(submit))).err(),
                        gate.reason,
                        "{status:?} submit={submit}\n{screen}"
                    );
                }
                (
                    checked.insert.reason,
                    checked.send.reason,
                    checked.steer.reason,
                )
            };
            let all = |reason| (Some(reason), Some(reason), Some(reason));
            let unknown = Surface {
                frame: Some(&shown),
                ..Surface::default()
            };
            assert_eq!(gates(&unknown), all(Refusal::NotVisible));
            let off_screen = Surface {
                scroll: Some(&live),
                ..Surface::default()
            };
            assert_eq!(gates(&off_screen), all(Refusal::NotVisible));
            let scrolled = Surface {
                frame: Some(&shown),
                scroll: Some(&back),
                ..Surface::default()
            };
            assert_eq!(gates(&scrolled), all(Refusal::Scrolled));
            let at_bottom = Surface {
                frame: Some(&shown),
                scroll: Some(&live),
                grid: Some(&grid),
                ..Surface::default()
            };
            let box_reason = draft.then_some(Refusal::RemoteDraft);
            let expected = match status {
                Idle => (None, box_reason, Some(Refusal::AgentIdle)),
                Working => (None, Some(Refusal::AgentWorking), box_reason),
                _ => all(Refusal::AgentBlocked),
            };
            assert_eq!(gates(&at_bottom), expected, "{status:?}\n{screen}");
        }
    }
}

#[test]
fn a_short_terminal_cannot_prove_the_box_empty() {
    // OMP at 7 rows shows only the cursor line of a draft "abc\n": the
    // box looks empty while "abc" waits above it.
    let looks_empty = frame("w1:p1", EMPTY);
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let metrics = scroll(false, MIN_SEND_ROWS - 1);
    let grid = acked(&looks_empty);
    let surface = Surface {
        frame: Some(&looks_empty),
        scroll: Some(&metrics),
        grid: Some(&grid),
        ..Surface::default()
    };
    let request = |submit| seen(&looks_empty, request(submit));
    assert_eq!(
        admit(&idle, &surface, &request(true)),
        Err(Refusal::TooShort)
    );
    // Insert only adds to whatever is there.
    assert_eq!(admit(&idle, &surface, &request(false)), Ok(()));
}

#[test]
fn text_limits() {
    let surface = Surface::default();
    let shell = registry(None, None);
    let mut blank = request(false);
    blank.agent = None;
    blank.text = " \n\t".into();
    assert_eq!(admit(&shell, &surface, &blank), Err(Refusal::EmptyText));
    blank.text = "x".repeat(MAX_TEXT_BYTES + 1);
    assert_eq!(admit(&shell, &surface, &blank), Err(Refusal::TooLarge));
}

#[test]
fn clean_keeps_lines_and_drops_terminal_controls() {
    assert_eq!(clean("a\r\nb\rc\td\u{1b}[201~e\u{7}"), "a\nb\nc\td[201~e");
}

#[test]
fn a_draft_goes_only_to_the_agent_instance_it_was_written_for() {
    let written = Identity {
        pane_id: "w1:p1".into(),
        terminal_id: "term_1".into(),
        pid: Some(42),
        path: "/home/u/.omp/agent/sessions/x/a.jsonl".into(),
        agent: "omp".into(),
        source: "herdr:omp".into(),
        kind: "path".into(),
    };
    assert_eq!(same_instance(&written, Some(&written.clone())), Ok(()));
    let fresh = Identity {
        pid: Some(43),
        path: "/home/u/.omp/agent/sessions/x/b.jsonl".into(),
        ..written.clone()
    };
    assert_eq!(
        same_instance(&written, Some(&fresh)),
        Err(Refusal::AgentRestarted)
    );
    // The same session resumed by a new process is still another process.
    let resumed = Identity {
        pid: Some(43),
        ..written.clone()
    };
    assert_eq!(
        same_instance(&written, Some(&resumed)),
        Err(Refusal::AgentRestarted)
    );
    assert_eq!(same_instance(&written, None), Err(Refusal::InstanceUnknown));
}

/// How many rows Herdr reports for `screen`: a pane drawn from it.
fn height(screen: &str) -> u64 {
    u64::try_from(rows(screen).len()).unwrap()
}

// Reader fixtures from a real Pi 1.5.2 TUI, captured in an isolated
// tmux session (scratch home `/tmp/pi-obs/home`, scratch cwd
// `/tmp/pi-obs/work`, no model, no prompt): two plain rules frame one blank
// cursor row, and Pi's status footer — the cwd row, then the stats row
// carrying `0.0%/0 (auto)` and the model name — sits under the lower rule.

const PI_EMPTY: &str = "\
─────────────────────────────────────────────

─────────────────────────────────────────────
/tmp/pi-obs/work
0.0%/0 (auto)                                                                            unknown
";

// A draft the user did not write here: text on the cursor row.
const PI_DRAFT: &str = "\
─────────────────────────────────────────────
  leftover draft
─────────────────────────────────────────────
/tmp/pi-obs/work
0.0%/0 (auto)                                                                            unknown
";

// Pi's own autocomplete list under the lower rule: Enter would pick from it.
const PI_LIST_BELOW: &str = "\
─────────────────────────────────────────────

─────────────────────────────────────────────
/model   Select a model
/tmp/pi-obs/work
0.0%/0 (auto)                                                                            unknown
";

// The editor scrolls within itself: Pi replaces its rules with markers,
// and rows of the editor stand outside the pane.
const PI_OVERFLOW: &str = "\
─────────────────────────────── ↑ 2 more ───
  draft above the cursor
─────────────────────────────── ↓ 3 more ───
/tmp/pi-obs/work
0.0%/0 (auto)                                                                            unknown
";

// Only Pi's cwd row where its two-row footer stands: unknown, refused.
const PI_SHORT_FOOTER: &str = "\
─────────────────────────────────────────────

─────────────────────────────────────────────
/tmp/pi-obs/work
";

// Claude Code's editor between two plain rules, prompt row inside.
const CLAUDE_EMPTY: &str = "\
────────────────────────────────────────
❯
────────────────────────────────────────
";

// Claude's dim ghost suggestion behind the prompt: unknown text, so a
// draft by this module's lights — never read as empty, never suppressed.
const CLAUDE_GHOST: &str = "\
────────────────────────────────────────
❯ explain the plan
────────────────────────────────────────
";

const CLAUDE_DRAFT: &str = "\
────────────────────────────────────────
❯ hello
  world
────────────────────────────────────────
";

// A Claude prompt of its own owns input: Enter would answer it.
const CLAUDE_BLOCKER: &str = "\
────────────────────────────────────────
❯  Do you want to proceed? › Yes
────────────────────────────────────────
";

const CLAUDE_LIST_BELOW: &str = "\
────────────────────────────────────────
❯
────────────────────────────────────────
   Tab to replace…
";

// Rules with no prompt row at all: not Claude's empty box.
const CLAUDE_NO_PROMPT: &str = "\
────────────────────────────────────────

────────────────────────────────────────
";

// Real Claude 2.1.322 idle, read with `herdr pane read --raw`,
// ANSI stripped, untrimmed: the one row under its lower rule per shift+tab
// mode (auto, manual, accept edits, plan) at each captured pane width.
// The 30-column rows are cut by the pane; 80 and 144 show them whole.
const CLAUDE_CAPTURED_FOOTERS: [(usize, [&str; 4]); 3] = [
    (
        30,
        [
            "  ⏵⏵ auto mode on (shift+tab",
            "  ⏸ manual mode on · ? for …",
            "  ⏵⏵ accept edits on ",
            "  ⏸ plan mode on (shift+tab ",
        ],
    ),
    (
        80,
        [
            "  ⏵⏵ auto mode on (shift+tab to cycle)",
            "  ⏸ manual mode on · ? for shortcuts",
            "  ⏵⏵ accept edits on (shift+tab to cycle)",
            "  ⏸ plan mode on (shift+tab to cycle)",
        ],
    ),
    (
        144,
        [
            "  ⏵⏵ auto mode on (shift+tab to cycle)",
            "  ⏸ manual mode on · ? for shortcuts",
            "  ⏵⏵ accept edits on (shift+tab to cycle)",
            "  ⏸ plan mode on (shift+tab to cycle)",
        ],
    ),
];

fn claude_captured(cols: usize, prompt: &str, below: &[&str]) -> Vec<String> {
    let rule = "─".repeat(cols);
    [rule.as_str(), prompt, rule.as_str()]
        .into_iter()
        .chain(below.iter().copied())
        .map(str::to_owned)
        .collect()
}

// Not Claude's own footer: a slash-command list over it, more after the
// footer (cut or whole), a cut inside the mode words, an ellipsis after
// the whole row, a mode never captured.
const CLAUDE_UNKNOWN_BELOW: [&[&str]; 7] = [
    &[
        "  /clear    Clear conversation history",
        "  ⏵⏵ auto mode on (shift+tab",
    ],
    &["  ⏵⏵ auto mode on (shift+tab · 3 files"],
    &["  ⏵⏵ auto mode on (shift+tab to cycle) · 3 files"],
    &["  ⏸ plan mode on (shift+tab to cycle)x"],
    &["  ⏵⏵ auto mode o…"],
    &["  ⏸ manual mode on · ? for shortcuts…"],
    &["  ⏵⏵ bypass permissions on (shift+tab to cycle)"],
];

#[test]
fn pi_editor_reads_only_the_captured_shape_as_empty() {
    assert_eq!(pi_editor(&rows(PI_EMPTY)), Ok(Editor::Empty));
    assert_eq!(pi_editor(&rows(PI_DRAFT)), Ok(Editor::Draft));
    // Below the lower rule only Pi's own footer fits: its list, a marker
    // or anything unrecognised refuses with its own cause.
    assert_eq!(pi_editor(&rows(PI_LIST_BELOW)), Err(Unseen::ListBelow));
    assert_eq!(pi_editor(&rows(PI_OVERFLOW)), Err(Unseen::TopMissing));
    assert_eq!(pi_editor(&rows(PI_SHORT_FOOTER)), Err(Unseen::ListBelow));
    assert_eq!(pi_editor(&rows("$ ls\npi")), Err(Unseen::None));
}

#[test]
fn claude_editor_takes_the_bare_prompt_and_fails_closed_on_the_rest() {
    assert_eq!(claude_editor(&rows(CLAUDE_EMPTY)), Ok(Editor::Empty));
    assert_eq!(claude_editor(&rows(CLAUDE_GHOST)), Ok(Editor::Draft));
    assert_eq!(claude_editor(&rows(CLAUDE_DRAFT)), Ok(Editor::Draft));
    assert_eq!(
        claude_editor(&rows(CLAUDE_BLOCKER)),
        Err(Unseen::DialogOpen)
    );
    assert_eq!(
        claude_editor(&rows(CLAUDE_LIST_BELOW)),
        Err(Unseen::ListBelow)
    );
    assert_eq!(claude_editor(&rows(CLAUDE_NO_PROMPT)), Err(Unseen::None));
}

#[test]
fn claude_editor_reads_through_its_captured_mode_footers_only() {
    for (cols, footers) in CLAUDE_CAPTURED_FOOTERS {
        for footer in footers {
            // The captured empty prompt row: `❯`, a no-break space, a space.
            let empty = claude_captured(cols, "❯\u{a0} ", &[footer]);
            assert_eq!(claude_editor(&empty), Ok(Editor::Empty), "{cols}: {footer}");
            let draft = claude_captured(cols, "❯ Qaclaudeturnone", &[footer, ""]);
            assert_eq!(claude_editor(&draft), Ok(Editor::Draft), "{cols}: {footer}");
        }
    }
    for below in CLAUDE_UNKNOWN_BELOW {
        let screen = claude_captured(80, "❯\u{a0} ", below);
        assert_eq!(claude_editor(&screen), Err(Unseen::ListBelow), "{below:?}");
    }
}

fn instance() -> Identity {
    Identity {
        pane_id: "w1:p1".into(),
        terminal_id: "term_1".into(),
        pid: Some(42),
        path: "/home/u/.omp/agent/sessions/x/a.jsonl".into(),
        agent: "omp".into(),
        source: "herdr:omp".into(),
        kind: "path".into(),
    }
}

/// The staged image the photo chip left: bound to the composer's own
/// target (w1:p1 of n1/lab on boot-1) and to the instance here.
fn attachment() -> attach::Attachment {
    attach::Attachment {
        kind: attach::Kind::Image,
        name: "photo.png".into(),
        path: "/home/u/.cache/herdline/attachments/photo.png".into(),
        size: 4096,
        sha256: "a".repeat(64),
        node: "n1".into(),
        session: "lab".into(),
        boot: "boot-1".into(),
        pane: "w1:p1".into(),
        instance: Some(instance()),
    }
}

/// A Chat send carrying only the staged image, as the photo chip made it.
fn chat_with_image() -> Request {
    let mut request = request(true);
    request.chat = true;
    request.text = String::new();
    request.attachments = vec![attachment()];
    request.instance = Some(instance());
    request
}

/// RZ-photo: the photo chip shrinks the Chat window over a hidden
/// terminal until the tap draws nothing of the pane. A chat send still
/// goes on the host's empty editor; the same frame never lets a terminal
/// send through, a draft waiting on the pane stops the chat send, and
/// only the instance the draft was written for signs it.
#[test]
fn a_chat_send_with_an_image_runs_on_the_host_alone() {
    // OMP's empty box at the live bottom, padded to the height Herdr
    // reports. No viewport exists: the phone drew nothing here.
    let screen = format!("{}{EMPTY}", "\n".repeat(20));
    let shown = frame("w1:p1", &screen);
    let metrics = scroll(false, height(&screen));
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&metrics),
        ..Surface::default()
    };
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    assert_eq!(admit(&idle, &surface, &chat_with_image()), Ok(()));
    // The very same frame, from the terminal editor: the phone gate stays
    // on that path, and it says the target is not drawn on the phone.
    let mut terminal = chat_with_image();
    terminal.chat = false;
    assert_eq!(
        admit(&idle, &surface, &terminal),
        Err(Refusal::EditorHidden)
    );
    // A draft of the user's waiting on the pane still blocks the chat.
    let screen = format!("{}{ONE_LINE}", "\n".repeat(20));
    let shown = frame("w1:p1", &screen);
    let metrics = scroll(false, height(&screen));
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&metrics),
        ..Surface::default()
    };
    assert_eq!(
        admit(&idle, &surface, &chat_with_image()),
        Err(Refusal::RemoteDraft)
    );
    // No instance read: no chat send either.
    let mut alone = request(true);
    alone.chat = true;
    alone.text = "hello".into();
    assert_eq!(
        admit(&idle, &surface, &alone),
        Err(Refusal::InstanceUnknown)
    );
    // A terminal pane has no verified submit, chat or not.
    let shell = registry(None, None);
    let mut to_shell = chat_with_image();
    to_shell.agent = None;
    assert_eq!(
        admit(&shell, &surface, &to_shell),
        Err(Refusal::UnsupportedProgram)
    );
}

/// Consumer mistakes this gate can trip on: the agent name disagreeing
/// with the pane, a missing instance, a popup owning input, and a pane
/// that works or asks instead of resting — each fails closed.
#[test]
fn a_chat_send_refuses_the_boundaries_a_consumer_can_trip() {
    let screen = format!("{}{PI_EMPTY}", "\n".repeat(12));
    let mut shown = frame("w1:p1", &screen);
    // Pi's hardware cursor stands on the blank editor row it drew.
    shown.cursor.y = 13;
    let metrics = scroll(false, height(&screen));
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&metrics),
        ..Surface::default()
    };
    let mut send = request(true);
    send.chat = true;
    send.agent = Some("pi".into());
    send.instance = Some(instance());
    send.text = "hello".into();
    let idle = registry(Some("pi"), Some(AgentStatus::Idle));
    assert_eq!(admit(&idle, &surface, &send), Ok(()));
    // The draft was written for another program than the pane runs now.
    let mut wrong = send.clone();
    wrong.agent = Some("omp".into());
    assert_eq!(admit(&idle, &surface, &wrong), Err(Refusal::AgentChanged));
    let mut alone = send.clone();
    alone.instance = None;
    assert_eq!(
        admit(&idle, &surface, &alone),
        Err(Refusal::InstanceUnknown)
    );
    assert_eq!(
        admit(
            &registry(Some("pi"), Some(AgentStatus::Working)),
            &surface,
            &send
        ),
        Err(Refusal::AgentWorking)
    );
    assert_eq!(
        admit(
            &registry(Some("pi"), Some(AgentStatus::Blocked)),
            &surface,
            &send
        ),
        Err(Refusal::AgentBlocked)
    );
    let popup = Surface {
        popup_open: true,
        ..Surface::default()
    };
    assert_eq!(admit(&idle, &popup, &send), Err(Refusal::PopupOpen));
    // Claude's ghost suggestion behind the prompt reads as a draft: the
    // chat send stops there too, whatever the status reports.
    let screen = format!("{}{CLAUDE_GHOST}", "\n".repeat(12));
    let mut shown = frame("w1:p1", &screen);
    shown.cursor.y = 13;
    let metrics = scroll(false, height(&screen));
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&metrics),
        ..Surface::default()
    };
    let mut to_claude = send.clone();
    to_claude.agent = Some("claude".into());
    assert_eq!(
        admit(
            &registry(Some("claude"), Some(AgentStatus::Idle)),
            &surface,
            &to_claude
        ),
        Err(Refusal::RemoteDraft)
    );
}

/// Herdr's REVERSED modifier bit, which it sends as SGR 7.
const REVERSED: u16 = 64;

fn write(grid: &mut FrameData, x: u16, y: u16, text: &str) {
    let start = usize::from(y) * usize::from(grid.width) + usize::from(x);
    for (i, symbol) in text.chars().enumerate() {
        grid.cells[start + i] = cell(&symbol.to_string());
    }
}

fn cell_at(grid: &mut FrameData, x: u16, y: u16) -> &mut CellData {
    let width = usize::from(grid.width);
    &mut grid.cells[usize::from(y) * width + usize::from(x)]
}

/// Claude idle in w1:p1 as Herdr sends it, `cols` by `height`: blank rows,
/// the upper rule, the prompt row (`❯`, a no-break space, then its caret,
/// one SGR 7 space), the lower rule and `below`, with the hardware cursor
/// hidden on the caret. `left` columns of a blank pane, w1:p2, stand
/// before it.
fn claude_grid(cols: u16, height: u16, below: &[&str], left: u16) -> FrameData {
    let width = left + cols;
    let mut grid = FrameData {
        cells: vec![cell(" "); usize::from(width) * usize::from(height)],
        width,
        height,
        cursor: None,
        hyperlinks: vec![],
        graphics: vec![],
    };
    let top = height - 3 - u16::try_from(below.len()).unwrap();
    let rule = "─".repeat(cols.into());
    write(&mut grid, left, top, &rule);
    write(&mut grid, left, top + 1, "❯\u{a0}");
    cell_at(&mut grid, left + 2, top + 1).modifier = REVERSED;
    write(&mut grid, left, top + 2, &rule);
    for (row, text) in (top + 3..).zip(below) {
        write(&mut grid, left, row, text);
    }
    grid.cursor = Some(CursorState {
        x: left + 2,
        y: top + 1,
        visible: false,
        shape: 0,
    });
    grid
}

fn surface_pane(id: &str, x: u16, width: u16, height: u16) -> PaneSurfacePane {
    let rect = || SurfaceRect {
        x,
        y: 0,
        width,
        height,
    };
    PaneSurfacePane {
        pane_id: id.into(),
        content_revision: 1,
        rect: rect(),
        inner_rect: rect(),
        scrollbar_rect: None,
        scroll: None,
        focused: id == "w1:p1",
        mouse_reporting: false,
        sgr_pixel_mouse: false,
        alternate_screen_active: false,
        pixel_width: 0,
        pixel_height: 0,
    }
}

/// `grid` rendered by Ghostty, with w1:p1 from column `left` on.
fn rendered(grid: FrameData, left: u16) -> RenderFrame {
    let (width, height) = (grid.width, grid.height);
    let mut panes = vec![surface_pane("w1:p1", left, width - left, height)];
    if left > 0 {
        panes.push(surface_pane("w1:p2", 0, left, height));
    }
    let surface = PaneSurfaceFrame {
        boot_id: "boot".into(),
        projection_revision: 1,
        surface_revision: 1,
        panes,
        frame: grid,
        splits: vec![],
        popup: None,
        graphics: Default::default(),
    };
    crate::Screen::new(Theme::DARK)
        .unwrap()
        .show(&surface)
        .unwrap()
}

/// Herdr's colour value for an RGB colour.
fn rgb(color: libghostty_vt::style::RgbColor) -> u32 {
    0x0200_0000 | (crate::screen::argb(color) & 0x00ff_ffff)
}

/// Claude hides its hardware cursor and draws an inverse caret. A Chat
/// send goes when the hidden cursor stands on that one plain inverse cell
/// right after `❯` and a no-break space in this pane's box, or when a
/// visible cursor is inside the box, as before. The same box with no
/// inverse run at all, as Herdr 0.9.5 sends it with the colours swapped,
/// says the caret is unseen. Lookalikes, a misplaced or extra caret,
/// another pane's caret, a styled caret, and the unknown footer, menu and
/// dialog cases refuse. Rows and footers are Claude's own at 30x20 and
/// 80x39, rendered through Ghostty from Herdr's cells.
#[test]
fn claude_chat_send_needs_its_caret_or_a_cursor_in_the_box() {
    type Edit = fn(&mut FrameData, u16, u16);
    type Case<'a> = (
        String,
        u16,
        u16,
        Vec<&'a str>,
        u16,
        Edit,
        Result<(), Refusal>,
    );
    let same: Edit = |_, _, _| {};
    let [w30, w80] = [0, 1].map(|i| CLAUDE_CAPTURED_FOOTERS[i].1);
    let auto = [w30[0]];
    let mut rows: Vec<Case<'_>> = Vec::new();
    for (cols, height, footers) in [(30, 20, w30), (80, 39, w80)] {
        for footer in footers {
            rows.push((
                format!("{cols}: {footer}"),
                cols,
                height,
                vec![footer],
                0,
                same,
                Ok(()),
            ));
        }
    }
    let cases: [(&str, Edit, Result<(), Refusal>); 15] = [
        (
            "visible cursor on the caret",
            |g, _, _| g.cursor.as_mut().unwrap().visible = true,
            Ok(()),
        ),
        (
            "swapped colours, not inverse",
            |g, x, y| {
                let caret = cell_at(g, x, y);
                caret.modifier = 0;
                caret.fg = rgb(Theme::DARK.background);
                caret.bg = rgb(Theme::DARK.foreground);
            },
            Err(Refusal::EditorCaretUnseen),
        ),
        (
            "no caret",
            |g, x, y| cell_at(g, x, y).modifier = 0,
            Err(Refusal::EditorCaretUnseen),
        ),
        (
            "no caret, hidden cursor one column on",
            |g, x, y| {
                cell_at(g, x, y).modifier = 0;
                g.cursor.as_mut().unwrap().x = x + 1;
            },
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "second caret",
            |g, x, y| cell_at(g, x + 3, y).modifier = REVERSED,
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "caret two cells wide",
            |g, x, y| cell_at(g, x + 1, y).modifier = REVERSED,
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "caret and cursor one column on",
            |g, x, y| {
                cell_at(g, x, y).modifier = 0;
                cell_at(g, x + 1, y).modifier = REVERSED;
                g.cursor.as_mut().unwrap().x = x + 1;
            },
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "caret and cursor on the footer row",
            |g, x, y| {
                cell_at(g, x, y).modifier = 0;
                cell_at(g, x - 1, y + 2).modifier = REVERSED;
                *g.cursor.as_mut().unwrap() = CursorState {
                    x: x - 1,
                    y: y + 2,
                    visible: false,
                    shape: 0,
                };
            },
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "caret and cursor above the box",
            |g, x, y| {
                cell_at(g, x, y).modifier = 0;
                cell_at(g, x, y - 2).modifier = REVERSED;
                g.cursor.as_mut().unwrap().y = y - 2;
            },
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "bold caret",
            |g, x, y| cell_at(g, x, y).modifier = REVERSED | 1,
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "underlined caret",
            |g, x, y| cell_at(g, x, y).modifier = REVERSED | 8,
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "caret on a red background",
            |g, x, y| cell_at(g, x, y).bg = rgb(Theme::DARK.ansi[1]),
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "text under the caret",
            |g, x, y| cell_at(g, x, y).symbol = "x".into(),
            Err(Refusal::RemoteDraft),
        ),
        (
            "hidden cursor off the caret",
            |g, x, _| g.cursor.as_mut().unwrap().x = x + 3,
            Err(Refusal::EditorUnrecognized),
        ),
        (
            "dialog above the box",
            |g, _, y| write(g, 0, y - 3, "Do you want to proceed?"),
            Err(Refusal::EditorDialogOpen),
        ),
    ];
    for (name, edit, expected) in cases {
        rows.push((name.into(), 30, 20, auto.to_vec(), 0, edit, expected));
    }
    // Another pane's caret beside an empty box with none: the hidden
    // cursor is not in this pane.
    rows.push((
        "caret in the pane beside".into(),
        30,
        20,
        auto.to_vec(),
        30,
        |g, x, y| {
            cell_at(g, x, y).modifier = 0;
            write(g, 0, y, "❯\u{a0}");
            cell_at(g, 2, y).modifier = REVERSED;
            g.cursor.as_mut().unwrap().x = 2;
        },
        Err(Refusal::EditorUnrecognized),
    ));
    for below in [CLAUDE_UNKNOWN_BELOW[0], CLAUDE_UNKNOWN_BELOW[6]] {
        rows.push((
            format!("{below:?}"),
            80,
            39,
            below.to_vec(),
            0,
            same,
            Err(Refusal::EditorListBelow),
        ));
    }
    let idle = registry(Some("claude"), Some(AgentStatus::Idle));
    let mut send = request(true);
    send.chat = true;
    send.agent = Some("claude".into());
    send.instance = Some(instance());
    send.text = "hello".into();
    for (name, cols, height, below, left, edit, expected) in rows {
        let mut grid = claude_grid(cols, height, &below, left);
        let caret = grid
            .cursor
            .as_ref()
            .map(|cursor| (cursor.x, cursor.y))
            .unwrap();
        edit(&mut grid, caret.0, caret.1);
        let shown = rendered(grid, left);
        let metrics = scroll(false, height.into());
        let surface = Surface {
            frame: Some(&shown),
            scroll: Some(&metrics),
            ..Surface::default()
        };
        assert_eq!(admit(&idle, &surface, &send), expected, "{name}");
    }
}
