package io.github.erikfrish.herdline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaneModesTest {
    private val p1 = ModeKey("n1", "main", "w1:p1")
    private val p2 = ModeKey("n1", "main", "w1:p2")
    private val elsewhere = ModeKey("n2", "main", "w1:p1")
    private val stored = mapOf(
        p1 to ModeChoice("boot-1", "omp", chat = true),
        p2 to ModeChoice("boot-1", "omp", chat = false),
        elsewhere to ModeChoice("boot-9", "omp", chat = false),
    )

    @Test
    fun aChoiceSurvivesWhileTheSameProgramRunsInTheSameDaemonRun() {
        assertEquals(emptyList<ModeKey>(), staleChoices(stored, "n1", "main", "boot-1") { "omp" })
    }

    @Test
    fun aRestartedDaemonForgetsEveryChoiceOnThatNodeAndSessionOnly() {
        assertEquals(setOf(p1, p2), staleChoices(stored, "n1", "main", "boot-2") { "omp" }.toSet())
        assertEquals(emptyList<ModeKey>(), staleChoices(stored, "n1", "other", "boot-2") { "omp" })
    }

    @Test
    fun aPaneThatLostOrChangedItsProgramForgetsItsChoice() {
        // A shell in each pane now (OMP exited, or the pane is gone): both forget.
        assertEquals(setOf(p1, p2), staleChoices(stored, "n1", "main", "boot-1") { null }.toSet())
        // Another program replaced OMP in w1:p1 only.
        assertEquals(listOf(p1), staleChoices(stored, "n1", "main", "boot-1") { pane -> if (pane == "w1:p1") "claude" else "omp" })
    }

    @Test
    fun aMatchingChoiceWinsAndAnythingElseOpensInTheDefault() {
        // Terminal chosen for an OMP pane stays Terminal; Chat chosen stays Chat.
        assertFalse(showsChat(stored, p2, "boot-1", "omp", chatAvailable = true))
        assertTrue(showsChat(stored, p1, "boot-1", "omp", chatAvailable = true))
        // After a daemon restart the old Terminal choice no longer applies: Chat, the agent default.
        assertTrue(showsChat(stored, p2, "boot-2", "omp", chatAvailable = true))
        // Where Chat cannot read the pane, a stored Chat never shows.
        assertFalse(showsChat(stored, p1, "boot-1", "omp", chatAvailable = false))
    }
}
