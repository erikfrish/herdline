package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
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
}
