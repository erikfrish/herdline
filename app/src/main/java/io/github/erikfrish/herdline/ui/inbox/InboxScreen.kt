package io.github.erikfrish.herdline.ui.inbox

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.ui.components.StatusGlyph
import io.github.erikfrish.herdline.ui.components.statusSpoken
import io.github.erikfrish.herdline.ui.glass.GlassScreen
import io.github.erikfrish.herdline.ui.glass.GlassMenu
import io.github.erikfrish.herdline.ui.session.SwitcherFilter
import io.github.erikfrish.herdline.ui.session.statusLabel
import io.github.erikfrish.herdline.ui.session.switcherFilter
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf

/** Agents that need the user across every attached host: the Agents badge, never scoped. */
fun needsYouCount(connections: Map<String, Connection>): Int =
    connections.values.sumOf { connection -> connection.view.value?.agents?.count { it.status == AgentStatus.Blocked } ?: 0 }

/** A [ScopeKey] kept across Back, rotation and process death. */
private val ScopeSaver = listSaver<ScopeKey?, String>(
    save = { key -> key?.let { listOf(it.nodeId, it.session, it.workspaceId) } ?: emptyList() },
    restore = { parts -> if (parts.size == 3) ScopeKey(parts[0], parts[1], parts[2]) else null },
)

/**
 * Every agent across the attached hosts, narrowed by one host's workspace and
 * a status. Rows keep Herdr's order within a host; tapping one opens that
 * session with the pane focused, the same path a notification takes. Choosing
 * a workspace only filters: it never focuses, connects or sends.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalLayoutApi::class)
@Composable
fun InboxScreen(app: HerdlineApp, onOpen: (nodeId: String, pane: String) -> Unit) {
    // Each host's live view: a status change on any host recomposes this screen.
    val hostsFlow = remember(app) {
        app.connections.flatMapLatest { connections ->
            if (connections.isEmpty()) {
                flowOf(emptyList<HostSnapshot>())
            } else {
                combine(connections.values.map { c -> combine(c.state, c.view) { _, view -> HostSnapshot(c.node, view) } }) { it.toList() }
            }
        }
    }
    val hosts by hostsFlow.collectAsState(initial = app.connections.value.values.map { HostSnapshot(it.node, it.view.value) })
    // Opens on the saved start filter; a chip tap changes only this visit.
    var filter by rememberSaveable { mutableStateOf(app.settings.settings.value.startFilter.switcherFilter) }
    var scope by rememberSaveable(stateSaver = ScopeSaver) { mutableStateOf<ScopeKey?>(null) }

    val all = agentRows(hosts)
    val groups = scopeGroups(hosts, all, keep = scope)
    val state = scopeState(scope, hosts, groups)
    // A detached host or a closed workspace drops the choice; a host still loading keeps it.
    LaunchedEffect(state, scope) {
        if (state == ScopeState.All && scope != null) scope = null
    }
    val scoped = all.inScope(state)
    val shown = scoped.withStatus(filter)

    GlassScreen(
        title = stringResource(R.string.dest_agents),
    ) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().testTag("inbox")) {
            item(key = "filters") {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    ScopeSelector(
                        state = state,
                        groups = groups,
                        agents = all.size,
                        hosts = hosts.size,
                        onScope = { scope = it },
                    )
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        StatusChip(stringResource(R.string.filter_all), filter == SwitcherFilter.All, null) { filter = SwitcherFilter.All }
                        StatusChip(stringResource(R.string.filter_needs_you, scoped.needsYou()), filter == SwitcherFilter.NeedsYou, AgentStatus.Blocked) {
                            filter = SwitcherFilter.NeedsYou
                        }
                        StatusChip(stringResource(R.string.filter_working, scoped.working()), filter == SwitcherFilter.Working, AgentStatus.Working) {
                            filter = SwitcherFilter.Working
                        }
                        StatusChip(stringResource(R.string.filter_ready, scoped.ready()), filter == SwitcherFilter.Ready, null) {
                            filter = SwitcherFilter.Ready
                        }
                    }
                }
            }
            if (hosts.isEmpty()) {
                item(key = "empty") { Empty(stringResource(R.string.inbox_no_hosts)) }
            } else if (shown.isEmpty()) {
                // Nothing can match before any host has sent its first view.
                val loading = hosts.all { it.view == null }
                item(key = "empty") { Empty(stringResource(if (loading) R.string.agents_loading else R.string.agents_none_match)) }
            } else {
                items(shown, key = { it.nodeId + ":" + it.paneId }) { row ->
                    InboxItem(row, onOpen = { onOpen(row.nodeId, row.paneId) })
                }
            }
        }
    }
}

/** A chosen workspace's name: its label, with its host when the label alone is ambiguous. */
@Composable
private fun scopeName(item: ScopeItem): String =
    item.onHost?.let { stringResource(R.string.scope_on_host, item.label, it) } ?: item.label

/** The workspace filter chip and its menu of hosts and their workspaces. */
@Composable
private fun ScopeSelector(state: ScopeState, groups: List<ScopeGroup>, agents: Int, hosts: Int, onScope: (ScopeKey?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val chosen = (state as? ScopeState.Chosen)?.item
    val name = chosen?.let { scopeName(it) } ?: stringResource(R.string.scope_all)
    val spoken = stringResource(R.string.scope_button, name)
    val choose = stringResource(R.string.scope_choose)
    BoxWithConstraints {
        // The menu spans the row's content width.
        val menuWidth = maxWidth
        // It opens a menu, so it is a button, not a toggle: an AssistChip in
        // the filter chip's selected colours while a workspace is chosen.
        AssistChip(
            onClick = { open = true },
            label = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            leadingIcon = if (chosen != null) {
                { Icon(Icons.Filled.Check, null, Modifier.size(FilterChipDefaults.IconSize)) }
            } else {
                null
            },
            trailingIcon = { Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(FilterChipDefaults.IconSize)) },
            colors = if (chosen != null) {
                AssistChipDefaults.assistChipColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            } else {
                AssistChipDefaults.assistChipColors()
            },
            border = if (chosen != null) null else AssistChipDefaults.assistChipBorder(enabled = true),
            modifier = Modifier
                .semantics {
                    contentDescription = spoken
                    role = Role.Button
                    onClick(label = choose) { open = true; true }
                }
                .testTag("scope"),
        )
        GlassMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.width(menuWidth),
        ) {
            ScopeMenuItem(
                name = stringResource(R.string.scope_all),
                detail = pluralStringResource(R.plurals.scope_agents, agents, agents, pluralStringResource(R.plurals.scope_hosts, hosts, hosts)),
                // The workspaces' glyph column, empty here: All's ✓ trails as theirs does.
                leading = { Spacer(Modifier.size(24.dp)) },
                spoken = null,
                selected = chosen == null,
                onClick = {
                    open = false
                    onScope(null)
                },
            )
            for (group in groups) {
                HorizontalDivider()
                Text(
                    group.header,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).semantics { heading() },
                )
                for (item in group.items) {
                    val status = statusSpoken(item.status)
                    val said = pluralStringResource(R.plurals.scope_item_spoken, item.agents, item.agents, item.label, group.header)
                    ScopeMenuItem(
                        name = item.label,
                        detail = pluralStringResource(R.plurals.scope_ws_agents, item.agents, item.agents, item.number),
                        leading = { StatusGlyph(item.status, 10.dp, slot = 24.dp) },
                        spoken = listOfNotNull(said, status).joinToString(", "),
                        selected = item.key == chosen?.key,
                        onClick = {
                            open = false
                            onScope(item.key)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeMenuItem(
    name: String,
    detail: String,
    leading: @Composable () -> Unit,
    spoken: String?,
    onClick: () -> Unit,
    selected: Boolean = false,
) {
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
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        leadingIcon = leading,
        // The choice is marked by a ✓ and spoken as selected, never by colour alone.
        trailingIcon = if (selected) {
            { Icon(Icons.Filled.Check, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary) }
        } else {
            null
        },
        onClick = onClick,
        modifier = if (spoken != null) {
            Modifier.clearAndSetSemantics {
                contentDescription = spoken
                this.selected = selected
                this.onClick { onClick(); true }
            }
        } else {
            Modifier.semantics { this.selected = selected }
        },
    )
}

/** A single-select status chip; Needs you and Working lead with their glyph, never colour alone. */
@Composable
private fun StatusChip(label: String, selected: Boolean, glyph: AgentStatus?, onClick: () -> Unit) {
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
    )
}

@Composable
private fun Empty(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(32.dp),
    )
}

@Composable
private fun InboxItem(row: InboxRow, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onOpen)
            .padding(horizontal = 20.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.clearAndSetSemantics { }) { StatusGlyph(row.status, 10.dp) }
            Text(
                row.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            statusLabel(row.status)?.let { label ->
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.size(2.dp))
        Text(
            listOfNotNull(row.host, row.where.takeIf { it.isNotEmpty() }).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
