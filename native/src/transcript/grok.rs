//! Grok Build (`grok`) history, read only, for exactly the session Herdr
//! reports in the pane. Grok appends every ACP `session/update` (and its own
//! `_x.ai/session/update` extensions) to one `updates.jsonl` per session.
//! This module never sends, steers, resumes or starts an agent: Grok Chat is
//! history only and the pane's Terminal stays the way to talk to it.
//!
//! Every rule below names the pinned source it mirrors: xai-org/grok-build
//! commit 2bdd1d6 (SOURCE_REV 559751fd), and the ACP schema of
//! agent-client-protocol v0.10.4, which it builds against.
//!
//! After an in-process `/new`, Herdr may keep reporting the old session id
//! (herdrdev/herdr#2681). Grok's own `active_sessions.json` is read only to
//! refuse then: the pane's process must have exactly one entry there, naming
//! Herdr's ref. Its entry never chooses, binds or shows a session.

use super::{Block, Entry, Identity, Image, Kind, Marker, Read, Response, Source, cap, text_block};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

const PAGE_ROOM: usize = 2 * 1024 * 1024;

/// Typed refusals, each one Chat says with its general text, except the
/// outside-home one, which shares OutsideHome's words.
/// `GROK_HOME`, the home it defaults to, or the session file resolves
/// outside the SSH user's home, or `GROK_HOME` is relative.
pub(super) const OUTSIDE_HOME: &str = "grok_store_outside_home";
/// More than one `sessions/*/<id>/updates.jsonl`, or more than one entry for
/// the pane's process in Grok's `active_sessions.json`: which one is the
/// pane's is not decided, so none is shown.
pub(super) const AMBIGUOUS: &str = "grok_session_ambiguous";
/// The pane's process has exactly one entry in `active_sessions.json`, and it
/// names another session than Herdr's ref (after an in-process `/new`,
/// herdr#2681). Neither is shown, and the entry's is never read instead.
pub(super) const STALE: &str = "grok_session_stale";
/// `active_sessions.json` is missing, unparseable, or has no entry for the
/// pane's process, so nothing confirms Herdr's ref is still open there.
pub(super) const UNREGISTERED: &str = "grok_session_unregistered";
/// The session file is not a regular file, or cannot be opened read-only.
pub(super) const UNREADABLE: &str = "grok_store_unreadable";
/// The session file is past the bound one poll parses in full.
pub(super) const TOO_LARGE: &str = "grok_session_too_large";

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
        "python3 - {} {} {} <<'HERDLINE_GROK_PY'\n{}\nHERDLINE_GROK_PY",
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
                OUTSIDE_HOME,
                AMBIGUOUS,
                STALE,
                UNREGISTERED,
                UNREADABLE,
                TOO_LARGE,
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

/// One host row, keyed by the byte offset of its first line. The bytes of an
/// image never leave the host: it shows as a placeholder Chat cannot open.
fn entry(row: &Value) -> Option<Entry> {
    let offset = row["id"].as_u64()?;
    let mut images = 0;
    let mut image = || {
        let part = images;
        images += 1;
        Image {
            part,
            source: Source::Unreadable,
        }
    };
    let mut blocks = Vec::new();
    let role = match row["kind"].as_str().unwrap_or_default() {
        kind @ ("user" | "assistant") => {
            for part in row["parts"].as_array().into_iter().flatten() {
                match part["t"].as_str().unwrap_or_default() {
                    "text" => {
                        blocks.push(text_block(part["text"].as_str().unwrap_or_default(), false))
                    }
                    "thought" => {
                        blocks.push(text_block(part["text"].as_str().unwrap_or_default(), true))
                    }
                    "image" => blocks.push(Block::Image(image())),
                    "note" => blocks.push(text_block(
                        &format!(
                            "[{}]",
                            cap(part["text"].as_str().unwrap_or_default(), 512).0
                        ),
                        false,
                    )),
                    // The host kept only the parts that fit the page budget.
                    "cut" => blocks.push(cut()),
                    _ => {}
                }
            }
            kind
        }
        "tool" => {
            let call = &row["call"];
            let call_id = cap(call["id"].as_str().unwrap_or_default(), 256).0;
            let name = cap(call["name"].as_str().unwrap_or_default(), 128).0;
            let (args, truncated) = cap(
                call["input"].as_str().unwrap_or_default(),
                super::ARGS_LIMIT,
            );
            blocks.push(Block::ToolCall {
                call_id,
                name,
                args,
                intent: None,
                truncated,
            });
            if row["result"].is_object() {
                blocks.push(result(&row["result"], &mut image));
            }
            "assistant"
        }
        "result" => {
            blocks.push(result(&row["result"], &mut image));
            "assistant"
        }
        "plan" => {
            // Each item a marker the app words; a host script without `entries` sends the joined text.
            match row["entries"].as_array().filter(|items| !items.is_empty()) {
                Some(items) => blocks.extend(items.iter().map(|item| {
                    let (text, truncated) = cap(item["text"].as_str().unwrap_or_default(), 512);
                    let marker = if item["done"] == true {
                        Marker::Done
                    } else {
                        Marker::ToDo
                    };
                    Block::Marker {
                        marker,
                        text,
                        truncated,
                    }
                })),
                None => blocks.push(text_block(row["text"].as_str().unwrap_or_default(), false)),
            }
            "assistant"
        }
        other => {
            let name = if other == "bad" {
                "unreadable"
            } else {
                row["name"].as_str().unwrap_or("none")
            };
            blocks.push(text_block(
                &format!("Unsupported Grok update: {}", cap(name, 64).0),
                false,
            ));
            "system"
        }
    };
    if row["cut"] == true {
        blocks.push(cut());
    }
    Some(Entry {
        id: None,
        parent_id: None,
        offset,
        end: offset.checked_add(1)?,
        kind: Kind::Message,
        role: Some(role.to_owned()),
        blocks,
        timestamp: row["ts"].as_u64(),
        usage: None,
        stop_reason: None,
        steer: None,
        subagent_ends: Vec::new(),
    })
}

fn cut() -> Block {
    Block::Text {
        text: "[Message too large to show in full]".to_owned(),
        truncated: true,
    }
}

/// A tool's final state: ACP `completed` or `failed`.
fn result(result: &Value, image: &mut impl FnMut() -> Image) -> Block {
    let is_error = result["status"] == "failed";
    let (text, truncated) = cap(
        &crate::herdr_setup::plain_text(result["output"].as_str().unwrap_or_default().as_bytes()),
        super::RESULT_LIMIT,
    );
    let count = result["images"].as_u64().unwrap_or(0).min(64);
    Block::ToolResult {
        call_id: cap(result["id"].as_str().unwrap_or_default(), 256).0,
        name: cap(result["name"].as_str().unwrap_or_default(), 128).0,
        text,
        is_error,
        images: (0..count).map(|_| image()).collect(),
        truncated,
        exit_code: None,
        answers: Vec::new(),
        timed_out: false,
        subagents: Vec::new(),
    }
}

/// The host side. The store comes from this shell's own HOME and GROK_HOME;
/// no other process's environment is read. An agent started with a
/// GROK_HOME this shell lacks is not found there (missing), never guessed.
/// Image data and resource contents are never returned.
const PYTHON: &str = r#"import json, os, re, stat, subprocess, sys
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
LIMIT=200
BUDGET=1572864
# Every poll parses the whole file (a rewind can cut any earlier turn), so a
# larger one is refused before it is read.
MAX_FILE=33554432
# Rust's per-item caps (transcript.rs TEXT_LIMIT, RESULT_LIMIT, ARGS_LIMIT) in
# UTF-8 bytes, plus a few so Rust still sees the field as cut and says so.
TEXT=65536+4; RESULT=16384+4; ARGS=8192+4
ACP='session/update'; XAI='_x.ai/session/update'
# ACP v0.10.4 SessionUpdate kinds that are session state, not conversation.
QUIET={'available_commands_update','current_mode_update','config_option_update','session_info_update'}
# xai-grok-shell extensions/notification.rs:517-1195 SessionUpdate at the pin:
# Grok's own status, telemetry and bookkeeping, none of it conversation.
STATUS=set('''diff_review retry_state auto_compact_started auto_compact_completed auto_compact_failed memory_flush_started
memory_flush_completed memory_capture_activity memory_dream_queued memory_dream_started memory_dream_completed memory_session_saved
auto_compact_cancelled auto_continue_completed feedback_request relay_sync_status auto_recovery_started auto_recovery_exhausted
hook_annotation hook_run_started hook_execution hooks_changed plugins_changed plugin_updates_installed session_status
session_summary_generated session_recap session_recap_unavailable last_turn_summary compaction_checkpoint task_completed
subagent_spawned subagent_progress subagent_finished task_backgrounded background_tasks scheduled_task_created
scheduled_task_fired scheduled_task_deleted monitor_event model_auto_switched model_changed served_model tool_call_delta_chunk
image_compressed image_dropped memory_files workflow_updated goal_updated pending_interaction interaction_resolved plan_kept
plan_cleared plan_executing turn_completed response_started reasoning_completed response_completed turn_usage'''.split())
NAMES=('grok',)

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
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:grok'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown')

def environment():
    return {key:os.environ[key] for key in ('HOME','GROK_HOME') if key in os.environ}

class Refused(Exception): pass

def inside(root,path): return os.path.commonpath([root,path])==root

# xai-dirs/src/lib.rs:43-59: GROK_HOME verbatim when non-empty, else the
# canonical home's .grok. A relative GROK_HOME would resolve against Grok's
# cwd, which this cannot know, so it is refused, never guessed.
def store(env):
    home=os.path.realpath(os.environ['HOME'])
    root=env.get('GROK_HOME','')
    if not root:
        own=env.get('HOME','')
        if not own.startswith('/'): raise Refused('grok_store_outside_home')
        root=os.path.join(own,'.grok')
    if not root.startswith('/'): raise Refused('grok_store_outside_home')
    root=os.path.realpath(root)
    if not inside(home,root): raise Refused('grok_store_outside_home')
    return home,os.path.join(root,'sessions')

# xai-grok-config/src/paths.rs:132-141 sessions/<encoded cwd>/<id>, found by
# the id directory one level down, as Grok's own lookup scans
# (leader_cluster/scenarios.rs:353-360). Never a cwd or latest-file pick.
def find(home,sessions,sid):
    try: dirs=[entry.path for entry in os.scandir(sessions)]
    except FileNotFoundError: raise LookupError()
    except OSError: raise Refused('grok_store_unreadable')
    found=[path for path in (os.path.join(d,sid,'updates.jsonl') for d in dirs) if os.path.lexists(path)]
    if not found: raise LookupError()
    if len(found)>1: raise Refused('grok_session_ambiguous')
    real=os.path.realpath(found[0])
    if not inside(home,real): raise Refused('grok_store_outside_home')
    return real

# xai-grok-active-sessions/src/lib.rs:16-21, :23, :35-37: grok_home's
# active_sessions.json, a list of {session_id, pid, cwd, opened_at}, each
# written with the pager's own pid (pager app/effects/mod.rs:133-141) when a
# session is created or loaded, and dropped when one is left (/new:
# dispatch/session/lifecycle.rs:380). Used only to refuse: the pane's
# process needs exactly one entry, naming sid. Grok reads a corrupt file as
# empty (lib.rs:111-127); so does this, and an empty one confirms nothing.
REGISTRY_MAX=1048576
def registered(home,sessions,pid,sid):
    try:
        real=os.path.realpath(os.path.join(os.path.dirname(sessions),'active_sessions.json'))
        st=os.stat(real)
    except FileNotFoundError: raise Refused('grok_session_unregistered')
    except OSError: raise Refused('grok_store_unreadable')
    if not inside(home,real): raise Refused('grok_store_outside_home')
    if not stat.S_ISREG(st.st_mode) or st.st_size>REGISTRY_MAX: raise Refused('grok_store_unreadable')
    try:
        fd=os.open(real,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        try: data=os.read(fd,REGISTRY_MAX+1)
        finally: os.close(fd)
    except OSError: raise Refused('grok_store_unreadable')
    try: entries=json.loads(data) if data.strip() else []
    except ValueError: entries=[]
    if not isinstance(entries,list) or not all(isinstance(e,dict) and isinstance(e.get('session_id'),str) and index(e.get('pid')) is not None for e in entries): entries=[]
    ids=[e['session_id'] for e in entries if e['pid']==pid]
    if not ids: raise Refused('grok_session_unregistered')
    if len(ids)>1: raise Refused('grok_session_ambiguous')
    if ids[0]!=sid: raise Refused('grok_session_stale')

# Only a regular file is opened, by its one resolved path: a FIFO or device
# there would block or misread.
def load(real):
    try: st=os.stat(real)
    except OSError: raise Refused('grok_store_unreadable')
    if not stat.S_ISREG(st.st_mode): raise Refused('grok_store_unreadable')
    try: fd=os.open(real,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except OSError: raise Refused('grok_store_unreadable')
    try:
        opened=os.fstat(fd)
        if (opened.st_dev,opened.st_ino)!=(st.st_dev,st.st_ino) or not stat.S_ISREG(opened.st_mode): raise Refused('grok_store_unreadable')
        if opened.st_size>MAX_FILE: raise Refused('grok_session_too_large')
        chunks=[]; left=opened.st_size
        while left>0:
            chunk=os.read(fd,min(left,1048576))
            if not chunk: break
            chunks.append(chunk); left-=len(chunk)
    finally: os.close(fd)
    data=b''.join(chunks)
    # A line still being written is left for a later read.
    return data[:data.rfind(b'\n')+1]

def lines(data):
    at=0
    for raw in data.splitlines(keepends=True):
        try: value=json.loads(raw)
        except ValueError: value=None
        yield at,at+len(raw),value if isinstance(value,dict) else None
        at+=len(raw)

def update(value):
    params=value.get('params') if value else None
    u=params.get('update') if isinstance(params,dict) else None
    return u if isinstance(u,dict) and isinstance(u.get('sessionUpdate'),str) else None

def index(value): return value if isinstance(value,int) and not isinstance(value,bool) and value>=0 else None

# session/storage/mod.rs:1600-1631 rewind_step_for_line.
def step(value):
    u=update(value)
    if u is None: return ('other',None)
    xai=value.get('method')==XAI
    if xai and u['sessionUpdate']=='rewind_marker' and index(u.get('target_prompt_index')) is not None: return ('rewind',u['target_prompt_index'])
    meta=u.get('_meta') if isinstance(u.get('_meta'),dict) else {}
    if not xai and meta.get('hostTurn') is not True and u['sessionUpdate']=='user_message_chunk': return ('user',index(meta.get('promptIndex')))
    return ('other',None)

# session/storage/mod.rs:1010-1056 UserRunTurnTracker.
class Tracker:
    def __init__(self): self.seen=False; self.in_user=False; self.run=None
    def user(self,pi):
        if pi is not None: self.seen=True
        counts=pi is not None if self.seen else True
        if not self.in_user: new=True
        elif self.seen or pi is not None: new=pi!=self.run
        else: new=False
        self.in_user=True
        if new: self.run=pi; return counts
        return False
    def other(self): self.in_user=False; self.run=None

# session/storage/mod.rs:1569-1596 filter_rewind_by: a marker truncates the
# survivors back to its target prompt's first chunk.
def live(items):
    if not any(step(value)[0]=='rewind' for _,_,value in items): return items
    result=[]; starts=[]; tracker=Tracker()
    for item in items:
        kind,arg=step(item[2])
        if kind=='rewind':
            cut=starts[arg] if arg<len(starts) else len(result)
            del result[cut:]; del starts[arg:]; tracker.other()
            continue
        if kind=='user':
            if tracker.user(arg): starts.append(len(result))
        else: tracker.other()
        result.append(item)
    return result

def text(value): return value if isinstance(value,str) else ''

# Cut to `limit` UTF-8 bytes on a character boundary.
def clip(value,limit): return value if len(value)*4<=limit else value.encode()[:limit].decode(errors='ignore')

def dump(value): return json.dumps(value,separators=(',',':'),ensure_ascii=False)

# ACP ContentBlock: text, image, audio, resource_link, resource.
def block(content):
    if not isinstance(content,dict): return {'t':'note','text':'unreadable'}
    kind=content.get('type')
    if kind=='text': return {'t':'text','text':text(content.get('text'))}
    if kind=='image': return {'t':'image'}
    if kind=='resource_link': return {'t':'note','text':'file: '+(text(content.get('name')) or text(content.get('uri')))[:256]}
    if kind=='resource':
        res=content.get('resource') if isinstance(content.get('resource'),dict) else {}
        return {'t':'note','text':'resource: '+text(res.get('uri'))[:256]}
    return {'t':'note','text':(kind if isinstance(kind,str) and kind else 'unknown')[:64]}

# ACP ToolCallContent: content, diff, terminal; text kept, images counted.
def output(state):
    out=[]; images=0
    for item in state.get('content') or []:
        if not isinstance(item,dict): continue
        if item.get('type')=='content':
            part=block(item.get('content'))
            if part['t']=='image': images+=1
            else: out.append(part['text'])
        elif item.get('type')=='diff': out.append('[diff: %s]'%text(item.get('path'))[:256])
        elif item.get('type')=='terminal': out.append('[terminal]')
    raw=state.get('rawOutput')
    if not out and raw is not None: out.append(raw if isinstance(raw,str) else dump(raw))
    return '\n'.join(out),images

def plan(u):
    entries=u.get('entries') if isinstance(u.get('entries'),list) else []
    return '\n'.join('[%s] %s'%(text(e.get('status')),text(e.get('content'))) for e in entries if isinstance(e,dict)) or '[plan]'

def plan_items(u):
    entries=u.get('entries') if isinstance(u.get('entries'),list) else []
    return [{'done':e.get('status')=='completed','text':text(e.get('content'))} for e in entries if isinstance(e,dict)]

def stamp(value):
    ts=value.get('timestamp') if value else None
    return ts*1000 if index(ts) is not None else None

# The live lines as Chat rows: a run of user chunks (one prompt) or of agent
# message and thought chunks is one row; a tool call is a row, and its final
# update (completed or failed) the row of its result.
def rows_of(items):
    rows=[]; tools={}; run=None
    for at,end,value in items:
        u=update(value)
        if value is None:
            rows.append({'id':at,'kind':'bad'}); run=None; continue
        if u is None:
            rows.append({'id':at,'kind':'unknown','name':'none'}); run=None; continue
        kind=u['sessionUpdate']; xai=value.get('method')==XAI
        if xai and (kind in STATUS or kind=='rewind_marker'): continue
        if not xai and kind in ('user_message_chunk','agent_message_chunk','agent_thought_chunk'):
            role='user' if kind=='user_message_chunk' else 'assistant'
            meta=u.get('_meta') if isinstance(u.get('_meta'),dict) else {}
            pi=index(meta.get('promptIndex')) if role=='user' else None
            if run is None or run['kind']!=role or (role=='user' and pi is not None and run.get('pi') not in (None,pi)):
                run={'id':at,'kind':role,'ts':stamp(value),'parts':[],'pi':pi}; rows.append(run)
            part=block(u.get('content'))
            if kind=='agent_thought_chunk' and part['t']=='text': part['t']='thought'
            last=run['parts'][-1] if run['parts'] else None
            if last and part['t'] in ('text','thought') and last['t']==part['t']: last['text']+=part['text']
            else: run['parts'].append(part)
            continue
        run=None
        if not xai and kind in ('tool_call','tool_call_update'):
            cid=text(u.get('toolCallId'))
            state=tools.setdefault(cid,{'id':cid,'done':False})
            for key in ('title','kind','status','content','rawInput','rawOutput'):
                if key in u: state[key]=u[key]
            name=text(state.get('title')) or text(state.get('kind'))
            row=None
            if kind=='tool_call':
                raw=u.get('rawInput')
                row={'id':at,'kind':'tool','ts':stamp(value),'call':{'id':cid,'name':name,'input':raw if isinstance(raw,str) else dump(raw if raw is not None else {})}}
                rows.append(row)
            if state.get('status') in ('completed','failed') and not state['done']:
                state['done']=True
                body,images=output(state)
                result={'id':cid,'name':name,'status':state['status'],'output':body,'images':images}
                if row is not None: row['result']=result
                else: rows.append({'id':at,'kind':'result','ts':stamp(value),'result':result})
            continue
        if not xai and kind=='plan':
            rows.append({'id':at,'kind':'plan','ts':stamp(value),'text':plan(u),'entries':plan_items(u)}); continue
        if not xai and kind in QUIET: continue
        rows.append({'id':at,'kind':'unknown','name':kind})
    return rows

# Field clips by bytes, after merging; a row whose parts alone pass the
# budget keeps the parts that fit, in order, and says it is cut.
def shape(row):
    row.pop('pi',None)
    for part in row.get('parts',[]):
        if 'text' in part: part['text']=clip(part['text'],TEXT if part['t'] in ('text','thought') else 516)
    if 'call' in row:
        row['call']['input']=clip(row['call']['input'],ARGS); row['call']['name']=row['call']['name'][:128]
    if 'result' in row:
        row['result']['output']=clip(row['result']['output'],RESULT); row['result']['name']=row['result']['name'][:128]
    if 'text' in row: row['text']=clip(row['text'],TEXT)
    if 'name' in row: row['name']=row['name'][:64]
    packed=dump(row).encode()
    if len(packed)<=BUDGET: return row,packed
    parts=row.get('parts',[]); row['parts']=[]; row['cut']=True
    if 'result' in row: row['result']['output']=''
    used=len(dump(row).encode())+64
    for part in parts:
        size=len(dump(part).encode())+1
        if used+size>BUDGET: break
        row['parts'].append(part); used+=size
    return row,dump(row).encode()

def main():
    got,status=identity()
    if got!=expect or got['agent']!='grok' or got['kind']!='id' or not got['pid']: return {'changed':True}
    sid=got['path']
    if not re.match(r'^[A-Za-z0-9_-][A-Za-z0-9._-]{0,511}$',sid): return {'changed':True}
    home,sessions=store(environment())
    try: real=find(home,sessions,sid)
    except LookupError: return {'error':'missing'}
    registered(home,sessions,got['pid'],sid)
    data=load(real)
    size=len(data)
    rows=rows_of(live(list(lines(data))))
    mode=opts['mode']; cursor=int(opts.get('cursor',0))
    # Rows are keyed by the offset of their first line; a page ends one past
    # the last row's key, so the next one reads that row again as it streams.
    # When no live row starts there any more (a rewind, a rewritten or
    # shorter file), the page starts over from the tail.
    reset=mode=='tail' or (mode=='after' and not any(row['id']==cursor-1 for row in rows))
    if reset: found=rows[-LIMIT:]
    elif mode=='before': found=[row for row in rows if row['id']<cursor][-LIMIT:]
    else: found=[row for row in rows if row['id']>=cursor-1][:LIMIT]
    out=[]; used=0
    newest_first=mode=='before' or reset
    for row in (found[::-1] if newest_first else found):
        row,packed=shape(row)
        if used+len(packed)>BUDGET and out: break
        used+=len(packed); out.append(row)
    if newest_first: out.reverse()
    start=out[0]['id'] if out else (size if reset else cursor)
    end=out[-1]['id']+1 if out else (size if reset else cursor)
    older=any(row['id']<start for row in rows)
    after,_=identity()
    if after!=expect: return {'changed':True}
    # A /new while this read: the page is not the pane's any more.
    registered(home,sessions,got['pid'],sid)
    return {'status':status,'reset':reset,'size':size,'start':start,'end':end,'has_older':older,'rows':out}
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
