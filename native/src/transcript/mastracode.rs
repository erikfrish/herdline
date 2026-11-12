//! Mastra Code (`mastracode`) history, read only, for exactly the thread Herdr
//! reports in the pane. Mastra Code keeps every thread in one libSQL file,
//! plain SQLite 3 on disk, with `mastra_threads` and `mastra_messages`. This
//! never sends, steers, resumes or starts an agent: Mastra Code Chat is
//! history only and the pane's Terminal stays the way to talk to it.
//!
//! Every rule below names the source it mirrors, mastra-ai/mastra commit
//! d432f1e: `mastracode/sdk` for where the store is, `stores/libsql` and
//! `packages/core` for the rows.

use super::{Block, Entry, Identity, Image, Kind, Read, Response, Source, cap, text_block};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

const PAGE_ROOM: usize = 2 * 1024 * 1024;

/// Typed refusals, each one Chat says with its general text except
/// `OUTSIDE_HOME`.
/// The store lacks the `mastra_threads`/`mastra_messages` columns this reads.
pub(super) const STORE_LAYOUT: &str = "mastracode_store_layout";
/// The store or a config file that picks it resolves outside the SSH user's
/// home, or to a relative path.
pub(super) const OUTSIDE_HOME: &str = "mastracode_store_outside_home";
/// Postgres, or a libSQL URL that is not a local `file:`.
pub(super) const REMOTE: &str = "mastracode_store_remote";
/// `:memory:`: the history is in the agent's memory, not on disk.
pub(super) const IN_MEMORY: &str = "mastracode_store_in_memory";
/// SQLite cannot open the store read-only, or it is not a regular file.
pub(super) const UNREADABLE: &str = "mastracode_store_unreadable";
/// Which store Mastra Code chose cannot be told without guessing: its cwd is
/// unreadable, git env or an odd repository moves its project root, or a
/// config file is unreadable or too large. Never the default store instead,
/// which could be the wrong database.
pub(super) const UNKNOWN: &str = "mastracode_store_unknown";
/// The thread holds more messages than one ordering scan may read.
pub(super) const TOO_LARGE: &str = "mastracode_session_too_large";

/// The pane's Mastra Code process. Herdr detects it by the `mastracode` bin
/// or `node_modules/mastracode/dist/cli.js` in a node argv
/// (`src/detect/mod.rs:210`, `:744-745`), so the process name alone is
/// usually `node`. The host script applies the same rule.
pub(super) fn pid(process: &Value) -> Option<u32> {
    process["result"]["process_info"]["foreground_processes"]
        .as_array()?
        .iter()
        .find(|process| {
            process["name"] == "mastracode"
                || process["argv"]
                    .as_array()
                    .is_some_and(|argv| argv.iter().take(2).filter_map(Value::as_str).any(launcher))
        })?["pid"]
        .as_u64()
        .and_then(|pid| u32::try_from(pid).ok())
}

fn launcher(arg: &str) -> bool {
    let parts: Vec<String> = arg
        .split(['/', '\\'])
        .filter(|part| !part.is_empty())
        .map(str::to_ascii_lowercase)
        .collect();
    matches!(
        parts.last().map(String::as_str),
        Some("mastracode" | "mastracode.js" | "mastracode.cmd" | "mastracode.exe")
    ) || parts.ends_with(&["node_modules", "mastracode", "dist", "cli.js"].map(String::from))
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
        "python3 - {} {} {} <<'HERDLINE_MASTRACODE_PY'\n{}\nHERDLINE_MASTRACODE_PY",
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
            [STORE_LAYOUT, OUTSIDE_HOME, REMOTE, IN_MEMORY, UNREADABLE, UNKNOWN, TOO_LARGE]
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

/// One `mastra_messages` row, keyed by its place in the thread's order and
/// its id (the host's KEY encoding). The bytes of an image never leave the
/// host: it shows as a placeholder Chat cannot open, and any other file is
/// named, never opened. A row with nothing to show is no entry, not an
/// empty bubble.
fn entry(row: &Value) -> Option<Entry> {
    let offset = row["id"].as_u64()?;
    let mut blocks = Vec::new();
    let mut parts = row["parts"].as_array();
    let role = match row["role"].as_str() {
        Some(role @ ("user" | "assistant")) => role,
        // Mastra Code sends what the user types as a signal of type `user`
        // (`tui/src/tui/mastra-tui.ts:594-597`), and core reads only `user`
        // and `user-message` signals as the user's words (`AIV4Adapter.ts:
        // 112-114`, `:359`). State, reminder, notification and reactive
        // signals are the harness's own (`db-message-parts.ts:341-350`):
        // system text, never a user bubble.
        Some("signal") if matches!(row["signal"].as_str(), Some("user" | "user-message")) => "user",
        // Their content is model-facing (reminders, state dumps), and core
        // blanks its content string (`AIV4Adapter.ts:360`): only the marker
        // shows, never their parts.
        Some("signal") => {
            let kind = row["signal"].as_str().unwrap_or("unknown");
            blocks.push(text_block(&format!("[Mastra Code {} signal]", cap(kind, 32).0), false));
            parts = None;
            "system"
        }
        Some("system" | "tool") => "system",
        other => {
            let name = other.unwrap_or("none");
            blocks.push(text_block(&format!("Unsupported Mastra Code message: {}", cap(name, 32).0), false));
            "system"
        }
    };
    let mut images = 0;
    let mut image = || {
        let part = images;
        images += 1;
        Image { part, source: Source::Unreadable }
    };
    for part in parts.into_iter().flatten() {
        match part["t"].as_str().unwrap_or_default() {
            "text" => {
                if let Some(text) = part["text"].as_str().filter(|text| !text.is_empty()) {
                    blocks.push(text_block(text, false));
                }
            }
            "reasoning" => {
                if let Some(text) = part["text"].as_str().filter(|text| !text.is_empty()) {
                    blocks.push(text_block(text, true));
                }
            }
            "file" if part["image"] == true => blocks.push(Block::Image(image())),
            "file" => blocks.push(text_block(&named("file", part), false)),
            "source" => blocks.push(text_block(&named("source", part), false)),
            "tool" => {
                let call_id = part["call"].as_str().unwrap_or_default().to_owned();
                let name = cap(part["name"].as_str().unwrap_or_default(), 128).0;
                let (args, truncated) = cap(part["input"].as_str().unwrap_or_default(), super::ARGS_LIMIT);
                blocks.push(Block::ToolCall {
                    call_id: call_id.clone(),
                    name: name.clone(),
                    args,
                    intent: None,
                    truncated,
                });
                let is_error = part["status"] == "error";
                if is_error || part["status"] == "completed" {
                    let output = part["output"].as_str().unwrap_or_default();
                    let (text, truncated) =
                        cap(&crate::herdr_setup::plain_text(output.as_bytes()), super::RESULT_LIMIT);
                    blocks.push(Block::ToolResult {
                        call_id,
                        name,
                        text,
                        is_error,
                        images: Vec::new(),
                        truncated,
                        exit_code: None,
                        answers: Vec::new(),
                        timed_out: false,
                        subagents: Vec::new(),
                    });
                }
            }
            // Step bookkeeping Mastra Code's own view does not show.
            "step-start" => {}
            // The host kept only the parts that fit the page budget.
            "cut" => blocks.push(Block::Text {
                text: "[Message too large to show in full]".to_owned(),
                truncated: true,
            }),
            other => blocks.push(text_block(&format!("[{}]", cap(other, 32).0), false)),
        }
    }
    if blocks.is_empty() {
        return None;
    }
    Some(Entry {
        id: row["mid"].as_str().map(str::to_owned),
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

fn named(what: &str, part: &Value) -> String {
    let name = part["name"].as_str().filter(|name| !name.is_empty());
    format!("[{what}: {}]", cap(name.unwrap_or("unknown"), 256).0)
}

/// The host side. The store comes from this shell's own environment (only
/// the variables that pick it) and the agent's working directory from
/// Herdr's `foreground_cwd`; no other process's environment or cwd is read.
/// An agent started with storage variables this shell lacks is not found
/// there, never guessed. A file part's `data` (inline base64 or a URL) is
/// never returned and never opened.
const PYTHON: &str = r#"import json, os, re, sqlite3, stat, subprocess, sys, urllib.parse, zlib
from datetime import datetime, timezone
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
LIMIT=200
BUDGET=1572864
# One ordering scan reads every row's id and createdAt; past this the thread
# is refused before any content is read.
MAX_ROWS=100000
# Rust's per-item caps (transcript.rs TEXT_LIMIT, RESULT_LIMIT, ARGS_LIMIT) in
# UTF-8 bytes, plus a few so Rust still sees the field as cut and says so.
TEXT=65536+4; RESULT=16384+4; ARGS=8192+4
CONFIG=65536
# Upstream orders a thread by (createdAt, id) (stores/libsql/src/storage/
# domains/memory/index.ts:458-489), and rowid does not follow that order. So
# an offset is the row's place in that order times KEY plus 20 bits of its
# id's CRC: a delete, a retention prune, a thread move or a late insert with an
# older createdAt shifts the places, the CRC no longer matches, and the next
# page starts over from the tail.
KEY=1<<20
ID=re.compile(r'^[A-Za-z0-9_-][A-Za-z0-9._-]{0,511}$')
# packages/core/src/storage/constants.ts:933-949, the columns this reads.
NEED={'mastra_threads':{'id'},'mastra_messages':{'id','thread_id','content','role','type','createdAt'}}
WANTED={'HOME','MASTRA_STORAGE_BACKEND','MASTRA_DB_URL','MASTRA_DB_PATH','MASTRA_APP_DATA_DIR','XDG_DATA_HOME',
    'GIT_DIR','GIT_WORK_TREE','GIT_COMMON_DIR','GIT_CEILING_DIRECTORIES','GIT_DISCOVERY_ACROSS_FILESYSTEM'}

def dump(value): return json.dumps(value,separators=(',',':'),ensure_ascii=False)

def check(mid): return zlib.crc32(str(mid).encode())&(KEY-1)

# Herdr's detection (src/detect/mod.rs:210, :744-745), the same rule as the
# Rust side: the process name, or the bin or package cli in the first argv.
def launcher(arg):
    parts=[p for p in re.split(rb'[/\\]',arg.encode().lower()) if p]
    return bool(parts) and (parts[-1] in (b'mastracode',b'mastracode.js',b'mastracode.cmd',b'mastracode.exe') or parts[-4:]==[b'node_modules',b'mastracode',b'dist',b'cli.js'])

def mastra(p):
    argv=p.get('argv') if isinstance(p.get('argv'),list) else []
    return p.get('name')=='mastracode' or any(isinstance(a,str) and launcher(a) for a in argv[:2])

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
    pid=next((p.get('pid') for p in fg if isinstance(p,dict) and mastra(p)),None)
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:mastracode'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown'),pane.get('foreground_cwd')

def environment():
    return {key:value for key,value in os.environ.items() if key in WANTED}

class Refused(Exception): pass

def inside(root,path): return os.path.commonpath([root,path])==root

def placed(path):
    if not path.startswith('/'): raise Refused('mastracode_store_outside_home')
    real=os.path.realpath(path)
    if not inside(os.path.realpath(os.environ['HOME']),real): raise Refused('mastracode_store_outside_home')
    return real

# A small regular file's bytes, None when absent. A FIFO, device or
# oversized file there is refused rather than waited on or cut.
def small(path):
    try:
        fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        try:
            if not stat.S_ISREG(os.fstat(fd).st_mode): raise Refused('mastracode_store_unknown')
            raw=os.read(fd,CONFIG+1)
        finally: os.close(fd)
    except FileNotFoundError: return None
    except OSError: raise Refused('mastracode_store_unknown')
    if len(raw)>CONFIG: raise Refused('mastracode_store_unknown')
    return raw

# A config file Mastra Code reads to pick the store: absent is None, as its
# loaders treat it; unparseable is {} (settings.ts falls back to defaults,
# project.ts:401-421 loadDatabaseConfig to null). Anything this cannot read
# the way the agent did is refused, never skipped.
def config(path):
    if not path.startswith('/'): raise Refused('mastracode_store_outside_home')
    if not os.path.exists(path): return None
    raw=small(placed(path))
    if raw is None: return None
    try: value=json.loads(raw.decode('utf-8'))
    except ValueError: return {}
    return value if isinstance(value,dict) else {}

# project.ts:187-216 getAppDataDir on Linux: MASTRA_APP_DATA_DIR as given,
# else (XDG_DATA_HOME or HOME/.local/share)/mastracode, never resolved.
def app_data(env):
    if env.get('MASTRA_APP_DATA_DIR'): return env['MASTRA_APP_DATA_DIR']
    return os.path.join(env.get('XDG_DATA_HOME') or os.path.join(env.get('HOME',''),'.local','share'),'mastracode')

# libSQL's `file:` URL to a path in this user's home.
def from_url(url):
    if url==':memory:' or url.startswith('file::memory:'): raise Refused('mastracode_store_in_memory')
    if not url.startswith('file:'): raise Refused('mastracode_store_remote')
    rest=url[5:]
    if '?' in rest or '#' in rest: raise Refused('mastracode_store_remote')
    if rest.startswith('///'): rest=rest[2:]
    elif rest.startswith('//') or '%' in rest: raise Refused('mastracode_store_unknown')
    return placed(rest)

# index.ts:601,700 detectProject(process.cwd()); project.ts:82-97: inside a
# git work tree the root is `git rev-parse --show-toplevel`, else the cwd.
# Git is never run here (its config is the agent's to control): this walks
# up to the first `.git` as git's discovery does, and refuses what that walk
# cannot settle, never the default store in its place.
def agent_cwd(cwd):
    if not isinstance(cwd,str) or not cwd.startswith('/'): raise Refused('mastracode_store_unknown')
    try:
        cwd=os.path.realpath(cwd)
        top=os.stat(cwd)
    except OSError: raise Refused('mastracode_store_unknown')
    if not stat.S_ISDIR(top.st_mode): raise Refused('mastracode_store_unknown')
    return cwd

def project_root(cwd):
    top=os.stat(cwd)
    if '.git' in cwd.split('/'): raise Refused('mastracode_store_unknown')
    here=cwd
    while True:
        try: st=os.stat(here)
        except OSError: raise Refused('mastracode_store_unknown')
        # Git stops at a filesystem boundary: no repository, so the cwd.
        if st.st_dev!=top.st_dev: return cwd
        dot=os.path.join(here,'.git')
        if os.path.lexists(dot):
            try: own=os.lstat(dot)
            except OSError: raise Refused('mastracode_store_unknown')
            # Another owner's repository git refuses as dubious (safe.directory).
            if own.st_uid!=os.getuid(): raise Refused('mastracode_store_unknown')
            if stat.S_ISDIR(own.st_mode) and os.path.isfile(os.path.join(dot,'HEAD')):
                # core.worktree or core.bare move or drop the work tree; with
                # extensions.worktreeConfig, config.worktree may set them too.
                # Git's true is true, yes, on, 1 or a bare key, any case. An
                # include or includeIf can set either from a file not read here.
                body=b'\n'.join(small(os.path.join(dot,name)) or b'' for name in ('config','config.worktree'))
                if re.search(rb'(?im)^[ \t]*worktree[ \t]*=',body) or re.search(rb'(?im)^[ \t]*bare[ \t]*(=[ \t]*(true|yes|on|1)[ \t]*)?\r?$',body) or re.search(rb'(?im)^[ \t]*\[[ \t]*include(if)?\b',body):
                    raise Refused('mastracode_store_unknown')
                return here
            if stat.S_ISREG(own.st_mode):
                head=small(dot) or b''
                if head.startswith(b'gitdir: '): return here
            raise Refused('mastracode_store_unknown')
        # A bare repository on the way up ends git's walk with no work tree.
        if os.path.isfile(os.path.join(here,'HEAD')) and os.path.isdir(os.path.join(here,'objects')) and os.path.isdir(os.path.join(here,'refs')):
            raise Refused('mastracode_store_unknown')
        if here=='/': return cwd
        here=os.path.dirname(here)

# project.ts:302-346 getStorageConfig, in its order, with the env keys from
# this shell's own environment and the cwd Herdr reports.
def store(running,env):
    if not env.get('HOME','').startswith('/'): raise Refused('mastracode_store_unknown')
    backend=env.get('MASTRA_STORAGE_BACKEND')
    if backend=='pg': raise Refused('mastracode_store_remote')
    if backend=='libsql' or env.get('MASTRA_DB_URL'):
        if not env.get('MASTRA_DB_URL'): raise Refused('mastracode_store_unknown')
        return from_url(env['MASTRA_DB_URL'])
    data=app_data(env)
    # settings.ts:790-791 getSettingsPath; :1231-1236 merges storage over
    # STORAGE_DEFAULTS {backend:'libsql', libsql:{}, pg:{}} (:481-485).
    settings=config(os.path.join(data,'settings.json'))
    storage=settings.get('storage') if isinstance(settings,dict) and isinstance(settings.get('storage'),dict) else {}
    chosen=storage.get('backend','libsql')
    if chosen=='pg': raise Refused('mastracode_store_remote')
    libsql=storage.get('libsql') if isinstance(storage.get('libsql'),dict) else {}
    url=libsql.get('url')
    if chosen=='libsql' and url:
        if not isinstance(url,str): raise Refused('mastracode_store_unknown')
        return from_url(url)
    # project.ts:333-339: <project>/.mastracode/database.json, then
    # HOME/.mastracode/database.json, each {url}. Git env can put the work
    # tree anywhere, even off the cwd's ancestors, so it is refused before
    # anything is looked for. Otherwise the root is one of the cwd's
    # ancestors: with no such file and no `.git` on the way up there is no
    # root to settle, and with a `.git` the walk still refuses a work tree
    # its config moves elsewhere.
    def legacy(path):
        value=config(path)
        url=value.get('url') if isinstance(value,dict) else None
        return from_url(url) if isinstance(url,str) and url else None
    if any(env.get(key) for key in ('GIT_DIR','GIT_WORK_TREE','GIT_COMMON_DIR','GIT_CEILING_DIRECTORIES','GIT_DISCOVERY_ACROSS_FILESYSTEM')):
        raise Refused('mastracode_store_unknown')
    cwd=agent_cwd(running)
    here=cwd; settle=False
    while not settle:
        settle=any(os.path.lexists(os.path.join(here,*name)) for name in (('.mastracode','database.json'),('.git',)))
        if here in ('/',''): break
        here=os.path.dirname(here)
    if settle:
        found=legacy(os.path.join(project_root(cwd),'.mastracode','database.json'))
        if found: return found
    found=legacy(os.path.join(env['HOME'],'.mastracode','database.json'))
    if found: return found
    # project.ts:218-223 MASTRA_DB_PATH, else getAppDataDir()/mastra.db.
    return from_url('file:'+(env.get('MASTRA_DB_PATH') or os.path.join(data,'mastra.db')))

# mode=ro, never immutable: Mastra Code is a live writer in WAL mode
# (stores/libsql/src/storage/index.ts:327), and an immutable read can see
# stale or torn pages. A store SQLite cannot open read-only is refused, with
# no fallback. Only a regular file is opened, by the one resolved path: a
# FIFO or device there would block or misread, and a symlinked -wal or -shm
# sidecar would let SQLite read outside the checked path.
def connect(db):
    try: st=os.stat(db)
    except FileNotFoundError: raise LookupError()
    except OSError: raise Refused('mastracode_store_unreadable')
    if not stat.S_ISREG(st.st_mode): raise Refused('mastracode_store_unreadable')
    if any(os.path.islink(db+side) for side in ('-wal','-shm')): raise Refused('mastracode_store_unreadable')
    try:
        fd=os.open(db,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        try:
            opened=os.fstat(fd)
            header=os.read(fd,100)
        finally: os.close(fd)
    except OSError: raise Refused('mastracode_store_unreadable')
    if (opened.st_dev,opened.st_ino)!=(st.st_dev,st.st_ino) or not stat.S_ISREG(opened.st_mode): raise Refused('mastracode_store_unreadable')
    if len(header)<100 or not header.startswith(b'SQLite format 3\x00'): raise LookupError()
    try:
        conn=sqlite3.connect('file:'+urllib.parse.quote(db,safe='/')+'?mode=ro',uri=True,timeout=5)
        conn.row_factory=sqlite3.Row
        conn.execute('PRAGMA query_only=ON')
        conn.execute('BEGIN')
        conn.execute('SELECT count(*) FROM sqlite_master').fetchone()
    except sqlite3.DatabaseError: raise Refused('mastracode_store_unreadable')
    # SQLite opened the path by name: it must still be the file checked above.
    try: again=os.stat(db)
    except OSError: again=None
    if again is None or (again.st_dev,again.st_ino)!=(st.st_dev,st.st_ino):
        conn.close()
        raise Refused('mastracode_store_unreadable')
    return conn

def columns(conn,table): return {row[1] for row in conn.execute('PRAGMA table_info(%s)'%table)}

# Cut to `limit` UTF-8 bytes on a character boundary.
def clip(text,limit): return text if len(text)*4<=limit else text.encode()[:limit].decode(errors='ignore')

def text(value): return value if isinstance(value,str) else ''

def image(mime): return isinstance(mime,str) and mime.lower().startswith('image/')

# createdAt is TEXT: toISOString 'YYYY-MM-DDTHH:MM:SS.sssZ', or SQLite's
# CURRENT_TIMESTAMP 'YYYY-MM-DD HH:MM:SS' in UTC (stores/libsql/src/storage/
# db/index.ts:717-718, :1126-1127). Parsed, since the two do not sort
# together as text; what does not parse sorts last.
def millis(value):
    if isinstance(value,(int,float)) and not isinstance(value,bool): return int(value if value>1e11 else value*1000)
    if not isinstance(value,str): return None
    raw=value.strip().replace(' ','T',1)
    if raw.endswith('Z') or raw.endswith('z'): raw=raw[:-1]+'+00:00'
    try: moment=datetime.fromisoformat(raw)
    except ValueError: return None
    if moment.tzinfo is None: moment=moment.replace(tzinfo=timezone.utc)
    return int(moment.timestamp()*1000)

# packages/core/src/agent/message-list/state/types.ts:64-130 parts: only
# what Chat shows, never a file's data or a source's url.
def part(p):
    if not isinstance(p,dict): return {'t':'unknown'}
    kind=p.get('type')
    if kind=='text': return {'t':'text','text':clip(text(p.get('text')),TEXT)}
    if kind=='reasoning':
        words=text(p.get('reasoning')) or text(p.get('text'))
        if not words and isinstance(p.get('details'),list):
            words='\n'.join(text(d.get('text')) for d in p['details'] if isinstance(d,dict) and text(d.get('text')))
        return {'t':'reasoning','text':clip(words,TEXT)}
    if kind=='file':
        mime=p.get('mimeType') or p.get('mediaType')
        return {'t':'file','image':image(mime),'name':(text(p.get('filename')) or text(mime))[:256]}
    if kind in ('source-url','source-document','source'):
        src=p.get('source') if isinstance(p.get('source'),dict) else p
        return {'t':'source','name':(text(src.get('title')) or text(src.get('filename')))[:256]}
    if kind=='tool-invocation':
        call=p.get('toolInvocation') if isinstance(p.get('toolInvocation'),dict) else {}
        state=text(call.get('state'))
        out={'t':'tool','call':text(call.get('toolCallId'))[:256],'name':text(call.get('toolName'))[:128],'status':'call',
            'input':clip(dump(call.get('args',{})),ARGS),'output':''}
        if state=='result':
            result=call.get('result')
            out['status']='error' if call.get('isError') is True else 'completed'
            out['output']=clip(result if isinstance(result,str) else dump(result),RESULT)
        elif state in ('output-error','output-denied'):
            out['status']='error'
            out['output']=clip(text(call.get('errorText')) or ('denied' if state=='output-denied' else 'error'),RESULT)
        return out
    return {'t':kind if isinstance(kind,str) and kind else 'unknown'}

# domains/memory/index.ts:375-392 parseRow: JSON content, else the raw text.
def content(raw):
    try: value=json.loads(raw)
    except (TypeError,ValueError): value=raw
    if isinstance(value,str): return {'parts':[{'t':'text','text':clip(value,TEXT)}]} if value else {'parts':[]}
    if not isinstance(value,dict): return {'parts':[{'t':'unreadable'}]}
    parts=[part(p) for p in value.get('parts')] if isinstance(value.get('parts'),list) else []
    if not parts and text(value.get('content')): parts=[{'t':'text','text':clip(value['content'],TEXT)}]
    for a in value.get('experimental_attachments') if isinstance(value.get('experimental_attachments'),list) else []:
        if isinstance(a,dict): parts.append({'t':'file','image':image(a.get('contentType')),'name':(text(a.get('name')) or text(a.get('contentType')))[:256]})
    meta=value.get('metadata') if isinstance(value.get('metadata'),dict) else {}
    return {'parts':parts,'signal':meta.get('signal')}

def project(conn,sid,place,rid):
    row=conn.execute('SELECT id,thread_id,role,type,content,createdAt FROM mastra_messages WHERE rowid=?',(rid,)).fetchone()
    body=content(row['content'])
    role=text(row['role'])
    kind=None
    if role=='signal':
        # AIV4Adapter.ts:89-97 getSignalType: metadata.signal.type, else
        # the row's type.
        signal=body.get('signal')
        kind=signal.get('type') if isinstance(signal,dict) and isinstance(signal.get('type'),str) else row['type']
        kind=text(kind)[:32]
    return {'id':place*KEY+check(row['id']),'mid':row['id'],'ts':millis(row['createdAt']),'role':role[:32],'signal':kind,'parts':body['parts']}

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
    got,status,running=identity()
    if got!=expect or got['agent']!='mastracode' or got['kind']!='id' or not got['pid']: return {'changed':True}
    sid=got['path']
    if not ID.match(sid): return {'changed':True}
    # Herdr reports `session-init` until Mastra Code names its thread
    # (sdk/src/index.ts:930-940): no thread yet, never the latest one.
    if sid=='session-init': return {'error':'missing'}
    db=store(running,environment())
    try: conn=connect(db)
    except LookupError: return {'error':'missing'}
    if any(not need<=columns(conn,table) for table,need in NEED.items()): raise Refused('mastracode_store_layout')
    if conn.execute('SELECT 1 FROM mastra_threads WHERE id=?',(sid,)).fetchone() is None: return {'error':'missing'}
    if conn.execute('SELECT count(*) FROM mastra_messages WHERE thread_id=?',(sid,)).fetchone()[0]>MAX_ROWS: raise Refused('mastracode_session_too_large')
    keyed=[(millis(r[2]),r[1],r[0]) for r in conn.execute('SELECT rowid,id,createdAt FROM mastra_messages WHERE thread_id=? LIMIT ?',(sid,MAX_ROWS+1))]
    if len(keyed)>MAX_ROWS: raise Refused('mastracode_session_too_large')
    keyed.sort(key=lambda k:(k[0] is None,k[0] or 0,k[1]))
    order=[(mid,rid) for _,mid,rid in keyed]
    n=len(order); size=n*KEY
    mode=opts['mode']; cursor=int(opts.get('cursor',0))
    reset=mode=='tail'
    if mode=='after':
        # The message last shown must still be at its place.
        last,mark=divmod(max(cursor-1,0),KEY)
        reset=cursor>size or last>=n or check(order[last][0])!=mark
        span=range(last,min(n,last+LIMIT))
    elif mode=='before':
        first,mark=divmod(cursor,KEY)
        reset=cursor>size or first>n or (first<n and check(order[first][0])!=mark)
        span=range(max(0,first-LIMIT),min(first,n))
    if reset: span=range(max(0,n-LIMIT),n)
    rows=[]; used=0
    newest_first=mode=='before' or reset
    for place in (reversed(span) if newest_first else span):
        item=project(conn,sid,place,order[place][1])
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
    # Every answer, a refusal or a missing thread too, must describe the
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
