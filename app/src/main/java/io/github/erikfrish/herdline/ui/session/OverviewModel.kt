package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import kotlin.math.floor
import kotlin.math.max

/*
 * The phone Tabs overview's pure parts (TB1): the column rule, the card
 * height, the workspace scope, and the
 * cards, read only from the ShellView the connection already holds.
 */

/** The overview's outer padding, gap between cards, and narrowest card at font scale 1, in dp. */
internal const val OVERVIEW_PADDING_DP = 16f
internal const val OVERVIEW_GAP_DP = 12f
private const val MIN_CARD_DP = 148f
private const val MAX_COLUMNS = 4

/** The narrowest card at [fontScale]: it grows with the font, never below 148 dp. */
internal fun minCardDp(fontScale: Float): Float = max(MIN_CARD_DP, MIN_CARD_DP * fontScale)

/** How many cards a row of a sheet [widthDp] wide holds at [fontScale]: 1 to 4. */
internal fun overviewColumns(widthDp: Float, fontScale: Float): Int {
    val fits = floor((widthDp - 2 * OVERVIEW_PADDING_DP + OVERVIEW_GAP_DP) / (minCardDp(fontScale) + OVERVIEW_GAP_DP)).toInt()
    return fits.coerceIn(1, MAX_COLUMNS)
}

/** Each card's width in a sheet [widthDp] wide with [columns] columns. */
internal fun overviewCardWidthDp(widthDp: Float, columns: Int): Float =
    (widthDp - 2 * OVERVIEW_PADDING_DP - OVERVIEW_GAP_DP * (columns - 1)) / columns

/** The preview's pill row and its tile row, in dp at [fontScale]. */
internal fun previewPillDp(fontScale: Float): Float = max(24f, 12f * fontScale + 12f)
internal fun previewTileDp(fontScale: Float): Float = max(36f, 16.8f * fontScale + 12f)

/** The preview's height: its pill row and one row of pane tiles. */
internal fun overviewPreviewDp(fontScale: Float): Float =
    8f + previewPillDp(fontScale) + 6f + previewTileDp(fontScale) + 8f

/**
 * Every card's height at [fontScale], the same for all, so grid rows never
 * jitter: two title lines are always reserved.
 */
internal fun overviewCardDp(fontScale: Float): Float =
    8f + overviewPreviewDp(fontScale) + 8f + 44f * fontScale + 4f + 36f * fontScale + 12f

/** At most this many pane tiles on a card narrower than [WIDE_CARD_DP], else [WIDE_TILES]. */
private const val NARROW_TILES = 2
private const val WIDE_TILES = 3
internal const val WIDE_CARD_DP = 220f

/** One pane of a tab: its agent, if any, and its readable title (Titles.kt). */
data class PaneTile(val pane: Pane, val agent: Agent?, val title: String) {
    /** What a preview tile names: the agent kind, else the last segment of the pane's directory. */
    val place: String? get() = agent?.agent ?: pane.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
}

/**
 * One tab's card. [panes] are all of its panes in Herdr's order; [matched]
 * the ones a query or filter matched, or null when nothing narrows to a
 * part of the tab; [suffix] the workspace's `#n` when another workspace
 * shares its label.
 */
data class OverviewCard(
    val row: SwitchTarget.TabRow,
    val panes: List<PaneTile>,
    val matched: List<PaneTile>?,
    val current: Boolean,
    val suffix: Int?,
) {
    val key: String get() = row.key
    val tab: Tab get() = row.tab
    val workspace: Workspace get() = row.workspace
    val split: Boolean get() = panes.size > 1

    /** The tiles a card [widthDp] wide shows, and how many panes a last "+k" tile stands for (0: none). */
    fun tiles(widthDp: Float): Pair<List<PaneTile>, Int> {
        val room = if (widthDp < WIDE_CARD_DP) NARROW_TILES else WIDE_TILES
        if (panes.size <= room) return panes to 0
        return panes.take(room - 1) to panes.size - (room - 1)
    }
}

/** A workspace's run of cards; [suffix] as on [OverviewCard]. */
data class OverviewSection(val workspace: Workspace, val suffix: Int?, val cards: List<OverviewCard>)

/**
 * Each workspace's `#n` among those sharing its label, in Herdr's order;
 * absent for a label only one workspace has.
 */
internal fun workspaceSuffixes(workspaces: List<Workspace>): Map<String, Int> {
    val byLabel = workspaces.groupBy { it.label }
    return buildMap {
        for (same in byLabel.values) {
            if (same.size < 2) continue
            same.forEachIndexed { index, workspace -> put(workspace.id, index + 1) }
        }
    }
}

/** The workspace a saved [chosen] id still names in [groups], else none (All workspaces). */
internal fun scopedWorkspace(groups: List<WorkspaceGroup>, chosen: String?): String? =
    chosen?.takeIf { id -> groups.any { it.workspace.id == id } }

/** [groups] limited to the workspace [scope], or all of them. */
internal fun List<WorkspaceGroup>.scoped(scope: String?): List<WorkspaceGroup> =
    if (scope == null) this else filter { it.workspace.id == scope }

/**
 * The cards of [all] (the scoped topology) that [shown] (the same, narrowed)
 * kept, by workspace in Herdr's order. A tab's card shows when its tab row
 * or any of its pane rows matched. When [narrowing] (a query or filter is
 * on) and only some of a split tab's panes matched, the card carries them.
 * The tab [currentTab] names (see [overviewCurrentTab]) is current. Live
 * updates never reorder: Herdr's order is kept.
 */
internal fun overviewSections(
    all: List<WorkspaceGroup>,
    shown: List<WorkspaceGroup>,
    narrowing: Boolean,
    view: ShellView,
    currentTab: String? = view.focusedTab,
): List<OverviewSection> {
    val suffixes = workspaceSuffixes(view.workspaces)
    val kept = shown.associateBy { it.workspace.id }
    return all.mapNotNull { group ->
        val hits = kept[group.workspace.id]?.rows ?: return@mapNotNull null
        val tabHits = hits.filterIsInstance<SwitchTarget.TabRow>().mapTo(HashSet()) { it.tab.id }
        val paneHits = hits.filterIsInstance<SwitchTarget.PaneRow>().mapTo(HashSet()) { it.pane.id }
        val cards = group.rows.filterIsInstance<SwitchTarget.TabRow>().mapNotNull { row ->
            val panes = view.panesOf(row.tab.id).map { PaneTile(it, view.agentIn(it.id), view.paneTitle(it.id)) }
            val matchedPanes = panes.filter { it.pane.id in paneHits }
            if (row.tab.id !in tabHits && matchedPanes.isEmpty()) return@mapNotNull null
            OverviewCard(
                row = row,
                panes = panes,
                matched = matchedPanes.takeIf { narrowing && panes.size > 1 && it.isNotEmpty() && it.size < panes.size },
                current = row.tab.id == currentTab,
                suffix = suffixes[group.workspace.id],
            )
        }
        cards.takeIf { it.isNotEmpty() }?.let { OverviewSection(group.workspace, suffixes[group.workspace.id], it) }
    }
}

/**
 * The tab the overview marks current: in Chat ([reading]) the tab of [here],
 * the pane read on this phone (CT-1), or none once that pane has closed (S5);
 * otherwise Herdr's focused tab. The desktop's focus never marks a card in Chat.
 */
internal fun overviewCurrentTab(view: ShellView, here: String?, reading: Boolean): String? =
    if (reading) here?.let { id -> view.panes.firstOrNull { it.id == id }?.tab } else view.focusedTab

/** The index in a grid laid out from [sections] of the card for [tab], with one header per section when [headers]. */
internal fun overviewIndexOf(sections: List<OverviewSection>, tab: String?, headers: Boolean): Int? {
    if (tab == null) return null
    var index = 0
    for (section in sections) {
        if (headers) index++
        for (card in section.cards) {
            if (card.tab.id == tab) return index
            index++
        }
    }
    return null
}

/**
 * The Needs you and Working counts of the status chips: agents in [groups]
 * (the scoped topology) with that status that [query] matches, also in
 * single-pane tabs.
 */
internal fun List<WorkspaceGroup>.agentCounts(query: String): Pair<Int, Int> {
    val agents = map { WorkspaceGroup(it.workspace, it.agentRows, it.agentRows) }
    fun count(filter: SwitcherFilter) = agents.narrowed(query, filter).sumOf { it.rows.size }
    return count(SwitcherFilter.NeedsYou) to count(SwitcherFilter.Working)
}

/**
 * A split tab's panes for the picker: with only some matching, those first
 * (under Matches), then the rest; else none first and all in Herdr's order.
 */
internal fun pickerPanes(card: OverviewCard): Pair<List<PaneTile>, List<PaneTile>> {
    val matched = card.matched ?: return emptyList<PaneTile>() to card.panes
    val ids = matched.mapTo(HashSet()) { it.pane.id }
    return matched to card.panes.filter { it.pane.id !in ids }
}
