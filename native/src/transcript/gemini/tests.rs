//! Session lines here are synthetic, written in the record shapes of
//! gemini-cli v0.68.0 (5a5c7180,
//! `packages/core/src/services/chatRecordingTypes.ts`), except
//! `subagent.jsonl` and the `live-*.jsonl` sessions and children, captured
//! from a real Gemini 0.68.0 against a mock provider. They pin what the
//! parser does with such lines, as `createJsonlRecordAccumulator` and
//! `convertSessionToHistoryFormats` do.

use super::*;
use serde_json::json;

const SID: &str = "6f1c2a9e-3b4d-4e5f-8a7b-9c0d1e2f3a4b";

fn jsonl(records: &[Value]) -> Vec<u8> {
    records
        .iter()
        .map(|record| record.to_string() + "\n")
        .collect::<String>()
        .into_bytes()
}

fn header() -> Value {
    json!({"sessionId": SID, "projectHash": "ab12", "startTime": "2026-11-12T10:00:00.000Z", "kind": "main"})
}

fn user(id: &str, text: &str) -> Value {
    json!({"id": id, "timestamp": "2026-11-12T10:00:01.000Z", "type": "user", "content": [{"text": text}]})
}

fn gemini(id: &str, text: &str) -> Value {
    json!({"id": id, "timestamp": "2026-11-12T10:00:02.000Z", "type": "gemini", "content": text})
}

fn ids(data: &[u8]) -> Vec<String> {
    fold(data)
        .unwrap()
        .messages
        .iter()
        .map(|message| message["id"].as_str().unwrap().to_owned())
        .collect()
}

fn texts(entry: &Entry) -> Vec<(bool, String)> {
    entry
        .blocks
        .iter()
        .filter_map(|block| match block {
            Block::Text { text, .. } => Some((false, text.clone())),
            Block::Thinking { text, .. } => Some((true, text.clone())),
            _ => None,
        })
        .collect()
}

#[test]
fn a_turn_shows_thoughts_then_text_then_tools_with_tokens() {
    let turn = json!({
        "id": "m2", "timestamp": "2026-11-12T10:00:02.000Z", "type": "gemini", "content": [{"text": "Listing."}],
        "thoughts": [{"subject": "Plan", "description": "look first", "timestamp": "t"}],
        "tokens": {"input": 120, "output": 30, "cached": 100, "total": 150},
        "toolCalls": [
            {"id": "c1", "name": "list_directory", "displayName": "ReadFolder", "args": {"path": "."},
             "status": "success", "timestamp": "t", "resultDisplay": "Listed 2 item(s).",
             "result": [{"functionResponse": {"id": "c1", "name": "list_directory", "response": {"output": "a\nb"}}}]},
            {"id": "c2", "name": "run_shell_command", "args": {"command": "make"}, "status": "executing", "timestamp": "t"},
            {"id": "c3", "name": "replace", "args": {}, "status": "cancelled", "timestamp": "t",
             "resultDisplay": {"fileDiff": "--- a\n+++ b", "fileName": "x"}},
        ],
    });
    let conversation = fold(&jsonl(&[header(), user("m1", "list it"), turn])).unwrap();
    assert_eq!(
        (
            conversation.session_id.as_str(),
            conversation.project_hash.as_str()
        ),
        (SID, "ab12")
    );
    let entries = conversation.entries();
    assert_eq!(entries.len(), 2);
    assert_eq!(
        (entries[0].role.as_deref(), entries[0].id.as_deref()),
        (Some("user"), Some("m1"))
    );
    assert_eq!(
        entries[0].timestamp,
        iso_timestamp("2026-11-12T10:00:01.000Z")
    );
    let turn = &entries[1];
    assert_eq!(
        (turn.offset, turn.end, turn.role.as_deref()),
        (1, 2, Some("assistant"))
    );
    assert_eq!(
        texts(turn),
        vec![
            (true, "Plan\nlook first".to_owned()),
            (false, "Listing.".to_owned())
        ]
    );
    assert_eq!(
        turn.usage,
        Some(Usage {
            input: 120,
            output: 30,
            total_tokens: 150,
            cost: None
        })
    );
    let tools: Vec<_> = turn
        .blocks
        .iter()
        .filter_map(|block| match block {
            Block::ToolCall { call_id, name, .. } => Some(format!("call {call_id} {name}")),
            Block::ToolResult {
                call_id,
                text,
                is_error,
                ..
            } => Some(format!("result {call_id} {is_error} {text}")),
            _ => None,
        })
        .collect();
    // The person's view of a result wins over the model's; a running tool has none yet.
    assert_eq!(
        tools,
        [
            "call c1 ReadFolder",
            "result c1 false Listed 2 item(s).",
            "call c2 run_shell_command",
            "call c3 replace",
            "result c3 true --- a\n+++ b",
        ]
    );
}

#[test]
fn rewind_drops_the_target_and_everything_after_it_and_an_unknown_target_drops_all() {
    let base = [
        header(),
        user("a", "1"),
        gemini("b", "2"),
        user("c", "3"),
        gemini("d", "4"),
    ];
    let mut lines = base.to_vec();
    lines.push(json!({"$rewindTo": "c"}));
    lines.push(user("e", "5"));
    assert_eq!(ids(&jsonl(&lines)), ["a", "b", "e"]);
    let mut lines = base.to_vec();
    lines.push(json!({"$rewindTo": "gone"}));
    assert!(ids(&jsonl(&lines)).is_empty());
}

#[test]
fn patches_replace_in_place_remove_and_move_listed_ids_to_the_end() {
    let call = json!({"id": "m2", "timestamp": "t", "type": "gemini", "content": "",
        "toolCalls": [{"id": "c1", "name": "read_file", "args": {}, "status": "success", "timestamp": "t"}]});
    let data = jsonl(&[
        header(),
        user("m1", "first"),
        call,
        user("m3", "third"),
        user("m4", "fourth"),
        // A known id set again keeps its place (JS Map).
        user("m1", "first, edited"),
        json!({"$patch": {"id": "m2", "toolCalls": [{"id": "c1", "result": [{"functionResponse": {"name": "read_file", "response": {"output": "body"}}}]}]}}),
        json!({"$patch": {"updates": [{"id": "m3", "content": "third, patched"}, {"id": "nope", "content": "x"}]}}),
        json!({"$patch": {"removeIds": ["m4", 7]}}),
        user("m5", "fifth"),
        json!({"$patch": {"orderIds": ["m3", "missing", "m1", "m3"]}}),
    ]);
    assert_eq!(ids(&data), ["m2", "m5", "m3", "m1"]);
    let entries = fold(&data).unwrap().entries();
    let shown: Vec<_> = entries
        .iter()
        .flat_map(texts)
        .map(|(_, text)| text)
        .collect();
    assert_eq!(shown, ["fifth", "third, patched", "first, edited"]);
    assert!(
        entries[0]
            .blocks
            .iter()
            .any(|block| matches!(block, Block::ToolResult { text, .. } if text == "body"))
    );
}

#[test]
fn a_patch_that_throws_in_gemini_keeps_what_it_changed_before_and_drops_the_rest() {
    let call = json!({"id": "m1", "timestamp": "t", "type": "gemini", "content": "old",
        "toolCalls": [{"id": "c1", "name": "x", "args": {}, "status": "success", "timestamp": "t"}]});
    let data = jsonl(&[
        header(),
        call,
        user("m2", "kept"),
        // `null.id` throws inside find: the content is already replaced, removeIds never runs.
        json!({"$patch": {"id": "m1", "content": "new", "toolCalls": [null], "removeIds": ["m2"]}}),
    ]);
    let conversation = fold(&data).unwrap();
    assert_eq!(ids(&data), ["m1", "m2"]);
    assert_eq!(conversation.messages[0]["content"], "new");
}

#[test]
fn a_set_checkpoint_replaces_all_messages_and_its_metadata_wins() {
    let data = jsonl(&[
        header(),
        user("a", "gone"),
        json!({"$set": {"projectHash": "cd34", "messages": [user("x", "kept"), {"no": "id"}, gemini("y", "too")]}}),
        user("z", "after"),
        json!("not a record"),
    ]);
    let conversation = fold(&data).unwrap();
    assert_eq!(conversation.project_hash, "cd34");
    assert_eq!(ids(&data), ["x", "y", "z"]);
}

#[test]
fn no_session_without_both_ids_unless_the_whole_file_is_a_legacy_record() {
    let mut data = jsonl(&[json!({"sessionId": SID, "projectHash": ""}), user("a", "1")]);
    data.extend_from_slice(b"{\"half\": \n");
    assert!(fold(&data).is_none());
    // Truthy but not a string: Gemini would load it; no id is trusted here.
    assert!(
        fold(&jsonl(&[
            json!({"$set": {"sessionId": 5, "projectHash": "h"}})
        ]))
        .is_none()
    );
    let legacy = serde_json::to_string_pretty(
        &json!({"sessionId": SID, "messages": [user("a", "old"), user("a", "twice")]}),
    )
    .unwrap();
    let conversation = fold(legacy.as_bytes()).unwrap();
    assert_eq!(
        (
            conversation.session_id.as_str(),
            conversation.messages.len()
        ),
        (SID, 2)
    );
}

#[test]
fn shown_text_follows_geminis_resumed_view() {
    let data = jsonl(&[
        header(),
        json!({"id": "u1", "timestamp": "t", "type": "user", "content": [{"text": "@a.txt expanded"}], "displayContent": "@a.txt"}),
        json!({"id": "u2", "timestamp": "t", "type": "user", "content": "  <session_context>cwd</session_context>"}),
        json!({"id": "u3", "timestamp": "t", "type": "user", "content": [{"text": "see "}, {"inlineData": {"mimeType": "image/png", "data": "AAAA"}}],
            "toolCalls": [{"id": "c", "name": "n", "args": {}, "status": "success", "timestamp": "t"}]}),
        json!({"id": "i1", "timestamp": "t", "type": "error", "content": "Quota exceeded"}),
        json!({"id": "g1", "timestamp": "t", "type": "gemini", "content": [{"text": "a"}, {"functionCall": {"name": "ls"}}, {"thought": true, "text": "x"}]}),
    ]);
    let entries = fold(&data).unwrap().entries();
    let shown: Vec<_> = entries
        .iter()
        .map(|entry| {
            (
                entry.role.clone().unwrap(),
                texts(entry)
                    .into_iter()
                    .map(|(_, text)| text)
                    .collect::<String>(),
                entry.blocks.len(),
            )
        })
        .collect();
    assert_eq!(
        shown,
        [
            ("user".to_owned(), "@a.txt".to_owned(), 1),
            // A user message's tool calls are never shown.
            (
                "user".to_owned(),
                "see [Image: image/png, 0.0 KB]".to_owned(),
                1
            ),
            ("system".to_owned(), "Quota exceeded".to_owned(), 1),
            (
                "assistant".to_owned(),
                "a[Function Call: ls][Thought: true]".to_owned(),
                1
            ),
        ]
    );
}

/// Captured, not synthetic: a real Gemini 0.68.0 session against a mock
/// provider, whose `invoke_agent` ran `codebase_investigator`; the child's
/// own record was `chats/b9ea73fe-…/ae28e145-21fb-4335-bf70-eed21c8ab79c.jsonl`.
const SUBAGENT_RUN: &[u8] = include_bytes!("subagent.jsonl");

fn children(data: &[u8]) -> Vec<Subagent> {
    fold(data)
        .unwrap()
        .entries()
        .iter()
        .flat_map(|entry| &entry.blocks)
        .filter_map(|block| match block {
            Block::ToolResult { subagents, .. } => Some(subagents.clone()),
            _ => None,
        })
        .flatten()
        .collect()
}

#[test]
fn a_finished_invoke_agent_names_its_child_session_with_its_report() {
    assert_eq!(
        children(SUBAGENT_RUN),
        vec![Subagent {
            id: "ae28e145-21fb-4335-bf70-eed21c8ab79c".into(),
            agent: "codebase_investigator".into(),
            agent_type: None,
            status: "completed",
            task: Some("Investigate the work dir".into()),
            stage: None,
            output: Some(
                "{\n  \"SummaryOfFindings\": \"mock findings\",\n  \"ExplorationTrace\": [\n    \"listed the work dir\"\n  ],\n  \"RelevantLocations\": []\n}".into()
            ),
            error: None,
            duration_ms: None,
            requests: None,
            exit_code: None,
        }]
    );
}

#[test]
fn a_child_left_running_ends_as_its_call_did_and_a_failure_keeps_its_error() {
    let call = |id: &str, status: &str, state: &str, activity: Value| {
        json!({"id": id, "name": "invoke_agent", "args": {"agent_name": "x", "prompt": "p"}, "status": status,
               "timestamp": "t", "resultDisplay": {"isSubagentProgress": true, "agentName": "x", "state": state,
               "recentActivity": activity}})
    };
    let turn = json!({"id": "m2", "timestamp": "2026-11-12T10:00:02.000Z", "type": "gemini", "content": "", "toolCalls": [
        call("c1", "cancelled", "running", json!([])),
        call("c2", "success", "error", json!([{"id": "a", "type": "thought", "content": "Error: quota", "status": "error"}])),
        call("c3", "success", "paused", json!([])),
        {"id": "c4", "name": "read_file", "status": "success", "timestamp": "t", "resultDisplay": {"agentName": "x", "state": "completed"}}
    ]});
    let found = children(&jsonl(&[header(), user("m1", "go"), turn]));
    let shown: Vec<_> = found
        .iter()
        .map(|child| (child.id.as_str(), child.status, child.error.as_deref()))
        .collect();
    assert_eq!(
        shown,
        vec![
            ("c1", "cancelled", None),
            ("c2", "failed", Some("Error: quota"))
        ]
    );
}

/// Captured, not synthetic: one `invoke_agent` run read while its child
/// had listed the work dir (`mid`, the session not yet naming it) and
/// after it ended (`end`, the call recorded with its `agentId`).
const LIVE_MID: &[u8] = include_bytes!("live-mid.jsonl");
const LIVE_MID_CHILD: &[u8] = include_bytes!("live-mid-child.jsonl");
const LIVE_END: &[u8] = include_bytes!("live-end.jsonl");
const LIVE_END_CHILD: &[u8] = include_bytes!("live-end-child.jsonl");
const CHILD: &str = "4aa4baf8-9311-46fd-a863-4041ff3538f8";

/// Captured, not synthetic: one session whose turn ran children A (3 s)
/// and B (30 s) in parallel, read while B ran (`two-mid`, A's own record
/// already ended), then a turn whose child C was cancelled with Esc, read
/// while C ran (`cancel-mid`) and after the cancel (`cancel`).
const TWO_MID: &[u8] = include_bytes!("live-two-mid.jsonl");
const TWO_A: &[u8] = include_bytes!("live-two-a.jsonl");
const TWO_B_MID: &[u8] = include_bytes!("live-two-b-mid.jsonl");
const TWO_B: &[u8] = include_bytes!("live-two-b.jsonl");
const CANCEL_MID: &[u8] = include_bytes!("live-cancel-mid.jsonl");
const CANCEL_C_MID: &[u8] = include_bytes!("live-cancel-c-mid.jsonl");
const CANCEL: &[u8] = include_bytes!("live-cancel.jsonl");
const CANCEL_C: &[u8] = include_bytes!("live-cancel-c.jsonl");
const A: &str = "9d96659a-c4ad-4f11-967a-43e1f0e669c2";
const B: &str = "3d4adb24-5893-4caf-a9a5-e60663c1509a";
const C: &str = "ba69ae95-57bb-4606-8499-d0edc47d0655";

fn running(parent: &[u8], children: &[(&str, &[u8])]) -> Vec<Subagent> {
    let conversation = fold(parent).unwrap();
    let entry = conversation.running(children);
    entry
        .iter()
        .flat_map(|entry| &entry.blocks)
        .flat_map(|block| match block {
            Block::ToolResult { subagents, .. } => subagents.clone(),
            _ => Vec::new(),
        })
        .collect()
}

fn rows(found: &[Subagent]) -> Vec<(&str, &str, Option<&str>)> {
    found
        .iter()
        .map(|child| (child.id.as_str(), child.status, child.stage.as_deref()))
        .collect()
}

#[test]
fn a_child_the_session_has_not_recorded_yet_shows_running_from_its_own_record() {
    let live = running(LIVE_MID, &[(CHILD, LIVE_MID_CHILD)]);
    assert_eq!(rows(&live), vec![(CHILD, "running", Some("ReadFolder"))]);
    assert!(
        live[0]
            .task
            .as_deref()
            .unwrap()
            .contains("Investigate the work dir")
    );
    assert!(children(LIVE_MID).is_empty());
}

#[test]
fn once_the_session_records_the_child_only_its_finished_row_shows() {
    assert!(running(LIVE_END, &[(CHILD, LIVE_END_CHILD)]).is_empty());
    let done: Vec<_> = children(LIVE_END)
        .iter()
        .map(|child| (child.id.clone(), child.status))
        .collect();
    assert_eq!(done, vec![(CHILD.to_owned(), "completed")]);
}

#[test]
fn a_sibling_that_finished_reads_done_from_its_own_record_while_the_other_runs() {
    let live = running(TWO_MID, &[(A, TWO_A), (B, TWO_B_MID)]);
    assert_eq!(
        rows(&live),
        vec![(A, "completed", None), (B, "running", Some("ReadFolder"))]
    );
    assert!(children(TWO_MID).is_empty());
}

#[test]
fn esc_cancels_the_running_child_into_the_sessions_cancelled_call() {
    // Mid-cancel: the earlier batch is recorded by agentId, only C runs.
    let live = running(CANCEL_MID, &[(A, TWO_A), (B, TWO_B), (C, CANCEL_C_MID)]);
    assert_eq!(rows(&live), vec![(C, "running", Some("ReadFolder"))]);

    // After Esc the session records the call cancelled with no agentId,
    // stamped after C started: the live row is gone, the call's own row stays.
    assert!(running(CANCEL, &[(A, TWO_A), (B, TWO_B), (C, CANCEL_C)]).is_empty());
    let ended: Vec<_> = children(CANCEL)
        .iter()
        .map(|child| (child.id.clone(), child.status))
        .collect();
    assert_eq!(
        ended,
        vec![
            (A.to_owned(), "completed"),
            (B.to_owned(), "completed"),
            (
                "invoke_agent__invoke_agent_1794526978849_0".to_owned(),
                "cancelled"
            )
        ]
    );
}

#[test]
fn a_child_ends_when_the_session_moves_on_and_never_from_another_project() {
    let at = |ts: &str| json!({"id": "late", "timestamp": ts, "type": "user", "content": [{"text": "x"}]});
    let with = |record: Value| {
        let mut data = LIVE_MID.to_vec();
        data.extend(jsonl(&[record]));
        data
    };
    // Recorded before the child started: still running.
    assert_eq!(
        running(
            &with(at("2026-11-12T23:15:48.000Z")),
            &[(CHILD, LIVE_MID_CHILD)]
        )
        .len(),
        1
    );
    assert!(
        running(
            &with(at("2026-11-12T23:16:00.000Z")),
            &[(CHILD, LIVE_MID_CHILD)]
        )
        .is_empty()
    );
    let foreign = String::from_utf8(LIVE_MID_CHILD.to_vec())
        .unwrap()
        .replacen("\"projectHash\":\"", "\"projectHash\":\"0", 1);
    assert!(running(LIVE_MID, &[(CHILD, foreign.as_bytes())]).is_empty());
}
