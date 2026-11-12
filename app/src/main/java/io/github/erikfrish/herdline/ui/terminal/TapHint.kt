package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.Viewport
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How long the frame and the view must stay unchanged before the tap-to-type hint is placed (QF-9). */
internal const val TAP_HINT_SETTLE_MS = 300L

/**
 * The still time before the hint returns once output has hidden it (QF9-F):
 * over three times the 0.4 s between lines QA saw streamed, and past the
 * gaps of common progress output, so a stream never blinks it on and off
 * per line; it comes back once output has really paused.
 */
internal const val TAP_HINT_RESETTLE_MS = 1500L

/**
 * How long the frame and the view must stay unchanged before the hint is
 * placed: [TAP_HINT_SETTLE_MS] for its first appearance, the longer
 * [TAP_HINT_RESETTLE_MS] after a change has hidden it ([hiddenByChange]).
 */
internal fun tapHintSettleMs(hiddenByChange: Boolean): Long =
    if (hiddenByChange) TAP_HINT_RESETTLE_MS else TAP_HINT_SETTLE_MS

/** A rounded pixel the pill may stick past its rows; below what a glyph shows. */
private const val ROUND_SLACK_PX = 0.5f

/**
 * Whether the tap-to-type hint is wanted at all (QF-9): on Terminal, the
 * keyboard down, before the user first typed, outside output focus, connected
 * with a view; and (A-26) with no notice over the terminal, which would sit
 * over the pill and let its text peek out. Unwanted, it is not composed, so it
 * leaves the TalkBack tree; wanted again, it is placed only after the settle.
 */
internal fun tapHintWanted(
    chatMode: Boolean,
    imeVisible: Boolean,
    typedOnce: Boolean,
    focusApplies: Boolean,
    connectedWithView: Boolean,
    noticeOver: Boolean,
): Boolean = !chatMode && !imeVisible && !typedOnce && !focusApplies && connectedWithView && !noticeOver

/**
 * Where the tap-to-type hint's top goes, in px from the view's top, or null
 * when it is hidden (QF-9b): until [stillMs], the time nothing changed, has
 * reached [TAP_HINT_SETTLE_MS], so a change hides it at once and output never
 * runs under it; and while no [tapHintRun] holds a pill [pillPx] tall. The
 * pill is centred on that run and never leaves it.
 */
internal fun tapHintTop(frame: RenderFrame, viewport: Viewport, pillPx: Int, stillMs: Long): Int? {
    val cell = viewport.cellHeightPx
    if (stillMs < TAP_HINT_SETTLE_MS || pillPx <= 0 || !(cell > 0f) || !cell.isFinite()) return null
    val run = tapHintRun(frame, viewport, ceil(pillPx / cell).toInt()) ?: return null
    val runTop = viewport.offsetYPx + run.first * cell
    val runBottom = viewport.offsetYPx + (run.last + 1) * cell
    val top = ((runTop + runBottom - pillPx) / 2f).roundToInt()
    return top.takeIf { it >= runTop - ROUND_SLACK_PX && it + pillPx <= runBottom + ROUND_SLACK_PX }
}

/**
 * The lowest [rows] consecutive frame rows [viewport] drew wholly on which
 * [TerminalView] paints nothing inside [tapHintArea], so the hint covers no
 * output and no prompt. Only the area counts: Herdr draws a zoomed pane's
 * border as box glyphs on every interior row, and those must not make the
 * row painted (QF9-R). The cursor's row is never blank: it is where typing
 * lands. Null when there are no such rows, or when [viewport] was drawn from
 * a frame of another height.
 */
internal fun tapHintRun(frame: RenderFrame, viewport: Viewport, rows: Int): IntRange? {
    if (viewport.frameRows != frame.rows || rows <= 0) return null
    val area = tapHintArea(frame)
    val left = area.x
    val right = area.x + area.width
    val painted = BooleanArray(frame.rows)
    for (run in frame.runs) {
        if (run.row in painted.indices && run.col < right && run.col + run.cells > left && paints(run, frame.background)) painted[run.row] = true
    }
    var blank = 0
    for (row in min(viewport.last, area.y + area.height - 1) downTo max(viewport.first, area.y)) {
        blank = if (row == frame.cursor.y || painted[row]) 0 else blank + 1
        if (blank == rows) return row until row + rows
    }
    return null
}

/**
 * Where the hint may go, in frame cells: the focused pane's inner rect within
 * the frame, the rect paneHasNoRoom reads. With no focused pane, the whole
 * frame, where a border on the same rows still counts as painted.
 */
private fun tapHintArea(frame: RenderFrame): RenderFrame.CellRect {
    val whole = RenderFrame.CellRect(0, 0, frame.cols, frame.rows)
    val rect = frame.panes.firstOrNull { it.focused }?.rect ?: return whole
    val x = rect.x.coerceIn(0, frame.cols)
    val y = rect.y.coerceIn(0, frame.rows)
    return RenderFrame.CellRect(x, y, (min(frame.cols, rect.x + rect.width) - x).coerceAtLeast(0), (min(frame.rows, rect.y + rect.height) - y).coerceAtLeast(0))
}

/**
 * The view pixels the pill may span across, whole pixels inside
 * [tapHintArea]'s columns, so it is centred on the pane and never sits over
 * its border.
 */
internal fun tapHintSpanPx(frame: RenderFrame, viewport: Viewport): IntRange {
    val area = tapHintArea(frame)
    val start = ceil(area.x * viewport.cellWidthPx).toInt()
    val end = floor((area.x + area.width) * viewport.cellWidthPx).toInt()
    return start until max(start, end)
}

/**
 * The terminal's contentDescription (QF-9b S2): [window], what output focus
 * hides, and the hint's [sentence] only while the hint is [wanted] but hidden
 * ([top] null). While the pill shows, it is its own node and the terminal
 * carries no copy, so TalkBack never reads the sentence twice.
 */
internal fun terminalDescription(window: String?, wanted: Boolean, top: Int?, sentence: String): String? =
    listOfNotNull(window, sentence.takeIf { wanted && top == null }).joinToString(" ").ifEmpty { null }

/**
 * Whether [a] and [b] look the same on screen (QF9-R): every field TerminalView
 * draws. Herdr resends unchanged surfaces and each one arrives as a new frame,
 * so the hint's settle compares this, not identity, or an idle pane never
 * stays still for [TAP_HINT_SETTLE_MS]. Walks without allocating and stops at
 * the first difference: it runs on every frame.
 */
internal fun sameLook(a: RenderFrame?, b: RenderFrame?): Boolean {
    if (a === b) return true
    if (a == null || b == null) return false
    if (a.cols != b.cols || a.rows != b.rows || a.cursor != b.cursor) return false
    if (a.foreground != b.foreground || a.background != b.background || a.cursorColor != b.cursorColor) return false
    if (a.popup != b.popup || a.panes != b.panes || a.runs.size != b.runs.size) return false
    for (i in a.runs.indices) {
        val x = a.runs[i]
        val y = b.runs[i]
        if (x.row != y.row || x.col != y.col || x.cells != y.cells || x.flags != y.flags) return false
        if (x.fg != y.fg || x.bg != y.bg || x.text != y.text) return false
    }
    return true
}

/**
 * Whether TerminalView's onDraw puts any pixel of [run] on screen. A
 * background equal to the frame's own [background] paints over the fill with
 * the same colour, so it shows nothing: Herdr sends explicit backgrounds on
 * cells that look blank, and those rows must still hold the hint (QF9-R).
 */
private fun paints(run: RenderFrame.Run, background: Int): Boolean =
    run.has(RenderFrame.BACKGROUND) && run.bg != background ||
        !run.has(RenderFrame.INVISIBLE) &&
        (run.text.isNotBlank() || run.has(RenderFrame.UNDERLINE) || run.has(RenderFrame.STRIKETHROUGH))
