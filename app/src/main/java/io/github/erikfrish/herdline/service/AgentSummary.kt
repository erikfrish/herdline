package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus

/**
 * Agent counts across every connected node, for a lock screen that shows
 * counts only. [waiting] is Herdr's Blocked. [connected] counts the nodes
 * whose live view was read; [unavailable] every other node, a retained view
 * of a lost node included, since it is a memory and not the present.
 */
internal data class AgentSummary(
    val working: Int,
    val waiting: Int,
    val idle: Int,
    val done: Int,
    val unknown: Int,
    val connected: Int,
    val unavailable: Int,
) {
    val available: Boolean get() = connected > 0
    val partial: Boolean get() = unavailable > 0
    val active: Boolean get() = working > 0 || waiting > 0
}

/**
 * One pass over [nodes]. An agent is counted once per daemon run and pane.
 * Core boot tokens include a final connection-local numeric link id, which
 * is excluded from this identity; classic daemon boot ids stay unchanged.
 * The first live view seen keeps its status.
 */
internal fun agentSummary(nodes: List<NodeSnapshot>): AgentSummary {
    var working = 0
    var waiting = 0
    var idle = 0
    var done = 0
    var unknown = 0
    var connected = 0
    var unavailable = 0
    val seen = HashSet<Pair<String, String>>()
    for (node in nodes) {
        val view = node.view
        if (!node.connected || view == null) {
            unavailable++
            continue
        }
        connected++
        val daemon = if (view.boot.startsWith("core-") && view.boot.substringAfterLast('-').all { it in '0'..'9' }) {
            view.boot.substringBeforeLast('-')
        } else view.boot
        for (agent in view.agents) {
            if (!seen.add(Pair(daemon, agent.pane))) continue
            when (agent.status) {
                AgentStatus.Working -> working++
                AgentStatus.Blocked -> waiting++
                AgentStatus.Idle -> idle++
                AgentStatus.Done -> done++
                AgentStatus.Unknown -> unknown++
            }
        }
    }
    return AgentSummary(working, waiting, idle, done, unknown, connected, unavailable)
}
