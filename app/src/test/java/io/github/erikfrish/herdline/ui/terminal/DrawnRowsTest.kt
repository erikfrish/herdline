package io.github.erikfrish.herdline.ui.terminal

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
        assertEquals(-2f * cell, viewOffset(8 * cell.toInt(), 0, 0, cell, 10))
        assertEquals(0f, viewOffset(12 * cell.toInt(), 0, 0, cell, 10))
        // Output focus: two rows scrolled past the bottom of a view that fits.
        assertEquals(2f * cell, viewOffset(10 * cell.toInt(), 0, 2, cell, 10))
        // A strip over the bottom row's space, nothing hidden: one row up.
        assertEquals(-cell, viewOffset(10 * cell.toInt(), cell.toInt(), 0, cell, 10))
    }
}
