package io.github.erikfrish.herdline.ui.terminal

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
    private val sent = mutableListOf<Int>()
    private var lastRows = 0

    /** A layout of a [viewPx] tall view at [cell] px cells, reported as reportGrid does. */
    private fun layout(viewPx: Int, cell: Float) {
        if (!turn.reports()) return
        lastRows = gridRows(turn.heightPx(viewPx), cell, hold = false, holdHeightPx = 0, lastRows = lastRows, capPx = 0f)
        sent += lastRows
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
        assertEquals(listOf(24), sent)
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
        assertEquals(listOf(20, 24, 20, 24), sent)
    }

    private companion object {
        const val ROOM = 2135
        const val TERMINAL_VIEW = 1798
        const val CHAT_VIEW = 1861
        const val FONT = 88f
        const val FALLBACK = 77f
    }
}
