package io.github.erikfrish.herdline.ui.nodes

import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.ui.nodes.NodeAction.Connect
import io.github.erikfrish.herdline.ui.nodes.NodeAction.Disconnect
import io.github.erikfrish.herdline.ui.nodes.NodeAction.Open
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NodeMenuTest {
    @Test
    fun aDetachedHostOffersConnectOnly() {
        assertEquals(listOf(Connect), nodeMenuActions(null))
    }

    @Test
    fun anAttachedHostOffersOpenAndDisconnect() {
        assertEquals(listOf(Open, Disconnect), nodeMenuActions(Connection.State.Connected("0.6")))
    }

    @Test
    fun aHostStillConnectingCanOnlyBeStopped() {
        assertEquals(listOf(Disconnect), nodeMenuActions(Connection.State.Connecting))
        assertEquals(listOf(Disconnect), nodeMenuActions(Connection.State.SettingUp))
    }

    @Test
    fun aFailedOrEndedLinkOffersConnectAgain() {
        listOf(
            Connection.State.Disconnected("network"),
            Connection.State.Failed("no password"),
            Connection.State.AuthRejected(0, "h:22"),
            Connection.State.CoreMissing,
        ).forEach { assertEquals(listOf(Connect), nodeMenuActions(it)) }
    }

    @Test
    fun connectAndDisconnectNeverShowTogether() {
        listOf(null, Connection.State.Connecting, Connection.State.Connected("0.6"), Connection.State.Failed("x")).forEach {
            val actions = nodeMenuActions(it)
            assertFalse(Connect in actions && Disconnect in actions)
        }
    }
}
