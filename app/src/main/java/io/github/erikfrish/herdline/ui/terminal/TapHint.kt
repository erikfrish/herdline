package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.Viewport

/**
 * The row the tap-to-type hint sits over (QF-9): the lowest frame row
 * [viewport] drew wholly on which [TerminalView] paints nothing, so the hint
 * covers no output and no prompt. The cursor's row is never blank: it is
 * where typing lands. Null when no drawn row is blank, or when [viewport] was
 * drawn from a frame of another height.
 */
internal fun tapHintRow(frame: RenderFrame, viewport: Viewport): Int? {
    if (viewport.frameRows != frame.rows) return null
    val painted = BooleanArray(frame.rows)
    for (run in frame.runs) if (run.row in painted.indices && paints(run)) painted[run.row] = true
    return (viewport.last downTo viewport.first).firstOrNull { it != frame.cursor.y && !painted[it] }
}

/** The centre of [row] in the view's pixels, as [viewport] drew it. */
internal fun rowCentrePx(viewport: Viewport, row: Int): Float =
    viewport.offsetYPx + (row + 0.5f) * viewport.cellHeightPx

/** Whether TerminalView's onDraw puts any pixel of [run] on screen. */
private fun paints(run: RenderFrame.Run): Boolean =
    run.has(RenderFrame.BACKGROUND) ||
        !run.has(RenderFrame.INVISIBLE) &&
        (run.text.isNotBlank() || run.has(RenderFrame.UNDERLINE) || run.has(RenderFrame.STRIKETHROUGH))
