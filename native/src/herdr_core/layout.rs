//! A tab's layout tree laid into the phone's cell grid, and the panes'
//! terminals composed into one frame. herdr-app sizes panes in pixels on a
//! desktop window; the phone has its own grid, so only the tree's shape and
//! weights carry over. A pixel-sized node takes an even share.

use super::doc;
use crate::render::{Cursor, Pane, Rect, RenderFrame, Run, flags};
use serde_json::Value;

/// One visible block and the cells it gets.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Placed {
    /// The block's id: the phone's pane id.
    pub pane: String,
    /// The core terminal it shows; `None` for content the phone cannot draw.
    pub terminal: Option<String>,
    pub rect: Rect,
}

fn hidden(node: &Value) -> bool {
    node.get("hidden").and_then(Value::as_bool) == Some(true)
}

fn share(node: &Value) -> f64 {
    node.get("size")
        .and_then(|size| size.get("weight"))
        .and_then(Value::as_f64)
        .filter(|weight| weight.is_finite() && *weight > 0.0)
        .unwrap_or(1.0)
}

/// Lay `root` out into `area`. With `zoomed` set to a visible block, that
/// block alone fills the area.
pub fn place(root: &Value, area: Rect, zoomed: Option<&str>) -> Vec<Placed> {
    let mut out = Vec::new();
    lay(root, area, &mut out);
    if let Some(zoomed) = zoomed
        && let Some(mut only) = out.iter().find(|placed| placed.pane == zoomed).cloned()
    {
        only.rect = area;
        return vec![only];
    }
    out
}

fn lay(node: &Value, area: Rect, out: &mut Vec<Placed>) {
    if hidden(node) || area.width == 0 || area.height == 0 {
        return;
    }
    match doc::kind(node) {
        "block" => out.push(Placed {
            pane: doc::id(node).to_owned(),
            terminal: match doc::content(node) {
                doc::Content::Terminal(terminal) => Some(terminal.to_owned()),
                doc::Content::Unsupported => None,
            },
            rect: area,
        }),
        "stack" => {
            let visible: Vec<&Value> = doc::children(node).iter().filter(|c| !hidden(c)).collect();
            let active = node.get("active").and_then(Value::as_str);
            let shown = active
                .and_then(|active| visible.iter().find(|child| doc::id(child) == active))
                .or_else(|| visible.first());
            if let Some(child) = shown {
                lay(child, area, out);
            }
        }
        axis @ ("row" | "column") => {
            let visible: Vec<&Value> = doc::children(node).iter().filter(|c| !hidden(c)).collect();
            let row = axis == "row";
            let length = if row { area.width } else { area.height };
            for (child, (offset, size)) in visible.iter().zip(divide(length, &visible)) {
                let rect = if row {
                    Rect {
                        x: area.x + offset,
                        width: size,
                        ..area
                    }
                } else {
                    Rect {
                        y: area.y + offset,
                        height: size,
                        ..area
                    }
                };
                lay(child, rect, out);
            }
        }
        _ => {}
    }
}

/// `length` cells shared by weight among `children` with one cell between
/// neighbours, as (offset, size) each. Children that get no cell are
/// dropped from the end.
fn divide(length: u16, children: &[&Value]) -> Vec<(u16, u16)> {
    let mut count = children.len();
    // Every child needs a cell and a gap before it.
    while count > 1 && usize::from(length) < count * 2 - 1 {
        count -= 1;
    }
    if count == 0 || length == 0 {
        return Vec::new();
    }
    let gaps = u16::try_from(count - 1).unwrap_or(u16::MAX);
    // Cells left for content, at least one per child.
    let room = length - gaps;
    let total: f64 = children[..count].iter().map(|c| share(c)).sum();
    let mut out = Vec::with_capacity(count);
    let mut before = 0u16;
    let mut used = 0f64;
    for (index, child) in children[..count].iter().enumerate() {
        used += share(child);
        let index = u16::try_from(index).unwrap_or(u16::MAX);
        // Rounded running totals keep the sizes summing to the room.
        let after = if index == gaps {
            room
        } else {
            let ideal = (f64::from(room) * used / total).round();
            (ideal as u16).clamp(before + 1, room - (gaps - index))
        };
        out.push((before + index, after - before));
        before = after;
    }
    out
}

/// One pane of the composed frame.
pub struct Part<'a> {
    pub pane: &'a str,
    pub rect: Rect,
    /// The terminal's viewport, or `None` for a pane with nothing to draw.
    pub screen: Option<&'a RenderFrame>,
    pub focused: bool,
    pub mouse_reporting: bool,
    pub alternate_screen: bool,
    /// The viewport is scrolled back: show its top rather than the cursor.
    pub scrolled: bool,
}

/// The first row of a `rows`-row screen a `height`-row pane shows: the
/// rows ending at the cursor, or the top when scrolled back.
pub fn window(rows: u16, height: u16, cursor_y: u16, scrolled: bool) -> u16 {
    if scrolled || rows <= height {
        return 0;
    }
    (cursor_y + 1).saturating_sub(height).min(rows - height)
}

/// Every part's screen clipped into its rect on a `cols`×`rows` grid.
pub fn compose(cols: u16, rows: u16, colors: (u32, u32, u32), parts: &[Part<'_>]) -> RenderFrame {
    let (foreground, background, cursor_color) = colors;
    let mut frame = RenderFrame {
        cols,
        rows,
        foreground,
        background,
        cursor_color,
        cursor: Cursor::default(),
        ..RenderFrame::default()
    };
    for part in parts {
        let Some(screen) = part.screen else {
            frame.panes.push(pane(part, part.rect));
            continue;
        };
        let width = part.rect.width.min(screen.cols);
        let height = part.rect.height.min(screen.rows);
        let top = window(screen.rows, height, screen.cursor.y, part.scrolled);
        for run in &screen.runs {
            if run.row < top || run.row >= top + height {
                continue;
            }
            if let Some(run) = clip(run, width) {
                frame.runs.push(Run {
                    row: run.row - top + part.rect.y,
                    col: run.col + part.rect.x,
                    ..run
                });
            }
        }
        if part.focused
            && screen.cursor.y >= top
            && screen.cursor.y < top + height
            && screen.cursor.x < width
        {
            frame.cursor = Cursor {
                x: screen.cursor.x + part.rect.x,
                y: screen.cursor.y - top + part.rect.y,
                ..screen.cursor
            };
        }
        frame.panes.push(pane(
            part,
            Rect {
                width,
                height,
                ..part.rect
            },
        ));
    }
    frame
}

fn pane(part: &Part<'_>, rect: Rect) -> Pane {
    Pane {
        id: part.pane.to_owned(),
        rect,
        focused: part.focused,
        mouse_reporting: part.mouse_reporting,
        alternate_screen: part.alternate_screen,
    }
}

/// `run` cut to columns below `width`. An ASCII run is cut per cell; any
/// other run is kept only if it fits whole.
fn clip(run: &Run, width: u16) -> Option<Run> {
    if run.col >= width {
        return None;
    }
    if run.col + run.cells <= width {
        return Some(run.clone());
    }
    if run.flags & flags::ASCII == 0 {
        return None;
    }
    let cells = width - run.col;
    Some(Run {
        cells,
        text: run.text.chars().take(usize::from(cells)).collect(),
        ..run.clone()
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
