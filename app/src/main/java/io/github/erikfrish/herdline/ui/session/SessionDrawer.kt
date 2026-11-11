/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.ui.glass.menuColor

/** The open sidebar: Herdr's switcher as a list pane beside the session. */
val SidebarWidth: Dp = 320.dp

/** The folded sidebar: one 48 dp item per workspace. */
val SidebarRailWidth: Dp = 56.dp

/**
 * Herdr's sidebar kept beside the session on a wide window, open as the
 * switcher or folded to a rail of workspaces. Both draw Herdr's shared focus
 * as current; a tap asks Herdr to move it, as the switcher does.
 */
@Composable
fun SessionSidebar(
    open: Boolean,
    workspaces: List<Workspace>,
    onToggle: () -> Unit,
    onWorkspace: (Workspace) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Column(modifier.background(menuColor()).statusBarsPadding().navigationBarsPadding().testTag("session:sidebar")) {
        val label = stringResource(if (open) R.string.sidebar_fold else R.string.sidebar_show)
        Box(Modifier.fillMaxWidth(), contentAlignment = if (open) Alignment.CenterEnd else Alignment.Center) {
            IconButton(onClick = onToggle, modifier = Modifier.testTag("session:sidebar-toggle")) {
                Icon(if (open) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.Filled.Menu, contentDescription = label)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (open) content() else WorkspaceRail(workspaces, onWorkspace)
        }
    }
}

@Composable
private fun WorkspaceRail(workspaces: List<Workspace>, onWorkspace: (Workspace) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        items(workspaces, key = { it.id }) { workspace ->
            RailItem(workspace, onClick = { onWorkspace(workspace) })
        }
    }
}

@Composable
private fun RailItem(workspace: Workspace, onClick: () -> Unit) {
    val name = workspace.label.ifEmpty { workspace.number.toString() }
    val spoken = statusLabel(workspace.status)?.let { stringResource(R.string.rail_workspace, name, it) } ?: name
    val indicator = if (workspace.focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(indicator)
            .selectable(selected = workspace.focused, role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = spoken }
            .testTag("session:rail:${workspace.id}"),
        contentAlignment = Alignment.Center,
    ) {
        // The item's description names the workspace; the number alone would be read twice.
        Text(
            workspace.number.toString(),
            modifier = Modifier.clearAndSetSemantics {},
            style = MaterialTheme.typography.labelLarge,
            color = if (workspace.focused) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box(Modifier.align(Alignment.TopEnd)) { StatusDot(workspace.status, small = true) }
    }
}
