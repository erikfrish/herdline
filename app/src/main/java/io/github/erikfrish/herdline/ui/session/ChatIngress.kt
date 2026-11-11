package io.github.erikfrish.herdline.ui.session

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.IngressProbe
import io.github.erikfrish.herdline.core.IngressQueued
import io.github.erikfrish.herdline.core.IngressSend
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState

/*
 * Chat's Send through the OMP endpoint (core/Ingress.kt): the pure rules the
 * composer follows, and the pending-queue panel. The endpoint route never
 * types into the pane, so none of this touches the PTY receipt flow; only
 * IngressSend.Absent hands a Send back to it.
 */

/** Marks a sent record whose request went to the endpoint: Check asks OMP, not the pane. */
internal const val INGRESS_RECEIPT = "ingress:"

internal fun ingressReceipt(id: String): String = INGRESS_RECEIPT + id

/** The request id of an endpoint send, or null for a delivery typed into the pane. */
internal fun ingressId(sent: SentRecord?): String? =
    sent?.receipt?.takeIf { it.startsWith(INGRESS_RECEIPT) }?.removePrefix(INGRESS_RECEIPT)?.ifEmpty { null }

/**
 * Whether a delivery asks the endpoint first: Chat's Send and Steer to a
 * draft bound to a known OMP instance. Insert puts text in the box without
 * Enter, which only the pane can do, and Terminal keeps typing.
 */
internal fun endpointRoute(reading: Boolean, instance: TranscriptIdentity?, submit: Boolean): Boolean =
    reading && submit && instance != null

/**
 * The draft's sent record once the endpoint answered [outcome]. A refusal,
 * or no endpoint (the pane route then records its own), gives [before]
 * back: nothing left. Accepted is settled, since OMP holds it durably;
 * uncertain keeps the id, so Check can ask OMP for exactly that request.
 */
internal fun sentAfterIngress(outcome: IngressSend, before: SentRecord?, text: String, now: Long): SentRecord? =
    when (outcome) {
        is IngressSend.Accepted -> SentRecord(text, ingressReceipt(outcome.id), true, SentState.Confirmed, now)
        is IngressSend.Uncertain -> SentRecord(text, ingressReceipt(outcome.id), true, SentState.Uncertain, now)
        is IngressSend.Refused, IngressSend.Absent -> before
    }

/** What Check on an uncertain endpoint send learned from OMP's own record. */
internal sealed interface IngressCheck {
    data object Accepted : IngressCheck
    data class Refused(val reason: String) : IngressCheck

    /** No record, a pending one, or no answer: still possibly queued, so nothing changes. */
    data object Unknown : IngressCheck
}

internal fun ingressChecked(result: Result<IngressSend>): IngressCheck =
    when (val found = result.getOrNull()) {
        is IngressSend.Accepted -> IngressCheck.Accepted
        is IngressSend.Refused -> IngressCheck.Refused(found.reason)
        is IngressSend.Uncertain, IngressSend.Absent, null -> IngressCheck.Unknown
    }

/** A probe of the endpoint, and the instance it was taken for. */
internal data class ProbeRead(val instance: TranscriptIdentity, val probe: IngressProbe)

/** [read]'s probe only while the draft is still bound to the instance it described. */
internal fun probeFor(read: ProbeRead?, instance: TranscriptIdentity?): IngressProbe? =
    read?.takeIf { instance != null && it.instance == instance }?.probe

/** What the queue panel shows. */
internal sealed interface QueuePanel {
    data object Hidden : QueuePanel
    data class Unsupported(val missing: String) : QueuePanel
    data class Unusable(val reason: String) : QueuePanel
    data class Queue(val entries: List<IngressQueued>) : QueuePanel
}

/**
 * The panel for [probe]. An instance without an endpoint shows nothing:
 * Chat then types into the pane as before. One that has the extension and
 * cannot use it says so, in different words for missing OMP hooks than for
 * an endpoint that failed its checks.
 */
internal fun queuePanel(probe: IngressProbe?): QueuePanel = when (probe) {
    null, IngressProbe.Absent -> QueuePanel.Hidden
    is IngressProbe.Unsupported -> QueuePanel.Unsupported(probe.missing.joinToString(", "))
    is IngressProbe.Unusable -> QueuePanel.Unusable(probe.reason)
    is IngressProbe.Live -> if (probe.state.queued.isEmpty()) QueuePanel.Hidden else QueuePanel.Queue(probe.state.queued)
}

/**
 * Only a request this app sent carries an id OMP can withdraw; one queued
 * at the desktop is withdrawn there. One withdraw at a time.
 */
internal fun withdrawEnabled(entry: IngressQueued, withdrawing: String?): Boolean =
    entry.requestId != null && withdrawing == null

/**
 * OMP's queue for the instance Chat reads, its own block above the
 * composer's notice. The persisted "Steering" label on a sent message is
 * history; this is what is waiting now.
 */
@Composable
internal fun IngressQueuePanel(
    panel: QueuePanel,
    withdrawing: String?,
    @StringRes note: Int?,
    onWithdraw: (IngressQueued) -> Unit,
    modifier: Modifier = Modifier,
) {
    val error = MaterialTheme.colorScheme.error
    val noteText: @Composable () -> Unit = {
        note?.let {
            Text(stringResource(it), style = MaterialTheme.typography.bodySmall, color = error, modifier = Modifier.testTag("ingress:note"))
        }
    }
    when (panel) {
        QueuePanel.Hidden -> Column(modifier) { noteText() }
        is QueuePanel.Unsupported -> Text(
            stringResource(R.string.ingress_unsupported, panel.missing),
            style = MaterialTheme.typography.bodySmall,
            color = error,
            modifier = modifier.padding(bottom = 4.dp).testTag("ingress:unsupported"),
        )
        is QueuePanel.Unusable -> Text(
            stringResource(R.string.ingress_unusable, panel.reason),
            style = MaterialTheme.typography.bodySmall,
            color = error,
            modifier = modifier.padding(bottom = 4.dp).testTag("ingress:unusable"),
        )
        is QueuePanel.Queue -> Column(
            modifier.fillMaxWidth().padding(bottom = 4.dp).testTag("ingress:queue"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(stringResource(R.string.ingress_queue_title), style = MaterialTheme.typography.labelMedium)
            panel.entries.forEach { entry ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(if (entry.steering) R.string.ingress_queue_steering else R.string.ingress_queue_follow_up),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        entry.text.ifEmpty { stringResource(R.string.ingress_queue_no_text) },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    )
                    if (entry.requestId == null) {
                        Text(stringResource(R.string.ingress_queue_desktop), style = MaterialTheme.typography.labelSmall)
                    } else {
                        TextButton(
                            onClick = { onWithdraw(entry) },
                            enabled = withdrawEnabled(entry, withdrawing),
                            modifier = Modifier.heightIn(min = 48.dp).testTag("ingress:withdraw"),
                        ) { Text(stringResource(R.string.ingress_withdraw)) }
                    }
                }
            }
            noteText()
        }
    }
}
