package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class TitlesTest {
    // lab.3 D1: two named tabs whose OMP agents carry the same task title, and an unnamed one.
    private val view = ShellView(
        revision = 1, boot = "b", focusedWorkspace = "w1", focusedTab = "t1", focusedPane = "p1", updateAvailable = null,
        workspaces = listOf(Workspace("w1", 1, "qa-alpha", "main", null, null, null, false, "t1", true, AgentStatus.Idle)),
        tabs = listOf(
            Tab("t1", "w1", 1, "alice", false, true, AgentStatus.Idle),
            Tab("t2", "w1", 2, "alice2", false, false, AgentStatus.Idle),
            Tab("t3", "w1", 3, "3", false, false, AgentStatus.Idle),
        ),
        panes = listOf(
            Pane("p1", "w1", "t1", null, "/tmp/work", true),
            Pane("p2", "w1", "t2", null, "/tmp/work", false),
            Pane("p3", "w1", "t3", "π > \uDB80\uDF68 Reply with ONE", "/tmp/work", false),
        ),
        agents = listOf(
            Agent("p1", "w1", "t1", null, "omp", "π > work", AgentStatus.Idle, true),
            Agent("p2", "w1", "t2", null, "omp", "π > work", AgentStatus.Idle, false),
        ),
    )

    @Test
    fun namedTabsKeepTheirHerdrNamesWhileTheirAgentsShareATitle() {
        assertEquals("alice", view.tabTitle("t1"))
        assertEquals("alice2", view.tabTitle("t2"))
        // The pane itself is still named by its task.
        assertEquals("work", view.paneTitle("p1"))
    }

    @Test
    fun anUnnamedTabFallsBackToItsSanitizedPaneLabel() {
        assertEquals("Reply with ONE", view.tabTitle("t3"))
    }

    @Test
    fun agentsWithTheSameTaskGetNotificationsThatNameTheirTabs() {
        // lab.3 D6: Herdr's own text read "lab · 1 · 1" for both agents.
        assertEquals("qa-alpha › alice · work", notificationBody(view, "p1"))
        assertEquals("qa-alpha › alice2 · work", notificationBody(view, "p2"))
    }
}
