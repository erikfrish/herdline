package io.github.erikfrish.herdline.ui.nodes

import io.github.erikfrish.herdline.core.Connection.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStateTest {
    @Test
    fun failuresThatNeedDifferentActionsNeverShareAPill() {
        // Each asks the user for something else: re-sign in, check the key, trust it, fix the route, install, retry setup.
        val failures = listOf(
            State.AuthRejected(1, "h:22"),
            State.UntrustedHostKey(1, "h:22", "SHA256:x", changed = true),
            State.UntrustedHostKey(1, "h:22", "SHA256:x", changed = false),
            State.Failed("password left unanswered"),
            State.HerdrMissing,
            State.SetupFailed(1, ""),
        )
        val pills = failures.map { hostReason(it)?.text }
        assertFalse("every failure has a pill", pills.contains(null))
        assertEquals(pills.size, pills.toSet().size)
    }

    @Test
    fun anIncompatibleHostSaysWhichSideToUpdate() {
        val herdr = hostReason(State.Incompatible(daemonOutdated = true, serverVersion = "0.1", minVersion = "0.5"))
        val app = hostReason(State.Incompatible(daemonOutdated = false, serverVersion = "9.0", minVersion = "0.5"))
        assertNotEquals(herdr?.text, app?.text)
    }

    @Test
    fun anUnknownHostKeyIsAStepToTakeNotAFault() {
        val unknown = hostReason(State.UntrustedHostKey(1, "h:22", "SHA256:x", changed = false))
        val changed = hostReason(State.UntrustedHostKey(1, "h:22", "SHA256:x", changed = true))
        assertNotEquals(HostTone.Error, unknown?.tone)
        // A changed key may be an attack: that one stays a fault.
        assertEquals(HostTone.Error, changed?.tone)
    }

    @Test
    fun aDisconnectTheUserChoseIsNeitherALossNorAFault() {
        val chosen = hostReason(State.Disconnected("Disconnected", byUser = true))
        val lost = hostReason(State.Disconnected("Disconnected"))
        assertEquals(HostTone.Neutral, chosen?.tone)
        assertEquals(HostTone.Warning, lost?.tone)
        assertNotEquals(chosen?.text, lost?.text)
    }

    @Test
    fun onlyALinkThatFailedOrIsUpCarriesABadge() {
        assertFalse(hasBadge(hostReason(State.Disconnected("Disconnected", byUser = true))))
        // A red cross beside a "do this next" pill would contradict it.
        assertFalse(hasBadge(hostReason(State.UntrustedHostKey(1, "h:22", "SHA256:x", changed = false))))
        assertFalse(hasBadge(hostReason(State.HerdrMissing)))
        assertTrue(hasBadge(hostReason(State.Disconnected("connection reset"))))
        assertTrue(hasBadge(hostReason(State.AuthRejected(1, "h:22"))))
        assertTrue(hasBadge(hostReason(State.Connected("0.6.0"))))
    }

    @Test
    fun upOrOnTheWayNamesNoReason() {
        assertNull(hostReason(State.Connected("0.6.0")))
        assertNull(hostReason(State.Connecting))
        assertNull(hostReason(State.SettingUp))
    }

    @Test
    fun theFreeTextIsSpokenOnlyWhenItSaysMoreThanThePill() {
        assertEquals("connection refused", hostDetail(State.Disconnected(" connection refused ")))
        assertEquals("password left unanswered", hostDetail(State.Failed("password left unanswered")))
        // The unnamed placeholder after "Connection lost" would only be heard twice.
        assertNull(hostDetail(State.Disconnected(State.Disconnected.UNNAMED)))
        assertNull(hostDetail(State.Disconnected("connection refused", byUser = true)))
        assertNull(hostDetail(State.Failed("  ")))
        assertNull(hostDetail(State.AuthRejected(1, "h:22")))
    }
}
