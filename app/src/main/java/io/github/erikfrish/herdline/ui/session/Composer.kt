package io.github.erikfrish.herdline.ui.session

import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import android.content.res.Configuration
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Harness
import io.github.erikfrish.herdline.core.HarnessKey
import io.github.erikfrish.herdline.core.isChatAgent
import io.github.erikfrish.herdline.core.ComposerCheck
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.IngressProbe
import io.github.erikfrish.herdline.core.IngressQueued
import io.github.erikfrish.herdline.core.IngressSend
import io.github.erikfrish.herdline.core.REASON_UNSUPPORTED
import io.github.erikfrish.herdline.core.REASON_UNUSABLE
import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Viewport
import io.github.erikfrish.herdline.core.TranscriptRead
import io.github.erikfrish.herdline.core.transcriptRequest
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.data.AttachConsent
import io.github.erikfrish.herdline.data.ComposerAction
import io.github.erikfrish.herdline.data.ComposerMenu
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.Draft
import io.github.erikfrish.herdline.data.DraftKey
import io.github.erikfrish.herdline.data.DraftStore
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import io.github.erikfrish.herdline.data.InstanceRead
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.ui.components.ConfirmDialog
import io.github.erikfrish.herdline.ui.chat.ChatColors
import io.github.erikfrish.herdline.ui.chat.ChatType
import io.github.erikfrish.herdline.ui.chat.chatColors
import io.github.erikfrish.herdline.ui.chat.historyEnd
import io.github.erikfrish.herdline.ui.chat.ptyTurnLanded
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.mutableStateMapOf
import io.github.erikfrish.herdline.core.AttachmentStager
import io.github.erikfrish.herdline.core.Staging
import io.github.erikfrish.herdline.data.AttachmentsState
import io.github.erikfrish.herdline.data.DraftAttachment
import io.github.erikfrish.herdline.data.MAX_ATTACHMENTS
import io.github.erikfrish.herdline.data.attachmentsState
import kotlin.math.ceil
import java.util.UUID

/**
 * A delivery handed to the daemon and not yet settled, with the IDs of the
 * files it carried. [watched] (P1): Chat typed this into an agent's pane to
 * send it, so the read receipt makes it [SentState.InPane], not Sent;
 * [historyFrom] is where that session's history ended before it went, null
 * when unread.
 */
private data class Pending(
    val receipt: String, val text: String, val submit: Boolean, val connection: Connection, val attachments: Set<String> = emptySet(),
    val watched: Boolean = false, val historyFrom: Long? = null, val to: String? = null,
)

/**
 * The draft's sent record once `deliver` returned [outcome]: [before] again
 * only when nothing left, so Send stays held until Unlock whenever the text
 * may be in the pane. [to] is the destination's name at the tap.
 */
internal fun sentAfter(outcome: Connection.Delivery, before: SentRecord?, text: String, submit: Boolean, now: Long, to: String? = null): SentRecord? =
    when (outcome) {
        is Connection.Delivery.Queued -> SentRecord(text, outcome.receipt, submit, SentState.Queued, now, to = to)
        is Connection.Delivery.Refused -> before
        is Connection.Delivery.Uncertain -> SentRecord(text, "", submit, SentState.Uncertain, now, to = to)
    }

/**
 * The record an answered receipt leaves. A watched send (P1) waits
 * [SentState.InPane] for its turn in the history. Any other is only
 * [SentState.Handed]: Herdr answers the receipt with success also when it
 * dropped the write, so it is never reported as delivered. [to]
 * is the destination's name captured at the tap.
 */
internal fun answeredRecord(
    text: String, receipt: String, submit: Boolean, watched: Boolean, historyFrom: Long?, now: Long, to: String? = null,
): SentRecord =
    if (watched) {
        SentRecord(text, receipt, submit, SentState.InPane, now, historyFrom = historyFrom, to = to)
    } else {
        SentRecord(text, receipt, submit, SentState.Handed, now, to = to)
    }

/** The editor once the delivery of [sent] is answered: empty, unless the user changed it meanwhile. */
internal fun editorAfterAnswer(editor: String, sent: String): String = if (editor == sent) "" else editor

/**
 * The draft a confirmed receipt for [text] leaves. A watched send (P1) keeps
 * the editor and its files. An unwatched one is [SentState.Handed]: only now
 * does the text leave the editor (Reuse brings it back), and the files in
 * [carried] leave with it; their host copies stay for OMP to read and go
 * with the seven-day sweep.
 */
internal fun answeredDraft(
    draft: Draft, editor: String, text: String, receipt: String, submit: Boolean, watched: Boolean,
    historyFrom: Long?, carried: Set<String>, now: Long, to: String? = null,
): Draft {
    val sent = answeredRecord(text, receipt, submit, watched, historyFrom, now, to)
    if (watched) return draft.copy(sent = sent)
    val kept = editorAfterAnswer(editor, text)
    return draft.copy(
        text = kept, selectionStart = kept.length, selectionEnd = kept.length, sent = sent,
        attachments = draft.attachments.filterNot { it.id in carried },
    )
}

/** How long the notice slot speaks of [sent]: [HANDED_NOTICE_MS] for Handed, [SENT_NOTICE_MS] for a proven Confirmed, else null. */
private fun sentNoticeWindow(sent: SentRecord?): Long? = when (sent?.state) {
    SentState.Handed -> HANDED_NOTICE_MS
    SentState.Confirmed -> SENT_NOTICE_MS
    else -> null
}

/**
 * What is left at [now] of [sent]'s notice window, counted from its stored
 * answer time, so also across a return or a restart; null when [sent] has
 * none. An answer time ahead of [now] (the wall clock stepped back) counts
 * from [now], so the window never outlasts its length.
 */
internal fun sentNoticeLeft(sent: SentRecord?, now: Long): Long? =
    sentNoticeWindow(sent)?.let { window -> (minOf(sent!!.at, now) + window - now).coerceAtLeast(0) }

/**
 * Returns once [sent]'s notice window is over. It reads [clock] once and
 * waits the rest with [sleep], `delay` on the monotonic clock: a wall clock
 * stepping back meanwhile cannot keep the notice up.
 */
internal suspend fun awaitSentNoticeEnd(
    sent: SentRecord?,
    clock: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
) {
    sentNoticeLeft(sent, clock())?.let { sleep(it) }
}

/** Reads the bound send's history until its turn lands or the existing watch ends. Never sends input. */
internal suspend fun awaitPtyTurn(
    sent: SentRecord,
    instance: TranscriptIdentity,
    read: suspend (Long) -> TranscriptPage?,
    working: () -> Boolean,
    clock: () -> Long = System::currentTimeMillis,
    sleep: suspend (Long) -> Unit = { delay(it) },
): Boolean {
    val from = sent.historyFrom ?: return false
    var cursor = from
    var until = sent.at + PTY_TURN_WATCH_MS
    do {
        val page = read(cursor)
        if (page != null) {
            if (page.reset || page.identity != instance || page.end < page.start) return false
            if (ptyTurnLanded(page, instance, from, sent.text)) return true
            cursor = maxOf(cursor, page.end)
        }
        sleep(PTY_TURN_POLL_MS)
        val now = clock()
        until = ptyWatchDeadline(sent, until, working(), now, PTY_TURN_WATCH_MS)
    } while (now < until)
    return false
}

/** Whether the slot shows [sent]'s notice and its Reuse: while its window is [live] and [editor] is empty. */
internal fun sentNoticeShown(sent: SentRecord?, editor: String, live: Boolean): Boolean =
    live && editor.isEmpty() && sentNoticeWindow(sent) != null

/**
 * The words of [sent]'s notice: only a proven delivery says Sent. Handed
 * comes only from a Chat Insert or Append: a Chat Send is always watched.
 */
internal fun sentNoticeText(sent: SentRecord): Int =
    if (sent.state == SentState.Handed) R.string.composer_handed_insert else R.string.composer_sent_to

/** Whether [sent]'s notice offers Check before Reuse: a Handed one asks the user to look at the terminal. */
internal fun sentNoticeChecks(sent: SentRecord): Boolean = sent.state == SentState.Handed

/**
 * The pane [sent]'s notice names: the name captured at the tap, so a closed
 * or renamed pane is still named as it was; [live] only for a
 * record that never captured it.
 */
internal fun sentNoticeName(sent: SentRecord, live: String): String = sent.to ?: live

/**
 * Runs [call] only once [writeAhead] stored the unconfirmed record on disk.
 * A failed write calls nothing and answers [refused]: nothing was sent.
 * Send, Steer's paste and Steer's Enter all go through it.
 */
internal inline fun <T> afterWriteAhead(writeAhead: () -> Boolean, refused: T, call: () -> T): T =
    if (writeAhead()) call() else refused

/** The refusal of a delivery whose write-ahead record could not be stored. */
internal const val WRITE_AHEAD_FAILED = "write_ahead_failed"

/** Image steering needs a live, working endpoint whose runtime takes a steer (OMP's, Codex's daemon, OpenCode's server); the PTY route is text-only. */
internal fun endpointSteerReady(reading: Boolean, instance: io.github.erikfrish.herdline.core.TranscriptIdentity?, probe: IngressProbe?): Boolean =
    reading && agentNamed(instance?.agent, Harness.steerAgents) && probe is IngressProbe.Live && !probe.state.idle

/**
 * Whether Chat's Send queues behind the running turn: a program whose
 * runtime has that verb, read live and working. The button then says
 * Queue before the tap; Auto never becomes a queue on the host.
 */
internal fun sendQueues(reading: Boolean, instance: io.github.erikfrish.herdline.core.TranscriptIdentity?, probe: IngressProbe?): Boolean =
    reading && agentNamed(instance?.agent, Harness.queueAgents) && probe is IngressProbe.Live && !probe.state.idle

/**
 * The refusal code an endpoint send shows: a route that needs its server,
 * or whose server could not be told apart, in its own words; any other
 * unusable route in the general ones.
 */
internal fun ingressRefusalCode(outcome: IngressSend.Refused): String =
    when (outcome.detail) {
        "needs_server", "server_discovery" -> outcome.detail.takeIf { outcome.reason == REASON_UNUSABLE } ?: outcome.reason
        else -> outcome.reason
    }

/**
 * The draft after an edit to [text] with the selection [start]..[end], or
 * null while the draft is frozen: while a delivery is [sending] or awaits
 * its answer, or its record is unsettled. A late IME commit after the tap
 * then changes nothing, so the attempted text stays exactly as it left.
 * A change to the text, not a cursor move, takes in the Unlocked notice.
 */
internal fun editedDraft(draft: Draft, sending: Boolean, text: String, start: Int, end: Int): Draft? =
    if (sending || draft.unsettled) null
    else draft.copy(text = text, selectionStart = start, selectionEnd = end).let { if (text != draft.text) acknowledged(it) else it }

/**
 * The editor with [clip] put in place of its selection, the cursor after it;
 * null while [held] or when there is nothing to paste. The composer's paste
 * only ever edits the local draft: nothing goes to the pane.
 */
internal fun pasteInto(value: TextFieldValue, clip: String, held: Boolean): TextFieldValue? {
    if (held || clip.isEmpty()) return null
    val start = value.selection.min
    val text = value.text.replaceRange(start, value.selection.max, clip)
    return TextFieldValue(text, TextRange(start + clip.length))
}

/**
 * Whether moving a draft onto another pane is refused: its source is busy
 * ([sourceBusy]: sending, awaiting an answer or unsettled), or the target's
 * stored draft is unsettled. Either text may already be in its pane, so
 * neither may be cleared, overwritten or extended.
 */
internal fun moveRefused(sourceBusy: Boolean, target: Draft?): Boolean = sourceBusy || target?.unsettled == true

/** What a draft shown on another pane offers in place of Move here (lab.8.1 audit R5, R6). */
internal enum class MoveHereOffer {
    Offered,
    /** The draft was unlocked and may already be in its own pane: ask first. */
    ConfirmUnlocked,
    /** The viewed pane's own delivery is unconfirmed: say so, offer nothing. */
    TargetHeld,
    /** The source is mid-delivery: nothing to offer for that moment. */
    Hidden,
}

/** [moveRefused] with its reason, and the confirmation unlocked text needs. */
internal fun moveHereOffer(sourceBusy: Boolean, target: Draft?, sourceUnlocked: Boolean): MoveHereOffer = when {
    target?.unsettled == true -> MoveHereOffer.TargetHeld
    moveRefused(sourceBusy, target) -> MoveHereOffer.Hidden
    sourceUnlocked -> MoveHereOffer.ConfirmUnlocked
    else -> MoveHereOffer.Offered
}

/**
 * [draft] released by Unlock: the pane may take a delivery again, the
 * attempted text stays in the editor and the record remembers that the
 * attempt may have landed. Nothing is sent.
 */
internal fun unlocked(draft: Draft): Draft =
    draft.sent?.takeIf { draft.unsettled }?.let { draft.copy(sent = it.copy(state = SentState.Unlocked)) } ?: draft

/**
 * Whether the composer still says an unlocked attempt may be in the pane:
 * until the user edits the text or dismisses it, or the next delivery
 * replaces the record (lab.8.1 QA U-notice).
 */
internal fun unlockedNoticeShown(draft: Draft): Boolean =
    draft.sent?.let { it.state == SentState.Unlocked && !it.acknowledged } == true

/** [draft] with the Unlocked notice taken in; nothing else changes and nothing is sent. */
internal fun acknowledged(draft: Draft): Draft =
    draft.sent?.takeIf { unlockedNoticeShown(draft) }?.let { draft.copy(sent = it.copy(acknowledged = true)) } ?: draft

/** What [moveDraft] did. */
internal sealed interface MoveOutcome {
    /** Refused before any write: the source or the target is held. */
    data object Refused : MoveOutcome

    /** The target holds text of its own: the user picks Replace or Append. */
    data object Conflict : MoveOutcome

    /** Nothing to move onto a pane with text: nothing changes. */
    data object Unchanged : MoveOutcome

    /** Nothing to move onto an empty pane: only the composer follows. */
    data object Followed : MoveOutcome

    /** Written onto the target; [source] is the source's stored, emptied draft. */
    data class Moved(val source: Draft) : MoveOutcome
}

/**
 * Moves [source], the draft of [from], onto [to] as [resolution] says, bound
 * to that pane as it is now ([current], [currentAgent]). A held source
 * ([sourceBusy] or unsettled) or a held target is refused before anything
 * is written; otherwise the target is written first, then the source is
 * emptied, so the text is never only in memory.
 */
internal fun moveDraft(
    store: DraftStore,
    from: DraftKey,
    source: Draft,
    sourceBusy: Boolean,
    to: DraftKey,
    current: String?,
    currentAgent: String?,
    resolution: MoveResolution,
    now: Long,
): MoveOutcome {
    val target = store.get(to)
    if (moveRefused(sourceBusy || source.unsettled, target)) return MoveOutcome.Refused
    if (resolution == MoveResolution.Ask && !source.blank && target?.blank == false) {
        return MoveOutcome.Conflict
    }
    val moved = when (resolution) {
        MoveResolution.Replace -> source.replacedOnto(target, current, currentAgent, now)
        MoveResolution.Append -> source.appendedOnto(target, current, currentAgent, now)
        MoveResolution.Ask -> source.movedOnto(target, current, currentAgent, now) ?: return MoveOutcome.Unchanged
    }
    if (source.blank && resolution == MoveResolution.Ask) return MoveOutcome.Followed
    store.put(to, moved)
    // The source's stored draft is emptied only after it was moved.
    val emptied = source.copy(text = "", selectionStart = 0, selectionEnd = 0, updatedAt = now, attachments = emptyList())
    store.put(from, emptied)
    return MoveOutcome.Moved(emptied)
}

/** Refusal of a move while the source or the target delivery is unsettled. */
internal const val MOVE_UNSETTLED = "move_unsettled"

/** Refusal of a move onto a pane whose own last delivery is unsettled. */
internal const val MOVE_TARGET_HELD = "move_target_held"

/**
 * The composer's vertical budget, in dp. A landscape phone with the keyboard
 * up leaves the session about 150 dp, and the regular composer took 126 of
 * them (L1, lab.7.3 J2). There the field holds one line and scrolls inside,
 * the paddings shrink, and a notice shares the field's top line with the
 * To-line (the notice first, never cut for it) while its actions sit in the
 * band beside Send. So a notice costs no height, also at a large font scale
 * (lab.8 J2 at 1.3). Every tap target stays 48 dp; nothing hides: the
 * notice, the To-line, Send, and the + menu with its Insert route.
 */
internal data class ComposerFit(
    val compact: Boolean,
    /** Lines the inline field grows to before it scrolls inside. */
    val maxLines: Int,
    /** Above and below the whole composer. */
    val outer: Int,
    /** Below the band's contents. */
    val band: Int,
    val bandMin: Int,
    val bandMax: Int,
    /** Above and below the field's text, inside the band. */
    val field: Int,
    /**
     * Whether the field's text row sits on the + and Send axis: the first
     * line while empty or one line, the last line of several (UR2 (b)). Else
     * the To-line and the text row are centred as a pair in the field.
     */
    val onAxis: Boolean,
    /** Whether a notice is its own block above the band; false when it is in the field's top line. */
    val noticeBlock: Boolean,
    /** Between the notice slot and the band. */
    val noticeGap: Int,
    /** Line height of the in-field To-line, in sp. */
    val toLine: Int,
    /** Whether the notice block pads its top and bottom; the side column's spends that height on its text. */
    val noticePadded: Boolean = true,
    /** Above the band's contents. */
    val bandTop: Int = band,
    /**
     * Whether attachments show as chips with their consent line above the
     * band (Regular); else the To-line counts them and the larger editor
     * shows the chips, so they cost this fit no height.
     */
    val chips: Boolean = false,
) {
    /**
     * The composer's height for a draft of [lines] lines, with or without a
     * notice, holding [attachments] files, at [fontScale]. An empty field is
     * as tall as a one-line draft: its To-line slot is reserved, so the first
     * keystroke moves nothing (UR2). The field's text is 24 sp a line; the dp
     * paddings and 48 dp targets do not scale. Attachments add their row
     * once, whatever their number or progress.
     */
    fun heightDp(lines: Int, notice: Boolean, fontScale: Float = 1f, attachments: Int = 0): Int {
        val rows = (toLine + FIELD_LINE * lines.coerceIn(1, maxLines)) * fontScale
        val band = (bandTop + band + 2 * field + rows + axisGap(fontScale)).coerceIn(bandMin.toFloat(), bandMax.toFloat())
        val block = if (notice && noticeBlock) noticeBlockDp(fontScale) + noticeGap else 0f
        val files = if (attachments > 0 && chips) attachmentsRowDp(fontScale) else 0f
        // Rounded up, but not past a float's last-bit error (40 × 1.3f).
        return ceil(2 * outer + block + files + band - 0.001f).toInt()
    }

    /**
     * Below the text row when [onAxis], in dp: what puts the row's centre on
     * Send's, half Send's 48 dp less half the 24 sp line.
     */
    fun axisGap(fontScale: Float): Float =
        if (onAxis) (SEND_TARGET / 2f - FIELD_LINE / 2f * fontScale).coerceAtLeast(0f) else 0f

    /**
     * [NoticeRow] at its tallest: [noticeLines] of text stacked over its
     * 48 dp actions, as the too-short notice is when it offers a hint.
     */
    private fun noticeBlockDp(fontScale: Float): Float {
        val stack = noticeLines(fontScale) * NOTICE_LINE * fontScale + NOTICE_GAP + NOTICE_ACTION
        return if (noticePadded) NOTICE_PAD_TOP + stack + NOTICE_PAD_UNDER_ACTIONS else stack
    }

    /**
     * Lines an unpadded notice's text is cut to at [fontScale]: [NOTICE_LINES],
     * or one above font scale 1.3, where two would push the side column's
     * band out of its 150 dp (J2 D-S1). The larger editor shows it whole.
     */
    fun noticeLines(fontScale: Float): Int = if (!noticePadded && fontScale > SIDE_TWO_LINE_SCALE) 1 else NOTICE_LINES

    companion object {
        /** The field's line height in sp: bodyLarge's, which the field is drawn in. */
        internal const val FIELD_LINE = 24
        private const val SEND_TARGET = 48

        /**
         * Portrait and the keyboard-down landscape: the text on the + and Send
         * axis, the To-line directly above it, 4 dp to the band's top (UR2 (b),
         * design 4bced42): the band is max(64, 36 + 28·fs) dp for one line.
         */
        val Regular = ComposerFit(compact = false, maxLines = 5, outer = 8, band = 8, bandMin = 64, bandMax = 152, field = 0, onAxis = true, noticeBlock = true, noticeGap = 4, toLine = 16, bandTop = 4, chips = true)
        val Compact = ComposerFit(compact = true, maxLines = 1, outer = 2, band = 0, bandMin = 48, bandMax = 80, field = 0, onAxis = false, noticeBlock = false, noticeGap = 0, toLine = 16)

        /**
         * J2 ruling S: landscape with the keyboard up, the composer in a side
         * column beside the session, as wide as the caller makes it and about
         * 150 dp tall. Compact's band and one-line field; the column is too
         * narrow for the notice's actions beside Send but tall enough for an
         * unpadded notice block above the band, its text capped at
         * [noticeLines] (the larger editor shows it whole).
         */
        val Side = Compact.copy(noticeBlock = true, noticePadded = false)
    }
}

// The regular notice block's layout ([NoticeRow]), shared with its budget in
// [ComposerFit], in dp: padding, bodySmall's line height, the gap above the
// action row, and an action's layout height. A Material button lays out
// 48 dp around its 40 dp container, so its own 4 dp above and below make
// every visible gap 8 dp. A notice is one sentence, at most two lines on a
// phone, also at font scale 1.3.
private const val NOTICE_PAD_TOP = 8
private const val NOTICE_PAD_SIDE = 12
private const val NOTICE_PAD_UNDER_ACTIONS = 4
private const val NOTICE_LINE = 16
private const val NOTICE_LINES = 2
private const val NOTICE_GAP = 4
private const val NOTICE_ACTION = 48

/** The largest font scale at which the side column's notice keeps two lines (J2 D-S1). */
private const val SIDE_TWO_LINE_SCALE = 1.3f

/** Below this height a landscape screen is a phone's (Material's compact height class). */
private const val COMPACT_HEIGHT_DP = 480

/**
 * The narrowest width [ComposerFit.Side] lays out in: the session's 280 dp
 * minimum column less a 48 dp navigation bar inset. The band keeps the +,
 * a field of about five bodyLarge characters and Send; the notice's two
 * actions sit side by side at Material's 58 dp minimum. Wider only widens
 * the field.
 */
internal const val SIDE_MIN_WIDTH_DP = 232

/**
 * [Side][ComposerFit.Side] when the caller lays the composer out in the side
 * column; else [Compact][ComposerFit.Compact] only on a landscape phone while
 * the keyboard shows.
 */
internal fun composerFit(landscape: Boolean, imeVisible: Boolean, screenHeightDp: Int, side: Boolean = false): ComposerFit = when {
    side -> ComposerFit.Side
    typingCompact(landscape, imeVisible, screenHeightDp) -> ComposerFit.Compact
    else -> ComposerFit.Regular
}

/**
 * A landscape phone while the keyboard shows: the window's current height
 * ([Configuration.screenHeightDp], so a split-screen or freeform window counts
 * by its own size) is under [COMPACT_HEIGHT_DP].
 */
private fun typingCompact(landscape: Boolean, imeVisible: Boolean, windowHeightDp: Int): Boolean =
    landscape && imeVisible && windowHeightDp < COMPACT_HEIGHT_DP

/**
 * The extra-keys panel's rows: one where [composerFit] is compact, since two
 * rows there leave a single grid row and the prompt off screen (QF-6); else
 * the panel's two. The one row keeps every key in saved order, the first
 * ones leading, and scrolls sideways if they do not fit.
 */
internal fun extraKeyRowCount(landscape: Boolean, imeVisible: Boolean, windowHeightDp: Int): Int =
    if (typingCompact(landscape, imeVisible, windowHeightDp)) 1 else 2

/**
 * Where the + menu's output-focus item goes among the shown [menu] entries
 *: the index it is placed before. Right after the larger
 * editor; with that hidden, before the terminal keyboard; else last.
 */
internal fun outputFocusSlot(menu: List<ComposerAction>): Int {
    val editor = menu.indexOf(ComposerAction.LargerEditor)
    if (editor >= 0) return editor + 1
    val keyboard = menu.indexOf(ComposerAction.TerminalKeyboard)
    return if (keyboard >= 0) keyboard else menu.size
}

/** What the "pane too short for Send" notice may offer besides its reason and row count. */
internal enum class ShortPaneOffer { Zoom, HideKeyboard, Portrait }

/**
 * The offers on the too-short notice. Zoom (shared) only while the target's
 * tab is known to be unzoomed: it is optional and may not reach 12 rows, so
 * the notice keeps its reason either way. Hiding the keyboard or rotating to
 * portrait only where the caller's row estimate says that reaches Send.
 */
internal fun shortPaneOffers(
    tabZoomed: Boolean?,
    landscape: Boolean,
    imeVisible: Boolean,
    hideKeyboardReachesSend: Boolean,
    portraitReachesSend: Boolean,
): List<ShortPaneOffer> = listOfNotNull(
    ShortPaneOffer.Zoom.takeIf { tabZoomed == false },
    ShortPaneOffer.HideKeyboard.takeIf { imeVisible && hideKeyboardReachesSend },
    ShortPaneOffer.Portrait.takeIf { landscape && portraitReachesSend },
)

/** What the "box not all on screen" notices may offer besides their reason (HG-VIS). */
internal enum class HiddenOffer { HideKeyboard, BackToBottom }

/**
 * The offers on the editor_hidden and target_not_visible notices: hiding the
 * keyboard while it is up, leaving output focus while it hides rows. Never
 * zoom, which changes the host's size and not what the phone draws.
 */
internal fun hiddenOffers(imeVisible: Boolean, hiddenRows: Int): List<HiddenOffer> = listOfNotNull(
    HiddenOffer.HideKeyboard.takeIf { imeVisible },
    HiddenOffer.BackToBottom.takeIf { hiddenRows > 0 },
)

/**
 * Which precheck refusal the notice slot shows, fed every recomposition with
 * the current refusal code (null when Send may go) and a monotonic clock.
 * The precheck runs on every frame and can refuse, or change its code, for a
 * single frame while the screen redraws, so:
 *
 * - "agent_working" shows at once: ordinary Send never reaches a working
 *   agent, and the user must read why right away (lab.8.1 decision A);
 * - any other change shows once it held for [settleMs];
 * - a refusal that keeps changing code still shows its latest code once Send
 *   has been refused for [settleMs];
 * - otherwise the shown reason stays, so a flapping code never flickers.
 */
internal class PrecheckHold(private val settleMs: Long) {
    private var shown: String? = null
    private var current: String? = null
    private var currentSince = 0L
    private var refusedSince: Long? = null

    fun observe(code: String?, now: Long): String? {
        if (code != current || currentSince == 0L) {
            current = code
            currentSince = now
        }
        refusedSince = if (code == null) null else refusedSince ?: now
        val refusedFor = refusedSince?.let { now - it } ?: 0L
        shown = when {
            code == AGENT_WORKING -> code
            now - currentSince >= settleMs -> code
            code != null && shown == null && refusedFor >= settleMs -> code
            else -> shown
        }
        return shown
    }

    companion object {
        const val AGENT_WORKING = "agent_working"
    }
}

/** What the notice slot says for a disabled Send that no other notice explains. */
internal enum class SendHold { Checking, CheckingResizing, Unsettled }

/** The send check's reasons for a grid still changing size: Herdr's, or the pane's own. */
private val RESIZING_REASONS = setOf("resizing", "editor_resizing")

/** Whether the send check's current [reason] (null: Send may go) is a resize. */
private fun resizingReason(reason: String?): Boolean = reason in RESIZING_REASONS

/**
 * Called only when the slot is otherwise empty: a draft with text whose
 * Send is off then still names why. Null when Send is on or there is
 * nothing to send. Every other state that turns Send off has its own
 * notice; what is left is a tap or a reason still settling ([Checking],
 * or [CheckingResizing] while the send check's current reason is a resize,
 * which [PrecheckHold] has not shown yet), or a delivery the editor holds
 * no answer for ([Unsettled]).
 */
internal fun silentSendHold(sendEnabled: Boolean, hasText: Boolean, sending: Boolean, unsettled: Boolean, resizing: Boolean): SendHold? {
    val checking = if (resizing) SendHold.CheckingResizing else SendHold.Checking
    return when {
        sendEnabled || !hasText -> null
        sending -> checking
        unsettled -> SendHold.Unsettled
        else -> checking
    }
}

/**
 * The app's own multiline editor for one explicit pane. Typing, dictation
 * and autocorrect happen in a real Android text field; text leaves only on
 * Insert without Enter (one bracketed paste, no Enter), Send (paste, then
 * one Enter), or, while OMP works, Steer (the paste alone, then one Enter
 * once the pane shows it; see [SteerPhase]), each checked against the
 * pane's current state by the native core. One notice block above the
 * field names why nothing may go, one state at a time, with its actions as
 * 48 dp buttons.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Composer(
    connection: Connection,
    pane: String?,
    view: ShellView?,
    frame: RenderFrame?,
    accent: Color,
    onRetarget: (String) -> Unit,
    onHasText: (Boolean) -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    onTerminalKeyboard: () -> Unit,
    onOpenTerminal: () -> Unit,
    onZoom: () -> Unit,
    modifier: Modifier = Modifier,
    /** True only where the caller's row estimate says hiding the keyboard gives the pane 12 rows. */
    hideKeyboardReachesSend: Boolean = false,
    /** True only where the caller's row estimate says portrait gives the pane 12 rows. */
    portraitReachesSend: Boolean = false,
    /**
     * True while the caller lays the composer out in the landscape + keyboard
     * side column (J2 ruling S), at whatever width it gives: [ComposerFit.Side].
     */
    side: Boolean = false,
    /**
     * J2 F, output focus: null offers no + menu item (Chat, or F not
     * offered); false offers "Focus on output", true "Show full terminal".
     */
    outputFocus: Boolean? = null,
    /** The user picked the output-focus item; the argument is the state wanted. */
    onOutputFocus: (Boolean) -> Unit = {},
    /**
     * Once each time a Send, Insert, Append or Steer is confirmed delivered,
     * or a steer's paste is left in the pane's box: text has landed in the
     * pane, so the caller shows the full terminal.
     */
    onDelivered: () -> Unit = {},
    /** True while the shown draft's delivery is unsettled or its Unlocked notice is up (K12), false once it clears. */
    onReceiptNotice: (Boolean) -> Unit = {},
    /**
     * Called synchronously on the main thread when a Send, Insert, Append,
     * Steer or Steer Enter is about to be recorded and asked of the core,
     * before its Queued record and before any admission. It may be refused
     * afterwards; the caller must treat it as "may deliver", never as sent.
     */
    onDeliveryStart: () -> Unit = {},
    /**
     * HG-VIS: what the terminal view last drew (`TerminalView.viewport()`,
     * kept as state from `onViewport`), for the checks before a tap. Null
     * before the first frame: the native core then refuses every delivery.
     */
    viewport: Viewport?,
    /**
     * Reads `TerminalView.viewport()` now. Called synchronously on the main
     * thread right after [onDeliveryStart] and at each steer Enter; what it
     * returns goes with that call, and null is refused natively.
     */
    captureViewport: () -> Viewport?,
    /**
     * The shown draft's file count, on every change and 0 when its editor
     * leaves, for the caller's height forecast ([ComposerFit.heightDp]'s
     * `attachments`). [onHasText] merges files with text; this does not.
     */
    onAttachments: (Int) -> Unit = {},
    /**
     * CT-1: in Chat, the pane read here, which the composer treats as viewed
     * in place of Herdr's focus; null in Terminal. Send's checks are the same either way.
     */
    reading: String? = null,
    /** The draft's "Open" action: in Chat it reads that pane here, in Terminal it focuses it. */
    onOpenPane: (String) -> Unit = { connection.focusPane(it) },
    /** The one labelled move of Herdr's shared focus to [reading] (CT-1 S6). */
    onShowShared: (String) -> Unit = { connection.focusPane(it) },
) {
    val context = LocalContext.current
    val store = remember { DraftStore.get(context) }
    // The + menu's entries in the order Settings › Layout › Controls saved.
    val menuIds = (context.applicationContext as? HerdlineApp)?.settings?.settings?.collectAsState()?.value?.composerMenu
    val menuItems = remember(menuIds) { ComposerMenu.actions(menuIds ?: ComposerMenu.DEFAULT) }
    val node = connection.node
    // Herdr's shared focus, and what this screen shows: the pane read here in Chat (CT-1).
    val desktop = frame?.panes?.firstOrNull { it.focused }?.id ?: view?.focusedPane
    val viewed = reading ?: desktop
    // L1: the same composables either way, so the field keeps its focus,
    // keyboard and draft while the fit changes with rotation or the IME.
    val configuration = LocalConfiguration.current
    val landscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val imeVisible = WindowInsets.isImeVisible
    val fit = composerFit(landscape = landscape, imeVisible = imeVisible, screenHeightDp = configuration.screenHeightDp, side = side)
    val offers = { tabZoomed: Boolean? ->
        shortPaneOffers(tabZoomed, landscape, imeVisible, hideKeyboardReachesSend, portraitReachesSend)
    }
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .padding(horizontal = 12.dp, vertical = fit.outer.dp)
            .testTag("composer"),
    ) {
        if (pane == null) {
            Text(
                stringResource(R.string.composer_no_pane),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.heightIn(min = 48.dp),
            )
        } else {
            // One editor per destination: switching panes swaps the whole state.
            androidx.compose.runtime.key(node.id, node.session, pane) {
                Editor(
                    store = store,
                    key = DraftKey(node.id, node.session, pane),
                    connection = connection,
                    viewed = viewed,
                    desktop = desktop,
                    reading = reading != null,
                    onOpenPane = onOpenPane,
                    onShowShared = onShowShared,
                    view = view,
                    frame = frame,
                    accent = accent,
                    menuItems = menuItems,
                    fit = fit,
                    offers = offers,
                    onRetarget = onRetarget,
                    onHasText = onHasText,
                    onAttachments = onAttachments,
                    onFieldFocus = onFieldFocus,
                    onTerminalKeyboard = onTerminalKeyboard,
                    onOpenTerminal = onOpenTerminal,
                    onZoom = onZoom,
                    outputFocus = outputFocus,
                    onOutputFocus = onOutputFocus,
                    onDelivered = onDelivered,
                    onReceiptNotice = onReceiptNotice,
                    onDeliveryStart = onDeliveryStart,
                    viewport = viewport,
                    captureViewport = captureViewport,
                    imeVisible = imeVisible,
                )
            }
        }
    }
    // Leaving the composer never leaves the terminal holding a stale grid.
    DisposableEffect(Unit) { onDispose { onFieldFocus(false) } }
}

@Composable
private fun Editor(
    store: DraftStore,
    key: DraftKey,
    connection: Connection,
    viewed: String?,
    /** Herdr's shared focus; differs from [viewed] only in Chat ([reading]). */
    desktop: String?,
    reading: Boolean,
    onOpenPane: (String) -> Unit,
    onShowShared: (String) -> Unit,
    view: ShellView?,
    frame: RenderFrame?,
    accent: Color,
    menuItems: List<ComposerAction>,
    fit: ComposerFit,
    offers: (tabZoomed: Boolean?) -> List<ShortPaneOffer>,
    onRetarget: (String) -> Unit,
    onHasText: (Boolean) -> Unit,
    onAttachments: (Int) -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    onTerminalKeyboard: () -> Unit,
    onOpenTerminal: () -> Unit,
    onZoom: () -> Unit,
    outputFocus: Boolean?,
    onOutputFocus: (Boolean) -> Unit,
    onDelivered: () -> Unit,
    onReceiptNotice: (Boolean) -> Unit,
    onDeliveryStart: () -> Unit,
    viewport: Viewport?,
    captureViewport: () -> Viewport?,
    imeVisible: Boolean,
) {
    val context = LocalContext.current
    var draft by remember(key) { mutableStateOf(store.get(key) ?: Draft()) }
    // The one field: the larger editor shows the same state, never a copy.
    var value by remember(key) {
        mutableStateOf(TextFieldValue(draft.text, TextRange(draft.selectionStart, draft.selectionEnd)))
    }
    var pending by remember(key) { mutableStateOf<Pending?>(null) }
    // Between the tap and the native answer, which may read the host first.
    var sending by remember(key) { mutableStateOf(false) }
    var refusal by remember(key) { mutableStateOf<String?>(null) }
    // A steer: its phase, the text it took and the record that text replaced.
    var steer by remember(key) { mutableStateOf<SteerPhase>(SteerPhase.Idle) }
    var steerText by remember(key) { mutableStateOf("") }
    var steerBefore by remember(key) { mutableStateOf<SentRecord?>(null) }
    var steerTarget by remember(key) { mutableStateOf<Connection.ComposerTarget?>(null) }
    // Where the bound session's history ended before the steer's paste.
    var steerFrom by remember(key) { mutableStateOf<Long?>(null) }
    // QA HB2: the receipt and the steer steps are awaited here only, so the
    // composer stays on this pane until they settle (DraftStore.releasesComposer).
    val waiting = sending || pending != null || steer.active
    DisposableEffect(key, waiting) {
        store.markInFlight(key, waiting)
        onDispose { store.markInFlight(key, false) }
    }
    var menu by remember { mutableStateOf(false) }
    var editorOpen by remember { mutableStateOf(false) }
    // Move here onto a pane that already holds text asks which draft survives.
    var moveConflict by remember { mutableStateOf<String?>(null) }
    // Move here for unlocked text asks first: it may already be in its pane.
    var moveAsk by remember { mutableStateOf<String?>(null) }
    // Unlock asks first: the held text may already be in the pane.
    var unlockAsk by remember(key) { mutableStateOf(false) }
    // Chat's OMP endpoint for the draft's instance (ChatIngress.kt): the
    // last probe, a Check under way or come back empty, and a withdraw.
    var probeRead by remember(key, connection) { mutableStateOf<ProbeRead?>(null) }
    // Bumped by every read a send, Withdraw or Steer makes itself, and when one starts: a poll
    // begun before then answers for an older queue and is dropped, never laid over the fresh one.
    var probeEpoch by remember(key, connection) { mutableIntStateOf(0) }
    var checking by remember(key) { mutableStateOf(false) }
    var checkUnknown by remember(key, draft.sent?.receipt) { mutableStateOf(false) }
    var withdrawing by remember(key) { mutableStateOf<String?>(null) }
    var withdrawNote by remember(key) { mutableStateOf<Int?>(null) }
    val state = connection.state.value
    val check: ComposerCheck? = remember(connection, view, frame, state, key.pane, viewport, draft.boot, draft.instance) {
        connection.composerCheck(key.pane, viewport, draft.boot, draft.instance)
    }
    val boot = check?.boot
    val agentNow = check?.agent
    // Which OMP process and session run in the pane: only the host knows, so
    // it is read there, again on any change Herdr reports for the agent and
    // every few seconds while the composer shows.
    var instanceNow by remember(key, connection) { mutableStateOf<InstanceRead>(InstanceRead.Unread) }
    val stateSeq = view?.agentIn(key.pane)?.stateSeq
    LaunchedEffect(key, connection, boot, agentNow, stateSeq) {
        if (boot == null) return@LaunchedEffect
        if (!isChatAgent(agentNow)) {
            instanceNow = InstanceRead.Read(null)
            return@LaunchedEffect
        }
        while (true) {
            val read = withContext(Dispatchers.IO) { connection.composerInstance(key.pane) }
            instanceNow = read.fold({ InstanceRead.Read(it) }, { InstanceRead.Unread })
            delay(INSTANCE_POLL_MS)
        }
    }
    val binding = draft.binding(boot, agentNow, instanceNow)
    val readInstance = (instanceNow as? InstanceRead.Read)?.identity

    fun save(update: Draft) {
        draft = update.copy(updatedAt = System.currentTimeMillis())
    }

    // Persist a little after typing stops, and always on leaving. Leaving
    // reads the state itself: a move empties it in the same tap.
    // Each save takes the destination's generation when it is scheduled: one
    // overtaken by a write-ahead record is dropped instead (lab.8.1 L1).
    LaunchedEffect(key, draft) {
        val generation = store.generation(key)
        delay(300)
        store.put(key, draft, generation)
    }
    DisposableEffect(key) { onDispose { store.put(key, draft) } }
    val hasText = value.text.isNotBlank()
    // Files hold the composer on this pane as text does (composerDestination).
    val holdsDraft = hasText || draft.attachments.isNotEmpty()
    LaunchedEffect(holdsDraft) { onHasText(holdsDraft) }
    val files = draft.attachments.size
    LaunchedEffect(files) { onAttachments(files) }
    val latestOnAttachments by rememberUpdatedState(onAttachments)
    DisposableEffect(Unit) { onDispose { latestOnAttachments(0) } }

    fun write(text: String, cursor: Int = text.length) {
        value = TextFieldValue(text, TextRange(cursor))
        save(draft.copy(text = text, selectionStart = cursor, selectionEnd = cursor))
    }

    // Every edit, typed, IME-committed or pasted, goes through here and reads
    // the state when it arrives: a frozen draft ignores it (lab.8.1 M1).
    fun edit(next: TextFieldValue) {
        val edited = editedDraft(draft, sending || pending != null || steer.active, next.text, next.selection.start, next.selection.end)
            ?: return
        value = next
        refusal = null
        // An ended steer's notice goes with the user's next edit.
        steer = SteerPhase.Idle
        save(edited)
    }

    /**
     * Writes to [to] instead: the text moves there unless that pane has a
     * draft of its own, which the user is then asked about. Nothing is sent.
     */
    fun moveHere(to: String, resolution: MoveResolution = MoveResolution.Ask) {
        val there = connection.composerCheck(to, viewport)
        val moving = draft.copy(text = value.text, selectionStart = value.selection.start, selectionEnd = value.selection.end)
        val outcome = moveDraft(
            store, key, moving, sending || pending != null || steer.active, key.copy(pane = to),
            there?.boot, there?.agent, resolution, System.currentTimeMillis(),
        )
        when (outcome) {
            MoveOutcome.Refused -> {
                moveConflict = null
                // Name the held side: this tab's own delivery, or the draft's (lab.8.1 N3).
                refusal = if (store.get(key.copy(pane = to))?.unsettled == true) MOVE_TARGET_HELD else MOVE_UNSETTLED
            }
            MoveOutcome.Conflict -> moveConflict = to
            MoveOutcome.Unchanged -> {}
            MoveOutcome.Followed -> onRetarget(to)
            is MoveOutcome.Moved -> {
                value = TextFieldValue("")
                draft = outcome.source
                onRetarget(to)
            }
        }
    }

    // An empty draft follows the pane; a written one keeps its binding, and
    // one written before the instance was read stays held until Rebind.
    LaunchedEffect(boot, agentNow, instanceNow, draft.blank) {
        draft.followed(boot, agentNow, instanceNow)?.let(::save)
    }

    // The last delivery was never confirmed: the native core holds the pane
    // too, also after the app restarted, until the user unlocks it. Text
    // that is only in the pane's input box (P1) is held the same way.
    LaunchedEffect(key, draft.sent?.state) {
        val state = draft.sent?.state
        if (state == SentState.Uncertain || state == SentState.InPane) connection.hold(key.nodeId, key.session, key.pane)
    }

    // J2 F: the receipt check and the K12 notices read the pane's bottom rows,
    // so the caller shows the full terminal while one is up.
    val receiptNotice = draft.unsettled || unlockedNoticeShown(draft)
    val liveReceiptNotice by rememberUpdatedState(onReceiptNotice)
    DisposableEffect(receiptNotice) {
        liveReceiptNotice(receiptNotice)
        onDispose { liveReceiptNotice(false) }
    }
    // Called from the delivery coroutines, so it reads the latest callback.
    val delivered by rememberUpdatedState(onDelivered)

    fun settle(p: Pending, update: Connection.DeliveryUpdate) {
        when (update) {
            is Connection.DeliveryUpdate.Confirmed -> {
                // P1: for a watched send the read receipt proves only that the
                // text reached the pane's input box, not that the agent took it.
                // The editor keeps it and the pane stays held until the history
                // shows it.
                val next = answeredDraft(
                    draft, value.text, p.text, p.receipt, p.submit, p.watched, p.historyFrom, p.attachments,
                    System.currentTimeMillis(), p.to,
                )
                if (!p.watched) value = TextFieldValue(next.text, TextRange(next.text.length))
                save(next)
                if (!p.watched) delivered()
            }
            is Connection.DeliveryUpdate.Uncertain -> save(
                draft.copy(sent = SentRecord(p.text, p.receipt, p.submit, SentState.Uncertain, System.currentTimeMillis(), to = p.to)),
            )
        }
    }

    // P1: text in the pane's input box becomes Sent only once the bound
    // session's history holds that very turn after the stored baseline. The
    // baseline is stored with the record, so a return or a relaunch looks
    // again (once, past the deadline). Nothing is ever typed again.
    val inPane = draft.sent?.takeIf { it.state == SentState.InPane }
    var inPaneWatched by remember(key, inPane) { mutableStateOf(false) }
    // An entered steer waits for OMP's next step: watched while the agent works.
    val paneWorking by rememberUpdatedState(view?.agentIn(key.pane)?.status == AgentStatus.Working)
    LaunchedEffect(key, inPane, connection) {
        val sent = inPane ?: return@LaunchedEffect
        val from = sent.historyFrom
        val instance = draft.instance
        if (from == null || instance == null) {
            inPaneWatched = true
            return@LaunchedEffect
        }
        val via = connection
        val landed = awaitPtyTurn(
            sent, instance,
            read = { offset -> withContext(Dispatchers.IO) { via.transcript(transcriptRequest(key.pane, instance, TranscriptRead.After(offset))) }.getOrNull() },
            working = { paneWorking },
        )
        if (landed) {
            if (draft.sent != sent) return@LaunchedEffect
            write(if (value.text == sent.text) "" else value.text)
            // The draft is held since the tap, so the files it holds are the ones sent.
            save(draft.copy(sent = sent.copy(state = SentState.Confirmed, at = System.currentTimeMillis()), attachments = emptyList()))
            via.unlock(key.nodeId, key.session, key.pane)
            delivered()
            return@LaunchedEffect
        }
        inPaneWatched = true
    }

    // Answers that came before their delivery returned from the native core.
    val early = remember(key) { LinkedHashMap<String, Connection.DeliveryUpdate>() }
    val liveConnection by rememberUpdatedState(connection)
    val steerEpoch = SteerEpoch(connection, boot)
    val liveEpoch by rememberUpdatedState(steerEpoch)
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    /** Exactly this pane, agent and instance, as the draft was bound to them. */
    fun composerTarget(): Connection.ComposerTarget? = Connection.ComposerTarget(
        nodeId = key.nodeId,
        session = key.session,
        boot = draft.boot ?: boot ?: return null,
        pane = key.pane,
        agent = draft.agent,
        instance = draft.instance,
    )

    // In Chat the endpoint and its queue are read at the instance poll's
    // pace, also while nothing is queued: a steer queued at the desktop must
    // show here too. A failed read shows nothing rather than an old queue.
    val probeTarget = composerTarget()?.takeIf { reading && it.instance != null && agentNamed(it.agent, Harness.endpointAgents) }
    LaunchedEffect(probeTarget, connection) {
        val target = probeTarget ?: return@LaunchedEffect
        val instance = target.instance ?: return@LaunchedEffect
        while (true) {
            val epoch = probeEpoch
            val read = withContext(Dispatchers.IO) { connection.ingressState(target) }
            if (epoch == probeEpoch && withdrawing == null) probeRead = read.getOrNull()?.let { ProbeRead(instance, it) }
            delay(INSTANCE_POLL_MS)
        }
    }
    val probe = probeFor(probeRead, draft.instance)
    // Kilo lists no waiting steer: its own steers leave the record once the
    // server reads the session idle, the only answer that says so. A send
    // whose withdraw got no answer comes back into the editor once its chip
    // goes without a retry; not while a withdraw is on its way, which answers itself.
    val unlistedSteers = agentNamed(draft.agent, Harness.unlistedSteerAgents)
    LaunchedEffect(probe, unlistedSteers, withdrawing) {
        if (withdrawing != null) return@LaunchedEffect
        val settled = queueSettled(probe, draft, unlistedSteers, draft.instance?.path, value.text) ?: return@LaunchedEffect
        if (settled.text != draft.text) value = TextFieldValue(settled.text, TextRange(settled.text.length))
        save(settled)
    }
    val endpointSteer = endpointSteerReady(reading, draft.instance, probe)
    val queueVerb = sendQueues(reading, draft.instance, probe)

    // UR3: the uploads under way, by attachment ID, and how far each got.
    // They belong to this destination: leaving it cancels them, and what
    // was picked reads back as not uploaded, to upload only on a tap.
    val stager = remember(connection) { AttachmentStager(connection, context.contentResolver) }
    val uploads = remember(key) { mutableStateMapOf<String, Job>() }
    val progress = remember(key) { mutableStateMapOf<String, Float?>() }
    DisposableEffect(key) { onDispose { uploads.values.forEach { it.cancel() } } }
    // A withdraw on its way holds the draft too: its request comes back into it.
    val draftBusy = sending || pending != null || steer.active || draft.unsettled || withdrawing != null
    val attachState = attachmentsState(draft.attachments, uploads.keys.toSet(), composerTarget())
    // Nothing goes to a host before the user agreed for that
    // host. The question comes before the picker, so a Cancel picks and
    // uploads nothing; the action it held runs only on Copy.
    val consent = remember { AttachConsent.get(context) }
    var consentAsk by remember(key) { mutableStateOf<Pair<Node, () -> Unit>?>(null) }

    fun withConsent(action: () -> Unit) {
        val node = connection.node
        if (consent.granted(node)) action() else consentAsk = node to action
    }

    fun withAttachment(id: String, change: (DraftAttachment) -> DraftAttachment) {
        save(draft.copy(attachments = draft.attachments.map { if (it.id == id) change(it) else it }))
    }

    /**
     * Uploads attachment [id] for the draft's target as it is now, replacing
     * an earlier copy. Only on the user's pick or tap; never again on its own.
     */
    fun upload(id: String) {
        val a = draft.attachments.firstOrNull { it.id == id } ?: return
        val target = composerTarget() ?: return
        val busyNow = sending || pending != null || steer.active || draft.unsettled
        if (id in uploads || binding != Binding.Bound || !attachOffered(busyNow, target, 0, 1) || !consent.granted(connection.node)) return
        a.staged?.let { old -> scope.launch { stager.discard(old) } }
        withAttachment(id) { it.copy(staged = null, failure = null) }
        progress[id] = null
        uploads[id] = scope.launch {
            val result = try {
                stager.stage(Uri.parse(a.uri), target) { sent, total ->
                    scope.launch { if (id in progress) progress[id] = uploadFraction(sent, total) }
                }
            } catch (e: SecurityException) {
                Staging.Refused("unreadable")
            } finally {
                uploads.remove(id)
                progress.remove(id)
            }
            val kept = draft.attachments.any { it.id == id }
            when (result) {
                is Staging.Staged -> if (kept) withAttachment(id) { it.copy(staged = result.attachment) } else stager.discard(result.attachment)
                is Staging.Refused -> if (kept) withAttachment(id) { it.copy(failure = result.reason) }
            }
        }
    }

    /** Remove: cancels its upload, takes it out of the draft and off the host. */
    fun removeAttachment(id: String) {
        if (draftBusy) return
        uploads.remove(id)?.cancel()
        progress.remove(id)
        val a = draft.attachments.firstOrNull { it.id == id } ?: return
        save(draft.copy(attachments = draft.attachments.filterNot { it.id == id }))
        a.staged?.let { staged -> scope.launch { stager.discard(staged) } }
    }

    /** The user's pick: added to the draft, read and uploaded off the main thread. */
    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch {
            val room = (MAX_ATTACHMENTS - draft.attachments.size).coerceAtLeast(0)
            val picked = withContext(Dispatchers.IO) { uris.take(room).map { pickedAttachment(context.contentResolver, it) } }
            if (picked.isEmpty() || sending || pending != null || steer.active || draft.unsettled) return@launch
            save(draft.copy(attachments = draft.attachments + picked))
            picked.forEach { upload(it.id) }
        }
    }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_ATTACHMENTS)) { attach(it) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { attach(it) }
    val attachBlocked = attachBlock(
        binding = binding,
        sending = sending || pending != null || steer.active,
        unsettled = draft.unsettled,
        target = composerTarget(),
        count = draft.attachments.size,
        max = MAX_ATTACHMENTS,
    )

    /** Moves the steer on by [event], records its outcome, and runs the one call it asks for. */
    fun steerOn(event: SteerEvent) {
        val was = steer
        val step = was.on(event)
        if (step.phase == was && step.effect == SteerEffect.None) return
        steer = step.phase
        val now = System.currentTimeMillis()
        when (val phase = step.phase) {
            is SteerPhase.Pasting, is SteerPhase.Entered -> if (phase != was) {
                save(draft.copy(sent = SentRecord(steerText, phase.awaiting ?: "", true, SentState.Queued, now)))
            }
            SteerPhase.Sent -> {
                // The Enter's receipt is the pane's, not the agent's: the editor
                // keeps the text until the history shows the steer (P1 watch).
                save(draft.copy(sent = steerEntered(steerText, was.awaiting ?: "", steerFrom, now)))
            }
            SteerPhase.Uncertain -> save(draft.copy(sent = SentRecord(steerText, was.awaiting ?: "", true, SentState.Uncertain, now)))
            is SteerPhase.Refused -> {
                save(draft.copy(sent = steerBefore))
                refusal = phase.reason
            }
            // The paste sits in the pane's box, settled; the draft stays here.
            is SteerPhase.Left -> {
                save(draft.copy(sent = steerBefore))
                delivered()
            }
            else -> {}
        }
        when (step.effect) {
            SteerEffect.AskEnter -> {
                val paste = (step.phase as? SteerPhase.Entering)?.paste ?: return
                val target = steerTarget ?: return
                val via = liveConnection
                // On disk before Enter may go: a restart after it reads back
                // unconfirmed, never enters again and never re-arms Send.
                onDeliveryStart()
                val seen = captureViewport()
                save(draft.copy(sent = SentRecord(steerText, paste, true, SentState.Queued, System.currentTimeMillis())))
                val ahead = draft
                scope.launch {
                    val outcome = withContext(Dispatchers.IO) {
                        afterWriteAhead({ store.writeAhead(key, ahead) }, Connection.SteerEnter.Refused(WRITE_AHEAD_FAILED)) {
                            via.steerEnter(target, paste, seen)
                        }
                    }
                    steerOn(SteerEvent.EnterAnswer(outcome, SystemClock.uptimeMillis()))
                    // Its Enter may have been answered before this call returned.
                    if (outcome is Connection.SteerEnter.Queued) {
                        early.remove(outcome.receipt)?.let { steerOn(SteerEvent.Answer(it, SystemClock.uptimeMillis())) }
                    }
                }
            }
            SteerEffect.Cancel -> liveConnection.steerCancel(key.nodeId, key.session, key.pane)
            SteerEffect.None -> {}
        }
    }

    LaunchedEffect(steerEpoch) { steerOn(SteerEvent.Epoch(steerEpoch)) }
    // Enter is asked again on each new frame while the pane catches up...
    LaunchedEffect(frame) { steerOn(SteerEvent.Frame(SystemClock.uptimeMillis())) }
    // ...and the window closes even when no frame comes.
    val entering = steer is SteerPhase.Entering
    LaunchedEffect(entering) {
        while (entering) {
            delay(STEER_TICK_MS)
            steerOn(SteerEvent.Tick(SystemClock.uptimeMillis()))
        }
    }

    // A reconnect replaces the connection: an answer can no longer come.
    LaunchedEffect(connection) {
        pending?.let { p ->
            if (p.connection !== connection) {
                pending = null
                save(draft.copy(sent = SentRecord(p.text, p.receipt, p.submit, SentState.Uncertain, System.currentTimeMillis(), to = p.to)))
            }
        }
        connection.deliveries.collect { update ->
            if (update.receipt == steer.awaiting) {
                steerOn(SteerEvent.Answer(update, SystemClock.uptimeMillis()))
                return@collect
            }
            val p = pending
            if (p == null || update.receipt != p.receipt) {
                early[update.receipt] = update
                if (early.size > EARLY_ANSWERS) early.remove(early.keys.first())
                return@collect
            }
            pending = null
            settle(p, update)
        }
    }

    /**
     * Sends the draft through Chat's OMP endpoint as [deliverAs]. Only an
     * instance that publishes no endpoint, known before anything left, runs
     * [typeIn] instead; every other answer stands, and doubt is uncertain.
     */
    fun endpointSend(deliverAs: String, typeIn: () -> Unit) {
        val target = composerTarget() ?: return
        val instance = target.instance ?: return
        val staged = draft.attachments.mapNotNull { it.staged }
        val carried = draft.attachments.map { it.id }.toSet()
        val text = value.text
        val before = draft.sent
        val via = connection
        val id = UUID.randomUUID().toString()
        refusal = null
        sending = true
        onDeliveryStart()
        // As for the pane route: on disk before the request may leave, so a
        // restart before the answer reads back unconfirmed, with the id Check asks for.
        // The text and files this request carries are kept by its id with it: a
        // withdrawal restores exactly them, never the queue's display text (QA m43 C3).
        save(draft.copy(sent = SentRecord(text, ingressReceipt(id), true, SentState.Queued, System.currentTimeMillis())).queuedWith(id, text, draft.attachments))
        val ahead = draft
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                afterWriteAhead({ store.writeAhead(key, ahead) }, IngressSend.Refused(WRITE_AHEAD_FAILED)) {
                    via.ingressSend(target, id, text, staged, deliverAs)
                }
            }
            val sent = sentAfterIngress(outcome, before, text, System.currentTimeMillis())
            if (outcome is IngressSend.Accepted) {
                // OMP holds it: the text and its files leave the editor. Nothing
                // was typed into the pane, so the terminal is not brought up.
                write(if (value.text == text) "" else value.text)
                // Its record, by request id, was written ahead (PHOTO-STEER-W). A steer
                // the server lists nowhere waits by this record and the server's word only.
                val kept = draft.copy(sent = sent, attachments = draft.attachments.filterNot { it.id in carried })
                save(if (deliverAs == "steer" && agentNamed(instance.agent, Harness.unlistedSteerAgents)) kept.waitingSteer(id, instance.path) else kept)
                outcome.state?.let { probeEpoch++; probeRead = ProbeRead(instance, IngressProbe.Live(it)) }
            } else {
                // Refused: nothing is queued, so nothing can be withdrawn. Uncertain keeps its record.
                save(draft.copy(sent = sent, queued = if (outcome is IngressSend.Refused) draft.queued - id else draft.queued))
            }
            sending = false
            when (outcome) {
                IngressSend.Absent -> typeIn()
                is IngressSend.Refused -> refusal = ingressRefusalCode(outcome)
                is IngressSend.Accepted, is IngressSend.Uncertain -> {}
            }
        }
    }

    /** The draft typed into the pane, with Enter when [submit]: Terminal, Insert, and Chat with no endpoint. */
    fun typeIn(submit: Boolean) {
        // The buttons say the same; this holds against a tap that raced them.
        if (sending || pending != null || steer.active || withdrawing != null || !draft.mayDeliver(binding)) return
        // Every file uploaded for exactly this target, or nothing goes.
        if (attachState != AttachmentsState.None && attachState != AttachmentsState.Ready) return
        val staged = draft.attachments.mapNotNull { it.staged }
        val target = composerTarget() ?: return
        val text = value.text
        val before = draft.sent
        val via = connection
        // The name the composer shows now, kept for the notices about this delivery.
        val to = view?.composerTitle(key.pane)
        refusal = null
        sending = true
        onDeliveryStart()
        val seen = captureViewport()
        // Recorded before the text may leave, and on disk before the call:
        // should the app stop before the answer, it reads back as unconfirmed.
        save(draft.copy(sent = SentRecord(text, "", submit, SentState.Queued, System.currentTimeMillis(), to = to)))
        val ahead = draft
        scope.launch {
            // P1: where Chat sends by typing into an agent's pane, where that
            // session's history ends before the text goes; only a turn after
            // it can be this send.
            val watched = reading && submit && target.instance != null
            val historyFrom = target.instance?.takeIf { watched }?.let { instance ->
                withContext(Dispatchers.IO) { via.transcript(transcriptRequest(key.pane, instance, TranscriptRead.Tail)) }
                    .getOrNull().let { historyEnd(it, instance) }
            }
            val outcome = withContext(Dispatchers.IO) {
                afterWriteAhead({ store.writeAhead(key, ahead) }, Connection.Delivery.Refused(WRITE_AHEAD_FAILED)) {
                    via.deliver(target, text, submit, seen, staged, chat = reading)
                }
            }
            // Stored before anything re-enables: an uncertain record holds the
            // band, + Insert and the editor until Unlock, also after a restart.
            // Only a refusal, where nothing left, gives the earlier record back.
            save(draft.copy(sent = sentAfter(outcome, before, text, submit, System.currentTimeMillis(), to)))
            sending = false
            when (outcome) {
                is Connection.Delivery.Queued -> {
                    val p = Pending(outcome.receipt, text, submit, via, draft.attachments.map { it.id }.toSet(), watched, historyFrom, to)
                    val answered = early.remove(outcome.receipt)
                    when {
                        answered != null -> settle(p, answered)
                        // Reconnected while it went: its answer cannot come.
                        liveConnection !== via -> settle(p, Connection.DeliveryUpdate.Uncertain(outcome.receipt))
                        else -> pending = p
                    }
                }
                is Connection.Delivery.Refused -> refusal = outcome.reason
                // Never "not sent": the Uncertain notice offers Check and Unlock.
                is Connection.Delivery.Uncertain -> {}
            }
        }
    }

    fun deliver(submit: Boolean) {
        if (sending || pending != null || steer.active || withdrawing != null || !draft.mayDeliver(binding)) return
        if (attachState != AttachmentsState.None && attachState != AttachmentsState.Ready) return
        if (endpointRoute(reading, draft.instance, submit)) {
            // The verb the button showed: Queue while a queueing runtime works.
            endpointSend(if (queueVerb) "queue" else "auto") { typeIn(submit) }
        } else {
            typeIn(submit)
        }
    }

    /**
     * The pane's steer: the paste alone first, then one Enter once its
     * receipt is confirmed and the pane shows it.
     */
    fun pasteSteer() {
        if (draft.attachments.isNotEmpty()) {
            refusal = "attachment_steer"
            return
        }
        if (sending || pending != null || steer.active || withdrawing != null || !draft.mayDeliver(binding)) return
        val target = composerTarget() ?: return
        val text = value.text
        val via = connection
        refusal = null
        steerText = text
        steerBefore = draft.sent
        steerTarget = target
        steerFrom = null
        onDeliveryStart()
        val seen = captureViewport()
        steerOn(SteerEvent.Tap(steerEpoch))
        // As for Send: on disk before the paste may leave, so an editor gone
        // before the answer reads back unconfirmed. A failed write is a
        // refusal: nothing is pasted and the earlier record comes back.
        save(draft.copy(sent = SentRecord(text, "", true, SentState.Queued, System.currentTimeMillis())))
        val ahead = draft
        scope.launch {
            // As for a Chat PTY send (P1): only a user entry after this end of
            // the bound session's history can be this steer.
            steerFrom = target.instance?.let { instance ->
                withContext(Dispatchers.IO) { via.transcript(transcriptRequest(key.pane, instance, TranscriptRead.Tail)) }
                    .getOrNull().let { historyEnd(it, instance) }
            }
            val outcome = withContext(Dispatchers.IO) {
                afterWriteAhead({ store.writeAhead(key, ahead) }, Connection.SteerStart.Refused(WRITE_AHEAD_FAILED)) {
                    via.steer(target, text, seen)
                }
            }
            steerOn(SteerEvent.Started(outcome, liveEpoch))
            if (outcome is Connection.SteerStart.Staged) {
                early.remove(outcome.receipt)?.let { steerOn(SteerEvent.Answer(it, SystemClock.uptimeMillis())) }
            }
        }
    }

    /**
     * Steer: in Chat through the endpoint as an explicit steer, else
     * [pasteSteer]. Its own tap only; Send never becomes it.
     */
    fun steerStart() {
        if (draftBusy || !draft.mayDeliver(binding) || (holdsDraft && viewed != null && viewed != key.pane)) return
        if (endpointRoute(reading, draft.instance, submit = true)) {
            if (attachState != AttachmentsState.None && attachState != AttachmentsState.Ready) return
            if (draft.attachments.isNotEmpty() && !endpointSteer) return
            endpointSend("steer", ::pasteSteer)
        } else {
            pasteSteer()
        }
    }

    /**
     * Check on an uncertain endpoint send: OMP's own durable record for its
     * id. It sends nothing; only a stored accept or refusal settles the
     * draft, and anything else leaves it held as it was.
     */
    fun ingressCheck() {
        val sent = draft.sent ?: return
        val id = ingressId(sent) ?: return
        val target = composerTarget()?.takeIf { it.instance != null } ?: return
        val via = connection
        checking = true
        checkUnknown = false
        scope.launch {
            val found = ingressChecked(withContext(Dispatchers.IO) { via.ingressStatus(target, id) })
            checking = false
            // Unlocked or replaced while the host was asked: that stands.
            if (draft.sent != sent) return@launch
            when (found) {
                IngressCheck.Accepted -> {
                    write(if (value.text == sent.text) "" else value.text)
                    // The draft was frozen since the tap, so the files it holds are the ones sent.
                    save(draft.copy(sent = sent.copy(state = SentState.Confirmed, at = System.currentTimeMillis()), attachments = emptyList()))
                    via.unlock(key.nodeId, key.session, key.pane)
                }
                is IngressCheck.Refused -> {
                    save(draft.copy(sent = null))
                    via.unlock(key.nodeId, key.session, key.pane)
                    refusal = found.reason
                }
                IngressCheck.Unknown -> checkUnknown = true
            }
        }
    }

    /**
     * Takes [entry] back out of OMP's queue, then reads the queue again.
     * Refused before the call when the editor could not take it back.
     */
    fun withdraw(entry: IngressQueued) {
        val requestId = entry.requestId ?: return
        if (!withdrawRestorable(value.text, draftBusy)) {
            withdrawNote = R.string.ingress_withdraw_editor
            return
        }
        val target = composerTarget() ?: return
        val instance = target.instance ?: return
        val via = connection
        withdrawing = requestId
        withdrawNote = null
        probeEpoch++
        scope.launch {
            val done = withContext(Dispatchers.IO) { via.ingressWithdraw(target, requestId) }
            val fresh = withContext(Dispatchers.IO) { via.ingressState(target) }
            withdrawing = null
            probeEpoch++
            probeRead = fresh.getOrNull()?.let { ProbeRead(instance, it) }
            withdrawNote = done.fold({ if (it) null else R.string.ingress_withdraw_gone }, { R.string.ingress_withdraw_failed })
            // Withdrawn is taken back, not lost: its text and files into the
            // editor, after anything typed while the withdraw was on its way.
            // Send and Steer stayed off meanwhile (draftBusy). A withdraw with no
            // answer is remembered: a later "not waiting" may mean it took it.
            val answer = done.getOrNull()
            // A request this draft has no record of (sent before 10064) has no known text:
            // nothing is guessed from the queue, and the user is told.
            if (answer == true && draft.queued[requestId]?.text == null) withdrawNote = R.string.ingress_withdraw_text_unknown
            val back = draft.afterWithdraw(requestId, answer, value.text)
            if (answer == true || (requestId in draft.queued && requestId !in back.queued)) {
                value = TextFieldValue(back.text, TextRange(back.text.length))
            }
            save(back)
        }
    }

    /**
     * Steers queued follow-up [entry] into the running turn, then reads the
     * queue again. Only the runtime's answer moves it; a failure says so.
     */
    fun promote(entry: IngressQueued) {
        val requestId = entry.requestId ?: return
        val target = composerTarget() ?: return
        val instance = target.instance ?: return
        val via = connection
        withdrawing = requestId
        withdrawNote = null
        probeEpoch++
        scope.launch {
            val done = withContext(Dispatchers.IO) { via.ingressPromote(target, requestId) }
            val fresh = withContext(Dispatchers.IO) { via.ingressState(target) }
            withdrawing = null
            probeEpoch++
            probeRead = fresh.getOrNull()?.let { ProbeRead(instance, it) }
            withdrawNote = done.fold({ if (it) null else R.string.ingress_promote_gone }, { R.string.ingress_promote_failed })
        }
    }

    // The draft's target title, for the placeholder, the field's hint and the notices: its tab, as the header says it.
    val titleWhere = view?.paneWhere(key.pane) ?: ""
    val draftTitle = view?.composerTitle(key.pane) ?: ""
    // The "Draft for …" strip names the destination in Herdr's own terms
    // ("workspace › tab"), never the agent's task title, which changes as
    // the agent works (lab.3 D2).
    val draftPath = titleWhere.takeIf { it.isNotEmpty() } ?: draftTitle
    // §9: a non-empty draft keeps its pane while the user views another.
    val mismatched = holdsDraft && viewed != null && viewed != key.pane && pending == null && !steer.active
    val targetGone = holdsDraft && check?.paneExists == false
    // The sent notice is a moment, not a state: it shows for a while after
    // the receipt, timed from the stored record, so also across a return or
    // a restart; then the slot is free again, whatever the wall clock does.
    val noticeSent = draft.sent
    var noticeLive by remember(noticeSent) { mutableStateOf((sentNoticeLeft(noticeSent, System.currentTimeMillis()) ?: 0L) > 0L) }
    LaunchedEffect(noticeSent) {
        awaitSentNoticeEnd(noticeSent)
        noticeLive = false
    }

    // Why Send is off before it is tapped: a disabled button cannot be
    // tapped to learn the reason, so the precheck's reason shows as the
    // same notice a refusal would, held steady by PrecheckHold.
    // A live endpoint takes Chat's Send without the pane: the box checks,
    // which read what the pane shows, neither gate nor explain it then.
    val endpointReady = reading && probe is IngressProbe.Live
    val sendGate = if (reading) check?.chatSend else check?.send
    val precheck = sendPrecheckCode(sendGate?.takeIf { !endpointReady && !it.ok && holdsDraft && binding == Binding.Bound && pending == null && !steer.active }?.reason, probe)
    val precheckHold = remember(key) { PrecheckHold(PRECHECK_SETTLE_MS) }
    // Re-reads the hold once a code may have settled, with no new frame.
    var settleTick by remember(key) { mutableStateOf(0) }
    LaunchedEffect(precheck) {
        delay(PRECHECK_SETTLE_MS)
        settleTick++
    }
    val steadyPrecheck = remember(precheck, settleTick) { precheckHold.observe(precheck, SystemClock.uptimeMillis()) }
    val blocked = refusal ?: steadyPrecheck
    // An unreadable instance at first open is usually just the first read
    // still on its way: shown only once it holds, while nothing may go anyway.
    var steadyUnknown by remember(key) { mutableStateOf(false) }
    LaunchedEffect(binding == Binding.InstanceUnknown) {
        steadyUnknown = false
        if (binding == Binding.InstanceUnknown) {
            delay(INSTANCE_SETTLE_MS)
            steadyUnknown = true
        }
    }
    // The one gate both buttons, the + menu and the larger editor share.
    val busy = draftBusy
    // Files hold the draft back until each is uploaded for this very target.
    val attachReady = attachState == AttachmentsState.None || attachState == AttachmentsState.Ready
    val insertEnabled = !busy && draft.mayDeliver(binding) && attachReady && check?.insert?.ok == true
    // Insert is off only because the desktop isn't on this tab,
    // so Herdr's frame has no rows to read the box from (live_box's
    // not_visible). The + menu says so; the desktop is never moved for it.
    val insertOffDesktop = !insertEnabled && reading && desktop != key.pane && check?.insert?.reason == "not_visible"
    val sendEnabled = !busy && draft.mayDeliver(binding) && attachReady && !mismatched && (endpointReady || sendGate?.ok == true)
    // Endpoint steering accepts ready attachments; PTY steering stays text-only.
    val steerEnabled = !busy && draft.mayDeliver(binding) && attachReady && !mismatched &&
        draft.instance != null && (endpointSteer || draft.attachments.isEmpty() && check?.steer?.ok == true)
    val rebind = NoticeAction(stringResource(R.string.composer_rebind)) {
        save(draft.rebound(boot, check?.agent, readInstance))
    }
    // Move here: offered where moveHere would go through; for unlocked text,
    // which may already be in its pane, only after a confirmation (R5); onto a
    // held tab, refused in words, not by a missing button (R6).
    val sourceUnlocked = draft.sent?.state == SentState.Unlocked
    val moveOffer = moveHereOffer(busy, viewed?.let { store.get(key.copy(pane = it)) }, sourceUnlocked)
    val moveHereAction = when (moveOffer) {
        MoveHereOffer.Offered -> NoticeAction(stringResource(R.string.composer_move_here), tag = "composer:move_here") {
            viewed?.let(::moveHere)
        }
        MoveHereOffer.ConfirmUnlocked -> NoticeAction(stringResource(R.string.composer_move_here), tag = "composer:move_here") {
            moveAsk = viewed
        }
        MoveHereOffer.TargetHeld, MoveHereOffer.Hidden -> null
    }
    val checkDescription = stringResource(R.string.composer_check_description)
    // The agent's own input box already holds text (lab.3 D9), seen by a
    // check or by a steer whose paste never showed. Append inserts this draft
    // after it, without Enter; replacing it means clearing it in Terminal
    // first, since no remote clear exists that could not also reach an empty
    // OMP prompt (where Ctrl+C arms exit). Append is an Insert, so it shows
    // only while Insert is enabled; without it the notice names only the
    // clear.
    val remoteDraft = Notice(
        stringResource(if (insertEnabled) R.string.composer_remote_draft else R.string.composer_remote_draft_no_append),
        // The one action here that delivers the draft: filled.
        NoticeAction(stringResource(R.string.composer_append), tag = "composer:append", primary = true) { deliver(submit = false) }.takeIf { insertEnabled },
        NoticeAction(
            stringResource(R.string.composer_clear_in_terminal),
            description = stringResource(R.string.composer_clear_in_terminal_description),
            onClick = onOpenTerminal,
        ),
    )

    val panel = if (unlistedSteers) unlistedQueuePanel(probe, draft, draft.instance?.path) else queuePanel(probe)
    // Which single notice the slot shows, highest priority first.
    val notice = when {
        // Held while offline: the unconfirmed delivery stays said (R8).
        binding == Binding.Offline && holdsDraft -> Notice(
            stringResource(if (draft.unsettled) R.string.composer_offline_held else R.string.composer_offline),
        )
        pending != null -> Notice(stringResource(R.string.composer_pending), warning = false)
        steer.active -> Notice(
            stringResource(
                if ((steer as? SteerPhase.Entering)?.waiting == "steer_not_shown") R.string.steer_not_shown else R.string.steer_pending,
            ),
            warning = false,
        )
        // The paste is in the pane's input box and no Enter went.
        steer is SteerPhase.Left -> when ((steer as SteerPhase.Left).reason) {
            "steer_not_shown" -> remoteDraft
            "steer_expired" -> Notice(
                stringResource(R.string.steer_expired),
                NoticeAction(stringResource(R.string.composer_open_terminal), onClick = onOpenTerminal),
            )
            else -> Notice(
                stringResource(R.string.steer_left),
                NoticeAction(stringResource(R.string.composer_open_terminal), onClick = onOpenTerminal),
            )
        }
        // Sent through the endpoint: Check asks OMP for its record of the id.
        draft.sent?.state == SentState.Uncertain && ingressId(draft.sent) != null -> Notice(
            stringResource(if (checkUnknown) R.string.ingress_check_unknown else R.string.ingress_uncertain),
            NoticeAction(stringResource(R.string.composer_check), tag = "composer:ingress_check", description = stringResource(R.string.ingress_check_description)) {
                ingressCheck()
            }.takeIf { !checking },
            NoticeAction(stringResource(R.string.composer_unlock)) { unlockAsk = true },
        )
        // P1: in the pane's input box (a steer: in OMP's queue), not yet in the session's history.
        draft.sent?.state == SentState.InPane -> Notice(
            stringResource(inPaneNotice(draft.sent)),
            NoticeAction(stringResource(R.string.composer_check), description = checkDescription, onClick = onOpenTerminal),
            NoticeAction(stringResource(R.string.composer_unlock)) { unlockAsk = true },
            warning = inPaneWatched,
        )
        draft.sent?.state == SentState.Uncertain -> Notice(
            stringResource(R.string.composer_uncertain),
            NoticeAction(stringResource(R.string.composer_check), description = checkDescription, onClick = onOpenTerminal),
            // Asks first: the text may already be in the pane (lab.8.1 U).
            NoticeAction(stringResource(R.string.composer_unlock)) { unlockAsk = true },
        )
        binding == Binding.BootChanged -> Notice(stringResource(R.string.composer_restarted, draftTitle), rebind)
        binding == Binding.AgentChanged -> Notice(
            stringResource(R.string.composer_agent_changed, check?.agent ?: stringResource(R.string.composer_plain_terminal), draft.agent ?: stringResource(R.string.composer_plain_terminal)),
            rebind,
        )
        binding == Binding.AgentRestarted -> Notice(
            stringResource(R.string.composer_agent_restarted, check?.agent ?: draft.agent ?: ""),
            rebind,
        )
        // Written before this pane's session was read; Rebind binds it to the one read now.
        binding == Binding.InstanceUnknown && steadyUnknown -> Notice(
            stringResource(R.string.composer_instance_unknown),
            rebind.takeIf { draft.instance == null && readInstance != null },
        )
        // Onto a held tab the move is refused in words, not by a missing button (R6).
        mismatched && targetGone -> Notice(
            stringResource(if (moveOffer == MoveHereOffer.TargetHeld) R.string.composer_pane_gone_target_held else R.string.composer_pane_gone),
            moveHereAction,
        )
        mismatched -> Notice(
            stringResource(
                when {
                    moveOffer == MoveHereOffer.TargetHeld -> R.string.composer_draft_for_target_held
                    sourceUnlocked -> R.string.composer_draft_for_unlocked
                    else -> R.string.composer_draft_for
                },
                draftPath,
            ),
            NoticeAction(stringResource(R.string.composer_open_pane), tag = "composer:open_pane") { onOpenPane(key.pane) },
            moveHereAction,
        )
        // Released by Unlock: free to edit and send, but the first attempt
        // may have landed. Said until the user edits or dismisses it, or the
        // next delivery; it never sends anything. A safety notice, so it
        // wins over Send's own refusals (QA S-6a); Insert stays in the + menu.
        unlockedNoticeShown(draft) -> Notice(
            stringResource(R.string.composer_unlocked),
            NoticeAction(stringResource(R.string.composer_check), description = checkDescription, onClick = onOpenTerminal),
            NoticeAction(stringResource(R.string.composer_dismiss), tag = "composer:unlocked_dismiss") { save(acknowledged(draft)) },
        )
        // CT-1 S6: the pane read here isn't on the desktop, so the check
        // can't see its terminal. Insert keeps its own check; only the
        // labelled Show moves the desktop, and nothing is resent.
        blocked == "not_visible" && reading && desktop != key.pane -> Notice(
            stringResource(R.string.reason_target_unchecked, draftTitle),
            NoticeAction(stringResource(R.string.composer_insert_no_enter), tag = "composer:insert_no_enter") { deliver(submit = false) }.takeIf { insertEnabled },
            NoticeAction(stringResource(R.string.focus_reading_shared, draftTitle), tag = "composer:show_shared") { onShowShared(key.pane) },
        )
        // The endpoint queues text and images; the PTY wording (paste, then Enter) is not its.
        // Where Send says Queue, the help names both verbs.
        endpointSteer && steerEnabled && holdsDraft -> Notice(
            stringResource(if (queueVerb) R.string.steer_help_queue else R.string.steer_help_endpoint),
            NoticeAction(stringResource(R.string.composer_steer), tag = "composer:steer") { steerStart() },
        )
        blocked != null -> when (val code = blocked) {
            // Insert goes only where its own check passes; Send stays disabled.
            "agent_working" -> {
                val insert = NoticeAction(stringResource(R.string.composer_insert_no_enter), tag = "composer:insert_no_enter") { deliver(submit = false) }.takeIf { insertEnabled }
                // Steer is its own labelled action; Send stays disabled.
                val steerAction = NoticeAction(stringResource(R.string.composer_steer), tag = "composer:steer") { steerStart() }.takeIf { steerEnabled }
                // Staged images on an OMP pane need a usable control endpoint: that is why there is
                // no Steer, ahead of any other reason. Else, where there is no Steer, the reason the
                // card gives for it (§3.5), else the plain one.
                val noControl = steerAction == null && draft.attachments.isNotEmpty() && ompControlMissing(draft.agent, probe)
                val noSteer = controlReason(draft.agent, HarnessKey.SteerQueue, R.string.control_steer_unavailable).takeIf { steerAction == null && !noControl }
                Notice(
                    noSteer ?: stringResource(
                        when {
                            noControl -> R.string.omp_no_control
                            steerAction == null -> R.string.reason_agent_working
                            endpointSteer -> R.string.steer_help_endpoint
                            else -> R.string.steer_help
                        },
                    ),
                    insert ?: steerAction,
                    steerAction?.takeIf { insert != null },
                )
            }
            // The reason with the pane's actual rows; the offers are optional
            // and none of them lowers the 12-row rule (parent decision, lab.8).
            "too_short" -> {
                val tabZoomed = view?.panes?.firstOrNull { it.id == key.pane }
                    ?.let { p -> view?.tabs?.firstOrNull { it.id == p.tab } }?.zoomed
                val offered = offers(tabZoomed)
                val reason = shortPaneText(frame, key.pane)
                val actions = offered.mapNotNull { offer ->
                    when (offer) {
                        ShortPaneOffer.Zoom -> NoticeAction(stringResource(R.string.zoom_shared), tag = "composer:zoom", onClick = onZoom)
                        // The same field and draft stay; only the keyboard goes.
                        ShortPaneOffer.HideKeyboard -> NoticeAction(stringResource(R.string.composer_hide_keyboard), tag = "composer:hide_keyboard") {
                            keyboard?.hide()
                        }
                        ShortPaneOffer.Portrait -> null
                    }
                }
                Notice(
                    if (ShortPaneOffer.Portrait in offered) stringResource(R.string.composer_portrait_reaches_send, reason) else reason,
                    actions.getOrNull(0),
                    actions.getOrNull(1),
                )
            }
            // HG-VIS: the phone doesn't draw all of the target's box (OMP) or
            // its cursor row (any other pane). The draft stays as it is; only
            // the user shows more, never the app on its own.
            "editor_hidden", "target_not_visible" -> {
                val actions = hiddenOffers(imeVisible, viewport?.hiddenRows ?: 0).map { offer ->
                    when (offer) {
                        HiddenOffer.HideKeyboard -> NoticeAction(stringResource(R.string.composer_hide_keyboard), tag = "composer:hide_keyboard") {
                            keyboard?.hide()
                        }
                        HiddenOffer.BackToBottom -> NoticeAction(stringResource(R.string.composer_back_to_bottom), tag = "composer:back_to_bottom") { onOutputFocus(false) }
                    }
                }
                Notice(reasonText(code ?: ""), actions.getOrNull(0), actions.getOrNull(1))
            }
            "remote_draft" -> remoteDraft
            // UR4: each unreadable-box cause says what was measured. Only rows
            // measured under the box, or OMP's measured question (UR4b), send
            // the user to Terminal. Insert, Steer's start and the precheck all
            // refuse before anything is written, so the words say "Nothing
            // sent"; a refused steer Enter is SteerPhase.Left, never this.
            "editor_list_below", "editor_dialog_open" -> Notice(
                reasonText(code ?: ""),
                NoticeAction(stringResource(R.string.composer_open_terminal), tag = "composer:open_terminal", onClick = onOpenTerminal),
            )
            // RZ-u-2: Herdr lays the pane out for whichever client last typed
            // into or focused it; focusing it here gives it this phone's size,
            // and the checks then read the real box again. Only on a tap; in
            // Chat on a pane the desktop isn't on, the words say it moves the
            // desktop too (CT-1 §6, case M), and it is the notice's one move.
            "pane_sized_elsewhere" -> {
                val moves = reading && desktop != key.pane
                Notice(
                    reasonText(code ?: "") + " " + stringResource(if (moves) R.string.fit_pane_moves_desktop else R.string.fit_pane_crops_desktop),
                    NoticeAction(
                        stringResource(if (moves) R.string.composer_fit_pane_moves else R.string.composer_fit_pane),
                        tag = "composer:fit_pane",
                        description = stringResource(if (moves) R.string.composer_fit_pane_moves_spoken else R.string.composer_fit_pane_spoken, draftTitle),
                    ) { connection.focusPane(key.pane) },
                )
            }
            "popup_open", "not_visible", "scrolled" -> Notice(
                reasonText(code ?: ""),
                NoticeAction(stringResource(R.string.composer_open_terminal), onClick = onOpenTerminal),
            )
            else -> Notice(stringResource(causeRes(code ?: "", panel), (panel as? QueuePanel.Unusable)?.reason.orEmpty()), cause = code)
        }
        !attachReady -> Notice(stringResource(R.string.attachment_held), warning = attachState != AttachmentsState.Uploading)
        sentNoticeShown(draft.sent, value.text, noticeLive) -> draft.sent?.let { sent ->
            val reuse = NoticeAction(stringResource(R.string.composer_reuse)) {
                val text = sent.text
                value = TextFieldValue(text, TextRange(text.length))
                save(draft.copy(text = text, selectionStart = text.length, selectionEnd = text.length))
            }
            Notice(
                stringResource(sentNoticeText(sent), sentNoticeName(sent, draftTitle)),
                if (sentNoticeChecks(sent)) NoticeAction(stringResource(R.string.composer_check), description = checkDescription, onClick = onOpenTerminal) else reuse,
                reuse.takeIf { sentNoticeChecks(sent) },
                warning = false,
            )
        }
        else -> null
    } ?: when (silentSendHold(sendEnabled, holdsDraft, sending, draft.unsettled, resizingReason(check?.send?.takeIf { !it.ok }?.reason))) {
        // Never a silent dead Send: while a reason settles, or the tap is
        // being checked, the slot says so (lab.8 Chat report), and names a
        // resize the check sees right now (10018 RZ-text).
        SendHold.Checking -> Notice(stringResource(R.string.composer_checking), warning = false)
        SendHold.CheckingResizing -> Notice(stringResource(R.string.composer_checking_resizing), warning = false)
        SendHold.Unsettled -> Notice(reasonText("unsettled"))
        null -> null
    }

    // U6-WRONG-TAB: the panel speaks of the draft's pane (its probe, queue and
    // withdraw); while the draft is for a pane other than the one read, it
    // would read as this tab's state, so only the slot's Draft for notice
    // speaks. The probe keeps running for queueSettled.
    if (reading && !mismatched) {
        // An unusable endpoint the notice names is said there, once: the panel's line of the same cause goes.
        IngressQueuePanel(
            besideNotice(panel, noticeCause(refusal, steadyPrecheck, notice?.cause)),
            withdrawing,
            withdrawNote,
            ::withdraw,
            onPromote = if (agentNamed(draft.agent, Harness.queueAgents)) ::promote else null,
        )
    }

    // Compact (J2): the notice is the field's top line (ComposerBand); its
    // actions sit in the band. Side: its own unpadded block above the band.
    if (fit.noticeBlock) {
        notice?.let {
            NoticeRow(
                it,
                accent,
                padded = fit.noticePadded,
                lines = fit.noticeLines(LocalDensity.current.fontScale),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = fit.noticeGap.dp)
                    .testTag("composer:notice"),
            )
        }
    }

    val chips: @Composable (Modifier) -> Unit = { modifier ->
        AttachmentChips(
            attachments = draft.attachments,
            progress = progress,
            target = composerTarget(),
            host = connection.node.title,
            enabled = !busy,
            onRemove = { removeAttachment(it.id) },
            onRetry = { withConsent { upload(it.id) } },
            modifier = modifier,
        )
    }
    // Compact and Side have no room for the chip row (J2): the To-line
    // counts the files and the larger editor shows their chips (§2.6).
    if (fit.chips) chips(Modifier)
    ComposerBand(
        value = value,
        onValueChange = ::edit,
        accent = accent,
        title = draftTitle,
        destinationMismatch = mismatched,
        view = view,
        toPane = viewed,
        sendHint = stringResource(R.string.chat_send_route, draftTitle).takeIf { reading },
        agentStatus = check?.status,
        readOnly = busy,
        menu = menu,
        menuItems = menuItems,
        fit = fit,
        attachmentsLine = if (fit.chips) {
            null
        } else {
            attachmentsLine(draft.attachments, uploads.keys.toSet(), composerTarget())?.let { attachmentsLineText(it) }
        },
        noticeActions = if (fit.noticeBlock) emptyList() else listOfNotNull(notice?.first, notice?.second),
        inlineNotice = notice?.text?.takeIf { !fit.noticeBlock },
        onMenu = { menu = it },
        insertEnabled = insertEnabled,
        insertOffDesktop = insertOffDesktop,
        sendEnabled = sendEnabled,
        sendQueues = queueVerb,
        onInsert = { deliver(submit = false) },
        onSend = { deliver(submit = true) },
        onTerminalKeyboard = {
            menu = false
            onTerminalKeyboard()
        },
        onLargerEditor = {
            menu = false
            editorOpen = true
        },
        onFieldFocus = onFieldFocus,
        // In flight the draft is sending, not locked (QA HB2-w); held once it stays unconfirmed.
        locked = draft.unsettled && !waiting,
        inFlight = waiting,
        outputFocus = outputFocus,
        onOutputFocus = onOutputFocus,
        onAttachImage = { menu = false; withConsent { pickImages.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } },
        onAttachFile = { menu = false; withConsent { pickFiles.launch(arrayOf("*/*")) } },
        attachReason = when (attachBlocked) {
            null -> null
            AttachBlock.NotConnected -> stringResource(R.string.attach_not_connected)
            AttachBlock.Held -> stringResource(R.string.composer_locked_reason)
            AttachBlock.Sending -> stringResource(R.string.composer_sending)
            AttachBlock.Rebind -> stringResource(R.string.attach_confirm_pane)
            AttachBlock.NeedsAgent -> controlReason(draft.agent, HarnessKey.Media, R.string.control_attach_unavailable)
                ?: stringResource(R.string.attach_needs_agent)
            AttachBlock.Full -> stringResource(R.string.attach_full, MAX_ATTACHMENTS)
        },
    )

    consentAsk?.let { (node, action) ->
        ConfirmDialog(
            title = stringResource(R.string.attach_consent_title, node.title),
            text = stringResource(R.string.attach_consent_body, node.title),
            confirm = stringResource(R.string.attach_consent_ok, node.title),
            onConfirm = {
                consentAsk = null
                consent.grant(node)
                // The host may have changed under the dialog: then the action asks again.
                withConsent(action)
            },
            onDismiss = { consentAsk = null },
        )
    }

    if (unlockAsk) {
        ConfirmDialog(
            title = stringResource(R.string.composer_unlock),
            text = stringResource(R.string.composer_unlock_warning),
            confirm = stringResource(R.string.composer_unlock),
            onConfirm = {
                unlockAsk = false
                // Releases the pane only: the attempted text stays in the
                // editor, the record keeps that it may have landed, and the
                // next delivery is the user's own tap.
                connection.unlock(key.nodeId, key.session, key.pane)
                pending = null
                steer = SteerPhase.Idle
                save(unlocked(draft))
            },
            onDismiss = { unlockAsk = false },
        )
    }

    // Unlocked text may already be in its own pane: moving it here asks first (R5).
    moveAsk?.let { to ->
        ConfirmDialog(
            title = stringResource(R.string.composer_move_here),
            text = stringResource(R.string.composer_move_unlocked_warning, draftPath),
            confirm = stringResource(R.string.composer_move_here),
            onConfirm = {
                moveAsk = null
                moveHere(to)
            },
            onDismiss = { moveAsk = null },
        )
    }

    if (editorOpen) {
        LargerEditor(
            value = value,
            onValueChange = ::edit,
            accent = accent,
            title = draftTitle,
            agentStatus = check?.status,
            view = view,
            toPane = viewed,
            sendHint = stringResource(R.string.chat_send_route, draftTitle).takeIf { reading },
            readOnly = busy,
            sendEnabled = sendEnabled,
            sendQueues = queueVerb,
            insertEnabled = insertEnabled,
            onInsert = { deliver(submit = false) },
            onSend = { deliver(submit = true) },
            onFieldFocus = onFieldFocus,
            onDismiss = { editorOpen = false },
            locked = draft.unsettled && !waiting,
            inFlight = waiting,
            notice = notice,
            chips = chips,
        )
    }

    // A held source or target refuses the move: the dialog's Replace and
    // Append are never offered onto text that may already be in its pane.
    moveConflict?.takeIf { !moveRefused(busy, store.get(key.copy(pane = it))) }?.let { to ->
        val toTitle = view?.composerTitle(to) ?: to
        AlertDialog(
            onDismissRequest = { moveConflict = null },
            title = { Text(stringResource(R.string.composer_has_draft, toTitle)) },
            text = { Text(stringResource(R.string.composer_has_draft_body)) },
            confirmButton = {
                TextButton(onClick = {
                    moveConflict = null
                    moveHere(to, MoveResolution.Replace)
                }) { Text(stringResource(R.string.composer_replace)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        moveConflict = null
                        moveHere(to, MoveResolution.Append)
                    }) { Text(stringResource(R.string.composer_append)) }
                    TextButton(onClick = { moveConflict = null }) {
                        Text(stringResource(R.string.composer_cancel))
                    }
                }
            },
        )
    }
}

/** What Move here does when the target pane already holds a draft. */
internal enum class MoveResolution { Ask, Replace, Append }

/**
 * The inline band: + menu · field · Send. The field is one line when empty
 * (48 dp in a 64 dp band), grows to five lines, then scrolls inside. A
 * [compact][ComposerFit.Compact] fit drops the band's padding and keeps one line.
 */
@Composable
internal fun ComposerBand(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    accent: Color,
    /** The draft's pane title, for the placeholder and the field's spoken hint. */
    title: String,
    view: ShellView?,
    /** The pane the To-line names: the one on screen (CT-1: in Chat, the one read here). */
    toPane: String? = view?.focusedPane,
    /** Send's spoken hint, in Chat "Sends into ‹pane›'s terminal" (CT-1). */
    sendHint: String? = null,
    agentStatus: AgentStatus?,
    readOnly: Boolean,
    menu: Boolean,
    menuItems: List<ComposerAction>,
    fit: ComposerFit,
    /**
     * The draft's files as a fit without [chips][ComposerFit.chips] shows them
     * at the head of the To-line, or null for none.
     */
    attachmentsLine: String? = null,
    /** A compact notice's actions, 48 dp tall here instead of in their own row. */
    noticeActions: List<NoticeAction>,
    /** A compact notice's sentence, shown first in the field's top line. */
    inlineNotice: String?,
    onMenu: (Boolean) -> Unit,
    insertEnabled: Boolean,
    /** Insert is off because the desktop isn't on this tab: its menu item says so. */
    insertOffDesktop: Boolean = false,
    sendEnabled: Boolean,
    /** Send queues behind the running turn: the button says Queue (§1 S). */
    sendQueues: Boolean = false,
    onInsert: () -> Unit,
    onSend: () -> Unit,
    destinationMismatch: Boolean,
    onTerminalKeyboard: () -> Unit,
    onLargerEditor: () -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    /** The draft is held (unsettled): read-only, and shown so (R1). */
    locked: Boolean,
    /** A delivery is on its way: the To-line says so instead of the agent's status (R9). */
    inFlight: Boolean,
    /** The output-focus item's state, or null for none (J2 F). */
    outputFocus: Boolean?,
    onOutputFocus: (Boolean) -> Unit,
    /** UR3's pickers: the menu offers them only while [attachReason] is null. */
    onAttachImage: () -> Unit = {},
    onAttachFile: () -> Unit = {},
    /** Why files can't be attached now, shown under both attach items; null when they can. */
    attachReason: String? = null,
) {
    val lockedDescription = stringResource(R.string.composer_locked_description)
    val sendingDescription = stringResource(R.string.composer_sending_description)
    val hint = if (title.isEmpty()) stringResource(R.string.composer_message_empty) else stringResource(R.string.composer_message, title)
    val context = LocalContext.current
    val lines = value.text.count { it == '\n' } + 1
    val hasText = value.text.isNotBlank()
    // Radius only (M4): the band's heights, paddings and fill are ComposerFit's.
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = fit.bandMin.dp, max = fit.bandMax.dp)
            .clip(shape)
            // A held draft's field is read-only and looks it (R1).
            .background(if (locked) MaterialTheme.colorScheme.surfaceContainerLow else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f), shape)
            .padding(start = 4.dp, end = 4.dp, top = fit.bandTop.dp, bottom = fit.band.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box {
            Box(
                Modifier
                    .size(48.dp)
                    .clickable(role = Role.Button, onClick = { onMenu(true) })
                    .testTag("composer:plus"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Default.Add, stringResource(R.string.composer_menu), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { onMenu(false) }) {
                // J2 F's item sits after the larger editor (or before the
                // terminal keyboard, or last); it is not a Controls entry.
                val focusAt = if (outputFocus != null) outputFocusSlot(menuItems) else -1
                val focusItem = @Composable {
                    if (outputFocus != null) {
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(if (outputFocus) R.string.output_focus_off else R.string.output_focus_on))
                                    Text(
                                        stringResource(R.string.output_focus_help),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            onClick = { onMenu(false); onOutputFocus(!outputFocus) },
                            modifier = Modifier.testTag("composer:output_focus"),
                        )
                    }
                }
                // UR3's pickers lead the menu, also while they can't be used:
                // then each names why instead of its help.
                val attachItem = @Composable { title: Int, help: Int, tag: String, onClick: () -> Unit ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(stringResource(title))
                                Text(
                                    attachReason ?: stringResource(help),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (attachReason == null) {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    } else {
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                                    },
                                )
                            }
                        },
                        enabled = attachReason == null,
                        onClick = onClick,
                        modifier = Modifier.testTag(tag),
                    )
                }
                attachItem(R.string.attachment_attach_image, R.string.attach_image_help, "composer:attach_image", onAttachImage)
                attachItem(R.string.attachment_attach_file, R.string.attach_file_help, "composer:attach_file", onAttachFile)
                menuItems.forEachIndexed { index, action ->
                    if (index == focusAt) focusItem()
                    when (action) {
                        ComposerAction.Insert -> DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(R.string.composer_insert_no_enter))
                                    // Held: the reason instead of the help; off while the
                                    // desktop is on another tab: that reason;
                                    // otherwise a disabled item's help is dimmed with its title (R4).
                                    val offDesktop = !insertEnabled && !locked && insertOffDesktop
                                    Text(
                                        stringResource(
                                            when {
                                                locked -> R.string.composer_locked_reason
                                                offDesktop -> R.string.composer_insert_off_desktop
                                                else -> R.string.composer_insert_no_enter_help
                                            },
                                        ),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (insertEnabled || locked || offDesktop) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
                                        },
                                    )
                                }
                            },
                            enabled = insertEnabled,
                            onClick = { onMenu(false); onInsert() },
                            modifier = Modifier.testTag("composer:insert"),
                        )
                        ComposerAction.LargerEditor -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.composer_larger_editor)) },
                            onClick = { onMenu(false); onLargerEditor() },
                        )
                        ComposerAction.TerminalKeyboard -> DropdownMenuItem(
                            text = { Text(stringResource(R.string.composer_terminal_keyboard)) },
                            onClick = onTerminalKeyboard,
                        )
                        // The editor's own paste: into the local draft at the
                        // selection, never to the pane; off while it is held.
                        ComposerAction.Paste -> DropdownMenuItem(
                            text = {
                                Column {
                                    Text(stringResource(R.string.composer_paste_clipboard))
                                    if (locked) {
                                        Text(
                                            stringResource(R.string.composer_locked_reason),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            enabled = !readOnly,
                            onClick = {
                                onMenu(false)
                                pasteInto(value, clipboardText(context), readOnly)?.let(onValueChange)
                            },
                        )
                    }
                }
                if (focusAt == menuItems.size) focusItem()
            }
        }
        // The field's inner width, which the To-line is fitted to by display columns (U1-1).
        var fieldPx by remember { mutableIntStateOf(0) }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            readOnly = readOnly,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(accent),
            // Enter writes a newline here; only the buttons deliver.
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.Sentences,
                autoCorrectEnabled = true,
                keyboardType = KeyboardType.Text,
            ),
            minLines = 1,
            maxLines = fit.maxLines,
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .padding(horizontal = 6.dp)
                .onFieldFocus(onFieldFocus)
                .fieldState(locked, inFlight, lockedDescription, sendingDescription, hint)
                .onSizeChanged { fieldPx = it.width }
                .testTag("composer:field"),
            decorationBox = { inner ->
                // Two rows in every state (UR2, design 528f5b8 and 4bced42): the
                // To-line slot, then the placeholder or the typed text. Regular
                // holds the text row on the + and Send axis, so extra lines
                // and spare height go above; Compact centres the pair.
                val toLineDp = with(LocalDensity.current) { fit.toLine.sp.toDp() }
                val textRowDp = with(LocalDensity.current) { ComposerFit.FIELD_LINE.sp.toDp() }
                val axisGap = fit.axisGap(LocalDensity.current.fontScale).dp
                val expand = lines >= 2 && !fit.compact
                Box(contentAlignment = if (fit.onAxis) Alignment.BottomStart else Alignment.CenterStart) {
                    Column(Modifier.padding(top = fit.field.dp, bottom = fit.field.dp + axisGap)) {
                        // The in-field To-line names the draft's own pane; while
                        // the user views another pane, the "Draft for …" strip
                        // alone names a destination, so this line hides (lab.3 D2).
                        // Compact: the notice leads this line; the To-line takes
                        // what is left and ellipsizes first. The slot keeps its
                        // 16 sp line when empty or hidden, so the placeholder and
                        // the typed text share one baseline and nothing moves on
                        // the first keystroke or the last delete.
                        val to = !destinationMismatch
                        // A held draft says it is locked where the To-line goes (R1).
                        val lockLine = locked && hasText
                        // Where no chip row shows, the files' state leads the line,
                        // also while the strip names another destination: it is
                        // what a Send held for attachments waits on.
                        val toFont = if (fit.compact) 12.sp else 11.sp
                        val toEnd = with(LocalDensity.current) { (if (expand) EXPAND_TARGET.dp else 0.dp).roundToPx() }
                        // What leads the line on it takes its own columns and a separator first.
                        val ahead = listOfNotNull(attachmentsLine, inlineNotice).sumOf { columns(it) + DESTINATION_SEPARATOR.length }
                        val toColumns = columnsIn(fieldPx - toEnd, toFont) - ahead
                        val label = if (to && !lockLine) toLabel(view, toPane, agentStatus, inFlight, toColumns) else null
                        val spoken = label?.let { destinationDescription(it) }
                        val line = listOfNotNull(
                            attachmentsLine,
                            when {
                                lockLine -> stringResource(R.string.composer_locked_line)
                                to -> label?.shown
                                else -> null
                            },
                        ).joinToString(" · ").ifEmpty { null }
                        val lineSpoken = spoken?.let { listOfNotNull(attachmentsLine, it).joinToString(" · ") }
                        Row(
                            Modifier
                                .height(toLineDp)
                                .padding(end = if (expand) EXPAND_TARGET.dp else 0.dp)
                                .testTag("composer:to_row"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            inlineNotice?.let {
                                Text(
                                    if (line != null) "$it · " else it,
                                    fontSize = 12.sp,
                                    lineHeight = fit.toLine.sp,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.testTag("composer:notice"),
                                )
                            }
                            if (line != null) {
                                if (lockLine) LockGlyph()
                                Text(
                                    line,
                                    fontSize = toFont,
                                    lineHeight = fit.toLine.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .weight(1f, fill = inlineNotice == null)
                                        .then(if (lineSpoken != null) Modifier.semantics { contentDescription = lineSpoken } else Modifier),
                                )
                            }
                        }
                        Box(Modifier.heightIn(min = textRowDp).testTag("composer:text_row")) {
                            if (!hasText) {
                                // While the To-line names the pane, the placeholder doesn't
                                // repeat it; the field's spoken hint always does.
                                val rowBlank = !(to || lockLine) && inlineNotice == null
                                Text(
                                    if (rowBlank) hint else stringResource(R.string.composer_message_empty),
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.clearAndSetSemantics {},
                                )
                            }
                            inner()
                        }
                    }
                    // The larger editor's 48 dp target lies over the field's top
                    // end, outside both rows' layout, so it sizes neither; its
                    // glyph sits in the To-line row, which leaves it room. The
                    // field is at least two lines tall whenever it shows.
                    if (expand) {
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .size(EXPAND_TARGET.dp)
                                .clickable(role = Role.Button, onClick = onLargerEditor)
                                .testTag("composer:expand"),
                            contentAlignment = Alignment.TopEnd,
                        ) {
                            Icon(
                                Icons.Default.KeyboardArrowUp,
                                stringResource(R.string.composer_larger_editor),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = fit.field.dp).size(toLineDp),
                            )
                        }
                    }
                }
            },
        )
        noticeActions.forEach { a ->
            TextButton(
                onClick = a.onClick,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .then(a.tag?.let { Modifier.testTag(it) } ?: Modifier),
            ) { Text(a.label, maxLines = 1) }
        }
        // The only primary action: Send, once the native check allows.
        Box(
            Modifier
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                // The accessibility node must say what the visuals say: a
                // greyed Send is disabled even though the tap target stays
                // clickable so the precheck notice explains the refusal
                // (lab.3 D5).
                .semantics { if (!sendEnabled) disabled() }
                .clickable(enabled = sendEnabled, onClickLabel = sendHint, role = Role.Button, onClick = onSend)
                .testTag("composer:send"),
            contentAlignment = Alignment.Center,
        ) {
            SendFace(sendEnabled, accent, sendQueues)
        }
    }
}

/**
 * The To-line for [pane] fitted to [maxColumns] (U1-1): who among its
 * workspace's panes, its state and the workspace; null for a pane not in view.
 * While a delivery is [inFlight] the state says so: the agent's own status
 * beside it would read as nothing happening.
 */
@Composable
private fun toLabel(view: ShellView?, pane: String?, status: AgentStatus?, inFlight: Boolean, maxColumns: Int): DestinationLabel? {
    pane ?: return null
    val state = if (inFlight) stringResource(R.string.composer_sending) else status?.let { statusLabel(it) }
    return view?.destinationOf(pane, state, maxColumns.coerceAtLeast(1))
}

/** The To-line spoken uncut: pane, workspace and state, whatever the room cut from the line. */
@Composable
private fun destinationDescription(label: DestinationLabel): String = label.state?.let {
    stringResource(R.string.composer_destination_description, label.who, label.workspace, it)
} ?: stringResource(R.string.composer_destination_description_nostate, label.who, label.workspace)

/** How many digit-wide display columns fit [widthPx] at [fontSize]. */
@Composable
private fun columnsIn(widthPx: Int, fontSize: TextUnit): Int {
    val measurer = rememberTextMeasurer()
    val advance = remember(measurer, fontSize) { measurer.measure("0", TextStyle(fontSize = fontSize)).size.width }
    return if (advance <= 0 || widthPx <= 0) 1 else widthPx / advance
}

/** The 16 dp lock before a held draft's line (R1); the line itself says why. */
@Composable
private fun LockGlyph() {
    Icon(
        Icons.Default.Lock,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(end = 4.dp).size(16.dp),
    )
}

/** Material's disabled content alpha. */
private const val DISABLED_ALPHA = 0.38f

/**
 * One notice in the single slot above the field: one sentence, ≤2 actions.
 * [warning] is false for a confirmation or a step in progress, which says
 * nothing is wrong. [cause] is the refusal code a plain reason notice says.
 */
private data class Notice(
    val text: String,
    val first: NoticeAction? = null,
    val second: NoticeAction? = null,
    val warning: Boolean = true,
    val cause: String? = null,
)

/**
 * [primary] marks the action that delivers the draft (filled); the rest are
 * outlined. [description] replaces [label] for TalkBack when the label alone
 * would not say where the action leads.
 */
internal data class NoticeAction(
    val label: String,
    val tag: String? = null,
    val primary: Boolean = false,
    val description: String? = null,
    val onClick: () -> Unit,
)

/**
 * The regular notice block: its text, then its actions inline at the end
 * while the text fits one line beside them, or on their own row once it wraps.
 * A [Notice.warning] notice sits on the warning background; the rest keep the
 * same layout without it, so the budget is the same either way. Un[padded]
 * (the side column's [ComposerFit.Side]) it is always stacked, its text at
 * most [lines] (the larger editor shows it whole), and its actions
 * share the column's width, however narrow, each still 48 dp tall.
 */
@Composable
private fun NoticeRow(notice: Notice, accent: Color, modifier: Modifier = Modifier, padded: Boolean = true, lines: Int = NOTICE_LINES) {
    val colors = chatColors(accent)
    val actions = listOfNotNull(notice.first, notice.second)
    // Once the text wrapped beside the actions it stays stacked, so the layout never flips back and forth.
    var wrapped by remember(notice.text) { mutableStateOf(false) }
    val text = @Composable { textModifier: Modifier ->
        Text(
            notice.text,
            style = MaterialTheme.typography.bodySmall,
            color = if (notice.warning) colors.blocked else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (padded) Int.MAX_VALUE else lines,
            overflow = if (padded) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = textModifier,
            onTextLayout = { if (!wrapped && actions.isNotEmpty() && it.lineCount > 1) wrapped = true },
        )
    }
    // A confirmation or a step in progress keeps the neutral style; the
    // warning block is only for why nothing may go.
    val block = (if (notice.warning) modifier.background(colors.warnBg, MaterialTheme.shapes.medium) else modifier)
        .padding(
            start = NOTICE_PAD_SIDE.dp,
            end = NOTICE_PAD_SIDE.dp,
            top = if (padded) NOTICE_PAD_TOP.dp else 0.dp,
            bottom = if (!padded) 0.dp else if (actions.isEmpty()) NOTICE_PAD_TOP.dp else NOTICE_PAD_UNDER_ACTIONS.dp,
        )
    if (wrapped || !padded) {
        Column(block, verticalArrangement = Arrangement.spacedBy(NOTICE_GAP.dp)) {
            text(Modifier)
            if (actions.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    actions.forEach { NoticeButton(it, colors, if (padded) Modifier else Modifier.weight(1f, fill = false)) }
                }
            }
        }
    } else {
        Row(block, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            text(Modifier.weight(1f))
            actions.forEach { NoticeButton(it, colors) }
        }
    }
}

/**
 * One notice action: a Material button, 40 dp drawn in its 48 dp target;
 * filled when it delivers the draft, outlined otherwise, both in the
 * accent's text-safe variant. Its label ellipsizes when [modifier] narrows it.
 */
@Composable
private fun NoticeButton(action: NoticeAction, colors: ChatColors, modifier: Modifier = Modifier) {
    val target = modifier
        .then(action.tag?.let { Modifier.testTag(it) } ?: Modifier)
        .then(action.description?.let { d -> Modifier.semantics { contentDescription = d } } ?: Modifier)
    val label = @Composable { Text(action.label, style = ChatType.button, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    if (action.primary) {
        // accentText, not the raw accent: under white the NodeColors are
        // 2.9–3.3:1 (Teal 3.24), while their accentText (55% towards black in
        // light, white in dark) is over 10:1 under white or near-black ink.
        val dark = colors.bg.luminance() < 0.5f
        Button(
            onClick = action.onClick,
            modifier = target,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accentText,
                contentColor = if (dark) Color(0xFF10151C) else Color.White,
            ),
        ) { label() }
    } else {
        OutlinedButton(
            onClick = action.onClick,
            modifier = target,
            // The raw accent is under 3:1 on the light warning block (Teal 2.85).
            border = BorderStroke(1.dp, colors.accentText),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.accentText),
        ) { label() }
    }
}

/**
 * The larger editor: an explicit full-height level over the session. Back or
 * ✕ returns with the draft intact; the field is the same state, never a copy.
 */
@Composable
private fun LargerEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    accent: Color,
    /** The draft's pane title, for the field's spoken hint. */
    title: String,
    view: ShellView?,
    toPane: String? = view?.focusedPane,
    sendHint: String? = null,
    agentStatus: AgentStatus?,
    readOnly: Boolean,
    sendEnabled: Boolean,
    sendQueues: Boolean,
    insertEnabled: Boolean,
    onInsert: () -> Unit,
    onSend: () -> Unit,
    onFieldFocus: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    /** The draft is held (unsettled): read-only, and shown so (R1). */
    locked: Boolean,
    /** A delivery is on its way: the To-line says so instead of the agent's status (R9). */
    inFlight: Boolean,
    notice: Notice?,
    /** The draft's attachment chips, shown above the field in every fit. */
    chips: @Composable (Modifier) -> Unit,
) {
    val lockedDescription = stringResource(R.string.composer_locked_description)
    val sendingDescription = stringResource(R.string.composer_sending_description)
    val hint = if (title.isEmpty()) stringResource(R.string.composer_message_empty) else stringResource(R.string.composer_message, title)
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("composer:editor"),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding(),
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .padding(horizontal = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .clickable(role = Role.Button, onClick = onDismiss)
                            .testTag("composer:editor_close"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Close, stringResource(R.string.composer_close))
                    }
                    var titlePx by remember { mutableIntStateOf(0) }
                    Column(Modifier.weight(1f).padding(horizontal = 4.dp).onSizeChanged { titlePx = it.width }) {
                        Text(stringResource(R.string.composer_draft), style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (locked) LockGlyph()
                            val label = if (locked) null else toLabel(view, toPane, agentStatus, inFlight, columnsIn(titlePx, MaterialTheme.typography.labelSmall.fontSize))
                            val spoken = label?.let { destinationDescription(it) }
                            Text(
                                if (locked) stringResource(R.string.composer_locked_line) else label?.shown.orEmpty(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier,
                            )
                        }
                    }
                    SendButton(sendEnabled, accent, sendHint, sendQueues, onSend)
                }
                chips(Modifier.padding(horizontal = 16.dp))
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    readOnly = readOnly,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        autoCorrectEnabled = true,
                        keyboardType = KeyboardType.Text,
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .onFieldFocus(onFieldFocus)
                        .fieldState(locked, inFlight, lockedDescription, sendingDescription, hint)
                        .testTag("composer:field"),
                )
                // The same notice as the inline composer: a held draft says so
                // here too, with Check and Unlock (lab.8.1 R2). An action
                // closes the editor first, so what it opens is not behind it.
                notice?.let { n ->
                    fun closing(a: NoticeAction?) = a?.copy(onClick = { onDismiss(); a.onClick() })
                    NoticeRow(
                        n.copy(first = closing(n.first), second = closing(n.second)),
                        accent,
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag("composer:editor_notice"),
                    )
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Disabled as the + menu draws it, and while held with its reason (R2).
                    Column(
                        Modifier
                            .clip(MaterialTheme.shapes.medium)
                            .clickable(enabled = insertEnabled, role = Role.Button, onClick = onInsert)
                            .padding(horizontal = 14.dp, vertical = 8.dp)
                            .testTag("composer:insert"),
                    ) {
                        Text(
                            stringResource(R.string.composer_insert_no_enter),
                            style = MaterialTheme.typography.labelLarge,
                            color = if (insertEnabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA),
                        )
                        if (locked) {
                            Text(
                                stringResource(R.string.composer_locked_reason),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Box(Modifier.weight(1f))
                    // The only counter anywhere in the composer.
                    val lines = value.text.count { it == '\n' } + 1
                    Text(
                        "$lines · ${value.text.length}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The round 40 dp Send inside a 48 dp target. */
@Composable
private fun SendButton(enabled: Boolean, accent: Color, hint: String?, queues: Boolean, onSend: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clickable(enabled = enabled, onClickLabel = hint, role = Role.Button, onClick = onSend)
            .testTag("composer:send"),
        contentAlignment = Alignment.Center,
    ) {
        SendFace(enabled, accent, queues)
    }
}

/**
 * Send's face: the round arrow, or the word Queue while a send waits for
 * the running turn, so the verb is seen before the tap (§1 S).
 */
@Composable
private fun SendFace(enabled: Boolean, accent: Color, queues: Boolean) {
    val fill = if (enabled) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val ink = if (enabled) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
    if (queues) {
        Box(
            Modifier
                .height(40.dp)
                .background(fill, CircleShape)
                .padding(horizontal = 14.dp)
                .testTag("composer:queue"),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.composer_queue), color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    } else {
        Box(Modifier.size(40.dp).background(fill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Send, stringResource(R.string.composer_send), tint = ink, modifier = Modifier.size(20.dp))
        }
    }
}

/** The target's pane height in rows, when this client's frame shows it. */
private fun paneRows(frame: RenderFrame?, pane: String): Int? =
    frame?.panes?.firstOrNull { it.id == pane }?.rect?.height

/** "Pane is N rows on Herdr: Send needs 12", or the version without N. */
@Composable
private fun shortPaneText(frame: RenderFrame?, pane: String): String =
    paneRows(frame, pane)?.let { stringResource(R.string.composer_pane_rows, it) }
        ?: stringResource(R.string.composer_pane_rows_unknown)

/**
 * QF-6c, QF9-N: the focused pane has no row of its own ([paneHasNoRoom]),
 * so the terminal draws only Herdr's border. An overlay at the terminal's
 * top: it changes no height and resizes nothing. Its words stay the same
 * while it shows, so its polite live region speaks once per appearance. Zoom
 * (shared) and Hide keyboard are the user's taps only, offered as
 * [shortPaneOffers] says; the notice takes no focus, and taps on its own
 * bounds stop there, never reaching the terminal under it.
 */
@Composable
internal fun PaneNoRoomNotice(offers: List<ShortPaneOffer>, accent: Color, onZoom: () -> Unit, onHideKeyboard: () -> Unit, modifier: Modifier = Modifier) {
    val reason = stringResource(R.string.terminal_pane_no_room)
    val actions = offers.mapNotNull { offer ->
        when (offer) {
            ShortPaneOffer.Zoom -> NoticeAction(stringResource(R.string.zoom_shared), tag = "terminal:no_room:zoom", onClick = onZoom)
            ShortPaneOffer.HideKeyboard -> NoticeAction(stringResource(R.string.composer_hide_keyboard), tag = "terminal:no_room:hide_keyboard", onClick = onHideKeyboard)
            ShortPaneOffer.Portrait -> null
        }
    }
    NoticeRow(
        Notice(
            if (ShortPaneOffer.Portrait in offers) stringResource(R.string.terminal_pane_no_room_portrait, reason) else reason,
            actions.getOrNull(0),
            actions.getOrNull(1),
        ),
        accent,
        modifier
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() } }
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("terminal:no_room"),
    )
}

/** The refusal code's words, as the native core's own mapping. */
@Composable
private fun reasonText(reason: String): String = stringResource(reasonRes(reason))

/**
 * The string for a refusal [reason] code. A code this build doesn't know
 * says only that nothing was sent, never a cause it can't vouch for: an
 * unknown `editor_*` code reads as the unreadable box, any other as the
 * neutral refusal, and only `not_connected` says the link is down (UR4).
 */
@StringRes
internal fun reasonRes(reason: String): Int =
    when (reason) {
        "not_connected" -> R.string.reason_not_connected
        "boot_changed" -> R.string.reason_boot_changed
        "pane_missing" -> R.string.reason_pane_missing
        "agent_changed" -> R.string.reason_agent_changed
        "popup_open" -> R.string.reason_popup_open
        "agent_blocked" -> R.string.reason_agent_blocked
        "agent_working" -> R.string.reason_agent_working
        "unsupported_program" -> R.string.reason_unsupported_program
        "not_visible" -> R.string.reason_not_visible
        "scrolled" -> R.string.reason_scrolled
        "remote_draft" -> R.string.reason_remote_draft
        "editor_list_below" -> R.string.reason_editor_list_below
        "editor_dialog_open" -> R.string.reason_editor_dialog_open
        "editor_covered" -> R.string.reason_editor_covered
        "editor_top_missing" -> R.string.reason_editor_top_missing
        "editor_resizing" -> R.string.reason_editor_resizing
        "pane_sized_elsewhere" -> R.string.reason_pane_sized_elsewhere
        "editor_none" -> R.string.reason_editor_none
        "editor_unrecognized" -> R.string.reason_editor_unrecognized
        "editor_caret_unseen" -> R.string.reason_editor_caret_unseen
        "editor_hidden" -> R.string.reason_editor_hidden
        "target_not_visible" -> R.string.reason_target_not_visible
        "resizing" -> R.string.reason_resizing
        "empty_text" -> R.string.reason_empty_text
        "too_large" -> R.string.reason_too_large
        "no_receipt" -> R.string.reason_no_receipt
        "unsettled", MOVE_UNSETTLED -> R.string.reason_unsettled
        MOVE_TARGET_HELD -> R.string.reason_move_target_held
        "agent_restarted" -> R.string.reason_agent_restarted
        "instance_unknown" -> R.string.reason_instance_unknown
        "agent_idle" -> R.string.agent_idle
        "steer_expired" -> R.string.steer_expired
        "needs_server" -> R.string.reason_needs_server
        "server_discovery" -> R.string.reason_server_discovery
        "steer_not_shown" -> R.string.steer_not_shown
        WRITE_AHEAD_FAILED -> R.string.reason_write_ahead_failed
        "attachment_target" -> R.string.reason_attachment_target
        "attachment_needs_agent" -> R.string.reason_attachment_needs_agent
        "attachment_steer" -> R.string.reason_attachment_steer
        "attachment_missing" -> R.string.reason_attachment_missing
        "attachment_too_many" -> R.string.reason_attachment_too_many
        "attachment_too_large" -> R.string.reason_attachment_too_large
        "attachment_unsupported" -> R.string.reason_attachment_unsupported
        "unconfirmed" -> R.string.composer_uncertain
        // Chat's OMP endpoint (ChatIngress.kt, host/omp/herdline-ingress.ts).
        "instance_changed" -> R.string.reason_agent_restarted
        "dialog_open" -> R.string.reason_dialog_open
        "collab" -> R.string.reason_collab
        "collab_state_unknown" -> R.string.reason_collab_state_unknown
        "unsupported_type" -> R.string.reason_unsupported_type
        "empty" -> R.string.reason_empty
        "dropped" -> R.string.reason_dropped
        REASON_UNSUPPORTED -> R.string.reason_endpoint_unsupported
        REASON_UNUSABLE -> R.string.reason_endpoint_unusable
        else -> if (reason.startsWith("editor_")) R.string.reason_editor_unrecognized else R.string.reason_unknown
    }

/** The clipboard's text, read locally for the composer's own paste. */
private fun clipboardText(context: Context): String {
    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip ?: return ""
    return (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
}

/** Reports the field's keyboard focus so the terminal can keep its grid. */
private fun Modifier.onFieldFocus(report: (Boolean) -> Unit): Modifier =
    this.onFocusChanged { report(it.isFocused) }

/**
 * The field's spoken state: sending while in flight, locked once held
 * unconfirmed, otherwise the [hint] naming the pane it writes to.
 */
private fun Modifier.fieldState(locked: Boolean, inFlight: Boolean, lockedDescription: String, sendingDescription: String, hint: String): Modifier =
    semantics {
        contentDescription = when {
            inFlight -> sendingDescription
            locked -> lockedDescription
            else -> hint
        }
    }

/** The larger-editor arrow's touch target, in dp. */
private const val EXPAND_TARGET = 48

private const val SENT_NOTICE_MS = 8_000L
/** How long a [SentState.Handed] notice offers Reuse, from the stored answer. */
private const val HANDED_NOTICE_MS = 60_000L
private const val PRECHECK_SETTLE_MS = 700L
private const val INSTANCE_POLL_MS = 5_000L
private const val INSTANCE_SETTLE_MS = 1_500L
/** How long an InPane send (P1) is watched for its turn in the history, from the receipt. */
private const val PTY_TURN_WATCH_MS = 20_000L
private const val PTY_TURN_POLL_MS = 1_000L

/** Answers kept while their delivery is still returning from the native core. */
private const val EARLY_ANSWERS = 8

/** How often an Enter waiting on the pane checks its window when no frame comes. */
private const val STEER_TICK_MS = 100L
