package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DoneCardsTest {
    private val key = MonitorKey("host", "pane")
    private val pins = setOf(key)
    private fun card(status: AgentStatus, stale: Long? = null) = LiveCard(
        listOf(LiveRow(key.nodeId, "Host", key.pane, "Agent", null, status, null)), stale,
    )

    @Test
    fun doneExpiresButTheSamePinnedAgentShowsItsNextTask() {
        val done = DoneCards()
        assertEquals(LiveState.Working, liveState(done.visible(card(AgentStatus.Working), pins, 100)!!))
        assertEquals(LiveState.Done, liveState(done.visible(card(AgentStatus.Done), pins, 200)!!))
        assertEquals(10_200L, done.nextExpiry(200))
        assertEquals(LiveState.Done, liveState(done.visible(card(AgentStatus.Done), pins, 10_199)!!))
        assertNull(done.visible(card(AgentStatus.Done), pins, 10_200))
        assertNull(done.nextExpiry(10_200))
        assertNull(done.visible(card(AgentStatus.Done), pins, 20_000))
        assertEquals(LiveState.Working, liveState(done.visible(card(AgentStatus.Working), pins, 20_001)!!))
        assertEquals(LiveState.Done, liveState(done.visible(card(AgentStatus.Done), pins, 20_002)!!))
        assertEquals(30_002L, done.nextExpiry(20_002))
    }

    @Test
    fun aLostLinkDoesNotRestartAnExpiredDoneEpisodeAndUnpinClearsIt() {
        val done = DoneCards()
        done.visible(card(AgentStatus.Done), pins, 100)
        assertNull(done.visible(card(AgentStatus.Done, stale = 90), pins, 10_100))
        assertNull(done.visible(card(AgentStatus.Done), pins, 11_000))
        done.visible(null, emptySet(), 11_001)
        assertEquals(LiveState.Done, liveState(done.visible(card(AgentStatus.Done), pins, 11_002)!!))
    }
}
