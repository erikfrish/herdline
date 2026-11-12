package io.github.erikfrish.herdline.ui.session

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.ui.components.SheetCloseButton
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.data.Node
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
    node: Node,
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
    modifier: Modifier = Modifier,
    here: String? = view?.focusedPane,
    reading: Boolean = false,
    /** The phone sheet's visible way out (U-1 #7); null where there is no sheet to close. */
    onClose: (() -> Unit)? = null,
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
    val headers = chosen == null
    // Hoisted over the picker, so Back finds the rows where they were.
    val rows = rememberLazyGridState()
    var picking by rememberSaveable { mutableStateOf<String?>(null) }
    val card = picking?.let { id -> sections.firstNotNullOfOrNull { s -> s.cards.firstOrNull { it.tab.id == id } } }
    LaunchedEffect(picking, card == null) { if (picking != null && card == null) picking = null }
    BackHandler(enabled = picking != null) { picking = null }
    // Once per open, without animation: the current tab is on screen even with 20+ tabs.
    var placed by remember { mutableStateOf(false) }
    LaunchedEffect(sections.isNotEmpty()) {
        if (placed || sections.isEmpty()) return@LaunchedEffect
        placed = true
        overviewIndexOf(sections, currentTab, headers)?.let { rows.scrollToItem(it) }
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
            OverviewHeader(host, view, chosen, scoped.gone, live = state is Connection.State.Connected, actions, onShowList, onClose)
            Box(Modifier.padding(horizontal = 16.dp)) {
                val fontScale = LocalDensity.current.fontScale
                SearchField(query, onQuery, stringResource(R.string.overview_search), minHeight = maxOf(48f, 16f * fontScale + 24f).dp)
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (view != null) ScopeSelector(view, groups, chosen, scoped.gone, onScope)
                if (filtersShown) {
                    OverviewChip(stringResource(R.string.filter_all), filter == SwitcherFilter.All, null) { onFilter(SwitcherFilter.All) }
                    OverviewChip(stringResource(R.string.filter_needs_you, counts.needsYou), filter == SwitcherFilter.NeedsYou, AgentStatus.Blocked) {
                        onFilter(SwitcherFilter.NeedsYou)
                    }
                    OverviewChip(stringResource(R.string.filter_working, counts.working), filter == SwitcherFilter.Working, AgentStatus.Working) {
                        onFilter(SwitcherFilter.Working)
                    }
                    OverviewChip(stringResource(R.string.filter_ready, counts.ready), filter == SwitcherFilter.Ready, null) {
                        onFilter(SwitcherFilter.Ready)
                    }
                }
            }
            when {
                view == null || state !is Connection.State.Connected -> Box(Modifier.padding(horizontal = 16.dp)) { HostLine(node, view, state) }
                view.workspaces.isEmpty() -> GlassButton(
                    stringResource(R.string.new_workspace),
                    onClick = actions.newWorkspace,
                    icon = Icons.Default.Add,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).heightIn(min = 48.dp).testTag("overview:new-workspace"),
                )
                scoped.gone -> ScopeGone { onScope(null) }
                sections.isEmpty() -> NoneMatch {
                    onQuery("")
                    onFilter(SwitcherFilter.All)
                    onScope(null)
                }
                else -> OverviewRows(sections, headers, rows, pins, monitored, actions) { picking = it.tab.id }
            }
        }
    }
}

/** Title, then New tab, Show as list and Close; New tab takes its own row, right-aligned, before its label would be cut. */
@Composable
private fun OverviewHeader(
    host: String,
    view: ShellView?,
    chosen: String?,
    gone: Boolean,
    live: Boolean,
    actions: SwitcherActions,
    onShowList: () -> Unit,
    onClose: (() -> Unit)?,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sheetWidth = maxWidth
        // Start and end padding, the title's least room, the button, then Show as list and Close.
        val inline = sheetWidth >= 16.dp + 4.dp + HEADER_TITLE_MIN + newTabButtonWidth() + 48.dp + (if (onClose != null) 48.dp else 0.dp)
        val newTab: @Composable () -> Unit = {
            NewTabButton(view, live, chosen, gone, actions.newTab, menuWidth = sheetWidth, tag = "overview:new-tab")
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.overview_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    if (view != null) {
                        Text(
                            stringResource(
                                R.string.overview_sub,
                                host,
                                pluralStringResource(R.plurals.overview_tabs, view.tabs.size, view.tabs.size),
                                pluralStringResource(R.plurals.overview_workspaces, view.workspaces.size, view.workspaces.size),
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (inline) newTab()
                IconButton(onClick = onShowList, modifier = Modifier.testTag("overview:as-list")) {
                    Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.overview_as_list))
                }
                if (onClose != null) SheetCloseButton(onClose, tag = "overview:close")
            }
            if (!inline) Box(Modifier.fillMaxWidth().padding(end = 12.dp), contentAlignment = Alignment.CenterEnd) { newTab() }
        }
    }
}

/** The least width a header's title keeps beside New tab; below it New tab wraps to its own row. */
internal val HEADER_TITLE_MIN = 96.dp

/** The rows: a full-span header per workspace with All chosen, then its tabs in Herdr's tab order, up to two columns. */
@Composable
private fun OverviewRows(
    sections: List<OverviewSection>,
    headers: Boolean,
    rows: LazyGridState,
    pins: List<String>,
    monitored: (String) -> Boolean,
    actions: SwitcherActions,
    onPick: (OverviewCard) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = compactColumns(maxWidth.value, fontScale)
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
                        OverviewWorkspaceHeader(section, actions)
                    }
                }
                items(section.cards, key = { it.key }) { card ->
                    OverviewRow(card, card.key in pins, monitored, actions, onPick)
                }
            }
        }
    }
}

/** A workspace's name for a header or a row line: its label, with `#n` when another workspace shares it. */
@Composable
internal fun workspaceName(workspace: Workspace, suffix: Int?): String =
    suffix?.let { stringResource(R.string.overview_ws_suffix, workspace.label, it) } ?: workspace.label

@Composable
private fun OverviewWorkspaceHeader(section: OverviewSection, actions: SwitcherActions) {
    var menu by remember { mutableStateOf(false) }
    val workspace = section.workspace
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            Text(
                workspaceName(workspace, section.suffix),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(
                    workspace.branch?.takeIf { it.isNotBlank() },
                    pluralStringResource(R.plurals.overview_tabs, section.cards.size, section.cards.size),
                ).joinToString(" · "),
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
    ).joinToString(", ")
    val openLabel = stringResource(if (card.split) R.string.overview_choose_pane else R.string.overview_open)
    val moreLabel = stringResource(R.string.more)
    val pinLabel = stringResource(if (pinned) R.string.unpin else R.string.pin)
    val renameLabel = stringResource(R.string.rename_tab)
    val closeLabel = stringResource(R.string.session_close_tab)

    Box(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(scheme.surfaceContainerHigh)
            .border(border, shape)
            .testTag("overview:card:${card.tab.id}"),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clearAndSetSemantics {
                    contentDescription = spoken
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
                if (card.current) {
                    Row(
                        Modifier.clip(CircleShape).background(scheme.primary).padding(horizontal = 8.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Icon(Icons.Default.Check, null, tint = scheme.onPrimary, modifier = Modifier.size(14.dp))
                        Text(stringResource(R.string.overview_current), style = MaterialTheme.typography.labelMedium, color = scheme.onPrimary, maxLines = 1)
                    }
                }
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
                if (status != null) StatusGlyph(card.tab.status, 8.dp)
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
        FilterChip(
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

/** A single-select status chip; Needs you and Working lead with their glyph, never colour alone. */
@Composable
private fun OverviewChip(label: String, selected: Boolean, glyph: AgentStatus?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        leadingIcon = when {
            glyph != null -> {
                { StatusGlyph(glyph, 8.dp, Modifier.clearAndSetSemantics { }, slot = FilterChipDefaults.IconSize) }
            }
            selected -> {
                { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
            }
            else -> null
        },
        modifier = Modifier.heightIn(min = 48.dp),
    )
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
