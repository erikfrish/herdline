package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ImageSource
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptImage
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.core.TranscriptSteer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatModelTest {
    private val agent = TranscriptIdentity("w1:p1", "term_1", 42, "/h/.omp/agent/sessions/x/a.jsonl")

    private fun text(id: String, parent: String?, offset: Long, role: String, text: String) =
        TranscriptEntry(id, parent, offset, offset + 10, TranscriptKind.Message, role, listOf(TranscriptBlock.Text(text, false)))

    private fun page(vararg entries: TranscriptEntry, start: Long = 0, end: Long = 100, reset: Boolean = false, identity: TranscriptIdentity = agent) =
        TranscriptPage(identity, reset, end, start, end, entries.toList())

    private fun wire(json: String) = TranscriptEntry.parse(JSONObject(json))

    private fun texts(entries: List<TranscriptEntry>) = chatItems(entries).map {
        when (it) {
            is ChatItem.User -> "u:${it.text}"
            is ChatItem.Assistant -> "a:${it.text}"
            else -> it.toString()
        }
    }

    @Test
    fun a_branch_from_an_earlier_message_hides_the_abandoned_turns() {
        val entries = listOf(
            text("1", null, 0, "user", "first"),
            text("2", "1", 10, "assistant", "old answer"),
            text("3", "2", 20, "user", "abandoned"),
            // The user went back to "first" and asked again.
            text("4", "1", 30, "assistant", "new answer"),
        )
        assertEquals(listOf("u:first", "a:new answer"), texts(entries))
    }

    @Test
    fun entries_older_than_the_loaded_part_of_the_branch_are_kept() {
        // Parent "0" is not loaded yet, so "x" may be on the branch.
        val entries = listOf(text("x", "w", 0, "user", "earlier"), text("1", "0", 10, "user", "hi"), text("2", "1", 20, "assistant", "yo"))
        assertEquals(listOf("u:earlier", "u:hi", "a:yo"), texts(entries))
    }

    @Test
    fun a_linear_agent_history_keeps_every_message_and_stops_at_its_oldest_page() {
        val rows = listOf(text("u", null, 1, "user", "hello"), text("a", null, 2, "assistant", "reply"))
        assertEquals(listOf("u:hello", "a:reply"), texts(rows))
        val first = TranscriptPage(agent.copy(agent = "opencode", kind = "id", path = "ses_a"), true, 2, 1, 2, rows, hasOlder = false)
        assertFalse(ChatHistory.of(first).hasOlder)
    }

    @Test
    fun tool_results_pair_with_their_call_and_a_blocked_call_is_pending() {
        val call = TranscriptBlock.ToolCall("t1", "ask", "{}", "Asking", false)
        val entries = listOf(
            TranscriptEntry("a", null, 0, 10, TranscriptKind.Message, "assistant", listOf(call)),
            TranscriptEntry("r", "a", 10, 20, TranscriptKind.Message, "toolResult", listOf(TranscriptBlock.ToolResult("t1", "ask", "red", false, emptyList(), false))),
        )
        val items = chatItems(entries)
        assertEquals(1, items.size)
        assertEquals("red", (items[0] as ChatItem.Tool).result?.text)
        assertNull(pendingTool(items))
        assertEquals(call, pendingTool(chatItems(entries.take(1))))
    }

    @Test
    fun reads_merge_without_duplicates_and_stale_answers_are_ignored() {
        val tail = ChatHistory.of(page(text("2", "1", 50, "assistant", "b"), start = 50, end = 100, reset = true))
        // An overlapping append and an older page arrive; each entry stays once, in file order.
        val merged = tail
            .merge(page(text("2", "1", 50, "assistant", "b"), text("3", "2", 100, "user", "c"), start = 50, end = 150), appended = true)
            .merge(page(text("1", null, 0, "user", "a"), start = 0, end = 50), appended = false)
        assertEquals(listOf(0L, 50L, 100L), merged.entries.map { it.offset })
        assertEquals(0L to 150L, merged.start to merged.end)

        val other = agent.copy(path = "/h/.omp/agent/sessions/x/b.jsonl")
        assertSame(merged, merged.merge(page(text("9", null, 0, "user", "z"), identity = other), appended = true))
        assertEquals(other, merged.merge(page(text("9", null, 0, "user", "z"), identity = other, reset = true), appended = true).identity)
    }

    @Test
    fun appends_past_the_bound_drop_the_oldest_and_offer_them_again() {
        val many = (0 until MAX_LOADED_ENTRIES + 5).map { text("$it", null, it * 10L, "user", "m$it") }
        val history = ChatHistory.of(page(start = 0, end = 0, reset = true))
            .merge(page(*many.toTypedArray(), end = many.size * 10L), appended = true)
        assertEquals(MAX_LOADED_ENTRIES, history.entries.size)
        assertEquals(50L, history.start)
        assertEquals(true, history.hasOlder)
    }

    @Test
    fun a_task_calls_children_keep_one_row_each_and_settled_ends_stay_stuck() {
        val call = """{"id":"a","offset":0,"end":10,"kind":"message","role":"assistant","blocks":[{"type":"tool_call","call_id":"t1","name":"task","args":"{}","truncated":false}]}"""
        // The first result lists both children; a later one restates only c2.
        val first = """{"id":"r1","parent_id":"a","offset":10,"end":20,"kind":"message","role":"toolResult","blocks":[{"type":"tool_result","call_id":"t1","name":"task","text":"children","is_error":false,"truncated":false,"subagents":[{"id":"c1","agent":"Explorer","status":"running"},{"id":"c2","status":"completed"}]}]}"""
        val restate = """{"id":"r2","parent_id":"r1","offset":20,"end":30,"kind":"message","role":"toolResult","blocks":[{"type":"tool_result","call_id":"t1","name":"task","text":"update","is_error":false,"truncated":false,"subagents":[{"id":"c2","status":"completed","output":"checked"}]}]}"""
        val end = """{"id":"e","parent_id":"r2","offset":30,"end":40,"kind":"other","subagent_ends":[{"id":"c1","status":"completed","durationMs":5000}]}"""
        val tool = chatItems(listOf(wire(call), wire(first), wire(restate), wire(end))).single() as ChatItem.Tool
        // One row per exact id: a result restating a sibling never duplicates
        // it, and c1's earlier snapshot survives the later result.
        assertEquals(listOf("c1", "c2"), tool.subagents.map { it.id })
        assertEquals("checked", tool.subagents[1].output)
        // The end settles c1 whichever order the file lists it in: sticky.
        assertEquals(SubagentStatus.Completed, tool.subagents[0].status)
        assertEquals(5000L, tool.subagents[0].durationMs)
        val endFirst = chatItems(listOf(wire(end), wire(call), wire(first), wire(restate))).single() as ChatItem.Tool
        assertEquals(listOf(SubagentStatus.Completed, SubagentStatus.Completed), endFirst.subagents.map { it.status })
    }
    @Test
    fun images_are_located_on_their_own_line_and_a_users_image_stays_in_their_turn() {
        val hash = "ab".repeat(32)
        val entries = listOf(
            wire("""{"id":"u","offset":0,"end":40,"kind":"message","role":"user","blocks":[{"type":"text","text":"look","truncated":false},{"type":"image","part":1,"source":{"kind":"inline","bytes":9}}]}"""),
            wire("""{"id":"a","parent_id":"u","offset":40,"end":60,"kind":"message","role":"assistant","blocks":[{"type":"tool_call","call_id":"c","name":"read","args":"{}","truncated":false}]}"""),
            wire("""{"id":"r","parent_id":"a","offset":60,"end":90,"kind":"message","role":"toolResult","blocks":[{"type":"tool_result","call_id":"c","name":"read","text":"","is_error":false,"truncated":false,"images":[{"part":2,"source":{"kind":"blob","hash":"$hash"}},{"part":3,"source":{"kind":"blob","hash":"not-hex"}}]}]}"""),
        )
        val items = chatItems(entries)
        val image = items[1] as ChatItem.Image
        assertEquals(TranscriptImage("u", 0, 40, 1, ImageSource.Inline(9)), image.image)
        assertEquals(true, image.fromUser)
        // A tool's images are read from the result's line, not the call's.
        assertEquals(
            listOf(TranscriptImage("r", 60, 90, 2, ImageSource.Blob(hash)), TranscriptImage("r", 60, 90, 3, ImageSource.Unreadable)),
            (items[2] as ChatItem.Tool).images,
        )
        val lines = chatLines(items)
        assertEquals(listOf(Gap.None, Gap.Block, Gap.Turn), lines.map { it.gap })
        assertEquals(listOf(false, false, true), lines.map { it.labelled })
    }

    @Test
    fun an_image_key_holds_across_reads_and_differs_by_line_and_part() {
        val json = """{"offset":120,"end":180,"kind":"message","role":"assistant","blocks":[{"type":"image","part":0,"source":{"kind":"inline","bytes":3}},{"type":"image","part":1,"source":{"kind":"inline","bytes":3}}]}"""
        val first = chatItems(listOf(wire(json))).map { (it as ChatItem.Image).image.key }
        assertEquals(first, chatItems(listOf(wire(json))).map { (it as ChatItem.Image).image.key })
        assertEquals(2, first.toSet().size)
        val elsewhere = chatItems(listOf(wire(json.replace("\"offset\":120", "\"offset\":60")))).map { (it as ChatItem.Image).image.key }
        assertFalse(elsewhere.any { it in first })
    }

    @Test
    fun a_steered_user_message_says_how_omp_took_it_and_nothing_else_does() {
        fun steer(value: String?) = (chatItems(listOf(wire("""{"id":"u","offset":0,"end":9,"kind":"message","role":"user",${value?.let { "\"steer\":\"$it\"," }.orEmpty()}"blocks":[{"type":"text","text":"hi","truncated":false}]}"""))).single() as ChatItem.User).steer
        assertEquals(TranscriptSteer.Steering, steer("steering"))
        assertEquals(TranscriptSteer.Live, steer("live"))
        assertNull(steer(null))
        assertNull("an unknown mark is no steer", steer("queued"))
    }

    @Test
    fun the_status_is_the_live_supported_agent_in_this_pane_and_unknown_otherwise() {
        val omp = Agent("w1:p1", "w1", "t1", null, "omp", null, AgentStatus.Working, false)
        assertEquals(AgentStatus.Working, chatStatus(connected = true, pane = "w1:p1", agent = omp))
        assertEquals("link lost", AgentStatus.Unknown, chatStatus(connected = false, pane = "w1:p1", agent = omp))
        assertEquals("no agent", AgentStatus.Unknown, chatStatus(connected = true, pane = "w1:p1", agent = null))
        assertEquals("another pane", AgentStatus.Unknown, chatStatus(connected = true, pane = "w1:p2", agent = omp))
        for (name in listOf("pi", "claude", "codex", "opencode")) {
            assertEquals(name, AgentStatus.Working, chatStatus(connected = true, pane = "w1:p1", agent = omp.copy(agent = name)))
        }
        assertEquals("unknown agent", AgentStatus.Unknown, chatStatus(connected = true, pane = "w1:p1", agent = omp.copy(agent = "other")))
        assertEquals("unknown program", AgentStatus.Unknown, chatStatus(connected = true, pane = "w1:p1", agent = omp.copy(agent = null)))
        assertEquals(AgentStatus.Idle, chatStatus(connected = true, pane = "w1:p1", agent = omp.copy(status = AgentStatus.Idle)))
    }

    @Test
    fun an_unclosed_code_fence_still_renders_as_code() {
        val blocks = markdownBlocks("Run:\n```sh\nls -la\n")
        assertEquals(listOf(MdBlock.Paragraph("Run:"), MdBlock.Code("sh", "ls -la\n")), blocks)
    }
}
