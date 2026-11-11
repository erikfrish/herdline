//! Chat's direct line to an OMP instance: the `herdline-ingress` extension
//! (host/omp/herdline-ingress.ts) listens on a per-session Unix socket under
//! the agent's `$XDG_RUNTIME_DIR`, and this module finds it over the link's
//! exec channel and speaks its newline-delimited JSON through `socat`.
//!
//! The one rule everything here keeps: the app may fall back to typing into
//! the pane only when this instance has no endpoint at all, known before
//! anything is sent ([`Reply::Absent`]). An endpoint that is there but
//! cannot be used (permissions, version, a missing runtime hook, a timeout
//! while looking) refuses; once a request may have reached the socket, any
//! doubt is uncertain and nothing is ever sent twice.

use crate::{
    Error, Result,
    attach::{self, Attachment, Bound, Kind},
    composer::{self, Refusal},
    hold,
    ssh::Exec,
    transcript::{self, Identity},
};
use herdr_client::shell_quote;
use serde::Deserialize;
use serde_json::{Map, Value, json};
use std::time::Duration;

/// Discovery is a few file checks after the pane report.
const DISCOVERY_TIMEOUT: Duration = Duration::from_secs(20);
const DISCOVERY_ROOM: usize = 64 * 1024;
/// `socat` gives up after this long with nothing moving either way; the
/// extension answers well within it (its own wait for an idle prompt to be
/// admitted is shorter).
const SOCKET_IDLE: u32 = 20;
const EXCHANGE_TIMEOUT: Duration = Duration::from_secs(40);
/// Push lines may come ahead of the answer; the answer is the last line.
const EXCHANGE_ROOM: usize = 1024 * 1024;
/// Longest request line sent; the extension reads lines up to 1 MiB.
const LINE_MAX: usize = 256 * 1024;
const SIDECAR_MAX: usize = 4096;
const PROC: &str = "/proc";
const DIR_NAME: &str = "herdline-ingress";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum Op {
    Send,
    Status,
    State,
    Withdraw,
    /// OMP's open dialogs, as its own snapshot.
    Dialogs,
    /// The user's answer to one open dialog, named by its exact id.
    DialogSubmit,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum DeliverAs {
    /// Steers a working agent; starts the turn of an idle one.
    Auto,
    Steer,
}

/// One ingress call as Kotlin asks for it.
#[derive(Debug, Clone, Deserialize)]
pub struct Request {
    pub op: Op,
    pub node: String,
    pub session: String,
    pub boot: String,
    pub pane: String,
    /// The agent instance the draft was written for; only that one's
    /// endpoint is ever used.
    pub instance: Identity,
    /// The request's id (send, status), a fresh id (state), or the queued
    /// entry's request id (withdraw).
    pub id: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub attachments: Vec<Attachment>,
    #[serde(default, rename = "deliverAs")]
    pub deliver_as: Option<DeliverAs>,
    /// The dialog a [`Op::DialogSubmit`] answers.
    #[serde(default, rename = "dialogId")]
    pub dialog_id: Option<String>,
    /// The answer of a [`Op::DialogSubmit`], in OMP's shape for the dialog's kind.
    #[serde(default)]
    pub answer: Option<Value>,
}

impl Request {
    pub fn target(&self) -> hold::Target {
        hold::Target {
            node: self.node.clone(),
            session: self.session.clone(),
            pane: self.pane.clone(),
        }
    }

    fn bound(&self) -> Bound<'_> {
        Bound {
            node: &self.node,
            session: &self.session,
            boot: &self.boot,
            pane: &self.pane,
            instance: Some(&self.instance),
        }
    }
}

/// The extension's endpoint for one instance, as its sidecar names it.
#[derive(Debug, Clone, PartialEq, Eq)]
struct Endpoint {
    sidecar: String,
    socket: String,
    /// The sidecar's bytes as read: the send step checks they are unchanged.
    content: String,
    session_id: String,
    session_file: String,
    pid: u32,
    nonce: String,
}

#[derive(Debug, PartialEq, Eq)]
enum Discovery {
    /// No endpoint for this instance: nothing was or will be sent.
    Absent,
    /// The extension runs but OMP lacks public hooks it needs.
    Unsupported(Vec<String>),
    /// Something is there and not usable as it is.
    Unusable(&'static str),
    /// The pane no longer runs the instance the draft was written for.
    Refused(Refusal),
    Found(Endpoint),
}

#[derive(Debug, PartialEq)]
enum Exchange {
    /// The endpoint changed since discovery; nothing was sent.
    Stale,
    /// The socket took no connection; nothing was sent.
    Unreachable,
    Answer(Value),
    /// The request may have reached the socket and no answer came.
    NoAnswer,
}

/// Run one ingress call on the host; JSON for Kotlin. A send never fails:
/// what may have reached the socket is reported uncertain in the reply.
/// Blocks on the host: call it from a background thread.
pub fn run(exec: &Exec, herdr_session: &str, mut request: Request) -> Result<String> {
    if !valid_id(&request.id) {
        return Err(Error::BadReply);
    }
    let send = request.op == Op::Send;
    if send {
        request.text = composer::clean(&request.text);
        if let Err(reason) = attach::admit(&request.attachments, request.bound()) {
            return Ok(refused(reason));
        }
    }
    // Without the agent's PID no sidecar can be named: this instance has
    // no endpoint the app could use.
    let Some(pid) = request.instance.pid else {
        return Ok(absent());
    };
    let discovery = match exec.run(
        sh(&discovery_script(herdr_session, &request.pane, pid, PROC)),
        DISCOVERY_TIMEOUT,
        DISCOVERY_ROOM,
    ) {
        Ok(output) => parse_discovery(output.exit_status, &output.tail, &request.instance),
        Err(error) => {
            tracing::warn!(%error, "ingress endpoint not looked up");
            Discovery::Unusable("discovery")
        }
    };
    let endpoint = match discovery {
        Discovery::Found(endpoint) => endpoint,
        Discovery::Absent => return Ok(absent()),
        Discovery::Unsupported(missing) => {
            return Ok(json!({"endpoint": "unsupported", "missing": missing}).to_string());
        }
        Discovery::Unusable(reason) => return Ok(unusable(reason)),
        Discovery::Refused(reason) if send => return Ok(refused(reason)),
        Discovery::Refused(reason) => return Ok(unusable(refusal_name(reason))),
    };
    let line = match request.op {
        Op::Send => {
            if !request.attachments.is_empty() {
                match attach::present(exec, &request.attachments) {
                    Ok(true) => {}
                    Ok(false) => return Ok(refused(Refusal::AttachmentMissing)),
                    Err(error) => {
                        tracing::warn!(%error, "staged attachments not checked");
                        return Ok(refused(Refusal::AttachmentMissing));
                    }
                }
            }
            send_line(&request, &endpoint)
        }
        Op::Status | Op::State => json!({"v": 1, "id": request.id, "op": "status"}).to_string(),
        Op::Withdraw => json!({"v": 1, "id": request.id, "op": "withdraw"}).to_string(),
        Op::Dialogs => json!({"v": 1, "id": request.id, "op": "dialogs"}).to_string(),
        Op::DialogSubmit => match (&request.dialog_id, &request.answer) {
            (Some(dialog_id), Some(answer @ Value::Object(_))) if !dialog_id.is_empty() => json!({
                "v": 1,
                "id": request.id,
                "op": "dialog_submit",
                "dialogId": dialog_id,
                "answer": answer,
            })
            .to_string(),
            _ => return Err(Error::BadReply),
        },
    };
    if line.len() > LINE_MAX {
        return Ok(refused(Refusal::TooLarge));
    }
    let exchange = match exec.run(
        sh(&exchange_script(&endpoint, &line, &request.id)),
        EXCHANGE_TIMEOUT,
        EXCHANGE_ROOM,
    ) {
        Ok(output) => parse_exchange(output.exit_status, &output.tail, &request.id),
        Err(error) if send => {
            tracing::warn!(%error, "ingress send not confirmed");
            Exchange::NoAnswer
        }
        Err(error) => return Err(error),
    };
    match exchange {
        Exchange::Stale => Ok(unusable("stale")),
        Exchange::Unreachable => Ok(unusable("unreachable")),
        Exchange::NoAnswer if send => Ok(uncertain(&request.id)),
        Exchange::NoAnswer => Err(Error::BadReply),
        Exchange::Answer(answer) => answer_reply(
            request.op,
            &request.id,
            request.dialog_id.as_deref(),
            &answer,
        )
        .ok_or(Error::BadReply),
    }
}

fn sh(script: &str) -> String {
    format!("/bin/sh -c {}", shell_quote(script))
}

fn absent() -> String {
    json!({"endpoint": "absent"}).to_string()
}

fn unusable(reason: &str) -> String {
    json!({"endpoint": "unusable", "reason": reason}).to_string()
}

fn refused(reason: Refusal) -> String {
    json!({"outcome": "refused", "reason": reason}).to_string()
}

fn uncertain(id: &str) -> String {
    json!({"endpoint": "live", "id": id, "outcome": "uncertain"}).to_string()
}

fn refusal_name(reason: Refusal) -> &'static str {
    match reason {
        Refusal::AgentRestarted => "agent_restarted",
        _ => "instance_unknown",
    }
}

/// The pane report, then the endpoint the extension in agent `pid` would
/// have published, without trusting anything it did not create: the
/// directory and sidecar must be this user's, private and no links. One
/// status word per outcome; only `absent` means there is none.
fn discovery_script(herdr_session: &str, pane: &str, pid: u32, proc: &str) -> String {
    let mut script = transcript::instance_script(herdr_session, pane);
    script.push_str(&format!(
        r#"printf '\n@@ingress\n'
fail() {{ printf 'error %s\n' "$1"; exit 0; }}
env={proc}/{pid}/environ
[ -r "$env" ] || fail proc
x=$(tr '\0' '\n' < "$env" | sed -n 's/^XDG_RUNTIME_DIR=//p' | head -n 1)
case "$x" in
    '') printf 'absent\n'; exit 0 ;;
    *[!A-Za-z0-9._/-]* | *//* | */./* | */../*) fail runtime ;;
    /*) ;;
    *) fail runtime ;;
esac
d=$x/{DIR_NAME}
if [ ! -e "$d" ] && [ ! -L "$d" ]; then printf 'absent\n'; exit 0; fi
[ -d "$d" ] && [ ! -L "$d" ] && [ -O "$d" ] && [ "$(stat -c %a "$d")" = 700 ] || fail dir
set -- "$d"/*-{pid}.json
if [ "$#" -eq 1 ] && [ ! -e "$1" ] && [ ! -L "$1" ]; then printf 'absent\n'; exit 0; fi
[ "$#" -eq 1 ] || fail many
c=$1
[ -f "$c" ] && [ ! -L "$c" ] && [ -O "$c" ] && [ "$(stat -c %a "$c")" = 600 ] || fail sidecar
n=$(wc -c < "$c") && [ "$n" -le {SIDECAR_MAX} ] || fail sidecar
command -v socat >/dev/null || fail socat
printf 'found %s\n' "$c"
cat "$c"
"#,
    ));
    script
}

fn parse_discovery(exit_status: Option<u32>, bytes: &[u8], expected: &Identity) -> Discovery {
    let (head, rest) = match split(bytes, b"\n@@ingress\n") {
        Some((head, rest)) => (head, Some(rest)),
        None => (bytes, None),
    };
    let now = match transcript::parse_instance(exit_status, head) {
        Ok(now) => now,
        Err(Error::PaneGone | Error::NoTranscript | Error::TranscriptUnconfined) => None,
        Err(_) => return Discovery::Unusable("discovery"),
    };
    if let Err(reason) = composer::same_instance(expected, now.as_ref()) {
        return Discovery::Refused(reason);
    }
    let Some(rest) = rest else {
        return Discovery::Unusable("discovery");
    };
    let Ok(rest) = std::str::from_utf8(rest) else {
        return Discovery::Unusable("discovery");
    };
    let (status, content) = rest.split_once('\n').unwrap_or((rest, ""));
    if status == "absent" {
        return Discovery::Absent;
    }
    if let Some(reason) = status.strip_prefix("error ") {
        return Discovery::Unusable(match reason {
            "proc" => "proc",
            "runtime" => "runtime",
            "dir" => "dir",
            "many" => "many",
            "sidecar" => "sidecar",
            "socat" => "socat",
            _ => "discovery",
        });
    }
    let Some(path) = status.strip_prefix("found ") else {
        return Discovery::Unusable("discovery");
    };
    // `pid` is known here: `instance.pid` named the sidecar's file.
    let pid = expected.pid.unwrap_or_default();
    sidecar(path, content, pid, &expected.path)
}

/// The endpoint a sidecar at `path` describes, checked against the instance.
fn sidecar(path: &str, content: &str, pid: u32, session_file: &str) -> Discovery {
    let Ok(value) = serde_json::from_str::<Value>(content) else {
        return Discovery::Unusable("sidecar");
    };
    if value["v"] != 1 {
        return Discovery::Unusable("version");
    }
    let session_id = value["sessionId"].as_str().unwrap_or_default();
    let nonce = value["nonce"].as_str().unwrap_or_default();
    let Some((dir, name)) = path.rsplit_once('/') else {
        return Discovery::Unusable("sidecar");
    };
    if value["pid"].as_u64() != Some(u64::from(pid))
        || !token(session_id, 1)
        || name != format!("{session_id}-{pid}.json")
        || !plain_path(dir)
    {
        return Discovery::Unusable("sidecar");
    }
    // The sidecar is per session: another file means OMP switched session
    // and Herdr has not caught up, or the other way round.
    if value["sessionFile"].as_str() != Some(session_file) {
        return Discovery::Unusable("session_file");
    }
    if let Some(missing) = value["unsupported"]
        .as_array()
        .filter(|missing| !missing.is_empty())
    {
        return Discovery::Unsupported(
            missing
                .iter()
                .filter_map(Value::as_str)
                .filter(|hook| {
                    (1..=64).contains(&hook.len())
                        && hook
                            .bytes()
                            .all(|b| b.is_ascii_alphanumeric() || b"._-".contains(&b))
                })
                .take(16)
                .map(str::to_owned)
                .collect(),
        );
    }
    if !token(nonce, 16) {
        return Discovery::Unusable("sidecar");
    }
    Discovery::Found(Endpoint {
        sidecar: path.to_owned(),
        socket: format!("{dir}/{session_id}-{pid}.sock"),
        content: content.to_owned(),
        session_id: session_id.to_owned(),
        session_file: session_file.to_owned(),
        pid,
        nonce: nonce.to_owned(),
    })
}

fn token(value: &str, min: usize) -> bool {
    (min..=128).contains(&value.len())
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'_' || b == b'-')
}

fn plain_path(path: &str) -> bool {
    path.starts_with('/')
        && path.len() <= 512
        && path
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b"._/-".contains(&b))
        && !path.contains("//")
        && path
            .split('/')
            .all(|segment| segment != "." && segment != "..")
}

/// A request id as the app makes them: a lowercase UUID.
fn valid_id(id: &str) -> bool {
    id.len() == 36
        && id.bytes().enumerate().all(|(at, b)| match at {
            8 | 13 | 18 | 23 => b == b'-',
            _ => b.is_ascii_digit() || (b'a'..=b'f').contains(&b),
        })
}

/// The wire's send request: images go as images, files as `@"path"`
/// mentions after the text, as the PTY route sends them.
fn send_line(request: &Request, endpoint: &Endpoint) -> String {
    let attachments: Vec<Value> = request
        .attachments
        .iter()
        .filter(|a| a.kind == Kind::Image)
        .map(|a| json!({"path": a.path, "mimeType": mime(&a.name)}))
        .collect();
    let mut line = Map::new();
    line.insert("v".into(), json!(1));
    line.insert("id".into(), json!(request.id));
    line.insert(
        "instance".into(),
        json!({
            "sessionId": endpoint.session_id,
            "sessionFile": endpoint.session_file,
            "pid": endpoint.pid,
            "nonce": endpoint.nonce,
        }),
    );
    line.insert(
        "text".into(),
        json!(attach::with_files(&request.text, &request.attachments)),
    );
    if !attachments.is_empty() {
        line.insert("attachments".into(), Value::Array(attachments));
    }
    let deliver_as = match request.deliver_as {
        Some(DeliverAs::Steer) => "steer",
        _ => "auto",
    };
    line.insert("deliverAs".into(), json!(deliver_as));
    Value::Object(line).to_string()
}

/// By the staged name's extension, which staging already limited to these.
fn mime(name: &str) -> &'static str {
    match name.rsplit_once('.').map(|(_, extension)| extension) {
        Some("png") => "image/png",
        Some("jpg" | "jpeg") => "image/jpeg",
        Some("gif") => "image/gif",
        Some("webp") => "image/webp",
        _ => "application/octet-stream",
    }
}

/// Check the endpoint is still the one discovery read, then send `line` and
/// copy what comes back until the line answering `id`. The connection is
/// never half-closed before that answer: the extension, like any Node
/// server, drops a socket whose other side ended. `@@stale` means nothing
/// was sent; `@@end unconnected` means `socat` never connected, so neither
/// was anything sent. Errors are silenced so they never mix with the data.
fn exchange_script(endpoint: &Endpoint, line: &str, id: &str) -> String {
    let mut content = endpoint.content.clone();
    content.push('x');
    format!(
        r#"exec 2>/dev/null
trap '' PIPE
stale() {{ printf '@@stale\n'; exit 0; }}
c={sidecar}; k={socket}
[ -f "$c" ] && [ ! -L "$c" ] && [ -O "$c" ] && [ "$(stat -c %a "$c")" = 600 ] || stale
[ "$(cat "$c"; printf x)" = {content} ] || stale
[ -S "$k" ] && [ ! -L "$k" ] && [ -O "$k" ] && [ "$(stat -c %a "$k")" = 600 ] || stale
t=$(mktemp -d) || stale
mkfifo "$t/in" "$t/out" || {{ rm -rf "$t"; stale; }}
printf '@@open\n'
socat -T {SOCKET_IDLE} - UNIX-CONNECT:"$k" < "$t/in" > "$t/out" 2> "$t/err" &
p=$!
exec 3> "$t/in" 4< "$t/out"
printf '%s\n' {line} >&3
sed '/"id":"{id}"/q' <&4
exec 3>&- 4<&-
kill "$p"
wait "$p"
if grep -q ' E connect(' "$t/err"; then s=unconnected; else s=connected; fi
rm -rf "$t"
printf '\n@@end %s\n' "$s"
"#,
        sidecar = shell_quote(&endpoint.sidecar),
        socket = shell_quote(&endpoint.socket),
        content = shell_quote(&content),
        line = shell_quote(line),
    )
}

fn parse_exchange(exit_status: Option<u32>, bytes: &[u8], id: &str) -> Exchange {
    let clean = exit_status == Some(0);
    if clean && bytes == b"@@stale\n" {
        return Exchange::Stale;
    }
    let (body, end) = match rsplit(bytes, b"\n@@end ") {
        Some((body, end)) => (body, Some(end)),
        None => (bytes, None),
    };
    let answer = body
        .split(|&b| b == b'\n')
        .filter_map(|line| serde_json::from_slice::<Value>(line).ok())
        .find(|line| line["id"] == id);
    match answer {
        Some(answer) => Exchange::Answer(answer),
        None if clean && end == Some(b"unconnected\n") => Exchange::Unreachable,
        None => Exchange::NoAnswer,
    }
}

/// Kotlin's reply for the extension's `answer` to `op`; `None` when the
/// answer does not say what the op asks. A dialog submit's answer counts
/// only for the very dialog it named, with one of OMP's results.
fn answer_reply(op: Op, id: &str, dialog_id: Option<&str>, answer: &Value) -> Option<String> {
    let state = state(&answer["state"]);
    let mut reply = Map::new();
    reply.insert("endpoint".into(), json!("live"));
    match op {
        Op::Withdraw => {
            reply.insert("id".into(), json!(id));
            reply.insert("withdrawn".into(), json!(answer["withdrawn"].as_bool()?));
        }
        Op::State => {
            reply.insert("state".into(), state?);
        }
        Op::Dialogs => {
            // OMP's snapshot as it is: the app's dialog models read it.
            reply.insert(
                "dialogs".into(),
                Value::Array(answer["dialogs"].as_array()?.clone()),
            );
        }
        Op::DialogSubmit => {
            let dialog_id = dialog_id?;
            if answer["dialogId"].as_str()? != dialog_id {
                return None;
            }
            let result = answer["result"].as_str().filter(|result| {
                matches!(
                    *result,
                    "submitted" | "not_open" | "invalid" | "kind_mismatch"
                )
            })?;
            reply.insert("dialogId".into(), json!(dialog_id));
            reply.insert("result".into(), json!(result));
        }
        Op::Send | Op::Status => {
            reply.insert("id".into(), json!(id));
            let reason = answer["reason"].as_str().filter(|reason| token(reason, 1));
            let outcome = match answer["outcome"].as_str() {
                Some(outcome @ ("accepted" | "admitted" | "replayed")) => outcome,
                Some("refused") if reason.is_some() => "refused",
                // A refusal without its reason may hide anything: only a
                // known refusal says nothing was queued.
                _ => "uncertain",
            };
            reply.insert("outcome".into(), json!(outcome));
            if outcome == "refused"
                && let Some(reason) = reason
            {
                reply.insert("reason".into(), json!(reason));
            }
            if let Some(state) = state {
                reply.insert("state".into(), state);
            }
        }
    }
    Some(Value::Object(reply).to_string())
}

/// The queue as the extension reports it, kept to the documented shape.
fn state(state: &Value) -> Option<Value> {
    let idle = state["idle"].as_bool()?;
    let queued: Vec<Value> = state["queued"]
        .as_array()?
        .iter()
        .filter_map(|entry| {
            let text = entry["text"].as_str()?;
            let queue = entry["queue"]
                .as_str()
                .filter(|q| matches!(*q, "steering" | "followUp"))?;
            let request_id = entry["requestId"].as_str().filter(|id| valid_id(id));
            Some(json!({"requestId": request_id, "text": text, "queue": queue}))
        })
        .collect();
    Some(json!({"idle": idle, "queued": queued}))
}

fn split<'a>(bytes: &'a [u8], marker: &[u8]) -> Option<(&'a [u8], &'a [u8])> {
    let at = bytes
        .windows(marker.len())
        .position(|window| window == marker)?;
    Some((&bytes[..at], &bytes[at + marker.len()..]))
}

fn rsplit<'a>(bytes: &'a [u8], marker: &[u8]) -> Option<(&'a [u8], &'a [u8])> {
    let at = bytes
        .windows(marker.len())
        .rposition(|window| window == marker)?;
    Some((&bytes[..at], &bytes[at + marker.len()..]))
}

#[cfg(test)]
mod tests;
