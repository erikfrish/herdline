/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.core

import io.github.erikfrish.herdline.data.AuthMethod
import io.github.erikfrish.herdline.data.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutesTest {
    private val jump = Node(id = "jump", name = "", address = "j", user = "u", auth = AuthMethod.Password)
    private val keyed = Node(id = "keyed", name = "", address = "k", user = "u", jumpId = "jump")
    private val target = Node(id = "target", name = "", address = "t", user = "u", auth = AuthMethod.Password, jumpId = "keyed")
    private val route = listOf(jump, keyed, target)

    @Test
    fun asksForEveryPasswordHopWhenNoneIsSaved() {
        assertEquals(listOf(jump, target), Routes.unsavedPasswordHops(route) { false })
    }

    @Test
    fun skipsHopsWithSavedPasswords() {
        assertEquals(listOf(target), Routes.unsavedPasswordHops(route) { it.id == "jump" })
        assertEquals(emptyList<Node>(), Routes.unsavedPasswordHops(route) { true })
    }

    @Test
    fun keyHopsAreNeverAskedFor() {
        assertEquals(emptyList<Node>(), Routes.unsavedPasswordHops(listOf(keyed)) { false })
    }

    @Test
    fun onlyTheImplicitDeviceKeyFallsBackToAPassword() {
        val deviceKeyed = Node(id = "dk", name = "", address = "d", user = "u", auth = AuthMethod.Key, identityId = null)
        val explicitKeyed = Node(id = "ek", name = "", address = "e", user = "u", auth = AuthMethod.Key, identityId = "identity")
        assertTrue(Routes.usesDeviceKey(deviceKeyed))
        assertTrue(Routes.fallbackEligible(deviceKeyed))
        assertFalse(Routes.usesDeviceKey(explicitKeyed))
        assertFalse(Routes.fallbackEligible(explicitKeyed))
        assertFalse(Routes.usesDeviceKey(jump))
        assertFalse(Routes.fallbackEligible(jump))
    }

    @Test
    fun typedPasswordsWinOverSavedOnes() {
        val saved: (String) -> String? = { mapOf("jump" to "saved")[it] }
        assertEquals("typed", Routes.usePassword("jump", mapOf("jump" to "typed"), saved))
        assertEquals("saved", Routes.usePassword("jump", emptyMap(), saved))
        assertEquals("", Routes.usePassword("target", emptyMap(), saved))
    }
}
