package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.ChatFormAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ToolStatusTest {
    private fun result(text: String, error: Boolean = true, name: String = "bash", truncated: Boolean = false) =
        TranscriptBlock.ToolResult("t", name, text, error, emptyList(), truncated)

    private fun state(text: String?, error: Boolean = true) = toolState(
        ChatItem.Tool("t", TranscriptBlock.ToolCall("t", "bash", "{}", null, false), text?.let { result(it, error) }),
    )

    @Test
    fun omp_refusals_are_denied() {
        listOf(
            "Tool call denied by user: bash",
            "Tool call rejected by user (preference)",
            "Tool \"bash\" is blocked by tool policy.\nReason: read only",
            "Tool \"edit\" is blocked by user policy.\nTo allow: remove \"tools.approval.edit: deny\" from config.",
            "Tool execution was blocked by an extension",
            "Tool execution was blocked by a hook",
        ).forEach { assertEquals(it, ToolState.Denied, state(it)) }
    }

    @Test
    fun an_interrupt_a_validation_error_or_an_extension_reason_is_failed_not_denied() {
        listOf(
            "Command aborted",
            "Operation aborted",
            "Command timed out after 300 seconds",
            "Edit rejected for app/x.kt: stale tag",
            "Denied at the desk",
            "Tool execution was blocked by an extension: shown later",
            "ls\nTool call denied by user: bash",
        ).forEach { assertEquals(it, ToolState.Failed, state(it)) }
    }

    @Test
    fun a_refusal_phrase_on_a_successful_result_is_done() {
        assertEquals(ToolState.Done, state("Tool call denied by user: bash", error = false))
        assertEquals(ToolState.Running, state(null))
    }

    @Test
    fun the_exit_code_is_read_from_omps_last_line_only() {
        assertEquals(2, exitCode(result("boom\n\nCommand exited with code 2")))
        assertEquals(1, exitCode(result("Command exited with code 1\n")))
        assertEquals(1, exitCode(result("x", name = "eval").copy(text = "x\n\nCommand exited with code 1")))
        assertNull("not last", exitCode(result("Command exited with code 3\nmore")))
        assertNull("no line is no code, not 0", exitCode(result("fine", error = false)))
        assertNull("cut for chat", exitCode(result("Command exited with code 4", truncated = true)))
        assertNull("other tools quote it", exitCode(result("Command exited with code 5", name = "read")))
    }

    @Test
    fun the_output_tail_keeps_the_last_lines_and_counts_the_rest() {
        assertEquals("3\n4" to 2, outputTail("1\n2\n3\n4\n", 2))
        assertEquals("1\n2" to 0, outputTail("1\n2", 2))
    }

    @Test
    fun askSummaryNeverShowsRawFormArgumentsAndUsesTheRecordedQuestion() {
        val call = TranscriptBlock.ToolCall("t", "ask", "{\"html\":\"<form>private form</form>\"}", null, false)
        assertEquals("", ChatItem.Tool("t", call, null).summary())
        val answered = result("answer", error = false, name = "ask").copy(answers = listOf(ChatFormAnswer("target", "Deploy where?", "Stage")))
        assertEquals("Deploy where?", ChatItem.Tool("t", call, answered).summary())
        assertEquals("Review destination", ChatItem.Tool("t", call.copy(intent = "Review destination"), answered).summary())
    }
}
