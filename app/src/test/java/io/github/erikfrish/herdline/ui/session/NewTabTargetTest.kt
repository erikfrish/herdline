package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Workspace
import org.junit.Assert.assertEquals
import org.junit.Test

class NewTabTargetTest {
    private val api = Workspace("w1", 1, "api", "main", null, null, null, false, "t1", true, AgentStatus.Idle)
    private val web = Workspace("w2", 2, "web", null, null, null, null, false, "t2", false, AgentStatus.Idle)

    @Test
    fun allWithSeveralWorkspacesAsksInsteadOfUsingTheFocusedOne() {
        // w1 is Herdr's focused workspace, possibly the desktop's; it is never assumed.
        assertEquals(NewTabTarget.Pick(listOf(api, web)), newTabTarget(listOf(api, web), chosen = null, gone = false))
    }

    @Test
    fun aChosenScopeOrTheOnlyWorkspaceIsUsedDirectly() {
        assertEquals(NewTabTarget.In(web), newTabTarget(listOf(api, web), chosen = "w2", gone = false))
        assertEquals(NewTabTarget.In(api), newTabTarget(listOf(api), chosen = null, gone = false))
    }

    @Test
    fun aClosedScopeAsksEvenWithOneWorkspaceLeft() {
        assertEquals(NewTabTarget.Pick(listOf(api)), newTabTarget(listOf(api), chosen = "w9", gone = true))
    }

    @Test
    fun aHostWithNoWorkspaceOffersNothing() {
        assertEquals(NewTabTarget.None, newTabTarget(emptyList(), chosen = null, gone = false))
    }
}
