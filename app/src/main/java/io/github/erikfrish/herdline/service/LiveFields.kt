package io.github.erikfrish.herdline.service

import android.content.Context
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail

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
 * - **Names:** agent names and states; no time or count.
 * - **Full:** names, states, times and counts; never Herdr's pane title,
 *   which may quote the task.
 *
 * Unlocked, the main content is the full words with titles, as for every
 * other Herdline notification.
 */

/** The small icon: SystemUI draws it as a mask, so only its shape tells the state. */
enum class LiveGlyph { Live, Working, NeedsInput, Done, Disconnected }

/** What the pinned agents are doing, where a live update is shown at all. */
enum class LiveState { Working, Done, Disconnected }

/** One MetricStyle metric (API 37+). */
internal sealed interface LiveMetric {
    val label: String

    /** A stopwatch counting from [since], epoch milliseconds. */
    data class Elapsed(override val label: String, val since: Long) : LiveMetric

    data class Count(override val label: String, val count: Int) : LiveMetric
}

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
    /** MetricStyle metrics on API 37+, the first one critical; empty for none. */
    val metrics: List<LiveMetric>,
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
internal fun liveFields(t: LiveTexts, card: LiveCard?, level: LockDetail, locked: Boolean, now: Long, time: (Long) -> String): LiveFields? {
    if (card == null) return null
    val state = liveState(card) ?: return null
    val top = when (state) {
        LiveState.Working -> card.rows.first { it.status == AgentStatus.Working }
        LiveState.Done -> card.rows.first { it.status == AgentStatus.Done }
        LiveState.Disconnected -> card.rows.first()
    }
    val permitted = face(t, card, state, top, level, titles = false, now, time)
    return LiveFields(
        state = state,
        // Link lost: demoted at once; it is old news, not a live task.
        promote = state != LiveState.Disconnected,
        unpin = true,
        main = if (locked) permitted else face(t, card, state, top, LockDetail.Full, titles = true, now, time),
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
    titles: Boolean,
    now: Long,
    time: (Long) -> String,
): LiveFace {
    if (lock == LockDetail.Hidden) {
        return LiveFace(LiveGlyph.Live, t.app(), t.unlock(), null, "", null, emptyList(), t.hiddenSpoken())
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
    // Time only while the link is up: a stale live time is never shown.
    val since = top.since.takeIf { state == LiveState.Working }
    fun elapsedOf(row: LiveRow): Int? = when {
        state == LiveState.Disconnected -> null
        row.status == AgentStatus.Working -> row.since?.let { minutes(now - it) }
        row.status == AgentStatus.Done -> row.worked?.let(::minutes)
        else -> null
    }
    fun elapsedText(row: LiveRow): String? = elapsedOf(row)?.let { if (row.status == AgentStatus.Done) t.total(t.minutes(it)) else t.minutes(it) }
    val spokenTime = elapsedOf(top)?.let(t::spokenMinutes)
    val timedChip = when {
        state == LiveState.Done -> word
        since != null -> null
        else -> ""
    }
    // Only panes really live count as tasks: an Idle or Done pin stays
    // listed but is no live task (QA LU-C).
    val live = card.rows.count { it.status == AgentStatus.Working || it.status == AgentStatus.Blocked }
    val title = when {
        state == LiveState.Disconnected -> word
        live <= 1 -> t.pair(top.name, word)
        else -> t.pair(t.many(live), t.row(top.name, word))
    }
    return when (lock) {
        LockDetail.StateOnly -> {
            val title = t.pair(t.app(), word)
            LiveFace(glyph, title, elapsedText(top)?.let { t.pair(word, it) } ?: word, null, timedChip, since, emptyList(), listOfNotNull(title, spokenTime).joinToString(", "))
        }
        LockDetail.NamesOnly -> {
            val names = card.rows.joinToString(" · ") { if (state == LiveState.Disconnected) it.name else t.row(it.name, t.status(it.status)) }
            LiveFace(glyph, title, names, null, if (state == LiveState.Done) word else "", null, emptyList(), listOf(title, names).joinToString(", "))
        }
        else -> {
            val lines = card.rows.map { row ->
                val status = t.status(row.status)
                val shown = row.title?.takeIf { titles && it.isNotBlank() && it != row.name }
                val line = when {
                    // The last status is not live once the link is lost.
                    state == LiveState.Disconnected -> shown?.let { t.pair(row.name, it) } ?: row.name
                    shown != null -> t.rowTitled(row.name, status, shown)
                    else -> t.row(row.name, status)
                }
                elapsedText(row)?.let { t.pair(line, it) } ?: line
            }
            val stale = card.staleSince?.let { t.staleAt(time(it)) }
            val text = when {
                stale != null -> stale
                card.rows.size > 1 -> lines.joinToString(" · ")
                state == LiveState.Done -> elapsedText(top) ?: t.place(top.node, top.name)
                else -> t.place(top.node, top.name)
            }
            val metrics = if (state == LiveState.Working) {
                listOfNotNull(
                    since?.let { LiveMetric.Elapsed(t.elapsed(), it) },
                    LiveMetric.Count(t.status(AgentStatus.Working), card.rows.count { it.status == AgentStatus.Working }),
                    card.needYou.takeIf { it > 0 }?.let { LiveMetric.Count(t.status(AgentStatus.Blocked), it) },
                )
            } else {
                emptyList()
            }
            LiveFace(
                glyph,
                title,
                text,
                (listOfNotNull(stale) + lines).joinToString("\n"),
                timedChip,
                since,
                metrics,
                listOfNotNull(title, text, spokenTime).joinToString(", "),
            )
        }
    }
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
    fun pair(first: String, second: String): String
    fun row(name: String, status: String): String
    fun rowTitled(name: String, status: String, title: String): String
    fun many(count: Int): String
    fun place(node: String, name: String): String
    fun minutes(count: Int): String
    fun total(elapsed: String): String
    fun spokenMinutes(count: Int): String
    fun staleAt(time: String): String
    fun elapsed(): String
}

internal class ResourceLiveTexts(private val context: Context) : LiveTexts {
    private val res = context.resources
    override fun app() = context.getString(R.string.live_neutral)
    override fun unlock() = context.getString(R.string.live_unlock)
    override fun hiddenSpoken() = context.getString(R.string.live_hidden_spoken)
    override fun status(status: AgentStatus) = AgentNotifications.statusText(context, status)
    override fun notConnected() = context.getString(R.string.live_not_connected)
    override fun pair(first: String, second: String) = context.getString(R.string.live_title_one, first, second)
    override fun row(name: String, status: String) = context.getString(R.string.live_row, name, status)
    override fun rowTitled(name: String, status: String, title: String) = context.getString(R.string.live_row_titled, name, status, title)
    override fun many(count: Int) = res.getQuantityString(R.plurals.live_title_many, count, count)
    override fun place(node: String, name: String) = context.getString(R.string.live_place, node, name)
    override fun minutes(count: Int) = res.getQuantityString(R.plurals.live_minutes, count, count)
    override fun total(elapsed: String) = context.getString(R.string.live_total, elapsed)
    override fun spokenMinutes(count: Int) = res.getQuantityString(R.plurals.live_minutes_spoken, count, count)
    override fun staleAt(time: String) = context.getString(R.string.live_stale, time)
    override fun elapsed() = context.getString(R.string.live_metric_elapsed)
}

/** The drawable of [glyph]. */
internal fun glyphIcon(glyph: LiveGlyph): Int = when (glyph) {
    LiveGlyph.Live -> R.drawable.ic_live
    LiveGlyph.Working -> R.drawable.ic_working
    LiveGlyph.NeedsInput -> R.drawable.ic_needs_input
    LiveGlyph.Done -> R.drawable.ic_done
    LiveGlyph.Disconnected -> R.drawable.ic_disconnected
}
