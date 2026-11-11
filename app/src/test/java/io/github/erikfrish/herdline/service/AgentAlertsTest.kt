package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentAlertsTest {
    private val pane = AlertKey("lab", "w1:p1")

    @Test
    fun theSamePaneOnTwoHostsOrTwoPanesOnOneHostNeverShareASlot() {
        val tags = listOf(
            AlertKey("lab", "w1:p1"),
            AlertKey("box", "w1:p1"),
            AlertKey("lab", "w1:p2"),
            AlertKey("lab", null),
        ).map { it.tag }
        assertEquals(tags.size, tags.toSet().size)
        assertTrue(AlertKey("lab", "w1:p1").tag.startsWith(AlertKey.prefix("lab")))
        assertFalse(AlertKey("lab2", "w1:p1").tag.startsWith(AlertKey.prefix("lab")))
    }

    @Test
    fun aRepeatedStateUpdatesSilentlyWhileShownAndStaysAwayOnceDismissed() {
        val alerts = AgentAlerts()
        assertEquals(Alert.Loud, alerts.onEvent(pane, AlertKind.NeedsInput, showing = false))
        assertEquals(Alert.Silent, alerts.onEvent(pane, AlertKind.NeedsInput, showing = true))
        assertEquals(Alert.Skip, alerts.onEvent(pane, AlertKind.NeedsInput, showing = false))
    }

    @Test
    fun aDifferentStateAlertsEvenWhileTheOldOneShows() {
        val alerts = AgentAlerts()
        alerts.onEvent(pane, AlertKind.NeedsInput, showing = false)
        assertEquals(Alert.Loud, alerts.onEvent(pane, AlertKind.Done, showing = true))
    }

    @Test
    fun theSameStateAlertsAgainOnlyAfterThePaneWasSeenLeavingIt() {
        val alerts = AgentAlerts()
        assertFalse(alerts.onStatus(pane, AgentStatus.Working))
        alerts.onEvent(pane, AlertKind.Done, showing = false)
        // The view catching up with the event resolves nothing.
        assertFalse(alerts.onStatus(pane, AgentStatus.Done))
        assertEquals(Alert.Skip, alerts.onEvent(pane, AlertKind.Done, showing = false))
        // A new turn starts: the posted Done is resolved, and the next Done is news.
        assertTrue(alerts.onStatus(pane, AgentStatus.Working))
        assertFalse(alerts.onStatus(pane, AgentStatus.Done))
        assertEquals(Alert.Loud, alerts.onEvent(pane, AlertKind.Done, showing = false))
    }

    @Test
    fun anEventBeforeTheViewCatchesUpIsNotResolvedByTheOldStatus() {
        val alerts = AgentAlerts()
        alerts.onStatus(pane, AgentStatus.Working)
        alerts.onEvent(pane, AlertKind.NeedsInput, showing = false)
        assertFalse(alerts.onStatus(pane, AgentStatus.Working))
        assertFalse(alerts.onStatus(pane, AgentStatus.Blocked))
        assertTrue(alerts.onStatus(pane, AgentStatus.Working))
    }

    @Test
    fun herdrsPanelessEchoOfAPaneNotificationNeverAddsASecondRow() {
        // lab.3 D6: Herdr's pane-less copy arrived ~1 s after the pane's Finished.
        val bare = AlertKey("lab", null)
        val posted = mapOf(pane to 10_000L)
        assertTrue(HerdrEcho.isEcho(bare, 11_000L, posted))
        assertFalse(HerdrEcho.isEcho(bare, 16_000L, posted))
        assertFalse(HerdrEcho.isEcho(AlertKey("box", null), 11_000L, posted))
        // The copy first, the pane's own notification second: the copy goes.
        assertEquals(bare, HerdrEcho.echoBefore(pane, 11_000L, mapOf(bare to 10_000L)))
        assertNull(HerdrEcho.echoBefore(pane, 20_000L, mapOf(bare to 10_000L)))
    }

    @Test
    fun elapsedTimeIsKnownOnlyFromAChangeHerdlineSawItself() {
        val clock = StatusClock()
        val key = MonitorKey("lab", "w1:p1")
        assertNull(clock.observe(key, AgentStatus.Working, 100))
        assertNull(clock.observe(key, AgentStatus.Working, 200))
        assertEquals(300L, clock.observe(key, AgentStatus.Blocked, 300))
        assertEquals(300L, clock.observe(key, AgentStatus.Blocked, 400))
        clock.forget("lab")
        // Whatever happened while the link was down has no known start.
        assertNull(clock.observe(key, AgentStatus.Done, 500))
        assertEquals(600L, clock.observe(key, AgentStatus.Working, 600))
    }

    @Test
    fun aWorkingRunHasALengthOnlyWhenHerdlineSawItStartAndEnd() {
        val clock = StatusClock()
        val key = MonitorKey("lab", "w1:p1")
        clock.observe(key, AgentStatus.Working, 100)
        clock.observe(key, AgentStatus.Done, 200)
        // The run's start was not seen.
        assertNull(clock.worked(key))
        clock.observe(key, AgentStatus.Working, 300)
        assertNull(clock.worked(key))
        clock.observe(key, AgentStatus.Done, 1_300)
        assertEquals(1_000L, clock.worked(key))
        // Kept while Done repeats, dropped once the status moves on.
        clock.observe(key, AgentStatus.Done, 2_000)
        assertEquals(1_000L, clock.worked(key))
        clock.observe(key, AgentStatus.Idle, 3_000)
        assertNull(clock.worked(key))
    }
}
