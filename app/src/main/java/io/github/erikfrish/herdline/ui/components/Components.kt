/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erikfrish.herdline.ui.glass.GlassGroup
import io.github.erikfrish.herdline.ui.glass.GlassPill
import io.github.erikfrish.herdline.ui.glass.GlassSectionHeader
import io.github.erikfrish.herdline.ui.theme.NodeColors

/** A node's initials on its colour label. */
@Composable
fun NodeAvatar(name: String, color: Int, size: Dp = 46.dp) {
    val background = NodeColors.of(color)
    val initials = name.split(' ', '-', '_', '.').filter { it.isNotBlank() }.take(2)
        .joinToString("") { it.first().uppercase() }.ifEmpty { "?" }
    val shape = RoundedCornerShape(size * 0.34f)
    Box(
        Modifier
            .size(size)
            .clip(shape)
            .background(background)
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            color = if (background.luminance() > 0.5f) Color(0xFF1B1B1B) else Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.38f).sp,
        )
    }
}

/** A small label: a route, a certificate, a status. */
@Composable
fun Pill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = false) = GlassPill(text, color, modifier, dot)

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier) = GlassSectionHeader(text, modifier)

/** A rounded surfaceContainerLow group of rows, the container every settings-like list uses. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = GlassGroup(modifier, content)

/**
 * One row of a [Group] as a Material [ListItem]: title, optional detail,
 * optional leading and trailing content. List rows cut [detail] at
 * [detailMaxLines]; Settings help passes [Int.MAX_VALUE] so it is never cut.
 */
@Composable
fun ItemRow(
    title: String,
    detail: String? = null,
    onClick: (() -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    chevron: Boolean = false,
    detailMaxLines: Int = 2,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        modifier = if (onClick != null) modifier.clickable(onClick = onClick, role = Role.Button) else modifier,
        supportingContent = detail?.let { { Text(it, maxLines = detailMaxLines, overflow = TextOverflow.Ellipsis) } },
        leadingContent = leading,
        trailingContent = if (trailing == null && !chevron) null else {
            {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    trailing?.invoke(this)
                    if (chevron) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

/** The node colour labels as tappable swatches; the chosen one grows a ring and a check. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: Int, onSelect: (Int) -> Unit) {
    FlowRow(Modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NodeColors.all.forEachIndexed { index, color ->
            val chosen = index == selected
            val ring by animateDpAsState(if (chosen) 3.dp else 0.dp, label = "ring")
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .border(ring, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f), CircleShape)
                    .padding(ring + if (chosen) 2.dp else 0.dp)
                    .clip(CircleShape)
                    .background(color)
                    .selectable(chosen, remember { MutableInteractionSource() }, indication = null, role = Role.RadioButton) { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                if (chosen) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
    }
}
