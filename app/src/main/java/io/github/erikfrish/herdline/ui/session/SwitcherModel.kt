package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.data.StartFilter

/** One place the switcher can take the user: a tab, or one pane of it. */
sealed interface SwitchTarget {
    val key: String
    val workspace: Workspace
    val tab: Tab

    /**
     * A tab. [agents] are the tab's agents: a single-pane tab is named by,
     * searched and filtered through its one agent; a split tab lists its
     * panes as [PaneRow]s under this row.
     */
    data class TabRow(
        override val workspace: Workspace,
        override val tab: Tab,
        val panes: Int,
        val agents: List<Agent> = emptyList(),
        val title: String = "",
    ) : SwitchTarget {
        override val key get() = "t:" + tab.id
    }

    /**
     * One pane of a split tab. [title] is the pane's readable name from
     * Titles.kt; the agent kind ("omp") is never a title, only a detail.
     */
    data class PaneRow(
        override val workspace: Workspace,
        override val tab: Tab,
        val pane: Pane,
        val agent: Agent?,
        val title: String = "",
    ) : SwitchTarget {
        override val key get() = "p:" + pane.id
    }
}

/** A workspace, the rows under it, and every agent row, in Herdr's order. */
data class WorkspaceGroup(
    val workspace: Workspace,
    val rows: List<SwitchTarget>,
    /**
     * A row for every agent pane, including the single-pane tabs that show
     * no pane row of their own. This is what filters, search and Needs-you
     * cycling walk, so every agent stays reachable from the model alone.
     */
    val agentRows: List<SwitchTarget.PaneRow> = emptyList(),
)

/** Which rows the switcher shows; every filter keeps Herdr's order. */
enum class SwitcherFilter { All, NeedsYou, Working }

/** The filter a saved [StartFilter] opens the Tabs list and Inbox with. */
val StartFilter.switcherFilter: SwitcherFilter
    get() = when (this) {
        StartFilter.All -> SwitcherFilter.All
        StartFilter.NeedsYou -> SwitcherFilter.NeedsYou
        StartFilter.Working -> SwitcherFilter.Working
    }

/**
 * The rows the Tabs list toggles hide: [keys] to leave out, and [count], how
 * many of them the list would otherwise show. Pane rows of a hidden tab go
 * with it but count only as their tab.
 */
data class HiddenRows(val keys: Set<String>, val count: Int)

/**
 * Which rows [hideShells] and [hideIdleDone] hide. A shell is a tab or pane
 * without an agent; idle-and-done means every agent in it is Idle or Done.
 * A row with an agent that needs you is never hidden, and neither is its
 * tab, since a tab holds all of its panes' agents.
 */
fun List<WorkspaceGroup>.hiddenRows(hideShells: Boolean, hideIdleDone: Boolean): HiddenRows {
    if (!hideShells && !hideIdleDone) return HiddenRows(emptySet(), 0)
    val keys = HashSet<String>()
    var count = 0
    for (group in this) {
        var tabHidden = false
        for (row in group.rows) {
            val statuses = when (row) {
                is SwitchTarget.TabRow -> row.agents.map { it.status }
                is SwitchTarget.PaneRow -> listOfNotNull(row.agent?.status)
            }
            val own = when {
                AgentStatus.Blocked in statuses -> false
                statuses.isEmpty() -> hideShells
                else -> hideIdleDone && statuses.all { it == AgentStatus.Idle || it == AgentStatus.Done }
            }
            when (row) {
                is SwitchTarget.TabRow -> {
                    tabHidden = own
                    if (own) count++
                }
                is SwitchTarget.PaneRow -> if (!tabHidden && own) count++
            }
            if (own || (row is SwitchTarget.PaneRow && tabHidden)) keys += row.key
        }
    }
    return HiddenRows(keys, count)
}

/** The groups without the rows [keys] name; groups left empty are dropped, order never changes. */
fun List<WorkspaceGroup>.without(keys: Set<String>): List<WorkspaceGroup> {
    if (keys.isEmpty()) return this
    return mapNotNull { group ->
        group.rows.filter { it.key !in keys }.takeIf { it.isNotEmpty() }?.let { group.copy(rows = it) }
    }
}

/**
 * The daemon's topology as rows: each workspace as its group, under it every
 * tab as one row, then pane rows only for tabs that have more than one pane.
 * A single-pane tab — agent or bare shell — is just its tab row; the
 * redundant agent row under it is gone.
 */
fun topology(view: ShellView): List<WorkspaceGroup> = view.workspaces.map { workspace ->
    val rows = mutableListOf<SwitchTarget>()
    val agentRows = mutableListOf<SwitchTarget.PaneRow>()
    for (tab in view.tabsOf(workspace.id)) {
        val panes = view.panesOf(tab.id)
        rows += SwitchTarget.TabRow(workspace, tab, panes.size, panes.mapNotNull { view.agentIn(it.id) }, view.tabTitle(tab.id))
        if (panes.size > 1) {
            for (pane in panes) {
                rows += SwitchTarget.PaneRow(workspace, tab, pane, view.agentIn(pane.id), view.paneTitle(pane.id))
            }
        }
        for (pane in panes) {
            val agent = view.agentIn(pane.id) ?: continue
            agentRows += SwitchTarget.PaneRow(workspace, tab, pane, agent, view.paneTitle(pane.id))
        }
    }
    WorkspaceGroup(workspace, rows, agentRows)
}

/** Every displayed row in topology order. */
fun List<WorkspaceGroup>.rows(): List<SwitchTarget> = flatMap { it.rows }

/**
 * The places [keys] name, each once and in order. A one-pane tab's agent has
 * no row of its own, so its pane key names the tab's row: a place
 * remembered under both keys appears once.
 */
fun List<WorkspaceGroup>.placesNamed(keys: List<String>): List<SwitchTarget> {
    val shown = flatMap { it.rows }.associateBy { it.key }
    val hidden = flatMap { it.agentRows }.associateBy { it.key }
    val tabRows = shown.values.filterIsInstance<SwitchTarget.TabRow>().associateBy { it.tab.id }
    return keys.mapNotNull { key -> shown[key] ?: hidden[key]?.let { tabRows[it.tab.id] } }.distinctBy { it.key }
}

/**
 * The groups narrowed to a filter and a query. A filter keeps the pane rows
 * whose agent has that status, and a tab row when any of its agents does: a
 * one-pane tab stands in through its single agent. Every word of the query
 * must appear somewhere in the row's context: workspace, branch, tab, pane
 * label, directory, agent name, kind, or title. Groups left empty are
 * dropped; order never changes.
 */
fun List<WorkspaceGroup>.narrowed(query: String, filter: SwitcherFilter): List<WorkspaceGroup> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    return mapNotNull { group ->
        val rows = group.rows.filter { row ->
            val matched = when (filter) {
                SwitcherFilter.All -> true
                SwitcherFilter.NeedsYou -> when (row) {
                    is SwitchTarget.PaneRow -> row.agent?.status == AgentStatus.Blocked
                    is SwitchTarget.TabRow -> row.agents.any { it.status == AgentStatus.Blocked }
                }
                SwitcherFilter.Working -> when (row) {
                    is SwitchTarget.PaneRow -> row.agent?.status == AgentStatus.Working
                    is SwitchTarget.TabRow -> row.agents.any { it.status == AgentStatus.Working }
                }
            }
            matched && (words.isEmpty() || haystack(row).let { text -> words.all { it in text } })
        }
        rows.takeIf { it.isNotEmpty() }?.let { WorkspaceGroup(group.workspace, it, group.agentRows) }
    }
}

private fun haystack(row: SwitchTarget): String {
    val parts = mutableListOf(row.workspace.label, row.workspace.branch, row.workspace.worktree, row.tab.label)
    when (row) {
        is SwitchTarget.PaneRow -> parts += listOf(
            row.title, row.pane.label, row.pane.cwd, row.agent?.name, row.agent?.agent, row.agent?.title,
        )
        is SwitchTarget.TabRow -> for (agent in row.agents) {
            parts += listOf(agent.name, agent.agent, agent.title)
        }
    }
    return parts.filterNotNull().joinToString("\n").lowercase()
}

/** Every agent in topology order that has [status], also in single-pane tabs. */
fun List<WorkspaceGroup>.agentsWith(status: AgentStatus): List<SwitchTarget.PaneRow> =
    flatMap { it.agentRows }.filter { it.agent?.status == status }

/**
 * The next agent asking for input after [current] in topology order,
 * wrapping around; [current] itself only when it is the only one.
 */
fun List<WorkspaceGroup>.nextNeedingInput(current: String?): SwitchTarget.PaneRow? {
    val all = flatMap { it.agentRows }
    val asking = all.filter { it.agent?.status == AgentStatus.Blocked }
    if (asking.isEmpty()) return null
    val at = all.indexOfFirst { it.pane.id == current }
    if (at < 0) return asking.first()
    return all.drop(at + 1).firstOrNull { it in asking } ?: asking.first()
}

/** How many recent targets the switcher remembers per node. */
const val RECENT_LIMIT = 5

/** [recent] after visiting [key]: it moves to the front, once. */
fun recentAfter(recent: List<String>, key: String): List<String> =
    (listOf(key) + recent.filter { it != key }).take(RECENT_LIMIT)
