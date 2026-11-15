package io.github.erikfrish.herdline.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * One agent launcher installed where the host's exec shell finds it, as the
 * new-tab picker lists it. [kind] is Herdr's canonical kind, the only kind
 * text ever sent anywhere; [label] is display text. A listing means the CLI
 * was found on the exec shell's PATH and no more: nothing was launched, and
 * the daemon's own PATH may still differ.
 */
data class AgentLauncher(val kind: String, val label: String) {
    companion object {
        /** The launchers one discovery read; malformed entries are skipped. */
        fun parse(json: String): List<AgentLauncher> {
            val list = JSONObject(json).optJSONArray("launchers") ?: return emptyList()
            return buildList {
                for (i in 0 until list.length()) {
                    val o = list.optJSONObject(i) ?: continue
                    val kind = o.optString("kind")
                    val label = o.optString("label")
                    if (kind.isEmpty() || label.isEmpty()) continue
                    add(AgentLauncher(kind, label))
                }
            }
        }
    }
}

/** How long a create waits for its one answer; nothing may retry past it. */
private const val CREATE_TIMEOUT_MS = 15_000L

/** How long a create's fresh pane is given to appear in the view before it is called gone. */
private const val PANE_APPEAR_MS = 15_000L

/**
 * Creates one tab in [workspace], always focused (one `tab.create`, asked
 * once), and answers its exact IDs. A refusal says nothing was created; a
 * reply never read says the outcome is unknown and is never called "not
 * created", and nothing is created or asked again on any failure.
 */
suspend fun Connection.createTab(workspace: String, label: String = ""): Result<Connection.CreatedTab> {
    val boot = view.value?.boot.orEmpty()
    if (boot.isEmpty()) return Result.failure(IOException("not_ready"))
    val params = JSONObject().put("workspace_id", workspace).put("focus", true)
    label.trim().takeIf { it.isNotEmpty() }?.let { params.put("label", it) }
    return when (val reply = awaitRequest("tab.create", params, CREATE_TIMEOUT_MS)) {
        is RequestReply.Answered -> daemonRefusal(reply.envelope)?.let {
            // The daemon answered, and its answer says no tab was made.
            Result.failure(IOException(it))
        } ?: parseCreated(reply.envelope, boot)
        is RequestReply.Refused -> Result.failure(IOException(reply.reason))
        is RequestReply.Uncertain -> Result.failure(IOException("outcome_unknown"))
    }
}

/** The daemon's own reason inside a reply's error object, or null when it did not refuse. */
private fun daemonRefusal(envelope: JSONObject): String? {
    val error = envelope.optJSONObject("error") ?: return null
    return error.optString("message").takeIf { it.isNotEmpty() } ?: envelope.optString("error").takeIf { it.isNotEmpty() }
}

/** The exact created IDs inside `tab.create`'s answered envelope. */
internal fun parseCreated(envelope: JSONObject, boot: String): Result<Connection.CreatedTab> {
    val result = envelope.optJSONObject("result") ?: return Result.failure(IOException("bad_reply"))
    val tab = result.optJSONObject("tab")?.optString("tab_id").orEmpty()
    val pane = result.optJSONObject("root_pane")?.optString("pane_id").orEmpty()
    val terminal = result.optJSONObject("root_pane")?.optString("terminal_id").orEmpty()
    if (tab.isEmpty() || pane.isEmpty() || terminal.isEmpty()) return Result.failure(IOException("bad_reply"))
    return Result.success(Connection.CreatedTab(boot, tab, pane, terminal))
}

/**
 * Starts [kind]'s agent in exactly [created]'s fresh root pane, on its own
 * boot, as one Herdr CLI `agent start` over the existing SSH exec channel.
 * The name is generated from [kind] with one random suffix; no label or other text
 * enters it. It waits for the created pane to show in this connection's
 * view first (which may lag the create's answer), refuses once the boot
 * changed or the pane is gone, and leaves the tab in place whatever the
 * outcome. A reply never read leaves startup unconfirmed; the user checks
 * its terminal before making another explicit choice.
 */
suspend fun Connection.startAgent(created: Connection.CreatedTab, kind: String): Result<Unit> {
    val name = agentStartName(kind) ?: return startRefused("bad_kind")
    if (created.isEmpty) return startRefused("no_created_tab")
    val included = withTimeoutOrNull(PANE_APPEAR_MS) {
        view.first { snapshot ->
            snapshot != null &&
                (snapshot.boot != created.boot || snapshot.panes.any { it.id == created.pane && it.tab == created.tab })
        }
    }
    val boot = view.value?.boot
    return when {
        // The daemon run changed under the create: same connection, other IDs.
        boot != created.boot -> startRefused("boot_changed")
        included == null -> startRefused("pane_gone")
        else -> {
            val session = link()
            if (session == 0L) return startRefused("not_connected")
            try {
                val request = created.toJson().put("kind", kind).put("name", name)
                val reply = withContext(Dispatchers.IO) { JSONObject(Native.startAgent(session, request.toString())) }
                if (reply.optBoolean("started")) Result.success(Unit)
                else startRefused(reply.optString("reason", "outcome_unknown"))
            } catch (error: IOException) {
                Result.failure(error)
            } catch (error: org.json.JSONException) {
                Result.failure(IOException("outcome_unknown", error))
            }
        }
    }
}

private fun startRefused(reason: String): Result<Unit> = Result.failure(IOException(reason))

/**
 * The agent launchers the host's exec shell finds, read-only (`command -v`
 * per fixed kind). Blocks on the host: call it off the main thread, as with
 * the other host reads. A failure is explicit and is never taken for "none
 * installed".
 */
fun Connection.agentLaunchers(): Result<List<AgentLauncher>> {
    val session = link()
    if (session == 0L) return Result.failure(IOException("not_connected"))
    return try {
        Result.success(AgentLauncher.parse(Native.agentLaunchers(session)))
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: org.json.JSONException) {
        Result.failure(IOException(e.message, e))
    }
}

/**
 * The machine name `agent.start` takes: `[a-z][a-z0-9_-]{0,31}`, the kind as
 * its prefix plus one random hex suffix so two launches never share a name.
 * No label or any other text reaches it; a kind that cannot be that name's
 * prefix is refused.
 */
internal fun agentStartName(kind: String, suffix: String = UUID.randomUUID().toString().substring(0, 8)): String? {
    if (kind.isEmpty() || kind.length > 23 || !kind.matches(KIND_PREFIX)) return null
    val name = "$kind-$suffix"
    return name.takeIf { it.matches(AGENT_NAME) }
}

private val KIND_PREFIX = Regex("[a-z][a-z0-9_-]*")
private val AGENT_NAME = Regex("[a-z][a-z0-9_-]{0,31}")
