package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.isChatAgent
import io.github.erikfrish.herdline.core.readableTitle

/**
 * CT-1: the pane this phone reads ("Reading here"), kept apart from Herdr's
 * shared focus ("On desktop"). Only a choice made on the phone sets it: a
 * tab, a pane, a switcher row, an Agents row, a banner, a notification, or a
 * tap in the terminal. A desktop focus change never moves it, and choosing it
 * in Chat never asks Herdr to focus, zoom or resize anything.
 *
 * It holds for one daemon run ([boot]): pane ids are only unique within one,
 * so a new run drops it. A new agent instance in the same pane keeps it; the
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
 * set), else its first supported Chat agent, else its first pane.
 */
fun paneForTab(view: ShellView, tab: String, lastRead: Map<String, String>): String? {
    val panes = view.panesOf(tab)
    return lastRead[tab]?.takeIf { pane -> panes.any { it.id == pane } }
        ?: panes.firstOrNull { it.focused }?.id
        ?: panes.firstOrNull { isChatAgent(view.agentIn(it.id)?.agent) }?.id
        ?: panes.firstOrNull()?.id
}

/** The pane a choice of [workspace] reads: its active tab's, as [paneForTab] picks. */
fun paneForWorkspace(view: ShellView, workspace: String, lastRead: Map<String, String>): String? =
    view.workspaces.firstOrNull { it.id == workspace }?.activeTab?.let { paneForTab(view, it, lastRead) }

/**
 * The desktop's pane, named in Chat only when it isn't the one read here
 * (S2's "Desktop: ‹tab›"); null when they match or either is unknown.
 */
fun desktopElsewhere(reading: String?, desktop: String?): String? =
    desktop?.takeIf { reading != null && it != reading }

/**
 * A tab as the header says it: its [title] as displayed ([tabTitle]), its
 * 1-based [number] of [count] in its workspace's tabs, that workspace's
 * [workspace] title as `session_place_spoken` says it, and the workspace's
 * 1-based place [workspaceNumber] of [workspaceCount] by `Workspace.number`.
 */
data class SpokenTab(
    val id: String,
    val title: String,
    val number: Int,
    val count: Int,
    val workspace: String,
    val workspaceNumber: Int,
    val workspaceCount: Int,
)

/** [tabId] as the header says it; a workspace without a readable label is [fallback], as the header's is. Null when not in view. */
fun ShellView.spokenTab(tabId: String, fallback: String): SpokenTab? {
    val tab = tabs.firstOrNull { it.id == tabId } ?: return null
    val siblings = tabsOf(tab.workspace)
    val workspace = workspaces.firstOrNull { it.id == tab.workspace }?.let { readableTitle(it.label) } ?: fallback
    val place = workspaces.sortedBy { it.number }.indexOfFirst { it.id == tab.workspace } + 1
    return SpokenTab(
        tab.id, tabTitle(tab.id), siblings.indexOfFirst { it.id == tab.id } + 1, siblings.size,
        workspace, place, workspaces.size,
    )
}

/** Which S2 sentence says where the desktop is (SN-1). */
sealed interface DesktopSpoken {
    /** No collision: the desktop's tab by its name. */
    data class Named(val desktop: String) : DesktopSpoken

    /** The desktop is on another pane of the tab read here. */
    data object SameTab : DesktopSpoken

    /** Another tab of the same workspace with the same name: its n of N. */
    data class Numbered(val desktop: String, val number: Int, val count: Int) : DesktopSpoken

    /** A tab of another workspace with the same name: that workspace. */
    data class InWorkspace(val desktop: String, val workspace: String) : DesktopSpoken

    /** As [InWorkspace], but that workspace is said as the one read here: its n of N. */
    data class InWorkspaceNumbered(val desktop: String, val number: Int, val count: Int) : DesktopSpoken
}

/**
 * SN-1: the desktop's tab is said by name unless that name is the one of
 * [readTab], the tab read here (exact, as displayed: a case difference is
 * text the user sees differ). Then it is told apart by where it is: the same
 * tab, its place in the same workspace, or the other workspace, by its
 * place there too when that workspace is said as the one read here.
 */
fun desktopSpoken(readTab: SpokenTab, desktopTab: SpokenTab, sameWorkspace: Boolean): DesktopSpoken = when {
    desktopTab.title != readTab.title -> DesktopSpoken.Named(desktopTab.title)
    desktopTab.id == readTab.id -> DesktopSpoken.SameTab
    sameWorkspace -> DesktopSpoken.Numbered(desktopTab.title, desktopTab.number, desktopTab.count)
    desktopTab.workspace == readTab.workspace ->
        DesktopSpoken.InWorkspaceNumbered(desktopTab.title, desktopTab.workspaceNumber, desktopTab.workspaceCount)
    else -> DesktopSpoken.InWorkspace(desktopTab.title, desktopTab.workspace)
}

/**
 * Whether a tab, pane or workspace chosen on this phone also moves Herdr's
 * shared focus. From Terminal ([chatShown] false) always: Terminal shows the
 * desktop's pane, so a choice there goes there even when the chosen pane
 * would read in Chat. From Chat only when the chosen pane shows Terminal
 * ([chosenShowsChat] false); a choice read in Chat never asks Herdr to focus.
 */
fun choiceMovesDesktop(chatShown: Boolean, chosenShowsChat: Boolean): Boolean = !chatShown || !chosenShowsChat
