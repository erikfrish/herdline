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
//! Both engines go through their own control path over the host's exec
//! channel, pinned to the exact session id the request names.
//!
//! Codex: the running app-server daemon, through `codex app-server proxy`
//! (`agent_chat/codex_ctl.py`). The proxy connects to the daemon's control
//! socket and never starts one, and a thread the daemon has not loaded is
//! never acted on, so nothing here resumes a session or runs a second agent.
//! Send starts a turn on an idle thread and steers a working one, as OMP's
//! endpoint does; nothing is queued behind the user's back. Steer goes into
//! the running turn, named by its id. The
//! queue reads and withdraws by the app's request id, which travels as
//! `clientUserMessageId`, and Status finds that id in the queue or the turns.
//!
//! OpenCode (`agent_chat/opencode_ctl.py`, pinned to v2.2.7 fedc6f2): the
//! session's own inbox on the explicitly discovered, already-running local
//! service, the one its TUI attaches to; nothing starts a second process.
//! Send is `POST /api/session/:id/prompt` with `{id, text, delivery?}`: Auto
//! carries no delivery and is refused `agent_working` while the session
//! runs, so the phone only ever queues or steers by the verb the user saw;
//! Steer and Queue name theirs. State reads the inbox and
//! `/api/session/active`; Status finds the id in the inbox or the history;
//! Withdraw cancels and then proves the entry did not run; Promote moves a
//! queued entry into the running turn. No server registered under this
//! user's defaults is `needs_server` for every op: an unusable route,
//! never an absence that would let a send fall through to the pane.
//!
//! OpenCode and Kilo control: the session's open permission requests as
//! dialogs, the user's reply to one, and Stop. Kilo (v1 server) takes only
//! these; every other Kilo op answers unsupported by name. Kilo has no
//! validated PTY route, so nothing of it is typed into the pane.
//!
//! Every call runs as one command. First the transcript's own pane report,
//! then the tiny embedded verifier, which must read that report as exactly
//! the instance the request names (its pane, terminal, foreground pid,
//! session id and session ref), and only then the engine step. A report
//! that cannot be read as that instance refuses before any CLI runs.
//!
//! Only answers provable before the agent could hold anything are definite
//! refusals or unusable endpoints. An admission is accepted only on the
//! engine's own answer. Every other answer follows a call that may have
//! been admitted, so a send reads it as [`uncertain`]: kept by the caller,
//! never sent again. OpenCode file parts and children are not served here.

use crate::{
    Error, Result, attach, composer,
    ingress::{DeliverAs, Op, Request},
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
    /// Kilo, the OpenCode fork: permissions and Stop through its server;
    /// every other op unsupported by name, never absent.
    Kilo,
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
    let control = matches!(request.op, Op::Dialogs | Op::DialogSubmit | Op::Abort);
    match engine {
        // Dialogs, Stop and Promote have no Codex path here: a truthful
        // absence of that op, not a request Kotlin may treat as done.
        Engine::Codex if control || request.op == Op::Promote => return Ok(unsupported_op(request.op).to_string()),
        // Its daemon steers or starts a turn; it never queues behind one.
        Engine::Codex if request.deliver_as == Some(DeliverAs::Queue) => {
            return Ok(json!({"endpoint": "unsupported", "missing": ["queue"]}).to_string());
        }
        // Kilo's server serves only permissions and Stop here. Its text and
        // checks have no route yet: said as such, never an absence a send
        // could fall through to the pane on.
        Engine::Kilo if !control => return Ok(unsupported_op(request.op).to_string()),
        _ => {}
    }
    let mut submitted = None;
    if request.op == Op::DialogSubmit {
        let dialog_id = request.dialog_id.as_deref().filter(|id| valid_permission_id(id)).ok_or(Error::BadReply)?;
        let decision = request.answer.as_ref().and_then(|answer| answer["data"]["decision"].as_str());
        let Some(decision) = decision.filter(|d| DECISIONS.contains(d)) else {
            // Nothing left the phone: the answer is not one the server takes.
            return Ok(submit_result(dialog_id, "invalid").to_string());
        };
        submitted = Some(json!({"dialogId": dialog_id, "decision": decision}).to_string());
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
        // Codex takes a staged image as its own `localImage` input, so a
        // photo alone is a message; nothing else carries files.
        let images = engine == Engine::Codex && request.attachments.iter().all(|a| a.kind == attach::Kind::Image);
        if text.trim().is_empty() && !(images && !request.attachments.is_empty()) {
            return Ok(refused(composer::Refusal::EmptyText).to_string());
        }
        if text.len() > MAX_TEXT_BYTES {
            return Ok(refused(composer::Refusal::TooLarge).to_string());
        }
        if !request.attachments.is_empty() && !images {
            // OpenCode, and files for Codex, have no verified payload here.
            // Drop nothing silently: once one has real confirmed support it
            // becomes the send body, not a text-only fallback.
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
    let step = match engine {
        Engine::Codex => codex_step(&request, &text),
        Engine::OpenCode | Engine::Kilo => {
            let message = format!("msg_{}", request.id);
            let (op, payload) = match request.op {
                Op::Send => {
                    let mut body = json!({"id": message, "text": text});
                    match request.deliver_as {
                        Some(DeliverAs::Steer) => body["delivery"] = json!("steer"),
                        Some(DeliverAs::Queue) => body["delivery"] = json!("queue"),
                        Some(DeliverAs::Auto) | None => {}
                    }
                    ("send", body.to_string())
                }
                Op::Status => ("status", json!({"id": message}).to_string()),
                Op::State => ("state", String::from("{}")),
                Op::Withdraw => ("withdraw", json!({"id": message}).to_string()),
                Op::Promote => ("promote", json!({"id": message}).to_string()),
                Op::Dialogs => ("dialogs", String::from("{}")),
                Op::DialogSubmit => ("submit", submitted.unwrap_or_else(|| String::from("{}"))),
                Op::Abort => ("abort", String::from("{}")),
            };
            control_step(engine, op, &request.instance, &payload)
        }
        Engine::Pty => unreachable!("routed to the PTY above"),
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
    let answer = match (engine, word.split_once('\t')) {
        (Engine::Codex, Some((word, payload))) => codex_reply(word, payload, request.op, &request.id)?,
        // The verifier refused before the client ran, or nothing answered.
        (Engine::Codex, None) => gate_reply(&word, request.op).map_or_else(|| unreadable(request.op, &request.id), Ok)?,
        _ => control_reply(&word, request.op, &request.id, request.dialog_id.as_deref().unwrap_or(""))?,
    };
    Ok(answer.to_string())
}

/// The reply for one line of the Codex daemon client (`codex_ctl.py`):
/// its word and its JSON payload. Only words said before any change was
/// asked of the thread give a definite "nothing sent"; an admission is
/// accepted only on the daemon's own answer naming our request.
fn codex_reply(word: &str, payload: &str, op: Op, id: &str) -> Result<Value> {
    let payload: Value = serde_json::from_str(payload).map_err(|_| Error::BadReply)?;
    let live = |mut value: Value| {
        value["endpoint"] = json!("live");
        value
    };
    Ok(match word {
        // No daemon for this user, no CLI, or a thread the daemon has not
        // loaded (the pane's TUI runs its own app server): this route does
        // not reach the pane's agent, so the caller's validated PTY route
        // takes the send, as for any agent without an endpoint.
        "nodaemon" | "cli" | "notloaded" => absent(),
        // The daemon is there and would not speak, or cannot serve the
        // thread: nothing reached the thread.
        "daemon" => unavailable("codex_daemon"),
        "error" => unavailable("codex_error"),
        "blocked" if op == Op::Send => refused(composer::Refusal::AgentBlocked),
        // Steer has no running turn to join.
        "not_working" if op == Op::Send => refused(composer::Refusal::AgentIdle),
        "accepted" if matches!(op, Op::Send | Op::Status) => {
            live(json!({"id": id, "outcome": "accepted", "state": payload}))
        }
        // A status check found no trace of the request: still possibly queued.
        "unknown" if op == Op::Status => live(json!({"id": id, "outcome": "unknown", "state": payload})),
        "state" if op == Op::State && payload.is_object() => live(json!({"state": payload})),
        "withdrawn" if op == Op::Withdraw && payload["withdrawn"].is_boolean() => live(json!({
            "withdrawn": payload["withdrawn"],
            "state": payload["state"],
        })),
        _ => match op {
            Op::Send => uncertain(id),
            _ => return Err(Error::BadReply),
        },
    })
}

/// The words the host gate itself answers before any engine step ran: no
/// scratch directory, or a pane report that is not this instance now or
/// could not be read as one. Both fail closed.
fn gate_reply(word: &str, op: Op) -> Option<Value> {
    let refusal = match word {
        "local" => return Some(unavailable("local")),
        "refused_changed" => composer::Refusal::AgentRestarted,
        "refused_unknown" => composer::Refusal::InstanceUnknown,
        _ => return None,
    };
    Some(match op {
        Op::Send => refused(refusal),
        _ => unavailable(refusal_name(refusal)),
    })
}

/// Any other answer follows an invocation whose fate is unreadable: it may
/// have been admitted. A send is uncertain, never a second try; a check
/// that cannot be read is a failure Kotlin settles nothing on.
fn unreadable(op: Op, id: &str) -> Result<Value> {
    match op {
        Op::Send => Ok(uncertain(id)),
        _ => Err(Error::BadReply),
    }
}

/// The answers a permission request takes, as both servers name them
/// (opencode `schema/src/permission.ts:42`, kilo `schema/src/v1/permission.ts`).
const DECISIONS: [&str; 3] = ["once", "always", "reject"];

/// The reply for one line of the OpenCode/Kilo script (`opencode_ctl.py`),
/// in the shapes Kotlin reads for OMP. Words said before any change was
/// asked are definite; anything else keeps a send uncertain and fails a
/// check or a submit, which the phone then keeps as it was.
fn control_reply(line: &str, op: Op, id: &str, dialog_id: &str) -> Result<Value> {
    if let Some(reply) = gate_reply(line, op) {
        return Ok(reply);
    }
    let (word, payload) = line.split_once('\t').unwrap_or((line, ""));
    let live = |mut value: Value| {
        value["endpoint"] = json!("live");
        value
    };
    Ok(match (word, op) {
        // No server registered under this user's defaults: a TUI started
        // standalone, or one whose server is gone. The route is there and
        // cannot be used until the server runs; nothing reached the agent.
        ("no_server", _) => unavailable("needs_server"),
        ("auth", _) => unavailable("server_auth"),
        // A registration not trusted as the one server for this user (a
        // custom root, two of them, loose permissions): refused, never the
        // latest one guessed.
        ("discovery", _) => unavailable("server_discovery"),
        // The server does not know this session: its ref moved on.
        ("gone", Op::Send) => refused(composer::Refusal::AgentRestarted),
        ("gone", _) => unavailable(refusal_name(composer::Refusal::AgentRestarted)),
        // A send with no verb while the session runs: nothing was queued,
        // and nothing is turned into a steer or a queue behind the user.
        ("busy", Op::Send) => refused(composer::Refusal::AgentWorking),
        ("queued", Op::Send) => accepted(id),
        ("state", Op::State) => {
            let state = serde_json::from_str(payload).ok().and_then(|state| crate::ingress::state(&state));
            live(json!({"state": state.ok_or(Error::BadReply)?}))
        }
        // In the inbox or the history: held. Nowhere: possibly still on
        // its way, never a refusal.
        ("accepted" | "unknown", Op::Status) => live(json!({"id": id, "outcome": word})),
        ("withdrawn", Op::Withdraw) => match payload {
            "true" | "false" => live(json!({"id": id, "withdrawn": payload == "true"})),
            _ => return Err(Error::BadReply),
        },
        ("promoted", Op::Promote) => match payload {
            "true" | "false" => live(json!({"id": id, "promoted": payload == "true"})),
            _ => return Err(Error::BadReply),
        },
        ("dialogs", Op::Dialogs) => {
            let dialogs: Value = serde_json::from_str(payload).map_err(|_| Error::BadReply)?;
            let named = dialogs.as_array().is_some_and(|all| {
                all.iter().all(|d| d["dialogId"].as_str().is_some_and(valid_permission_id) && d["kind"] == "permission")
            });
            if !named {
                return Err(Error::BadReply);
            }
            live(json!({"dialogs": dialogs}))
        }
        ("submitted" | "not_open" | "invalid", Op::DialogSubmit) => submit_result(dialog_id, word),
        ("aborted", Op::Abort) => match payload {
            "true" | "false" => live(json!({"aborted": payload == "true"})),
            _ => return Err(Error::BadReply),
        },
        _ => return unreadable(op, id),
    })
}

fn submit_result(dialog_id: &str, result: &str) -> Value {
    json!({"endpoint": "live", "dialogId": dialog_id, "result": result})
}

/// A permission request id as both servers make them (`per` + an ascending
/// id): never a path or a shell-relevant byte.
fn valid_permission_id(id: &str) -> bool {
    id.len() <= 128 && id.starts_with("per") && id.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
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
        "kilo" => Engine::Kilo,
        _ => Engine::Pty,
    }
}

/// The Codex daemon client for this request, bounded to its own thread: one
/// line, a word and a JSON payload ([`codex_reply`]). The client is found as
/// the night route found the CLI, and speaks only to a daemon already
/// running for this user.
fn codex_step(request: &Request, text: &str) -> String {
    let op = match request.op {
        Op::Send => "send",
        Op::Status => "status",
        Op::State => "state",
        Op::Withdraw => "withdraw",
        Op::Dialogs | Op::DialogSubmit | Op::Abort | Op::Promote => unreachable!("answered as unsupported"),
    };
    let deliver = match request.deliver_as {
        Some(DeliverAs::Steer) => "steer",
        Some(DeliverAs::Auto) | None => "auto",
        Some(DeliverAs::Queue) => unreachable!("answered as unsupported"),
    };
    let images: Vec<&str> = request.attachments.iter().map(|a| a.path.as_str()).collect();
    format!(
        r#"codex=$(command -v codex) || codex=$HOME/.codex/bin/codex
[ -x "${{codex:-}}" ] || {{ printf 'cli\tnull\n'; exit 0; }}
command -v python3 >/dev/null || {{ printf 'cli\tnull\n'; exit 0; }}
python3 - "$codex" {op} {thread} {id} {deliver} {text} {images} <<'HERDLINE_CODEX_CTL'
{client}
HERDLINE_CODEX_CTL"#,
        thread = shell_quote(&request.instance.path),
        id = shell_quote(&request.id),
        text = shell_quote(text),
        images = shell_quote(&json!(images).to_string()),
        client = include_str!("agent_chat/codex_ctl.py"),
    )
}

/// The OpenCode/Kilo control script for one `op` on this exact session:
/// only the already-running server registered under this user's default
/// state directory, which must know the session. No process environment is
/// read. The CLI's default api path can autostart a service, so discovery
/// and HTTP stay explicit here.
fn control_step(engine: Engine, op: &str, instance: &Identity, payload: &str) -> String {
    let flavor = if engine == Engine::Kilo { "kilo" } else { "opencode" };
    format!(
        "python3 - {flavor} {op} {} {} <<'HERDLINE_OPENCODE_CTL'\n{}\nHERDLINE_OPENCODE_CTL",
        shell_quote(&instance.path),
        shell_quote(payload),
        include_str!("agent_chat/opencode_ctl.py"),
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
        Op::Send => "send",
        Op::Status => "status",
        Op::State => "queue",
        Op::Withdraw => "withdraw",
        Op::Promote => "promote",
        Op::Dialogs => "dialogs",
        Op::DialogSubmit => "dialog_submit",
        Op::Abort => "abort",
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
