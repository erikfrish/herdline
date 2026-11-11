//! Whether the composer's target is wholly on the PHONE's screen (HG-VIS).
//!
//! The host checks in [`crate::composer`] read Herdr's whole frame: the
//! dialog guard, the 12-row rule and the box reading never depend on what
//! the phone shows, and run first. This module adds one more refusal on
//! top of them: the phone may draw only part of the frame (a held grid
//! taller than the view cuts its top rows; output focus scrolls the bottom
//! ones away), and text may go only where the user can see it land.
//!
//! The app captures a [`Viewport`] on the main thread at the tap: what it
//! drew, for which target, at which geometry. Here it is bound to the same
//! target and to the frame being admitted, recomputed from its own metrics,
//! and refused when anything is missing, stale or disagrees.

use crate::composer::{Program, Refusal};
use crate::render::{Rect, RenderFrame};
use crate::transcript::Identity;
use serde::Deserialize;
use std::ops::RangeInclusive;

/// Float noise allowed when placing a row edge, in pixels. Far below a
/// pixel: a row cut by any visible amount stays out.
const EDGE_SLACK_PX: f32 = 0.01;

/// What the phone drew when the user tapped, as the app reports it.
#[derive(Debug, Clone, PartialEq, Deserialize)]
pub struct Viewport {
    pub node: String,
    pub session: String,
    /// The boot the draft is bound to; `None` before it is bound (checks only).
    pub boot: Option<String>,
    pub pane: String,
    pub instance: Option<Identity>,
    /// The size of the frame the view drew.
    pub frame_rows: u16,
    pub frame_cols: u16,
    /// The grid the view last asked Herdr for (`Native.resize`).
    pub grid_rows: u16,
    pub grid_cols: u16,
    /// Frame rows wholly drawn, inclusive; `first > last` for none.
    pub first: u16,
    pub last: u16,
    /// The drawable area in view pixels, after the bottom clip; `y = 0` is
    /// the view's top.
    pub rect: PxRect,
    /// How far the frame is drawn below the view's top (negative: its top
    /// rows are cut).
    pub offset_y_px: f32,
    pub hidden_rows: u16,
    pub clip_bottom_px: i32,
    pub cell_width_px: f32,
    pub cell_height_px: f32,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
pub struct PxRect {
    pub x: i32,
    pub y: i32,
    pub width: i32,
    pub height: i32,
}

/// The target a viewport must have been captured for. A `None` field is
/// not compared: a check has no node, and no instance it could read.
#[derive(Debug, Clone, Copy)]
pub struct Bind<'a> {
    pub node: Option<&'a str>,
    pub session: &'a str,
    pub boot: Option<&'a str>,
    pub pane: &'a str,
    pub instance: Option<Option<&'a Identity>>,
}

/// The grid this client asked Herdr for, and whether Herdr has drawn it.
///
/// A request is registered here before it is sent ([`Grid::request`]), so
/// its answer can never arrive unregistered. Herdr answers a resize with a
/// surface of exactly the requested size (live, 0.9.5: 1x1 to 500x300, split
/// or not); the first frame of that size after the request settles it
/// ([`Grid::shown`]). Only this client grid is compared: a pane in a split
/// is smaller than it by design.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Grid {
    cols: u16,
    rows: u16,
    acked: bool,
    /// A later request that never left this client: the phone already
    /// lays out for it, while Herdr still answers the one before.
    unsent: Option<(u16, u16)>,
}

impl Grid {
    /// The grid a connection opens with; settled by its first frame.
    pub fn opened(cols: u16, rows: u16) -> Self {
        Self {
            cols,
            rows,
            acked: false,
            unsent: None,
        }
    }

    /// A resize to `cols` x `rows` is about to be sent; `shown` is the size
    /// of the frame held now. Asking again for the size already settled
    /// and drawn changes nothing on the host, so it stays settled; any
    /// other request waits for its frame.
    pub fn request(&mut self, cols: u16, rows: u16, shown: Option<(u16, u16)>) {
        let unchanged =
            self.acked && (self.cols, self.rows) == (cols, rows) && shown == Some((cols, rows));
        *self = Self {
            cols,
            rows,
            acked: unchanged,
            unsent: None,
        };
    }

    /// The request registered over `before` was never sent: nothing will
    /// answer it, so Herdr's grid is still `before`'s, settled or not.
    pub fn unsent(&mut self, before: Self) {
        *self = Self {
            unsent: Some((self.cols, self.rows)),
            ..before
        };
    }

    /// A frame of `cols` x `rows` was drawn.
    pub fn shown(&mut self, cols: u16, rows: u16) {
        if (cols, rows) == (self.cols, self.rows) {
            self.acked = true;
        }
    }

    /// Herdr draws the grid last asked for, and `frame` is that drawing.
    pub(crate) fn settled(&self, frame: &RenderFrame) -> bool {
        self.acked && (frame.cols, frame.rows) == (self.cols, self.rows)
    }

    /// `cols` x `rows` is the grid the phone last asked for.
    fn asked(&self, cols: u16, rows: u16) -> bool {
        (self.cols, self.rows) == (cols, rows) || self.unsent == Some((cols, rows))
    }
}

/// The frame rows wholly inside `0..height_px` when row 0 is drawn at
/// `offset_px`; `None` when not one row is whole.
pub fn drawn_rows(
    height_px: f32,
    offset_px: f32,
    cell_px: f32,
    frame_rows: u16,
) -> Option<RangeInclusive<u16>> {
    if !positive(cell_px) || frame_rows == 0 || !height_px.is_finite() || !offset_px.is_finite() {
        return None;
    }
    // Row r spans offset + r*cell .. offset + (r+1)*cell.
    let first = ((-offset_px - EDGE_SLACK_PX) / cell_px).ceil().max(0.0);
    let last = ((height_px - offset_px + EDGE_SLACK_PX) / cell_px).floor() - 1.0;
    let last = last.min(f32::from(frame_rows) - 1.0);
    if last < first {
        return None;
    }
    // Both are whole numbers within 0..frame_rows here.
    #[allow(clippy::cast_possible_truncation, clippy::cast_sign_loss)]
    Some(first as u16..=last as u16)
}

/// Where TerminalView draws row 0 (its `offsetY`): the frame's bottom on
/// the view's bottom when it is taller than the view; under output focus,
/// `hidden_rows` scrolled past the bottom of the area above the clip.
pub fn view_offset(
    view_height_px: f32,
    clip_bottom_px: f32,
    hidden_rows: u16,
    cell_px: f32,
    frame_rows: u16,
) -> f32 {
    let rows = f32::from(frame_rows);
    if hidden_rows == 0 && clip_bottom_px <= 0.0 {
        return (view_height_px - rows * cell_px).min(0.0);
    }
    let fits = ((view_height_px - clip_bottom_px) / cell_px).floor();
    let hidden = f32::from(hidden_rows.min(frame_rows.saturating_sub(1)));
    (fits - (rows - hidden)) * cell_px
}

/// The phone gate for one target, after every host check has passed.
pub fn gate(
    program: Program,
    frame: Option<&RenderFrame>,
    pane: &str,
    editor: Option<RangeInclusive<u16>>,
    grid: Option<&Grid>,
    viewport: Option<&Viewport>,
    bind: &Bind<'_>,
) -> Result<(), Refusal> {
    let hidden = match program {
        Program::Omp => Refusal::EditorHidden,
        Program::Terminal | Program::OtherAgent => Refusal::TargetNotVisible,
    };
    let (Some(frame), Some(viewport)) = (frame, viewport) else {
        return Err(hidden);
    };
    if !bound(viewport, bind) {
        return Err(hidden);
    }
    // The frame the view drew is not the one admitted, or a resize this
    // client sent is not drawn yet: the geometry is about to change.
    if (viewport.frame_rows, viewport.frame_cols) != (frame.rows, frame.cols)
        || grid.is_none_or(|grid| {
            !grid.settled(frame) || !grid.asked(viewport.grid_cols, viewport.grid_rows)
        })
    {
        return Err(Refusal::Resizing);
    }
    let drawn = consistent(viewport, frame).ok_or(hidden)?;
    let area = frame
        .panes
        .iter()
        .find(|area| area.id == pane)
        .ok_or(hidden)?;
    let cursor = frame.cursor;
    // Text lands at the cursor: it must be known, drawn, and in the target.
    if !cursor.visible || !inside(area.rect, cursor.x, cursor.y) || !drawn.contains(&cursor.y) {
        return Err(hidden);
    }
    if program == Program::Omp {
        let editor = editor.ok_or(hidden)?;
        if !editor.contains(&cursor.y)
            || !drawn.contains(editor.start())
            || !drawn.contains(editor.end())
        {
            return Err(hidden);
        }
    }
    Ok(())
}

fn bound(viewport: &Viewport, bind: &Bind<'_>) -> bool {
    bind.node.is_none_or(|node| viewport.node == node)
        && viewport.session == bind.session
        && bind
            .boot
            .is_none_or(|boot| viewport.boot.as_deref() == Some(boot))
        && viewport.pane == bind.pane
        && bind
            .instance
            .is_none_or(|instance| viewport.instance.as_ref() == instance)
}

/// The rows the viewport's own metrics draw, when they agree with what it
/// reports and with `frame`; `None` otherwise.
fn consistent(viewport: &Viewport, frame: &RenderFrame) -> Option<RangeInclusive<u16>> {
    let cell = viewport.cell_height_px;
    let rect = viewport.rect;
    if !positive(cell)
        || !positive(viewport.cell_width_px)
        || rect.x != 0
        || rect.y != 0
        || rect.height <= 0
    {
        return None;
    }
    #[allow(clippy::cast_precision_loss)]
    let (height, width, clip) = (
        rect.height as f32,
        rect.width as f32,
        viewport.clip_bottom_px.max(0) as f32,
    );
    // Every column of the frame is drawn.
    if f32::from(frame.cols) * viewport.cell_width_px > width + EDGE_SLACK_PX {
        return None;
    }
    let offset = view_offset(height + clip, clip, viewport.hidden_rows, cell, frame.rows);
    if (offset - viewport.offset_y_px).abs() > EDGE_SLACK_PX {
        return None;
    }
    let drawn = drawn_rows(height, offset, cell, frame.rows)?;
    (*drawn.start() == viewport.first && *drawn.end() == viewport.last).then_some(drawn)
}

fn inside(rect: Rect, x: u16, y: u16) -> bool {
    x >= rect.x && y >= rect.y && x - rect.x < rect.width && y - rect.y < rect.height
}

/// A finite size above zero; NaN is not one.
fn positive(px: f32) -> bool {
    px.is_finite() && px > 0.0
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
