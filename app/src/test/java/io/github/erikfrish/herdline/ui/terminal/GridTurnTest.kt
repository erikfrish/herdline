package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/** The grids Herdr is told across mode changes: Terminal's as it lays out, Chat's settled one once per visit. */
class GridTurnTest {
    private val turn = GridTurn()
    private val sent = mutableListOf<Int>()

    /** A layout of [rows]: reported when the turn lets it go, as reportGrid does. */
    private fun layout(rows: Int) {
        if (!turn.reports()) return
        sent += rows
        turn.reported()
    }

    @Test
    fun chatSendsOnlyItsSettledLayoutAndTerminalsGridNeverSticks() {
        // Cold launch onto Chat: the first layouts, before its pane resolves, go nowhere.
        turn.show(terminal = false)
        layout(23)
        turn.settled = true
        layout(24)
        // The keyboard and the composer moving in Chat never resize the pane.
        turn.settled = false
        layout(15)
        turn.settled = true
        layout(23)
        // Terminal follows its own layouts.
        turn.show(terminal = true)
        turn.settled = false
        layout(20)
        layout(12)
        // Back in Chat: its settled layout goes again, not Terminal's 12.
        turn.show(terminal = false)
        layout(21)
        turn.settled = true
        layout(24)
        layout(23)
        assertEquals(listOf(24, 20, 12, 24), sent)
    }
}
