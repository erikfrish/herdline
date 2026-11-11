package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RefusalReasonTest {
    @Test
    fun aCodeThisBuildDoesNotKnowNeverSaysTheConnectionIsDown() {
        // UR4: a newer core's code must not read as "Not connected".
        for (code in listOf("future_refusal", "", "editor_future")) {
            assertNotEquals(code, R.string.reason_not_connected, reasonRes(code))
        }
        assertEquals(R.string.reason_not_connected, reasonRes("not_connected"))
    }

    @Test
    fun anUnknownEditorCodeReadsAsTheUnreadableBoxAndAnyOtherAsNeutral() {
        assertEquals(R.string.reason_editor_unrecognized, reasonRes("editor_future"))
        assertEquals(R.string.reason_unknown, reasonRes("future_refusal"))
    }

    @Test
    fun theMeasuredQuestionHasItsOwnWordsNotTheUnreadableBox() {
        // UR4b: the one code that may say a dialog is open; the editor_*
        // fallback must not swallow it.
        assertEquals(R.string.reason_editor_dialog_open, reasonRes("editor_dialog_open"))
    }

    @Test
    fun aPaneSizedForAnotherScreenIsNotTheNeutralRefusal() {
        // RZ-u-2: the core's steady cause, not a "resizing" that never ends.
        assertEquals(R.string.reason_pane_sized_elsewhere, reasonRes("pane_sized_elsewhere"))
    }
}
