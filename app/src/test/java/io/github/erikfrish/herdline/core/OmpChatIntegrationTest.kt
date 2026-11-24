package io.github.erikfrish.herdline.core

import io.github.erikfrish.herdline.core.OmpChatIntegration.State
import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OmpChatIntegrationTest {
    @Test
    fun anInstallReportsItsFolderAndFiles() {
        val reply = OmpChatIntegration.parse(
            """{"state": "installed", "path": "/home/me/.omp/agent/extensions/herdline-chat", "files": ["herdline-core.ts", "package.json"], "message": null}""",
        )
        assertEquals(
            OmpChatIntegration(State.Installed, "/home/me/.omp/agent/extensions/herdline-chat", listOf("herdline-core.ts", "package.json"), null),
            reply,
        )
    }

    @Test
    fun aHostErrorKeepsTheExactMessage() {
        val reply = OmpChatIntegration.parse(
            """{"state": "error", "path": null, "files": [], "message": "OMP has not been set up for this account: /home/me/.omp/agent is missing."}""",
        )
        assertEquals(State.Error, reply.state)
        assertEquals(null, reply.path)
        assertEquals("OMP has not been set up for this account: /home/me/.omp/agent is missing.", reply.message)
    }

    @Test
    fun anUnknownStateIsNotTakenForAbsent() {
        assertThrows(JSONException::class.java) {
            OmpChatIntegration.parse("""{"state": "maybe", "path": null, "files": [], "message": null}""")
        }
    }
}
