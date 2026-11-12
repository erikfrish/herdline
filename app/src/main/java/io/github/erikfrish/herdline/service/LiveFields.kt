package io.github.erikfrish.herdline.service

import android.content.Context
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.data.NotificationFields

/*
 * The live update of pinned agents, as the fields of
 * the foreground notification: icon, chip, title, text, style and what
 * TalkBack announces. Pure, so every state × lock level is unit-tested.
 *
 * One redaction covers every surface. While locked, the main content and the
 * publicVersion are the same [LiveFace] for the user's level:
 * - **Hidden:** the neutral glyph, "Herdline" and "Unlock for details"; no
 *   state, time, count or name.
 * - **State only:** the state glyph, the state word and the elapsed time when
 *   Herdline saw the status begin; no name or count.
 * - **Names:** agent names and states, with the original live-title count; no time.
 * - **Full:** names, states, times and counts; never Herdr's pane title,
 *   which may quote the task.
 *
 * The same cap applies while unlocked; configurable fields never quote task titles.
 */

/** The small icon: SystemUI draws it as a mask, so only its shape tells the state. */
enum class LiveGlyph { Live, Working, NeedsInput, Done, Disconnected }

/** What the pinned agents are doing, where a live update is shown at all. */
enum class LiveState { Working, Done, Disconnected }

/** What one version of the notification shows. */
internal data class LiveFace(
    val glyph: LiveGlyph,
    val title: String,
    val text: String?,
    /** Expanded text (BigTextStyle); null for none. */
    val big: String?,
    /** Status-bar chip text; null lets the chronometer show, "" shows the icon alone. */
    val chip: String?,
    /** Base of the chronometer, epoch milliseconds; null shows no time. */
    val since: Long?,
    /** What accessibility services announce (the ticker). */
    val spoken: String,
)

internal data class LiveFields(
    val state: LiveState,
    /** Ask Android to promote it: only while a task is actually running or just finished. */
    val promote: Boolean,
    /** A durable pin may be removed explicitly in every live state. */
    val unpin: Boolean,
    val main: LiveFace,
    val public: LiveFace,
    /** The row the card opens and speaks for. */
    val top: LiveRow,
)

/**
 * The live state of [card], or null where nothing is live: a Blocked agent
 * is handed to its attention notification, and Idle or Unknown is not a
 * task in progress. The pin is kept either way.
 */
internal fun liveState(card: LiveCard): LiveState? = when {
    card.staleSince != null -> LiveState.Disconnected
    card.rows.any { it.status == AgentStatus.Working } -> LiveState.Working
    card.rows.any { it.status == AgentStatus.Done } -> LiveState.Done
    else -> null
}

/** Hides finished episodes without deleting the user's durable monitoring pins. */
internal class DoneCards {
    private val until = HashMap<MonitorKey, Long>()

    fun visible(card: LiveCard?, pins: Set<MonitorKey>, now: Long): LiveCard? {
        until.keys.retainAll(pins)
        if (card == null) return null
        if (card.staleSince == null) {
            for (row in card.rows) {
                if (row.status == AgentStatus.Done) until.putIfAbsent(row.key, now + 10_000L)
                else until.remove(row.key)
            }
        }
        val rows = card.rows.filter { it.status != AgentStatus.Done || now < (until[it.key] ?: Long.MAX_VALUE) }
        return if (rows.isEmpty()) null else if (rows.size == card.rows.size) card else card.copy(rows = rows)
    }

    fun nextExpiry(now: Long): Long? = until.values.filter { it > now }.minOrNull()
}


/**
 * The live update for [card] at lock [level], or null when the foreground
 * notification shows the plain Attached content instead. [now] dates the
 * elapsed minutes and [time] formats a time of day.
 */
internal fun liveFields(t: LiveTexts, card: LiveCard?, level: LockDetail, locked: Boolean, now: Long, fields: List<String> = NotificationFields.DEFAULT, time: (Long) -> String): LiveFields? {
    if (card == null) return null
    val state = liveState(card) ?: return null
    val top = when (state) {
        LiveState.Working -> card.rows.first { it.status == AgentStatus.Working }
        LiveState.Done -> card.rows.first { it.status == AgentStatus.Done }
        LiveState.Disconnected -> card.rows.first()
    }
    val permitted = face(t, card, state, top, level, now, fields, time)
    return LiveFields(
        state = state,
        // Link lost: demoted at once; it is old news, not a live task.
        promote = state != LiveState.Disconnected,
        unpin = true,
        main = permitted,
        public = permitted,
        top = top,
    )
}

/** Whole minutes since [since], or null under one minute: a missing value is absent, never 0. */
private fun minutes(ms: Long): Int? = (ms / 60_000).toInt().takeIf { it > 0 }

private fun face(
    t: LiveTexts,
    card: LiveCard,
    state: LiveState,
    top: LiveRow,
    lock: LockDetail,
    // The selected fields can narrow the privacy level, never broaden it.
    now: Long,
    fields: List<String>,
    time: (Long) -> String,
): LiveFace {
    if (lock == LockDetail.Hidden) {
        return LiveFace(LiveGlyph.Live, t.app(), t.unlock(), null, "", null, t.hiddenSpoken())
    }
    val word = when (state) {
        LiveState.Working -> t.status(AgentStatus.Working)
        LiveState.Done -> t.status(AgentStatus.Done)
        LiveState.Disconnected -> t.notConnected()
    }
    val glyph = when (state) {
        LiveState.Working -> LiveGlyph.Working
        LiveState.Done -> LiveGlyph.Done
        LiveState.Disconnected -> LiveGlyph.Disconnected
    }
    val allowed = NotificationFields.normalized(fields).filter { NotificationFields.allowed(it, lock) }.toSet()
    val since = top.since.takeIf { state == LiveState.Working && "elapsed" in allowed }
    fun elapsedText(row: LiveRow): String? {
        if (state == LiveState.Disconnected || "elapsed" !in allowed) return null
        val duration = when (row.status) {
            AgentStatus.Working -> null // A real header chronometer, never duplicated body text.
            AgentStatus.Done -> row.worked
            else -> null
        } ?: return null
        return minutes(duration)?.let { if (row.status == AgentStatus.Done) t.total(t.minutes(it)) else t.minutes(it) }
    }
    val counts = if (state != LiveState.Disconnected && "counts" in allowed && (card.rows.size > 1 || card.needYou > 0)) t.agents(card.rows.size, card.needYou) else null
    fun parts(row: LiveRow): List<String> = fields.distinct().mapNotNull { field ->
        if (field !in allowed) return@mapNotNull null
        when (field) {
            "agent" -> row.name.takeIf { it.isNotBlank() }
            "tab" -> row.tab?.takeIf { it.isNotBlank() }
            "host" -> row.node.takeIf { it.isNotBlank() }
            "state" -> if (state == LiveState.Disconnected) word else t.status(row.status)
            "elapsed" -> elapsedText(row)
            "counts" -> counts
            else -> null
        }
    }
    val topParts = parts(top)
    val liveCount = card.rows.count { it.status == AgentStatus.Working || it.status == AgentStatus.Blocked }
    val title = if (lock == LockDetail.NamesOnly && state == LiveState.Disconnected) word else (if (lock == LockDetail.NamesOnly && liveCount > 1 && "agent" in allowed && "state" in allowed && state != LiveState.Disconnected) listOf(t.many(liveCount)) else emptyList()).plus(topParts.take(2)).joinToString(" · ").ifBlank { t.app() }
    val detail = topParts.drop(2).joinToString(" · ").ifBlank { null }
    // A stale marker is mandatory even when the user hides State.
    val stale = card.staleSince?.let { if (lock == LockDetail.NamesOnly) t.notLive() else t.staleAt(time(it)) }
    val text = listOfNotNull(stale, detail).joinToString(" · ").ifBlank { null }
    val big = if (card.rows.size > 1) (listOfNotNull(stale) + card.rows.take(5).map { parts(it).joinToString(" · ") }.filter { it.isNotBlank() } + listOfNotNull(card.rows.size.takeIf { it > 5 && lock == LockDetail.Full && "counts" in allowed }?.let { t.more(it - 5) })).joinToString("\n").ifBlank { null } else text
    val spokenTime = since?.let { minutes(now - it) }?.let(t::spokenMinutes)
    return LiveFace(
        if ("state" in allowed) glyph else LiveGlyph.Live,
        title, text, big,
        if ("state" in allowed) (if (card.needYou > 0 && state != LiveState.Disconnected && "counts" in allowed) t.needYou(card.needYou) else word) else "",
        since, listOfNotNull(title.replace(" · ", ", "), text?.replace(" · ", ", "), spokenTime).joinToString(", "),
    )
}

/**
 * The lock level the pin sheet preselects: the live level the user chose,
 * else the lock detail they ever saved (Hidden included), else State only,
 * which the sheet then marks as suggested.
 */
internal fun pinPreset(live: LockDetail?, saved: LockDetail?): LockDetail = live ?: saved ?: LockDetail.StateOnly

/** The level the live update uses: the one chosen when pinning, else the notification lock detail, unchanged. */
internal fun liveLevel(live: LockDetail?, alerts: LockDetail): LockDetail = live ?: alerts

/** The strings the live update needs, so it can be tested without resources. */
internal interface LiveTexts {
    fun app(): String
    fun unlock(): String
    fun hiddenSpoken(): String
    fun status(status: AgentStatus): String
    fun notConnected(): String
    fun many(count: Int): String
    fun minutes(count: Int): String
    fun total(elapsed: String): String
    fun spokenMinutes(count: Int): String
    fun staleAt(time: String): String
    fun agents(count: Int, needYou: Int): String
    fun more(count: Int): String
    fun needYou(count: Int): String
    fun notLive(): String
}

internal class ResourceLiveTexts(private val context: Context) : LiveTexts {
    private val res = context.resources
    override fun app() = context.getString(R.string.live_neutral)
    override fun unlock() = context.getString(R.string.live_unlock)
    override fun hiddenSpoken() = context.getString(R.string.live_hidden_spoken)
    override fun status(status: AgentStatus) = AgentNotifications.statusText(context, status)
    override fun notConnected() = context.getString(R.string.live_not_connected)
    override fun many(count: Int) = res.getQuantityString(R.plurals.live_title_many, count, count)
    override fun minutes(count: Int) = res.getQuantityString(R.plurals.live_minutes, count, count)
    override fun total(elapsed: String) = context.getString(R.string.live_total, elapsed)
    override fun spokenMinutes(count: Int) = res.getQuantityString(R.plurals.live_minutes_spoken, count, count)
    override fun staleAt(time: String) = context.getString(R.string.notification_last_seen, time)
    override fun agents(count: Int, needYou: Int) = res.getQuantityString(R.plurals.notification_agents_count, count, count) + (if (needYou > 0) " · " + needYou(needYou) else "")
    override fun more(count: Int) = context.getString(R.string.notification_more, count)
    override fun needYou(count: Int) = res.getQuantityString(R.plurals.notification_needs_count, count, count)
    override fun notLive() = context.getString(R.string.notification_not_live)
}

/** The drawable of [glyph]. */
internal fun glyphIcon(glyph: LiveGlyph): Int = when (glyph) {
    LiveGlyph.Live -> R.drawable.ic_live
    LiveGlyph.Working -> R.drawable.ic_working
    LiveGlyph.NeedsInput -> R.drawable.ic_needs_input
    LiveGlyph.Done -> R.drawable.ic_done
    LiveGlyph.Disconnected -> R.drawable.ic_disconnected
}
