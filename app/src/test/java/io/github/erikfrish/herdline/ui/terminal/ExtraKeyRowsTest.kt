package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.ui.session.extraKeyRowCount
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The panel's row split (HG-EXK): `ExtraKeysRow` feeds every composition
 * through [extraKeyRows], so how many keys land in each of the two rows — and
 * that the fuller row leads — is the layout the user sees. A custom set of
 * many keys must stay two rows, never a hidden third.
 */
class ExtraKeyRowsTest {

    @Test
    fun manyKeysStayTwoRowsAndUnevenSplitsLeadFull() {
        val all = extraKeysFrom(AllExtraKeys.keys.toList())
        // Every key the app has, then one fewer for an odd count: two rows,
        // every key kept once in saved order, the fuller row first.
        for (keys in listOf(all, all.dropLast(1), all.take(5))) {
            val rows = extraKeyRows(keys)
            assertEquals(2, rows.size)
            assertEquals(keys, rows.flatten())
            assertEquals(listOf((keys.size + 1) / 2, keys.size / 2), rows.map { it.size })
        }
    }

    @Test
    fun theDefaultKeepsUpAboveDownEvenWhereOneRowWouldBeUsed() {
        val keys = extraKeysFrom(DEFAULT_EXTRA_KEY_IDS)
        val two = listOf(keys.take(7), keys.drop(7))
        // Every case, QF-6's landscape typing under 480 dp included: the two rows,
        // with Up (column 4) directly above Down, Left and Right either side.
        for ((landscape, ime, height) in listOf(Triple(true, true, 411), Triple(true, false, 411), Triple(false, true, 400), Triple(true, true, 480))) {
            assertEquals(two, extraKeyRows(keys, extraKeyRowCount(landscape, ime, height)))
        }
        assertEquals(extraKeysFrom(listOf("tab", "esc", "home", "up", "end", "slash", "backspace")), two[0])
        assertEquals(extraKeysFrom(listOf("ctrl", "alt", "left", "down", "right", "pipe", "enter")), two[1])
    }

    @Test
    fun aLandscapePhoneTypingStillFlattensASetWithNoStackedArrows() {
        // The 0.6.22 default, arrows in one run: QF-6's one row, saved order kept.
        val keys = extraKeysFrom(PREVIOUS_DEFAULT_EXTRA_KEY_IDS)
        assertEquals(listOf(keys), extraKeyRows(keys, extraKeyRowCount(landscape = true, imeVisible = true, windowHeightDp = 411)))
    }

    @Test
    fun sevenCapsFitA360DpPhoneWholeAndOnlyNarrowerScrolls() {
        // KEY-LAYOUT R1: 360 dp no longer scrolls Enter and Backspace off the edge; the gap tightens instead.
        for (panel in listOf(360f, 411f, 640f)) {
            val row = capLayout(panel, 7)
            assertTrue("$panel: $row", !row.scrolls && row.width >= 48f)
            assertTrue("$panel: $row", 7 * row.width + 6 * row.gap + 12f <= panel + 0.01f)
        }
        // Roomy screens keep the 5 dp gap; only 360 dp needs the tight one.
        assertEquals(5f, capLayout(411f, 7).gap, 0f)
        assertEquals(2f, capLayout(360f, 7).gap, 0f)
        // Too narrow even with a 2 dp gap: the row scrolls with 48 dp caps or wider.
        val narrow = capLayout(340f, 7)
        assertTrue("$narrow", narrow.scrolls && narrow.width >= 48f)
    }

    @Test
    fun aScrollingRowEndsOnHalfACapAndNeverShrinksATarget() {
        // 640 dp landscape and a 457 dp split, less the 6 dp padding a side; 5 dp gaps.
        for (viewport in listOf(628f, 445f)) {
            val width = scrollingCapWidth(viewport, 5f)
            // Whole caps and gaps, then exactly half a cap at the edge.
            val whole = kotlin.math.round((viewport - width / 2) / (width + 5f)).toInt()
            assertTrue("$viewport: $width", width >= 48f && whole >= 1)
            assertEquals("$viewport", width / 2, viewport - whole * (width + 5f), 0.01f)
        }
        // Not room for one cap and half the next: the plain 48 dp target, no cue.
        assertEquals(48f, scrollingCapWidth(70f, 5f), 0f)
    }
}
