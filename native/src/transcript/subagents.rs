//! Display-only subagent snapshots: OMP task details and exact-id async
//! completions, and Gemini's `invoke_agent` progress and its running
//! children's own records. No status is inferred from output text.

use super::cap;
use crate::herdr_setup::plain_text;
use serde::Serialize;
use serde_json::Value;

const MAX_CHILDREN: usize = 64;
pub(super) const OUTPUT_LIMIT: usize = 4096;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Subagent {
    pub id: String,
    pub agent: String,
    #[serde(rename = "type", skip_serializing_if = "Option::is_none")]
    pub agent_type: Option<String>,
    pub status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub task: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub stage: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub requests: Option<u64>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub exit_code: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Completion {
    pub id: String,
    pub status: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
}

pub(super) fn text(value: &Value, key: &str, limit: usize) -> Option<String> {
    value[key].as_str().and_then(|s| clean(s, limit))
}

/// [`text`] for a string already in hand: none when blank.
pub(super) fn clean(s: &str, limit: usize) -> Option<String> {
    if s.trim().is_empty() {
        return None;
    }
    // Bound before stripping terminal escapes, so untrusted output cannot
    // make the display sanitizer allocate an entire task report.
    let (bounded, _) = cap(s, limit);
    Some(plain_text(bounded.as_bytes()))
}

fn status(value: &str) -> Option<&'static str> {
    match value {
        "pending" => Some("pending"),
        "running" => Some("running"),
        "completed" => Some("completed"),
        "failed" => Some("failed"),
        "aborted" => Some("aborted"),
        "cancelled" => Some("cancelled"),
        _ => None,
    }
}

fn child(value: &Value, settled: bool) -> Option<Subagent> {
    // IDs stay exact: truncating one could merge two unrelated children.
    let id = value["id"]
        .as_str()
        .filter(|s| !s.trim().is_empty() && s.len() <= 256)?;
    let exit_code = value["exitCode"].as_i64();
    let error = text(value, "error", OUTPUT_LIMIT);
    let state = if settled {
        if value["aborted"] == true {
            "aborted"
        } else if error.is_some() || exit_code.is_some_and(|code| code != 0) {
            "failed"
        } else if exit_code == Some(0) {
            "completed"
        } else {
            return None;
        }
    } else {
        status(value["status"].as_str()?)?
    };
    Some(Subagent {
        id: id.to_owned(),
        agent: id.to_owned(),
        agent_type: text(value, "agent", 128),
        status: state,
        task: text(value, "description", 1024).or_else(|| text(value, "task", 1024)),
        stage: text(value, "stage", 256).or_else(|| text(value, "lastIntent", 256)),
        output: text(value, "output", OUTPUT_LIMIT),
        error,
        duration_ms: value["durationMs"].as_u64(),
        requests: value["requests"].as_u64(),
        exit_code,
    })
}

/// A task tool's persisted details. Final results override progress for the
/// same child; a tool returning an async running snapshot closes nothing.
pub(super) fn snapshot(details: &Value) -> Vec<Subagent> {
    let mut children: Vec<Subagent> = Vec::new();
    for (key, settled) in [("progress", false), ("results", true)] {
        let Some(rows) = details[key].as_array() else {
            continue;
        };
        for row in rows.iter().take(MAX_CHILDREN) {
            let Some(mut next) = child(row, settled) else {
                continue;
            };
            if let Some(previous) = children.iter_mut().find(|old| old.id == next.id) {
                next.task = next.task.or_else(|| previous.task.take());
                next.stage = next.stage.or_else(|| previous.stage.take());
                next.output = next.output.or_else(|| previous.output.take());
                *previous = next;
            } else if children.len() < MAX_CHILDREN {
                children.push(next);
            }
        }
    }
    children
}

/// Only the runtime's typed async-result envelope can settle a child.
pub(super) fn completions(message: &Value) -> Vec<Completion> {
    if (message["role"] != "custom" && message["type"] != "custom_message")
        || message["customType"] != "async-result"
    {
        return Vec::new();
    }
    let Some(jobs) = message["details"]["jobs"].as_array() else {
        return Vec::new();
    };
    jobs.iter()
        .take(MAX_CHILDREN)
        .filter_map(|job| {
            if job["type"] != "task" {
                return None;
            }
            let state = status(job["status"].as_str()?)?;
            if !matches!(state, "completed" | "failed" | "cancelled") {
                return None;
            }
            let id = job["agentId"]
                .as_str()
                .filter(|s| !s.is_empty())
                .or_else(|| job["jobId"].as_str())?;
            if id.trim().is_empty() || id.len() > 256 {
                return None;
            }
            Some(Completion {
                id: id.to_owned(),
                status: state,
                duration_ms: job["durationMs"].as_u64(),
            })
        })
        .collect()
}

#[cfg(test)]
#[allow(clippy::expect_used)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn async_spawn_stays_running_until_its_own_final_result() {
        let children = snapshot(&json!({
            "progress": [
                {"id":"Parser", "agent":"scout", "status":"running", "stage":"reading"},
                {"id":"UI", "agent":"task", "status":"running"}
            ],
            "results": [{"id":"Parser", "agent":"scout", "exitCode":1, "output":"broken"}],
            "async": {"state":"completed", "jobId":"batch", "type":"task"}
        }));
        assert_eq!(children.len(), 2);
        assert_eq!(children[0].status, "failed");
        assert_eq!(children[0].stage.as_deref(), Some("reading"));
        assert_eq!(children[0].output.as_deref(), Some("broken"));
        assert_eq!(children[1].status, "running");
    }

    #[test]
    fn completion_uses_exact_task_agent_id_not_message_text_or_other_jobs() {
        let message = json!({"role":"custom", "customType":"async-result", "content":"UI completed", "details":{"jobs":[
            {"type":"task", "jobId":"job-1", "agentId":"Parser", "status":"completed", "durationMs":42},
            {"type":"bash", "jobId":"UI", "status":"completed"},
            {"type":"task", "jobId":"UI", "status":"running"}
        ]}});
        assert_eq!(
            completions(&message),
            vec![Completion {
                id: "Parser".into(),
                status: "completed",
                duration_ms: Some(42)
            }]
        );
        let mut persisted = message.clone();
        persisted.as_object_mut().expect("object").remove("role");
        persisted["type"] = json!("custom_message");
        assert_eq!(completions(&persisted), completions(&message));
        assert!(
            completions(
                &json!({"role":"user", "customType":"async-result", "details":message["details"]})
            )
            .is_empty()
        );
    }
}
