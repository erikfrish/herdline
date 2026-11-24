package io.github.erikfrish.herdline.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBounceTest {
    @Test
    fun aTapOnAnotherItemBounces() {
        assertTrue(navBounces("inbox", "hosts"))
        assertTrue(navBounces("hosts", "inbox"))
        assertTrue(navBounces("settings", "inbox"))
        assertTrue(navBounces("inbox", "session-settings"))
    }

    @Test
    fun aReTapNeverBounces() {
        assertFalse(navBounces("hosts", "hosts"))
        assertFalse(navBounces("inbox", "inbox"))
        assertFalse(navBounces("settings", "settings"))
    }

    @Test
    fun settingsFromASessionIsTheSameItem() {
        assertFalse(navBounces("settings", "session-settings"))
    }
}
