package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.Viewport
import kotlin.math.ceil
import kotlin.math.roundToInt

/** How long the frame and the view must stay unchanged before the tap-to-type hint is placed (QF-9). */
internal const val TAP_HINT_SETTLE_MS = 300L

/** A rounded pixel the pill may stick past its rows; below what a glyph shows. */
private const val ROUND_SLACK_PX = 0.5f

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
 * [TerminalView] paints nothing, so the hint covers no output and no prompt.
 * The cursor's row is never blank: it is where typing lands. Null when there
 * are no such rows, or when [viewport] was drawn from a frame of another height.
 */
internal fun tapHintRun(frame: RenderFrame, viewport: Viewport, rows: Int): IntRange? {
    if (viewport.frameRows != frame.rows || rows <= 0) return null
    val painted = BooleanArray(frame.rows)
    for (run in frame.runs) if (run.row in painted.indices && paints(run)) painted[run.row] = true
    var blank = 0
    for (row in viewport.last downTo viewport.first) {
        blank = if (row == frame.cursor.y || painted[row]) 0 else blank + 1
        if (blank == rows) return row until row + rows
    }
    return null
}

/**
 * The terminal's contentDescription (QF-9b S2): [window], what output focus
 * hides, and the hint's [sentence] only while the hint is [wanted] but hidden
 * ([top] null). While the pill shows, it is its own node and the terminal
 * carries no copy, so TalkBack never reads the sentence twice.
 */
internal fun terminalDescription(window: String?, wanted: Boolean, top: Int?, sentence: String): String? =
    listOfNotNull(window, sentence.takeIf { wanted && top == null }).joinToString(" ").ifEmpty { null }

/** Whether TerminalView's onDraw puts any pixel of [run] on screen. */
private fun paints(run: RenderFrame.Run): Boolean =
    run.has(RenderFrame.BACKGROUND) ||
        !run.has(RenderFrame.INVISIBLE) &&
        (run.text.isNotBlank() || run.has(RenderFrame.UNDERLINE) || run.has(RenderFrame.STRIKETHROUGH))
