package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRecheckTest {
    @Test
    fun a_tap_reads_a_transient_reason_once_and_only_says_a_typed_refusal() {
        assertEquals(ChatRecheck.Read, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = false, polling = false))
        // A hidden Chat that polls is no reason to leave the tap unanswered.
        assertEquals(ChatRecheck.Read, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = false, polling = true))
        assertEquals(ChatRecheck.Notice, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = true, polling = false))
    }

    @Test
    fun only_a_new_agent_reads_a_pane_no_chat_polls_and_never_a_refused_one() {
        assertEquals(ChatRecheck.Read, chatRecheck(tap = false, agentChanged = true, unreadable = true, lasting = false, polling = false))
        // A status tick is no change; a polling Chat reads on its own; a typed refusal never reads.
        assertEquals(ChatRecheck.Nothing, chatRecheck(tap = false, agentChanged = false, unreadable = true, lasting = false, polling = false))
        assertEquals(ChatRecheck.Nothing, chatRecheck(tap = false, agentChanged = true, unreadable = true, lasting = false, polling = true))
        assertEquals(ChatRecheck.Nothing, chatRecheck(tap = false, agentChanged = true, unreadable = true, lasting = true, polling = false))
        assertEquals(ChatRecheck.Nothing, chatRecheck(tap = false, agentChanged = true, unreadable = false, lasting = false, polling = false))
    }

    @Test
    fun a_good_recheck_switches_only_for_a_tap_still_pending_on_that_pane_and_tab() {
        val tap = PendingChatTap("w1:p2", "w1:t1")
        assertTrue(switchesToChat(tap, "w1:p2", "w1:t1"))
        assertFalse(switchesToChat(null, "w1:p2", "w1:t1"))
        assertFalse(switchesToChat(tap, "w1:p3", "w1:t1"))
        assertFalse(switchesToChat(tap, "w1:p2", "w1:t2"))
    }
}
