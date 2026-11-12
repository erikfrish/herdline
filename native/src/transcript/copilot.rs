//! GitHub Copilot CLI (`@github/copilot` 1.0.109) history, read only, for
//! exactly the session Herdr reports in the pane. Copilot writes one JSON
//! Lines event log per session, `session-state/<id>/events.jsonl` under
//! `COPILOT_HOME` (else `~/.copilot`). The format is closed source: every rule
//! below names the declaration it mirrors in the shipped SDK types,
//! `copilot-sdk/generated/session-events.d.ts` of 1.0.109. It never sends,
//! steers, resumes or starts an agent: Copilot Chat is history only and the
//! pane's Terminal stays the way to talk to it.
//!
//! This module has its own reader rather than the shared byte-window script:
//! Copilot rewrites the head of its log on `/rewind` and when it rotates the
//! journal, so every cursor is checked against the event it last named.

use super::{Block, Entry, Identity, Image, Kind, Marker, Read, Response, Source, cap, text_block};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

const PAGE_ROOM: usize = 2 * 1024 * 1024;

// The pane foreground is the native binary npm's loader starts, named
// `copilot` (Herdr `src/detect/mod.rs:212`): the agent's own name, so the
// shared foreground check applies as is.

/// Typed refusals, each one Chat says with its general text, except
/// [`OUTSIDE_HOME`].
/// `COPILOT_HOME` is relative, or the store resolves outside the SSH user's home.
pub(super) const OUTSIDE_HOME: &str = "copilot_store_outside_home";
/// The log is not a regular file opened without following a link.
pub(super) const UNREADABLE: &str = "copilot_session_unreadable";
/// The log is over the 32 MiB this reader takes.
pub(super) const TOO_LARGE: &str = "copilot_session_too_large";
/// The log's first record does not name the session Herdr reports.
pub(super) const MISMATCH: &str = "copilot_session_mismatch";

/// The id Copilot gives a session: a lowercase UUID. A prefix `--resume`
/// accepts is not an id.
pub(super) fn session_id(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 36
        && bytes.iter().enumerate().all(|(at, byte)| match at {
            8 | 13 | 18 | 23 => *byte == b'-',
            _ => byte.is_ascii_digit() || (b'a'..=b'f').contains(byte),
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
            shell_quote(&query_script(session, pane, expect, &options(read), None))
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

/// `environ` replaces `/proc/<pid>/environ` for the fixture tests only; every
/// production read passes `None`, so no app or host input can point it
/// elsewhere.
fn query_script(
    session: &str,
    pane: &str,
    expect: &Identity,
    options: &Value,
    environ: Option<&str>,
) -> String {
    format!(
        "python3 - {} {} {}{} <<'HERDLINE_COPILOT_PY'\n{}\nHERDLINE_COPILOT_PY",
        shell_quote(&serde_json::to_string(expect).unwrap_or_default()),
        shell_quote(&options.to_string()),
        shell_quote(&super::instance_script(session, pane)),
        environ
            .map(|path| format!(" {}", shell_quote(path)))
            .unwrap_or_default(),
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
            [OUTSIDE_HOME, UNREADABLE, TOO_LARGE, MISMATCH]
                .into_iter()
                .find(|known| *known == format)
                .ok_or(Error::BadReply)?,
        ));
    }
    if reply.get("error").is_some() {
        return Err(Error::NoTranscript);
    }
    let rows = reply["rows"].as_array().ok_or(Error::BadReply)?;
    let has_older = reply["has_older"].as_bool().ok_or(Error::BadReply)?;
    let mut entries = Vec::with_capacity(rows.len() + 1);
    // A head that starts at a journal link, not at the session's start: the
    // turns before it are in an archived segment this reader does not open.
    // Only on the page that reaches the head's first record.
    if reply["boundary"] == true && !has_older {
        entries.push(boundary());
    }
    entries.extend(rows.iter().filter_map(entry));
    Ok(Response {
        identity: expect.clone(),
        status: reply["status"].as_str().unwrap_or("unknown").to_owned(),
        reset: reset || reply["reset"] == true,
        size: reply["size"].as_u64().ok_or(Error::BadReply)?,
        start: reply["start"].as_u64().ok_or(Error::BadReply)?,
        end: reply["end"].as_u64().ok_or(Error::BadReply)?,
        has_older,
        entries,
    })
}

/// Where the shown history stops. It has no text: Chat draws its own.
/// Offset 0 sorts it first; the head's first record, at byte 0, is never an
/// entry.
fn boundary() -> Entry {
    Entry {
        id: None,
        parent_id: None,
        offset: 0,
        end: 0,
        kind: Kind::Boundary,
        role: None,
        blocks: Vec::new(),
        timestamp: None,
        usage: None,
        stop_reason: None,
        steer: None,
        subagent_ends: Vec::new(),
    }
}

/// One event as the host projected it, keyed by its line's byte range and
/// event id (the host's KEY encoding). The bytes of an image never leave the
/// host: it shows as a placeholder Chat cannot open, and an attached file is
/// named, never opened.
fn entry(row: &Value) -> Option<Entry> {
    let offset = row["o"].as_u64()?;
    let end = row["e"].as_u64()?;
    let string = |key: &str| row[key].as_str().unwrap_or_default();
    let mut images = 0;
    let mut image = || {
        let part = images;
        images += 1;
        Image { part, source: Source::Unreadable }
    };
    let (kind, role, blocks) = match string("t") {
        // UserMessageData: `content` as the timeline shows it, and the
        // attachments by name.
        "user" => {
            let mut blocks = vec![text_block(string("text"), false)];
            for attachment in row["att"].as_array().into_iter().flatten() {
                if attachment["image"] == true {
                    blocks.push(Block::Image(image()));
                } else {
                    let name = attachment["name"].as_str().unwrap_or("unknown");
                    blocks.push(text_block(&format!("[file: {}]", cap(name, 256).0), false));
                }
            }
            (Kind::Message, "user", blocks)
        }
        "assistant" => (Kind::Message, "assistant", vec![text_block(string("text"), false)]),
        "reasoning" => (Kind::Message, "reasoning", vec![text_block(string("text"), true)]),
        // ToolExecutionStartData.
        "call" => {
            let (args, truncated) = cap(string("args"), super::ARGS_LIMIT);
            let block = Block::ToolCall {
                call_id: string("call").to_owned(),
                name: cap(string("name"), 128).0,
                args,
                intent: None,
                truncated,
            };
            (Kind::Message, "tool", vec![block])
        }
        // ToolExecutionCompleteData: `success`, the result's text or the
        // error's message, and its image content blocks.
        "result" => {
            let (text, truncated) =
                cap(&crate::herdr_setup::plain_text(string("text").as_bytes()), super::RESULT_LIMIT);
            let count = row["images"].as_u64().unwrap_or(0).min(64);
            let block = Block::ToolResult {
                call_id: string("call").to_owned(),
                name: cap(string("name"), 128).0,
                text,
                is_error: row["ok"] != true,
                images: (0..count).map(|_| image()).collect(),
                truncated,
                exit_code: None,
                answers: Vec::new(),
                timed_out: false,
                subagents: Vec::new(),
            };
            (Kind::Message, "tool", vec![block])
        }
        // AbortData.reason: only the person's own stop says so; any other reason is a plain stop.
        "abort" => {
            let marker = if matches!(string("reason"), "user" | "user_initiated") { Marker::StoppedByYou } else { Marker::Stopped };
            (Kind::Message, "error", vec![Block::Marker { marker, text: String::new(), truncated: false }])
        }
        "error" => (Kind::Message, "error", vec![text_block(&format!("[{}]", cap(string("text"), 512).0), false)]),
        "compaction" => (Kind::Compaction, "", Vec::new()),
        _ => {
            let name = cap(row["name"].as_str().unwrap_or("unreadable"), 64).0;
            (Kind::Message, "system", vec![text_block(&format!("Unsupported Copilot event: {name}"), false)])
        }
    };
    Some(Entry {
        id: row["id"].as_str().map(str::to_owned),
        // One flat log: Chat walks no branch through it.
        parent_id: None,
        offset,
        end,
        kind,
        role: (kind == Kind::Message).then(|| role.to_owned()),
        blocks,
        timestamp: row["ts"].as_u64(),
        usage: None,
        stop_reason: None,
        steer: None,
        subagent_ends: Vec::new(),
    })
}

/// The host side. Reads of `/proc/<pid>/environ` keep only HOME and
/// COPILOT_HOME; nothing else from the agent's environment is returned,
/// logged or stored. An attachment's `path`, a blob's `data` and a
/// `session.binary_asset` are never returned, and no other file is opened.
const PYTHON: &str = r#"import json, os, re, stat, subprocess, sys, zlib
from datetime import datetime
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
environ_path = sys.argv[4] if len(sys.argv) > 4 else None
LIMIT=200
BUDGET=1572864
MAX=32<<20
# Rust's per-item caps (transcript.rs TEXT_LIMIT, RESULT_LIMIT, ARGS_LIMIT) in
# UTF-8 bytes, plus a few so Rust still sees the field as cut and says so.
TEXT=65536+4; RESULT=16384+4; ARGS=8192+4
# Copilot rewrites the head of its log on /rewind, and moves older events to
# an archived segment when it rotates. So a cursor is a line boundary's byte
# times KEY plus 20 bits of the CRC of the event id next to it: the line
# ending there for an `after` page, the line starting there for `before`.
# A later page that finds another event there, or a shorter file, starts
# over from the tail.
KEY=1<<20
NAMES=('copilot',)
ID=re.compile(r'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$')
# Persisted event types (no `ephemeral: true`) Chat has nothing to show for:
# session state, permissions, hooks, the system prompt, model-facing assets.
QUIET={'assistant.turn_start','assistant.turn_end','assistant.fusion_phase_completed','assistant.fusion_phase_failed',
 'external_tool.requested','hook.start','hook.end','human_response.recorded','instruction.update','mcp.oauth_recovery_completed',
 'permission.assentDetected','permission.carriedForward','permission.completed','permission.contextualAuthorization',
 'permission.messageAuthorization','permission.messageAuthorizationDegraded','permission.messageAuthorizationRead','permission.requested',
 'session.auto_mode_resolved','session.autopilot_objective_changed','session.binary_asset','session.canvas.recorded','session.canvas.removed',
 'session.compaction_start','session.completion_receipt','session.context_changed','session.context_cleared','session.fusion_completed',
 'session.fusion_resolved','session.fusion_route_failed','session.handoff','session.info','session.journal_link','session.mode_changed',
 'session.mode_notice_delivered','session.model_change','session.model_deselected','session.permission_recovery','session.permissions_changed',
 'session.plan_changed','session.remote_steerable_changed','session.resume','session.schedule_cancelled','session.schedule_created',
 'session.schedule_rearmed','session.session_limits_changed','session.shutdown','session.start','session.task_complete','session.truncation',
 'session.usage_checkpoint','session.warning','session.workspace_file_changed','skill.invoked','subagent.completed','subagent.configured',
 'subagent.deselected','subagent.failed','subagent.selected','subagent.started','system.message','system.notification',
 'tool.user_requested','tool_search.activated'}

def dump(value): return json.dumps(value,separators=(',',':'),ensure_ascii=False)

def check(eid): return zlib.crc32(str(eid).encode())&(KEY-1)

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
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:copilot'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown')

def environment(pid):
    wanted={'HOME','COPILOT_HOME'}
    env={}
    for field in open(environ_path or '/proc/%d/environ'%pid,'rb').read(262144).split(b'\0'):
        key,sep,value=field.partition(b'=')
        key=key.decode(errors='replace')
        if sep and key in wanted: env[key]=value.decode(errors='replace')
    return env

class Refused(Exception): pass

def inside(root,path): return os.path.commonpath([root,path])==root

# Help text "Where sessions are stored": ~/.copilot/session-state/<id>/
# (under $COPILOT_HOME when set). A relative COPILOT_HOME, or a store that
# cannot be placed inside this user's home, is refused before anything there
# is opened. --config-dir is not in the environment and is not followed.
def locate(env,sid):
    home=os.path.realpath(os.environ['HOME'])
    value=env.get('COPILOT_HOME','')
    if value:
        if not value.startswith('/'): raise Refused('copilot_store_outside_home')
        root=value
    else:
        own=env.get('HOME','')
        root=os.path.join(own if own.startswith('/') else home,'.copilot')
    root=os.path.realpath(root)
    if root==home or not inside(home,root): raise Refused('copilot_store_outside_home')
    path=os.path.join(root,'session-state',sid,'events.jsonl')
    if not inside(root,os.path.realpath(path)): raise Refused('copilot_store_outside_home')
    return path

# Only a regular file, opened by the one checked path without following a
# link: a FIFO or device there would block or misread.
def load(path):
    try: fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except FileNotFoundError: raise LookupError()
    except OSError: raise Refused('copilot_session_unreadable')
    try:
        st=os.fstat(fd)
        if not stat.S_ISREG(st.st_mode): raise Refused('copilot_session_unreadable')
        if st.st_size>MAX: raise Refused('copilot_session_too_large')
        chunks=[]; left=MAX+1
        while left>0:
            chunk=os.read(fd,min(left,1<<20))
            if not chunk: break
            chunks.append(chunk); left-=len(chunk)
    finally: os.close(fd)
    data=b''.join(chunks)
    if len(data)>MAX: raise Refused('copilot_session_too_large')
    # Only whole lines: the last one may still be being written.
    return data[:data.rfind(b'\n')+1]

def parse(line):
    try: value=json.loads(line)
    except ValueError: return None
    return value if isinstance(value,dict) else None

def text(value): return value if isinstance(value,str) else ''

# Cut to `limit` UTF-8 bytes on a character boundary.
def clip(value,limit): return value if len(value)*4<=limit else value.encode()[:limit].decode(errors='ignore')

def millis(stamp):
    try: return int(datetime.fromisoformat(text(stamp).replace('Z','+00:00')).timestamp()*1000)
    except (ValueError,OverflowError): return None

def image(mime): return isinstance(mime,str) and mime.lower().startswith('image/')

def attachment(a):
    if not isinstance(a,dict): return {'name':'unknown','image':False}
    name=text(a.get('displayName')) or text(a.get('title')) or text(a.get('type')) or 'unknown'
    return {'name':name[:256],'image':a.get('type') in ('blob','file') and image(a.get('mimeType'))}

# What Chat shows of one event, or None for one it has nothing to show for.
def project(v,names):
    if v is None: return {'t':'unknown','name':'unreadable'}
    # Not on disk by their schema; never shown if they are. A checkpoint
    # record is a replayed copy, not original history; a sub-agent's events
    # stay with it.
    if v.get('ephemeral') is True or v.get('checkpoint') is True or v.get('agentId') is not None: return None
    kind=text(v.get('type')); d=v.get('data') if isinstance(v.get('data'),dict) else {}
    if kind=='user.message':
        # Hidden skill or inter-agent prompts, and autopilot's own turns.
        if text(d.get('source')) or d.get('isAutopilotContinuation') is True: return None
        att=d.get('attachments') if isinstance(d.get('attachments'),list) else []
        return {'t':'user','text':clip(text(d.get('content')),TEXT),'att':[attachment(a) for a in att[:64]]}
    if kind=='assistant.message':
        content=text(d.get('content'))
        return {'t':'assistant','text':clip(content,TEXT)} if content else None
    if kind=='assistant.reasoning':
        content=text(d.get('content'))
        return {'t':'reasoning','text':clip(content,TEXT)} if content else None
    if kind=='tool.execution_start':
        call=text(d.get('toolCallId'))[:256]; name=text(d.get('toolTitle')) or text(d.get('toolName'))
        names[call]=name[:128]
        return {'t':'call','call':call,'name':name[:128],'args':clip(dump(d.get('arguments',{})),ARGS)}
    if kind=='tool.execution_complete':
        call=text(d.get('toolCallId'))[:256]
        result=d.get('result') if isinstance(d.get('result'),dict) else {}
        error=d.get('error') if isinstance(d.get('error'),dict) else {}
        ok=d.get('success') is True
        body=(text(result.get('detailedContent')) or text(result.get('content'))) if ok else text(error.get('message'))
        contents=result.get('contents') if isinstance(result.get('contents'),list) else []
        return {'t':'result','call':call,'name':names.get(call,''),'ok':ok,'text':clip(body,RESULT),
            'images':sum(1 for c in contents if isinstance(c,dict) and c.get('type')=='image')}
    if kind=='abort': return {'t':'abort','reason':text(d.get('reason'))[:64]}
    if kind=='session.error': return {'t':'error','text':clip(text(d.get('message')),516)}
    if kind=='session.compaction_complete': return None if text(d.get('error')) else {'t':'compaction'}
    if kind in QUIET: return None
    return {'t':'unknown','name':kind[:64] or 'none'}

def main():
    got,status=identity()
    if got!=expect or got['agent']!='copilot' or got['kind']!='id' or not got['pid']: return {'changed':True}
    sid=got['path']
    if not ID.match(sid): return {'changed':True}
    path=locate(environment(got['pid']),sid)
    try: data=load(path)
    except LookupError: return {'error':'missing'}
    size=len(data)
    boundary=False
    if size:
        # The head's first record names its session: session.start, or after
        # a rotation the journal link to its archived predecessor.
        head=parse(data[:data.find(b'\n')+1])
        first=text(head.get('type')) if head else ''
        hd=head.get('data') if head and isinstance(head.get('data'),dict) else {}
        if first not in ('session.start','session.journal_link') or hd.get('sessionId')!=sid: raise Refused('copilot_session_mismatch')
        boundary=first=='session.journal_link'
    def eid(lo,hi):
        v=parse(data[lo:hi])
        return v.get('id') if v else None
    def line_ending(at): return data.rfind(b'\n',0,at-1)+1
    def line_starting(at): return data.find(b'\n',at)+1
    mode=opts['mode']; cursor=int(opts.get('cursor',0))
    at,mark=divmod(cursor,KEY)
    reset=mode=='tail'
    if mode=='after' and at>0:
        reset=at>size or data[at-1:at]!=b'\n' or check(eid(line_ending(at),at))!=mark
    elif mode=='before' and at>0:
        reset=at>size or data[at-1:at]!=b'\n' or check(eid(at,line_starting(at)))!=mark
    names={}; rows=[]; used=0
    # One line into the page: 'skip' when it shows nothing, 'over' when it
    # does not fit (and is left out), 'full' when the page is now full.
    def take(lo,hi):
        nonlocal used
        v=parse(data[lo:hi])
        item=project(v,names)
        if item is None: return 'skip'
        mid=v.get('id') if v else None
        item['id']=mid if isinstance(mid,str) else None
        item['o']=lo*KEY+check(mid); item['e']=hi*KEY+check(mid)
        stamp=millis(v.get('timestamp')) if v else None
        if stamp is not None: item['ts']=stamp
        packed=len(dump(item).encode())
        # Every field is clipped, so one event alone always fits; the first
        # is taken regardless so a page never stalls empty.
        if used+packed>BUDGET and rows: return 'over'
        used+=packed; rows.append(item)
        return 'full' if len(rows)>=LIMIT else 'took'
    if reset or mode=='before':
        # Newest first, back from the end or from the cursor.
        end=size if reset else at; start=hi=end
        while hi>0:
            lo=line_ending(hi); taken=take(lo,hi)
            if taken=='over': break
            start=hi=lo
            if taken=='full': break
        rows.reverse()
    else:
        start=end=lo=at
        while lo<size:
            hi=line_starting(lo); taken=take(lo,hi)
            if taken=='over': break
            end=lo=hi
            if taken=='full': break
    # A result's tool name, from its start on this page in either direction.
    for row in rows:
        if row['t']=='result' and not row['name']: row['name']=names.get(row['call'],'')
    # Cursors carry the event next to them: the line starting at `start`,
    # the line ending at `end`.
    start_key=start*KEY+(check(eid(start,line_starting(start))) if start<size else 0)
    end_key=end*KEY+(check(eid(line_ending(end),end)) if end>0 else 0)
    after,_=identity()
    if after!=expect: return {'changed':True}
    return {'status':status,'reset':reset,'size':size,'start':start_key,'end':end_key,'has_older':start>0,'boundary':boundary,'rows':rows}
try:
    sys.stdout.buffer.write(dump(main()).encode())
except Refused as refusal:
    print(json.dumps({'format':str(refusal)}))
except Exception:
    sys.exit(1)
"#;

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests;
