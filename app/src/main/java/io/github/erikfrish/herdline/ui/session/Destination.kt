package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.readableTitle

/*
 * The composer's To-line: which pane Send goes to, so that no two panes of
 * one workspace read alike (U1-1, team/assignments/night-u1-specs.md §B).
 * Pure: no Compose, no resources, so the rules are unit-tested; the caller
 * measures the room and passes it as display columns.
 */

/**
 * One pane as the To-line names it.
 * - [tab] is the tab's own name (its user label, else "Tab N"), never the
 *   agent's task title, which changes as the agent works (lab.3 D2).
 * - [number] is the pane's 1-based place among its workspace's panes,
 *   unique within the workspace, for "pane N".
 * - [title] (the task title) goes only into the spoken description.
 * - [state] is "Sending…" or the status label; it is never cut.
 */
data class Destination(
    val pane: String,
    val tabId: String,
    val tab: String,
    val workspace: String,
    val label: String? = null,
    val program: String? = null,
    val number: Int,
    val title: String? = null,
    val state: String? = null,
)

/**
 * [shown] fits the room. The rest is uncut, for the spoken description
 * (`composer_destination_description`): [who] with the task title after it,
 * [workspace] and [state].
 */
data class DestinationLabel(val shown: String, val who: String, val workspace: String, val state: String?)

/** Between the parts of the line. */
const val DESTINATION_SEPARATOR = " · "

/** Between the tab and what tells its panes apart. */
const val DESTINATION_WITHIN = " › "

/** The least room the workspace is shown in after a whole `who`; less drops it (§B fit 3, A-29). */
private const val WHERE_MIN = 8

/**
 * [target]'s line among [siblings], the other panes of its workspace, in at
 * most [maxColumns] display columns (an East-Asian wide character or an
 * emoji counts 2). The same group gives every pane a different `who`.
 */
fun destinationLabel(target: Destination, siblings: List<Destination>, maxColumns: Int): DestinationLabel {
    val group = siblings.filterNot { it.pane == target.pane } + target
    return destinationLabels(group, maxColumns).getValue(target.pane)
}

/**
 * [paneId]'s line among the panes of its workspace in this view; null for a
 * pane not in view. [state] is the target's own; siblings are compared by
 * `who` only, so theirs does not matter.
 */
fun ShellView.destinationOf(paneId: String, state: String?, maxColumns: Int): DestinationLabel? {
    val workspace = panes.firstOrNull { it.id == paneId }?.workspace ?: return null
    val where = workspaces.firstOrNull { it.id == workspace }?.let { readableTitle(it.label) }.orEmpty()
    val group = panes.filter { it.workspace == workspace }.mapIndexed { i, p ->
        val tab = tabs.firstOrNull { it.id == p.tab }
        val agent = agentIn(p.id)
        Destination(
            pane = p.id,
            tabId = p.tab,
            // The user's tab label (as Titles.kt Tab.userLabel), else "Tab N".
            tab = tab?.let { t -> readableTitle(t.label)?.takeIf { it != t.number.toString() } ?: "Tab ${t.number}" } ?: "Tab",
            workspace = where,
            label = readableTitle(p.label, agent?.agent),
            program = agent?.agent,
            number = i + 1,
            title = paneTitle(p.id),
            state = state.takeIf { p.id == paneId },
        )
    }
    return destinationLabels(group, maxColumns)[paneId]
}

/**
 * Every pane of one workspace, labelled together (§B):
 * `{who} · {state} · {where}`. `who` is the tab, with ` › ` and the first
 * of pane label, program, `pane N` that tells it apart when its tab has more
 * than one pane or another tab has the same name. A cut that makes two
 * `who` read alike first drops the workspace, then adds ` › pane N`, which
 * is never cut.
 */
fun destinationLabels(group: List<Destination>, maxColumns: Int): Map<String, DestinationLabel> {
    val who = group.associate { it.pane to whoOf(it, group) }
    val dropWhere = mutableSetOf<String>()
    val numbered = mutableSetOf<String>()

    fun line(d: Destination): Line = fitLine(
        who.getValue(d.pane),
        if (d.pane in numbered) "${DESTINATION_WITHIN}pane ${d.number}" else "",
        d.state,
        d.workspace.takeIf { d.pane !in dropWhere }.orEmpty(),
        maxColumns,
    )

    var lines = group.associate { it.pane to line(it) }
    for (step in 0..1) {
        val alike = group.groupBy { lines.getValue(it.pane).who }.values.filter { it.size > 1 }.flatten().map { it.pane }
        if (alike.isEmpty()) break
        if (step == 0) dropWhere += alike else numbered += alike
        lines = group.associate { it.pane to line(it) }
    }
    return group.associate { d ->
        val fitted = lines.getValue(d.pane)
        val spokenWho = who.getValue(d.pane) + (d.title?.takeIf { it.isNotEmpty() && it != d.tab }?.let { "$DESTINATION_SEPARATOR$it" } ?: "")
        d.pane to DestinationLabel(fitted.shown, spokenWho, d.workspace, d.state)
    }
}

private fun whoOf(d: Destination, group: List<Destination>): String {
    val peers = group.filter { it.pane != d.pane && (it.tabId == d.tabId || it.tab == d.tab) }
    if (peers.isEmpty()) return d.tab
    val within = listOf(d.label, d.program).firstOrNull { part ->
        !part.isNullOrEmpty() && peers.none { listOf(it.label, it.program).firstOrNull { p -> !p.isNullOrEmpty() } == part }
    } ?: "pane ${d.number}"
    return "${d.tab}$DESTINATION_WITHIN$within"
}

private data class Line(val shown: String, val who: String)

/**
 * The line in at most [maxColumns]: [state] and [protected] whole; `who`
 * whole when it fits, and the workspace after it only in the room left,
 * when that is at least `min(len, 8)` columns. Otherwise the workspace is
 * dropped first, since the header names it, and `who` takes all the room
 * (A-29, audit 2.9).
 */
private fun fitLine(who: String, protected: String, state: String?, where: String, maxColumns: Int): Line {
    val sep = columns(DESTINATION_SEPARATOR)
    val stateCols = state?.let { columns(it) + sep } ?: 0

    fun build(whoW: Int, whereW: Int?): Line {
        val shownWho = middleEllipsis(who, whoW.coerceAtLeast(1)) + protected
        val parts = listOfNotNull(shownWho, state, whereW?.let { middleEllipsis(where, it) })
        return Line(parts.joinToString(DESTINATION_SEPARATOR), shownWho)
    }

    val alone = maxColumns - stateCols - columns(protected)
    val left = alone - columns(who) - sep
    if (where.isEmpty() || left < minOf(columns(where), WHERE_MIN)) return build(alone, null)
    return build(columns(who), left)
}

/**
 * [text] in at most [maxColumns] columns, cut in the middle: a head of
 * ceil((n−1)/2) columns, "…", and the tail in the rest; room a wide
 * character leaves at the head goes to the tail. A text that fits is
 * returned unchanged; a cut never splits a character, an emoji sequence or
 * a combining mark.
 */
fun middleEllipsis(text: String, maxColumns: Int): String {
    if (columns(text) <= maxColumns) return text
    val clusters = clusters(text)
    val room = (maxColumns - 1).coerceAtLeast(0)
    val headRoom = (room + 1) / 2
    var head = 0
    var headWidth = 0
    while (head < clusters.size && headWidth + width(clusters[head]) <= headRoom) headWidth += width(clusters[head++])
    var tail = clusters.size
    var tailWidth = 0
    while (tail > head && headWidth + tailWidth + width(clusters[tail - 1]) <= room) tailWidth += width(clusters[--tail])
    return clusters.subList(0, head).joinToString("") + "…" + clusters.subList(tail, clusters.size).joinToString("")
}

/** Display columns: wide (CJK, fullwidth, emoji) clusters count 2. */
fun columns(text: String): Int = clusters(text).sumOf(::width)

private fun width(cluster: String): Int = if (wide(cluster.codePointAt(0)) || cluster.contains('\uFE0F')) 2 else 1

private fun wide(cp: Int): Boolean =
    cp in 0x1100..0x115F || cp in 0x2E80..0x303E || cp in 0x3041..0x33FF || cp in 0x3400..0x4DBF ||
        cp in 0x4E00..0x9FFF || cp in 0xA000..0xA4CF || cp in 0xAC00..0xD7A3 || cp in 0xF900..0xFAFF ||
        cp in 0xFE30..0xFE4F || cp in 0xFF00..0xFF60 || cp in 0xFFE0..0xFFE6 || cp in 0x1F300..0x1FAFF ||
        cp in 0x20000..0x3FFFD

/*
 * Grapheme clusters, near enough, by hand rather than java.text.BreakIterator:
 * the JVM's iterator before JDK 20 splits emoji ZWJ sequences, so unit tests
 * and the device would disagree. A cluster is a code point with its marks,
 * variation selectors, skin-tone modifiers, tag characters and ZWJ partners.
 */
private fun clusters(text: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < text.length) {
        val start = i
        i += Character.charCount(text.codePointAt(i))
        while (i < text.length) {
            val cp = text.codePointAt(i)
            when {
                joins(cp) -> i += Character.charCount(cp)
                cp == 0x200D -> {
                    i += 1
                    if (i < text.length) i += Character.charCount(text.codePointAt(i))
                }
                else -> break
            }
        }
        out += text.substring(start, i)
    }
    return out
}

private fun joins(cp: Int): Boolean {
    val type = Character.getType(cp)
    return type == Character.NON_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt() ||
        type == Character.COMBINING_SPACING_MARK.toInt() || cp in 0xFE00..0xFE0F || cp in 0x1F3FB..0x1F3FF ||
        cp in 0xE0020..0xE007F
}
