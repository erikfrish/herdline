package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ShellView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentSummaryTest {
    private fun view(boot: String, agents: List<Pair<String, AgentStatus>>) = ShellView(
        revision = 1, boot = boot, focusedWorkspace = null, focusedTab = null, focusedPane = null, updateAvailable = null,
        workspaces = emptyList(), tabs = emptyList(), panes = emptyList(),
        agents = agents.map { (pane, status) -> Agent(pane, "w1", "w1:t1", null, "omp", null, status, false) },
    )

    private fun node(id: String, connected: Boolean, view: ShellView?) = NodeSnapshot(id, id, connected, view, 0)

    @Test
    fun oneDaemonAttachedTwiceCountsItsAgentsOnce() {
        for ((firstBoot, secondBoot) in listOf("b1" to "b1", "core-daemon-42-1" to "core-daemon-42-2")) {
            val agents = listOf("p1" to AgentStatus.Working, "p2" to AgentStatus.Idle)
            val summary = agentSummary(listOf(
                node("a", true, view(firstBoot, agents)),
                node("b", true, view(secondBoot, agents)),
                node("c", true, view("other-daemon", listOf("p1" to AgentStatus.Working))),
            ))
            assertEquals(AgentSummary(working = 2, waiting = 0, idle = 1, done = 0, unknown = 0, connected = 3, unavailable = 0), summary)
        }
    }

    @Test
    fun aLostNodesRetainedViewCountsNoAgentsAndOneUnavailable() {
        val summary = agentSummary(
            listOf(
                node("live", true, view("b1", listOf("p1" to AgentStatus.Idle))),
                node("lost", false, view("b2", listOf("p1" to AgentStatus.Working, "p2" to AgentStatus.Blocked))),
                node("connecting", true, null),
            ),
        )
        assertEquals(AgentSummary(working = 0, waiting = 0, idle = 1, done = 0, unknown = 0, connected = 1, unavailable = 2), summary)
        assertTrue(summary.partial)
        assertFalse(summary.active)
    }

    @Test
    fun noNodesIsAllZeroAndUnavailable() {
        val summary = agentSummary(emptyList())
        assertEquals(AgentSummary(0, 0, 0, 0, 0, 0, 0), summary)
        assertFalse(summary.available)
        assertFalse(summary.partial)
    }

    @Test
    fun manyAgentsAreCountedExactly() {
        val agents = (0 until 200).map { "w$it" to AgentStatus.Working } +
            (0 until 2).map { "b$it" to AgentStatus.Blocked } +
            (0 until 2000).map { "i$it" to AgentStatus.Idle }
        val summary = agentSummary(listOf(node("a", true, view("b1", agents))))
        assertEquals(AgentSummary(working = 200, waiting = 2, idle = 2000, done = 0, unknown = 0, connected = 1, unavailable = 0), summary)
        assertTrue(summary.active)
    }

    @Test
    fun doneAndUnknownKeepTheirOwnCounts() {
        val summary = agentSummary(
            listOf(node("a", true, view("b1", listOf("p1" to AgentStatus.Done, "p2" to AgentStatus.Done, "p3" to AgentStatus.Unknown)))),
        )
        assertEquals(AgentSummary(working = 0, waiting = 0, idle = 0, done = 2, unknown = 1, connected = 1, unavailable = 0), summary)
        assertFalse(summary.active)
    }
}
