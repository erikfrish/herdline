package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The rows and offset TerminalView reports are the ones the native core
 * recomputes (`visible::drawn_rows`, `visible::view_offset`); any drift
 * refuses every delivery, so the same cases hold on both sides (HG-VIS).
 */
class DrawnRowsTest {
    private val cell = 20f

    @Test
    fun onlyWhollyDrawnRowsCount() {
        assertEquals(0..9, drawnRows(10 * cell, 0f, cell, 10))
        // Cut by a pixel at either edge: that row is out.
        assertEquals(1..9, drawnRows(10 * cell, -1f, cell, 10))
        assertEquals(0..8, drawnRows(10 * cell - 1f, 0f, cell, 10))
        assertEquals(1..9, drawnRows(10 * cell, -cell / 2f, cell, 10))
        // Drawn lower than the view's top: the rows run off its bottom.
        assertEquals(0..7, drawnRows(10 * cell, 2f * cell, cell, 10))
        // A cell as fonts measure it, not a whole pixel.
        assertEquals(0..25, drawnRows(1000f, 0f, 37.4f, 40))
        assertNull(drawnRows(cell - 1f, 0f, cell, 10))
        assertNull(drawnRows(10 * cell, 0f, 0f, 10))
        assertNull(drawnRows(10 * cell, 0f, Float.NaN, 10))
    }

    @Test
    fun theOffsetIsTheOneTheViewDrawsAt() {
        // A held grid two rows taller than the view: its top two rows are cut.
        assertEquals(-2f * cell, viewOffset(8 * cell.toInt(), cell, 10))
        assertEquals(0f, viewOffset(12 * cell.toInt(), cell, 10))
    }

    @Test
    fun aShownCursorAboveTheWindowIsItsTopRow() {
        // A held grid two rows taller than the view, the prompt on its top row
        // (a fresh shell): drawn from row 0, the prompt's row whole.
        assertEquals(0f, viewOffset(8 * cell.toInt(), cell, 10, cursorRow = 0))
        assertEquals(0..7, drawnRows(8 * cell, viewOffset(8 * cell.toInt(), cell, 10, cursorRow = 0), cell, 10))
        assertEquals(-cell, viewOffset(8 * cell.toInt(), cell, 10, cursorRow = 1))
        // Cut by half a row under the anchor: brought whole, by whole rows.
        assertEquals(-cell, viewOffset((8.5f * cell).toInt(), cell, 10, cursorRow = 1))
    }

    @Test
    fun aCursorInTheWindowOrHiddenKeepsTheOffset() {
        // Already whole under the bottom anchor: the anchor stays.
        assertEquals(-2f * cell, viewOffset(8 * cell.toInt(), cell, 10, cursorRow = 2))
        assertEquals(-2f * cell, viewOffset(8 * cell.toInt(), cell, 10, cursorRow = 9))
        assertEquals(-1.5f * cell, viewOffset((8.5f * cell).toInt(), cell, 10, cursorRow = 2))
        // No cursor shown: the anchor.
        assertEquals(-2f * cell, viewOffset(8 * cell.toInt(), cell, 10, cursorRow = null))
        // A frame no taller than the view: from the top, wherever the cursor.
        assertEquals(0f, viewOffset(12 * cell.toInt(), cell, 10, cursorRow = 0))
        assertEquals(0f, viewOffset(10 * cell.toInt(), cell, 10, cursorRow = 9))
    }

    /**
     * Ten rows, 20 columns: [focused]'s inner rect on rows 1-3 over another
     * pane's on rows 5-8, the surface cursor at column 2 of [cursorRow].
     */
    private fun split(cursorRow: Int = 7, cursorVisible: Boolean = true, focused: Int? = 0): RenderFrame {
        val rects = listOf(RenderFrame.CellRect(1, 1, 18, 3), RenderFrame.CellRect(1, 5, 18, 4))
        val panes = rects.mapIndexed { i, rect -> RenderFrame.PaneArea("p$i", rect, focused = i == focused, mouseReporting = false, alternateScreen = false) }
        val cursor = RenderFrame.Cursor(2, cursorRow, cursorVisible, RenderFrame.CURSOR_BLOCK)
        return RenderFrame(20, 10, cursor, 0, 0, 0, null, panes, emptyList())
    }

    /** The offset TerminalView draws [frame] at in a view [rows] cells tall. */
    private fun drawnAt(frame: RenderFrame, rows: Int): Float {
        val height = rows * cell.toInt()
        return viewOffset(height, cell, frame.rows, shownAnchorRow(frame, height, cell))
    }

    @Test
    fun aFocusedPaneAboveTheWindowIsDrawnFromItsTop() {
        // Four rows tall: the cursor in the other pane keeps rows 6-9, none
        // of the focused pane's. Its top inner row is kept instead (QF9-U2).
        assertEquals(1, shownAnchorRow(split(), 4 * cell.toInt(), cell))
        assertEquals(-cell, drawnAt(split(), 4))
        assertEquals(1..4, drawnRows(4 * cell, drawnAt(split(), 4), cell, 10))
        // No cursor shown: the bottom anchor drew rows 6-9 alone.
        assertEquals(1, shownAnchorRow(split(cursorVisible = false), 4 * cell.toInt(), cell))
        // Taller than the view (limit): its top, the rest cut below.
        assertEquals(1..2, drawnRows(2 * cell, drawnAt(split(), 2), cell, 10))
    }

    @Test
    fun aCursorInsideTheFocusedPaneWins() {
        assertEquals(2, shownAnchorRow(split(cursorRow = 2), 4 * cell.toInt(), cell))
        assertEquals(-2f * cell, drawnAt(split(cursorRow = 2), 4))
        assertEquals(3, shownAnchorRow(split(cursorRow = 3), 2 * cell.toInt(), cell))
    }

    @Test
    fun aFocusedPaneAlreadyDrawnKeepsTheWindow() {
        // Eight rows tall: rows 2-9 hold the focused pane's rows 2-3.
        assertEquals(7, shownAnchorRow(split(), 8 * cell.toInt(), cell))
        assertEquals(viewOffset(8 * cell.toInt(), cell, 10, cursorRow = 7), drawnAt(split(), 8))
        // Zoomed: the focused pane fills the frame, so every window holds it.
        val zoomed = RenderFrame(
            20, 10, RenderFrame.Cursor(2, 7, false, RenderFrame.CURSOR_BLOCK), 0, 0, 0, null,
            listOf(RenderFrame.PaneArea("p", RenderFrame.CellRect(1, 1, 18, 8), focused = true, mouseReporting = false, alternateScreen = false)),
            emptyList(),
        )
        assertNull(shownAnchorRow(zoomed, 4 * cell.toInt(), cell))
        assertEquals(viewOffset(4 * cell.toInt(), cell, 10), drawnAt(zoomed, 4))
    }

    @Test
    fun noFocusedPaneKeepsTheShownCursorRule() {
        assertEquals(7, shownAnchorRow(split(focused = null), 4 * cell.toInt(), cell))
        assertNull(shownAnchorRow(split(focused = null, cursorVisible = false), 4 * cell.toInt(), cell))
        assertNull(shownAnchorRow(split(focused = null, cursorRow = 10), 4 * cell.toInt(), cell))
        assertEquals(viewOffset(4 * cell.toInt(), cell, 10, cursorRow = 7), drawnAt(split(focused = null), 4))
    }

    @Test
    fun theAnchorRowIsTheNativeCores() {
        // Same frame, view and cell as visible::tests::the_anchor_row_is_kotlins.
        assertEquals(1, shownAnchorRow(split(), 80, 20f))
        assertEquals(-20f, drawnAt(split(), 4))
        assertEquals(2, shownAnchorRow(split(cursorRow = 2), 80, 20f))
        assertEquals(7, shownAnchorRow(split(), 160, 20f))
    }
}
