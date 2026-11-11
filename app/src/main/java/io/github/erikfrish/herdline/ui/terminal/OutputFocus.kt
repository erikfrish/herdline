package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import kotlin.math.min

/** The most rows output focus ever hides. */
internal const val MAX_HIDDEN_ROWS = 8

/** The rows of the grid output focus always leaves visible. */
internal const val MIN_SHOWN_ROWS = 3

/** The most rows between a matched top border and the cursor's row. */
internal const val MAX_BORDER_DISTANCE = 3

/** A matched top border fills at least this share of the pane's width. */
private const val BORDER_SHARE = 0.8f

private val TOP_LEFT = setOf('╭', '┌', '┏', '╔')
private val TOP_RIGHT = setOf('╮', '┐', '┓', '╗')

/** The most rows output focus may hide in a grid of [gridRows] rows. */
internal fun maxHidden(gridRows: Int): Int = min(MAX_HIDDEN_ROWS, gridRows - MIN_SHOWN_ROWS)

/**
 * How many rows at the grid's bottom output focus suggests hiding, or null
 * when nothing matches.
 *
 * A best-effort scroll suggestion from untrusted content: it never proves
 * what the hidden rows hold, so nothing calls them by a name, and the strip
 * counts them and shows them again in one tap. A match needs [pane] to be
 * the grid's bottom-most pane with the cursor visible inside it, and a
 * full-width top border (a corner pair over [BORDER_SHARE] of the pane's
 * width) at most [MAX_BORDER_DISTANCE] rows above the cursor. The scan goes
 * up from the cursor and takes the nearest border, so a border-looking line
 * further up in the output never moves the cut higher. The result lies in
 * 1..[maxHidden]; anything else is no suggestion.
 */
internal fun suggestedHidden(frame: RenderFrame, pane: RenderFrame.PaneArea): Int? {
    val rect = pane.rect
    val bottom = rect.y + rect.height
    if (bottom != frame.rows) return null
    val cursor = frame.cursor
    if (!cursor.visible || !rect.contains(cursor.x, cursor.y)) return null
    val lowest = maxOf(rect.y, cursor.y - MAX_BORDER_DISTANCE)
    val border = (cursor.y - 1 downTo lowest).firstOrNull { isTopBorder(frame, rect, it) } ?: return null
    return (bottom - border).takeIf { it in 1..maxHidden(frame.rows) }
}

/**
 * The output-focus window over [pane]'s grid: [hidden] rows at the bottom
 * are scrolled past, never removed. [dragged] once the user moved it, after
 * which no suggestion moves it again until F is armed anew. [seeded] once a
 * suggestion matched; until then each frame tries again. [rows] is the grid
 * height it was last fitted to, null before the first frame.
 */
internal data class OutputWindow(
    val pane: String?,
    val hidden: Int,
    val dragged: Boolean = false,
    val seeded: Boolean = false,
    val rows: Int? = null,
)

/** The window right after the user arms F, or the pane or grid changes: the validated suggestion, else nothing hidden. */
internal fun armedWindow(pane: String?, suggestion: Int?, rows: Int? = null): OutputWindow =
    OutputWindow(pane, suggestion ?: 0, seeded = suggestion != null, rows = rows)

/**
 * The window after a new frame. A pane change, a new grid height or a
 * window not yet seeded starts over from [suggestion], so a frame caught
 * mid-transition (the IME or the side composer still moving, the box not
 * yet redrawn) never leaves it at nothing hidden for good (HG-vis-of).
 * Otherwise a seeded window only ever shrinks toward the full terminal, to
 * the new suggestion or to nothing hidden when none matches, and it holds
 * still while the user dragged it or holds a selection.
 */
internal fun OutputWindow.next(pane: String?, suggestion: Int?, selecting: Boolean, gridRows: Int): OutputWindow {
    if (pane != this.pane) return armedWindow(pane, suggestion, gridRows)
    val bound = hidden.coerceAtMost(maxHidden(gridRows).coerceAtLeast(0))
    if (dragged || selecting) return copy(hidden = bound, rows = gridRows)
    if (!seeded || (rows != null && rows != gridRows)) return armedWindow(pane, suggestion, gridRows)
    return copy(hidden = min(bound, suggestion ?: 0), rows = gridRows)
}

/**
 * A user drag of [lines] toward the bottom (negative: toward scrollback).
 * Toward the bottom it first shows hidden rows; returns the window and how
 * many of the lines it used, the rest going to Herdr's scroll as before.
 */
internal fun OutputWindow.drag(lines: Int): Pair<OutputWindow, Int> {
    val used = if (lines > 0) min(lines, hidden) else 0
    return copy(hidden = hidden - used, dragged = true) to used
}

/** Whether [row] of [rect] reads as a box's top border: a corner pair spanning most of the width. */
private fun isTopBorder(frame: RenderFrame, rect: RenderFrame.CellRect, row: Int): Boolean {
    val text = rowText(frame, rect, row)
    val start = text.indexOfFirst { !it.isWhitespace() }
    val end = text.indexOfLast { !it.isWhitespace() }
    if (start < 0 || text[start] !in TOP_LEFT || text[end] !in TOP_RIGHT) return false
    return end - start + 1 >= rect.width * BORDER_SHARE
}

/** The text [rect] shows on [row], one char per cell, blank where nothing is drawn. */
private fun rowText(frame: RenderFrame, rect: RenderFrame.CellRect, row: Int): CharArray {
    val cells = CharArray(rect.width) { ' ' }
    frame.runs.forEach { run ->
        if (run.row != row) return@forEach
        var col = run.col
        var i = 0
        while (i < run.text.length && col < run.col + run.cells) {
            val cp = run.text.codePointAt(i)
            val at = col - rect.x
            if (at in cells.indices && Character.charCount(cp) == 1) cells[at] = cp.toChar()
            col += if (run.has(RenderFrame.WIDE)) 2 else 1
            i += Character.charCount(cp)
        }
    }
    return cells
}
