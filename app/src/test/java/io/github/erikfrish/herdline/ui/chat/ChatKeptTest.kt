package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.ui.session.ChatRecheck
import io.github.erikfrish.herdline.ui.session.chatOutcomeApplies
import io.github.erikfrish.herdline.ui.session.chatRecheck
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class ChatKeptTest {
    private val claude = TranscriptIdentity("w1:p2", "term_1", 42, "/h/.claude/projects/x/a.jsonl", agent = "claude", source = "herdr:claude")
    private val page = TranscriptPage(claude, reset = true, size = 100, start = 0, end = 100, entries = emptyList())

    @Test
    fun c9_a_read_that_fails_after_a_good_one_and_a_report_queued_before_it_never_keep_chat_shut() = runBlocking {
        val kept = ChatKept()
        val requests = mutableListOf<JSONObject>()
        val inFlight = AtomicInteger()
        var most = 0
        val slow = CompletableDeferred<Result<TranscriptPage>>()
        val answers = ArrayDeque(listOf<suspend () -> Result<TranscriptPage>>({ slow.await() }, { Result.success(page) }))
        val transcript = { request: JSONObject ->
            requests += request
            most = maxOf(most, inFlight.incrementAndGet())
            val answer = answers.removeFirst()
            runBlocking { answer() }.also { inFlight.decrementAndGet() }
        }

        // The hidden Chat's poll asks first and hangs; the tap's re-check asks while it does.
        val poll = async { kept.readNext(transcript, "w1:p2") }
        yield()
        val tap = async { kept.recheck(transcript, "w1:p2") }
        yield()
        // A second ask finds the re-check in flight and never stacks a read.
        assertNull(kept.recheck(transcript, "w1:p2"))
        // The poll's read fails late, an untyped timeout; the re-check's read then comes good.
        slow.complete(Result.failure(IOException("the command on the host did not finish in time")))
        assertFalse(poll.await())
        assertEquals(true, tap.await())
        // One read at a time: the late failure landed first, so it never overwrites the good answer.
        assertEquals(1, most)
        assertEquals(2, requests.size)
        assertNotNull(kept.history)
        assertNull(kept.read.problem)
        assertFalse(chatUnavailable(loaded = kept.history != null, failed = kept.read.problem != null, readHere = true))

        // The poll's Chat had reported the failure before; that queued report changes nothing now.
        assertFalse(chatOutcomeApplies(unreadable = true, readPane = "w1:p2", readBoot = "b1", pane = "w1:p2", boot = "b1", leaving = false, cached = kept.history != null))
        // A Chat tap now shows the history held, without another read.
        assertEquals(ChatRecheck.Show, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = false, polling = true, cached = kept.history != null))
        assertEquals(2, requests.size)

        // A slow read of another pane, or from another daemon boot with a reused pane id, changes nothing here.
        assertFalse(chatOutcomeApplies(unreadable = false, readPane = "w1:p1", readBoot = "b1", pane = "w1:p2", boot = "b1", leaving = false, cached = false))
        assertFalse(chatOutcomeApplies(unreadable = false, readPane = "w1:p2", readBoot = "b0", pane = "w1:p2", boot = "b1", leaving = false, cached = false))
        // A pane with nothing held that still fails falls back, as before.
        assertTrue(chatOutcomeApplies(unreadable = true, readPane = "w1:p2", readBoot = "b1", pane = "w1:p2", boot = "b1", leaving = false, cached = false))
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
}
