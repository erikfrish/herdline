//! Codex (`openai/codex`) rollout JSONL: `rollout-<time>-<id>.jsonl` under
//! `~/.codex/sessions`. Two wire generations are read as one stream:
//! older rollouts store `{"type":"response_item","payload":<item>}`, newer
//! ones stream `{"type":"event_msg","payload":{"type":"item_completed",
//! "item":<item>}}`. The same item appears in both (or is replayed), so
//! records de-duplicate by their (record type, id or call id) `function_call`
//! and its `function_call_output` never share a key and both survive. Live
//! event noise (`item_updated`, harness instructions, turn context, usage
//! events) is kept as `Kind::Other`: content is taken from completed items
//! only, never replayed as messages.

use std::collections::HashSet;

use serde_json::Value;

use super::{
    ARGS_LIMIT, Block, Entry, Image, Kind, Source, TEXT_LIMIT, cap, iso_timestamp, string,
    text_block,
};

/// The entries the complete lines `lines` (read from byte `start`) hold.
#[cfg(test)]
pub(super) fn entries(lines: &[u8], start: u64) -> Vec<Entry> {
    let mut offset = start;
    let mut entries = Vec::new();
    let mut seen = HashSet::new();
    for line in lines.split_inclusive(|&byte| byte == b'\n') {
        let at = offset;
        offset += line.len() as u64;
        let Ok(value) = serde_json::from_slice::<Value>(line) else {
            continue;
        };
        if let Some(entry) = entry(&value, at, offset, &mut seen) {
            entries.push(entry);
        }
    }
    entries
}

/// The one conversation record a wire line carries, if it is not a replay.
pub(super) fn entry(
    value: &Value,
    offset: u64,
    end: u64,
    seen: &mut HashSet<String>,
) -> Option<Entry> {
    let timestamp = value["timestamp"]
        .as_str()
        .and_then(iso_timestamp)
        .or_else(|| {
            value["payload"]["timestamp"]
                .as_str()
                .and_then(iso_timestamp)
        });
    let (item, mut key) = match value["type"].as_str().unwrap_or_default() {
        "response_item" => (&value["payload"], item_key(&value["payload"])),
        "event_msg" if value["payload"]["type"] == "item_completed" => (
            &value["payload"]["item"],
            item_key(&value["payload"]["item"]),
        ),
        "compacted" => {
            return Some(record(
                value,
                offset,
                end,
                timestamp,
                Kind::Compaction,
                None,
                value["payload"]["summary"]
                    .as_str()
                    .map(|summary| vec![text_block(summary, false)])
                    .unwrap_or_default(),
            ));
        }
        // Events without content (harness instructions, turn context, usage,
        // `item_updated` previews): kept so the run can be walked, not shown.
        _ => {
            return Some(record(
                value,
                offset,
                end,
                timestamp,
                Kind::Other,
                None,
                Vec::new(),
            ));
        }
    };
    let record_type = item["type"].as_str().unwrap_or_default();
    if key.is_empty() {
        key = format!("{record_type}@{offset}");
    }
    if !seen.insert(format!("{record_type}:{key}")) {
        return None;
    }
    let (kind, role, blocks) = match record_type {
        "message" => (
            Kind::Message,
            Some(cap(item["role"].as_str().unwrap_or_else(|| "unknown"), 32).0),
            content(&item["content"]),
        ),
        "reasoning" => (Kind::Message, Some("reasoning".to_owned()), reasoning(item)),
        "function_call" | "custom_tool_call" => {
            (Kind::Message, Some("tool".to_owned()), vec![call(item)])
        }
        "function_call_output" | "custom_tool_call_output" => (
            Kind::Message,
            Some("tool".to_owned()),
            vec![call_output(item)],
        ),
        "error" => (
            Kind::Message,
            Some("error".to_owned()),
            vec![text_block(&string(&item["message"]), false)],
        ),
        _ => (Kind::Other, None, Vec::new()),
    };
    Some(record(value, offset, end, timestamp, kind, role, blocks))
}

/// The record's stable id: item id, else its call id.
fn item_key(item: &Value) -> String {
    item["id"]
        .as_str()
        .or_else(|| item["call_id"].as_str())
        .unwrap_or_default()
        .to_owned()
}

fn record(
    value: &Value,
    offset: u64,
    end: u64,
    timestamp: Option<u64>,
    kind: Kind,
    role: Option<String>,
    blocks: Vec<Block>,
) -> Entry {
    Entry {
        id: message_id(value),
        // A rollout is one flat run; nothing links records to each other.
        parent_id: None,
        offset,
        end,
        kind,
        role,
        blocks,
        timestamp,
        usage: None,
        stop_reason: None,
        steer: None,
        subagent_ends: Vec::new(),
    }
}

/// Stable across old/new rollout envelopes; a call and its output share
/// a linking call_id, not a list key.
pub(super) fn message_id(value: &Value) -> Option<String> {
    let item = if value["type"] == "event_msg" {
        &value["payload"]["item"]
    } else {
        &value["payload"]
    };
    let id = item["id"].as_str().or_else(|| item["call_id"].as_str())?;
    Some(format!("{}:{id}", item["type"].as_str()?))
}

/// A `message` payload's content items. Image tiles carry a data URI whose
/// base64 can be re-read and verified against the file, so they are named
/// [`Source::Inline`]; anything else (file paths, URLs) has no verifiable
/// bytes and fails closed as [`Source::Unreadable`].
fn content(content: &Value) -> Vec<Block> {
    let Some(parts) = content.as_array() else {
        return Vec::new();
    };
    let mut image = 0;
    parts
        .iter()
        .filter_map(|part| match part["type"].as_str()? {
            "input_text" | "output_text" | "text" => {
                Some(text_block(part["text"].as_str()?, false))
            }
            "input_image" | "image" => {
                let index = image;
                image += 1;
                Some(Block::Image(image_part(index, part)))
            }
            _ => None,
        })
        .collect()
}

/// The image's `part` is its index among the images of its message, the
/// flattened order a media read walks again.
fn image_part(part: usize, value: &Value) -> Image {
    let source = match data_uri(value) {
        Some(data) => Source::Inline {
            bytes: super::inline_bytes(data),
        },
        None => Source::Unreadable,
    };
    Image { part, source }
}

/// The base64 payload of a `data:…;base64,…` image reference; the media read
/// decodes and type-checks exactly these bytes.
pub(super) fn image_data(value: &Value, part: usize) -> Option<&str> {
    let content = value["payload"]["content"]
        .as_array()
        .or_else(|| value["payload"]["item"]["content"].as_array())?;
    let image = content
        .iter()
        .filter(|part| matches!(part["type"].as_str(), Some("input_image" | "image")))
        .nth(part)?;
    data_uri(image)
}

/// The payload of an image tile's data URI, else `None` (fail closed).
fn data_uri(part: &Value) -> Option<&str> {
    let uri = part["image_url"]
        .as_str()
        .or_else(|| part["url"].as_str())
        .or_else(|| part["source"]["data"].as_str())?;
    let (_, data) = uri.split_once(";base64,")?;
    (!data.is_empty()).then_some(data)
}

/// A `reasoning` payload: summary parts first, then the thinking text.
fn reasoning(item: &Value) -> Vec<Block> {
    let mut blocks = Vec::new();
    for key in ["summary", "content"] {
        for part in item[key].as_array().into_iter().flatten() {
            if let Some(text) = part["text"].as_str() {
                blocks.push(text_block(text, true));
            }
        }
    }
    blocks
}

/// A `function_call`/`custom_tool_call` payload as one tool call.
fn call(item: &Value) -> Block {
    let raw = item["arguments"]
        .as_str()
        .or_else(|| item["input"].as_str())
        .unwrap_or_default();
    let args = match serde_json::from_str::<Value>(raw) {
        // Codex keeps the arguments as one JSON string; show object-shaped
        // arguments as written and prettify only what parses as one.
        Ok(formatted @ Value::Object(_) | formatted @ Value::Array(_)) => {
            serde_json::to_string_pretty(&formatted).unwrap_or_default()
        }
        _ => raw.to_owned(),
    };
    let (args, truncated) = cap(&args, ARGS_LIMIT);
    Block::ToolCall {
        call_id: string(&item["call_id"]),
        name: cap(&string(&item["name"]), 128).0,
        args,
        intent: None,
        truncated,
    }
}

/// A `function_call_output`/`custom_tool_call_output` payload as one result.
fn call_output(item: &Value) -> Block {
    let raw = string(&item["output"]);
    let text = match serde_json::from_str::<Value>(&raw) {
        // The output is often `{"output": …, "metadata": …}`: show `output`.
        Ok(output) if output.is_object() => string(&output["output"]),
        _ => raw,
    };
    let (text, truncated) = cap(&text, TEXT_LIMIT);
    Block::ToolResult {
        call_id: string(&item["call_id"]),
        name: String::new(),
        text,
        is_error: item["status"] == "failed" || item["type"] == "error",
        images: Vec::new(),
        truncated,
        exit_code: None,
        answers: Vec::new(),
        timed_out: false,
        subagents: Vec::new(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn call_result_and_replayed_message_keep_distinct_stable_rows() {
        let call = json!({"type":"response_item", "payload":{"type":"function_call", "call_id":"c1", "name":"shell", "arguments":"{}"}});
        let output = json!({"type":"response_item", "payload":{"type":"function_call_output", "call_id":"c1", "output":"done"}});
        let message = json!({"type":"response_item", "payload":{"type":"message", "id":"m1", "role":"assistant", "content":[{"type":"output_text", "text":"fixed"}]}});
        let replay = json!({"type":"event_msg", "payload":{"type":"item_completed", "item":message["payload"]}});
        let wire = [call, output, message, replay]
            .iter()
            .map(|row| format!("{row}\n"))
            .collect::<String>();
        let rows = entries(wire.as_bytes(), 0);
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[0].id.as_deref(), Some("function_call:c1"));
        assert_eq!(rows[1].id.as_deref(), Some("function_call_output:c1"));
        assert_eq!(rows[2].id.as_deref(), Some("message:m1"));
        assert!(matches!(&rows[0].blocks[0], Block::ToolCall {call_id, ..} if call_id == "c1"));
        assert!(
            matches!(&rows[1].blocks[0], Block::ToolResult {call_id, text, ..} if call_id == "c1" && text == "done")
        );
    }
}
