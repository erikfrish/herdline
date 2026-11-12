package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptRefusal
import io.github.erikfrish.herdline.core.TranscriptRefused
import io.github.erikfrish.herdline.ui.session.OUTAGE_SHOW_AFTER_MS
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadStateTest {
    @Test
    fun every_typed_refusal_is_lasting_and_every_other_failure_transient() {
        val lasting = listOf(
            TranscriptRefusal.OutsideHome,
            TranscriptRefusal.UnsupportedFormat("kilo_store_outside_home"),
            TranscriptRefusal.UnsupportedFormat("managed_session_journal"),
            TranscriptRefusal.UnsupportedVersion("hermes", 1, 2),
        )
        for (refusal in lasting) assertEquals(refusal, lastingRefusal(TranscriptRefused(refusal)))
        // An untyped core error, such as an unconfined page path, may end with the next poll.
        assertNull(lastingRefusal(IOException("the session file is outside OMP's session folder")))
        assertNull(lastingRefusal(SocketTimeoutException()))
    }

    @Test
    fun a_refused_read_keeps_its_mark_through_a_reentry_until_a_good_read() {
        val read = ChatReadState()
        // Chat showed the history, then the read was refused and its strip came up.
        read.outage.value = read.outage.value.observe(live = true, now = 0).outage
        read.failed(TranscriptRefused(TranscriptRefusal.UnsupportedFormat("kilo_store_outside_home")))
        read.outage.value = read.outage.value.observe(live = false, now = 1_000).outage
        read.outage.value = read.outage.value.tick(1_000 + OUTAGE_SHOW_AFTER_MS)

        // Terminal, then Chat: the new composition goes on from the kept outage and polls again.
        read.failed(TranscriptRefused(TranscriptRefusal.UnsupportedFormat("kilo_store_outside_home")))
        read.outage.value = read.outage.value.observe(live = false, now = 9_000).outage
        assertTrue(read.outage.value.strip)
        assertEquals(1_000L, read.outage.value.since)
        assertEquals(TranscriptRefusal.UnsupportedFormat("kilo_store_outside_home"), read.refusal)

        read.succeeded()
        read.outage.value = read.outage.value.observe(live = true, now = 10_000).outage
        assertNull(read.refusal)
        assertNull(read.problem)
        assertNull(read.outage.value.since)
        assertFalse(read.outage.value.strip)
    }
}
