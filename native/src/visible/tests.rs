use super::*;
use crate::composer::tests::{
    ASK_OVER_EMPTY, CELL_H, CELL_W, EMPTY, MULTILINE, ONE_LINE, acked, frame, phone, registry,
    scroll, whole,
};
use crate::composer::{Check, MIN_SEND_ROWS, PaneFacts, Registry, Surface, check};
use crate::render::Pane;
use herdr_client::protocol::AgentStatus;
use std::collections::HashMap;

/// What TerminalView reports for `frame` in a view `rows_tall` cells high,
/// with output focus hiding `hidden` rows and `clip` px under the strip.
fn view(frame: &RenderFrame, height_px: f32, hidden: u16, clip: i32) -> Viewport {
    #[allow(clippy::cast_precision_loss)]
    let offset = view_offset(height_px, clip as f32, hidden, CELL_H, frame.rows);
    #[allow(clippy::cast_precision_loss)]
    let drawable = height_px - clip as f32;
    let drawn = drawn_rows(drawable, offset, CELL_H, frame.rows);
    #[allow(clippy::cast_possible_truncation)]
    let height = drawable as i32;
    Viewport {
        first: drawn.as_ref().map_or(1, |rows| *rows.start()),
        last: drawn.as_ref().map_or(0, |rows| *rows.end()),
        rect: PxRect {
            height,
            ..whole(frame).rect
        },
        offset_y_px: offset,
        hidden_rows: hidden,
        clip_bottom_px: clip,
        ..whole(frame)
    }
}

fn rows_px(rows: u16) -> f32 {
    f32::from(rows) * CELL_H
}

/// What TerminalView reports for `frame` in a view `height_px` tall without
/// output focus, its shown cursor kept in the window (A-27P).
fn cursor_view(frame: &RenderFrame, height_px: f32) -> Viewport {
    let cursor = shown_cursor_row(frame);
    let offset = view_offset_at(height_px, 0.0, 0, CELL_H, frame.rows, cursor);
    let drawn = drawn_rows(height_px, offset, CELL_H, frame.rows);
    Viewport {
        first: drawn.as_ref().map_or(1, |rows| *rows.start()),
        last: drawn.as_ref().map_or(0, |rows| *rows.end()),
        offset_y_px: offset,
        ..view(frame, height_px, 0, 0)
    }
}

/// The composer's check of w1:p1 for `agent`, the frame shown live and
/// tall enough for Send, with the grid `grid` and the phone's `viewport`.
fn checked(
    agent: Option<&str>,
    status: Option<AgentStatus>,
    shown: &RenderFrame,
    grid: Option<&Grid>,
    viewport: Option<&Viewport>,
) -> Check {
    let metrics = scroll(false, 40);
    let surface = Surface {
        frame: Some(shown),
        scroll: Some(&metrics),
        grid,
        ..Surface::default()
    };
    check(
        &registry(agent, status),
        &surface,
        "w1:p1",
        &phone(viewport),
    )
}

fn idle_omp(shown: &RenderFrame, viewport: Option<&Viewport>) -> Check {
    checked(
        Some("omp"),
        Some(AgentStatus::Idle),
        shown,
        Some(&acked(shown)),
        viewport,
    )
}

fn shell(shown: &RenderFrame, viewport: Option<&Viewport>) -> Check {
    checked(None, None, shown, Some(&acked(shown)), viewport)
}

/// The gates the host checks pass for idle OMP or a shell (Insert, and Send
/// where verified), and the phone gate itself, refuse with `reason`.
fn all_refuse(check: &Check, reason: Refusal) {
    assert_eq!(check.insert.reason, Some(reason), "insert");
    if check.program == Program::Omp {
        assert_eq!(check.send.reason, Some(reason), "send");
    }
    assert_eq!(check.phone.reason, Some(reason), "phone");
}

#[test]
fn only_wholly_drawn_rows_count() {
    // Ten rows in a view ten rows tall: all of them.
    assert_eq!(drawn_rows(rows_px(10), 0.0, CELL_H, 10), Some(0..=9));
    // Cut by a pixel at either edge: that row is out.
    assert_eq!(drawn_rows(rows_px(10), -1.0, CELL_H, 10), Some(1..=9));
    assert_eq!(drawn_rows(rows_px(10) - 1.0, 0.0, CELL_H, 10), Some(0..=8));
    // Half a row at the top.
    assert_eq!(
        drawn_rows(rows_px(10), -CELL_H / 2.0, CELL_H, 10),
        Some(1..=9)
    );
    // Drawn lower than the view's top: the rows run off its bottom.
    assert_eq!(
        drawn_rows(rows_px(10), 2.0 * CELL_H, CELL_H, 10),
        Some(0..=7)
    );
    // A cell as fonts measure it, not a whole pixel (DrawnRowsTest has it too).
    assert_eq!(drawn_rows(1000.0, 0.0, 37.4, 40), Some(0..=25));
    // A frame shorter than the view.
    assert_eq!(drawn_rows(rows_px(10), 0.0, CELL_H, 4), Some(0..=3));
    // Nothing whole, or no metrics: nothing.
    assert_eq!(drawn_rows(CELL_H - 1.0, 0.0, CELL_H, 10), None);
    assert_eq!(drawn_rows(rows_px(10), 0.0, 0.0, 10), None);
    assert_eq!(drawn_rows(rows_px(10), f32::NAN, CELL_H, 10), None);
}

#[test]
fn the_offset_is_terminal_views() {
    // A held grid two rows taller than the view: its top two rows are cut.
    assert_eq!(view_offset(rows_px(8), 0.0, 0, CELL_H, 10), -2.0 * CELL_H);
    // A grid that fits is drawn from the top.
    assert_eq!(view_offset(rows_px(12), 0.0, 0, CELL_H, 10), 0.0);
    // Output focus: two rows scrolled past the bottom of a view that fits.
    assert_eq!(view_offset(rows_px(10), 0.0, 2, CELL_H, 10), 2.0 * CELL_H);
    // A strip over the bottom row's space, nothing hidden: one row up.
    assert_eq!(view_offset(rows_px(10), CELL_H, 0, CELL_H, 10), -CELL_H);
}

/// `view_offset_at` for ten rows without output focus, `CELL_H` cells.
fn at(height_px: f32, cursor: Option<u16>) -> f32 {
    view_offset_at(height_px, 0.0, 0, CELL_H, 10, cursor)
}

#[test]
fn a_shown_cursor_above_the_window_is_its_top_row() {
    // A held grid two rows taller than the view, the prompt on its top row.
    assert_eq!(at(rows_px(8), Some(0)), 0.0);
    assert_eq!(drawn_rows(rows_px(8), 0.0, CELL_H, 10), Some(0..=7));
    assert_eq!(at(rows_px(8), Some(1)), -CELL_H);
    // Cut by half a row under the anchor: brought whole, by whole rows.
    assert_eq!(at(rows_px(8) + CELL_H / 2.0, Some(1)), -CELL_H);
}

#[test]
fn a_cursor_in_the_window_hidden_or_under_output_focus_keeps_the_offset() {
    // Already whole under the bottom anchor: the anchor stays.
    assert_eq!(at(rows_px(8), Some(2)), -2.0 * CELL_H);
    assert_eq!(at(rows_px(8), Some(9)), -2.0 * CELL_H);
    assert_eq!(at(rows_px(8) + CELL_H / 2.0, Some(2)), -1.5 * CELL_H);
    // No cursor shown: the anchor.
    assert_eq!(at(rows_px(8), None), -2.0 * CELL_H);
    // A frame no taller than the view: from the top, wherever the cursor.
    assert_eq!(at(rows_px(12), Some(0)), 0.0);
    assert_eq!(at(rows_px(10), Some(9)), 0.0);
    // Output focus scrolled the cursor's row away: never pulled back.
    let focus = |hidden, clip| view_offset_at(rows_px(10), clip, hidden, CELL_H, 10, Some(9));
    assert_eq!(focus(2, 0.0), 2.0 * CELL_H);
    assert_eq!(focus(0, CELL_H), -CELL_H);
}

#[test]
fn a_prompt_on_a_held_grids_top_row_is_drawn_and_takes_text() {
    let mut prompt = frame("w1:p1", "$ ls\nfile\n$");
    prompt.cursor.y = 0;
    // One row tall: TerminalView draws the cursor's row, and the gate agrees.
    let kept = cursor_view(&prompt, rows_px(1));
    assert_eq!((kept.first, kept.last), (0, 0));
    assert!(shell(&prompt, Some(&kept)).insert.ok);
    // The bottom anchor is no longer what the view draws for this frame.
    let anchored = view(&prompt, rows_px(1), 0, 0);
    assert_eq!(
        shell(&prompt, Some(&anchored)).insert.reason,
        Some(Refusal::TargetNotVisible)
    );
    // A hidden cursor keeps the anchor: nothing to keep in view.
    let mut hidden = prompt.clone();
    hidden.cursor.visible = false;
    assert_eq!(shown_cursor_row(&hidden), None);
    assert_eq!(cursor_view(&hidden, rows_px(1)), view(&hidden, rows_px(1), 0, 0));
}

/// Ten rows, 20 columns: pane `focused`'s inner rect on rows 1-3 over the
/// other's on rows 5-8, the surface cursor at column 2 of `cursor_row`.
fn stacked(cursor_row: u16, visible: bool, focused: Option<usize>) -> RenderFrame {
    let rects = [
        Rect {
            x: 1,
            y: 1,
            width: 18,
            height: 3,
        },
        Rect {
            x: 1,
            y: 5,
            width: 18,
            height: 4,
        },
    ];
    let mut shown = RenderFrame {
        cols: 20,
        rows: 10,
        ..RenderFrame::default()
    };
    shown.cursor.x = 2;
    shown.cursor.y = cursor_row;
    shown.cursor.visible = visible;
    shown.panes = rects
        .iter()
        .enumerate()
        .map(|(i, &rect)| Pane {
            id: format!("p{i}"),
            rect,
            focused: Some(i) == focused,
            mouse_reporting: false,
            alternate_screen: false,
        })
        .collect();
    shown
}

/// The anchor row for `frame` in a view `rows` cells tall, no output focus.
fn anchor(frame: &RenderFrame, rows: u16) -> Option<u16> {
    shown_anchor_row(frame, rows_px(rows), 0.0, 0, CELL_H)
}

/// The offset TerminalView draws `frame` at in a view `height_px` tall.
fn drawn_at(frame: &RenderFrame, height_px: f32, hidden: u16, clip: f32) -> f32 {
    let row = shown_anchor_row(frame, height_px, clip, hidden, CELL_H);
    view_offset_at(height_px, clip, hidden, CELL_H, frame.rows, row)
}

#[test]
fn a_focused_pane_above_the_window_is_drawn_from_its_top() {
    // Four rows tall: the cursor in the other pane keeps rows 6-9, none of
    // the focused pane's. Its top inner row is kept instead (QF9-U2).
    let shown = stacked(7, true, Some(0));
    assert_eq!(anchor(&shown, 4), Some(1));
    let offset = drawn_at(&shown, rows_px(4), 0, 0.0);
    assert_eq!(offset, -CELL_H);
    assert_eq!(drawn_rows(rows_px(4), offset, CELL_H, 10), Some(1..=4));
    // No cursor shown: the bottom anchor drew rows 6-9 alone.
    assert_eq!(anchor(&stacked(7, false, Some(0)), 4), Some(1));
    // Taller than the view (limit): its top, the rest cut below.
    let short = drawn_at(&shown, rows_px(2), 0, 0.0);
    assert_eq!(drawn_rows(rows_px(2), short, CELL_H, 10), Some(1..=2));
}

#[test]
fn a_cursor_inside_the_focused_pane_wins() {
    let shown = stacked(2, true, Some(0));
    assert_eq!(anchor(&shown, 4), Some(2));
    assert_eq!(drawn_at(&shown, rows_px(4), 0, 0.0), -2.0 * CELL_H);
    assert_eq!(anchor(&stacked(3, true, Some(0)), 2), Some(3));
}

#[test]
fn a_focused_pane_already_drawn_keeps_the_window() {
    // Eight rows tall: rows 2-9 hold the focused pane's rows 2-3.
    let shown = stacked(7, true, Some(0));
    assert_eq!(anchor(&shown, 8), Some(7));
    assert_eq!(
        drawn_at(&shown, rows_px(8), 0, 0.0),
        at(rows_px(8), Some(7))
    );
    // Zoomed: the focused pane fills the frame, so every window holds it.
    let mut zoomed = stacked(7, false, Some(0));
    zoomed.panes.truncate(1);
    zoomed.panes[0].rect.height = 8;
    assert_eq!(anchor(&zoomed, 4), None);
    assert_eq!(drawn_at(&zoomed, rows_px(4), 0, 0.0), at(rows_px(4), None));
    // Output focus holds its window whatever the row.
    assert_eq!(
        drawn_at(&shown, rows_px(4), 2, 0.0),
        view_offset(rows_px(4), 0.0, 2, CELL_H, 10)
    );
    assert_eq!(
        drawn_at(&shown, rows_px(4), 0, CELL_H),
        view_offset(rows_px(4), CELL_H, 0, CELL_H, 10)
    );
}

#[test]
fn no_focused_pane_keeps_the_shown_cursor_rule() {
    assert_eq!(anchor(&stacked(7, true, None), 4), Some(7));
    assert_eq!(anchor(&stacked(7, false, None), 4), None);
    assert_eq!(anchor(&stacked(10, true, None), 4), None);
    assert_eq!(
        drawn_at(&stacked(7, true, None), rows_px(4), 0, 0.0),
        at(rows_px(4), Some(7))
    );
}

#[test]
fn the_anchor_row_is_kotlins() {
    // Same frame, view and cell as DrawnRowsTest.theAnchorRowIsTheNativeCores.
    let shown = stacked(7, true, Some(0));
    assert_eq!(shown_anchor_row(&shown, 80.0, 0.0, 0, 20.0), Some(1));
    assert_eq!(drawn_at(&shown, 80.0, 0, 0.0), -20.0);
    let inside = stacked(2, true, Some(0));
    assert_eq!(shown_anchor_row(&inside, 80.0, 0.0, 0, 20.0), Some(2));
    assert_eq!(shown_anchor_row(&shown, 160.0, 0.0, 0, 20.0), Some(7));
    // The gate takes the view TerminalView reports for this window.
    let mut seen = view(&shown, 80.0, 0, 0);
    seen.offset_y_px = -20.0;
    seen.first = 1;
    seen.last = 4;
    assert_eq!(consistent(&seen, &shown), Some(1..=4));
    let anchored = view(&shown, 80.0, 0, 0);
    assert_eq!(consistent(&anchored, &shown), None);
}

#[test]
fn omp_needs_its_whole_box_and_cursor_on_the_phone() {
    let shown = frame("w1:p1", EMPTY);
    let seen = whole(&shown);
    let check = idle_omp(&shown, Some(&seen));
    assert_eq!(check.editor_span, Some([2, 3]));
    assert!(check.insert.ok && check.send.ok, "{check:?}");
    // Transcript rows cut off above the box take nothing from it.
    let above = view(&shown, rows_px(shown.rows - 1), 0, 0);
    assert_eq!((above.first, above.last), (1, 4));
    assert!(idle_omp(&shown, Some(&above)).send.ok);
    // Output focus scrolled the `╰─` row away; the blank row below it alone
    // takes nothing.
    let blank_gone = view(&shown, rows_px(shown.rows), 1, 0);
    assert!(idle_omp(&shown, Some(&blank_gone)).send.ok);
    let bottom_gone = view(&shown, rows_px(shown.rows), 2, 0);
    assert_eq!((bottom_gone.first, bottom_gone.last), (0, 2));
    all_refuse(&idle_omp(&shown, Some(&bottom_gone)), Refusal::EditorHidden);
    // Steer's paste is refused the same way while OMP works.
    let working = |viewport| {
        checked(
            Some("omp"),
            Some(AgentStatus::Working),
            &shown,
            Some(&acked(&shown)),
            viewport,
        )
    };
    assert!(working(Some(&seen)).steer.ok);
    assert_eq!(
        working(Some(&bottom_gone)).steer.reason,
        Some(Refusal::EditorHidden)
    );
    // A strip two rows tall: TerminalView lifts the frame so its bottom row
    // shows above it, and the box stays whole.
    let lifted = view(&shown, rows_px(shown.rows), 0, 40);
    assert_eq!((lifted.first, lifted.last), (2, 4));
    assert!(idle_omp(&shown, Some(&lifted)).send.ok);
    // The same strip while two rows are scrolled past: the `╰─` row is under it.
    let under_strip = view(&shown, rows_px(shown.rows), 2, 40);
    assert_eq!((under_strip.first, under_strip.last), (0, 2));
    all_refuse(&idle_omp(&shown, Some(&under_strip)), Refusal::EditorHidden);
}

#[test]
fn every_box_shape_has_its_span_and_a_cut_top_border_hides_it() {
    let tall = |screen: &str| format!("{}{screen}", "\n".repeat(12));
    let blank_end = "\
╭── π  dia-proxy/claude-opus-5-5  bellterm-lab/work ────8%──╮
│  probe line one
╰─
";
    for (screen, span) in [
        (ONE_LINE, [12, 13]),
        (MULTILINE, [12, 15]),
        (blank_end, [12, 14]),
    ] {
        let screen = tall(screen);
        let shown = frame("w1:p1", &screen);
        let working = |viewport: Option<&Viewport>| {
            checked(
                Some("omp"),
                Some(AgentStatus::Working),
                &shown,
                Some(&acked(&shown)),
                viewport,
            )
        };
        let check = working(Some(&whole(&shown)));
        assert_eq!(check.editor_span, Some(span), "{screen}");
        assert!(check.insert.ok, "{screen}");
        // The view starts at the top border: still whole.
        let from_top = view(&shown, rows_px(shown.rows - 12), 0, 0);
        assert_eq!(from_top.first, 12);
        assert!(working(Some(&from_top)).insert.ok, "{screen}");
        // One row less: the border is cut.
        let cut = view(&shown, rows_px(shown.rows - 13), 0, 0);
        assert_eq!(
            working(Some(&cut)).insert.reason,
            Some(Refusal::EditorHidden),
            "{screen}"
        );
        // Half a row less: the border is partly drawn, so not drawn.
        let partly = view(&shown, rows_px(shown.rows - 12) - CELL_H / 2.0, 0, 0);
        assert_eq!(partly.first, 13);
        assert_eq!(
            working(Some(&partly)).insert.reason,
            Some(Refusal::EditorHidden),
            "{screen}"
        );
    }
}

#[test]
fn omp_with_its_cursor_hidden_or_outside_the_box_takes_nothing() {
    let mut shown = frame("w1:p1", EMPTY);
    let seen = whole(&shown);
    shown.cursor.visible = false;
    all_refuse(&idle_omp(&shown, Some(&seen)), Refusal::EditorHidden);
    shown.cursor.visible = true;
    shown.cursor.y = 0;
    all_refuse(&idle_omp(&shown, Some(&seen)), Refusal::EditorHidden);
}

#[test]
fn no_viewport_is_refused_with_the_programs_label() {
    let omp = frame("w1:p1", EMPTY);
    all_refuse(&idle_omp(&omp, None), Refusal::EditorHidden);
    let prompt = frame("w1:p1", "$ ls\nfile\n$");
    assert_eq!(
        shell(&prompt, None).insert.reason,
        Some(Refusal::TargetNotVisible)
    );
}

#[test]
fn a_shell_needs_only_its_cursor_row_on_the_phone() {
    let prompt = frame("w1:p1", "$ ls\nfile\n$");
    let check = shell(&prompt, Some(&whole(&prompt)));
    assert!(check.insert.ok, "{check:?}");
    // Send stays unverified for a shell, before any phone check.
    assert_eq!(check.send.reason, Some(Refusal::UnsupportedProgram));
    // Earlier rows cut off: still fine.
    let top_cut = view(&prompt, rows_px(1), 0, 0);
    assert_eq!((top_cut.first, top_cut.last), (2, 2));
    assert!(shell(&prompt, Some(&top_cut)).insert.ok);
    // The cursor row scrolled away under output focus.
    let gone = view(&prompt, rows_px(3), 1, 0);
    assert_eq!(
        shell(&prompt, Some(&gone)).insert.reason,
        Some(Refusal::TargetNotVisible)
    );
    // The cursor hidden: where text lands is unknown.
    let mut hidden = prompt.clone();
    hidden.cursor.visible = false;
    assert_eq!(
        shell(&hidden, Some(&whole(&hidden))).insert.reason,
        Some(Refusal::TargetNotVisible)
    );
}

/// A shell in w1:p2 over OMP in w1:p1, one client grid of 15 rows.
fn split() -> RenderFrame {
    let screen = format!("{}{EMPTY}", "$ make\n".repeat(10));
    let mut shown = frame("w1:p1", &screen);
    let full = shown.panes[0].clone();
    shown.panes = vec![
        Pane {
            id: "w1:p2".into(),
            rect: Rect {
                height: 10,
                ..full.rect
            },
            focused: false,
            ..full.clone()
        },
        Pane {
            rect: Rect {
                y: 10,
                height: shown.rows - 10,
                ..full.rect
            },
            ..full
        },
    ];
    shown
}

#[test]
fn a_split_pane_is_located_by_its_rect_never_compared_to_the_grid() {
    let shown = split();
    let check = idle_omp(&shown, Some(&whole(&shown)));
    assert_eq!(check.editor_span, Some([12, 13]));
    assert!(check.send.ok, "{check:?}");
    // The other pane cut away entirely: OMP's box is still whole.
    let lower = view(&shown, rows_px(shown.rows - 10), 0, 0);
    assert_eq!(lower.first, 10);
    assert!(idle_omp(&shown, Some(&lower)).send.ok);
}

#[test]
fn a_cursor_in_another_pane_is_not_the_targets() {
    let mut shown = split();
    shown.cursor.y = 2;
    assert_eq!(
        shell(&shown, Some(&whole(&shown))).insert.reason,
        Some(Refusal::TargetNotVisible)
    );
    // The pane moved: its rect no longer holds the cursor row.
    let mut moved = split();
    moved.panes[1].rect.y = 14;
    moved.panes[1].rect.height = 1;
    assert_eq!(
        shell(&moved, Some(&whole(&moved))).insert.reason,
        Some(Refusal::TargetNotVisible)
    );
}

#[test]
fn a_resize_not_yet_drawn_refuses_as_resizing() {
    let shown = frame("w1:p1", EMPTY);
    let seen = whole(&shown);
    let idle = Some(AgentStatus::Idle);
    // The grid asked for at connect, no frame of it drawn yet.
    let opened = Grid::opened(shown.cols, shown.rows);
    let check = checked(Some("omp"), idle, &shown, Some(&opened), Some(&seen));
    all_refuse(&check, Refusal::Resizing);
    // Asked for one row more: until a frame of it arrives, a frame of the
    // old size settles nothing.
    let mut grid = acked(&shown);
    grid.request(shown.cols, shown.rows + 1, Some((shown.cols, shown.rows)));
    grid.shown(shown.cols, shown.rows);
    let check = checked(Some("omp"), idle, &shown, Some(&grid), Some(&seen));
    all_refuse(&check, Refusal::Resizing);
    // Its frame arrived: settled, and the old frame is no longer it.
    grid.shown(shown.cols, shown.rows + 1);
    let check = checked(Some("omp"), idle, &shown, Some(&grid), Some(&seen));
    all_refuse(&check, Refusal::Resizing);
    let taller = frame("w1:p1", &format!("\n{EMPTY}"));
    let check = checked(
        Some("omp"),
        idle,
        &taller,
        Some(&grid),
        Some(&whole(&taller)),
    );
    assert!(check.send.ok, "{check:?}");
    // Asking again for the grid drawn now changes nothing on the host.
    grid.request(taller.cols, taller.rows, Some((taller.cols, taller.rows)));
    let check = checked(
        Some("omp"),
        idle,
        &taller,
        Some(&grid),
        Some(&whole(&taller)),
    );
    assert!(check.send.ok, "{check:?}");
    // No grid known at all.
    let check = checked(Some("omp"), idle, &taller, None, Some(&whole(&taller)));
    all_refuse(&check, Refusal::Resizing);
}

#[test]
fn a_resize_that_never_left_the_phone_waits_for_nothing() {
    let shown = frame("w1:p1", EMPTY);
    let idle = Some(AgentStatus::Idle);
    let size = Some((shown.cols, shown.rows));
    // The phone already lays out for the failed size; Herdr still draws the
    // settled one, so the drawing is judged as it is.
    let mut grid = acked(&shown);
    let before = grid;
    grid.request(shown.cols, shown.rows + 1, size);
    grid.unsent(before);
    let mut phone_grid = whole(&shown);
    phone_grid.grid_rows += 1;
    let check = checked(Some("omp"), idle, &shown, Some(&grid), Some(&phone_grid));
    assert!(check.send.ok, "{check:?}");
    assert!(
        checked(Some("omp"), idle, &shown, Some(&grid), Some(&whole(&shown)))
            .send
            .ok
    );
    // Failed over a request still pending: that one is still awaited, and
    // its frame settles it.
    let mut grid = acked(&shown);
    grid.request(shown.cols, shown.rows + 1, size);
    let pending = grid;
    grid.request(shown.cols, shown.rows + 2, size);
    grid.unsent(pending);
    let taller = frame("w1:p1", &format!("\n{EMPTY}"));
    let mut seen = whole(&taller);
    seen.grid_rows = shown.rows + 2;
    let check = checked(Some("omp"), idle, &taller, Some(&grid), Some(&seen));
    all_refuse(&check, Refusal::Resizing);
    grid.shown(taller.cols, taller.rows);
    assert!(
        checked(Some("omp"), idle, &taller, Some(&grid), Some(&seen))
            .send
            .ok
    );
    // A size the phone never asked for is still another grid.
    seen.grid_rows = shown.rows + 3;
    let check = checked(Some("omp"), idle, &taller, Some(&grid), Some(&seen));
    all_refuse(&check, Refusal::Resizing);
}

#[test]
fn a_viewport_of_another_frame_size_or_grid_refuses_as_resizing() {
    let shown = frame("w1:p1", EMPTY);
    let mut older = whole(&shown);
    older.frame_rows += 1;
    all_refuse(&idle_omp(&shown, Some(&older)), Refusal::Resizing);
    let mut narrower = whole(&shown);
    narrower.frame_cols -= 1;
    all_refuse(&idle_omp(&shown, Some(&narrower)), Refusal::Resizing);
    let mut other_grid = whole(&shown);
    other_grid.grid_rows += 1;
    all_refuse(&idle_omp(&shown, Some(&other_grid)), Refusal::Resizing);
}

#[test]
fn a_viewport_that_disagrees_with_itself_is_refused() {
    let shown = frame("w1:p1", EMPTY);
    // It claims more rows than its metrics draw.
    let cut = view(&shown, rows_px(shown.rows), 2, 0);
    let claims_all = Viewport {
        first: 0,
        last: shown.rows - 1,
        ..cut.clone()
    };
    all_refuse(&idle_omp(&shown, Some(&claims_all)), Refusal::EditorHidden);
    // An offset other than TerminalView's for these metrics.
    let shifted = Viewport {
        offset_y_px: 0.0,
        ..cut
    };
    all_refuse(&idle_omp(&shown, Some(&shifted)), Refusal::EditorHidden);
    // Columns past the view's width.
    let mut narrow = whole(&shown);
    narrow.rect.width = (f32::from(shown.cols - 1) * CELL_W) as i32;
    all_refuse(&idle_omp(&shown, Some(&narrow)), Refusal::EditorHidden);
    let mut no_cells = whole(&shown);
    no_cells.cell_height_px = 0.0;
    all_refuse(&idle_omp(&shown, Some(&no_cells)), Refusal::EditorHidden);
}

#[test]
fn a_viewport_captured_for_another_target_is_refused() {
    let shown = frame("w1:p1", EMPTY);
    // Captured for another pane, as when the draft moves to a new target.
    let other_pane = Viewport {
        pane: "w1:p2".into(),
        ..whole(&shown)
    };
    all_refuse(&idle_omp(&shown, Some(&other_pane)), Refusal::EditorHidden);
    let other_session = Viewport {
        session: "work".into(),
        ..whole(&shown)
    };
    all_refuse(
        &idle_omp(&shown, Some(&other_session)),
        Refusal::EditorHidden,
    );
    let other_boot = Viewport {
        boot: Some("boot-0".into()),
        ..whole(&shown)
    };
    all_refuse(&idle_omp(&shown, Some(&other_boot)), Refusal::EditorHidden);
    // A check before the draft is bound names no boot.
    let unbound = Viewport {
        boot: None,
        ..whole(&shown)
    };
    let metrics = scroll(false, 40);
    let grid = acked(&shown);
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&metrics),
        grid: Some(&grid),
        ..Surface::default()
    };
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let mut loose = phone(Some(&unbound));
    loose.bind.boot = None;
    assert!(check(&idle, &surface, "w1:p1", &loose).send.ok);
    // A draft moved to another pane is checked with that pane's viewport,
    // never with the one captured for the pane it left.
    let shells = split();
    let mut moved = shells.clone();
    moved.cursor.y = 2;
    let both = Registry {
        boot: Some("boot-1".into()),
        panes: HashMap::from([
            ("w1:p1".to_owned(), idle.panes["w1:p1"].clone()),
            (
                "w1:p2".to_owned(),
                PaneFacts {
                    agent: None,
                    status: None,
                },
            ),
        ]),
    };
    let grid = acked(&moved);
    let surface = Surface {
        frame: Some(&moved),
        scroll: Some(&metrics),
        grid: Some(&grid),
        ..Surface::default()
    };
    let for_p2 = Viewport {
        pane: "w1:p2".into(),
        ..whole(&moved)
    };
    let mut to_p2 = phone(Some(&for_p2));
    to_p2.bind.pane = "w1:p2";
    assert!(check(&both, &surface, "w1:p2", &to_p2).insert.ok);
    let for_p1 = whole(&moved);
    let mut stale = phone(Some(&for_p1));
    stale.bind.pane = "w1:p2";
    assert_eq!(
        check(&both, &surface, "w1:p2", &stale).insert.reason,
        Some(Refusal::TargetNotVisible)
    );
}

#[test]
fn earlier_host_refusals_are_never_replaced() {
    // A whole dialog over the box, cut on the phone: still the dialog.
    let ask = frame("w1:p1", ASK_OVER_EMPTY);
    let cut = view(&ask, rows_px(2), 0, 0);
    for status in [
        AgentStatus::Idle,
        AgentStatus::Working,
        AgentStatus::Blocked,
    ] {
        for (grid, viewport) in [(Some(acked(&ask)), Some(&cut)), (None, None)] {
            let check = checked(Some("omp"), Some(status), &ask, grid.as_ref(), viewport);
            for gate in [&check.insert, &check.send, &check.steer] {
                assert_eq!(gate.reason, Some(Refusal::EditorDialogOpen), "{status:?}");
            }
        }
    }
    // Too short stays too short, however little the phone shows.
    let shown = frame("w1:p1", EMPTY);
    let short = scroll(false, MIN_SEND_ROWS - 1);
    let surface = Surface {
        frame: Some(&shown),
        scroll: Some(&short),
        ..Surface::default()
    };
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let check = check(&idle, &surface, "w1:p1", &phone(None));
    assert_eq!(check.send.reason, Some(Refusal::TooShort));
    // Insert passed every host check: only then is the phone asked.
    assert_eq!(check.insert.reason, Some(Refusal::EditorHidden));
}
