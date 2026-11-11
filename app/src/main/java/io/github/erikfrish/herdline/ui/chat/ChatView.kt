package io.github.erikfrish.herdline.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.KeyboardArrowUp
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptRead
import io.github.erikfrish.herdline.core.transcriptRequest
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.session.OutageStrip
import io.github.erikfrish.herdline.ui.session.outageTime
import io.github.erikfrish.herdline.ui.session.rememberOutage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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

/**
 * The OMP agent in [pane] as a conversation, read from the session file
 * Herdr names for that pane. The agent keeps running in its terminal; this
 * only reads. Interactive dialogs are answered in the terminal: a blocked
 * agent shows a card that opens it, never buttons that pretend to answer.
 *
 * [onUnavailable] hears `true` when chat has no transcript to show and its
 * last read failed, and `false` once a read succeeds (also at first). A
 * refresh that fails after a transcript loaded is not unavailable: chat keeps
 * the transcript under its offline strip. Reads go on while this stays
 * composed, so keep it composed (hidden) to learn when chat can read again.
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
    onUnavailable: ((Boolean) -> Unit)? = null,
    tabTitle: String? = null,
    dense: Boolean = false,
    /** The caller's per-connection memory, so each pane read here comes back as it was (CT-1). */
    memory: ChatMemory = remember(connection) { ChatMemory() },
) {
    // A reconnect is a new Connection and starts over (the caller's memory is
    // keyed on it). A new OMP instance in the pane resets what it loaded (ChatHistory.merge).
    key(connection, pane) {
        ChatContent(connection, pane, memory.of(pane), accent, onTerminal, modifier, onUnavailable, tabTitle, dense)
    }
}

/** What each pane's Chat loaded and the reader opened, kept while another pane is read; one per connection. */
class ChatMemory {
    private val panes = HashMap<String, ChatKept>()
    internal fun of(pane: String): ChatKept = panes.getOrPut(pane) { ChatKept() }
}

internal class ChatKept {
    var history by mutableStateOf<ChatHistory?>(null)
    var problem by mutableStateOf<String?>(null)
    val expanded = mutableStateMapOf<String, Boolean>()
    var seen by mutableStateOf<String?>(null)
}

@Composable
private fun ChatContent(
    connection: Connection,
    pane: String,
    kept: ChatKept,
    accent: Color,
    onTerminal: () -> Unit,
    modifier: Modifier,
    onUnavailable: ((Boolean) -> Unit)?,
    tabTitle: String?,
    dense: Boolean,
) {
    var history by kept::history
    var problem by kept::problem
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

    LaunchedEffect(Unit) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                val known = history
                val read = known?.let { TranscriptRead.After(it.end) } ?: TranscriptRead.Tail
                val page = withContext(Dispatchers.IO) { connection.transcript(transcriptRequest(pane, known?.identity, read)) }
                page.onSuccess { read ->
                    // Merged into whatever is current now: a load of older entries may have finished meanwhile.
                    history = history?.merge(read, appended = true) ?: ChatHistory.of(read)
                    problem = null
                }.onFailure { problem = it.message ?: it.javaClass.simpleName }
                val status = history?.status
                withTimeoutOrNull(
                    when {
                        page.isFailure -> ERROR_POLL_MS
                        status == AgentStatus.Working || status == AgentStatus.Blocked -> BUSY_POLL_MS
                        else -> IDLE_POLL_MS
                    },
                ) { wake.receive() }
            }
        }
    }

    val shown = history
    // A refresh that fails after a transcript loaded. A lost link is the session's strip, not this one.
    val linkLive = connection.state.collectAsState().value is Connection.State.Connected
    var outage by rememberOutage(if (shown == null) null else problem == null)
    val items = remember(shown?.entries) { shown?.let { chatItems(it.entries) }.orEmpty() }
    val status = shown?.status ?: AgentStatus.Unknown
    val asking = if (status == AgentStatus.Blocked) pendingTool(items) else null
    // The call the agent is blocked on stands alone, so the waiting row below reads it in place.
    val lines = remember(items, asking) { chatLines(items, asking) }
    val titled = remember(lines) { lines.titledKey }
    val unavailable = shown == null && problem != null
    val report by rememberUpdatedState(onUnavailable)
    LaunchedEffect(unavailable) { report?.invoke(unavailable) }
    val header = if (shown?.hasOlder == true) 1 else 0

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

    // To the very end, also when the last item is taller than the list. A scroll of the reader's
    // own outranks it and wins; then their scroll decides.
    suspend fun pinToEnd() {
        try {
            list.scrollToEnd()
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive()
        }
    }

    LaunchedEffect(list, band) {
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
            if (scrolling && dragged) draggedScroll = true
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
        if (shown != null && linkLive && outage.strip && since != null) {
            OutageStrip(
                title = stringResource(R.string.outage_saved_at, outageTime(since)),
                detail = stringResource(R.string.chat_not_refreshed),
                retrying = false,
                onRetry = { wake.trySend(Unit) },
                onDismiss = { outage = outage.dismiss() },
                tag = "chat:offline",
            )
        }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                shown == null && problem != null -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(stringResource(R.string.chat_unavailable, problem.orEmpty()), style = ChatType.body.copy(color = colors.muted), textAlign = TextAlign.Center)
                    GlassButton(stringResource(R.string.chat_open_terminal), onClick = onTerminal, accent = accent)
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
                items.isEmpty() && !shown.hasOlder && status != AgentStatus.Working && status != AgentStatus.Blocked -> Text(
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
                            pill && dense -> PillHeight + DensePillGap * 2
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
                    items(lines, key = { it.key }) { line ->
                        Column(Modifier.padding(top = line.gap.dp())) {
                            when (line) {
                                is ChatLine.Single -> {
                                    if (line.labelled) AgentLabel(colors, tabTitle.takeIf { line.key == titled })
                                    ChatRow(line.item, colors, expanded)
                                }
                                is ChatLine.ToolGroup -> {
                                    if (line.labelled) AgentLabel(colors, tabTitle.takeIf { line.key == titled })
                                    ToolGroupRow(line, colors, expanded)
                                }
                            }
                        }
                    }
                    val failure = (items.lastOrNull() as? ChatItem.Tool)?.takeIf { it.failed }
                    when {
                        status == AgentStatus.Blocked -> item(key = "blocked") { LiveGap { NeedsInputRow(asking, colors, onTerminal) } }
                        status == AgentStatus.Working -> item(key = "working") { LiveGap { WorkingRow(workingOn(items), colors) } }
                        // Herdr has no agent error state; a turn that ended on a failed tool is the error chat can see.
                        failure != null -> item(key = "error") { LiveGap { ErrorRow(failure, colors, onTerminal) } }
                        else -> Unit
                    }
                }
            }
            if (pill) {
                LatestPill(fresh, colors, Modifier.align(Alignment.BottomCenter).padding(bottom = if (dense) DensePillGap else 12.dp)) {
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
 * "omp", then " · ‹tab›" in the quieter meta style when given the tab's title,
 * which only the first labelled line is; one line, ellipsised.
 */
@Composable
private fun AgentLabel(colors: ChatColors, tabTitle: String?) {
    val agent = stringResource(R.string.chat_agent_label)
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

@Composable
private fun LatestPill(count: Int, colors: ChatColors, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .height(PillHeight)
            .clip(CircleShape)
            .background(colors.surf)
            .border(1.dp, colors.border, CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp)
            .testTag("chat:latest"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.KeyboardArrowDown, null, Modifier.size(16.dp), tint = colors.accentText)
        Text(pluralStringResource(R.plurals.chat_latest_new, count, count), style = ChatType.button.copy(color = colors.accentText))
    }
}

@Composable
private fun ChatRow(item: ChatItem, colors: ChatColors, expanded: MutableMap<String, Boolean>) {
    val open = expanded[item.key] == true
    val toggle = { expanded[item.key] = !open }
    when (item) {
        // The row ends on the prose edge, so a bubble never sits far right of the reply.
        is ChatItem.User -> BoxWithConstraints(Modifier.widthIn(max = ProseMaxWidth).fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            val shape = MaterialTheme.shapes.medium
            Box(
                Modifier
                    .widthIn(max = minOf(maxWidth * 0.85f, BubbleMaxWidth))
                    .clip(shape)
                    .background(colors.userBubble)
                    .border(1.dp, colors.border, shape)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .testTag("chat:user"),
            ) {
                Markdown(item.text, colors)
            }
        }
        is ChatItem.Assistant -> Column(Modifier.fillMaxWidth().testTag("chat:assistant")) {
            Markdown(item.text, colors)
            if (item.truncated) Text(stringResource(R.string.chat_truncated), style = ChatType.meta.copy(color = colors.dim), modifier = Modifier.padding(top = 4.dp))
        }
        is ChatItem.Thinking -> {
            val count = remember(item.text) { item.text.trimEnd().lines().size }
            Aside(pluralStringResource(R.plurals.chat_thinking_lines, count, count), null, colors, open, toggle, italic = true) {
                Text(item.text, style = ChatType.aside.copy(color = colors.muted), modifier = Modifier.widthIn(max = ProseMaxWidth))
            }
        }
        is ChatItem.Tool -> ToolRow(item, colors, expanded)
        is ChatItem.Note -> Aside(item.role, item.text.lineSequence().firstOrNull(), colors, open, toggle, italic = false) {
            Markdown(item.text, colors, color = colors.muted)
        }
        is ChatItem.Compaction -> CompactionDivider(colors, open, toggle) {
            Markdown(item.summary, colors, color = colors.muted)
        }
        is ChatItem.Image -> Text(stringResource(R.string.chat_image), style = ChatType.body.copy(color = colors.muted))
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
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = colors.dim)
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
 * full chip's 48 dp would make every collapsed row taller. [spoken] is what
 * TalkBack reads; null when the glyph already said it.
 */
@Composable
private fun StatusChip(label: String, color: Color, spoken: String?) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color),
        modifier = Modifier.clearAndSetSemantics { if (spoken != null) contentDescription = spoken },
    ) {
        Text(label, style = ChatType.meta.copy(color = color), maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

/**
 * A tool as one 36 dp row: status, name, what it worked on, its state chip
 * (failed or `exit N`, denied), chevron. Open, the last [TOOL_TAIL_LINES]
 * lines of the result show on a container surface, with the arguments
 * behind their own disclosure. A failed tool starts open; a denied one,
 * which did not run, does not.
 */
@Composable
private fun ToolRow(item: ChatItem.Tool, colors: ChatColors, expanded: MutableMap<String, Boolean>) {
    val state = toolState(item)
    val result = item.result
    val exit = remember(result) { result?.let(::exitCode)?.takeIf { it != 0 } }
    val open = expanded[item.key] ?: (state == ToolState.Failed)
    val edge = when (state) {
        ToolState.Failed -> colors.err
        ToolState.Denied -> colors.blocked
        else -> colors.border
    }
    val shape = MaterialTheme.shapes.medium
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surf)
            .border(1.dp, edge, shape)
            .animateContentSize()
            .testTag("chat:tool"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 36.dp)
                .clickable(role = Role.Button) { expanded[item.key] = !open }
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolGlyph(state, colors)
            Text(item.name, style = ChatType.toolName.copy(color = colors.text), maxLines = 1)
            Text(
                item.summary(),
                style = ChatType.toolArg.copy(color = colors.muted),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            when {
                state == ToolState.Failed && exit != null ->
                    StatusChip(stringResource(R.string.chat_tool_exit, exit), colors.err, stringResource(R.string.chat_tool_exit_spoken, exit))
                state == ToolState.Failed -> StatusChip(stringResource(R.string.chat_tool_failed), colors.err, null)
                state == ToolState.Denied -> StatusChip(stringResource(R.string.chat_tool_denied), colors.blocked, null)
                else -> Unit
            }
            Icon(
                if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                stringResource(if (open) R.string.chat_hide_output else R.string.chat_show_output),
                Modifier.size(16.dp),
                tint = colors.dim,
            )
        }
        if (open) {
            Barred(if (state == ToolState.Done || state == ToolState.Running) colors.rule else edge, 2.dp, Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (result != null && result.text.isNotBlank()) {
                        val all = expanded["more:" + item.key] == true
                        val (tail, earlier) = remember(result.text) { outputTail(result.text, TOOL_TAIL_LINES) }
                        Surface(color = colors.codeChip, shape = RoundedCornerShape(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Text(
                                if (all) result.text.trimEnd('\n') else tail,
                                style = ChatType.toolOutput.copy(color = colors.text),
                                softWrap = false,
                                modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        if (!all && earlier > 0) {
                            val total = earlier + TOOL_TAIL_LINES
                            Disclosure(pluralStringResource(R.plurals.chat_show_all_lines, total, total), colors) { expanded["more:" + item.key] = true }
                        }
                    }
                    if (result != null && result.images > 0) {
                        Text(stringResource(R.string.chat_tool_images, result.images), style = ChatType.meta.copy(color = colors.muted))
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
}

@Composable
private fun Disclosure(label: String, colors: ChatColors, open: Boolean? = null, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (open != null) {
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp), tint = colors.accentText)
        }
        Text(label, style = ChatType.action.copy(color = colors.accentText))
    }
}

/** A run's one 48 dp control row: a disclosure glyph TalkBack names and [content]. */
@Composable
private fun RunRow(open: Boolean, colors: ChatColors, onClick: () -> Unit, content: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
            stringResource(if (open) R.string.chat_hide_steps else R.string.chat_show_steps),
            Modifier.size(16.dp),
            tint = colors.dim,
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
 */
@Composable
private fun ToolGroupRow(group: ChatLine.ToolGroup, colors: ChatColors, expanded: MutableMap<String, Boolean>) {
    val live = group.live
    // Kept in the list-wide map, not in the row: a run scrolled away and back is still held.
    LaunchedEffect(group.key, live) { if (live) expanded["held:" + group.key] = true }
    val open = expanded[group.key] == true
    val start = runStart(group, open, held = expanded["held:" + group.key] == true)
    val tools = group.tools
    val names = tools.map { it.name }.filter { it.isNotBlank() }.distinct().joinToString(", ")
    val counts = pluralStringResource(R.plurals.chat_tool_group, tools.size, tools.size) + " · " + names
    Column(Modifier.fillMaxWidth().animateContentSize().testTag("chat:tools")) {
        if (start == null) {
            RunRow(open = false, colors, onClick = { expanded[group.key] = true }) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(counts, style = ChatType.toolArg.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (group.failed > 0) {
                            Badge(containerColor = colors.errBg, contentColor = colors.err) {
                                Text(pluralStringResource(R.plurals.chat_tool_group_failed, group.failed, group.failed), style = ChatType.meta)
                            }
                        }
                        if (group.denied > 0) {
                            Badge(containerColor = colors.warnBg, contentColor = colors.blocked) {
                                Text(pluralStringResource(R.plurals.chat_tool_group_denied, group.denied, group.denied), style = ChatType.meta)
                            }
                        }
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
                    ChatRow(step, colors, expanded)
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

@Composable
private fun WorkingRow(what: String?, colors: ChatColors) {
    StateRow(colors.working, colors.border, Color.Transparent, "chat:working") {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = colors.working)
            Text(
                if (what != null) stringResource(R.string.chat_working_on, what) else stringResource(R.string.chat_working),
                style = ChatType.body.copy(color = colors.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A blocked agent. Its dialog is answered in the terminal, never here: no
 * choice is a button and nothing is sent from chat. When the one waiting
 * question is known ([pendingTool], [waitingQuestion]) it shows read-only,
 * its choices as plain text; otherwise only what is waiting is named.
 */
@Composable
private fun NeedsInputRow(asking: TranscriptBlock.ToolCall?, colors: ChatColors, onTerminal: () -> Unit) {
    val question = remember(asking) { asking?.let(::waitingQuestion) }
    StateRow(colors.blocked, colors.blocked, colors.warnBg, "chat:blocked") {
        Text(stringResource(R.string.chat_blocked_title), style = ChatType.button.copy(color = colors.blocked))
        if (question != null) {
            ListItem(
                headlineContent = { Text(question.question, style = ChatType.body.copy(color = colors.text)) },
                supportingContent = question.options.takeIf { it.isNotEmpty() }?.let { options ->
                    { Text(options.joinToString("\n") { "• $it" }, style = ChatType.meta.copy(color = colors.muted)) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.testTag("chat:question"),
            )
        }
        Text(
            when {
                question != null -> stringResource(R.string.chat_blocked_question)
                asking != null -> stringResource(R.string.chat_blocked_tool, asking.name)
                else -> stringResource(R.string.chat_blocked_other)
            },
            style = ChatType.meta.copy(color = colors.text),
        )
        val label = stringResource(R.string.chat_answer_in_terminal)
        val spoken = question?.let { stringResource(R.string.chat_answer_in_terminal_for, it.question) } ?: label
        FilledTonalButton(
            onClick = onTerminal,
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }.testTag("chat:answer_in_terminal"),
        ) {
            Text(label)
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
