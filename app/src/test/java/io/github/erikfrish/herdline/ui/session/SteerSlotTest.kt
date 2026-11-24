package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ComposerCheck
import io.github.erikfrish.herdline.core.IngressProbe
import io.github.erikfrish.herdline.core.IngressState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteerSlotTest {
    @Test
    fun `the slot follows the design table`() {
        // Not working: no route, no queue verb.
        assertEquals(SlotFace.Send, slotFace(steerRoute = false, queueVerb = false, notice = NoticeRole.None))
        // Working, queue only.
        assertEquals(SlotFace.Queue, slotFace(steerRoute = false, queueVerb = true, notice = NoticeRole.None))
        // Working, neither: the disabled Send with agent_working.
        assertEquals(SlotFace.Send, slotFace(steerRoute = false, queueVerb = false, notice = NoticeRole.Other))
        // Working, both: Steer in the slot, Queue in the working notice.
        assertEquals(SlotFace.Steer, slotFace(steerRoute = true, queueVerb = true, notice = NoticeRole.WorkingQueue))
        // Working, steer only: Steer, with or without the PTY working notice.
        assertEquals(SlotFace.Steer, slotFace(steerRoute = true, queueVerb = false, notice = NoticeRole.Working))
        assertEquals(SlotFace.Steer, slotFace(steerRoute = true, queueVerb = false, notice = NoticeRole.None))
    }

    @Test
    fun `an outranking notice takes Steer out of the slot`() {
        assertEquals(SlotFace.Queue, slotFace(steerRoute = true, queueVerb = true, notice = NoticeRole.Other))
        assertEquals(SlotFace.Send, slotFace(steerRoute = true, queueVerb = false, notice = NoticeRole.Other))
        // Without the Queue action shown, the queue route never yields Steer.
        assertEquals(SlotFace.Queue, slotFace(steerRoute = true, queueVerb = true, notice = NoticeRole.None))
        assertEquals(SlotFace.Queue, slotFace(steerRoute = true, queueVerb = true, notice = NoticeRole.Working))
    }

    @Test
    fun `Queue stays one visible tap away in every state`() {
        for (route in listOf(false, true)) for (queue in listOf(false, true)) for (role in NoticeRole.entries) {
            val face = slotFace(route, queue, role)
            assertTrue("route=$route queue=$queue role=$role face=$face", queueReachable(face, queue, role))
            if (face == SlotFace.Steer) assertTrue(route)
        }
    }

    @Test
    fun `a paste the pane never showed is not a draft in its box`() {
        assertEquals(SteerLeft.NotShown, steerLeft("steer_not_shown"))
        assertEquals(SteerLeft.Expired, steerLeft("steer_expired"))
        assertEquals(SteerLeft.Left, steerLeft("enter_refused"))
    }

    @Test
    fun `only a current read of text in the box confirms a remote draft`() {
        assertTrue(remoteDraftConfirmed(check(editor = "draft")))
        assertTrue(remoteDraftConfirmed(check(editor = "unrecognized", steer = ComposerCheck.Gate(false, REMOTE_DRAFT))))
        // A tap's stale refusal is no read: an empty or unread box now says nothing of a draft.
        assertFalse(remoteDraftConfirmed(check(editor = "empty")))
        assertFalse(remoteDraftConfirmed(check(editor = "unrecognized")))
        assertFalse(remoteDraftConfirmed(null))
    }

    @Test
    fun `a disabled slot of any face says why, in its own verb`() {
        val sendWords = setOf(R.string.composer_checking, R.string.composer_checking_resizing)
        for (face in SlotFace.entries) for (sending in listOf(false, true)) for (unsettled in listOf(false, true)) for (resizing in listOf(false, true)) {
            val state = "face=$face sending=$sending unsettled=$unsettled resizing=$resizing"
            val hold = silentSendHold(false, hasText = true, sending, unsettled, resizing)
            assertNotNull(state, hold)
            val words = holdRes(hold!!, face)
            if (face == SlotFace.Send) {
                assertTrue(state, words in sendWords || words == R.string.reason_unsettled)
            } else {
                assertFalse(state, words in sendWords)
            }
        }
    }

    @Test
    fun `an endpoint probe read before the turn holds the slot on Steer`() {
        // F1: Herdr reads the pane working; the endpoint was last read idle.
        val stale = IngressProbe.Live(IngressState(idle = true, queued = emptyList()))
        val held = endpointGates(stale, endpointSteer = false)
        assertFalse(held.sendEnabled)
        assertFalse(held.steerEnabled)
        assertEquals(Slot(SlotFace.Steer, enabled = false, words = R.string.composer_checking_steer), slot(held, chatCheck))

        // The current read answers: Steer goes, and nothing went before it.
        val fresh = IngressProbe.Live(IngressState(idle = false, queued = emptyList()))
        assertEquals(Slot(SlotFace.Steer, enabled = true, words = null), slot(endpointGates(fresh, endpointSteer = true), chatCheck))

        // It refuses: the slot is off, and the refusal is the reason shown.
        val refused = IngressProbe.Unusable("server_discovery")
        val gates = endpointGates(refused, endpointSteer = false)
        assertEquals(SlotFace.Send, slot(gates, chatCheck).face)
        assertFalse(gates.sendEnabled)
        assertEquals("server_discovery", composerSendGate(reading = true, refused, chatCheck.chatSend)?.reason)
    }

    @Test
    fun `a pane check made before the turn holds the slot on Steer`() {
        val idle = check(editor = "empty", status = AgentStatus.Idle, steer = ComposerCheck.Gate(false, "agent_idle"))
        assertEquals(Slot(SlotFace.Steer, enabled = false, words = R.string.composer_checking_steer), slot(paneGates(idle), idle))
        // A resize the steer gate names right now is said in Steer's words too.
        val resizing = check(editor = "empty", status = AgentStatus.Idle, steer = ComposerCheck.Gate(false, "resizing"))
        assertEquals(R.string.composer_checking_resizing_steer, slot(paneGates(resizing), resizing).words)

        val working = check(editor = "empty", status = AgentStatus.Working, send = ComposerCheck.Gate(false, "agent_working"))
        assertEquals(Slot(SlotFace.Steer, enabled = true, words = null), slot(paneGates(working), working))

        // Steer refused in the current state: Send's own refusal stands, nothing goes.
        val unread = working.copy(steer = ComposerCheck.Gate(false, "editor_unrecognized"))
        val gates = paneGates(unread)
        assertEquals(SlotFace.Send, slot(gates, unread).face)
        assertFalse(gates.sendEnabled || gates.steerEnabled)
        assertEquals("agent_working", composerSendGate(reading = false, null, unread.send)?.reason)
    }

    @Test
    fun `a held slot where nothing steers keeps Send's words and stays off`() {
        val idle = check(editor = "empty", status = AgentStatus.Idle)
        val gates = paneGates(idle, steers = false)
        assertEquals(Slot(SlotFace.Send, enabled = false, words = R.string.composer_checking), slot(gates, idle))
        // PTY steering is text-only: staged files keep the held slot on Send.
        assertEquals(SlotFace.Send, slot(paneGates(idle, staged = true), idle).face)
    }

    @Test
    fun `a read in the current state is not held`() {
        // Idle pane, idle check: Send goes as before.
        val idle = check(editor = "empty", status = AgentStatus.Idle)
        val gates = paneGates(idle, paneWorking = false)
        assertEquals(Slot(SlotFace.Send, enabled = true, words = null), slot(gates, idle))
    }

    private data class Slot(val face: SlotFace, val enabled: Boolean, val words: Int?)

    /** The slot as Composer builds it with no other notice shown, for a draft with text. */
    private fun slot(gates: SlotGates, check: ComposerCheck): Slot {
        val face = slotFace(gates.steerRoute, queueVerb = false, notice = NoticeRole.None)
        val enabled = if (face == SlotFace.Steer) gates.steerEnabled else gates.sendEnabled
        val gate = if (face == SlotFace.Steer) check.steer else check.send
        val resizing = gate.reason in setOf("resizing", "editor_resizing")
        val hold = silentSendHold(enabled, hasText = true, sending = false, unsettled = false, resizing = resizing)
        return Slot(face, enabled, hold?.let { holdRes(it, face) })
    }

    /** Chat on OMP's endpoint: the host check reads the turn, the phone draws no pane, so no PTY steer. */
    private val chatCheck = check(
        editor = "empty",
        status = AgentStatus.Working,
        send = ComposerCheck.Gate(false, "agent_working"),
        steer = ComposerCheck.Gate(false, "not_visible"),
    )

    private fun endpointGates(probe: IngressProbe, endpointSteer: Boolean) = slotGates(
        free = true,
        sendGate = composerSendGate(reading = true, probe, chatCheck.chatSend),
        check = chatCheck,
        paneWorking = true,
        endpoint = true,
        endpointSteer = endpointSteer,
        probe = probe,
        bound = true,
        staged = false,
        steers = true,
    )

    private fun paneGates(check: ComposerCheck, paneWorking: Boolean = true, steers: Boolean = true, staged: Boolean = false) = slotGates(
        free = true,
        sendGate = composerSendGate(reading = false, null, check.send),
        check = check,
        paneWorking = paneWorking,
        endpoint = false,
        endpointSteer = false,
        probe = null,
        bound = true,
        staged = staged,
        steers = steers,
    )

    private fun check(
        editor: String,
        steer: ComposerCheck.Gate = ComposerCheck.Gate(true, null),
        status: AgentStatus? = null,
        send: ComposerCheck.Gate = ComposerCheck.Gate(true, null),
    ) = ComposerCheck(
        boot = "b",
        paneExists = true,
        visible = true,
        agent = "omp",
        status = status,
        program = "omp",
        editor = editor,
        insert = ComposerCheck.Gate(true, null),
        send = send,
        steer = steer,
    )
}
