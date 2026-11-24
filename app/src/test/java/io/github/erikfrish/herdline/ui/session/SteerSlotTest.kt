package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ComposerCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun check(editor: String, steer: ComposerCheck.Gate = ComposerCheck.Gate(true, null)) = ComposerCheck(
        boot = "b",
        paneExists = true,
        visible = true,
        agent = "omp",
        status = null,
        program = "omp",
        editor = editor,
        insert = ComposerCheck.Gate(true, null),
        send = ComposerCheck.Gate(true, null),
        steer = steer,
    )
}
