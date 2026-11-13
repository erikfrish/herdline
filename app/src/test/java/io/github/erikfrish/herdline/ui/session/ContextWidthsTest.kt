package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The place line's ranks: the desktop icon is never dropped, n/N is whole or
 * gone, the desktop's name comes only beside n/N, and the workspace gives way
 * first. A text span is whole, cut no narrower than its four characters and
 * "…", or gone. Only the workspace pays a separator, to n/N; the span drawn
 * just before the icon pays the gap instead.
 */
class ContextWidthsTest {
    // icon 37, gap 11, separator 20, n/N 40, desktop name 100 (60 at its shortest), workspace 120 (50).
    private fun widths(available: Int, icon: Int = 37, position: Int = 40, separator: Int = 20, desktop: Int = 100, desktopMin: Int = 60) =
        contextWidths(available, workspace = 120, workspaceMin = 50, position = position, icon = icon, separator = separator, gap = 11, desktop = desktop, desktopMin = desktopMin)

    // What icon, gap, n/N and the whole name take.
    private val line = 37 + 11 + 40 + 100

    @Test
    fun theDesktopNameIsWholeThenCutThenGone() {
        assertEquals(ContextWidths(0, 40, 37, 100), widths(line))
        assertEquals(ContextWidths(0, 40, 37, 99), widths(line - 1))
        assertEquals(ContextWidths(0, 40, 37, 60), widths(37 + 11 + 40 + 60))
        assertEquals(ContextWidths(0, 40, 37, 0), widths(37 + 11 + 40 + 59))
    }

    @Test
    fun theWorkspaceGivesWayBeforeTheDesktopName() {
        assertEquals(ContextWidths(120, 40, 37, 100), widths(line + 20 + 120))
        assertEquals(ContextWidths(50, 40, 37, 100), widths(line + 20 + 50))
        assertEquals(ContextWidths(0, 40, 37, 100), widths(line + 20 + 49))
    }

    @Test
    fun nNAndTheIconFitWhereASeparatorBeforeTheIconWouldNot() {
        // 10082 en 2.0: 168 px, 5/6 94, the icon 37, " · " 51; the workspace too wide to show.
        assertEquals(ContextWidths(0, 94, 37, 0), widths(168, position = 94, separator = 51, desktop = 0, desktopMin = 0))
        assertEquals(ContextWidths(0, 94, 37, 0), widths(94 + 11 + 37, position = 94, separator = 51, desktop = 0, desktopMin = 0))
        // 1 px short: n/N goes whole, and the workspace takes what is left, cut.
        assertEquals(ContextWidths(93, 0, 37, 0), widths(94 + 11 + 37 - 1, position = 94, separator = 51, desktop = 0, desktopMin = 0))
    }

    @Test
    fun aWorkspaceRightOfTheIconPaysTheGapNotTheSeparator() {
        // n/N too wide to fit: the workspace sits straight before the icon.
        assertEquals(ContextWidths(120, 0, 37, 0), widths(37 + 11 + 120, position = 200))
        assertEquals(ContextWidths(119, 0, 37, 0), widths(37 + 11 + 119, position = 200))
    }

    @Test
    fun withoutNNTheDesktopNameIsNotDrawn() {
        // Room for the name's shortest form (30), none for n/N and its gap (51).
        assertEquals(ContextWidths(0, 0, 37, 0), widths(37 + 50, desktopMin = 30))
    }

    @Test
    fun theIconIsKeptEvenWhenNothingElseFits() {
        assertEquals(ContextWidths(0, 0, 37, 0), widths(10))
        assertEquals(ContextWidths(0, 0, 37, 0), widths(37))
    }

    @Test
    fun withTheDesktopHereOnlyTheWorkspaceSeparatorIsPaid() {
        // No icon: n/N pays no gap.
        assertEquals(ContextWidths(120, 40, 0, 0), widths(20 + 40 + 120, icon = 0, desktop = 0, desktopMin = 0))
        assertEquals(ContextWidths(0, 40, 0, 0), widths(40, icon = 0, desktop = 0, desktopMin = 0))
    }
}
