package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Harness
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
            assertTrue(agent, agentNamed(agent, Harness.endpointAgents))
            assertFalse(agent, agentNamed(agent, Harness.queueAgents))
        }
        // OpenCode's inbox is its running server's own; only it queues on Send.
        assertTrue(agentNamed("OpenCode", Harness.endpointAgents))
        assertTrue(agentNamed("OpenCode", Harness.queueAgents))
        // Codex's queue is its running app-server daemon's own.
        assertTrue(agentNamed("CODEX", Harness.endpointAgents))
        assertFalse(agentNamed("codex", Harness.queueAgents))
        assertFalse(agentNamed("claude", Harness.endpointAgents))
        assertFalse(agentNamed(null, Harness.endpointAgents))
        assertFalse(agentNamed(null, Harness.queueAgents))
    }

    @Test
    fun sendSaysQueueOnlyWhileAQueueingRuntimeIsReadWorking() {
        val opencode = instance.copy(agent = "opencode")
        val working = IngressProbe.Live(IngressState(idle = false, queued = emptyList()))
        val idle = IngressProbe.Live(IngressState(idle = true, queued = emptyList()))
        assertTrue(sendQueues(reading = true, instance = opencode, probe = working))
        // Idle starts a turn: Send stays Send.
        assertFalse(sendQueues(reading = true, instance = opencode, probe = idle))
        // No live read, no verb change.
        assertFalse(sendQueues(reading = true, instance = opencode, probe = null))
        assertFalse(sendQueues(reading = true, instance = opencode, probe = IngressProbe.Unusable("needs_server")))
        assertFalse(sendQueues(reading = false, instance = opencode, probe = working))
        // A runtime without a queue verb never shows it.
        assertFalse(sendQueues(reading = true, instance = instance.copy(agent = "codex"), probe = working))
        assertFalse(sendQueues(reading = true, instance = instance, probe = working))
    }

    @Test
    fun aMissingOrUntrustedServerIsRefusedInItsOwnWords() {
        assertEquals("needs_server", ingressRefusalCode(IngressSend.Refused(REASON_UNUSABLE, detail = "needs_server")))
        assertEquals("server_discovery", ingressRefusalCode(IngressSend.Refused(REASON_UNUSABLE, detail = "server_discovery")))
        assertEquals(REASON_UNUSABLE, ingressRefusalCode(IngressSend.Refused(REASON_UNUSABLE, detail = "stale")))
        assertEquals(REASON_UNSUPPORTED, ingressRefusalCode(IngressSend.Refused(REASON_UNSUPPORTED, detail = "needs_server")))
        assertEquals(R.string.reason_needs_server, reasonRes("needs_server"))
        assertEquals(R.string.ingress_needs_server, unusableRes("needs_server"))
        assertEquals(R.string.ingress_server_discovery, unusableRes("server_discovery"))
        assertEquals(R.string.ingress_unusable, unusableRes("stale"))
    }

    @Test
    fun aRefusedSendOnAPaneWithoutItsServerGivesTheServersReason() {
        // opencode --standalone: the box check refuses the PTY route; the endpoint says why Chat can't send.
        assertEquals("needs_server", sendPrecheckCode("unsupported_program", IngressProbe.Unusable("needs_server")))
        assertEquals("server_discovery", sendPrecheckCode("not_visible", IngressProbe.Unusable("server_discovery")))
        // Any other probe leaves the box check's own code; a Send the gate allows stays allowed.
        assertEquals("unsupported_program", sendPrecheckCode("unsupported_program", IngressProbe.Unusable("stale")))
        assertEquals("unsupported_program", sendPrecheckCode("unsupported_program", null))
        assertEquals("agent_working", sendPrecheckCode("agent_working", IngressProbe.Absent))
        assertNull(sendPrecheckCode(null, IngressProbe.Unusable("needs_server")))
    }

    /**
     * The endpoint's words Chat shows above the field: the queue panel's status line, then the
     * composer's notice of a refused Send's code [refusal] or Send's [precheck]. [slot] is the cause
     * the notice says, null under a notice of something else (offline, a pending send, …).
     */
    private fun shown(probe: IngressProbe, refusal: String? = null, precheck: String? = null, slot: String? = refusal ?: precheck): List<Int> {
        val panel = queuePanel(probe)
        return listOfNotNull(
            (besideNotice(panel, noticeCause(refusal, precheck, slot)) as? QueuePanel.Unusable)?.let { unusableRes(it.reason) },
            slot?.let { causeRes(it, panel) },
        )
    }

    @Test
    fun aSendRefusedForAMissingServerSaysItOnceInTheSendTimeWords() {
        // QA m123 (Kilo, server killed): the panel's status and the refused Send's notice stacked.
        for (cause in listOf("needs_server", "server_discovery")) {
            val probe = IngressProbe.Unusable(cause)
            val refused = ingressRefusalCode(IngressSend.Refused(REASON_UNUSABLE, detail = cause))
            assertEquals(cause, listOf(reasonRes(cause)), shown(probe, refusal = refused))
            // No notice of it: the panel's status stays as it was.
            assertEquals(cause, listOf(unusableRes(cause)), shown(probe))
            // A notice of something else covers the refusal: the panel's status still says it.
            assertEquals(cause, listOf(unusableRes(cause)), shown(probe, refusal = refused, slot = null))
        }
        // Another cause behind the panel keeps it: the refusal names something else.
        assertEquals(
            listOf(R.string.ingress_unusable, R.string.reason_needs_server),
            shown(IngressProbe.Unusable("stale"), refusal = "needs_server"),
        )
    }

    @Test
    fun sendsPrecheckForAMissingServerSaysItOnceWithNoSend() {
        // OpenCode --standalone: the box check refuses the PTY route before any Send, and the precheck names the server.
        for (cause in listOf("needs_server", "server_discovery")) {
            val probe = IngressProbe.Unusable(cause)
            val precheck = sendPrecheckCode("unsupported_program", probe)
            assertEquals(cause, listOf(reasonRes(cause)), shown(probe, precheck = precheck))
        }
    }

    @Test
    fun aSendRefusedForAnyOtherUnusableEndpointSaysItOnceNamingTheFailedCheck() {
        val refused = ingressRefusalCode(IngressSend.Refused(REASON_UNUSABLE, detail = "stale"))
        // The panel's words name the check ("stale"); Send's general ones do not.
        assertEquals(listOf(R.string.ingress_unusable), shown(IngressProbe.Unusable("stale"), refusal = refused))
        // A server cause behind the panel is not this one: both stay, each its own.
        assertEquals(
            listOf(R.string.ingress_needs_server, R.string.reason_endpoint_unusable),
            shown(IngressProbe.Unusable("needs_server"), refusal = refused),
        )
        // Without the panel's line, Send's own general words.
        assertEquals(listOf(R.string.reason_endpoint_unusable), shown(IngressProbe.Absent, refusal = refused))
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
    fun aWithdrawStartsOnlyWhenItsRequestCanComeBack() {
        assertTrue(withdrawRestorable("", busy = false))
        // Typed text or a send on its way: refused before the queue is touched.
        assertFalse(withdrawRestorable("draft", busy = false))
        assertFalse(withdrawRestorable("", busy = true))
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
