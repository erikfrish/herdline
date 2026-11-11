//! Steer: text for OMP while it works, as its own explicit action, never as
//! a Send that happened to meet a working agent.
//!
//! One tap stages exactly one steer, in two steps. Its paste goes first,
//! alone. Its Enter follows only once the daemon has answered the paste's
//! receipt on the same link, and OMP's box shows exactly that text while the
//! same agent instance still works. A staged steer ends at its Enter or at
//! any refusal that is not a short wait, so its Enter is pressed at most
//! once. A reconnect or a new daemon run expires it: nothing is replayed.
//!
//! Verified once, on the lab's OMP: paste and Enter while it works became a
//! steer of the running turn ("Steering · 1"). Nothing here assumes more.

use crate::composer::Refusal;
use crate::hold::Target;
use crate::transcript::Identity;
use std::collections::HashMap;
use std::sync::{LazyLock, Mutex, MutexGuard, PoisonError};

/// A steer whose paste is queued and whose Enter is not yet pressed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Staged {
    pub boot: String,
    /// The link that queued the paste.
    pub link: u64,
    /// The receipt queued behind the paste.
    pub receipt: String,
    pub agent: Option<String>,
    pub instance: Identity,
    pub text: String,
}

/// What the Enter step does now.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Next {
    /// Press Enter once; the steer ends.
    Enter,
    /// Not yet: the paste is unconfirmed or not drawn. The steer stays.
    Wait(Refusal),
    /// The steer ends without Enter; the paste stays in OMP's box for the
    /// user to see.
    End(Refusal),
}

/// The decision for one Enter step on `target`, from what was staged and
/// what holds now. `instance` and `gate` are the host's instance comparison
/// and [`crate::composer::admit_steer_enter`] for the staged steer.
pub fn next(
    staged: Option<&Staged>,
    link: u64,
    receipt: &str,
    paste_held: bool,
    instance: Result<(), Refusal>,
    gate: Result<(), Refusal>,
) -> Next {
    let Some(staged) = staged else {
        return Next::End(Refusal::SteerExpired);
    };
    if staged.link != link || staged.receipt != receipt {
        return Next::End(Refusal::SteerExpired);
    }
    if paste_held {
        return Next::Wait(Refusal::Unsettled);
    }
    if let Err(reason) = instance {
        return Next::End(reason);
    }
    match gate {
        Ok(()) => Next::Enter,
        Err(Refusal::SteerNotShown) => Next::Wait(Refusal::SteerNotShown),
        Err(reason) => Next::End(reason),
    }
}

/// Every staged steer, by target: at most one each.
#[derive(Debug, Default)]
pub struct Steers {
    staged: HashMap<Target, Staged>,
}

impl Steers {
    /// Whether a steer staged for `target` on `link` still takes the
    /// target. One staged on another link has expired, since its Enter is
    /// never sent (see [`next`]), so it is dropped here: a link lost before
    /// the steer ended never blocks the pane after a reconnect.
    pub fn takes(&mut self, target: &Target, link: u64) -> bool {
        if self
            .staged
            .get(target)
            .is_some_and(|staged| staged.link != link)
        {
            self.staged.remove(target);
        }
        self.staged.contains_key(target)
    }

    pub fn get(&self, target: &Target) -> Option<&Staged> {
        self.staged.get(target)
    }

    /// Stage `steer` for `target`. A steer already staged there stays: the
    /// caller checks [`Steers::is_staged`] under the same lock first.
    pub fn stage(&mut self, target: Target, steer: Staged) {
        self.staged.entry(target).or_insert(steer);
    }

    /// End the steer staged for `target`, if any.
    pub fn end(&mut self, target: &Target) -> Option<Staged> {
        self.staged.remove(target)
    }
}

static STEERS: LazyLock<Mutex<Steers>> = LazyLock::new(Mutex::default);

/// The process-wide staged steers. Take this after [`crate::hold::holds`],
/// never before, and keep both across a check and its queueing.
pub fn steers() -> MutexGuard<'static, Steers> {
    STEERS.lock().unwrap_or_else(PoisonError::into_inner)
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
