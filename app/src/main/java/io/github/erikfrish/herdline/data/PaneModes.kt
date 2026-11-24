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
 * daemon run and [agent] the program in it. Pane IDs are unique only within
 * one run and a pane can get a new program, so a choice holds only while both
 * still match.
 */
data class ModeChoice(val boot: String, val agent: String, val chat: Boolean)

/**
 * The keys in [stored] for [nodeId]/[session] that no longer describe what
 * runs there: another daemon run, a pane that is gone, or a different program
 * (including none) in the pane. Dropping them keeps a mode from carrying over
 * to a replaced process.
 */
fun staleChoices(
    stored: Map<ModeKey, ModeChoice>,
    nodeId: String,
    session: String,
    boot: String,
    agentOf: (pane: String) -> String?,
): List<ModeKey> = stored.filter { (key, choice) ->
    key.nodeId == nodeId && key.session == session && (choice.boot != boot || agentOf(key.pane) != choice.agent)
}.keys.toList()

/**
 * Whether the pane at [key] shows Chat: the pane's own choice while it still
 * matches [boot] and [agent]; else [remembered], the mode last tapped in this
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
): Boolean {
    if (!chatAvailable) return false
    stored[key]?.takeIf { it.boot == boot && it.agent == agent }?.let { return it.chat }
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
        key to ModeChoice(boot, agent, json.optBoolean("chat", true))
    }.toMap()

    fun set(key: ModeKey, choice: ModeChoice) {
        _choices.update { it + (key to choice) }
        val json = JSONObject().put("boot", choice.boot).put("agent", choice.agent).put("chat", choice.chat)
        prefs.edit().putString(key.encode(), json.toString()).apply()
    }

    /** Remembers [chat] as the mode last tapped in [key]'s session. */
    fun prefer(key: SessionKey, chat: Boolean) {
        if (_sessions.value[key] == chat) return
        _sessions.update { it + (key to chat) }
        sessionPrefs.edit().putBoolean(key.encode(), chat).apply()
    }

    /** Drops [nodeId]/[session] choices that no longer match the daemon's view; see [staleChoices]. */
    fun forget(nodeId: String, session: String, boot: String, agentOf: (pane: String) -> String?) {
        val stale = staleChoices(_choices.value, nodeId, session, boot, agentOf)
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
