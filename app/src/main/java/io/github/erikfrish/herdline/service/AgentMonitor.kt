package io.github.erikfrish.herdline.service

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** One agent pane the user pinned as a live update. */
data class MonitorKey(val nodeId: String, val pane: String)

/**
 * The agents pinned as live updates. Pinned only by the user, from the pin
 * sheet; unpinned by the user, the notification's Unpin or swipe, or a
 * finished task. Kept across restarts. Herdr reuses no pane ID within one
 * daemon run, but a restarted daemon may, so the card always names what the
 * pane runs now.
 */
class AgentMonitor(context: Context) {
    private val prefs = context.getSharedPreferences("monitor", Context.MODE_PRIVATE)
    private val _monitored = MutableStateFlow(load())
    val monitored: StateFlow<Set<MonitorKey>> = _monitored

    private fun load(): Set<MonitorKey> = prefs.getStringSet(KEY, emptySet()).orEmpty().mapNotNull { entry ->
        val parts = entry.split(SEPARATOR, limit = 2)
        if (parts.size == 2 && parts.none { it.isEmpty() }) MonitorKey(parts[0], parts[1]) else null
    }.toSet()

    fun isMonitored(nodeId: String, pane: String): Boolean = MonitorKey(nodeId, pane) in _monitored.value

    fun pin(nodeId: String, pane: String) = save(_monitored.value + MonitorKey(nodeId, pane))

    fun unpin(keys: Collection<MonitorKey>) {
        if (keys.any { it in _monitored.value }) save(_monitored.value - keys.toSet())
    }

    /** Drops every agent of [nodeId], as when the node is deleted. */
    fun forget(nodeId: String) = save(_monitored.value.filterNot { it.nodeId == nodeId }.toSet())

    private fun save(next: Set<MonitorKey>) {
        prefs.edit().putStringSet(KEY, next.map { it.nodeId + SEPARATOR + it.pane }.toSet()).apply()
        _monitored.value = next
    }

    private companion object {
        const val KEY = "panes"
        const val SEPARATOR = "\t"
    }
}
