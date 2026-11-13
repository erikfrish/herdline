//! Kilo CLI (`@kilocode/cli` 7.8.27) history, read only, for exactly the
//! session Herdr reports in the pane. Kilo is a fork of OpenCode, but its
//! store has not followed upstream: one SQLite `kilo.db` with the v1
//! `message` and `part` tables, and none of OpenCode 2.2's `session_v2`,
//! `session_part` or `blob`. So this module shares no code path with
//! `opencode.rs`, and an OpenCode store is never read as Kilo's. It never
//! sends, steers, resumes or starts an agent: Kilo Chat is history only and the
//! pane's Terminal stays the way to talk to it.
//!
//! Every rule below names the pinned source it mirrors, Kilo-Org/kilocode tag
//! v7.8.27 (commit 03a6f07d053cd7e6aa88fa1e258e93510335259f).

use super::{
    Block, Entry, Identity, Image, Kind, Marker, Read, Response, Source, Usage, cap, text_block,
};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

const PAGE_ROOM: usize = 2 * 1024 * 1024;

/// The pane foreground names Herdr detects as Kilo (`src/detect/mod.rs:219`).
pub(super) const PROCESS_NAMES: [&str; 2] = ["kilo", "kilo-code"];

/// Typed refusals, each one Chat says with its general text.
/// The store lacks the v1 `session`/`message`/`part` columns this reads.
pub(super) const STORE_LAYOUT: &str = "kilo_store_layout";
/// The session exists only in the experimental v2 event store
/// (`session_message` with `seq`), which this reader does not decode.
pub(super) const EVENT_STORE: &str = "kilo_event_store";
/// `KILO_DB` or the data dir resolves outside the SSH user's home.
pub(super) const OUTSIDE_HOME: &str = "kilo_store_outside_home";
/// `KILO_DB=:memory:`: the history is in the agent's memory, not on disk.
pub(super) const IN_MEMORY: &str = "kilo_store_in_memory";
/// SQLite cannot open the store read-only, such as a WAL store without its
/// sidecars in a folder this user cannot write.
pub(super) const UNREADABLE: &str = "kilo_store_unreadable";

/// The exact session id Kilo writes: `ses_` and its ascending identifier
/// (`schema/src/session-id.ts:5`, `identifier.ts:1-2`), bounded.
pub(super) fn session_id(value: &str) -> bool {
    value.strip_prefix("ses_").is_some_and(|rest| {
        (1..=64).contains(&rest.len()) && rest.bytes().all(|byte| byte.is_ascii_alphanumeric())
    })
}

pub(super) fn page(
    exec: &Exec,
    session: &str,
    pane: &str,
    expect: &Identity,
    read: Read,
    reset: bool,
) -> Result<Response> {
    let output = exec.run(
        format!(
            "/bin/sh -c {}",
            shell_quote(&query_script(session, pane, expect, &options(read)))
        ),
        Duration::from_secs(60),
        PAGE_ROOM,
    )?;
    if output.exit_status != Some(0) {
        return Err(Error::NoTranscript);
    }
    response(&serde_json::from_slice(&output.tail)?, expect, reset)
}

fn options(read: Read) -> Value {
    let (mode, cursor) = match read {
        Read::Tail => ("tail", 0),
        Read::After { offset } => ("after", offset),
        Read::Before { offset } => ("before", offset),
    };
    json!({"mode":mode,"cursor":cursor})
}

fn query_script(session: &str, pane: &str, expect: &Identity, options: &Value) -> String {
    format!(
        "python3 - {} {} {} <<'HERDLINE_KILO_PY'\n{}\nHERDLINE_KILO_PY",
        shell_quote(&serde_json::to_string(expect).unwrap_or_default()),
        shell_quote(&options.to_string()),
        shell_quote(&super::instance_script(session, pane)),
        PYTHON
    )
}

/// The host's answer as a page, or the typed reason there is none.
fn response(reply: &Value, expect: &Identity, reset: bool) -> Result<Response> {
    if reply["changed"] == true {
        return Err(Error::NoTranscript);
    }
    if let Some(format) = reply["format"].as_str() {
        return Err(Error::UnsupportedFormat(
            [
                STORE_LAYOUT,
                EVENT_STORE,
                OUTSIDE_HOME,
                IN_MEMORY,
                UNREADABLE,
            ]
            .into_iter()
            .find(|known| *known == format)
            .ok_or(Error::BadReply)?,
        ));
    }
    if reply.get("error").is_some() {
        return Err(Error::NoTranscript);
    }
    let rows = reply["rows"].as_array().ok_or(Error::BadReply)?;
    Ok(Response {
        identity: expect.clone(),
        status: reply["status"].as_str().unwrap_or("unknown").to_owned(),
        reset: reset || reply["reset"] == true,
        size: reply["size"].as_u64().ok_or(Error::BadReply)?,
        start: reply["start"].as_u64().ok_or(Error::BadReply)?,
        end: reply["end"].as_u64().ok_or(Error::BadReply)?,
        has_older: reply["has_older"].as_bool().ok_or(Error::BadReply)?,
        entries: rows.iter().filter_map(entry).collect(),
    })
}

/// One `message` row with its parts, keyed by its place in Kilo's order and
/// its message id (the host's KEY encoding). The bytes of an
/// image never leave the host: it shows as a placeholder Chat cannot open,
/// and any other file is named, never opened.
fn entry(row: &Value) -> Option<Entry> {
    let offset = row["id"].as_u64()?;
    let info = &row["info"];
    let mut blocks = Vec::new();
    let (mut kind, role) = match info["role"].as_str() {
        // An assistant `summary: true` message is a compaction's summary
        // (`schema/src/v1/session.ts:516`).
        Some("assistant") if info["summary"] == true => (Kind::Compaction, None),
        Some(role @ ("user" | "assistant")) => (Kind::Message, Some(role.to_owned())),
        other => {
            let name = if row["bad"] == true {
                "unreadable"
            } else {
                other.unwrap_or("none")
            };
            blocks.push(text_block(
                &format!("Unsupported Kilo message: {}", cap(name, 32).0),
                false,
            ));
            (Kind::Message, Some("system".to_owned()))
        }
    };
    let mut images = 0;
    let mut image = || {
        let part = images;
        images += 1;
        Image {
            part,
            source: Source::Unreadable,
        }
    };
    for part in row["parts"].as_array().into_iter().flatten() {
        match part["t"].as_str().unwrap_or_default() {
            // `synthetic` text is context Kilo adds, never shown in its TUI.
            "text" if part["synthetic"] == true => {}
            "text" => blocks.push(text_block(part["text"].as_str().unwrap_or_default(), false)),
            "reasoning" => {
                if let Some(text) = part["text"].as_str().filter(|text| !text.is_empty()) {
                    blocks.push(text_block(text, true));
                }
            }
            "file" if part["image"] == true => blocks.push(Block::Image(image())),
            "file" => blocks.push(text_block(&file_name(part), false)),
            "tool" => {
                let call_id = part["call"].as_str().unwrap_or_default().to_owned();
                let name = cap(part["name"].as_str().unwrap_or_default(), 128).0;
                let (args, truncated) = cap(
                    part["input"].as_str().unwrap_or_default(),
                    super::ARGS_LIMIT,
                );
                blocks.push(Block::ToolCall {
                    call_id: call_id.clone(),
                    name: name.clone(),
                    args,
                    intent: None,
                    truncated,
                });
                let is_error = part["status"] == "error";
                if is_error || part["status"] == "completed" {
                    let output = part[if is_error { "error" } else { "output" }]
                        .as_str()
                        .unwrap_or_default();
                    let (text, truncated) = cap(
                        &crate::herdr_setup::plain_text(output.as_bytes()),
                        super::RESULT_LIMIT,
                    );
                    let count = part["images"].as_u64().unwrap_or(0).min(64);
                    blocks.push(Block::ToolResult {
                        call_id,
                        name,
                        text,
                        is_error,
                        images: (0..count).map(|_| image()).collect(),
                        truncated,
                        exit_code: None,
                        answers: Vec::new(),
                        timed_out: false,
                        subagents: Vec::new(),
                    });
                }
            }
            // The user turn that asked for a compaction carries no content.
            "compaction" => kind = Kind::Other,
            // Bookkeeping Kilo's own view does not show as content.
            "step-start" | "step-finish" | "snapshot" | "patch" => {}
            // The host kept only the parts that fit the page budget.
            "cut" => blocks.push(Block::Text {
                text: "[Message too large to show in full]".to_owned(),
                truncated: true,
            }),
            other => blocks.push(text_block(&format!("[{}]", cap(other, 32).0), false)),
        }
    }
    if let Some(name) = info["error"]["name"].as_str() {
        // MessageAbortedError (schema MessageV2): the turn was stopped; it names no one.
        if name == "MessageAbortedError" {
            blocks.push(Block::Marker {
                marker: Marker::Stopped,
                text: String::new(),
                truncated: false,
            });
        } else {
            let message = info["error"]["message"]
                .as_str()
                .filter(|text| !text.is_empty());
            blocks.push(text_block(
                &format!("[{}]", cap(message.unwrap_or(name), 512).0),
                false,
            ));
        }
    }
    if kind == Kind::Other {
        blocks.clear();
    }
    let tokens = &info["tokens"];
    let usage = tokens.as_object().map(|_| {
        let input = tokens["input"].as_u64().unwrap_or(0)
            + tokens["cache"]["read"].as_u64().unwrap_or(0)
            + tokens["cache"]["write"].as_u64().unwrap_or(0);
        let output =
            tokens["output"].as_u64().unwrap_or(0) + tokens["reasoning"].as_u64().unwrap_or(0);
        Usage {
            input,
            output,
            total_tokens: input + output,
            cost: info["cost"]
                .as_f64()
                .filter(|cost| cost.is_finite() && *cost >= 0.0),
        }
    });
    Some(Entry {
        id: row["mid"].as_str().map(str::to_owned),
        parent_id: None,
        offset,
        end: offset.checked_add(1)?,
        kind,
        role: (kind == Kind::Message).then_some(role).flatten(),
        blocks,
        timestamp: row["ts"].as_u64(),
        usage,
        stop_reason: info["finish"].as_str().map(|reason| cap(reason, 32).0),
        steer: None,
        subagent_ends: Vec::new(),
    })
}

fn file_name(part: &Value) -> String {
    let name = part["name"].as_str().filter(|name| !name.is_empty());
    format!(
        "[file: {}]",
        cap(name.or(part["mime"].as_str()).unwrap_or("unknown"), 256).0
    )
}

/// The host side. The store comes from this shell's own HOME, XDG_DATA_HOME
/// and KILO_DB; no other process's environment is read. An agent started
/// with values this shell lacks is not found there, never guessed. A file
/// part's `url` (inline base64 or a `file://` path) is never returned and
/// never opened.
const PYTHON: &str = r#"import json, os, re, sqlite3, stat, subprocess, sys, urllib.parse, zlib
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
LIMIT=200
BUDGET=1572864
# Rust's per-item caps (transcript.rs TEXT_LIMIT, RESULT_LIMIT, ARGS_LIMIT) in
# UTF-8 bytes, plus a few so Rust still sees the field as cut and says so.
TEXT=65536+4; RESULT=16384+4; ARGS=8192+4
# Kilo lists a session's messages by (time_created, id) (opencode/src/
# session/message-v2.ts:585; index message_session_time_created_id_idx,
# core/src/session/sql.ts:86; time_created NOT NULL, core/src/database/
# schema.sql.ts:4-5). An offset is a message's place in that order times KEY
# plus 20 bits of its id's CRC, so a later page can tell that the place it
# last showed now holds another message (a revert, an import or a late older
# message shifted the order) and starts over from the tail.
KEY=1<<20
NAMES=('kilo','kilo-code')
ID=re.compile(r'^ses_[A-Za-z0-9]{1,64}$')
# The v1 columns this reads (packages/core/src/session/sql.ts:24-114).
NEED={'session':{'id'},'message':{'id','session_id','time_created','data'},'part':{'id','message_id','session_id','data'}}

def dump(value): return json.dumps(value,separators=(',',':'),ensure_ascii=False)

def check(mid): return zlib.crc32(str(mid).encode())&(KEY-1)

def identity():
    result=subprocess.run(['/bin/sh','-c',report],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20)
    raw=result.stdout
    if result.returncode or len(raw)>65536 or not raw.startswith(b'@@pane\n'): raise ValueError('report')
    first,proc=raw[len(b'@@pane\n'):].split(b'\n@@proc\n',1)
    pane=json.loads(first)['result']['pane']
    try: process=json.loads(proc).get('result',{}).get('process_info',{})
    except ValueError: process={}
    ref=pane['agent_session']
    fg=process.get('foreground_processes',[])
    pid=next((p.get('pid') for name in NAMES for p in fg if p.get('name')==name),None)
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:kilo'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown')

def environment():
    return {key:os.environ[key] for key in ('HOME','XDG_DATA_HOME','KILO_DB') if key in os.environ}

class Refused(Exception): pass

def inside(root,path): return os.path.commonpath([root,path])==root

# core/src/global.ts:21-22 data = xdgData/kilo, CR/LF stripped; xdg-basedir
# takes XDG_DATA_HOME, else HOME/.local/share. database.ts:50-60 KILO_DB:
# ':memory:' or absolute as is, else under data; otherwise data/kilo.db. A
# channel db (kilo-<channel>.db) or a legacy opencode-<channel>.db is never
# tried. What cannot be placed inside this user's home is refused before
# anything there is opened.
def store(env):
    home=os.path.realpath(os.environ['HOME'])
    clean=lambda value: re.sub(r'[\r\n]+','',value)
    xdg=clean(env.get('XDG_DATA_HOME','')) or os.path.join(clean(env['HOME']),'.local','share')
    if not xdg.startswith('/'): raise Refused('kilo_store_outside_home')
    data=os.path.join(xdg,'kilo')
    value=env.get('KILO_DB','')
    if value==':memory:': raise Refused('kilo_store_in_memory')
    db=value if value.startswith('/') else os.path.join(data,value or 'kilo.db')
    db=os.path.realpath(db)
    if not inside(home,db): raise Refused('kilo_store_outside_home')
    return db

# mode=ro, never immutable: Kilo is a live writer, and an immutable read can
# see stale or torn pages. A store SQLite cannot open read-only is refused,
# with no fallback. Only a regular file is opened, by the one resolved path:
# a FIFO or device there would block or misread, and a symlinked -wal or -shm
# sidecar would let SQLite read outside the checked path. On a WAL store
# with no -wal or -shm and a writable dir, a read-only open creates both,
# owned by this user and left after close: -wal empty, -shm only SQLite's
# wal-index. It never writes the db (query_only, and a read-only close
# skips the checkpoint).
def connect(db):
    try: st=os.stat(db)
    except FileNotFoundError: raise LookupError()
    except OSError: raise Refused('kilo_store_unreadable')
    if not stat.S_ISREG(st.st_mode): raise Refused('kilo_store_unreadable')
    if any(os.path.islink(db+side) for side in ('-wal','-shm')): raise Refused('kilo_store_unreadable')
    try:
        fd=os.open(db,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        try:
            opened=os.fstat(fd)
            header=os.read(fd,100)
        finally: os.close(fd)
    except OSError: raise Refused('kilo_store_unreadable')
    if (opened.st_dev,opened.st_ino)!=(st.st_dev,st.st_ino) or not stat.S_ISREG(opened.st_mode): raise Refused('kilo_store_unreadable')
    if len(header)<100 or not header.startswith(b'SQLite format 3\x00'): raise LookupError()
    try:
        conn=sqlite3.connect('file:'+urllib.parse.quote(db,safe='/')+'?mode=ro',uri=True,timeout=5)
        conn.row_factory=sqlite3.Row
        conn.execute('PRAGMA query_only=ON')
        conn.execute('BEGIN')
        conn.execute('SELECT count(*) FROM sqlite_master').fetchone()
    except sqlite3.DatabaseError: raise Refused('kilo_store_unreadable')
    return conn

def columns(conn,table): return {row[1] for row in conn.execute('PRAGMA table_info(%s)'%table)}

# Cut to `limit` UTF-8 bytes on a character boundary.
def clip(text,limit): return text if len(text)*4<=limit else text.encode()[:limit].decode(errors='ignore')

def text(value): return value if isinstance(value,str) else ''

def load(raw):
    try: value=json.loads(raw)
    except (TypeError,ValueError): return None
    return value if isinstance(value,dict) else None

def image(mime): return isinstance(mime,str) and mime.lower().startswith('image/')

# schema/src/v1/session.ts:102-361 Part: only what Chat shows, never a url.
def part(raw):
    p=load(raw)
    if p is None: return {'t':'unreadable'}
    kind=p.get('type')
    if kind in ('text','reasoning'): return {'t':kind,'text':clip(text(p.get('text')),TEXT),'synthetic':p.get('synthetic') is True}
    if kind=='file': return {'t':'file','image':image(p.get('mime')),'mime':text(p.get('mime'))[:128],'name':text(p.get('filename'))[:256]}
    if kind=='tool':
        state=p.get('state') if isinstance(p.get('state'),dict) else {}
        attachments=state.get('attachments') if isinstance(state.get('attachments'),list) else []
        return {'t':'tool','call':text(p.get('callID'))[:256],'name':text(p.get('tool'))[:128],'status':text(state.get('status'))[:32],
            'input':clip(dump(state.get('input',{})),ARGS),'output':clip(text(state.get('output')),RESULT),'error':clip(text(state.get('error')),RESULT),
            'images':sum(1 for a in attachments if isinstance(a,dict) and image(a.get('mime')))}
    return {'t':kind if isinstance(kind,str) and kind else 'unknown'}

# schema/src/v1/session.ts:374-535 User/Assistant: role, summary, error,
# finish, usage.
def info(data):
    error=data.get('error') if isinstance(data.get('error'),dict) else {}
    details=error.get('data') if isinstance(error.get('data'),dict) else {}
    out={'role':text(data.get('role')),'summary':data.get('summary') is True,'finish':data.get('finish') if isinstance(data.get('finish'),str) else None}
    if text(error.get('name')): out['error']={'name':error['name'][:128],'message':clip(text(details.get('message')),516)}
    if isinstance(data.get('tokens'),dict): out['tokens']=data['tokens']; out['cost']=data.get('cost')
    return out

def project(conn,sid,place,row):
    data=load(row['data'])
    parts=[part(p[0]) for p in conn.execute('SELECT data FROM part WHERE message_id=? AND session_id=? ORDER BY id',(row['id'],sid))]
    return {'id':place*KEY+check(row['id']),'mid':row['id'],'ts':row['time_created'],'bad':data is None,'info':info(data or {}),'parts':parts}

# One message whose parts alone pass the budget keeps the parts that fit, in
# order, and a mark that the rest is cut, so it never fails the page.
def fit(item):
    parts=item['parts']; item['parts']=[]
    used=len(dump(item).encode())+64
    for p in parts:
        size=len(dump(p).encode())+1
        if used+size>BUDGET: break
        item['parts'].append(p); used+=size
    item['parts'].append({'t':'cut'})
    return item

def main():
    got,status=identity()
    if got!=expect or got['agent']!='kilo' or got['kind']!='id' or not got['pid']: return {'changed':True}
    sid=got['path']
    if not ID.match(sid): return {'changed':True}
    db=store(environment())
    try: conn=connect(db)
    except LookupError: return {'error':'missing'}
    if any(not need<=columns(conn,table) for table,need in NEED.items()): raise Refused('kilo_store_layout')
    if conn.execute('SELECT 1 FROM session WHERE id=?',(sid,)).fetchone() is None: return {'error':'missing'}
    select='SELECT id,time_created,data FROM message WHERE session_id=? ORDER BY time_created,id LIMIT ? OFFSET ?'
    n=conn.execute('SELECT count(*) FROM message WHERE session_id=?',(sid,)).fetchone()[0]
    if n==0 and {'session_id','seq'}<=columns(conn,'session_message'):
        # kilocode_change sql.ts:144: v2 rows carry seq only when the
        # experimental event system wrote them; those are not decoded here.
        if conn.execute('SELECT 1 FROM session_message WHERE session_id=? AND seq IS NOT NULL LIMIT 1',(sid,)).fetchone(): raise Refused('kilo_event_store')
    size=n*KEY
    at=lambda place: conn.execute(select,(sid,1,place)).fetchone()
    mode=opts['mode']; cursor=int(opts.get('cursor',0))
    reset=mode=='tail'
    if mode=='after':
        # The message last shown must still be at its place. Kilo upserts a
        # message and its parts as it streams, so this page rereads it.
        last,mark=divmod(max(cursor-1,0),KEY)
        shown=at(last) if cursor<=size else None
        reset=shown is None or check(shown['id'])!=mark
        first=last
    elif mode=='before':
        end,mark=divmod(cursor,KEY)
        reset=cursor>size or (end<n and check(at(end)['id'])!=mark)
        first=max(0,end-LIMIT)
    if reset: first=max(0,n-LIMIT); end=n
    elif mode=='after': end=min(n,first+LIMIT)
    found=list(enumerate(conn.execute(select,(sid,end-first,first)).fetchall(),first))
    rows=[]; used=0
    newest_first=mode=='before' or reset
    for place,row in (found[::-1] if newest_first else found):
        item=project(conn,sid,place,row)
        packed=dump(item).encode()
        if used+len(packed)>BUDGET:
            if rows: break
            item=fit(item); packed=dump(item).encode()
        used+=len(packed); rows.append(item)
    if newest_first: rows.reverse()
    start=rows[0]['id'] if rows else (size if reset else cursor)
    end=rows[-1]['id']+1 if rows else (size if reset else cursor)
    older=rows[0]['id']//KEY>0 if rows else False
    conn.close()
    return {'status':status,'reset':reset,'size':size,'start':start,'end':end,'has_older':older,'rows':rows}
def answer():
    try: out=main()
    except Refused as refusal: out={'format':str(refusal)}
    # Every answer, a refusal or a missing session too, must describe the
    # process still in the pane: one that changed mid-read gives no answer.
    if not out.get('changed') and identity()[0]!=expect: return {'changed':True}
    return out
try:
    sys.stdout.buffer.write(dump(answer()).encode())
except Exception:
    sys.exit(1)
"#;

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests;
