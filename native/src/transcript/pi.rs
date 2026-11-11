//! Pi (badlogic/pi-mono v3) transcript lines: OMP's record format with a few
//! deltas. Unlike OMP, Pi keeps `i` inside tool arguments, has `bashExecution`
//! and `branch_summary` records worth showing, and carries inline base64
//! images only — there is no blob store, so `blob:sha256:` is unreadable.

use super::{Block, Entry, Kind, Steer, cap, string, text_block};
use serde_json::Value;

/// One JSONL record into an [`Entry`]. `offset`/`end` bound the line.
pub fn normalize(value: &Value, offset: u64, end: u64) -> Entry {
    let (kind, role, blocks) = record(value);
    let none = Value::Null;
    let message = if kind == Kind::Message {
        &value["message"]
    } else {
        &none
    };
    Entry {
        id: value["id"].as_str().map(str::to_owned),
        parent_id: value["parentId"].as_str().map(str::to_owned),
        offset,
        end,
        kind,
        role,
        blocks,
        timestamp: message["timestamp"].as_u64(),
        usage: super::usage(&message["usage"]),
        stop_reason: message["stopReason"]
            .as_str()
            .map(|reason| cap(reason, 32).0),
        steer: if message["role"] == "user" {
            if message["liveSteered"] == true {
                Some(Steer::Live)
            } else if message["steering"] == true {
                Some(Steer::Steering)
            } else {
                None
            }
        } else {
            None
        },
        subagent_ends: Vec::new(),
    }
}

/// A key unique per message turn, `None` when the record has no ID. Lines
/// sharing a key are one message split across lines: both halves are shown,
/// their shared `usage` counted once by the caller.
pub fn message_key(value: &Value) -> Option<String> {
    value["id"].as_str().map(str::to_owned)
}

/// Where a content image keeps its bytes. `part` is the image's ordinal among
/// `message.content` images (the index an [`Entry`] gives its
/// `Block::Image`); Pi's inline base64 only, `None` for a `blob:sha256:`
/// since Pi has no blob store to read it from.
pub fn image_data(value: &Value, part: usize) -> Option<&str> {
    images(value).nth(part)?.1["data"]
        .as_str()
        .filter(|data| !data.is_empty() && !data.starts_with(super::BLOB_PREFIX))
}

/// The image parts of a record's message content, with their image ordinals.
fn images(value: &Value) -> impl Iterator<Item = (usize, &Value)> {
    let content = if value["type"] == "custom_message" {
        &value["content"]
    } else {
        &value["message"]["content"]
    };
    content
        .as_array()
        .into_iter()
        .flatten()
        .filter(|part| part["type"] == "image")
        .enumerate()
}

fn record(value: &Value) -> (Kind, Option<String>, Vec<Block>) {
    match value["type"].as_str() {
        Some("message") => match value["message"]["role"].as_str().unwrap_or_default() {
            "bashExecution" => (
                Kind::Message,
                Some("bashExecution".to_owned()),
                vec![bash_execution(&value["message"])],
            ),
            role if role == "custom" && value["message"]["display"] == false => {
                (Kind::Other, None, Vec::new())
            }
            "toolResult" => (
                Kind::Message,
                Some("toolResult".to_owned()),
                vec![tool_result(&value["message"])],
            ),
            role => (
                Kind::Message,
                Some(cap(role, 32).0),
                content(&value["message"]["content"]),
            ),
        },
        Some("compaction") | Some("branch_summary") => (
            Kind::Compaction,
            None,
            value["summary"]
                .as_str()
                .map(|summary| vec![text_block(summary, false)])
                .unwrap_or_default(),
        ),
        Some("custom_message") if value["display"] == true => (
            Kind::Message,
            Some("custom".to_owned()),
            content(&value["content"]),
        ),
        // usage, model_change, thinking_level_change, context_edit, custom,
        // label, session_info: kept without content so a branch walks through
        // them.
        _ => (Kind::Other, None, Vec::new()),
    }
}

/// A `bashExecution` line shows as its command output in one tool result.
fn bash_execution(message: &Value) -> Block {
    let (command, truncated_a) = cap(&string(&message["command"]), 1024);
    let (output, truncated_b) = cap(&string(&message["output"]), super::RESULT_LIMIT);
    let text = if command.is_empty() {
        output
    } else {
        format!("{command}\n{output}")
    };
    Block::ToolResult {
        call_id: string(&message["id"]),
        name: "bash".to_owned(),
        text,
        is_error: message["cancelled"] == true
            || message["exitCode"].as_i64().is_some_and(|code| code != 0),
        images: Vec::new(),
        truncated: truncated_a || truncated_b,
        exit_code: message["exitCode"].as_i64(),
        answers: Vec::new(),
        timed_out: false,
        subagents: Vec::new(),
    }
}

fn tool_result(message: &Value) -> Block {
    let parts = message["content"]
        .as_array()
        .map(Vec::as_slice)
        .unwrap_or_default();
    let text = parts
        .iter()
        .filter_map(|part| part["text"].as_str())
        .collect::<Vec<_>>()
        .join("\n");
    let (text, truncated) = cap(
        &crate::herdr_setup::plain_text(text.as_bytes()),
        super::RESULT_LIMIT,
    );
    let mut image_ordinals = 0..;
    Block::ToolResult {
        call_id: string(&message["toolCallId"]),
        name: string(&message["toolName"]),
        text,
        is_error: message["isError"] == true,
        images: parts
            .iter()
            .filter(|part| part["type"] == "image")
            .map(|part| image(image_ordinals.next().unwrap_or(0), part))
            .collect(),
        truncated,
        exit_code: message["details"]["exitCode"].as_i64(),
        answers: Vec::new(),
        timed_out: false,
        subagents: Vec::new(),
    }
}

fn content(content: &Value) -> Vec<Block> {
    if let Some(text) = content.as_str() {
        return vec![text_block(text, false)];
    }
    let Some(parts) = content.as_array() else {
        return Vec::new();
    };
    let mut image_ordinals = 0..;
    parts
        .iter()
        .filter_map(|part| match part["type"].as_str()? {
            "text" => Some(text_block(part["text"].as_str()?, false)),
            "thinking" => Some(text_block(part["thinking"].as_str()?, true)),
            // Pi keeps the intent as `i` among the arguments; leave it there.
            "toolCall" => Some(tool_call(part)),
            "image" => Some(Block::Image(image(
                image_ordinals.next().unwrap_or(0),
                part,
            ))),
            _ => None,
        })
        .collect()
}

/// An image part as Pi stores it: inline base64 or nothing. There is no blob
/// store, so a `blob:sha256:` reference is unreadable rather than fetchable.
fn image(part: usize, value: &Value) -> super::Image {
    let source = match value["data"].as_str() {
        Some(data) if !data.is_empty() && !data.starts_with(super::BLOB_PREFIX) => {
            super::Source::Inline {
                bytes: super::inline_bytes(data),
            }
        }
        _ => super::Source::Unreadable,
    };
    super::Image { part, source }
}

/// Pi keeps OMP's `i` intent among the arguments, unlike the shared parser
/// which strips it.
fn tool_call(part: &Value) -> Block {
    let args = match part["arguments"] {
        Value::Null => String::new(),
        ref arguments => serde_json::to_string_pretty(arguments).unwrap_or_default(),
    };
    let (args, truncated) = cap(&args, super::ARGS_LIMIT);
    Block::ToolCall {
        call_id: string(&part["id"]),
        name: cap(&string(&part["name"]), 128).0,
        args,
        intent: part["intent"].as_str().map(|intent| cap(intent, 512).0),
        truncated,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use crate::transcript::Source;
    use serde_json::json;

    fn line(body: Value) -> Value {
        let mut root = json!({"id":"line","parentId":"parent","type":"message"});
        for (key, value) in body.as_object().unwrap() {
            root[key] = value.clone();
        }
        root
    }

    /// What each record shape shows, and what stays to walk the branch.
    #[test]
    fn normalizes_record_shapes() {
        // A user turn with its timestamp and steer flag.
        let e = normalize(
            &line(json!({"message":{"role":"user","content":"hello",
                "timestamp":123,"steering":true}})),
            0,
            1,
        );
        assert_eq!(e.id.as_deref(), Some("line"));
        assert_eq!(e.parent_id.as_deref(), Some("parent"));
        assert_eq!(e.kind, Kind::Message);
        assert_eq!(e.role.as_deref(), Some("user"));
        assert_eq!(e.timestamp, Some(123));
        assert_eq!(e.steer, Some(Steer::Steering));
        assert!(matches!(e.blocks.as_slice(), [Block::Text { text, .. }] if text == "hello"));

        // Pi keeps `i` among the arguments and its usage on the turn.
        let e = normalize(
            &line(json!({"message":{"role":"assistant","stopReason":"stop",
                "usage":{"input":10,"output":20,"totalTokens":30,"cost":{"total":0.25}},
                "content":[{"type":"toolCall","id":"c1","name":"bash",
                    "arguments":{"i":"unit-test","i2":"dot","cmd":"ls"},
                    "intent":"list files"}]}})),
            0,
            1,
        );
        let [
            Block::ToolCall {
                call_id,
                name,
                args,
                intent,
                ..
            },
        ] = e.blocks.as_slice()
        else {
            panic!("tool call expected: {:?}", e.blocks);
        };
        assert_eq!(call_id, "c1");
        assert_eq!(name, "bash");
        assert_eq!(intent.as_deref(), Some("list files"));
        assert!(args.contains("\"i\": \"unit-test\""), "{args}");
        assert_eq!(e.stop_reason.as_deref(), Some("stop"));
        assert_eq!(e.usage.unwrap().total_tokens, 30);

        // A bashExecution is shown, not dropped: its command and output on
        // one result, with the exit code; a cancellation is an error.
        for (record, error, code) in [
            (
                json!({"command":"make","output":"failed","exitCode":1}),
                true,
                Some(1),
            ),
            (
                json!({"command":"sleep 9","output":"","cancelled":true}),
                true,
                None,
            ),
            (
                json!({"command":"ls","output":"src","exitCode":0}),
                false,
                Some(0),
            ),
        ] {
            let mut message = json!({"role":"bashExecution","timestamp":1});
            for (key, value) in record.as_object().unwrap() {
                message[key] = value.clone();
            }
            let e = normalize(&line(json!({"message": message})), 0, 1);
            assert_eq!(e.kind, Kind::Message);
            assert_eq!(e.role.as_deref(), Some("bashExecution"));
            let [
                Block::ToolResult {
                    text,
                    is_error,
                    exit_code,
                    name,
                    ..
                },
            ] = e.blocks.as_slice()
            else {
                panic!("bash result expected: {:?}", e.blocks);
            };
            assert_eq!(name, "bash");
            assert_eq!(*is_error, error, "{text}");
            assert_eq!(*exit_code, code, "{text}");
        }

        // A branch summary compacts the branch it came from.
        let e = normalize(
            &line(json!({"type":"branch_summary","summary":"went down a path"})),
            0,
            1,
        );
        assert_eq!(e.kind, Kind::Compaction);
        assert!(
            matches!(e.blocks.as_slice(), [Block::Text { text, .. }] if text == "went down a path")
        );

        // Records shown without content still keep their branch IDs.
        for body in [
            json!({"type":"usage","id":"u","parentId":"a","usage":{}}),
            json!({"type":"thinking_level_change","id":"t","parentId":"a","level":"high"}),
            json!({"type":"session_info","id":"s","parentId":"a","workdir":"/x"}),
        ] {
            let (id, parent) = (body["id"].clone(), body["parentId"].clone());
            let e = normalize(&line(body), 0, 1);
            assert_eq!(e.id.as_deref(), id.as_str(), "{:?}", e);
            assert_eq!(e.parent_id.as_deref(), parent.as_str(), "{:?}", e);
            assert_eq!(e.kind, Kind::Other, "{:?}", e);
            assert!(e.blocks.is_empty(), "{:?}", e);
        }
    }

    /// Pi images: inline base64 only, flat index, no blob store.
    #[test]
    fn images_flat_index_inline_only() {
        let value = line(json!({"message":{"role":"assistant","content":[
            {"type":"image","data":"aGVsbG8="},
            {"type":"text","text":"and"},
            {"type":"image","data":"blob:sha256:"},
            {"type":"image","data":"d29ybGQ="}]}}));
        let e = normalize(&value, 0, 1);
        let [
            Block::Image(first),
            Block::Text { .. },
            Block::Image(second),
            Block::Image(third),
        ] = e.blocks.as_slice()
        else {
            panic!("images expected: {:?}", e.blocks);
        };
        assert_eq!(first.part, 0);
        assert_eq!(second.part, 1);
        assert_eq!(third.part, 2);
        assert!(matches!(first.source, Source::Inline { bytes: 5 }));
        // No Pi blob store exists: a blob reference is unreadable and its
        // bytes are never offered to a media read.
        assert_eq!(second.source, Source::Unreadable);
        assert_eq!(image_data(&value, 0), Some("aGVsbG8="));
        assert_eq!(image_data(&value, 1), None);
        assert_eq!(image_data(&value, 2), Some("d29ybGQ="));
    }

    /// Lines sharing a message key are one turn counted once.
    #[test]
    fn message_key_groups_split_turns() {
        let first = line(json!({"id":"m1","message":{"role":"user","content":"long"}}));
        let second = line(json!({"id":"m1","message":{"role":"user","content":"message"}}));
        assert_eq!(message_key(&first), message_key(&second));
        assert_ne!(message_key(&first), message_key(&line(json!({"id":"m2"}))));
    }
}
