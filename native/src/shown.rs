//! The latest frame the phone draws, the pane scroll read from the same
//! surface, and the grid this client asked Herdr for: one value behind one
//! lock, so the composer never pairs a frame with another surface's scroll,
//! and a resize never compares its request with a frame it did not see.

#[cfg(test)]
mod tests;

use crate::{
    composer::{PaneScroll, Surface},
    render::RenderFrame,
    visible::Grid,
};
use std::{collections::HashMap, sync::Arc};

#[derive(Debug)]
pub struct Shown {
    frame: Option<Arc<RenderFrame>>,
    /// Each pane's scroll position and terminal height, from `frame`'s
    /// surface.
    scroll: HashMap<String, PaneScroll>,
    grid: Grid,
}

impl Shown {
    /// What a connection asking for `cols` x `rows` shows before its first
    /// frame.
    pub fn opened(cols: u16, rows: u16) -> Self {
        Self {
            frame: None,
            scroll: HashMap::new(),
            grid: Grid::opened(cols, rows),
        }
    }

    pub fn frame(&self) -> Option<&Arc<RenderFrame>> {
        self.frame.as_ref()
    }

    /// `frame` is drawn, with the scroll of the surface it was drawn from.
    pub fn publish(&mut self, frame: RenderFrame, scroll: HashMap<String, PaneScroll>) {
        self.grid.shown(frame.cols, frame.rows);
        self.frame = Some(Arc::new(frame));
        self.scroll = scroll;
    }

    /// A resize to `cols` x `rows` is about to be sent; answers the grid
    /// before it, for [`Shown::unsent`].
    pub fn request(&mut self, cols: u16, rows: u16) -> Grid {
        let before = self.grid;
        let shown = self.frame.as_ref().map(|frame| (frame.cols, frame.rows));
        self.grid.request(cols, rows, shown);
        before
    }

    /// The request over `before` was never sent.
    pub fn unsent(&mut self, before: Grid) {
        self.grid.unsent(before);
    }

    /// The composer's view of what is shown.
    pub fn surface(&self, popup_open: bool) -> Surface<'_> {
        Surface {
            frame: self.frame.as_deref(),
            popup_open,
            scroll: Some(&self.scroll),
            grid: Some(&self.grid),
        }
    }
}
