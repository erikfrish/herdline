package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptPage
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

        // The pane's Chat poll is inside its read when the harness card's one read asks.
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
    fun c9_an_old_boots_memory_never_shows_on_a_reused_pane_id() = runBlocking {
        val memories = ChatMemories()
        val old = memories.at("boot-a")
        assertTrue(old.of("w1:p2").readNext({ Result.success(page) }, "w1:p2"))
        // A moment with no view keeps the memory; the restarted daemon is a new one.
        assertSame(old, memories.at(null))
        val now = memories.at("boot-b")
        assertNotSame(old, now)
        assertSame(now, memories.at("boot-b"))
        // The new boot reuses w1:p2: nothing the old boot read shows there, and the old memory's late failure lands in it alone.
        assertFalse(now.holds("w1:p2"))
        assertFalse(old.of("w1:p2").readNext({ Result.failure(IOException("the pane is not an OMP agent with a session file")) }, "w1:p2"))
        assertNull(now.of("w1:p2").read.problem)
        assertTrue(old.holds("w1:p2"))
    }

    @Test
    fun c9_failure_then_success_remain_pane_local() = runBlocking {
        val memory = ChatMemories().at("b1")
        val pKPage = TranscriptPage(claude.copy(paneId = "w4:pK", terminalId = "term_2", path = "/h/.claude/projects/x/k.jsonl"), reset = true, size = 10, start = 0, end = 10, entries = emptyList())
        assertTrue(memory.of("w4:pJ").readNext({ Result.success(page) }, "w4:pJ"))
        val pJHistory = memory.of("w4:pJ").history
        // NoTranscript, for a fresh session or an unbound agent, arrives untyped: a read error that keeps trying, never a refusal.
        val pK = memory.of("w4:pK")
        assertFalse(pK.readNext({ Result.failure(IOException("the pane is not an OMP agent with a session file")) }, "w4:pK"))
        assertTrue(chatUnavailable(loaded = pK.history != null, failed = pK.read.problem != null, readHere = true))
        assertNull(pK.read.refusal)
        assertNull(memory.of("w4:pJ").read.problem)
        // The next read of pK (the poll, or Retry waking it) succeeds: its history is pK's own page.
        assertTrue(pK.readNext({ Result.success(pKPage) }, "w4:pK"))
        assertEquals(pKPage.identity, pK.history?.identity)
        assertNull(pK.read.problem)
        // pJ keeps exactly what it read: neither pK's failure nor its page reached it.
        assertSame(pJHistory, memory.of("w4:pJ").history)
        assertEquals(claude, memory.of("w4:pJ").history?.identity)
        assertNull(memory.of("w4:pJ").read.problem)
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
