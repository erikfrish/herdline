/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.core

import io.github.erikfrish.herdline.data.AuthMethod
import io.github.erikfrish.herdline.data.Node
import org.junit.Assert.assertEquals
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
}
