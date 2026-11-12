package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * One test per row of the lab.7 lock-screen table (releases/1.0.1-lab.7/CHECKS.md):
 * branch × privacy setting × locked. Hidden must name no host, agent, task or
 * status while locked, in the main content or the publicVersion.
 */
class NotificationContentTest {
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

    private val host = "secret-host"
    private val agentName = "bob"
    private val task = "fix the payroll bug"

    private fun words(c: NoteContent?) = listOfNotNull(c?.title, c?.text, c?.subText, c?.big)

    private fun assertNone(r: Redacted, vararg secrets: String) {
        for (w in words(r.main) + words(r.public)) {
            for (s in secrets) assertFalse("\"$w\" contains \"$s\"", w.contains(s))
        }
    }

    private fun agent(lock: LockDetail, locked: Boolean) = agentContent(t, true, host, agentName, task, "body: $task", null, lock, locked)

    // Connection lost: not re-posted on the lock edge, so the same words locked or not.

    @Test
    fun lostHiddenNeverNamesTheHost() {
        val r = lostContent(t, host, LockDetail.Hidden)
        assertEquals(NoteContent("A connection was lost", "Open Herdline to reconnect"), r.main)
        assertEquals(NoteContent("A connection was lost"), r.public)
    }

    @Test
    fun lostNamesShowsTheHost() {
        val r = lostContent(t, host, LockDetail.NamesOnly)
        assertEquals("Lost connection to $host", r.main.title)
        assertEquals(NoteContent("Lost connection to $host"), r.public)
    }

    @Test
    fun lostFullShowsTheHostInBothVersions() {
        val r = lostContent(t, host, LockDetail.Full)
        assertEquals("Lost connection to $host", r.main.title)
        assertEquals(r.main, r.public)
    }

    // Agent notification.

    @Test
    fun agentHiddenLockedIsGeneric() {
        val r = agent(LockDetail.Hidden, locked = true)
        assertEquals(NoteContent("An agent needs you"), r.main)
        assertEquals(r.main, r.public)
        assertNone(r, host, agentName, task, "status:")
    }

    @Test
    fun agentHiddenUnlockedIsFullWithAGenericPublicVersion() {
        val r = agent(LockDetail.Hidden, locked = false)
        assertEquals(NoteContent(task, "body: $task", host, "body: $task"), r.main)
        assertEquals(NoteContent("An agent needs you"), r.public)
    }

    @Test
    fun agentHiddenLockedAndStaleSaysNothingOfTheLink() {
        val r = agentContent(t, false, host, agentName, task, "body", "Not connected · as of 12:00", LockDetail.Hidden, locked = true)
        assertEquals(NoteContent("An agent finished"), r.main)
        assertEquals(r.main, r.public)
    }

    @Test
    fun agentNamesLockedAndStaleKeepsTheLinkLine() {
        val r = agentContent(t, false, host, agentName, task, "body", "Not connected · as of 12:00", LockDetail.NamesOnly, locked = true)
        assertEquals(NoteContent("$agentName — status:Done", "Not connected · as of 12:00", host), r.main)
        assertNone(r, task)
    }

    @Test
    fun agentNamesLockedShowsWhoAndStatusButNoTask() {
        val r = agent(LockDetail.NamesOnly, locked = true)
        assertEquals(NoteContent("$agentName — status:Blocked", null, host), r.main)
        assertEquals(r.main, r.public)
        assertNone(r, task)
    }

    @Test
    fun agentNamesUnlocked() {
        val r = agent(LockDetail.NamesOnly, locked = false)
        assertEquals(task, r.main.title)
        assertEquals(NoteContent("$agentName — status:Blocked", null, host), r.public)
    }

    @Test
    fun agentFullLockedShowsTheTask() {
        val r = agent(LockDetail.Full, locked = true)
        assertEquals(NoteContent(task, "body: $task", host, "body: $task"), r.main)
        assertEquals(r.main, r.public)
    }

    @Test
    fun agentFullUnlocked() {
        assertEquals(agent(LockDetail.Full, locked = true).main, agent(LockDetail.Full, locked = false).public)
    }

    // Group summary.

    @Test
    fun summaryHiddenLockedIsGeneric() {
        val r = summaryContent(t, host, LockDetail.Hidden, locked = true)
        assertEquals(NoteContent("Agent notifications"), r.main)
        assertEquals(r.main, r.public)
    }

    @Test
    fun summaryHiddenUnlocked() {
        val r = summaryContent(t, host, LockDetail.Hidden, locked = false)
        assertEquals(NoteContent(host), r.main)
        assertEquals(NoteContent("Agent notifications"), r.public)
    }

    @Test
    fun summaryNamesShowsTheHost() {
        assertEquals(NoteContent(host), summaryContent(t, host, LockDetail.NamesOnly, locked = true).main)
        assertEquals(NoteContent(host), summaryContent(t, host, LockDetail.NamesOnly, locked = false).public)
    }

    @Test
    fun summaryFull() {
        val r = summaryContent(t, host, LockDetail.Full, locked = true)
        assertEquals(NoteContent(host), r.main)
        assertEquals(r.main, r.public)
    }
}
