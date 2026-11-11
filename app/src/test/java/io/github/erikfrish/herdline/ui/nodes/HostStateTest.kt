package io.github.erikfrish.herdline.ui.nodes

import io.github.erikfrish.herdline.core.Connection.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HostStateTest {
    @Test
    fun eachFailureIsNamedFromItsType() {
        assertEquals(HostReason.Rejected, hostReason(State.AuthRejected(1, "h:22")))
        assertEquals(HostReason.KeyChanged, hostReason(State.UntrustedHostKey(1, "h:22", "SHA256:x", changed = true)))
        assertEquals(HostReason.KeyUnknown, hostReason(State.UntrustedHostKey(1, "h:22", "SHA256:x", changed = false)))
        assertEquals(HostReason.CannotConnect, hostReason(State.Failed("password left unanswered")))
        assertEquals(HostReason.NoHerdr, hostReason(State.HerdrMissing))
        assertEquals(HostReason.SetupFailed, hostReason(State.SetupFailed(1, "")))
        assertEquals(HostReason.UpdateHerdr, hostReason(State.Incompatible(daemonOutdated = true, serverVersion = "0.1", minVersion = "0.5")))
        assertEquals(HostReason.UpdateApp, hostReason(State.Incompatible(daemonOutdated = false, serverVersion = "9.0", minVersion = "0.5")))
    }

    @Test
    fun aDisconnectTheUserChoseIsNeitherALossNorAFault() {
        assertEquals(HostReason.Closed, hostReason(State.Disconnected("Disconnected", byUser = true)))
        assertEquals(HostTone.Neutral, HostReason.Closed.tone)
        assertEquals(HostReason.Lost, hostReason(State.Disconnected("Disconnected")))
        assertEquals(HostTone.Warning, HostReason.Lost.tone)
    }

    @Test
    fun onlyRealFaultsAreErrors() {
        val errors = HostReason.entries.filter { it.tone == HostTone.Error }.toSet()
        assertEquals(setOf(HostReason.Rejected, HostReason.KeyChanged, HostReason.SetupFailed, HostReason.CannotConnect), errors)
    }

    @Test
    fun upOrOnTheWayNamesNoReason() {
        assertNull(hostReason(State.Connected("0.6.0")))
        assertNull(hostReason(State.Connecting))
        assertNull(hostReason(State.SettingUp))
    }

    @Test
    fun theFreeTextIsSpokenOnlyForAFailureOrALoss() {
        assertEquals("connection refused", hostDetail(State.Disconnected(" connection refused ")))
        assertEquals("password left unanswered", hostDetail(State.Failed("password left unanswered")))
        assertNull(hostDetail(State.Disconnected("Disconnected", byUser = true)))
        assertNull(hostDetail(State.Failed("  ")))
        assertNull(hostDetail(State.AuthRejected(1, "h:22")))
    }
}
