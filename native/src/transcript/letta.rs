//! Letta Code 0.34.54 (5eb817d6) local-backend history only.
//!
//! `local-conversation-key.ts` and `local-store.ts:2834-2844` name one
//! base64url conversation directory. `local-transcript.ts:272-335` folds
//! replacement messages and selects `in_context_message_ids` in its order.
//! This never calls Letta, reads auth, resumes an agent or scans for a session.
//! Cloud and unsupported manifests refuse. Registration belongs to the
//! integration owner; this module provides the complete bounded reader.
//!
//! Only `LETTA_LOCAL_BACKEND_DIR`, `LETTA_CONFIG_DIR` and HOME from the exec
//! shell choose roots. Agent-only overrides are unknown, never guessed.
//! Roots must be absolute, below HOME, link-free and private to this uid;
//! each file is regular, private, single-linked and bounded. Schema 1 and
//! cloud history are not read or migrated. Inline media is normalized but
//! this reader supplies no media-fetch route or prompt/control channel.


use super::{Identity, Read, Response, pi};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::Value;
use std::time::Duration;

const ROOM: usize = 34 * 1024 * 1024;
const STRIDE: u64 = 65_536;
const PAGE: u64 = 200;
/// The exact official Letta ref and foreground process Herdr reports.
pub(super) fn identity(pane: &Value, process: &Value) -> Result<Option<Identity>> {
    let pane = &pane["result"]["pane"];
    let reference = &pane["agent_session"];
    if reference["agent"] != "letta" { return Ok(None); }
    if reference["kind"] != "id" || reference["source"] != "herdr:letta" {
        return Err(Error::UnsupportedFormat("letta_local_format"));
    }
    let text = |value: &Value| value.as_str().filter(|s| !s.is_empty()).map(str::to_owned).ok_or(Error::BadReply);
    let processes = process["result"]["process_info"]["foreground_processes"].as_array().ok_or(Error::NoTranscript)?;
    let mut found = processes.iter().filter(|p| p["name"] == "letta");
    let (Some(foreground), None) = (found.next(), found.next()) else { return Err(Error::NoTranscript); };
    let pid = foreground["pid"].as_u64().and_then(|v| u32::try_from(v).ok()).filter(|v| *v > 0).ok_or(Error::NoTranscript)?;
    Ok(Some(Identity { pane_id:text(&pane["pane_id"])?, terminal_id:text(&pane["terminal_id"])?,
        pid:Some(pid), agent:"letta".into(), source:"herdr:letta".into(), kind:"id".into(), path:text(&reference["value"])? }))
}


pub(super) fn page(exec: &Exec, session: &str, pane: &str, expect: &Identity, read: Read, reset: bool) -> Result<Response> {
    let output = exec.run(format!("/bin/sh -c {}", shell_quote(&script(session, pane, expect))), Duration::from_secs(60), ROOM)?;
    if output.exit_status != Some(0) {
        return Err(Error::NoTranscript);
    }
    response(&serde_json::from_slice(&output.tail)?, expect, read, reset)
}

fn script(session: &str, pane: &str, expect: &Identity) -> String {
    format!("python3 - {} {} <<'HERDLINE_LETTA_LOCAL'\n{}\nHERDLINE_LETTA_LOCAL",
        shell_quote(&serde_json::to_string(expect).unwrap_or_default()), shell_quote(&super::instance_script(session, pane)), PYTHON)
}

fn response(reply: &Value, expect: &Identity, read: Read, reset: bool) -> Result<Response> {
    match reply["error"].as_str() {
        Some("changed") => return Err(Error::NoTranscript),
        Some("outside_home") => return Err(Error::SessionRootOutsideHome),
        Some("unconfined") => return Err(Error::TranscriptUnconfined),
        Some("format") => return Err(Error::UnsupportedFormat("letta_local_format")),
        Some("too_large") => return Err(Error::EntryTooLarge),
        Some(_) => return Err(Error::NoTranscript),
        None => {}
    }
    let stamp = reply["stamp"].as_u64().filter(|s| *s > 0 && *s < (1_u64 << 37)).ok_or(Error::BadReply)?;
    let rows = reply["rows"].as_array().filter(|r| r.len() < STRIDE as usize).ok_or(Error::BadReply)?;
    let base = stamp * STRIDE;
    let cursor = match read { Read::After { offset } | Read::Before { offset } => Some(offset), Read::Tail => None };
    let same = cursor.is_some_and(|c| c / STRIDE == stamp);
    let end = match read {
        Read::Before { offset } if same => (offset % STRIDE).min(rows.len() as u64),
        _ => rows.len() as u64,
    };
    let start = if matches!(read, Read::After { .. }) && same { end } else { end.saturating_sub(PAGE) };
    let mut entries = Vec::with_capacity((end - start) as usize);
    for index in start..end {
        let record = &rows[index as usize];
        let mut entry = pi::normalize(record, base + index, base + index + 1);
        // Letta's logical message id differs from the enclosing session-entry id.
        entry.id = record["message"]["id"].as_str().map(str::to_owned);
        if entry.timestamp.is_none() {
            entry.timestamp = record["timestamp"].as_str().and_then(super::iso_timestamp);
        }
        entries.push(entry);
    }
    Ok(Response {
        identity: expect.clone(), status: reply["status"].as_str().unwrap_or("unknown").to_owned(),
        reset: reset || !same, size: rows.len() as u64, start: base + start, end: base + end,
        has_older: start > 0, entries,
    })
}

const PYTHON: &str = r#"import base64, hashlib, json, os, re, stat, subprocess, sys
expect, report = json.loads(sys.argv[1]), sys.argv[2]
MAX_FILE = 32 * 1024 * 1024
MAX_META = 1024 * 1024
MAX_ROWS = 65535
SAFE = re.compile(r'[A-Za-z0-9_-]{1,128}')
class Refused(Exception): pass

def identity():
    got = subprocess.run(['/bin/sh', '-c', report], stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=20)
    raw = got.stdout
    if got.returncode or len(raw) > 65536 or not raw.startswith(b'@@pane\n'): raise Refused('changed')
    first, process = raw[len(b'@@pane\n'):].split(b'\n@@proc\n', 1)
    pane = json.loads(first)['result']['pane']
    ref = pane.get('agent_session') or {}
    processes = json.loads(process).get('result', {}).get('process_info', {}).get('foreground_processes') or []
    matches = [p for p in processes if isinstance(p, dict) and p.get('pid') == expect.get('pid') and p.get('name') == 'letta']
    current = {'pane_id':pane['pane_id'], 'terminal_id':pane['terminal_id'], 'pid':expect.get('pid'), 'agent':ref.get('agent'), 'kind':ref.get('kind'), 'path':ref.get('value'), 'source':ref.get('source', 'herdr:letta')}
    if len(matches) != 1 or current != expect: raise Refused('changed')
    return pane.get('agent_status', 'unknown')

def root():
    home = os.path.realpath(os.environ['HOME'])
    config = os.environ.get('LETTA_CONFIG_DIR', '').strip()
    value = os.environ.get('LETTA_LOCAL_BACKEND_DIR')
    path = value if value is not None else os.path.join(config or os.path.join(home, '.letta'), 'lc-local-backend')
    if not os.path.isabs(path) or any(ord(c) < 32 for c in path): raise Refused('unconfined')
    canonical = os.path.realpath(path)
    if canonical == home or os.path.commonpath([home, canonical]) != home: raise Refused('outside_home')
    if canonical != path: raise Refused('unconfined')
    return path

def private_directory(path):
    node = os.lstat(path)
    if not stat.S_ISDIR(node.st_mode) or node.st_uid != os.getuid() or node.st_mode & 0o077: raise Refused('unconfined')

def directory_fd(path):
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_DIRECTORY
    fd = os.open('/', flags)
    try:
        for component in path.split('/')[1:]:
            if not component or component in ('.', '..'): raise Refused('unconfined')
            child = os.open(component, flags, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise

def read_file(folder, name, limit):
    # Walk all ancestors through descriptors; a replaced symlink is never followed.
    flags = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK
    directory = directory_fd(folder)
    try:
        node = os.fstat(directory)
        if node.st_uid != os.getuid() or node.st_mode & 0o077: raise Refused('unconfined')
        fd = os.open(name, flags, dir_fd=directory)
    finally:
        os.close(directory)
    with os.fdopen(fd, 'rb') as handle:
        before = os.fstat(handle.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_uid != os.getuid() or before.st_mode & 0o077 or before.st_nlink != 1: raise Refused('unconfined')
        if before.st_size > limit: raise Refused('too_large')
        data = handle.read(limit + 1)
        after = os.fstat(handle.fileno())
        if len(data) > limit: raise Refused('too_large')
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns): raise Refused('changed')
        return data

def reference():
    sid = expect.get('path')
    if expect.get('agent') != 'letta' or expect.get('kind') != 'id' or not isinstance(sid, str): raise Refused('format')
    if sid.startswith('default:'):
        agent = sid[len('default:'):]
        if not SAFE.fullmatch(agent): raise Refused('format')
        return 'default', agent, sid
    if not SAFE.fullmatch(sid): raise Refused('format')
    return sid, None, 'conversation:' + sid

def fold(data, record):
    if not data.endswith(b'\n'): raise Refused('format')
    lines = data.splitlines()
    if not lines: raise Refused('format')
    header = json.loads(lines[0])
    if not isinstance(header, dict) or header.get('type') != 'session' or header.get('version') != 3 or header.get('id') != record['id']: raise Refused('format')
    rows = {}
    for line in lines[1:]:
        value = json.loads(line)
        if not isinstance(value, dict) or value.get('type') not in ('message', 'compaction'): continue
        message = value.get('message')
        if not isinstance(message, dict) or not isinstance(message.get('id'), str): raise Refused('format')
        owner = record['agent_id'] if record['agent_id'] is not None else 'agent-free:' + record['id']
        if message.get('role') not in ('user', 'assistant', 'toolResult'): raise Refused('format')
        metadata = message.get('metadata') or {}
        if not isinstance(metadata, dict): raise Refused('format')
        if metadata.get('conversation_id', record['id']) != record['id'] or metadata.get('agent_id', owner) != owner: raise Refused('format')
        mid = message['id']
        rows.pop(mid, None)
        rows[mid] = value
        if len(rows) > MAX_ROWS: raise Refused('too_large')
    active = record.get('in_context_message_ids')
    if not isinstance(active, list) or any(not isinstance(mid, str) for mid in active): raise Refused('format')
    # This is Letta's own localTranscriptRowsResult order, including rewind/compaction.
    return [rows[mid] for mid in active if mid in rows] if active else list(rows.values())

def main():
    status = identity()
    sid, agent, key = reference()
    storage = root()
    private_directory(storage)
    conversations = os.path.join(storage, 'conversations')
    private_directory(conversations)
    folder = os.path.join(conversations, base64.urlsafe_b64encode(key.encode()).decode().rstrip('='))
    if os.path.realpath(folder) != folder: raise Refused('unconfined')
    private_directory(folder)
    manifest_bytes = read_file(folder, 'manifest.json', MAX_META)
    manifest = json.loads(manifest_bytes)
    if manifest.get('schema_version') != 2 or manifest.get('message_format') != 'pi-session-entry-jsonl' or manifest.get('provider_stack') != 'pi-ai': raise Refused('format')
    record_bytes = read_file(folder, 'conversation.json', MAX_META)
    record = json.loads(record_bytes)
    if not isinstance(record, dict) or record.get('id') != sid or not isinstance(record.get('agent_id'), (str, type(None))) or (agent is not None and record.get('agent_id') != agent): raise Refused('format')
    if record['agent_id'] is None and record.get('agent_free') is not True: raise Refused('format')
    data = read_file(folder, 'messages.jsonl', MAX_FILE)
    rows = fold(data, record)
    if read_file(folder, 'conversation.json', MAX_META) != record_bytes or read_file(folder, 'manifest.json', MAX_META) != manifest_bytes: raise Refused('changed')
    identity()
    digest = hashlib.sha256(record_bytes + b'\0' + manifest_bytes + b'\0' + data).hexdigest()
    answer = json.dumps({'status':status, 'stamp':int(digest[:9],16) or 1, 'rows':rows}, separators=(',', ':'))
    if len(answer.encode()) > MAX_FILE: raise Refused('too_large')
    print(answer)

try: main()
except Refused as error: print(json.dumps({'error':str(error)}))
except FileNotFoundError: print(json.dumps({'error':'missing'}))
except (ValueError, KeyError, TypeError): print(json.dumps({'error':'format'}))
except Exception: print(json.dumps({'error':'unconfined'}))
"#;

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests;
