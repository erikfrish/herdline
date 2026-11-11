package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFoldTest {
    private fun tool(key: String, name: String = "read", done: Boolean = true, error: Boolean = false) = ChatItem.Tool(
        key,
        TranscriptBlock.ToolCall(key, name, "{}", null, false),
        if (done) TranscriptBlock.ToolResult(key, name, "ok", error, emptyList(), false) else null,
    )

    private fun thinking(key: String) = ChatItem.Thinking(key, "hmm")
    private fun reply(key: String) = ChatItem.Assistant(key, "ok", false)

    private fun shape(lines: List<ChatLine>) = lines.map { line ->
        when (line) {
            is ChatLine.Single -> line.key
            is ChatLine.ToolGroup -> line.steps.joinToString("+", "[", "]") { it.key }
        }
    }

    @Test
    fun thinking_counts_as_a_step_of_the_run() {
        assertEquals(listOf("[a+t+b]"), shape(chatLines(listOf(tool("a"), thinking("t"), tool("b")))))
    }

    @Test
    fun steps_without_a_tool_never_fold() {
        assertEquals(listOf("t1", "t2", "t3"), shape(chatLines(listOf(thinking("t1"), thinking("t2"), thinking("t3")))))
    }

    @Test
    fun a_reply_ends_the_run() {
        val lines = chatLines(listOf(tool("a"), tool("b"), reply("r"), tool("c"), tool("d"), tool("e")))
        assertEquals(listOf("a", "b", "r", "[c+d+e]"), shape(lines))
    }

    @Test
    fun a_running_tool_joins_the_run_and_makes_it_live() {
        val group = chatLines(listOf(tool("a"), tool("b"), tool("c", done = false))).single() as ChatLine.ToolGroup
        assertTrue(group.live)
        assertEquals("g:a", group.key)
        val done = chatLines(listOf(tool("a"), tool("b"), tool("c"))).single() as ChatLine.ToolGroup
        assertFalse(done.live)
    }

    @Test
    fun the_group_key_holds_while_the_run_grows() {
        val small = chatLines(listOf(tool("a"), tool("b"), tool("c", done = false))).single()
        val grown = chatLines(listOf(tool("a"), tool("b"), tool("c"), thinking("t"), tool("d", done = false))).single()
        assertEquals(small.key, grown.key)
    }

    @Test
    fun a_question_and_an_agent_call_stand_alone_and_split_the_run() {
        val items = listOf(tool("a"), tool("b"), tool("c"), tool("q", "ask"), tool("d"), tool("x", "task"), tool("e"), tool("f"), tool("g"))
        assertEquals(listOf("[a+b+c]", "q", "d", "x", "[e+f+g]"), shape(chatLines(items)))
    }

    @Test
    fun the_call_a_blocked_agent_waits_on_never_folds_whatever_its_tool() {
        val waiting = tool("c", "bash", done = false)
        val items = listOf(tool("a"), tool("b"), waiting)
        assertEquals(listOf("[a+b+c]"), shape(chatLines(items)))
        assertEquals(listOf("a", "b", "c"), shape(chatLines(items, pending = waiting.call)))
    }

    @Test
    fun a_finished_run_seen_only_finished_is_folded() {
        val group = chatLines(listOf(tool("a"), tool("b"), tool("c"))).single() as ChatLine.ToolGroup
        assertNull(runStart(group, open = false, held = false))
        assertEquals(0, runStart(group, open = true, held = false))
    }

    @Test
    fun a_live_run_shows_its_last_six_steps_and_stays_so_when_held() {
        val steps = (0 until 9).map { tool("s$it") }
        val live = chatLines(steps.dropLast(1) + tool("s8", done = false)).single() as ChatLine.ToolGroup
        assertEquals(9 - LIVE_TAIL, runStart(live, open = false, held = false))
        val finished = chatLines(steps).single() as ChatLine.ToolGroup
        assertEquals(9 - LIVE_TAIL, runStart(finished, open = false, held = true))
        assertEquals(0, runStart(finished, open = true, held = true))
        val short = chatLines(listOf(tool("a"), tool("b"), tool("c", done = false))).single() as ChatLine.ToolGroup
        assertEquals(0, runStart(short, open = false, held = false))
    }

    @Test
    fun failures_and_denials_are_counted_apart() {
        val denied = ChatItem.Tool(
            "d",
            TranscriptBlock.ToolCall("d", "bash", "{}", null, false),
            TranscriptBlock.ToolResult("d", "bash", "Tool call denied by user: bash", true, emptyList(), false),
        )
        val group = chatLines(listOf(tool("a", error = true), denied, tool("c"))).single() as ChatLine.ToolGroup
        assertEquals(1 to 1, group.failed to group.denied)
    }
}
