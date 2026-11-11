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
