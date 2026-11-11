package io.github.erikfrish.herdline.ui.chat

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNotNull

class ChatLayoutTest {
    private fun tool(key: String, done: Boolean = true, error: Boolean = false) = ChatItem.Tool(
        key,
        TranscriptBlock.ToolCall(key, "read", "{}", null, false),
        if (done) TranscriptBlock.ToolResult(key, "read", "ok", error, emptyList(), false) else null,
    )

    private fun user(key: String) = ChatItem.User(key, "hi")
    private fun reply(key: String) = ChatItem.Assistant(key, "ok", false)

    @Test
    fun three_finished_tools_fold_into_one_group_that_keeps_every_tool() {
        val tools = listOf(tool("a:0"), tool("a:1"), tool("a:2"))
        val lines = chatLines(listOf(user("u")) + tools)
        assertEquals(2, lines.size)
        val group = lines[1] as ChatLine.ToolGroup
        assertEquals("g:a:0", group.key)
        assertEquals(tools.map { it.key }, group.tools.map { it.key })
    }

    @Test
    fun two_finished_tools_stay_single_rows() {
        val lines = chatLines(listOf(tool("a"), tool("b")))
        assertTrue(lines.all { it is ChatLine.Single })
        assertEquals(Gap.Tool, lines[1].gap)
    }

    @Test
    fun a_group_with_a_failure_counts_it() {
        val group = chatLines(listOf(tool("a"), tool("b", error = true), tool("c"))).single() as ChatLine.ToolGroup
        assertEquals(1, group.failed)
    }

    @Test
    fun a_user_message_starts_a_turn_and_only_the_first_reply_row_is_labelled() {
        val lines = chatLines(listOf(reply("r0"), user("u"), reply("r1"), reply("r2"), tool("t")))
        assertEquals(listOf(Gap.None, Gap.Turn, Gap.Turn, Gap.Block, Gap.Block), lines.map { it.gap })
        assertEquals(listOf(true, false, true, false, false), lines.map { (it as ChatLine.Single).labelled })
    }

    @Test
    fun only_the_first_labelled_line_names_the_tab_and_older_history_takes_it() {
        val recent = listOf(user("u1"), tool("a"), tool("b"), tool("c"), user("u2"), reply("r2"))
        assertEquals("g:a", chatLines(recent).titledKey)
        val older = listOf(user("u0"), reply("r0"))
        assertEquals("r0", chatLines(older + recent).titledKey)
        assertEquals(null, chatLines(listOf(user("u"))).titledKey)
    }

    @Test
    fun naming_the_tab_once_leaves_list_keys_anchors_and_content_unchanged() {
        val recent = listOf(user("u1"), tool("a"), tool("b"), tool("c"), user("u2"), reply("r2"))
        val lines = chatLines(recent)
        assertEquals(listOf("u1", "g:a", "u2", "r2"), lines.map { it.key })
        assertEquals(listOf("u1", "a", "u2", "r2"), lines.map { it.anchorKey })
        // Load older moves the title, never a key, an anchor or an item.
        val loaded = chatLines(listOf(user("u0"), reply("r0")) + recent)
        assertEquals(listOf("u0", "r0", "u1", "g:a", "u2", "r2"), loaded.map { it.key })
        assertEquals(listOf("u0", "r0", "u1", "a", "u2", "r2"), loaded.map { it.anchorKey })
        assertEquals(listOf(2, 3, 4, 5), listOf("u1", "b", "u2", "r2").map(loaded::indexOfItem))
        val shown = loaded.flatMap { line ->
            when (line) {
                is ChatLine.Single -> listOf(line.item)
                is ChatLine.ToolGroup -> line.steps
            }
        }
        assertEquals(listOf(user("u0"), reply("r0")) + recent, shown)
    }

    @Test
    fun an_anchor_inside_a_group_resolves_to_the_group_line() {
        val lines = chatLines(listOf(user("u"), tool("a"), tool("b"), tool("c")))
        assertEquals(1, lines.indexOfItem("b"))
        assertEquals(-1, lines.indexOfItem("missing"))
    }

    @Test
    fun cap_lines_reports_how_many_were_left_out() {
        assertEquals("1\n2" to 2, capLines("1\n2\n3\n4\n", 2))
        assertEquals("1\n2" to 0, capLines("1\n2", 2))
    }

    @Test
    fun a_table_needs_its_separator_and_short_rows_are_padded() {
        assertEquals(listOf(MdBlock.Paragraph("| a | b |\n| 1 | 2 |")), markdownBlocks("| a | b |\n| 1 | 2 |"))
        assertEquals(
            listOf(MdBlock.Table(listOf("a", "b"), listOf(listOf("1", "")))),
            markdownBlocks("| a | b |\n|---|:---:|\n| 1 |"),
        )
    }

    @Test
    fun only_web_links_open() {
        assertTrue(openableLink("https://herdr.dev"))
        assertTrue(openableLink("HTTP://herdr.dev"))
        assertFalse(openableLink("javascript:alert(1)"))
        assertFalse(openableLink("file:///etc/passwd"))
        assertFalse(openableLink("intent://scan#Intent;end"))
    }

    @Test
    fun a_link_to_another_scheme_keeps_only_its_label() {
        val text = inlineMarkdown("see [x](javascript:alert) and [y](https://herdr.dev)", SpanStyle(), TextLinkStyles())
        assertEquals("see x and y", text.text)
        val links = text.getLinkAnnotations(0, text.length)
        assertEquals(listOf("https://herdr.dev"), links.map { (it.item as LinkAnnotation.Url).url })
        assertEquals("y", text.text.substring(links.single().start, links.single().end))
    }

    @Test
    fun inline_code_is_marked_for_its_chip() {
        val text = inlineMarkdown("run `ls -l` then `cd`", SpanStyle())
        assertEquals("run ls -l then cd", text.text)
        assertEquals(
            listOf("ls -l", "cd"),
            text.getStringAnnotations(INLINE_CODE, 0, text.length).map { text.text.substring(it.start, it.end) },
        )
    }

    @Test
    fun a_code_block_collapses_only_past_thirty_lines_to_twelve() {
        val thirty = (1..30).joinToString("\n") + "\n"
        val thirtyOne = (1..31).joinToString("\n")
        assertFalse(codeStartsCollapsed(thirty))
        assertTrue(codeStartsCollapsed(thirtyOne))
        val (shown, hidden) = capLines(thirtyOne, CODE_COLLAPSED_LINES)
        assertEquals((1..12).joinToString("\n"), shown)
        assertEquals(19, hidden)
    }

    private fun group(lines: List<ChatLine>) = lines.filterIsInstance<ChatLine.ToolGroup>().single()

    @Test
    fun a_task_whose_exact_children_run_stays_visible_and_never_folds_away() {
        val task = tool("a:2").copy(subagents = listOf(Subagent("c", status = SubagentStatus.Running)))
        // The task's own result has arrived, but one exact child still runs:
        // the run keeps drawing its steps and is never replaced by the fold.
        val lines = chatLines(listOf(user("u"), tool("a:0"), tool("a:1"), task, reply("r")))
        val group = group(lines)
        assertTrue(group.live)
        assertNotNull(runStart(group, open = false, held = false))
        assertTrue(lines.completedRuns().isEmpty())
        // Once the children settle, the run folds like any other.
        val done = task.copy(subagents = listOf(Subagent("c", status = SubagentStatus.Completed)))
        assertTrue(chatLines(listOf(user("u"), tool("a:0"), tool("a:1"), done, reply("r"))).completedRuns().isNotEmpty())
    }

    @Test
    fun a_run_becomes_a_completed_fold_only_when_its_reply_arrives_and_keeps_its_key_as_history_loads() {
        val live = listOf(user("u"), tool("a:0"), tool("a:1"), tool("a:2", done = false))
        assertTrue(chatLines(live).completedRuns().isEmpty())
        val finished = live.dropLast(1) + tool("a:2")
        // Finished but not yet answered: still a run, not the fold.
        assertTrue(chatLines(finished).completedRuns().isEmpty())
        val answered = finished + reply("r")
        assertEquals(setOf("g:a:0"), chatLines(answered).completedRuns().keys)
        val withOlder = listOf(user("u0"), tool("o:0"), tool("o:1"), tool("o:2"), reply("r0")) + answered
        assertEquals(setOf("g:o:0", "g:a:0"), chatLines(withOlder).completedRuns().keys)
    }

    @Test
    fun a_run_the_next_turn_follows_without_a_reply_is_not_completed() {
        val lines = chatLines(listOf(user("u"), tool("a:0"), tool("a:1"), tool("a:2"), user("u2"), reply("r")))
        assertTrue(lines.completedRuns().isEmpty())
    }

    @Test
    fun the_fold_counts_tools_thoughts_and_failures_and_names_each_tool_once_in_order() {
        val steps = listOf(
            ChatItem.Tool("s:0", TranscriptBlock.ToolCall("s:0", "bash", "{}", null, false), TranscriptBlock.ToolResult("s:0", "bash", "no", true, emptyList(), false)),
            ChatItem.Thinking("s:1", "hmm"),
            ChatItem.Tool("s:2", TranscriptBlock.ToolCall("s:2", "read", "{}", null, false), TranscriptBlock.ToolResult("s:2", "read", "ok", false, emptyList(), false)),
            ChatItem.Tool("s:3", TranscriptBlock.ToolCall("s:3", "bash", "{}", null, false), TranscriptBlock.ToolResult("s:3", "bash", "ok", false, emptyList(), false)),
        )
        val lines = chatLines(steps + reply("r"))
        val fold = runFold(group(lines), lines.completedRuns().getValue("g:s:0"))
        assertEquals(RunFold(tools = 3, thoughts = 1, failed = 1, denied = 0, names = listOf("bash", "read"), seconds = null), fold)
    }

    private fun entry(id: String, parent: String?, offset: Long, role: String, at: Long?, blocks: List<TranscriptBlock>) =
        TranscriptEntry(id, parent, offset, offset + 1, TranscriptKind.Message, role, blocks, timestamp = at)

    /** The fold's seconds for a run whose call entry is dated [start] and whose answer [end]. */
    private fun timed(start: Long?, end: Long?): Long? {
        val calls = (0..2).map { TranscriptBlock.ToolCall("t$it", "read", "{}", null, false) }
        val entries = listOf(
            entry("u", null, 0, "user", 0, listOf(TranscriptBlock.Text("go", false))),
            entry("a", "u", 1, "assistant", start, calls),
            entry("t", "a", 2, "toolResult", 99_999_999, calls.map { TranscriptBlock.ToolResult(it.callId, "read", "ok", false, emptyList(), false) }),
            entry("r", "t", 3, "assistant", end, listOf(TranscriptBlock.Text("done", false))),
        )
        val lines = chatLines(chatItems(entries))
        return runFold(group(lines), lines.completedRuns().values.single()).seconds
    }

    @Test
    fun the_fold_times_only_from_the_run_s_first_entry_to_its_answer_both_as_recorded() {
        assertEquals(42L, timed(1_000, 43_000))
        assertEquals(null, timed(null, 43_000))
        assertEquals(null, timed(1_000, null))
        assertEquals(null, timed(43_000, 1_000))
        assertEquals(null, timed(1_000, 1_999))
    }

    @Test
    fun a_held_run_folds_on_a_new_user_turn_only_while_following_and_only_once_completed() {
        val before = listOf(user("u"), tool("a:0"), tool("a:1"), tool("a:2"), reply("r"))
        val lines = chatLines(before + user("u2") + listOf(tool("b:0"), tool("b:1"), tool("b:2", done = false)))
        val held = setOf("g:a:0", "g:b:0")
        assertEquals(setOf("g:a:0"), releasedRuns(lines, held, follow = true, seenUser = "u", newestUser = "u2"))
        // Scrolled up: nothing folds under the reader.
        assertTrue(releasedRuns(lines, held, follow = false, seenUser = "u", newestUser = "u2").isEmpty())
        // The first load, or a reread with no new turn, is not a new turn.
        assertTrue(releasedRuns(lines, held, follow = true, seenUser = null, newestUser = "u2").isEmpty())
        assertTrue(releasedRuns(lines, held, follow = true, seenUser = "u2", newestUser = "u2").isEmpty())
    }

    @Test
    fun output_links_are_web_addresses_without_the_sentence_s_closing_punctuation() {
        val text = "see https://ex.com/a. and (http://x.io), ftp://no, https:// alone"
        assertEquals(listOf("https://ex.com/a", "http://x.io"), outputLinks(text).map { text.substring(it) })
    }

    @Test
    fun a_near_tap_opens_the_closest_link_in_reach_and_none_outside_it_or_its_width() {
        val boxes = listOf(floatArrayOf(0f, 0f, 100f, 17f), floatArrayOf(0f, 17f, 100f, 34f))
        assertEquals(1, nearLink(50f, 30f, boxes, reach = 48f))
        assertEquals(0, nearLink(50f, -10f, boxes, reach = 48f))
        assertEquals(null, nearLink(50f, 60f, boxes, reach = 48f))
        assertEquals(null, nearLink(150f, 8f, boxes, reach = 48f))
    }
}
