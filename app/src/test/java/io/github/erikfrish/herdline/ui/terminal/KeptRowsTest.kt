package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V4-a: with a draft in the composer, the composer's notice comes and goes
 * with the native check, and a grid that followed the composer's height
 * resized the pane each time; each resize refused Send until Herdr drew it.
 */
class KeptRowsTest {
    private val cell = 40f
    private val rows = 34
    private val noticePx = 80

    /**
     * Portrait, keyboard down, a draft in the composer, OMP's box on the
     * pane's bottom 4 rows. Each step lays out the terminal under the
     * notice the last check left, reports the grid, checks Send as the
     * native gate does (Herdr drew the grid the phone asked for, and the
     * whole box is drawn), and lets Herdr answer the request.
     */
    private class Loop(private val keep: Boolean, private val cell: Float, private val rows: Int, private val noticePx: Int) {
        var asked = 0
        private var drawn = 0
        private var notice = false
        val askedSeen = mutableSetOf<Int>()

        fun step(): Boolean {
            val view = (rows * cell).toInt() - if (notice) noticePx else 0
            asked = gridRows(view, cell, hold = false, holdHeightPx = 0, lastRows = asked, capPx = 0f, keep = keep)
            askedSeen += asked
            val box = drawn - 4 until drawn
            val shown = drawnRows(view.toFloat(), viewOffset(view, cell, drawn), cell, drawn)
            val ok = drawn == asked && shown != null && box.first in shown && box.last in shown
            notice = !ok
            drawn = asked
            return ok
        }
    }

    @Test
    fun theNoticeNoLongerResizesThePaneItGatesAndSendSettles() {
        val before = Loop(keep = false, cell, rows, noticePx)
        val gated = List(30) { before.step() }
        // The reproduction: the pane swings by the notice's two rows and Send never holds.
        assertEquals(setOf(rows, rows - 2), before.askedSeen)
        assertTrue(gated.takeLast(20).any { !it })

        val fixed = Loop(keep = true, cell, rows, noticePx)
        val sends = List(30) { fixed.step() }
        assertEquals(setOf(rows), fixed.askedSeen)
        assertTrue(sends.drop(2).all { it })
    }

    @Test
    fun keptRowsStillGrowIntoATallerView() {
        assertEquals(30, gridRows((28 * cell).toInt(), cell, hold = false, holdHeightPx = 0, lastRows = 30, capPx = 0f, keep = true))
        assertEquals(33, gridRows((33 * cell).toInt(), cell, hold = false, holdHeightPx = 0, lastRows = 30, capPx = 0f, keep = true))
        // An unconfirmed delivery's cap still bounds them.
        assertEquals(29, gridRows((33 * cell).toInt(), cell, hold = false, holdHeightPx = 0, lastRows = 30, capPx = 29 * cell, keep = true))
    }

    @Test
    fun aShorterViewOfKeptRowsCountsOnlyTheWholeRowsItDraws() {
        // 32.5 rows of a 34-row frame: the bottom-aligned frame cuts row 1, and only rows 2..33 count.
        val view = (32.5f * cell).toInt()
        assertEquals(2..33, drawnRows(view.toFloat(), viewOffset(view, cell, rows), cell, rows))
    }

    @Test
    fun aNewWidthOrFontRefitsTheGrid() {
        val last = intArrayOf(54, 34, 20, 40)
        assertTrue(keepsRows(true, 54, 20, 40, last))
        assertFalse(keepsRows(false, 54, 20, 40, last))
        assertFalse(keepsRows(true, 98, 20, 40, last))
        assertFalse(keepsRows(true, 49, 22, 44, last))
        assertFalse(keepsRows(true, 54, 20, 44, last))
        assertFalse(keepsRows(true, 54, 20, 40, IntArray(4)))
    }
}
