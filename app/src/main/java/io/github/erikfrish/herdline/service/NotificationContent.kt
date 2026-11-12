package io.github.erikfrish.herdline.service

import android.content.Context
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail

/**
 * The words of every Herdline notification, chosen from the lock-screen
 * privacy setting and whether the device is locked (lab.3 D10/D11, lab.6 I1).
 *
 * Android shows a notification's publicVersion only when the system redacts
 * by itself. With "show sensitive content" on, the lock screen shows the main
 * content, so a notification posted while the device is locked carries the
 * permitted words as its main content too. The card, agent notes and summary
 * are re-posted on the lock edges ([LockGate]); the lost note has the same
 * words locked and unlocked. All are re-posted when the setting changes.
 *
 * - **Hidden:** no host, node, session, tab, agent, task, status or link state
 *   while locked; only a generic title.
 * - **Names:** agent, status and host; never Herdr's title or body, which may
 *   quote the task.
 * - **Full:** everything.
 *
 * [LockDetail.StateOnly] belongs to the live update ([liveFields]); here it
 * names nothing, like Hidden. Pure, so each branch × setting × lock state is
 * unit-tested.
 */
internal data class NoteContent(
    val title: String,
    val text: String? = null,
    val subText: String? = null,
    /** Expanded text; null for none. */
    val big: String? = null,
)

/**
 * The main content and the publicVersion. The publicVersion always carries
 * the words for a locked device: the permitted words, and under Full the
 * same words as the main content (lab.7.3 J1).
 */
internal data class Redacted(val main: NoteContent, val public: NoteContent)

/** The strings the choice needs, so it can be tested without resources. */
internal interface NoteTexts {
    fun attachedTo(host: String): String
    fun attachedHosts(count: Int): String
    fun attachedDetail(): String
    fun lostTitle(host: String): String
    fun lostGeneric(): String
    fun lostText(): String
    fun row(name: String, status: String): String
    fun status(status: AgentStatus): String
    fun agentAttention(): String
    fun agentDone(): String
    fun agentsSummary(): String
    fun connected(): String
    fun agentCounts(count: Int, needYou: Int): String
}

internal enum class AttachedState { Connected, Connecting, Disconnected }

/** A mixed set is never labelled wholly connected; unknown/lost states win over a pending connect. */
internal fun attachedState(states: List<io.github.erikfrish.herdline.core.Connection.State>): AttachedState = when {
    states.isNotEmpty() && states.all { it is io.github.erikfrish.herdline.core.Connection.State.Connected } -> AttachedState.Connected
    states.any { it is io.github.erikfrish.herdline.core.Connection.State.Connecting || it is io.github.erikfrish.herdline.core.Connection.State.SettingUp } && states.all { it is io.github.erikfrish.herdline.core.Connection.State.Connected || it is io.github.erikfrish.herdline.core.Connection.State.Connecting || it is io.github.erikfrish.herdline.core.Connection.State.SettingUp } -> AttachedState.Connecting
    else -> AttachedState.Disconnected
}

internal fun attachedTitle(context: Context, state: AttachedState): String = context.getString(when (state) {
    AttachedState.Connected -> R.string.connection_connected
    AttachedState.Connecting -> R.string.connection_connecting
    AttachedState.Disconnected -> R.string.disconnected
})

/** The foreground notification while no live update shows: connecting, attached, reconnecting or offline, or a pin that is not live. */
internal fun attachedContent(t: NoteTexts, count: Int, host: String?, lock: LockDetail, locked: Boolean, fields: List<String> = io.github.erikfrish.herdline.data.NotificationFields.DEFAULT, agents: Int? = null, needYou: Int = 0, connection: AttachedState = AttachedState.Connected, connectionTitle: String = t.connected()): Redacted {
    val title = if ("host" in fields && lock == LockDetail.Full && count == 1 && !host.isNullOrBlank()) connectionTitle + " · " + host else connectionTitle
    val text = agents?.takeIf { connection == AttachedState.Connected && "counts" in fields && lock == LockDetail.Full && (it > 1 || needYou > 0) }?.let { t.agentCounts(it, needYou) }
    val content = NoteContent(if (lock == LockDetail.Hidden) "Herdline" else title, text)
    return Redacted(content, content)
}

/**
 * The connection-lost note. It is not re-posted on the lock edge, so its words
 * are the same locked and unlocked: under Hidden it never names the host. A
 * privacy-setting change re-posts it (lab.7 P8).
 */
internal fun lostContent(t: NoteTexts, host: String, lock: LockDetail): Redacted {
    val title = if (lock.showsNames) t.lostTitle(host) else t.lostGeneric()
    return Redacted(NoteContent(title, t.lostText()), if (lock == LockDetail.Full) NoteContent(title, t.lostText()) else NoteContent(title))
}

/**
 * One agent notification. [title] and [body] are Herdr's and may quote the
 * task; [stale] is the "not connected" line while the link is down.
 */
internal fun agentContent(
    t: NoteTexts,
    attention: Boolean,
    node: String,
    agent: String?,
    title: String,
    body: String?,
    stale: String?,
    lock: LockDetail,
    locked: Boolean,
): Redacted {
    val full = NoteContent(title, body ?: node, stale ?: node, big = body)
    val permitted = when (lock) {
        LockDetail.Full -> full
        LockDetail.NamesOnly -> NoteContent(t.row(agent ?: node, t.status(if (attention) AgentStatus.Blocked else AgentStatus.Done)), stale, node)
        LockDetail.Hidden, LockDetail.StateOnly -> NoteContent(if (attention) t.agentAttention() else t.agentDone())
    }
    return Redacted(if (locked) permitted else full, permitted)
}

/** The group summary of one node's agent notifications. */
internal fun summaryContent(t: NoteTexts, node: String, lock: LockDetail, locked: Boolean): Redacted {
    val permitted = NoteContent(if (lock.showsNames) node else t.agentsSummary())
    return Redacted(if (locked) permitted else NoteContent(node), permitted)
}

/** [NoteTexts] from the app's resources. */
internal class ResourceTexts(private val context: Context) : NoteTexts {
    private val res = context.resources
    override fun attachedTo(host: String) = context.getString(R.string.attached_to, host)
    override fun attachedHosts(count: Int) = res.getQuantityString(R.plurals.attached_hosts, count, count)
    override fun attachedDetail() = context.getString(R.string.attached_detail)
    override fun lostTitle(host: String) = context.getString(R.string.connection_lost_title, host)
    override fun lostGeneric() = context.getString(R.string.lock_connection_lost)
    override fun lostText() = context.getString(R.string.connection_lost_text)
    override fun row(name: String, status: String) = context.getString(R.string.live_row, name, status)
    override fun status(status: AgentStatus) = AgentNotifications.statusText(context, status)
    override fun agentAttention() = context.getString(R.string.lock_agent_attention)
    override fun agentDone() = context.getString(R.string.lock_agent_done)
    override fun agentsSummary() = context.getString(R.string.lock_agents_summary)
    override fun connected() = context.getString(R.string.notification_connected)
    override fun agentCounts(count: Int, needYou: Int) = ResourceLiveTexts(context).agents(count, needYou)
}
