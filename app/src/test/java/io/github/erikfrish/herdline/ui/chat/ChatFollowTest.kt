package io.github.erikfrish.herdline.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When chat counts as at the bottom of its list (D1: a band, not 0 px). */
class ChatFollowTest {
    private val band = 63 // 24 dp at 2.625x

    @Test
    fun theLastItemEndingWithinTheBandIsTheBottom() {
        // Content ends at 1000 px: exactly there, a fling short of it, and at the band's edge.
        assertTrue(atListEnd(lastVisibleIndex = 9, lastVisibleEnd = 1000, totalItems = 10, contentEnd = 1000, band = band))
        assertTrue(atListEnd(9, 1001, 10, 1000, band))
        assertTrue(atListEnd(9, 1000 + band, 10, 1000, band))
        assertFalse(atListEnd(9, 1001 + band, 10, 1000, band))
    }

    @Test
    fun theLastItemOffScreenIsNotTheBottomAndAnEmptyListIs() {
        assertFalse(atListEnd(lastVisibleIndex = 8, lastVisibleEnd = 900, totalItems = 10, contentEnd = 1000, band = band))
        assertTrue(atListEnd(-1, 0, 0, 1000, band))
    }

    @Test
    fun aLayoutChangeThatMovesTheEndAwayRePinsOnlyAFollowingList() {
        // J2b: after a rotation or a recreated screen the end slid below the viewport.
        assertEquals(FollowStep(follow = true, pin = true), followStep(follow = true, scrolling = false, dragged = false, atBand = false, atEnd = false))
        // Within the band but not at the end: still pinned to the very end.
        assertEquals(FollowStep(follow = true, pin = true), followStep(true, false, false, atBand = true, atEnd = false))
        assertEquals(FollowStep(follow = true, pin = false), followStep(true, false, false, atBand = true, atEnd = true))
        // A reader who left the bottom is never moved, and nothing moves mid-scroll.
        assertEquals(FollowStep(follow = false, pin = false), followStep(false, false, false, atBand = false, atEnd = false))
        assertEquals(FollowStep(follow = true, pin = false), followStep(true, scrolling = true, dragged = false, atBand = false, atEnd = false))
    }

    @Test
    fun onlyASettledDragDecidesWhetherToFollowAndItNeverPins() {
        assertEquals(FollowStep(follow = false, pin = false), followStep(follow = true, scrolling = false, dragged = true, atBand = false, atEnd = false))
        assertEquals(FollowStep(follow = true, pin = false), followStep(follow = false, scrolling = false, dragged = true, atBand = true, atEnd = false))
    }
}
