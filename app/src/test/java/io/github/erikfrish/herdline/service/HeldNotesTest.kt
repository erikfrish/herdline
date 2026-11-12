package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words a done note carries through a disconnect (lab.7.3 J1, J1b), with
 * the shade modelled by [Posted] as AgentNotifications uses it: a re-post is
 * sent only when its words change, and once notes are held every re-post,
 * in any order, has the locked words.
 */
class HeldNotesTest {
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

    private val host = "qa-alpha"
    private val task = "SLEEP: reply with the word KILO"
    private val tag = "agent\nnode\nw1:p4"

    /** The phone in hand, unlocked, unless the notes are [held]. */
    private fun locked(held: Boolean) = LockGate.lockedNow(held = held, present = true, interactive = true, keyguard = false)

    private fun done(lock: LockDetail, held: Boolean, stale: String? = null) =
        agentContent(t, false, host, "alice2", "omp finished", task, stale, lock, locked(held))

    private fun Redacted.words() = listOfNotNull(main.title, main.text, main.subText, main.big, public.title, public.text, public.subText)

    @Test
    fun hiddenNoteHeldAtDisconnectKeepsTheLockedWordsThroughTheLinkDown() {
        val shade = Posted<Redacted>()
        val posted = done(LockDetail.Hidden, held = false)
        assertTrue(shade.put(tag, posted))
        assertTrue(posted.words().any { it.contains(task) })

        // Disconnect holds before the link closes: the re-post is the locked words.
        val heldWords = done(LockDetail.Hidden, held = true)
        assertTrue(shade.put(tag, heldWords))
        assertEquals(NoteContent("An agent finished"), heldWords.main)
        assertEquals(heldWords.main, heldWords.public)

        // The link-down re-post and the service's own hold come after, in either order.
        val linkDown = done(LockDetail.Hidden, held = true, stale = "Not connected · as of 14:51")
        assertEquals(heldWords, linkDown)
        assertFalse(shade.put(tag, linkDown))
        assertFalse(shade.put(tag, done(LockDetail.Hidden, held = true)))
        for (w in shade[tag]!!.words()) {
            for (secret in listOf(host, "alice2", task, "Not connected")) assertFalse("\"$w\" contains \"$secret\"", w.contains(secret))
        }
    }

    @Test
    fun fullNoteHeldThenSwitchedToHiddenTakesTheHiddenWords() {
        val shade = Posted<Redacted>()
        assertTrue(shade.put(tag, done(LockDetail.Full, held = false)))
        // Under Full the locked words are the full words: holding changes nothing.
        assertFalse(shade.put(tag, done(LockDetail.Full, held = true, stale = null)))

        // No service runs now; the setting change re-words the held note.
        val hidden = done(LockDetail.Hidden, held = true)
        assertTrue(shade.put(tag, hidden))
        assertEquals(NoteContent("An agent finished"), hidden.main)
        assertEquals(hidden.main, hidden.public)
    }

    @Test
    fun aCancelledNoteIsPostedAgainWithTheSameWords() {
        val shade = Posted<Redacted>()
        val words = done(LockDetail.Hidden, held = true)
        assertTrue(shade.put(tag, words))
        shade.forget(tag)
        assertTrue(shade.put(tag, words))
    }
}
