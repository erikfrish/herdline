package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Where [TerminalView] draws a frame's row 0, in px from the view's top: the
 * frame's bottom on the view's bottom while it is taller than the view. The
 * native core recomputes it with the same arithmetic (`visible::view_offset_at`,
 * with no hidden rows and no bottom clip, which the app always reports) before
 * it trusts a viewport, so the two must stay identical.
 *
 * [cursorRow] is the row to keep in view ([shownAnchorRow]), else null. A row
 * the bottom anchor would leave above the view is put on its top row instead
 * (A-27P): a held or not-yet-refitted grid taller than the view never hides
 * the prompt the keyboard types into, nor the focused pane (QF9-U2). Whole
 * rows, never past row 0 nor below the anchor.
 */
internal fun viewOffset(viewHeightPx: Int, cellPx: Float, frameRows: Int, cursorRow: Int? = null): Float {
    val anchor = min(0f, viewHeightPx - frameRows * cellPx)
    return if (cursorRow == null) anchor else max(anchor, -cursorRow * cellPx)
}

/**
 * The row [viewOffset] keeps in view for [frame] in a view [viewHeightPx]
 * tall (QF9-U2). The base is the shown cursor's row (visible, inside the
 * frame), else null (A-27P). The cursor inside the focused pane's inner rect
 * is kept. Otherwise, when the window the base gives draws no row of the
 * focused pane, its top inner row is kept, so an unzoomed focused pane is
 * never left off screen. A focused pane already drawn, or none, keeps the
 * base. The native core takes the same row (`visible::shown_anchor_row`).
 */
internal fun shownAnchorRow(frame: RenderFrame, viewHeightPx: Int, cellPx: Float): Int? {
    val cursor = frame.cursor
    val shown = if (cursor.visible && cursor.x < frame.cols && cursor.y < frame.rows) cursor.y else null
    val pane = frame.panes.firstOrNull { it.focused }?.rect ?: return shown
    val top = max(0, pane.y)
    val bottom = min(frame.rows, pane.y + pane.height) - 1
    if (bottom < top) return shown
    if (shown != null && pane.contains(cursor.x, cursor.y)) return shown
    val offset = viewOffset(viewHeightPx, cellPx, frame.rows, shown)
    val drawn = drawnRows(viewHeightPx.toFloat(), offset, cellPx, frame.rows)
    return if (drawn != null && drawn.first <= bottom && drawn.last >= top) shown else top
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
