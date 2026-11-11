package io.github.erikfrish.herdline.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.data.DraftStore
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.data.SheetSection
import io.github.erikfrish.herdline.data.SheetSections
import io.github.erikfrish.herdline.data.TargetOrder
import io.github.erikfrish.herdline.ui.components.NodeAvatar
import io.github.erikfrish.herdline.ui.components.StatusGlyph
import io.github.erikfrish.herdline.ui.components.statusSpoken
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.GlassMenu
import io.github.erikfrish.herdline.ui.glass.GlassMenuItem
import io.github.erikfrish.herdline.ui.theme.LocalCompact
import io.github.erikfrish.herdline.ui.theme.color
import io.github.erikfrish.herdline.service.MonitorKey
import io.github.erikfrish.herdline.service.StatusClock
import kotlinx.coroutines.delay

/** What the switcher's rows and menus do; every one is a user's tap, never automatic. */
class SwitcherActions(
    val go: (SwitchTarget) -> Unit,
    val workspace: (Workspace) -> Unit,
    val pin: (String) -> Unit,
    val monitor: (String) -> Unit,
    val newWorkspace: () -> Unit,
    val newTab: (Workspace) -> Unit,
    val renameWorkspace: (Workspace) -> Unit,
    val closeWorkspace: (Workspace) -> Unit,
    val renameTab: (Tab) -> Unit,
    val closeTab: (Tab) -> Unit,
)

/**
 * Herdr's sidebar for a phone or a tablet: a host line, search, the Order
 * selector, the Needs you and Working filters, pinned and recent places as
 * one chip row, then the places in [order]. Workspace shows every workspace
 * as a section with its tabs; panes appear only under a split tab, expanded
 * by default when they hold the focused or a needs-you pane. Last activity
 * lists every place flat by its agent's last state change, places without
 * one in a section after them, held while the switcher is shown: newer
 * changes wait behind "N changed · Update order" (docs/ordering.md). Exactly
 * one row — the Herdr-focused target — is highlighted, and rows never
 * reorder under live updates. Every long-press action is also behind a
 * row's visible menu button.
 */
@Composable
fun Switcher(
    node: Node,
    view: ShellView?,
    state: Connection.State,
    accent: Color,
    order: TargetOrder,
    pins: List<String>,
    recent: List<String>,
    monitored: (String) -> Boolean,
    query: String,
    onQuery: (String) -> Unit,
    filter: SwitcherFilter,
    onFilter: (SwitcherFilter) -> Unit,
    actions: SwitcherActions,
    modifier: Modifier = Modifier,
    /** The pane on screen, marked "here": in Chat the one read here (CT-1); Herdr's focus by default. */
    here: String? = view?.focusedPane,
    /** Chat: the here row says "Reading here" and Herdr's focused row "On desktop" to TalkBack. */
    reading: Boolean = false,
    /** The phone sheet's "Show as grid" in the header (TB1); null where the list is the only view. */
    onShowGrid: (() -> Unit)? = null,
    /**
     * Whether this list applies the saved start filter when it first shows.
     * The phone sheet applies it once per open itself, so Grid ⇄ List keeps
     * the filter the user chose.
     */
    appliesStartFilter: Boolean = true,
) {
    val groups = remember(view) { view?.let(::topology).orEmpty() }
    // Pinned then recent places, each once: a one-pane tab remembered under
    // both its tab and its agent's pane key is one chip.
    val pinnedPlaces = remember(groups, pins) { groups.placesNamed(pins).mapTo(HashSet()) { it.key } }
    val places = remember(groups, pins, recent) { groups.placesNamed(pins + recent).take(RECENT_LIMIT) }
    // The selector writes the persisted setting; the caller's [order] follows it.
    val store = (LocalContext.current.applicationContext as? HerdlineApp)?.settings
    val prefs = store?.settings?.collectAsState()?.value
    // With its filter chips shown, each time the list opens it starts on the
    // saved filter, and a chip tap then changes only this open list.
    // Saveable, so a rotation keeps the tap. With the chips hidden there is
    // no way to change a filter here, so the list always shows All.
    val filtersShown = prefs == null || SheetSections.shows(prefs.sheetSections, SheetSection.Filters)
    val placesShown = prefs == null || SheetSections.shows(prefs.sheetSections, SheetSection.Places)
    var started by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            store?.let { if (filtersShown && appliesStartFilter) onFilter(it.settings.value.startFilter.switcherFilter) }
        }
    }
    LaunchedEffect(filtersShown) {
        if (!filtersShown && filter != SwitcherFilter.All) onFilter(SwitcherFilter.All)
    }
    // Rows the Tabs list toggles hide come back for this open list on Show,
    // and search always finds them; either way they are marked "hidden".
    val hiding = remember(groups, prefs?.hideShells, prefs?.hideIdleDone) {
        groups.hiddenRows(hideShells = prefs?.hideShells == true, hideIdleDone = prefs?.hideIdleDone == true)
    }
    var showHidden by remember { mutableStateOf(false) }
    val revealed = showHidden || query.isNotBlank()
    val listed = remember(groups, hiding, revealed) { if (revealed) groups else groups.without(hiding.keys) }
    val marked = if (revealed) hiding.keys else emptySet()
    val shown = remember(listed, query, filter) { listed.narrowed(query, filter) }
    // Counts come from every row, hidden or not, so the chips stay honest.
    val asking = remember(groups) { groups.agentsWith(AgentStatus.Blocked) }
    val working = remember(groups) { groups.agentsWith(AgentStatus.Working) }
    val browsing = query.isBlank() && filter == SwitcherFilter.All
    val flat = order == TargetOrder.LastActivity
    val compact = LocalCompact.current
    // Last activity: the order on screen is held from the moment the switcher
    // shows, each row with the seq it had then; live seq changes only count
    // towards the Update order pill. A new daemon run starts over, since its
    // pane IDs and seqs share nothing with the last.
    val fresh = remember(groups, view?.boot, flat) {
        if (flat) lastActivityOrder(groups.places().activityKeys(view?.boot.orEmpty()) { it.stateSeq }) else emptyList()
    }
    var frozen by remember(node.id, view?.boot, order) { mutableStateOf(emptyList<ActivityKey>()) }
    val onScreen = heldOrder(frozen, fresh)
    SideEffect { frozen = onScreen }
    val changed = changedSince(onScreen, fresh)
    val shownPlaces = remember(shown, flat) { if (flat) shown.places().associateBy { it.key } else emptyMap() }
    // When Herdline itself saw each agent's current status begin, while this
    // switcher was shown; a status already there when it opened has no time.
    val clock = remember(node.id) { StatusClock() }
    val seenAt = remember(view) {
        val at = System.currentTimeMillis()
        view?.agents.orEmpty().associate { it.pane to clock.observe(MonitorKey(node.id, it.pane), it.status, at) }
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(flat) {
        while (flat) {
            now = System.currentTimeMillis()
            delay(TIME_TICK_MS)
        }
    }
    // Panes whose delivery is unconfirmed (AUDIT R7), read once per view or
    // in-flight change rather than per row; a read only, nothing is written.
    val drafts = DraftStore.get(LocalContext.current)
    val flying by drafts.inFlight.collectAsState()
    val held = remember(view, flying, node.id, node.session) { drafts.held(node.id, node.session) }
    val readingHere = stringResource(R.string.chat_reading_here)
    val onDesktop = stringResource(R.string.chat_on_desktop)
    // In Chat a closed pane read here leaves no row marked (S5).
    val hereRow = if (reading && here == null) null else hereKey(view, here)
    val desktopRow = hereKey(view, view?.focusedPane).takeIf { reading && it != hereRow }
    val spoken = mapOf(hereRow to readingHere, desktopRow to onDesktop).filterKeys { reading && it != null }
    val style = RowStyle(accent, pins.toSet(), monitored, actions, hereRow, held, view, spoken)
    // Split tabs start expanded when they hold the focused or a needs-you pane.
    val expanded = remember { mutableStateMapOf<String, Boolean>() }
    fun startsOpen(tab: SwitchTarget.TabRow): Boolean {
        val held = view?.panes.orEmpty().filter { it.tab == tab.tab.id }.map { it.id }
        return expanded[tab.tab.id] ?: held.any { it == here || asking.any { row -> row.pane.id == it } }
    }
    val pinnedTitle = stringResource(R.string.section_pinned)
    val recentTitle = stringResource(R.string.section_recent)

    Box(modifier) {
        LazyColumn(
            Modifier.fillMaxWidth().testTag("switcher"),
            contentPadding = PaddingValues(horizontal = 12.dp),
        ) {
            item(key = "header") {
                HostLine(node, view, state) {
                    if (onShowGrid != null) {
                        IconButton(onClick = onShowGrid, modifier = Modifier.testTag("switcher:as-grid")) {
                            Icon(GridIcon, stringResource(R.string.overview_as_grid), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item(key = "search") { SearchField(query, onQuery, stringResource(R.string.switcher_search)) }
            if (store != null) {
                item(key = "order:select") {
                    OrderSelector(order, accent) { picked -> store.update { it.copy(targetOrder = picked) } }
                }
            }
            if (filtersShown) item(key = "filters") {
                Row(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(stringResource(R.string.filter_all), filter == SwitcherFilter.All, accent, "all") { onFilter(SwitcherFilter.All) }
                    FilterChip(
                        stringResource(R.string.filter_needs_you, asking.size),
                        filter == SwitcherFilter.NeedsYou,
                        AgentStatus.Blocked.color(),
                        "needs_you",
                    ) { onFilter(SwitcherFilter.NeedsYou) }
                    FilterChip(
                        stringResource(R.string.filter_working, working.size),
                        filter == SwitcherFilter.Working,
                        AgentStatus.Working.color(),
                        "working",
                    ) { onFilter(SwitcherFilter.Working) }
                }
            }
            if (browsing && placesShown) {
                item(key = "places") { PlacesRow(pinnedTitle, recentTitle, places, pinnedPlaces, style) }
            }
            if ((if (flat) shownPlaces.isEmpty() else shown.isEmpty()) && view != null) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.switcher_nothing),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }
            if (flat) {
                item(key = "order:limit") { LimitLine(compact) }
                val ranked = onScreen.filter { it.seq != null && it.key in shownPlaces }
                val unranked = onScreen.filter { it.seq == null && it.key in shownPlaces }
                for (row in ranked) {
                    val target = shownPlaces.getValue(row.key)
                    item(key = target.key) {
                        TargetRow(target, style, open = false, onToggle = null, flat = true, since = target.agentPane()?.let(seenAt::get), now = now, hidden = target.key in marked)
                    }
                }
                if (unranked.isNotEmpty()) {
                    item(key = "order:unranked") { SectionLabel(stringResource(R.string.order_unranked), "switcher:unranked") }
                }
                for (row in unranked) {
                    val target = shownPlaces.getValue(row.key)
                    item(key = target.key) {
                        TargetRow(target, style, open = false, onToggle = null, flat = true, since = target.agentPane()?.let(seenAt::get), now = now, hidden = target.key in marked)
                    }
                }
            }
            for (group in if (flat) emptyList() else shown) {
                item(key = "w:" + group.workspace.id) { WorkspaceHeader(group.workspace, style) }
                var i = 0
                while (i < group.rows.size) {
                    when (val target = group.rows[i]) {
                        is SwitchTarget.TabRow -> {
                            val open = startsOpen(target)
                            item(key = target.key) {
                                TargetRow(target, style, open, onToggle = { expanded[target.tab.id] = !open }, hidden = target.key in marked)
                            }
                            i++
                            while (i < group.rows.size && group.rows[i] is SwitchTarget.PaneRow) {
                                val pane = group.rows[i] as SwitchTarget.PaneRow
                                if (open) item(key = pane.key) { TargetRow(pane, style, open = false, onToggle = null, hidden = pane.key in marked) }
                                i++
                            }
                        }
                        is SwitchTarget.PaneRow -> {
                            item(key = target.key) { TargetRow(target, style, open = false, onToggle = null, hidden = target.key in marked) }
                            i++
                        }
                    }
                }
            }
            if (browsing && !revealed && hiding.count > 0) {
                item(key = "hidden") { HiddenFooter(hiding.count) { showHidden = true } }
            }
            if (browsing) {
                item(key = "new") {
                    GlassButton(
                        stringResource(R.string.new_workspace),
                        onClick = actions.newWorkspace,
                        icon = Icons.Default.Add,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    )
                }
            }
            // Room for the Update order pill, so it never covers the last row.
            item(key = "end") { Spacer(Modifier.height(if (flat && changed > 0) 72.dp else 24.dp)) }
        }
        if (flat && changed > 0) {
            UpdatePill(changed, accent, Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)) { frozen = fresh }
        }
    }
}

/** How often observed "changed …" times are refreshed. */
private const val TIME_TICK_MS = 30_000L

/** The pane whose agent's observed status time a row shows: its own, or a one-pane tab's single agent's. */
private fun SwitchTarget.agentPane(): String? = when (this) {
    is SwitchTarget.PaneRow -> agent?.pane
    is SwitchTarget.TabRow -> agents.singleOrNull()?.pane
}

/** The muted line under a list with hidden rows: how many, and Show to list them for now. */
@Composable
private fun HiddenFooter(count: Int, onShow: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(role = Role.Button, onClick = onShow)
            .padding(horizontal = 14.dp)
            .testTag("switcher:hidden"),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            pluralStringResource(R.plurals.switcher_hidden, count, count),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private class RowStyle(
    val accent: Color,
    val pins: Set<String>,
    val monitored: (String) -> Boolean,
    val actions: SwitcherActions,
    val hereKey: String?,
    private val held: Set<String>,
    private val view: ShellView?,
    /** A row's spoken state by key (CT-1: "Reading here", "On desktop"); empty outside Chat. */
    val spoken: Map<String?, String> = emptyMap(),
) {
    /** The pane behind [target] whose delivery is unconfirmed, or null; a tab is held when any of its panes is. */
    fun heldPane(target: SwitchTarget): String? = when (target) {
        is SwitchTarget.TabRow -> view?.heldPaneOf(target.tab.id, held)
        is SwitchTarget.PaneRow -> target.pane.id.takeIf { it in held }
    }
}

/** The key of the [pane]'s row, or its tab's when the tab has one pane; with no pane, the focused tab's. */
private fun hereKey(view: ShellView?, pane: String?): String? {
    view ?: return null
    pane ?: return view.focusedTab?.let { "t:$it" }
    val tabId = view.panes.firstOrNull { it.id == pane }?.tab
    return if (tabId != null && view.panesOf(tabId).size > 1) "p:$pane" else tabId?.let { "t:$it" }
}

/** What a tab row is called: the title the model resolved from Titles.kt. */
private fun SwitchTarget.TabRow.tabTitle(): String = title

/**
 * One 48 dp line instead of the stats card: the host's name and how much is
 * waiting there. Connecting and disconnected states still say so; the
 * Tabs overview shows it while there is no live view. [trailing] ends the row.
 */
@Composable
internal fun HostLine(node: Node, view: ShellView?, state: Connection.State, trailing: @Composable () -> Unit = {}) {
    val summary = when (state) {
        is Connection.State.Connecting -> stringResource(R.string.connecting)
        is Connection.State.SettingUp -> stringResource(R.string.herdr_setup_pill)
        is Connection.State.Connected -> listOfNotNull(
            pluralStringResource(R.plurals.switcher_workspace_count, view?.workspaces?.size ?: 0, view?.workspaces?.size ?: 0),
            view?.agents?.count { it.status == AgentStatus.Blocked }?.takeIf { it > 0 }
                ?.let { pluralStringResource(R.plurals.need_you_chip, it, it) },
        ).joinToString(" · ").ifEmpty { stringResource(R.string.connected_empty) }
        else -> stringResource(R.string.disconnected)
    }
    Row(
        Modifier
            .padding(top = 12.dp, bottom = 4.dp)
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NodeAvatar(node.title, node.color, size = 28.dp)
        Column(Modifier.weight(1f)) {
            Text(
                node.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                summary,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailing()
    }
}

/** The search pill; [minHeight] is the overview's font-scaled one (TB1 §4), else the list's 44 dp. */
@Composable
internal fun SearchField(query: String, onQuery: (String) -> Unit, placeholder: String, minHeight: Dp = 44.dp) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        BasicTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            modifier = Modifier.weight(1f).testTag("switcher:search"),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    inner()
                }
            },
        )
        if (query.isNotEmpty()) {
            Icon(
                Icons.Default.Close,
                stringResource(R.string.switcher_clear_search),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onQuery("") }.padding(4.dp),
            )
        }
    }
}

/** A compact chip, one choice of a group: 30 dp drawn, 48 dp tall to touch. */
@Composable
private fun FilterChip(label: String, selected: Boolean, color: Color, tag: String, onClick: () -> Unit) {
    val fill by animateColorAsState(if (selected) color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), label = "chip")
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .testTag("switcher:filter:$tag"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier
                .height(30.dp)
                .clip(CircleShape)
                .background(fill)
                .padding(horizontal = 12.dp)
                .wrapContentHeight(Alignment.CenterVertically),
        )
    }
}

/** "Order" and the Workspace | Last activity choice, which writes the same setting as Settings › Layout. */
@Composable
private fun OrderSelector(order: TargetOrder, accent: Color, onPick: (TargetOrder) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(top = 4.dp).selectableGroup().testTag("switcher:order"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(R.string.order_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp),
        )
        FilterChip(stringResource(R.string.order_workspace), order == TargetOrder.Workspace, accent, "order:workspace") { onPick(TargetOrder.Workspace) }
        FilterChip(stringResource(R.string.order_last_activity), order == TargetOrder.LastActivity, accent, "order:last_activity") {
            onPick(TargetOrder.LastActivity)
        }
    }
}

/** The honest limit of Last activity: where the order comes from, and what any time means. */
@Composable
private fun LimitLine(compact: Boolean) {
    Text(
        stringResource(if (compact) R.string.order_limit else R.string.order_limit_short),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 4.dp).testTag("switcher:order:limit"),
    )
}

/** An 11 sp caps label over rows; not tappable. */
@Composable
private fun SectionLabel(text: String, tag: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp).testTag(tag),
    )
}

/** Newer changes waiting behind the held order; nothing moves until the user taps this. */
@Composable
private fun UpdatePill(changed: Int, accent: Color, modifier: Modifier, onUpdate: () -> Unit) {
    Text(
        pluralStringResource(R.plurals.order_update, changed, changed),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .background(accent.copy(alpha = 0.22f))
            .clickable(role = Role.Button, onClick = onUpdate)
            .padding(horizontal = 18.dp, vertical = 14.dp)
            .testTag("switcher:reorder"),
    )
}

/**
 * Pinned and recent places as one horizontal chip row, five at most: pinned
 * first, each chip a status dot and a title, pinned ones starred. The row
 * shows when there is anything to show.
 */
@Composable
private fun PlacesRow(
    pinnedTitle: String,
    recentTitle: String,
    places: List<SwitchTarget>,
    pinned: Set<String>,
    style: RowStyle,
) {
    if (places.isEmpty()) return
    Row(
        Modifier.padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (pinned.isNotEmpty()) pinnedTitle else recentTitle,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(
            Modifier.weight(1f).testTag("switcher:places"),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(places, key = { _, target -> target.key }) { _, target ->
                PlaceChip(target, target.key in pinned, style.accent, style.heldPane(target)) { style.actions.go(target) }
            }
        }
    }
}

@Composable
private fun PlaceChip(target: SwitchTarget, pinned: Boolean, accent: Color, held: String?, onGo: () -> Unit) {
    val dot = when (target) {
        is SwitchTarget.TabRow -> target.agents.singleOrNull()?.status ?: target.tab.status
        is SwitchTarget.PaneRow -> target.agent?.status ?: AgentStatus.Unknown
    }
    val label = when (target) {
        is SwitchTarget.TabRow -> target.tabTitle()
        is SwitchTarget.PaneRow -> target.title
    }
    val state = if (held != null) heldDescription(dot) else null
    Row(
        Modifier
            // A 32 dp chip with a 48 dp target.
            .minimumInteractiveComponentSize()
            .heightIn(min = 32.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable(onClick = onGo)
            .then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusGlyph(dot, 8.dp, slot = 24.dp)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (held != null) Icon(Icons.Default.Lock, stringResource(R.string.held_state), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(12.dp).testTag("held:$held"))
        if (pinned) Icon(Icons.Default.Star, stringResource(R.string.pinned), tint = accent, modifier = Modifier.size(14.dp))
    }
}

/** Section label: 11 sp caps, not tappable, with a ⋮ for the workspace's actions. */
@Composable
private fun WorkspaceHeader(workspace: Workspace, style: RowStyle) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .heightIn(min = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            workspace.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("workspace:${workspace.id}:menu")) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, 0.dp)) {
                GlassMenuItem(stringResource(R.string.new_tab), Icons.Default.Add, onClick = { menu = false; style.actions.newTab(workspace) })
                GlassMenuItem(stringResource(R.string.rename_workspace), Icons.Default.Edit, onClick = { menu = false; style.actions.renameWorkspace(workspace) })
                GlassMenuItem(
                    stringResource(R.string.close_workspace),
                    Icons.Default.Delete,
                    onClick = { menu = false; style.actions.closeWorkspace(workspace) },
                    destructive = true,
                )
            }
        }
    }
}

/** Line 2 of a tab row: its label, then agent kinds, then a status when the tab has one. */
@Composable
private fun tabDetail(target: SwitchTarget.TabRow): String? {
    val kinds = target.agents.mapNotNull { it.agent ?: it.name }.distinct()
    val statusText = target.agents.singleOrNull()?.status?.let { statusLabel(it) }
        ?: target.agents.map { it.status }.distinct().singleOrNull()?.let { statusLabel(it) }
    return listOfNotNull(
        target.tab.label.takeIf { it.isNotBlank() },
        kinds.joinToString(" + ").takeIf { it.isNotEmpty() },
        statusText,
        shellNote(target, kinds.isEmpty() && statusText == null),
    ).filter { it.isNotBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }
}

@Composable
private fun shellNote(target: SwitchTarget.TabRow, bare: Boolean): String? =
    if (bare && target.panes == 1) stringResource(R.string.switcher_shell) else null

/**
 * The switcher's rows. The Herdr-focused target carries the accent tint, a
 * 3 dp leading bar and the "here" tag; every other row is plain. Rows never
 * reorder under live updates; long-press and the trailing ⋮ share one menu.
 * A [flat] row stands outside its workspace section (Last activity), so its
 * detail reads `workspace › tab · status`, plus "changed …" when Herdline saw
 * the status begin at [since], and a pane row is not indented.
 */
@Composable
private fun TargetRow(
    target: SwitchTarget,
    style: RowStyle,
    open: Boolean,
    onToggle: (() -> Unit)?,
    flat: Boolean = false,
    since: Long? = null,
    now: Long = 0L,
    hidden: Boolean = false,
) {
    val hiddenBadge = if (hidden) stringResource(R.string.switcher_hidden_badge) else null
    val flatDetail = if (flat) {
        val agent = when (target) {
            is SwitchTarget.PaneRow -> target.agent
            is SwitchTarget.TabRow -> target.agents.singleOrNull()
        }
        listOfNotNull(
            listOf(target.workspace.label, target.tab.label).filter { it.isNotBlank() }.joinToString(" › "),
            agent?.status?.let { statusLabel(it) } ?: if (agent == null) stringResource(R.string.switcher_shell) else null,
            since?.let { changedLabel(ago(it, now)) },
        ).filter { it.isNotBlank() }.joinToString(" · ")
    } else {
        null
    }
    fun placed(detail: String?): String? = flatDetail ?: detail
    val pinned = target.key in style.pins
    val here = target.key == style.hereKey
    val pinLabel = stringResource(if (pinned) R.string.unpin else R.string.pin)
    // A selected row's glyph takes the row's content colour; the word carries the state.
    val glyphTint = if (here) LocalContentColor.current else Color.Unspecified
    val held = style.heldPane(target)
    when (target) {
        is SwitchTarget.TabRow -> Entry(
            selected = here,
            accent = style.accent,
            indent = 14.dp,
            minHeight = 56.dp,
            onClick = { style.actions.go(target) },
            leading = { StatusGlyph(target.tab.status, 10.dp, slot = 24.dp, tint = glyphTint) },
            status = target.tab.status,
            held = held,
            extraState = style.spoken[target.key],
            title = target.tabTitle(),
            titleStyle = MaterialTheme.typography.bodyLarge,
            detail = placed(tabDetail(target)),
            badges = listOfNotNull(
                if (target.panes > 1) pluralStringResource(R.plurals.pane_count, target.panes, target.panes) else null,
                if (target.tab.zoomed) stringResource(R.string.zoomed_badge) else null,
                hiddenBadge,
            ),
            hereLabel = stringResource(R.string.switcher_here),
            trailing = if (target.panes > 1) {
                {
                    IconButton(onClick = { onToggle?.invoke() }, modifier = Modifier.testTag("switcher:expand:${target.tab.id}")) {
                        Icon(
                            if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            stringResource(if (open) R.string.switcher_collapse else R.string.switcher_expand),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            } else {
                null
            },
            pinned = pinned,
            tag = "tab-row:${target.tab.id}",
            menu = { close ->
                GlassMenuItem(pinLabel, Icons.Default.Star, onClick = { close(); style.actions.pin(target.key) })
                for (agent in target.agents) {
                    GlassMenuItem(
                        stringResource(if (style.monitored(agent.pane)) R.string.monitor_stop else R.string.monitor_agent),
                        Icons.Default.Notifications,
                        onClick = { close(); style.actions.monitor(agent.pane) },
                    )
                }
                GlassMenuItem(stringResource(R.string.rename_tab), Icons.Default.Edit, onClick = { close(); style.actions.renameTab(target.tab) })
                GlassMenuItem(stringResource(R.string.close_tab), Icons.Default.Close, onClick = { close(); style.actions.closeTab(target.tab) }, destructive = true)
            },
        )
        is SwitchTarget.PaneRow -> Entry(
            selected = here,
            accent = style.accent,
            indent = if (flat) 14.dp else 38.dp,
            minHeight = 48.dp,
            onClick = { style.actions.go(target) },
            leading = { StatusGlyph(target.agent?.status ?: AgentStatus.Unknown, 10.dp, slot = 24.dp, tint = glyphTint) },
            status = target.agent?.status ?: AgentStatus.Unknown,
            held = held,
            extraState = style.spoken[target.key],
            title = target.title.ifEmpty { target.pane.id },
            titleStyle = MaterialTheme.typography.bodyMedium,
            detail = placed(
                listOfNotNull(
                    (target.agent?.agent ?: target.agent?.name)?.takeIf { it.isNotBlank() },
                    target.agent?.status?.let { statusLabel(it) },
                    target.pane.cwd?.trimEnd('/')?.substringAfterLast('/'),
                ).joinToString(" · ").takeIf { it.isNotEmpty() },
            ),
            badges = listOfNotNull(
                if (target.agent != null && style.monitored(target.pane.id)) stringResource(R.string.monitor_badge) else null,
                hiddenBadge,
            ),
            hereLabel = stringResource(R.string.switcher_here),
            pinned = pinned,
            tag = if (target.agent != null) "agent:${target.pane.id}" else "pane:${target.pane.id}",
            menu = { close ->
                GlassMenuItem(pinLabel, Icons.Default.Star, onClick = { close(); style.actions.pin(target.key) })
                if (target.agent != null) {
                    GlassMenuItem(
                        stringResource(if (style.monitored(target.pane.id)) R.string.monitor_stop else R.string.monitor_agent),
                        Icons.Default.Notifications,
                        onClick = { close(); style.actions.monitor(target.pane.id) },
                    )
                }
            },
        )
    }
}

@Composable
private fun Entry(
    selected: Boolean,
    accent: Color,
    indent: Dp,
    onClick: () -> Unit,
    leading: @Composable () -> Unit,
    title: String,
    titleStyle: androidx.compose.ui.text.TextStyle,
    detail: String?,
    badges: List<String>,
    hereLabel: String,
    tag: String,
    minHeight: Dp = 48.dp,
    pinned: Boolean = false,
    status: AgentStatus = AgentStatus.Unknown,
    held: String? = null,
    /** A state of the caller's, spoken before the held one. */
    extraState: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    menu: @Composable ColumnScope.(close: () -> Unit) -> Unit,
) {
    val fill by animateColorAsState(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent, label = "row")
    val haptics = LocalHapticFeedback.current
    var open by remember { mutableStateOf(false) }
    val compact = LocalCompact.current
    // Set on the row, which merges its children, so it carries the glyph's status too.
    val state = listOfNotNull(extraState, if (held != null) heldDescription(status) else null).joinToString(", ").takeIf { it.isNotEmpty() }
    Box(Modifier.padding(start = indent)) {
        Row(
            Modifier
                .padding(vertical = 1.dp)
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .clip(MaterialTheme.shapes.large)
                .background(fill)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        open = true
                    },
                    onLongClickLabel = stringResource(R.string.more),
                )
                // Only the text is padded vertically, so the 48 dp ⋮ fills the row instead of growing it.
                .padding(start = 8.dp)
                .then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier)
                .testTag(tag),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (selected) Box(Modifier.width(3.dp).height(28.dp).clip(CircleShape).background(accent))
            leading()
            Column(Modifier.weight(1f).padding(vertical = if (compact) 4.dp else 8.dp)) {
                Text(
                    title,
                    style = titleStyle,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (selected) {
                Text(
                    hereLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = accent,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.14f))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (pinned) Icon(Icons.Default.Star, stringResource(R.string.pinned), tint = accent, modifier = Modifier.size(16.dp))
            for (badge in badges) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            // The held badge: no tap of its own (AUDIT R7).
            if (held != null) {
                Icon(Icons.Default.Lock, stringResource(R.string.held_state), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp).testTag("held:$held"))
            }
            trailing?.invoke()
            Box {
                IconButton(onClick = { open = true }, modifier = Modifier.testTag("$tag:menu")) {
                    Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                GlassMenu(expanded = open, onDismissRequest = { open = false }, offset = DpOffset(0.dp, 0.dp)) { menu { open = false } }
            }
        }
    }
}

@Composable
fun statusLabel(status: AgentStatus): String? = when (status) {
    AgentStatus.Working -> stringResource(R.string.status_working)
    AgentStatus.Blocked -> stringResource(R.string.status_blocked)
    AgentStatus.Done -> stringResource(R.string.status_done)
    AgentStatus.Idle -> stringResource(R.string.status_idle)
    AgentStatus.Unknown -> null
}

/** An observed state change's age, worded as a change, never as output. */
@Composable
private fun changedLabel(ago: Ago): String = when (ago) {
    Ago.Now -> stringResource(R.string.changed_now)
    is Ago.Minutes -> pluralStringResource(R.plurals.changed_minutes, ago.count, ago.count)
    is Ago.Hours -> pluralStringResource(R.plurals.changed_hours, ago.count, ago.count)
    is Ago.Days -> pluralStringResource(R.plurals.changed_days, ago.count, ago.count)
}

/**
 * A held row's state: its spoken status. "Delivery not confirmed" is the
 * lock icon's own description (QA M5-a), so it is read once, not twice.
 */
@Composable
private fun heldDescription(status: AgentStatus): String? = statusSpoken(status)

/** The status glyph in the 24 dp column rows use; [small] is the 8 dp rail size, else 10 dp. */
@Composable
fun StatusDot(status: AgentStatus, small: Boolean = false) {
    StatusGlyph(status, if (small) 8.dp else 10.dp, slot = 24.dp)
}
