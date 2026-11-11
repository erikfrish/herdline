package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Collie rows 4 and 5 over [Outage]: the strip comes up only after a loss
 * outlasts a blip, its dismissal lasts for that outage, and "Reconnected"
 * fires once, only after a strip that was shown.
 */
class OutageTest {
    private val live = Outage().observe(live = true, now = 0).outage

    @Test
    fun aFirstPaintAlreadyLiveSaysNothing() {
        val step = Outage().observe(live = true, now = 0)
        assertFalse(step.reconnected)
        assertFalse(step.dropped)
        assertFalse(step.outage.strip)
    }

    @Test
    fun aFirstConnectThatNeverGotLiveIsNoOutage() {
        val step = Outage().observe(live = false, now = 0)
        assertFalse(step.dropped)
        assertNull(step.outage.since)
        assertFalse(step.outage.tick(OUTAGE_SHOW_AFTER_MS * 10).strip)
        assertFalse(step.outage.observe(live = true, now = 5_000).reconnected)
    }

    @Test
    fun aBlipShowsNoStripAndSaysNoReconnected() {
        val down = live.observe(live = false, now = 10_000)
        assertTrue(down.dropped)
        val early = down.outage.tick(10_000 + OUTAGE_SHOW_AFTER_MS - 1)
        assertFalse(early.strip)
        assertFalse(early.observe(live = true, now = 10_500).reconnected)
    }

    @Test
    fun aLossThatOutlastsTheBlipShowsTheStripStampedWhenSeen() {
        val shown = live.observe(live = false, now = 10_000).outage.tick(10_000 + OUTAGE_SHOW_AFTER_MS)
        assertTrue(shown.strip)
        assertEquals(10_000L, shown.since)
    }

    @Test
    fun recoveryFromAShownStripSaysReconnectedOnce() {
        val shown = live.observe(live = false, now = 10_000).outage.tick(20_000)
        val back = shown.observe(live = true, now = 30_000)
        assertTrue(back.reconnected)
        assertFalse(back.outage.strip)
        assertFalse(back.outage.observe(live = true, now = 31_000).reconnected)
    }

    @Test
    fun stillDownKeepsTheFirstStampAndStartsNoSecondOutage() {
        val shown = live.observe(live = false, now = 10_000).outage.tick(20_000)
        val again = shown.observe(live = false, now = 25_000)
        assertFalse(again.dropped)
        assertEquals(10_000L, again.outage.since)
        assertTrue(again.outage.strip)
    }

    @Test
    fun aDismissLastsForThisOutageOnly() {
        val dismissed = live.observe(live = false, now = 10_000).outage.tick(20_000).dismiss()
        assertFalse(dismissed.strip)
        assertFalse(dismissed.observe(live = false, now = 25_000).outage.strip)
        val next = dismissed.observe(live = true, now = 30_000).outage
            .observe(live = false, now = 40_000).outage
            .tick(41_000)
        assertTrue(next.strip)
    }

    @Test
    fun aDismissedStripWasStillShownSoRecoverySaysReconnected() {
        val dismissed = live.observe(live = false, now = 10_000).outage.tick(20_000).dismiss()
        assertTrue(dismissed.observe(live = true, now = 30_000).reconnected)
    }

    @Test
    fun aNewLossAfterRecoveryIsANewOutage() {
        val back = live.observe(live = false, now = 10_000).outage.tick(20_000)
            .observe(live = true, now = 30_000).outage
        val down = back.observe(live = false, now = 31_000)
        assertTrue(down.dropped)
        assertEquals(31_000L, down.outage.since)
        assertFalse(down.outage.strip)
    }

    @Test
    fun dismissWhileLiveChangesNothing() {
        assertEquals(live, live.dismiss())
    }
}
