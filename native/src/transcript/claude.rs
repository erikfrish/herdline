//! Claude Code transcript lines: one JSONL record per content block of a
//! turn, so a message splits across consecutive lines sharing one
//! `message.id`, and `message.usage` repeats on each of them. Images carry
//! base64 in `source.data`; some sit nested inside a `tool_result`'s own
//! content, so images of a line are numbered in one flattened order across
//! all nesting.

use super::{Block, Entry, Image, Kind, Source, Usage, cap, text_block};
use serde_json::Value;

/// One JSONL record into an [`Entry`]. `offset`/`end` bound the line.
/// Noise keeps its `id`/`parent_id` so a branch still walks through it.
pub fn normalize(value: &Value, offset: u64, end: u64) -> Entry {
    let (kind, role, blocks) = record(value);
    Entry {
        id: value["uuid"].as_str().map(str::to_owned),
        parent_id: parent(value),
        offset,
        end,
        kind,
        role,
        blocks,
        timestamp: value["timestamp"].as_str().and_then(super::iso_timestamp),
        // One message turns up as several lines; usage counts once per turn.
        usage: if value["message"]["stop_reason"].is_null() {
            None
        } else {
            usage(&value["message"]["usage"])
        },
        stop_reason: value["message"]["stop_reason"]
            .as_str()
            .map(|reason| cap(reason, 32).0),
        steer: None,
        subagent_ends: Vec::new(),
    }
}

/// A key unique per message turn, `None` without one. Lines sharing a key
/// form one message: it uses one [`Usage`] no matter how many lines repeat
/// it; a caller paginating must dedupe `usage` by this key across pages,
/// since the single counted line can fall off either page's edge.
pub fn message_key(value: &Value) -> Option<String> {
    value["message"]["id"].as_str().map(str::to_owned)
}

/// Where a flattened image keeps its bytes: Claude's base64 in
/// `source.data`, at whatever nesting `part` sits in. `part` is the image's
/// ordinal in the line's flattened image order — the index an [`Entry`]
/// gives its [`Image`]; use it with the same numbering.
pub fn image_data(value: &Value, part: usize) -> Option<&str> {
    images(value).nth(part)?["source"]["data"].as_str()
}

/// The line's images in a flat, walk order: each `message.content` slot in
/// turn, a `tool_result`'s nested content included where it stands.
fn images(value: &Value) -> impl Iterator<Item = &Value> {
    value["message"]["content"]
        .as_array()
        .into_iter()
        .flatten()
        .flat_map(|part| {
            let nested = part["content"]
                .as_array()
                .into_iter()
                .flatten()
                .filter(|nested| nested["type"] == "image");
            std::iter::once(part).chain(nested)
        })
        .filter(|part| part["type"] == "image")
}

fn parent(value: &Value) -> Option<String> {
    // `logicalParentUuid` links a record past a compaction boundary.
    value["parentUuid"]
        .as_str()
        .or_else(|| value["logicalParentUuid"].as_str())
        .map(str::to_owned)
}

/// Usage as Claude counts one turn: cached input counts in. A count that is
/// not present is absent rather than guessed.
fn usage(usage: &Value) -> Option<Usage> {
    let input = usage["input_tokens"].as_u64()?;
    let output = usage["output_tokens"].as_u64()?;
    // Cache counts may be absent in older logs; they are input tokens too.
    let cache = usage["cache_creation_input_tokens"].as_u64().unwrap_or(0)
        + usage["cache_read_input_tokens"].as_u64().unwrap_or(0);
    let input = input + cache;
    Some(Usage {
        input,
        output,
        total_tokens: input + output,
        cost: None,
    })
}

fn record(value: &Value) -> (Kind, Option<String>, Vec<Block>) {
    if noise(value) {
        return (Kind::Other, None, Vec::new());
    }
    match value["type"].as_str() {
        Some("system") if value["subtype"] == "compact_boundary" => (
            Kind::Compaction,
            None,
            vec![text_block("Conversation compacted", false)],
        ),
        Some("user") => {
            let blocks = content(&value["message"]["content"]);
            if value["isCompactSummary"] == true {
                (Kind::Compaction, Some("user".to_owned()), blocks)
            } else {
                (Kind::Message, Some("user".to_owned()), blocks)
            }
        }
        Some("assistant") => (
            Kind::Message,
            Some("assistant".to_owned()),
            content(&value["message"]["content"]),
        ),
        _ => (Kind::Other, None, Vec::new()),
    }
}

/// Records chat shows without content, but keeps so a branch walks through
/// them: sidechains, meta and progress noise and metadata lines.
fn noise(value: &Value) -> bool {
    if value["isSidechain"] == true {
        return true;
    }
    match value["type"].as_str() {
        Some("user") if value["isMeta"] == true || value["isVisibleInTranscriptOnly"] == true => {
            true
        }
        Some(
            "progress"
            | "attachment"
            | "file-history-snapshot"
            | "queue-operation"
            | "last-prompt"
            | "ai-title"
            | "pr-link"
            | "summary"
            | "permission-mode"
            | "mode",
        ) => true,
        Some("system") => value["subtype"] != "compact_boundary",
        _ => false,
    }
}

/// Message content: a string, or an array of typed parts. Parts translate
/// one for one, so a `tool_result` and the images around it stay in the same
/// message in their original order; `next` numbers images across the whole
/// walk.
fn content(content: &Value) -> Vec<Block> {
    if let Some(text) = content.as_str() {
        return vec![text_block(text, false)];
    }
    let Some(parts) = content.as_array() else {
        return Vec::new();
    };
    let mut next = 0..;
    parts
        .iter()
        .filter_map(|part| match part["type"].as_str()? {
            "text" => Some(text_block(part["text"].as_str()?, false)),
            "thinking" => Some(text_block(part["thinking"].as_str()?, true)),
            "tool_use" => Some(Block::ToolCall {
                call_id: super::string(&part["id"]),
                name: cap(&super::string(&part["name"]), 128).0,
                args: args(&part["input"]),
                intent: None,
                truncated: false,
            }),
            "image" => Some(Block::Image(img(&mut next, part))),
            "tool_result" => Some(tool_result(part, &mut next)),
            _ => None,
        })
        .collect()
}

/// One image as Claude stores it, numbered `next` in the line's flat order.
fn img(next: &mut impl Iterator<Item = usize>, part: &Value) -> Image {
    Image {
        part: next.next().unwrap_or(0),
        source: source(part),
    }
}

fn args(input: &Value) -> String {
    let args = match input {
        Value::Null => String::new(),
        input => serde_json::to_string_pretty(input).unwrap_or_default(),
    };
    cap(&args, super::ARGS_LIMIT).0
}

/// A `tool_result` pairs with the `tool_use` of its `tool_use_id`. Its
/// content is a string or text and image parts, and its images keep the
/// line's flattened numbering.
fn tool_result(part: &Value, next: &mut impl Iterator<Item = usize>) -> Block {
    let content = &part["content"];
    let text = match content.as_str() {
        Some(text) => text.to_owned(),
        None => content
            .as_array()
            .map(|parts| {
                parts
                    .iter()
                    .filter_map(|nested| nested["text"].as_str())
                    .collect::<Vec<_>>()
                    .join("\n")
            })
            .unwrap_or_default(),
    };
    let (text, truncated) = cap(&text, super::RESULT_LIMIT);
    let images = content
        .as_array()
        .map(|parts| {
            parts
                .iter()
                .filter(|nested| nested["type"] == "image")
                .map(|nested| img(next, nested))
                .collect()
        })
        .unwrap_or_default();
    Block::ToolResult {
        call_id: super::string(&part["tool_use_id"]),
        name: String::new(),
        text,
        is_error: part["is_error"] == true,
        images,
        truncated,
        exit_code: None,
        answers: Vec::new(),
        timed_out: false,
        subagents: Vec::new(),
    }
}

/// An image part as Claude stores it: base64 in `source.data`. Anything
/// else is unreadable.
fn source(part: &Value) -> Source {
    match part["source"].as_object() {
        Some(source) if source.get("type").and_then(Value::as_str) == Some("base64") => {
            match source.get("data").and_then(Value::as_str) {
                Some(data) if !data.is_empty() => Source::Inline {
                    bytes: super::inline_bytes(data),
                },
                _ => Source::Unreadable,
            }
        }
        _ => Source::Unreadable,
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Claude's record shapes: what is shown and what only walks the branch.
    #[test]
    fn normalizes_record_shapes() {
        // A user line's body can be plain text, and the tree comes from the
        // uuid chain with a logical parent past a compaction.
        let e = normalize(
            &json!({"uuid":"u1","parentUuid":"p1","type":"user",
                "timestamp":"1970-01-01T00:00:01Z",
                "message":{"role":"user","content":"hi","id":"m1"}}),
            0,
            1,
        );
        assert_eq!(e.id.as_deref(), Some("u1"));
        assert_eq!(e.parent_id.as_deref(), Some("p1"));
        assert_eq!(e.kind, Kind::Message);
        assert_eq!(e.timestamp, Some(1000));
        assert_eq!(e.role.as_deref(), Some("user"));

        let e = normalize(
            &json!({"uuid":"u2","logicalParentUuid":"p1","type":"system",
                "subtype":"compact_boundary"}),
            0,
            1,
        );
        assert_eq!(e.parent_id.as_deref(), Some("p1"));
        assert_eq!(e.kind, Kind::Compaction);

        // A user tool_result pairs by tool_use_id and reports its error and
        // text; nested images keep the line's flat numbering.
        let e = normalize(
            &json!({"uuid":"u3","type":"user","message":{"role":"user","content":[
                {"type":"tool_result","tool_use_id":"call-1","is_error":true,
                 "content":[{"type":"text","text":"boom"},
                            {"type":"image","source":{"type":"base64","data":"aW1n"}}]}]}}),
            0,
            1,
        );
        let [
            Block::ToolResult {
                call_id,
                is_error,
                text,
                images,
                name,
                ..
            },
        ] = e.blocks.as_slice()
        else {
            panic!("tool result expected: {:?}", e.blocks);
        };
        assert_eq!(call_id, "call-1");
        assert_eq!(name, "");
        assert!(*is_error);
        assert_eq!(text, "boom");
        assert_eq!(images.len(), 1);
        assert_eq!(images[0].part, 0);
        assert!(matches!(images[0].source, Source::Inline { bytes: 3 }));

        // An assistant line: thinking and text in order, a tool_use paired
        // by its id with pretty arguments.
        let e = normalize(
            &json!({"uuid":"u4","type":"assistant","timestamp":"1970-01-01T00:00:02Z",
                "message":{"role":"assistant","id":"m4","stop_reason":"stop",
                "content":[{"type":"thinking","thinking":"hmm"},
                           {"type":"text","text":"answer"},
                           {"type":"tool_use","id":"call-2","name":"Bash",
                            "input":{"cmd":"ls"}}]}}),
            0,
            1,
        );
        assert_eq!(e.role.as_deref(), Some("assistant"));
        assert_eq!(e.stop_reason.as_deref(), Some("stop"));
        assert_eq!(e.timestamp, Some(2000));
        let [
            Block::Thinking { text: thinking, .. },
            Block::Text { text, .. },
            Block::ToolCall {
                call_id,
                name,
                args,
                ..
            },
        ] = e.blocks.as_slice()
        else {
            panic!("thinking/text/tool expected: {:?}", e.blocks);
        };
        assert_eq!(thinking, "hmm");
        assert_eq!(text, "answer");
        assert_eq!(call_id, "call-2");
        assert_eq!(name, "Bash");
        assert!(args.contains("\"cmd\": \"ls\""), "{args}");

        // Noise keeps its branch IDs but shows nothing.
        for body in [
            json!({"uuid":"n1","parentUuid":"p","type":"user","isMeta":true,
                "message":{"content":"internal note"}}),
            json!({"uuid":"n2","parentUuid":"p","type":"user","isSidechain":true,
                "message":{"content":"side"}}),
            json!({"uuid":"n3","parentUuid":"p","type":"progress"}),
            json!({"uuid":"n4","parentUuid":"p","type":"system","subtype":"spinner"}),
        ] {
            let e = normalize(&body, 0, 1);
            assert_eq!(e.kind, Kind::Other, "{:?}", e);
            assert_eq!(e.id.as_deref(), body["uuid"].as_str(), "{:?}", e);
            assert_eq!(
                e.parent_id.as_deref(),
                body["parentUuid"].as_str(),
                "{:?}",
                e
            );
            assert!(e.blocks.is_empty(), "{:?}", e);
        }
    }

    /// Usage counts once per message turn even though every line repeats it.
    #[test]
    fn usage_once_per_message() {
        // Cached input counts in, and the count lands only on the line that
        // ends the turn.
        let end = json!({"uuid":"e","type":"assistant","message":{
            "id":"m1","stop_reason":"stop","usage":{
            "input_tokens":5,"output_tokens":7,
            "cache_creation_input_tokens":2,"cache_read_input_tokens":3}}});
        let e = normalize(&end, 0, 1);
        assert_eq!(
            e.usage,
            Some(Usage {
                input: 10,
                output: 7,
                total_tokens: 17,
                cost: None
            })
        );
        assert_eq!(message_key(&end).as_deref(), Some("m1"));

        // The earlier line of the same turn carries the same repeated usage
        // but no stop reason: it shows no usage, and its message key tells
        // any page dedupe these are one turn.
        let earlier = json!({"uuid":"a","type":"assistant","message":{
            "id":"m1","content":[{"type":"text","text":"half"}],
            "usage":{"input_tokens":5,"output_tokens":7,
            "cache_creation_input_tokens":2,"cache_read_input_tokens":3}}});
        let e = normalize(&earlier, 0, 1);
        assert!(e.usage.is_none(), "{:?}", e);
        assert_eq!(message_key(&earlier), message_key(&end));

        // A count that is not present is absent rather than guessed.
        let missing = json!({"uuid":"b","type":"assistant","message":{
            "id":"m2","stop_reason":"stop","usage":{"input_tokens":5}}});
        let e = normalize(&missing, 0, 1);
        assert!(e.usage.is_none(), "{:?}", e);
    }

    /// Images in one flattened order: content slots including nested
    /// tool_result images, all named by their position in that walk.
    #[test]
    fn images_flatten_nested_tool_results() {
        let value = json!({"uuid":"i","type":"user","message":{"role":"user","content":[
            {"type":"image","source":{"type":"base64","data":"Zmlyc3Q="}},
            {"type":"tool_result","tool_use_id":"c","content":[
                {"type":"text","text":"look"},
                {"type":"image","source":{"type":"base64","data":"c2Vjb25k"}},
                {"type":"image","source":{"type":"base64","data":"dGhpcmQ"}}]},
            {"type":"image","source":{"type":"base64","data":"Zm91cnQ="}}]}});
        let e = normalize(&value, 0, 1);
        let [
            Block::Image(a),
            Block::ToolResult { images, .. },
            Block::Image(d),
        ] = e.blocks.as_slice()
        else {
            panic!("image/result/image expected: {:?}", e.blocks);
        };
        assert_eq!(a.part, 0);
        assert_eq!(images[0].part, 1);
        assert_eq!(images[1].part, 2);
        assert_eq!(d.part, 3);

        // The same numbering names bytes for a media read, wherever the
        // image sits.
        assert_eq!(image_data(&value, 0), Some("Zmlyc3Q="));
        assert_eq!(image_data(&value, 1), Some("c2Vjb25k"));
        assert_eq!(image_data(&value, 2), Some("dGhpcmQ"));
        assert_eq!(image_data(&value, 3), Some("Zm91cnQ="));
        assert_eq!(image_data(&value, 4), None);
    }
}
