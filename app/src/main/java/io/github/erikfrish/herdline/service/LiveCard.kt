package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.readableTitle

/** What one attached node contributes to the live card. */
data class NodeSnapshot(
    val nodeId: String,
    val title: String,
    val connected: Boolean,
    val view: ShellView?,
    /** When the view was last known current, in epoch milliseconds. */
    val lastSeen: Long,
)

/**
 * [since] is when Herdline saw the agent enter [status], or null when it did
 * not see that happen. [worked] is how long the Working run before it lasted,
 * when Herdline saw both its start and its end.
 */
data class LiveRow(
    val nodeId: String,
    val node: String,
    val pane: String,
    val name: String,
    val title: String?,
    val status: AgentStatus,
    val since: Long?,
    val worked: Long? = null,
    val tab: String? = null,
) {
    val key: MonitorKey get() = MonitorKey(nodeId, pane)
}

/**
 * The monitored agents as last reported. [staleSince] is set when any of
 * their nodes is no longer connected: the rows are then a memory, not the
 * daemon's present state, and say so.
 */
data class LiveCard(val rows: List<LiveRow>, val staleSince: Long?) {
    val needYou: Int get() = rows.count { it.status == AgentStatus.Blocked }
}

/** Actual foreground-card data and privacy, shared with the Settings preview. */
internal data class NotificationCardPreview(val card: LiveCard?, val nodes: Int, val host: String?, val agents: Int, val needYou: Int, val level: io.github.erikfrish.herdline.data.LockDetail, val connection: AttachedState)

/**
 * The card for [monitored] agents found in [nodes], most urgent first, or
 * null when none of them is in any view. A pane that left the view is
 * dropped rather than shown with an old status. [since] gives the observed
 * start of each agent's status, and [worked] its last Working run, if known.
 */
fun liveCard(
    nodes: List<NodeSnapshot>,
    monitored: Set<MonitorKey>,
    since: (MonitorKey) -> Long?,
    worked: (MonitorKey) -> Long? = { null },
): LiveCard? {
    var staleSince: Long? = null
    val rows = nodes.flatMap { node ->
        val agents = node.view?.agents.orEmpty().filter { MonitorKey(node.nodeId, it.pane) in monitored }
        if (agents.isNotEmpty() && !node.connected) staleSince = minOf(staleSince ?: node.lastSeen, node.lastSeen)
        agents.map {
            val key = MonitorKey(node.nodeId, it.pane)
            LiveRow(node.nodeId, node.title, it.pane, it.displayName, readableTitle(it.title, it.agent), it.status, since(key), worked(key), node.view?.tabs?.firstOrNull { tab -> tab.id == it.tab }?.label)
        }
    }.sortedBy { it.status.attention }
    return if (rows.isEmpty()) null else LiveCard(rows, staleSince)
}

/**
 * The pins a swipe or Unpin on the card removes: every row the live update
 * showed, or none while the card shows the plain Attached content, which
 * names no pin.
 */
internal fun dismissedPins(card: LiveCard?): Set<MonitorKey> =
    if (card == null || liveState(card) == null) emptySet() else card.rows.mapTo(HashSet()) { it.key }
