//! The agent launchers installed on the host, discovered read-only.
//!
//! The catalogue is fixed: Herdr's canonical agent kinds, each probed with
//! one `command -v` in the exec shell, whose PATH is extended with the
//! user's own binary folders. Listing a kind means its CLI was found on
//! PATH, and no more: nothing here starts, installs or reconfigures an
//! agent, and no credential, environment file or foreign process is read.

use crate::{Result, ssh};
use serde_json::Value;
use serde_json::json;
use std::time::Duration;

/// Herdr's canonical kinds, in the picker's order, with the label the app
/// shows beside each. The kind is also the executable probed.
pub const CATALOGUE: &[(&str, &str)] = &[
    ("pi", "Pi"),
    ("claude", "Claude"),
    ("codex", "Codex"),
    ("gemini", "Gemini"),
    ("cursor", "Cursor"),
    ("devin", "Devin"),
    ("agy", "Antigravity"),
    ("cline", "Cline"),
    ("omp", "OMP"),
    ("mastracode", "Mastracode"),
    ("opencode", "OpenCode"),
    ("copilot", "Copilot"),
    ("kimi", "Kimi"),
    ("kiro", "Kiro"),
    ("droid", "Droid"),
    ("amp", "Amp"),
    ("grok", "Grok"),
    ("hermes", "Hermes"),
    ("kilo", "Kilo"),
    ("qodercli", "Qoder"),
    ("qwen", "Qwen"),
    ("letta", "Letta"),
    ("maki", "Maki"),
    ("muse", "Muse"),
];

/// One probe must finish promptly and yields one short line per kind.
const PROBE_TIMEOUT: Duration = Duration::from_secs(15);
const OUTPUT_LIMIT: usize = 4096;

/// The one probe command: the user's binary folders in front of PATH, then
/// one `command -v` per fixed kind. Only catalogue text reaches the shell.
pub fn probe_command() -> String {
    let kinds: Vec<&str> = CATALOGUE.iter().map(|(kind, _)| *kind).collect();
    format!(
        r#"PATH="$HOME/.bun/bin:$HOME/.local/bin:$PATH"; for k in {}; do command -v "$k" >/dev/null 2>&1 && printf 'HIT %s\n' "$k"; done; printf 'END\n'"#,
        kinds.join(" ")
    )
}

/// The launchers one probe found, as JSON `{"launchers": [{kind, label}]}`;
/// an unreadable probe is an error, never an empty listing.
pub fn catalogue(exec: &ssh::Exec) -> Result<String> {
    let command = format!("/bin/sh -c {}", herdr_client::shell_quote(&probe_command()));
    let output = exec.run(command, PROBE_TIMEOUT, OUTPUT_LIMIT)?;
    Ok(parse(&output)?.to_string())
}

/// Reads one probe's [`ssh::Output`] as the `{"launchers": …}` document.
pub fn parse(output: &ssh::Output) -> Result<Value> {
    if output.exit_status != Some(0) {
        return Err(crate::Error::BadReply);
    }
    let text = String::from_utf8_lossy(&output.tail);
    // Without the end marker the output was cut, so a missing kind is
    // unknown, not absent.
    if !text.lines().any(|line| line.trim_end() == "END") {
        return Err(crate::Error::BadReply);
    }
    let launchers: Vec<Value> = found(&text)
        .into_iter()
        .map(|(kind, label)| json!({"kind": kind, "label": label}))
        .collect();
    Ok(json!({"launchers": launchers}))
}

/// The catalogue entries one probe's `HIT` lines name, in catalogue order.
fn found(text: &str) -> Vec<(&'static str, &'static str)> {
    CATALOGUE
        .iter()
        .copied()
        .filter(|(kind, _)| {
            text.lines().any(|line| {
                line.strip_prefix("HIT ")
                    .is_some_and(|hit| hit.trim_end() == *kind)
            })
        })
        .collect()
}

/// Start only the newly created pane that the caller fenced, through Herdr's
/// public CLI (agent.start is not offered on the mobile client endpoint).
pub fn start(
    exec: &ssh::Exec,
    session: &str,
    pane: &str,
    tab: &str,
    terminal: &str,
    kind: &str,
    name: &str,
) -> Result<String> {
    if !CATALOGUE.iter().any(|(known, _)| *known == kind)
        || name.is_empty()
        || name.len() > 32
        || !name
            .bytes()
            .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == b'-' || c == b'_')
    {
        return Err(crate::Error::BadReply);
    }
    let script = format!(
        r#"herdr=
{candidates}
    if [ -n "$path" ] && [ -x "$path" ]; then herdr=$path; break; fi
done
[ -n "$herdr" ] || exit 127
python3 - "$herdr" {session} {pane} {tab} {terminal} {kind} {name} <<'HERDLINE_START'
import json, subprocess, sys
herdr, session, pane, tab, terminal, kind, name = sys.argv[1:]
before = subprocess.run([herdr, '--session', session, 'pane', 'get', pane], stdin=subprocess.DEVNULL, capture_output=True, timeout=15)
if before.returncode:
    sys.exit(20)
reported = json.loads(before.stdout)['result']['pane']
if reported.get('terminal_id') != terminal or reported.get('tab_id') != tab or reported.get('agent'):
    sys.exit(21)
result = subprocess.run([herdr, '--session', session, 'agent', 'start', name, '--kind', kind, '--pane', pane, '--timeout', '45000'], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=50)
data = result.stdout if result.stdout else result.stderr
sys.stdout.buffer.write(data)
sys.exit(result.returncode)
HERDLINE_START
"#,
        candidates = herdr_client::HERDR_CANDIDATES,
        session = herdr_client::shell_quote(session),
        pane = herdr_client::shell_quote(pane),
        tab = herdr_client::shell_quote(tab),
        terminal = herdr_client::shell_quote(terminal),
        kind = herdr_client::shell_quote(kind),
        name = herdr_client::shell_quote(name),
    );
    let output = exec.run(
        format!("/bin/sh -c {}", herdr_client::shell_quote(&script)),
        Duration::from_secs(65),
        32 * 1024,
    )?;
    let envelope: Value = serde_json::from_slice(&output.tail)?;
    if output.exit_status == Some(0) && envelope["result"]["type"] == "agent_started" {
        Ok(json!({"started": true}).to_string())
    } else {
        Ok(json!({"started": false, "reason": envelope["error"]["message"].as_str().unwrap_or("Harness startup was not confirmed; check its terminal before retrying")}).to_string())
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
