package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptBlock

/** The space above a chat row: a new turn, a block inside a turn, or between tool rows (§16.1). */
enum class Gap { None, Turn, Block, Tool }

/**
 * One row of the chat list as it is drawn. Grouping is presentation only:
 * every [ChatItem] keeps its own key and state, and a [ToolGroup] just holds
 * the steps it covers.
 */
sealed interface ChatLine {
    val key: String
    val gap: Gap

    /** The first assistant-side row of a turn, which carries the agent label. */
    val labelled: Boolean

    data class Single(val item: ChatItem, override val gap: Gap, override val labelled: Boolean) : ChatLine {
        override val key get() = item.key
    }

    /**
     * A run of [TOOL_GROUP_MIN] or more steps: tools and the Thinking between
     * them. Keyed by its first step, so the key holds while the run grows.
     */
    data class ToolGroup(val steps: List<ChatItem>, override val gap: Gap, override val labelled: Boolean) : ChatLine {
        override val key get() = "g:" + steps.first().key
        val tools: List<ChatItem.Tool> get() = steps.filterIsInstance<ChatItem.Tool>()
        val failed: Int get() = tools.count { toolState(it) == ToolState.Failed }
        val denied: Int get() = tools.count { toolState(it) == ToolState.Denied }

        /** A step still runs: the run draws its newest steps, not its folded line. */
        val live: Boolean get() = tools.any { it.running }
    }
}

/** How many steps in a row fold into one group line. */
const val TOOL_GROUP_MIN = 3

/** A live run shows its newest this many steps; the earlier ones wait behind one row. */
const val LIVE_TAIL = 6

val ChatItem.Tool.running: Boolean get() = result == null
val ChatItem.Tool.failed: Boolean get() = toolState(this) == ToolState.Failed

/** The tools that start a delegated agent; it carries a report the person came for. */
private val AGENT_TOOLS = setOf("task")

/**
 * A step that never folds and splits the run around it: a question put to
 * the person, a delegated agent, and [pending], the call the agent is
 * blocked on, whatever its tool.
 */
private fun standsAlone(item: ChatItem.Tool, pending: TranscriptBlock.ToolCall?): Boolean =
    item.name == ASK_TOOL || item.name in AGENT_TOOLS || (pending != null && item.call?.callId == pending.callId)

private fun isStep(item: ChatItem, pending: TranscriptBlock.ToolCall?): Boolean = when (item) {
    is ChatItem.Thinking -> true
    is ChatItem.Tool -> !standsAlone(item, pending)
    else -> false
}

/**
 * [items] as drawn lines. A turn starts at a user message and at the first
 * row after it; inside a turn rows sit a block apart, tool rows closer.
 * Runs of at least [TOOL_GROUP_MIN] steps (tools and Thinking, running or
 * not) with at least one tool fold into a group. [pending] is the call a
 * blocked agent waits on ([pendingTool]); it, a question and an agent call
 * stand alone.
 */
fun chatLines(items: List<ChatItem>, pending: TranscriptBlock.ToolCall? = null): List<ChatLine> {
    val lines = mutableListOf<ChatLine>()
    var previous: ChatItem? = null
    var i = 0
    while (i < items.size) {
        val item = items[i]
        val turn = previous == null || item is ChatItem.User || previous is ChatItem.User
        val labelled = item !is ChatItem.User && (previous == null || previous is ChatItem.User)
        val gap = when {
            previous == null -> Gap.None
            turn -> Gap.Turn
            item is ChatItem.Tool && previous is ChatItem.Tool -> Gap.Tool
            else -> Gap.Block
        }
        var end = i
        while (end < items.size && isStep(items[end], pending)) end++
        val run = items.subList(i, end)
        if (run.size >= TOOL_GROUP_MIN && run.any { it is ChatItem.Tool }) {
            lines += ChatLine.ToolGroup(run.toList(), gap, labelled)
            previous = run.last()
            i = end
        } else {
            lines += ChatLine.Single(item, gap, labelled)
            previous = item
            i++
        }
    }
    return lines
}

/**
 * The first step a run draws, or null for its folded line. [open]: the
 * reader asked for every step. A live run, and one [held] open because it
 * was seen live, draws its last [LIVE_TAIL] steps.
 */
fun runStart(group: ChatLine.ToolGroup, open: Boolean, held: Boolean): Int? = when {
    open -> 0
    group.live || held -> (group.steps.size - LIVE_TAIL).coerceAtLeast(0)
    else -> null
}

/**
 * The completed runs among these lines, each with the answer that ended it:
 * a group no step of which still runs, followed in its turn by a reply.
 * Only these draw as the "Worked" fold; any other group stays a run.
 */
fun List<ChatLine>.completedRuns(): Map<String, ChatItem.Assistant> {
    val runs = HashMap<String, ChatItem.Assistant>()
    for (at in 0 until size - 1) {
        val group = this[at] as? ChatLine.ToolGroup ?: continue
        val next = this[at + 1]
        val answer = (next as? ChatLine.Single)?.item as? ChatItem.Assistant
        if (!group.live && answer != null && next.gap != Gap.Turn) runs[group.key] = answer
    }
    return runs
}

/** What a completed run's fold says: counts, the distinct tool names in order, and how long, when known. */
data class RunFold(val tools: Int, val thoughts: Int, val failed: Int, val denied: Int, val names: List<String>, val seconds: Long?)

fun runFold(group: ChatLine.ToolGroup, answer: ChatItem.Assistant): RunFold = RunFold(
    tools = group.tools.size,
    thoughts = group.steps.count { it is ChatItem.Thinking },
    failed = group.failed,
    denied = group.denied,
    names = group.tools.map { it.name }.filter { it.isNotBlank() }.distinct(),
    seconds = workedSeconds(group.steps.first().timestamp, answer.timestamp),
)

/**
 * Whole seconds from the run's first step to its answer, both as the
 * transcript recorded them; null when either is missing, the answer is
 * earlier, or under a second passed. Nothing is estimated.
 */
fun workedSeconds(start: Long?, end: Long?): Long? {
    if (start == null || end == null || end < start) return null
    return ((end - start) / 1000).takeIf { it >= 1 }
}

/**
 * The held runs (`"held:" + key`, seen live) that fold now: only when a new
 * user turn, [newestUser] after [seenUser], arrives while the reader
 * [follow]s the end, and only the completed ones above that turn. A first
 * load (no [seenUser] yet) releases nothing, and a reader who scrolled up
 * keeps every run as it is. Only the held mark goes: a run the reader
 * opened stays open.
 */
fun releasedRuns(lines: List<ChatLine>, held: Set<String>, follow: Boolean, seenUser: String?, newestUser: String?): Set<String> {
    if (!follow || seenUser == null || newestUser == null || newestUser == seenUser) return emptySet()
    val turn = lines.indexOfFirst { it is ChatLine.Single && it.key == newestUser }
    if (turn < 0) return emptySet()
    val completed = lines.subList(0, turn).completedRuns().keys
    return held.filterTo(HashSet()) { it in completed }
}

/** An http(s) address in tool output, ending before whitespace, quotes and closing punctuation. */
private val OUTPUT_LINK = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)

/** Where [text] has openable links, each without the trailing `.,;:!?)]}` a sentence puts after it. */
fun outputLinks(text: String): List<IntRange> = OUTPUT_LINK.findAll(text).mapNotNull { match ->
    var end = match.range.last
    while (end > match.range.first && text[end] in ".,;:!?)]}") end--
    // Something must be left after the scheme's "://".
    (match.range.first..end).takeIf { end > text.indexOf("://", match.range.first) + 2 }
}.toList()

/**
 * The link a tap at ([x], [y]) opens when it misses every link's own box:
 * the nearest one whose line box, grown to [reach] tall about its centre,
 * holds the tap within the link's width. [boxes] are the links' line boxes
 * as (left, top, right, bottom); null when no box is in reach.
 */
fun nearLink(x: Float, y: Float, boxes: List<FloatArray>, reach: Float): Int? {
    var best: Int? = null
    var distance = Float.MAX_VALUE
    boxes.forEachIndexed { index, (left, top, right, bottom) ->
        if (x < left || x > right) return@forEachIndexed
        val centre = (top + bottom) / 2
        val half = maxOf(reach, bottom - top) / 2
        val off = kotlin.math.abs(y - centre)
        if (off <= half && off < distance) {
            best = index
            distance = off
        }
    }
    return best
}

/** The line that shows the item keyed [key], itself or inside its group. */
fun List<ChatLine>.indexOfItem(key: String): Int = indexOfFirst { line ->
    when (line) {
        is ChatLine.Single -> line.key == key
        is ChatLine.ToolGroup -> line.steps.any { it.key == key }
    }
}

/**
 * The key of the one line whose agent label also names the tab: the first
 * labelled line. Later labels say the agent alone, and after older history
 * loads the title moves up to the new first turn.
 */
val List<ChatLine>.titledKey: String? get() = firstOrNull { it.labelled }?.key

/** The item key a line is anchored by when older history goes in above it. */
val ChatLine.anchorKey: String get() = when (this) {
    is ChatLine.Single -> item.key
    is ChatLine.ToolGroup -> steps.first().key
}

/** [text] cut to [limit] lines, and how many lines were left out. */
fun capLines(text: String, limit: Int): Pair<String, Int> {
    val lines = text.trimEnd('\n').lines()
    return if (lines.size <= limit) lines.joinToString("\n") to 0 else lines.take(limit).joinToString("\n") to lines.size - limit
}

/** A code block longer than [CODE_COLLAPSE_OVER] lines starts cut to [CODE_COLLAPSED_LINES]. */
const val CODE_COLLAPSE_OVER = 30
const val CODE_COLLAPSED_LINES = 12
