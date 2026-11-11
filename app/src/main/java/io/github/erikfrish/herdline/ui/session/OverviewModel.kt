package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import kotlin.math.floor
import kotlin.math.max

/*
 * The phone Tabs sheet's pure parts (TB1): the column rule, the workspace
 * scope, and the cards, read only from the ShellView the connection already
 * holds.
 */

/** The overview's outer padding and gap between rows, in dp. */
internal const val OVERVIEW_PADDING_DP = 16f
internal const val OVERVIEW_GAP_DP = 12f

/** The narrowest readable compact list row at font scale 1, in content dp per column. */
private const val MIN_ROW_DP = 152f

/**
 * How many columns the compact list lays out in a sheet [widthDp] wide at
 * [fontScale]: at most two, and one when two columns would each fall under
 * [MIN_ROW_DP], which is where large accessibility fonts make two illegible.
 */
internal fun compactColumns(widthDp: Float, fontScale: Float): Int {
    val content = widthDp - 2 * OVERVIEW_PADDING_DP - OVERVIEW_GAP_DP
    return if (content >= 2 * MIN_ROW_DP * fontScale) 2 else 1
}

/** One pane of a tab: its agent, if any, and its readable title (Titles.kt). */
data class PaneTile(val pane: Pane, val agent: Agent?, val title: String)

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

/** The workspace a saved [chosen] id still names in [groups], else none. */
internal fun scopedWorkspace(groups: List<WorkspaceGroup>, chosen: String?): String? =
    chosen?.takeIf { id -> groups.any { it.workspace.id == id } }

/** [groups] limited to the workspace [scope], or all of them. */
internal fun List<WorkspaceGroup>.scoped(scope: String?): List<WorkspaceGroup> =
    if (scope == null) this else filter { it.workspace.id == scope }

/**
 * The phone Tabs sheet's workspace scope, one for its Grid and its List:
 * [chosen], the workspace the saved id still names, or null for all of
 * them, and [groups], the topology in that scope. Cards, rows, pinned and
 * recent places and the status chips' counts all come from [groups]. A
 * chosen workspace that has closed is [gone]: the sheet then shows no
 * workspace and says so, never widening to all of them on its own.
 */
internal data class TabsScope(val chosen: String?, val gone: Boolean, val groups: List<WorkspaceGroup>)

/** [all] (this host's topology) in the saved scope [saved]; [loaded] once a view has arrived, so a choice is not "gone" before. */
internal fun tabsScope(all: List<WorkspaceGroup>, saved: String?, loaded: Boolean): TabsScope {
    val chosen = scopedWorkspace(all, saved)
    val gone = loaded && saved != null && chosen == null
    return TabsScope(chosen, gone, if (gone) emptyList() else all.scoped(chosen))
}

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

/** The Needs you, Working and Ready counts of the status chips. */
internal data class FilterCounts(val needsYou: Int, val working: Int, val ready: Int)

/**
 * The counts of the status chips: agents in [groups] (the scoped topology)
 * with each filter's status that [query] matches, also in single-pane tabs.
 * Every filter counts what it lists, hidden rows included, so the chips stay
 * honest.
 */
internal fun List<WorkspaceGroup>.agentCounts(query: String): FilterCounts {
    val agents = map { WorkspaceGroup(it.workspace, it.agentRows, it.agentRows) }
    fun count(filter: SwitcherFilter) = agents.narrowed(query, filter).sumOf { it.rows.size }
    return FilterCounts(count(SwitcherFilter.NeedsYou), count(SwitcherFilter.Working), count(SwitcherFilter.Ready))
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
