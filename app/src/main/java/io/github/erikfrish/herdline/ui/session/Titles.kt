package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.readableTitle

/*
 * Readable names for Herdr targets. An agent kind such as "omp" says what
 * runs, not which task it is, so it never becomes a title; pane IDs are
 * internal and never shown as one either. Every terminal-supplied part goes
 * through readableTitle, the one sanitizer, before it is shown.
 */

/** A label the user gave the tab, as opposed to Herdr's default number. */
private val Tab.userLabel: String?
    get() = readableTitle(label)?.takeIf { it != number.toString() }

/**
 * The pane's title: the agent's task title, then the tab's own label when the
 * pane is the tab's only one, then the pane label, the working directory's
 * last segment, and finally the tab's number.
 */
fun ShellView.paneTitle(paneId: String): String {
    val pane = panes.firstOrNull { it.id == paneId }
    val tab = pane?.let { p -> tabs.firstOrNull { it.id == p.tab } }
    val agent = agentIn(paneId)
    return agent?.let { readableTitle(it.title, it.agent) }
        ?: tab?.takeIf { panesOf(it.id).size == 1 }?.userLabel
        ?: readableTitle(pane?.label, agent?.agent)
        ?: pane?.cwd?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        ?: tab?.let { "Tab ${it.number}" }
        ?: "Pane"
}

/**
 * The tab's title: the name the user gave it in Herdr, which stays put while
 * agents retitle and tells apart tabs running the same task (lab.3 D1:
 * "alice" and "alice2" both read "work"); else a one-pane tab is named by its
 * pane, and a split tab by the pane it focuses.
 */
fun ShellView.tabTitle(tabId: String): String {
    val tab = tabs.firstOrNull { it.id == tabId } ?: return "Tab"
    tab.userLabel?.let { return it }
    val panes = panesOf(tabId)
    val lead = panes.firstOrNull { it.focused } ?: panes.firstOrNull()
    return lead?.let { paneTitle(it.id) } ?: "Tab ${tab.number}"
}

/** Where a pane sits, "workspace › tab", to tell apart panes with equal titles. */
fun ShellView.paneWhere(paneId: String): String {
    val pane = panes.firstOrNull { it.id == paneId } ?: return ""
    val workspace = workspaces.firstOrNull { it.id == pane.workspace }?.label
    val tab = tabs.firstOrNull { it.id == pane.tab }?.let { it.userLabel ?: it.number.toString() }
    return listOfNotNull(workspace, tab).joinToString(" › ")
}

/** Whether another pane in the session carries the same title as [paneId]. */
fun ShellView.titleCollides(paneId: String): Boolean {
    val title = paneTitle(paneId)
    return panes.any { it.id != paneId && paneTitle(it.id) == title }
}

/**
 * A notification's text for [paneId]: "workspace › tab", then the pane's own
 * title when it says more than the tab's name. Null for a pane not in view.
 */
fun notificationBody(view: ShellView, paneId: String): String? {
    val where = view.paneWhere(paneId).ifEmpty { return null }
    val title = view.paneTitle(paneId)
    return if (where.endsWith(title)) where else "$where · $title"
}
