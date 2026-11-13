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
import io.github.erikfrish.herdline.core.QUEUE_STEERING
import io.github.erikfrish.herdline.core.REASON_UNUSABLE
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.data.Draft
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
 * Whether the pane's program [agent] goes by one of [names]. Herdr names
 * the program as it likes (the daemon's `display_agent`, never a fixed
 * string this build dictates), so every gate here compares them loosely,
 * as `Draft.binding` and `attachOffered` already do.
 * A case-strict compare instead dropped a live endpoint and left Send off
 * after images were staged.
 */
internal fun agentNamed(agent: String?, names: Set<String>): Boolean =
    names.any { agent.equals(it, ignoreCase = true) }

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

/**
 * The code Send's precheck shows for the pane's refused box check [gate]: where the
 * endpoint said its server is missing or can't be told apart, that reason, since the
 * box check is the PTY route's and says nothing of the program's own server.
 */
internal fun sendPrecheckCode(gate: String?, probe: IngressProbe?): String? =
    gate?.let { code -> (probe as? IngressProbe.Unusable)?.reason?.takeIf { it == "needs_server" || it == "server_discovery" } ?: code }

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
 * Whether the composer's notice of [cause] says what [panel] says: one unusable endpoint, named
 * by a Send refused for it or by Send's precheck. A server the session does not run, or one that
 * can't be told apart, is that same cause only by name; any other failed check is the general
 * [REASON_UNUSABLE], whatever the check was.
 */
internal fun sameCause(panel: QueuePanel, cause: String?): Boolean =
    panel is QueuePanel.Unusable && cause != null &&
        if (panel.reason in SERVER_CAUSES) cause == panel.reason else cause == REASON_UNUSABLE

/**
 * The code the composer's notice says of Send's block: a refused Send's [refusal], else Send's
 * [precheck] with no Send at all; null while the slot [shown] says something else first.
 */
internal fun noticeCause(refusal: String?, precheck: String?, shown: String?): String? =
    (refusal ?: precheck)?.takeIf { it == shown }

/** [panel] beside the composer's notice of [cause]: one cause is said once, in the notice. */
internal fun besideNotice(panel: QueuePanel, cause: String?): QueuePanel =
    if (sameCause(panel, cause)) QueuePanel.Hidden else panel

/**
 * The notice's words for [cause] beside [panel], the more specific of the two: for a missing or
 * untrusted server Send's own, which also say nothing was sent; for any other unusable endpoint
 * the panel's, which name the check that failed (its reason, the format argument).
 */
@StringRes
internal fun causeRes(cause: String, panel: QueuePanel): Int =
    if (cause == REASON_UNUSABLE && panel is QueuePanel.Unusable && sameCause(panel, cause)) unusableRes(panel.reason) else reasonRes(cause)

/** The unusable-endpoint causes both the panel and the notice name in their own words. */
private val SERVER_CAUSES = setOf("needs_server", "server_discovery")

/**
 * The panel for a program whose server lists no waiting steer (Kilo): [probe]'s own, except that
 * while it reads session [session] at work, the steers this app recorded as waiting there show,
 * by the text each carried. Nothing else is guessed: an idle read shows none, since nothing
 * waits behind no turn (kilo prompt-queue.ts:86-107, 273-286), and [queueSettled] then drops them.
 */
internal fun unlistedQueuePanel(probe: IngressProbe?, draft: Draft, session: String?): QueuePanel {
    if (probe !is IngressProbe.Live || probe.state.idle || session == null) return queuePanel(probe)
    val waiting = draft.waitingSteers(session).map { (id, send) -> IngressQueued(id, send.text.orEmpty(), QUEUE_STEERING) }
    return if (waiting.isEmpty()) QueuePanel.Hidden else QueuePanel.Queue(waiting)
}

/**
 * [draft] after [probe]'s read, else null when nothing changes; [editor] is the text typed now.
 * A send whose withdraw got no answer ([io.github.erikfrish.herdline.data.QueuedSend.withdrawDoubt])
 * and whose chip goes without a retry may have been taken back by that withdraw: its text and
 * files come back after [editor], unsent. Losing them is worse than a duplicate. Its chip goes
 * when the server's listed queue no longer holds it, or, where the server lists none ([unlisted],
 * Kilo), when session [session] reads idle, which also ends every other steer recorded there.
 */
internal fun queueSettled(probe: IngressProbe?, draft: Draft, unlisted: Boolean, session: String?, editor: String): Draft? {
    if (probe !is IngressProbe.Live) return null
    val gone = if (unlisted) {
        if (!probe.state.idle || session == null) return null
        draft.waitingSteers(session).map { it.first }
    } else {
        val listed = probe.state.queued.mapNotNullTo(HashSet()) { it.requestId }
        draft.queued.keys.filter { it !in listed }
    }
    val doubted = gone.filter { draft.queued[it]?.withdrawDoubt == true }
    val restored = doubted.fold(draft.copy(text = editor)) { d, id -> d.withdrawn(id, d.text) }
    val settled = (if (doubted.isEmpty()) draft else restored).steersLeft(gone)
    return settled.takeIf { it != draft }
}

/**
 * Only a request this app sent carries an id OMP can withdraw; one queued
 * at the desktop is withdrawn there. One withdraw at a time.
 */
internal fun withdrawEnabled(entry: IngressQueued, withdrawing: String?): Boolean =
    entry.requestId != null && withdrawing == null

/**
 * Whether a withdraw may start: the request comes back into the editor, so
 * the editor must be empty, with nothing on its way. Otherwise it is
 * refused before the queue is touched, and the request stays queued.
 */
internal fun withdrawRestorable(editor: String, busy: Boolean): Boolean = editor.isEmpty() && !busy

/**
 * The words for an unusable endpoint: a server the session needs and does
 * not run, or one whose registration could not be trusted, each in its own
 * terms; any other check that failed in the general ones.
 */
@StringRes
internal fun unusableRes(reason: String): Int = when (reason) {
    "needs_server" -> R.string.ingress_needs_server
    "server_discovery" -> R.string.ingress_server_discovery
    else -> R.string.ingress_unusable
}

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
    /** Steers a queued follow-up into the running turn; null where the runtime has no such verb. */
    onPromote: ((IngressQueued) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val error = MaterialTheme.colorScheme.error
    val noteText: @Composable () -> Unit = {
        when {
            note != null -> Text(stringResource(note), style = MaterialTheme.typography.bodySmall, color = error, modifier = Modifier.testTag("ingress:note"))
            // A Withdraw or Steer under way: greyed buttons alone would read as a tap that did nothing.
            withdrawing != null -> Text(stringResource(R.string.ingress_queue_asking), style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("ingress:asking"))
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
            stringResource(unusableRes(panel.reason), panel.reason),
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
                        if (onPromote != null && !entry.steering) {
                            TextButton(
                                onClick = { onPromote(entry) },
                                enabled = withdrawEnabled(entry, withdrawing),
                                modifier = Modifier.heightIn(min = 48.dp).testTag("ingress:promote"),
                            ) { Text(stringResource(R.string.ingress_promote)) }
                        }
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
