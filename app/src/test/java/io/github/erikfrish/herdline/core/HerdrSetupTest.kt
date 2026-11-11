/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrSetupTest {
    @Test
    fun aZeroExitStatusSucceeded() {
        val outcome = HerdrSetup.parse("""{"exit_status":0,"output":"Updating /home/me/.local/bin/herdr"}""")
        assertEquals(HerdrSetup(0, "Updating /home/me/.local/bin/herdr"), outcome)
        assertTrue(outcome.succeeded)
    }

    @Test
    fun aMissingExitStatusIsAFailure() {
        val outcome = HerdrSetup.parse("""{"exit_status":null,"output":""}""")
        assertEquals(HerdrSetup(null, ""), outcome)
        assertFalse(outcome.succeeded)
    }

    @Test
    fun aNonZeroExitStatusIsAFailure() {
        assertFalse(HerdrSetup.parse("""{"exit_status":1,"output":"download failed"}""").succeeded)
    }
}
