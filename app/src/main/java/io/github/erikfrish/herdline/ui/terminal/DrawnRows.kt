package io.github.erikfrish.herdline.ui.terminal

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Where [TerminalView] draws a frame's row 0, in px from the view's top: the
 * frame's bottom on the view's bottom while it is taller than the view; under
 * output focus, [hiddenRows] scrolled past the bottom of the area above the
 * [clipBottomPx] strip, by whole rows so the top row is never cut. The native
 * core recomputes it with the same arithmetic (`visible::view_offset`) before
 * it trusts a viewport, so the two must stay identical.
 */
internal fun viewOffset(viewHeightPx: Int, clipBottomPx: Int, hiddenRows: Int, cellPx: Float, frameRows: Int): Float {
    if (hiddenRows <= 0 && clipBottomPx <= 0) return min(0f, viewHeightPx - frameRows * cellPx)
    val fits = floor((viewHeightPx - clipBottomPx) / cellPx).toInt()
    return (fits - (frameRows - hiddenRows.coerceAtMost(frameRows - 1))) * cellPx
}

/**
 * The frame rows wholly inside `0..heightPx` when row 0 is drawn at
 * [offsetPx]; a row cut by any pixel is not one of them. Null when none is
 * whole. Same arithmetic as the native `visible::drawn_rows`.
 */
internal fun drawnRows(heightPx: Float, offsetPx: Float, cellPx: Float, frameRows: Int): IntRange? {
    if (!(cellPx > 0f) || !cellPx.isFinite() || frameRows <= 0 || !heightPx.isFinite() || !offsetPx.isFinite()) return null
    val first = ceil((-offsetPx - EDGE_SLACK_PX) / cellPx).coerceAtLeast(0f)
    val last = (floor((heightPx - offsetPx + EDGE_SLACK_PX) / cellPx) - 1f).coerceAtMost(frameRows - 1f)
    if (last < first) return null
    return first.toInt()..last.toInt()
}

/** Float error allowed at a row's edge; far below a pixel. */
private const val EDGE_SLACK_PX = 0.01f
