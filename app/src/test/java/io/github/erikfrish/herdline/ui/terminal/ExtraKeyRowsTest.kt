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
        assertEquals(2, extraKeyRows(all).size)
        assertEquals(listOf(12, 11), extraKeyRows(all).map { it.size })
        assertEquals(listOf(3, 2), extraKeyRows(all.take(5)).map { it.size })
    }
}
