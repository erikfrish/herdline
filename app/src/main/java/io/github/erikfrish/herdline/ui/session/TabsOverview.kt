package io.github.erikfrish.herdline.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip as MaterialFilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.ui.theme.color
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.ui.components.StatusGlyph
import io.github.erikfrish.herdline.ui.components.statusSpoken
import io.github.erikfrish.herdline.ui.glass.GlassMenu
import io.github.erikfrish.herdline.ui.glass.GlassMenuItem
import io.github.erikfrish.herdline.ui.glass.GlassButton

/**
 * The phone Tabs sheet's compact overview (TB1): a header, search, a
 * workspace selector and status chips over two-column list rows (one column
 * when two would be illegible), by workspace in Herdr's order. It reads only
 * [view]: no capture, no frame request, no polling. A one-pane tab's row goes
 * to that tab; a split tab's opens the pane picker in the same sheet, and
 * Back returns to the rows where they were. [query] and [filter] are the
 * list's own, so Show as list keeps them; [scope] is the chosen workspace id,
 * or null for all of them, shared with the list ([tabsScope]): a closed one
 * shows no tabs and says so. [here] and [reading] are the list's own (CT-1):
 * in Chat the pane read on this phone marks the current tab and pane, never
 * Herdr's focus.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TabsOverview(
    host: String,
    view: ShellView?,
    state: Connection.State,
    pins: List<String>,
    monitored: (String) -> Boolean,
    query: String,
    onQuery: (String) -> Unit,
    filter: SwitcherFilter,
    onFilter: (SwitcherFilter) -> Unit,
    filtersShown: Boolean,
    scope: String?,
    onScope: (String?) -> Unit,
    actions: SwitcherActions,
    onShowList: () -> Unit,
    /** Two columns' toggle: the next view in the cycle, List → Two columns → Rails → List. */
    onShowRails: () -> Unit,
    /** The phone sheet's visible way out (U-1 #7). */
    onClose: () -> Unit,
    /** The tabs marked changed since the user looked ([rememberUnseenTabs]); required, as the strip's (SN-2). */
    unseen: Set<String>,
    modifier: Modifier = Modifier,
    here: String? = view?.focusedPane,
    reading: Boolean = false,
    /** Rails (F2): the workspace rails in place of the rows, scrolled by this hoisted state; null for the rows. */
    rails: RailsScroll? = null,
    /** A rail's New tab tile: that rail's workspace only, never moving Herdr's focus. */
    onRailNewTab: (Workspace) -> Unit = actions.newTab,
) {
    val groups = remember(view) { view?.let(::topology).orEmpty() }
    val scoped = remember(groups, scope, view != null) { tabsScope(groups, scope, loaded = view != null) }
    val chosen = scoped.chosen
    val inScope = scoped.groups
    val narrowing = query.isNotBlank() || filter != SwitcherFilter.All
    val currentTab = view?.let { overviewCurrentTab(it, here, reading) }
    val sections = remember(inScope, query, filter, view, currentTab) {
        view?.let { overviewSections(inScope, inScope.narrowed(query, filter), narrowing, it, currentTab) }.orEmpty()
    }
    val counts = remember(inScope, query) { inScope.agentCounts(query) }
    // The full topology in scope beside the narrowed sections: a workspace narrowing emptied keeps its rail.
    val railList = remember(inScope, sections, view, currentTab, rails != null) {
        if (rails == null || view == null) emptyList() else overviewRails(inScope, sections, view, currentTab)
    }
    val headers = chosen == null
    // Hoisted over the picker, so Back finds the rows where they were.
    val rows = rememberLazyGridState()
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val card = picking?.let { id -> sections.firstNotNullOfOrNull { s -> s.cards.firstOrNull { it.tab.id == id } } }
    LaunchedEffect(picking, card == null) { if (picking != null && card == null) picking = null }
    BackHandler(enabled = picking != null) { picking = null }
    // Once per open, without animation: the current tab is on screen even with 20+ tabs.
    var placed by remember { mutableStateOf(false) }
    if (rails != null) {
        // Once per session, without animation; after that the saved positions win (§5).
        LaunchedEffect(rails, railList.isNotEmpty()) {
            if (rails.placed || railList.isEmpty()) return@LaunchedEffect
            rails.placed = true
            railIndexOf(railList, currentTab)?.let { (row, column) ->
                rails.outer.scrollToItem(row)
                rails.ribbon(railList[row].workspace.id).scrollToItem(column)
            }
        }
        // A closed workspace drops its saved ribbon; a scope change hides one but keeps it.
        val ids = remember(view?.workspaces) { view?.workspaces?.mapTo(HashSet()) { it.id } }
        LaunchedEffect(rails, ids) { ids?.let(rails::keep) }
    }
    val clear = {
        onQuery("")
        onFilter(SwitcherFilter.All)
        onScope(null)
    }

    // Keyed by the picked tab's id, not its row: a live status update redraws the picker in place,
    // with no transition and nothing reset.
    AnimatedContent(targetState = picking, label = "overview", modifier = modifier) { id ->
        val shown = id?.let { picked -> sections.firstNotNullOfOrNull { s -> s.cards.firstOrNull { it.tab.id == picked } } }
        if (shown != null && view != null) {
            PanePicker(shown, here, reading, onBack = { picking = null }, onGo = actions.go)
            return@AnimatedContent
        }
        Column(Modifier.fillMaxWidth().testTag("overview")) {
            // The Tabs sheet's one header (H1), at the List's inset; the search pill keeps the overview's own.
            Box(Modifier.padding(horizontal = TABS_SHEET_INSET)) {
                // The toggle shows the next view in the Settings order: Rails after Two columns, List after Rails.
                val toggle = if (rails != null) {
                    TabsViewToggle(Icons.AutoMirrored.Filled.List, stringResource(R.string.overview_as_list), "overview:as-list", onShowList)
                } else {
                    TabsViewToggle(RailsIcon, stringResource(R.string.overview_as_rails), "overview:as-rails", onShowRails)
                }
                TabsHeader(host, view, state, chosen, scoped.gone, actions.newTab, toggle, onClose, tag = "overview")
            }
            Box(Modifier.padding(horizontal = TABS_SHEET_INSET)) {
                SearchField(query, onQuery, stringResource(R.string.switcher_search), minHeight = tabsSearchMinHeight())
            }
            if (view != null) {
                Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ScopeSelector(view, groups, chosen, scoped.gone, onScope)
                }
            }
            if (filtersShown) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                        .horizontalScroll(rememberScrollState()).selectableGroup().testTag("overview:filters"),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    OverviewChip(stringResource(R.string.filter_all), filter == SwitcherFilter.All, null, "all") { onFilter(SwitcherFilter.All) }
                    OverviewChip(stringResource(R.string.filter_needs_you, counts.needsYou), filter == SwitcherFilter.NeedsYou, AgentStatus.Blocked, "needs_you") {
                        onFilter(SwitcherFilter.NeedsYou)
                    }
                    OverviewChip(stringResource(R.string.filter_working, counts.working), filter == SwitcherFilter.Working, AgentStatus.Working, "working") {
                        onFilter(SwitcherFilter.Working)
                    }
                    OverviewChip(stringResource(R.string.filter_ready, counts.ready), filter == SwitcherFilter.Ready, null, "ready") {
                        onFilter(SwitcherFilter.Ready)
                    }
                }
            }
            when {
                // The header's subtitle says connecting or disconnected, as the List's does.
                view == null || state !is Connection.State.Connected -> Unit
                view.workspaces.isEmpty() -> GlassButton(
                    stringResource(R.string.new_workspace),
                    onClick = actions.newWorkspace,
                    icon = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag("overview:new-workspace"),
                )
                scoped.gone -> ScopeGone { onScope(null) }
                rails != null -> if (narrowing && railList.all { it.cards.isEmpty() }) {
                    NoneMatch(clear)
                } else {
                    OverviewRails(railList, rails, narrowing, pins, monitored, actions, unseen, onRailNewTab) { picking = it.tab.id }
                }
                sections.isEmpty() -> NoneMatch(clear)
                else -> OverviewRows(sections, headers, rows, pins, monitored, actions, unseen, currentTab, placed, { placed = true }) { picking = it.tab.id }
            }
        }
    }
}

/** The rows: a full-span header per workspace with All chosen, then its tabs in Herdr's tab order, up to two columns. */
@Composable
private fun OverviewRows(
    sections: List<OverviewSection>,
    headers: Boolean,
    rows: LazyGridState,
    pins: List<String>,
    monitored: (String) -> Boolean,
    actions: SwitcherActions,
    unseen: Set<String>,
    currentTab: String?,
    placed: Boolean,
    onPlaced: () -> Unit,
    onPick: (OverviewCard) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = compactColumns(maxWidth.value, fontScale)
        LaunchedEffect(sections.isNotEmpty(), columns) {
            if (placed || sections.isEmpty()) return@LaunchedEffect
            onPlaced()
            overviewIndexOf(sections, currentTab, headers, columns)?.let { rows.scrollToItem(it) }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            state = rows,
            contentPadding = PaddingValues(start = OVERVIEW_PADDING_DP.dp, end = OVERVIEW_PADDING_DP.dp, top = 4.dp, bottom = 16.dp + bottom),
            horizontalArrangement = Arrangement.spacedBy(OVERVIEW_GAP_DP.dp),
            verticalArrangement = Arrangement.spacedBy(OVERVIEW_GAP_DP.dp),
            modifier = Modifier.fillMaxWidth().testTag("overview:rows"),
        ) {
            for (section in sections) {
                if (headers) {
                    item(key = "ws:" + section.workspace.id, span = { GridItemSpan(maxLineSpan) }) {
                        OverviewWorkspaceHeader(section.workspace, section.suffix, section.cards.size, actions)
                    }
                }
                val pairs = section.cards.chunked(columns)
                items(pairs, key = { it.first().key }, span = { GridItemSpan(maxLineSpan) }) { pair ->
                    Row(
                        Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                        horizontalArrangement = Arrangement.spacedBy(OVERVIEW_GAP_DP.dp),
                    ) {
                        for (card in pair) key(card.key) {
                            OverviewRow(
                                card, card.key in pins, monitored, actions, onPick,
                                changed = card.row.changedIn(unseen),
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        if (pair.size < columns) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** A workspace's name for a header or a row line: its label, with `#n` when another workspace shares it. */
@Composable
internal fun workspaceName(workspace: Workspace, suffix: Int?): String =
    suffix?.let { stringResource(R.string.overview_ws_suffix, workspace.label, it) } ?: workspace.label

/**
 * A workspace's header: its name, `branch · N tabs` and the ⋮ workspace
 * menu. In Rails (§2) [tabs] and [needsYou] are the full topology's, the
 * Current pill leads when [current], and [spokenHeading] reads it all as one
 * heading.
 */
@Composable
private fun OverviewWorkspaceHeader(
    workspace: Workspace,
    suffix: Int?,
    tabs: Int,
    actions: SwitcherActions,
    needsYou: Int = 0,
    current: Boolean = false,
    spokenHeading: Boolean = false,
) {
    var menu by remember { mutableStateOf(false) }
    val name = workspaceName(workspace, suffix)
    val count = pluralStringResource(R.plurals.overview_tabs, tabs, tabs)
    val needs = if (needsYou > 0) stringResource(R.string.filter_needs_you, needsYou) else null
    val currentSpoken = if (current) stringResource(R.string.overview_current_ws_spoken) else null
    val heading = if (spokenHeading) {
        val spoken = listOfNotNull(name, count, needs, currentSpoken).joinToString(", ")
        Modifier.clearAndSetSemantics {
            heading()
            contentDescription = spoken
        }
    } else {
        Modifier.semantics(mergeDescendants = true) { heading() }
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).then(heading)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (current) CurrentPill()
                Text(
                    name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                listOfNotNull(workspace.branch?.takeIf { it.isNotBlank() }, count, needs).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("overview:ws:${workspace.id}:menu")) {
                Icon(Icons.Default.MoreVert, stringResource(R.string.more), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, 0.dp)) {
                GlassMenuItem(stringResource(R.string.new_tab), Icons.Default.Add, onClick = { menu = false; actions.newTab(workspace) })
                GlassMenuItem(stringResource(R.string.rename_workspace), Icons.Default.Edit, onClick = { menu = false; actions.renameWorkspace(workspace) })
                GlassMenuItem(
                    stringResource(R.string.close_workspace),
                    Icons.Default.Close,
                    onClick = { menu = false; actions.closeWorkspace(workspace) },
                    destructive = true,
                )
            }
        }
    }
}

/**
 * One compact tab row: the title (always two lines of room), the agent and
 * status line, and the workspace line. The tab the reader is on is marked by
 * a 2 dp primary border, the Current pill and its spoken label, never colour
 * alone. Tapping opens the tab, or the pane picker for a split tab;
 * long-press or the ⋮ opens its menu. Pin, monitor, rename and close go
 * through that menu; closing goes through its confirmation.
 */
@Composable
private fun OverviewRow(
    card: OverviewCard,
    pinned: Boolean,
    monitored: (String) -> Boolean,
    actions: SwitcherActions,
    onPick: (OverviewCard) -> Unit,
    /** The tab is marked changed (SN-2): the strip's dot on its status glyph and "changed" as its state. */
    changed: Boolean,
    /** Rails: the tile's place in its ribbon, spoken last (§4). */
    position: String? = null,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val haptics = LocalHapticFeedback.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(16.dp)
    val border = if (card.current) BorderStroke(2.dp, scheme.primary) else BorderStroke(1.dp, scheme.outlineVariant)
    val open = { if (card.split) onPick(card) else actions.go(card.row) }
    val title = card.row.title
    val status = statusLabel(card.tab.status)
    val panes = card.matched?.let { stringResource(R.string.overview_matched, it.size, card.panes.size) }
        ?: if (card.split) pluralStringResource(R.plurals.pane_count, card.panes.size, card.panes.size) else null
    val place = stringResource(R.string.overview_tab_n, card.tab.number)
    val where = workspaceName(card.workspace, card.suffix) + " · " + place
    val zoomed = if (card.tab.zoomed) stringResource(R.string.zoomed_badge) else null
    val spoken = listOfNotNull(
        title,
        statusSpoken(card.tab.status),
        where,
        panes,
        if (card.current) stringResource(R.string.overview_current_spoken) else null,
        position,
    ).joinToString(", ")
    val openLabel = stringResource(if (card.split) R.string.overview_choose_pane else R.string.overview_open)
    val moreLabel = stringResource(R.string.more)
    val pinLabel = stringResource(if (pinned) R.string.unpin else R.string.pin)
    val renameLabel = stringResource(R.string.rename_tab)
    val closeLabel = stringResource(R.string.session_close_tab)
    val changedLabel = stringResource(R.string.tab_changed)

    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .border(border, shape)
            .testTag("overview:card:${card.tab.id}"),
    ) {
        Column(
            Modifier
                .fillMaxWidth().fillMaxHeight()
                .clearAndSetSemantics {
                    contentDescription = spoken
                    if (changed) stateDescription = changedLabel
                    role = Role.Button
                    onClick(openLabel) { open(); true }
                    onLongClick(moreLabel) { menu = true; true }
                    customActions = listOfNotNull(
                        if (card.split) CustomAccessibilityAction(openLabel) { onPick(card); true } else null,
                        CustomAccessibilityAction(pinLabel) { actions.pin(card.key); true },
                        CustomAccessibilityAction(renameLabel) { actions.renameTab(card.tab); true },
                        CustomAccessibilityAction(closeLabel) { actions.closeTab(card.tab); true },
                    )
                }
                .combinedClickable(
                    onClick = open,
                    onLongClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        menu = true
                    },
                )
                .padding(start = 12.dp, end = 48.dp, top = 10.dp, bottom = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (card.current) CurrentPill()
                if (zoomed != null) {
                    Text(zoomed, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant, maxLines = 1)
                }
            }
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status != null) {
                    StatusGlyph(card.tab.status, 8.dp, Modifier.unseenMark(changed, 8.dp, scheme.surfaceContainerHigh, scheme.onSurface))
                } else if (changed) {
                    // NIT-2: no glyph for Unknown, so the mark sits on a bare box the glyph's size.
                    Box(Modifier.size(8.dp).unseenMark(true, 8.dp, scheme.surfaceContainerHigh, scheme.onSurface))
                }
                Text(
                    listOfNotNull(status, panes).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (card.tab.status == AgentStatus.Blocked) FontWeight.SemiBold else FontWeight.Normal,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(where, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = { menu = true }, modifier = Modifier.align(Alignment.TopEnd).testTag("overview:card:${card.tab.id}:menu")) {
            Icon(Icons.Default.MoreVert, moreLabel, tint = scheme.onSurfaceVariant)
        }
        GlassMenu(expanded = menu, onDismissRequest = { menu = false }, offset = DpOffset(0.dp, 0.dp)) {
            GlassMenuItem(stringResource(R.string.overview_open), Icons.Default.PlayArrow, onClick = { menu = false; actions.go(card.row) })
            if (card.split) {
                GlassMenuItem(openLabel, GridIcon, onClick = { menu = false; onPick(card) })
            }
            GlassMenuItem(pinLabel, Icons.Default.Star, onClick = { menu = false; actions.pin(card.key) })
            for (agent in card.row.agents) {
                GlassMenuItem(
                    stringResource(if (monitored(agent.pane)) R.string.monitor_stop else R.string.monitor_agent),
                    Icons.Default.Notifications,
                    onClick = { menu = false; actions.monitor(agent.pane) },
                )
            }
            GlassMenuItem(renameLabel, Icons.Default.Edit, onClick = { menu = false; actions.renameTab(card.tab) })
            GlassMenuItem(closeLabel, Icons.Default.Close, onClick = { menu = false; actions.closeTab(card.tab) }, destructive = true)
        }
    }
}

/** The Current pill: a check and the word, never colour alone. */
@Composable
private fun CurrentPill() {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.clip(CircleShape).background(scheme.primary).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Default.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.overview_current), style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary, maxLines = 1)
    }
}

/**
 * The Rails body: workspaces down, each a header over a
 * ribbon of its tiles across, in Herdr's order. Scrolling either axis sends
 * nothing to the host. Tiles take the §3 width and least height and grow
 * past it, never clip, so heights may differ within a ribbon; the ribbon is
 * as tall as its tallest tile laid out. The fit at font 2.0 is QA's to prove.
 */
@Composable
private fun OverviewRails(
    rails: List<Rail>,
    scroll: RailsScroll,
    narrowing: Boolean,
    pins: List<String>,
    monitored: (String) -> Boolean,
    actions: SwitcherActions,
    unseen: Set<String>,
    onNewTab: (Workspace) -> Unit,
    onPick: (OverviewCard) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val tileWidth = railTileWidth(maxWidth.value - 2 * OVERVIEW_PADDING_DP, fontScale).dp
        val tileHeight = railTileHeight(fontScale).dp
        val newTabWidth = maxOf(96.dp, tileWidth / 2)
        LazyColumn(
            state = scroll.outer,
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp + bottom),
            modifier = Modifier.fillMaxWidth().testTag("overview:rails"),
        ) {
            items(rails, key = { "ws:" + it.workspace.id }) { rail ->
                val workspace = rail.workspace
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.padding(horizontal = OVERVIEW_PADDING_DP.dp)) {
                        OverviewWorkspaceHeader(workspace, rail.suffix, rail.tabs, actions, rail.needsYou, rail.current, spokenHeading = true)
                    }
                    when {
                        rail.cards.isNotEmpty() -> LazyRow(
                            state = scroll.ribbon(workspace.id),
                            contentPadding = PaddingValues(horizontal = OVERVIEW_PADDING_DP.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(OVERVIEW_GAP_DP.dp),
                            modifier = Modifier.fillMaxWidth().testTag("overview:rail:${workspace.id}"),
                        ) {
                            itemsIndexed(rail.cards, key = { _, card -> card.key }) { index, card ->
                                OverviewRow(
                                    card,
                                    card.key in pins,
                                    monitored,
                                    actions,
                                    onPick,
                                    changed = card.row.changedIn(unseen),
                                    position = stringResource(R.string.rails_position, index + 1, rail.cards.size),
                                    modifier = Modifier.width(tileWidth).heightIn(min = tileHeight),
                                )
                            }
                            if (!narrowing) {
                                item(key = "new-tab") { NewTabTile(workspace, rail.suffix, newTabWidth, tileHeight, onNewTab) }
                            }
                        }
                        // §8: a workspace Herdr reports with no tabs says so, never a blank ribbon.
                        rail.tabs == 0 -> Row(
                            Modifier.fillMaxWidth().padding(horizontal = OVERVIEW_PADDING_DP.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(OVERVIEW_GAP_DP.dp),
                        ) {
                            Text(
                                stringResource(R.string.rails_no_tabs),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            if (!narrowing) NewTabTile(workspace, rail.suffix, newTabWidth, tileHeight, onNewTab)
                        }
                        // §7: emptied by narrowing, the rail stays and says so.
                        else -> Box(
                            Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = OVERVIEW_PADDING_DP.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                stringResource(R.string.overview_none_match),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A ribbon's last tile (§6): New tab in this rail's workspace only. */
@Composable
private fun NewTabTile(workspace: Workspace, suffix: Int?, width: Dp, height: Dp, onNewTab: (Workspace) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    val spoken = stringResource(R.string.new_tab_in, workspaceName(workspace, suffix))
    Column(
        Modifier
            .width(width)
            .heightIn(min = height)
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .border(BorderStroke(1.dp, scheme.outlineVariant), shape)
            .clearAndSetSemantics {
                contentDescription = spoken
                role = Role.Button
                onClick { onNewTab(workspace); true }
            }
            .clickable { onNewTab(workspace) }
            .padding(8.dp)
            .testTag("overview:rail:${workspace.id}:new-tab"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Default.Add, null, tint = scheme.primary)
        Text(stringResource(R.string.new_tab), style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/**
 * The Rails scroll: the outer list's state and one
 * ribbon state per workspace id. SessionScreen holds it beside tabsScope,
 * keyed by node and session only, so rotation, a sheet reopen, a view-mode
 * round trip and live updates keep both axes; the axes never move each
 * other. [placed] once the current tab was first put in view.
 */
@Stable
class RailsScroll private constructor(
    val outer: LazyListState,
    placed: Boolean,
    private val ribbons: HashMap<String, LazyListState>,
) {
    constructor() : this(LazyListState(), false, HashMap())

    var placed by mutableStateOf(placed)

    fun ribbon(workspace: String): LazyListState = ribbons.getOrPut(workspace) { LazyListState() }

    /** Drops the ribbons of workspaces no longer in [workspaces]. */
    fun keep(workspaces: Set<String>) {
        ribbons.keys.retainAll(workspaces)
    }

    companion object {
        val Saver: Saver<RailsScroll, Any> = listSaver(
            save = { scroll ->
                buildList {
                    add(scroll.placed)
                    add(scroll.outer.firstVisibleItemIndex)
                    add(scroll.outer.firstVisibleItemScrollOffset)
                    for ((id, ribbon) in scroll.ribbons) {
                        add(id)
                        add(ribbon.firstVisibleItemIndex)
                        add(ribbon.firstVisibleItemScrollOffset)
                    }
                }
            },
            restore = { saved ->
                val ribbons = HashMap<String, LazyListState>()
                for (i in 3 until saved.size step 3) {
                    ribbons[saved[i] as String] = LazyListState(saved[i + 1] as Int, saved[i + 2] as Int)
                }
                RailsScroll(LazyListState(saved[1] as Int, saved[2] as Int), saved[0] as Boolean, ribbons)
            },
        )
    }
}

/**
 * The workspace selector chip: this host's workspaces, All first; choosing
 * one only filters. A [gone] choice reads as a closed workspace, not All.
 */
@Composable
internal fun ScopeSelector(view: ShellView, groups: List<WorkspaceGroup>, chosen: String?, gone: Boolean, onScope: (String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val suffixes = remember(view.workspaces) { workspaceSuffixes(view.workspaces) }
    val picked = groups.firstOrNull { it.workspace.id == chosen }?.workspace
    val name = picked?.let { workspaceName(it, suffixes[it.id]) } ?: stringResource(if (gone) R.string.scope_gone else R.string.scope_all)
    val spoken = stringResource(R.string.scope_button, name)
    BoxWithConstraints {
        val menuWidth = maxWidth
        MaterialFilterChip(
            selected = picked != null || gone,
            onClick = { open = true },
            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = if (picked != null || gone) {
                { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
            trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = spoken }.testTag("overview:scope"),
        )
        GlassMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(menuWidth)) {
            ScopeItem(
                name = stringResource(R.string.scope_all),
                detail = pluralStringResource(R.plurals.overview_tabs, view.tabs.size, view.tabs.size),
                selected = picked == null && !gone,
                leading = { Icon(Icons.Filled.Check, null, Modifier.size(24.dp), tint = if (picked == null && !gone) MaterialTheme.colorScheme.primary else Color.Transparent) },
            ) {
                open = false
                onScope(null)
            }
            HorizontalDivider()
            for (workspace in view.workspaces) {
                ScopeItem(
                    name = workspaceName(workspace, suffixes[workspace.id]),
                    detail = workspaceDetail(view, workspace),
                    selected = workspace.id == chosen,
                    leading = { StatusGlyph(workspace.status, 10.dp, slot = 24.dp) },
                ) {
                    open = false
                    onScope(workspace.id)
                }
            }
        }
    }
}

/** A workspace's menu line: its branch, a linked worktree, then its tab count. */
@Composable
internal fun workspaceDetail(view: ShellView, workspace: Workspace): String {
    val tabs = view.tabsOf(workspace.id).size
    return listOfNotNull(
        workspace.branch?.takeIf { it.isNotBlank() },
        if (workspace.linkedWorktree) stringResource(R.string.overview_linked_worktree) else null,
        pluralStringResource(R.plurals.overview_tabs, tabs, tabs),
    ).joinToString(" · ")
}

@Composable
internal fun ScopeItem(name: String, detail: String, selected: Boolean, leading: @Composable () -> Unit, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Column(Modifier.padding(vertical = 8.dp)) {
                Text(
                    name,
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        },
        leadingIcon = leading,
        onClick = onClick,
    )
}

/** Shared compact category pills; the selected state and full label remain accessible. */
@Composable
private fun OverviewChip(label: String, selected: Boolean, glyph: AgentStatus?, tag: String, onClick: () -> Unit) {
    val color = glyph?.color() ?: MaterialTheme.colorScheme.primary
    FilterChip(label, selected, color, tag, leading = when {
        glyph != null -> ({ StatusGlyph(glyph, 8.dp, Modifier.clearAndSetSemantics { }, slot = 18.dp) })
        selected -> ({ Icon(Icons.Filled.Check, null, Modifier.size(18.dp)) })
        else -> null
    }, tagPrefix = "overview", onClick = onClick)
}

@Composable
private fun NoneMatch(onClear: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.overview_none_match),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onClear, modifier = Modifier.testTag("overview:clear").heightIn(min = 48.dp)) { Text(stringResource(R.string.overview_clear)) }
    }
}

/** The chosen workspace has closed: nothing of another workspace shows until the reader widens the scope. */
@Composable
internal fun ScopeGone(onShowAll: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(32.dp).testTag("tabs:scope-gone"), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            stringResource(R.string.scope_gone_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onShowAll, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.scope_show_all)) }
    }
}

/*
 * Material Symbols `grid_view` for Show as two columns and Choose pane. The
 * path is copied from material-icons-extended (Icons.Filled.GridView,
 * Copyright The Android Open Source Project, Apache-2.0); the app keeps only
 * the core icons.
 */
internal val GridIcon: ImageVector by lazy {
    ImageVector.Builder(name = "GridView", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M3,3v8h8V3H3zM13,3v8h8V3H13zM3,13v8h8v-8H3zM13,13v8h8v-8H3z"),
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        )
        .build()
}

/**
 * Show as workspace rails: three full-width rows of short tiles, one row per
 * workspace, drawn as [GridIcon] is (a 24 dp path, no extended icons).
 */
internal val RailsIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Rails", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M3,4h8v4H3zM13,4h8v4H13z" +
                    "M3,10h5v4H3zM10,10h4v4H10zM16,10h5v4H16z" +
                    "M3,16h11v4H3zM16,16h5v4H16z",
            ),
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        )
        .build()
}
