package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.TargetOrder
import io.github.erikfrish.herdline.ui.components.StatusGlyph
import io.github.erikfrish.herdline.ui.glass.GlassMenu

/**
 * What the Filter control's line says (W10152): the status choice with its
 * count when it is not All, and the order when it is not Herdr's Workspace
 * order. Neither is active: the line says All.
 */
internal data class FilterSummary(val status: SwitcherFilter?, val count: Int?, val lastActivity: Boolean) {
    val active: Boolean get() = status != null || lastActivity
}

/** [filter] and [order] (null where Tabs has no order choice) as the Filter control's line; [counts] are the chips' own. */
internal fun filterSummary(filter: SwitcherFilter, order: TargetOrder?, counts: FilterCounts): FilterSummary {
    val count = when (filter) {
        SwitcherFilter.All -> null
        SwitcherFilter.NeedsYou -> counts.needsYou
        SwitcherFilter.Working -> counts.working
        SwitcherFilter.Ready -> counts.ready
    }
    return FilterSummary(filter.takeIf { it != SwitcherFilter.All }, count, order == TargetOrder.LastActivity)
}

/** The status a choice is drawn with, as its chip was; null for All. */
private val SwitcherFilter.glyph: AgentStatus?
    get() = when (this) {
        SwitcherFilter.All -> null
        SwitcherFilter.NeedsYou -> AgentStatus.Blocked
        SwitcherFilter.Working -> AgentStatus.Working
        SwitcherFilter.Ready -> AgentStatus.Done
    }

@Composable
private fun statusText(filter: SwitcherFilter, counts: FilterCounts): String = when (filter) {
    SwitcherFilter.All -> stringResource(R.string.filter_all)
    SwitcherFilter.NeedsYou -> stringResource(R.string.filter_needs_you, counts.needsYou)
    SwitcherFilter.Working -> stringResource(R.string.filter_working, counts.working)
    SwitcherFilter.Ready -> stringResource(R.string.filter_ready, counts.ready)
}

/**
 * One Filter control in place of the Order row and the status chips
 * (W10152): a 48 dp button whose line names what is active, wrapping to a
 * second line rather than cutting a long label; it opens Order (when
 * [onOrder] is given) and the status choices (when [statuses]), each a whole
 * line in the menu. TalkBack hears "Filter", the active choices as its state,
 * and each choice as selected or not.
 */
@Composable
internal fun FilterControl(
    filter: SwitcherFilter,
    onFilter: (SwitcherFilter) -> Unit,
    counts: FilterCounts,
    statuses: Boolean,
    order: TargetOrder?,
    onOrder: ((TargetOrder) -> Unit)?,
    tagPrefix: String,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    val summary = filterSummary(if (statuses) filter else SwitcherFilter.All, order.takeIf { onOrder != null }, counts)
    val value = listOfNotNull(
        summary.status?.let { statusText(it, counts) },
        stringResource(R.string.order_last_activity).takeIf { summary.lastActivity },
    ).joinToString(DESTINATION_SEPARATOR).ifEmpty { stringResource(R.string.filter_all) }
    val name = stringResource(R.string.tabs_filter)
    val openLabel = stringResource(R.string.tabs_filter_open)
    val outline = if (summary.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    BoxWithConstraints(modifier) {
        val menuWidth = maxWidth.coerceAtLeast(240.dp)
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, outline, RoundedCornerShape(24.dp))
                .clickable(role = Role.Button, onClickLabel = openLabel) { open = true }
                .semantics {
                    contentDescription = name
                    stateDescription = value
                }
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .testTag("$tagPrefix:filter"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (summary.active) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
            Text(
                stringResource(R.string.tabs_filter_value, value),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (summary.active) FontWeight.SemiBold else FontWeight.Medium,
                // Two lines at a large font or a long label, then an ellipsis; the state above is said whole.
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false).clearAndSetSemantics { },
            )
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp))
        }
        GlassMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.width(menuWidth)) {
            if (onOrder != null && order != null) {
                MenuHeading(stringResource(R.string.order_label))
                Column(Modifier.selectableGroup()) {
                    for ((choice, label) in listOf(TargetOrder.Workspace to R.string.order_workspace, TargetOrder.LastActivity to R.string.order_last_activity)) {
                        Choice(stringResource(label), order == choice, null, "$tagPrefix:filter:order:${choice.name.lowercase()}") {
                            open = false
                            onOrder(choice)
                        }
                    }
                }
                if (statuses) HorizontalDivider()
            }
            if (statuses) {
                MenuHeading(stringResource(R.string.tabs_filter_status))
                Column(Modifier.selectableGroup()) {
                    for (choice in SwitcherFilter.entries) {
                        Choice(statusText(choice, counts), filter == choice, choice.glyph, "$tagPrefix:filter:${choice.tag}") {
                            open = false
                            onFilter(choice)
                        }
                    }
                }
            }
        }
    }
}

/** The status chips' old test tags, kept for the menu's choices. */
private val SwitcherFilter.tag: String
    get() = when (this) {
        SwitcherFilter.All -> "all"
        SwitcherFilter.NeedsYou -> "needs_you"
        SwitcherFilter.Working -> "working"
        SwitcherFilter.Ready -> "ready"
    }

@Composable
private fun MenuHeading(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** One radio line of the Filter menu: its whole label, wrapping if it must, a check when [selected]. */
@Composable
private fun Choice(label: String, selected: Boolean, glyph: AgentStatus?, tag: String, onClick: () -> Unit) {
    DropdownMenuItem(
        text = {
            Text(
                label,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (selected) FontWeight.SemiBold else null,
            )
        },
        leadingIcon = if (glyph != null) ({ StatusGlyph(glyph, 10.dp, Modifier.clearAndSetSemantics { }, slot = 24.dp) }) else null,
        trailingIcon = { Icon(Icons.Filled.Check, null, Modifier.size(20.dp), tint = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent) },
        onClick = onClick,
        modifier = Modifier.heightIn(min = 48.dp).semantics {
            this.selected = selected
            role = Role.RadioButton
        }.testTag(tag),
    )
}
