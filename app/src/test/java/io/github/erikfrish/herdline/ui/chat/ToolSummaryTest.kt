package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptBlock
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSummaryTest {
    private fun summary(name: String, args: String) = ChatItem.Tool("t", TranscriptBlock.ToolCall("t", name, args, null, false), null).summary()

    @Test
    fun a_json_call_names_its_command_not_its_braces() {
        assertEquals("ls", summary("run_terminal_cmd", """{"command":"ls"}"""))
        // The command outranks whatever else the call carries, and only its first line shows.
        assertEquals("make test", summary("bash", """{"timeout":30,"description":"Run tests","command":"make test\nmake lint"}"""))
        assertEquals("src/a.kt", summary("read", """{"limit":20,"file_path":"src/a.kt"}"""))
        // No known key: the call's only string.
        assertEquals("pending", summary("todo", """{"n":2,"state":"pending"}"""))
    }

    @Test
    fun an_array_names_its_first_string_and_cut_json_says_nothing() {
        assertEquals("a.txt", summary("rm", """[3, "a.txt", "b.txt"]"""))
        assertEquals("", summary("rm", """["a.txt", "b"""))
        assertEquals("", summary("bash", """{"command":"ls -la /very/long"""))
        assertEquals("", summary("noop", "{}"))
    }

    @Test
    fun plain_text_arguments_keep_their_first_line() {
        assertEquals("ls -la", summary("bash", "\n  ls -la  \nsecond"))
    }
}
