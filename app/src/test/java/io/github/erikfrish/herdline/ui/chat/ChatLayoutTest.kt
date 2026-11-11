package io.github.erikfrish.herdline.ui.chat

import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import io.github.erikfrish.herdline.core.TranscriptBlock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatLayoutTest {
    private fun tool(key: String, done: Boolean = true, error: Boolean = false) = ChatItem.Tool(
        key,
        TranscriptBlock.ToolCall(key, "read", "{}", null, false),
        if (done) TranscriptBlock.ToolResult(key, "read", "ok", error, 0, false) else null,
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
}
