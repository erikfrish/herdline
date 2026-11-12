package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowKeyTest {
    @Test
    fun the_pane_bar_shows_for_a_split_tab_or_a_wide_window_never_typing_short() {
        assertTrue(paneBarShown(typingShort = false, tabPanes = 2, wide = false))
        assertFalse(paneBarShown(typingShort = false, tabPanes = 1, wide = false))
        assertTrue(paneBarShown(typingShort = false, tabPanes = 1, wide = true))
        // No view or tab yet: no bar, whatever the window.
        assertFalse(paneBarShown(typingShort = false, tabPanes = null, wide = true))
        assertFalse(paneBarShown(typingShort = true, tabPanes = 2, wide = true))
    }

    @Test
    fun the_pane_bar_coming_or_going_is_a_new_window() {
        val portrait = 1
        val without = sessionWindowKey(1080 to 2400, 0, portrait, paneBar = false)
        val with = sessionWindowKey(1080 to 2400, 0, portrait, paneBar = true)
        // The view arrives after the first grid and the bar takes 64 px: a refit, not chrome.
        assertNotEquals(without, with)
        assertEquals(with, sessionWindowKey(1080 to 2400, 0, portrait, paneBar = true))
    }
}
