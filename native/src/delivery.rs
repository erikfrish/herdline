//! Queueing composer input with its receipt. The client calls sit behind
//! [`Queue`], so what a delivery or steer step answers when queueing fails
//! halfway is decided here and tested on the host.
//!
//! Input goes first, then a read-only request whose answer means the daemon
//! read it. Input that was not queued reached nothing: an error, nothing
//! held. Input queued without its request may still land: *uncertain*, held
//! until the user unlocks the target (see [`crate::hold`]).

use crate::{
    Result, attach,
    composer::{Refusal, Request, SteerRequest},
    hold::{Holds, Target},
    input,
    steer::{Staged, Steers},
    transcript::Identity,
};
use herdr_client::{ClientHandle, Method, protocol::ClientPaneInputEvent};
use serde_json::{Value, json};

/// The two client calls that put composer input on the ordered writer.
pub trait Queue {
    fn send_input(
        &self,
        boot: &str,
        pane: &str,
        events: Vec<ClientPaneInputEvent>,
    ) -> herdr_client::Result<()>;
    fn request(&self, boot: &str, method: Method, params: Value) -> herdr_client::Result<String>;
}

impl Queue for ClientHandle {
    fn send_input(
        &self,
        boot: &str,
        pane: &str,
        events: Vec<ClientPaneInputEvent>,
    ) -> herdr_client::Result<()> {
        ClientHandle::send_input(self, boot, pane, events)
    }

    fn request(&self, boot: &str, method: Method, params: Value) -> herdr_client::Result<String> {
        ClientHandle::request(self, boot, method, params)
    }
}

/// Input that was queued: with its receipt's request id, or without one.
enum Queued {
    Receipt(String),
    Uncertain,
}

/// Queue `events` for `target`'s pane and `receipt` right behind them. The
/// target is held from then on: until the receipt is answered on `link`,
/// or, with no receipt, until unlocked.
fn queue(
    queue: &impl Queue,
    holds: &mut Holds,
    target: Target,
    link: u64,
    boot: &str,
    events: Vec<ClientPaneInputEvent>,
    receipt: Method,
) -> Result<Queued> {
    // Not queued: nothing reached the pane, so nothing is held.
    queue.send_input(boot, &target.pane, events)?;
    match queue.request(boot, receipt, json!({})) {
        Ok(id) => {
            holds.hold_until(target, link, id.clone());
            Ok(Queued::Receipt(id))
        }
        // The input is queued but nothing can confirm it: it may land, so
        // it is never reported as not sent.
        Err(error) => {
            tracing::warn!(%error, "composer input unconfirmed");
            holds.hold(target);
            Ok(Queued::Uncertain)
        }
    }
}

pub fn refused(reason: Refusal) -> String {
    json!({"outcome": "refused", "reason": reason}).to_string()
}

fn uncertain() -> String {
    json!({"outcome": "uncertain", "reason": Refusal::Unconfirmed}).to_string()
}

fn queued(id: &str) -> String {
    json!({"outcome": "queued", "receipt": id}).to_string()
}

/// Deliver `request` as one paste and, when submitting, one Enter, unless
/// its target is held or has a steer staged on `link`, or `admit` refuses
/// it. Hold the guards from the check through the queueing, so no second
/// delivery passes the check before this one holds the target.
pub fn deliver(
    queue_to: &impl Queue,
    holds: &mut Holds,
    steers: &mut Steers,
    link: u64,
    receipt: Method,
    request: Request,
    admit: impl FnOnce(&Request) -> std::result::Result<(), Refusal>,
) -> Result<String> {
    let target = request.target();
    if holds.is_held(&target) || steers.takes(&target, link) {
        return Ok(refused(Refusal::Unsettled));
    }
    if let Err(reason) = admit(&request) {
        return Ok(refused(reason));
    }
    let mut events = attach::pastes(&request.text, &request.attachments);
    if request.submit {
        events.push(input::enter());
    }
    Ok(
        match queue(
            queue_to,
            holds,
            target,
            link,
            &request.boot,
            events,
            receipt,
        )? {
            Queued::Receipt(id) => queued(&id),
            Queued::Uncertain => uncertain(),
        },
    )
}

/// A steer's paste, alone, staged for its Enter once queued with its
/// receipt. The caller has checked the gates under the same guards.
pub fn stage_steer(
    queue_to: &impl Queue,
    holds: &mut Holds,
    steers: &mut Steers,
    link: u64,
    receipt: Method,
    request: SteerRequest,
    instance: Identity,
) -> Result<String> {
    let target = request.target();
    let events = vec![ClientPaneInputEvent::Paste(request.text.clone())];
    Ok(
        match queue(
            queue_to,
            holds,
            target.clone(),
            link,
            &request.boot,
            events,
            receipt,
        )? {
            Queued::Receipt(id) => {
                let staged = json!({"outcome": "staged", "receipt": id}).to_string();
                steers.stage(
                    target,
                    Staged {
                        boot: request.boot,
                        link,
                        receipt: id,
                        agent: request.agent,
                        instance,
                        text: request.text,
                    },
                );
                staged
            }
            // No Enter ever follows a paste nothing can confirm.
            Queued::Uncertain => uncertain(),
        },
    )
}

/// The one Enter of `staged`, already ended, queued with its receipt.
pub fn press_steer_enter(
    queue_to: &impl Queue,
    holds: &mut Holds,
    target: Target,
    link: u64,
    staged: Staged,
    receipt: Method,
) -> Result<String> {
    Ok(
        match queue(
            queue_to,
            holds,
            target,
            link,
            &staged.boot,
            vec![input::enter()],
            receipt,
        )? {
            Queued::Receipt(id) => queued(&id),
            Queued::Uncertain => uncertain(),
        },
    )
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
