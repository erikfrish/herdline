package io.github.erikfrish.herdline.ui.terminal

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The cap an unconfirmed delivery puts on the remote grid's height (S-4a,
 * HG-1). It is the pixel height of the rows in force when the user tapped
 * Send, Insert or Steer, taken by [tap] on the main thread before anything
 * the delivery changes (the field going read-only, the keyboard, a hold
 * ending) is laid out. Only [on] makes a cap apply. A tap is used only by
 * the freeze that follows it: at the same width and within [PENDING_MS],
 * else [on] takes the grid in force, so a tap the composer then refused
 * never caps a later freeze. A cap already on is kept by a later tap
 * (Steer's Enter), so a shrink during the delivery never becomes the cap.
 * A new width (rotation, split, configuration) drops the cap for the rest
 * of the freeze: the view there may be keyboard-short or held, and a cap
 * taken from it could not grow back.
 */
internal class GridFreeze {
    private var pendingPx = 0f
    private var pendingWidth = 0
    private var pendingAt = 0L
    private var capPx = 0f
    private var capWidth = 0
    private var active = false

    fun tap(rows: Int, cellHeight: Float, width: Int, nowMs: Long) {
        if (active || rows <= 0 || cellHeight <= 0f) return
        pendingPx = rows * cellHeight
        pendingWidth = width
        pendingAt = nowMs
    }

    /** A delivery went unconfirmed; with no tap just before (a restart), the grid in force. */
    fun on(rows: Int, cellHeight: Float, width: Int, nowMs: Long) {
        active = true
        val tapped = pendingPx > 0f && pendingWidth == width && nowMs - pendingAt in 0..PENDING_MS
        capPx = when {
            tapped -> pendingPx
            rows > 0 && cellHeight > 0f -> rows * cellHeight
            else -> 0f
        }
        capWidth = width
        pendingPx = 0f
    }

    fun off() {
        active = false
        capPx = 0f
        pendingPx = 0f
    }

    /** The height in pixels the grid may not grow past at [width], or 0 for none. */
    fun cap(width: Int): Float {
        if (!active || capPx <= 0f) return 0f
        if (width != capWidth) capPx = 0f
        return capPx
    }

    companion object {
        /** Longer than a tap takes to become an unconfirmed delivery: a few frames. */
        const val PENDING_MS = 2_000L
    }
}

/**
 * Whose layout Herdr's grid follows. Terminal on screen owns it: each grid
 * it lays out goes. Under Chat one goes per visit, once Chat's layout has
 * settled ([settled]: the mode and pane resolved, the keyboard down) and
 * the terminal's own font is in ([fonts]), so neither a grid Terminal or a
 * first layout gave nor one counted in the fallback font's cells sticks
 * under Chat. That grid takes the rows of the session's column under its
 * header ([roomPx]), the composer's height included, so the keyboard, a
 * notice or the composer there never resize the pane.
 */
internal class GridTurn {
    var terminal = true
        private set

    /** Under Chat: whether its layout has settled, as the screen says. */
    var settled = false

    /** Whether the terminal's own font is set, so its cells are the ones it draws. */
    var fonts = false

    /** The height of the session's column under its header, as last laid out; 0 before. */
    var roomPx = 0

    /** Under Chat: its one grid is still to go. */
    private var owed = false

    /** Terminal ([terminal]) or Chat came on screen; Chat coming owes its one grid. */
    fun show(terminal: Boolean) {
        if (this.terminal == terminal) return
        this.terminal = terminal
        owed = !terminal
    }

    /** Whether the grid laid out now goes to Herdr. */
    fun reports(): Boolean = terminal || chatDue()

    /** Whether Chat's one grid is due now, to report once its layout is done. */
    fun chatDue(): Boolean = !terminal && owed && settled && fonts && roomPx > 0

    /** The height whose rows the grid takes: the view's in Terminal, the column's under Chat. */
    fun heightPx(viewPx: Int): Int = if (terminal) viewPx else roomPx

    /** A grid went to Herdr. */
    fun reported() {
        if (!terminal) owed = false
    }
}

/**
 * The rows the grid reports: what the view fits, or under a hold the rows
 * of [holdHeightPx] or those the grid had ([lastRows]); when only transient
 * chrome moved the view ([chrome]) the rows the grid had; under [keep] never
 * fewer than [lastRows], though a taller view still grows them; under a
 * freeze never more than [capPx] holds at the current cell.
 */
internal fun gridRows(viewHeightPx: Int, cellHeight: Float, hold: Boolean, holdHeightPx: Int, lastRows: Int, capPx: Float, keep: Boolean = false, chrome: Boolean = false): Int {
    val view = max(1, floor(viewHeightPx / cellHeight).toInt())
    val fits = when {
        lastRows <= 0 -> view
        hold && holdHeightPx > 0 -> max(1, floor(holdHeightPx / cellHeight).toInt())
        hold -> lastRows
        chrome -> lastRows
        keep -> max(lastRows, view)
        else -> view
    }
    if (capPx <= 0f) return fits
    // rows * cell / cell can land a hair under rows in floats.
    return min(fits, max(1, floor(capPx / cellHeight + CAP_SLACK).toInt()))
}

/**
 * Whether [keepRows] may keep the rows of [last] (cols, rows, cell width,
 * cell height, as reported): only at its columns and cell size. A new width
 * or font is a real resize, so the grid then fits the view again.
 */
internal fun keepsRows(keepRows: Boolean, cols: Int, cellWidth: Int, cellHeight: Int, last: IntArray): Boolean =
    keepRows && last.size == 4 && last[0] == cols && last[2] == cellWidth && last[3] == cellHeight

private const val CAP_SLACK = 1e-3f
