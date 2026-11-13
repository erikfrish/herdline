package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.Viewport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QF-9 / QF-9b: the tap-to-type hint sits over enough wholly drawn blank rows to
 * hold it, never over output, and only once nothing has changed for a while.
 */
class TapHintTest {
    private val cell = 20f

    private fun run(row: Int, text: String, flags: Int = 0) = RenderFrame.Run(row, 0, text.length, flags, 0, 0, text)

    /** A 6-row frame, the cursor at row [cursorRow], with text on [written]. */
    private fun frame(cursorRow: Int, vararg written: Int, extra: List<RenderFrame.Run> = emptyList()) = RenderFrame(
        cols = 20,
        rows = 6,
        cursor = RenderFrame.Cursor(0, cursorRow, true, RenderFrame.CURSOR_BLOCK),
        foreground = 0,
        background = THEME,
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
        cellWidthPx = 10f,
        cellHeightPx = cell,
    )

    @Test
    fun blankRowsAtTheBottomTakeTheLowest() {
        // Output on 0-2, the cursor on 2; 3-5 blank (5 holds only spaces).
        assertEquals(5..5, tapHintRun(frame(2, 0, 1, 2), viewport(), 1))
        // A one-row pill 16 px tall, centred on row 5 (100..120).
        assertEquals(102, tapHintTop(frame(2, 0, 1, 2), viewport(), 16, TAP_HINT_SETTLE_MS))
    }

    @Test
    fun aBlankRowOnlyInTheMiddleIsTheOne() {
        assertEquals(2..2, tapHintRun(frame(5, 0, 1, 3, 4, 5), viewport(), 1))
    }

    @Test
    fun noBlankRowShowsNoRow() {
        assertNull(tapHintRun(frame(5, 0, 1, 2, 3, 4, 5), viewport(), 1))
        assertNull(tapHintTop(frame(5, 0, 1, 2, 3, 4, 5), viewport(), 16, TAP_HINT_SETTLE_MS))
    }

    @Test
    fun theCursorsBlankRowIsNotBlank() {
        // The prompt's cursor on blank row 5: the hint goes one up, never over where typing lands.
        assertEquals(4..4, tapHintRun(frame(5, 0, 1), viewport(), 1))
        assertNull(tapHintRun(frame(2, 0, 1, 3, 4, 5), viewport(), 1))
    }

    @Test
    fun aPillTallerThanARowNeedsThatManyBlankRowsInARow() {
        // 21 px needs two 20 px rows. Rows 2 and 4 blank, 3 written: no two in a row.
        assertNull(tapHintRun(frame(5, 0, 1, 3, 5), viewport(), 2))
        assertNull(tapHintTop(frame(5, 0, 1, 3, 5), viewport(), 21, TAP_HINT_SETTLE_MS))
        // 2 and 3 blank: the run is 2..3 (40..80), the 21 px pill centred inside it.
        assertEquals(2..3, tapHintRun(frame(5, 0, 1, 4, 5), viewport(), 2))
        assertEquals(50, tapHintTop(frame(5, 0, 1, 4, 5), viewport(), 21, TAP_HINT_SETTLE_MS))
        // Exactly two rows tall still fits two rows; one pixel more needs a third.
        assertEquals(40, tapHintTop(frame(5, 0, 1, 4, 5), viewport(), 40, TAP_HINT_SETTLE_MS))
        assertNull(tapHintTop(frame(5, 0, 1, 4, 5), viewport(), 41, TAP_HINT_SETTLE_MS))
    }

    @Test
    fun anyChangeHidesItUntilItHasBeenStill() {
        val frame = frame(2, 0, 1, 2)
        // Just changed (a frame, the view, the pill): hidden at once, wherever it was.
        assertNull(tapHintTop(frame, viewport(), 16, 0))
        assertNull(tapHintTop(frame, viewport(), 16, TAP_HINT_SETTLE_MS - 1))
        assertEquals(102, tapHintTop(frame, viewport(), 16, TAP_HINT_SETTLE_MS))
    }

    @Test
    fun onceOutputHidItItReturnsOnlyAfterALongerStill() {
        val frame = frame(2, 0, 1, 2)
        // First appearance: the short settle.
        assertEquals(300L, tapHintSettleMs(hiddenByChange = false))
        assertEquals(102, tapHintTop(frame, viewport(), 16, tapHintSettleMs(hiddenByChange = false)))
        // Hidden by output: a line every 0.4 s never stays still long enough to bring it back.
        val streamed = 400L
        assertTrue(streamed >= tapHintSettleMs(hiddenByChange = false))
        assertTrue(streamed < tapHintSettleMs(hiddenByChange = true))
        // Once output pauses for the longer settle, it is placed as before.
        assertEquals(1500L, tapHintSettleMs(hiddenByChange = true))
        assertEquals(102, tapHintTop(frame, viewport(), 16, tapHintSettleMs(hiddenByChange = true)))
    }

    @Test
    fun theTerminalSaysTheHintOnlyWhileThePillIsHidden() {
        val sentence = "Tap to type"
        // Hidden: the terminal carries it.
        assertEquals(sentence, terminalDescription(wanted = true, top = null, sentence = sentence))
        // Shown: the pill is its own node, the terminal has no copy.
        assertNull(terminalDescription(wanted = true, top = 102, sentence = sentence))
        // Not wanted at all: nothing.
        assertNull(terminalDescription(wanted = false, top = null, sentence = sentence))
    }

    @Test
    fun onlyRowsDrawnWhollyAndUnpaintedCount() {
        // Row 5 cut off the bottom: row 4 is the lowest whole one.
        assertEquals(4..4, tapHintRun(frame(0, 0), viewport(last = 4), 1))
        // Drawn 20 px up: row 4 spans 60..80.
        assertEquals(62, tapHintTop(frame(0, 0), viewport(last = 4, offset = -20f), 16, TAP_HINT_SETTLE_MS))
        // A coloured bar, an underline on spaces: painted. Invisible text: not.
        val painted = listOf(run(5, "   ", RenderFrame.BACKGROUND), run(4, "  ", RenderFrame.UNDERLINE), run(3, "hidden", RenderFrame.INVISIBLE))
        assertEquals(3..3, tapHintRun(frame(0, 0, extra = painted), viewport(), 1))
        // A viewport drawn from a frame of another height says nothing about this one.
        assertNull(tapHintRun(frame(0, 0), viewport(rows = 7), 1))
    }

    @Test
    fun aResentFrameIsTheSameLookAndARealChangeIsNot() {
        // Herdr resends an unchanged surface: a new object with the same cells.
        assertTrue(sameLook(frame(2, 0, 1), frame(2, 0, 1)))
        assertFalse(sameLook(frame(2, 0, 1), frame(3, 0, 1)))
        assertFalse(sameLook(frame(2, 0, 1), frame(2, 0, 1, extra = listOf(run(4, "x")))))
        assertFalse(sameLook(frame(2, 0, 1, extra = listOf(run(4, "x"))), frame(2, 0, 1, extra = listOf(run(4, "y")))))
        assertFalse(sameLook(frame(2, 0, 1), null))
    }

    @Test
    fun blanksOnTheThemesOwnBackgroundAreBlank() {
        val themed = listOf(RenderFrame.Run(5, 0, 20, RenderFrame.BACKGROUND, 0, THEME, " "))
        assertEquals(5..5, tapHintRun(frame(0, 0, extra = themed), viewport(), 1))
        val coloured = listOf(RenderFrame.Run(5, 0, 20, RenderFrame.BACKGROUND, 0, THEME + 1, " "))
        assertEquals(4..4, tapHintRun(frame(0, 0, extra = coloured), viewport(), 1))
    }

    @Test
    fun aZoomedPanesBorderLeavesItsInteriorBlank() {
        // Herdr's border: box rows 0 and 5, side glyphs at columns 0 and 19; the prompt on row 1.
        val border = listOf(RenderFrame.Run(0, 0, 20, 0, 0, 0, "┌" + "─".repeat(18) + "┐"), RenderFrame.Run(5, 0, 20, 0, 0, 0, "└" + "─".repeat(18) + "┘")) +
            (1..4).flatMap { listOf(RenderFrame.Run(it, 0, 1, 0, 0, 0, "│"), RenderFrame.Run(it, 19, 1, 0, 0, 0, "│")) }
        val prompt = RenderFrame.Run(1, 1, 5, 0, 0, 0, "\$ out")
        fun bordered(vararg extra: RenderFrame.Run) = RenderFrame(
            cols = 20,
            rows = 6,
            cursor = RenderFrame.Cursor(6, 1, true, RenderFrame.CURSOR_BLOCK),
            foreground = 0,
            background = THEME,
            cursorColor = 0,
            popup = null,
            panes = listOf(RenderFrame.PaneArea("p", RenderFrame.CellRect(1, 1, 18, 4), focused = true, mouseReporting = false, alternateScreen = false)),
            runs = border + prompt + extra,
        )
        assertEquals(4..4, tapHintRun(bordered(), viewport(), 1))
        assertEquals(3..3, tapHintRun(bordered(RenderFrame.Run(4, 1, 3, 0, 0, 0, "out")), viewport(), 1))
        // Centred across the interior's columns, never over the border: 10..190 at 10 px a cell.
        assertEquals(10 until 190, tapHintSpanPx(bordered(), viewport()))
    }

    @Test
    fun aNoticeOverTheTerminalHidesItAndItsGoingBringsItBack() {
        fun wanted(noticeOver: Boolean = false, typedOnce: Boolean = false, chatMode: Boolean = false) =
            tapHintWanted(chatMode, imeVisible = false, typedOnce = typedOnce, connectedWithView = true, noticeOver = noticeOver)
        assertTrue(wanted())
        // A snackbar (Chat's refusal) or the no-room notice over it: not drawn.
        assertFalse(wanted(noticeOver = true))
        // Gone, it is wanted again only while still untyped, and never on Chat.
        assertFalse(wanted(typedOnce = true))
        assertFalse(wanted(chatMode = true))
    }

    private companion object {
        /** The fixtures' theme background, unlike the 0 their runs carry. */
        const val THEME = 0x00FFFFFF
    }
}
