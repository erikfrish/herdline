package io.github.erikfrish.herdline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class IngressTest {
    private val id = "0b5d3c1e-8f2a-4c6d-9e7b-1a2b3c4d5e6f"

    @Test
    fun onlyAnInstanceWithNoEndpointFallsBackToThePane() {
        assertEquals(IngressSend.Absent, ingressSendOutcome(id) { """{"endpoint":"absent"}""" })
        // An endpoint that is there and cannot be used is never a reason to type into the pane.
        val notFallback = mapOf(
            "unsupported OMP" to """{"endpoint":"unsupported","missing":["getQueuedMessages"]}""",
            "private dir open to others" to """{"endpoint":"unusable","reason":"dir"}""",
            "lookup timed out" to """{"endpoint":"unusable","reason":"discovery"}""",
            "sidecar of another version" to """{"endpoint":"unusable","reason":"version"}""",
            "stale socket" to """{"endpoint":"unusable","reason":"unreachable"}""",
        )
        for ((case, reply) in notFallback) {
            val outcome = ingressSendOutcome(id) { reply }
            assertTrue("$case: $outcome", outcome is IngressSend.Refused)
        }
        assertEquals(
            IngressSend.Refused(REASON_UNSUPPORTED, detail = "getQueuedMessages"),
            ingressSendOutcome(id) { """{"endpoint":"unsupported","missing":["getQueuedMessages"]}""" },
        )
        assertEquals(
            IngressSend.Refused(REASON_UNUSABLE, detail = "version"),
            ingressSendOutcome(id) { """{"endpoint":"unusable","reason":"version"}""" },
        )
    }

    @Test
    fun aSendWhoseFateIsUnknownIsUncertainNeverAbsentOrRefused() {
        val cases: Map<String, () -> String> = mapOf(
            "call threw" to { throw IOException("link closed") },
            "reply not JSON" to { "accepted" },
            "reply cut short" to { """{"endpoint":"live","outc""" },
            "extension uncertain" to { """{"endpoint":"live","id":"$id","outcome":"uncertain"}""" },
            "refused without a reason" to { """{"endpoint":"live","id":"$id","outcome":"refused"}""" },
            "unknown outcome" to { """{"endpoint":"live","id":"$id","outcome":"later"}""" },
            "unknown endpoint word" to { """{"endpoint":"maybe"}""" },
            "native reply without outcome" to { """{}""" },
        )
        for ((case, call) in cases) {
            assertEquals(case, IngressSend.Uncertain(id), ingressSendOutcome(id, call))
        }
    }

    @Test
    fun durableOutcomesAreAcceptedWithTheQueue() {
        val reply = """{"endpoint":"live","id":"$id","outcome":"accepted","state":{"idle":false,"queued":[
            {"requestId":"$id","text":"stop","queue":"steering"},
            {"requestId":null,"text":"from the desktop","queue":"followUp"}]}}"""
        assertEquals(
            IngressSend.Accepted(
                id,
                "accepted",
                IngressState(
                    idle = false,
                    queued = listOf(
                        IngressQueued(id, "stop", QUEUE_STEERING),
                        IngressQueued(null, "from the desktop", QUEUE_FOLLOW_UP),
                    ),
                ),
            ),
            ingressSendOutcome(id) { reply },
        )
        for (outcome in listOf("admitted", "replayed")) {
            val sent = ingressSendOutcome(id) { """{"endpoint":"live","id":"$id","outcome":"$outcome"}""" }
            assertEquals(outcome, IngressSend.Accepted(id, outcome, null), sent)
        }
    }

    @Test
    fun aRefusalCarriesItsReasonFromEitherSide() {
        assertEquals(
            IngressSend.Refused("dialog_open", IngressState(idle = false, queued = emptyList())),
            ingressSendOutcome(id) {
                """{"endpoint":"live","id":"$id","outcome":"refused","reason":"dialog_open","state":{"idle":false,"queued":[]}}"""
            },
        )
        // The native core's own refusal, before any endpoint was looked up.
        assertEquals(IngressSend.Refused("unsettled"), ingressSendOutcome(id) { """{"outcome":"refused","reason":"unsettled"}""" })
    }

    @Test
    fun aStatusCheckThatCannotAskLeavesTheRequestAsItWas() {
        // A check sends nothing: failing to check is not a refusal.
        for (reply in listOf("""{"endpoint":"unusable","reason":"stale"}""", """{"endpoint":"absent"}""", "nope")) {
            assertTrue(reply, ingressStatusOutcome(id) { reply }.isFailure)
        }
        assertTrue(ingressStatusOutcome(id) { throw IOException("gone") }.isFailure)
        assertEquals(
            IngressSend.Uncertain(id),
            ingressStatusOutcome(id) { """{"endpoint":"live","id":"$id","outcome":"uncertain"}""" }.getOrThrow(),
        )
        assertEquals(
            IngressSend.Accepted(id, "accepted", null),
            ingressStatusOutcome(id) { """{"endpoint":"live","id":"$id","outcome":"accepted"}""" }.getOrThrow(),
        )
    }

    @Test
    fun theProbeTellsNotInstalledFromUnsupportedFromBroken() {
        assertEquals(IngressProbe.Absent, ingressProbe { """{"endpoint":"absent"}""" }.getOrThrow())
        assertEquals(
            IngressProbe.Unsupported(listOf("withdrawQueuedMessage")),
            ingressProbe { """{"endpoint":"unsupported","missing":["withdrawQueuedMessage"]}""" }.getOrThrow(),
        )
        assertEquals(IngressProbe.Unusable("dir"), ingressProbe { """{"endpoint":"unusable","reason":"dir"}""" }.getOrThrow())
        assertEquals(
            IngressProbe.Live(IngressState(idle = true, queued = emptyList())),
            ingressProbe { """{"endpoint":"live","state":{"idle":true,"queued":[]}}""" }.getOrThrow(),
        )
        assertTrue(ingressProbe { """{"endpoint":"live"}""" }.isFailure)
    }

    @Test
    fun aWithdrawAnswerMustSayWhetherItWithdrew() {
        assertEquals(true, ingressWithdrawn { """{"endpoint":"live","id":"$id","withdrawn":true}""" }.getOrThrow())
        assertEquals(false, ingressWithdrawn { """{"endpoint":"live","id":"$id","withdrawn":false}""" }.getOrThrow())
        assertTrue(ingressWithdrawn { """{"endpoint":"live","id":"$id"}""" }.isFailure)
        assertTrue(ingressWithdrawn { """{"endpoint":"unusable","reason":"stale"}""" }.isFailure)
    }

    @Test
    fun theDialogSnapshotComesOnlyFromALiveEndpoint() {
        val dialogs = ingressDialogsOutcome {
            """{"endpoint":"live","dialogs":[{"dialogId":"d1","kind":"form","source":"ask","createdAt":5}]}"""
        }.getOrThrow()
        assertEquals("d1", dialogs.getJSONObject(0).getString("dialogId"))
        assertTrue(ingressDialogsOutcome { """{"endpoint":"absent"}""" }.isFailure)
        assertTrue(ingressDialogsOutcome { """{"endpoint":"live"}""" }.isFailure)
        assertTrue(ingressDialogsOutcome { throw IOException("gone") }.isFailure)
    }

    @Test
    fun aDialogSubmitIsOnlyWhatOmpSaidForThatDialog() {
        for (result in listOf("submitted", "not_open", "invalid", "kind_mismatch")) {
            assertEquals(
                result,
                ingressSubmitOutcome("d1") { """{"endpoint":"live","dialogId":"d1","result":"$result"}""" }.getOrThrow(),
            )
        }
        // Never taken for submitted: another dialog's answer, an unknown result, or no answer.
        assertTrue(ingressSubmitOutcome("d1") { """{"endpoint":"live","dialogId":"d2","result":"submitted"}""" }.isFailure)
        assertTrue(ingressSubmitOutcome("d1") { """{"endpoint":"live","dialogId":"d1","result":"approved"}""" }.isFailure)
        assertTrue(ingressSubmitOutcome("d1") { throw IOException("timeout") }.isFailure)
    }
}
