package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** A-24: the header's context line never cuts the tab count, and Desktop gives way before the host. */
class ContextWidthsTest {
    @Test
    fun allFitsGivesEveryoneTheirFullWidth() {
        assertEquals(ContextWidths(100, 40, 80), contextWidths(300, host = 100, count = 40, desktop = 80))
    }

    @Test
    fun theCountStaysWholeWhileTheHostShrinks() {
        assertEquals(ContextWidths(60, 40, 0), contextWidths(100, host = 200, count = 40, desktop = 80))
    }

    @Test
    fun desktopIsCutBeforeTheHost() {
        assertEquals(ContextWidths(100, 40, 30), contextWidths(170, host = 100, count = 40, desktop = 80))
    }

    @Test
    fun theCountIsDroppedOnlyWhenItAloneOverflows() {
        assertEquals(ContextWidths(30, 0, 0), contextWidths(30, host = 100, count = 40, desktop = 80))
        assertEquals(ContextWidths(0, 40, 0), contextWidths(40, host = 100, count = 40, desktop = 80))
    }
}
