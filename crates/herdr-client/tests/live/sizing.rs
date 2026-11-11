//! How Herdr (0.9.5) shares one pane's terminal size between two attached
//! clients of different sizes, which the composer's `PaneSizedElsewhere`
//! and the "Fit to this phone" action rely on (RZ-u-2).

use super::*;
use herdr_client::protocol::PaneSurfaceFrame;

impl Session {
    fn resize_to(&self, boot: &str, cols: u16, rows: u16) {
        self.client
            .handle
            .resize(
                boot,
                ConnectOptions {
                    surface_size: ClientSurfaceSize { cols, rows },
                    ..ConnectOptions::default()
                },
            )
            .unwrap();
    }

    /// The newest surface whose pane meets `wanted`.
    fn surface_until(
        &mut self,
        label: &str,
        pane: &str,
        wanted: impl Fn(u16, u64) -> bool,
    ) -> Arc<PaneSurfaceFrame> {
        let mut found = None;
        self.until(label, |event| match event {
            ClientEvent::Surface(surface)
                if pane_size(surface, pane)
                    .is_some_and(|(rows, viewport)| wanted(rows, viewport)) =>
            {
                found = Some(surface.clone());
                true
            }
            _ => false,
        });
        found.unwrap()
    }
}

/// The pane's rows in this client's frame, and the rows Herdr's terminal has.
fn pane_size(surface: &PaneSurfaceFrame, pane: &str) -> Option<(u16, u64)> {
    let area = surface.panes.iter().find(|area| area.pane_id == pane)?;
    Some((area.inner_rect.height, area.scroll?.viewport_rows))
}

fn row_text(surface: &PaneSurfaceFrame, row: u16) -> String {
    let width = usize::from(surface.frame.width);
    surface.frame.cells[usize::from(row) * width..][..width]
        .iter()
        .map(|cell| cell.symbol.as_str())
        .collect()
}

fn enter() -> ClientPaneInputEvent {
    ClientPaneInputEvent::Key {
        code: ClientKeyCode::Enter,
        modifiers: 0,
        kind: ClientKeyKind::Press,
        repeat_count: 1,
        shifted_codepoint: None,
        generated_text: None,
        tracks_release: false,
        physical_key_id: None,
        windows_record: None,
    }
}

#[test]
#[ignore = "requires explicit HERDR_TEST_BINARY; spawns an isolated live daemon"]
fn a_pane_is_sized_for_the_client_that_last_typed_or_focused_live() {
    let daemon = Daemon::start();
    let mut desk = Session::open(&daemon);
    let mut phone = Session::open(&daemon);
    let boot = desk.snapshot.as_ref().unwrap().boot_id.clone();
    let pane = desk
        .snapshot
        .as_ref()
        .unwrap()
        .focused_pane_id
        .clone()
        .unwrap();
    phone.resize_to(&boot, 45, 20);
    phone.surface_until("phone 45x20", &pane, |rows, _| rows == 20);
    desk.resize_to(&boot, 80, 40);
    desk.surface_until("desk 80x40", &pane, |rows, _| rows == 40);

    // The desk types: Herdr gives the terminal the desk's size.
    desk.client
        .handle
        .send_input(
            &boot,
            &pane,
            vec![
                ClientPaneInputEvent::TextCommit("clear; seq -f 'M%02g' 1 80".into()),
                enter(),
            ],
        )
        .unwrap();
    desk.surface_until("desk owns the size", &pane, |rows, viewport| {
        viewport == u64::from(rows)
    });
    let cropped = phone.surface_until("phone sees a taller terminal", &pane, |rows, viewport| {
        rows == 20 && viewport == 40
    });

    // The phone's own resize does not take the size back.
    phone.resize_to(&boot, 45, 18);
    let still = phone.surface_until("phone 45x18 drawn", &pane, |rows, _| rows == 18);
    assert_eq!(
        pane_size(&still, &pane),
        Some((18, 40)),
        "a resize alone took the size"
    );

    // The phone's frame is the top of the taller terminal: the prompt the
    // desk sees at its bottom is cut off here.
    let area = cropped
        .panes
        .iter()
        .find(|area| area.pane_id == pane)
        .unwrap();
    let rect = area.inner_rect;
    let last = row_text(&cropped, rect.y + rect.height - 1);
    assert!(
        !last.contains("LIVE>"),
        "the crop showed the bottom: {last:?}"
    );

    // Focusing the pane, already focused, gives it the phone's size.
    phone
        .client
        .handle
        .request(&boot, Method::PaneFocus, json!({"pane_id": pane}))
        .unwrap();
    let fitted = phone.surface_until("phone owns the size", &pane, |rows, viewport| {
        rows == 18 && viewport == 18
    });
    let area = fitted
        .panes
        .iter()
        .find(|area| area.pane_id == pane)
        .unwrap();
    let rect = area.inner_rect;
    let bottom: Vec<String> = (rect.y..rect.y + rect.height)
        .map(|row| row_text(&fitted, row))
        .collect();
    assert!(
        bottom.iter().any(|row| row.contains("LIVE>")),
        "the prompt is not on the phone: {bottom:?}"
    );
    desk.detach();
    phone.detach();
}
