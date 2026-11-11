/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.core

import org.json.JSONObject

/** Herdr's agent status, as the daemon reports it for every client alike. */
enum class AgentStatus {
    Idle, Working, Blocked, Done, Unknown;

    /** Rank for attention ordering: lower needs the user sooner. */
    val attention: Int get() = when (this) {
        Blocked -> 0
        Working -> 1
        Done -> 2
        Idle -> 3
        Unknown -> 4
    }

    companion object {
        fun parse(value: String) = when (value) {
            "idle" -> Idle
            "working" -> Working
            "blocked" -> Blocked
            "done" -> Done
            else -> Unknown
        }
    }
}

data class Workspace(
    val id: String,
    val number: Int,
    val label: String,
    val branch: String?,
    val ahead: Int?,
    val behind: Int?,
    val worktree: String?,
    val linkedWorktree: Boolean,
    val activeTab: String,
    val focused: Boolean,
    val status: AgentStatus,
)

data class Tab(
    val id: String,
    val workspace: String,
    val number: Int,
    val label: String,
    val zoomed: Boolean,
    val focused: Boolean,
    val status: AgentStatus,
)

data class Pane(
    val id: String,
    val workspace: String,
    val tab: String,
    val label: String?,
    val cwd: String?,
    val focused: Boolean,
)

data class Agent(
    val pane: String,
    val workspace: String,
    val tab: String,
    val name: String?,
    val agent: String?,
    val title: String?,
    val status: AgentStatus,
    val focused: Boolean,
    /**
     * Herdr's `state_change_seq` for this agent: a larger value is a later
     * state change. It is an order, not a time; null when the daemon sent none.
     */
    val stateSeq: Long? = null,
) {
    val displayName: String get() = name ?: agent ?: "agent"
}

/** The daemon's workspaces, tabs, panes, and agents (see `view.rs`). */
data class ShellView(
    val revision: Long,
    /** The daemon run; pane IDs are never reused within one. */
    val boot: String,
    val focusedWorkspace: String?,
    val focusedTab: String?,
    val focusedPane: String?,
    val updateAvailable: String?,
    val workspaces: List<Workspace>,
    val tabs: List<Tab>,
    val panes: List<Pane>,
    val agents: List<Agent>,
) {
    val workspace: Workspace? get() = workspaces.firstOrNull { it.id == focusedWorkspace }
    val tab: Tab? get() = tabs.firstOrNull { it.id == focusedTab }

    fun tabsOf(workspace: String) = tabs.filter { it.workspace == workspace }.sortedBy { it.number }

    fun panesOf(tab: String) = panes.filter { it.tab == tab }

    fun agentIn(pane: String) = agents.firstOrNull { it.pane == pane }

    /**
     * Agents by what needs the user first: asking, then working, then
     * finished, then idle. Within a status the daemon's order holds, so
     * rows move only when their status changes.
     */
    fun agentsByAttention(): List<Agent> = agents.sortedBy { it.status.attention }

    companion object {
        fun parse(json: String): ShellView {
            val o = JSONObject(json)
            return ShellView(
                revision = o.optLong("revision"),
                boot = o.optString("boot"),
                focusedWorkspace = o.str("focused_workspace"),
                focusedTab = o.str("focused_tab"),
                focusedPane = o.str("focused_pane"),
                updateAvailable = o.str("update_available"),
                workspaces = o.list("workspaces") {
                    Workspace(
                        id = getString("id"),
                        number = optInt("number"),
                        label = getString("label"),
                        branch = str("branch"),
                        ahead = if (isNull("ahead")) null else optInt("ahead"),
                        behind = if (isNull("behind")) null else optInt("behind"),
                        worktree = str("worktree"),
                        linkedWorktree = optBoolean("linked_worktree"),
                        activeTab = getString("active_tab"),
                        focused = optBoolean("focused"),
                        status = AgentStatus.parse(optString("status")),
                    )
                },
                tabs = o.list("tabs") {
                    Tab(
                        id = getString("id"),
                        workspace = getString("workspace"),
                        number = optInt("number"),
                        label = getString("label"),
                        zoomed = optBoolean("zoomed"),
                        focused = optBoolean("focused"),
                        status = AgentStatus.parse(optString("status")),
                    )
                },
                panes = o.list("panes") {
                    Pane(
                        id = getString("id"),
                        workspace = getString("workspace"),
                        tab = getString("tab"),
                        label = str("label"),
                        cwd = str("cwd"),
                        focused = optBoolean("focused"),
                    )
                },
                agents = o.list("agents") {
                    Agent(
                        pane = getString("pane"),
                        workspace = getString("workspace"),
                        tab = getString("tab"),
                        name = str("name"),
                        agent = str("agent"),
                        title = str("title"),
                        status = AgentStatus.parse(optString("status")),
                        stateSeq = if (has("state_seq") && !isNull("state_seq")) optLong("state_seq") else null,
                        focused = optBoolean("focused"),
                    )
                },
            )
        }

        private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

        private fun <T> JSONObject.list(key: String, item: JSONObject.() -> T): List<T> {
            val array = optJSONArray(key) ?: return emptyList()
            return List(array.length()) { array.getJSONObject(it).item() }
        }
    }
}
