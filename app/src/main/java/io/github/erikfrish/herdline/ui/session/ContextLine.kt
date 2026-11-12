package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import kotlin.math.min

/** The px each span of the place line gets; 0 is not drawn. */
internal data class ContextWidths(val workspace: Int, val position: Int, val desktop: Int)

/**
 * How [available] px are shared by the place line's spans, given each one's
 * full width (A-24). The position (n/N with its marks) is never cut: a cut
 * number misleads, so it is whole or, when it alone does not fit, gone. The
 * workspace then takes up to its own width, and the Desktop text the rest, so
 * Desktop is what ellipsizes first.
 */
internal fun contextWidths(available: Int, workspace: Int, position: Int, desktop: Int): ContextWidths {
    val room = available.coerceAtLeast(0)
    val kept = if (position <= room) position else 0
    val workspaceWidth = min(workspace, room - kept)
    return ContextWidths(workspaceWidth, kept, min(desktop, room - kept - workspaceWidth))
}

/**
 * The session place button's line of context (HEADER-COMPACT): the
 * [workspace], then [position] ("3/8"), a changed mark when [marked], and
 * while [desktopElsewhere] a monitor glyph with, if given, its [desktop] text.
 * Laid out by [contextWidths], so the Desktop text gives way first, then the
 * workspace; n/N, the mark and the glyph never ellipsize, so the desktop being
 * elsewhere always shows, and not by colour. The caller speaks the whole line.
 */
@Composable
internal fun PlaceLine(
    workspace: String?,
    position: String?,
    marked: Boolean,
    desktopElsewhere: Boolean,
    desktop: String?,
    halo: Color,
    modifier: Modifier = Modifier,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val style = MaterialTheme.typography.labelSmall
    val markColor = MaterialTheme.colorScheme.onSurface
    val text = desktop?.takeIf { desktopElsewhere }
    Layout(
        content = {
            if (workspace != null) Text(workspace, style = style, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (position != null) {
                    Text(if (workspace != null) " · $position" else position, style = style, color = muted, maxLines = 1, softWrap = false)
                }
                if (marked) {
                    // The strip's changed dot, its halo in the header's colour (TabRow.kt).
                    Box(Modifier.size(10.dp).drawBehind { drawUnseenMark(center, halo, markColor) }.testTag("session:place_mark"))
                }
                if (desktopElsewhere) {
                    if (workspace != null || position != null) Text(" · ", style = style, color = muted, maxLines = 1, softWrap = false)
                    Icon(DesktopIcon, null, tint = muted, modifier = Modifier.size(14.dp).testTag("session:desktop_elsewhere"))
                }
            }
            if (text != null) Text(" $text", style = style, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val full = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val positionAt = if (workspace != null) 1 else 0
        val desktopAt = if (text != null) positionAt + 1 else -1
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else full.sum()
        val widths = contextWidths(
            available,
            workspace = if (workspace != null) full[0] else 0,
            position = full[positionAt],
            desktop = if (desktopAt >= 0) full[desktopAt] else 0,
        )
        val placeables = measurables.mapIndexedNotNull { i, measurable ->
            val width = when (i) {
                positionAt -> widths.position
                desktopAt -> widths.desktop
                else -> widths.workspace
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

/*
 * Material Symbols `computer` for the desktop-elsewhere marker. The path is
 * copied from material-icons-extended (Icons.Outlined.Computer, Copyright The
 * Android Open Source Project, Apache-2.0); the app keeps only the core icons.
 */
internal val DesktopIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Computer", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes("M20,18c1.1,0 1.99,-0.9 1.99,-2L22,6c0,-1.1 -0.9,-2 -2,-2H4c-1.1,0 -2,0.9 -2,2v10c0,1.1 0.9,2 2,2H0v2h24v-2h-4zM4,6h16v10H4V6z"),
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        )
        .build()
}
