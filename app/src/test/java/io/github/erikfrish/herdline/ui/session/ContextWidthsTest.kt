package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/** A-24: the place line never cuts n/N, and the Desktop text gives way before the workspace. */
class ContextWidthsTest {
    @Test
    fun allFitsGivesEveryoneTheirFullWidth() {
        assertEquals(ContextWidths(100, 40, 80), contextWidths(300, workspace = 100, position = 40, desktop = 80))
    }

    @Test
    fun thePositionStaysWholeWhileTheWorkspaceShrinks() {
        assertEquals(ContextWidths(60, 40, 0), contextWidths(100, workspace = 200, position = 40, desktop = 80))
    }

    @Test
    fun desktopIsCutBeforeTheWorkspace() {
        assertEquals(ContextWidths(100, 40, 30), contextWidths(170, workspace = 100, position = 40, desktop = 80))
    }

    @Test
    fun thePositionIsDroppedOnlyWhenItAloneOverflows() {
        assertEquals(ContextWidths(30, 0, 0), contextWidths(30, workspace = 100, position = 40, desktop = 80))
        assertEquals(ContextWidths(0, 40, 0), contextWidths(40, workspace = 100, position = 40, desktop = 80))
    }
}
