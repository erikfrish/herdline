package io.github.erikfrish.herdline.ui

import io.github.erikfrish.herdline.data.LayoutMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowClassTest {
    @Test
    fun a_phone_held_sideways_stays_compact() {
        // Collie's case: wide enough for a rail, too short for one.
        assertEquals(WindowClass.Compact, windowClass(915, 412))
        assertEquals(WindowClass.Compact, windowClass(1280, 479))
    }

    @Test
    fun width_bounds_once_tall_enough() {
        assertEquals(WindowClass.Compact, windowClass(599, 900))
        assertEquals(WindowClass.Medium, windowClass(600, 480))
        assertEquals(WindowClass.Medium, windowClass(839, 1000))
        assertEquals(WindowClass.Expanded, windowClass(840, 480))
    }

    @Test
    fun the_keyboard_holds_the_wider_class() {
        assertEquals(WindowClass.Expanded, heldWindowClass(WindowClass.Compact, WindowClass.Expanded, imeVisible = true))
        assertEquals(WindowClass.Medium, heldWindowClass(WindowClass.Compact, WindowClass.Medium, imeVisible = true))
    }

    @Test
    fun the_hold_ends_with_the_keyboard() {
        assertEquals(WindowClass.Compact, heldWindowClass(WindowClass.Compact, WindowClass.Expanded, imeVisible = false))
    }

    @Test
    fun widening_is_taken_with_the_keyboard_up() {
        assertEquals(WindowClass.Expanded, heldWindowClass(WindowClass.Expanded, WindowClass.Compact, imeVisible = true))
    }

    @Test
    fun the_first_measure_is_never_held() {
        assertEquals(WindowClass.Compact, heldWindowClass(WindowClass.Compact, null, imeVisible = true))
    }

    @Test
    fun the_session_sidebar_follows_the_user_then_the_window() {
        assertTrue(sessionSidebar(LayoutMode.Wide, WindowClass.Compact))
        assertFalse(sessionSidebar(LayoutMode.Compact, WindowClass.Expanded))
        assertTrue(sessionSidebar(LayoutMode.Auto, WindowClass.Expanded))
        // Medium keeps the root rail but not the session sidebar.
        assertFalse(sessionSidebar(LayoutMode.Auto, WindowClass.Medium))
    }

    @Test
    fun the_side_composer_scales_with_the_window_within_its_bounds() {
        // The QA phone sideways: 915 dp wide.
        assertEquals(915 * SIDE_COMPOSER_FRACTION, sideComposerWidthDp(915f, typingShort = true)!!, 0.01f)
        // A narrow window keeps the minimum, a wide one stops at the maximum.
        assertEquals(SIDE_COMPOSER_MIN_DP.toFloat(), sideComposerWidthDp(700f, typingShort = true)!!, 0.01f)
        assertEquals(SIDE_COMPOSER_MAX_DP.toFloat(), sideComposerWidthDp(1400f, typingShort = true)!!, 0.01f)
    }

    @Test
    fun the_composer_goes_aside_only_while_typing_sideways_with_room_left() {
        assertNull(sideComposerWidthDp(915f, typingShort = false))
        // The content must keep its minimum beside the narrowest composer.
        val edge = (SIDE_COMPOSER_MIN_DP + SIDE_CONTENT_MIN_DP).toFloat()
        assertEquals(SIDE_COMPOSER_MIN_DP.toFloat(), sideComposerWidthDp(edge, typingShort = true)!!, 0.01f)
        assertNull(sideComposerWidthDp(edge - 1, typingShort = true))
    }
}
