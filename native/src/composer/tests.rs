use super::*;
use crate::render::{Cursor, Pane, Rect, Run, flags};

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
