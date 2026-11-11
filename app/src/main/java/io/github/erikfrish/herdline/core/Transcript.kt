package io.github.erikfrish.herdline.core

import org.json.JSONArray
import org.json.JSONObject

/** The agent a transcript belongs to: one pane, terminal, OMP process and session file. */
data class TranscriptIdentity(val paneId: String, val terminalId: String, val pid: Long?, val path: String) {
    fun toJson(): JSONObject = JSONObject()
        .put("pane_id", paneId)
        .put("terminal_id", terminalId)
        .put("pid", pid ?: JSONObject.NULL)
        .put("path", path)

    companion object {
        fun parse(json: JSONObject) = TranscriptIdentity(
            paneId = json.getString("pane_id"),
            terminalId = json.getString("terminal_id"),
            pid = if (json.isNull("pid")) null else json.getLong("pid"),
            path = json.getString("path"),
        )
    }
}

/** Which bytes of the transcript to read next (see `transcript.rs`). */
sealed interface TranscriptRead {
    data object Tail : TranscriptRead
    data class After(val offset: Long) : TranscriptRead
    data class Before(val offset: Long) : TranscriptRead

    fun toJson(): JSONObject = when (this) {
        Tail -> JSONObject().put("mode", "tail")
        is After -> JSONObject().put("mode", "after").put("offset", offset)
        is Before -> JSONObject().put("mode", "before").put("offset", offset)
    }
}

fun transcriptRequest(pane: String, expect: TranscriptIdentity?, read: TranscriptRead): JSONObject = JSONObject()
    .put("pane", pane)
    .put("expect", expect?.toJson() ?: JSONObject.NULL)
    .put("read", read.toJson())

sealed interface TranscriptBlock {
    data class Text(val text: String, val truncated: Boolean) : TranscriptBlock
    data class Thinking(val text: String, val truncated: Boolean) : TranscriptBlock
    data class ToolCall(val callId: String, val name: String, val args: String, val intent: String?, val truncated: Boolean) : TranscriptBlock
    data class ToolResult(
        val callId: String,
        val name: String,
        val text: String,
        val isError: Boolean,
        val images: Int,
        val truncated: Boolean,
        /** The command's exit code, for tools that report one. */
        val exitCode: Long? = null,
    ) : TranscriptBlock
    data object Image : TranscriptBlock

    companion object {
        fun parse(json: JSONObject): TranscriptBlock? = when (json.optString("type")) {
            "text" -> Text(json.getString("text"), json.optBoolean("truncated"))
            "thinking" -> Thinking(json.getString("text"), json.optBoolean("truncated"))
            "tool_call" -> ToolCall(
                callId = json.optString("call_id"),
                name = json.optString("name"),
                args = json.optString("args"),
                intent = json.optString("intent").takeUnless { json.isNull("intent") || it.isEmpty() },
                truncated = json.optBoolean("truncated"),
            )
            "tool_result" -> ToolResult(
                callId = json.optString("call_id"),
                name = json.optString("name"),
                text = json.optString("text"),
                isError = json.optBoolean("is_error"),
                images = json.optInt("images"),
                truncated = json.optBoolean("truncated"),
                exitCode = json.optLongOrNull("exit_code"),
            )
            "image" -> Image
            else -> null
        }
    }
}

enum class TranscriptKind { Message, Compaction, Other }

/** An assistant message's tokens and OMP's own cost estimate in USD, null when OMP has no price. */
data class TranscriptUsage(val input: Long, val output: Long, val totalTokens: Long, val cost: Double?) {
    companion object {
        fun parse(json: JSONObject?): TranscriptUsage? = json?.let {
            TranscriptUsage(
                input = it.optLong("input"),
                output = it.optLong("output"),
                totalTokens = it.optLong("total_tokens"),
                cost = if (it.has("cost") && !it.isNull("cost")) it.optDouble("cost") else null,
            )
        }
    }
}

/**
 * One line of the session file; [offset] is where it starts, unique within
 * one file. [timestamp] (epoch milliseconds), [usage] and [stopReason] are
 * set only on messages that carry them.
 */
data class TranscriptEntry(
    val id: String?,
    val parentId: String?,
    val offset: Long,
    val kind: TranscriptKind,
    val role: String?,
    val blocks: List<TranscriptBlock>,
    val timestamp: Long? = null,
    val usage: TranscriptUsage? = null,
    val stopReason: String? = null,
) {
    companion object {
        fun parse(json: JSONObject) = TranscriptEntry(
            id = json.optString("id").takeUnless { json.isNull("id") || it.isEmpty() },
            parentId = json.optString("parent_id").takeUnless { json.isNull("parent_id") || it.isEmpty() },
            offset = json.getLong("offset"),
            kind = when (json.optString("kind")) {
                "message" -> TranscriptKind.Message
                "compaction" -> TranscriptKind.Compaction
                else -> TranscriptKind.Other
            },
            role = json.optString("role").takeUnless { json.isNull("role") || it.isEmpty() },
            blocks = json.optJSONArray("blocks").objects().mapNotNull(TranscriptBlock::parse),
            timestamp = json.optLongOrNull("timestamp"),
            usage = TranscriptUsage.parse(json.optJSONObject("usage")),
            stopReason = json.optString("stop_reason").takeUnless { json.isNull("stop_reason") || it.isEmpty() },
        )
    }
}

/** One read: entries from bytes [start, end) of a file [size] long. */
data class TranscriptPage(
    val identity: TranscriptIdentity,
    val status: AgentStatus,
    val reset: Boolean,
    val size: Long,
    val start: Long,
    val end: Long,
    val entries: List<TranscriptEntry>,
) {
    companion object {
        fun parse(text: String): TranscriptPage {
            val json = JSONObject(text)
            return TranscriptPage(
                identity = TranscriptIdentity.parse(json.getJSONObject("identity")),
                status = AgentStatus.parse(json.optString("status")),
                reset = json.optBoolean("reset"),
                size = json.getLong("size"),
                start = json.getLong("start"),
                end = json.getLong("end"),
                entries = json.optJSONArray("entries").objects().map(TranscriptEntry::parse),
            )
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
