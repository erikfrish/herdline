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
import org.junit.Assert.assertTrue
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
    fun another_session_in_the_pane_shows_only_that_session_under_a_divider() {
        val old = ChatHistory.of(page(text("1", null, 0, "user", "old turn"), reset = true))
        assertFalse(old.newSession)
        // The same session read again (a tail, a rewrite, a restart that resumed it): no divider.
        val resumed = old.merge(page(text("1", null, 0, "user", "old turn"), reset = true, identity = agent.copy(pid = 43, terminalId = "term_2")), appended = true)
        assertFalse(resumed.newSession)
        // /new in the same pane: the next reset names another session; only its turns are kept.
        val fresh = agent.copy(pid = 43, path = "/h/.omp/agent/sessions/x/b.jsonl")
        val switched = resumed.merge(page(text("9", null, 0, "user", "new turn"), reset = true, identity = fresh), appended = true)
        assertTrue(switched.newSession)
        assertEquals(listOf("u:new turn"), texts(switched.entries))
        // It stays marked while that session grows, pages back, or is re-read from its tail.
        val grown = switched
            .merge(page(text("10", "9", 100, "user", "next"), start = 100, end = 110, identity = fresh), appended = true)
            .merge(page(text("9", null, 0, "user", "new turn"), reset = true, identity = fresh), appended = true)
        assertTrue(grown.newSession)
        // Another agent in the pane is another session too.
        assertTrue(old.merge(page(reset = true, identity = agent.copy(agent = "claude", source = "herdr:claude")), appended = true).newSession)
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
    fun the_agents_own_server_decides_working_and_the_panes_blocked_stays() {
        val opencode = Agent("w1:p1", "w1", "t1", null, "opencode", null, AgentStatus.Idle, false)
        fun with(status: AgentStatus, running: Boolean?) = chatStatus(true, "w1:p1", opencode.copy(status = status), running)
        assertEquals(AgentStatus.Working, with(AgentStatus.Idle, running = true))
        assertEquals("a new turn after Done", AgentStatus.Working, with(AgentStatus.Done, running = true))
        assertEquals("its drain ended", AgentStatus.Idle, with(AgentStatus.Working, running = false))
        assertEquals(AgentStatus.Done, with(AgentStatus.Done, running = false))
        assertEquals("waiting on the user", AgentStatus.Blocked, with(AgentStatus.Blocked, running = true))
        assertEquals("no server answer: the pane's", AgentStatus.Working, with(AgentStatus.Working, running = null))
        assertEquals("never past a lost link", AgentStatus.Unknown, chatStatus(false, "w1:p1", opencode, running = true))
    }

    @Test
    fun an_unclosed_code_fence_still_renders_as_code() {
        val blocks = markdownBlocks("Run:\n```sh\nls -la\n")
        assertEquals(listOf(MdBlock.Paragraph("Run:"), MdBlock.Code("sh", "ls -la\n")), blocks)
    }

    @Test
    fun a_pty_send_is_sent_only_when_its_exact_session_gains_that_turn() {
        val sent = "ping ✓\nline two\nline three\nline four"
        val landed = text("u9", null, 100, "user", sent)
        // One new user turn holding all four lines past the history's end before the tap.
        assertTrue(ptyTurnLanded(page(landed, start = 100, end = 140), agent, before = 100, text = sent))
        // The same words already in the history before the tap are not this send, also next to a changed new turn.
        assertFalse(ptyTurnLanded(page(text("u1", null, 40, "user", sent), text("u9", null, 100, "user", "other")), agent, before = 100, text = sent))
        // Recorded with different line ends: not the submitted form, so not proven.
        assertFalse(ptyTurnLanded(page(text("u9", null, 100, "user", sent.replace("\n", "\r\n"))), agent, 100, sent))
        // Split across turns or echoed by the assistant: not landed.
        assertFalse(ptyTurnLanded(page(text("u9", null, 100, "user", "ping ✓"), text("u10", null, 110, "user", "line two\nline three\nline four")), agent, 100, sent))
        assertFalse(ptyTurnLanded(page(text("a9", null, 100, "assistant", sent)), agent, 100, sent))
        // Another instance or a history that started over proves nothing.
        assertFalse(ptyTurnLanded(page(landed, identity = agent.copy(pid = 43)), agent, 100, sent))
        assertFalse(ptyTurnLanded(page(landed, reset = true), agent, 100, sent))
    }

    @Test
    fun anOmpTurnShownInChatConfirmsTheTextItsEditorActuallySubmits() {
        // OMP's pinned editor folds paste line ends/NFC/tabs, then trims on submit.
        // The actual trailing-space/newline case left the composer held after Chat showed the turn.
        val cases = listOf(
            "Qa-inpane-a \n" to "Qa-inpane-a",
            "  Проверка\nвторая строка  " to "Проверка\nвторая строка",
            "\u00a0\uFEFFе\u0308\tтекст\r\nещё\uFEFF" to "ё   текст\nещё",
            "до\u0007 после" to "до после",
        )
        for ((typed, recorded) in cases) {
            val landed = text("u9", null, 100, "user", recorded)
            val read = page(landed, start = 100, end = 140)
            assertEquals(recorded, (chatItems(read.entries).single() as ChatItem.User).text)
            assertTrue(recorded, ptyTurnLanded(read, agent, 100, typed))
            assertFalse("older turn", ptyTurnLanded(read, agent, 101, typed))
            assertFalse("other instance", ptyTurnLanded(read.copy(identity = agent.copy(pid = 43)), agent, 100, typed))
            assertFalse("reset", ptyTurnLanded(read.copy(reset = true), agent, 100, typed))
            assertFalse("truncated text", ptyTurnLanded(read.copy(entries = listOf(landed.copy(blocks = listOf(TranscriptBlock.Text(recorded, true))))), agent, 100, typed))
        }
        assertFalse("interior spaces remain significant", ptyTurnLanded(page(text("u9", null, 100, "user", "one two")), agent, 100, "one  two"))
        assertFalse("no substring match", ptyTurnLanded(page(text("u9", null, 100, "user", "prefix text")), agent, 100, "text"))
        assertFalse("empty submitted form", ptyTurnLanded(page(text("u9", null, 100, "user", "")), agent, 100, " \n"))
        assertFalse("zero-width space is not trimmed", ptyTurnLanded(page(text("u9", null, 100, "user", "text")), agent, 100, "\u200btext"))
        val pi = agent.copy(agent = "pi", source = "herdr:pi")
        assertFalse("no unpinned trim for other programs", ptyTurnLanded(page(text("u9", null, 100, "user", "text"), identity = pi), pi, 100, " text "))
    }
}
