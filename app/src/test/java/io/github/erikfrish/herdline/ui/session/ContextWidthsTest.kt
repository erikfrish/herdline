package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The place line's ranks (W10152): the desktop icon is never dropped, the
 * host's name comes next and never gives way to n/N, n/N is whole or gone,
 * and the desktop's name comes only beside n/N. A text span is whole, cut no
 * narrower than its four characters and "…", or gone. Only n/N pays a
 * separator, to the host; the span drawn just before the icon pays the gap.
 */
class ContextWidthsTest {
    // icon 37, gap 11, separator 20, n/N 40, desktop name 100 (60 at its shortest), host 120 (50).
    private fun widths(
        available: Int,
        host: Int = 120,
        icon: Int = 37,
        position: Int = 40,
        separator: Int = 20,
        desktop: Int = 100,
        desktopMin: Int = 60,
    ) = contextWidths(available, workspace = host, workspaceMin = 50, position = position, icon = icon, separator = separator, gap = 11, desktop = desktop, desktopMin = desktopMin)

    @Test
    fun nNGivesWayBeforeTheHost() {
        // N1 en 1.0: the host whole and n/N gone, not the host cut for "1/12".
        assertEquals(ContextWidths(120, 40, 37, 100), widths(37 + 11 + 120 + 20 + 40 + 100))
        assertEquals(ContextWidths(120, 0, 37, 0), widths(37 + 11 + 120 + 20 + 40 - 1))
        assertEquals(ContextWidths(120, 0, 0, 0), widths(120 + 59, icon = 0))
        assertEquals(ContextWidths(120, 40, 0, 0), widths(120 + 20 + 40, icon = 0, desktop = 0, desktopMin = 0))
    }

    @Test
    fun theHostNarrowsToItsShortestFormThenGoes() {
        // Narrower than whole, the drawn host wraps (PlaceLine); below its shortest form it goes.
        assertEquals(ContextWidths(80, 0, 37, 0), widths(37 + 11 + 80))
        assertEquals(ContextWidths(50, 0, 37, 0), widths(37 + 11 + 50))
        assertEquals(ContextWidths(0, 40, 37, 0), widths(37 + 11 + 49))
    }

    @Test
    fun theDesktopNameIsWholeThenCutThenGone() {
        val line = 37 + 11 + 120 + 20 + 40
        assertEquals(ContextWidths(120, 40, 37, 99), widths(line + 99))
        assertEquals(ContextWidths(120, 40, 37, 60), widths(line + 60))
        assertEquals(ContextWidths(120, 40, 37, 0), widths(line + 59))
    }

    @Test
    fun withoutAHostNNPaysTheGapNotTheSeparator() {
        // 10082 en 2.0: 168 px, 5/6 94, the icon 37, " · " 51, no host drawn.
        assertEquals(ContextWidths(0, 94, 37, 0), widths(94 + 11 + 37, host = 0, position = 94, separator = 51, desktop = 0, desktopMin = 0))
        assertEquals(ContextWidths(0, 0, 37, 0), widths(94 + 11 + 37 - 1, host = 0, position = 94, separator = 51, desktop = 0, desktopMin = 0))
    }

    @Test
    fun withoutNNTheDesktopNameIsNotDrawn() {
        // Room for the host and the name's shortest form, none for n/N and its separator.
        assertEquals(ContextWidths(120, 0, 37, 0), widths(37 + 11 + 120 + 59))
    }

    @Test
    fun theIconIsKeptEvenWhenNothingElseFits() {
        assertEquals(ContextWidths(0, 0, 37, 0), widths(10))
        assertEquals(ContextWidths(0, 0, 37, 0), widths(37))
    }

    // One px per character: the break is chosen by width only.
    private fun broken(text: String, width: Int) = hostBreak(text, width) { it.length }

    @Test
    fun aHostBreaksAfterAHyphenNeverInsideAWord() {
        // N1-3 zh 1.5: room for 9 characters drew "qa-p1-cor" / "e".
        assertEquals("qa-p1-\ncore", broken("qa-p1-core", 9))
        // Both lines must fit: "qa-p1-" is too wide, "qa-" leaves "p1-core" too wide.
        assertEquals(null, broken("qa-p1-core", 5))
        assertEquals(null, broken("compik", 4))
    }

    @Test
    fun aSharedNameBreaksBeforeItsNumber() {
        assertEquals("qa-p1-core\n· 2", broken("qa-p1-core · 2", 10))
        assertEquals("lab-a\n· 1", broken("lab-a · 1", 6))
    }
}
