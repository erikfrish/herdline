package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SendHoldTest {
    @Test
    fun aDisabledSendWithTextIsNeverSilent() {
        for (sending in listOf(false, true)) {
            for (unsettled in listOf(false, true)) {
                for (resizing in listOf(false, true)) {
                    assertNotNull("sending=$sending unsettled=$unsettled resizing=$resizing", silentSendHold(false, hasText = true, sending, unsettled, resizing))
                }
            }
        }
    }

    @Test
    fun aTapInFlightReadsAsCheckingNotAsAHeldDelivery() {
        // deliver() records the draft as queued before the native answer.
        assertEquals(SendHold.Checking, silentSendHold(false, hasText = true, sending = true, unsettled = true, resizing = false))
        assertEquals(SendHold.Unsettled, silentSendHold(false, hasText = true, sending = false, unsettled = true, resizing = false))
        // A reason still settling.
        assertEquals(SendHold.Checking, silentSendHold(false, hasText = true, sending = false, unsettled = false, resizing = false))
    }

    @Test
    fun aResizeTheCheckSeesIsNamedWhileItStillReadsAsChecking() {
        // 10018 RZ-text: a real resize never outlasts the settle, so the reason alone never showed.
        assertEquals(SendHold.CheckingResizing, silentSendHold(false, hasText = true, sending = true, unsettled = true, resizing = true))
        assertEquals(SendHold.CheckingResizing, silentSendHold(false, hasText = true, sending = false, unsettled = false, resizing = true))
        // A delivery the editor holds no answer for is still named as such.
        assertEquals(SendHold.Unsettled, silentSendHold(false, hasText = true, sending = false, unsettled = true, resizing = true))
    }

    @Test
    fun nothingToExplainWhenSendIsOnOrTheDraftIsEmpty() {
        assertNull(silentSendHold(true, hasText = true, sending = false, unsettled = false, resizing = true))
        assertNull(silentSendHold(false, hasText = false, sending = false, unsettled = false, resizing = true))
    }

    @Test
    fun aWorkingAgentIsNamedAtOnceWithNoSettleBlank() {
        val hold = PrecheckHold(SETTLE)
        assertNull(hold.observe(null, 1_000))
        assertEquals(WORKING, hold.observe(WORKING, 1_001))
    }

    @Test
    fun theWorkingReasonStaysPutWhileTheCodeFlaps() {
        val hold = PrecheckHold(SETTLE)
        assertEquals(WORKING, hold.observe(WORKING, 1_000))
        // One-frame flaps to "may go" or to another code do not flicker it.
        assertEquals(WORKING, hold.observe(null, 1_016))
        assertEquals(WORKING, hold.observe(WORKING, 1_032))
        assertEquals(WORKING, hold.observe("scrolled", 1_048))
        assertEquals(WORKING, hold.observe(null, 1_064))
        assertEquals(WORKING, hold.observe(null, 1_064 + SETTLE - 1))
        // Idle for the whole settle: the notice goes.
        assertNull(hold.observe(null, 1_064 + SETTLE))
    }

    @Test
    fun anotherReasonShowsOnceItHeldAndAFlappingOneStillShows() {
        val hold = PrecheckHold(SETTLE)
        // Held: shown after the settle (the slot says "Checking" until then).
        assertNull(hold.observe("scrolled", 1_000))
        assertNull(hold.observe("scrolled", 1_000 + SETTLE - 1))
        assertEquals("scrolled", hold.observe("scrolled", 1_000 + SETTLE))
        // A code that changes faster than the settle, from a blank slot.
        val flapping = PrecheckHold(SETTLE)
        assertNull(flapping.observe("scrolled", 1_000))
        assertNull(flapping.observe("not_visible", 1_300))
        assertNull(flapping.observe("scrolled", 1_600))
        assertEquals("not_visible", flapping.observe("not_visible", 1_750))
    }

    private companion object {
        const val SETTLE = 700L
        const val WORKING = PrecheckHold.AGENT_WORKING
    }
}
