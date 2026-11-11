package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.IngressProbe
import io.github.erikfrish.herdline.core.IngressQueued
import io.github.erikfrish.herdline.core.IngressSend
import io.github.erikfrish.herdline.core.IngressState
import io.github.erikfrish.herdline.core.QUEUE_FOLLOW_UP
import io.github.erikfrish.herdline.core.QUEUE_STEERING
import io.github.erikfrish.herdline.core.REASON_UNSUPPORTED
import io.github.erikfrish.herdline.core.REASON_UNUSABLE
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IngressRouteTest {
    private val instance = TranscriptIdentity("p1", "t1", 4242, "/home/u/.omp/sessions/a.jsonl")
    private val before = SentRecord("earlier", "r0", true, SentState.Confirmed, 1)

    @Test
    fun onlyChatSendAndSteerToAKnownInstanceAskTheEndpoint() {
        assertTrue(endpointRoute(reading = true, instance = instance, submit = true))
        // Insert types into the box without Enter: only the pane can.
        assertFalse(endpointRoute(reading = true, instance = instance, submit = false))
        assertFalse(endpointRoute(reading = false, instance = instance, submit = true))
        assertFalse(endpointRoute(reading = true, instance = null, submit = true))
    }

    @Test
    fun aProgramHerdrNamesInAnyCaseStillTakesItsEndpointRoutes() {
        // Herdr reports the program's own casing (its display name first):
        // a case-strict compare dropped the live endpoint and left Send off
        // after images were staged.
        for (agent in listOf("omp", "OMP", "Omp")) {
            assertTrue(agent, agentNamed(agent, ENDPOINT_AGENTS))
            assertFalse(agent, agentNamed(agent, API_AGENTS))
        }
        assertFalse("OpenCode sends prompts but exposes no ingress queue", agentNamed("OpenCode", ENDPOINT_AGENTS))
        assertTrue(agentNamed("OpenCode", API_AGENTS))
        assertTrue(agentNamed("CODEX", API_AGENTS))
        assertFalse(agentNamed("codex", ENDPOINT_AGENTS))
        assertFalse(agentNamed("claude", ENDPOINT_AGENTS))
        assertFalse(agentNamed(null, ENDPOINT_AGENTS))
        assertFalse(agentNamed(null, API_AGENTS))
    }

    @Test
    fun acceptedSettlesUncertainHoldsAndNothingSentGivesTheEarlierRecordBack() {
        val accepted = sentAfterIngress(IngressSend.Accepted("id-1", "accepted", null), before, "hi", 9)
        assertEquals(SentState.Confirmed, accepted?.state)
        assertEquals("id-1", ingressId(accepted))

        val uncertain = sentAfterIngress(IngressSend.Uncertain("id-2"), before, "hi", 9)
        assertEquals(SentState.Uncertain, uncertain?.state)
        // Check must be able to ask OMP for exactly this request.
        assertEquals("id-2", ingressId(uncertain))

        assertEquals(before, sentAfterIngress(IngressSend.Refused("dialog_open"), before, "hi", 9))
        assertEquals(before, sentAfterIngress(IngressSend.Absent, before, "hi", 9))
    }

    @Test
    fun aDeliveryTypedIntoThePaneHasNoEndpointId() {
        assertNull(ingressId(SentRecord("x", "receipt-7", true, SentState.Uncertain, 1)))
        assertNull(ingressId(SentRecord("x", "", true, SentState.Uncertain, 1)))
        assertNull(ingressId(SentRecord("x", INGRESS_RECEIPT, true, SentState.Uncertain, 1)))
    }

    @Test
    fun checkSettlesOnlyOnOmpsStoredRecord() {
        assertEquals(IngressCheck.Accepted, ingressChecked(Result.success(IngressSend.Accepted("i", "replayed", null))))
        assertEquals(IngressCheck.Refused("too_large"), ingressChecked(Result.success(IngressSend.Refused("too_large"))))
        // A pending record, no record, or no answer leave the draft held.
        assertEquals(IngressCheck.Unknown, ingressChecked(Result.success(IngressSend.Uncertain("i"))))
        assertEquals(IngressCheck.Unknown, ingressChecked(Result.failure(IOException("endpoint absent"))))
    }

    @Test
    fun aProbeForAnotherInstanceIsNotShown() {
        val live = IngressProbe.Live(IngressState(idle = true, queued = emptyList()))
        val read = ProbeRead(instance, live)
        assertEquals(live, probeFor(read, instance))
        assertNull(probeFor(read, instance.copy(pid = 4243)))
        assertNull(probeFor(read, null))
    }

    @Test
    fun theQueuePanelSaysUnsupportedApartFromUnusableAndHidesWhenNothingWaits() {
        assertEquals(QueuePanel.Hidden, queuePanel(null))
        assertEquals(QueuePanel.Hidden, queuePanel(IngressProbe.Absent))
        assertEquals(QueuePanel.Hidden, queuePanel(IngressProbe.Live(IngressState(idle = false, queued = emptyList()))))
        assertEquals(
            QueuePanel.Unsupported("queue, dialog"),
            queuePanel(IngressProbe.Unsupported(listOf("queue", "dialog"))),
        )
        assertEquals(QueuePanel.Unusable("socat"), queuePanel(IngressProbe.Unusable("socat")))
        val queued = listOf(IngressQueued("id-1", "fix it", QUEUE_STEERING), IngressQueued(null, "typed at the desk", QUEUE_FOLLOW_UP))
        assertEquals(QueuePanel.Queue(queued), queuePanel(IngressProbe.Live(IngressState(idle = false, queued = queued))))
    }

    @Test
    fun onlyThisAppsRequestsAreWithdrawnHereOneAtATime() {
        val ours = IngressQueued("id-1", "a", QUEUE_STEERING)
        assertTrue(withdrawEnabled(ours, withdrawing = null))
        assertFalse(withdrawEnabled(ours, withdrawing = "id-9"))
        assertFalse(withdrawEnabled(IngressQueued(null, "desktop", QUEUE_STEERING), withdrawing = null))
    }

    @Test
    fun endpointRefusalsSayWhatOmpRefusedNotTheNeutralWords() {
        for (code in listOf("dialog_open", "collab", "unsupported_type", "empty", "dropped", REASON_UNSUPPORTED, REASON_UNUSABLE)) {
            assertNotEquals(code, R.string.reason_unknown, reasonRes(code))
        }
        // Missing hooks and a broken endpoint are different words.
        assertNotEquals(reasonRes(REASON_UNSUPPORTED), reasonRes(REASON_UNUSABLE))
    }
}
