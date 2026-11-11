package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionOrderTest {
    private fun view(seqs: Map<String, Long?>) = ShellView(
        revision = 1, boot = "b1", focusedWorkspace = "w1", focusedTab = "t1", focusedPane = "p1", updateAvailable = null,
        workspaces = listOf(
            Workspace("w1", 1, "api", null, null, null, null, false, "t1", true, AgentStatus.Working),
            Workspace("w2", 2, "web", null, null, null, null, false, "t3", false, AgentStatus.Idle),
        ),
        tabs = listOf(
            Tab("t1", "w1", 1, "agents", false, true, AgentStatus.Working),
            Tab("t2", "w1", 2, "logs", false, false, AgentStatus.Unknown),
            Tab("t3", "w2", 1, "ui", false, false, AgentStatus.Idle),
            Tab("t4", "w2", 2, "docs", false, false, AgentStatus.Idle),
        ),
        panes = listOf(
            Pane("p1", "w1", "t1", null, null, true),
            Pane("p2", "w1", "t1", null, null, false),
            Pane("p3", "w1", "t2", null, null, false),
            Pane("p4", "w2", "t3", null, null, false),
            Pane("p5", "w2", "t4", null, null, false),
        ),
        agents = listOf("p1" to "t1", "p2" to "t1", "p4" to "t3", "p5" to "t4").map { (pane, tab) ->
            val workspace = if (tab in setOf("t1", "t2")) "w1" else "w2"
            Agent(pane, workspace, tab, null, "omp", null, AgentStatus.Idle, false, stateSeq = seqs[pane])
        },
    )

    private fun keys(view: ShellView) =
        lastActivityOrder(topology(view).places().activityKeys(view.boot) { it.stateSeq })

    private fun order(view: ShellView) = keys(view).map { it.key }

    @Test
    fun mostRecentChangeFirstWithNullSeqsAfterInHerdrOrder() {
        // p4's tab t3 changed last, then p2. p5's daemon sent no seq and the
        // shell tab t2 has no agent: both go after, in Herdr's order (t2 before t4).
        val order = order(view(mapOf("p1" to 30L, "p2" to 478L, "p4" to 497L, "p5" to null)))
        assertEquals(listOf("t:t3", "p:p2", "p:p1", "t:t2", "t:t4"), order)
    }

    @Test
    fun equalSeqsKeepHerdrOrder() {
        val order = order(view(mapOf("p1" to 7L, "p2" to 7L, "p4" to 7L, "p5" to 7L)))
        assertEquals(listOf("p:p1", "p:p2", "t:t3", "t:t4", "t:t2"), order)
    }

    @Test
    fun seqsOfDifferentDaemonRunsAreNeverCompared() {
        // A restarted daemon (or a second host) counts from scratch: its 900
        // says nothing against the first run's 50. Each run keeps its rows
        // together, in the order the runs first appear.
        val rows = listOf(
            ActivityKey("a1", "boot-a", 5),
            ActivityKey("b1", "boot-b", 900),
            ActivityKey("a2", "boot-a", 50),
            ActivityKey("b2", "boot-b", null),
        )
        assertEquals(listOf("a2", "a1", "b1", "b2"), lastActivityOrder(rows).map { it.key })
    }

    @Test
    fun aLiveChangeWhileTheSwitcherIsShownMovesNoRow() {
        val before = keys(view(mapOf("p1" to 30L, "p2" to 40L, "p4" to 50L, "p5" to 60L)))
        assertEquals(listOf("t:t4", "t:t3", "p:p2", "p:p1", "t:t2"), before.map { it.key })
        // p1 changes state: the fresh order puts it first, the shown one holds, and one change waits.
        val fresh = keys(view(mapOf("p1" to 61L, "p2" to 40L, "p4" to 50L, "p5" to 60L)))
        assertEquals("p:p1", fresh.first().key)
        val held = heldOrder(before, fresh)
        assertEquals(before, held)
        assertEquals(1, changedSince(held, fresh))
        assertEquals(0, changedSince(fresh, fresh))
        // A place that closes drops out, a new one joins at the end; the rest stay put.
        val reshaped = fresh.filter { it.key != "t:t3" } + ActivityKey("t:t9", "b1", null)
        assertEquals(listOf("t:t4", "p:p2", "p:p1", "t:t2", "t:t9"), heldOrder(before, reshaped).map { it.key })
    }

    @Test
    fun aPlaceKeepsItsHeldSectionUntilTheOrderIsUpdated() {
        // t2 has no seq when the switcher opens, then its first change arrives:
        // it stays among the places without one until the user updates the order.
        val before = listOf(ActivityKey("a", "b1", 5), ActivityKey("t2", "b1", null))
        val fresh = lastActivityOrder(listOf(ActivityKey("a", "b1", 5), ActivityKey("t2", "b1", 6)))
        assertEquals(listOf<Long?>(5, null), heldOrder(before, fresh).map { it.seq })
        assertEquals(1, changedSince(before, fresh))
    }

    @Test
    fun observedTimesUseTheLargestWholeUnit() {
        val minute = 60_000L
        assertEquals(Ago.Now, ago(0, 59_999))
        assertEquals(Ago.Minutes(12), ago(0, 12 * minute + 5))
        assertEquals(Ago.Hours(2), ago(0, 150 * minute))
        assertEquals(Ago.Days(1), ago(0, 25 * 60 * minute))
        // A clock that stepped back never shows a time in the future.
        assertEquals(Ago.Now, ago(10 * minute, 0))
    }
}
