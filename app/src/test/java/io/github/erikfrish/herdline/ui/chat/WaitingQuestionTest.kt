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

    private fun result(id: String, name: String = "read") = TranscriptBlock.ToolResult(id, name, "ok", false, 0, false)

    private fun items(vararg blocks: TranscriptBlock): List<ChatItem> = chatItems(
        blocks.mapIndexed { index, block ->
            val role = if (block is TranscriptBlock.ToolResult) "toolResult" else "assistant"
            TranscriptEntry("e$index", if (index == 0) null else "e${index - 1}", index * 10L, TranscriptKind.Message, role, listOf(block))
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

    @Test
    fun oneCompleteAskShowsItsQuestionAndChoiceLabels() {
        val args = """
            {"questions": [{"id": "scope", "header": "Scope", "question": "Include ORR-2051?",
              "options": [{"label": "Leave it out", "description": "as staged"}, {"label": "Include it"}], "recommended": 1}]}
        """.trimIndent()
        assertEquals(WaitingQuestion("Include ORR-2051?", listOf("Leave it out", "Include it")), waitingQuestion(call("t1", args = args)))
        // The single-question shape.
        assertEquals(
            WaitingQuestion("Proceed?", listOf("Yes", "No")),
            waitingQuestion(call("t1", args = """{"question": " Proceed? ", "options": ["Yes", {"label": "No"}, ""]}""")),
        )
        assertEquals(WaitingQuestion("Name it?", emptyList()), waitingQuestion(call("t1", args = """{"question": "Name it?"}""")))
    }

    @Test
    fun anythingUncertainShowsNoQuestion() {
        val two = """{"questions": [{"question": "A?"}, {"question": "B?"}]}"""
        val cut = """{"questions": [{"question": "A?", "options": [{"label": "x"""
        assertNull("several questions", waitingQuestion(call("t1", args = two)))
        assertNull("cut arguments", waitingQuestion(call("t1", args = cut)))
        assertNull("marked truncated", waitingQuestion(call("t1", args = """{"question": "A?"}""", truncated = true)))
        assertNull("another tool", waitingQuestion(call("t1", name = "bash", args = """{"question": "A?"}""")))
        assertNull("blank question", waitingQuestion(call("t1", args = """{"question": "  "}""")))
    }
}
