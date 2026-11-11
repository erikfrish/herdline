package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ShellView

/**
 * CT-1: the pane this phone reads ("Reading here"), kept apart from Herdr's
 * shared focus ("On desktop"). Only a choice made on the phone sets it: a
 * tab, a pane, a switcher row, an Agents row, a banner, a notification, or a
 * tap in the terminal. A desktop focus change never moves it, and choosing it
 * in Chat never asks Herdr to focus, zoom or resize anything.
 *
 * It holds for one daemon run ([boot]): pane ids are only unique within one,
 * so a new run drops it. A new OMP instance in the same pane keeps it; the
 * transcript's own identity resets what Chat shows (ChatHistory.merge).
 */
data class ReadingTarget(val pane: String, val boot: String)

/**
 * The reading target once the view says [boot]: the [current] one within the
 * same daemon run, even if its pane has closed (Chat says so, S5, and never
 * falls back to the desktop by itself). Otherwise, the first time, [opened]
 * (a notification, deep link or Agents row) or else [desktop], read once and
 * not followed. Before any view, nothing changes.
 */
fun readingFor(current: ReadingTarget?, boot: String?, opened: String?, desktop: String?): ReadingTarget? = when {
    boot == null -> current
    current != null && current.boot == boot -> current
    else -> (opened ?: desktop)?.let { ReadingTarget(it, boot) }
}

/** True when the [target]'s pane is no longer in [view] (S5). Unknown without a view of the same run. */
fun readingClosed(target: ReadingTarget?, view: ShellView?): Boolean =
    target != null && view != null && view.boot == target.boot && view.panes.none { it.id == target.pane }

/**
 * The pane a choice of [tab] reads: the one last read there on this phone
 * ([lastRead], by tab), else the one Herdr reports focused in it (read, not
 * set), else its first OMP pane, else its first pane.
 */
fun paneForTab(view: ShellView, tab: String, lastRead: Map<String, String>): String? {
    val panes = view.panesOf(tab)
    return lastRead[tab]?.takeIf { pane -> panes.any { it.id == pane } }
        ?: panes.firstOrNull { it.focused }?.id
        ?: panes.firstOrNull { view.agentIn(it.id)?.agent == "omp" }?.id
        ?: panes.firstOrNull()?.id
}

/** The pane a choice of [workspace] reads: its active tab's, as [paneForTab] picks. */
fun paneForWorkspace(view: ShellView, workspace: String, lastRead: Map<String, String>): String? =
    view.workspaces.firstOrNull { it.id == workspace }?.activeTab?.let { paneForTab(view, it, lastRead) }

/**
 * The desktop's pane, named in Chat only when it isn't the one read here
 * (S2's "Desktop: ‹tab›", S3's notice); null when they match or either is unknown.
 */
fun desktopElsewhere(reading: String?, desktop: String?): String? =
    desktop?.takeIf { reading != null && it != reading }

/**
 * Whether a tab, pane or workspace chosen on this phone also moves Herdr's
 * shared focus. From Terminal ([chatShown] false) always: Terminal shows the
 * desktop's pane, so a choice there goes there even when the chosen pane
 * would read in Chat. From Chat only when the chosen pane shows Terminal
 * ([chosenShowsChat] false); a choice read in Chat never asks Herdr to focus.
 */
fun choiceMovesDesktop(chatShown: Boolean, chosenShowsChat: Boolean): Boolean = !chatShown || !chosenShowsChat
