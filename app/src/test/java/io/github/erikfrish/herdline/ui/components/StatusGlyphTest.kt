package io.github.erikfrish.herdline.ui.components

import io.github.erikfrish.herdline.core.AgentStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusGlyphTest {
    @Test
    fun everyShownStatusAndLinkHasItsOwnShape() {
        val shown = AgentStatus.entries.filter { it != AgentStatus.Unknown }.map { it.shape() }
        assertEquals("statuses without colour", shown.size, shown.toSet().size)
        val links = Link.entries.map { it.shape() }
        assertEquals("links without colour", links.size, links.toSet().size)
        // The connecting arc must not read as the lost ring when both stand still.
        assertTrue(Link.Trying.shape() != Link.Lost.shape())
        assertEquals(GlyphShape.None, AgentStatus.Unknown.shape())
    }

    @Test
    fun onlyARealChangeToBlockedOrDoneBumps() {
        assertTrue(bumps(AgentStatus.Working, AgentStatus.Blocked))
        assertTrue(bumps(AgentStatus.Working, AgentStatus.Done))
        // The first status a glyph shows is not news.
        assertFalse(bumps(null, AgentStatus.Blocked))
        assertFalse(bumps(AgentStatus.Blocked, AgentStatus.Blocked))
        assertFalse(bumps(AgentStatus.Blocked, AgentStatus.Working))
        assertFalse(bumps(AgentStatus.Done, AgentStatus.Idle))
    }
}
