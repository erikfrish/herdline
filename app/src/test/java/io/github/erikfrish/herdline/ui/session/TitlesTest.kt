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
    fun aPaneCueNamesTheTabTheHeaderShowsNotThePaneTask() {
        // Regression: Opening Terminal said the pane's "work" under the tab named l6-unread.
        assertEquals("alice", view.paneTabTitle("p1"))
        assertEquals("Reply with ONE", view.paneTabTitle("p3"))
        assertEquals("Pane", view.paneTabTitle("gone"))
    }

    @Test
    fun agentsWithTheSameTaskGetNotificationsThatNameTheirTabs() {
        // lab.3 D6: Herdr's own text read "lab · 1 · 1" for both agents.
        assertEquals("qa-alpha › alice · work", notificationBody(view, "p1"))
        assertEquals("qa-alpha › alice2 · work", notificationBody(view, "p2"))
    }

    @Test
    fun theComposerNamesTheTabWhateverTheHarnessTitlesItsTerminal() {
        // Regression: Kilo's composer read "Message Kilo CLI | QA-MOCK…" while its To line said kilo-g3c.
        val shell = ShellView(
            revision = 1, boot = "b", focusedWorkspace = "w1", focusedTab = "t1", focusedPane = "k1", updateAvailable = null,
            workspaces = listOf(
                Workspace("w1", 1, "qa-alpha", "main", null, null, null, false, "t1", true, AgentStatus.Idle),
                Workspace("w2", 2, "qa-beta", "main", null, null, null, false, "t3", false, AgentStatus.Idle),
            ),
            tabs = listOf(
                Tab("t1", "w1", 1, "kilo-g3c", false, true, AgentStatus.Idle),
                Tab("t2", "w1", 2, "alice", false, false, AgentStatus.Idle),
                Tab("t3", "w2", 1, "alice", false, false, AgentStatus.Idle),
            ),
            panes = listOf(
                Pane("k1", "w1", "t1", null, "/tmp/work", true),
                Pane("k2", "w1", "t1", null, "/tmp/work", false),
                Pane("a1", "w1", "t2", null, "/tmp/work", false),
                Pane("a2", "w2", "t3", null, "/tmp/work", false),
            ),
            agents = listOf(Agent("k1", "w1", "t1", null, "kilo", "Kilo CLI | QA-MOCK synthetic reply", AgentStatus.Idle, true)),
        )
        assertEquals("Kilo CLI | QA-MOCK synthetic reply", shell.paneTitle("k1"))
        assertEquals("kilo-g3c", shell.composerTitle("k1"))
        // Two panes of one split tab: the To line tells them apart, the tab name stays plain.
        assertEquals("kilo-g3c", shell.composerTitle("k2"))
        // Tabs named alike in two workspaces: each says where it is.
        assertEquals("qa-alpha › alice", shell.composerTitle("a1"))
        assertEquals("qa-beta › alice", shell.composerTitle("a2"))
    }
}
