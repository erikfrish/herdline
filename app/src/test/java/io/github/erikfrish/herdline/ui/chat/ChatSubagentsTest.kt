package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptSubagent
import io.github.erikfrish.herdline.core.TranscriptSubagentEnd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatSubagentsTest {

    private fun child(
        id: String = "a1",
        agent: String? = null,
        type: String? = null,
        status: String,
        task: String? = null,
        stage: String? = null,
        output: String? = null,
        error: String? = null,
        durationMs: Long? = null,
        requests: Int? = null,
        exitCode: Int? = null,
    ) = subagentOf(TranscriptSubagent(id, agent, type, status, task, stage, output, error, durationMs, requests, exitCode))

    private fun end(id: String, status: String, durationMs: Long? = null) = completionsOf(listOf(TranscriptSubagentEnd(id, status, durationMs)))

    private val running = child(agent = "explorer", type = "researcher", status = "running", task = "Find the parser", stage = "reading parser.ts", durationMs = 5210, requests = 3)!!

    @Test
    fun theWireStatusIsTheStateAndNothingElseIsGuessed() {
        for ((word, known) in listOf("pending" to SubagentStatus.Pending, "running" to SubagentStatus.Running, "completed" to SubagentStatus.Completed, "failed" to SubagentStatus.Failed, "aborted" to SubagentStatus.Aborted, "cancelled" to SubagentStatus.Cancelled)) {
            assertEquals(known, child(status = word)?.status)
        }
        // A status this app has no word for is not read into any of its states: no row at all.
        assertNull(child(status = "busy"))
    }

    @Test
    fun namesComeFromTheWireFieldsNeverFromRawJsonLines() {
        assertEquals("explorer", child(agent = "explorer", status = "running", task = "{\"cmd\":\"ls\"}")!!.displayName)
        assertEquals("Find the parser", child(status = "running", task = "Find the parser\nclean up")!!.displayName)
        assertEquals("researcher", child(type = "researcher", status = "running")!!.displayName)
        assertEquals("a1", child(status = "running")!!.displayName)
        // A label never outgrows the head row it prints on.
        assertEquals(SUBAGENT_LABEL_CHARS, child(agent = "x".repeat(SUBAGENT_LABEL_CHARS * 4), status = "running")!!.displayName.length)
    }

    @Test
    fun eachChildKeepsOneRowThroughSnapshotsAndASettledEnd() {
        val first = listOf(running)
        // A later snapshot restates the child under the same id: still one row, new state.
        val merged = mergeSubagents(first, listOf(child(status = "failed", error = "boom")!!))
        assertEquals(listOf("a1"), merged.map { it.id })
        assertEquals(SubagentStatus.Failed, merged.single().status)
        // The settled end for that id merges into the same row, never a second one.
        val final = mergeSubagents(merged, emptyList(), end("a1", "failed", durationMs = 8000))
        assertEquals(listOf("a1"), final.map { it.id })
        assertEquals(SubagentStatus.Failed, final.single().status)
        assertEquals(8000L, final.single().durationMs)
    }

    @Test
    fun aSettledEndOnlyClosesTheChildItNamesByExactId() {
        val rows = listOf(running, running.copy(id = "a2", name = "reviewer"))
        // The end for the one child closes it; the sibling keeps running untouched.
        assertEquals(SubagentStatus.Running, mergeSubagents(rows, emptyList(), end("a1", "completed")).single { it.id == "a2" }.status)
        assertEquals(SubagentStatus.Completed, mergeSubagents(rows, emptyList(), end("a1", "completed")).single { it.id == "a1" }.status)
        // An end for an id nothing carries settles nothing at all.
        assertEquals(SubagentStatus.Running, mergeSubagents(listOf(running), emptyList(), end("zz", "cancelled")).single().status)
    }

    @Test
    fun aPendingOrRunningEndSettlesNothingEver() {
        // Only terminal statuses close a row: a `pending` end changes nothing,
        // however the model reached it — parser or manual.
        assertEquals(emptyList<SubagentCompletion>(), completionsOf(listOf(TranscriptSubagentEnd("a1", "pending"))))
        assertEquals(emptyList<SubagentCompletion>(), completionsOf(listOf(TranscriptSubagentEnd("a1", "running"))))
        assertEquals(SubagentStatus.Running, mergeSubagents(listOf(running), emptyList(), end("a1", "pending")).single().status)
    }
    @Test
    fun aSettledRowSticksAndMergesNeverDropWhatAnEarlierSnapshotListed() {
        val held = listOf(running, running.copy(id = "a2", name = "reviewer", status = SubagentStatus.Pending))
        // A newer snapshot dropping a sibling is silence about it, never a removal.
        val narrowed = mergeSubagents(held, listOf(child(status = "running", stage = "parsing")!!))
        assertEquals(listOf("a1", "a2"), narrowed.map { it.id })
        // The end settles a1; a slower snapshot claiming it still runs cannot reopen it.
        val settled = mergeSubagents(narrowed, emptyList(), end("a1", "completed", durationMs = 12000))
        assertEquals(SubagentStatus.Completed, settled.first().status)
        assertEquals(SubagentStatus.Completed, mergeSubagents(settled, listOf(child(status = "running")!!)).first().status)
    }

    @Test
    fun anEndSettlesWhatIsNotListedYetOnlyWhenTheCallClaimedThatId() {
        // The settled end can be recorded before any snapshot lists the child.
        val early = mergeSubagents(emptyList(), emptyList(), end("a1", "completed", durationMs = 12000), claimed = listOf("a1"))
        assertEquals(listOf(SubagentStatus.Completed), early.map { it.status })
        // Without the claim nothing is drawn from an id no snapshot named.
        assertEquals(emptyList<Subagent>(), mergeSubagents(emptyList(), emptyList(), end("a1", "completed")))
        // The snapshot that names it becomes its one row, keeping the settled state.
        val listed = mergeSubagents(early, listOf(running))
        assertEquals(listOf("a1"), listed.map { it.id })
        assertEquals(listOf(SubagentStatus.Completed), listed.map { it.status })
    }

    @Test
    fun rowsReadTheirStateOffTheSheetNotOffTheTransport() {
        assertEquals(SubagentGraphic.Failed, child(status = "failed")!!.graphic)
        assertEquals(SubagentGraphic.Stopped, child(status = "aborted")!!.graphic)
        assertEquals(SubagentGraphic.Done, child(status = "completed")!!.graphic)
        assertEquals(true, running.alive)
        // The output block shows the child's output, its failing reason when there is one instead.
        assertEquals("boots and all", child(status = "completed", output = "boots and all")!!.outputText)
        assertEquals("exploded", child(status = "failed", error = "exploded")!!.outputText)
    }
}
