package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.RenderFrame
import kotlin.math.max
import kotlin.math.min

/**
 * Pane rows Herdr needs before Send is allowed. The native core enforces it
 * (`composer::MIN_SEND_ROWS`); this copy only decides what the too-short
 * notice may offer.
 */
internal const val MIN_SEND_ROWS = 12

/**
 * What portrait's session chrome takes beyond a landscape phone's one-row
 * header: the title row, the tab row, the pane strip, and a navigation bar
 * that moves from the side to the bottom (4 × 48 dp, plus 16). More than it
 * is, so portrait is never promised rows it lacks.
 */
internal const val PORTRAIT_CHROME_EXTRA_DP = 208

/**
 * The rows a pane of [paneRows] in a [gridRows]-row grid keeps when the grid
 * becomes [gridRowsAfter] rows: its share, rounded down. A split keeps its
 * ratio and its border stays one row, so this never promises more than
 * Herdr gives.
 */
internal fun paneRowsAfter(paneRows: Int, gridRows: Int, gridRowsAfter: Int): Int =
    if (paneRows <= 0 || gridRows <= 0 || gridRowsAfter <= 0) {
        0
    } else {
        (paneRows.toLong() * gridRowsAfter / gridRows).toInt().coerceAtMost(gridRowsAfter)
    }

/**
 * One orientation's session window, in pixels: [sharedPx] is what the
 * terminal and the composer (with its navigation-bar padding) share.
 */
internal data class SessionSpace(val windowWidthPx: Int, val windowHeightPx: Int, val sharedPx: Int)

/** Whether hiding the keyboard, or turning the phone to portrait, gives the pane [MIN_SEND_ROWS]. */
internal data class ShortPaneReach(val hideKeyboard: Boolean, val portrait: Boolean)

/**
 * The terminal's height, in px, whose rows the held grid keeps while the
 * side composer is up (S-4a): with the keyboard down the terminal is
 * stacked over the composer, whose height grows with the font scale, but
 * beside it the terminal has the whole shared column. That column is
 * [open]'s shared space less the composer's [navBarPx] padding. Null, so
 * the grid keeps the rows it had, unless [side] and [open] was measured in
 * this same window: a space from another window size promises rows it lacks.
 */
internal fun sideHoldPx(side: Boolean, open: SessionSpace?, windowWidthPx: Int, windowHeightPx: Int, navBarPx: Int): Int? =
    if (!side || open == null || open.windowWidthPx != windowWidthPx || open.windowHeightPx != windowHeightPx) {
        null
    } else {
        (open.sharedPx - navBarPx).takeIf { it > 0 }
    }

/**
 * What the too-short notice may offer (lab.7.3 J2, parent decision (e)):
 * hiding the keyboard or portrait only where the estimate from the current
 * geometry reaches [MIN_SEND_ROWS]. Zoom is not estimated: it is offered on
 * its own, and the pane's rows after it come from Herdr's next frame.
 *
 * [paneRows] is the target pane's height in the daemon's [gridRows]-row
 * grid, [cellPx] one terminal row. [open] is the space measured in this
 * orientation with the keyboard down, null until seen; [now] is the space
 * at this moment. [composerMaxPx] is the regular composer at its tallest
 * with its notice: hiding the keyboard keeps the draft, which may grow the
 * field to its last line. [portraitExtraPx] is [PORTRAIT_CHROME_EXTRA_DP].
 * [sideHeld] is a held grid from [sideHoldPx]: the rows it keeps already
 * outnumber the stacked layout's, so hiding the keyboard only loses rows.
 */
internal fun shortPaneReach(
    paneRows: Int?,
    gridRows: Int,
    cellPx: Int,
    landscape: Boolean,
    imeVisible: Boolean,
    open: SessionSpace?,
    now: SessionSpace,
    composerMaxPx: Int,
    portraitExtraPx: Int,
    sideHeld: Boolean,
): ShortPaneReach {
    if (paneRows == null || gridRows <= 0 || cellPx <= 0) return ShortPaneReach(hideKeyboard = false, portrait = false)
    fun reaches(sharedPx: Int) = paneRowsAfter(paneRows, gridRows, max(0, sharedPx - composerMaxPx) / cellPx) >= MIN_SEND_ROWS
    val hide = imeVisible && !sideHeld && open != null && reaches(open.sharedPx)
    // Without a measurement with the keyboard down, the space now is smaller still.
    val base = open ?: now
    val portrait = landscape && base.windowWidthPx > base.windowHeightPx &&
        reaches(base.sharedPx + base.windowWidthPx - base.windowHeightPx - portraitExtraPx)
    return ShortPaneReach(hide, portrait)
}

/**
 * Grid rows below which a frame with no focused pane rect is taken to leave
 * the pane no content row: a bordered pane's top and bottom border lines.
 * Only the fallback; [paneHasNoRoom] reads the rect when the frame has it.
 */
internal const val NO_ROOM_FALLBACK_ROWS = 3

/**
 * Whether the focused pane has no row of its own (QF-6c, QF9-N): the content
 * area Herdr gave it ([RenderFrame.PaneArea.rect], its inner rect) has no row
 * inside the frame, so only Herdr's chrome is drawn. That holds with the
 * keyboard up or down: the 2-row landscape grid, or an unzoomed split that
 * leaves the pane only its border (QA m26). A frame that names no focused
 * pane falls back to fewer than [NO_ROOM_FALLBACK_ROWS] grid rows. An open
 * popup takes the input, so it is never this case, and neither is typing in
 * the composer's own field ([composerTyping]).
 */
internal fun paneHasNoRoom(frame: RenderFrame?, composerTyping: Boolean): Boolean {
    if (composerTyping || frame == null || frame.rows <= 0 || frame.popup != null) return false
    val rect = frame.panes.firstOrNull { it.focused }?.rect ?: return frame.rows < NO_ROOM_FALLBACK_ROWS
    return min(frame.rows, rect.y + rect.height) - max(0, rect.y) <= 0
}
