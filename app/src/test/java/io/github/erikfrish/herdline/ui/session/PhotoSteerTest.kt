package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.IngressProbe
import io.github.erikfrish.herdline.core.IngressState
import io.github.erikfrish.herdline.core.TranscriptIdentity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoSteerTest {
    private val omp = TranscriptIdentity("p1", "t1", 42, "/home/u/.omp/agent/sessions/s.jsonl")
    private val working = IngressProbe.Live(IngressState(idle = false, queued = emptyList()))

    @Test
    fun imageSteeringUsesOnlyTheCurrentWorkingEndpoint() {
        assertTrue(endpointSteerReady(true, omp, working))
        assertFalse(endpointSteerReady(false, omp, working))
        assertFalse(endpointSteerReady(true, null, working))
        assertFalse(endpointSteerReady(true, omp.copy(agent = "pi"), working))
        assertTrue(endpointSteerReady(true, omp.copy(agent = "codex"), working))
        for (unusable in listOf(null, IngressProbe.Absent, IngressProbe.Unsupported(listOf("queue")), IngressProbe.Unusable("socket"), IngressProbe.Live(IngressState(true, emptyList())))) {
            assertFalse(endpointSteerReady(true, omp, unusable))
        }
        assertFalse(endpointSteerReady(true, omp, probeFor(ProbeRead(omp.copy(pid = 43), working), omp)))
    }
}
