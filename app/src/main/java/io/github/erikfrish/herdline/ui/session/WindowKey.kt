package io.github.erikfrish.herdline.ui.session

/**
 * Whether the pane bar is under the tab row: not while typing in a short
 * window, only with a view and a tab ([tabPanes], that tab's pane count, null
 * without them), and then for a split tab on a phone or any tab when [wide],
 * where it is also the pane toolbar.
 */
internal fun paneBarShown(typingShort: Boolean, tabPanes: Int?, wide: Boolean): Boolean =
    !typingShort && tabPanes != null && (tabPanes > 1 || wide)

/**
 * What TerminalView takes as its window (its `windowKey`): a new key refits
 * the grid to the view, while any other height change is transient chrome and
 * keeps the grid's rows. The pane bar is in it (OBS-Z1): it stays while the
 * tab is split, and kept rows under it would hide Herdr's top row. Nothing
 * here depends on the grid, so the refit it asks for cannot change it.
 */
internal fun sessionWindowKey(windowPx: Pair<Int, Int>, imeBottomPx: Int, orientation: Int, paneBar: Boolean): List<Any> =
    listOf(windowPx, imeBottomPx, orientation, paneBar)
