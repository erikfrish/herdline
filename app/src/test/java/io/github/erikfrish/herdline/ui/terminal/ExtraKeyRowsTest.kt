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
    fun aLandscapePhoneTypingGetsOneRowLedByTheFirstKeys() {
        val keys = extraKeysFrom(DEFAULT_EXTRA_KEY_IDS)
        // QF-6: landscape, keyboard up, a window under 480 dp tall: one row,
        // every key in saved order, so Esc, Tab, Ctrl and the arrows lead.
        val one = extraKeyRows(keys, extraKeyRowCount(landscape = true, imeVisible = true, windowHeightDp = 411))
        assertEquals(listOf(keys), one)
        // Keyboard down, portrait, or a window tall enough: the user's two rows.
        for ((landscape, ime, height) in listOf(Triple(true, false, 411), Triple(false, true, 400), Triple(true, true, 480))) {
            val rows = extraKeyRows(keys, extraKeyRowCount(landscape, ime, height))
            assertEquals(listOf(keys.take(7), keys.drop(7)), rows)
        }
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
