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
    fun aPromoteAnswerMustSayWhetherItSteered() {
        assertEquals(true, ingressPromoted { """{"endpoint":"live","id":"$id","promoted":true}""" }.getOrThrow())
        assertEquals(false, ingressPromoted { """{"endpoint":"live","id":"$id","promoted":false}""" }.getOrThrow())
        assertTrue(ingressPromoted { """{"endpoint":"live","id":"$id","promoted":"true"}""" }.isFailure)
        assertTrue(ingressPromoted { """{"endpoint":"unsupported","missing":["promote"]}""" }.isFailure)
    }

    @Test
    fun theDialogSnapshotComesOnlyFromALiveEndpoint() {
        val dialogs = ingressDialogsOutcome {
            """{"endpoint":"live","dialogs":[{"dialogId":"d1","kind":"form","source":"ask","createdAt":5}]}"""
        }.getOrThrow()
        assertEquals("d1", dialogs.dialogs.getJSONObject(0).getString("dialogId"))
        assertEquals(false, dialogs.more)
        // A cut snapshot says so: never shown as all that is open.
        assertEquals(true, ingressDialogsOutcome { """{"endpoint":"live","dialogs":[],"more":true}""" }.getOrThrow().more)
        assertTrue(ingressDialogsOutcome { """{"endpoint":"absent"}""" }.isFailure)
        assertTrue(ingressDialogsOutcome { """{"endpoint":"live"}""" }.isFailure)
        assertTrue(ingressDialogsOutcome { throw IOException("gone") }.isFailure)
    }

    @Test
    fun childrenComeOnlyFromALiveListOfIdsAndTitles() {
        val children = ingressChildrenOutcome { """{"endpoint":"live","children":[{"id":"ses_kid","title":"Explore"}]}""" }.getOrThrow()
        assertEquals(IngressChildren(listOf(IngressChild("ses_kid", "Explore")), more = false), children)
        // A cut list says so: never shown as the whole.
        assertTrue(ingressChildrenOutcome { """{"endpoint":"live","children":[],"more":true}""" }.getOrThrow().more)
        // Unsupported is no list, never an empty one read as no children.
        assertTrue(ingressChildrenOutcome { """{"endpoint":"unsupported","missing":["children"]}""" }.isFailure)
        assertTrue(ingressChildrenOutcome { """{"endpoint":"live","children":[{"id":"ses_kid"}]}""" }.isFailure)
        assertTrue(ingressChildrenOutcome { throw IOException("gone") }.isFailure)
    }

    @Test
    fun onlyTheCoresOwnWordSaysTheServerIsMissing() {
        assertEquals(true, ingressNeedsServerOutcome { """{"endpoint":"unusable","reason":"needs_server"}""" }.getOrThrow())
        assertEquals(false, ingressNeedsServerOutcome { """{"endpoint":"live","dialogs":[]}""" }.getOrThrow())
        // An untrusted server, another refusal, or no answer says nothing about the server either way.
        assertTrue(ingressNeedsServerOutcome { """{"endpoint":"unusable","reason":"server_discovery"}""" }.isFailure)
        assertTrue(ingressNeedsServerOutcome { """{"endpoint":"unsupported","missing":["dialogs"]}""" }.isFailure)
        assertTrue(ingressNeedsServerOutcome { throw IOException("gone") }.isFailure)
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

    @Test
    fun aDeclineIsOnlyTheServersWordForThatQuestionAndAnsweredElsewhereIsSaidApart() {
        assertEquals("cancelled", ingressCancelOutcome("frm_1") { """{"endpoint":"live","dialogId":"frm_1","result":"cancelled"}""" }.getOrThrow())
        assertEquals("not_open", ingressCancelOutcome("frm_1") { """{"endpoint":"live","dialogId":"frm_1","result":"not_open"}""" }.getOrThrow())
        val elsewhere = """{"endpoint":"live","dialogId":"frm_1","result":"not_open","settled":"answered"}"""
        assertEquals(DIALOG_ANSWERED_ELSEWHERE, ingressCancelOutcome("frm_1") { elsewhere }.getOrThrow())
        assertEquals(DIALOG_ANSWERED_ELSEWHERE, ingressSubmitOutcome("frm_1") { elsewhere }.getOrThrow())
        // A decline never reads as an answer, and only for its own question on a live endpoint.
        assertTrue(ingressCancelOutcome("frm_1") { """{"endpoint":"live","dialogId":"frm_1","result":"submitted"}""" }.isFailure)
        assertTrue(ingressCancelOutcome("frm_1") { """{"endpoint":"live","dialogId":"frm_2","result":"cancelled"}""" }.isFailure)
        assertTrue(ingressCancelOutcome("frm_1") { """{"endpoint":"unsupported","dialogId":"frm_1","result":"cancelled"}""" }.isFailure)
    }

    @Test
    fun aPtyStopThatPressedNoKeySaysWhyAndAnUnconfirmedOneNeverDoes() {
        val refused = ingressAbortOutcome { """{"route":"pty","outcome":"refused","reason":"not_visible"}""" }.exceptionOrNull()
        assertEquals("not_visible", (refused as StopRefused).reason)
        assertEquals(true, ingressAbortOutcome { """{"route":"pty","outcome":"stopped"}""" }.getOrThrow())
        // A key that may have gone, a refusal without its reason, a server's unknown answer: unconfirmed.
        for (reply in listOf(
            """{"route":"pty","outcome":"uncertain","reason":"unconfirmed"}""",
            """{"route":"pty","outcome":"refused"}""",
            """{"endpoint":"down","reason":"not_visible"}""",
            """{"refused":true,"reason":"boot_changed"}""",
        )) {
            val failure = ingressAbortOutcome { reply }.exceptionOrNull()
            assertTrue(reply, failure != null && failure !is StopRefused)
        }
    }
}
