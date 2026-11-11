package io.github.erikfrish.herdline.core

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/*
 * Chat's direct line to an OMP instance through the `herdline-ingress`
 * extension (host/omp/herdline-ingress.ts, docs/decisions.md). The native
 * core finds the endpoint on the host and speaks its wire; this file reads
 * the core's answers. One rule runs through all of it: only [IngressSend.Absent]
 * (this instance has no endpoint, known before anything was sent) lets the
 * caller fall back to typing into the pane. An endpoint that is there and
 * cannot be used refuses, and once a request may have reached it, doubt is
 * [IngressSend.Uncertain]: nothing is sent again.
 */

/** One entry waiting in OMP's queue; [requestId] is null for one the desktop queued. */
data class IngressQueued(val requestId: String?, val text: String, val queue: String) {
    val steering: Boolean get() = queue == QUEUE_STEERING
}

/**
 * One live child of a `task` call the endpoint's state carries: the
 * `tool_result` `subagents[]` wire shape (bounded 64) plus [parentCallId],
 * the id of the `task` tool call that spawned it. An older extension that
 * sends no `subagents` simply has none; nothing is invented.
 */
data class IngressSubagent(val parentCallId: String?, val child: TranscriptSubagent)

/** OMP's queue as the extension reports it, and whether the agent is idle. */
data class IngressState(val idle: Boolean, val queued: List<IngressQueued>, val subagents: List<IngressSubagent> = emptyList())

/** What one send through the endpoint amounts to. */
sealed interface IngressSend {
    /** This instance publishes no endpoint: nothing was sent, and the pane route may be used. */
    data object Absent : IngressSend

    /** OMP holds the request durably: queued, admitted as the prompt, or an earlier send of it was. */
    data class Accepted(val id: String, val outcome: String, val state: IngressState?) : IngressSend

    /**
     * Nothing was queued. [reason] is the extension's or the native core's
     * word; an endpoint that exists and could not be used is
     * [REASON_UNSUPPORTED] or [REASON_UNUSABLE] with the core's [detail].
     */
    data class Refused(val reason: String, val state: IngressState? = null, val detail: String? = null) : IngressSend

    /** The request may have reached OMP and no answer said so: keep the draft, never resend, check [id]. */
    data class Uncertain(val id: String) : IngressSend
}

/** Whether Chat can see this instance's queue, and what it holds. */
sealed interface IngressProbe {
    data object Absent : IngressProbe
    data class Unsupported(val missing: List<String>) : IngressProbe
    data class Unusable(val reason: String) : IngressProbe
    data class Live(val state: IngressState) : IngressProbe
}

const val QUEUE_STEERING = "steering"
const val QUEUE_FOLLOW_UP = "followUp"
const val REASON_UNSUPPORTED = "endpoint_unsupported"
const val REASON_UNUSABLE = "endpoint_unusable"

internal fun ingressRequest(op: String, target: Connection.ComposerTarget, id: String): JSONObject = JSONObject()
    .put("op", op)
    .put("node", target.nodeId)
    .put("session", target.session)
    .put("boot", target.boot)
    .put("pane", target.pane)
    .put("agent", target.agent ?: JSONObject.NULL)
    .put("instance", requireNotNull(target.instance).toJson())
    .put("id", id)

/**
 * What an ingress send [call] that was made amounts to. Only a refusal with
 * its reason and a reply that no endpoint is there or usable say nothing
 * left; a call that throws or cannot be read may follow a request that
 * reached the socket, so it is uncertain.
 */
internal fun ingressSendOutcome(id: String, call: () -> String): IngressSend {
    val reply = ingressReply(call) ?: return IngressSend.Uncertain(id)
    val reason = reply.optString("reason")
    return when (reply.optString("endpoint")) {
        // The native core refused before looking for the endpoint.
        "" -> if (reply.optString("outcome") == "refused" && reason.isNotEmpty()) {
            IngressSend.Refused(reason)
        } else {
            IngressSend.Uncertain(id)
        }
        "absent" -> IngressSend.Absent
        "unsupported" -> IngressSend.Refused(REASON_UNSUPPORTED, detail = missing(reply).joinToString(", "))
        "unusable" -> IngressSend.Refused(REASON_UNUSABLE, detail = reason.ifEmpty { null })
        "live" -> liveOutcome(id, reply)
        else -> IngressSend.Uncertain(id)
    }
}

/**
 * What a status check of request [id] says. A check sends nothing, so a
 * check that fails, or an endpoint gone meanwhile, leaves the request as it
 * was: a failure, never a refusal.
 */
internal fun ingressStatusOutcome(id: String, call: () -> String): Result<IngressSend> {
    val reply = ingressReply(call) ?: return Result.failure(IOException("no answer"))
    return when (val endpoint = reply.optString("endpoint")) {
        "live" -> Result.success(liveOutcome(id, reply))
        else -> Result.failure(IOException("endpoint ${endpoint.ifEmpty { "unknown" }}"))
    }
}

internal fun ingressProbe(call: () -> String): Result<IngressProbe> {
    val reply = ingressReply(call) ?: return Result.failure(IOException("no answer"))
    return when (reply.optString("endpoint")) {
        "absent" -> Result.success(IngressProbe.Absent)
        "unsupported" -> Result.success(IngressProbe.Unsupported(missing(reply)))
        "unusable" -> Result.success(IngressProbe.Unusable(reply.optString("reason").ifEmpty { "unknown" }))
        "live" -> parseIngressState(reply.optJSONObject("state"))
            ?.let { Result.success(IngressProbe.Live(it)) }
            ?: Result.failure(IOException("no queue"))
        else -> Result.failure(IOException("no endpoint answer"))
    }
}

internal fun ingressWithdrawn(call: () -> String): Result<Boolean> {
    val reply = ingressReply(call) ?: return Result.failure(IOException("no answer"))
    if (reply.optString("endpoint") != "live" || !reply.has("withdrawn")) {
        return Result.failure(IOException("not withdrawn"))
    }
    return Result.success(reply.optBoolean("withdrawn"))
}

internal fun ingressDialogsOutcome(call: () -> String): Result<JSONArray> {
    val reply = ingressReply(call) ?: return Result.failure(IOException("no answer"))
    val dialogs = reply.optJSONArray("dialogs")
    if (reply.optString("endpoint") != "live" || dialogs == null) {
        return Result.failure(IOException("endpoint ${reply.optString("endpoint").ifEmpty { "unknown" }}"))
    }
    return Result.success(dialogs)
}

/** OMP's results for a dialog submit; only these say what became of the answer. */
private val SUBMIT_RESULTS = setOf("submitted", "not_open", "invalid", "kind_mismatch")

internal fun ingressSubmitOutcome(dialogId: String, call: () -> String): Result<String> {
    val reply = ingressReply(call) ?: return Result.failure(IOException("no answer"))
    val result = reply.optString("result")
    if (reply.optString("endpoint") != "live" || reply.optString("dialogId") != dialogId || result !in SUBMIT_RESULTS) {
        return Result.failure(IOException("no submit result"))
    }
    return Result.success(result)
}

private fun liveOutcome(id: String, reply: JSONObject): IngressSend {
    val state = parseIngressState(reply.optJSONObject("state"))
    val reason = reply.optString("reason")
    return when (val outcome = reply.optString("outcome")) {
        "accepted", "admitted", "replayed" -> IngressSend.Accepted(id, outcome, state)
        "refused" -> if (reason.isEmpty()) IngressSend.Uncertain(id) else IngressSend.Refused(reason, state)
        else -> IngressSend.Uncertain(id)
    }
}

internal fun parseIngressState(json: JSONObject?): IngressState? {
    json ?: return null
    if (!json.has("idle")) return null
    val queued = json.optJSONArray("queued") ?: JSONArray()
    val subagents = json.optJSONArray("subagents")
    return IngressState(
        idle = json.optBoolean("idle"),
        queued = (0 until queued.length()).mapNotNull { at ->
            val entry = queued.optJSONObject(at) ?: return@mapNotNull null
            val queue = entry.optString("queue").takeIf { it == QUEUE_STEERING || it == QUEUE_FOLLOW_UP }
                ?: return@mapNotNull null
            IngressQueued(
                requestId = if (entry.isNull("requestId")) null else entry.optString("requestId").ifEmpty { null },
                text = entry.optString("text"),
                queue = queue,
            )
        },
        subagents = (0 until (subagents?.length() ?: 0).coerceAtMost(MAX_SUBAGENTS)).mapNotNull { at ->
            val entry = subagents?.optJSONObject(at) ?: return@mapNotNull null
            val child = TranscriptSubagent.parse(entry) ?: return@mapNotNull null
            IngressSubagent(entry.optString("parentCallId").takeIf { it.isNotBlank() }, child)
        },
    )
}

private fun missing(reply: JSONObject): List<String> {
    val missing = reply.optJSONArray("missing") ?: return emptyList()
    return (0 until missing.length()).mapNotNull { missing.optString(it).ifEmpty { null } }
}

private fun ingressReply(call: () -> String): JSONObject? = try {
    JSONObject(call())
} catch (e: IOException) {
    null
} catch (e: JSONException) {
    null
}
