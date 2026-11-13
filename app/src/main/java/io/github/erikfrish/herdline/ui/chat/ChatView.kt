package io.github.erikfrish.herdline.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.Harness
import io.github.erikfrish.herdline.core.HarnessRuntime
import io.github.erikfrish.herdline.core.ServerProbe
import io.github.erikfrish.herdline.core.HarnessKey
import io.github.erikfrish.herdline.core.StopRoute
import io.github.erikfrish.herdline.core.StopRefused
import androidx.annotation.StringRes
import io.github.erikfrish.herdline.core.ChatDialog
import io.github.erikfrish.herdline.core.DIALOG_ANSWERED_ELSEWHERE
import io.github.erikfrish.herdline.core.IngressProbe
import io.github.erikfrish.herdline.core.IngressChildren
import io.github.erikfrish.herdline.core.IngressSubagent
import io.github.erikfrish.herdline.core.MarkerKind
import io.github.erikfrish.herdline.core.visibleDialogs
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptRead
import io.github.erikfrish.herdline.core.TranscriptSteer
import io.github.erikfrish.herdline.core.mediaRequest
import io.github.erikfrish.herdline.core.transcriptRequest
import io.github.erikfrish.herdline.core.TranscriptRefusal
import io.github.erikfrish.herdline.core.TranscriptRefused
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.session.Outage
import io.github.erikfrish.herdline.ui.session.controlReason
import io.github.erikfrish.herdline.ui.session.ompControlMissing
import io.github.erikfrish.herdline.ui.session.outageTime
import io.github.erikfrish.herdline.ui.session.rememberOutage
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** How often chat rereads the transcript while the agent is busy or waiting, and otherwise. */
private const val BUSY_POLL_MS = 1_500L
private const val IDLE_POLL_MS = 4_000L
private const val ERROR_POLL_MS = 5_000L

/**
 * The widest a line of prose grows in landscape and on a tablet: about 80
 * characters of body text. Prose and the user bubble's row take it, so both
 * end on one edge; code, tables and tool output keep the list's full width
 * and scroll sideways. Phones in portrait are narrower than the cap.
 */
internal val ProseMaxWidth = 560.dp

/** The widest a user bubble grows: 85 % of the column, at most this. */
private val BubbleMaxWidth = 560.dp

private val PillHeight = 36.dp

/** Under and over the Latest pill in a [dense][ChatView] list. */
private val DensePillGap = 2.dp

/** The dense pill: one touch target, at the list's end edge. */
private val CompactPillSize = 48.dp
private val DensePillEnd = 4.dp

/**
 * The OMP agent in [pane] as a conversation, read from the session file
 * Herdr names for that pane. The agent keeps running in its terminal; this
 * only reads. Interactive dialogs are answered in the terminal: a blocked
 * agent shows a card that opens it, never buttons that pretend to answer.
 *
 * What it reads changes only this pane's own [ChatKept], never the mode
 * shown (C9): a pane it cannot read stays in Chat on its own page, loading,
 * empty, a read error with Retry or a typed refusal, and a refresh that
 * fails after a transcript loaded keeps it under the stale strip.
 *
 * [dense] drops the list's own top and bottom padding, for a window that
 * leaves the conversation only a few lines (a landscape phone with the
 * keyboard up, lab.7.3 J2).
 */
@Composable
fun ChatView(
    connection: Connection,
    pane: String,
    accent: Color,
    onTerminal: () -> Unit,
    modifier: Modifier = Modifier,
    tabTitle: String? = null,
    dense: Boolean = false,
    /** The caller's memory for one connection and daemon boot, so each pane read here comes back as it was (CT-1). */
    memory: ChatMemory,
) {
    // A reconnect is a new Connection and starts over, as a restarted daemon is a new
    // memory (the caller's is keyed on both). A new OMP instance in the pane resets what it
    // loaded (ChatHistory.merge).
    key(connection, pane, memory) {
        ChatContent(connection, pane, memory.of(pane), accent, onTerminal, modifier, tabTitle, dense)
    }
}

/**
 * What each pane's Chat loaded and the reader opened, kept while another
 * pane is read. The caller makes one per connection and daemon boot, since
 * a restarted daemon may reuse pane ids, and a read's outcome stays in the
 * memory it was made through.
 */
class ChatMemory {
    private val panes = HashMap<String, ChatKept>()
    internal fun of(pane: String): ChatKept = panes.getOrPut(pane) { ChatKept() }

    /** Whether [pane] holds history read through this memory. */
    internal fun holds(pane: String): Boolean = panes[pane]?.history != null
}

/**
 * The [ChatMemory] of one connection's current daemon boot: a new boot
 * starts a new one, since a restarted daemon may reuse pane ids, and a
 * read's outcome is the memory's it was made through. A null boot (no view
 * yet, or none for a moment) keeps the current memory.
 */
class ChatMemories {
    private var boot: String? = null
    private var memory = ChatMemory()

    fun at(boot: String?): ChatMemory {
        if (boot != null && boot != this.boot) {
            if (this.boot != null) memory = ChatMemory()
            this.boot = boot
        }
        return memory
    }
}

/** Programs Chat reaches through their own server, whose absence the card names (card §3.4). */
private val SERVER_AGENTS = setOf("opencode", "kilo")

/**
 * The card's runtime row for [pane]: what the native core says of its
 * OpenCode or Kilo server (Connection.ingressNeedsServer). Unknown until it
 * answers and after a failed probe. Asked again whenever the link, the boot,
 * the pane's agent or its instance changes, never polled. When Chat has not
 * read the pane yet, one history read names the instance first. Grok's
 * ambiguous-session refusal, once a read returned it, is said on the card.
 */
@Composable
fun rememberHarnessRuntime(connection: Connection, memory: ChatMemory, pane: String?): HarnessRuntime {
    val view by connection.view.collectAsState()
    val linkLive = connection.state.collectAsState().value is Connection.State.Connected
    val boot = view?.boot
    val agent = pane?.let { view?.agentIn(it) }
    val server = SERVER_AGENTS.any { it.equals(agent?.agent, ignoreCase = true) }
    val identity = pane?.let { memory.of(it).history?.identity }
    val probe by produceState(ServerProbe.Unknown, connection, linkLive, boot, pane, agent, identity) {
        if (pane == null || boot == null || !server || !linkLive) return@produceState
        // Its history landing changes [identity] and asks again with it.
        if (identity == null) {
            memory.of(pane).recheck(connection::transcript, pane)
            return@produceState
        }
        if (identity.agent !in SERVER_AGENTS) return@produceState
        val target = Connection.ComposerTarget(connection.node.id, connection.node.session, boot, pane, identity.agent, identity)
        value = withContext(Dispatchers.IO) { connection.ingressNeedsServer(target) }
            .fold({ missing -> if (missing) ServerProbe.Missing else ServerProbe.Present }, { ServerProbe.Unknown })
    }
    // The typed Grok refusals where Chat stands behind no one session (ChatRefusal.ambiguousSession): the card says it too.
    return HarnessRuntime(server = probe, ambiguousSession = ambiguousSession(pane?.let { memory.of(it).read.refusal }))
}

internal class ChatKept {
    var history by mutableStateOf<ChatHistory?>(null)
    val read = ChatReadState()
    val expanded = mutableStateMapOf<String, Boolean>()
    var seen by mutableStateOf<String?>(null)
    // Made on the first image row: a pane that shows none never builds its caches.
    val media by lazy { ChatMedia() }
    private val rechecking = Mutex()
    // The poll's reads and the re-checks', one at a time: a slow read never lands over a newer answer.
    private val reading = Mutex()

    /**
     * One read of [pane]'s next page through [transcript]
     * ([Connection.transcript]), the poll's and a re-check's alike; it
     * records how it went.
     */
    suspend fun readNext(transcript: (JSONObject) -> Result<TranscriptPage>, pane: String): Boolean = reading.withLock {
        val known = history
        val at = known?.let { TranscriptRead.After(it.end) } ?: TranscriptRead.Tail
        val page = withContext(Dispatchers.IO) { transcript(transcriptRequest(pane, known?.identity, at)) }
        page.onSuccess { read ->
            // Merged into whatever is current now: a load of older entries may have finished meanwhile.
            history = history?.merge(read, appended = true) ?: ChatHistory.of(read)
            this.read.succeeded()
        }.onFailure { read.failed(it) }
        page.isSuccess
    }

    /**
     * One read while Chat is not composed to poll (F-OBS1); null when one is
     * already in flight, so repeated asks never stack reads.
     */
    suspend fun recheck(transcript: (JSONObject) -> Result<TranscriptPage>, pane: String): Boolean? {
        if (!rechecking.tryLock()) return null
        try {
            return readNext(transcript, pane)
        } finally {
            rechecking.unlock()
        }
    }
}

/**
 * How this pane's last reads went, kept with its history rather than
 * remembered, so leaving Chat for Terminal and coming back keeps the strip
 * (or its dismissal) until a good read ends it (HH-2).
 */
internal class ChatReadState {
    var problem by mutableStateOf<String?>(null)
    /** Set with [problem] when the core refused the session by type; it never clears by itself. */
    var refusal by mutableStateOf<TranscriptRefusal?>(null)
    /** The failed refresh the cached history shows under. */
    val outage = mutableStateOf(Outage())

    fun succeeded() {
        problem = null
        refusal = null
    }

    fun failed(error: Throwable) {
        problem = error.message ?: error.javaClass.simpleName
        refusal = lastingRefusal(error)
    }
}

/**
 * The refusal [error] carries when no retry ends it: any typed refusal, the
 * same kind on every poll. Null is a transient failure (I/O, a timeout, an
 * untyped core error), which the next poll may end.
 */
internal fun lastingRefusal(error: Throwable): TranscriptRefusal? = (error as? TranscriptRefused)?.refusal

/**
 * Whether Chat has nothing to show and says so: nothing [loaded], its last
 * read [failed], and that read was this Chat's own ([readHere]). A failure
 * kept in the pane's memory from before is no report on the pane now: Chat
 * opened again reads first, and only then says so.
 */
internal fun chatUnavailable(loaded: Boolean, failed: Boolean, readHere: Boolean): Boolean = !loaded && failed && readHere

@Composable
private fun ChatContent(
    connection: Connection,
    pane: String,
    kept: ChatKept,
    accent: Color,
    onTerminal: () -> Unit,
    modifier: Modifier,
    tabTitle: String?,
    dense: Boolean,
) {
    var history by kept::history
    var problem by kept.read::problem
    val refusal by kept.read::refusal
    var loadingOlder by remember { mutableStateOf(false) }
    // The item at the top of the screen when older ones were requested, and how far it was scrolled.
    var anchor by remember { mutableStateOf<Pair<String, Int>?>(null) }
    val expanded = kept.expanded
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Retry cuts the wait before the next read short.
    val wake = remember { Channel<Unit>(Channel.CONFLATED) }
    val colors = chatColors(accent)
    // What the agent does now: the live view's agent in this pane, never the last poll's answer,
    // and the agent's own server where it has one, for exactly the instance shown.
    val linkLive = connection.state.collectAsState().value is Connection.State.Connected
    val view by connection.view.collectAsState()
    var serverRunning by remember { mutableStateOf<Pair<io.github.erikfrish.herdline.core.TranscriptIdentity, Boolean>?>(null) }
    // The sessions the program's own server says this one started, for exactly the instance shown.
    var serverChildren by remember { mutableStateOf<Pair<io.github.erikfrish.herdline.core.TranscriptIdentity, IngressChildren>?>(null) }
    val status = chatStatus(linkLive, pane, view?.agentIn(pane), serverRunning?.takeIf { it.first == history?.identity }?.second)
    val busy by rememberUpdatedState(status == AgentStatus.Working || status == AgentStatus.Blocked)

    // Whether this Chat has read the pane itself yet: until then a failure kept from an earlier one
    // (another pane read, or Chat closed meanwhile) says nothing about the pane now.
    var readHere by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val ok = kept.readNext(connection::transcript, pane)
                readHere = true
                withTimeoutOrNull(
                    when {
                        !ok -> ERROR_POLL_MS
                        busy -> BUSY_POLL_MS
                        else -> IDLE_POLL_MS
                    },
                ) { wake.receive() }
            }
        }
    }

    val shown = history
    // A refresh that fails after a transcript loaded. A lost link is the session's strip, not this one.
    val outage by rememberOutage(if (shown == null) null else problem == null, state = kept.read.outage)
    val persistedItems = remember(shown?.entries) { shown?.let { chatItems(it.entries) }.orEmpty() }
    val identity = shown?.identity
    val images = remember(identity) {
        identity?.let { id -> ChatImages(kept.media, id) { connection.media(mediaRequest(pane, id, it)) } }
    }
    // OpenCode and Kilo ask through their own server: read it only while the agent waits on the user.
    val serverAgent = identity?.agent == "opencode" || identity?.agent == "kilo"
    val asked = status == AgentStatus.Blocked
    val dialogTarget = remember(connection, view?.boot, identity, pane, serverAgent && asked) {
        val boot = view?.boot
        val reads = identity?.agent == "omp" || serverAgent && asked
        if (boot == null || identity == null || !reads) null else Connection.ComposerTarget(connection.node.id, connection.node.session, boot, pane, identity.agent, identity)
    }
    // Stop goes where the program's Harness row says: its server, or one key in its pane.
    val stopTarget = remember(connection, view?.boot, identity, pane) {
        val boot = view?.boot
        if (boot == null || identity == null || Harness.of(identity.agent).stop == StopRoute.None) null else Connection.ComposerTarget(connection.node.id, connection.node.session, boot, pane, identity.agent, identity)
    }
    // OpenCode's server says which of its sessions it runs (GET /api/session/active); a read that
    // fails or isn't Live says nothing, so the pane's own status stands.
    val stateTarget = remember(connection, view?.boot, identity, pane) {
        val boot = view?.boot
        if (boot == null || identity == null || !serverAgent) null else Connection.ComposerTarget(connection.node.id, connection.node.session, boot, pane, identity.agent, identity)
    }
    LaunchedEffect(stateTarget, linkLive) {
        serverRunning = null
        serverChildren = null
        val target = stateTarget ?: return@LaunchedEffect
        val instance = target.instance ?: return@LaunchedEffect
        if (!linkLive) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val probe = withContext(Dispatchers.IO) { connection.ingressState(target) }.getOrNull()
                serverRunning = (probe as? IngressProbe.Live)?.let { instance to !it.state.idle }
                // Only a program's own server (OpenCode, Kilo) lists a session's children; a failed read shows none, never an old list.
                if (Harness.of(target.agent.orEmpty()).stop == StopRoute.Server) {
                    serverChildren = withContext(Dispatchers.IO) { connection.ingressChildren(target) }.getOrNull()?.let { instance to it }
                }
                delay(if (busy) BUSY_POLL_MS else IDLE_POLL_MS)
            }
        }
    }
    // Kept per pane, not per target: a re-read identity mid-call must not drop the tap's answer.
    var stopping by remember(pane) { mutableStateOf(false) }
    // Why the last Stop did nothing: a refusal's code (no key went), [STOP_NONE_RUNNING] or [STOP_UNCONFIRMED].
    var stopProblem by remember(pane) { mutableStateOf<String?>(null) }
    fun stop() {
        val target = stopTarget ?: return
        // One call at a time: a second tap while the first runs does nothing (Stop is greyed, "Stopping…").
        if (!linkLive || stopping) return
        stopping = true
        stopProblem = null
        scope.launch {
            try {
                val result = stopOutcome(STOP_ANSWER_MS, { withContext(Dispatchers.IO) { connection.ingressAbort(target) } }) { stopProblem = it }
                if (result.getOrNull() == true) wake.trySend(Unit)
            } finally {
                stopping = false
            }
        }
    }
    // The endpoint's live children, folded into the task calls that spawned them.
    var liveSubagents by remember(dialogTarget) { mutableStateOf<List<IngressSubagent>>(emptyList()) }
    val items = remember(persistedItems, liveSubagents) { withLiveSubagents(persistedItems, liveSubagents) }
    var dialogs by remember(dialogTarget) { mutableStateOf<List<ChatDialog>>(emptyList()) }
    var dialogProblem by remember(dialogTarget) { mutableStateOf<String?>(null) }
    // The server holds more open dialogs than the snapshot shows: said, never passed off as all of them.
    var dialogsMore by remember(dialogTarget) { mutableStateOf(false) }
    var submittingDialog by remember(dialogTarget) { mutableStateOf<String?>(null) }
    val uncertainDialogs = remember(dialogTarget) { mutableStateMapOf<String, Boolean>() }
    val currentDialogTarget by rememberUpdatedState(dialogTarget)
    val dialogErrors = remember(dialogTarget) { mutableStateMapOf<String, String>() }
    // Questions this phone answered or declined, shown as they ended; never resent.
    val settledDialogs = remember(dialogTarget) { mutableStateMapOf<String, Pair<ChatDialog, DialogSettled>>() }
    // The endpoint's last read for an OMP pane: without a usable one, the questionnaire says why (ompControlMissing).
    var ompProbe by remember(dialogTarget) { mutableStateOf<IngressProbe?>(null) }
    LaunchedEffect(dialogTarget, linkLive) {
        val target = dialogTarget ?: return@LaunchedEffect
        if (!linkLive) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val result = withContext(Dispatchers.IO) { connection.ingressDialogs(target) }
                result.onSuccess { snapshot ->
                    try {
                        val array = snapshot.dialogs
                        dialogs = visibleDialogs((0 until array.length()).map { ChatDialog.parse(array.getJSONObject(it)) }).filterNot { it.id in settledDialogs }
                        dialogsMore = snapshot.more
                        dialogProblem = null
                    } catch (error: IllegalArgumentException) {
                        dialogs = emptyList()
                        dialogProblem = error.message
                    } catch (error: org.json.JSONException) {
                        dialogs = emptyList()
                        dialogProblem = error.message
                    }
                }.onFailure { error ->
                    // Keep the user's answers while a read fails; actions remain disabled below.
                    dialogProblem = error.message
                }
                // The live state names each child under the tool call that spawned it; a read
                // that fails keeps the last one. Only OMP's endpoint has one.
                if (target.agent.equals("omp", ignoreCase = true)) {
                    withContext(Dispatchers.IO) { connection.ingressState(target) }.onSuccess { probe ->
                        ompProbe = probe
                        liveSubagents = (probe as? IngressProbe.Live)?.state?.subagents.orEmpty()
                    }
                }
                delay(BUSY_POLL_MS)
            }
        }
    }
    fun settle(dialog: ChatDialog, outcome: DialogSettled) {
        settledDialogs[dialog.id] = dialog to outcome
        dialogs = dialogs.filterNot { it.id == dialog.id }
        uncertainDialogs.remove(dialog.id)
        dialogErrors.remove(dialog.id)
    }
    fun submitDialog(dialog: ChatDialog, answer: org.json.JSONObject, summary: String) {
        val target = dialogTarget ?: return
        if (!linkLive || dialogProblem != null || submittingDialog != null || uncertainDialogs[dialog.id] == true || !dialog.submittable || dialogs.none { it.id == dialog.id }) return
        submittingDialog = dialog.id
        dialogErrors.remove(dialog.id)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { connection.ingressSubmitDialog(target, dialog.id, answer) }
                if (currentDialogTarget != target) return@launch
                val code = result.getOrNull()
                when {
                    // A question the host can decline shows how it ended; OMP's own forms settle in the transcript.
                    dialog.cancellable && code == "submitted" -> settle(dialog, DialogSettled.Answered(summary))
                    dialog.cancellable && code == FORM_NOT_OPEN -> settle(dialog, DialogSettled.NoLongerAsked)
                    dialog.cancellable && code == DIALOG_ANSWERED_ELSEWHERE -> settle(dialog, DialogSettled.AnsweredElsewhere)
                    code == "submitted" -> {
                        dialogs = dialogs.filterNot { it.id == dialog.id }
                        uncertainDialogs.remove(dialog.id)
                    }
                    else -> {
                        if (code == null) uncertainDialogs[dialog.id] = true
                        dialogErrors[dialog.id] = if (code == DIALOG_ANSWERED_ELSEWHERE) FORM_NOT_OPEN else code ?: "unconfirmed"
                    }
                }
                if (code == "submitted") wake.trySend(Unit)
            } finally {
                submittingDialog = null
            }
        }
    }
    fun cancelDialog(dialog: ChatDialog) {
        val target = dialogTarget ?: return
        if (!linkLive || dialogProblem != null || submittingDialog != null || uncertainDialogs[dialog.id] == true || !dialog.cancellable || dialogs.none { it.id == dialog.id }) return
        submittingDialog = dialog.id
        dialogErrors.remove(dialog.id)
        scope.launch {
            try {
                val result = withContext(Dispatchers.IO) { connection.ingressCancelDialog(target, dialog.id) }
                if (currentDialogTarget != target) return@launch
                when (result.getOrNull()) {
                    "cancelled" -> settle(dialog, DialogSettled.Declined).also { wake.trySend(Unit) }
                    FORM_NOT_OPEN -> settle(dialog, DialogSettled.NoLongerAsked)
                    DIALOG_ANSWERED_ELSEWHERE -> settle(dialog, DialogSettled.AnsweredElsewhere)
                    else -> {
                        // The decline may have reached the host: never retried as if it had not.
                        uncertainDialogs[dialog.id] = true
                        dialogErrors[dialog.id] = "unconfirmed"
                    }
                }
            } finally {
                submittingDialog = null
            }
        }
    }
    val asking = if (status == AgentStatus.Blocked) pendingTool(items) else null
    // The call the agent is blocked on stands alone, so the waiting row below reads it in place.
    val lines = remember(items, asking) { chatLines(items, asking) }
    // Runs a reply ended, drawn as the "Worked" fold, each with that reply.
    val runs = remember(lines) { lines.completedRuns() }
    val titled = remember(lines) { lines.titledKey }
    val unavailable = chatUnavailable(shown != null, problem != null, readHere)
    val refused = refusal.takeIf { unavailable }
    // Rows above the first line: Load older, then the new-session divider (R1).
    val header = (if (shown?.hasOlder == true) 1 else 0) + (if (shown?.newSession == true) 1 else 0)

    fun loadOlder() {
        val known = history ?: return
        if (loadingOlder || !known.hasOlder) return
        loadingOlder = true
        anchor = list.layoutInfo.visibleItemsInfo.firstNotNullOfOrNull { info ->
            lines.firstOrNull { it.key == info.key }?.let { it.anchorKey to info.offset }
        }
        scope.launch {
            withContext(Dispatchers.IO) {
                connection.transcript(transcriptRequest(pane, known.identity, TranscriptRead.Before(known.start)))
            }.onSuccess { page -> history = history?.merge(page, appended = false) ?: ChatHistory.of(page) }
                .onFailure {
                    anchor = null
                    problem = it.message
                }
            loadingOlder = false
        }
    }

    // Follows new messages while the reader is at the bottom. Only the reader's own drag, and the fling
    // after it, decides: settled within FollowBand of the end it follows, else not until they return.
    // Layout is not intent: a rotation, a recreated screen, the keyboard or the composer re-pin instead
    // (followStep). Saved, so a reader who scrolled up stays there across a recreation (J2b).
    var follow by rememberSaveable { mutableStateOf(true) }
    // The newest item the reader has seen at the bottom; anything after it is "new" for the Latest pill.
    var seen by kept::seen
    val band = with(LocalDensity.current) { FollowBand.roundToPx() }
    // A run held open because it was seen live folds when the next user turn arrives, only while
    // the reader follows the end (releasedRuns): nothing above a reader who scrolled up changes.
    val newestUser = remember(items) { items.lastOrNull { it is ChatItem.User }?.key }
    var seenUser by remember { mutableStateOf(newestUser) }
    LaunchedEffect(newestUser) {
        val held = expanded.keys.filter { it.startsWith("held:") }.mapTo(HashSet()) { it.removePrefix("held:") }
        releasedRuns(lines, held, follow, seenUser, newestUser).forEach { expanded.remove("held:$it") }
        seenUser = newestUser
    }

    // To the very end, also when the last item is taller than the list. A scroll of the reader's
    // own outranks it and wins; then their scroll decides.
    suspend fun pinToEnd() {
        try {
            list.scrollToEnd()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }

    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(list, band, keyboard) {
        var dragged = false
        var draggedScroll = false
        launch {
            list.interactionSource.interactions.collect {
                when (it) {
                    is DragInteraction.Start -> dragged = true
                    // A drag that ended without moving the list decides nothing.
                    is DragInteraction.Stop, is DragInteraction.Cancel -> if (!draggedScroll) dragged = false
                }
            }
        }
        // One collector, so a settled drag is decided before any re-pin could undo it. A drag that has
        // not started scrolling yet counts as scrolling: nothing pins under the reader's finger.
        snapshotFlow { list.isScrollInProgress to list.layoutInfo }.collect { (scrolling, layout) ->
            if (scrolling && dragged) {
                // Only the reader's first scroll hides IME; follow/restore never clear focus or the draft.
                if (!draggedScroll) keyboard?.hide()
                draggedScroll = true
            }
            val settled = !scrolling && draggedScroll
            val step = followStep(follow, scrolling || dragged && !draggedScroll, settled, atBand = layout.atEnd(band), atEnd = layout.atEnd(0))
            if (settled) {
                dragged = false
                draggedScroll = false
            }
            follow = step.follow
            if (step.pin) pinToEnd()
        }
    }
    LaunchedEffect(items, follow) { if (follow) seen = items.lastOrNull()?.key }
    LaunchedEffect(lines, status) {
        val kept = anchor
        if (kept != null) {
            // Older messages went in above: the one being read stays where it was on screen.
            val at = lines.indexOfItem(kept.first)
            if (at >= 0) {
                list.scrollToItem(at + header)
                list.scrollBy(-kept.second.toFloat())
            }
            anchor = null
        } else if (follow) {
            pinToEnd()
        }
    }
    val fresh = when {
        follow -> 0
        else -> items.indexOfFirst { it.key == seen }.let { if (it < 0) items.size else items.size - 1 - it }
    }
    val pill = shown != null && !follow && fresh > 0

    // The terminal stays laid out underneath so its pane keeps its size; touches stop here.
    Column(modifier.fillMaxSize().background(colors.bg).pointerInput(Unit) {}.testTag("chat")) {
        val since = outage.since
        // R-F1c: the cause More opened, held outside the strip so a good read never
        // closes it or changes its words; keyed to the pane and agent, so a switch
        // never shows one pane's cause over another's.
        var moreCause by rememberSaveable(pane, identity?.agent) { mutableStateOf<String?>(null) }
        // R-F1b: fixed lines for both kinds, so the list never jumps; no close, since
        // cached history must stay marked; it leaves at once on a good read (no motion).
        if (shown != null && linkLive && outage.shown && since != null) {
            val lasting = refusal
            val cause = if (lasting != null) {
                LocalContext.current.refusalText(lasting, view?.agentIn(pane)?.displayName ?: identity?.agent.orEmpty())
            } else {
                stringResource(R.string.chat_not_refreshed)
            }
            StaleStrip(
                savedAt = outageTime(since),
                cause = cause,
                shortCause = stringResource(if (lasting != null) R.string.chat_strip_cannot_read else R.string.chat_strip_not_refreshed),
                lasting = lasting != null,
                dense = dense,
                onRetry = { wake.trySend(Unit) },
                onTerminal = onTerminal,
                onMore = { moreCause = cause },
            )
        }
        moreCause?.let { words -> StaleCauseDialog(words, onDismiss = { moreCause = null }) }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                unavailable -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val context = LocalContext.current
                    // Never the core's raw message: for a session with no file yet it reads as a missing integration.
                    val said = refused?.let { context.refusalText(it, view?.agentIn(pane)?.displayName ?: identity?.agent.orEmpty()) }
                    Text(said ?: stringResource(R.string.chat_read_failed), style = ChatType.body.copy(color = colors.muted), textAlign = TextAlign.Center, modifier = Modifier.testTag("chat:unavailable"))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (refused == null) {
                            GlassButton(stringResource(R.string.outage_retry), onClick = { wake.trySend(Unit) }, primary = true, accent = accent, modifier = Modifier.testTag("chat:retry"))
                        }
                        GlassButton(stringResource(R.string.chat_open_terminal), onClick = onTerminal, accent = accent)
                    }
                }
                shown == null -> Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = accent)
                    Text(stringResource(R.string.chat_loading), style = ChatType.body.copy(color = colors.muted))
                }
                // An empty session says so in the middle of the page; older history, if any, is still one tap away.
                // A new session in the pane keeps its divider above even while it is empty (R1).
                items.isEmpty() && !shown.hasOlder && !shown.newSession && status != AgentStatus.Working && status != AgentStatus.Blocked -> Text(
                    stringResource(R.string.chat_empty),
                    style = ChatType.body.copy(color = colors.muted),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("chat:empty"),
                )
                else -> LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize().testTag("chat:list"),
                    // The Latest pill floats over the bottom padding, never over the last item.
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = if (dense) 0.dp else 8.dp,
                        bottom = when {
                            pill && dense -> CompactPillSize + DensePillGap * 2
                            pill -> 56.dp
                            dense -> 2.dp
                            else -> 12.dp
                        },
                    ),
                ) {
                    // Older history loads only from the top of the list, never from a control over the newest messages.
                    if (shown.hasOlder) {
                        item(key = "older") { LoadOlder(loadingOlder, colors, ::loadOlder) }
                    }
                    // The pane's agent moved to another session: only that session is below (R1, C-2).
                    if (shown.newSession) {
                        item(key = "new_session") { NewSessionDivider(colors) }
                    }
                    items(lines, key = { it.key }) { line ->
                        CompositionLocalProvider(LocalChatImages provides images) {
                            Column(Modifier.padding(top = line.gap.dp())) {
                                when (line) {
                                    is ChatLine.Single -> {
                                        if (line.labelled) AgentLabel(colors, tabTitle.takeIf { line.key == titled }, identity?.agent ?: view?.agentIn(pane)?.agent.orEmpty())
                                        ChatRow(line.item, colors, expanded, dense)
                                    }
                                    is ChatLine.ToolGroup -> {
                                        if (line.labelled) AgentLabel(colors, tabTitle.takeIf { line.key == titled }, identity?.agent ?: view?.agentIn(pane)?.agent.orEmpty())
                                        ToolGroupRow(line, colors, expanded, runs[line.key], dense)
                                    }
                                }
                            }
                        }
                    }
                    dialogs.forEach { dialog ->
                        item(key = "dialog:" + dialog.id) {
                            LiveGap {
                                ChatFormCard(
                                    dialog, colors,
                                    submitting = !linkLive || dialogProblem != null || submittingDialog == dialog.id || uncertainDialogs[dialog.id] == true,
                                    submitError = dialogErrors[dialog.id],
                                    onSubmit = { answer, summary -> submitDialog(dialog, answer, summary) },
                                    onCancel = if (dialog.cancellable) ({ cancelDialog(dialog) }) else null,
                                )
                            }
                        }
                    }
                    if (dialogsMore) {
                        item(key = "dialogs:more") { LiveGap { DialogsMoreRow(colors) } }
                    }
                    settledDialogs.values.forEach { (dialog, outcome) ->
                        item(key = "dialog:" + dialog.id) {
                            LiveGap { ChatFormCard(dialog, colors, submitting = false, submitError = null, onSubmit = { _, _ -> }, settled = outcome) }
                        }
                    }
                    val children = serverChildren?.takeIf { it.first == identity }?.second
                    if (children != null && children.sessions.isNotEmpty()) {
                        item(key = "children") { LiveGap { ChildrenRow(children, colors) } }
                    }
                    val failure = (items.lastOrNull() as? ChatItem.Tool)?.takeIf { it.failed }
                    when {
                        status == AgentStatus.Blocked && dialogs.isEmpty() -> item(key = "blocked") {
                            // An OMP with no control endpoint: why Chat can't answer, never the raw read failure.
                            val noControl = ompControlMissing(identity?.agent, ompProbe)
                            val answerReason = if (noControl) stringResource(R.string.omp_no_control) else controlReason(identity?.agent, HarnessKey.Questions, R.string.control_answer_unavailable)
                            LiveGap { NeedsInputRow(asking, colors, onTerminal, dialogProblem.takeUnless { noControl }, answerReason) }
                        }
                        status == AgentStatus.Working -> item(key = "working") {
                            LiveGap {
                                WorkingRow(
                                    workingOn(items), colors,
                                    onStop = if (stopTarget != null && linkLive) ({ stop() }) else null,
                                    stopping = stopping, stopProblem = stopProblem,
                                    // Text only, where Stop is not offered: why (§3.5).
                                    stopReason = when {
                                        stopTarget != null && linkLive -> null
                                        !linkLive -> identity?.agent?.let {
                                            stringResource(R.string.control_cant_check, it, stringResource(R.string.harness_cant_check_offline))
                                        }
                                        else -> controlReason(identity?.agent, HarnessKey.Stop, R.string.control_stop_unavailable)
                                    },
                                )
                            }
                        }
                        // Herdr has no agent error state; a turn that ended on a failed tool is the error chat can see.
                        failure != null -> item(key = "error") { LiveGap { ErrorRow(failure, colors, onTerminal) } }
                        else -> Unit
                    }
                }
            }
            if (pill) {
                // Dense: the reader is mid-list, so no padding keeps the lines read clear of a centred pill.
                // It moves to the end gutter instead, where it covers at most a long line's trailing end.
                val place = if (dense) {
                    Modifier.align(Alignment.BottomEnd).padding(end = DensePillEnd, bottom = DensePillGap)
                } else {
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)
                }
                LatestPill(fresh, colors, place, compact = dense) {
                    follow = true
                    scope.launch { list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0)) }
                }
            }
        }
    }
}

private fun Gap.dp() = when (this) {
    Gap.None -> 0.dp
    Gap.Turn -> ChatSpace.turn
    Gap.Block -> ChatSpace.block
    Gap.Tool -> ChatSpace.tool
}

/** What a Working row names: the running tool, else Thinking when that is the newest item. */
@Composable
private fun workingOn(items: List<ChatItem>): String? {
    val last = items.lastOrNull()
    val tool = (items.lastOrNull { it is ChatItem.Tool } as? ChatItem.Tool)?.takeIf { it.running }
    return when {
        tool != null -> tool.call?.name ?: tool.result?.name
        last is ChatItem.Thinking -> stringResource(R.string.chat_thinking)
        else -> null
    }
}

@Composable
private fun LiveGap(content: @Composable () -> Unit) {
    Box(Modifier.padding(top = ChatSpace.turn)) { content() }
}

/**
 * The recorded agent kind, then the tab title in a quieter style on the
 * first labelled line; no engine is inferred from message text.
 */
@Composable
private fun AgentLabel(colors: ChatColors, tabTitle: String?, agent: String) {
    Text(
        buildAnnotatedString {
            append(agent)
            if (!tabTitle.isNullOrBlank()) {
                withStyle(ChatType.meta.toSpanStyle().copy(color = colors.dim)) { append(" · $tabTitle") }
            }
        },
        style = ChatType.label.copy(color = colors.accentText),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

@Composable
private fun LoadOlder(loading: Boolean, colors: ChatColors, onLoad: () -> Unit) {
    Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
        } else {
            Text(
                stringResource(R.string.chat_load_older),
                style = ChatType.button.copy(color = colors.accentText),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(CircleShape)
                    .clickable(role = Role.Button, onClick = onLoad)
                    .padding(horizontal = 16.dp, vertical = 15.dp)
                    .testTag("chat:older"),
            )
        }
    }
}

/** [compact]: arrow over the bare count in one 48 dp square; the full plural is still what TalkBack reads. */
@Composable
private fun LatestPill(count: Int, colors: ChatColors, modifier: Modifier, compact: Boolean, onClick: () -> Unit) {
    val spoken = pluralStringResource(R.plurals.chat_latest_new, count, count)
    val shape = if (compact) MaterialTheme.shapes.medium else CircleShape
    val frame = modifier
        // A floor, not a size: at large font scales the count needs more than 48 dp and must not clip.
        .then(if (compact) Modifier.sizeIn(minWidth = CompactPillSize, minHeight = CompactPillSize) else Modifier.height(PillHeight))
        .clip(shape)
        .background(colors.surf)
        .border(1.dp, colors.border, shape)
        .clickable(role = Role.Button, onClick = onClick)
        .semantics { contentDescription = spoken }
        .testTag("chat:latest")
    if (compact) {
        Column(frame, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = colors.accentText)
            Text(
                if (count > 99) "99+" else count.toString(),
                style = ChatType.button.copy(color = colors.accentText),
                maxLines = 1,
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    } else {
        Row(
            frame.padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = colors.accentText)
            Text(spoken, style = ChatType.button.copy(color = colors.accentText), modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

@Composable
private fun ChatRow(item: ChatItem, colors: ChatColors, expanded: MutableMap<String, Boolean>, dense: Boolean) {
    val open = expanded[item.key] == true
    val toggle = { expanded[item.key] = !open }
    when (item) {
        // The row ends on the prose edge, so a bubble never sits far right of the reply.
        is ChatItem.User -> BoxWithConstraints(Modifier.widthIn(max = ProseMaxWidth).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            // The small corner points at the end edge, where the turn sits; it mirrors in RTL.
            val shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp)
            val bubbleWidth = minOf(maxWidth * 0.85f, BubbleMaxWidth)
            Column(horizontalAlignment = Alignment.End) {
                // Already in the history: how OMP took it in, never a queue it still waits in.
                item.steer?.let { steer ->
                    Text(
                        stringResource(if (steer == TranscriptSteer.Live) R.string.chat_steer_live else R.string.chat_steer_steering),
                        style = ChatType.meta.copy(color = colors.muted),
                        modifier = Modifier.padding(bottom = 4.dp).testTag("chat:steer"),
                    )
                }
                Box(
                    Modifier
                        .widthIn(max = bubbleWidth)
                        .clip(shape)
                        .background(colors.userBubble)
                        .border(1.dp, colors.bubbleEdge, shape)
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("chat:user"),
                ) {
                    Markdown(item.text, colors, dense = dense)
                }
            }
        }
        is ChatItem.Assistant -> Column(Modifier.fillMaxWidth().testTag("chat:assistant")) {
            Markdown(item.text, colors, dense = dense)
            if (item.truncated) Text(stringResource(R.string.chat_truncated), style = ChatType.meta.copy(color = colors.dim), modifier = Modifier.padding(top = 4.dp))
        }
        is ChatItem.Thinking -> {
            val count = remember(item.text) { item.text.trimEnd().lines().size }
            Aside(pluralStringResource(R.plurals.chat_thought_lines, count, count), null, colors, open, toggle, italic = true) {
                Text(item.text, style = ChatType.aside.copy(color = colors.muted), modifier = Modifier.widthIn(max = ProseMaxWidth))
            }
        }
        is ChatItem.Tool -> ToolRow(item, colors, expanded)
        is ChatItem.Note -> Aside(item.role, item.text.lineSequence().firstOrNull(), colors, open, toggle, italic = false) {
            Markdown(item.text, colors, color = colors.muted, dense = dense)
        }
        is ChatItem.Compaction -> CompactionDivider(colors, open, toggle) {
            Markdown(item.summary, colors, color = colors.muted, dense = dense)
        }
        is ChatItem.Boundary -> HistoryBoundary(colors)
        is ChatItem.Marker -> MarkerRow(item, colors)
        is ChatItem.Image -> Box(Modifier.widthIn(max = ProseMaxWidth).fillMaxWidth(), contentAlignment = if (item.fromUser) Alignment.CenterEnd else Alignment.CenterStart) {
            ChatImageThumb(item.image, colors)
        }
    }
}

/** A one-line, plain disclosure (Thinking, notes) that opens to [content] under a 2 dp bar. */
@Composable
private fun Aside(
    title: String,
    hint: String?,
    colors: ChatColors,
    open: Boolean,
    onToggle: () -> Unit,
    italic: Boolean,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            DisclosureArrow(open, 16.dp, colors.dim)
            val style = (if (italic) ChatType.aside.copy(fontStyle = FontStyle.Italic) else ChatType.meta).copy(color = colors.dim)
            Text(title, style = style, maxLines = 1)
            if (!open && hint != null) {
                Text(hint, style = ChatType.meta.copy(color = colors.dim), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        if (open) Barred(colors.rule, 2.dp) { content() }
    }
}

/** [content] beside a leading bar of [color]. */
@Composable
private fun Barred(color: Color, width: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(Modifier.width(width).fillMaxHeight().background(color))
        Box(Modifier.padding(start = 12.dp).weight(1f)) { content() }
    }
}

@Composable
private fun CompactionDivider(colors: ChatColors, open: Boolean, onToggle: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().animateContentSize()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
            Text(stringResource(R.string.chat_compacted), style = ChatType.meta.copy(color = colors.dim))
            Box(Modifier.weight(1f).height(1.dp).background(colors.border))
        }
        if (open) Barred(colors.rule, 2.dp) { content() }
    }
}

/**
 * A stop or one plan item in the app's own words. The glyph is decoration:
 * TalkBack hears the word, then the item, as one line.
 */
@Composable
private fun MarkerRow(item: ChatItem.Marker, colors: ChatColors) {
    val word = stringResource(
        when (item.kind) {
            MarkerKind.StoppedByYou -> R.string.chat_marker_stopped_by_you
            MarkerKind.Stopped -> R.string.chat_marker_stopped
            MarkerKind.Done -> R.string.chat_marker_done
            MarkerKind.ToDo -> R.string.chat_marker_to_do
        },
    )
    val glyph = when (item.kind) {
        MarkerKind.Done -> "✓"
        MarkerKind.ToDo -> "○"
        MarkerKind.StoppedByYou, MarkerKind.Stopped -> null
    }
    val spoken = if (item.text.isBlank()) word else stringResource(R.string.chat_marker_item_spoken, word, item.text)
    Row(
        Modifier
            .widthIn(max = ProseMaxWidth)
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = spoken }
            .testTag("chat:marker"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (glyph != null) Text(glyph, style = ChatType.meta.copy(color = colors.dim))
        Text(
            buildAnnotatedString {
                withStyle(ChatType.meta.toSpanStyle().copy(color = colors.muted)) { append(word) }
                if (item.text.isNotBlank()) {
                    append("  ")
                    append(item.text)
                }
            },
            style = ChatType.aside.copy(color = colors.text),
        )
    }
}

/**
 * The compaction divider's look with the boundary's own words in its place.
 * Nothing opens: it has no body, and the text itself says what it is, so it
 * wraps instead of being cut.
 */
@Composable
private fun HistoryBoundary(colors: ChatColors) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat:history_boundary"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(0.15f).height(1.dp).background(colors.border))
        Text(
            stringResource(R.string.chat_history_boundary),
            style = ChatType.meta.copy(color = colors.dim),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.weight(0.15f).height(1.dp).background(colors.border))
    }
}

/** "New session in this pane": every line below is the session the pane's agent moved to (designer §1 R). */
@Composable
private fun NewSessionDivider(colors: ChatColors) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("chat:new_session"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.weight(0.15f).height(1.dp).background(colors.border))
        Text(
            stringResource(R.string.chat_new_session_divider),
            style = ChatType.meta.copy(color = colors.dim),
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.weight(0.15f).height(1.dp).background(colors.border))
    }
}

@Composable
private fun ToolGlyph(state: ToolState, colors: ChatColors) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (state) {
            ToolState.Running -> {
                val spoken = stringResource(R.string.chat_tool_running)
                CircularProgressIndicator(Modifier.size(12.dp).semantics { contentDescription = spoken }, strokeWidth = 1.5.dp, color = colors.working)
            }
            ToolState.Failed -> Icon(Icons.Default.Close, stringResource(R.string.chat_tool_failed), Modifier.size(14.dp), tint = colors.err)
            ToolState.Denied -> Icon(Icons.Default.Lock, stringResource(R.string.chat_tool_denied), Modifier.size(14.dp), tint = colors.blocked)
            ToolState.Done -> Icon(Icons.Default.Check, stringResource(R.string.chat_tool_done), Modifier.size(14.dp), tint = colors.done)
        }
    }
}

/**
 * A tool's state at the end of its head row, drawn like a small outlined
 * M3 AssistChip but not a target of its own: the row is the target, and a
 * full chip's 48 dp would make every collapsed row taller. TalkBack hears
 * the state from the head's one node.
 */
@Composable
private fun StatusChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color),
        modifier = Modifier.clearAndSetSemantics {},
    ) {
        Text(label, style = ChatType.meta.copy(color = color), maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

/** Where a tool's output starts: under its name, past the 16 dp glyph and the 8 dp gap. */
private val ToolBodyIndent = 24.dp

/**
 * A tool as one quiet 48 dp head, with no card around it: status glyph,
 * name, what it worked on, its state chip (failed or `exit N`, denied) and
 * a chevron; TalkBack reads the head as one node that expands and
 * collapses. Open, the last [TOOL_TAIL_LINES] lines of the result sit in
 * one frame under the name, with the arguments behind their own
 * disclosure. The glyph and the chip carry the state, never a border. A
 * failed tool starts open; a denied one, which did not run, does not.
 */
@Composable
private fun ToolRow(item: ChatItem.Tool, colors: ChatColors, expanded: MutableMap<String, Boolean>) {
    val state = toolState(item)
    val result = item.result
    val exit = remember(result) { result?.let(::exitCode)?.takeIf { it != 0 } }
    val open = expanded[item.key] ?: (state == ToolState.Failed)
    val toggle = { expanded[item.key] = !open }
    val summary = item.summary()
    val spoken = listOfNotNull(
        // The name and what it worked on read as one phrase: "run_terminal_cmd: ls".
        if (summary.isBlank()) item.name else "${item.name}: $summary",
        stringResource(
            when (state) {
                ToolState.Running -> R.string.chat_tool_running
                ToolState.Failed -> R.string.chat_tool_failed
                ToolState.Denied -> R.string.chat_tool_denied
                ToolState.Done -> R.string.chat_tool_done
            },
        ),
        exit?.takeIf { state == ToolState.Failed }?.let { stringResource(R.string.chat_tool_exit_spoken, it) },
    ).joinToString(", ")
    val stateText = stringResource(if (open) R.string.chat_state_expanded else R.string.chat_state_collapsed)
    Column(Modifier.fillMaxWidth().testTag("chat:tool")) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = toggle)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    stateDescription = stateText
                    if (open) collapse { toggle(); true } else expand { toggle(); true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolGlyph(state, colors)
            Text(item.name, style = ChatType.toolName.copy(color = colors.text), maxLines = 1)
            Text(
                summary,
                style = ChatType.toolArg.copy(color = colors.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                state == ToolState.Failed && exit != null -> StatusChip(stringResource(R.string.chat_tool_exit, exit), colors.err)
                state == ToolState.Failed -> StatusChip(stringResource(R.string.chat_tool_failed), colors.err)
                state == ToolState.Denied -> StatusChip(stringResource(R.string.chat_tool_denied), colors.blocked)
                else -> Unit
            }
            // One glyph turned, not two swapped: the turn shows the change and the
            // spoken state carries it, never the motion alone.
            val turn by animateFloatAsState(if (open) 180f else 0f, tween(250), label = "toolChevron")
            Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp).graphicsLayer { rotationZ = turn }, tint = colors.dim)
        }
        // Under the tail: every child when the row is open; else the ones still
        // running, so spawned children show even while the tool row is collapsed.
        if (item.subagents.isNotEmpty()) {
            // Only the children's list resizes as they come and go; the body below has its own motion.
            Box(Modifier.fillMaxWidth().animateContentSize()) {
                SubagentSection(if (open) item.subagents else item.subagents.filter { it.alive }, colors, expanded, item.key)
            }
        }
        AnimatedVisibility(
            open,
            enter = fadeIn(tween(150, delayMillis = 50)) + expandVertically(),
            exit = fadeOut(tween(100)) + shrinkVertically(),
        ) {
            Column(Modifier.padding(start = ToolBodyIndent, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (result != null && result.answers.isNotEmpty()) {
                    ChatFormAnswers(result.answers, result.timedOut, colors)
                } else if (result != null && result.text.isNotBlank()) {
                    val all = expanded["more:" + item.key] == true
                    val (tail, earlier) = remember(result.text) { outputTail(result.text, TOOL_TAIL_LINES) }
                    ToolOutput(if (all) result.text.trimEnd('\n') else tail, colors)
                    if (!all && earlier > 0) {
                        val total = earlier + TOOL_TAIL_LINES
                        Disclosure(pluralStringResource(R.plurals.chat_show_all_lines, total, total), colors) { expanded["more:" + item.key] = true }
                    }
                }
                if (item.images.isNotEmpty()) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        item.images.forEach { ChatImageThumb(it, colors) }
                    }
                }
                item.call?.takeIf { it.args.isNotBlank() }?.let { call ->
                    val args = expanded["args:" + item.key] == true
                    Disclosure(stringResource(R.string.chat_tool_arguments), colors, open = args) { expanded["args:" + item.key] = !args }
                    if (args) CodeBlock(call.args, colors)
                }
                if (item.call?.truncated == true || result?.truncated == true) {
                    Text(stringResource(R.string.chat_truncated), style = ChatType.meta.copy(color = colors.dim))
                }
            }
        }
    }
}

private val ToolOutputPadH = 12.dp
private val ToolOutputPadV = 8.dp

/**
 * A tool's output in its one frame, scrolling sideways. Each http(s)
 * address ([outputLinks]) is a link of its own for taps and TalkBack; a tap
 * that misses every link but lands within a 48 dp-tall box about a link's
 * line opens the nearest one ([nearLink]). Only a tap counts: a long press
 * or a drag opens nothing.
 */
@Composable
private fun ToolOutput(text: String, colors: ChatColors) {
    val links = remember(text) { outputLinks(text) }
    val linkStyle = TextLinkStyles(SpanStyle(color = colors.accentText, textDecoration = TextDecoration.Underline))
    val shown = remember(text, links, linkStyle) {
        buildAnnotatedString {
            var at = 0
            for (link in links) {
                append(text, at, link.first)
                withLink(LinkAnnotation.Url(text.substring(link.first, link.last + 1), linkStyle)) { append(text, link.first, link.last + 1) }
                at = link.last + 1
            }
            append(text, at, text.length)
        }
    }
    val uri = LocalUriHandler.current
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val shape = MaterialTheme.shapes.medium
    val near = if (links.isEmpty()) Modifier else Modifier.pointerInput(links) {
        val reach = 48.dp.toPx()
        val pad = Offset(ToolOutputPadH.toPx(), ToolOutputPadV.toPx())
        // Inside the sideways scroll and outside the padding: positions are the scrolled content's, so less
        // the padding they are the text layout's own.
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // A scroll consumes the move and cancels; a hold past the long-press time is not a tap.
            val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation() } ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            val laid = layout ?: return@awaitEachGesture
            val tap = up.position - pad
            val boxes = links.map { link ->
                val line = laid.getLineForOffset(link.first)
                val a = laid.getHorizontalPosition(link.first, usePrimaryDirection = true)
                val b = laid.getHorizontalPosition(link.last + 1, usePrimaryDirection = true)
                floatArrayOf(minOf(a, b), laid.getLineTop(line), maxOf(a, b), laid.getLineBottom(line))
            }
            // A tap on a link itself is the link's own.
            if (boxes.any { (l, t, r, b) -> tap.x in l..r && tap.y in t..b }) return@awaitEachGesture
            val hit = nearLink(tap.x, tap.y, boxes, reach) ?: return@awaitEachGesture
            val link = links[hit]
            runCatching { uri.openUri(text.substring(link.first, link.last + 1)) }
        }
    }
    Box(Modifier.fillMaxWidth().clip(shape).background(colors.codeBg).border(1.dp, colors.border, shape)) {
        Text(
            shown,
            style = ChatType.toolOutput.copy(color = colors.text),
            softWrap = false,
            onTextLayout = { layout = it },
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .then(near)
                .padding(horizontal = ToolOutputPadH, vertical = ToolOutputPadV),
        )
    }
}

@Composable
private fun Disclosure(label: String, colors: ChatColors, open: Boolean? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (open != null) {
            DisclosureArrow(open, 14.dp, colors.accentText)
        }
        Text(label, style = ChatType.action.copy(color = colors.accentText))
    }
}

/**
 * The disclosure arrow: one end-pointing glyph that turns to point down when
 * [open] (clockwise in LTR, anticlockwise in RTL, where it starts mirrored).
 * Drawn turned only, so the row never reflows; the row's text or spoken
 * state says open or closed, never the turn alone.
 */
@Composable
internal fun DisclosureArrow(open: Boolean, size: Dp, tint: Color, description: String? = null) {
    val down = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 90f else -90f
    val turn by animateFloatAsState(if (open) down else 0f, tween(250), label = "disclosure")
    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, description, Modifier.size(size).graphicsLayer { rotationZ = turn }, tint = tint)
}

/**
 * A run's one 48 dp control row: a disclosure glyph and [content]. TalkBack
 * names the glyph, or, given [spoken], reads the row as that one node in
 * [state] with an expand or collapse action.
 */
@Composable
private fun RunRow(
    open: Boolean,
    colors: ChatColors,
    onClick: () -> Unit,
    spoken: String? = null,
    state: String? = null,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .then(
                if (spoken == null) Modifier else Modifier.clearAndSetSemantics {
                    contentDescription = spoken
                    if (state != null) stateDescription = state
                    if (open) collapse { onClick(); true } else expand { onClick(); true }
                },
            )
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        DisclosureArrow(
            open,
            16.dp,
            colors.dim,
            if (spoken != null) null else stringResource(if (open) R.string.chat_hide_steps else R.string.chat_show_steps),
        )
        content()
    }
}

/**
 * A run of steps ([ChatLine.ToolGroup]). Folded it is one two-line row in
 * the shape of an M3 ListItem (counts with failure badges, then the last
 * step), kept at 48 dp rather than ListItem's 72 so history stays dense. A
 * live run draws its last [LIVE_TAIL] steps under an "N earlier" row, and
 * stays that way once it finishes ([runStart]): a run seen live is held
 * open, so its finishing never pulls the steps out from under the eye.
 * A run [answer]ed by a reply draws, when not held, as the "Worked" fold
 * ([WorkedRun]) instead.
 */
@Composable
private fun ToolGroupRow(group: ChatLine.ToolGroup, colors: ChatColors, expanded: MutableMap<String, Boolean>, answer: ChatItem.Assistant?, dense: Boolean) {
    val live = group.live
    // Kept in the list-wide map, not in the row: a run scrolled away and back is still held.
    LaunchedEffect(group.key, live) { if (live) expanded["held:" + group.key] = true }
    val open = expanded[group.key] == true
    val start = runStart(group, open, held = expanded["held:" + group.key] == true)
    if (answer != null && (start == null || open)) {
        WorkedRun(group, answer, open, colors, expanded, dense)
        return
    }
    val tools = group.tools
    val names = tools.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(", ")
    val counts = pluralStringResource(R.plurals.chat_tool_group, tools.size, tools.size) + " · " + names
    Column(Modifier.fillMaxWidth().animateContentSize().testTag("chat:tools")) {
        if (start == null) {
            RunRow(open = false, colors, onClick = { expanded[group.key] = true }) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(counts, style = ChatType.toolArg.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        RunBadges(group, colors)
                    }
                    tools.lastOrNull()?.let { last ->
                        Text(last.stepLine(), style = ChatType.meta.copy(color = colors.dim), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            when {
                open -> RunRow(open = true, colors, onClick = {
                    expanded[group.key] = false
                    expanded.remove("held:" + group.key)
                }) {
                    Text(counts, style = ChatType.toolArg.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                start > 0 -> RunRow(open = false, colors, onClick = { expanded[group.key] = true }) {
                    Text(pluralStringResource(R.plurals.chat_run_earlier, start, start), style = ChatType.toolArg.copy(color = colors.muted), maxLines = 1)
                }
            }
            group.steps.drop(start).forEachIndexed { at, step ->
                key(step.key) {
                    if (at > 0 || open || start > 0) Spacer(Modifier.height(ChatSpace.tool))
                    ChatRow(step, colors, expanded, dense)
                }
            }
        }
    }
}

/** A run's failed and denied counts as words on their badges. */
@Composable
private fun RunBadges(group: ChatLine.ToolGroup, colors: ChatColors) {
    if (group.failed > 0) {
        Badge(containerColor = colors.errBg, contentColor = colors.err) {
            Text(pluralStringResource(R.plurals.chat_tool_group_failed, group.failed, group.failed), style = ChatType.meta, maxLines = 1)
        }
    }
    if (group.denied > 0) {
        Badge(containerColor = colors.warnBg, contentColor = colors.blocked) {
            Text(pluralStringResource(R.plurals.chat_tool_group_denied, group.denied, group.denied), style = ChatType.meta, maxLines = 1)
        }
    }
}

/**
 * A run a reply ended, as one row at least `max(48, 12 + 18·fs + 16·fs)` dp
 * tall: "Worked · N tool calls · N thoughts" (with the time between the
 * run's first step and the reply only when the transcript dated both,
 * [RunFold.seconds]), then the failed and denied counts and the tools'
 * names; only the names ellipsize. Open, every step shows as its head,
 * 16 dp in, beside a 1 dp rule under the chevron. Its open state is the
 * group key's in [expanded], so it holds across reads and older pages.
 */
@Composable
private fun WorkedRun(group: ChatLine.ToolGroup, answer: ChatItem.Assistant, open: Boolean, colors: ChatColors, expanded: MutableMap<String, Boolean>, dense: Boolean) {
    val fold = remember(group, answer) { runFold(group, answer) }
    val seconds = fold.seconds
    val title = buildList {
        add(
            if (seconds != null) pluralStringResource(R.plurals.chat_fold_tools_timed, fold.tools, seconds.toInt(), fold.tools)
            else pluralStringResource(R.plurals.chat_fold_tools, fold.tools, fold.tools),
        )
        if (fold.thoughts > 0) add(pluralStringResource(R.plurals.chat_fold_thoughts, fold.thoughts, fold.thoughts))
    }
    val spoken = buildList {
        title.forEach { part -> addAll(part.split(" · ")) }
        if (fold.failed > 0) add(pluralStringResource(R.plurals.chat_tool_group_failed, fold.failed, fold.failed))
        if (fold.denied > 0) add(pluralStringResource(R.plurals.chat_tool_group_denied, fold.denied, fold.denied))
    }.joinToString(", ")
    val state = stringResource(if (open) R.string.chat_state_expanded else R.string.chat_state_collapsed)
    val rule = colors.rule
    Column(Modifier.fillMaxWidth().animateContentSize().testTag("chat:worked")) {
        RunRow(
            open,
            colors,
            onClick = {
                expanded[group.key] = !open
                expanded.remove("held:" + group.key)
            },
            spoken = spoken,
            state = state,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 2.dp)) {
                Text(title.joinToString(" · "), style = ChatType.fold.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RunBadges(group, colors)
                    Text(
                        fold.names.joinToString(", "),
                        style = ChatType.toolArg.copy(color = colors.dim),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
            }
        }
        if (open) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawBehind {
                        val x = 8.dp.toPx().let { if (layoutDirection == LayoutDirection.Ltr) it else size.width - it }
                        drawLine(rule, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
                    }
                    .padding(start = 16.dp),
            ) {
                group.steps.forEachIndexed { at, step ->
                    key(step.key) {
                        if (at > 0) Spacer(Modifier.height(ChatSpace.tool))
                        ChatRow(step, colors, expanded, dense)
                    }
                }
            }
        }
    }
}

/** An end-of-list state row: a hairline or [fill] card with a 3 dp bar of [bar]. */
@Composable
private fun StateRow(bar: Color, border: Color, fill: Color, tag: String, content: @Composable () -> Unit) {
    val shape = MaterialTheme.shapes.medium
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = 40.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, border, shape)
            .testTag(tag),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(bar))
        Column(Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/**
 * [onStop], when the agent's own server or its pane can stop this turn.
 * Without it, [stopReason] shows Stop disabled with why; with neither there is no Stop.
 */
@Composable
private fun WorkingRow(
    what: String?,
    colors: ChatColors,
    onStop: (() -> Unit)? = null,
    stopping: Boolean = false,
    stopProblem: String? = null,
    stopReason: String? = null,
) {
    StateRow(colors.working, colors.border, Color.Transparent, "chat:working") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.working)
            Text(
                if (what != null) stringResource(R.string.chat_working_on, what) else stringResource(R.string.chat_working),
                style = ChatType.body.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (onStop != null) {
                FilledTonalButton(onClick = onStop, enabled = !stopping, modifier = Modifier.heightIn(min = 48.dp).testTag("chat:stop")) {
                    Text(stringResource(R.string.chat_stop))
                }
            } else if (stopReason != null) {
                FilledTonalButton(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = stopReason }.testTag("chat:stop"),
                ) {
                    Text(stringResource(R.string.chat_stop))
                }
            }
        }
        if (onStop == null && stopReason != null) Text(stopReason, style = ChatType.meta.copy(color = colors.muted))
        if (stopProblem != null) {
            Text(
                when (stopProblem) {
                    STOP_UNCONFIRMED -> stringResource(R.string.chat_stop_failed)
                    STOP_NONE_RUNNING -> stringResource(R.string.chat_stop_none_running)
                    else -> stringResource(stopRefusalRes(stopProblem))
                },
                style = ChatType.meta.copy(color = colors.muted),
                modifier = Modifier.testTag("chat:stop:problem"),
            )
        } else if (stopping) {
            Text(stringResource(R.string.chat_stopping), style = ChatType.meta.copy(color = colors.muted), modifier = Modifier.testTag("chat:stopping"))
        }
    }
}

/**
 * The sessions this one started, by title (the id where it has none); the server gives no state for
 * them, so none is shown. A cut list says there are more, never passes for the whole.
 */
@Composable
private fun ChildrenRow(children: IngressChildren, colors: ChatColors) {
    StateRow(colors.muted, colors.border, Color.Transparent, "chat:children") {
        val count = children.sessions.size
        Text(
            if (children.more) stringResource(R.string.chat_children_more, count) else stringResource(R.string.chat_children, count),
            style = ChatType.meta.copy(color = colors.muted),
        )
        children.sessions.forEach { child ->
            Text(
                child.title.ifBlank { child.id },
                style = ChatType.body.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** More permissions or questions are open than Chat shows: answering these brings the next ones in. */
@Composable
private fun DialogsMoreRow(colors: ChatColors) {
    StateRow(colors.blocked, colors.border, Color.Transparent, "chat:dialogs:more") {
        Text(stringResource(R.string.chat_dialogs_more), style = ChatType.meta.copy(color = colors.muted))
    }
}

/** A Stop that may have pressed its key without a confirmation. */
private const val STOP_UNCONFIRMED = "unconfirmed"

/** A server Stop whose server had no turn running for the instance. */
private const val STOP_NONE_RUNNING = "none_running"

/** How long a Stop tap waits for the host before it says "not confirmed"; the host's own answer still replaces it. */
internal const val STOP_ANSWER_MS = 20_000L

/**
 * The words a finished Stop call leaves: none once stopped (the turn's end shows it), else the
 * refusal's code, [STOP_NONE_RUNNING] when the server found nothing running, or [STOP_UNCONFIRMED].
 */
internal fun stopProblemOf(result: Result<Boolean>): String? = result.fold(
    { stopped -> if (stopped) null else STOP_NONE_RUNNING },
    { (it as? StopRefused)?.reason ?: STOP_UNCONFIRMED },
)

/**
 * One Stop tap's words, said through [say]: [STOP_UNCONFIRMED] once [waitMs] passes with no answer,
 * then always the answer's own ([stopProblemOf]) when [call] returns, so no tap ends in silence.
 */
internal suspend fun stopOutcome(waitMs: Long, call: suspend () -> Result<Boolean>, say: (String?) -> Unit): Result<Boolean> = coroutineScope {
    val answer = async { call() }
    if (withTimeoutOrNull(waitMs) { answer.await() } == null) say(STOP_UNCONFIRMED)
    answer.await().also { say(stopProblemOf(it)) }
}

/** A PTY stop's refusal (`control/pty.rs` `Refusal`) in words; each means no key reached the pane. */
@StringRes
internal fun stopRefusalRes(reason: String): Int =
    when (reason) {
        "boot_changed" -> R.string.stop_refused_boot_changed
        "pane_missing" -> R.string.stop_refused_pane_missing
        "agent_changed" -> R.string.stop_refused_agent_changed
        "popup_open" -> R.string.stop_refused_popup_open
        "not_working" -> R.string.stop_refused_not_working
        "not_visible" -> R.string.stop_refused_not_visible
        "scrolled" -> R.string.stop_refused_scrolled
        "no_stop_key" -> R.string.stop_refused_no_stop_key
        "screen_not_stoppable" -> R.string.stop_refused_screen
        "unsettled" -> R.string.stop_refused_unsettled
        "screen_unread" -> R.string.stop_refused_screen_unread
        "key_unsent" -> R.string.stop_refused_key_unsent
        else -> R.string.stop_refused_other
    }

/** A blocked agent without a usable structured dialog snapshot: keep the terminal route explicit. */
@Composable
private fun NeedsInputRow(asking: TranscriptBlock.ToolCall?, colors: ChatColors, onTerminal: () -> Unit, problem: String?, answerReason: String? = null) {
    StateRow(colors.blocked, colors.blocked, colors.warnBg, "chat:blocked") {
        Text(stringResource(R.string.chat_blocked_title), style = ChatType.button.copy(color = colors.blocked))
        Text(
            if (asking != null) stringResource(R.string.chat_blocked_tool, asking.name) else stringResource(R.string.chat_blocked_other),
            style = ChatType.meta.copy(color = colors.text),
        )
        if (!problem.isNullOrBlank()) Text(problem, style = ChatType.meta.copy(color = colors.muted))
        // No answer from Chat for this program: why, before the way that works (§3.5).
        answerReason?.let { Text(it, style = ChatType.meta.copy(color = colors.muted)) }
        FilledTonalButton(onClick = onTerminal, modifier = Modifier.heightIn(min = 48.dp).testTag("chat:answer_in_terminal")) {
            Text(stringResource(R.string.chat_answer_in_terminal))
        }
    }
}

@Composable
private fun ErrorRow(tool: ChatItem.Tool, colors: ChatColors, onTerminal: () -> Unit) {
    StateRow(colors.err, colors.err, colors.errBg, "chat:error") {
        Text(stringResource(R.string.chat_error_title), style = ChatType.button.copy(color = colors.err))
        val reason = tool.result?.text?.lineSequence()?.firstOrNull { it.isNotBlank() }?.trim()
        Text(
            listOfNotNull(tool.call?.name ?: tool.result?.name, reason).joinToString(": "),
            style = ChatType.meta.copy(color = colors.text),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        GlassButton(stringResource(R.string.chat_open_terminal), onClick = onTerminal, accent = colors.err)
    }
}
