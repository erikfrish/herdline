package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.ui.session.ChatRecheck
import io.github.erikfrish.herdline.ui.session.chatOutcomeApplies
import io.github.erikfrish.herdline.ui.session.chatRecheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ChatKeptTest {
    private val claude = TranscriptIdentity("w1:p2", "term_1", 42, "/h/.claude/projects/x/a.jsonl", agent = "claude", source = "herdr:claude")
    private val page = TranscriptPage(claude, reset = true, size = 100, start = 0, end = 100, entries = emptyList())

    @Test
    fun c9_a_slow_failed_read_never_lands_over_a_good_one_that_asked_later() = runBlocking {
        val kept = ChatKept()
        val inFlight = AtomicInteger()
        val most = AtomicInteger()
        val calls = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        // Runs on the IO dispatcher, as Connection.transcript does: each call says when it has started.
        val transcript = { _: JSONObject ->
            most.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            val answer = if (calls.incrementAndGet() == 1) {
                firstStarted.complete(Unit)
                runBlocking { releaseFirst.await() }
                Result.failure(IOException("the command on the host did not finish in time"))
            } else {
                secondStarted.complete(Unit)
                Result.success(page)
            }
            inFlight.decrementAndGet()
            answer
        }

        // The hidden Chat's poll is inside its read when the tap's re-check asks.
        val poll = async { kept.readNext(transcript, "w1:p2") }
        firstStarted.await()
        val tap = async { kept.recheck(transcript, "w1:p2") }
        // Unserialized, the re-check's read starts and comes good while the poll's hangs.
        // Serialized, it never starts before the poll's ends: these waits time out.
        if (withTimeoutOrNull(BASELINE_WAIT_MS) { secondStarted.await() } != null) withTimeoutOrNull(BASELINE_WAIT_MS) { tap.await() }
        // The poll's read now fails late, an untyped timeout.
        releaseFirst.complete(Unit)
        assertFalse(poll.await())
        assertEquals(true, tap.await())

        assertEquals(1, most.get())
        assertEquals(2, calls.get())
        assertNotNull(kept.history)
        assertNull(kept.read.problem)
        assertFalse(chatUnavailable(loaded = kept.history != null, failed = kept.read.problem != null, readHere = true))
    }

    @Test
    fun c9_an_old_boots_memory_neither_reports_on_nor_shows_a_reused_pane_id() = runBlocking {
        val memories = ChatMemories()
        val old = memories.at("boot-a")
        // Before the restart: claude in w1:p2 read good, then failed (its Chat's report is queued).
        assertTrue(old.of("w1:p2").readNext({ Result.success(page) }, "w1:p2"))
        assertTrue(old.cachedFor("w1:p2", "claude"))
        // A moment with no view keeps the memory; the restarted daemon is a new one.
        assertSame(old, memories.at(null))
        val now = memories.at("boot-b")
        assertNotSame(old, now)
        assertSame(now, memories.at("boot-b"))

        // The new boot reuses w1:p2 for another claude: no history of the old one shows on a tap.
        assertFalse(now.cachedFor("w1:p2", "claude"))
        assertEquals(ChatRecheck.Read, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = false, polling = true, cached = now.cachedFor("w1:p2", "claude")))
        // An outcome from the old boot's memory, failed or good, changes nothing on this boot's pane.
        assertFalse(old.of("w1:p2").readNext({ Result.failure(IOException("the pane is not an OMP agent with a session file")) }, "w1:p2"))
        assertFalse(chatOutcomeApplies(unreadable = true, owner = old, readPane = "w1:p2", current = memories.at("boot-b"), pane = "w1:p2", leaving = false))
        assertFalse(chatOutcomeApplies(unreadable = false, owner = old, readPane = "w1:p2", current = memories.at("boot-b"), pane = "w1:p2", leaving = false))
        // This boot's own failure, with nothing read yet, still falls back; once it reads good, a queued failure is stale.
        assertFalse(now.of("w1:p2").readNext({ Result.failure(IOException("no transcript yet")) }, "w1:p2"))
        assertTrue(chatOutcomeApplies(unreadable = true, owner = now, readPane = "w1:p2", current = memories.at("boot-b"), pane = "w1:p2", leaving = false))
        assertTrue(now.of("w1:p2").readNext({ Result.success(page) }, "w1:p2"))
        assertFalse(chatOutcomeApplies(unreadable = true, owner = now, readPane = "w1:p2", current = memories.at("boot-b"), pane = "w1:p2", leaving = false))
        assertEquals(ChatRecheck.Show, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = false, polling = true, cached = now.cachedFor("w1:p2", "claude")))
        // History of another kind in the pane is no cache for the agent there now.
        assertFalse(now.cachedFor("w1:p2", "codex"))
    }

    @Test
    fun c9_a_failure_kept_from_an_earlier_chat_is_no_report_until_this_chat_reads() = runBlocking {
        val kept = ChatKept()
        assertFalse(kept.readNext({ Result.failure(IOException("the pane is not an OMP agent with a session file")) }, "w1:p2"))
        // Chat opened on the pane again: nothing loaded and the failure kept, but not read here yet.
        assertFalse(chatUnavailable(loaded = false, failed = kept.read.problem != null, readHere = false))
        // Its own read fails too: now it says so.
        assertTrue(chatUnavailable(loaded = false, failed = kept.read.problem != null, readHere = true))
    }

    private companion object {
        const val BASELINE_WAIT_MS = 2_000L
    }
}
