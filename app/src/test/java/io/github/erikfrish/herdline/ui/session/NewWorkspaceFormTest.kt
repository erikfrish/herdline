package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.CreatedWorkspace
import io.github.erikfrish.herdline.core.WorkspaceCreate
import io.github.erikfrish.herdline.core.WorkspaceCreateError
import io.github.erikfrish.herdline.core.WorkspaceCreateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class NewWorkspaceFormTest {
    private val created = CreatedWorkspace("boot", "w9", "qa-ws", false, "w9t1", "w9p1", "term9")

    @Test
    fun nameAndFolderAreSentExactlyAsTypedAndBlankIsOmitted() {
        assertEquals(
            WorkspaceCreate(label = "  qa ws ", cwd = " /tmp/a b ", sourceWorkspaceId = "w1", focus = false, env = emptyMap()),
            workspaceRequest("  qa ws ", " /tmp/a b ", "w1", focus = false, env = emptyList()),
        )
        assertEquals(
            WorkspaceCreate(label = null, cwd = null, sourceWorkspaceId = null, focus = true, env = emptyMap()),
            workspaceRequest("   ", "", null, focus = true, env = emptyList()),
        )
    }

    @Test
    fun envRowsAreSentAsTypedEmptyRowsDroppedAndAClashingKeyBlocksCreate() {
        val sent = workspaceRequest("", "", null, true, listOf(EnvRow("A", " 1 "), EnvRow(), EnvRow("", "v")))
        // An empty name with a value goes to Herdr, which refuses it as invalid_env.
        assertEquals(mapOf("A" to " 1 ", "" to "v"), sent?.env)
        assertNull(workspaceRequest("", "", null, true, listOf(EnvRow("A", "1"), EnvRow("A", "2"))))
    }

    @Test
    fun createIsSingleShotAndAnUnknownOutcomeIsNeverResent() {
        assertEquals(CreatePhase.Creating, CreatePhase.Editing.submit())
        // A second tap or IME Done while in flight does nothing.
        assertNull(CreatePhase.Creating.submit())
        assertNull(CreatePhase.Unknown.submit())
        assertNull(CreatePhase.Created(created).submit())
        // Nothing was created: the user may send the kept values again by hand.
        assertEquals(CreatePhase.Creating, CreatePhase.Refused("invalid_env", "bad key").submit())
    }

    @Test
    fun onlyADefiniteRefusalLetsTheFormSendAgain() {
        fun phase(error: WorkspaceCreateError) = createdPhase(Result.failure(WorkspaceCreateException(error)))
        assertEquals(CreatePhase.Refused("invalid_env", "bad key"), phase(WorkspaceCreateError.Refused("invalid_env", "bad key")))
        assertEquals(CreatePhase.Refused("not_connected", null), phase(WorkspaceCreateError.NotConnected))
        assertEquals(CreatePhase.Unknown, phase(WorkspaceCreateError.OutcomeUnknown("outcome_unknown")))
        assertEquals(CreatePhase.Unknown, phase(WorkspaceCreateError.OutcomeUnknown("bad_reply")))
        assertEquals(CreatePhase.Unknown, createdPhase(Result.failure(IOException("reset"))))
    }

    @Test
    fun focusOffNeverOpensByItselfAndFocusOnOpensTheReply() {
        val done = createdPhase(Result.success(created))
        assertFalse(opensAtOnce(done, focus = false))
        assertTrue(opensAtOnce(done, focus = true))
        assertFalse(opensAtOnce(CreatePhase.Unknown, focus = true))
    }
}
