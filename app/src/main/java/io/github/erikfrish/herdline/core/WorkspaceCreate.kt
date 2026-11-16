package io.github.erikfrish.herdline.core

import org.json.JSONObject
import java.io.IOException

/**
 * One `workspace.create`, with exactly Herdr's own five parameters. [label]
 * and [cwd] are sent verbatim; null leaves them out, so blank input is the
 * caller's to turn into null. [focus] is always sent, since Herdr and
 * herdr-core default it differently; [env] is sent only when not empty.
 */
data class WorkspaceCreate(
    val label: String? = null,
    val cwd: String? = null,
    val sourceWorkspaceId: String? = null,
    val focus: Boolean = true,
    val env: Map<String, String> = emptyMap(),
)

/** Exactly what a definite success answered with, on boot [boot]. */
data class CreatedWorkspace(
    val boot: String,
    val workspace: String,
    val label: String,
    val focused: Boolean,
    val tab: String,
    val pane: String,
    val terminal: String,
) {
    /** The workspace's first tab, for the open-after-create path `tab.create` already uses. */
    fun rootTab(): Connection.CreatedTab = Connection.CreatedTab(boot, tab, pane, terminal)
}

/** Why no [CreatedWorkspace]: only [Refused], [NotReady] and [NotConnected] mean nothing was created. */
sealed interface WorkspaceCreateError {
    /** No view yet, so no boot to tie the answer to; nothing was sent. */
    data object NotReady : WorkspaceCreateError

    /** No live link; nothing was sent. */
    data object NotConnected : WorkspaceCreateError

    /** A definite refusal: nothing was created. [code] and [message] are as the daemon or the app named them. */
    data class Refused(val code: String, val message: String) : WorkspaceCreateError

    /**
     * Sent, but no definite answer was read: the workspace may exist. Never
     * reported as "not created" and never sent again. [reason] is
     * `outcome_unknown` or, for a success missing its IDs, `bad_reply`.
     */
    data class OutcomeUnknown(val reason: String) : WorkspaceCreateError
}

class WorkspaceCreateException(val error: WorkspaceCreateError) : IOException(
    when (error) {
        WorkspaceCreateError.NotReady -> "not_ready"
        WorkspaceCreateError.NotConnected -> "not_connected"
        is WorkspaceCreateError.Refused -> error.message
        is WorkspaceCreateError.OutcomeUnknown -> error.reason
    },
)

/** How long a create waits for its one answer; nothing retries past it. */
private const val WORKSPACE_CREATE_TIMEOUT_MS = 15_000L

/**
 * Creates one workspace with one `workspace.create`, asked once. Failures
 * carry a [WorkspaceCreateException]; nothing here resends on any outcome.
 */
suspend fun Connection.createWorkspace(request: WorkspaceCreate): Result<CreatedWorkspace> {
    val boot = view.value?.boot.orEmpty()
    if (boot.isEmpty()) return failed(WorkspaceCreateError.NotReady)
    return workspaceCreated(awaitRequest("workspace.create", request.params(), WORKSPACE_CREATE_TIMEOUT_MS), boot)
}

internal fun WorkspaceCreate.params(): JSONObject = JSONObject().apply {
    put("focus", focus)
    label?.let { put("label", it) }
    cwd?.let { put("cwd", it) }
    sourceWorkspaceId?.let { put("source_workspace_id", it) }
    if (env.isNotEmpty()) put("env", JSONObject(env))
}

/** The one reply's outcome, for a request sent on [boot]. */
internal fun workspaceCreated(reply: RequestReply, boot: String): Result<CreatedWorkspace> = when (reply) {
    is RequestReply.Answered -> reply.envelope.optJSONObject("error")?.let { failed(answeredError(it)) }
        ?: if (reply.envelope.has("error") && !reply.envelope.isNull("error")) {
            // An error that is no object still says no.
            val said = reply.envelope.optString("error").ifBlank { REQUEST_FAILED }
            failed(WorkspaceCreateError.Refused(said, said))
        } else {
            parseCreatedWorkspace(reply.envelope, boot)
        }
    is RequestReply.Refused -> failed(
        if (reply.reason == "not_connected") WorkspaceCreateError.NotConnected
        else WorkspaceCreateError.Refused(reply.reason, reply.reason),
    )
    is RequestReply.Uncertain -> failed(WorkspaceCreateError.OutcomeUnknown("outcome_unknown"))
}

/**
 * Herdr answers `{"code","message"}`, kept as sent; herdr-core answers
 * `{"kind","reason","message"}`, whose `uncertain` is a link that ended
 * before the edit's answer and so never a refusal.
 */
private fun answeredError(error: JSONObject): WorkspaceCreateError {
    val kind = error.optString("kind")
    if (kind == "uncertain") return WorkspaceCreateError.OutcomeUnknown("outcome_unknown")
    val code = error.takeUnless { it.isNull("code") }?.opt("code")?.toString()?.takeIf { it.isNotEmpty() }
        ?: error.optString("reason").takeIf { kind == "unsupported" && it.isNotEmpty() }
        ?: kind.ifEmpty { REQUEST_FAILED }
    val message = error.optString("message").ifBlank { code }
    return WorkspaceCreateError.Refused(code, message)
}

/** The created IDs in an answered success; a success missing one leaves the outcome unknown. */
internal fun parseCreatedWorkspace(envelope: JSONObject, boot: String): Result<CreatedWorkspace> {
    val result = envelope.optJSONObject("result") ?: return failed(WorkspaceCreateError.OutcomeUnknown(BAD_REPLY))
    val workspace = result.optJSONObject("workspace")
    val root = result.optJSONObject("root_pane")
    val created = CreatedWorkspace(
        boot = boot,
        workspace = workspace?.optString("workspace_id").orEmpty(),
        label = workspace?.optString("label").orEmpty(),
        focused = workspace?.optBoolean("focused") ?: false,
        tab = result.optJSONObject("tab")?.optString("tab_id").orEmpty(),
        pane = root?.optString("pane_id").orEmpty(),
        terminal = root?.optString("terminal_id").orEmpty(),
    )
    if (listOf(created.workspace, created.tab, created.pane, created.terminal).any { it.isEmpty() }) {
        return failed(WorkspaceCreateError.OutcomeUnknown(BAD_REPLY))
    }
    return Result.success(created)
}

private fun failed(error: WorkspaceCreateError): Result<CreatedWorkspace> = Result.failure(WorkspaceCreateException(error))
