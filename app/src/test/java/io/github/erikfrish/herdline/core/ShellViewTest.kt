/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShellViewTest {
    private val json = """
        {"revision":7,"focused_workspace":"w1","focused_tab":"w1:t2","focused_pane":"p2","update_available":null,
         "workspaces":[{"id":"w1","number":1,"label":"api","branch":"main","ahead":2,"behind":null,
           "worktree":"feat","linked_worktree":true,"active_tab":"w1:t2","focused":true,"status":"blocked"}],
         "tabs":[{"id":"w1:t2","workspace":"w1","number":2,"label":"agent","zoomed":false,"focused":true,"status":"working"},
                 {"id":"w1:t1","workspace":"w1","number":1,"label":"shell","zoomed":true,"focused":false,"status":"unknown"}],
         "panes":[{"id":"p2","workspace":"w1","tab":"w1:t2","label":null,"cwd":"/src/api","focused":true}],
         "agents":[{"pane":"p2","workspace":"w1","tab":"w1:t2","name":null,"agent":"claude","title":"Fix tests",
           "status":"blocked","state_seq":478,"focused":true}]}
    """.trimIndent()

    @Test
    fun parsesHerdrConcepts() {
        val view = ShellView.parse(json)
        assertEquals(7L, view.revision)
        assertEquals("api", view.workspace?.label)
        assertEquals(2, view.workspace?.ahead)
        assertNull(view.workspace?.behind)
        assertEquals(AgentStatus.Blocked, view.workspace?.status)
        assertEquals(listOf("shell", "agent"), view.tabsOf("w1").map { it.label })
        assertEquals("agent", view.tab?.label)
        assertEquals("/src/api", view.panesOf("w1:t2").single().cwd)
        val agent = view.agentIn("p2")!!
        assertEquals("claude", agent.displayName)
        assertEquals(AgentStatus.Blocked, agent.status)
        assertEquals(478L, agent.stateSeq)
        // An older core that sends no sequence leaves the order unknown, not zero.
        assertNull(ShellView.parse(json.replace("\"state_seq\":478,", "")).agentIn("p2")!!.stateSeq)
        assertNull(view.updateAvailable)
    }

    @Test
    fun unknownStatusesAreUnknown() {
        assertEquals(AgentStatus.Unknown, AgentStatus.parse("sleeping"))
    }

    @Test
    fun attentionPutsAskingAgentsFirstAndKeepsDaemonOrderWithinAStatus() {
        fun agent(pane: String, status: AgentStatus) = Agent(pane, "w1", "w1:t1", null, "omp", null, status, false)
        val view = ShellView.parse(json).copy(
            agents = listOf(
                agent("p1", AgentStatus.Idle),
                agent("p2", AgentStatus.Working),
                agent("p3", AgentStatus.Done),
                agent("p4", AgentStatus.Blocked),
                agent("p5", AgentStatus.Working),
                agent("p6", AgentStatus.Unknown),
            ),
        )
        assertEquals(listOf("p4", "p2", "p5", "p3", "p1", "p6"), view.agentsByAttention().map { it.pane })
    }
}
