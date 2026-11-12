package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import org.junit.Assert.*
import org.junit.Test

class AttachedFieldsTest {
    private val t = object : NoteTexts {
        override fun attachedTo(host: String) = "Attached to $host"
        override fun attachedHosts(count: Int) = "$count hosts"
        override fun attachedDetail() = "old boilerplate"
        override fun lostTitle(host: String) = "lost $host"
        override fun lostGeneric() = "lost"
        override fun lostText() = "reconnect"
        override fun row(name: String, status: String) = "$name $status"
        override fun status(status: AgentStatus) = status.name
        override fun agentAttention() = "attention"
        override fun agentDone() = "done"
        override fun connected() = "Connected"
        override fun agentCounts(count: Int, needYou: Int) = "$count agents · $needYou need input"
        override fun agentsSummary() = "agents"
    }
    @Test
    fun attachmentFieldsKeepPrivacyAndNeverRestoreBoilerplate() {
        for (level in LockDetail.entries) for (locked in listOf(true, false)) {
            val shown = attachedContent(t, 1, "secret-host", level, locked, listOf("host", "counts"), 3, 1)
            assertEquals(shown.main, shown.public)
            val words = listOfNotNull(shown.main.title, shown.main.text).joinToString(" ")
            assertFalse(words.contains("old boilerplate"))
            if (level == LockDetail.Full) {
                assertTrue(words.contains("secret-host"))
                assertTrue(words.contains("3 agents"))
            } else {
                assertFalse(words.contains("secret-host"))
                assertFalse(words.contains("3 agents"))
            }
        }
        val hidden = attachedContent(t, 1, "secret-host", LockDetail.Full, false, emptyList(), 3, 1)
        assertNull(hidden.main.text)
        assertFalse(hidden.main.title.contains("secret-host"))
        val several = attachedContent(t, 3, "secret-host", LockDetail.Full, false, listOf("host", "counts"), 4, 1)
        assertFalse(several.main.title.contains("secret-host"))
        assertTrue(several.main.text!!.contains("4 agents"))
    }

    @Test
    fun anAttachedForegroundCardNeverClaimsAnUnconnectedLinkIsConnected() {
        val connected = io.github.erikfrish.herdline.core.Connection.State.Connected("0.9.5")
        val cases = listOf(
            listOf(connected) to AttachedState.Connected,
            listOf(io.github.erikfrish.herdline.core.Connection.State.Connecting) to AttachedState.Connecting,
            listOf(connected, io.github.erikfrish.herdline.core.Connection.State.SettingUp) to AttachedState.Connecting,
            listOf(connected, io.github.erikfrish.herdline.core.Connection.State.Disconnected("lost")) to AttachedState.Disconnected,
            listOf(io.github.erikfrish.herdline.core.Connection.State.AuthRejected(0, "host")) to AttachedState.Disconnected,
            emptyList<io.github.erikfrish.herdline.core.Connection.State>() to AttachedState.Disconnected,
        )
        for ((states, expected) in cases) {
            val actual = attachedState(states)
            assertEquals(expected, actual)
            val title = actual.name
            val card = attachedContent(t, 1, "host", LockDetail.Full, false, listOf("counts"), 3, 1, actual, title)
            assertEquals(title, card.main.title)
            if (actual != AttachedState.Connected) assertNull(card.main.text)
            assertEquals(card.main, card.public)
        }
    }
}
