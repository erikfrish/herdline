package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** QF-9: the tap-to-type hint moves over the lowest wholly drawn blank row, never over output. */
class TapHintTest {
    private val cell = 20f

    private fun run(row: Int, text: String, flags: Int = 0) = RenderFrame.Run(row, 0, text.length, flags, 0, 0, text)

    /** A 6-row frame, the cursor at row [cursorRow], with text on [written]. */
    private fun frame(cursorRow: Int, vararg written: Int, extra: List<RenderFrame.Run> = emptyList()) = RenderFrame(
        cols = 20,
        rows = 6,
        cursor = RenderFrame.Cursor(0, cursorRow, true, RenderFrame.CURSOR_BLOCK),
        foreground = 0,
        background = 0,
        cursorColor = 0,
        popup = null,
        panes = emptyList(),
        runs = written.map { run(it, "\$ out") } + listOf(run(5, "     ")).filter { 5 !in written } + extra,
    )

    private fun viewport(first: Int = 0, last: Int = 5, offset: Float = 0f, rows: Int = 6) = Viewport(
        frameRows = rows,
        frameCols = 20,
        gridRows = rows,
        gridCols = 20,
        first = first,
        last = last,
        rect = Viewport.Rect(0, 0, 400, 120),
        offsetYPx = offset,
        hiddenRows = 0,
        clipBottomPx = 0,
        cellWidthPx = 10f,
        cellHeightPx = cell,
    )

    @Test
    fun blankRowsAtTheBottomTakeTheLowest() {
        // Output on 0-2, the cursor on 2; 3-5 blank (5 holds only spaces).
        assertEquals(5, tapHintRow(frame(2, 0, 1, 2), viewport()))
        assertEquals(110f, rowCentrePx(viewport(), 5))
    }

    @Test
    fun aBlankRowOnlyInTheMiddleIsTheOne() {
        assertEquals(2, tapHintRow(frame(5, 0, 1, 3, 4, 5), viewport()))
    }

    @Test
    fun noBlankRowShowsNoRow() {
        assertNull(tapHintRow(frame(5, 0, 1, 2, 3, 4, 5), viewport()))
    }

    @Test
    fun theCursorsBlankRowIsNotBlank() {
        // The prompt's cursor on blank row 5: the hint goes one up, never over where typing lands.
        assertEquals(4, tapHintRow(frame(5, 0, 1), viewport()))
        assertNull(tapHintRow(frame(2, 0, 1, 3, 4, 5), viewport()))
    }

    @Test
    fun onlyRowsDrawnWhollyAndUnpaintedCount() {
        // Row 5 cut off the bottom: row 4 is the lowest whole one.
        assertEquals(4, tapHintRow(frame(0, 0), viewport(last = 4)))
        assertEquals(70f, rowCentrePx(viewport(offset = -20f), 4))
        // A coloured bar, an underline on spaces: painted. Invisible text: not.
        val painted = listOf(run(5, "   ", RenderFrame.BACKGROUND), run(4, "  ", RenderFrame.UNDERLINE), run(3, "hidden", RenderFrame.INVISIBLE))
        assertEquals(3, tapHintRow(frame(0, 0, extra = painted), viewport()))
        // A viewport drawn from a frame of another height says nothing about this one.
        assertNull(tapHintRow(frame(0, 0), viewport(rows = 7)))
    }
}
