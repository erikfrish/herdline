package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.CantCheckWhy
import io.github.erikfrish.herdline.core.Cell
import io.github.erikfrish.herdline.core.HarnessKey
import io.github.erikfrish.herdline.core.HarnessRuntime
import io.github.erikfrish.herdline.core.Reason
import io.github.erikfrish.herdline.core.ServerProbe
import io.github.erikfrish.herdline.core.Shown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessCardRowsTest {
    private val serverKeys = listOf(HarnessKey.Send, HarnessKey.Stop, HarnessKey.SteerQueue, HarnessKey.Questions)

    @Test
    fun aPaneWithoutItsServerSaysSoInTheCardsFirstRows() {
        // The list is capped and scrolls: the server's rows must not sit under the static claims.
        for ((program, named) in listOf("opencode" to "OpenCode", "kilo" to "Kilo")) {
            val rows = cardRows(program, live = true, HarnessRuntime(server = ServerProbe.Missing))
            assertEquals(program, serverKeys.map { it to Shown.Claim(Cell.NotYet(Reason.NeedsServer, named)) }, rows.take(4))
            assertTrue(program, rows.drop(4).none { (key, _) -> key in serverKeys })
        }
    }

    @Test
    fun aServerNotAskedYetLeadsAsCantCheckAndARunningOneKeepsItsPlace() {
        val unknown = cardRows("opencode", live = true, HarnessRuntime(server = ServerProbe.Unknown))
        assertEquals(serverKeys.map { it to Shown.CantCheck(CantCheckWhy.Server) }, unknown.take(4))
        // A running server: the static claims in key order, nothing lifted.
        val present = cardRows("opencode", live = true, HarnessRuntime(server = ServerProbe.Present))
        assertEquals(HarnessKey.entries.toList(), present.map { it.first })
        // Offline is no server answer: its rows stay with the other can't-checks.
        val offline = cardRows("opencode", live = false, HarnessRuntime(server = ServerProbe.Missing))
        assertEquals(HarnessKey.entries.toList(), offline.map { it.first })
    }
}
