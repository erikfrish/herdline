package io.github.erikfrish.herdline.ui.session

import androidx.annotation.StringRes
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ComposerCheck
import io.github.erikfrish.herdline.core.IngressProbe

/** What the main slot right of the field is: the verb its one tap delivers. */
internal enum class SlotFace { Send, Queue, Steer }

/** Which notice holds the slot's notice row, as far as the slot's face cares. */
internal enum class NoticeRole {
    /** No notice. */
    None,

    /** The working notice where Steer is the slot's verb and nothing queues. */
    Working,

    /** The working notice that carries the labelled Queue action. */
    WorkingQueue,

    /** Any other notice: it outranks the working one. */
    Other,
}

/**
 * The slot's face. [steerRoute] says a Steer route exists (the endpoint's,
 * or the PTY check's steer gate while the agent works); it never follows the
 * text, so the face does not flip while typing. Steer takes the slot only
 * while the working notice is the one shown, or no notice where nothing
 * queues, so wherever Queue is offered it stays one visible tap away: the
 * slot itself, or the working notice's Queue action ([queueReachable]).
 */
internal fun slotFace(steerRoute: Boolean, queueVerb: Boolean, notice: NoticeRole): SlotFace = when {
    steerRoute && notice == NoticeRole.WorkingQueue -> SlotFace.Steer
    steerRoute && !queueVerb && (notice == NoticeRole.None || notice == NoticeRole.Working) -> SlotFace.Steer
    queueVerb -> SlotFace.Queue
    else -> SlotFace.Send
}

/** Queue, where the route offers it, is the slot's face or the shown notice's action. */
internal fun queueReachable(face: SlotFace, queueVerb: Boolean, notice: NoticeRole): Boolean =
    !queueVerb || face == SlotFace.Queue || notice == NoticeRole.WorkingQueue

/** Which verb the slot wears and whether each of its taps may go. */
internal data class SlotGates(val steerRoute: Boolean, val sendEnabled: Boolean, val steerEnabled: Boolean)

/**
 * The slot's gates from the reads in hand. [free]: nothing else holds the
 * draft back (a delivery, its binding, uploads, another pane). [endpoint]:
 * Send goes through the program's endpoint; [steers]: the program takes a
 * steer on that route. [bound]: the draft names its agent instance.
 *
 * F1: once Herdr reads the pane working ([paneWorking]), a read made before
 * the turn began permits nothing: a check naming another status, or on the
 * endpoint route a probe still reading the session idle (it is polled; the
 * status is pushed). The slot then wears Steer where the program steers,
 * disabled, until a read made in the current state answers. Nothing goes
 * meanwhile, and nothing goes by itself when that read comes.
 */
internal fun slotGates(
    free: Boolean,
    sendGate: ComposerCheck.Gate?,
    check: ComposerCheck?,
    paneWorking: Boolean,
    endpoint: Boolean,
    endpointSteer: Boolean,
    probe: IngressProbe?,
    bound: Boolean,
    staged: Boolean,
    steers: Boolean,
): SlotGates {
    val checkedBefore = check?.status.let { it != null && it != AgentStatus.Working }
    val probedBefore = endpoint && probe is IngressProbe.Live && probe.state.idle
    if (paneWorking && (checkedBefore || probedBefore)) {
        // PTY steering is text-only: staged files keep it Send there.
        return SlotGates(steerRoute = steers && (endpoint || !staged), sendEnabled = false, steerEnabled = false)
    }
    // Endpoint steering accepts ready attachments; PTY steering stays text-only.
    val paneSteer = !staged && check?.steer?.ok == true
    return SlotGates(
        steerRoute = endpointSteer || paneWorking && paneSteer,
        sendEnabled = free && sendGate?.ok == true,
        steerEnabled = free && bound && (endpointSteer || paneSteer),
    )
}

/** A [hold]'s words for the slot's [face]: the verb shown is the verb named. */
@StringRes
internal fun holdRes(hold: SendHold, face: SlotFace): Int = when (hold) {
    SendHold.Unsettled -> R.string.reason_unsettled
    SendHold.Checking -> when (face) {
        SlotFace.Send -> R.string.composer_checking
        SlotFace.Queue -> R.string.composer_checking_queue
        SlotFace.Steer -> R.string.composer_checking_steer
    }
    SendHold.CheckingResizing -> when (face) {
        SlotFace.Send -> R.string.composer_checking_resizing
        SlotFace.Queue -> R.string.composer_checking_resizing_queue
        SlotFace.Steer -> R.string.composer_checking_resizing_steer
    }
}

/**
 * A current, positive read that the pane's input box holds text: this
 * pane's check saw its box as a draft, or one of its gates refuses for that
 * very reason (the native core says remote_draft only from a box it read
 * non-empty). A tap's earlier refusal alone is no such read, and without
 * one nothing advises clearing the box in Terminal.
 */
internal fun remoteDraftConfirmed(check: ComposerCheck?): Boolean =
    check != null &&
        (check.editor == "draft" || listOf(check.send, check.chatSend, check.insert, check.steer).any { it.reason == REMOTE_DRAFT })

/** The notice a steer that stopped without Enter leaves, by [reason]. */
internal enum class SteerLeft {
    /** The pane never showed the paste in time: what its box holds is unknown. */
    NotShown,

    /** The steer outlived its link or run. */
    Expired,

    /** Any other stop before Enter. */
    Left,
}

internal fun steerLeft(reason: String): SteerLeft = when (reason) {
    "steer_not_shown" -> SteerLeft.NotShown
    "steer_expired" -> SteerLeft.Expired
    else -> SteerLeft.Left
}

internal const val REMOTE_DRAFT = "remote_draft"
