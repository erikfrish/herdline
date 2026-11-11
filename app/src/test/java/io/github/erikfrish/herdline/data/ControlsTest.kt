package io.github.erikfrish.herdline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ControlsTest {
    private val insert = ComposerAction.Insert.id
    private val editor = ComposerAction.LargerEditor.id
    private val keyboard = ComposerAction.TerminalKeyboard.id
    private val paste = ComposerAction.Paste.id

    @Test
    fun insertOrTheLargerEditorAlwaysStaysInThePlusMenu() {
        // Q-2: either one may go, but never the second of the two.
        val noInsert = ComposerMenu.toggled(ComposerMenu.DEFAULT, insert)
        assertEquals(listOf(editor, keyboard, paste), noInsert)
        assertFalse(ComposerMenu.mayHide(noInsert, editor))
        assertEquals(noInsert, ComposerMenu.toggled(noInsert, editor))

        val noEditor = ComposerMenu.toggled(ComposerMenu.DEFAULT, editor)
        assertFalse(ComposerMenu.mayHide(noEditor, insert))
        assertEquals(noEditor, ComposerMenu.toggled(noEditor, insert))

        // The other entries hide freely, down to the one Insert route.
        val least = listOf(keyboard, paste, insert).fold(ComposerMenu.DEFAULT) { ids, id -> ComposerMenu.toggled(ids, id) }
        assertEquals(listOf(editor), least)
        assertTrue(ComposerMenu.keepsInsert(least))
    }

    @Test
    fun aSavedMenuWithoutEitherInsertRouteGetsInsertBack() {
        assertEquals(listOf(paste, insert), ComposerMenu.repaired(listOf(paste)))
        assertEquals(listOf(insert), ComposerMenu.repaired(emptyList()))
        assertEquals(listOf(editor), ComposerMenu.repaired(listOf(editor)))
    }

    @Test
    fun aShownEntryComesBackAtTheEndAndKeepsTheSavedOrder() {
        val reordered = listOf(paste, editor, keyboard)
        assertEquals(listOf(paste, editor, keyboard, insert), ComposerMenu.toggled(reordered, insert))
        assertEquals(
            listOf(ComposerAction.Paste, ComposerAction.LargerEditor, ComposerAction.TerminalKeyboard),
            ComposerMenu.actions(reordered + "gone"),
        )
    }

    @Test
    fun savedControlsDropUnknownIdsAndShowOnesAddedLater() {
        val catalogue = listOf("a", "b", "c")
        // Nothing saved: everything, in catalogue order.
        assertEquals(catalogue, savedControls(null, null, catalogue))
        // "b" was hidden on purpose and stays hidden; "x" no longer exists.
        assertEquals(listOf("c", "a"), savedControls(listOf("c", "x", "a"), listOf("a", "b", "c"), catalogue))
        // "c" is new since the list was saved, so it is appended shown.
        assertEquals(listOf("a", "c"), savedControls(listOf("a"), listOf("a", "b"), catalogue))
        // Every control hidden is kept as saved.
        assertEquals(emptyList<String>(), savedControls(emptyList(), catalogue, catalogue))
        assertEquals(1, hiddenCount(listOf("c", "a"), catalogue))
    }
}
