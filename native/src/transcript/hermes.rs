//! Hermes (NousResearch hermes-agent v0.23.1, state schema 33) history, read
//! only, for exactly the session Herdr reports in the pane. Hermes keeps every
//! conversation in one SQLite `state.db`; this reads that session and the
//! compression segments it continues, in a read-only transaction, and never
//! sends, steers, resumes or starts an agent: Hermes Chat is history only and
//! the pane's Terminal stays the way to talk to it.
//!
//! Every rule below names the pinned source it mirrors, at tag v0.23.1
//! (commit 3b421578588243f920886e6fe79a6faa87ea026e). Another schema version is
//! refused rather than read with guessed columns.

use super::{Block, Entry, Identity, Image, Kind, Read, Response, Source, cap, text_block};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

const PAGE_ROOM: usize = 2 * 1024 * 1024;

/// The pane foreground names Herdr can report for Hermes, in the order the
/// host script also tries them. A `python3` foreground is not guessed to be
/// Hermes: without its process the store cannot be told, so the read refuses.
pub(super) const PROCESS_NAMES: [&str; 2] = ["hermes", "hermes-agent"];

/// The schema this reader was written against (`hermes_state_common.py:295`).
/// The host script gets it from here, and Chat's refusal names it.
pub(crate) const SCHEMA_VERSION: u32 = 33;

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
    json!({"mode":mode,"cursor":cursor,"schema":SCHEMA_VERSION})
}

fn query_script(session: &str, pane: &str, expect: &Identity, options: &Value) -> String {
    format!(
        "python3 - {} {} {} <<'HERDLINE_HERMES_PY'\n{}\nHERDLINE_HERMES_PY",
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
    if reply["too_large"] == true {
        return Err(Error::EntryTooLarge);
    }
    if let Some(found) = reply.get("version") {
        // A missing table, several rows or a value no format can be: no
        // single format to name, so None rather than a guess.
        return Err(Error::UnsupportedHermes {
            found: found.as_u64().and_then(|n| u32::try_from(n).ok()),
            supported: SCHEMA_VERSION,
        });
    }
    match reply["error"].as_str() {
        Some("unconfined") => return Err(Error::TranscriptUnconfined),
        Some(_) => return Err(Error::NoTranscript),
        None => {}
    }
    let rows = reply["rows"].as_array().ok_or(Error::BadReply)?;
    let start = reply["start"].as_u64().ok_or(Error::BadReply)?;
    let has_older = reply["has_older"].as_bool().ok_or(Error::BadReply)?;
    let mut entries = Vec::with_capacity(rows.len() + 1);
    // Only on the page that reaches the chain's first row, and never on an
    // `after` page, which would repeat it below newer rows.
    if reply["boundary"] == true && !has_older {
        entries.push(boundary());
    }
    entries.extend(rows.iter().filter_map(entry));
    Ok(Response {
        identity: expect.clone(),
        status: reply["status"].as_str().unwrap_or("unknown").to_owned(),
        reset: reset || reply["reset"] == true,
        size: reply["size"].as_u64().ok_or(Error::BadReply)?,
        start,
        end: reply["end"].as_u64().ok_or(Error::BadReply)?,
        has_older,
        entries,
    })
}

/// Where the loaded history stops at a session that is not this one's
/// compression continuation (a /branch, /new or subagent source, a missing
/// parent or the 100-hop cap), so Chat never presents another conversation's
/// turns as this one's. It has no text: Chat draws its own. Offset 0 is never
/// a message, since `messages.id` is AUTOINCREMENT from 1.
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

/// One projected `messages` row. Roles Hermes writes map to chat messages and
/// tool results; any other role is shown as an unsupported row with its text,
/// never dropped.
fn entry(row: &Value) -> Option<Entry> {
    let id = row["id"].as_u64()?;
    let role = row["role"].as_str()?;
    let mut blocks = Vec::new();
    let (kind, role) = if row["summary"] == true {
        // `_compressed_summary = 1`: the summary a compression carried forward.
        (Kind::Compaction, None)
    } else {
        match role {
            "user" | "assistant" | "system" => (Kind::Message, Some(role.to_owned())),
            "tool" => (Kind::Message, Some("toolResult".to_owned())),
            other => {
                blocks.push(text_block(
                    &format!("Unsupported Hermes row: {}", cap(other, 32).0),
                    false,
                ));
                (Kind::Message, Some("system".to_owned()))
            }
        }
    };
    if let Some(reasoning) = row["reasoning"].as_str().filter(|text| !text.is_empty()) {
        blocks.push(text_block(reasoning, true));
    }
    if role.as_deref() == Some("toolResult") {
        let (text, truncated) = cap(
            &crate::herdr_setup::plain_text(parts_text(&row["parts"]).as_bytes()),
            super::RESULT_LIMIT,
        );
        blocks.push(Block::ToolResult {
            call_id: row["tool_call_id"].as_str().unwrap_or_default().to_owned(),
            name: cap(row["tool_name"].as_str().unwrap_or_default(), 128).0,
            text,
            is_error: false,
            images: Vec::new(),
            truncated,
            exit_code: None,
            answers: Vec::new(),
            timed_out: false,
            subagents: Vec::new(),
        });
    } else {
        let mut images = 0;
        for part in row["parts"].as_array().into_iter().flatten() {
            match part["t"].as_str() {
                Some("text") => blocks.push(text_block(part["text"].as_str().unwrap_or_default(), false)),
                // The bytes stay on the host: H-3 reads no media, so the
                // image shows as a placeholder that cannot be opened.
                Some("image") => {
                    blocks.push(Block::Image(Image {
                        part: images,
                        source: Source::Unreadable,
                    }));
                    images += 1;
                }
                _ => {}
            }
        }
    }
    for call in row["tool_calls"].as_array().into_iter().flatten() {
        let (args, truncated) = cap(call["args"].as_str().unwrap_or_default(), super::ARGS_LIMIT);
        blocks.push(Block::ToolCall {
            call_id: call["id"].as_str().unwrap_or_default().to_owned(),
            name: cap(call["name"].as_str().unwrap_or_default(), 128).0,
            args,
            intent: None,
            truncated,
        });
    }
    if row["tool_calls_unreadable"] == true {
        blocks.push(text_block("[unreadable tool calls]", false));
    }
    Some(Entry {
        id: None,
        parent_id: None,
        offset: id,
        end: id.checked_add(1)?,
        kind,
        role,
        blocks,
        timestamp: row["ts"]
            .as_f64()
            .filter(|seconds| seconds.is_finite() && *seconds >= 0.0)
            .map(|seconds| (seconds * 1000.0) as u64),
        usage: None,
        stop_reason: row["finish"].as_str().map(|reason| cap(reason, 32).0),
        steer: None,
        subagent_ends: Vec::new(),
    })
}

fn parts_text(parts: &Value) -> String {
    parts
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|part| match part["t"].as_str() {
            Some("text") => part["text"].as_str(),
            Some("image") => Some("[image]"),
            _ => None,
        })
        .collect::<Vec<_>>()
        .join("\n")
}

/// The host side. The store comes from this shell's own HOME, HERMES_HOME
/// and HERMES_DATA_DIR_SUFFIX; no other process's environment is read. An
/// agent started with values this shell lacks is not found there, never
/// guessed.
const PYTHON: &str = r#"import json, os, re, sqlite3, subprocess, sys, urllib.parse
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
LIMIT=200
BUDGET=1572864
CLIP=70000
NAMES=('hermes','hermes-agent')
PREFIX='\x00json:'
ID=re.compile(r'^[A-Za-z0-9_-][A-Za-z0-9._-]{0,511}$')
PROFILE=re.compile(r'^[a-z0-9][a-z0-9_-]{0,63}$')
SUFFIX=re.compile(r'^[A-Za-z0-9._-]{0,64}$')

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
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:hermes'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown')

def environment():
    return {key:os.environ[key] for key in ('HERMES_HOME','HERMES_DATA_DIR_SUFFIX') if key in os.environ}

class Refused(Exception): pass

def inside(root,path): return os.path.commonpath([root,path])==root

# hermes_constants.py get_hermes_home(): HERMES_HOME, else ~/.hermes<suffix>
# with the active profile. Only what can be pinned exactly is followed: a
# value naming other variables, or a relative one, is refused, not guessed.
def store(env):
    home=os.path.realpath(os.environ['HOME'])
    value=env.get('HERMES_HOME','')
    if value:
        if '$' in value or '%' in value: raise Refused('unconfined')
        if value=='~' or value.startswith('~/'): value=home+value[1:]
        if not value.startswith('/'): raise Refused('unconfined')
        root=os.path.realpath(value)
    else:
        suffix=env.get('HERMES_DATA_DIR_SUFFIX','')
        if not SUFFIX.match(suffix): raise Refused('unconfined')
        root=os.path.realpath(os.path.join(home,'.hermes'+suffix))
        try: profile=open(os.path.join(root,'active_profile')).read(128).strip()
        except OSError: profile=''
        if profile and profile!='default':
            if not PROFILE.match(profile) or os.path.exists(os.path.join(root,'profiles','.deleted',profile)): raise Refused('unconfined')
            root=os.path.realpath(os.path.join(root,'profiles',profile))
    if not inside(home,root): raise Refused('unconfined')
    db=os.path.realpath(os.path.join(root,'state.db'))
    if not inside(root,db): raise Refused('unconfined')
    return db

def connect(db):
    try:
        with open(db,'rb') as f: header=f.read(100)
    except OSError: raise Refused('missing')
    if len(header)<100 or not header.startswith(b'SQLite format 3\x00'): raise Refused('missing')
    uri='file:'+urllib.parse.quote(db,safe='/')+'?mode=ro'
    # A WAL store with neither sidecar has no live writer, so nothing can be
    # missed by reading it immutable, and SQLite then creates no -shm either.
    if header[18]==2 and not os.path.exists(db+'-wal') and not os.path.exists(db+'-shm'): uri+='&immutable=1'
    conn=sqlite3.connect(uri,uri=True,timeout=5)
    conn.row_factory=sqlite3.Row
    conn.execute('PRAGMA query_only=ON')
    conn.execute('BEGIN')
    return conn

def session(conn,sid):
    return conn.execute('SELECT id,source,model_config,parent_session_id,end_reason FROM sessions WHERE id=?',(sid,)).fetchone()

# hermes_state_messages.py:1909 _is_explicit_fork_child_row.
def fork_child(row,include_reset):
    if row['source']=='tool': return True
    cfg=row['model_config']
    if isinstance(cfg,str):
        try: cfg=json.loads(cfg)
        except ValueError: return False
    if not isinstance(cfg,dict): return False
    markers=[cfg.get('_branched_from'),cfg.get('_delegate_from')]
    if include_reset: markers.append(cfg.get('_reset_from'))
    parent=row['parent_session_id']
    return parent in markers if parent else any(m is not None for m in markers)

# hermes_state_messages.py:1698 _resume_lineage_ids: root-first compression
# ancestors of the session, gated per hop by hermes_state_compression.py:908
# _is_compression_child_row; a fork or reset child is its session alone.
def lineage(conn,sid):
    current=session(conn,sid)
    chain=[sid]
    if fork_child(current,False): return chain,current
    seen={sid}
    while len(chain)<100:
        parent_id=current['parent_session_id']
        if not parent_id or fork_child(current,True): break
        parent=session(conn,parent_id)
        if not parent or parent['end_reason']!='compression' or parent['id'] in seen: break
        seen.add(parent['id']); chain.append(parent['id']); current=parent
    chain.reverse()
    return chain,current

def decode(content):
    # hermes_state_messages.py:164 _decode_content: fail closed to the raw string.
    if isinstance(content,str) and content.startswith(PREFIX):
        try: return json.loads(content[len(PREFIX):])
        except ValueError: return content
    return content

def clip(text): return text if len(text)<=CLIP else text[:CLIP]

# tui_gateway/session_history.py _history_dict_text: text parts read, images
# named, anything else shown by its type.
def parts(value):
    if value is None: return []
    if isinstance(value,str): return [{'t':'text','text':clip(value)}]
    if isinstance(value,dict): value=[value]
    if not isinstance(value,list): return [{'t':'text','text':clip(json.dumps(value))}]
    out=[]
    for part in value:
        if isinstance(part,str): out.append({'t':'text','text':clip(part)}); continue
        if not isinstance(part,dict): out.append({'t':'text','text':'[structured content]'}); continue
        kind=part.get('type')
        if kind in ('text','input_text','output_text') or (kind is None and isinstance(part.get('text'),str)):
            text=part.get('text')
            if not isinstance(text,str): text=part.get('content') if isinstance(part.get('content'),str) else ''
            out.append({'t':'text','text':clip(text)})
        elif kind in ('image_url','input_image','image'): out.append({'t':'image'})
        elif kind in ('input_audio','audio'): out.append({'t':'text','text':'[audio]'})
        else: out.append({'t':'text','text':'[%s]'%kind if isinstance(kind,str) and kind else '[structured content]'})
    return out

def calls(raw):
    if raw is None or raw=='': return [],False
    try: value=json.loads(raw)
    except ValueError: return [],True
    if not isinstance(value,list): return [],True
    out=[]
    for call in value:
        fn=call.get('function') if isinstance(call,dict) else None
        if not isinstance(fn,dict): return out,True
        args=fn.get('arguments')
        out.append({'id':str(call.get('id') or ''),'name':str(fn.get('name') or ''),'args':clip(args if isinstance(args,str) else json.dumps(args))})
    return out,False

def project(row):
    found,bad=calls(row['tool_calls'])
    reasoning=row['reasoning_content'] or row['reasoning'] or ''
    return {'id':row['id'],'role':row['role'],'ts':row['timestamp'],'parts':parts(decode(row['content'])),
        'reasoning':clip(reasoning) if isinstance(reasoning,str) else '','tool_calls':found,'tool_calls_unreadable':bad,
        'tool_call_id':row['tool_call_id'],'tool_name':row['tool_name'],'finish':row['finish_reason'],
        'summary':row['_compressed_summary']==1}

def main():
    got,status=identity()
    if got!=expect or got['agent']!='hermes' or got['kind']!='id' or not got['pid']: return {'changed':True}
    sid=got['path']
    if not ID.match(sid): return {'changed':True}
    conn=connect(store(environment()))
    try: versions=[row[0] for row in conn.execute('SELECT version FROM schema_version').fetchall()]
    except sqlite3.DatabaseError: versions=[]
    if versions!=[opts['schema']]: return {'version':versions[0] if len(versions)==1 and isinstance(versions[0],int) else None}
    # gateway-local-sessions: a logical id's receipt names its physical row;
    # this is a lookup of that exact id, never a choice among sessions.
    receipt=conn.execute('SELECT value FROM state_meta WHERE key=?',('gateway.local_policy.v1:'+sid,)).fetchone()
    if receipt is not None:
        try: target=json.loads(receipt[0])['entry']['session_id']
        except (ValueError,KeyError,TypeError): target=None
        if isinstance(target,str) and ID.match(target): sid=target
    if session(conn,sid) is None: return {'error':'missing'}
    chain,root=lineage(conn,sid)
    marks=','.join('?'*len(chain))
    # hermes_state_messages.py:1730 the display set, minus what Hermes never
    # shows (session-storage docs: session_meta, hidden, model-only, [System:
    # switches) and, statelessly so no page repeats one, a compaction clone:
    # a row whose message_uid an earlier visible row of the lineage holds.
    visible=('m.session_id IN (%s) AND (m.active=1 OR m.compacted=1) AND m.role!=\'session_meta\''
        ' AND COALESCE(m.display_kind,\'\')!=\'hidden\''
        ' AND COALESCE(json_extract(CASE WHEN json_valid(m.display_metadata) THEN m.display_metadata ELSE \'{}\' END,\'$._model_only\'),0) NOT IN (1,\'1\')'
        ' AND NOT (m.role=\'user\' AND substr(COALESCE(m.content,\'\'),1,8)=\'[System:\')'
        ' AND NOT (m.message_uid IS NOT NULL AND EXISTS (SELECT 1 FROM messages e WHERE e.session_id IN (%s)'
        ' AND e.message_uid=m.message_uid AND e.id<m.id AND (e.active=1 OR e.compacted=1)))')%(marks,marks)
    bound=tuple(chain)*2
    select='SELECT m.* FROM messages m WHERE '+visible
    size=conn.execute('SELECT COALESCE(MAX(id),0)+1 FROM messages WHERE session_id IN (%s)'%marks,tuple(chain)).fetchone()[0]
    mode=opts['mode']; cursor=int(opts.get('cursor',0))
    reset=mode=='tail' or (mode=='after' and cursor>size)
    if reset: found=conn.execute(select+' ORDER BY m.id DESC LIMIT ?',bound+(LIMIT,)).fetchall()[::-1]
    elif mode=='before': found=conn.execute(select+' AND m.id<? ORDER BY m.id DESC LIMIT ?',bound+(cursor,LIMIT)).fetchall()[::-1]
    # Hermes inserts a row whole, so the next page starts at the cursor itself.
    else: found=conn.execute(select+' AND m.id>=? ORDER BY m.id LIMIT ?',bound+(cursor,LIMIT)).fetchall()
    rows=[]; used=0
    newest_first=mode=='before' or reset
    for row in (found[::-1] if newest_first else found):
        packed=json.dumps(project(row),separators=(',',':'))
        if used+len(packed.encode())>BUDGET:
            if not rows: raise OverflowError()
            break
        used+=len(packed.encode()); rows.append(json.loads(packed))
    if newest_first: rows.reverse()
    start=rows[0]['id'] if rows else (size if reset else cursor)
    end=rows[-1]['id']+1 if rows else (size if reset else cursor)
    older=conn.execute('SELECT 1 FROM messages m WHERE '+visible+' AND m.id<? LIMIT 1',bound+(start,)).fetchone() is not None
    conn.close()
    after,_=identity()
    if after!=expect: return {'changed':True}
    return {'status':status,'reset':reset,'size':size,'start':start,'end':end,'has_older':older,
        'boundary':(mode!='after' or reset) and root['parent_session_id'] is not None,'rows':rows}
try:
    print(json.dumps(main(),separators=(',',':')))
except Refused as refusal:
    print(json.dumps({'error':str(refusal)}))
except OverflowError:
    print('{"too_large":true}')
except Exception:
    sys.exit(1)
"#;

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests;
