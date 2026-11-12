package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The place line's ranks: the desktop icon is never dropped, n/N is whole or
 * gone, the desktop's name comes only beside n/N, and the workspace gives way
 * first. A text span is whole, cut no narrower than its four characters and
 * "…", or gone.
 */
class ContextWidthsTest {
    // icon 37, separator 20, n/N 40, desktop name 100 (60 at its shortest), workspace 120 (50).
    private fun widths(available: Int, icon: Int = 37, desktop: Int = 100, desktopMin: Int = 60) =
        contextWidths(available, workspace = 120, workspaceMin = 50, position = 40, icon = icon, separator = 20, desktop = desktop, desktopMin = desktopMin)

    // What icon, separator, n/N and the whole name take.
    private val line = 37 + 20 + 40 + 100

    @Test
    fun theDesktopNameIsWholeThenCutThenGone() {
        assertEquals(ContextWidths(0, 40, 37, 100), widths(line))
        assertEquals(ContextWidths(0, 40, 37, 99), widths(line - 1))
        assertEquals(ContextWidths(0, 40, 37, 60), widths(37 + 20 + 40 + 60))
        assertEquals(ContextWidths(0, 40, 37, 0), widths(37 + 20 + 40 + 59))
    }

    @Test
    fun theWorkspaceGivesWayBeforeTheDesktopName() {
        assertEquals(ContextWidths(120, 40, 37, 100), widths(line + 20 + 120))
        assertEquals(ContextWidths(50, 40, 37, 100), widths(line + 20 + 50))
        assertEquals(ContextWidths(0, 40, 37, 100), widths(line + 20 + 49))
    }

    @Test
    fun withoutNNTheDesktopNameIsNotDrawn() {
        // Room for the name's shortest form (30), none for n/N and its separator (60).
        assertEquals(ContextWidths(0, 0, 37, 0), widths(37 + 59, desktopMin = 30))
    }

    @Test
    fun theIconIsKeptEvenWhenNothingElseFits() {
        assertEquals(ContextWidths(0, 0, 37, 0), widths(10))
        assertEquals(ContextWidths(0, 0, 37, 0), widths(37))
    }

    @Test
    fun withTheDesktopHereOnlyTheWorkspaceSeparatorIsPaid() {
        // No icon: n/N needs no separator of its own.
        assertEquals(ContextWidths(120, 40, 0, 0), widths(20 + 40 + 120, icon = 0, desktop = 0, desktopMin = 0))
        assertEquals(ContextWidths(0, 40, 0, 0), widths(40, icon = 0, desktop = 0, desktopMin = 0))
    }
}
