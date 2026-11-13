package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverviewModelTest {
    // Two workspaces share the label "api"; w1 has a split agent tab (t1),
    // a one-pane shell tab (t2) and a four-pane tab (t4); w3 one agent tab.
    private val view = ShellView(
        revision = 1, boot = "b", focusedWorkspace = "w1", focusedTab = "t2", focusedPane = "p3", updateAvailable = null,
        workspaces = listOf(
            Workspace("w1", 1, "api", "main", null, null, null, false, "t2", true, AgentStatus.Blocked),
            Workspace("w2", 2, "web", null, null, null, null, false, "t3", false, AgentStatus.Working),
            Workspace("w3", 3, "api", "fix", null, null, null, true, "t5", false, AgentStatus.Blocked),
        ),
        tabs = listOf(
            Tab("t1", "w1", 1, "agents", false, false, AgentStatus.Blocked),
            Tab("t2", "w1", 2, "logs", false, true, AgentStatus.Unknown),
            Tab("t4", "w1", 3, "grid", false, false, AgentStatus.Unknown),
            Tab("t3", "w2", 1, "ui", false, false, AgentStatus.Working),
            Tab("t5", "w3", 1, "hotfix", false, false, AgentStatus.Blocked),
        ),
        panes = listOf(
            Pane("p1", "w1", "t1", null, "/src/api", false),
            Pane("p2", "w1", "t1", null, "/src/api/db", false),
            Pane("p3", "w1", "t2", null, "/var/log", true),
            Pane("p6", "w1", "t4", null, "/a", false),
            Pane("p7", "w1", "t4", null, "/b", false),
            Pane("p8", "w1", "t4", null, "/c", false),
            Pane("p9", "w1", "t4", null, "/d", false),
            Pane("p4", "w2", "t3", null, "/src/web", false),
            Pane("p5", "w3", "t5", null, "/src/api", false),
        ),
        agents = listOf(
            Agent("p1", "w1", "t1", null, "omp", "Fix migration", AgentStatus.Blocked, false),
            Agent("p2", "w1", "t1", "reviewer", "claude", null, AgentStatus.Working, false),
            Agent("p4", "w2", "t3", null, "codex", "Login form", AgentStatus.Working, false),
            Agent("p5", "w3", "t5", null, "omp", "Hotfix", AgentStatus.Blocked, false),
        ),
    )
    private val groups = topology(view)

    private fun sections(query: String = "", filter: SwitcherFilter = SwitcherFilter.All, scope: String? = null): List<OverviewSection> {
        val inScope = tabsScope(groups, scope, loaded = true).groups
        return overviewSections(inScope, inScope.narrowed(query, filter), query.isNotBlank() || filter != SwitcherFilter.All, view)
    }

    private fun List<OverviewSection>.cards() = flatMap { it.cards }

    @Test
    fun twoColumnsOnlyWhenTwoRowsStayReadable() {
        // A phone at font scale 1 keeps two columns; the accessibility scale
        // or a narrow window falls back to one, which is the readability floor.
        assertEquals(2, compactColumns(411f, 1f))
        assertEquals(1, compactColumns(360f, 1.3f))
        assertEquals(1, compactColumns(411f, 1.3f))
        assertEquals(2, compactColumns(840f, 1.3f))
        // Never past two, never one that cannot carry a row.
        assertEquals(2, compactColumns(2000f, 1f))
        assertEquals(1, compactColumns(320f, 1f))
    }


    @Test
    fun onlyWorkspacesSharingALabelGetASuffix() {
        assertEquals(mapOf("w1" to 1, "w3" to 2), workspaceSuffixes(view.workspaces))
        val bySpace = sections().associate { it.workspace.id to it.suffix }
        assertEquals(1, bySpace["w1"])
        assertNull(bySpace["w2"])
        assertEquals(2, bySpace["w3"])
    }

    @Test
    fun theScopeComposesWithStatusAndSearchAndAClosedOneNeverWidens() {
        // In scope, the filter and the search narrow only that workspace's tabs and counts.
        val w1 = tabsScope(groups, "w1", loaded = true)
        assertEquals("w1" to false, w1.chosen to w1.gone)
        assertEquals(listOf("t1"), sections(filter = SwitcherFilter.Working, scope = "w1").cards().map { it.tab.id })
        assertEquals(emptyList<String>(), sections(query = "hotfix", scope = "w1").cards().map { it.tab.id })
        assertEquals(listOf("t5"), sections(query = "hotfix").cards().map { it.tab.id })
        assertEquals(FilterCounts(1, 1, 0), w1.groups.agentCounts(""))
        assertEquals(FilterCounts(0, 0, 0), w1.groups.agentCounts("hotfix"))
        // A closed workspace is gone: nothing listed or counted, not All.
        val gone = tabsScope(groups, "gone", loaded = true)
        assertEquals(null to true, gone.chosen to gone.gone)
        assertTrue(gone.groups.isEmpty())
        assertEquals(FilterCounts(0, 0, 0), gone.groups.agentCounts(""))
        // Before the first view a saved choice is not gone yet.
        assertEquals(false, tabsScope(emptyList(), "w1", loaded = false).gone)
        assertEquals(listOf("w1", "w2", "w3"), tabsScope(groups, null, loaded = true).groups.map { it.workspace.id })
    }

    @Test
    fun aOnePaneShellTabStillGetsItsPaneTileAndIsCurrent() {
        val logs = sections().cards().single { it.tab.id == "t2" }
        assertEquals(listOf("p3"), logs.panes.map { it.pane.id })
        assertTrue(logs.current)
        assertEquals(false, logs.split)
        assertEquals(listOf("t2"), sections().cards().filter { it.current }.map { it.tab.id })
    }

    @Test
    fun aQueryOnOnePaneMarksAPartialMatchAndThePickerListsItFirst() {
        val card = sections(query = "db").cards().single()
        assertEquals("t1", card.tab.id)
        assertEquals(listOf("p2"), card.matched?.map { it.pane.id })
        val (first, rest) = pickerPanes(card)
        assertEquals(listOf("p2"), first.map { it.pane.id })
        assertEquals(listOf("p1"), rest.map { it.pane.id })
        // With nothing narrowing, the picker has no Matches part.
        val plain = sections().cards().single { it.tab.id == "t1" }
        assertNull(plain.matched)
        assertEquals(emptyList<PaneTile>() to listOf("p1", "p2"), pickerPanes(plain).let { (m, r) -> m to r.map { it.pane.id } })
    }

    @Test
    fun aFilterKeepsHerdrOrder() {
        assertEquals(listOf("t1", "t5"), sections(filter = SwitcherFilter.NeedsYou).cards().map { it.tab.id })
        assertEquals(listOf("t1", "t3"), sections(filter = SwitcherFilter.Working).cards().map { it.tab.id })
        assertEquals(listOf("t1", "t2", "t4", "t3", "t5"), sections().cards().map { it.tab.id })
        // Nothing idle or done lives in this fixture: Ready lists no card at all.
        assertEquals(emptyList<String>(), sections(filter = SwitcherFilter.Ready).cards().map { it.tab.id })
    }

    @Test
    fun theFocusedCardIndexCountsWorkspaceHeadersOnlyWhenShown() {
        val all = sections()
        for ((columns, second, nextWorkspace) in listOf(Triple(1, 2, 5), Triple(2, 1, 4))) {
            // The second card shares the first row only with two columns.
            assertEquals(second, overviewIndexOf(all, "t2", headers = true, columns = columns))
            assertEquals(second - 1, overviewIndexOf(all, "t2", headers = false, columns = columns))
            assertEquals(nextWorkspace, overviewIndexOf(all, "t3", headers = true, columns = columns))
            assertNull(overviewIndexOf(all, "missing", headers = true, columns = columns))
            assertNull(overviewIndexOf(all, null, headers = true, columns = columns))
        }
    }

    @Test
    fun chipCountsFollowTheScopeAndTheQuery() {
        assertEquals(FilterCounts(2, 2, 0), groups.agentCounts(""))
        assertEquals(FilterCounts(1, 1, 0), groups.scoped("w1").agentCounts(""))
        assertEquals(FilterCounts(0, 1, 0), groups.scoped("w2").agentCounts(""))
        assertEquals(FilterCounts(1, 0, 0), groups.agentCounts("hotfix"))
        // Ready counts idle and done agents the same way: one done agent in w1's shell tab.
        val withDone = topology(view.copy(agents = view.agents + Agent("p3", "w1", "t2", null, "omp", null, AgentStatus.Done, false)))
        assertEquals(FilterCounts(2, 2, 1), withDone.agentCounts(""))
        assertEquals(FilterCounts(0, 0, 1), withDone.agentCounts("/var"))
    }

    @Test
    fun inChatTheTabReadHereIsCurrentNeverTheDesktops() {
        // Herdr focuses t2 (p3); the phone reads p5 in t5.
        val current = overviewCurrentTab(view, here = "p5", reading = true)
        assertEquals("t5", current)
        val marked = overviewSections(groups, groups, false, view, current).cards().filter { it.current }.map { it.tab.id }
        assertEquals(listOf("t5"), marked)
        // S5: a closed reading pane marks nothing; it never falls back to the desktop's tab.
        assertNull(overviewCurrentTab(view, here = null, reading = true))
        assertNull(overviewCurrentTab(view, here = "gone", reading = true))
        // Outside Chat, Herdr's focus.
        assertEquals("t2", overviewCurrentTab(view, here = "p5", reading = false))
    }

    @Test
    fun aRailNarrowingEmptiedStaysAndCountsItsFullTabsAndTabsThatNeedYou() {
        // t1 has two blocked agents: one tab needs you, not two.
        val both = view.copy(agents = view.agents.map { if (it.pane == "p2") it.copy(status = AgentStatus.Blocked) else it })
        val all = topology(both)
        val shown = all.narrowed("", SwitcherFilter.Working)
        val rails = overviewRails(all, overviewSections(all, shown, narrowing = true, both, currentTab = "t2"), both, currentTab = "t2")
        assertEquals(listOf("w1", "w2", "w3"), rails.map { it.workspace.id })
        val w1 = rails[0]
        assertTrue(w1.cards.isEmpty())
        assertEquals(3, w1.tabs)
        assertEquals(1, w1.needsYou)
        // The current tab is filtered out, but its workspace is still the current one.
        assertTrue(w1.current)
        assertEquals(listOf("t3"), rails[1].cards.map { it.tab.id })
        assertEquals(0, rails[1].needsYou)
        assertEquals(1, rails[2].tabs)
        assertTrue(rails[2].cards.isEmpty())
    }
}
