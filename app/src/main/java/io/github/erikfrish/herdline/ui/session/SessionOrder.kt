package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent

/**
 * The rows Last activity lists, in Herdr's order: a one-pane tab as its tab
 * row, a split tab as its pane rows. Each is one place the user goes to, so
 * none stands for another's activity and the highlighted row stays the
 * Herdr-focused one, as in the Workspace order.
 */
fun List<WorkspaceGroup>.places(): List<SwitchTarget> =
    rows().filter { it is SwitchTarget.PaneRow || (it is SwitchTarget.TabRow && it.panes <= 1) }

/** [places] as [ActivityKey]s, each carrying its agent's seq from [seqOf]. */
fun List<SwitchTarget>.activityKeys(boot: String, seqOf: (Agent) -> Long?): List<ActivityKey> = map { row ->
    val agents = when (row) {
        is SwitchTarget.PaneRow -> listOfNotNull(row.agent)
        is SwitchTarget.TabRow -> row.agents
    }
    ActivityKey(row.key, boot, agents.mapNotNull(seqOf).maxOrNull())
}

/**
 * One switcher row as the Last activity order sees it. [seq] is Herdr's
 * `state_change_seq` of the row's most recent agent state change, or null
 * when the row has no agent or the daemon sent none. A seq only means
 * something next to other seqs of the same daemon run, [boot].
 */
data class ActivityKey(val key: String, val boot: String, val seq: Long?)

/**
 * [rows], most recent state change first. They arrive in Herdr's order,
 * which breaks ties and orders the rows without a seq, placed last. Seqs are
 * compared only within one boot: each boot's rows stay together, in the
 * order the boots first appear, so two hosts or a restarted daemon never
 * interleave on numbers that do not compare.
 */
fun lastActivityOrder(rows: List<ActivityKey>): List<ActivityKey> =
    rows.groupBy { it.boot }.values.flatMap { run ->
        run.withIndex()
            .sortedWith(compareBy<IndexedValue<ActivityKey>>({ it.value.seq == null }, { -(it.value.seq ?: 0L) }, { it.index }))
            .map { it.value }
    }

/**
 * What the switcher keeps showing while it is in use: the rows the user is
 * looking at, [held], each with the seq it had then, so it stays in its
 * place and section. Rows that disappeared are dropped and new rows join at
 * the end in [fresh] order. Nothing on screen moves until the user asks for
 * the fresh order or opens the switcher again.
 */
fun heldOrder(held: List<ActivityKey>, fresh: List<ActivityKey>): List<ActivityKey> {
    val present = fresh.mapTo(HashSet()) { it.key }
    val kept = held.filter { it.key in present }
    val seen = kept.mapTo(HashSet()) { it.key }
    return kept + fresh.filter { it.key !in seen }
}

/**
 * How many of the [fresh] rows changed state since [shown] was taken: a new
 * seq or a row that was not there. Zero when updating would move nothing
 * (same rows, same order, same sections); otherwise at least 1, so
 * "N changed · Update order" never offers to update nothing.
 */
fun changedSince(shown: List<ActivityKey>, fresh: List<ActivityKey>): Int {
    fun layout(rows: List<ActivityKey>) = rows.map { it.key to (it.seq == null) }
    if (layout(shown) == layout(fresh)) return 0
    val before = shown.associate { it.key to it.seq }
    return fresh.count { it.key !in before || before[it.key] != it.seq }.coerceAtLeast(1)
}

/** A minute, an hour and a day in milliseconds, for "changed …" times. */
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS

/** How long ago a change Herdline saw at [since] was, as of [now], in the largest whole unit. */
sealed interface Ago {
    data object Now : Ago
    data class Minutes(val count: Int) : Ago
    data class Hours(val count: Int) : Ago
    data class Days(val count: Int) : Ago
}

fun ago(since: Long, now: Long): Ago {
    val elapsed = (now - since).coerceAtLeast(0)
    return when {
        elapsed < MINUTE_MS -> Ago.Now
        elapsed < HOUR_MS -> Ago.Minutes((elapsed / MINUTE_MS).toInt())
        elapsed < DAY_MS -> Ago.Hours((elapsed / HOUR_MS).toInt())
        else -> Ago.Days((elapsed / DAY_MS).toInt())
    }
}
