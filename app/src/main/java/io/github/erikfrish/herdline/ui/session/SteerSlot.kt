package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ComposerCheck

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
