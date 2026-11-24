package io.github.erikfrish.herdline.ui.components

import io.github.erikfrish.herdline.ui.theme.NodeColors
import org.junit.Assert.assertEquals
import org.junit.Test

class ColorNamesTest {
    @Test
    fun everyNodeColourHasItsOwnSpokenName() {
        assertEquals(NodeColors.all.size, NodeColorNames.size)
        assertEquals(NodeColorNames.size, NodeColorNames.toSet().size)
    }
}
