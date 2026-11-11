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
import kotlin.math.abs

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
    fun columnsFollowTheSpecTableAcrossWidthsAndFontScales() {
        val expected = mapOf(
            360f to listOf(2, 1, 1),
            411f to listOf(2, 1, 1),
            486f to listOf(2, 2, 2),
            600f to listOf(3, 2, 2),
            840f to listOf(4, 4, 3),
        )
        for ((width, columns) in expected) {
            assertEquals("width $width", columns, listOf(1f, 1.3f, 1.45f).map { overviewColumns(width, it) })
        }
        // Never zero, never past four, however narrow or wide.
        assertEquals(1, overviewColumns(120f, 2f))
        assertEquals(4, overviewColumns(2000f, 1f))
    }

    @Test
    fun cardHeightsGrowWithTheFontAsTheSpecSays() {
        for ((scale, height) in listOf(1f to 194f, 1.3f to 222f, 1.45f to 236f)) {
            assertTrue("fs $scale: ${overviewCardDp(scale)}", abs(overviewCardDp(scale) - height) < 0.5f)
        }
    }

    @Test
    fun narrowCardsShowTwoTilesWideOnesThreeAndTheRestAsPlusK() {
        val grid = sections().cards().single { it.tab.id == "t4" }
        val (narrow, narrowMore) = grid.tiles(160f)
        assertEquals(listOf("p6"), narrow.map { it.pane.id })
        assertEquals(3, narrowMore)
        val (wide, wideMore) = grid.tiles(260f)
        assertEquals(listOf("p6", "p7"), wide.map { it.pane.id })
        assertEquals(2, wideMore)
        // Panes that fit are all shown, with no "+k".
        val agents = sections().cards().single { it.tab.id == "t1" }
        assertEquals(listOf("p1", "p2") to 0, agents.tiles(160f).let { (t, k) -> t.map { it.pane.id } to k })
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
        assertEquals(1 to 1, w1.groups.agentCounts(""))
        assertEquals(0 to 0, w1.groups.agentCounts("hotfix"))
        // A closed workspace is gone: nothing listed or counted, not All.
        val gone = tabsScope(groups, "gone", loaded = true)
        assertEquals(null to true, gone.chosen to gone.gone)
        assertTrue(gone.groups.isEmpty())
        assertEquals(0 to 0, gone.groups.agentCounts(""))
        // Before the first view a saved choice is not gone yet.
        assertEquals(false, tabsScope(emptyList(), "w1", loaded = false).gone)
        assertEquals(listOf("w1", "w2", "w3"), tabsScope(groups, null, loaded = true).groups.map { it.workspace.id })
    }

    @Test
    fun aOnePaneShellTabStillGetsItsPaneTileAndIsCurrent() {
        val logs = sections().cards().single { it.tab.id == "t2" }
        assertEquals(listOf("p3"), logs.panes.map { it.pane.id })
        assertEquals("log", logs.panes.single().place)
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
    }

    @Test
    fun theFocusedCardIndexCountsWorkspaceHeadersOnlyWhenShown() {
        val all = sections()
        // Header w1, t1, t2: with headers t2 is item 2, without them item 1.
        assertEquals(2, overviewIndexOf(all, "t2", headers = true))
        assertEquals(1, overviewIndexOf(all, "t2", headers = false))
        // Header w1, three cards, header w2, t3.
        assertEquals(5, overviewIndexOf(all, "t3", headers = true))
        assertNull(overviewIndexOf(all, "missing", headers = true))
        assertNull(overviewIndexOf(all, null, headers = true))
    }

    @Test
    fun chipCountsFollowTheScopeAndTheQuery() {
        assertEquals(2 to 2, groups.agentCounts(""))
        assertEquals(1 to 1, groups.scoped("w1").agentCounts(""))
        assertEquals(0 to 1, groups.scoped("w2").agentCounts(""))
        assertEquals(1 to 0, groups.agentCounts("hotfix"))
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
}
