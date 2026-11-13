package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.Draft
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TC-RCPT: a receipt nothing watches proves only that Herdr took the input. */
class HandedReceiptTest {
    private val answeredAt = 1_000_000L

    @Test
    fun anUnwatchedReceiptIsHandedClearsTheEditorAndHoldsNothing() {
        val sent = answeredRecord("tc-rcpt-ok", "r1", submit = true, watched = false, historyFrom = 7, now = answeredAt)
        assertEquals(SentState.Handed, sent.state)
        assertNull(sent.historyFrom)
        assertEquals("", editorAfterAnswer("tc-rcpt-ok", "tc-rcpt-ok"))
        // Typed while the answer was on its way: kept.
        assertEquals("tc-rcpt-ok more", editorAfterAnswer("tc-rcpt-ok more", "tc-rcpt-ok"))
        val after = Draft(text = "", sent = sent)
        assertFalse(after.unsettled)
        assertTrue(after.copy(text = "next").mayDeliver(Binding.Bound))
    }

    @Test
    fun aWatchedReceiptStillWaitsInPane() {
        val sent = answeredRecord("hi", "r1", submit = true, watched = true, historyFrom = 7, now = answeredAt)
        assertEquals(SentState.InPane, sent.state)
        assertEquals(7L, sent.historyFrom)
        assertTrue(Draft(text = "hi", sent = sent).unsettled)
    }

    @Test
    fun theNoticeNeverSaysSentOrInsertedForAHandedRecord() {
        val send = SentRecord("a", "r1", submit = true, SentState.Handed, answeredAt)
        assertEquals(R.string.composer_handed_send, sentNoticeText(send))
        assertEquals(R.string.composer_handed_insert, sentNoticeText(send.copy(submit = false)))
        // Only a proven delivery says Sent.
        assertEquals(R.string.composer_sent_to, sentNoticeText(send.copy(state = SentState.Confirmed)))
    }

    @Test
    fun theHandedWindowRunsSixtySecondsFromTheStoredAnswerAlsoAfterARestart() {
        val draft = Draft(text = "", sent = SentRecord("tc-rcpt-ok", "r1", submit = true, SentState.Handed, answeredAt))
        // A restart reads the record back from disk with its answer time.
        val back = Draft.fromJson(JSONObject(draft.toJson().toString()))
        assertEquals(draft.sent, back.sent)
        assertTrue(sentNoticeShown(back.sent, "", answeredAt + 20_000))
        assertTrue(sentNoticeShown(back.sent, "", answeredAt + 59_999))
        assertFalse(sentNoticeShown(back.sent, "", answeredAt + 60_000))
        // A proven Chat send keeps its 8 s.
        val confirmed = back.sent!!.copy(state = SentState.Confirmed)
        assertTrue(sentNoticeShown(confirmed, "", answeredAt + 7_999))
        assertFalse(sentNoticeShown(confirmed, "", answeredAt + 8_000))
    }

    @Test
    fun theNoticeEndsWithReuseTypingOrTheNextDelivery() {
        val a = SentRecord("A", "r1", submit = true, SentState.Handed, answeredAt)
        val now = answeredAt + 10_000
        assertTrue(sentNoticeShown(a, "", now))
        // Reuse puts the text back, and any typing fills the editor: the slot is free.
        assertFalse(sentNoticeShown(a, "A", now))
        assertFalse(sentNoticeShown(a, "x", now))
        // The next tap replaces the record, queued first, then answered with its own time.
        val queued = SentRecord("B", "", submit = true, SentState.Queued, now)
        assertFalse(sentNoticeShown(queued, "", now))
        val b = answeredRecord("B", "r2", submit = true, watched = false, historyFrom = null, now = now + 100)
        assertTrue(sentNoticeShown(b, "", answeredAt + 65_000))
        assertEquals("B", b.text)
        // Nothing unsettled ever shows it.
        for (state in listOf(SentState.InPane, SentState.Uncertain, SentState.Unlocked)) {
            assertFalse(sentNoticeShown(a.copy(state = state), "", now))
        }
    }
}
