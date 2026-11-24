package io.github.erikfrish.herdline.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TranscriptProbeTest {
    private val probed = TranscriptIdentity(
        paneId = "w1:p1", terminalId = "t9", pid = 42,
        path = "/home/u/.omp/agent/sessions/-work/2026-11-24T10-00-00-000Z_0193a1b2-c3d4-7e5f-8a9b-0c1d2e3f4a5b.jsonl",
        source = TranscriptIdentity.PROBE_SOURCE, start = 123456,
    )

    private fun page(identity: JSONObject) = """{"identity":$identity,"status":"idle","reset":true,"size":10,"start":0,"end":10,"has_older":false,"entries":[]}"""

    private inline fun <reified T : Throwable> thrown(text: String): T = try {
        TranscriptPage.parse(text)
        fail("parsed $text")
        throw AssertionError()
    } catch (error: Throwable) {
        error as? T ?: throw error
    }

    @Test
    fun a_probed_page_keeps_its_start_and_is_read_only() {
        val parsed = TranscriptPage.parse(page(probed.toJson())).identity
        assertEquals(probed, parsed)
        assertTrue(parsed.readOnlyProbe)
        // Sent back as the next read's expect, unchanged.
        assertEquals(123456L, probed.toJson().getLong("start"))
    }

    @Test
    fun a_reused_pid_with_another_start_is_another_instance() {
        assertNotEquals(probed, probed.copy(start = 999999))
    }

    @Test
    fun a_hooked_identity_has_no_start_on_the_wire_and_is_not_read_only() {
        val hooked = probed.copy(source = "herdline-core:omp", start = null)
        assertFalse(hooked.toJson().has("start"))
        assertNull(TranscriptIdentity.parse(hooked.toJson()).start)
        assertNull(TranscriptIdentity.parse(hooked.toJson().put("start", JSONObject.NULL)).start)
        assertFalse(hooked.readOnlyProbe)
    }

    @Test
    fun every_unbound_reason_is_a_typed_refusal() {
        for (reason in UnboundReason.entries) {
            val error = thrown<TranscriptRefused>("""{"refused":{"kind":"unbound","reason":"${reason.wire}"}}""")
            assertEquals(TranscriptRefusal.Unbound(reason), error.refusal)
        }
        // A reason from a newer core proves nothing.
        assertEquals(
            TranscriptRefusal.Unbound(UnboundReason.Ambiguous),
            thrown<TranscriptRefused>("""{"refused":{"kind":"unbound","reason":"later"}}""").refusal,
        )
    }

    @Test
    fun a_detached_read_carries_no_page() {
        assertEquals(
            TranscriptDetachment.Refused(UnboundReason.LeaseNotHeld),
            thrown<TranscriptDetached>("""{"detached":{"kind":"refused","reason":"lease_not_held"}}""").detachment,
        )
        assertEquals(
            TranscriptDetachment.Rebound,
            thrown<TranscriptDetached>("""{"detached":{"kind":"rebound"}}""").detachment,
        )
    }
}
