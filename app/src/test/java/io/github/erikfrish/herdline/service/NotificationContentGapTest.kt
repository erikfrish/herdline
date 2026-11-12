package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Lock-screen rows NotificationContentTest leaves out, and the parent
 * contract swept across every branch: while locked, Hidden names no host,
 * node, tab, agent, task, status or link state, and Names never quotes the
 * task, in the main content or the publicVersion.
 */
class NotificationContentGapTest {
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
    private val tab = "payroll-tab"
    private val agentName = "bob"
    private val other = "alice"
    private val task = "fix the payroll bug"
    private val staleLine = "Not connected · as of 12:00"

    /** Words Hidden must never show while locked: who, where, what, and how it is doing. */
    private val hiddenSecrets = listOf(host, tab, agentName, other, task, "status:", "Not connected")

    private fun words(c: NoteContent?) = listOfNotNull(c?.title, c?.text, c?.subText, c?.big)

    private fun assertNone(label: String, r: Redacted, secrets: List<String>) {
        for (w in words(r.main) + words(r.public)) {
            for (s in secrets) assertFalse("$label: \"$w\" contains \"$s\"", w.contains(s))
        }
    }

    /** Every branch with every input that may carry a name, for [lock] while locked. */
    private fun everyBranchLocked(lock: LockDetail): Map<String, Redacted> = buildMap {
        put("attached one", attachedContent(t, 1, host, lock, locked = true))
        put("attached several", attachedContent(t, 3, host, lock, locked = true))
        put("lost", lostContent(t, host, lock))
        for (attention in listOf(true, false)) {
            for (stale in listOf(null, staleLine)) {
                put(
                    "agent attention=$attention stale=$stale",
                    agentContent(t, attention, host, agentName, "$task in $tab", "body: $task", stale, lock, locked = true),
                )
            }
            // No agent name: Names falls back to the node, never to the task.
            put("agent unnamed attention=$attention", agentContent(t, attention, host, null, task, null, null, lock, locked = true))
        }
        put("summary", summaryContent(t, host, lock, locked = true))
    }

    @Test
    fun hiddenWhileLockedNamesNothingOnAnyBranch() {
        everyBranchLocked(LockDetail.Hidden).forEach { (label, r) -> assertNone(label, r, hiddenSecrets) }
    }

    @Test
    fun hiddenPublicVersionsNameNothingWhileUnlockedEither() {
        val unlocked = mapOf(
            "attached" to attachedContent(t, 1, host, LockDetail.Hidden, locked = false),
            "agent done" to agentContent(t, false, host, agentName, task, "body: $task", staleLine, LockDetail.Hidden, locked = false),
            "summary" to summaryContent(t, host, LockDetail.Hidden, locked = false),
        )
        unlocked.forEach { (label, r) -> assertNone("$label public", Redacted(r.public, r.public), hiddenSecrets) }
    }

    @Test
    fun namesWhileLockedNeverQuotesTheTaskOnAnyBranch() {
        everyBranchLocked(LockDetail.NamesOnly).forEach { (label, r) -> assertNone(label, r, listOf(task, tab, "body:")) }
    }

    @Test
    fun agentDoneUnderEachSettingWhileLocked() {
        fun done(lock: LockDetail) = agentContent(t, false, host, agentName, task, "body: $task", null, lock, locked = true)

        val hidden = done(LockDetail.Hidden)
        assertEquals(NoteContent("An agent finished"), hidden.main)
        assertEquals(hidden.main, hidden.public)

        val names = done(LockDetail.NamesOnly)
        assertEquals(NoteContent("$agentName — status:Done", null, host), names.main)
        assertEquals(names.main, names.public)

        val full = done(LockDetail.Full)
        assertEquals(NoteContent(task, "body: $task", host, "body: $task"), full.main)
        assertEquals(full.main, full.public)
    }

    @Test
    fun summaryFullUnlockedNamesTheNodeInBothVersions() {
        val r = summaryContent(t, host, LockDetail.Full, locked = false)
        assertEquals(NoteContent(host), r.main)
        assertEquals(r.main, r.public)
    }

}
