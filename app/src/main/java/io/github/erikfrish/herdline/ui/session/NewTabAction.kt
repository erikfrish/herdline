package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.ui.components.StatusGlyph
import io.github.erikfrish.herdline.ui.glass.GlassMenu

/** Where the Tabs sheet's New tab makes its tab: one known workspace, the reader picks, or nowhere. */
internal sealed interface NewTabTarget {
    data object None : NewTabTarget
    data class In(val workspace: Workspace) : NewTabTarget
    data class Pick(val workspaces: List<Workspace>) : NewTabTarget
}

/**
 * The workspace a New tab goes to: the chosen scope, or a host's only
 * workspace. With All and several workspaces, or a scope that has closed,
 * the reader picks one. Herdr's focused workspace is never assumed: it may
 * be the desktop's, not the reader's. No workspace offers nothing.
 */
internal fun newTabTarget(workspaces: List<Workspace>, chosen: String?, gone: Boolean): NewTabTarget {
    if (workspaces.isEmpty()) return NewTabTarget.None
    if (!gone) {
        chosen?.let { id -> workspaces.firstOrNull { it.id == id }?.let { return NewTabTarget.In(it) } }
        workspaces.singleOrNull()?.let { return NewTabTarget.In(it) }
    }
    return NewTabTarget.Pick(workspaces)
}

private val NEW_TAB_PADDING = 12.dp

/** The New tab button's width at this font scale: its padding, icon and the whole label, never cut. */
@Composable
internal fun newTabButtonWidth(): Dp {
    val label = stringResource(R.string.new_tab)
    val style = MaterialTheme.typography.labelLarge
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val text = remember(label, style, density) { with(density) { measurer.measure(label, style).size.width.toDp() } }
    return NEW_TAB_PADDING * 2 + ButtonDefaults.IconSize + ButtonDefaults.IconSpacing + text + 1.dp
}

/**
 * The Tabs sheet's New tab action, inline in the compact phone header
 * ([TabsHeader]) and in the sidebar's host line. A tap only opens the existing
 * New tab dialog through [newTab] (Prompt.NewTab): nothing reaches Herdr
 * before its Create, and the scope, search, order and drafts stay as they
 * are. When the workspace is ambiguous it first lists the workspaces, none
 * preselected, as wide as [menuWidth]. Disabled, label kept, while [view]
 * is not live; absent on a host with no workspace, whose empty state
 * offers New workspace instead.
 */
@Composable
internal fun NewTabButton(
    view: ShellView?, live: Boolean, chosen: String?, gone: Boolean,
    newTab: (Workspace) -> Unit, menuWidth: Dp, tag: String,
    compact: Boolean = false, showLabel: Boolean = true,
) {
    val workspaces = view?.workspaces.orEmpty()
    if (live && workspaces.isEmpty()) return
    val target = if (live) newTabTarget(workspaces, chosen, gone) else NewTabTarget.None
    var picking by remember { mutableStateOf(false) }
    val suffixes = remember(workspaces) { workspaceSuffixes(workspaces) }
    val spoken = when (target) {
        is NewTabTarget.In -> stringResource(R.string.new_tab_in, workspaceName(target.workspace, suffixes[target.workspace.id]))
        is NewTabTarget.Pick -> stringResource(R.string.new_tab_choose)
        NewTabTarget.None -> stringResource(R.string.new_tab)
    }
    val enabled = target != NewTabTarget.None
    // The tonal container is about 1.1:1 on the sheet, so the outline is the 3:1 boundary.
    // Disabled dims it like the label: inactive controls are exempt.
    val outline = MaterialTheme.colorScheme.outline.let { if (enabled) it else it.copy(alpha = 0.38f) }
    Box {
        val open = {
            when (target) {
                is NewTabTarget.In -> newTab(target.workspace)
                is NewTabTarget.Pick -> picking = true
                NewTabTarget.None -> Unit
            }
        }
        val button = Modifier.heightIn(min = 48.dp)
            .then(if (showLabel) Modifier else Modifier.width(48.dp))
            .semantics { contentDescription = spoken }.testTag(tag)
        val content: @Composable () -> Unit = {
            Icon(Icons.Default.Add, null, Modifier.size(if (compact) 18.dp else ButtonDefaults.IconSize))
            if (showLabel) {
                Spacer(Modifier.size(if (compact) 4.dp else ButtonDefaults.IconSpacing))
                Text(stringResource(R.string.new_tab), style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
        if (compact) {
            TextButton(onClick = open, enabled = enabled, contentPadding = PaddingValues(horizontal = 8.dp), modifier = button) { content() }
        } else {
            FilledTonalButton(
                onClick = open, enabled = enabled, border = BorderStroke(1.dp, outline),
                contentPadding = PaddingValues(horizontal = NEW_TAB_PADDING), modifier = button,
            ) { content() }
        }
        if (target is NewTabTarget.Pick && view != null) {
            GlassMenu(expanded = picking, onDismissRequest = { picking = false }, modifier = Modifier.width(menuWidth)) {
                Text(
                    stringResource(R.string.new_tab),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).semantics { heading() },
                )
                for (workspace in target.workspaces) {
                    ScopeItem(
                        name = workspaceName(workspace, suffixes[workspace.id]),
                        detail = workspaceDetail(view, workspace),
                        selected = false,
                        leading = { StatusGlyph(workspace.status, 10.dp, slot = 24.dp) },
                    ) {
                        picking = false
                        newTab(workspace)
                    }
                }
            }
        }
    }
}
