package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** The px each span of the place line gets; 0 is not drawn. */
internal data class ContextWidths(val workspace: Int, val position: Int, val icon: Int, val desktop: Int)

/**
 * How [available] px are shared by the place line's spans, given each one's
 * full width, in rank order. The desktop [icon] is always kept: the desktop
 * being elsewhere is what the line must say. The position (n/N with its marks)
 * is never cut, since a cut number misleads (A-24): it is whole or gone. The
 * desktop's name comes next, only beside n/N, then the workspace, which
 * ellipsizes first. A text span is whole, cut no narrower than its
 * [workspaceMin] / [desktopMin] (four characters and "…"), or gone. The
 * workspace pays the [separator] that parts it from n/N; whatever is drawn
 * just left of the icon pays the [gap] before it, never a separator.
 */
internal fun contextWidths(
    available: Int,
    workspace: Int,
    workspaceMin: Int,
    position: Int,
    icon: Int,
    separator: Int,
    gap: Int,
    desktop: Int,
    desktopMin: Int,
): ContextWidths {
    var room = available.coerceAtLeast(0) - icon
    val positionCost = position + if (icon > 0) gap else 0
    val kept = if (position > 0 && positionCost <= room) position else 0
    if (kept > 0) room -= positionCost
    val desktopWidth = if (position > 0 && kept == 0) 0 else span(desktop, desktopMin, room)
    room -= desktopWidth
    val workspaceWidth = span(workspace, workspaceMin, room - if (kept > 0) separator else if (icon > 0) gap else 0)
    return ContextWidths(workspaceWidth, kept, icon, desktopWidth)
}

private fun span(full: Int, min: Int, room: Int) = when {
    full <= room -> full
    room >= min -> room
    else -> 0
}

/** [text] as its shortest drawn form: its first four characters and "…", or whole if no longer. */
private fun shortest(text: String): String =
    if (text.codePointCount(0, text.length) <= 4) text else text.substring(0, text.offsetByCodePoints(0, 4)) + "…"

/**
 * The session place button's line of context (HEADER-COMPACT): the
 * [workspace], then [position] ("3/8"), a changed mark when [marked], and
 * while [desktopElsewhere] a monitor glyph with, if given, the [desktop] tab's
 * name. Laid out by [contextWidths], so the workspace gives way first, then
 * the desktop's name; n/N, the mark and the glyph never ellipsize, and the
 * glyph is never dropped, so the desktop being elsewhere always shows, and not
 * by colour. The caller speaks the whole line.
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
    val text = desktop?.takeIf { desktopElsewhere }?.let { " $it" }
    val measurer = rememberTextMeasurer()
    Layout(
        // Fixed slots, each placed only when its width is not 0.
        content = {
            Text(workspace.orEmpty(), style = style, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
            Text(" · ", style = style, color = muted, maxLines = 1, softWrap = false)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (position != null) Text(position, style = style, color = muted, maxLines = 1, softWrap = false)
                if (marked) {
                    // The strip's changed dot, its halo in the header's colour (TabRow.kt).
                    Box(Modifier.size(10.dp).drawBehind { drawUnseenMark(center, halo, markColor) }.testTag("session:place_mark"))
                }
            }
            Box(Modifier.width(IconGap))
            Icon(DesktopIcon, null, tint = muted, modifier = Modifier.size(14.dp).testTag("session:desktop_elsewhere"))
            Text(text.orEmpty(), style = style, color = muted, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val full = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity) }
        val gap = IconGap.roundToPx()
        fun measured(span: String) = measurer.measure(span, style, maxLines = 1, softWrap = false).size.width
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else full.sum()
        val widths = contextWidths(
            available,
            workspace = if (workspace != null) full[0] else 0,
            workspaceMin = workspace?.let { measured(shortest(it)) } ?: 0,
            position = full[2],
            icon = if (desktopElsewhere) full[4] else 0,
            separator = full[1],
            gap = gap,
            desktop = if (text != null) full[5] else 0,
            // The leading space, then four characters of the name itself.
            desktopMin = if (text != null) measured(" " + shortest(desktop.orEmpty())) else 0,
        )
        val leftOfIcon = widths.workspace > 0 || widths.position > 0
        val slots = listOf(
            widths.workspace,
            if (widths.workspace > 0 && widths.position > 0) full[1] else 0,
            widths.position,
            if (widths.icon > 0 && leftOfIcon) gap else 0,
            widths.icon,
            widths.desktop,
        )
        val placeables = measurables.mapIndexedNotNull { i, measurable ->
            if (slots[i] <= 0) null else measurable.measure(Constraints(maxWidth = slots[i]))
        }
        // One text line tall whatever is drawn, the icon alone included, so
        // the header keeps its height when every text span gives way.
        val line = measurables[1].maxIntrinsicHeight(full[1])
        val height = maxOf(line, placeables.maxOfOrNull { it.height } ?: 0)
        layout(placeables.sumOf { it.width }.coerceAtMost(available), height) {
            var x = 0
            for (placeable in placeables) {
                placeable.place(x, (height - placeable.height) / 2)
                x += placeable.width
            }
        }
    }
}

/** Between the desktop icon and the span drawn before it: a space, not a separator. */
private val IconGap = 4.dp

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
