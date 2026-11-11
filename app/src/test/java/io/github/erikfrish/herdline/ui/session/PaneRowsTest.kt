package io.github.erikfrish.herdline.ui.session

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
}
