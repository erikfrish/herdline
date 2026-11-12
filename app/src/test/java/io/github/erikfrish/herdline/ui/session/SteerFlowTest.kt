package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Connection.DeliveryUpdate
import io.github.erikfrish.herdline.core.Connection.SteerEnter
import io.github.erikfrish.herdline.core.Connection.SteerStart
import io.github.erikfrish.herdline.core.steerEnterOutcome
import io.github.erikfrish.herdline.core.steerStartOutcome
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.Draft
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.core.TranscriptSteer
import io.github.erikfrish.herdline.ui.chat.historyEnd
import io.github.erikfrish.herdline.ui.chat.ptyTurnLanded
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class SteerFlowTest {
    private val link = Any()
    private val epoch = SteerEpoch(link, "boot-1")

    /** Drives [events] from Idle, as the Editor would, counting the effects asked for. */
    private class Run(var phase: SteerPhase = SteerPhase.Idle) {
        val effects = mutableListOf<SteerEffect>()
        fun on(event: SteerEvent): Run {
            val step = phase.on(event)
            phase = step.phase
            if (step.effect != SteerEffect.None) effects += step.effect
            return this
        }
        val enters get() = effects.count { it == SteerEffect.AskEnter }
        val cancels get() = effects.count { it == SteerEffect.Cancel }
    }

    /** Tapped, staged as paste receipt R, and R confirmed at t=0: the first Enter is asked. */
    private fun pasted(): Run = Run()
        .on(SteerEvent.Tap(epoch))
        .on(SteerEvent.Started(SteerStart.Staged("R"), epoch))
        .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("R"), now = 0))

    @Test
    fun enterWaitsForThePasteReceiptThenGoesExactlyOnce() {
        val run = Run()
            .on(SteerEvent.Tap(epoch))
            .on(SteerEvent.Started(SteerStart.Staged("R"), epoch))
            .on(SteerEvent.Frame(10))
            .on(SteerEvent.Tick(20))
            // Another delivery's answer is not the paste's.
            .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("other"), 30))
        assertEquals("no Enter before the paste is confirmed", 0, run.enters)

        run.on(SteerEvent.Answer(DeliveryUpdate.Confirmed("R"), 40))
            // Frames while that call is out ask nothing more.
            .on(SteerEvent.Frame(50))
            .on(SteerEvent.Frame(60))
        assertEquals(1, run.enters)

        // The pane is still catching up: one more ask per later frame.
        run.on(SteerEvent.EnterAnswer(SteerEnter.Waiting("steer_not_shown"), 70))
            .on(SteerEvent.Frame(80))
            .on(SteerEvent.EnterAnswer(SteerEnter.Queued("E"), 90))
        assertEquals(2, run.enters)
        assertEquals(SteerPhase.Entered("E", epoch), run.phase)

        // Once Enter is queued nothing asks for another, even past the window.
        run.on(SteerEvent.Frame(100))
            .on(SteerEvent.Tick(10_000))
            .on(SteerEvent.EnterAnswer(SteerEnter.Queued("E2"), 110))
            .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("R"), 120))
            .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("E"), 130))
        assertEquals(2, run.enters)
        assertEquals(SteerPhase.Sent, run.phase)
        // A second tap is a new steer, never a replay of this one.
        assertEquals(SteerPhase.Staging(epoch), run.on(SteerEvent.Tap(epoch)).phase)
    }

    @Test
    fun aTapDuringASteerStartsNothing() {
        val run = pasted().on(SteerEvent.Tap(epoch))
        assertTrue(run.phase is SteerPhase.Entering)
    }

    @Test
    fun agentGoneIdleAtTheTapRefusesWithNothingPasted() {
        val run = Run()
            .on(SteerEvent.Tap(epoch))
            .on(SteerEvent.Started(SteerStart.Refused("agent_idle"), epoch))
            .on(SteerEvent.Frame(10))
        assertEquals(SteerPhase.Refused("agent_idle"), run.phase)
        assertEquals(emptyList<SteerEffect>(), run.effects)
    }

    @Test
    fun agentGoneIdleAtEnterEndsTheSteerWithoutEnter() {
        val run = pasted()
            .on(SteerEvent.EnterAnswer(SteerEnter.Refused("agent_idle"), 10))
            .on(SteerEvent.Frame(20))
            .on(SteerEvent.Tick(30))
        assertEquals(SteerPhase.Left("agent_idle"), run.phase)
        assertEquals(1, run.enters)
    }

    @Test
    fun aBlockedAskIsNeverAnsweredByTheSteer() {
        val atTap = Run()
            .on(SteerEvent.Tap(epoch))
            .on(SteerEvent.Started(SteerStart.Refused("agent_blocked"), epoch))
        assertEquals(SteerPhase.Refused("agent_blocked"), atTap.phase)
        assertEquals(0, atTap.enters)

        val atEnter = pasted()
            .on(SteerEvent.EnterAnswer(SteerEnter.Refused("agent_blocked"), 10))
            .on(SteerEvent.Frame(20))
        assertEquals(SteerPhase.Left("agent_blocked"), atEnter.phase)
        assertEquals(1, atEnter.enters)
    }

    @Test
    fun aPaneThatNeverShowsTheTextEndsTheSteerAfterTheWindow() {
        val run = pasted()
            .on(SteerEvent.EnterAnswer(SteerEnter.Waiting("steer_not_shown"), 10))
            // No frame comes: the tick still closes the window.
            .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS - 1))
        assertTrue(run.phase is SteerPhase.Entering)
        run.on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS))
            .on(SteerEvent.Frame(STEER_ENTER_WINDOW_MS + 10))
            .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS + 20))
        assertEquals(SteerPhase.Left("steer_not_shown"), run.phase)
        assertEquals(1, run.enters)
        assertEquals(1, run.cancels)
    }

    @Test
    fun aWaitingAnswerPastTheWindowCancelsInsteadOfAskingAgain() {
        val run = pasted().on(SteerEvent.EnterAnswer(SteerEnter.Waiting("unsettled"), STEER_ENTER_WINDOW_MS + 5))
        assertEquals(SteerPhase.Left("steer_not_shown"), run.phase)
        assertEquals(listOf(SteerEffect.AskEnter, SteerEffect.Cancel), run.effects)
    }

    @Test
    fun aReconnectOrDaemonRestartNeverLetsEnterGo() {
        for (next in listOf(SteerEpoch(Any(), "boot-1"), SteerEpoch(link, "boot-2"))) {
            // The paste's answer can no longer come.
            val pasting = Run()
                .on(SteerEvent.Tap(epoch))
                .on(SteerEvent.Started(SteerStart.Staged("R"), epoch))
                .on(SteerEvent.Epoch(next))
                .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("R"), 10))
                .on(SteerEvent.Frame(20))
            assertEquals("$next", SteerPhase.Uncertain, pasting.phase)
            assertEquals(0, pasting.enters)

            // Staged on the old link, returned on the new one.
            val staged = Run()
                .on(SteerEvent.Tap(epoch))
                .on(SteerEvent.Started(SteerStart.Staged("R"), next))
                .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("R"), 10))
            assertEquals(SteerPhase.Uncertain, staged.phase)
            assertEquals(0, staged.enters)

            // Confirmed, no call out: the steer expires without Enter.
            val idle = pasted()
                .on(SteerEvent.EnterAnswer(SteerEnter.Waiting("steer_not_shown"), 10))
                .on(SteerEvent.Epoch(next))
                .on(SteerEvent.Frame(20))
            assertEquals(SteerPhase.Left("steer_expired"), idle.phase)
            assertEquals(1, idle.enters)
            assertEquals(1, idle.cancels)

            // A call out across the change: whatever it queued is unconfirmed.
            val asking = pasted().on(SteerEvent.Epoch(next)).on(SteerEvent.EnterAnswer(SteerEnter.Queued("E"), 10))
            assertEquals(SteerPhase.Uncertain, asking.phase)
            val waited = pasted().on(SteerEvent.Epoch(next)).on(SteerEvent.EnterAnswer(SteerEnter.Waiting("unsettled"), 10))
                .on(SteerEvent.Frame(20))
            assertEquals(SteerPhase.Left("steer_expired"), waited.phase)
            assertEquals(1, waited.enters)

            // Enter queued, its answer lost with the link.
            val entered = pasted().on(SteerEvent.EnterAnswer(SteerEnter.Queued("E"), 10)).on(SteerEvent.Epoch(next))
            assertEquals(SteerPhase.Uncertain, entered.phase)
        }
    }

    @Test
    fun anUnansweredPasteIsUncertainAndNeverEntered() {
        val run = Run()
            .on(SteerEvent.Tap(epoch))
            .on(SteerEvent.Started(SteerStart.Staged("R"), epoch))
            .on(SteerEvent.Answer(DeliveryUpdate.Uncertain("R"), 10))
            .on(SteerEvent.Frame(20))
        assertEquals(SteerPhase.Uncertain, run.phase)
        assertEquals(0, run.enters)
    }

    @Test
    fun switchingChatAndTerminalMidSteerChangesNothing() {
        // The Composer stays composed across the toggle: the same link and
        // daemon run are seen again, and frames keep coming.
        val run = pasted()
            .on(SteerEvent.Epoch(SteerEpoch(link, "boot-1")))
            .on(SteerEvent.EnterAnswer(SteerEnter.Waiting("steer_not_shown"), 10))
            .on(SteerEvent.Epoch(SteerEpoch(link, "boot-1")))
            .on(SteerEvent.Frame(20))
            .on(SteerEvent.EnterAnswer(SteerEnter.Queued("E"), 30))
            .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("E"), 40))
        assertEquals(SteerPhase.Sent, run.phase)
        assertEquals(2, run.enters)
    }

    @Test
    fun aSteerWhoseEnterThePaneTookIsNeverSentOnThatReceiptAlone() {
        // With the history's end read before the paste: it waits for that
        // session's own user entry, also after a restart, and holds the text.
        val watched = Draft.fromJson(Draft(text = "fix it", sent = steerEntered("fix it", "E", historyFrom = 4096, now = 1)).toJson())
        assertEquals(SentState.InPane, watched.sent?.state)
        assertEquals(4096L, watched.sent?.historyFrom)
        assertEquals("fix it", watched.text)
        assertTrue(watched.unsettled)
        assertFalse(watched.mayDeliver(Binding.Bound))

        // No baseline: nothing can ever show it was admitted.
        val blind = Draft.fromJson(Draft(text = "fix it", sent = steerEntered("fix it", "E", historyFrom = null, now = 1)).toJson())
        assertEquals(SentState.Uncertain, blind.sent?.state)
        assertTrue(blind.unsettled)
        assertFalse(blind.mayDeliver(Binding.Bound))
    }

    @Test
    fun anEnteredPtySteerWaitsOnTheTailItReadAndIsDeliveredWhenItsHistoryHoldsItLater() {
        // QA S 10070: the baseline is a Tail read, which the core always marks reset.
        val omp = TranscriptIdentity("w4:pG", "term_1", 1967663, "/h/.omp/agent/sessions/x/a.jsonl")
        val tail = TranscriptPage(omp, reset = true, size = 4096, start = 0, end = 4096, entries = emptyList())
        val from = historyEnd(tail, omp)
        assertEquals(4096L, from)
        assertEquals(null, historyEnd(tail.copy(identity = omp.copy(pid = 7)), omp))
        val sent = steerEntered("Steer two", "E", from, now = 1_000)
        assertEquals(SentState.InPane, sent.state)
        assertEquals(R.string.composer_steer_entered, inPaneNotice(sent))

        // OMP takes it at its next step, past the typed-send window: still watched while it works.
        val window = 20_000L
        var until = sent.at + window
        until = ptyWatchDeadline(sent, until, working = true, now = 90_000, window = window)
        assertTrue(90_000 < until)
        val landed = TranscriptEntry("u", null, 4096, 4130, TranscriptKind.Message, "user", listOf(TranscriptBlock.Text("Steer two", false)), steer = TranscriptSteer.Steering)
        assertTrue(ptyTurnLanded(TranscriptPage(omp, false, 4130, 4096, 4130, listOf(landed)), omp, from!!, sent.text))
        // Idle again, the window runs out; a typed send never gets more than its window.
        assertEquals(until, ptyWatchDeadline(sent, until, working = false, now = 95_000, window = window))
        val typed = SentRecord("Steer two", "R", true, SentState.InPane, 1_000, historyFrom = 4096)
        assertEquals(21_000L, ptyWatchDeadline(typed, 21_000, working = true, now = 90_000, window = window))
    }

    @Test
    fun anEnteredSteerReadBackSaysItWaitsInOmpNotInTheInputBox() {
        val stored = Draft(sent = steerEntered("fix it", "E", historyFrom = 4096, now = 1))
        val back = Draft.fromJson(JSONObject(stored.toJson().toString())).sent!!
        assertEquals(SentState.InPane, back.state)
        assertEquals(R.string.composer_steer_entered, inPaneNotice(back))
    }

    @Test
    fun aTypedSendInThePaneStillSaysItIsInTheInputBox() {
        // A Chat PTY send with Enter (P1): submit alone never makes it a steer.
        val typed = SentRecord("fix it", "R", true, SentState.InPane, 1, historyFrom = 4096)
        val back = Draft.fromJson(JSONObject(Draft(sent = typed).toJson().toString())).sent!!
        assertEquals(R.string.composer_in_pane, inPaneNotice(back))
        // Stored before the steer mark existed: read as typed.
        val old = JSONObject(Draft(sent = typed).toJson().toString()).apply { getJSONObject("sent").remove("steer") }
        assertEquals(R.string.composer_in_pane, inPaneNotice(Draft.fromJson(old).sent!!))
    }

    @Test
    fun aSteerStoredMidFlightReadsBackUncertainAndIsNeverEntered() {
        // What the Editor saves while the paste is out, and while Enter is asked.
        for (receipt in listOf("", "R")) {
            val saved = Draft(text = "fix it", sent = SentRecord("fix it", receipt, submit = true, SentState.Queued, at = 1))
            val restored = Draft.fromJson(saved.toJson())
            assertEquals(SentState.Uncertain, restored.sent?.state)
            assertTrue(restored.unsettled)
            assertEquals("fix it", restored.text)

            // A fresh editor has no steer: the old receipt's answer asks nothing.
            val run = Run()
                .on(SteerEvent.Answer(DeliveryUpdate.Confirmed("R"), 10))
                .on(SteerEvent.Frame(20))
                .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS * 2))
            assertEquals(SteerPhase.Idle, run.phase)
            assertFalse(run.effects.contains(SteerEffect.AskEnter))
        }
    }

    @Test
    fun aSteerCallThatMayHaveLandedIsUncertainNeverRefused() {
        // "not_connected" is what a call failing midway reports; the other is the native core's own.
        for (reason in listOf("not_connected", "socket_lost")) {
            val run = Run()
                .on(SteerEvent.Tap(epoch))
                .on(SteerEvent.Started(SteerStart.Uncertain(reason), epoch))
                .on(SteerEvent.Frame(10))
                .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS * 2))
            assertEquals(reason, SteerPhase.Uncertain, run.phase)
            assertEquals(reason, emptyList<SteerEffect>(), run.effects)
        }
    }

    @Test
    fun anEnterCallThatMayHaveLandedIsUncertainAndNeverAskedAgain() {
        for (reason in listOf("not_connected", "socket_lost")) {
            val run = pasted()
                .on(SteerEvent.EnterAnswer(SteerEnter.Uncertain(reason), 10))
                .on(SteerEvent.Frame(20))
                .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS * 2))
            assertEquals(reason, SteerPhase.Uncertain, run.phase)
            assertEquals(reason, listOf(SteerEffect.AskEnter), run.effects)
        }
    }

    // Replies to a call that was made which do not prove what happened (S1, S2).
    private val unreadable: Map<String, () -> String> = mapOf(
        "link failed midway" to { throw IOException("link closed") },
        "reply not JSON" to { "staged R" },
        "reply cut short" to { "{\"outcome\":\"sta" },
        "unknown outcome" to { "{\"outcome\":\"later\",\"receipt\":\"R\"}" },
        "no outcome" to { "{}" },
        "staged without a receipt" to { "{\"outcome\":\"staged\"}" },
        "queued with an empty receipt" to { "{\"outcome\":\"queued\",\"receipt\":\"\"}" },
        "refused without a reason" to { "{\"outcome\":\"refused\"}" },
        "waiting without a reason" to { "{\"outcome\":\"waiting\"}" },
    )

    @Test
    fun aSteerReplyThatProvesNothingIsUncertainAndNeverEntered() {
        for ((case, reply) in unreadable) {
            val start = steerStartOutcome(reply)
            assertTrue(case, start is SteerStart.Uncertain)
            val run = Run().on(SteerEvent.Tap(epoch)).on(SteerEvent.Started(start, epoch)).on(SteerEvent.Frame(10))
            assertEquals(case, SteerPhase.Uncertain, run.phase)
            assertEquals(case, 0, run.enters)
        }
    }

    @Test
    fun anEnterReplyThatProvesNothingIsUncertainAndNeverAskedAgain() {
        for ((case, reply) in unreadable) {
            val enter = steerEnterOutcome(reply)
            assertTrue(case, enter is SteerEnter.Uncertain)
            val run = pasted().on(SteerEvent.EnterAnswer(enter, 10)).on(SteerEvent.Frame(20)).on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS * 2))
            assertEquals(case, SteerPhase.Uncertain, run.phase)
            assertEquals(case, 1, run.enters)
            assertEquals(case, 0, run.cancels)
        }
    }

    @Test
    fun theNativeCoresOwnAnswersKeepTheirMeaning() {
        assertEquals(SteerStart.Staged("R"), steerStartOutcome { "{\"outcome\":\"staged\",\"receipt\":\"R\"}" })
        assertEquals(SteerStart.Refused("agent_idle"), steerStartOutcome { "{\"outcome\":\"refused\",\"reason\":\"agent_idle\"}" })
        assertEquals(SteerEnter.Queued("E"), steerEnterOutcome { "{\"outcome\":\"queued\",\"receipt\":\"E\"}" })
        assertEquals(SteerEnter.Waiting("steer_not_shown"), steerEnterOutcome { "{\"outcome\":\"waiting\",\"reason\":\"steer_not_shown\"}" })
        assertEquals(SteerEnter.Refused("steer_expired"), steerEnterOutcome { "{\"outcome\":\"refused\",\"reason\":\"steer_expired\"}" })
    }

    @Test
    fun everySteerThatEndsWithThePasteLeftCancelsItOnce() {
        val next = SteerEpoch(Any(), "boot-1")
        val ends = mapOf(
            "enter refused, link gone" to pasted().on(SteerEvent.EnterAnswer(SteerEnter.Refused("not_connected"), 10)),
            "enter refused by the core" to pasted().on(SteerEvent.EnterAnswer(SteerEnter.Refused("agent_idle"), 10)),
            "window closed by a tick" to pasted()
                .on(SteerEvent.EnterAnswer(SteerEnter.Waiting("steer_not_shown"), 10))
                .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS)),
            "window closed by a frame" to pasted()
                .on(SteerEvent.EnterAnswer(SteerEnter.Waiting("steer_not_shown"), 10))
                .on(SteerEvent.Frame(STEER_ENTER_WINDOW_MS)),
            "waiting past the window" to pasted().on(SteerEvent.EnterAnswer(SteerEnter.Waiting("unsettled"), STEER_ENTER_WINDOW_MS)),
            "link changed while idle" to pasted()
                .on(SteerEvent.EnterAnswer(SteerEnter.Waiting("steer_not_shown"), 10))
                .on(SteerEvent.Epoch(next)),
            "link changed while asking" to pasted()
                .on(SteerEvent.Epoch(next))
                .on(SteerEvent.EnterAnswer(SteerEnter.Waiting("unsettled"), 10)),
        )
        for ((case, run) in ends) {
            run.on(SteerEvent.Frame(STEER_ENTER_WINDOW_MS * 3)).on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS * 4))
            assertTrue(case, run.phase is SteerPhase.Left)
            assertEquals(case, 1, run.cancels)
        }
    }

    @Test
    fun aFailedWriteAheadNeverCallsThePasteOrTheEnter() {
        var calls = 0

        val start = afterWriteAhead({ false }, SteerStart.Refused(WRITE_AHEAD_FAILED)) { calls++; SteerStart.Staged("R") }
        val refused = Run().on(SteerEvent.Tap(epoch)).on(SteerEvent.Started(start, epoch))
        assertEquals(SteerPhase.Refused(WRITE_AHEAD_FAILED), refused.phase)
        assertEquals(emptyList<SteerEffect>(), refused.effects)

        // The paste is in the box; its Enter is never called and never asked again.
        val enter = afterWriteAhead({ false }, SteerEnter.Refused(WRITE_AHEAD_FAILED)) { calls++; SteerEnter.Queued("E") }
        val left = pasted()
            .on(SteerEvent.EnterAnswer(enter, 10))
            .on(SteerEvent.Frame(20))
            .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS * 2))
        assertTrue(left.phase is SteerPhase.Left)
        assertEquals(listOf(SteerEffect.AskEnter, SteerEffect.Cancel), left.effects)

        assertEquals(0, calls)
    }

    @Test
    fun aSteerKilledDuringItsPasteOrEnterRestartsUncertainAndOnlyUnlockReleasesIt() {
        // What steerStart and the Enter ask write ahead: no receipt yet, then the paste's.
        for (receipt in listOf("", "R")) {
            val ahead = Draft(text = "fix it", sent = SentRecord("fix it", receipt, submit = true, SentState.Queued, at = 1))
            var disk: String? = null
            var calls = 0
            afterWriteAhead({ disk = ahead.toJson().toString(); true }, SteerEnter.Refused(WRITE_AHEAD_FAILED)) {
                calls++
                SteerEnter.Queued("E")
            }
            assertEquals(1, calls)

            // The app died before the answer: the relaunch reads only what was on disk.
            val relaunched = Draft.fromJson(JSONObject(disk ?: "{}"))
            assertEquals(receipt, SentState.Uncertain, relaunched.sent?.state)
            assertEquals(receipt, "fix it", relaunched.text)
            assertFalse("$receipt: Send or Steer re-armed after a relaunch", relaunched.mayDeliver(Binding.Bound))

            // A fresh editor has no steer: a late answer or frame asks no Enter.
            val run = Run()
                .on(SteerEvent.Answer(DeliveryUpdate.Confirmed(receipt), 10))
                .on(SteerEvent.Frame(20))
                .on(SteerEvent.Tick(STEER_ENTER_WINDOW_MS * 2))
            assertEquals(SteerPhase.Idle, run.phase)
            assertEquals(emptyList<SteerEffect>(), run.effects)

            // Only Unlock lets the text go again.
            assertTrue(receipt, unlocked(relaunched).mayDeliver(Binding.Bound))
        }
    }
}
