package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.data.ComposerAction.Insert
import io.github.erikfrish.herdline.data.ComposerAction.LargerEditor
import io.github.erikfrish.herdline.data.ComposerAction.Paste
import io.github.erikfrish.herdline.data.ComposerAction.TerminalKeyboard
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputFocusMenuTest {
    @Test
    fun outputFocusFollowsTheLargerEditorWhereverTheUserMovedIt() {
        // Default order: between the larger editor and the terminal keyboard.
        assertEquals(2, outputFocusSlot(listOf(Insert, LargerEditor, TerminalKeyboard, Paste)))
        assertEquals(1, outputFocusSlot(listOf(LargerEditor, Paste, Insert)))
    }

    @Test
    fun withTheLargerEditorHiddenItGoesBeforeTheTerminalKeyboardElseLast() {
        assertEquals(1, outputFocusSlot(listOf(Insert, TerminalKeyboard, Paste)))
        assertEquals(2, outputFocusSlot(listOf(Insert, Paste)))
        assertEquals(0, outputFocusSlot(emptyList()))
    }
}
