package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatTargetTest {
    private fun pane(id: String, tab: String, focused: Boolean = false) = Pane(id, "w1", tab, null, null, focused)

    private fun view(panes: List<Pane>, agents: List<Agent> = emptyList(), boot: String = "b1") = ShellView(
        revision = 1,
        boot = boot,
        focusedWorkspace = "w1",
        focusedTab = panes.firstOrNull { it.focused }?.tab,
        focusedPane = panes.firstOrNull { it.focused }?.id,
        updateAvailable = null,
        workspaces = listOf(Workspace("w1", 1, "ws", null, null, null, null, false, "t2", true, AgentStatus.Idle)),
        tabs = panes.map { it.tab }.distinct().mapIndexed { i, t -> Tab(t, "w1", i + 1, t, false, false, AgentStatus.Idle) },
        panes = panes,
        agents = agents,
    )

    private fun omp(pane: String, tab: String) = Agent(pane, "w1", tab, null, "omp", null, AgentStatus.Idle, false)

    @Test
    fun desktopMovesNeverMoveTheTarget() {
        val read = ReadingTarget("p1", "b1")
        assertEquals(read, readingFor(read, "b1", opened = null, desktop = "p2"))
        assertEquals(read, readingFor(read, "b1", opened = "p3", desktop = "p2"))
    }

    @Test
    fun aNewDaemonRunAdoptsAgainOpenedFirst() {
        val read = ReadingTarget("p1", "b1")
        assertEquals(ReadingTarget("p2", "b2"), readingFor(read, "b2", opened = null, desktop = "p2"))
        assertEquals(ReadingTarget("p3", "b2"), readingFor(null, "b2", opened = "p3", desktop = "p2"))
        assertEquals(read, readingFor(read, null, opened = "p3", desktop = "p2"))
        assertNull(readingFor(null, "b1", opened = null, desktop = null))
    }

    @Test
    fun aClosedPaneKeepsTheTargetAndSaysSo() {
        val read = ReadingTarget("gone", "b1")
        val shown = view(listOf(pane("p2", "t1", focused = true)))
        assertEquals(read, readingFor(read, shown.boot, opened = null, desktop = "p2"))
        assertTrue(readingClosed(read, shown))
        assertFalse(readingClosed(ReadingTarget("p2", "b1"), shown))
        // Another run's view says nothing about this run's pane.
        assertFalse(readingClosed(read, shown.copy(boot = "b2")))
    }

    @Test
    fun aTabChoiceReadsLastReadThenFocusedThenOmpThenFirst() {
        val shown = view(
            listOf(pane("a", "t1"), pane("b", "t1"), pane("c", "t1", focused = true), pane("d", "t2"), pane("e", "t2"), pane("f", "t3")),
            agents = listOf(omp("e", "t2")),
        )
        assertEquals("b", paneForTab(shown, "t1", mapOf("t1" to "b")))
        // A last-read pane that closed falls through to the next rule.
        assertEquals("c", paneForTab(shown, "t1", mapOf("t1" to "gone")))
        assertEquals("e", paneForTab(shown, "t2", emptyMap()))
        assertEquals("f", paneForTab(shown, "t3", emptyMap()))
        assertNull(paneForTab(shown, "t9", emptyMap()))
        assertEquals("e", paneForWorkspace(shown, "w1", emptyMap()))
    }

    @Test
    fun theDesktopIsNamedOnlyWhenElsewhere() {
        assertEquals("p2", desktopElsewhere("p1", "p2"))
        assertNull(desktopElsewhere("p1", "p1"))
        assertNull(desktopElsewhere(null, "p2"))
        assertNull(desktopElsewhere("p1", null))
    }

    @Test
    fun aChoiceFromTerminalMovesTheDesktopEvenToAChatPaneAndFromChatOnlyToATerminalPane() {
        // QA F-B: Terminal on screen, an OMP tab whose saved or default mode is Chat; the desktop must go there.
        assertTrue(choiceMovesDesktop(chatShown = false, chosenShowsChat = true))
        assertTrue(choiceMovesDesktop(chatShown = false, chosenShowsChat = false))
        // CT-1: from Chat, a pane read in Chat stays local; one shown in Terminal is focused.
        assertFalse(choiceMovesDesktop(chatShown = true, chosenShowsChat = true))
        assertTrue(choiceMovesDesktop(chatShown = true, chosenShowsChat = false))
    }

    // SN-1, the m126 fixture: t5 and t6 both "l6-read" in "lab", t4 differing only in case, u1 "l6-read" in "other".
    private fun sameNames(): ShellView {
        val labels = listOf("a", "b", "c", "L6-read", "l6-read", "l6-read")
        val tabs = labels.mapIndexed { i, l -> Tab("t${i + 1}", "w1", i + 1, l, false, false, AgentStatus.Idle) } +
            Tab("u1", "w2", 1, "l6-read", false, false, AgentStatus.Idle)
        return ShellView(
            revision = 1,
            boot = "b1",
            focusedWorkspace = "w1",
            focusedTab = "t5",
            focusedPane = "p5",
            updateAvailable = null,
            workspaces = listOf(
                Workspace("w1", 1, "lab", null, null, null, null, false, "t5", true, AgentStatus.Idle),
                Workspace("w2", 2, "other", null, null, null, null, false, "u1", false, AgentStatus.Idle),
            ),
            tabs = tabs,
            panes = tabs.map { Pane("p-" + it.id, it.workspace, it.id, null, null, it.id == "t5") },
            agents = emptyList(),
        )
    }

    private fun ShellView.spoken(read: String, desktop: String): DesktopSpoken {
        val readTab = spokenTab(read, "node")!!
        val desktopTab = spokenTab(desktop, "node")!!
        val sameWorkspace = tabs.first { it.id == read }.workspace == tabs.first { it.id == desktop }.workspace
        return desktopSpoken(readTab, desktopTab, sameWorkspace)
    }

    @Test
    fun aDesktopTabNamedAsTheOneReadHereIsSaidByItsPlaceInTheWorkspace() {
        // Acceptance 1: reading t6, the desktop on t5 is "l6-read, tab 5 of 6", not "l6-read" again.
        assertEquals(DesktopSpoken.Numbered("l6-read", 5, 6), sameNames().spoken("t6", "t5"))
    }

    @Test
    fun aDesktopOnAnotherPaneOfTheTabReadHereIsSaidAsThisTab() {
        assertEquals(DesktopSpoken.SameTab, sameNames().spoken("t6", "t6"))
    }

    @Test
    fun aDesktopTabNamedAsTheOneReadHereInAnotherWorkspaceIsSaidByThatWorkspace() {
        assertEquals(DesktopSpoken.InWorkspace("l6-read", "other"), sameNames().spoken("t6", "u1"))
        // And back: reading u1, the desktop on t6 is in "lab".
        assertEquals(DesktopSpoken.InWorkspace("l6-read", "lab"), sameNames().spoken("u1", "t6"))
    }

    @Test
    fun aDesktopTabWithAnotherNameIsSaidByNameEvenWhenOnlyItsCaseDiffers() {
        assertEquals(DesktopSpoken.Named("c"), sameNames().spoken("t6", "t3"))
        // The displayed text differs, so it is no collision.
        assertEquals(DesktopSpoken.Named("L6-read"), sameNames().spoken("t6", "t4"))
    }

    @Test
    fun aWorkspaceWithNoReadableLabelIsSaidAsTheHeaderSaysIt() {
        val view = sameNames().let { v -> v.copy(workspaces = v.workspaces.map { if (it.id == "w2") it.copy(label = " ") else it }) }
        assertEquals(DesktopSpoken.InWorkspace("l6-read", "node"), view.spoken("t6", "u1"))
    }

    @Test
    fun aDesktopWorkspaceSaidAsTheOneReadHereIsSaidByItsPlace() {
        // SHOULD-1, acceptance 5: both workspaces "ws", reading t6, the desktop on u1 is "in workspace 2 of 2".
        val sameLabel = sameNames().let { v -> v.copy(workspaces = v.workspaces.map { it.copy(label = "ws") }) }
        assertEquals(DesktopSpoken.InWorkspaceNumbered("l6-read", 2, 2), sameLabel.spoken("t6", "u1"))
        assertEquals(DesktopSpoken.InWorkspaceNumbered("l6-read", 1, 2), sameLabel.spoken("u1", "t6"))
        // Both unreadable: both said as the node's title, so numbered too.
        val unreadable = sameNames().let { v -> v.copy(workspaces = v.workspaces.map { it.copy(label = " ") }) }
        assertEquals(DesktopSpoken.InWorkspaceNumbered("l6-read", 2, 2), unreadable.spoken("t6", "u1"))
        // The place is by Workspace.number, not list order.
        val reordered = sameLabel.let { v -> v.copy(workspaces = v.workspaces.reversed()) }
        assertEquals(DesktopSpoken.InWorkspaceNumbered("l6-read", 2, 2), reordered.spoken("t6", "u1"))
    }
}
