package io.github.erikfrish.herdline.ui.terminal

import androidx.compose.ui.unit.IntSize
import kotlin.math.floor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The grids Herdr is told across mode changes, over the layouts a
 * 1080x2400 phone gives: the column under the header is 2135 px; Terminal's
 * view above the keys row 1798 px; Chat's view above its composer 1861 px.
 * The terminal's font has 88 px cells, the fallback font drawn before it
 * loads 77 px.
 */
class GridTurnTest {
    private val turn = GridTurn()
    private val sent = mutableListOf<Pair<Int, Int>>()
    private var lastRows = 0

    /** A layout of a [viewPx] tall view at [cell] px cells, reported as reportGrid does. */
    private fun layout(viewPx: Int, cell: Float, viewWidth: Int = 1080, hold: Boolean = false, capPx: Float = 0f) {
        if (!turn.reports()) return
        val cols = floor(turn.widthPx(viewWidth) / 20f).toInt()
        lastRows = turn.rows(viewPx, cell, hold, holdHeightPx = ROOM, lastRows = lastRows, capPx = capPx)
        sent += cols to lastRows
        turn.reported()
    }

    @Test
    fun coldLaunchOntoChatSendsOneGridOfTheColumnInTheTerminalsFont() {
        // The session's view is not in yet: the mode is unknown and nothing goes, whatever is laid out.
        turn.show(terminal = false)
        turn.roomPx = 2040
        layout(1798, FALLBACK)
        layout(1861, FALLBACK)
        // The view is in and Chat settles while the font still loads: still nothing.
        turn.roomPx = ROOM
        turn.settled = true
        layout(CHAT_VIEW, FALLBACK)
        turn.fonts = true
        layout(CHAT_VIEW, FONT)
        // Later Chat layouts, the keyboard's included, never resize the pane.
        turn.settled = false
        layout(900, FONT)
        turn.settled = true
        layout(CHAT_VIEW, FONT)
        assertEquals(listOf(54 to 24), sent)
    }

    @Test
    fun terminalThenChatSendsTerminalsGridThenChatsColumn() {
        turn.fonts = true
        turn.roomPx = ROOM
        layout(TERMINAL_VIEW, FONT)
        turn.show(terminal = false)
        turn.settled = true
        layout(CHAT_VIEW, FONT)
        layout(CHAT_VIEW, FONT)
        // And again: Terminal's 20 never sticks under Chat.
        turn.show(terminal = true)
        turn.settled = false
        layout(TERMINAL_VIEW, FONT)
        turn.show(terminal = false)
        turn.settled = true
        layout(CHAT_VIEW, FONT)
        assertEquals(listOf(54 to 20, 54 to 24, 54 to 20, 54 to 24), sent)
    }

    @Test
    fun explicitFitFollowsMeasuredHistoryUntilReleased() {
        // A 2082 px room includes the composer. The hidden canvas is not the history, either.
        turn.fonts = true
        turn.roomPx = 2082
        turn.show(terminal = false)
        turn.settled = true
        layout(1745, 49.5f) // The pre-existing passive Chat report.

        turn.fitViewport = IntSize.Zero
        layout(1745, 49.5f) // A tap before the history has visible bounds must not report the room.
        turn.fitViewport = IntSize(1080, 1703)
        layout(1745, 49.5f)
        turn.settled = false // IME shown; ordinary Chat would not report and the composer would hold 34 rows.
        turn.fitViewport = IntSize(1080, 883)
        layout(930, 49.5f, hold = true)
        // The width can also differ from the canvas (e.g. a side system inset).
        turn.fitViewport = IntSize(1040, 883)
        layout(930, 49.5f, hold = true)
        turn.fitViewport = null
        turn.settled = true
        layout(1745, 49.5f) // Passive Chat must not start tracking layout after release.
        turn.show(terminal = true)
        layout(1745, 49.5f)
        assertEquals(listOf(54 to 42, 54 to 34, 54 to 17, 52 to 17, 54 to 35), sent)
    }

    @Test
    fun fittedChatStillHonorsTheDeliveryCap() {
        turn.fonts = true
        turn.show(terminal = false)
        turn.fitViewport = IntSize(1080, 1703)
        // Closing IME during an unconfirmed delivery must not grow beyond its pre-send height.
        layout(1745, 49.5f, capPx = 17 * 49.5f)
        assertEquals(listOf(54 to 17), sent)
    }

    private companion object {
        const val ROOM = 2135
        const val TERMINAL_VIEW = 1798
        const val CHAT_VIEW = 1861
        const val FONT = 88f
        const val FALLBACK = 77f
    }
}
