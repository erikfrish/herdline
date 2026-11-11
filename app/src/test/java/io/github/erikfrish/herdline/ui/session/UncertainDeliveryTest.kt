package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.deliveryOutcome
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.Draft
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UncertainDeliveryTest {
    private val earlier = SentRecord("old", "r1", submit = true, SentState.Confirmed, at = 1)
    private val written = Draft(text = "fix the build", sent = earlier)

    /** The pane on the host: how often `composerDeliver` was called and how many pastes landed. */
    private class Pane {
        var calls = 0
        var landed = 0
    }

    /** One tap through the composer's gate, as Editor.deliver runs it; [reply] stands in for composerDeliver. */
    private fun tap(draft: Draft, pane: Pane, reply: (Pane) -> String): Draft {
        if (!draft.mayDeliver(Binding.Bound)) return draft
        val before = draft.sent
        // Recorded before the call, as Editor.deliver does.
        val queued = draft.copy(sent = SentRecord(draft.text, "", submit = true, SentState.Queued, at = 2))
        val outcome = deliveryOutcome {
            pane.calls++
            reply(pane)
        }
        return queued.copy(sent = sentAfter(outcome, before, draft.text, submit = true, now = 3))
    }

    @Test
    fun aSendThatMayHaveLandedIsHeldUntilUnlockAndNeverGoesTwice() {
        // deliveryOutcome reads no link state, so a link still up and one already lost read the same.
        val cases: Map<String, (Pane) -> String> = mapOf(
            "paste admitted, receipt request failed, link up" to { pane -> pane.landed++; throw IOException("receipt request failed") },
            "paste admitted, link lost before the reply" to { pane -> pane.landed++; throw IOException("link closed") },
            "failed before anything landed" to { _ -> throw IOException("send failed") },
            "reply not JSON" to { pane -> pane.landed++; "queued r9" },
            "reply cut short" to { pane -> pane.landed++; "{\"outcome\":\"que" },
            "unknown outcome" to { pane -> pane.landed++; "{\"outcome\":\"later\"}" },
            "native uncertain" to { pane -> pane.landed++; "{\"outcome\":\"uncertain\",\"reason\":\"unconfirmed\"}" },
            "queued without a receipt" to { pane -> pane.landed++; "{\"outcome\":\"queued\"}" },
            "refused without a reason" to { _ -> "{\"outcome\":\"refused\"}" },
        )
        for ((case, reply) in cases) {
            val pane = Pane()
            val held = tap(written, pane, reply)
            assertEquals(case, SentState.Uncertain, held.sent?.state)
            assertNotEquals("$case: the earlier Sent record came back", earlier, held.sent)
            assertEquals(case, "fix the build", held.text)
            assertFalse("$case: Send re-armed without Unlock", held.mayDeliver(Binding.Bound))

            // Tapped again, then after an app restart: nothing reaches the pane.
            val again = tap(held, pane, reply)
            val restarted = Draft.fromJson(again.toJson())
            assertEquals(case, SentState.Uncertain, restarted.sent?.state)
            tap(restarted, pane, reply)
            assertEquals(case, 1, pane.calls)
            assertTrue(case, pane.landed <= 1)

            // Only the user's Unlock lets the text go again.
            assertTrue(case, unlocked(restarted).mayDeliver(Binding.Bound))
        }
    }

    @Test
    fun theUnlockedWarningGoesWithTheFirstEditOrADismiss() {
        val pane = Pane()
        val released = unlocked(tap(written, pane) { p -> p.landed++; throw IOException("link closed") })
        assertTrue(unlockedNoticeShown(released))

        // A cursor move is not an edit; the warning also survives a restart.
        val moved = editedDraft(released, sending = false, released.text, 0, 0)!!
        assertTrue(unlockedNoticeShown(Draft.fromJson(moved.toJson())))

        // Editing or dismissing takes it in, for good, and leaves the record
        // Unlocked, so Move here still asks first.
        val edited = editedDraft(released, sending = false, "fix the build!", 14, 14)!!
        for (seen in listOf(edited, acknowledged(released))) {
            val restarted = Draft.fromJson(seen.toJson())
            assertFalse(unlockedNoticeShown(restarted))
            assertEquals(SentState.Unlocked, restarted.sent?.state)
        }

        // The next delivery's record is warned about afresh if it is unlocked too.
        val next = unlocked(tap(edited, pane) { p -> p.landed++; throw IOException("link closed") })
        assertTrue(unlockedNoticeShown(next))
    }

    @Test
    fun onlyTheNativeRefusalShowsNotSentAndGivesTheEarlierRecordBack() {
        val pane = Pane()
        val refused = tap(written, pane) { "{\"outcome\":\"refused\",\"reason\":\"agent_working\"}" }
        assertEquals(earlier, refused.sent)
        assertTrue(refused.mayDeliver(Binding.Bound))
        assertEquals(
            Connection.Delivery.Refused("agent_working"),
            deliveryOutcome { "{\"outcome\":\"refused\",\"reason\":\"agent_working\"}" },
        )
        assertEquals(Connection.Delivery.Queued("r9"), deliveryOutcome { "{\"outcome\":\"queued\",\"receipt\":\"r9\"}" })
    }

    @Test
    fun deliveryWaitsForItsWriteAheadAndAFailedOneSendsNothing() {
        val ahead = written.copy(sent = SentRecord(written.text, "", submit = true, SentState.Queued, at = 2))
        var calls = 0

        val failed = afterWriteAhead({ false }, Connection.Delivery.Refused(WRITE_AHEAD_FAILED)) { calls++; Connection.Delivery.Queued("r9") }
        assertEquals(0, calls)
        assertEquals(Connection.Delivery.Refused(WRITE_AHEAD_FAILED), failed)
        // Nothing left: the earlier record and Send come back.
        assertEquals(earlier, sentAfter(failed, earlier, written.text, submit = true, now = 3))

        // Stored, then the app is force-stopped while the call is out.
        var disk: String? = null
        afterWriteAhead({ disk = ahead.toJson().toString(); true }, Connection.Delivery.Refused(WRITE_AHEAD_FAILED)) {
            calls++
            val relaunched = Draft.fromJson(JSONObject(disk ?: "{}"))
            assertEquals(SentState.Uncertain, relaunched.sent?.state)
            assertFalse("Send re-armed after a relaunch", relaunched.mayDeliver(Binding.Bound))
            Connection.Delivery.Uncertain("unconfirmed")
        }
        assertEquals(1, calls)
    }
}
