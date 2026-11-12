package io.github.erikfrish.herdline.ui.session

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import kotlin.math.min

/** The px each span of the session's context line gets; 0 is not drawn. */
internal data class ContextWidths(val host: Int, val count: Int, val desktop: Int)

/**
 * How [available] px are shared by the context line's spans, given each one's
 * full width (A-24). The tab count is never cut: a cut number misleads, so it
 * is whole or, when it alone does not fit, gone. The host then takes up to its
 * own width, and Desktop the rest, so Desktop is what ellipsizes first.
 */
internal fun contextWidths(available: Int, host: Int, count: Int, desktop: Int): ContextWidths {
    val room = available.coerceAtLeast(0)
    val kept = if (count <= room) count else 0
    val hostWidth = min(host, room - kept)
    return ContextWidths(hostWidth, kept, min(desktop, room - kept - hostWidth))
}

/**
 * The session header's context line: [host], the tab [count] and [desktop],
 * each on one line, laid out by [contextWidths] so a long host or Desktop name
 * never hides how many tabs there are. One TalkBack node that says [spoken],
 * so the host is never read alone.
 */
@Composable
internal fun ContextLine(host: String?, count: String?, desktop: String?, spoken: String, modifier: Modifier = Modifier) {
    val spans = listOfNotNull(host, count, desktop)
    Layout(
        content = {
            // The separator leads every span after the first, so a dropped span takes its own.
            for ((i, span) in spans.withIndex()) {
                Text(
                    if (i == 0) span else " · $span",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        modifier = modifier.clearAndSetSemantics { contentDescription = spoken },
    ) { measurables, constraints ->
        val full = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        fun fullOf(present: Boolean, index: Int) = if (present) full[index] else 0
        val hostAt = if (host != null) 0 else -1
        val countAt = if (count != null) hostAt + 1 else -1
        val desktopAt = if (desktop != null) maxOf(hostAt, countAt) + 1 else -1
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else full.sum()
        val widths = contextWidths(available, fullOf(hostAt >= 0, hostAt), fullOf(countAt >= 0, countAt), fullOf(desktopAt >= 0, desktopAt))
        val placeables = measurables.mapIndexedNotNull { i, measurable ->
            val width = when (i) {
                hostAt -> widths.host
                countAt -> widths.count
                else -> widths.desktop
            }
            if (width <= 0) null else measurable.measure(Constraints(maxWidth = width))
        }
        val height = placeables.maxOfOrNull { it.height } ?: 0
        layout(placeables.sumOf { it.width }.coerceAtMost(available), height) {
            var x = 0
            for (placeable in placeables) {
                placeable.place(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}
