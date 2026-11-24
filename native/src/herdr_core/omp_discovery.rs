//! The Chat integration in OMP's own extension discovery on the host
//! (`omp_discovery.py`): its status, and the one-time install and the
//! uninstall the user asks for. Nothing here runs on connect; a running OMP
//! is not changed and loads the integration at its next start.

use crate::{Error, Result, ssh::Exec};
use base64::{Engine, engine::general_purpose::STANDARD};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

const SCRIPT: &str = include_str!("omp_discovery.py");
/// The host copies and hashes ~35 KB; a slow disk still answers well within this.
const WAIT: Duration = Duration::from_secs(20);
const ROOM: usize = 16 * 1024;

/// What the user asked the host for.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Status,
    Install,
    Uninstall,
}

impl Action {
    pub fn parse(text: &str) -> Result<Self> {
        match text {
            "status" => Ok(Self::Status),
            "install" => Ok(Self::Install),
            "uninstall" => Ok(Self::Uninstall),
            _ => Err(Error::BadReply),
        }
    }

    fn word(self) -> &'static str {
        match self {
            Self::Status => "status",
            Self::Install => "install",
            Self::Uninstall => "uninstall",
        }
    }
}

/// The host command for `action`: the fixed script with the app's own
/// runtime files, the ones its managed launch stages, as payloads.
pub(crate) fn command(action: Action) -> String {
    let mut command = format!("python3 -c {} {}", shell_quote(SCRIPT), action.word());
    if action != Action::Uninstall {
        for payload in [
            &include_bytes!("../../../host/omp/herdline-core.ts")[..],
            include_bytes!("../../../host/omp/herdline-ingress.ts"),
            include_bytes!("../../../host/omp/live-subagents.ts"),
        ] {
            command.push_str(&format!(" '{}'", STANDARD.encode(payload)));
        }
    }
    command
}

/// The script's one JSON line, or an `error` line carrying exactly what the
/// host said when it gave none.
pub(crate) fn reply(exit_status: Option<u32>, output: &[u8]) -> String {
    let text = String::from_utf8_lossy(output);
    let line = text.lines().rev().find(|line| !line.trim().is_empty());
    if exit_status == Some(0)
        && let Some(value) = line.and_then(|line| serde_json::from_str::<Value>(line).ok())
        && value["state"].is_string()
    {
        return value.to_string();
    }
    let message = match text.trim() {
        "" => "The host did not answer the Chat integration request.",
        trimmed => trimmed,
    };
    json!({"state": "error", "path": null, "files": [], "message": message}).to_string()
}

/// Runs `action` on the host over `exec`. Blocks on the host.
pub fn run(exec: &Exec, action: Action) -> Result<String> {
    let output = exec.run(command(action), WAIT, ROOM)?;
    Ok(reply(output.exit_status, &output.tail))
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
