package io.github.erikfrish.herdline.service

import org.junit.Assert.assertEquals
import org.junit.Test

/** When the notification words count as locked (lab.3 D10, lab.7.1 S1, S2). */
class LockGateTest {
    private fun locked(present: Boolean, interactive: Boolean, keyguard: Boolean, held: Boolean = false) =
        LockGate.lockedNow(held = held, present = present, interactive = interactive, keyguard = keyguard)

    @Test
    fun screenOffInsideALockDelayCountsAsLocked() {
        // "Lock after 30 s": the keyguard is not up yet when the screen goes off.
        assertEquals(true, locked(present = false, interactive = false, keyguard = false))
    }

    @Test
    fun wakingInsideTheLockDelayIsUnlocked() {
        assertEquals(false, locked(present = false, interactive = true, keyguard = false))
    }

    @Test
    fun wakingToTheKeyguardIsLocked() {
        assertEquals(true, locked(present = false, interactive = true, keyguard = true))
    }

    @Test
    fun theUnlockEdgeIsUnlockedWhileTheKeyguardAnimatesAway() {
        assertEquals(false, locked(present = true, interactive = true, keyguard = true))
    }

    @Test
    fun heldNotesStayLockedOnAnUnlockedScreen() {
        // No connection service: the process may die and nothing would re-post the note.
        assertEquals(true, locked(present = true, interactive = true, keyguard = false, held = true))
    }
}
