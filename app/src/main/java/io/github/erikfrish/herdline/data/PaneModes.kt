package io.github.erikfrish.herdline.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException
import org.json.JSONObject

/** One pane of one Herdr session on one saved node, as a mode is remembered for. */
data class ModeKey(val nodeId: String, val session: String, val pane: String) {
    /** Unambiguous: node IDs, session names and pane IDs never hold a NUL. */
    fun encode(): String = "$nodeId\u0000$session\u0000$pane"

    companion object {
        fun decode(encoded: String): ModeKey? = encoded.split('\u0000').takeIf { it.size == 3 }?.let { ModeKey(it[0], it[1], it[2]) }
    }
}

/** One Herdr session on one saved node, as its last tapped mode is remembered for. */
data class SessionKey(val nodeId: String, val session: String) {
    /** Unambiguous: node IDs and session names never hold a NUL. */
    fun encode(): String = "$nodeId\u0000$session"

    companion object {
        fun decode(encoded: String): SessionKey? = encoded.split('\u0000').takeIf { it.size == 2 }?.let { SessionKey(it[0], it[1]) }
    }
}

/**
 * [chat] (Chat, else Terminal) was chosen for a pane while [boot] was the
 * daemon run, [agent] the program in it and [session] the agent's confirmed
 * session there, or null where none was confirmed. Pane IDs are unique only
 * within one run and a pane can get a new program or session, so a choice
 * holds only while all of them still match.
 *
 * [session] is the session file of the instance the host confirmed
 * ([io.github.erikfrish.herdline.core.TranscriptIdentity.path]). The host
 * checks that the file carries the id of the `herdline.session_id` label
 * (native/src/herdr_core/identity.rs), so one ID is one file, and resuming
 * that session opens it again. The process ID is left out: it is new on
 * every start.
 */
data class ModeChoice(val boot: String, val agent: String, val chat: Boolean, val session: String? = null)

/**
 * Whether [choice] is another session than the [now] confirmed in its pane.
 * Only two confirmed sessions can differ: a choice with none follows the run
 * and program alone, and none confirmed now (a view still loading, a shell or
 * other program between agents, labels not published yet) keeps the choice
 * until the next confirmed session decides.
 */
private fun otherSession(choice: ModeChoice, now: String?) =
    choice.session != null && now != null && now != choice.session

/**
 * The daemon run in a view's [boot], which a choice is kept for. herdr-core's
 * boot is `core-<id>-<pid>-<link>` (native/src/herdr_core/pump.rs): the link
 * number is new on every reconnect within this process while the core run
 * and its pane IDs stay, so it is left out. Herdr's own boot is the run.
 */
fun modeRun(boot: String?): String? {
    if (boot == null || !boot.startsWith("core-")) return boot
    val link = boot.substringAfterLast('-')
    return if (link.isNotEmpty() && link.all(Char::isDigit)) boot.substringBeforeLast('-') else boot
}

/**
 * The keys in [stored] for [nodeId]/[session] that no longer describe what
 * runs there: another daemon run ([modeRun]); for a choice bound to a
 * session, another session confirmed in the pane ([sessionOf]); for one
 * without, another program seen in the pane. Dropping them keeps a mode from
 * carrying over to a replaced process.
 *
 * Only a confirmed change counts. A view without a [boot], a pane missing
 * from it, a pane with no program known yet ([agentOf] null) or no session
 * confirmed ([sessionOf] null) drops nothing: a reconnect's view still
 * loading, or a shell between two agents, is no change by itself. Pane IDs
 * are never reused within one run, so a kept choice cannot reach another pane.
 */
fun staleChoices(
    stored: Map<ModeKey, ModeChoice>,
    nodeId: String,
    session: String,
    boot: String,
    sessionOf: (pane: String) -> String? = { null },
    agentOf: (pane: String) -> String?,
): List<ModeKey> {
    if (boot.isBlank()) return emptyList()
    return stored.filter { (key, choice) ->
        key.nodeId == nodeId && key.session == session && (
            modeRun(choice.boot) != modeRun(boot) ||
                if (choice.session != null) {
                    otherSession(choice, sessionOf(key.pane))
                } else {
                    agentOf(key.pane)?.let { it != choice.agent } == true
                }
            )
    }.keys.toList()
}

/**
 * Whether the pane at [key] shows Chat: the pane's own choice while it still
 * matches [boot]'s run, [agent] and the [session] confirmed there (none
 * confirmed keeps it); else [remembered], the mode last tapped in this
 * host's session; else Chat. Never Chat where Chat cannot read the agent
 * ([chatAvailable]), and that fallback changes nothing stored, so the choice
 * applies again once Chat can read it.
 */
fun showsChat(
    stored: Map<ModeKey, ModeChoice>,
    key: ModeKey,
    boot: String?,
    agent: String?,
    chatAvailable: Boolean,
    remembered: Boolean?,
    session: String? = null,
): Boolean {
    if (!chatAvailable) return false
    stored[key]?.takeIf { modeRun(it.boot) == modeRun(boot) && it.agent == agent && !otherSession(it, session) }?.let { return it.chat }
    return remembered ?: true
}

/**
 * Each pane's Chat or Terminal choice, and each session's last tapped one,
 * kept across navigation, reconnects and restarts. Only explicit taps call
 * [set] and [prefer]. Writes go through [SharedPreferences.Editor.apply], so
 * the UI thread never waits on disk.
 */
class PaneModes internal constructor(private val prefs: SharedPreferences, private val sessionPrefs: SharedPreferences) {
    private val _choices = MutableStateFlow(load())
    val choices: StateFlow<Map<ModeKey, ModeChoice>> = _choices.asStateFlow()
    private val _sessions = MutableStateFlow(
        sessionPrefs.all.entries.mapNotNull { (encoded, value) ->
            SessionKey.decode(encoded)?.let { key -> (value as? Boolean)?.let { key to it } }
        }.toMap(),
    )

    /** Each session's last tapped mode (true: Chat). A daemon restart or a new program keeps it. */
    val sessions: StateFlow<Map<SessionKey, Boolean>> = _sessions.asStateFlow()

    private fun load(): Map<ModeKey, ModeChoice> = prefs.all.entries.mapNotNull { (encoded, value) ->
        val key = ModeKey.decode(encoded) ?: return@mapNotNull null
        val json = try {
            JSONObject(value as? String ?: return@mapNotNull null)
        } catch (e: JSONException) {
            return@mapNotNull null
        }
        val boot = json.optString("boot").ifEmpty { return@mapNotNull null }
        val agent = json.optString("agent").ifEmpty { return@mapNotNull null }
        // Entries written before Terminal was stored too were Chat choices.
        key to ModeChoice(boot, agent, json.optBoolean("chat", true), json.optString("session").ifEmpty { null })
    }.toMap()

    fun set(key: ModeKey, choice: ModeChoice) {
        _choices.update { it + (key to choice) }
        val json = JSONObject().put("boot", choice.boot).put("agent", choice.agent).put("chat", choice.chat)
            .apply { choice.session?.let { put("session", it) } }
        prefs.edit().putString(key.encode(), json.toString()).apply()
    }

    /** Remembers [chat] as the mode last tapped in [key]'s session. */
    fun prefer(key: SessionKey, chat: Boolean) {
        if (_sessions.value[key] == chat) return
        _sessions.update { it + (key to chat) }
        sessionPrefs.edit().putBoolean(key.encode(), chat).apply()
    }

    /** Drops [nodeId]/[session] choices that no longer match the daemon's view; see [staleChoices]. */
    fun forget(nodeId: String, session: String, boot: String, sessionOf: (pane: String) -> String? = { null }, agentOf: (pane: String) -> String?) {
        val stale = staleChoices(_choices.value, nodeId, session, boot, sessionOf, agentOf)
        if (stale.isEmpty()) return
        _choices.update { it - stale.toSet() }
        prefs.edit().apply { stale.forEach { remove(it.encode()) } }.apply()
    }

    companion object {
        @Volatile
        private var instance: PaneModes? = null

        fun get(context: Context): PaneModes = instance ?: synchronized(this) {
            instance ?: PaneModes(
                context.applicationContext.getSharedPreferences("pane_modes", Context.MODE_PRIVATE),
                context.applicationContext.getSharedPreferences("session_modes", Context.MODE_PRIVATE),
            )
                .also { instance = it }
        }
    }
}
