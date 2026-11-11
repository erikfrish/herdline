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

    @Test
    fun onlyAConnectionCompletingClosesTheArc() {
        assertTrue(closes(Link.Trying, Link.Up))
        // A session opened already connected shows the disc still.
        assertFalse(closes(null, Link.Up))
        assertFalse(closes(Link.Up, Link.Up))
        assertFalse(closes(Link.Lost, Link.Up))
        assertFalse(closes(Link.Trying, Link.Lost))
    }

    @Test
    fun theCloseStartsAsTheArcAndEndsAsTheDisc() {
        assertEquals(270f, closeSweep(0f), 0f)
        assertEquals(0f, closeFill(0f), 0f)
        // The ring is shut before it fills: the arc never reads as filled while open.
        assertEquals(360f, closeSweep(0.6f), 0.001f)
        assertEquals(0f, closeFill(0.6f), 0.001f)
        assertEquals(360f, closeSweep(1f), 0f)
        assertEquals(1f, closeFill(1f), 0f)
    }
}
