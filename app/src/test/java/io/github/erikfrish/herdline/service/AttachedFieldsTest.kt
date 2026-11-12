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
    }
}
