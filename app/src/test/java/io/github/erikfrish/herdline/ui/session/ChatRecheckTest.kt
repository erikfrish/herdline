package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatRecheckTest {
    @Test
    fun a_tap_reads_a_transient_reason_once() {
        assertEquals(ChatRecheck.Read, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = false, polling = false))
        // A hidden Chat that polls is no reason to leave the tap unanswered.
        assertEquals(ChatRecheck.Read, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = false, polling = true))
    }

    @Test
    fun m25_a_tap_with_a_lasting_refusal_reads() {
        // QA m25: the refusal's session dir was removed; a tap that only said the old
        // refusal never read again, so Chat stayed shut until a cold restart.
        assertEquals(ChatRecheck.Read, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = true, polling = false))
        assertEquals(ChatRecheck.Read, chatRecheck(tap = true, agentChanged = false, unreadable = true, lasting = true, polling = true))
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

    @Test
    fun a_read_that_comes_good_on_its_own_never_ends_the_fallback() {
        val tap = PendingChatTap("w1:p2", "w1:t1")
        // The refused session dir is gone and the hidden Chat reads: no tap, so Terminal stays.
        assertFalse(fallbackEnds(readable = true, pending = null, pane = "w1:p2", tab = "w1:t1"))
        assertFalse(fallbackEnds(readable = true, pending = tap, pane = "w1:p3", tab = "w1:t1"))
        assertFalse(fallbackEnds(readable = true, pending = tap, pane = "w1:p2", tab = "w1:t2"))
        assertFalse(fallbackEnds(readable = false, pending = tap, pane = "w1:p2", tab = "w1:t1"))
        assertTrue(fallbackEnds(readable = true, pending = tap, pane = "w1:p2", tab = "w1:t1"))
    }

    @Test
    fun a_failed_recheck_answers_its_own_tap_or_one_that_waited_on_it() {
        val tap = PendingChatTap("w1:p2", "w1:t1")
        // The tap's own re-check failed.
        assertTrue(tapAnswered(tap = true, pending = tap, pane = "w1:p2", tab = "w1:t1"))
        // An agent-change re-check was in flight when Chat was tapped: its failure is that tap's.
        assertTrue(tapAnswered(tap = false, pending = tap, pane = "w1:p2", tab = "w1:t1"))
        // No tap waiting: an agent-change re-check fails silently, as before.
        assertFalse(tapAnswered(tap = false, pending = null, pane = "w1:p2", tab = "w1:t1"))
        // A tap made on another pane or tab is not this re-check's.
        assertFalse(tapAnswered(tap = false, pending = tap, pane = "w1:p3", tab = "w1:t1"))
        assertFalse(tapAnswered(tap = false, pending = tap, pane = "w1:p2", tab = "w1:t2"))
    }

    @Test
    fun a_switch_away_ends_a_pending_tap_and_staying_keeps_it() {
        val tap = PendingChatTap("w1:p2", "w1:t1")
        assertEquals(tap, pendingAfterSwitch(tap, "w1:p2", "w1:t1"))
        assertEquals(null, pendingAfterSwitch(tap, "w1:p2", "w1:t2"))
        assertEquals(null, pendingAfterSwitch(tap, "w1:p3", "w1:t1"))
        // Away and back: gone, so a later good re-check there never switches for it.
        val back = pendingAfterSwitch(pendingAfterSwitch(tap, "w1:p2", "w1:t2"), "w1:p2", "w1:t1")
        assertFalse(switchesToChat(back, "w1:p2", "w1:t1"))
    }
}
