package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ShellView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveCardTest {
    private fun view(vararg agents: Pair<String, AgentStatus>) = ShellView(
        revision = 1, boot = "b", focusedWorkspace = null, focusedTab = null, focusedPane = null, updateAvailable = null,
        workspaces = emptyList(), tabs = emptyList(), panes = emptyList(),
        agents = agents.map { (pane, status) -> Agent(pane, "w1", "w1:t1", null, "omp", null, status, false) },
    )

    private val noTimes: (MonitorKey) -> Long? = { null }

    @Test
    fun onlyMonitoredPanesStillInTheViewAppearMostUrgentFirst() {
        val card = liveCard(
            listOf(
                NodeSnapshot("a", "lab", true, view("p1" to AgentStatus.Working, "p2" to AgentStatus.Blocked, "p3" to AgentStatus.Done), 100),
                NodeSnapshot("b", "box", true, view("p1" to AgentStatus.Idle), 100),
            ),
            // "a"/"p9" left the view; "b"/"p1" shares a pane ID with "a"/"p1" but is not monitored.
            setOf(MonitorKey("a", "p1"), MonitorKey("a", "p2"), MonitorKey("a", "p9")),
            noTimes,
        )!!
        assertEquals(listOf("a" to "p2", "a" to "p1"), card.rows.map { it.nodeId to it.pane })
        assertEquals(1, card.needYou)
        assertNull(card.staleSince)
    }

    @Test
    fun aDisconnectedNodeMakesTheCardStaleFromItsLastSeenTime() {
        val monitored = setOf(MonitorKey("a", "p1"), MonitorKey("b", "p1"))
        val card = liveCard(
            listOf(
                NodeSnapshot("a", "lab", true, view("p1" to AgentStatus.Working), 900),
                NodeSnapshot("b", "box", false, view("p1" to AgentStatus.Working), 400),
            ),
            monitored,
            noTimes,
        )!!
        assertEquals(400L, card.staleSince)
    }

    @Test
    fun aDisconnectedNodeWithNoMonitoredAgentLeavesTheCardCurrent() {
        val card = liveCard(
            listOf(
                NodeSnapshot("a", "lab", true, view("p1" to AgentStatus.Working), 900),
                NodeSnapshot("b", "box", false, view("p2" to AgentStatus.Working), 400),
            ),
            setOf(MonitorKey("a", "p1")),
            noTimes,
        )!!
        assertNull(card.staleSince)
    }

    @Test
    fun noCardWithoutAMonitoredAgentInAnyView() {
        assertNull(liveCard(listOf(NodeSnapshot("a", "lab", true, null, 0)), setOf(MonitorKey("a", "p1")), noTimes))
    }
}
