package io.github.erikfrish.herdline.core

import org.json.JSONArray
import org.json.JSONObject

/** One running agent and its reported session reference, never shared across programs. */
data class TranscriptIdentity(
    val paneId: String, val terminalId: String, val pid: Long?, val path: String,
    val agent: String = "omp", val source: String = "herdr:omp", val kind: String = "path",
) {
    fun toJson(): JSONObject = JSONObject()
        .put("pane_id", paneId)
        .put("terminal_id", terminalId)
        .put("pid", pid ?: JSONObject.NULL)
        .put("path", path)
        .put("agent", agent)
        .put("source", source)
        .put("kind", kind)

    companion object {
        fun parse(json: JSONObject) = TranscriptIdentity(
            paneId = json.getString("pane_id"),
            terminalId = json.getString("terminal_id"),
            pid = if (json.isNull("pid")) null else json.getLong("pid"),
            path = json.getString("path"),
            agent = json.optString("agent", "omp"),
            source = json.optString("source", "herdr:omp"),
            kind = json.optString("kind", "path"),
        )
    }
}

/** Chat reads the existing session of these agent kinds; other terminals stay Terminal. */
fun isChatAgent(agent: String?): Boolean = agent?.lowercase() in CHAT_AGENTS
private val CHAT_AGENTS = setOf("omp", "pi", "opencode", "codex", "claude")

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

/** Where an image's bytes are; the bytes themselves stay on the host until a media read. */
sealed interface ImageSource {
    /** Base64 in the line; [bytes] is the decoded size its length claims. */
    data class Inline(val bytes: Long) : ImageSource
    /** A file in OMP's blob store, named by this SHA-256 (64 lower-case hex). */
    data class Blob(val hash: String) : ImageSource
    /** No data, a reference that is no valid blob, or a kind this app does not know. */
    data object Unreadable : ImageSource

    companion object {
        private val HASH = Regex("[0-9a-f]{64}")

        fun parse(json: JSONObject?): ImageSource = when (json?.optString("kind")) {
            "inline" -> json.optLongOrNull("bytes")?.let(::Inline) ?: Unreadable
            "blob" -> json.optString("hash").takeIf(HASH::matches)?.let(::Blob) ?: Unreadable
            else -> Unreadable
        }
    }
}

sealed interface TranscriptBlock {
    data class Text(val text: String, val truncated: Boolean) : TranscriptBlock
    data class Thinking(val text: String, val truncated: Boolean) : TranscriptBlock
    data class ToolCall(val callId: String, val name: String, val args: String, val intent: String?, val truncated: Boolean) : TranscriptBlock
    data class ToolResult(
        val callId: String,
        val name: String,
        val text: String,
        val isError: Boolean,
        val images: List<Image>,
        val truncated: Boolean,
        /** The command's exit code, for tools that report one. */
        val exitCode: Long? = null,
        val answers: List<ChatFormAnswer> = emptyList(),
        val timedOut: Boolean = false,
        /** The children a `task` call spawned and this result lists (wire `subagents`, bounded 64). */
        val subagents: List<TranscriptSubagent> = emptyList(),
    ) : TranscriptBlock
    /** An image at [part] of its message's content. */
    data class Image(val part: Int, val source: ImageSource) : TranscriptBlock

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
                images = json.optJSONArray("images").objects().map(::image),
                truncated = json.optBoolean("truncated"),
                exitCode = json.optLongOrNull("exit_code"),
                answers = json.optJSONArray("answers").objects().map(ChatFormAnswer::parse),
                timedOut = json.optBoolean("timed_out"),
                subagents = json.optJSONArray("subagents").objects().mapNotNull(TranscriptSubagent.Companion::parse).take(MAX_SUBAGENTS),
            )
            "image" -> image(json)
            else -> null
        }

        private fun image(json: JSONObject) = Image(json.optInt("part"), ImageSource.parse(json.optJSONObject("source")))
    }
}

/** How many children one call's snapshot and one entry's ends may carry (wire bounds). */
const val MAX_SUBAGENTS = 64

/** How many chars of one child's output the wire may carry. */
const val MAX_SUBAGENT_OUTPUT_CHARS = 4096

/** The terminal statuses an end may carry; a running child is never an end. */
private val END_STATUSES = setOf("completed", "failed", "aborted", "cancelled")

/**
 * One child of a `task` call as the tool result snapshot lists it (wire
 * `subagents[]`, see `ChatSubagents.kt`'s canvas contract). [id] is its
 * exact child id; every other field may be missing.
 */
data class TranscriptSubagent(
    val id: String,
    val agent: String? = null,
    val type: String? = null,
    val status: String,
    val task: String? = null,
    val stage: String? = null,
    val output: String? = null,
    val error: String? = null,
    val durationMs: Long? = null,
    val requests: Int? = null,
    val exitCode: Int? = null,
) {
    companion object {
        fun parse(json: JSONObject): TranscriptSubagent? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val status = json.optString("status").takeIf { it.isNotBlank() } ?: return null
            return TranscriptSubagent(
                id = id,
                agent = json.optString("agent").takeIf { it.isNotBlank() },
                type = json.optString("type").takeIf { it.isNotBlank() },
                status = status,
                task = json.optString("task").takeIf { it.isNotBlank() },
                stage = json.optString("stage").takeIf { it.isNotBlank() },
                output = json.optString("output").takeIf { it.isNotBlank() }?.take(MAX_SUBAGENT_OUTPUT_CHARS),
                error = json.optString("error").takeIf { it.isNotBlank() },
                durationMs = json.optLongOrNull("durationMs"),
                requests = json.optLongOrNull("requests")?.toInt(),
                exitCode = json.optLongOrNull("exitCode")?.toInt(),
            )
        }
    }
}

/**
 * A settled async job's end, as [TranscriptEntry.subagentEnds] records it
 * (`{id,status,durationMs}`). [id] matches a child's exact id; an unknown
 * status is not parsed at all ([parse] returns null).
 */
data class TranscriptSubagentEnd(
    val id: String,
    val status: String,
    val durationMs: Long? = null,
) {
    companion object {
        fun parse(json: JSONObject): TranscriptSubagentEnd? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            // Terminal only: an end never carries a running child, and a
            // status outside the terminal set is not read at all.
            val status = json.optString("status").takeIf { it in END_STATUSES } ?: return null
            return TranscriptSubagentEnd(id, status, json.optLongOrNull("durationMs"))
        }
    }
}

/**
 * How OMP recorded a user message that reached a running agent. Both are
 * already in the history; neither says anything about messages still waiting.
 */
enum class TranscriptSteer {
    /** `steering: true`: injected mid-turn. */
    Steering,
    /** `liveSteered: true`: taken into the active response. */
    Live,
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
    /** Where the line ends, after its newline. */
    val end: Long,
    val kind: TranscriptKind,
    val role: String?,
    val blocks: List<TranscriptBlock>,
    val timestamp: Long? = null,
    val usage: TranscriptUsage? = null,
    val stopReason: String? = null,
    val steer: TranscriptSteer? = null,
    /**
     * The settled ends a persisted custom/async-result entry carries (wire
     * `subagent_ends`): each `{id,status,durationMs}`, matched to children
     * by exact id only.
     */
    val subagentEnds: List<TranscriptSubagentEnd> = emptyList(),
) {
    companion object {
        fun parse(json: JSONObject) = TranscriptEntry(
            id = json.optString("id").takeUnless { json.isNull("id") || it.isEmpty() },
            parentId = json.optString("parent_id").takeUnless { json.isNull("parent_id") || it.isEmpty() },
            offset = json.getLong("offset"),
            end = json.getLong("end"),
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
            steer = when (json.optString("steer")) {
                "steering" -> TranscriptSteer.Steering
                "live" -> TranscriptSteer.Live
                else -> null
            },
            subagentEnds = json.optJSONArray("subagent_ends").objects().mapNotNull(TranscriptSubagentEnd.Companion::parse).take(MAX_SUBAGENTS),
        )
    }
}

/** One read: entries from bytes [start, end) of a file [size] long. */
data class TranscriptPage(
    val identity: TranscriptIdentity,
    val reset: Boolean,
    val size: Long,
    val start: Long,
    val end: Long,
    val entries: List<TranscriptEntry>,
    val hasOlder: Boolean = start > 0,
) {
    companion object {
        fun parse(text: String): TranscriptPage {
            val json = JSONObject(text)
            return TranscriptPage(
                identity = TranscriptIdentity.parse(json.getJSONObject("identity")),
                reset = json.optBoolean("reset"),
                size = json.getLong("size"),
                start = json.getLong("start"),
                end = json.getLong("end"),
                entries = json.optJSONArray("entries").objects().map(TranscriptEntry::parse),
                hasOlder = json.optBoolean("has_older", json.getLong("start") > 0),
            )
        }
    }
}

/** The most image bytes one media read decodes (native `MAX_IMAGE`). */
const val MAX_IMAGE_BYTES = 20L * 1024 * 1024

/**
 * One image a transcript line names: the line [offset, end) of entry
 * [entry] (null for entries without an ID), the image's [part] in its
 * content, and where its bytes are. For a tool's image the line is the
 * tool result's. [key] is unique within one session file.
 */
data class TranscriptImage(val entry: String?, val offset: Long, val end: Long, val part: Int, val source: ImageSource) {
    val key: String get() = "${entry.orEmpty()}@$offset-$end:$part"
}

/** A media read of [image] from the instance [expect], through `Native.transcript`. */
fun mediaRequest(pane: String, expect: TranscriptIdentity, image: TranscriptImage): JSONObject = JSONObject()
    .put("pane", pane)
    .put("expect", expect.toJson())
    .put(
        "media",
        JSONObject()
            .put("v", MEDIA_VERSION)
            .put("entry", image.entry ?: JSONObject.NULL)
            .put("offset", image.offset)
            .put("end", image.end)
            .put("part", image.part)
            .apply { (image.source as? ImageSource.Blob)?.let { put("blob", it.hash) } },
    )

private const val MEDIA_VERSION = 1

/** Why the host shows no image. None is retried by the core. */
enum class MediaRefusal {
    InstanceChanged, Moved, Missing, Unconfined, UnknownStore, Corrupt, TooLarge, Unsupported, Version;

    companion object {
        /** A refusal this app does not know comes from a newer core: [Version]. */
        fun parse(value: String): MediaRefusal = when (value) {
            "instance_changed" -> InstanceChanged
            "moved" -> Moved
            "missing" -> Missing
            "unconfined" -> Unconfined
            "unknown_store" -> UnknownStore
            "corrupt" -> Corrupt
            "too_large" -> TooLarge
            "unsupported" -> Unsupported
            else -> Version
        }
    }
}

/** A media read's answer: one image's bytes as base64 with the mime read from them, or why there are none. */
sealed interface MediaAnswer {
    data class Image(val mime: String, val data: String) : MediaAnswer
    data class Refused(val refusal: MediaRefusal) : MediaAnswer

    companion object {
        fun parse(text: String): MediaAnswer {
            val media = JSONObject(text).getJSONObject("media")
            return when {
                media.optInt("v") != MEDIA_VERSION -> Refused(MediaRefusal.Version)
                media.has("error") -> Refused(MediaRefusal.parse(media.optString("error")))
                else -> Image(media.getString("mime"), media.getString("data"))
            }
        }
    }
}

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.optLongOrNull(name: String): Long? = if (has(name) && !isNull(name)) optLong(name) else null
