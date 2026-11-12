//! Gemini CLI (`gemini`) chat recording, read only: the parser. A session is
//! one `session-*.jsonl` that Gemini both appends to and rewrites through
//! records (`$rewindTo`, `$patch`, `$set`), so there is no byte cursor: the
//! whole file is folded into the conversation Gemini itself would load, and
//! any change replaces it.
//!
//! Every rule names the pinned source it mirrors: google-gemini/gemini-cli
//! v0.68.0, commit 5a5c7180. The fold is `createJsonlRecordAccumulator` and
//! its legacy fallback (`packages/core/src/services/chatRecordingService.ts:
//! 256-602, 1647-1699`); what is shown is `convertSessionToHistoryFormats`
//! and `partListUnionToString` from the same release's bundle.
//!
//! Which file belongs to a pane (the owned launcher, its sidecar and the
//! `hl_gemini_*` pane tokens) is [`binding`]; transcript.rs asks it first
//! for every pane Herdr names `gemini`.

use super::{ARGS_LIMIT, Block, Entry, Kind, RESULT_LIMIT, Usage, cap, iso_timestamp, text_block};
use serde_json::{Map, Value};
use std::collections::{HashMap, HashSet};

pub(super) mod binding;

/// A loaded session: what `finalize` returns, the messages in Gemini's order.
#[derive(Debug)]
pub(super) struct Conversation {
    pub session_id: String,
    /// Checked against the slug dir on the host (binding.rs); kept for the fold's own tests.
    #[cfg_attr(not(test), allow(dead_code))]
    pub project_hash: String,
    pub messages: Vec<Value>,
}

/// A JavaScript TypeError in Gemini's loader: the rest of that one record
/// is not applied, what it already changed stays (`processLine` catches per
/// line, :529-531).
struct Throw;

/// The loader's `messagesMap`: a JS `Map`, so a known id keeps its place
/// when it is set again, and iteration is insertion order.
#[derive(Default)]
struct Messages {
    order: Vec<String>,
    by_id: HashMap<String, Value>,
}

impl Messages {
    fn set(&mut self, id: &str, message: Value) {
        if self.by_id.insert(id.to_owned(), message).is_none() {
            self.order.push(id.to_owned());
        }
    }

    fn clear(&mut self) {
        self.order.clear();
        self.by_id.clear();
    }

    /// `$rewindTo`: the target and everything after it; an id never seen
    /// clears all (:313-328).
    fn rewind(&mut self, target: &str) {
        match self.order.iter().position(|id| id == target) {
            Some(at) => {
                for id in self.order.drain(at..) {
                    self.by_id.remove(&id);
                }
            }
            None => self.clear(),
        }
    }

    fn remove(&mut self, ids: &HashSet<&str>) {
        self.order.retain(|id| !ids.contains(id.as_str()));
        self.by_id.retain(|id, _| !ids.contains(id.as_str()));
    }

    /// `orderIds`: the listed ids Gemini still has move to the end in the
    /// listed order (first mention wins); the rest keep theirs (:388-409).
    fn reorder(&mut self, listed: &[Value]) {
        let mut tail = Vec::new();
        let mut moved = HashSet::new();
        for id in listed.iter().filter_map(Value::as_str) {
            if self.by_id.contains_key(id) && moved.insert(id) {
                tail.push(id.to_owned());
            }
        }
        self.order.retain(|id| !moved.contains(id.as_str()));
        self.order.extend(tail);
    }
}

/// `in`/property read on a parsed JSON value: on `null` it throws; on any
/// other primitive the key is never there.
fn get<'a>(value: &'a Value, key: &str) -> Result<Option<&'a Value>, Throw> {
    match value {
        Value::Null => Err(Throw),
        Value::Object(map) => Ok(map.get(key)),
        _ => Ok(None),
    }
}

/// `===` between two property reads: parsed objects and arrays are never
/// the same reference.
fn same(a: Option<&Value>, b: Option<&Value>) -> bool {
    match (a, b) {
        (None, None) => true,
        (Some(Value::Null), Some(Value::Null)) => true,
        (Some(Value::Bool(a)), Some(Value::Bool(b))) => a == b,
        (Some(Value::Number(a)), Some(Value::Number(b))) => a.as_f64() == b.as_f64(),
        (Some(Value::String(a)), Some(Value::String(b))) => a == b,
        _ => false,
    }
}

fn truthy(value: Option<&Value>) -> bool {
    match value {
        None | Some(Value::Null) => false,
        Some(Value::Bool(b)) => *b,
        Some(Value::Number(n)) => n.as_f64().is_some_and(|n| n != 0.0),
        Some(Value::String(s)) => !s.is_empty(),
        Some(Value::Array(_) | Value::Object(_)) => true,
    }
}

fn string<'a>(value: &'a Value, key: &str) -> Option<&'a str> {
    value.as_object()?.get(key)?.as_str()
}

/// `isObjectProperty`: present and a non-null object, arrays included.
fn object<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    value.as_object()?.get(key).filter(|v| v.is_object() || v.is_array())
}

/// `isMessageRecord`: a string id and no `$patch`.
fn message_id(value: &Value) -> Option<&str> {
    let map = value.as_object()?;
    if map.contains_key("$patch") {
        return None;
    }
    map.get("id")?.as_str()
}

/// `applySinglePatch` outside `metadataOnly` (:268-299).
fn patch_one(messages: &mut Messages, patch: &Value) -> Result<(), Throw> {
    let Some(id) = string(patch, "id") else { return Ok(()) };
    let Some(existing) = messages.by_id.get_mut(id) else { return Ok(()) };
    if let Some(content) = patch.get("content") {
        existing["content"] = content.clone();
    }
    let Some(patches) = patch.get("toolCalls").and_then(Value::as_array) else { return Ok(()) };
    if existing.get("type").and_then(Value::as_str) != Some("gemini") || !truthy(existing.get("toolCalls")) {
        return Ok(());
    }
    // `existing.toolCalls.find`: only an array has it.
    if !existing["toolCalls"].is_array() {
        return Err(Throw);
    }
    for tc_patch in patches {
        let calls = existing["toolCalls"].as_array_mut().ok_or(Throw)?;
        let mut found = None;
        for (at, call) in calls.iter().enumerate() {
            if same(get(call, "id")?, get(tc_patch, "id")?) {
                found = Some(at);
                break;
            }
        }
        let Some(at) = found else { continue };
        // `'result' in tcPatch` on a primitive throws.
        let result = match tc_patch {
            Value::Object(map) => map.get("result"),
            Value::Array(_) => None,
            _ => return Err(Throw),
        };
        if let Some(result) = result {
            match &mut calls[at] {
                Value::Object(call) => {
                    call.insert("result".to_owned(), result.clone());
                }
                Value::Array(_) => {}
                // Strict mode: a property set on a primitive throws.
                _ => return Err(Throw),
            }
        }
    }
    Ok(())
}

fn patch(messages: &mut Messages, patch: &Value) -> Result<(), Throw> {
    if string(patch, "id").is_some() {
        patch_one(messages, patch)?;
    }
    if let Some(updates) = patch.get("updates").and_then(Value::as_array) {
        for update in updates.iter().filter(|update| string(update, "id").is_some()) {
            patch_one(messages, update)?;
        }
    }
    if let Some(ids) = patch.get("removeIds").and_then(Value::as_array) {
        messages.remove(&ids.iter().filter_map(Value::as_str).collect());
    }
    if let Some(ids) = patch.get("orderIds").and_then(Value::as_array) {
        messages.reorder(ids);
    }
    Ok(())
}

/// `{ ...metadata, ...record }` for the keys `finalize` reads.
fn merge(metadata: &mut Map<String, Value>, record: &Value) {
    if let Some(map) = record.as_object() {
        metadata.extend(map.iter().map(|(key, value)| (key.clone(), value.clone())));
    }
}

fn load(messages: &mut Messages, list: &Value) {
    for message in list.as_array().into_iter().flatten() {
        if let Some(id) = message_id(message) {
            messages.set(id, message.clone());
        }
    }
}

fn line(messages: &mut Messages, metadata: &mut Map<String, Value>, record: &Value) -> Result<(), Throw> {
    if let Some(target) = string(record, "$rewindTo") {
        messages.rewind(target);
    } else if let Some(body) = object(record, "$patch") {
        patch(messages, body)?;
    } else if let Some(id) = message_id(record) {
        messages.set(id, record.clone());
    } else if let Some(set) = object(record, "$set") {
        // The legacy full-history checkpoint (:461-501).
        if set.get("messages").is_some_and(Value::is_array) {
            messages.clear();
            load(messages, &set["messages"]);
        }
        merge(metadata, set);
    } else if string(record, "sessionId").is_some() && string(record, "projectHash").is_some() {
        merge(metadata, record);
        load(messages, &record["messages"]);
    }
    Ok(())
}

/// The session in `data`, or None where Gemini loads none: no truthy
/// `sessionId` and `projectHash` and no legacy whole-file record. A session
/// id or project hash that is not a string is refused here, not shown.
pub(super) fn fold(data: &[u8]) -> Option<Conversation> {
    let text = String::from_utf8_lossy(data);
    let mut messages = Messages::default();
    let mut metadata = Map::new();
    for raw in text.split('\n') {
        if raw.trim().is_empty() {
            continue;
        }
        let Ok(record) = serde_json::from_str::<Value>(raw) else { continue };
        let _ = line(&mut messages, &mut metadata, &record);
    }
    if truthy(metadata.get("sessionId")) && truthy(metadata.get("projectHash")) {
        let Messages { order, mut by_id } = messages;
        return Some(Conversation {
            session_id: metadata["sessionId"].as_str()?.to_owned(),
            project_hash: metadata["projectHash"].as_str()?.to_owned(),
            messages: order.iter().filter_map(|id| by_id.remove(id)).collect(),
        });
    }
    legacy(&text)
}

/// `parseLegacyContentFallback`: one JSON object with a `sessionId`; its
/// messages exactly as listed.
fn legacy(text: &str) -> Option<Conversation> {
    let record: Value = serde_json::from_str(text).ok()?;
    let map = record.as_object()?;
    Some(Conversation {
        session_id: map.get("sessionId")?.as_str()?.to_owned(),
        project_hash: map.get("projectHash").and_then(Value::as_str).unwrap_or_default().to_owned(),
        messages: map.get("messages").and_then(Value::as_array).cloned().unwrap_or_default(),
    })
}

impl Conversation {
    /// What Gemini's resumed history shows, one entry per message, keyed by
    /// its place in the conversation.
    pub(super) fn entries(&self) -> Vec<Entry> {
        self.messages
            .iter()
            .filter_map(entry)
            .enumerate()
            .map(|(index, mut entry)| {
                entry.offset = index as u64;
                entry.end = index as u64 + 1;
                entry
            })
            .collect()
    }
}

/// One message as `convertSessionToHistoryFormats` shows it: a Gemini
/// message's thoughts, then its text (`displayContent` when it has any),
/// unless that is injected `<session_context>`/`<hook_context>`, then the
/// tool calls of any message but the user's.
fn entry(message: &Value) -> Option<Entry> {
    let kind = message.get("type").and_then(Value::as_str);
    let mut blocks = Vec::new();
    if kind == Some("gemini") {
        for thought in message.get("thoughts").and_then(Value::as_array).into_iter().flatten() {
            let text = [string(thought, "subject"), string(thought, "description")]
                .into_iter()
                .flatten()
                .filter(|part| !part.trim().is_empty())
                .collect::<Vec<_>>()
                .join("\n");
            if !text.is_empty() {
                blocks.push(text_block(&text, true));
            }
        }
    }
    let display = message.get("displayContent").filter(|value| truthy(Some(value))).map(part_text);
    let text = match display.filter(|text| !text.is_empty()) {
        Some(text) => text,
        None => part_text(message.get("content").unwrap_or(&Value::Null)),
    };
    let text = text.trim();
    if !text.is_empty() && !text.starts_with("<session_context>") && !text.starts_with("<hook_context>") {
        blocks.push(text_block(text, false));
    }
    if kind != Some("user") {
        for call in message.get("toolCalls").and_then(Value::as_array).into_iter().flatten() {
            tool(call, &mut blocks);
        }
    }
    if blocks.is_empty() {
        return None;
    }
    let role = match kind {
        Some("user") => "user",
        Some("info" | "error" | "warning") => "system",
        // `checkExhaustive` falls back to a Gemini message.
        _ => "assistant",
    };
    Some(Entry {
        id: message_id(message).map(str::to_owned),
        parent_id: None,
        offset: 0,
        end: 0,
        kind: Kind::Message,
        role: Some(role.to_owned()),
        blocks,
        timestamp: string(message, "timestamp").and_then(iso_timestamp),
        usage: usage(message.get("tokens").unwrap_or(&Value::Null)),
        stop_reason: None,
        steer: None,
        subagent_ends: Vec::new(),
    })
}

/// A recorded tool call, and its result once its status is final. Gemini's
/// own resumed view words every status but `success` as an error; one
/// still running here has no result yet.
fn tool(call: &Value, blocks: &mut Vec<Block>) {
    let call_id = cap(string(call, "id").unwrap_or_default(), 256).0;
    let name = string(call, "displayName")
        .filter(|name| !name.is_empty())
        .or_else(|| string(call, "name"))
        .unwrap_or_default();
    let name = cap(name, 128).0;
    let args = match call.get("args") {
        None | Some(Value::Null) => String::new(),
        Some(args) => serde_json::to_string_pretty(args).unwrap_or_default(),
    };
    let (args, truncated) = cap(&args, ARGS_LIMIT);
    let intent = string(call, "description").filter(|text| !text.is_empty()).map(|text| cap(text, 512).0);
    blocks.push(Block::ToolCall { call_id: call_id.clone(), name: name.clone(), args, intent, truncated });
    let status = string(call, "status").unwrap_or_default();
    if !matches!(status, "success" | "error" | "cancelled") {
        return;
    }
    let (text, truncated) = cap(&result_text(call), RESULT_LIMIT);
    blocks.push(Block::ToolResult {
        call_id,
        name,
        text,
        is_error: status != "success",
        images: Vec::new(),
        truncated,
        exit_code: None,
        answers: Vec::new(),
        timed_out: false,
        subagents: Vec::new(),
    });
}

/// What the person saw (`resultDisplay`: its text, or a file edit's diff),
/// else what the model was told (`functionResponse.response`).
fn result_text(call: &Value) -> String {
    match call.get("resultDisplay") {
        Some(Value::String(text)) if !text.is_empty() => return text.clone(),
        Some(display) => {
            if let Some(diff) = string(display, "fileDiff").filter(|diff| !diff.is_empty()) {
                return diff.to_owned();
            }
        }
        None => {}
    }
    let parts = match call.get("result") {
        Some(Value::Array(parts)) => parts.iter().collect(),
        Some(part @ Value::Object(_)) => vec![part],
        Some(Value::String(text)) => return text.clone(),
        _ => Vec::new(),
    };
    let mut out = Vec::new();
    for part in parts {
        let body = part.get("functionResponse").map(|response| &response["response"]);
        match body {
            Some(body) => match string(body, "output").or_else(|| string(body, "error")) {
                Some(text) => out.push(text.to_owned()),
                None if body.is_null() => {}
                None => out.push(serde_json::to_string_pretty(body).unwrap_or_default()),
            },
            None => out.push(part_text(part)),
        }
    }
    out.retain(|text| !text.is_empty());
    out.join("\n")
}

/// `partListUnionToString` (`partToString` with `verbose`): text parts as
/// written, every other part a bracketed name; media never decoded.
fn part_text(value: &Value) -> String {
    if !truthy(Some(value)) {
        return String::new();
    }
    match value {
        Value::String(text) => text.clone(),
        Value::Array(parts) => parts.iter().map(part_text).collect(),
        Value::Object(part) => {
            let named = |key: &str| {
                let name = part[key].get("name").and_then(Value::as_str).unwrap_or("undefined");
                format!("{name}]")
            };
            if part.contains_key("videoMetadata") {
                "[Video Metadata]".to_owned()
            } else if let Some(thought) = part.get("thought") {
                // A template literal writes null as `null`.
                format!("[Thought: {}]", if thought.is_null() { "null".to_owned() } else { js_string(thought) })
            } else if part.contains_key("codeExecutionResult") {
                "[Code Execution Result]".to_owned()
            } else if part.contains_key("executableCode") {
                "[Executable Code]".to_owned()
            } else if part.contains_key("fileData") {
                "[File Data]".to_owned()
            } else if part.contains_key("functionCall") {
                format!("[Function Call: {}", named("functionCall"))
            } else if part.contains_key("functionResponse") {
                format!("[Function Response: {}", named("functionResponse"))
            } else if let Some(inline) = part.get("inlineData") {
                let mime = inline.get("mimeType").and_then(Value::as_str).unwrap_or("unknown");
                let data = inline.get("data").and_then(Value::as_str).unwrap_or_default();
                let kb = (data.len() * 3).div_ceil(4) as f64 / 1024.0;
                let category = ["audio/", "video/", "image/"]
                    .into_iter()
                    .zip(["Audio", "Video", "Image"])
                    .find(|(prefix, _)| mime.starts_with(prefix))
                    .map_or("Media", |(_, category)| category);
                format!("[{category}: {mime}, {kb:.1} KB]")
            } else {
                part.get("text").map(js_string).unwrap_or_default()
            }
        }
        _ => String::new(),
    }
}

/// A primitive as a template literal writes it; null as `??` drops it.
fn js_string(value: &Value) -> String {
    match value {
        Value::Null => String::new(),
        Value::String(text) => text.clone(),
        Value::Bool(b) => b.to_string(),
        Value::Number(n) => n.to_string(),
        Value::Array(_) | Value::Object(_) => "[object Object]".to_owned(),
    }
}

/// `TokensSummary`: `input` is the prompt count, cached tokens a part of it.
fn usage(tokens: &Value) -> Option<Usage> {
    let input = tokens.get("input")?.as_u64()?;
    let output = tokens.get("output")?.as_u64()?;
    Some(Usage {
        input,
        output,
        total_tokens: tokens.get("total").and_then(Value::as_u64).unwrap_or(input + output),
        cost: None,
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used)]
mod tests;
