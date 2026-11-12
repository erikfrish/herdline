package io.github.erikfrish.herdline.ui.terminal

import io.github.erikfrish.herdline.data.MemoryPrefs
import io.github.erikfrish.herdline.data.SettingsStore
import io.github.erikfrish.herdline.ui.session.extraKeyRowCount
import io.github.erikfrish.herdline.ui.settings.movedUp
import io.github.erikfrish.herdline.ui.settings.toggled
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test
    fun keysAddedToTheDefaultSitApartAndTheGridKeepsItsRelations() {
        // "Enable another key" from the default appends it: the grid stays 7 × 2, typing in landscape too.
        val ids = toggled(toggled(DEFAULT_EXTRA_KEY_IDS, "tilde"), "pgup")
        assertTrue(isDefaultBased(ids))
        val grid = listOf(extraKeysFrom(DEFAULT_EXTRA_KEY_IDS).take(7), extraKeysFrom(DEFAULT_EXTRA_KEY_IDS).drop(7))
        for (rowsOf in listOf(1, 2)) {
            assertEquals(KeyPanel(grid, extraKeysFrom(listOf("tilde", "pgup"))), extraKeyPanel(extraKeysFrom(ids), rowsOf))
        }
        // The default itself is default-based with nothing apart.
        assertEquals(KeyPanel(grid, emptyList()), extraKeyPanel(extraKeysFrom(DEFAULT_EXTRA_KEY_IDS)))
    }

    @Test
    fun anyChangeInsideTheFirstFourteenIsTheUsersOwnOrder() {
        val added = DEFAULT_EXTRA_KEY_IDS + "tilde"
        for (ids in listOf(
            movedUp(added, "ctrl"), // a default key moved
            movedUp(added, "tilde"), // an extra moved into the first 14
            toggled(added, "esc"), // a default key removed
        )) {
            assertFalse("$ids", isDefaultBased(ids))
            val keys = extraKeysFrom(ids)
            // Saved order, today's split, nothing drawn apart.
            assertEquals(KeyPanel(extraKeyRows(keys), emptyList()), extraKeyPanel(keys))
        }
    }

    @Test
    fun everyAddedKeyShowsWithoutScrollingBesideOrWrappedAbove() {
        // Beside iff (7 + c) × 48 + (6 + c − 1) × 2 + 8 + 12 ≤ panel, c = ceil(n / 2).
        val all = DEFAULT_EXTRA_KEY_IDS + AllExtraKeys.keys.filter { it !in DEFAULT_EXTRA_KEY_IDS }
        val extras = extraKeyPanel(extraKeysFrom(all)).extras
        assertEquals(8, extras.size) // every key the app has: the most a default-based set can add
        // Portrait 360 and 411, a split-screen half at 450, landscape 566 (the 8-extra minimum) and 890.
        for (panel in listOf(360f, 411f, 450f, 566f, 890f)) {
            for (n in 1..extras.size) {
                val c = (n + 1) / 2
                val fits = (7 + c) * 48 + (6 + c - 1) * 2 + 8 + 12 <= panel
                val beside = besideLayout(panel, n)
                assertEquals("$panel dp, $n extras", fits, beside != null)
                if (beside != null) {
                    assertTrue("$beside", beside.width >= 48f && !beside.scrolls)
                } else {
                    // Above: rows of at most seven on the grid's unscrolled 48 dp caps, two rows at most.
                    val grid = capLayout(panel, 7)
                    val rows = extraRowsAbove(extras.take(n))
                    assertTrue("$panel dp: $grid", !grid.scrolls && grid.width >= 48f)
                    assertTrue("$panel dp, $n extras: $rows", rows.size <= 2 && rows.all { it.size <= 7 })
                    assertEquals(extras.take(n), rows.flatten())
                }
            }
        }
        assertNull(besideLayout(450f, 8))
        assertNotNull(besideLayout(566f, 8))
    }

    @Test
    fun aSavedSetIsNeverRewrittenToFitALayout() {
        // Default plus extras, and a moved default key: loaded byte for byte, whatever the panel draws.
        for (saved in listOf((DEFAULT_EXTRA_KEY_IDS + "tilde").joinToString(","), movedUp(DEFAULT_EXTRA_KEY_IDS + "tilde", "ctrl").joinToString(","))) {
            val prefs = MemoryPrefs().apply { values["extra_keys"] = saved }
            val ids = SettingsStore(prefs).settings.value.extraKeys
            extraKeyPanel(extraKeysFrom(ids))
            assertEquals(saved.split(","), ids)
            assertEquals(saved, prefs.values["extra_keys"])
        }
    }
}
