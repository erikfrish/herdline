//! Live text sends to a Codex or OpenCode agent running in a pane, and the
//! honest answer when there is none: Pi and Claude Code have no verified
//! program path here and report [`absent`], never a guessed one, so the
//! caller's validated PTY route takes over.
//!
//! This is the non-OMP half of [`crate::ingress`]. The request and reply
//! shapes are unchanged, and the same rule runs through every reply: only
//! [`absent`] says nothing was sent and the pane route may be used; a
//! refusal is a fact known before anything touched an agent's input
//! (`outcome: refused` or a definite `endpoint: unusable`); anything that
//! may have reached the agent and is not confirmed is `outcome: uncertain`,
//! kept by the caller and never sent again.
//!
//! Both engines send through their own control path over the host's exec
//! channel, pinned to the exact session id the request names — Codex
//! `codex queue --thread --message`, queued on that live thread's own
//! session, never `exec resume` and never a second agent, and OpenCode
//! `POST /api/session/:sessionID/prompt` with `{id, text}` on an explicitly
//! discovered already-running local service. The default CLI API command
//! may autostart a service, so it is not used for delivery.
//!
//! Every call runs as one command: the transcript's own pane report first,
//! then the tiny embedded verifier that must read that report as exactly
//! the instance the request names (its pane, terminal, foreground pid,
//! session id and session ref), and only then the engine step. A report
//! that cannot be read as that instance refuses before any CLI runs. Past
//! the CLI boundary only four facts are answered, each provable before the
//! agent could hold anything: the CLI is absent ([`cli_reason`]), the
//! server refuses the login ([`server`]), this session or thread is gone
//! (`gone`), or nothing ran at all (`local`). Every other answer follows an
//! invocation that may have been admitted somewhere, so a send reads it as
//! [`uncertain`] — kept by the caller, never sent again — and never as a
//! refusal or an unusable endpoint.
//!
//! The check ops run the same binding and then answer that no wire path
//! here serves this call: neither CLI exposes this client a request id's
//! receipt or an engine queue, so a definite unusable endpoint stands and
//! Kotlin keeps the receipt it holds exactly as it was. Nothing is invented
//! to make a queue look readable.

use crate::{
    Error, Result, attach, composer,
    ingress::{Op, Request},
    ssh::Exec,
    transcript::{self, Identity},
};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

/// How long one call may take: the pane report, then the engine's CLI past
/// it, waiting for it to flush.
const CALL_TIMEOUT: Duration = Duration::from_secs(60);
/// The report and one status word; kept tight so a chatty CLI never fills a
/// channel.
const ROOM: usize = 64 * 1024;
/// Longest text a send carries, as the composer draws it.
const MAX_TEXT_BYTES: usize = composer::MAX_TEXT_BYTES;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum Engine {
    Codex,
    OpenCode,
    /// Pi, Claude Code, or any name not recognized: report [`absent`], so
    /// the caller's validated PTY route (`composer::deliver`) takes over
    /// instead of a guessed unguarded input.
    Pty,
}

pub fn run(exec: &Exec, herdr_session: &str, request: Request) -> Result<String> {
    if !valid_request_id(&request.id) {
        return Err(Error::BadReply);
    }
    let engine = engine(&request.instance);
    if engine == Engine::Pty {
        return Ok(absent().to_string());
    }
    if !matches!(request.op, Op::Send | Op::Status | Op::State | Op::Withdraw) {
        // Dialogs and their submissions are OMP-only snapshots; these
        // engines have no dialog surface we know. Target not supported, so
        // the reply is a truthful absence, not a request Kotlin may treat
        // as sent.
        return Ok(unsupported_op(request.op).to_string());
    }
    let text = composer::clean(&request.text);
    if request.op == Op::Send {
        let bound = attach::Bound {
            node: &request.node,
            session: &request.session,
            boot: &request.boot,
            pane: &request.pane,
            instance: Some(&request.instance),
        };
        if let Err(reason) = attach::admit(&request.attachments, bound) {
            return Ok(refused(reason).to_string());
        }
        if text.trim().is_empty() {
            return Ok(refused(composer::Refusal::EmptyText).to_string());
        }
        if text.len() > MAX_TEXT_BYTES {
            return Ok(refused(composer::Refusal::TooLarge).to_string());
        }
        if !request.attachments.is_empty() {
            // Codex and OpenCode have no verified command payload for
            // attachments. Drop nothing silently. Once an upload has real
            // confirmed support here this becomes the send body, not a
            // text-only fallback.
            return Ok(unsupported_type().to_string());
        }
    }
    if !sendable_session(&request.instance) {
        // The ref this request drafted for is not one the engines name a
        // session with: nothing here can reach that instance.
        return Ok(refused(composer::Refusal::AgentRestarted).to_string());
    }
    // One command: the pane report, the exact-instance verifier, and only
    // past it the engine step (or `bound` when there is no step to run).
    let step = match request.op {
        Op::Send => match engine {
            Engine::Codex => codex_step(&request.instance, &text),
            Engine::OpenCode => opencode_step(&request.instance, &text, &request.id),
            Engine::Pty => unreachable!("routed to the PTY above"),
        },
        Op::Status | Op::State | Op::Withdraw => String::from("printf 'bound\\n'"),
        Op::Dialogs | Op::DialogSubmit => unreachable!("dialogs are OMP-only"),
    };
    let word = match host_gate(exec, herdr_session, &request.instance, &step) {
        Ok(word) => word,
        Err(error) => {
            tracing::warn!(%error, "agent chat call not confirmed");
            // A send may have reached the agent before the link broke:
            // uncertain, never a second try. The rest are checks; a failure
            // is a failure, and Kotlin keeps its receipt unchanged.
            if request.op == Op::Send {
                return Ok(uncertain(&request.id).to_string());
            }
            return Err(error);
        }
    };
    Ok(reply(word.as_str(), request.op, engine, &request.id)?.to_string())
}

/// The reply for one status word the command answered. Only the four words
/// proved before the agent could hold anything may say a definite verdict;
/// anything else keeps a send uncertain and never names a refusal.
fn reply(word: &str, op: Op, engine: Engine, id: &str) -> Result<Value> {
    Ok(match word {
        "queued" => accepted(id),
        // This session or thread is one the engine does not know: its ref
        // moved on while the pane report still read ours, or the session is
        // gone. Nothing of ours was queued.
        "gone" => match op {
            Op::Send => refused(composer::Refusal::AgentRestarted),
            _ => unavailable(refusal_name(composer::Refusal::AgentRestarted)),
        },
        // Nothing ran: the CLI is absent, the login was refused, or the
        // work directory could not even be made.
        "cli" => unavailable(cli_reason(engine)),
        "auth" => unavailable(server(engine)),
        "local" => unavailable("local"),
        // The binding stood and nothing else ran: the check ops, whose only
        // honest answer today is that no wire path here serves this call.
        "bound" => match op {
            Op::Status => unavailable("status"),
            Op::State => unavailable("queue"),
            Op::Withdraw => unavailable("withdraw"),
            Op::Send | Op::Dialogs | Op::DialogSubmit => return Err(Error::BadReply),
        },
        // The pane report is not this instance now, or could not be read as
        // one: both fail closed, before any CLI ran.
        "refused_changed" => match op {
            Op::Send => refused(composer::Refusal::AgentRestarted),
            _ => unavailable(refusal_name(composer::Refusal::AgentRestarted)),
        },
        "refused_unknown" => match op {
            Op::Send => refused(composer::Refusal::InstanceUnknown),
            _ => unavailable(refusal_name(composer::Refusal::InstanceUnknown)),
        },
        // Anything else follows an invocation whose fate is unreadable: it
        // may have been admitted. A send is uncertain, never a second try;
        // a check that cannot be read is a failure Kotlin does not settle
        // anything on.
        _ => match op {
            Op::Send => uncertain(id),
            _ => return Err(Error::BadReply),
        },
    })
}

/// The refusal word for a failed binding: what the app already shows for
/// an instance that moved on versus one it could not read.
fn refusal_name(reason: composer::Refusal) -> &'static str {
    match reason {
        composer::Refusal::AgentRestarted => "agent_restarted",
        _ => "instance_unknown",
    }
}

fn engine(instance: &Identity) -> Engine {
    match instance.agent.as_str() {
        "codex" => Engine::Codex,
        "opencode" => Engine::OpenCode,
        _ => Engine::Pty,
    }
}

fn cli_reason(engine: Engine) -> &'static str {
    match engine {
        Engine::Codex => "codex_cli",
        _ => "opencode_cli",
    }
}

fn server(engine: Engine) -> &'static str {
    match engine {
        Engine::Codex => "codex_auth",
        _ => "server_auth",
    }
}

/// The queued word for a `codex queue` admission on the live thread the
/// request names. `codex queue` never starts or resumes an agent: the one
/// running on that thread drains its queue into its turn, which is also how
/// a working turn takes the text.
fn codex_step(instance: &Identity, text: &str) -> String {
    format!(
        r#"codex=$(command -v codex) || codex=$HOME/.codex/bin/codex
[ -x "${{codex:-}}" ] || {{ printf 'cli\n'; exit 0; }}
t=$(mktemp -d) || {{ printf 'local\n'; exit 0; }}
if "$codex" queue --thread {thread} --message {text} >"$t/out" 2>"$t/err"; then
    printf 'queued\n'
else
    case "$(cat "$t/out" "$t/err")" in
        *'no rollout found'*) printf 'gone\n' ;;
        *'Operation not found'*) printf 'cli\n' ;;
        *) printf 'doubt\n' ;;
    esac
fi
rm -rf "$t""#,
        thread = shell_quote(&instance.path),
        text = shell_quote(&text),
    )
}

/// Send only to the already-running service registered by this pane's
/// OpenCode process. The CLI's default api path can autostart a service,
/// so discovery and HTTP admission stay explicit here.
fn opencode_step(instance: &Identity, text: &str, id: &str) -> String {
    let body = json!({"id": format!("msg_{id}"), "text": text}).to_string();
    format!(
        "python3 - {} {} {} <<'HERDLINE_OPENCODE_SEND'\n{}\nHERDLINE_OPENCODE_SEND",
        instance.pid.unwrap_or(0),
        shell_quote(&instance.path),
        shell_quote(&body),
        include_str!("agent_chat/opencode_send.py"),
    )
}

/// One command, bounded to `expect`: the transcript's own pane report for
/// the real session and pane, then the tiny embedded verifier that must read
/// that report as exactly `expect` (its pane id, terminal id, foreground
/// pid under the report's own agent name, session id and session ref), and
/// only after it passes, `step`'s own single word. A report the verifier
/// rejects prints its own refusal word and `step` never runs.
fn host_gate(exec: &Exec, herdr_session: &str, expect: &Identity, step: &str) -> Result<String> {
    let pane = expect.pane_id.as_str();
    let mut report = transcript::instance_script(herdr_session, pane);
    report.push('\n');
    let script = format!(
        r#"exec 2>/dev/null
t=$(mktemp -d) || {{ printf 'local\n'; exit 0; }}
( {report} ) > "$t/report"
command -v python3 >/dev/null || {{ rm -rf "$t"; printf 'refused_unknown\n'; exit 0; }}
python3 - "$t/report" {pane_id} {terminal_id} {pid} {path} {agent} {source} {kind} <<'PY'
import json, sys

report = open(sys.argv[1], "rb").read()
if not report.startswith(b"@@pane\n"):
    sys.exit(2)
head, sep, rest = report.partition(b"\n@@proc\n")
if not sep:
    sys.exit(2)
process, _, _ = rest.partition(b"\n@@file ")
try:
    pane = json.loads(head[len(b"@@pane\n"):])
    proc = json.loads(process) if process.strip() else {{}}
    result = pane["result"]["pane"]
    session = result["agent_session"]
    agent = session["agent"]
    kind, value = session["kind"], session["value"]
    source = session.get("source")
    if not isinstance(agent, str) or not isinstance(value, str):
        sys.exit(2)
    if kind not in ("path", "id") or not (isinstance(source, str) or source is None):
        sys.exit(2)
    if source is None:
        source = "herdr:" + agent
    pid = None
    for entry in proc.get("result", {{}}).get("process_info", {{}}).get("foreground_processes") or []:
        if entry.get("name") == agent:
            found = entry.get("pid")
            pid = found if isinstance(found, int) and found > 0 else None
            break
except Exception:
    sys.exit(2)

want = sys.argv[2:]
want_pid = None if want[2] == "-" else int(want[2])
if pid is None or want_pid is None or want_pid <= 0:
    sys.exit(2)
ok = (
    result.get("pane_id") == want[0]
    and result.get("terminal_id") == want[1]
    and pid == want_pid
    and value == want[3]
    and agent == want[4]
    and source == want[5]
    and kind == want[6]
)
sys.exit(0 if ok else 3)
PY
v=$?
rm -rf "$t"
if [ "$v" -eq 0 ]; then
    {step}
elif [ "$v" -eq 3 ]; then
    printf 'refused_changed\n'
else
    printf 'refused_unknown\n'
fi"#,
        report = report.trim_end(),
        pane_id = shell_quote(&expect.pane_id),
        terminal_id = shell_quote(&expect.terminal_id),
        pid = shell_quote(
            &expect
                .pid
                .map_or_else(|| "-".to_owned(), |pid| pid.to_string())
        ),
        path = shell_quote(&expect.path),
        agent = shell_quote(&expect.agent),
        source = shell_quote(&expect.source),
        kind = shell_quote(&expect.kind),
    );
    let output =
        String::from_utf8_lossy(&exec.run(sh(&script), CALL_TIMEOUT, ROOM)?.tail).into_owned();
    let word = output.trim();
    if word.is_empty() || word.contains('\n') {
        return Err(Error::BadReply);
    }
    Ok(word.to_owned())
}

fn sh(script: &str) -> String {
    format!("/bin/sh -c {}", shell_quote(script))
}

fn refused(reason: composer::Refusal) -> Value {
    json!({"outcome": "refused", "reason": reason})
}

fn absent() -> Value {
    json!({"endpoint": "absent"})
}

fn unavailable(reason: &str) -> Value {
    json!({"endpoint": "unusable", "reason": reason})
}

fn accepted(id: &str) -> Value {
    json!({"endpoint": "live", "id": id, "outcome": "accepted"})
}

/// The call may have reached the agent and no answer confirmed it: the
/// caller keeps its receipt and never sends this text again.
fn uncertain(id: &str) -> Value {
    json!({"endpoint": "live", "id": id, "outcome": "uncertain"})
}

fn unsupported_op(op: Op) -> Value {
    let op = match op {
        Op::Dialogs => "dialogs",
        Op::DialogSubmit => "dialog_submit",
        Op::Send | Op::Status | Op::State | Op::Withdraw => unreachable!("handled"),
    };
    json!({"endpoint": "unsupported", "missing": [op]})
}

fn unsupported_type() -> Value {
    json!({"endpoint": "unsupported", "missing": ["attachments"]})
}

/// A session ref the engines name with, and one this request may speak to:
/// an `id` kind of plain session-id shape, never a path. Herdr fills this
/// for codex and opencode panes (`session_id`-shaped values only).
fn sendable_session(instance: &Identity) -> bool {
    instance.kind == "id"
        && instance.pid.is_some_and(|pid| pid > 0)
        && !instance.path.is_empty()
        && instance.path.len() <= 512
        && !instance.path.starts_with('.')
        && instance
            .path
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
}

/// A request id as the app makes them: a lowercase UUID.
fn valid_request_id(id: &str) -> bool {
    id.len() == 36
        && id.bytes().enumerate().all(|(at, b)| match at {
            8 | 13 | 18 | 23 => b == b'-',
            _ => b.is_ascii_digit() || (b'a'..=b'f').contains(&b),
        })
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests;
