package io.github.erikfrish.herdline.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Switcher targets the user pinned or recently went to, per node, as the
 * switcher's row keys (`t:<tab>`, `p:<pane>`). Keys are resolved against the
 * live view every time, so a target that is gone is simply not shown, and
 * one whose ID a restarted daemon reused shows what it is now.
 */
class TargetStore(context: Context) {
    private val prefs = context.getSharedPreferences("targets", Context.MODE_PRIVATE)
    private val _pins = MutableStateFlow(load(PIN))
    private val _recent = MutableStateFlow(load(RECENT))
    val pins: StateFlow<Map<String, List<String>>> = _pins
    val recent: StateFlow<Map<String, List<String>>> = _recent

    private fun load(prefix: String): Map<String, List<String>> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(prefix) || value !is String) return@mapNotNull null
        key.removePrefix(prefix) to value.split(SEPARATOR).filter { it.isNotEmpty() }
    }.toMap()

    fun togglePin(nodeId: String, key: String) {
        val now = _pins.value[nodeId].orEmpty()
        put(_pins, PIN, nodeId, if (key in now) now - key else now + key)
    }

    fun visited(nodeId: String, key: String) =
        put(_recent, RECENT, nodeId, io.github.erikfrish.herdline.ui.session.recentAfter(_recent.value[nodeId].orEmpty(), key))

    /** Drops everything kept for [nodeId], as when the node is deleted. */
    fun forget(nodeId: String) {
        put(_pins, PIN, nodeId, emptyList())
        put(_recent, RECENT, nodeId, emptyList())
    }

    private fun put(flow: MutableStateFlow<Map<String, List<String>>>, prefix: String, nodeId: String, keys: List<String>) {
        prefs.edit().apply {
            if (keys.isEmpty()) remove(prefix + nodeId) else putString(prefix + nodeId, keys.joinToString(SEPARATOR))
        }.apply()
        flow.value = if (keys.isEmpty()) flow.value - nodeId else flow.value + (nodeId to keys)
    }

    private companion object {
        const val PIN = "pin:"
        const val RECENT = "recent:"
        const val SEPARATOR = "\n"
    }
}
