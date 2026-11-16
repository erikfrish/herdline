package io.github.erikfrish.herdline.core

import io.github.erikfrish.herdline.data.Backend
import io.github.erikfrish.herdline.data.Node
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HerdrCoreTest {
    @Test
    fun anActionHerdrCoreLeavesOutIsOffButHerdrKeepsWhatItHad() {
        val map = JSONObject("""{"tab.create":null,"terminal.input":"takes_size","herdr.setup":"core_managed"}""")
        val core = Capabilities.parse(map, Backend.HerdrCore)
        assertNull(core.reason("tab.create"))
        assertEquals(ReasonCode.TAKES_SIZE, core.reason("terminal.input"))
        assertEquals(ReasonCode.CORE_MANAGED, core.reason("herdr.setup"))
        assertEquals(ReasonCode.UNSUPPORTED_BACKEND, core.reason("pane.split"))
        // Herdr predates the map: an old daemon's missing key is still supported.
        assertNull(Capabilities.parse(null, Backend.Herdr).reason("pane.split"))
    }

    @Test
    fun aHerdrPaneWithoutTheFlagAlwaysTakesInputAndAWatchedCorePaneNever() {
        val view = ShellView.parse(
            """
            {"revision":1,"focused_pane":"p1","workspaces":[],"tabs":[],"agents":[],
             "panes":[{"id":"p1","workspace":"w","tab":"t","label":null,"cwd":null,"focused":true},
                      {"id":"p2","workspace":"w","tab":"t","label":null,"cwd":null,"focused":false,
                       "kind":"terminal","chat":"identity_unknown","unsupported":null,"interactive":false},
                      {"id":"p3","workspace":"w","tab":"t","label":null,"cwd":null,"focused":false,
                       "kind":"mod","chat":"identity_unknown","unsupported":"mod_panel","interactive":null}]}
            """.trimIndent(),
        )
        val (herdr, watched, mod) = view.panes
        assertNull(herdr.interactive)
        assertTrue(herdr.takesInput)
        assertNull(herdr.chat)
        assertFalse(watched.takesInput)
        assertEquals(ReasonCode.IDENTITY_UNKNOWN, watched.chat)
        assertEquals(PaneKind.Mod, mod.kind)
        assertEquals(ReasonCode.MOD_PANEL, mod.unsupported)
        assertFalse(mod.takesInput)
    }

    @Test
    fun anyErrorIsAFailureCarryingWhatItSaidAndOnlyNoErrorIsNot() {
        assertEquals(ReasonCode.TAKES_SIZE, refusal(JSONObject("""{"error":{"kind":"unsupported","reason":"takes_size"}}""")))
        assertEquals("conflict", refusal(JSONObject("""{"error":{"kind":"conflict"}}""")))
        assertEquals("uncertain", refusal(JSONObject("""{"error":{"kind":"uncertain"}}""")))
        // A generic JSON-RPC error is never a success, and keeps its message, kind and code.
        assertEquals(
            "pane not found (kind=not_found, code=-32602)",
            refusal(JSONObject("""{"error":{"kind":"not_found","code":-32602,"message":"pane not found"}}""")),
        )
        assertEquals(REQUEST_FAILED, refusal(JSONObject("""{"error":{}}""")))
        assertNull(refusal(JSONObject("""{"result":{}}""")))
    }

    @Test
    fun anUnknownSavedBackendIsKeptAndNeverTakenForHerdr() {
        val base = """{"id":"n","name":"","address":"h","user":"u""""
        val unknown = Node.fromJson(JSONObject("""$base,"backend":"herdr_next"}"""))
        assertNull(unknown.backend)
        assertEquals("herdr_next", unknown.toJson().getString("backend"))
        assertEquals(Backend.Herdr, Node.fromJson(JSONObject("""$base}""")).backend)
        assertEquals(Backend.Herdr, Node.fromJson(JSONObject("""$base,"backend":null}""")).backend)
        assertEquals(Backend.HerdrCore, Node.fromJson(JSONObject("""$base,"backend":"herdr_core"}""")).backend)
    }
}
