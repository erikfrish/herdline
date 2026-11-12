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
        val settled = steersSettled(idle, sent, "ses_a")!!
        assertEquals(QueuePanel.Hidden, unlistedQueuePanel(working, settled, "ses_a"))
        assertEquals(listOf("r3"), settled.waitingSteers("ses_b").map { it.first })
        // The records stay: a later withdraw still restores exactly what was sent.
        assertEquals("first", settled.withdrawn("r1", "").text)
        // At work, unread, or nothing waiting: nothing to save.
        assertNull(steersSettled(working, sent, "ses_a"))
        assertNull(steersSettled(null, sent, "ses_a"))
        assertNull(steersSettled(idle, settled, "ses_a"))
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
