package io.github.erikfrish.herdline.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** R-F1b: the stale-history strip over Chat keeps one height across kinds. */
class StaleStripTest {
    @Test
    fun bothKindsKeepTheSameLinesSoTheListNeverJumps() {
        for (dense in listOf(false, true)) {
            assertEquals(staleStripShape(lasting = true, dense = dense).lines, staleStripShape(lasting = false, dense = dense).lines)
        }
        assertEquals(2, staleStripShape(lasting = true, dense = false).lines)
        assertEquals(1, staleStripShape(lasting = false, dense = true).lines)
    }

    @Test
    fun onlyTheTransientKindRetriesAndNeitherCloses() {
        for (dense in listOf(false, true)) {
            val lasting = staleStripShape(lasting = true, dense = dense)
            val transient = staleStripShape(lasting = false, dense = dense)
            assertFalse(lasting.retry)
            assertTrue(transient.retry)
            assertFalse(lasting.close || transient.close)
            assertTrue(lasting.more && transient.more)
        }
    }

    @Test
    fun aLastingRefusalOffersTerminalWhereRetryWouldBeAndNeverBoth() {
        for (dense in listOf(false, true)) {
            val lasting = staleStripShape(lasting = true, dense = dense)
            val transient = staleStripShape(lasting = false, dense = dense)
            assertTrue(lasting.terminal)
            assertFalse(transient.terminal)
            for (shape in listOf(lasting, transient)) assertTrue(shape.retry != shape.terminal)
        }
    }
}
