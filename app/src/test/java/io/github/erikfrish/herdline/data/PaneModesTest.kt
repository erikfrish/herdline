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
        assertFalse(showsChat(stored, p2, "boot-1", "omp", chatAvailable = true, remembered = null))
        assertTrue(showsChat(stored, p1, "boot-1", "omp", chatAvailable = true, remembered = null))
        // After a daemon restart the old Terminal choice no longer applies: Chat, the agent default.
        assertTrue(showsChat(stored, p2, "boot-2", "omp", chatAvailable = true, remembered = null))
        // Where Chat cannot read the pane, a stored Chat never shows.
        assertFalse(showsChat(stored, p1, "boot-1", "omp", chatAvailable = false, remembered = null))
    }

    @Test
    fun aPaneChoiceBeatsTheSessionsRememberedModeWhichBeatsTheDefault() {
        val fresh = ModeKey("n1", "main", "w2:p1")
        // The pane's own choice wins either way.
        assertTrue(showsChat(stored, p1, "boot-1", "omp", chatAvailable = true, remembered = false))
        assertFalse(showsChat(stored, p2, "boot-1", "omp", chatAvailable = true, remembered = true))
        // A pane with no choice, or a stale one, takes the session's last tapped mode.
        assertFalse(showsChat(stored, fresh, "boot-1", "omp", chatAvailable = true, remembered = false))
        assertFalse(showsChat(stored, p1, "boot-2", "omp", chatAvailable = true, remembered = false))
        // Nothing remembered: Chat.
        assertTrue(showsChat(stored, fresh, "boot-1", "omp", chatAvailable = true, remembered = null))
    }

    @Test
    fun unreadableChatShowsTerminalAndTheRememberedChatAppliesOnceReadable() {
        val prefs = MemoryPrefs()
        val sessionPrefs = MemoryPrefs()
        val modes = PaneModes(prefs, sessionPrefs)
        val session = SessionKey("n1", "main")
        modes.prefer(session, chat = true)
        val fresh = ModeKey("n1", "main", "w2:p1")
        // Stock OMP: Chat cannot read it, so Terminal, and resolving that writes nothing.
        assertFalse(showsChat(modes.choices.value, fresh, "boot-1", "omp", chatAvailable = false, remembered = modes.sessions.value[session]))
        assertEquals(emptyMap<String, Any?>(), prefs.values)
        assertEquals(mapOf(session.encode() to true), sessionPrefs.values)
        // OMP restarted with the integration: the remembered Chat applies again.
        assertTrue(showsChat(modes.choices.value, fresh, "boot-1", "omp", chatAvailable = true, remembered = modes.sessions.value[session]))
    }

    @Test
    fun theRememberedModeSurvivesAnAppRestartAndADaemonRestart() {
        val prefs = MemoryPrefs()
        val sessionPrefs = MemoryPrefs()
        val before = PaneModes(prefs, sessionPrefs)
        before.prefer(SessionKey("n1", "main"), chat = false)
        before.prefer(SessionKey("n1", "main"), chat = true)
        before.prefer(SessionKey("n1", "main"), chat = false)
        before.set(p1, ModeChoice("boot-1", "omp", chat = true))
        // A new daemon run drops the pane's choice, never the session's mode.
        before.forget("n1", "main", "boot-2") { "omp" }

        val after = PaneModes(prefs, sessionPrefs)
        assertEquals(mapOf(SessionKey("n1", "main") to false), after.sessions.value)
        assertEquals(emptyMap<ModeKey, ModeChoice>(), after.choices.value)
        assertFalse(showsChat(after.choices.value, p1, "boot-2", "omp", chatAvailable = true, remembered = after.sessions.value[SessionKey("n1", "main")]))
    }
}
