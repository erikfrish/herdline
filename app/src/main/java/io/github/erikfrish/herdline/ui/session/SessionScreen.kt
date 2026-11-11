/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.session

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContextWrapper
import android.content.res.Configuration
import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.selection.selectableGroup
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Native
import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Viewport
import io.github.erikfrish.herdline.core.readableTitle
import io.github.erikfrish.herdline.data.AuthMethod
import io.github.erikfrish.herdline.data.SheetSection
import io.github.erikfrish.herdline.data.SheetSections
import io.github.erikfrish.herdline.data.TabsView
import io.github.erikfrish.herdline.ui.inbox.hostLabels
import io.github.erikfrish.herdline.data.DraftKey
import io.github.erikfrish.herdline.data.ModeChoice
import io.github.erikfrish.herdline.data.DraftStore
import io.github.erikfrish.herdline.data.composerDestination
import io.github.erikfrish.herdline.data.ModeKey
import io.github.erikfrish.herdline.data.PaneModes
import io.github.erikfrish.herdline.data.showsChat
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.service.AgentNotifications
import io.github.erikfrish.herdline.service.MonitorKey
import io.github.erikfrish.herdline.service.LockGate
import io.github.erikfrish.herdline.service.NoteSurface
import io.github.erikfrish.herdline.service.routeNote
import io.github.erikfrish.herdline.ui.chat.ChatMemory
import io.github.erikfrish.herdline.ui.chat.ChatView
import io.github.erikfrish.herdline.ui.LocalWindowClass
import io.github.erikfrish.herdline.ui.sessionSidebar
import io.github.erikfrish.herdline.ui.sideComposerWidthDp
import io.github.erikfrish.herdline.ui.components.PasswordsDialog
import io.github.erikfrish.herdline.ui.components.ConnectionGlyph
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.menuColor
import io.github.erikfrish.herdline.ui.glass.GlassIconButton
import io.github.erikfrish.herdline.ui.glass.GlassMenu
import io.github.erikfrish.herdline.ui.glass.GlassSnackbarHost
import io.github.erikfrish.herdline.ui.glass.GroupShape
import io.github.erikfrish.herdline.ui.glass.IconBadge
import androidx.compose.ui.draw.shadow
import io.github.erikfrish.herdline.ui.terminal.extraKeysFrom
import io.github.erikfrish.herdline.ui.terminal.ExtraKeysRow
import io.github.erikfrish.herdline.ui.terminal.TerminalTypefaces
import io.github.erikfrish.herdline.ui.terminal.TerminalView
import io.github.erikfrish.herdline.ui.terminal.OutputFocusStrip
import io.github.erikfrish.herdline.ui.terminal.OutputFocusStripHeight
import io.github.erikfrish.herdline.ui.terminal.OutputWindow
import io.github.erikfrish.herdline.ui.terminal.armedWindow
import io.github.erikfrish.herdline.ui.terminal.drag
import io.github.erikfrish.herdline.ui.terminal.next
import io.github.erikfrish.herdline.ui.terminal.suggestedHidden
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import io.github.erikfrish.herdline.ui.theme.LocalCompact
import io.github.erikfrish.herdline.ui.theme.sessionAccent
import io.github.erikfrish.herdline.ui.theme.terminalScheme
import io.github.erikfrish.herdline.ui.theme.color
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.max

/** Below this window height (a phone held sideways) the header and tab row share one 48 dp row. */
private val MergedHeaderHeight: Dp = 480.dp
private val MergedTitleWidth: Dp = 160.dp

/** What a confirmation or text dialog is for. */
internal sealed interface Prompt {
    data class NewTab(val workspaceId: String) : Prompt
    data class RenameTab(val id: String, val label: String) : Prompt
    data class RenameWorkspace(val id: String, val label: String) : Prompt
    data class CloseTab(val id: String, val label: String) : Prompt
    data class ClosePane(val id: String) : Prompt
    data class CloseWorkspace(val id: String, val label: String) : Prompt
    data class Disconnect(val host: String) : Prompt
}

/**
 * An attached Herdr session: the switcher holds workspaces, tabs, panes and
 * agents, the tab row and pane bar the focused tab's neighbours, the
 * terminal the focused tab's panes as the daemon lays them out, and the
 * composer, folded or open, sits under it near the thumb.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SessionScreen(
    app: HerdlineApp,
    connection: Connection,
    focusPane: String?,
    dark: Boolean,
    onLeave: () -> Unit,
    /** Opens Settings above the session, from the session menu; Back returns here. */
    onSettings: () -> Unit,
) {
    val context = LocalContext.current
    val settings by app.settings.settings.collectAsState()
    val fontChanges by app.fonts.changes.collectAsState()
    val state by connection.state.collectAsState()
    val view by connection.view.collectAsState()
    val frame by connection.frame.collectAsState()
    var switcherOpen by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf(SwitcherFilter.All) }
    val pins by app.targets.pins.collectAsState()
    val recent by app.targets.recent.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    // Herdr's notifications and the host's problems, as the top banner (BN-1), one shown at a time.
    var banners by remember { mutableStateOf(BannerQueue()) }
    var noteCount by remember { mutableLongStateOf(0L) }
    var latched by remember { mutableIntStateOf(0) }
    var menu by remember { mutableStateOf(false) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    // The agent pane whose pin sheet is open.
    var pinFor by remember { mutableStateOf<String?>(null) }
    val pinOrUnpin: (String) -> Unit = { pane ->
        val nodeId = connection.node.id
        if (app.monitor.isMonitored(nodeId, pane)) {
            app.monitor.unpin(setOf(MonitorKey(nodeId, pane)))
        } else {
            switcherOpen = false
            pinFor = pane
        }
    }
    var askPasswords by remember { mutableStateOf<List<Node>?>(null) }
    var terminal by remember { mutableStateOf<TerminalView?>(null) }
    // What the terminal view last drew, for the composer's checks (HG-VIS);
    // a new connection starts with none, which the native core refuses.
    var drawnViewport by remember(connection) { mutableStateOf<Viewport?>(null) }
    // The pane the composer's draft is for; see composerDestination below.
    var composerPane by rememberSaveable { mutableStateOf<String?>(null) }
    // CT-1: the pane Chat reads on this phone and its daemon run (ReadingTarget), apart from Herdr's focus.
    var readingPane by rememberSaveable { mutableStateOf<String?>(null) }
    var readingBoot by rememberSaveable { mutableStateOf<String?>(null) }
    // The pane last read in each tab on this phone, for a choice of the tab (paneForTab).
    val lastRead = remember { mutableStateMapOf<String, String>() }
    // A Terminal tab or workspace choice: the tab whose pane Herdr focuses next becomes the one read here.
    var pendingTab by remember { mutableStateOf<String?>(null) }
    /** Reads [pane] here. Nothing is asked of Herdr. */
    fun readHere(pane: String) {
        val shown = connection.view.value ?: return
        readingPane = pane
        readingBoot = shown.boot
        pendingTab = null
        shown.panes.firstOrNull { it.id == pane }?.let { lastRead[it.tab] = pane }
    }
    // Raw input: the terminal's keyboard and extra keys take the composer's place.
    var rawKeys by rememberSaveable { mutableStateOf(false) }
    // Output focus: armed only by the user, per session screen; a
    // reconnect is a new Connection and starts with it off.
    var outputFocus by rememberSaveable(connection) { mutableStateOf(false) }
    var outputWindow by remember(connection) { mutableStateOf<OutputWindow?>(null) }
    // A K12 notice reads the bottom rows: the full terminal while it shows.
    var receiptNotice by remember { mutableStateOf(false) }
    fun setOutputFocus(on: Boolean) {
        outputFocus = on
        outputWindow = null
    }
    val scope = rememberCoroutineScope()
    var composerField by remember { mutableStateOf(false) }
    // The pane-row estimate behind the too-short notice's offers (PaneRows.kt):
    // one terminal row, and what the terminal and the composer share, measured.
    var cellPx by remember { mutableIntStateOf(0) }
    var contentPx by remember { mutableIntStateOf(0) }
    var composerPx by remember { mutableIntStateOf(0) }
    // Per orientation, the space last seen with the keyboard down.
    val openSpace = remember { mutableStateMapOf<Int, SessionSpace>() }
    val orientation = LocalConfiguration.current.orientation
    // Chat or Terminal per pane, persisted; Chat is the default where Chat can read the agent.
    val modes = remember { PaneModes.get(context) }
    val modeChoices by modes.choices.collectAsState()
    val imeVisible = WindowInsets.isImeVisible
    val monitored by app.monitor.monitored.collectAsState()
    val node = connection.node
    // The Tabs overview's chosen workspace (TB1 §4): kept across closing the sheet, Grid ⇄ List and rotation.
    var tabsScope by rememberSaveable(node.id, node.session) { mutableStateOf<String?>(null) }
    // The node's colour label marks this session: the bar under the tabs, the
    // selected tab, and the terminal cursor. A chosen app accent overrides it.
    val accent = sessionAccent(settings.accent, node.color)
    val scheme = settings.terminalScheme(dark).withCursor(accent)
    // Spelled out rather than produceState, whose lint check misses this assignment.
    var typefaces by remember { mutableStateOf<TerminalTypefaces?>(null) }
    LaunchedEffect(settings.font, fontChanges) { typefaces = app.fonts.typefaces(settings.font) }

    /** Reconnect along the node's route, asking for any unsaved passwords it needs first. */
    fun retry() {
        val needed = app.passwordsToAsk(app.nodes.get(node.id) ?: node)
        if (needed.isEmpty()) app.reconnect(node, emptyMap(), dark) else askPasswords = needed
    }

    // Set while the passwords dialog collects sign-ins for installing or updating Herdr.
    var setupAfterPasswords by remember { mutableStateOf(false) }
    fun setupHerdr() {
        val needed = app.passwordsToAsk(app.nodes.get(node.id) ?: node)
        if (needed.isEmpty()) {
            app.setupHerdr(node, emptyMap(), dark)
        } else {
            setupAfterPasswords = true
            askPasswords = needed
        }
    }

    LaunchedEffect(scheme, state) { connection.setTheme(scheme.toJson().toString()) }
    LaunchedEffect(typefaces, terminal) { typefaces?.let { terminal?.setTypefaces(it) } }
    LaunchedEffect(settings.fontSize, terminal) { terminal?.fontSizeSp = settings.fontSize }
    LaunchedEffect(settings.keepScreenOn, terminal) { terminal?.keepScreenOn = settings.keepScreenOn }
    LaunchedEffect(settings.terminalSuggestions, terminal) { terminal?.keyboardSuggestions = settings.terminalSuggestions }
    // While the composer's field has the keyboard, the daemon's pane keeps its size so Send stays allowed;
    // the Tabs sheet holds it too, for its whole life, so hiding the keyboard under it never grows the pane.
    LaunchedEffect(composerField, imeVisible, switcherOpen, terminal) { terminal?.holdGrid = (composerField && imeVisible) || switcherOpen }
    // An unconfirmed delivery's pane is never resized while the agent takes it (S-4a).
    LaunchedEffect(receiptNotice, terminal) { terminal?.freezeRows = receiptNotice }
    LaunchedEffect(frame) { terminal?.frame = frame }
    LaunchedEffect(connection) {
        connection.events.collect { event ->
            when (event) {
                is Connection.Event.Clipboard -> context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Herdline", event.text))
                is Connection.Event.Bell -> if (settings.vibrateOnBell) terminal?.performHapticFeedback(BellHaptic)
                is Connection.Event.Problem -> banners = banners.add(AgentNote(event.message, null, NoteKind.Problem, null, ++noteCount))
                // The service makes the same decision and posts the system notification instead (BN-1 §2).
                is Connection.Event.Notification -> {
                    val route = routeNote(
                        viewing = app.viewingSession == node.id,
                        locked = LockGate.isDeviceLocked(context),
                        enabled = if (event.attention) settings.notifyAttention else settings.notifyDone,
                    )
                    if (route == NoteSurface.Banner) {
                        val shown = connection.view.value
                        banners = banners.add(
                            AgentNote(
                                // The system notification's words: Herdr's title, and where the pane is.
                                title = readableTitle(event.title) ?: node.title,
                                location = event.pane?.let { pane -> shown?.let { notificationBody(it, pane) } } ?: event.body,
                                kind = if (event.attention) NoteKind.Attention else NoteKind.Finished,
                                pane = event.pane,
                                id = ++noteCount,
                            ),
                        )
                    }
                }
            }
        }
    }

    // Collie rows 4 and 5: a loss after the screen was live shows a strip over
    // the last screen; the recovery from one whose strip was shown says
    // "Reconnected" once, and a new loss takes that back.
    var reconnected by remember { mutableStateOf<Job?>(null) }
    var outage by rememberOutage(
        live = state is Connection.State.Connected,
        onDrop = { reconnected?.cancel() },
        onReconnected = {
            reconnected = scope.launch { snackbar.showSnackbar(context.getString(R.string.session_reconnected)) }
        },
    )
    // A loss of a live screen: the strip, not the card, which keeps the states that need a decision.
    val lostLive = state is Connection.State.Disconnected && outage.wasLive

    BackHandler { onLeave() }

    // The composer writes to the viewed pane while its draft is empty; written
    // text stays with its pane until the user moves it (see composerPane).
    val drafts = remember { DraftStore.get(context) }
    var composerText by remember(composerPane) {
        mutableStateOf(composerPane?.let { drafts.get(DraftKey(node.id, node.session, it)) }?.blank == false)
    }
    // Files in the composer's draft, for its height forecast (composerMaxPx); the editor reports 0 when it goes.
    var composerFiles by remember { mutableStateOf(0) }
    // Herdr's shared focus: what the desktop and Terminal show.
    val desktopPane = frame?.panes?.firstOrNull { it.focused }?.id ?: view?.focusedPane
    // CT-1 (ChatTarget.kt): the pane read here, adopted once, then moved only by a choice on this phone.
    val reading = readingFor(readingPane?.let { p -> readingBoot?.let { ReadingTarget(p, it) } }, view?.boot, opened = focusPane, desktop = desktopPane)
    LaunchedEffect(reading) {
        readingPane = reading?.pane
        readingBoot = reading?.boot
        reading?.let { r -> connection.view.value?.panes?.firstOrNull { it.id == r.pane }?.let { lastRead[it.tab] = r.pane } }
    }
    // The read pane's title while it is there, so S5 can still name it once it has closed.
    var readingTitle by remember { mutableStateOf("") }
    LaunchedEffect(reading?.pane, view) {
        reading?.pane?.takeIf { p -> view?.panes?.any { it.id == p } == true }?.let { readingTitle = view?.paneTitle(it).orEmpty() }
    }
    // Each pane read here keeps its own Chat scroll anchor and follow state (CT-1 §3).
    val chatState = rememberSaveableStateHolder()
    // ...and its own loaded transcript, for this connection.
    val chatMemory = remember(connection) { ChatMemory() }
    val closed = readingClosed(reading, view)
    /** Whether [pane] shows Chat: an OMP pane whose choice, or the default, is Chat. */
    fun chatFor(pane: String?): Boolean {
        val shown = view ?: return false
        val agent = pane?.let { shown.agentIn(it)?.agent }
        return pane != null && showsChat(modeChoices, ModeKey(node.id, node.session, pane), shown.boot, agent, chatAvailable = agent == "omp")
    }
    // Chat reads OMP's session file, so it is offered for OMP agents only.
    val chatPane = reading?.pane?.takeIf { view?.agentIn(it)?.agent == "omp" }
    // A choice holds for one daemon run and program: a restarted daemon or a new
    // program in a pane forgets it, so a mode never carries over to a replaced process.
    LaunchedEffect(view) {
        val shown = view ?: return@LaunchedEffect
        modes.forget(node.id, node.session, shown.boot) { pane -> shown.agentIn(pane)?.agent }
    }
    val chatWanted = chatFor(chatPane)
    // While Chat can't read the agent's transcript, the tab shows Terminal and
    // switches back by itself once a read succeeds; the stored choice stays
    // (r3 §17.4). ChatView stays composed, hidden, so it keeps trying.
    var chatUnreadable by remember(connection, chatPane) { mutableStateOf(false) }
    // S5: the pane read in Chat closed. Chat says so and keeps the draft; it never falls back to the desktop.
    var chatShown by remember { mutableStateOf(false) }
    LaunchedEffect(chatWanted, chatUnreadable, closed) { if (!closed) chatShown = chatWanted && !chatUnreadable }
    val chatMode = if (closed) chatShown else chatWanted && !chatUnreadable
    // What is on screen: the pane read here in Chat, the desktop's in Terminal.
    val focusedPane = if (chatMode) reading?.pane?.takeIf { !closed } else desktopPane
    val viewedPane = focusedPane
    // A delivery in flight keeps the composer on its pane until it settles (HB2).
    val flying by drafts.inFlight.collectAsState()
    val unconfirmed by drafts.unconfirmed.collectAsState()
    LaunchedEffect(viewedPane, composerText, flying, unconfirmed) {
        composerPane = composerDestination(composerPane, viewedPane, composerText, boundHeld = composerPane?.let { drafts.releasesComposer(DraftKey(node.id, node.session, it)) } == true)
    }
    // Panes whose delivery is unconfirmed: their tabs carry the lock (M5).
    val held = remember(unconfirmed, node.id, node.session) { drafts.held(node.id, node.session) }
    // Terminal without the composer (Settings › Composer): the terminal and its extra keys take its place. A delivery
    // waiting on its receipt or steer, or left unconfirmed, keeps the composer: its editor holds that state and Unlock.
    val composerPinned = receiptNotice || composerPane?.let { it in held || DraftKey(node.id, node.session, it) in flying } == true
    val composerHidden = !chatMode && !settings.terminalComposer && !composerPinned
    val composerKept = !chatMode && !settings.terminalComposer && composerPinned
    LaunchedEffect(composerKept) { if (composerKept) snackbar.showSnackbar(context.getString(R.string.terminal_composer_kept)) }
    // Hidden, the composer measures nothing: no stale height, open space or held grid for the terminal to keep.
    LaunchedEffect(composerHidden, terminal) {
        if (composerHidden) {
            composerPx = 0
            openSpace.clear()
            terminal?.holdHeightPx = 0
        }
    }
    // The pane on screen has been seen: withdraw its notification, on a deep-link
    // arrival, a tab switch and every return to the app (lab.3 D7: resuming onto
    // the pane left its notification posted, since the pane itself never changed).
    LifecycleResumeEffect(viewedPane) {
        viewedPane?.let { AgentNotifications.clear(context, node.id, it) }
        onPauseOrDispose { }
    }
    // While this session is resumed its notifications are its banner, not the system's (BN-1 §2).
    LifecycleResumeEffect(node.id) {
        app.viewingSession = node.id
        onPauseOrDispose { if (app.viewingSession == node.id) app.viewingSession = null }
    }
    // A completion of the pane on screen says nothing new, and a question goes once answered (BN-1 §3).
    val blockedPanes = view?.agents?.filter { it.status == AgentStatus.Blocked }?.mapTo(HashSet()) { it.pane }
    LaunchedEffect(banners, viewedPane, blockedPanes) { banners = banners.settle(viewedPane, blockedPanes) }
    // Rotating closes the banner shown; it doesn't come back (BN-1 §4).
    var bannerOrientation by remember { mutableIntStateOf(orientation) }
    LaunchedEffect(orientation) {
        if (orientation != bannerOrientation) {
            bannerOrientation = orientation
            banners.shown?.let { banners = banners.dismiss(it.id) }
        }
    }

    // The header names what is on screen: in Chat the tab and workspace read here, never the desktop's.
    val tab = if (chatMode) {
        focusedPane?.let { p -> view?.panes?.firstOrNull { it.id == p }?.tab }?.let { id -> view?.tabs?.firstOrNull { it.id == id } }
    } else {
        view?.tab
    }
    val workspace = tab?.workspace?.let { id -> view?.workspaces?.firstOrNull { it.id == id } } ?: view?.workspace
    val tabs = workspace?.let { view?.tabsOf(it.id) }.orEmpty()
    // S2: where the desktop is, said in Chat only when it isn't the pane read here.
    val desktopOther = desktopElsewhere(focusedPane, desktopPane).takeIf { chatMode }
    val groups = remember(view) { view?.let(::topology).orEmpty() }
    // Chat has no terminal keyboard: whichever way Chat comes on screen, raw keys and the IME go.
    LaunchedEffect(chatMode) {
        if (chatMode) {
            terminal?.hideKeyboard()
            rawKeys = false
        }
    }
    // While the composer on screen holds a draft, its notice coming and going never resizes the pane (V4-a).
    LaunchedEffect(composerText, composerHidden, rawKeys, chatMode, terminal) {
        terminal?.keepRows = composerText && !composerHidden && !(rawKeys && !chatMode)
    }
    // S3: once per switch from Chat to Terminal while the desktop is on another pane; only its action moves the desktop.
    // It uses the screen's existing notice slot (the snackbar) and goes when Chat returns or the two panes match.
    var terminalNotice by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(chatMode, desktopPane, reading) {
        if (chatMode || reading?.pane == desktopPane) terminalNotice = null
    }
    LaunchedEffect(terminalNotice) {
        val pane = terminalNotice ?: return@LaunchedEffect
        val shown = connection.view.value ?: return@LaunchedEffect
        val desktop = desktopPane ?: return@LaunchedEffect
        val result = snackbar.showSnackbar(
            context.getString(R.string.terminal_shows_desktop, shown.paneTitle(desktop), shown.paneTitle(pane)),
            actionLabel = context.getString(R.string.focus_reading_shared, shown.paneTitle(pane)),
            withDismissAction = true,
            duration = SnackbarDuration.Long,
        )
        if (result == SnackbarResult.ActionPerformed && connection.view.value?.panes?.any { it.id == pane } == true) connection.focusPane(pane)
        terminalNotice = null
    }

    /**
     * A choice of [pane] on this phone: it is read here. From Terminal it also moves the desktop to it;
     * from Chat ([fromChat]) only a pane that shows Terminal does ([choiceMovesDesktop]).
     */
    fun choosePane(pane: String, fromChat: Boolean = chatMode) {
        readHere(pane)
        if (choiceMovesDesktop(fromChat, chatFor(pane))) connection.focusPane(pane)
    }

    /** A choice of [tab]: from Chat, a pane read in Chat is read here only; otherwise Herdr focuses the tab and picks its pane. */
    fun chooseTab(tab: String) {
        val shown = view ?: return
        val pane = paneForTab(shown, tab, lastRead)
        if (pane != null && !choiceMovesDesktop(chatMode, chatFor(pane))) {
            readHere(pane)
        } else {
            pendingTab = tab
            connection.focusTab(tab)
        }
    }

    /** A choice of [workspace]: as [chooseTab] for its active tab. */
    fun chooseWorkspace(workspace: String) {
        val shown = view ?: return
        val pane = paneForWorkspace(shown, workspace, lastRead)
        if (pane != null && !choiceMovesDesktop(chatMode, chatFor(pane))) {
            readHere(pane)
        } else {
            pendingTab = shown.workspaces.firstOrNull { it.id == workspace }?.activeTab
            connection.focusWorkspace(workspace)
        }
    }
    // A Terminal tab or workspace choice lands where Herdr puts the focus: that pane is then the one read here.
    LaunchedEffect(pendingTab, desktopPane) {
        val wanted = pendingTab ?: return@LaunchedEffect
        val landed = desktopPane?.takeIf { p -> view?.panes?.firstOrNull { it.id == p }?.tab == wanted } ?: return@LaunchedEffect
        pendingTab = null
        readHere(landed)
    }
    // A notification, deep link or Agents row: that pane, read here in Chat; only Terminal focuses it on the desktop.
    // It arrives rather than being chosen from the screen shown, so only the pane's own mode decides.
    val linked = view?.boot?.takeIf { focusPane != null && state is Connection.State.Connected && view?.panes?.any { it.id == focusPane } == true }
    LaunchedEffect(focusPane, linked) {
        if (linked != null && focusPane != null) choosePane(focusPane, fromChat = true)
    }
    fun showChat(on: Boolean, pane: String? = chatPane) {
        pane ?: return
        val boot = view?.boot ?: return
        val agent = view?.agentIn(pane)?.agent ?: return
        modes.set(ModeKey(node.id, node.session, pane), ModeChoice(boot, agent, on))
        // S3: Terminal shows the desktop's pane; say so when it isn't the one read here.
        terminalNotice = if (!on && desktopPane != pane) pane else null
    }

    /** The Chat | Terminal control: choosing the shown mode does nothing; Chat needs an OMP pane. */
    fun chooseMode(chat: Boolean) {
        // In Terminal on a pane read here without Chat, the desktop's OMP pane on screen is the one asked for.
        val desktopOmp = desktopPane?.takeIf { chat && !chatMode && chatPane == null && view?.agentIn(it)?.agent == "omp" }
        when {
            chat && chatUnreadable -> scope.launch { snackbar.showSnackbar(context.getString(R.string.session_chat_unreadable)) }
            desktopOmp != null -> {
                readHere(desktopOmp)
                showChat(true, desktopOmp)
            }
            chat == chatWanted -> Unit
            chat && chatPane == null -> {
                val shown = view
                val tabName = tab?.let { shown?.tabTitle(it.id) }.orEmpty()
                val program = focusedPane?.let { shown?.agentIn(it) }?.let { it.agent ?: it.name }
                val text = if (program == null) {
                    context.getString(R.string.session_chat_shell, tabName)
                } else {
                    context.getString(R.string.session_chat_other_agent, tabName, program)
                }
                scope.launch { snackbar.showSnackbar(text) }
            }
            else -> showChat(chat)
        }
    }

    fun openSwitcher() {
        // The phone sheet starts each open on the saved filter, once, so Grid ⇄ List keeps a chip tap (TB1 §4).
        if (SheetSections.shows(settings.sheetSections, SheetSection.Filters)) filter = settings.startFilter.switcherFilter
        switcherOpen = true
        // Held before the keyboard starts to go, not on the next effect pass.
        terminal?.holdGrid = true
        terminal?.hideKeyboard()
    }

    fun toggleSidebar() {
        app.settings.update { it.copy(sidebarOpen = !it.sidebarOpen) }
    }

    /** Goes where the user tapped, and remembers it as recent. In Chat that moves only what is read here (CT-1). */
    fun go(target: SwitchTarget) {
        when (target) {
            is SwitchTarget.TabRow -> chooseTab(target.tab.id)
            is SwitchTarget.PaneRow -> choosePane(target.pane.id)
        }
        app.targets.visited(node.id, target.key)
        switcherOpen = false
    }

    val actions = SwitcherActions(
        go = ::go,
        workspace = { chooseWorkspace(it.id); switcherOpen = false },
        pin = { app.targets.togglePin(node.id, it) },
        monitor = pinOrUnpin,
        newWorkspace = { connection.newWorkspace(workspace?.id); switcherOpen = false },
        newTab = { prompt = Prompt.NewTab(it.id) },
        renameWorkspace = { prompt = Prompt.RenameWorkspace(it.id, it.label) },
        closeWorkspace = { prompt = Prompt.CloseWorkspace(it.id, it.label) },
        renameTab = { prompt = Prompt.RenameTab(it.id, it.label) },
        closeTab = { prompt = Prompt.CloseTab(it.id, it.label) },
    )
    val switcher: @Composable (Modifier) -> Unit = { modifier ->
        Switcher(
            node = node,
            view = view,
            state = state,
            accent = accent,
            order = settings.targetOrder,
            pins = pins[node.id].orEmpty(),
            recent = recent[node.id].orEmpty(),
            monitored = { MonitorKey(node.id, it) in monitored },
            query = query,
            onQuery = { query = it },
            filter = filter,
            onFilter = { filter = it },
            actions = actions,
            modifier = modifier,
            here = focusedPane,
            reading = chatMode,
        )
    }

    val chromeColor = Color(0xff000000 or scheme.background)

    BoxWithConstraints(Modifier.fillMaxSize().background(chromeColor)) {
        // The app-wide window class, so this sidebar and the root rail never disagree.
        val wide = sessionSidebar(settings.layoutMode, LocalWindowClass.current)
        // The session window in pixels, for the pane-row estimate (PaneRows.kt).
        val windowPx = constraints.maxWidth to constraints.maxHeight
        val windowWidth = maxWidth
        LaunchedEffect(wide) { if (wide) switcherOpen = false }
        // A sideways phone typing: the keyboard leaves about 138 dp, which the status
        // bar, the merged header and the composer used up (lab.6 I2). While it is up,
        // the header and status bar step aside so the transcript or terminal keeps
        // its latest lines; the composer still names the target. Back restores both.
        val short = maxHeight < MergedHeaderHeight
        val typingShort = short && imeVisible
        val hostView = LocalView.current
        DisposableEffect(typingShort, hostView) {
            val window = generateSequence(hostView.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull()
                ?.window
            val bars = window?.let { WindowCompat.getInsetsController(it, hostView) }
            if (typingShort && bars != null) {
                bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                bars.hide(WindowInsetsCompat.Type.statusBars())
            }
            onDispose { if (typingShort) bars?.show(WindowInsetsCompat.Type.statusBars()) }
        }
        // One tree for both layouts: the sidebar is added beside the same session
        // column, so the terminal view, composer and drafts survive a resize.
        Row(Modifier.fillMaxSize()) {
            if (wide) {
                SessionSidebar(
                    open = settings.sidebarOpen,
                    workspaces = view?.workspaces.orEmpty(),
                    onToggle = ::toggleSidebar,
                    onWorkspace = actions.workspace,
                    modifier = Modifier.width(if (settings.sidebarOpen) SidebarWidth else SidebarRailWidth).fillMaxHeight(),
                ) { switcher(Modifier.fillMaxSize()) }
                Box(Modifier.width(1.dp).fillMaxHeight().background(accent.copy(alpha = 0.35f)))
            }
            Box(Modifier.weight(1f)) {
                // Sideways and typing, the composer becomes a column beside the content (J2).
                val sessionWidth = if (wide) windowWidth - (if (settings.sidebarOpen) SidebarWidth else SidebarRailWidth) - 1.dp else windowWidth
                val sideComposer = sideComposerWidthDp(sessionWidth.value, typingShort)?.dp?.takeIf { !composerHidden }
                // Typing beside the content, Terminal only: the armed window over the grid.
                val focusWanted = outputFocus && sideComposer != null && !chatMode && !rawKeys
                val focusApplies = focusWanted && !receiptNotice
                val stripPx = with(LocalDensity.current) { OutputFocusStripHeight.roundToPx() }
                LaunchedEffect(frame, focusApplies, outputFocus) {
                    val shown = frame
                    // Suspended F (the receipt notice, the side composer or IME on the move) arms anew when it applies again.
                    if (!focusApplies) outputWindow = null
                    if (!focusApplies || shown == null) return@LaunchedEffect
                    val pane = shown.panes.firstOrNull { it.focused }
                    val suggestion = pane?.let { suggestedHidden(shown, it) }
                    outputWindow = outputWindow?.next(pane?.id, suggestion, terminal?.hasSelection == true, shown.rows)
                        ?: armedWindow(pane?.id, suggestion, shown.rows)
                }
                val hiddenRows = if (focusApplies) outputWindow?.hidden ?: 0 else 0
                SessionColumn(sideComposer, Modifier.fillMaxSize().background(chromeColor).imePadding()) {
                    // A flat wash of the node's colour behind the header, lent to the glass controls on it.
                    val headerColor = accent.copy(alpha = if (dark) 0.14f else 0.1f).compositeOver(chromeColor)
                    Box {
                        Box(
                            Modifier
                                .matchParentSize()
                                .background(headerColor),
                        )
                            Column(Modifier.statusBarsPadding()) {
                                val blocked = groups.agentsWith(AgentStatus.Blocked)
                                val up = @Composable {
                                    GlassIconButton(
                                        Icons.AutoMirrored.Filled.ArrowBack,
                                        stringResource(R.string.session_up),
                                        onClick = onLeave,
                                        modifier = Modifier.testTag("session:up"),
                                    )
                                }
                                val title = workspace?.let { readableTitle(it.label) } ?: node.title
                                // Up, the workspace label, the tabs, what needs the user, the one
                                // Chat | Terminal control, and ⋮ (Herdr actions, Settings, Disconnect).
                                val trailing = @Composable {
                                    if (blocked.isNotEmpty()) {
                                        AttentionChip(
                                            pluralStringResource(R.plurals.need_you_chip, blocked.size, blocked.size),
                                            AgentStatus.Blocked.color(),
                                            "session:need_you",
                                        ) {
                                            groups.nextNeedingInput(focusedPane)?.let(::go)
                                        }
                                    }
                                    ModeSwitch(chat = chatMode, chatAvailable = chatPane != null, accent = accent, onMode = ::chooseMode)
                                    Box {
                                        GlassIconButton(
                                            Icons.Default.MoreVert,
                                            stringResource(R.string.more),
                                            onClick = { menu = true },
                                            modifier = Modifier.testTag("session:more"),
                                        )
                                        SessionMenu(
                                            expanded = menu,
                                            onDismiss = { menu = false },
                                            connection = connection,
                                            workspaceId = workspace?.id,
                                            tabId = tab?.id,
                                            tabLabel = tab?.label,
                                            pane = focusedPane,
                                            zoomed = tab?.zoomed == true,
                                            split = (tab?.let { t -> view?.panesOf(t.id)?.size } ?: 0) > 1,
                                            paneActions = !wide,
                                            settingsFirst = short,
                                            onPrompt = { prompt = it },
                                            onDisconnect = { prompt = Prompt.Disconnect(node.title) },
                                            // The strip can be hidden; the menu keeps a way to retry.
                                            onReconnect = if (lostLive) ::retry else null,
                                            onSettings = onSettings,
                                            pinned = focusedPane?.takeIf { view?.agentIn(it) != null }?.let { MonitorKey(node.id, it) in monitored },
                                            outputFocus = outputFocus.takeIf { !chatMode && !wide },
                                            onOutputFocus = ::setOutputFocus,
                                            onPin = { focusedPane?.let(pinOrUnpin) },
                                        )
                                    }
                                }
                                val readingHereLabel = stringResource(R.string.chat_reading_here)
                                val onDesktopLabel = stringResource(R.string.chat_on_desktop)
                                val tabRow = @Composable {
                                    if (tabs.isNotEmpty()) {
                                        TabRow(
                                            tabs = tabs,
                                            title = { t -> view?.tabTitle(t.id) ?: readableTitle(t.label) ?: t.number.toString() },
                                            selectedId = tab?.id,
                                            heldPane = { view?.heldPaneOf(it.id, held) },
                                            accent = accent,
                                            // In Chat the selection is the tab read here; the desktop's, if another, says so (CT-1).
                                            tabState = { t ->
                                                when {
                                                    !chatMode -> null
                                                    t.id == tab?.id -> readingHereLabel
                                                    desktopOther != null && t.id == view?.panes?.firstOrNull { it.id == desktopOther }?.tab -> onDesktopLabel
                                                    else -> null
                                                }
                                            },
                                            onTab = ::chooseTab,
                                            // n/N opens the switcher wherever it is not already in view.
                                            onTabs = when {
                                                !wide -> ::openSwitcher
                                                settings.sidebarOpen -> null
                                                else -> ::toggleSidebar
                                            },
                                            onRename = { prompt = Prompt.RenameTab(it.id, it.label) },
                                            onClose = { prompt = Prompt.CloseTab(it.id, it.label) },
                                        )
                                    }
                                }
                                if (typingShort) {
                                    // Nothing: the keyboard is up in a short window (see typingShort).
                                } else if (short) {
                                    // Sideways phone: one row, so the content keeps the height. The
                                    // host and tab count move out; the dot keeps the link state.
                                    Row(
                                        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 6.dp).testTag("session:header_merged"),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        up()
                                        ConnectionGlyph(state)
                                        Text(
                                            title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.widthIn(max = MergedTitleWidth).padding(horizontal = 4.dp).testTag("session:title"),
                                        )
                                        Box(Modifier.weight(1f)) { tabRow() }
                                        trailing()
                                    }
                                } else {
                                    Row(
                                        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        up()
                                        // A plain label: the tab row and the Tabs sheet are the ways to move.
                                        Column(
                                            Modifier
                                                .weight(1f)
                                                .padding(horizontal = 6.dp, vertical = 4.dp)
                                                .testTag("session:place"),
                                        ) {
                                            Text(
                                                title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.testTag("session:title"),
                                            )
                                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                ConnectionGlyph(state)
                                                val place = listOfNotNull(
                                                    node.title,
                                                    tabs.size.takeIf { it > 0 }?.let { pluralStringResource(R.plurals.session_tab_count, it, it) },
                                                )
                                                // S2: last, so it is what ellipsizes first.
                                                val detail = place + listOfNotNull(desktopOther?.let { stringResource(R.string.chat_desktop_elsewhere, view?.paneTitle(it).orEmpty()) })
                                                val spoken = desktopOther?.let {
                                                    place.joinToString(" · ") + ". " +
                                                        stringResource(R.string.chat_desktop_elsewhere_spoken, focusedPane?.let { p -> view?.paneTitle(p) }.orEmpty(), view?.paneTitle(it).orEmpty())
                                                }
                                                Text(
                                                    detail.joinToString(" · "),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis,
                                                    modifier = Modifier
                                                        .then(if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier)
                                                        .testTag("session:context"),
                                                )
                                            }
                                        }
                                        trailing()
                                    }
                                    tabRow()
                                }
                                val shown = view
                                if (!typingShort && shown != null && tab != null) {
                                    val panes = shown.panesOf(tab.id)
                                    // Phone: a split tab's panes only. Wide: also the pane toolbar, so
                                    // splitting and zooming are reachable from any tab.
                                    if (panes.size > 1 || wide) {
                                        PaneBar(
                                            panes = panes,
                                            view = shown,
                                            focused = focusedPane,
                                            zoomed = tab.zoomed,
                                            accent = accent,
                                            toolbar = wide,
                                            onPane = ::choosePane,
                                            onZoom = { focusedPane?.let(connection::toggleZoom) },
                                            onSplit = { direction -> focusedPane?.let { connection.split(it, direction) } },
                                            onClosePane = { focusedPane?.let { prompt = Prompt.ClosePane(it) } },
                                        )
                                    }
                                }
                            }
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(accent.copy(alpha = 0.6f)),
                    )
                    // Nothing in Chat or Terminal draws above this box, under the
                    // tab row and its accent line: overscroll stretch and bubble
                    // shadows would otherwise spill up (lab.7 L3).
                    Box(Modifier.layoutId(ContentSlot).fillMaxSize().clipToBounds().onSizeChanged { contentPx = it.height }) {
                        // A reconnect is a new Connection: rebuild the view so its
                        // listener never feeds a closed one.
                        key(connection) {
                            // The window's size and the keyboard as it animates: what may resize the grid.
                            val windowKey = listOf(windowPx, WindowInsets.ime.getBottom(LocalDensity.current), orientation)
                            // A screen-reader user who skips the strip still hears that rows are hidden.
                            val windowDescription = if (hiddenRows > 0) pluralStringResource(R.plurals.output_focus_strip_description, hiddenRows, hiddenRows) else null
                            AndroidView(
                                // TerminalView fills its canvas with drawColor; unclipped it would paint over the header.
                                modifier = Modifier.fillMaxSize().clipToBounds().testTag("terminal"),
                                factory = { ctx ->
                                    TerminalView(ctx).also { view ->
                                        view.fontSizeSp = app.settings.settings.value.fontSize
                                        typefaces?.let(view::setTypefaces)
                                        view.listener = terminalListener(
                                            app,
                                            connection,
                                            view,
                                            onCell = { cellPx = it },
                                            drew = { drawnViewport = it },
                                            windowDrag = { lines ->
                                                outputWindow?.takeIf { view.hiddenRows > 0 }?.drag(lines)?.let { (window, used) ->
                                                    outputWindow = window
                                                    used
                                                } ?: 0
                                            },
                                            onLatchCleared = { latched = it },
                                            // A tap on a pane in the mirror is a choice on this phone: read there, as Terminal focuses it.
                                            onPaneTapped = { readHere(it) },
                                        )
                                        terminal = view
                                    }
                                },
                                update = { view ->
                                    view.latchedMeta = latched
                                    view.windowKey = windowKey
                                    // Terminal on screen owns Herdr's grid; hidden under Chat the view sends only its first one.
                                    view.sharesGrid = !chatMode
                                    view.scheme = scheme
                                    view.frame = frame
                                    view.showWindow(hiddenRows, if (focusApplies) stripPx else 0)
                                    view.contentDescription = windowDescription
                                },
                            )
                        }
                        if (focusApplies) {
                            OutputFocusStrip(hiddenRows, onOff = { setOutputFocus(false) }, modifier = Modifier.align(Alignment.BottomCenter))
                        }
                        if (chatMode && closed) {
                            // S5: the pane read here closed; Chat says so and never falls back to the desktop.
                            Text(
                                stringResource(R.string.chat_pane_closed, readingTitle),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).padding(24.dp).testTag("chat:pane_closed"),
                            )
                        }
                        if (chatWanted && chatPane != null) chatState.SaveableStateProvider(chatPane) {
                            ChatView(
                                connection,
                                chatPane,
                                accent,
                                onTerminal = { showChat(false) },
                                modifier = if (chatUnreadable) Modifier.size(0.dp) else Modifier,
                                onUnavailable = { unreadable ->
                                    if (unreadable && !chatUnreadable) {
                                        // The fallback happens on its own, so it
                                        // stays long enough to be read (lab.4 G5).
                                        scope.launch {
                                            snackbar.showSnackbar(
                                                context.getString(R.string.session_chat_unreadable),
                                                duration = SnackbarDuration.Long,
                                            )
                                        }
                                    }
                                    chatUnreadable = unreadable
                                },
                                // The tab's readable title (Titles.kt), never a pane ID.
                                tabTitle = tab?.let { t -> view?.tabTitle(t.id) },
                                dense = typingShort,
                                memory = chatMemory,
                            )
                        }
                        if (state is Connection.State.Connecting) {
                            LinearProgressIndicator(
                                Modifier.fillMaxWidth().height(2.dp).align(Alignment.TopCenter),
                                color = accent,
                                trackColor = Color.Transparent,
                            )
                        }
                        // The node behind a rejected sign-in, so its password can be asked again.
                        val rejected = (state as? Connection.State.AuthRejected)?.let { rejection ->
                            app.nodes.route(app.nodes.get(node.id) ?: node).getOrNull()?.getOrNull(rejection.hop)
                        }
                        outage.since?.takeIf { outage.strip }?.let { since ->
                            val lost = state as? Connection.State.Disconnected
                            if (lost != null || state is Connection.State.Connecting) {
                                // A retry is a new Connection, whose screen starts empty: it says so.
                                OutageStrip(
                                    title = if (lost != null) {
                                        stringResource(R.string.outage_saved_at, outageTime(since))
                                    } else {
                                        stringResource(R.string.outage_reconnecting)
                                    },
                                    detail = if (lost != null) {
                                        stringResource(R.string.outage_not_connected, lost.reason)
                                    } else {
                                        stringResource(R.string.outage_last_live, outageTime(since))
                                    },
                                    retrying = lost == null,
                                    onRetry = ::retry,
                                    onDismiss = { outage = outage.dismiss() },
                                    modifier = Modifier.align(Alignment.TopCenter),
                                    tag = "session:outage",
                                )
                            }
                        }
                        if (!lostLive) {
                            StateCard(
                                state = state,
                                accent = accent,
                                rejectedPassword = rejected?.auth == AuthMethod.Password,
                                onRetry = ::retry,
                                onSignIn = { rejected?.let { askPasswords = listOf(it) } },
                                onTrust = { trusted ->
                                    app.nodes.trust(trusted.host, trusted.fingerprint)
                                    retry()
                                },
                                onSetup = ::setupHerdr,
                                onLeave = { app.disconnect(node.id); onLeave() },
                            )
                        }
                        // Over the terminal's top edge, under the tab row: an overlay that never resizes the grid (BN-1).
                        val note = banners.settle(viewedPane, blockedPanes).shown
                        val notePane = note?.pane?.takeIf { pane -> view?.panes?.any { it.id == pane } == true }
                        AgentBannerSlot(
                            note,
                            openable = notePane != null && notePane != viewedPane,
                            openLabel = notePane?.let { view?.paneTitle(it) },
                            onOpen = { pane ->
                                // Exactly that pane, if it is still there when Open is tapped.
                                val there = connection.view.value?.panes?.any { it.id == pane } == true
                                if (there) choosePane(pane)
                                there
                            },
                            onDismiss = { id -> banners = banners.dismiss(id) },
                        )
                        GlassSnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
                    }
                    if ((rawKeys || composerHidden) && !chatMode) {
                        // Raw input: the terminal's own keys, and one way back to the composer when it is shown.
                        Column(Modifier.navigationBarsPadding()) {
                            if (settings.showExtraKeys) {
                                ExtraKeysRow(
                                    keys = remember(settings.extraKeys) { extraKeysFrom(settings.extraKeys) },
                                    latched = latched,
                                    accent = accent,
                                    background = chromeColor,
                                    onLatch = { latched = it; terminal?.latchedMeta = it },
                                    onKey = { code, repeat -> terminal?.sendKey(code, 0, 0, repeat) },
                                    onText = { connection.text(focusedPane, it) },
                                    compact = LocalCompact.current,
                                )
                            }
                            if (!composerHidden) Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                                GlassButton(
                                    stringResource(R.string.session_back_to_composer),
                                    onClick = {
                                        terminal?.hideKeyboard()
                                        rawKeys = false
                                    },
                                    icon = Icons.Default.Edit,
                                    modifier = Modifier.align(Alignment.Center).heightIn(min = 48.dp).testTag("session:back_to_composer"),
                                )
                            }
                        }
                    } else {
                        val density = LocalDensity.current
                        // Beside the content, the composer takes none of its height; the
                        // output-focus strip takes its own from the terminal's.
                        val contentSpace = contentPx - if (focusApplies) stripPx else 0
                        val now = SessionSpace(windowPx.first, windowPx.second, if (sideComposer != null) contentSpace else contentSpace + composerPx)
                        LaunchedEffect(now, imeVisible, orientation) {
                            if (!imeVisible && contentPx > 0 && composerPx > 0) openSpace[orientation] = now
                        }
                        // Beside the side composer the held grid keeps the rows of the whole column, not
                        // the stacked layout's, whose composer grows with the font (S-4a, PaneRows.kt).
                        val sideHold = sideHoldPx(
                            side = sideComposer != null,
                            open = openSpace[orientation],
                            windowWidthPx = windowPx.first,
                            windowHeightPx = windowPx.second,
                            navBarPx = WindowInsets.navigationBars.getBottom(density),
                        )
                        LaunchedEffect(sideHold, terminal) { terminal?.holdHeightPx = sideHold ?: 0 }
                        val reach = shortPaneReach(
                            paneRows = composerPane?.let { p -> frame?.panes?.firstOrNull { it.id == p }?.rect?.height },
                            gridRows = frame?.rows ?: 0,
                            cellPx = cellPx,
                            landscape = orientation == Configuration.ORIENTATION_LANDSCAPE,
                            imeVisible = imeVisible,
                            open = openSpace[orientation],
                            now = now,
                            // Font scale grows the composer's text; never below the dp budget. A draft
                            // with files adds the chip row (ComposerFit.Regular.chips).
                            composerMaxPx = with(density) {
                                (ComposerFit.Regular.heightDp(ComposerFit.Regular.maxLines, notice = true, attachments = composerFiles) * fontScale.coerceAtLeast(1f)).dp.roundToPx()
                            } + WindowInsets.navigationBars.getBottom(density),
                            portraitExtraPx = with(density) { PORTRAIT_CHROME_EXTRA_DP.dp.roundToPx() },
                            sideHeld = sideHold != null,
                        )
                        Composer(
                            connection = connection,
                            pane = composerPane,
                            view = view,
                            frame = frame,
                            accent = accent,
                            onRetarget = { composerPane = it },
                            onHasText = { composerText = it },
                            onAttachments = { composerFiles = it },
                            onFieldFocus = { composerField = it },
                            onTerminalKeyboard = {
                                showChat(false)
                                rawKeys = true
                                terminal?.showKeyboard()
                            },
                            onOpenTerminal = {
                                // From Chat, Terminal shows the desktop as it is (S3 offers the move); from Terminal, as before.
                                if (!chatMode) composerPane?.takeIf { it != focusedPane }?.let(connection::focusPane)
                                showChat(false)
                            },
                            // In Chat the pane read here, also once it has closed (S5): the draft never drifts to the desktop's.
                            reading = reading?.pane?.takeIf { chatMode },
                            onOpenPane = { if (chatMode) choosePane(it) else connection.focusPane(it) },
                            onShowShared = { connection.focusPane(it) },
                            onZoom = {
                                val target = composerPane
                                val targetTab = target?.let { p -> view?.panes?.firstOrNull { it.id == p } }?.let { p -> view?.tabs?.firstOrNull { it.id == p.tab } }
                                // Zoom is shared with the desktop: only ever turn it on here, never toggle it off by surprise.
                                if (target != null && targetTab?.zoomed == false) connection.toggleZoom(target)
                            },
                            modifier = Modifier.onSizeChanged { composerPx = it.height }.navigationBarsPadding(),
                            hideKeyboardReachesSend = reach.hideKeyboard,
                            portraitReachesSend = reach.portrait,
                            side = sideComposer != null,
                            outputFocus = outputFocus.takeIf { !chatMode },
                            onOutputFocus = ::setOutputFocus,
                            // What landed in the agent's input must be seen before it is edited or run;
                            // the receipt's own notice may still be suspending F when it lands.
                            onDelivered = { if (focusWanted) setOutputFocus(false) },
                            onReceiptNotice = { receiptNotice = it },
                            // The grid in force at the tap, before the delivery changes the layout (HG-1, S-4a-r).
                            onDeliveryStart = { terminal?.freezeAt() },
                            viewport = drawnViewport,
                            // Read at the tap, right after onDeliveryStart, from the view itself.
                            captureViewport = { terminal?.viewport() },
                        )
                    }
                }
            }
        }
        if (switcherOpen && !wide) {
            ModalBottomSheet(
                onDismissRequest = { switcherOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = menuColor(),
                modifier = Modifier.testTag("switcher:sheet"),
            ) {
                // TB1: the overview, or today's list; either one's header switches to the other.
                val showAs: (TabsView) -> Unit = { picked -> app.settings.update { it.copy(tabsView = picked) } }
                when (settings.tabsView) {
                    TabsView.Grid -> TabsOverview(
                        node = node,
                        host = hostLabels(app.connections.value.values.map { it.node })[node.id] ?: node.title,
                        view = view,
                        state = state,
                        pins = pins[node.id].orEmpty(),
                        monitored = { MonitorKey(node.id, it) in monitored },
                        query = query,
                        onQuery = { query = it },
                        filter = filter,
                        onFilter = { filter = it },
                        filtersShown = SheetSections.shows(settings.sheetSections, SheetSection.Filters),
                        scope = tabsScope,
                        onScope = { tabsScope = it },
                        actions = actions,
                        onShowList = { showAs(TabsView.List) },
                        modifier = Modifier.fillMaxWidth(),
                        here = focusedPane,
                        reading = chatMode,
                    )
                    TabsView.List -> Switcher(
                        node = node,
                        view = view,
                        state = state,
                        accent = accent,
                        order = settings.targetOrder,
                        pins = pins[node.id].orEmpty(),
                        recent = recent[node.id].orEmpty(),
                        monitored = { MonitorKey(node.id, it) in monitored },
                        query = query,
                        onQuery = { query = it },
                        filter = filter,
                        onFilter = { filter = it },
                        actions = actions,
                        modifier = Modifier.fillMaxWidth(),
                        here = focusedPane,
                        reading = chatMode,
                        onShowGrid = { showAs(TabsView.Grid) },
                        appliesStartFilter = false,
                        scope = tabsScope,
                        onScope = { tabsScope = it },
                    )
                }
            }
        }
    }

    prompt?.let { current ->
        PromptDialog(current, connection, onDisconnect = { app.disconnect(node.id); onLeave() }) { prompt = null }
    }
    pinFor?.let { pane ->
        PinSheet(
            live = app.settings.liveLockDetail.value,
            saved = remember { app.settings.savedLockDetail() },
            nodeId = node.id,
            node = node.title,
            pane = pane,
            agent = view?.agentIn(pane)?.displayName ?: pane,
            onPin = { level ->
                app.settings.chooseLiveLockDetail(level)
                app.monitor.pin(node.id, pane)
                pinFor = null
            },
            onDismiss = { pinFor = null },
        )
    }
    askPasswords?.let { hops ->
        PasswordsDialog(
            hops = hops,
            onConnect = { secrets, remember ->
                askPasswords = null
                if (setupAfterPasswords) app.setupHerdr(node, secrets, dark, remember) else app.reconnect(node, secrets, dark, remember)
                setupAfterPasswords = false
            },
            onDismiss = {
                askPasswords = null
                setupAfterPasswords = false
            },
        )
    }
}

/** How much of a failed install or update the state card shows. */
private const val SETUP_OUTPUT_LINES = 40

/** REJECT, the closest haptic to a bell, exists from API 30; older devices get a long press. */
private val BellHaptic =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS

private fun pasteClipboard(context: android.content.Context, connection: Connection, pane: String?) {
    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip ?: return
    val text = (0 until clip.itemCount).joinToString("") { clip.getItemAt(it).coerceToText(context) }
    if (text.isNotEmpty()) connection.paste(pane, text)
}

private fun terminalListener(
    app: HerdlineApp,
    connection: Connection,
    view: TerminalView,
    onCell: (Int) -> Unit,
    drew: (Viewport?) -> Unit,
    windowDrag: (Int) -> Int,
    onLatchCleared: (Int) -> Unit,
    onPaneTapped: (String) -> Unit,
) = object : TerminalView.Listener {
    private fun pane() = view.frame?.panes?.firstOrNull { it.focused }?.id

    override fun onGrid(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        app.settings.lastGrid = intArrayOf(cols, rows, cellWidth, cellHeight)
        onCell(cellHeight)
        connection.resize(cols, rows, cellWidth, cellHeight)
    }

    override fun onViewport(viewport: Viewport?) = drew(viewport)

    override fun onFocusPane(pane: RenderFrame.PaneArea) {
        onPaneTapped(pane.id)
        connection.focusPane(pane.id)
    }

    override fun onTap(pane: RenderFrame.PaneArea?, column: Int, row: Int) =
        connection.touch(pane?.id ?: "", Native.TOUCH_TAP, column, row)

    override fun onScroll(pane: RenderFrame.PaneArea?, column: Int, row: Int, up: Boolean, lines: Int) =
        connection.touch(pane?.id ?: "", if (up) Native.TOUCH_SCROLL_UP else Native.TOUCH_SCROLL_DOWN, column, row, lines)

    override fun onKey(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean) =
        connection.key(pane(), keyCode, metaState, unicode, repeat)

    override fun onText(text: String) = connection.text(pane(), text)

    override fun onChord(codepoint: Int, metaState: Int) = connection.chord(pane(), codepoint, metaState)

    override fun onCopy(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int) {
        val text = connection.selectionText(startColumn, startRow, endColumn, endRow)
        if (text.isNotEmpty()) {
            view.context.getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("Herdline", text))
        }
    }

    override fun onPaste() = pasteClipboard(view.context, connection, pane())

    override fun onFontSize(sp: Float) {
        app.settings.update { it.copy(fontSize = sp.coerceIn(8f, 24f)) }
    }

    override fun onModifiersConsumed() = onLatchCleared(0)

    override fun onWindowDrag(towardBottom: Int) = windowDrag(towardBottom)
}

/**
 * The session ⋮, in the same groups at every width: the tab, the shared pane
 * actions (phone only; [paneActions] false on wide layouts, where the pane
 * toolbar has them), then Settings above Disconnect…. In a short window
 * ([settingsFirst], a phone held sideways) the menu cannot show every row, so
 * Settings and Disconnect… come first and stay reachable without scrolling
 * (lab.6 limit, DR10). Workspace actions live on the workspace rows of the
 * Tabs sheet and sidebar.
 */
@Composable
private fun SessionMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    connection: Connection,
    workspaceId: String?,
    tabId: String?,
    tabLabel: String?,
    pane: String?,
    zoomed: Boolean,
    /** The tab has more than one pane: closing its only pane would close the tab. */
    split: Boolean,
    paneActions: Boolean,
    settingsFirst: Boolean,
    onPrompt: (Prompt) -> Unit,
    onDisconnect: () -> Unit,
    /** Set while the link is lost: Retry stays here when the outage strip is hidden. */
    onReconnect: (() -> Unit)?,
    onSettings: () -> Unit,
    /** Whether the focused pane's agent is pinned as a live update; null when it runs no agent. */
    pinned: Boolean?,
    onPin: () -> Unit,
    /** Whether output focus is armed; null where the item is not offered (Chat, or a wide layout). */
    outputFocus: Boolean?,
    onOutputFocus: (Boolean) -> Unit,
) {
    @Composable
    fun item(
        label: Int,
        icon: ImageVector,
        enabled: Boolean = true,
        destructive: Boolean = false,
        subtitle: Int? = null,
        tag: String? = null,
        run: () -> Unit,
    ) {
        val color = (if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
            .let { if (enabled) it else it.copy(alpha = 0.38f) }
        DropdownMenuItem(
            text = {
                Column {
                    Text(stringResource(label), color = color)
                    if (subtitle != null) {
                        Text(stringResource(subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            leadingIcon = { Icon(icon, null, tint = color.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) },
            enabled = enabled,
            onClick = { onDismiss(); run() },
            contentPadding = PaddingValues(horizontal = 18.dp),
            modifier = if (tag != null) Modifier.testTag(tag) else Modifier,
        )
    }

    @Composable
    fun divider() = HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

    @Composable
    fun account() {
        item(R.string.settings, Icons.Default.Settings, tag = "session:menu:settings") { onSettings() }
        onReconnect?.let { item(R.string.reconnect, Icons.Default.Refresh, tag = "session:menu:reconnect") { it() } }
        item(R.string.session_disconnect, Icons.AutoMirrored.Filled.ExitToApp, destructive = true, tag = "session:menu:disconnect") { onDisconnect() }
    }

    GlassMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (settingsFirst) {
            account()
            divider()
        }
        item(R.string.new_tab, Icons.Default.Add, workspaceId != null, tag = "session:menu:new_tab") { workspaceId?.let { onPrompt(Prompt.NewTab(it)) } }
        item(R.string.rename_tab, Icons.Default.Edit, tabId != null) { tabId?.let { onPrompt(Prompt.RenameTab(it, tabLabel.orEmpty())) } }
        item(R.string.session_close_tab, Icons.Default.Close, tabId != null, destructive = true) { tabId?.let { onPrompt(Prompt.CloseTab(it, tabLabel.orEmpty())) } }
        if (pinned != null) {
            divider()
            item(if (pinned) R.string.monitor_stop else R.string.monitor_agent, Icons.Default.Notifications, tag = "session:menu:pin") { onPin() }
        }
        if (paneActions) {
            divider()
            item(R.string.session_split_right_shared, Icons.AutoMirrored.Filled.KeyboardArrowRight, pane != null) { pane?.let { connection.split(it, "right") } }
            item(R.string.session_split_down_shared, Icons.Default.KeyboardArrowDown, pane != null) { pane?.let { connection.split(it, "down") } }
            // Zoom is Herdr's: the desktop zooms too, so the item says so.
            item(if (zoomed) R.string.session_unzoom_shared else R.string.zoom_shared, Icons.Default.Search, pane != null, subtitle = R.string.session_zoom_help) {
                pane?.let(connection::toggleZoom)
            }
            // As in the pane toolbar: the only pane is closed with its tab, by Close tab.
            if (split) {
                item(R.string.session_close_pane, Icons.Default.Close, pane != null, destructive = true) { pane?.let { onPrompt(Prompt.ClosePane(it)) } }
            }
            if (outputFocus != null) {
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(stringResource(R.string.output_focus_menu), color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                stringResource(R.string.output_focus_help),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    leadingIcon = { Icon(Icons.Default.KeyboardArrowDown, null, tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) },
                    trailingIcon = { Checkbox(checked = outputFocus, onCheckedChange = null) },
                    onClick = { onDismiss(); onOutputFocus(!outputFocus) },
                    contentPadding = PaddingValues(horizontal = 18.dp),
                    modifier = Modifier
                        .testTag("session:menu:output_focus")
                        .semantics { role = Role.Checkbox; toggleableState = ToggleableState(outputFocus) },
                )
            }
        }
        if (!settingsFirst) {
            divider()
            account()
        }
    }
}

/** The header's one-tap way to what needs the user: the next agent asking, or the working ones. */
@Composable
private fun AttentionChip(label: String, color: Color, tag: String, onClick: () -> Unit) {
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.2f))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * One segmented control for the viewed pane: Chat or Terminal, both always
 * labelled, the selected one tinted. Chat reads OMP's transcript, so on any
 * other pane it is dimmed and a tap explains why.
 */
@Composable
private fun ModeSwitch(chat: Boolean, chatAvailable: Boolean, accent: Color, onMode: (Boolean) -> Unit) {
    val outline = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f)
    Row(
        Modifier
            .height(36.dp)
            .clip(CircleShape)
            .border(1.dp, outline, CircleShape)
            .selectableGroup()
            .testTag("session:mode"),
    ) {
        ModeSegment(stringResource(R.string.chat), chat, chatAvailable, accent, "session:chat") { onMode(true) }
        ModeSegment(stringResource(R.string.chat_terminal), !chat, true, accent, "session:terminal") { onMode(false) }
    }
}

@Composable
private fun ModeSegment(label: String, selected: Boolean, enabled: Boolean, accent: Color, tag: String, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) accent.copy(alpha = 0.24f) else Color.Transparent, label = "segment")
    val text = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.38f) }
    Box(
        Modifier
            .fillMaxHeight()
            .widthIn(min = 52.dp)
            .clip(CircleShape)
            .background(fill)
            .then(if (selected) Modifier.border(1.dp, accent, CircleShape) else Modifier)
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(horizontal = 10.dp)
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/**
 * The Panes row: on a split tab, the focused tab's panes as outlined chips,
 * so a phone too narrow to read a split can still pick a pane by name. As the
 * wide layout's pane toolbar ([toolbar]) it also carries Split, Zoom and Close
 * pane, the ⋮ menu's pane group on phones. Zoom and splits are Herdr's and
 * every attached client sees them, so each says "(shared)".
 */
@Composable
private fun PaneBar(
    panes: List<Pane>,
    view: ShellView,
    focused: String?,
    zoomed: Boolean,
    accent: Color,
    toolbar: Boolean,
    onPane: (String) -> Unit,
    onZoom: () -> Unit,
    onSplit: (String) -> Unit,
    onClosePane: () -> Unit,
) {
    val split = panes.size > 1
    Row(
        Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (split) {
            Text(
                stringResource(R.string.session_panes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
        LazyRow(
            Modifier.weight(1f).testTag("session:panes"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (split) {
                itemsIndexed(panes, key = { _, pane -> pane.id }) { _, pane ->
                    val chosen = pane.id == focused
                    val outline = if (chosen) accent else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f)
                    Row(
                        Modifier
                            .height(30.dp)
                            .widthIn(max = 200.dp)
                            .clip(CircleShape)
                            .border(1.dp, outline, CircleShape)
                            .clickable(role = Role.Tab) { onPane(pane.id) }
                            .padding(horizontal = 12.dp)
                            .testTag("pane-chip:${pane.id}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        view.agentIn(pane.id)?.let { Box(Modifier.size(7.dp).clip(CircleShape).background(it.status.color())) }
                        Text(
                            view.paneTitle(pane.id),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
        if (toolbar) {
            PaneAction(stringResource(R.string.session_split_right_shared), false, accent, "session:split_right") { onSplit("right") }
            PaneAction(stringResource(R.string.session_split_down_shared), false, accent, "session:split_down") { onSplit("down") }
        }
        PaneAction(stringResource(if (zoomed) R.string.session_unzoom_shared else R.string.zoom_shared), zoomed, accent, "session:zoom", onZoom)
        if (toolbar && split) {
            PaneAction(stringResource(R.string.session_close_pane), false, accent, "session:close_pane", onClosePane)
        }
    }
}

@Composable
private fun PaneAction(label: String, on: Boolean, accent: Color, tag: String, onClick: () -> Unit) {
    val fill by animateColorAsState(if (on) accent.copy(alpha = 0.28f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), label = "paneAction")
    Text(
        label,
        style = MaterialTheme.typography.labelLarge,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(fill)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
            .testTag(tag),
    )
}

@Composable
private fun StateCard(
    state: Connection.State,
    accent: Color,
    rejectedPassword: Boolean,
    onRetry: () -> Unit,
    onSignIn: () -> Unit,
    onTrust: (Connection.State.UntrustedHostKey) -> Unit,
    onSetup: () -> Unit,
    onLeave: () -> Unit,
) {
    val (title, body) = when (state) {
        is Connection.State.Connecting, is Connection.State.Connected -> return
        is Connection.State.Disconnected -> stringResource(R.string.disconnected) to state.reason
        is Connection.State.AuthRejected -> stringResource(R.string.sign_in_rejected) to stringResource(
            if (rejectedPassword) R.string.password_rejected_text else R.string.key_rejected_text,
            state.host,
        )
        is Connection.State.Incompatible -> {
            val version = state.serverVersion ?: stringResource(R.string.unknown_version)
            if (state.daemonOutdated) {
                stringResource(R.string.incompatible_daemon_title) to
                    stringResource(R.string.incompatible_daemon_text, version, state.minVersion)
            } else {
                stringResource(R.string.incompatible_app_title) to stringResource(R.string.incompatible_app_text, version)
            }
        }
        is Connection.State.Failed -> stringResource(R.string.could_not_connect) to state.reason
        is Connection.State.HerdrMissing -> stringResource(R.string.herdr_missing_title) to stringResource(R.string.herdr_missing_text)
        is Connection.State.SettingUp -> stringResource(R.string.herdr_setup_running_title) to stringResource(R.string.herdr_setup_running_text)
        is Connection.State.SetupFailed -> stringResource(R.string.herdr_setup_failed_title) to stringResource(
            R.string.herdr_setup_failed_text,
            state.exitStatus?.toString() ?: stringResource(R.string.unknown_exit_status),
        )
        is Connection.State.UntrustedHostKey -> stringResource(
            if (state.changed) R.string.host_key_changed else R.string.host_key_unknown,
        ) to stringResource(
            if (state.changed) R.string.host_key_changed_text else R.string.host_key_unknown_text,
            if (state.hop > 0) stringResource(R.string.host_on_hop, state.host, state.hop + 1) else state.host,
            state.fingerprint,
        )
    }
    val warning = state is Connection.State.UntrustedHostKey && state.changed
    val (icon, tone) = when (state) {
        is Connection.State.UntrustedHostKey -> Icons.Default.Lock to if (warning) MaterialTheme.colorScheme.error else accent
        is Connection.State.AuthRejected -> Icons.Default.Lock to MaterialTheme.colorScheme.error
        is Connection.State.Incompatible, is Connection.State.HerdrMissing, is Connection.State.SetupFailed ->
            Icons.Default.Warning to AgentStatus.Blocked.color()
        is Connection.State.SettingUp -> Icons.Default.Refresh to accent
        else -> Icons.Default.Refresh to MaterialTheme.colorScheme.error
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(20.dp)
            .testTag("session:state_card"),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .shadow(1.dp, GroupShape)
                .clip(GroupShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh, GroupShape)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                IconBadge(icon, tone, Modifier.size(44.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (state is Connection.State.SettingUp) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("session:setup_progress"), color = accent)
                GlassButton(stringResource(R.string.nodes), onClick = onLeave, modifier = Modifier.fillMaxWidth())
                return@Column
            }
            if (state is Connection.State.SetupFailed && state.output.isNotBlank()) {
                Text(
                    state.output.lines().takeLast(SETUP_OUTPUT_LINES).joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.testTag("session:setup_output"),
                )
            }
            // Installing or updating Herdr is offered only where it is the fix, and runs only on this tap.
            val setupLabel = when {
                state is Connection.State.Incompatible && state.daemonOutdated -> R.string.update_herdr
                state is Connection.State.HerdrMissing -> R.string.install_herdr
                state is Connection.State.SetupFailed -> R.string.try_again
                else -> null
            }
            setupLabel?.let {
                GlassButton(
                    stringResource(it),
                    onClick = onSetup,
                    primary = true,
                    accent = accent,
                    modifier = Modifier.fillMaxWidth().testTag("session:setup_herdr"),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
                GlassButton(stringResource(R.string.nodes), onClick = onLeave, modifier = Modifier.weight(1f))
                val (label, action) = when (state) {
                    is Connection.State.UntrustedHostKey -> stringResource(R.string.trust_and_connect) to { onTrust(state) }
                    is Connection.State.AuthRejected -> if (rejectedPassword) {
                        stringResource(R.string.enter_password) to onSignIn
                    } else {
                        stringResource(R.string.reconnect) to onRetry
                    }
                    else -> stringResource(R.string.reconnect) to onRetry
                }
                GlassButton(
                    label,
                    onClick = action,
                    primary = setupLabel == null,
                    accent = if (warning) MaterialTheme.colorScheme.error else accent,
                    modifier = Modifier.weight(1.4f).testTag("session:primary_action"),
                )
            }
        }
    }
}

/** Marks the terminal and transcript box among [SessionColumn]'s children. */
private object ContentSlot

/**
 * The session column: what comes before the [ContentSlot] child stacks on
 * top at full width; what comes after it is the composer. That goes under
 * the content, or, with a [side] width, into a column of that width at the
 * bottom right, beside the content, which then keeps the full height (J2).
 * One tree for both, so the terminal view and the draft survive the switch.
 */
@Composable
private fun SessionColumn(side: Dp?, modifier: Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val at = measurables.indexOfFirst { it.layoutId == ContentSlot }
        var top = 0
        val header = measurables.take(if (at < 0) measurables.size else at).map { m ->
            m.measure(Constraints(maxWidth = width, maxHeight = max(0, height - top))).also { top += it.height }
        }
        if (at < 0) {
            return@Layout layout(width, height) {
                var y = 0
                header.forEach { it.place(0, y); y += it.height }
            }
        }
        val left = max(0, height - top)
        val sidePx = side?.roundToPx()?.coerceAtMost(width)
        val composerWidth = sidePx ?: width
        var composerHeight = 0
        val composer = measurables.drop(at + 1).map { m ->
            val bounds = Constraints(minWidth = if (sidePx != null) composerWidth else 0, maxWidth = composerWidth, maxHeight = max(0, left - composerHeight))
            m.measure(bounds).also { composerHeight += it.height }
        }
        val body = measurables[at].measure(
            if (sidePx != null) Constraints.fixed(width - sidePx, left) else Constraints.fixed(width, max(0, left - composerHeight)),
        )
        layout(width, height) {
            var y = 0
            header.forEach { it.place(0, y); y += it.height }
            body.place(0, y)
            var composerY = if (sidePx != null) y + left - composerHeight else y + body.height
            val composerX = if (sidePx != null) width - sidePx else 0
            composer.forEach { it.place(composerX, composerY); composerY += it.height }
        }
    }
}
