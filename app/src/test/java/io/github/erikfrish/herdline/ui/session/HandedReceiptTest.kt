package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AttachmentKind
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.Draft
import io.github.erikfrish.herdline.data.DraftAttachment
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A receipt nothing watches proves only that Herdr took the input. */
class HandedReceiptTest {
    private val answeredAt = 1_000_000L

    private fun file(id: String) = DraftAttachment(id, "content://$id", AttachmentKind.entries.first(), "$id.png", size = 1L)

    @Test
    fun anUnwatchedReceiptSettlesHandedClearsTheEditorAndDropsItsFiles() {
        val queued = Draft(
            text = "tc-rcpt-ok",
            sent = SentRecord("tc-rcpt-ok", "r1", submit = true, SentState.Queued, answeredAt - 100),
            attachments = listOf(file("a1"), file("a2")),
        )
        assertTrue(queued.unsettled)
        val after = answeredDraft(queued, "tc-rcpt-ok", "tc-rcpt-ok", "r1", submit = true, watched = false, historyFrom = 7, carried = setOf("a1"), now = answeredAt)
        assertEquals(SentState.Handed, after.sent?.state)
        assertNull(after.sent?.historyFrom)
        assertEquals("", after.text)
        assertEquals(listOf("a2"), after.attachments.map { it.id })
        assertFalse(after.unsettled)
        assertTrue(after.copy(text = "next").mayDeliver(Binding.Bound))
        assertEquals(HANDED_WINDOW, sentNoticeLeft(after.sent, answeredAt))
        // Typed while the answer was on its way: kept, cursor at its end.
        val typed = answeredDraft(queued, "tc-rcpt-ok more", "tc-rcpt-ok", "r1", submit = true, watched = false, historyFrom = null, carried = emptySet(), now = answeredAt)
        assertEquals("tc-rcpt-ok more", typed.text)
        assertEquals(typed.text.length, typed.selectionStart)
    }

    @Test
    fun aWatchedReceiptStillWaitsInPaneWithTheEditorAndFiles() {
        val queued = Draft(text = "hi", attachments = listOf(file("a1")))
        val after = answeredDraft(queued, "hi", "hi", "r1", submit = true, watched = true, historyFrom = 7, carried = setOf("a1"), now = answeredAt)
        assertEquals(SentState.InPane, after.sent?.state)
        assertEquals("hi", after.text)
        assertEquals(listOf("a1"), after.attachments.map { it.id })
        assertTrue(after.unsettled)
        assertNull(sentNoticeLeft(after.sent, answeredAt))
    }

    @Test
    fun aHandedNoticeNeverSaysSentAndOffersCheckFirst() {
        val insert = SentRecord("a", "r1", submit = false, SentState.Handed, answeredAt)
        assertEquals(R.string.composer_handed_insert, sentNoticeText(insert))
        assertTrue(sentNoticeChecks(insert))
        // Only a proven delivery says Sent, and it asks for no check.
        val confirmed = insert.copy(submit = true, state = SentState.Confirmed)
        assertEquals(R.string.composer_sent_to, sentNoticeText(confirmed))
        assertFalse(sentNoticeChecks(confirmed))
    }

    @Test
    fun theHandedNoticeNamesThePaneAsAtTheTapAfterItClosesAndAReload() {
        val queued = Draft(text = "tc-rcpt-ok", sent = sentAfter(Connection.Delivery.Queued("r1"), null, "tc-rcpt-ok", submit = false, now = answeredAt - 100, to = "ws › tab"))
        val after = answeredDraft(queued, "tc-rcpt-ok", "tc-rcpt-ok", "r1", submit = false, watched = false, historyFrom = null, carried = emptySet(), now = answeredAt, to = queued.sent?.to)
        // The pane closed: the live title has fallen back to "Pane"; restart reads the draft back.
        val reloaded = Draft.fromJson(JSONObject(after.toJson().toString())).sent!!
        assertEquals(SentState.Handed, reloaded.state)
        assertEquals("ws › tab", sentNoticeName(reloaded, "Pane"))
        // A draft stored before the field existed reads null and names the live pane.
        val old = JSONObject(after.toJson().toString()).apply { getJSONObject("sent").remove("to") }
        val legacy = Draft.fromJson(old).sent!!
        assertNull(legacy.to)
        assertEquals("Pane", sentNoticeName(legacy, "Pane"))
    }

    @Test
    fun theHandedWindowRunsSixtySecondsFromTheStoredAnswerAlsoAfterARestart() {
        val draft = Draft(text = "", sent = SentRecord("tc-rcpt-ok", "r1", submit = true, SentState.Handed, answeredAt))
        // A restart reads the record back from disk with its answer time.
        val back = Draft.fromJson(JSONObject(draft.toJson().toString()))
        assertEquals(draft.sent, back.sent)
        assertEquals(40_000L, sentNoticeLeft(back.sent, answeredAt + 20_000))
        assertEquals(1L, sentNoticeLeft(back.sent, answeredAt + 59_999))
        assertEquals(0L, sentNoticeLeft(back.sent, answeredAt + 60_000))
        // A proven Chat send keeps its 8 s.
        val confirmed = back.sent!!.copy(state = SentState.Confirmed)
        assertEquals(1L, sentNoticeLeft(confirmed, answeredAt + 7_999))
        assertEquals(0L, sentNoticeLeft(confirmed, answeredAt + 8_000))
    }

    @Test
    fun theTimerEndsAtSixtySecondsEvenWhenTheWallClockStepsBack() = runBlocking {
        val sent = SentRecord("tc-rcpt-ok", "r1", submit = true, SentState.Handed, answeredAt)
        var wall = answeredAt
        var slept = 0L
        // The wall clock steps back an hour while the timer waits.
        awaitSentNoticeEnd(sent, clock = { wall }, sleep = { check(slept == 0L) { "re-armed" }; slept += it; wall -= 3_600_000L })
        assertEquals(HANDED_WINDOW, slept)
        // Read after a backward step past the answer (or a restart then):
        // the rest is one window from now, never until the clock gets back.
        assertEquals(HANDED_WINDOW, sentNoticeLeft(sent, answeredAt - 3_600_000L))
        slept = 0L
        awaitSentNoticeEnd(sent, clock = { answeredAt - 3_600_000L }, sleep = { slept += it })
        assertEquals(HANDED_WINDOW, slept)
        // Read after the window: no wait at all.
        slept = -1L
        awaitSentNoticeEnd(sent, clock = { answeredAt + 61_000 }, sleep = { slept = it })
        assertEquals(0L, slept)
    }

    @Test
    fun theNoticeEndsWithReuseTypingOrTheNextDelivery() {
        val a = SentRecord("A", "r1", submit = true, SentState.Handed, answeredAt)
        assertTrue(sentNoticeShown(a, "", live = true))
        assertFalse(sentNoticeShown(a, "", live = false))
        // Reuse puts the text back, and any typing fills the editor: the slot is free.
        assertFalse(sentNoticeShown(a, "A", live = true))
        assertFalse(sentNoticeShown(a, "x", live = true))
        // The next tap replaces the record, queued first: no notice, no window.
        val queued = SentRecord("B", "", submit = true, SentState.Queued, answeredAt + 10_000)
        assertFalse(sentNoticeShown(queued, "", live = true))
        assertNull(sentNoticeLeft(queued, answeredAt + 10_000))
        // Nothing unsettled ever shows it.
        for (state in listOf(SentState.InPane, SentState.Uncertain, SentState.Unlocked)) {
            assertFalse(sentNoticeShown(a.copy(state = state), "", live = true))
        }
    }

    private companion object {
        const val HANDED_WINDOW = 60_000L
    }
}
