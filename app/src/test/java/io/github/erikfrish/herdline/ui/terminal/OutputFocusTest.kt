package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Output focus suggests and moves a window over the grid; it never trusts the content beyond a scroll hint. */
class OutputFocusTest {
    private val width = 40
    private val label = "╭─ model › cwd › \$0.21 "
    private val border = label + "─".repeat(width - label.length - 1) + "╮"

    /** A one-pane grid of [lines], top first, with the cursor at [cursorRow]. */
    private fun frame(lines: List<String>, cursorRow: Int, cursorVisible: Boolean = true): Pair<RenderFrame, RenderFrame.PaneArea> {
        val pane = RenderFrame.PaneArea("p", RenderFrame.CellRect(0, 0, width, lines.size), focused = true, mouseReporting = false, alternateScreen = false)
        val runs = lines.mapIndexedNotNull { row, text ->
            text.takeIf { it.isNotBlank() }?.let { RenderFrame.Run(row, 0, it.length, 0, 0, 0, it) }
        }
        val cursor = RenderFrame.Cursor(2, cursorRow, cursorVisible, RenderFrame.CURSOR_BLOCK)
        return RenderFrame(width, lines.size, cursor, 0, 0, 0, null, listOf(pane), runs) to pane
    }

    private fun output(count: Int) = List(count) { "output line $it" }

    @Test
    fun theBoxTheCursorSitsInIsSuggestedDownToTheBottom() {
        // OMP: the top border, the input line with the cursor, two lines under it.
        val (f, pane) = frame(output(8) + listOf(border, "> typing", "status one", "status two"), cursorRow = 9)
        assertEquals(4, suggestedHidden(f, pane))
    }

    @Test
    fun noBorderNearTheCursorMeansNoSuggestion() {
        val (f, pane) = frame(output(10) + listOf("> typing", ""), cursorRow = 10)
        assertNull(suggestedHidden(f, pane))
        val (hiddenCursor, p2) = frame(output(8) + listOf(border, "> typing", "", ""), cursorRow = 9, cursorVisible = false)
        assertNull(suggestedHidden(hiddenCursor, p2))
    }

    @Test
    fun aBorderInTheOutputAboveTheBoxNeverMovesTheCutHigher() {
        // A box-drawing table in the output, then the real box: the nearest border wins.
        val (f, pane) = frame(output(4) + listOf(border, "│ table │", "└───────┘", border, "> typing", "", ""), cursorRow = 8)
        assertEquals(4, suggestedHidden(f, pane))
        // A border four rows above the cursor is beyond reach: no output row is hidden.
        val (far, p2) = frame(output(4) + listOf(border, "output a", "output b", "output c", "> typing", "", ""), cursorRow = 8)
        assertNull(suggestedHidden(far, p2))
    }

    @Test
    fun aSuggestionLeavesAtLeastThreeRowsAndHidesAtMostEight() {
        // A 6-row grid may hide 3 at most: a 4-row footer is no suggestion.
        val (small, p1) = frame(listOf("out", "out", border, "> typing", "", ""), cursorRow = 3)
        assertNull(suggestedHidden(small, p1))
        val (fits, p2) = frame(listOf("out", "out", "out", border, "> typing", ""), cursorRow = 4)
        assertEquals(3, suggestedHidden(fits, p2))
        assertEquals(8, maxHidden(40))
    }

    @Test
    fun aBorderShorterThanThePaneIsNotABox() {
        val (f, pane) = frame(output(8) + listOf("╭── short ──╮", "> typing", "", ""), cursorRow = 9)
        assertNull(suggestedHidden(f, pane))
    }

    @Test
    fun theWindowOnlyEverShrinksTowardTheFullTerminal() {
        val armed = armedWindow("p", 4)
        assertEquals(4, armed.hidden)
        // A larger suggestion later never hides more; a smaller one or none shows more.
        assertEquals(4, armed.next("p", 6, selecting = false, gridRows = 20).hidden)
        assertEquals(2, armed.next("p", 2, selecting = false, gridRows = 20).hidden)
        assertEquals(0, armed.next("p", null, selecting = false, gridRows = 20).hidden)
        // A grid too short for the window clamps it.
        assertEquals(2, armed.next("p", 4, selecting = false, gridRows = 5).hidden)
    }

    @Test
    fun aDragOrASelectionHoldsTheWindowAndAPaneChangeStartsOver() {
        val (dragged, used) = armedWindow("p", 4).drag(1)
        assertEquals(1, used)
        assertEquals(3, dragged.hidden)
        // After a drag no suggestion moves it, not even toward full.
        assertEquals(3, dragged.next("p", null, selecting = false, gridRows = 20).hidden)
        assertEquals(4, armedWindow("p", 4).next("p", null, selecting = true, gridRows = 20).hidden)
        // A drag toward scrollback goes to Herdr whole.
        assertEquals(0, armedWindow("p", 4).drag(-3).second)
        // Past the hidden rows, the rest of a drag goes to Herdr.
        assertEquals(4 to 0, armedWindow("p", 4).drag(9).let { it.second to it.first.hidden })
        val moved = dragged.next("q", 2, selecting = false, gridRows = 20)
        assertEquals("q" to 2, moved.pane to moved.hidden)
        assertFalse(moved.dragged)
    }

    @Test
    fun aWindowArmedOnAFrameWithoutTheBoxTakesTheFirstMatch() {
        // HG-vis-of: F armed mid-transition, before the box was redrawn, used to stay at 0 for good.
        val early = armedWindow("p", null, rows = 20)
        assertEquals(0, early.hidden)
        val still = early.next("p", null, selecting = false, gridRows = 20)
        assertEquals(0, still.hidden)
        assertEquals(4, still.next("p", 4, selecting = false, gridRows = 20).hidden)
    }

    @Test
    fun aNewGridHeightStartsOverFromItsSuggestion() {
        // The IME or the side composer moving resizes the grid: its unmatched frames never pin 0.
        val seeded = armedWindow("p", 4, rows = 20)
        val resizing = seeded.next("p", null, selecting = false, gridRows = 12)
        assertEquals(0, resizing.hidden)
        assertEquals(4, resizing.next("p", 4, selecting = false, gridRows = 12).hidden)
        // A drag still holds across a resize, clamped to the new grid.
        val (dragged, _) = seeded.drag(1)
        assertEquals(3, dragged.next("p", null, selecting = false, gridRows = 12).hidden)
        assertEquals(2, dragged.next("p", 4, selecting = false, gridRows = 5).hidden)
    }

    @Test
    fun aSeededWindowOnTheSameGridStillOnlyShrinks() {
        // Once the box is gone on a settled grid the full terminal stays: a later match never hides rows again.
        val gone = armedWindow("p", 4, rows = 20).next("p", null, selecting = false, gridRows = 20)
        assertEquals(0, gone.hidden)
        assertEquals(0, gone.next("p", 4, selecting = false, gridRows = 20).hidden)
        assertEquals(4, armedWindow("p", 4, rows = 20).next("p", 6, selecting = false, gridRows = 20).hidden)
    }
}
