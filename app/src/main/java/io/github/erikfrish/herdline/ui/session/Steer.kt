package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Connection

/**
 * What a steer can rely on: the link it was started on and the daemon run
 * that link reported. Either changing ends any hope of an answer for it.
 */
internal data class SteerEpoch(val link: Any, val boot: String?)

/**
 * One steer, from the tap to its single Enter (native `steer.rs`). The
 * paste goes first; Enter goes only after the paste's receipt confirmed it
 * and only once, through `composerSteerEnter`. Pure, so every path can be
 * tested without a device.
 */
internal sealed interface SteerPhase {
    /** No steer in flight. */
    data object Idle : SteerPhase

    /** The steer call is on its way to the native core. */
    data class Staging(val epoch: SteerEpoch) : SteerPhase

    /** The paste is queued; [receipt] has not been answered yet. */
    data class Pasting(val receipt: String, val epoch: SteerEpoch) : SteerPhase

    /**
     * The paste was confirmed; Enter is being asked for. [asking] while a
     * call is out, [since] when the paste was confirmed, [waiting] the last
     * reason the native core gave for not sending Enter yet.
     */
    data class Entering(
        val paste: String,
        val epoch: SteerEpoch,
        val since: Long,
        val asking: Boolean,
        val waiting: String? = null,
        /** The epoch changed while a call was out: its answer cannot be trusted. */
        val stale: Boolean = false,
    ) : SteerPhase

    /** The one Enter is queued; [receipt] has not been answered yet. */
    data class Entered(val receipt: String, val epoch: SteerEpoch) : SteerPhase

    /** The Enter was confirmed: the text was submitted once. */
    data object Sent : SteerPhase

    /** An answer can no longer come: the user checks the pane; nothing is resent. */
    data object Uncertain : SteerPhase

    /** Refused before anything left: nothing is in the pane. */
    data class Refused(val reason: String) : SteerPhase

    /** The paste is in the pane's input box and the steer ended without Enter. */
    data class Left(val reason: String) : SteerPhase

    val active: Boolean get() = this is Staging || this is Pasting || this is Entering || this is Entered
}

internal sealed interface SteerEvent {
    /** The user tapped Steer. */
    data class Tap(val epoch: SteerEpoch) : SteerEvent

    /** The steer call returned; [epoch] is the one current at its return. */
    data class Started(val outcome: Connection.SteerStart, val epoch: SteerEpoch) : SteerEvent

    /** A delivery receipt was answered on the current link. */
    data class Answer(val update: Connection.DeliveryUpdate, val now: Long) : SteerEvent

    /** A new frame: the pane may show the text now. */
    data class Frame(val now: Long) : SteerEvent

    /** Time passed with no frame. */
    data class Tick(val now: Long) : SteerEvent

    /** A `composerSteerEnter` call returned. */
    data class EnterAnswer(val outcome: Connection.SteerEnter, val now: Long) : SteerEvent

    /** The link or daemon run is now [epoch]. */
    data class Epoch(val epoch: SteerEpoch) : SteerEvent
}

internal enum class SteerEffect {
    None,

    /** Call `composerSteerEnter` once, for the paste receipt in [SteerPhase.Entering.paste]. */
    AskEnter,

    /** Call `composerSteerCancel`: the steer ends without Enter, the paste stays. */
    Cancel,
}

internal data class SteerStep(val phase: SteerPhase, val effect: SteerEffect = SteerEffect.None)

/** How long a confirmed paste may wait for the pane to show it before the steer ends. */
internal const val STEER_ENTER_WINDOW_MS = 2_000L

/** The receipt this phase waits on, if any. */
internal val SteerPhase.awaiting: String?
    get() = when (this) {
        is SteerPhase.Pasting -> receipt
        is SteerPhase.Entered -> receipt
        else -> null
    }

internal fun SteerPhase.on(event: SteerEvent, window: Long = STEER_ENTER_WINDOW_MS): SteerStep = when (event) {
    is SteerEvent.Tap -> if (active) SteerStep(this) else SteerStep(SteerPhase.Staging(event.epoch))

    is SteerEvent.Started -> when {
        this !is SteerPhase.Staging -> SteerStep(this)
        event.outcome is Connection.SteerStart.Refused -> SteerStep(SteerPhase.Refused(event.outcome.reason))
        // The paste may have left: never treated as refused, never resent.
        event.outcome is Connection.SteerStart.Uncertain -> SteerStep(SteerPhase.Uncertain)
        // Queued on a link or run that is gone: its answer will not reach us.
        event.epoch != epoch -> SteerStep(SteerPhase.Uncertain)
        event.outcome is Connection.SteerStart.Staged -> SteerStep(SteerPhase.Pasting(event.outcome.receipt, epoch))
        else -> SteerStep(this)
    }

    is SteerEvent.Answer -> when {
        event.update.receipt != awaiting -> SteerStep(this)
        event.update is Connection.DeliveryUpdate.Uncertain -> SteerStep(SteerPhase.Uncertain)
        this is SteerPhase.Pasting ->
            SteerStep(SteerPhase.Entering(receipt, epoch, event.now, asking = true), SteerEffect.AskEnter)
        else -> SteerStep(SteerPhase.Sent)
    }

    is SteerEvent.Frame -> when {
        this !is SteerPhase.Entering || asking -> SteerStep(this)
        event.now - since >= window -> SteerStep(SteerPhase.Left("steer_not_shown"), SteerEffect.Cancel)
        else -> SteerStep(copy(asking = true), SteerEffect.AskEnter)
    }

    is SteerEvent.Tick -> when {
        this is SteerPhase.Entering && !asking && event.now - since >= window ->
            SteerStep(SteerPhase.Left("steer_not_shown"), SteerEffect.Cancel)
        else -> SteerStep(this)
    }

    is SteerEvent.EnterAnswer -> when {
        this !is SteerPhase.Entering || !asking -> SteerStep(this)
        else -> when (val outcome = event.outcome) {
            // An Enter queued on a link that is gone: whether it landed is unknown.
            is Connection.SteerEnter.Queued ->
                if (stale) SteerStep(SteerPhase.Uncertain) else SteerStep(SteerPhase.Entered(outcome.receipt, epoch))
            // The steer is over without Enter: the native core drops it too.
            is Connection.SteerEnter.Refused -> SteerStep(SteerPhase.Left(outcome.reason), SteerEffect.Cancel)
            // Enter may have left: the user checks the pane; nothing is asked again.
            is Connection.SteerEnter.Uncertain -> SteerStep(SteerPhase.Uncertain)
            is Connection.SteerEnter.Waiting -> when {
                stale -> SteerStep(SteerPhase.Left("steer_expired"), SteerEffect.Cancel)
                event.now - since >= window -> SteerStep(SteerPhase.Left("steer_not_shown"), SteerEffect.Cancel)
                else -> SteerStep(copy(asking = false, waiting = outcome.reason))
            }
        }
    }

    is SteerEvent.Epoch -> when (this) {
        // The call's own answer carries the epoch it returned under.
        is SteerPhase.Staging -> SteerStep(this)
        is SteerPhase.Pasting -> if (event.epoch == epoch) SteerStep(this) else SteerStep(SteerPhase.Uncertain)
        is SteerPhase.Entered -> if (event.epoch == epoch) SteerStep(this) else SteerStep(SteerPhase.Uncertain)
        is SteerPhase.Entering -> when {
            event.epoch == epoch -> SteerStep(this)
            asking -> SteerStep(copy(stale = true))
            // Nothing was asked: no Enter can have gone.
            else -> SteerStep(SteerPhase.Left("steer_expired"), SteerEffect.Cancel)
        }
        else -> SteerStep(this)
    }
}
