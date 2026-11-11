package io.github.erikfrish.herdline.ui.inbox

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.ui.session.SwitcherFilter
import io.github.erikfrish.herdline.ui.session.paneTitle
import io.github.erikfrish.herdline.ui.session.paneWhere

/** One workspace on one attached host: what the Agents workspace filter selects. */
data class ScopeKey(val nodeId: String, val session: String, val workspaceId: String)

/** An attached host as Agents sees it, in `app.connections` order. */
data class HostSnapshot(val node: Node, val view: ShellView?)

/** The Agents rows: one agent per pane, across every attached host. */
data class InboxRow(
    val nodeId: String,
    /** The host's name, qualified as its menu header is ([hostLabels]). */
    val host: String,
    val paneId: String,
    val title: String,
    val where: String,
    val status: AgentStatus,
    val scope: ScopeKey,
)

/** A workspace offered in the filter menu. */
data class ScopeItem(
    val key: ScopeKey,
    val label: String,
    val number: Int,
    val status: AgentStatus,
    val agents: Int,
    /** The host to name beside [label] once chosen, or null when [label] alone is unambiguous. */
    val onHost: String?,
)

/** One host's header and its workspaces in the filter menu. */
data class ScopeGroup(val nodeId: String, val header: String, val items: List<ScopeItem>)

/** How a saved scope stands against the attached hosts. */
sealed interface ScopeState {
    /** Nothing chosen, or the choice is gone: the screen shows All workspaces. */
    data object All : ScopeState

    /** The host is attached but its first view has not arrived: show All, keep the choice. */
    data object Pending : ScopeState

    data class Chosen(val item: ScopeItem) : ScopeState
}

/**
 * What tells each attached host apart from another with its title:
 * `user@address` (`:port` off 22) and ` · session` off `default`, and
 * ` · 2`, ` · 3`… for further saved copies of the same server; null for a
 * host whose title no other attached host has.
 */
private fun hostQualifiers(nodes: List<Node>): Map<String, String?> {
    val shared = nodes.groupingBy { it.title }.eachCount()
    val seen = mutableMapOf<Pair<String, String?>, Int>()
    return nodes.associate { node ->
        val qualifier = if ((shared[node.title] ?: 0) < 2) {
            null
        } else {
            listOfNotNull(node.destination, node.session.takeIf { it != "default" }).joinToString(" · ")
        }
        val copy = seen.merge(node.title to qualifier, 1, Int::plus) ?: 1
        node.id to if (copy > 1) listOfNotNull(qualifier, "$copy").joinToString(" · ") else qualifier
    }
}

/**
 * The menu header of each attached host: its title, and when another attached
 * host has the same title, ` · ` and what tells them apart ([hostQualifiers]),
 * so two hosts called `lab` read apart.
 */
fun hostLabels(nodes: List<Node>): Map<String, String> {
    val qualifiers = hostQualifiers(nodes)
    return nodes.associate { node -> node.id to listOfNotNull(node.title, qualifiers[node.id]).joinToString(" · ") }
}

/** The workspace an agent's pane is in: Herdr's own id, never a display string. */
private fun ShellView.workspaceOf(paneId: String, reported: String): String? =
    reported.ifEmpty { null }
        ?: panes.firstOrNull { it.id == paneId }?.let { pane ->
            pane.workspace.ifEmpty { null } ?: tabs.firstOrNull { it.id == pane.tab }?.workspace
        }

/** Every agent on every attached host, in host order and Herdr's order within a host. */
fun agentRows(hosts: List<HostSnapshot>): List<InboxRow> {
    val labels = hostLabels(hosts.map { it.node })
    return hosts.flatMap { host ->
        val view = host.view ?: return@flatMap emptyList()
        view.agents.mapNotNull { agent ->
            val workspace = view.workspaceOf(agent.pane, agent.workspace) ?: return@mapNotNull null
            InboxRow(
                nodeId = host.node.id,
                host = labels.getValue(host.node.id),
                paneId = agent.pane,
                title = view.paneTitle(agent.pane),
                where = view.paneWhere(agent.pane),
                status = agent.status,
                scope = ScopeKey(host.node.id, host.node.session, workspace),
            )
        }
    }
}

/**
 * The filter menu: one group per host with a view, holding the workspaces
 * that have agents. [keep] is listed even when its last agent has gone, so a
 * chosen workspace stays visible while it exists.
 */
fun scopeGroups(hosts: List<HostSnapshot>, rows: List<InboxRow>, keep: ScopeKey? = null): List<ScopeGroup> {
    val labels = hostLabels(hosts.map { it.node })
    val counts = rows.groupingBy { it.scope }.eachCount()
    val groups = hosts.mapNotNull { host ->
        val view = host.view ?: return@mapNotNull null
        val items = view.workspaces.sortedBy { it.number }.mapNotNull { workspace ->
            val key = ScopeKey(host.node.id, host.node.session, workspace.id)
            val agents = counts[key] ?: 0
            if (agents == 0 && key != keep) return@mapNotNull null
            ScopeItem(key, workspace.label, workspace.number, workspace.status, agents, onHost = null)
        }
        ScopeGroup(host.node.id, labels.getValue(host.node.id), items).takeIf { items.isNotEmpty() }
    }
    // A label needs its host once chosen when another host lists it too, or
    // its host is qualified; then as `lab (erik@host)`, so the host's own
    // ` · ` doesn't read as one more field after the workspace.
    val qualifiers = hostQualifiers(hosts.map { it.node })
    val hostsPerLabel = groups.flatMap { g -> g.items.map { it.label to g.nodeId } }.distinct().groupingBy { it.first }.eachCount()
    return groups.map { group ->
        val node = hosts.first { it.node.id == group.nodeId }.node
        val onHost = qualifiers[node.id]?.let { "${node.title} ($it)" }
        group.copy(
            items = group.items.map { item ->
                item.copy(onHost = onHost ?: node.title.takeIf { (hostsPerLabel[item.label] ?: 0) > 1 })
            },
        )
    }
}

/**
 * Where a saved [scope] stands: chosen while its host is attached and its
 * workspace is in that host's view; pending while the host's first view is
 * still on its way; otherwise All, and the caller drops the choice.
 */
fun scopeState(scope: ScopeKey?, hosts: List<HostSnapshot>, groups: List<ScopeGroup>): ScopeState {
    scope ?: return ScopeState.All
    val host = hosts.firstOrNull { it.node.id == scope.nodeId && it.node.session == scope.session } ?: return ScopeState.All
    if (host.view == null) return ScopeState.Pending
    val item = groups.firstOrNull { it.nodeId == scope.nodeId }?.items?.firstOrNull { it.key == scope }
    return item?.let { ScopeState.Chosen(it) } ?: ScopeState.All
}

/** The rows inside [state]'s workspace; All and Pending keep every row. */
fun List<InboxRow>.inScope(state: ScopeState): List<InboxRow> = when (state) {
    is ScopeState.Chosen -> filter { it.scope == state.item.key }
    ScopeState.All, ScopeState.Pending -> this
}

/** The rows a status chip keeps. Ready is every idle or done agent. */
fun List<InboxRow>.withStatus(status: SwitcherFilter): List<InboxRow> = when (status) {
    SwitcherFilter.All -> this
    SwitcherFilter.NeedsYou -> filter { it.status == AgentStatus.Blocked }
    SwitcherFilter.Working -> filter { it.status == AgentStatus.Working }
    SwitcherFilter.Ready -> filter { it.status == AgentStatus.Idle || it.status == AgentStatus.Done }
}

/** Agents that need the user: over every row, this is the destination's badge. */
fun List<InboxRow>.needsYou(): Int = count { it.status == AgentStatus.Blocked }

/** Agents at work. */
fun List<InboxRow>.working(): Int = count { it.status == AgentStatus.Working }

/** Agents that are idle or done. */
fun List<InboxRow>.ready(): Int = count { it.status == AgentStatus.Idle || it.status == AgentStatus.Done }
