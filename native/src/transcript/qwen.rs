//! Qwen Code's legacy session journal (`<base>/projects/<sanitizeCwd>/chats/
//! <id>.jsonl`, QwenLM/qwen-code 03c125cb), read only. The host script
//! fences the pane's exact identity, resolves the file without following a
//! link and proves the file is this project's; the tree is rebuilt here.
//! A managed journal is refused by type, never shown as an empty chat. Never
//! sends, resumes, queues or talks to `qwen serve`.

use super::{
    ARGS_LIMIT, Block, Entry, HEADER_ROOM, Identity, Image, Kind, MAX_WINDOW, RESULT_LIMIT, Read,
    Response, Source, Usage, cap, inline_bytes, iso_timestamp, text_block,
};
use crate::{Error, Result, ssh::Exec};
use herdr_client::shell_quote;
use serde_json::{Value, json};
use std::collections::{HashMap, HashSet};

/// What [`Error::UnsupportedFormat`] names for a managed local journal.
const MANAGED: &str = "managed_session_journal";

/// The whole active chain of the session, or "not modified" when the file's
/// size and mtime are unchanged since the stamp the app holds in `end`. A
/// journal is a tree, not an append-only list, so there is no byte cursor:
/// any change reloads the chain and replaces it (`reset`).
pub(super) fn page(
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
        super::read_timeout(MAX_WINDOW),
        usize::try_from(MAX_WINDOW).unwrap_or(usize::MAX) + HEADER_ROOM,
    )?;
    if output.exit_status != Some(0) {
        return Err(Error::NoTranscript);
    }
    answer(&output.tail, expect, reset)
}

fn script(session: &str, pane: &str, expect: &Identity, stamp: u64) -> String {
    format!(
        "python3 - {} {} {} <<'HERDLINE_QWEN_PY'\n{PYTHON}\nHERDLINE_QWEN_PY",
        shell_quote(&serde_json::to_string(expect).unwrap_or_default()),
        shell_quote(&json!({ "stamp": stamp }).to_string()),
        shell_quote(&super::instance_script(session, pane)),
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
    match head["error"].as_str() {
        None => {}
        Some("unconfined" | "foreign") => return Err(Error::TranscriptUnconfined),
        Some("outside_home") => return Err(Error::SessionRootOutsideHome),
        Some("too_large") => return Err(Error::EntryTooLarge),
        Some(_) => return Err(Error::NoTranscript),
    }
    if head["managed"] == true {
        return Err(Error::UnsupportedFormat(MANAGED));
    }
    let stamp = head["stamp"].as_u64().ok_or(Error::BadReply)?;
    let not_modified = head["not_modified"] == true;
    let entries = if not_modified {
        Vec::new()
    } else {
        chain(&records(data))?
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

/// Every record in file order. A crash can glue two objects on one line
/// (`}{`, qwen-code #3606); each is kept. A line that stops parsing keeps
/// what came before it, so a half-written last line drops only its tail.
fn records(data: &[u8]) -> Vec<Value> {
    let mut records = Vec::new();
    for line in data.split(|&byte| byte == b'\n') {
        for value in serde_json::Deserializer::from_slice(line).into_iter::<Value>() {
            match value {
                Ok(value) if value.is_object() => records.push(value),
                Ok(_) => {}
                Err(error) => {
                    tracing::debug!(%error, "qwen record skipped");
                    break;
                }
            }
        }
    }
    records
}

fn managed(record: &Value) -> bool {
    let subtype = record["subtype"].as_str().unwrap_or_default();
    subtype.starts_with("managed_session_")
        || (subtype == "session_execution_engine" && record["systemPayload"]["engine"] == "managed")
}

/// Records that are not part of the conversation tree
/// (`isTranscriptConversationRecord`).
fn conversation(record: &Value) -> bool {
    !matches!(
        record["subtype"].as_str(),
        Some("session_sources_snapshot" | "session_artifact_event" | "session_artifact_snapshot")
    )
}

/// The active chain as entries: fragments of one uuid merged, the leaf the
/// last conversation record in the file, walked back by `parentUuid` to its
/// root. A rewind's abandoned branch is never on that walk; a missing parent
/// or a cycle ends it, nothing is stitched across a gap.
fn chain(records: &[Value]) -> Result<Vec<Entry>> {
    if records.iter().any(managed) {
        return Err(Error::UnsupportedFormat(MANAGED));
    }
    let mut merged: HashMap<&str, Value> = HashMap::new();
    let mut leaf = None;
    for record in records.iter().filter(|record| conversation(record)) {
        let Some(uuid) = record["uuid"].as_str() else {
            continue;
        };
        leaf = Some(uuid);
        match merged.get_mut(uuid) {
            None => {
                merged.insert(uuid, record.clone());
            }
            Some(first) => merge(first, record),
        }
    }
    let mut walk = Vec::new();
    let mut seen = HashSet::new();
    let mut at = leaf;
    while let Some(uuid) = at {
        let Some(record) = merged.get(uuid) else {
            break;
        };
        if !seen.insert(uuid) {
            break;
        }
        walk.push(record);
        at = record["parentUuid"].as_str();
    }
    walk.reverse();
    let entries = walk
        .into_iter()
        .filter_map(project)
        .enumerate()
        .map(|(index, mut entry)| {
            entry.offset = index as u64;
            entry.end = index as u64 + 1;
            entry
        })
        .collect();
    Ok(entries)
}

/// One more fragment of a uuid already seen: parent, type, subtype, tool
/// result and model stay the first's; parts are appended; usage is the
/// last one written; the timestamp is the latest.
fn merge(first: &mut Value, next: &Value) {
    if let Some(parts) = next["message"]["parts"].as_array() {
        match first["message"]["parts"].as_array_mut() {
            Some(have) => have.extend(parts.iter().cloned()),
            None => {
                if !first["message"].is_object() {
                    first["message"] = json!({});
                }
                first["message"]["parts"] = Value::Array(parts.clone());
            }
        }
    }
    if !next["usageMetadata"].is_null() {
        first["usageMetadata"] = next["usageMetadata"].clone();
    }
    let time = |value: &Value| value["timestamp"].as_str().and_then(iso_timestamp);
    if time(next) > time(first) {
        first["timestamp"] = next["timestamp"].clone();
    }
}

/// A shown record as an [`Entry`]; system records and Qwen's internal
/// code-mode results are not shown. `parent_id` stays empty: the chain is
/// already the branch, and the app's own branch walk must not stop at a
/// record left out here.
fn project(record: &Value) -> Option<Entry> {
    let parts = record["message"]["parts"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let (role, blocks, usage) = match record["type"].as_str()? {
        "user" => ("user", user(record, parts), None),
        "assistant" => (
            "assistant",
            assistant(parts),
            usage(&record["usageMetadata"]),
        ),
        "tool_result" if record["subtype"] != "code_mode_tool_result" => {
            ("user", tool_results(record, parts), None)
        }
        _ => return None,
    };
    if blocks.is_empty() {
        return None;
    }
    Some(Entry {
        id: record["uuid"].as_str().map(str::to_owned),
        parent_id: None,
        offset: 0,
        end: 0,
        kind: Kind::Message,
        role: Some(role.to_owned()),
        blocks,
        timestamp: record["timestamp"].as_str().and_then(iso_timestamp),
        usage,
        stop_reason: None,
        steer: None,
        subagent_ends: Vec::new(),
    })
}

/// The text the user typed (`systemPayload.displayText`, without the
/// `@attachment:///` tokens Qwen adds; the parts also carry hook context),
/// then images in array order: inline parts, then attachment references.
/// Neither is ever fetched: a reference has no bytes here.
fn user(record: &Value, parts: &[Value]) -> Vec<Block> {
    let payload = &record["systemPayload"];
    let text = match payload["displayText"].as_str() {
        Some(text) => strip_attachments(text),
        None => parts
            .iter()
            .filter(|part| part["thought"] != true)
            .filter_map(|part| part["text"].as_str())
            .map(strip_hook_context)
            .collect::<Vec<_>>()
            .join("\n"),
    };
    let mut blocks = Vec::new();
    let text = text.trim();
    if !text.is_empty() {
        blocks.push(text_block(text, false));
    }
    for (part, value) in parts.iter().enumerate() {
        let inline = &value["inlineData"];
        if inline["mimeType"]
            .as_str()
            .is_some_and(|mime| mime.starts_with("image/"))
        {
            let source = match inline["data"].as_str() {
                Some(data) if !data.is_empty() => Source::Inline {
                    bytes: inline_bytes(data),
                },
                _ => Source::Unreadable,
            };
            blocks.push(Block::Image(Image { part, source }));
        }
    }
    let references = payload["attachmentReferences"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    for (index, reference) in references.iter().enumerate() {
        if reference["type"] == "image" {
            blocks.push(Block::Image(Image {
                part: parts.len() + index,
                source: Source::Unreadable,
            }));
        }
    }
    blocks
}

fn strip_attachments(text: &str) -> String {
    text.split_inclusive(char::is_whitespace)
        .filter(|word| !word.trim_start().starts_with("@attachment:///"))
        .collect()
}

/// Without the `<qwen:user-prompt-submit-context>` blocks hooks inject, and
/// without attachment tokens.
fn strip_hook_context(text: &str) -> String {
    const OPEN: &str = "<qwen:user-prompt-submit-context>";
    const CLOSE: &str = "</qwen:user-prompt-submit-context>";
    let mut out = String::new();
    let mut rest = text;
    while let Some(start) = rest.find(OPEN) {
        out.push_str(&rest[..start]);
        rest = match rest[start..].find(CLOSE) {
            Some(end) => &rest[start + end + CLOSE.len()..],
            None => "",
        };
    }
    out.push_str(rest);
    strip_attachments(&out)
}

fn assistant(parts: &[Value]) -> Vec<Block> {
    parts
        .iter()
        .filter_map(|part| {
            if let Some(call) = part.get("functionCall").filter(|call| call.is_object()) {
                let args = match &call["args"] {
                    Value::Null => String::new(),
                    args => serde_json::to_string_pretty(args).unwrap_or_default(),
                };
                let (args, truncated) = cap(&args, ARGS_LIMIT);
                return Some(Block::ToolCall {
                    call_id: super::string(&call["id"]),
                    name: cap(&super::string(&call["name"]), 128).0,
                    args,
                    intent: None,
                    truncated,
                });
            }
            let text = part["text"].as_str().filter(|text| !text.is_empty())?;
            Some(text_block(text, part["thought"] == true))
        })
        .collect()
}

/// Each `functionResponse`, keyed by `toolCallResult.callId`, else the
/// response's own id, to the call that asked for it.
fn tool_results(record: &Value, parts: &[Value]) -> Vec<Block> {
    let result = &record["toolCallResult"];
    let is_error = matches!(result["status"].as_str(), Some("error" | "cancelled"));
    parts
        .iter()
        .filter_map(|part| {
            part.get("functionResponse")
                .filter(|response| response.is_object())
        })
        .map(|response| {
            let call_id = result["callId"]
                .as_str()
                .or_else(|| response["id"].as_str())
                .unwrap_or_default();
            let body = &response["response"];
            let text = match body["output"].as_str().or_else(|| body["error"].as_str()) {
                Some(text) => text.to_owned(),
                None if body.is_null() => String::new(),
                None => serde_json::to_string_pretty(body).unwrap_or_default(),
            };
            let (text, truncated) = cap(&text, RESULT_LIMIT);
            Block::ToolResult {
                call_id: call_id.to_owned(),
                name: cap(&super::string(&response["name"]), 128).0,
                text,
                is_error,
                images: Vec::new(),
                truncated,
                exit_code: None,
                answers: Vec::new(),
                timed_out: false,
                subagents: Vec::new(),
            }
        })
        .collect()
}

/// Qwen's `usageMetadata`: prompt tokens are the input, and cached tokens
/// are a part of them, never added on top.
fn usage(usage: &Value) -> Option<Usage> {
    let input = usage["promptTokenCount"].as_u64()?;
    let output = usage["candidatesTokenCount"].as_u64()?;
    Some(Usage {
        input,
        output,
        total_tokens: usage["totalTokenCount"].as_u64().unwrap_or(input + output),
        cost: None,
    })
}

/// A Qwen session id (`SESSION_FILE_PATTERN`): one path segment of hex and
/// dashes, checked before anything touches the host's files.
pub(super) fn session_id(value: &str) -> bool {
    (32..=36).contains(&value.len())
        && value
            .bytes()
            .all(|byte| byte.is_ascii_hexdigit() || byte == b'-')
}

/// Runs on the host. Reads from this shell's own environment only
/// `QWEN_RUNTIME_DIR` and `QWEN_HOME` (no other process's environment; one
/// set only for the agent is not known here), and never prints either. A relative
/// base is refused as unconfined; one that resolves to `$HOME` itself or
/// outside it is refused as outside home, and never followed. The
/// stamp hashes the open file's device, inode, size and mtime: equal, the
/// reply says so and carries no records.
const PYTHON: &str = r#"import hashlib, json, os, re, stat, subprocess, sys
expect, opts, report = json.loads(sys.argv[1]), json.loads(sys.argv[2]), sys.argv[3]
MAX_FILE=33554432
SESSION_ID=re.compile(r'[0-9a-fA-F-]{32,36}')

def reply(head, data=b''):
    sys.stdout.buffer.write(json.dumps(head).encode()+b'\n'+data)

def identity():
    result=subprocess.run(['/bin/sh','-c',report],stdin=subprocess.DEVNULL,stdout=subprocess.PIPE,stderr=subprocess.DEVNULL,timeout=20)
    raw=result.stdout
    if result.returncode or len(raw)>65536 or not raw.startswith(b'@@pane\n'): raise ValueError('report')
    first,proc=raw[len(b'@@pane\n'):].split(b'\n@@proc\n',1)
    pane=json.loads(first)['result']['pane']
    process=json.loads(proc).get('result',{}).get('process_info',{})
    ref=pane['agent_session']
    pid=next((p.get('pid') for p in process.get('foreground_processes',[]) if p.get('name')=='qwen'),None)
    got={'pane_id':pane['pane_id'],'terminal_id':pane['terminal_id'],'pid':pid,'path':ref['value'],'agent':ref['agent'],'source':ref.get('source','herdr:qwen'),'kind':ref['kind']}
    return got,pane.get('agent_status','unknown'),pane.get('foreground_cwd') or pane.get('cwd')

class OutsideHome(Exception): pass

def base_dir(home):
    value=os.environ.get('QWEN_RUNTIME_DIR') or os.environ.get('QWEN_HOME')
    if not value: return os.path.join(home,'.qwen')
    if value=='~': value=home
    elif value.startswith('~/'): value=os.path.join(home,value[2:])
    if not value.startswith('/') or any(ord(c)<32 for c in value): return None
    base=os.path.realpath(value)
    if base==home or os.path.commonpath([home,base])!=home: raise OutsideHome()
    return base

def main():
    got,status,cwd=identity()
    if got!=expect or got['agent']!='qwen' or got['kind']!='id' or not got['pid']: return reply({'changed':True})
    sid=got['path']
    if not SESSION_ID.fullmatch(sid): return reply({'error':'bad_id'})
    if not isinstance(cwd,str) or not cwd.startswith('/'): return reply({'error':'no_cwd'})
    home=os.path.realpath(os.environ['HOME'])
    try: base=base_dir(home)
    except OutsideHome: return reply({'error':'outside_home'})
    except (OSError,UnicodeDecodeError): base=None
    if base is None: return reply({'error':'unconfined'})
    chats=os.path.join(base,'projects',re.sub(r'[^a-zA-Z0-9]','-',cwd),'chats')
    for path in (os.path.join(chats,sid+'.jsonl'),os.path.join(chats,'archive',sid+'.jsonl')):
        try: fd=os.open(path,os.O_RDONLY|os.O_NOFOLLOW|os.O_NONBLOCK)
        except FileNotFoundError: continue
        except OSError: return reply({'error':'unconfined'})
        break
    else: return reply({'error':'missing'})
    with os.fdopen(fd,'rb') as handle:
        st=os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_ino==0: return reply({'error':'unconfined'})
        # No link anywhere below the base: the open file is that very path.
        if os.path.realpath('/proc/self/fd/%d'%fd)!=path: return reply({'error':'unconfined'})
        if st.st_size>MAX_FILE: return reply({'error':'too_large'})
        stamp=int(hashlib.sha256(('%d:%d:%d:%d:%s'%(st.st_dev,st.st_ino,st.st_size,st.st_mtime_ns,path)).encode()).hexdigest()[:13],16) or 1
        head={'stamp':stamp,'size':st.st_size,'status':status}
        if opts.get('stamp')==stamp: return reply(dict(head,not_modified=True))
        data=handle.read(st.st_size)
    if not data: return reply(head)
    try: first=json.JSONDecoder().raw_decode(data.split(b'\n',1)[0].decode('utf-8'))[0]
    except ValueError: return reply({'error':'foreign'})
    if not isinstance(first,dict): return reply({'error':'foreign'})
    subtype=str(first.get('subtype') or '')
    if subtype.startswith('managed_session_') or (subtype=='session_execution_engine' and (first.get('systemPayload') or {}).get('engine')=='managed'): return reply({'managed':True})
    # getProjectHash: sha256 of the record's cwd against the pane's.
    owner=hashlib.sha256(str(first.get('cwd')).encode()).digest()==hashlib.sha256(cwd.encode()).digest()
    if str(first.get('sessionId','')).lower()!=sid.lower() or not owner: return reply({'error':'foreign'})
    reply(head,data)

try: main()
except Exception:
    sys.exit(1)
"#;

/// FIXTURE-ONLY: hand-written records after the facts sheet (§8 A–C), not
/// files a real Qwen Code wrote.
#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::path::Path;
    use std::process::{Command, Stdio};

    const SID: &str = "550e8400-e29b-41d4-a716-446655440000";

    fn identity(pid: u32) -> Identity {
        Identity {
            pane_id: "p1".into(),
            terminal_id: "t1".into(),
            pid: Some(pid),
            path: SID.into(),
            agent: "qwen".into(),
            source: "herdr:qwen".into(),
            kind: "id".into(),
        }
    }

    fn line(value: Value) -> String {
        value.to_string() + "\n"
    }

    /// Fixture A, every record stamped with the session and cwd as Qwen does.
    fn fixture_a(cwd: &str) -> String {
        let record = |mut value: Value| {
            value["sessionId"] = json!(SID);
            value["cwd"] = json!(cwd);
            value
        };
        let r2a = record(json!({"uuid":"r2","parentUuid":"r1","type":"assistant",
            "timestamp":"2026-11-12T00:00:02Z",
            "message":{"role":"model","parts":[{"thought":true,"text":"plan"}]}}));
        let r2b = record(json!({"uuid":"r2","parentUuid":"other","type":"assistant",
            "timestamp":"2026-11-12T00:00:03Z",
            "message":{"role":"model","parts":[{"functionCall":{"id":"call_1","name":"read_file","args":{"file_path":"a.ts"}}}]},
            "usageMetadata":{"promptTokenCount":100,"candidatesTokenCount":25,"cachedContentTokenCount":40,"totalTokenCount":125}}));
        [
            line(record(json!({"uuid":"r0","parentUuid":null,"type":"system","subtype":"session_source","systemPayload":{"sourceType":"cli"}}))),
            line(record(json!({"uuid":"r1","parentUuid":"r0","type":"user",
                "message":{"role":"user","parts":[
                    {"text":"check image\n\n@attachment:///img.png\n<qwen:user-prompt-submit-context>\ninjected\n</qwen:user-prompt-submit-context>"},
                    {"inlineData":{"data":"iVBORw0KGgo=","mimeType":"image/png"}}]},
                "systemPayload":{"displayText":"check image\n\n@attachment:///img.png","hookContext":"injected",
                    "attachmentReferences":[{"type":"image","attachmentId":"img.png","mimeType":"image/png","size":123}]}}))),
            // Two fragments of r2 glued on one physical line.
            format!("{r2a}{r2b}\n"),
            line(record(json!({"uuid":"r3","parentUuid":"r2","type":"tool_result","subtype":"code_mode_tool_result","toolCallResult":{"callId":"internal_1","status":"success"}}))),
            line(record(json!({"uuid":"r4","parentUuid":"r2","type":"tool_result",
                "message":{"role":"user","parts":[{"functionResponse":{"id":"call_1","name":"read_file","response":{"output":"ok"}}}]},
                "toolCallResult":{"callId":"call_1","status":"success"}}))),
            line(record(json!({"uuid":"r5_dead","parentUuid":"r4","type":"user","message":{"role":"user","parts":[{"text":"abandoned"}]}}))),
            line(record(json!({"uuid":"r6","parentUuid":"r4","type":"system","subtype":"rewind","systemPayload":{"truncatedCount":1}}))),
            line(record(json!({"uuid":"r7","parentUuid":"r6","type":"system","subtype":"custom_title","systemPayload":{"customTitle":"My Title","titleSource":"manual"}}))),
        ]
        .concat()
    }

    /// FIXTURE-ONLY. The active chain of fixture A: glued fragments merged,
    /// the rewound branch and the code-mode result left out.
    #[test]
    fn fixture_a_active_chain() {
        let entries = chain(&records(fixture_a("/tmp/proj-a").as_bytes())).unwrap();
        let ids: Vec<_> = entries.iter().map(|e| e.id.as_deref().unwrap()).collect();
        assert_eq!(ids, ["r1", "r2", "r4"]);

        let [
            Block::Text { text, .. },
            Block::Image(inline),
            Block::Image(reference),
        ] = entries[0].blocks.as_slice()
        else {
            panic!("user text and two images expected: {:?}", entries[0].blocks);
        };
        assert_eq!(text, "check image");
        assert_eq!(inline.part, 1);
        assert!(matches!(inline.source, Source::Inline { .. }));
        assert_eq!(reference.part, 2);
        assert_eq!(reference.source, Source::Unreadable);

        let [
            Block::Thinking { text, .. },
            Block::ToolCall { call_id, name, .. },
        ] = entries[1].blocks.as_slice()
        else {
            panic!("thought then call expected: {:?}", entries[1].blocks);
        };
        assert_eq!(
            (text.as_str(), call_id.as_str(), name.as_str()),
            ("plan", "call_1", "read_file")
        );
        // Cached tokens are part of the input, not added to it.
        let usage = entries[1].usage.unwrap();
        assert_eq!(
            (usage.input, usage.output, usage.total_tokens),
            (100, 25, 125)
        );
        // The later fragment's time wins.
        assert_eq!(entries[1].timestamp, iso_timestamp("2026-11-12T00:00:03Z"));

        let [
            Block::ToolResult {
                call_id,
                text,
                is_error,
                ..
            },
        ] = entries[2].blocks.as_slice()
        else {
            panic!("tool result expected: {:?}", entries[2].blocks);
        };
        assert_eq!(
            (call_id.as_str(), text.as_str(), *is_error),
            ("call_1", "ok", false)
        );
        // The chain is already the branch: no parent for the app to walk.
        assert!(entries.iter().all(|e| e.parent_id.is_none()));
        assert_eq!(
            entries.iter().map(|e| e.offset).collect::<Vec<_>>(),
            [0, 1, 2]
        );
    }

    /// FIXTURE-ONLY. A managed record anywhere is a typed refusal, never an
    /// empty chat; a parent cycle ends the walk instead of looping.
    #[test]
    fn managed_refused_and_cycle_ends() {
        let managed = [
            json!({"uuid":"h","type":"system","subtype":"managed_session_header_v1"}),
            json!({"uuid":"e","type":"system","subtype":"session_execution_engine","systemPayload":{"engine":"managed"}}),
        ];
        for record in managed {
            let user = json!({"uuid":"u","parentUuid":null,"type":"user","message":{"parts":[{"text":"hi"}]}});
            assert!(matches!(
                chain(&[user, record]),
                Err(Error::UnsupportedFormat(MANAGED))
            ));
        }
        let legacy = json!({"uuid":"e","type":"system","subtype":"session_execution_engine","systemPayload":{"engine":"legacy"}});
        assert!(chain(&[legacy]).unwrap().is_empty());

        let a =
            json!({"uuid":"a","parentUuid":"b","type":"user","message":{"parts":[{"text":"a"}]}});
        let b =
            json!({"uuid":"b","parentUuid":"a","type":"user","message":{"parts":[{"text":"b"}]}});
        let ids: Vec<_> = chain(&[a, b])
            .unwrap()
            .into_iter()
            .map(|e| e.id.unwrap())
            .collect();
        assert_eq!(ids, ["a", "b"]);
    }

    #[test]
    fn session_id_gate() {
        assert!(session_id(SID));
        assert!(session_id("550e8400e29b41d4a716446655440000"));
        for bad in [
            "bad",
            "../etc/passwd",
            "550e8400-e29b-41d4-a716-44665544000/",
            "550e8400-e29b-41d4-a716-4466554400zz",
        ] {
            assert!(!session_id(bad), "{bad}");
        }
    }

    /// The real host script against a pane report printed by `printf`, in a
    /// shell whose environment holds HOME and exactly `env`.
    fn host(
        home: &Path,
        env: &[(&str, &Path)],
        cwd: &str,
        sid: &str,
        stamp: u64,
    ) -> Result<Response> {
        let pid = 42;
        let pane = json!({"result":{"pane":{"pane_id":"p1","terminal_id":"t1","agent_status":"idle",
            "foreground_cwd":cwd,"agent_session":{"agent":"qwen","kind":"id","value":sid,"source":"herdr:qwen"}}}});
        let process =
            json!({"result":{"process_info":{"foreground_processes":[{"name":"qwen","pid":pid}]}}});
        let report = format!(
            "printf '@@pane\\n%s\\n@@proc\\n%s' {} {}",
            shell_quote(&pane.to_string()),
            shell_quote(&process.to_string())
        );
        let mut expect = identity(pid);
        expect.path = sid.to_owned();
        let mut child = Command::new("python3")
            .arg("-")
            .arg(serde_json::to_string(&expect).unwrap())
            .arg(json!({ "stamp": stamp }).to_string())
            .arg(report)
            .env_remove("QWEN_RUNTIME_DIR")
            .env_remove("QWEN_HOME")
            .envs(env.iter().map(|(key, value)| (*key, *value)))
            .env("HOME", home)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        std::io::Write::write_all(child.stdin.as_mut().unwrap(), PYTHON.as_bytes()).unwrap();
        let output = child.wait_with_output().unwrap();
        assert!(output.status.success(), "host script failed");
        answer(&output.stdout, &expect, false)
    }

    fn chats(base: &Path, cwd: &str) -> std::path::PathBuf {
        let dir = base
            .join("projects")
            .join(cwd.replace(|c: char| !c.is_ascii_alphanumeric(), "-"))
            .join("chats");
        std::fs::create_dir_all(dir.join("archive")).unwrap();
        dir
    }

    /// FIXTURE-ONLY. The host script: runtime dir from the shell's own
    /// environment, the archive path, not-modified on an unchanged file and
    /// a full reload once it changes.
    #[test]
    fn host_reads_archive_and_reports_not_modified() {
        let home = tempfile::tempdir().unwrap();
        let home = std::fs::canonicalize(home.path()).unwrap();
        let base = home.join("runtime");
        let cwd = "/tmp/proj-a";
        let file = chats(&base, cwd)
            .join("archive")
            .join(format!("{SID}.jsonl"));
        std::fs::write(&file, fixture_a(cwd)).unwrap();
        let env = [
            ("QWEN_RUNTIME_DIR", base.as_path()),
            ("QWEN_HOME", &home.join("unused")),
        ];

        let first = host(&home, &env, cwd, SID, 0).unwrap();
        assert!(first.reset);
        assert_eq!(first.entries.len(), 3);
        assert_ne!(first.end, 0);

        let again = host(&home, &env, cwd, SID, first.end).unwrap();
        assert!(!again.reset && again.entries.is_empty());
        assert_eq!(again.end, first.end);

        std::fs::write(&file, fixture_a(cwd) + &line(json!({"uuid":"r8","parentUuid":"r7","type":"user","sessionId":SID,"cwd":cwd,"message":{"parts":[{"text":"next"}]}}))).unwrap();
        let changed = host(&home, &env, cwd, SID, first.end).unwrap();
        assert!(changed.reset);
        assert_ne!(changed.end, first.end);
        assert_eq!(changed.entries.len(), 4);
        // Set only for the agent, the runtime dir is not known here: the
        // default ~/.qwen has no such session, and nothing else is searched.
        assert!(matches!(
            host(&home, &[], cwd, SID, 0),
            Err(Error::NoTranscript)
        ));
    }

    /// FIXTURE-ONLY. Fixture B and C on the host: a link out, a runtime
    /// dir outside HOME, a foreign project behind a colliding name, a bad
    /// id and a managed header each refuse by type.
    #[test]
    fn host_refusals() {
        let home = tempfile::tempdir().unwrap();
        let home = std::fs::canonicalize(home.path()).unwrap();
        let outside = tempfile::tempdir().unwrap();
        let base = home.join(".qwen");
        let none: &[(&str, &Path)] = &[];

        // A link to a file outside, even one with the right content.
        let cwd = "/tmp/proj-a";
        let target = outside.path().join("outside.jsonl");
        std::fs::write(&target, fixture_a(cwd)).unwrap();
        std::os::unix::fs::symlink(&target, chats(&base, cwd).join(format!("{SID}.jsonl")))
            .unwrap();
        assert!(matches!(
            host(&home, none, cwd, SID, 0),
            Err(Error::TranscriptUnconfined)
        ));

        // `/tmp/a/b` and `/tmp/a-b` share a folder; the first record's cwd tells them apart.
        std::fs::write(
            chats(&base, "/tmp/a/b").join(format!("{SID}.jsonl")),
            fixture_a("/tmp/a-b"),
        )
        .unwrap();
        assert!(matches!(
            host(&home, none, "/tmp/a/b", SID, 0),
            Err(Error::TranscriptUnconfined)
        ));
        assert_eq!(
            host(&home, none, "/tmp/a-b", SID, 0).unwrap().entries.len(),
            3
        );

        // Refused before any file is looked at.
        assert!(matches!(
            host(&home, none, cwd, "bad", 0),
            Err(Error::NoTranscript)
        ));

        let managed = "/tmp/managed";
        std::fs::write(chats(&base, managed).join(format!("{SID}.jsonl")),
            line(json!({"uuid":"h","type":"system","subtype":"managed_session_header_v1","sessionId":SID}))).unwrap();
        assert!(matches!(
            host(&home, none, managed, SID, 0),
            Err(Error::UnsupportedFormat(MANAGED))
        ));

        // A runtime dir outside HOME is its own refusal, so Chat can say where the session is.
        assert!(matches!(
            host(&home, &[("QWEN_RUNTIME_DIR", outside.path())], cwd, SID, 0),
            Err(Error::SessionRootOutsideHome)
        ));

        // A relative one names no place at all: unconfined, not "outside".
        assert!(matches!(
            host(
                &home,
                &[("QWEN_RUNTIME_DIR", Path::new("rel/dir"))],
                cwd,
                SID,
                0
            ),
            Err(Error::TranscriptUnconfined)
        ));
    }
}
