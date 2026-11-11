package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * RZ-u: with no draft held, transient chrome (a composer or receipt notice,
 * the sent confirmation, the pane bar, the short-window header) moved the
 * terminal's height, and the grid followed it: each resize refused Send
 * until Herdr drew it. Only the window, the keyboard or the font resize it.
 */
class ChromeRowsTest {
    private val cell = 40f
    private val rows = 34
    private val noticePx = 80

    /** The rows reported for a view [px] tall, from [last], when [chrome] alone moved it. */
    private fun report(px: Int, last: Int, chrome: Boolean, keep: Boolean = false, capPx: Float = 0f) =
        gridRows(px, cell, hold = false, holdHeightPx = 0, lastRows = last, capPx = capPx, keep = keep, chrome = chrome)

    @Test
    fun aNoticeComingAndGoingNeverResizesThePane() {
        val full = (rows * cell).toInt()
        val asked = mutableListOf(report(full, 0, chrome = false))
        for (px in listOf(full - noticePx, full, full - noticePx, full)) asked += report(px, asked.last(), chrome = true)
        assertEquals(listOf(rows, rows, rows, rows, rows), asked)
        // The reproduction: fitted each time, the pane swung by the notice's two rows.
        assertEquals(rows - 2, report(full - noticePx, rows, chrome = false))
    }

    @Test
    fun theKeyboardAndTheWindowStillResizeIt() {
        val keyboardUp = ((rows - 12) * cell).toInt()
        assertEquals(rows - 12, report(keyboardUp, rows, chrome = false))
        assertEquals(rows, report((rows * cell).toInt(), rows - 12, chrome = false))
    }

    @Test
    fun chromeNeverOutranksTheHoldOrTheFreeze() {
        // A delivery's freeze still caps the grid while chrome moves (HG-1).
        val capPx = (rows - 3) * cell
        assertEquals(rows - 3, report((rows * cell).toInt(), rows, chrome = true, capPx = capPx))
        // A held draft's kept rows grow into a taller window, not into chrome.
        assertEquals(rows, report(((rows + 2) * cell).toInt(), rows, chrome = true, keep = true))
        assertEquals(rows + 2, report(((rows + 2) * cell).toInt(), rows, chrome = false, keep = true))
    }
}
