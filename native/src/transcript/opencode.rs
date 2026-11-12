//! OpenCode 2.2 session projections, fenced by the pane's exact current identity.
//! Reads only this session, in a read-only SQLite transaction; never exports,
//! resumes or starts an agent. Sequence cursors also reread a streaming row.

use super::{
    Block, Entry, Identity, Image, Kind, Media, MediaOutcome, Read, Refusal, Response, Source,
    Usage, cap, text_block,
};
use crate::{Error, Result, ssh::Exec};
use base64::{Engine, engine::general_purpose::STANDARD};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::time::Duration;

const PAGE_ROOM: usize = 2 * 1024 * 1024;

pub(super) fn page(
    exec: &Exec,
    session: &str,
    pane: &str,
    expect: &Identity,
    read: Read,
    reset: bool,
) -> Result<Response> {
    let (mode, cursor) = match read {
        Read::Tail => ("tail", 0),
        Read::After { offset } => ("after", offset),
        Read::Before { offset } => ("before", offset),
    };
    let reply = query(
        exec,
        session,
        pane,
        expect,
        json!({"mode":mode,"cursor":cursor}),
        PAGE_ROOM,
    )?;
    if reply["changed"] == true {
        return Err(Error::NoTranscript);
    }
    if reply["too_large"] == true {
        return Err(Error::EntryTooLarge);
    }
    let rows = reply["rows"].as_array().ok_or(Error::BadReply)?;
    let entries = rows.iter().filter_map(entry).collect();
    Ok(Response {
        identity: expect.clone(),
        status: reply["status"].as_str().unwrap_or("unknown").to_owned(),
        reset: reset || reply["reset"] == true,
        size: reply["size"].as_u64().ok_or(Error::BadReply)?,
        start: reply["start"].as_u64().ok_or(Error::BadReply)?,
        end: reply["end"].as_u64().ok_or(Error::BadReply)?,
        has_older: reply["has_older"].as_bool().ok_or(Error::BadReply)?,
        entries,
    })
}

pub(super) fn image(
    exec: &Exec,
    session: &str,
    pane: &str,
    expect: &Identity,
    media: &Media,
) -> Result<MediaOutcome> {
    let refused = |error| Ok(MediaOutcome::Refused { error });
    if media.v != super::MEDIA_VERSION {
        return refused(Refusal::Version);
    }
    if media.end != media.offset.checked_add(1).unwrap_or(0) {
        return refused(Refusal::Moved);
    }
    if media
        .blob
        .as_deref()
        .is_some_and(|hash| !super::blob_hash(hash))
    {
        return refused(Refusal::Missing);
    }
    let reply = query(
        exec,
        session,
        pane,
        expect,
        json!({"mode":"media", "seq":media.offset,"entry":media.entry,"part":media.part,"blob":media.blob}),
        (super::MAX_IMAGE as usize * 4 / 3) + PAGE_ROOM,
    )?;
    if reply["changed"] == true {
        return refused(Refusal::InstanceChanged);
    }
    if let Some(error) = reply["error"].as_str() {
        return refused(match error {
            "moved" => Refusal::Moved,
            "corrupt" => Refusal::Corrupt,
            "too_large" => Refusal::TooLarge,
            "unsupported" => Refusal::Unsupported,
            _ => Refusal::Missing,
        });
    }
    let data = reply["data"].as_str().ok_or(Error::BadReply)?;
    if super::inline_bytes(data) > super::MAX_IMAGE {
        return refused(Refusal::TooLarge);
    }
    let decoded = match super::LENIENT.decode(data) {
        Ok(bytes) => bytes,
        Err(_) => return refused(Refusal::Unsupported),
    };
    match super::image_mime(&decoded) {
        Some(mime) => Ok(MediaOutcome::Image {
            mime,
            data: STANDARD.encode(decoded),
        }),
        None => refused(Refusal::Unsupported),
    }
}

fn query(
    exec: &Exec,
    session: &str,
    pane: &str,
    expect: &Identity,
    options: Value,
    room: usize,
) -> Result<Value> {
    let output = exec.run(
        format!(
            "/bin/sh -c {}",
            shell_quote(&query_script(session, pane, expect, &options))
        ),
        Duration::from_secs(60),
        room,
    )?;
    if output.exit_status != Some(0) {
        return Err(Error::NoTranscript);
    }
    serde_json::from_slice(&output.tail).map_err(Error::from)
}

fn query_script(session: &str, pane: &str, expect: &Identity, options: &Value) -> String {
    format!(
        "python3 - {} {} {} <<'HERDLINE_OPENCODE_PY'\n{}\nHERDLINE_OPENCODE_PY",
        shell_quote(&serde_json::to_string(expect).unwrap_or_default()),
        shell_quote(&options.to_string()),
        shell_quote(&super::instance_script(session, pane)),
        PYTHON
    )
}

fn part_data(part: &Value) -> &Value {
    part.get("data")
        .filter(|data| data.is_object())
        .unwrap_or(part)
}

fn parts(row: &Value) -> impl Iterator<Item = &Value> {
    let projected = row["parts"].as_array().filter(|parts| !parts.is_empty());
    projected
        .or_else(|| row["data"]["content"].as_array())
        .into_iter()
        .flatten()
}

fn files(row: &Value) -> Vec<&Value> {
    let mut files: Vec<&Value> = row["data"]["files"]
        .as_array()
        .into_iter()
        .flatten()
        .collect();
    for part in parts(row) {
        let data = part_data(part);
        if data["type"] == "tool" || part["type"] == "tool" {
            files.extend(
                data["state"]["content"]
                    .as_array()
                    .into_iter()
                    .flatten()
                    .filter(|file| file["type"] == "file"),
            );
        }
    }
    files
}

fn image_source(file: &Value) -> Source {
    if let Some(hash) = file["uri"]
        .as_str()
        .and_then(|uri| uri.strip_prefix("media:"))
        .filter(|hash| super::blob_hash(hash))
    {
        Source::Blob {
            hash: hash.to_owned(),
        }
    } else if let Some(data) = file["data"].as_str().filter(|data| !data.is_empty()) {
        Source::Inline {
            bytes: super::inline_bytes(data),
        }
    } else {
        Source::Unreadable
    }
}

pub(super) fn entry(row: &Value) -> Option<Entry> {
    let seq = row["seq"].as_u64()?;
    let end = seq.checked_add(1)?;
    let data = &row["data"];
    let kind = match row["type"].as_str()? {
        "compaction" => Kind::Compaction,
        "user" | "assistant" | "system" | "synthetic" | "skill" | "shell" => Kind::Message,
        _ => Kind::Other,
    };
    let role =
        (kind == Kind::Message).then(|| row["type"].as_str().unwrap_or("unknown").to_owned());
    let mut blocks = Vec::new();
    match row["type"].as_str()? {
        "user" | "system" | "synthetic" | "skill" => {
            if let Some(text) = data["text"].as_str() {
                blocks.push(text_block(text, false));
            }
        }
        "compaction" => {
            if let Some(text) = data["summary"].as_str() {
                blocks.push(text_block(text, false));
            }
        }
        "assistant" => {
            for part in parts(row) {
                let content = part_data(part);
                let tag = part["type"]
                    .as_str()
                    .or_else(|| content["type"].as_str())
                    .unwrap_or_default();
                match tag {
                    "text" | "reasoning" => {
                        if let Some(text) = content["text"].as_str() {
                            blocks.push(text_block(text, tag == "reasoning"));
                        }
                    }
                    "tool" => {
                        let state = &content["state"];
                        let call_id = part["call_id"]
                            .as_str()
                            .or_else(|| content["id"].as_str())
                            .unwrap_or_default();
                        let (args, truncated) = cap(
                            &state["input"]
                                .as_str()
                                .map(str::to_owned)
                                .unwrap_or_else(|| state["input"].to_string()),
                            super::ARGS_LIMIT,
                        );
                        let name = cap(content["name"].as_str().unwrap_or_default(), 128).0;
                        blocks.push(Block::ToolCall {
                            call_id: call_id.to_owned(),
                            name: name.clone(),
                            args,
                            intent: None,
                            truncated,
                        });
                        if matches!(state["status"].as_str(), Some("completed" | "error")) {
                            let output = state["content"]
                                .as_array()
                                .into_iter()
                                .flatten()
                                .filter_map(|part| part["text"].as_str())
                                .collect::<Vec<_>>()
                                .join("\n");
                            let output = if output.is_empty() {
                                state["error"]["data"]["message"]
                                    .as_str()
                                    .unwrap_or_default()
                                    .to_owned()
                            } else {
                                output
                            };
                            let (text, truncated) = cap(
                                &crate::herdr_setup::plain_text(output.as_bytes()),
                                super::RESULT_LIMIT,
                            );
                            blocks.push(Block::ToolResult {
                                call_id: call_id.to_owned(),
                                name,
                                text,
                                is_error: state["status"] == "error",
                                images: Vec::new(),
                                truncated,
                                exit_code: state["metadata"]["exit"].as_i64(),
                                answers: Vec::new(),
                                timed_out: false,
                                subagents: Vec::new(),
                            });
                        }
                    }
                    _ => {}
                }
            }
        }
        "shell" => {
            let (text, truncated) = cap(
                data["output"].as_str().unwrap_or_default(),
                super::RESULT_LIMIT,
            );
            blocks.push(Block::ToolResult {
                call_id: row["id"].as_str().unwrap_or_default().to_owned(),
                name: "shell".into(),
                text,
                is_error: data["exit"].as_i64().is_some_and(|n| n != 0),
                images: Vec::new(),
                truncated,
                exit_code: data["exit"].as_i64(),
                answers: Vec::new(),
                timed_out: false,
                subagents: Vec::new(),
            });
        }
        _ => {}
    }
    if kind == Kind::Message {
        for (part, file) in files(row).into_iter().enumerate() {
            blocks.push(Block::Image(Image {
                part,
                source: image_source(file),
            }));
        }
    }
    let tokens = &data["tokens"];
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
            cost: data["cost"]
                .as_f64()
                .filter(|cost| cost.is_finite() && *cost >= 0.0),
        }
    });
    Some(Entry {
        id: row["id"].as_str().map(str::to_owned),
        parent_id: None,
        offset: seq,
        end,
        kind,
        role,
        blocks,
        timestamp: row["time_created"].as_u64(),
        usage,
        stop_reason: data["finish"].as_str().map(|reason| cap(reason, 32).0),
        steer: None,
        subagent_ends: Vec::new(),
    })
}

const PYTHON: &str = r#"import base64, hashlib, json, os, sqlite3, subprocess, sys, urllib.parse
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
LIMIT=200
MAX_LINE=1048576
MAX_IMAGE=20971520

def identity():
    result=subprocess.run(['/bin/sh','-c',report],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20)
    raw=result.stdout
    if result.returncode or len(raw)>65536 or not raw.startswith(b'@@pane\n'): raise ValueError('report')
    first,proc=raw[len(b'@@pane\n'):].split(b'\n@@proc\n',1)
    pane=json.loads(first)['result']['pane']
    process=json.loads(proc).get('result',{}).get('process_info',{})
    ref=pane['agent_session']
    pid=next((p.get('pid') for p in process.get('foreground_processes',[]) if p.get('name')=='opencode'),None)
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:opencode'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown')

def file_parts(row):
    refs=list(row['data'].get('files') or [])
    for part in row['parts']:
        data=part.get('data') or part
        if part.get('type')=='tool' or data.get('type')=='tool':
            refs.extend(x for x in data.get('state',{}).get('content',[]) if x.get('type')=='file')
    return refs

def main():
    got,status=identity()
    if got!=expect or got['agent']!='opencode' or got['kind']!='id' or not got['pid']: return {'changed':True}
    # The store from this shell's own HOME, XDG_DATA_HOME and OPENCODE_DB; no
    # other process's environment is read. An agent started with values this
    # shell lacks is not found here, never guessed.
    env=os.environ
    home=os.path.realpath(env['HOME'])
    data=os.path.realpath(os.path.join(env.get('XDG_DATA_HOME') or os.path.join(home,'.local','share'),'opencode'))
    name=env.get('OPENCODE_DB') or 'opencode.db'
    if name==':memory:': raise ValueError('memory')
    db=os.path.realpath(os.path.join(data,name))
    if os.path.commonpath([home,data,db])!=home or os.path.commonpath([data,db])!=data: raise ValueError('root')
    if not os.path.isfile(db): raise ValueError('db')
    conn=sqlite3.connect('file:'+urllib.parse.quote(db,safe='/')+'?mode=ro',uri=True,timeout=5)
    conn.execute('PRAGMA query_only=ON')
    conn.execute('BEGIN')
    sid=expect['path']
    if conn.execute('SELECT 1 FROM session_v2 WHERE id=?',(sid,)).fetchone() is None: return {'changed':True}
    def row(value):
        mid,kind,seq,created,raw=value
        if len(raw)>MAX_LINE: raise OverflowError()
        content=json.loads(raw)
        parts=[]
        for tag,cid,body in conn.execute('SELECT type,call_id,data FROM session_part WHERE session_id=? AND message_id=? ORDER BY ordinal',(sid,mid)):
            if len(body)>MAX_LINE: raise OverflowError()
            parts.append({'type':tag,'call_id':cid,'data':json.loads(body)})
        if not parts:
            parts=content.get('content') or []
        return {'id':mid,'type':kind,'seq':seq,'time_created':created,'data':content,'parts':parts}
    mode=opts['mode']
    if mode=='media':
        value=conn.execute('SELECT id,type,seq,time_created,data FROM session_message WHERE session_id=? AND seq=?',(sid,int(opts['seq']))).fetchone()
        if value is None or value[0]!=opts.get('entry'): answer={'error':'moved'}
        else:
            refs=file_parts(row(value)); part=int(opts['part'])
            if part<0 or part>=len(refs): answer={'error':'moved'}
            else:
                ref=refs[part]; uri=ref.get('uri',''); claimed=opts.get('blob')
                if uri.startswith('media:'):
                    hash=uri[6:]
                    if hash!=claimed or len(hash)!=64 or any(c not in '0123456789abcdef' for c in hash): answer={'error':'moved'}
                    else:
                        value=conn.execute('SELECT data FROM blob WHERE hash=? AND bytes<=?',(hash,MAX_IMAGE)).fetchone()
                        if value is None: answer={'error':'missing'}
                        elif hashlib.sha256(value[0]).hexdigest()!=hash: answer={'error':'corrupt'}
                        else: answer={'data':base64.b64encode(value[0]).decode()}
                elif claimed: answer={'error':'moved'}
                else:
                    encoded=ref.get('data')
                    if not isinstance(encoded,str): answer={'error':'unsupported'}
                    elif len(encoded)>MAX_IMAGE*4//3+4: answer={'error':'too_large'}
                    else: answer={'data':encoded}
    else:
        cursor=int(opts.get('cursor',0))
        size=conn.execute('SELECT COALESCE(MAX(seq),0)+1 FROM session_message WHERE session_id=?',(sid,)).fetchone()[0]
        reset=mode=='tail' or (mode=='after' and cursor>size)
        select='SELECT id,type,seq,time_created,data FROM session_message WHERE session_id=?'
        if reset:
            found=conn.execute(select+' ORDER BY seq DESC LIMIT ?',(sid,LIMIT)).fetchall()[::-1]
        elif mode=='before':
            found=conn.execute(select+' AND seq<? ORDER BY seq DESC LIMIT ?',(sid,cursor,LIMIT)).fetchall()[::-1]
        else:
            # Reread the last loaded message, never jump to a later tail past a backlog.
            found=conn.execute(select+' AND seq>=? ORDER BY seq LIMIT ?',(sid,max(1,cursor-1),LIMIT)).fetchall()
        rows=[]; used=0
        for value in found:
            normalized=row(value)
            packed=json.dumps(normalized,separators=(',',':'))
            if used+len(packed.encode())>1572864:
                if not rows: raise OverflowError()
                break
            used+=len(packed.encode()); rows.append(normalized)
        start=rows[0]['seq'] if rows else cursor
        end=rows[-1]['seq']+1 if rows else cursor
        if reset and not rows: start=end=size
        older=bool(conn.execute('SELECT 1 FROM session_message WHERE session_id=? AND seq<? LIMIT 1',(sid,start)).fetchone())
        answer={'status':status,'reset':reset,'size':size,'start':start,'end':end,'has_older':older,'rows':rows}
    conn.close()
    after,_=identity()
    if after!=expect: return {'changed':True}
    return answer
try:
    print(json.dumps(main(),separators=(',',':')))
except OverflowError:
    print('{"too_large":true}')
except Exception:
    sys.exit(1)
"#;

#[cfg(test)]
#[allow(clippy::expect_used)]
mod tests {
    use super::*;
    #[test]
    fn running_tool_has_no_result_and_two_images_have_distinct_ordinals() {
        let row = json!({"id":"msg_1","type":"assistant","seq":1,"data":{},"parts":[
            {"type":"tool","call_id":"c1","data":{"name":"read","state":{"status":"running","input":{"path":"x"}}}},
            {"type":"tool","call_id":"c2","data":{"name":"image","state":{"status":"completed","input":{},"content":[{"type":"file","data":"YWJj"},{"type":"file","data":"ZGVm"}]}}}
        ]});
        let entry = entry(&row).expect("row");
        assert!(matches!(&entry.blocks[0],Block::ToolCall {call_id,..} if call_id=="c1"));
        assert!(
            !entry
                .blocks
                .iter()
                .any(|block| matches!(block,Block::ToolResult {call_id,..} if call_id=="c1"))
        );
        let ordinals = entry
            .blocks
            .iter()
            .filter_map(|block| {
                if let Block::Image(image) = block {
                    Some(image.part)
                } else {
                    None
                }
            })
            .collect::<Vec<_>>();
        assert_eq!(ordinals, vec![0, 1]);
    }

    #[test]
    fn inline_assistant_projection_keeps_text_when_part_table_is_empty() {
        let row = json!({"id":"msg_inline","type":"assistant","seq":2,"data":{"content":[{"type":"text","text":"consumer answer"}]},"parts":[]});
        let parsed = entry(&row).expect("row");
        assert!(matches!(&parsed.blocks[..], [Block::Text {text,..}] if text == "consumer answer"));
    }

    /// FIXTURE-ONLY: a synthetic store with just the columns the reader
    /// selects (`session_v2`, `session_message`, `session_part`), not
    /// captured from a real OpenCode. Each session holds one user turn.
    const STORE: &str = r#"import json, sqlite3, sys
conn = sqlite3.connect(sys.argv[1])
conn.executescript('''
CREATE TABLE session_v2 (id TEXT PRIMARY KEY);
CREATE TABLE session_message (id TEXT PRIMARY KEY, session_id TEXT, type TEXT, seq INTEGER, time_created INTEGER, data TEXT);
CREATE TABLE session_part (session_id TEXT, message_id TEXT, type TEXT, call_id TEXT, data TEXT, ordinal INTEGER);
''')
for sid, text in json.loads(sys.argv[2]).items():
    conn.execute('INSERT INTO session_v2 VALUES (?)', (sid,))
    conn.execute('INSERT INTO session_message VALUES (?,?,?,?,?,?)', ('msg_' + sid, sid, 'user', 1, 1000, json.dumps({'text': text})))
conn.commit()
"#;

    const OLD: &str = "ses_0a1b2c3d4e5fAbCdEfGhIjKlMn";
    const NEW: &str = "ses_0a1b2c3d4e60ZyXwVuTsRqPoNm";

    /// A host whose fake Herdr reports `pane` and `process` for w1:p1.
    struct Host(tempfile::TempDir);

    impl Host {
        fn new(sessions: Value) -> Self {
            let home = tempfile::tempdir().expect("home");
            let bin = home.path().join(".local/bin");
            std::fs::create_dir_all(&bin).expect("bin");
            let db = home.path().join(".local/share/opencode/opencode.db");
            std::fs::create_dir_all(db.parent().expect("dir")).expect("data");
            let made = std::process::Command::new("python3").args(["-c", STORE]).arg(&db).arg(sessions.to_string()).status();
            assert!(made.expect("python3").success());
            let herdr = bin.join("herdr");
            std::fs::write(&herdr, "#!/bin/sh\ncase \"$*\" in *process-info*) cat \"$HOME/proc.json\";; *) cat \"$HOME/pane.json\";; esac\necho\n").expect("herdr");
            std::fs::set_permissions(&herdr, std::os::unix::fs::PermissionsExt::from_mode(0o755)).expect("mode");
            Self(home)
        }

        /// Herdr's pane report: `ref` as the pane's agent_session.
        fn reports(&self, session_ref: Value) {
            let pane = json!({"id": "cli:pane:get", "result": {"pane": {"agent": "opencode", "agent_session": session_ref,
                "agent_status": "idle", "pane_id": "w1:p1", "terminal_id": "term_1"}, "type": "pane_info"}});
            std::fs::write(self.0.path().join("pane.json"), pane.to_string()).expect("pane");
        }

        /// The pane's foreground process group as Herdr lists it.
        fn foreground(&self, members: Value) {
            let process = json!({"result": {"process_info": {"foreground_processes": members, "pane_id": "w1:p1", "shell_pid": 7}}});
            std::fs::write(self.0.path().join("proc.json"), process.to_string()).expect("proc");
        }

        /// Chat's next read of the pane while it shows `expect`.
        fn read(&self, expect: &Identity, read: Read) -> Result<Response> {
            let home = self.0.path();
            let exec = Exec::local(vec![
                ("HOME".into(), home.display().to_string()),
                ("PATH".into(), format!("{}:/usr/bin:/bin", home.join(".local/bin").display())),
            ]);
            super::super::page(&exec, "lab", &super::super::Request { pane: "w1:p1".into(), expect: Some(expect.clone()), read, media: None })
        }
    }

    fn shown(sid: &str) -> Identity {
        Identity {
            pane_id: "w1:p1".into(),
            terminal_id: "term_1".into(),
            pid: Some(42),
            path: sid.into(),
            agent: "opencode".into(),
            source: "herdr:opencode".into(),
            kind: "id".into(),
        }
    }

    fn id(sid: &str) -> Value {
        json!({"agent": "opencode", "kind": "id", "source": "herdr:opencode", "value": sid})
    }

    fn texts(page: &Response) -> Vec<&str> {
        page.entries.iter().flat_map(|entry| &entry.blocks).filter_map(|block| match block {
            Block::Text { text, .. } => Some(text.as_str()),
            _ => None,
        }).collect()
    }

    #[test]
    fn a_new_session_herdr_reports_for_the_same_process_restarts_chat_from_its_tail() {
        let host = Host::new(json!({OLD: "old turn", NEW: "new turn"}));
        host.foreground(json!([{"name": "opencode", "pid": 42}]));
        host.reports(id(OLD));
        let after = Read::After { offset: 2 };
        // Still the shown session: read on from where Chat is, no restart.
        let same = host.read(&shown(OLD), after).expect("same session");
        assert_eq!((same.identity.path.as_str(), same.reset), (OLD, false));
        // The TUI selected another session (Herdr's opencode select report):
        // the next read restarts from that session's tail, and only it.
        host.reports(id(NEW));
        let moved = host.read(&shown(OLD), after).expect("new session");
        assert_eq!(moved.identity, shown(NEW));
        assert!(moved.reset);
        assert_eq!(texts(&moved), ["new turn"]);
    }

    #[test]
    fn an_uncertain_new_session_is_refused_never_read_as_the_old_or_another() {
        let host = Host::new(json!({OLD: "old turn", NEW: "new turn"}));
        let refused = |host: &Host| matches!(host.read(&shown(OLD), Read::After { offset: 2 }), Err(Error::NoTranscript));
        // No foreground opencode: no process vouches for the reported session.
        host.reports(id(NEW));
        host.foreground(json!([{"name": "node", "pid": 42}]));
        assert!(refused(&host), "no opencode process");
        host.foreground(json!([{"name": "opencode", "pid": 42}]));
        // A reference that is not an OpenCode session id.
        for session_ref in [
            json!({"agent": "opencode", "kind": "path", "source": "herdr:opencode", "value": "/h/s.jsonl"}),
            json!({"agent": "opencode", "kind": "id", "source": "herdr:opencode", "value": "../ses_x"}),
        ] {
            host.reports(session_ref.clone());
            assert!(refused(&host), "{session_ref}");
        }
        // An id this store has no session for: never the store's other one.
        let missing = Host::new(json!({OLD: "old turn"}));
        missing.foreground(json!([{"name": "opencode", "pid": 42}]));
        missing.reports(id(NEW));
        assert!(refused(&missing), "unknown session");
    }
}
