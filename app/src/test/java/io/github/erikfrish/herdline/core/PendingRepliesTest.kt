package io.github.erikfrish.herdline.core

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reply registry behind `Connection.awaitRequest` and the two parsers the
 * new-tab flow trusts: one semantically meaningful case per boundary.
 */
class PendingRepliesTest {

    @Test
    fun a_reply_that_lands_before_the_waiter_registers_still_reaches_it() {
        val replies = PendingReplies()
        val waiter = CompletableDeferred<RequestReply>()
        // The reply raced ahead of registration: it was settled first.
        assertFalse(replies.settle("gpui-1", RequestReply.Uncertain))
        assertEquals(RequestReply.Uncertain, replies.register("gpui-1", waiter))
    }

    @Test
    fun a_registered_waiter_takes_its_reply_and_nothing_else() {
        val replies = PendingReplies()
        val waiter = CompletableDeferred<RequestReply>()
        assertNull(replies.register("gpui-1", waiter))
        val answer = RequestReply.Refused("rejected")
        assertTrue(replies.settle("gpui-1", answer))
        // A second settle must find no waiter: one request, one answer.
        assertFalse(replies.settle("gpui-1", answer))
        assertEquals(answer, waiter.getCompleted())
    }

    @Test
    fun a_dying_link_refuses_its_waiters_instead_of_keeping_them_waiting() {
        val replies = PendingReplies()
        val waiter = CompletableDeferred<RequestReply>()
        assertNull(replies.register("gpui-1", waiter))
        replies.failAll(RequestReply.Refused("disconnected"))
        assertEquals(RequestReply.Refused("disconnected"), waiter.getCompleted())
    }

    @Test
    fun replies_waiting_for_a_gone_waiter_are_dropped_not_replayed() {
        val replies = PendingReplies()
        val waiter = CompletableDeferred<RequestReply>()
        assertNull(replies.register("gpui-1", waiter))
        replies.remove("gpui-1")
        // The waiter cancelled: its late reply triggers no delivery, now or later.
        assertFalse(replies.settle("gpui-1", RequestReply.Uncertain))
        assertEquals(RequestReply.Uncertain, replies.register("gpui-1", CompletableDeferred()))
    }
}

class CreatedTabParseTest {

    @Test
    fun the_created_ids_come_from_the_tab_create_result_verbatim() {
        val envelope = JSONObject("""{"id":"gpui-1","result":{
            "tab":{"tab_id":"tab-7","number":2},
            "root_pane":{"pane_id":"pane-9","terminal_id":"terminal-3"}
        }}""")
        val created = parseCreated(envelope, "boot-1").getOrThrow()
        assertEquals(Connection.CreatedTab("boot-1", "tab-7", "pane-9", "terminal-3"), created)
    }

    @Test
    fun a_result_without_both_exact_ids_is_never_called_created() {
        for (body in listOf(
            """{"id":"gpui-1","error":{"message":"no workspace"}}""",
            """{"id":"gpui-1","result":{"tab":{"tab_id":"tab-7"}}}""",
            """{"id":"gpui-1","result":{"root_pane":{"pane_id":""}}}""",
        )) {
            assertTrue(parseCreated(JSONObject(body), "boot-1").isFailure)
        }
    }
}

class AgentStartNameTest {

    @Test
    fun the_name_is_the_kind_plus_a_random_suffix_and_nothing_else() {
        val name = agentStartName("codex", suffix = "00ab12ef")!!
        assertEquals("codex-00ab12ef", name)
        // The rules every generated name must hold.
        assertTrue(name.matches(Regex("[a-z][a-z0-9_-]{0,31}")))
    }

    @Test
    fun a_kind_that_cannot_lead_a_legal_name_is_refused() {
        for (kind in listOf("", "Claude", "claude้าน", "-claude", "nd".repeat(16))) {
            assertNull(kind, agentStartName(kind, suffix = "00ab12ef"))
        }
    }

    @Test
    fun a_32_character_name_still_fits_the_rules() {
        // 23-char kind + dash + 8 hex: the longest name the rules allow.
        val name = agentStartName("a".repeat(23), suffix = "00ab12ef")!!
        assertEquals(32, name.length)
        assertNull(agentStartName("a".repeat(24), suffix = "00ab12ef"))
    }
}

class AgentLauncherParseTest {

    @Test
    fun a_listing_carries_kind_and_label_only() {
        val launchers = AgentLauncher.parse("""{"launchers":[{"kind":"pi","label":"Pi"}]}""")
        assertEquals(listOf(AgentLauncher("pi", "Pi")), launchers)
        // Malformed entries are skipped, never invented: a bad line is not a launcher.
        assertTrue(
            AgentLauncher.parse("""{"launchers":[{"kind":"codex"},{"label":"Orphan"},{"kind":"kimi","label":"Kimi"}]}""")
                == listOf(AgentLauncher("kimi", "Kimi")),
        )
    }
}
