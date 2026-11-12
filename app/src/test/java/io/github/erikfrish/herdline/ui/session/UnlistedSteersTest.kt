package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.IngressProbe
import io.github.erikfrish.herdline.core.IngressQueued
import io.github.erikfrish.herdline.core.IngressState
import io.github.erikfrish.herdline.core.QUEUE_STEERING
import io.github.erikfrish.herdline.data.Draft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Kilo lists no waiting steer: Chat's queue is the app's own accepted steers, until the server says otherwise. */
class UnlistedSteersTest {
    private val working = IngressProbe.Live(IngressState(idle = false, queued = emptyList()))
    private val idle = IngressProbe.Live(IngressState(idle = true, queued = emptyList()))

    // Two steers the server took in ses_a, one in ses_b, and a plain send that never waited.
    private val sent = Draft()
        .queuedWith("r1", "first", emptyList()).waitingSteer("r1", "ses_a")
        .queuedWith("r2", "second", emptyList()).waitingSteer("r2", "ses_a")
        .queuedWith("r3", "other", emptyList()).waitingSteer("r3", "ses_b")
        .queuedWith("r4", "plain", emptyList())

    @Test
    fun aSessionAtWorkShowsOnlyItsOwnWaitingSteersInOrder() {
        assertEquals(
            QueuePanel.Queue(listOf(IngressQueued("r1", "first", QUEUE_STEERING), IngressQueued("r2", "second", QUEUE_STEERING))),
            unlistedQueuePanel(working, sent, "ses_a"),
        )
        assertEquals(QueuePanel.Hidden, unlistedQueuePanel(working, Draft().queuedWith("r4", "plain", emptyList()), "ses_a"))
    }

    @Test
    fun noReadOfTheSessionAtWorkShowsNoRecordedSteer() {
        assertEquals(QueuePanel.Hidden, unlistedQueuePanel(idle, sent, "ses_a"))
        assertEquals(QueuePanel.Hidden, unlistedQueuePanel(null, sent, "ses_a"))
        assertEquals(QueuePanel.Unusable("x"), unlistedQueuePanel(IngressProbe.Unusable("x"), sent, "ses_a"))
        assertEquals(QueuePanel.Hidden, unlistedQueuePanel(working, sent, null))
    }

    @Test
    fun anIdleReadEndsThatSessionsSteersOnlyAndKeepsTheirText() {
        val settled = queueSettled(idle, sent, unlisted = true, session = "ses_a", editor = "")!!
        assertEquals(QueuePanel.Hidden, unlistedQueuePanel(working, settled, "ses_a"))
        assertEquals(listOf("r3"), settled.waitingSteers("ses_b").map { it.first })
        // No withdraw was in doubt: the editor is untouched, and the records stay for a later withdraw.
        assertEquals("", settled.text)
        assertEquals("first", settled.withdrawn("r1", "").text)
        // At work, unread, or nothing waiting: nothing to save.
        assertNull(queueSettled(working, sent, unlisted = true, session = "ses_a", editor = ""))
        assertNull(queueSettled(null, sent, unlisted = true, session = "ses_a", editor = ""))
        assertNull(queueSettled(idle, settled, unlisted = true, session = "ses_a", editor = ""))
    }

    @Test
    fun aSendWhoseWithdrawGotNoAnswerComesBackWhenItsChipGoesWithoutARetry() {
        // Kilo: r1's withdraw had no answer; the session then reads idle. r1 returns after the typed text, r2 does not.
        val kilo = sent.afterWithdraw("r1", null, "")
        val back = queueSettled(idle, kilo, unlisted = true, session = "ses_a", editor = "typed")!!
        assertEquals("typed\n\nfirst", back.text)
        assertEquals(setOf("r2", "r3", "r4"), back.queued.keys)
        assertEquals(emptyList<Any>(), back.waitingSteers("ses_a"))
        // A listed queue (OMP): restored only once the entry leaves the list, never while it is still there.
        val omp = Draft().queuedWith("o1", "fix it", emptyList()).queuedWith("o2", "other", emptyList()).afterWithdraw("o1", null, "")
        val stillThere = IngressProbe.Live(IngressState(idle = false, queued = listOf(IngressQueued("o1", "fix it", QUEUE_STEERING))))
        assertNull(queueSettled(stillThere, omp, unlisted = false, session = null, editor = "typed"))
        val left = queueSettled(working, omp, unlisted = false, session = null, editor = "typed")!!
        assertEquals("typed\n\nfix it", left.text)
        assertEquals(setOf("o2"), left.queued.keys)
    }

    @Test
    fun aWithdrawAnswerEndsOnlyThatSteer() {
        // false: it already left the queue; true: it is taken back with its text.
        val gone = sent.steersLeft(listOf("r1"))
        assertEquals(listOf("r2"), gone.waitingSteers("ses_a").map { it.first })
        val back = sent.withdrawn("r2", "")
        assertEquals("second", back.text)
        assertEquals(listOf("r1"), back.waitingSteers("ses_a").map { it.first })
    }

    @Test
    fun aWaitingSteerOutlivesARestart() {
        val read = Draft.fromJson(JSONObject(sent.toJson().toString()))
        assertEquals(sent.queued, read.queued)
        assertEquals(listOf("r1", "r2"), read.waitingSteers("ses_a").map { it.first })
    }

    @Test
    fun aSteerNeverRecordedIsNeverMarkedWaiting() {
        assertEquals(Draft(), Draft().waitingSteer("r9", "ses_a"))
    }
}
