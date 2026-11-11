package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.ui.components.StatusGlyph

/**
 * A split tab's panes, as the Tabs sheet's second level (TB1 §6): Whole
 * tab goes to the tab as Herdr focuses it, a pane row to exactly that pane;
 * either closes the sheet through [onGo], the list's own path. With only
 * some panes matching the search, those come first under Matches. [here] is
 * marked: Herdr's focused pane, or in Chat ([reading]) the one read here (CT-1).
 */
@Composable
internal fun PanePicker(card: OverviewCard, here: String?, reading: Boolean, onBack: () -> Unit, onGo: (SwitchTarget) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val (matches, rest) = pickerPanes(card)
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    fun go(tile: PaneTile) = onGo(SwitchTarget.PaneRow(card.workspace, card.tab, tile.pane, tile.agent, tile.title))

    LazyColumn(Modifier.fillMaxWidth().testTag("picker"), contentPadding = PaddingValues(bottom = 16.dp + bottom)) {
        item(key = "header") {
            Row(Modifier.fillMaxWidth().padding(end = 16.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack, modifier = Modifier.testTag("picker:back")) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
                }
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
                    Text(card.row.title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOf(
                            workspaceName(card.workspace, card.suffix),
                            stringResource(R.string.overview_tab_n, card.tab.number),
                            pluralStringResource(R.plurals.pane_count, card.panes.size, card.panes.size),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        item(key = "whole") {
            PickerRow(
                selected = false,
                leading = { Icon(GridIcon, null, tint = scheme.onSurfaceVariant) },
                title = stringResource(R.string.picker_whole_tab),
                detail = stringResource(R.string.picker_whole_tab_sub, card.tab.number),
                fontScale = fontScale,
                mark = "",
                tag = "picker:whole",
            ) { onGo(card.row) }
        }
        if (matches.isNotEmpty()) {
            item(key = "label:matches") { PickerLabel(stringResource(R.string.picker_matches)) }
            items(matches, key = { "m:" + it.pane.id }) { tile -> PaneOption(tile, here, reading, fontScale) { go(tile) } }
        }
        item(key = "label:panes") { PickerLabel(stringResource(R.string.picker_panes)) }
        items(rest, key = { "p:" + it.pane.id }) { tile -> PaneOption(tile, here, reading, fontScale) { go(tile) } }
    }
}

@Composable
private fun PickerLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontSize = 14.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One pane: its status (or › for a shell), title, and "kind · Status · cwd"; the pane [here] is marked. */
@Composable
private fun PaneOption(tile: PaneTile, here: String?, reading: Boolean, fontScale: Float, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val focused = tile.pane.id == here
    val agent = tile.agent
    val cwd = tile.pane.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
    val detail = if (agent != null) {
        listOfNotNull(agent.agent ?: agent.name, statusLabel(agent.status), cwd)
    } else {
        listOfNotNull(stringResource(R.string.picker_shell), cwd)
    }.filter { it.isNotBlank() }.joinToString(" · ")
    PickerRow(
        selected = focused,
        leading = {
            if (agent != null && agent.status != AgentStatus.Unknown) {
                StatusGlyph(agent.status, 10.dp, slot = 24.dp)
            } else {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { Text("›", color = scheme.onSurfaceVariant) }
            }
        },
        title = tile.title.ifEmpty { tile.pane.label ?: tile.pane.id },
        detail = detail,
        fontScale = fontScale,
        mark = stringResource(if (reading) R.string.chat_reading_here else R.string.picker_focused),
        tag = "picker:pane:${tile.pane.id}",
        onClick = onClick,
    )
}

@Composable
private fun PickerRow(
    selected: Boolean,
    leading: @Composable () -> Unit,
    title: String,
    detail: String,
    fontScale: Float,
    mark: String,
    tag: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val content = if (selected) scheme.onSecondaryContainer else scheme.onSurface
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .fillMaxWidth()
            .heightIn(min = maxOf(56f, 24f + 40f * fontScale).dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (selected) scheme.secondaryContainer else Color.Transparent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading()
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontSize = 16.sp, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = if (selected) content else scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        if (selected) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.Check, null, tint = content, modifier = Modifier.size(18.dp))
                Text(mark, style = MaterialTheme.typography.labelLarge, color = content)
            }
        }
    }
}
