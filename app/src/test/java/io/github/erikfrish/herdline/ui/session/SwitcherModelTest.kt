package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SwitcherModelTest {
    // Two workspaces; "api" has a split tab with two agents and a single-shell tab.
    private val view = ShellView(
        revision = 1, boot = "b", focusedWorkspace = "w1", focusedTab = "t1", focusedPane = "p1", updateAvailable = null,
        workspaces = listOf(
            Workspace("w1", 1, "api", "main", null, null, null, false, "t1", true, AgentStatus.Blocked),
            Workspace("w2", 2, "web", "feature/login", null, null, null, false, "t3", false, AgentStatus.Blocked),
        ),
        // Tab numbers out of list order: Herdr's numbering wins.
        tabs = listOf(
            Tab("t2", "w1", 2, "logs", false, false, AgentStatus.Unknown),
            Tab("t1", "w1", 1, "agents", false, true, AgentStatus.Blocked),
            Tab("t3", "w2", 1, "ui", false, false, AgentStatus.Blocked),
        ),
        panes = listOf(
            Pane("p1", "w1", "t1", null, "/src/api", true),
            Pane("p2", "w1", "t1", null, "/src/api/db", false),
            Pane("p3", "w1", "t2", null, "/var/log", false),
            Pane("p4", "w2", "t3", null, "/src/web", false),
        ),
        agents = listOf(
            Agent("p1", "w1", "t1", null, "omp", "Fix migration", AgentStatus.Blocked, true),
            Agent("p2", "w1", "t1", "reviewer", "claude", null, AgentStatus.Working, false),
            Agent("p4", "w2", "t3", null, "codex", "Login form", AgentStatus.Blocked, false),
        ),
    )
    private val groups = topology(view)

    @Test
    fun aOnePaneTabIsOnlyItsTabRowAndASplitTabListsItsPanes() {
        // t1 splits, so its panes are rows; t2 and t3 are single-pane tabs,
        // agent or shell, and get no pane row at all.
        assertEquals(listOf("t:t1", "p:p1", "p:p2", "t:t2"), groups[0].rows.map { it.key })
        assertEquals(listOf("t:t3"), groups[1].rows.map { it.key })
        // Titles come from Titles.kt: the split tab wears its user label,
        // the single-pane shell tab its label too.
        assertEquals("agents", (groups[0].rows[0] as SwitchTarget.TabRow).title)
        assertEquals("logs", (groups[0].rows[3] as SwitchTarget.TabRow).title)
    }

    @Test
    fun everyAgentIsReachableEvenInASinglePaneTab() {
        // The codex agent lives in the one-pane tab t3, which shows no pane
        // row; the agent list still covers it.
        assertEquals(listOf("p1", "p2", "p4"), groups.flatMap { it.agentRows }.map { it.pane.id })
        assertEquals(listOf("p:p1", "p:p4"), groups.agentsWith(AgentStatus.Blocked).map { it.key })
        assertEquals(listOf("p:p2"), groups.agentsWith(AgentStatus.Working).map { it.key })
    }

    @Test
    fun everyQueryWordMustMatchSomewhereInTheRowContext() {
        // "web" is the workspace, "login" both the branch and the agent
        // title: the one-pane tab matches through its agent.
        val found = groups.narrowed("WEB login", SwitcherFilter.All)
        assertEquals(listOf("t:t3"), found.rows().map { it.key })
        assertEquals(listOf("p:p2"), groups.narrowed("db review", SwitcherFilter.All).rows().map { it.key })
        assertEquals(emptyList<WorkspaceGroup>(), groups.narrowed("nothing", SwitcherFilter.All))
    }

    @Test
    fun filtersReachAgentsThroughSplitPaneRowsAndSinglePaneTabs() {
        // Needs you keeps the split tab (one of its agents asks), the asking
        // pane under it, and the single-pane tab holding one; the working
        // pane is left out. Working keeps the tab and its working pane.
        assertEquals(
            listOf("t:t1", "p:p1", "t:t3"),
            groups.narrowed("", SwitcherFilter.NeedsYou).rows().map { it.key },
        )
        assertEquals(
            listOf("t:t1", "p:p2"),
            groups.narrowed("", SwitcherFilter.Working).rows().map { it.key },
        )
    }

    @Test
    fun nextNeedingInputCyclesThroughEveryAgentAndWraps() {
        // The single-pane agent t3/p4 is part of the cycle.
        assertEquals("p4", groups.nextNeedingInput("p1")?.pane?.id)
        assertEquals("p4", groups.nextNeedingInput("p2")?.pane?.id)
        assertEquals("p1", groups.nextNeedingInput("p4")?.pane?.id)
        assertEquals("p1", groups.nextNeedingInput(null)?.pane?.id)
        val calm = topology(view.copy(agents = view.agents.map { it.copy(status = AgentStatus.Idle) }))
        assertNull(calm.nextNeedingInput("p1"))
    }

    @Test
    fun aVisitMovesToTheFrontOnceAndTheListIsBounded() {
        val recent = listOf("a", "b", "c", "d", "e")
        assertEquals(listOf("c", "a", "b", "d", "e"), recentAfter(recent, "c"))
        assertEquals(listOf("f", "a", "b", "c", "d"), recentAfter(recent, "f"))
    }

    @Test
    fun aOnePaneTabRememberedUnderItsTabAndAgentKeysIsOnePlace() {
        // p4 is the only pane of tab t3, so "p:p4" and "t:t3" are one place;
        // a split tab's pane keeps its own row, and a closed place drops out.
        val places = groups.placesNamed(listOf("p:p4", "t:t3", "t:t2", "p:p1", "p:gone"))
        assertEquals(listOf("t:t3", "t:t2", "p:p1"), places.map { it.key })
    }

    // For hiding: a split tab with an idle agent and a shell, a shell tab, a
    // done tab, a split tab with an asking and a done agent, and a workspace
    // holding only a shell.
    private val calm = topology(
        ShellView(
            revision = 1, boot = "b", focusedWorkspace = "w1", focusedTab = "t1", focusedPane = "p1", updateAvailable = null,
            workspaces = listOf(
                Workspace("w1", 1, "api", "main", null, null, null, false, "t1", true, AgentStatus.Blocked),
                Workspace("w2", 2, "web", "main", null, null, null, false, "t5", false, AgentStatus.Unknown),
            ),
            tabs = listOf(
                Tab("t1", "w1", 1, "agents", false, true, AgentStatus.Idle),
                Tab("t2", "w1", 2, "logs", false, false, AgentStatus.Unknown),
                Tab("t3", "w1", 3, "build", false, false, AgentStatus.Done),
                Tab("t4", "w1", 4, "review", false, false, AgentStatus.Blocked),
                Tab("t5", "w2", 1, "shell", false, false, AgentStatus.Unknown),
            ),
            panes = listOf(
                Pane("p1", "w1", "t1", null, "/src/api", true),
                Pane("p2", "w1", "t1", null, "/src/api", false),
                Pane("p3", "w1", "t2", null, "/var/log", false),
                Pane("p4", "w1", "t3", null, "/src/api", false),
                Pane("p5", "w1", "t4", null, "/src/api", false),
                Pane("p6", "w1", "t4", null, "/src/api", false),
                Pane("p7", "w2", "t5", null, "/src/web", false),
            ),
            agents = listOf(
                Agent("p1", "w1", "t1", null, "omp", null, AgentStatus.Idle, true),
                Agent("p4", "w1", "t3", null, "codex", null, AgentStatus.Done, false),
                Agent("p5", "w1", "t4", null, "claude", null, AgentStatus.Blocked, false),
                Agent("p6", "w1", "t4", null, "omp", null, AgentStatus.Done, false),
            ),
        ),
    )

    @Test
    fun hidingShellsLeavesOutAgentlessTabsAndPanesAndEmptiedWorkspaces() {
        val hidden = calm.hiddenRows(hideShells = true, hideIdleDone = false)
        assertEquals(setOf("p:p2", "t:t2", "t:t5"), hidden.keys)
        assertEquals(3, hidden.count)
        val listed = calm.without(hidden.keys)
        // w2 held only the shell, so its header goes too; order never changes.
        assertEquals(listOf("w1"), listed.map { it.workspace.id })
        assertEquals(listOf("t:t1", "p:p1", "t:t3", "t:t4", "p:p5", "p:p6"), listed.rows().map { it.key })
    }

    @Test
    fun hidingIdleAndDoneTakesATabsPanesWithItAndCountsThemOnce() {
        val hidden = calm.hiddenRows(hideShells = false, hideIdleDone = true)
        // t1's agents are all idle, so its panes go with it; t4 has an asking
        // agent and stays, losing only its done pane.
        assertEquals(setOf("t:t1", "p:p1", "p:p2", "t:t3", "p:p6"), hidden.keys)
        assertEquals(3, hidden.count)
        assertEquals(listOf("t:t2", "t:t4", "p:p5", "t:t5"), calm.without(hidden.keys).rows().map { it.key })
    }

    @Test
    fun aRowThatNeedsYouIsNeverHiddenAndNothingIsHiddenWithBothTogglesOff() {
        val hidden = calm.hiddenRows(hideShells = true, hideIdleDone = true)
        assertEquals(listOf("t:t4", "p:p5"), calm.without(hidden.keys).rows().map { it.key })
        assertEquals(HiddenRows(emptySet(), 0), calm.hiddenRows(hideShells = false, hideIdleDone = false))
    }
}
