package io.github.erikfrish.herdline.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R

/** The strip's own height: the terminal's clip ends at its top edge. */
val OutputFocusStripHeight = 48.dp

/**
 * The output-focus strip under the terminal: how many of the grid's bottom
 * rows are scrolled past, or that nothing is, and one tap back to the full
 * terminal. It names no content: the rows are counted, never described.
 */
@Composable
fun OutputFocusStrip(hidden: Int, onOff: () -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val text = if (hidden > 0) pluralStringResource(R.plurals.output_focus_hidden, hidden, hidden) else stringResource(R.string.output_focus_nothing)
    val description = if (hidden > 0) pluralStringResource(R.plurals.output_focus_strip_description, hidden, hidden) else text
    val actionLabel = stringResource(if (hidden > 0) R.string.output_focus_off else R.string.output_focus_turn_off)
    Row(
        modifier
            .fillMaxWidth()
            .height(OutputFocusStripHeight)
            .background(colors.surfaceContainerHigh)
            .drawBehind {
                val edge = 1.dp.toPx()
                drawLine(colors.outline, Offset(0f, edge / 2), Offset(size.width, edge / 2), edge)
            }
            .testTag("session:output_focus_strip")
            .clickable(onClick = onOff)
            .clearAndSetSemantics {
                contentDescription = description
                role = Role.Button
                liveRegion = LiveRegionMode.Polite
                onClick(actionLabel) { onOff(); true }
            }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Default.KeyboardArrowDown, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        // The count comes first, so the ellipsis never cuts it.
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            stringResource(if (hidden > 0) R.string.output_focus_show_all else R.string.output_focus_turn_off),
            style = MaterialTheme.typography.labelLarge,
            color = colors.primary,
            maxLines = 1,
        )
    }
}
