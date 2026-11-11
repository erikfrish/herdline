package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptBlock
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which question a blocked agent's chat shows: joined fail-closed, read-only. */
class WaitingQuestionTest {
    private fun call(id: String, name: String = "ask", args: String = "{}", truncated: Boolean = false) =
        TranscriptBlock.ToolCall(id, name, args, null, truncated)

    private fun result(id: String, name: String = "read") = TranscriptBlock.ToolResult(id, name, "ok", false, emptyList(), false)

    private fun items(vararg blocks: TranscriptBlock): List<ChatItem> = chatItems(
        blocks.mapIndexed { index, block ->
            val role = if (block is TranscriptBlock.ToolResult) "toolResult" else "assistant"
            TranscriptEntry("e$index", if (index == 0) null else "e${index - 1}", index * 10L, index * 10L + 10, TranscriptKind.Message, role, listOf(block))
        },
    )

    @Test
    fun onlyTheOneUnansweredNewestCallIsJoined() {
        val ask = call("t2")
        assertNull("none waiting", pendingTool(items(call("t1", "read"), result("t1"))))
        assertEquals(ask, pendingTool(items(call("t1", "read"), result("t1"), ask)))
        // Two unanswered calls: either may be the open dialog.
        assertNull("two waiting", pendingTool(items(call("t1", "read"), ask)))
        // The one unanswered call is not the newest tool: the newest is answered, so the dialog is unknown.
        assertNull("stale call", pendingTool(items(ask, call("t3", "read"), result("t3"))))
    }

}
