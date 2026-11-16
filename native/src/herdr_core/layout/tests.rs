use super::*;
use crate::render::{Cursor, Rect, RenderFrame, Run, flags};
use serde_json::json;

fn area(width: u16, height: u16) -> Rect {
    Rect {
        x: 0,
        y: 0,
        width,
        height,
    }
}

fn block(id: &str) -> Value {
    json!({"id": id, "type": "block", "content": {"terminal": format!("t-{id}")}})
}

#[test]
fn a_row_shares_its_width_by_weight_with_one_cell_gaps() {
    let mut a = block("a");
    a["size"] = json!({"weight": 3.0});
    let root = json!({"id": "r", "type": "row", "children": [a, block("b")]});
    let placed = place(&root, area(81, 10), None);
    assert_eq!(placed.len(), 2);
    assert_eq!((placed[0].rect.x, placed[0].rect.width), (0, 60));
    assert_eq!((placed[1].rect.x, placed[1].rect.width), (61, 20));
    assert_eq!(placed[1].terminal.as_deref(), Some("t-b"));
}

#[test]
fn a_stack_shows_its_active_child_and_hidden_nodes_take_no_room() {
    let mut hidden = block("h");
    hidden["hidden"] = json!(true);
    let stack = json!({"id": "s", "type": "stack", "active": "b", "children": [block("a"), block("b")]});
    let root = json!({"id": "r", "type": "column", "children": [stack, hidden]});
    let placed = place(&root, area(20, 9), None);
    assert_eq!(placed.len(), 1);
    assert_eq!(placed[0].pane, "b");
    assert_eq!(placed[0].rect, area(20, 9));
}

#[test]
fn a_zoomed_block_fills_the_area_alone() {
    let root = json!({"id": "r", "type": "row", "children": [block("a"), block("b")]});
    let placed = place(&root, area(40, 10), Some("b"));
    assert_eq!(placed.len(), 1);
    assert_eq!(placed[0].pane, "b");
    assert_eq!(placed[0].rect, area(40, 10));
}

#[test]
fn content_the_phone_cannot_draw_has_no_terminal() {
    let root = json!({"id": "m", "type": "block", "content": {"mod": "panel"}});
    assert_eq!(place(&root, area(10, 10), None)[0].terminal, None);
}

fn run(row: u16, col: u16, text: &str, ascii: bool) -> Run {
    Run {
        row,
        col,
        cells: u16::try_from(text.chars().count()).unwrap(),
        flags: if ascii { flags::ASCII } else { 0 },
        fg: 0,
        bg: 0,
        text: text.into(),
    }
}

#[test]
fn compose_clips_runs_and_follows_the_cursor_into_the_rect() {
    let screen = RenderFrame {
        cols: 10,
        rows: 4,
        cursor: Cursor {
            x: 2,
            y: 3,
            visible: true,
            ..Cursor::default()
        },
        runs: vec![
            run(0, 0, "top", true),
            run(3, 0, "abcdefghij", true),
            run(2, 4, "é漢字", false),
        ],
        ..RenderFrame::default()
    };
    let part = Part {
        pane: "p",
        rect: Rect {
            x: 5,
            y: 1,
            width: 6,
            height: 2,
        },
        screen: Some(&screen),
        focused: true,
        mouse_reporting: false,
        alternate_screen: false,
        scrolled: false,
    };
    let frame = compose(20, 5, (1, 2, 3), &[part]);
    // Rows 2 and 3 end at the cursor; row 0 is above the window.
    let texts: Vec<_> = frame.runs.iter().map(|r| (r.row, r.col, r.text.as_str())).collect();
    assert_eq!(texts, vec![(2, 5, "abcdef")]);
    assert_eq!((frame.cursor.x, frame.cursor.y), (7, 2));
    assert_eq!(frame.panes[0].rect.height, 2);
}

#[test]
fn a_scrolled_pane_shows_the_top_of_its_viewport() {
    assert_eq!(window(10, 4, 9, false), 6);
    assert_eq!(window(10, 4, 9, true), 0);
    assert_eq!(window(3, 4, 2, false), 0);
}
