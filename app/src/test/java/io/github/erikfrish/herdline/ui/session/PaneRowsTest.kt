package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.RenderFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil

/**
 * The too-short notice's offers (lab.7.3 J2, parent decision): Send stays
 * gated at [MIN_SEND_ROWS]; hiding the keyboard and portrait are offered only
 * where the current geometry gives the pane 12 rows; after Zoom the rows are
 * Herdr's, rechecked from its next frame.
 */
class PaneRowsTest {
    // A 2400×1080 phone at 420 dpi held sideways: one 44 px terminal row
    // (16.7 dp), and a 625 px budget for the regular composer plus the nav bar.
    private val cell = 44
    private val composerMax = 625
    private val portraitExtra = 546
    private val landscapeOpen = SessionSpace(2400, 1080, 888)
    private val landscapeTyping = SessionSpace(2400, 1080, 373)

    private fun reach(
        paneRows: Int?,
        gridRows: Int = 8,
        landscape: Boolean = true,
        imeVisible: Boolean = true,
        open: SessionSpace? = landscapeOpen,
        now: SessionSpace = landscapeTyping,
        sideHeld: Boolean = false,
    ) = shortPaneReach(paneRows, gridRows, cell, landscape, imeVisible, open, now, composerMax, portraitExtra, sideHeld)

    @Test
    fun landscapePhoneOffersPortraitButNotHidingTheKeyboard() {
        // Keyboard down the pane gets (888 - 625) / 44 = 5 rows; portrait 23.
        assertEquals(ShortPaneReach(hideKeyboard = false, portrait = true), reach(paneRows = 8))
    }

    @Test
    fun halfOfASplitIsNotPromisedPortrait() {
        // Its share of portrait's 23 rows is 4 × 23 / 8 = 11.
        assertEquals(ShortPaneReach(hideKeyboard = false, portrait = false), reach(paneRows = 4))
    }

    @Test
    fun zoomIsRecheckedFromHerdrsRows() {
        assertFalse(reach(paneRows = 4).portrait)
        // Herdr's frame after Zoom: the pane has the whole grid.
        assertTrue(reach(paneRows = 8).portrait)
    }

    @Test
    fun hidingTheKeyboardIsOfferedFromTwelveRowsOn() {
        val twelve = SessionSpace(1080, 900, composerMax + 12 * cell)
        val eleven = twelve.copy(sharedPx = twelve.sharedPx - 1)
        assertTrue(reach(paneRows = 6, gridRows = 6, landscape = false, open = twelve, now = twelve).hideKeyboard)
        assertFalse(reach(paneRows = 6, gridRows = 6, landscape = false, open = eleven, now = eleven).hideKeyboard)
    }

    @Test
    fun hidingTheKeyboardIsNotOfferedWhereTheNoticeBlockTakesTheTwelfthRow() {
        // The session screen's budget: the regular composer at its tallest
        // with its notice, at 2.625 px/dp. The too-short notice offering the
        // hint is a block: 8 dp padding, two 16 sp lines, 4 dp, its 48 dp
        // action row, 4 dp padding, then 4 dp to the band.
        val pxPerDp = 2.625f
        val fit = ComposerFit.Regular
        val composerPx = ceil(fit.heightDp(fit.maxLines, notice = true) * pxPerDp).toInt()
        val noticeDp = 8 + 2 * 16 + 4 + 48 + 4 + 4
        val realComposerPx = ceil((fit.heightDp(fit.maxLines, notice = false) + noticeDp) * pxPerDp).toInt()
        val fits = SessionSpace(1080, 2400, realComposerPx + 12 * cell)
        val short = fits.copy(sharedPx = fits.sharedPx - 1)
        assertTrue(shortPaneReach(6, 6, cell, false, true, fits, fits, composerPx, portraitExtra, false).hideKeyboard)
        assertFalse(shortPaneReach(6, 6, cell, false, true, short, short, composerPx, portraitExtra, false).hideKeyboard)
    }

    @Test
    fun hidingTheKeyboardNeedsTheKeyboardUpAndASpaceSeenWithoutIt() {
        val roomy = SessionSpace(1080, 2400, 2000)
        assertFalse(reach(paneRows = 6, gridRows = 6, landscape = false, imeVisible = false, open = roomy, now = roomy).hideKeyboard)
        assertFalse(reach(paneRows = 6, gridRows = 6, landscape = false, open = null, now = roomy).hideKeyboard)
    }

    @Test
    fun hidingTheKeyboardIsNotOfferedOverASideHeldGrid() {
        val roomy = SessionSpace(1080, 2400, 2000)
        assertTrue(reach(paneRows = 6, gridRows = 6, landscape = false, open = roomy, now = roomy).hideKeyboard)
        assertFalse(reach(paneRows = 6, gridRows = 6, landscape = false, open = roomy, now = roomy, sideHeld = true).hideKeyboard)
    }

    @Test
    fun theSideHoldIsTheKeyboardDownColumnLessTheNavBar() {
        assertEquals(888 - 63, sideHoldPx(side = true, open = landscapeOpen, windowWidthPx = 2400, windowHeightPx = 1080, navBarPx = 63))
    }

    @Test
    fun noSideHoldWithoutTheSideComposerOrFromAnotherWindow() {
        assertEquals(null, sideHoldPx(side = false, open = landscapeOpen, windowWidthPx = 2400, windowHeightPx = 1080, navBarPx = 0))
        assertEquals(null, sideHoldPx(side = true, open = null, windowWidthPx = 2400, windowHeightPx = 1080, navBarPx = 0))
        // Split screen: the space was seen in the full window.
        assertEquals(null, sideHoldPx(side = true, open = landscapeOpen, windowWidthPx = 1200, windowHeightPx = 1080, navBarPx = 0))
        assertEquals(null, sideHoldPx(side = true, open = landscapeOpen.copy(sharedPx = 40), windowWidthPx = 2400, windowHeightPx = 1080, navBarPx = 63))
    }

    @Test
    fun portraitOnlyFromLandscape() {
        val portraitNow = SessionSpace(1080, 2400, 2000)
        assertFalse(reach(paneRows = 8, landscape = false, open = portraitNow, now = portraitNow).portrait)
    }

    @Test
    fun portraitWithoutAKeyboardDownMeasurementUsesTheSmallerSpaceNow() {
        // 373 + 1320 - 546 = 1147 px: (1147 - 625) / 44 = 11 rows.
        assertFalse(reach(paneRows = 8, open = null).portrait)
    }

    @Test
    fun noOfferWithoutThePanesRowsOrACell() {
        assertEquals(ShortPaneReach(hideKeyboard = false, portrait = false), reach(paneRows = null))
        assertEquals(
            ShortPaneReach(hideKeyboard = false, portrait = false),
            shortPaneReach(8, 8, 0, true, true, landscapeOpen, landscapeTyping, composerMax, portraitExtra, false),
        )
    }

    @Test
    fun aPanesShareRoundsDownAndNeverExceedsTheGrid() {
        assertEquals(13, paneRowsAfter(paneRows = 5, gridRows = 11, gridRowsAfter = 30))
        assertEquals(30, paneRowsAfter(paneRows = 40, gridRows = 11, gridRowsAfter = 30))
        assertEquals(0, paneRowsAfter(paneRows = 5, gridRows = 0, gridRowsAfter = 30))
    }

    /** A frame of [rows] grid rows; [inner] is the focused pane's content rect, or none. */
    private fun frame(rows: Int, inner: RenderFrame.CellRect?, popup: RenderFrame.CellRect? = null) = RenderFrame(
        cols = 80,
        rows = rows,
        cursor = RenderFrame.Cursor(0, 0, true, RenderFrame.CURSOR_BLOCK),
        foreground = 0,
        background = 0,
        cursorColor = 0,
        popup = popup,
        panes = listOfNotNull(inner?.let { RenderFrame.PaneArea("p1", it, focused = true, mouseReporting = false, alternateScreen = false) }),
        runs = emptyList(),
    )

    @Test
    fun aPaneHerdrGaveNoRowShowsTheNoticeAndOneRowHidesIt() {
        // QA m18: 2 grid rows, the border on both, the content rect 0 rows tall.
        assertTrue(paneHasNoRoom(frame(2, RenderFrame.CellRect(1, 1, 78, 0)), composerTyping = false))
        // A content rect that starts at or past the frame's last row has none of it on screen.
        assertTrue(paneHasNoRoom(frame(2, RenderFrame.CellRect(1, 2, 78, 2)), composerTyping = false))
        // One content row inside the frame: the pane is drawn, no notice.
        assertFalse(paneHasNoRoom(frame(3, RenderFrame.CellRect(1, 1, 78, 1)), composerTyping = false))
        // A rect taller than the frame still has its first row drawn.
        assertFalse(paneHasNoRoom(frame(2, RenderFrame.CellRect(0, 1, 80, 4)), composerTyping = false))
    }

    @Test
    fun withoutAFocusedRectTheGridRowsDecide() {
        assertTrue(paneHasNoRoom(frame(NO_ROOM_FALLBACK_ROWS - 1, inner = null), composerTyping = false))
        assertFalse(paneHasNoRoom(frame(NO_ROOM_FALLBACK_ROWS, inner = null), composerTyping = false))
    }

    @Test
    fun noNoticeWhileTypingInTheComposerOrUnderAPopup() {
        val empty = RenderFrame.CellRect(1, 1, 78, 0)
        assertFalse(paneHasNoRoom(frame(2, empty), composerTyping = true))
        assertFalse(paneHasNoRoom(frame(2, empty, popup = RenderFrame.CellRect(0, 0, 40, 2)), composerTyping = false))
        assertFalse(paneHasNoRoom(null, composerTyping = false))
    }

    @Test
    fun qaM26AnUnzoomedSplitPaneWithOnlyItsBorderShowsTheNoticeWithTheKeyboardDown() {
        // QA m26: an 18-row grid, the focused pane's content rect 0 rows tall at the top; no keyboard.
        assertTrue(paneHasNoRoom(frame(18, RenderFrame.CellRect(1, 0, 13, 0)), composerTyping = false))
    }
}
