package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatModelTest {
    private val agent = TranscriptIdentity("w1:p1", "term_1", 42, "/h/.omp/agent/sessions/x/a.jsonl")

    private fun text(id: String, parent: String?, offset: Long, role: String, text: String) =
        TranscriptEntry(id, parent, offset, TranscriptKind.Message, role, listOf(TranscriptBlock.Text(text, false)))

    private fun page(vararg entries: TranscriptEntry, start: Long = 0, end: Long = 100, reset: Boolean = false, identity: TranscriptIdentity = agent) =
        TranscriptPage(identity, AgentStatus.Idle, reset, end, start, end, entries.toList())

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
    fun tool_results_pair_with_their_call_and_a_blocked_call_is_pending() {
        val call = TranscriptBlock.ToolCall("t1", "ask", "{}", "Asking", false)
        val entries = listOf(
            TranscriptEntry("a", null, 0, TranscriptKind.Message, "assistant", listOf(call)),
            TranscriptEntry("r", "a", 10, TranscriptKind.Message, "toolResult", listOf(TranscriptBlock.ToolResult("t1", "ask", "red", false, 0, false))),
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
    fun an_unclosed_code_fence_still_renders_as_code() {
        val blocks = markdownBlocks("Run:\n```sh\nls -la\n")
        assertEquals(listOf(MdBlock.Paragraph("Run:"), MdBlock.Code("sh", "ls -la\n")), blocks)
    }
}
