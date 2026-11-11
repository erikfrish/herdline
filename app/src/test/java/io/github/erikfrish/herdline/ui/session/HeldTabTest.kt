package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeldTabTest {
    private val view = ShellView(
        revision = 1, boot = "b", focusedWorkspace = "w1", focusedTab = "t1", focusedPane = "p1", updateAvailable = null,
        workspaces = listOf(Workspace("w1", 1, "api", "main", null, null, null, false, "t1", true, AgentStatus.Idle)),
        tabs = listOf(
            Tab("t1", "w1", 1, "agents", false, true, AgentStatus.Idle),
            Tab("t2", "w1", 2, "logs", false, false, AgentStatus.Unknown),
        ),
        panes = listOf(
            Pane("p1", "w1", "t1", null, "/src", true),
            Pane("p2", "w1", "t1", null, "/src/db", false),
            Pane("p3", "w1", "t2", null, "/var/log", false),
        ),
        agents = emptyList(),
    )

    @Test
    fun aSplitTabIsHeldWhenAnyOfItsPanesIsAndNamesThatPane() {
        assertEquals("p2", view.heldPaneOf("t1", setOf("p2")))
    }

    @Test
    fun anotherTabsHeldPaneDoesNotMarkThisTab() {
        assertNull(view.heldPaneOf("t2", setOf("p1", "p2")))
        assertNull(view.heldPaneOf("t1", emptySet()))
    }
}
