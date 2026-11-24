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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.isSpecified

/** The px each span of the place line gets; 0 is not drawn. [workspace] is the host's span (W10152). */
internal data class ContextWidths(val workspace: Int, val position: Int, val icon: Int, val desktop: Int)

/**
 * How [available] px are shared by the place line's spans, given each one's
 * full width, in rank order (W10152). The desktop [icon] is always kept: the
 * desktop being elsewhere is what the line must say. The host's name (the
 * [workspace] slot) comes next and is never dropped for n/N: it is whole, or
 * narrower and wrapped to a second line, or gone only below its
 * [workspaceMin] (four characters and "…"). The position (n/N with its
 * marks) follows in what is left and is never cut, since a cut number
 * misleads (A-24): it is whole or gone. The desktop's name comes last, only
 * beside n/N, whole or cut no narrower than [desktopMin]. n/N pays the
 * [separator] that parts it from the host; whatever is drawn just left of
 * the icon pays the [gap] before it, never a separator.
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
    val iconGap = if (icon > 0) gap else 0
    val hostWidth = span(workspace, workspaceMin, room - iconGap)
    if (hostWidth > 0) room -= hostWidth + iconGap
    val positionCost = position + if (hostWidth > 0) separator else iconGap
    val kept = if (position > 0 && positionCost <= room) position else 0
    if (kept > 0) room -= positionCost
    val desktopWidth = if (position > 0 && kept == 0) 0 else span(desktop, desktopMin, room)
    return ContextWidths(hostWidth, kept, icon, desktopWidth)
}

private fun span(full: Int, min: Int, room: Int) = when {
    full <= 0 -> 0
    full <= room -> full
    room >= min -> room
    else -> 0
}

/** [text] as its shortest drawn form: its first four characters and "…", or whole if no longer. */
private fun shortest(text: String): String =
    if (text.codePointCount(0, text.length) <= 4) text else text.substring(0, text.offsetByCodePoints(0, 4)) + "…"

/**
 * The session place button's line of context (HEADER-COMPACT, W10152): the
 * node's [workspace] slot (the host's name), then [position] ("3/8"), a
 * changed mark when [marked], and while [desktopElsewhere] a monitor glyph
 * with, if given, the [desktop] tab's name. Laid out by [contextWidths], so
 * n/N gives way before the host, which wraps to a second line rather than
 * cut; n/N, the mark and the glyph never ellipsize, and the glyph is never
 * dropped, so the desktop being elsewhere always shows, and not by colour.
 * The caller speaks the whole line.
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
            HostText(workspace.orEmpty(), style, muted, measurer)
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
        // Slot 0 (the host) is sized from its text: its layout picks size and line break from the width it gets.
        val full = measurables.mapIndexed { i, m -> if (i == 0) 0 else m.maxIntrinsicWidth(Constraints.Infinity) }
        val gap = IconGap.roundToPx()
        fun measured(span: String) = measurer.measure(span, style, maxLines = 1, softWrap = false).size.width
        val available = if (constraints.hasBoundedWidth) constraints.maxWidth else full.sum()
        val widths = contextWidths(
            available,
            workspace = if (workspace != null) measured(workspace) else 0,
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

/**
 * The host's name in [width] px (W10153): one line at [style]; else one line
 * a step smaller; else two lines broken only after a hyphen or before " · "
 * ([hostBreak]), at [style] and then smaller; only a name with no such fit
 * wraps freely, at most two lines, then "…".
 */
private fun hostLayout(text: String, width: Int, style: TextStyle, measurer: TextMeasurer): TextLayoutResult {
    val small = style.copy(
        fontSize = style.fontSize * HostStep,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * HostStep else style.lineHeight,
    )
    fun line(s: String, at: TextStyle) = measurer.measure(s, at, maxLines = 2, softWrap = false)
    for (at in listOf(style, small)) line(text, at).let { if (it.size.width <= width) return it }
    for (at in listOf(style, small)) {
        hostBreak(text, width) { line(it, at).size.width }?.let { return line(it, at) }
    }
    return measurer.measure(text, style, TextOverflow.Ellipsis, maxLines = 2, constraints = Constraints(maxWidth = width.coerceAtLeast(0)))
}

/** The one step smaller size a host name may take before it breaks (W10153). */
private const val HostStep = 0.85f

/**
 * [text] on two lines broken after a hyphen or before " · " (that line then
 * starts "· "), each no wider than [width] by [measure]; the longest first
 * line that fits wins. Null when no such break fits: a word is never cut.
 */
internal fun hostBreak(text: String, width: Int, measure: (String) -> Int): String? {
    val cuts = buildList {
        for (i in 1 until text.length) if (text[i - 1] == '-') add(i to i)
        var j = text.indexOf(" · ")
        while (j > 0) {
            add(j to j + 1)
            j = text.indexOf(" · ", j + 1)
        }
    }.sortedByDescending { it.first }
    return cuts.firstNotNullOfOrNull { (end, start) ->
        val first = text.substring(0, end)
        val rest = text.substring(start)
        if (measure(first) <= width && measure(rest) <= width) "$first\n$rest" else null
    }
}

/** The host's name, laid out by [hostLayout] for the width its slot gives it. */
@Composable
private fun HostText(text: String, style: TextStyle, color: Color, measurer: TextMeasurer) {
    val drawn = remember { mutableStateOf<TextLayoutResult?>(null) }
    Layout(
        modifier = Modifier.testTag("session:place_host").drawBehind { drawn.value?.let { drawText(it, color) } },
        measurePolicy = object : MeasurePolicy {
            override fun MeasureScope.measure(measurables: List<Measurable>, constraints: Constraints): MeasureResult {
                val result = hostLayout(text, constraints.maxWidth, style, measurer)
                drawn.value = result
                return layout(result.size.width.coerceAtMost(constraints.maxWidth), result.size.height) {}
            }

            private fun whole() = measurer.measure(text, style, maxLines = 1, softWrap = false).size

            override fun IntrinsicMeasureScope.maxIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = whole().width
            override fun IntrinsicMeasureScope.minIntrinsicWidth(measurables: List<IntrinsicMeasurable>, height: Int) = whole().width
            override fun IntrinsicMeasureScope.maxIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = whole().height
            override fun IntrinsicMeasureScope.minIntrinsicHeight(measurables: List<IntrinsicMeasurable>, width: Int) = whole().height
        },
    )
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
