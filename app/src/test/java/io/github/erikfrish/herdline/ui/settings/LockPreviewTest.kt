package io.github.erikfrish.herdline.ui.settings

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.service.NoteContent
import io.github.erikfrish.herdline.service.NoteTexts
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Settings preview shows a locked phone's card, not the unlocked one (lab.8.1 audit C1). */
class LockPreviewTest {
    private val t = object : NoteTexts {
        override fun attachedTo(host: String) = "Attached to $host"
        override fun attachedHosts(count: Int) = "Attached to $count node(s)"
        override fun attachedDetail() = "Agents keep running"
        override fun lostTitle(host: String) = "Lost connection to $host"
        override fun lostGeneric() = "A connection was lost"
        override fun lostText() = "Open Herdline to reconnect"
        override fun row(name: String, status: String) = "$name — $status"
        override fun status(status: AgentStatus) = "status:${status.name}"
        override fun agentAttention() = "An agent needs you"
        override fun agentDone() = "An agent finished"
        override fun agentsSummary() = "Agent notifications"
        override fun connected() = "Connected"
        override fun agentCounts(count: Int, needYou: Int) = "$count agents · $needYou need input"
    }
    private val body = "The agent's message appears here"

    @Test
    fun eachLevelShowsOnlyWhatTheLockedCardShows() {
        // Hidden names no host, agent, title or message.
        assertEquals(NoteContent("An agent finished"), lockPreview(t, LockDetail.Hidden, body))
        // Names: agent, status and host; never the title or message.
        assertEquals(NoteContent("agent — status:Done", null, "my-host"), lockPreview(t, LockDetail.NamesOnly, body))
        // Full: the title and the message.
        val full = lockPreview(t, LockDetail.Full, body)
        assertEquals("omp finished", full.title)
        assertEquals(body, full.text)
        assertEquals("my-host", full.subText)
    }
}
