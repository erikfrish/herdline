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
}
