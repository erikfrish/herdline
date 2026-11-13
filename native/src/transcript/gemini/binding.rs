//! Which Gemini session belongs to a pane, and its bytes, read only. Gemini
//! reports no session to Herdr, so the binding is Herdline's own: the owned
//! launcher (`wrapper.js`, installed as `gemini`), its pane sidecar, and the
//! SessionStart hook (`hook.js`) that reports the session uuid with the
//! launcher's pid and nonce as pane tokens. Herdr keeps tokens after the
//! process exits and keys them by source, not pid (terminal/metadata.rs:
//! 61-126, state.rs:570-607), so every read proves, before and again after
//! the file is read, that the binding is live:
//!
//! - (a) Herdr detects the pane's agent as exactly `gemini`;
//! - (b) the tokens are well formed, and one foreground process has
//!   `hl_gemini_pid`;
//! - (c) that process is `node <dir>/gemini` in Herdr's own argv;
//! - (d) the pane's sidecar is this uid's, 0600 in a 0700 dir, no link, at
//!   most 256 bytes, and names that pid with the nonce `hl_gemini_gen`, and
//!   that launch's published `<pane>.<nonce>.session` is exactly
//!   `hl_gemini_id` (tokens Herdr kept past a SessionStart it did not take
//!   are refused); the runtime dir holding them is a private dir of this uid
//!   and its own realpath, so no ancestor is a link (`sidecar.js` holds the
//!   same rule). The sidecar is named by the pane id, so a pane Herdr
//!   renumbered finds another pane's sidecar or none, and is refused;
//! - (e) exactly one `session-*-<id8>.jsonl` under Gemini's project dirs has
//!   that uuid as its header, and the folded file has it too;
//! - (f) that file is under `$GEMINI_CLI_HOME` or `$HOME`, inside home, with
//!   no link on the way, and within the size one read parses in full.
//!
//! Gemini v0.68.0 keeps chats in `<home>/.gemini/tmp/<project slug>/chats`
//! (Storage.getProjectTempDir and ProjectRegistry.getShortId, bundle
//! chunk-RCB6BI5H.js:253340-253344, 252874-253150) and names a main session
//! `session-<YYYY-MM-DDTHH-MM>[-<n>]-<id8>.jsonl`
//! (chatRecordingService.ts:806-823). The slug dir's `.project_root` holds
//! the project root whose sha256 is the header's projectHash
//! (paths.ts:318-320). No latest-file guess: the id picks the file or none.
//!
//! A running `invoke_agent` child records itself beside the session, in
//! `chats/<uuid>/<child uuid>.jsonl`. With the session go the newest
//! [`MAX_RUNNING`] of those the session does not name as an `agentId`:
//! regular files only, no link followed, each its first 16 KiB and last
//! 64 KiB in whole lines; their sizes and mtimes are in the stamp.
//!
//! An already-running Gemini started without the launcher has no tokens or
//! no sidecar and is always refused.

use super::{MAX_RUNNING, fold};
use crate::transcript::{HEADER_ROOM, Identity, MAX_WINDOW, Read, Response, read_timeout};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};

/// The source the hook reports with, and the identity's `source`.
pub(super) const SOURCE: &str = "user:gemini-session";
/// The pane tokens the hook reports.
pub(super) const TOKEN_ID: &str = "hl_gemini_id";
pub(super) const TOKEN_PID: &str = "hl_gemini_pid";
pub(super) const TOKEN_GEN: &str = "hl_gemini_gen";

/// Typed refusals.
/// A Gemini pane without well-formed binding tokens: started without the
/// launcher, or before its SessionStart reported.
pub(super) const UNBOUND: &str = "gemini_session_unbound";
/// The tokens name a process that is not the pane's launcher now, or a
/// sidecar that is missing or holds another nonce: a stale binding.
pub(super) const STALE: &str = "gemini_binding_stale";
/// The sidecar or its dir is a link, another uid's, readable by others,
/// too large or not `{pid, nonce}`.
pub(super) const SIDECAR: &str = "gemini_sidecar_unsafe";
/// More than one session file has the uuid as its header.
pub(super) const AMBIGUOUS: &str = "gemini_session_ambiguous";
/// The file is not the session the tokens name, or not its project's.
pub(super) const FOREIGN: &str = "gemini_session_foreign";
/// The session file is past the bound one read parses in full.
pub(super) const TOO_LARGE: &str = "gemini_session_too_large";

const REFUSALS: [&str; 6] = [UNBOUND, STALE, SIDECAR, AMBIGUOUS, FOREIGN, TOO_LARGE];

/// The pane's Gemini identity from `pane get` and `pane process-info`:
/// `None` when Herdr does not see Gemini in the pane, a typed refusal when
/// it does but the tokens do not name its live launcher. The sidecar is
/// only on the host; [`page`] checks it on every read.
pub(in crate::transcript) fn identity(pane: &Value, process: &Value) -> Result<Option<Identity>> {
    let pane = &pane["result"]["pane"];
    if pane["agent"] != "gemini" {
        return Ok(None);
    }
    let tokens = &pane["tokens"];
    let token = |key: &str| tokens[key].as_str();
    let (Some(id), Some(pid), Some(generation)) = (token(TOKEN_ID), token(TOKEN_PID), token(TOKEN_GEN)) else {
        return Err(Error::UnsupportedFormat(UNBOUND));
    };
    let pid = match pid.parse::<u32>() {
        Ok(value) if uuid(id) && nonce(generation) && !pid.starts_with('0') && pid.len() <= 10 => value,
        _ => return Err(Error::UnsupportedFormat(UNBOUND)),
    };
    let processes = process["result"]["process_info"]["foreground_processes"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let mut owned = processes.iter().filter(|process| process["pid"].as_u64() == Some(u64::from(pid)));
    match (owned.next(), owned.next()) {
        (Some(process), None) if launcher(&process["argv"]) => {}
        _ => return Err(Error::UnsupportedFormat(STALE)),
    }
    let text = |value: &Value| value.as_str().map(str::to_owned).ok_or(Error::BadReply);
    Ok(Some(Identity {
        pane_id: text(&pane["pane_id"])?,
        terminal_id: text(&pane["terminal_id"])?,
        pid: Some(pid),
        path: id.to_owned(),
        agent: "gemini".to_owned(),
        source: SOURCE.to_owned(),
        kind: "id".to_owned(),
    }))
}

/// A session id as Gemini's `randomUUID` writes it: lowercase, hyphenated.
fn uuid(value: &str) -> bool {
    let bytes = value.as_bytes();
    bytes.len() == 36
        && bytes.iter().enumerate().all(|(at, byte)| match at {
            8 | 13 | 18 | 23 => *byte == b'-',
            _ => byte.is_ascii_digit() || (b'a'..=b'f').contains(byte),
        })
}

/// The launcher's nonce: 128 bits as lowercase hex.
fn nonce(value: &str) -> bool {
    value.len() == 32 && value.bytes().all(|byte| byte.is_ascii_digit() || (b'a'..=b'f').contains(&byte))
}

/// `node <dir>/gemini`: Herdr's argv of the owned launcher, nothing else.
fn launcher(argv: &Value) -> bool {
    let base = |value: &Value| value.as_str().map(|arg| arg.rsplit('/').next().unwrap_or(arg).to_owned());
    match argv.as_array().map(Vec::as_slice) {
        Some([node, script, ..]) => {
            base(node).is_some_and(|name| node_name(&name))
                && script.as_str().is_some_and(|arg| arg.starts_with('/'))
                && base(script).as_deref() == Some("gemini")
        }
        _ => false,
    }
}

fn node_name(name: &str) -> bool {
    let rest = name.strip_prefix("nodejs").or_else(|| name.strip_prefix("node"));
    rest.is_some_and(|rest| rest.bytes().all(|byte| byte.is_ascii_digit() || byte == b'.'))
}

/// The whole session, or "not modified" when the open file's device, inode,
/// size and mtime match the stamp the app holds in `end`. Gemini rewrites
/// records, so there is no byte cursor: any change replaces the chat.
pub(in crate::transcript) fn page(
    exec: &Exec,
    session: &str,
    pane: &str,
    expect: &Identity,
    read: Read,
    reset: bool,
) -> Result<Response> {
    let stamp = match read {
        Read::After { offset } => offset,
        Read::Tail | Read::Before { .. } => 0,
    };
    let output = exec.run(
        format!(
            "/bin/sh -c {}",
            shell_quote(&script(session, pane, expect, stamp))
        ),
        read_timeout(MAX_WINDOW),
        usize::try_from(MAX_WINDOW).unwrap_or(usize::MAX) + HEADER_ROOM + CHILDREN_ROOM,
    )?;
    if output.exit_status != Some(0) {
        return Err(Error::NoTranscript);
    }
    answer(&output.tail, expect, reset)
}

/// What the running children's slices add to a reply: [`MAX_RUNNING`] of
/// the host script's `CHILD_HEAD + CHILD_TAIL`.
const CHILDREN_ROOM: usize = MAX_RUNNING * (16 + 64) * 1024;

fn script(session: &str, pane: &str, expect: &Identity, stamp: u64) -> String {
    format!(
        "python3 - {} {} {} <<'HERDLINE_GEMINI_PY'\n{PYTHON}\nHERDLINE_GEMINI_PY",
        shell_quote(&serde_json::to_string(expect).unwrap_or_default()),
        shell_quote(&json!({ "stamp": stamp }).to_string()),
        shell_quote(&crate::transcript::instance_script(session, pane)),
    )
}

/// The host script's reply: one JSON header line, then for a changed file
/// its bytes exactly as read.
fn answer(bytes: &[u8], expect: &Identity, reset: bool) -> Result<Response> {
    let (head, data) = match bytes.iter().position(|&byte| byte == b'\n') {
        Some(at) => (&bytes[..at], &bytes[at + 1..]),
        None => (bytes, &[][..]),
    };
    let head: Value = serde_json::from_slice(head)?;
    if head["changed"] == true {
        return Err(Error::NoTranscript);
    }
    if let Some(name) = head["refused"].as_str() {
        return Err(Error::UnsupportedFormat(
            REFUSALS.into_iter().find(|known| *known == name).ok_or(Error::BadReply)?,
        ));
    }
    match head["error"].as_str() {
        None => {}
        Some("unconfined") => return Err(Error::TranscriptUnconfined),
        Some("outside_home") => return Err(Error::SessionRootOutsideHome),
        Some(_) => return Err(Error::NoTranscript),
    }
    let stamp = head["stamp"].as_u64().ok_or(Error::BadReply)?;
    let not_modified = head["not_modified"] == true;
    let entries = if not_modified {
        Vec::new()
    } else {
        // The session's bytes, then each running child's, as the reply measures them.
        let ids = head["children"].as_array().ok_or(Error::BadReply)?;
        let parts = head["parts"].as_array().ok_or(Error::BadReply)?;
        if parts.len() != ids.len() + 1 || ids.len() > MAX_RUNNING {
            return Err(Error::BadReply);
        }
        let mut rest = data;
        let mut slices = Vec::with_capacity(parts.len());
        for part in parts {
            let length = part.as_u64().and_then(|length| usize::try_from(length).ok()).filter(|length| *length <= rest.len()).ok_or(Error::BadReply)?;
            let (slice, after) = rest.split_at(length);
            slices.push(slice);
            rest = after;
        }
        if !rest.is_empty() {
            return Err(Error::BadReply);
        }
        let mut children = Vec::with_capacity(ids.len());
        for (id, slice) in ids.iter().zip(&slices[1..]) {
            children.push((id.as_str().filter(|id| uuid(id)).ok_or(Error::BadReply)?, *slice));
        }
        // (e) again, on what Gemini itself would load: a `$set` may rewrite
        // the id after the header.
        let conversation = fold(slices[0]).ok_or(Error::UnsupportedFormat(FOREIGN))?;
        if conversation.session_id != expect.path {
            return Err(Error::UnsupportedFormat(FOREIGN));
        }
        let mut entries = conversation.entries();
        if let Some(mut running) = conversation.running(&children) {
            running.offset = entries.len() as u64;
            running.end = running.offset + 1;
            entries.push(running);
        }
        entries
    };
    Ok(Response {
        identity: expect.clone(),
        status: head["status"].as_str().unwrap_or("unknown").to_owned(),
        reset: reset || !not_modified,
        size: head["size"].as_u64().ok_or(Error::BadReply)?,
        start: 0,
        end: stamp,
        has_older: false,
        entries,
    })
}

/// Runs on the host. Reads from this shell's own environment only HOME,
/// `GEMINI_CLI_HOME` and `XDG_RUNTIME_DIR` (no other process's environment
/// and no `/proc`), and never prints them. The binding is proved by
/// `bind()` before the file is opened and again after its bytes are read;
/// any difference is a change or a refusal, never the data.
const PYTHON: &str = r#"import hashlib, json, os, re, stat, subprocess, sys
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
MAX_FILE=33554432
MAX_HEAD=1048576
MAX_SIDECAR=256
MAX_MARKER=4096
MAX_DIRS=4096
UUID=re.compile(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}')
MAX_CHILDREN=8
CHILD_HEAD=16384
CHILD_TAIL=65536
PID=re.compile(r'[1-9][0-9]{0,9}')
GEN=re.compile(r'[0-9a-f]{32}')
PANE=re.compile(r'[A-Za-z0-9:_-]{1,64}')
BASE=re.compile(r'/[A-Za-z0-9._/-]*')
NODE=re.compile(r'node(js)?[0-9.]*')

class Refuse(Exception): pass
class Changed(Exception): pass
class Fail(Exception): pass

def reply(head, data=b''):
    sys.stdout.buffer.write(json.dumps(head).encode()+b'\n'+data)

def launcher(argv):
    if not isinstance(argv,list) or len(argv)<2 or not all(isinstance(a,str) for a in argv[:2]): return False
    return bool(NODE.fullmatch(argv[0].rsplit('/',1)[-1])) and argv[1].startswith('/') and argv[1].rsplit('/',1)[-1]=='gemini'

def identity():
    result=subprocess.run(['/bin/sh','-c',report],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20)
    raw=result.stdout
    if result.returncode or len(raw)>65536 or not raw.startswith(b'@@pane\n'): raise ValueError('report')
    first,proc=raw[len(b'@@pane\n'):].split(b'\n@@proc\n',1)
    pane=json.loads(first)['result']['pane']
    procs=json.loads(proc).get('result',{}).get('process_info',{}).get('foreground_processes') or []
    if pane.get('agent')!='gemini': raise Changed()
    tokens=pane.get('tokens') or {}
    sid,pid,gen=(tokens.get(k) for k in ('hl_gemini_id','hl_gemini_pid','hl_gemini_gen'))
    if not all(isinstance(v,str) for v in (sid,pid,gen)) or not (UUID.fullmatch(sid) and PID.fullmatch(pid) and GEN.fullmatch(gen)): raise Refuse('gemini_session_unbound')
    pid=int(pid)
    owned=[p for p in procs if isinstance(p,dict) and p.get('pid')==pid]
    if len(owned)!=1 or not launcher(owned[0].get('argv')): raise Refuse('gemini_binding_stale')
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':sid,'agent':'gemini','source':'user:gemini-session','kind':'id'}
    return got,gen,pane.get('agent_status','unknown')

def private(path):
    try: st=os.lstat(path)
    except FileNotFoundError: raise Refuse('gemini_binding_stale')
    if not stat.S_ISDIR(st.st_mode) or st.st_uid!=os.getuid() or st.st_mode&0o077: raise Refuse('gemini_sidecar_unsafe')

def owned_text(path):
    try: fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except FileNotFoundError: raise Refuse('gemini_binding_stale')
    except OSError: raise Refuse('gemini_sidecar_unsafe')
    with os.fdopen(fd,'rb') as handle:
        st=os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_uid!=os.getuid() or st.st_mode&0o077 or st.st_nlink!=1 or st.st_size>MAX_SIDECAR: raise Refuse('gemini_sidecar_unsafe')
        return handle.read(MAX_SIDECAR+1)

def sidecar(pane, pid, gen, sid):
    # sidecar.js's rule: a plain absolute runtime dir that is its own
    # realpath, and it and herdline-gemini private dirs of this uid.
    if not isinstance(pane,str) or not PANE.fullmatch(pane): raise Refuse('gemini_sidecar_unsafe')
    base=os.environ.get('XDG_RUNTIME_DIR') or '/run/user/%d'%os.getuid()
    if not BASE.fullmatch(base) or os.path.realpath(base)!=base: raise Refuse('gemini_sidecar_unsafe')
    private(base)
    folder=os.path.join(base,'herdline-gemini')
    private(folder)
    raw=owned_text(os.path.join(folder,pane+'.json'))
    try: value=json.loads(raw)
    except ValueError: raise Refuse('gemini_sidecar_unsafe')
    if not isinstance(value,dict) or set(value)!={'pid','nonce'} or type(value['pid']) is not int or not isinstance(value['nonce'],str): raise Refuse('gemini_sidecar_unsafe')
    if value['pid']!=pid or value['nonce']!=gen: raise Refuse('gemini_binding_stale')
    # The session this launch's hook last published: tokens Herdr kept from
    # before a SessionStart it did not take name another one.
    if owned_text(os.path.join(folder,pane+'.'+gen+'.session'))!=sid.encode(): raise Refuse('gemini_binding_stale')

def bind():
    got,gen,status=identity()
    if got!=expect: raise Changed()
    sidecar(got['pane_id'],got['pid'],gen,got['path'])
    return gen,status

def regular(path, limit):
    try: fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except FileNotFoundError: return None
    except OSError: raise Fail('unconfined')
    handle=os.fdopen(fd,'rb')
    st=os.fstat(fd)
    if not stat.S_ISREG(st.st_mode): handle.close(); raise Fail('unconfined')
    if st.st_size>limit: handle.close(); raise Refuse('gemini_session_too_large')
    return handle,st

def directory(path):
    try: st=os.lstat(path)
    except FileNotFoundError: return False
    if stat.S_ISLNK(st.st_mode): raise Fail('unconfined')
    return stat.S_ISDIR(st.st_mode)

def root():
    home=os.path.realpath(os.environ['HOME'])
    value=os.environ.get('GEMINI_CLI_HOME')
    base=home
    if value:
        if not value.startswith('/') or any(ord(c)<32 for c in value): raise Fail('unconfined')
        base=os.path.realpath(value)
        if os.path.commonpath([home,base])!=home: raise Fail('outside_home')
    tmp=os.path.join(base,'.gemini','tmp')
    if os.path.realpath(tmp)!=tmp: raise Fail('unconfined')
    return tmp

def header(handle):
    line=handle.read(MAX_HEAD).split(b'\n',1)[0]
    handle.seek(0)
    try: value=json.loads(line.decode('utf-8'))
    except ValueError: return None
    return value if isinstance(value,dict) else None

def find(tmp, sid):
    name=re.compile(r'session-[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}-[0-9]{2}(-[0-9]+)?-'+sid[:8]+r'\.jsonl')
    if not directory(tmp): return []
    slugs=os.listdir(tmp)
    if len(slugs)>MAX_DIRS: raise Fail('unconfined')
    found=[]
    for slug in sorted(slugs):
        if slug.startswith('.') or not directory(os.path.join(tmp,slug)): continue
        chats=os.path.join(tmp,slug,'chats')
        if not directory(chats): continue
        names=os.listdir(chats)
        if len(names)>MAX_DIRS*16: raise Fail('unconfined')
        for entry in sorted(names):
            if not name.fullmatch(entry): continue
            opened=regular(os.path.join(chats,entry),MAX_FILE)
            if opened is None: continue
            handle,st=opened
            head=header(handle)
            if head is not None and head.get('sessionId')==sid: found.append((slug,os.path.join(chats,entry),handle,st,head))
            else: handle.close()
    return found

def owner(tmp, slug, head):
    # ProjectRegistry: the slug dir's .project_root names the project whose
    # sha256 is the projectHash; a legacy dir is named by the hash itself.
    project=head.get('projectHash')
    if not isinstance(project,str): return False
    opened=regular(os.path.join(tmp,slug,'.project_root'),MAX_MARKER)
    if opened is None: return slug==project
    handle,_=opened
    with handle: marker=handle.read(MAX_MARKER).decode('utf-8','replace').strip()
    return hashlib.sha256(marker.encode()).hexdigest()==project

def children(chats, sid):
    # A running invoke_agent child's own record: chats/<sid>/<uuid>.jsonl,
    # each opened in that dir's fd. A link, at the dir or a file, is never
    # followed; nothing unusual there refuses the session itself.
    try: fd=os.open(os.path.join(chats,sid),os.O_RDONLY|os.O_DIRECTORY|os.O_NOFOLLOW|os.O_NONBLOCK)
    except OSError: return None,[]
    names=os.listdir(fd)
    if len(names)>MAX_DIRS: return fd,[]
    found=[]
    for entry in sorted(names):
        if not (entry.endswith('.jsonl') and UUID.fullmatch(entry[:-6])): continue
        try: st=os.lstat(entry,dir_fd=fd)
        except FileNotFoundError: continue
        if stat.S_ISREG(st.st_mode): found.append((entry[:-6],st))
    return fd,found

def child_bytes(folder, kid):
    # Its first and last lines whole: the header and query, and what it does now.
    try: fd=os.open(kid+'.jsonl',os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK,dir_fd=folder)
    except OSError: return b''
    try:
        st=os.fstat(fd)
        if not stat.S_ISREG(st.st_mode): return b''
        if st.st_size<=CHILD_HEAD+CHILD_TAIL: return os.pread(fd,CHILD_HEAD+CHILD_TAIL,0)
        head=os.pread(fd,CHILD_HEAD,0)
        tail=os.pread(fd,CHILD_TAIL,st.st_size-CHILD_TAIL)
        return head[:head.rfind(b'\n')+1]+tail[tail.find(b'\n')+1:]
    finally: os.close(fd)

def main():
    gen,status=bind()
    sid=expect['path']
    tmp=root()
    found=find(tmp,sid)
    folder=None
    try:
        if not found: return reply({'error':'missing'})
        if len(found)>1: raise Refuse('gemini_session_ambiguous')
        slug,path,handle,st,head=found[0]
        if not owner(tmp,slug,head): raise Refuse('gemini_session_foreign')
        folder,kids=children(os.path.dirname(path),sid)
        seen=''.join('%s:%d:%d:%d;'%(kid,s.st_ino,s.st_size,s.st_mtime_ns) for kid,s in kids)
        stamp=int(hashlib.sha256(('%d:%d:%d:%d:%s:%s'%(st.st_dev,st.st_ino,st.st_size,st.st_mtime_ns,path,seen)).encode()).hexdigest()[:13],16) or 1
        out={'stamp':stamp,'size':st.st_size,'status':status}
        fresh=opts.get('stamp')!=stamp
        data=handle.read(st.st_size) if fresh else b''
        parts=[]
        if fresh:
            # A child the session names as an agentId has ended there.
            left=[kid for kid in kids if not re.search(rb'"agentId"\s*:\s*"'+kid[0].encode()+rb'"',data)]
            left.sort(key=lambda kid:kid[1].st_mtime_ns,reverse=True)
            parts=[(kid,child_bytes(folder,kid)) for kid,_ in left[:MAX_CHILDREN]]
            out['children']=[kid for kid,_ in parts]
            out['parts']=[len(data)]+[len(b) for _,b in parts]
    finally:
        for entry in found: entry[2].close()
        if folder is not None: os.close(folder)
    after,_=bind()
    if after!=gen: raise Changed()
    if not fresh: out['not_modified']=True
    reply(out,data+b''.join(b for _,b in parts))

try: main()
except Changed: reply({'changed':True})
except Refuse as refusal: reply({'refused':refusal.args[0]})
except Fail as failure: reply({'error':failure.args[0]})
except Exception:
    sys.exit(1)
"#;

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests;
