package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus

/**
 * One agent notification slot: exactly one pane of one node. Daemon messages
 * that name no pane share one slot per node. [tag] is the notification tag
 * and the PendingIntent identifier, so two slots never replace each other's
 * notification or rewrite each other's deep link.
 */
data class AlertKey(val nodeId: String, val pane: String?) {
    val tag: String get() = "agent\n" + nodeId + "\n" + pane.orEmpty()

    companion object {
        /** Prefix shared by every agent notification of [nodeId]. */
        fun prefix(nodeId: String): String = "agent\n" + nodeId + "\n"
    }
}

/** What Herdr's semantic notification reported; Herdr sends no error kind. */
enum class AlertKind {
    NeedsInput,
    Done,
    ;

    /** Whether [status] is still the state this alert announced. */
    fun current(status: AgentStatus): Boolean = when (this) {
        NeedsInput -> status == AgentStatus.Blocked
        Done -> status == AgentStatus.Done
    }
}

enum class Alert {
    /** A new Needs input or Done: post and alert. */
    Loud,

    /** The same state again while its notification still shows: update in place, no sound. */
    Silent,

    /** The same state again after the user dismissed or opened it: do not post. */
    Skip,
}

/**
 * Decides when an agent notification alerts. Herdr may repeat a state's
 * notification; only a new state, or the same state after the pane was seen
 * leaving it, alerts again. A pane's status changing away from what its
 * notification announced resolves that notification.
 */
class AgentAlerts {
    private class Entry(val kind: AlertKind, var armed: Boolean = false)

    private val entries = HashMap<AlertKey, Entry>()
    private val statuses = HashMap<AlertKey, AgentStatus>()

    /** [showing] is whether the notification for [key] is still posted. */
    @Synchronized
    fun onEvent(key: AlertKey, kind: AlertKind, showing: Boolean): Alert {
        val entry = entries[key]
        val alert = when {
            entry == null || entry.kind != kind || entry.armed -> Alert.Loud
            showing -> Alert.Silent
            else -> Alert.Skip
        }
        if (alert == Alert.Loud) entries[key] = Entry(kind)
        return alert
    }

    /**
     * The daemon's current [status] of [key]'s pane. True when it changed
     * away from the state its notification announced: that notification is
     * resolved, and the next notification of that state alerts again. Only a
     * change counts, so a view that has not caught up with the event yet
     * resolves nothing.
     */
    @Synchronized
    fun onStatus(key: AlertKey, status: AgentStatus): Boolean {
        val previous = statuses.put(key, status)
        if (previous == null || previous == status) return false
        val entry = entries[key] ?: return false
        if (entry.kind.current(status) || entry.armed) return false
        entry.armed = true
        return true
    }
}

/**
 * Herdr follows a pane's semantic notification with a classic one that names
 * no pane, about a second later (lab.3 D6). The pane's own notification already
 * says it, so a pane-less notification received within [WINDOW_MS] of a pane
 * notification of the same node is an echo. A pane-less one with no pane
 * notification near it, such as a program's own terminal notification, stays.
 *
 * What counts is the pane notification Herdr sent, not one Herdline showed: with
 * its switch off nothing shows it, and its classic copy, which always reads as a
 * completion, must not slip through the other switch.
 */
class HerdrEcho {
    private val panes = HashMap<AlertKey, Long>()

    /**
     * Herdr's notification for [key] arrived at [at], before any switch or surface
     * decides on it. Each receiver of the event records it; a repeat only moves its time.
     */
    @Synchronized
    fun received(key: AlertKey, at: Long) {
        panes.values.removeAll { at - it > WINDOW_MS }
        if (key.pane != null) panes[key] = maxOf(at, panes[key] ?: at)
    }

    /** Whether a notification for [key] at [at] repeats a pane notification of its node received just before. */
    @Synchronized
    fun isEcho(key: AlertKey, at: Long): Boolean =
        key.pane == null && panes.any { (k, t) -> k.nodeId == key.nodeId && follows(t, at) }

    companion object {
        const val WINDOW_MS = 5_000L

        /** Whether [at] is within [WINDOW_MS] after [earlier]. */
        fun follows(earlier: Long, at: Long): Boolean = at - earlier in 0..WINDOW_MS

        /** The pane-less notification of [key]'s node that a pane notification at [at] shows was an echo. */
        fun echoBefore(key: AlertKey, at: Long, posted: Map<AlertKey, Long>): AlertKey? {
            if (key.pane == null) return null
            val bare = AlertKey(key.nodeId, null)
            return bare.takeIf { posted[it]?.let { t -> follows(t, at) } == true }
        }
    }
}

/**
 * When each agent entered its current status, as Herdline itself observed
 * it. Herdr reports no such time, so a status first seen on attach, or seen
 * again after the link dropped, has no known start.
 */
class StatusClock {
    private data class Seen(val status: AgentStatus, val since: Long?, val worked: Long?)

    private val seen = HashMap<MonitorKey, Seen>()

    /** Records [status] at [now] and returns when this status began, or null if unknown. */
    fun observe(key: MonitorKey, status: AgentStatus, now: Long): Long? {
        val previous = seen[key]
        val since = when {
            previous == null -> null
            previous.status != status -> now
            else -> previous.since
        }
        val worked = when {
            previous == null -> null
            previous.status == status -> previous.worked
            previous.status == AgentStatus.Working -> previous.since?.let { now - it }
            else -> null
        }
        seen[key] = Seen(status, since, worked)
        return since
    }

    fun since(key: MonitorKey): Long? = seen[key]?.since

    /** How long the Working run that ended in the current status lasted, if Herdline saw it start and end. */
    fun worked(key: MonitorKey): Long? = seen[key]?.worked

    /** The link to [nodeId] dropped: whatever changed meanwhile has no known time. */
    fun forget(nodeId: String) {
        seen.keys.removeAll { it.nodeId == nodeId }
    }
}
