package io.github.erikfrish.herdline.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceCreateTest {
    private fun errorOf(result: Result<CreatedWorkspace>): WorkspaceCreateError =
        (result.exceptionOrNull() as WorkspaceCreateException).error

    private fun answered(json: String) = workspaceCreated(RequestReply.Answered(JSONObject(json)), "b1")

    @Test
    fun aLabelAndACwdGoVerbatimAndOnlyNullIsLeftOut() {
        val params = WorkspaceCreate(label = " qa ws ", cwd = " /tmp/x ", sourceWorkspaceId = "w1", focus = false, env = mapOf("A" to "1")).params()
        assertEquals(" qa ws ", params.getString("label"))
        assertEquals(" /tmp/x ", params.getString("cwd"))
        assertEquals("w1", params.getString("source_workspace_id"))
        assertFalse(params.getBoolean("focus"))
        assertEquals("1", params.getJSONObject("env").getString("A"))
        val bare = WorkspaceCreate().params()
        assertEquals(setOf("focus"), bare.keys().asSequence().toSet())
        assertTrue(bare.getBoolean("focus"))
    }

    @Test
    fun herdrsAndHerdrCoresSuccessBothParseToTheCreatedIds() {
        val classic = answered(
            """{"result":{"workspace":{"workspace_id":"w2","number":2,"label":"qa","focused":true,"pane_count":1,"tab_count":1},
               "tab":{"tab_id":"w2:t1"},"root_pane":{"pane_id":"w2:p1","terminal_id":"term-9","workspace_id":"w2","tab_id":"w2:t1"}}}""",
        ).getOrThrow()
        assertEquals(CreatedWorkspace("b1", "w2", "qa", true, "w2:t1", "w2:p1", "term-9"), classic)
        assertEquals(Connection.CreatedTab("b1", "w2:t1", "w2:p1", "term-9"), classic.rootTab())
        val core = answered(
            """{"result":{"workspace":{"workspace_id":"s2","label":"","focused":false},"tab":{"tab_id":"t2"},"root_pane":{"pane_id":"p2","terminal_id":"x2"}}}""",
        ).getOrThrow()
        assertEquals(CreatedWorkspace("b1", "s2", "", false, "t2", "p2", "x2"), core)
    }

    @Test
    fun aSuccessMissingAnIdIsAnUnknownOutcomeNeverARefusal() {
        val result = answered("""{"result":{"workspace":{"workspace_id":"w2"},"tab":{"tab_id":"t"},"root_pane":{"pane_id":"p"}}}""")
        assertEquals(WorkspaceCreateError.OutcomeUnknown(BAD_REPLY), errorOf(result))
    }

    @Test
    fun aDefiniteRefusalKeepsTheDaemonsCodeAndMessage() {
        assertEquals(
            WorkspaceCreateError.Refused("invalid_env", "env key A=B must not contain '='"),
            errorOf(answered("""{"id":"r","error":{"code":"invalid_env","message":"env key A=B must not contain '='"}}""")),
        )
        assertEquals(
            WorkspaceCreateError.Refused("workspace_not_found", "workspace gone not found"),
            errorOf(answered("""{"id":"r","error":{"kind":"workspace_not_found","reason":"workspace_not_found","message":"workspace gone not found"}}""")),
        )
        assertEquals(
            WorkspaceCreateError.Refused(ReasonCode.UNSUPPORTED_BACKEND, "unsupported_backend"),
            errorOf(answered("""{"error":{"kind":"unsupported","reason":"unsupported_backend"}}""")),
        )
        assertEquals(
            WorkspaceCreateError.Refused("takes_size", "takes_size"),
            errorOf(workspaceCreated(RequestReply.Refused("takes_size"), "b1")),
        )
        assertEquals(WorkspaceCreateError.NotConnected, errorOf(workspaceCreated(RequestReply.Refused("not_connected"), "b1")))
    }

    @Test
    fun anUnreadAnswerOrAnEndedCoreLinkIsAnUnknownOutcome() {
        assertEquals(WorkspaceCreateError.OutcomeUnknown("outcome_unknown"), errorOf(workspaceCreated(RequestReply.Uncertain, "b1")))
        assertEquals(
            WorkspaceCreateError.OutcomeUnknown("outcome_unknown"),
            errorOf(answered("""{"error":{"kind":"uncertain","reason":"uncertain","message":"uncertain"}}""")),
        )
    }
}
