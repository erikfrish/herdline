#![allow(clippy::unwrap_used)]

use super::*;
use crate::composer::{
    self, Refusal,
    tests::{EMPTY, frame, phone, registry, scroll, whole},
};
use herdr_client::protocol::AgentStatus;

/// OMP's empty box at the bottom of a pane `rows` tall.
fn screen(rows: usize) -> RenderFrame {
    let lines = EMPTY.lines().count();
    frame("w1:p1", &format!("{}{EMPTY}", "\n".repeat(rows - lines)))
}

/// Why Send is refused on `surface`, with `frame` drawn whole on the phone.
fn send_reason(surface: &Surface<'_>, frame: &RenderFrame) -> Option<Refusal> {
    let idle = registry(Some("omp"), Some(AgentStatus::Idle));
    let viewport = whole(frame);
    composer::check(&idle, surface, "w1:p1", &phone(Some(&viewport)))
        .send
        .reason
}

#[test]
fn a_frame_is_checked_with_the_scroll_of_its_own_surface() {
    let mut shown = Shown::opened(80, 20);
    let small = screen(20);
    shown.publish(small.clone(), scroll(false, 20));
    let _ = shown.request(80, 30);
    let large = screen(30);
    shown.publish(large.clone(), scroll(false, 30));
    assert_eq!(send_reason(&shown.surface(false), &large), None);

    // The skew a split publish allowed: the older frame read with the newer
    // surface's scroll looks like a resize where there is none.
    let grid = shown.grid;
    let skewed = scroll(false, 30);
    let surface = Surface {
        frame: Some(&small),
        popup_open: false,
        scroll: Some(&skewed),
        grid: Some(&grid),
    };
    assert_ne!(send_reason(&surface, &small), None);
}

#[test]
fn asking_again_for_a_grid_already_drawn_keeps_it_settled() {
    let mut shown = Shown::opened(80, 20);
    shown.publish(screen(20), scroll(false, 20));
    let _ = shown.request(80, 30);
    let large = screen(30);
    assert_eq!(
        send_reason(&shown.surface(false), &large),
        Some(Refusal::Resizing),
        "no frame of the new grid yet"
    );
    shown.publish(large.clone(), scroll(false, 30));
    // The same size asked again: Herdr draws no new frame for it, so the
    // request must see the frame already drawn, or it never settles.
    let _ = shown.request(80, 30);
    assert_eq!(send_reason(&shown.surface(false), &large), None);

    // What a request that read the frame before the newest one landed did.
    let mut stale = shown.grid;
    stale.request(80, 30, Some((80, 20)));
    let surface = Surface {
        grid: Some(&stale),
        ..shown.surface(false)
    };
    assert_eq!(send_reason(&surface, &large), Some(Refusal::Resizing));
}

#[test]
fn a_request_that_was_never_sent_leaves_the_drawn_grid_settled() {
    let mut shown = Shown::opened(80, 20);
    let small = screen(20);
    shown.publish(small.clone(), scroll(false, 20));
    let before = shown.request(80, 30);
    shown.unsent(before);
    assert_eq!(send_reason(&shown.surface(false), &small), None);
}
