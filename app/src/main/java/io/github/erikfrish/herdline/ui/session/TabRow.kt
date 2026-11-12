package io.github.erikfrish.herdline.ui.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.ui.components.StatusGlyph
import io.github.erikfrish.herdline.ui.components.bumps
import io.github.erikfrish.herdline.ui.components.statusSpoken
import io.github.erikfrish.herdline.ui.glass.GlassMenu
import io.github.erikfrish.herdline.ui.glass.GlassMenuItem
import io.github.erikfrish.herdline.ui.theme.indicatorAccent

/** Titles in the tab row are cut here, so one long task title never hides its neighbours. */
private const val TAB_TITLE_CHARS = 18

/** [title] cut to [TAB_TITLE_CHARS] code points with an ellipsis. */
internal fun tabRowTitle(title: String): String {
    val count = title.codePointCount(0, title.length)
    if (count <= TAB_TITLE_CHARS) return title
    return title.substring(0, title.offsetByCodePoints(0, TAB_TITLE_CHARS - 1)).trimEnd() + "…"
}

/**
 * The first pane of [tab] whose delivery is unconfirmed ([held] pane ids,
 * from `DraftStore.held`), or null: a tab is held when any of its panes is
 *. The id names the badge's `held:<pane id>` tag.
 */
internal fun ShellView.heldPaneOf(tab: String, held: Set<String>): String? =
    if (held.isEmpty()) null else panes.firstOrNull { it.tab == tab && it.id in held }?.id

/**
 * The tabs marked "changed since you looked" after the tabs went from the
 * statuses [before] to [now]: a tab that was marked stays so, and a tab whose
 * glyph [bumps] (a real change into Blocked or Done) is marked, until it is
 * the [selectedId] tab, which never is. A tab with no earlier status is not
 * marked: nothing was seen before, so nothing changed since.
 */
internal fun nextUnseen(before: Map<String, AgentStatus>, now: List<Tab>, selectedId: String?, unseen: Set<String>): Set<String> =
    now.mapNotNullTo(HashSet()) { t ->
        t.id.takeIf { it != selectedId && (it in unseen || bumps(before[it], t.status)) }
    }

/** What [rememberUnseenTabs] last applied: each tab's status and the marked tabs. */
internal class SeenTabs(
    var statuses: Map<String, AgentStatus> = emptyMap(),
    var unseen: Set<String> = emptySet(),
)

/**
 * [SeenTabs] as saved state: tab ids with their status names, then the
 * marked ids, so a font or locale change or a process death keeps the
 * marks. A status name this build doesn't know drops that tab's status.
 */
internal val SeenTabsSaver: Saver<SeenTabs, Any> = Saver(
    save = { arrayListOf(ArrayList(it.statuses.keys), ArrayList(it.statuses.values.map(AgentStatus::name)), ArrayList(it.unseen)) },
    restore = { saved ->
        @Suppress("UNCHECKED_CAST")
        val (ids, names, unseen) = saved as List<List<String>>
        val statuses = ids.zip(names).mapNotNull { (id, name) -> AgentStatus.entries.firstOrNull { it.name == name }?.let { id to it } }
        SeenTabs(statuses.toMap(), unseen.toSet())
    },
)

/**
 * The tabs marked changed since the user looked ([nextUnseen]), tracked over
 * recompositions and saved state, per [node] and [session]. Kept by the
 * session header, so the strip and the place button show one and the same
 * set, whichever of them is on screen.
 */
@Composable
internal fun rememberUnseenTabs(tabs: List<Tab>, selectedId: String?, node: String, session: String): Set<String> {
    // Plain fields, written after each applied composition, so a discarded one changes nothing.
    val seen = rememberSaveable(node, session, saver = SeenTabsSaver) { SeenTabs() }
    val unseen = nextUnseen(seen.statuses, tabs, selectedId, seen.unseen)
    SideEffect {
        seen.statuses = tabs.associate { it.id to it.status }
        seen.unseen = unseen
    }
    return unseen
}

/** The changed mark: a [color] dot cut out of what is under it by a [halo] ring at [center]. */
internal fun DrawScope.drawUnseenMark(center: Offset, halo: Color, color: Color) {
    drawCircle(halo, radius = 4.dp.toPx(), center = center)
    drawCircle(color, radius = 2.5.dp.toPx(), center = center)
}

/** A tab's Rename and Close tab, from a long press on it in the strip or on the session's place button. */
@Composable
internal fun TabMenu(expanded: Boolean, onDismiss: () -> Unit, onRename: () -> Unit, onClose: () -> Unit) {
    GlassMenu(expanded = expanded, onDismissRequest = onDismiss) {
        GlassMenuItem(stringResource(R.string.rename_tab), Icons.Default.Edit, onClick = { onDismiss(); onRename() })
        GlassMenuItem(stringResource(R.string.session_close_tab), Icons.Default.Close, onClick = { onDismiss(); onClose() }, destructive = true)
    }
}

/**
 * The workspace's tabs in Herdr order, one dedicated 48 dp row: ‹ previous,
 * a scrolling strip that keeps the active tab centred and fades where tabs
 * are hidden, and › next. Where it is shown (a short window's merged header
 * and the wide layout), the session's place button beside or above it says
 * n/N and opens Tabs. ‹ and › stop at the ends instead of wrapping. A long
 * press on a tab offers rename and close, as the ⋮ menu does for the focused one.
 * [heldPane] names a tab's pane with an unconfirmed delivery, which marks
 * the tab with a lock and "delivery not confirmed" (AUDIT R7); no tap of its own.
 * [tabState] adds a spoken state of the caller's, such as Chat's "Reading here" (CT-1).
 * A background tab in [unseen] ([rememberUnseenTabs]) gets a dot on its
 * glyph's corner and "changed" until it is selected; the glyph's own bump is
 * the one-off cue. [backdrop] is the colour the row is drawn on, so the dot's
 * halo matches it exactly.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TabRow(
    tabs: List<Tab>,
    title: (Tab) -> String,
    selectedId: String?,
    accent: Color,
    onTab: (String) -> Unit,
    onRename: (Tab) -> Unit,
    onClose: (Tab) -> Unit,
    // Required: the row draws on the header's colour, not surface, and a default would hide a missed caller.
    backdrop: Color,
    heldPane: (Tab) -> String? = { null },
    tabState: (Tab) -> String? = { null },
    // Required, as the header's: a default would hide a caller that forgot to track it.
    unseen: Set<String>,
) {
    val list = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val selected = tabs.indexOfFirst { it.id == selectedId }
    LaunchedEffect(selected, tabs.size) {
        if (selected < 0) return@LaunchedEffect
        if (list.layoutInfo.visibleItemsInfo.none { it.index == selected }) list.scrollToItem(selected)
        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == selected } ?: return@LaunchedEffect
        val viewport = list.layoutInfo.viewportEndOffset - list.layoutInfo.viewportStartOffset
        list.animateScrollBy((item.offset + item.size / 2 - viewport / 2).toFloat())
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Guarded to 3:1 on surface: a raw node colour can fall to 2.59.
    val indicator = MaterialTheme.colorScheme.indicatorAccent(accent)
    val heldState = stringResource(R.string.held_state)
    val changedState = stringResource(R.string.tab_changed)
    val markColor = MaterialTheme.colorScheme.onSurface
    // The halo cuts the dot out of the glyph beneath it, in the row's own backdrop.
    val haloColor = backdrop
    Row(
        Modifier.fillMaxWidth().height(48.dp).testTag("session:tabrow"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ‹ and › are laid over the strip's ends, after it, so they come later in
        // the semantics tree. Compose gives a node's accessibility bounds the area
        // no later node has claimed, without clipping to the parent, so a partly
        // scrolled tab now loses the overlap to ‹ or › instead of shrinking them.
        Box(Modifier.weight(1f).fillMaxHeight()) {
            val fade = 40.dp
            LazyRow(
                state = list,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 48.dp)
                    // Offscreen for the edge fades; clipped so no tab draws under ‹ or ›.
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        clip = true
                    }
                    .drawWithContent {
                        drawContent()
                        val edge = fade.toPx()
                        if (list.canScrollBackward) {
                            drawRect(Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), 0f, edge), size = Size(edge, size.height), blendMode = BlendMode.DstOut)
                        }
                        if (list.canScrollForward) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), size.width - edge, size.width),
                                topLeft = Offset(size.width - edge, 0f),
                                size = Size(edge, size.height),
                                blendMode = BlendMode.DstOut,
                            )
                        }
                    }
                    .testTag("session:tabs"),
            ) {
                itemsIndexed(tabs, key = { _, t -> t.id }) { _, t ->
                    val chosen = t.id == selectedId
                    val text by animateColorAsState(if (chosen) MaterialTheme.colorScheme.onSurface else muted, label = "tabText")
                    var menu by remember { mutableStateOf(false) }
                    val held = heldPane(t)
                    val mark = t.id in unseen
                    val state = listOfNotNull(tabState(t), statusSpoken(t.status), changedState.takeIf { mark }, held?.let { heldState }).joinToString(", ")
                    Box(Modifier.fillMaxHeight()) {
                        Row(
                            Modifier
                                .fillMaxHeight()
                                .drawBehind {
                                    if (chosen) {
                                        val inset = 10.dp.toPx()
                                        val line = 3.dp.toPx()
                                        drawRect(indicator, topLeft = Offset(inset, size.height - line), size = Size(size.width - 2 * inset, line))
                                    }
                                }
                                .semantics {
                                    this.selected = chosen
                                    if (state.isNotEmpty()) stateDescription = state
                                }
                                .combinedClickable(
                                    role = Role.Tab,
                                    onClick = { onTab(t.id) },
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        menu = true
                                    },
                                    onLongClickLabel = stringResource(R.string.more),
                                )
                                .padding(horizontal = 12.dp)
                                .testTag("tab:${t.label}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (t.status != AgentStatus.Unknown) {
                                StatusGlyph(
                                    t.status,
                                    10.dp,
                                    // Drawn over the glyph and outside the bump's layer, so it holds still and
                                    // overflows into the gap and padding: the tab keeps its bounds.
                                    Modifier.drawWithContent {
                                        drawContent()
                                        if (mark) {
                                            val corner = Offset(if (layoutDirection == LayoutDirection.Ltr) size.width else 0f, 0f)
                                            drawUnseenMark(corner, haloColor, markColor)
                                        }
                                    },
                                )
                            }
                            Text(
                                tabRowTitle(title(t)),
                                color = text,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = if (chosen) FontWeight.Medium else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (held != null) {
                                Icon(Icons.Filled.Lock, null, tint = muted, modifier = Modifier.size(12.dp).testTag("held:$held"))
                            }
                        }
                        TabMenu(menu, onDismiss = { menu = false }, onRename = { onRename(t) }, onClose = { onClose(t) })
                    }
                }
            }
            StepButton(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                stringResource(R.string.session_tab_previous),
                selected > 0,
                "session:tab_previous",
                Modifier.align(Alignment.CenterStart),
            ) {
                onTab(tabs[selected - 1].id)
            }
            StepButton(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                stringResource(R.string.session_tab_next),
                selected in 0 until tabs.lastIndex,
                "session:tab_next",
                Modifier.align(Alignment.CenterEnd),
            ) {
                onTab(tabs[selected + 1].id)
            }
        }
    }
}

/** ‹ or ›: 48 dp square, the row's full height, dimmed at the end of the row. */
@Composable
private fun StepButton(icon: ImageVector, label: String, enabled: Boolean, tag: String, modifier: Modifier, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.onSurface.let { if (enabled) it else it.copy(alpha = 0.3f) }
    Box(
        modifier
            .width(48.dp)
            .fillMaxHeight()
            .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = tint)
    }
}
