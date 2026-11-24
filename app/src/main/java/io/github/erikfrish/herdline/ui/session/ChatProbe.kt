package io.github.erikfrish.herdline.ui.session

import androidx.annotation.StringRes
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.ReasonCode
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.TranscriptDetached
import io.github.erikfrish.herdline.core.TranscriptDetachment
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.core.TranscriptPending
import io.github.erikfrish.herdline.core.TranscriptRefusal
import io.github.erikfrish.herdline.core.TranscriptRefused
import io.github.erikfrish.herdline.core.UnboundReason
import io.github.erikfrish.herdline.ui.chat.unboundRes

/**
 * Whether [pane] is an OMP herdr-core runs without Herdline's hook
 * labels ([ReasonCode.HOOKLESS_OMP]): Chat may show its history read only,
 * once the host proves which session it runs.
 */
internal fun hooklessOmp(view: ShellView?, pane: String): Boolean =
    view != null &&
        view.agentIn(pane)?.agent.equals("omp", ignoreCase = true) &&
        view.panes.firstOrNull { it.id == pane }?.chat == ReasonCode.HOOKLESS_OMP

/**
 * Whether Chat opens on [pane]: a pane it reads as ever ([chatReadable]), or
 * a hookless OMP a Chat tap bound in this daemon run ([boundBoot] is the
 * boot it was bound in). A binding never outlives the boot or the pane
 * staying a hookless OMP: labels appearing hand it to the hooked path.
 */
internal fun chatOpens(view: ShellView?, pane: String, boundBoot: String?): Boolean =
    chatReadable(view, pane) || boundBoot != null && boundBoot == view?.boot && hooklessOmp(view, pane)

/** How a Chat tap's identifying read of a hookless OMP went. */
internal sealed interface ProbeOutcome {
    /** The host proved the pane's session: Chat opens read only. */
    data object Bound : ProbeOutcome
    /** No session is proved, by [reason]: the pane stays in Terminal and says why. */
    data class Refused(val reason: UnboundReason) : ProbeOutcome
    /** The OMP has not written its session yet: no Chat before its first write. */
    data object Pending : ProbeOutcome
    /** The read failed for another cause (link, timeout, a newer core's answer): Terminal, try again. */
    data object Failed : ProbeOutcome
}

/** What [read], one transcript read without `expect`, says of a hookless OMP. */
internal fun probeOutcome(read: Result<TranscriptPage>): ProbeOutcome {
    if (read.isSuccess) return ProbeOutcome.Bound
    return when (val error = read.exceptionOrNull()) {
        is TranscriptPending -> ProbeOutcome.Pending
        is TranscriptRefused -> (error.refusal as? TranscriptRefusal.Unbound)?.let { ProbeOutcome.Refused(it.reason) } ?: ProbeOutcome.Failed
        // Without expect native does not detach; a refused one still names its reason.
        is TranscriptDetached -> (error.detachment as? TranscriptDetachment.Refused)?.let { ProbeOutcome.Refused(it.reason) } ?: ProbeOutcome.Failed
        else -> ProbeOutcome.Failed
    }
}

/** The compact Terminal line for an [outcome] that keeps the pane in Terminal; null once bound. */
@StringRes
internal fun probeHintRes(outcome: ProbeOutcome): Int? = when (outcome) {
    ProbeOutcome.Bound -> null
    is ProbeOutcome.Refused -> unboundRes(outcome.reason)
    ProbeOutcome.Pending -> R.string.chat_probe_fresh
    ProbeOutcome.Failed -> R.string.chat_probe_failed
}
