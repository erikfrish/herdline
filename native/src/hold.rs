//! The composer's last line of defence against a second delivery of text
//! that may already have landed.
//!
//! A delivery holds its target from the moment its paste is queued. Only the
//! answer to its own receipt, on the link that queued it, releases the hold
//! by itself; a lost link, a rejected receipt or an app restart leave it
//! held until the user explicitly unlocks the target. The holds outlive any
//! one link, so a reconnect cannot release them, and Kotlin re-arms them from
//! the drafts it persisted after the process restarts.

use serde::Deserialize;
use std::collections::HashMap;
use std::sync::{LazyLock, Mutex, MutexGuard, PoisonError};

/// One composer destination: a pane of one Herdr session on one saved node.
#[derive(Debug, Clone, PartialEq, Eq, Hash, Deserialize)]
pub struct Target {
    pub node: String,
    pub session: String,
    pub pane: String,
}

/// The receipt that may release a hold: request `id` on link `link`.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Receipt {
    link: u64,
    id: String,
}

/// Every held target, with the receipt that may release it, if any.
#[derive(Debug, Default)]
pub struct Holds {
    held: HashMap<Target, Option<Receipt>>,
}

impl Holds {
    pub fn is_held(&self, target: &Target) -> bool {
        self.held.contains_key(target)
    }

    /// Held until request `id` on `link` is answered, or until unlocked.
    pub fn hold_until(&mut self, target: Target, link: u64, id: String) {
        self.held.insert(target, Some(Receipt { link, id }));
    }

    /// Held until unlocked. A hold already waiting for its receipt keeps it.
    pub fn hold(&mut self, target: Target) {
        self.held.entry(target).or_insert(None);
    }

    /// The daemon answered request `id` on `link`: the input queued before it
    /// was read, so the target it held is free again.
    pub fn answered(&mut self, link: u64, id: &str) {
        self.held.retain(|_, receipt| {
            receipt
                .as_ref()
                .is_none_or(|receipt| receipt.link != link || receipt.id != id)
        });
    }

    /// The user settled the target's last delivery.
    pub fn unlock(&mut self, target: &Target) {
        self.held.remove(target);
    }
}

static HOLDS: LazyLock<Mutex<Holds>> = LazyLock::new(Mutex::default);

/// The process-wide holds. Keep the guard across the check and the queueing
/// of a delivery, so two deliveries can never both pass the check.
pub fn holds() -> MutexGuard<'static, Holds> {
    HOLDS.lock().unwrap_or_else(PoisonError::into_inner)
}

#[cfg(test)]
mod tests;
