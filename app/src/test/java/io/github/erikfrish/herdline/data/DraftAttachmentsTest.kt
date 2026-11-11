package io.github.erikfrish.herdline.data

import io.github.erikfrish.herdline.core.Attachment
import io.github.erikfrish.herdline.core.AttachmentKind
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.ui.session.MoveOutcome
import io.github.erikfrish.herdline.ui.session.MoveResolution
import io.github.erikfrish.herdline.ui.session.moveDraft
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftAttachmentsTest {
    private val alice = TranscriptIdentity("w1:p1", "term_1", 4100, "/h/.omp/agent/sessions/w/a.jsonl")
    private val target = Connection.ComposerTarget("n", "s", "boot-1", "w1:p1", "omp", alice)
    private fun staged(on: Connection.ComposerTarget = target) =
        Attachment(AttachmentKind.IMAGE, "shot.png", "/tmp/herdline/x/shot.png", 1234, "ab".repeat(32), on)
    private val picked = DraftAttachment("a1", "content://media/1", AttachmentKind.IMAGE, "shot.png", 1234)
    private val ready = picked.copy(staged = staged())
    private val draft = Draft(boot = "boot-1", agent = "omp", instance = alice, attachments = listOf(ready))

    @Test
    fun attachmentsComeBackFromDiskWithTheirHostCopyStillBoundToItsTarget() {
        val failed = DraftAttachment("a2", "content://docs/2", AttachmentKind.FILE, "notes.pdf", null, failure = "too_large")
        val stored = draft.copy(text = "look", attachments = listOf(ready, failed))

        val back = Draft.fromJson(JSONObject(stored.toJson().toString()))

        assertEquals(stored.attachments, back.attachments)
        assertEquals(AttachmentsState.Failed, attachmentsState(back.attachments, emptySet(), target))
        assertEquals(AttachmentsState.Ready, attachmentsState(back.attachments.take(1), emptySet(), target))
    }

    @Test
    fun anAttachmentThisBuildCannotReadDropsOutAndTheRestStay() {
        val json = draft.toJson()
        json.getJSONArray("attachments").put(JSONObject().put("id", "x").put("uri", "content://x").put("kind", "video").put("name", "v"))

        assertEquals(listOf(ready), Draft.fromJson(JSONObject(json.toString())).attachments)
    }

    @Test
    fun aDraftOfFilesAloneIsKeptDeliverableAndKeepsItsBinding() {
        val store = DraftStore(MemoryPrefs())
        val key = DraftKey("n", "s", "w1:p1")
        store.put(key, draft)

        assertEquals(draft, store.get(key))
        assertEquals(listOf(key), store.withText("n"))
        assertTrue(draft.mayDeliver(Binding.Bound))
        // Not blank: a new run in the pane asks for confirmation instead of rebinding silently.
        assertEquals(Binding.BootChanged, draft.binding("boot-2", "omp", InstanceRead.Unread))
        assertNull(draft.followed("boot-2", "omp", InstanceRead.Read(alice)))
        // Emptied of text and files, it is removed like any empty draft.
        store.put(key, Draft())
        assertNull(store.get(key))
    }

    @Test
    fun theWorstAttachmentHoldsTheWholeDraftBack() {
        val uploading = picked.copy(id = "u")
        val failed = picked.copy(id = "f", failure = "mismatch")
        val fresh = picked.copy(id = "p")

        assertEquals(AttachmentsState.None, attachmentsState(emptyList(), emptySet(), target))
        assertEquals(AttachmentsState.Uploading, attachmentsState(listOf(ready, failed, uploading), setOf("u"), target))
        assertEquals(AttachmentsState.Failed, attachmentsState(listOf(ready, fresh, failed), emptySet(), target))
        assertEquals(AttachmentsState.NotUploaded, attachmentsState(listOf(ready, fresh), emptySet(), target))
        assertEquals(AttachmentsState.Ready, attachmentsState(listOf(ready), emptySet(), target))
        assertEquals(AttachmentsState.OtherTarget, attachmentsState(listOf(ready), emptySet(), null))
    }

    @Test
    fun aCopyStagedBeforeTheAgentRestartedNoLongerCountsAsUploaded() {
        val restarted = alice.copy(pid = 4200, path = "/h/.omp/agent/sessions/w/b.jsonl")
        // Rebind confirms the new instance; the old host copy is for the old one.
        val rebound = draft.rebound("boot-1", "omp", restarted)
        val now = target.copy(instance = restarted)

        assertEquals(AttachmentsState.OtherTarget, attachmentsState(rebound.attachments, emptySet(), now))
        assertEquals(AttachmentsState.OtherTarget, attachmentsState(rebound.attachments, emptySet(), target.copy(boot = "boot-2")))
        assertEquals(AttachmentsState.Ready, attachmentsState(listOf(picked.copy(staged = staged(now))), emptySet(), now))
    }

    @Test
    fun movedFilesGoAlongToBeUploadedAgainAndLeaveTheSource() {
        val store = DraftStore(MemoryPrefs())
        val from = DraftKey("n", "s", "w1:p1")
        val to = DraftKey("n", "s", "w1:p2")
        store.put(from, draft)

        val outcome = moveDraft(store, from, draft, sourceBusy = false, to, "boot-1", "omp", MoveResolution.Ask, now = 9)

        assertTrue(outcome is MoveOutcome.Moved)
        assertEquals(listOf(picked), store.get(to)?.attachments)
        assertEquals(AttachmentsState.NotUploaded, attachmentsState(store.get(to)!!.attachments, emptySet(), target.copy(pane = to.pane)))
        assertNull(store.get(from))
    }

    @Test
    fun filesOnTheTargetPaneAreNeverOverwrittenByAMove() {
        val store = DraftStore(MemoryPrefs())
        val from = DraftKey("n", "s", "w1:p1")
        val to = DraftKey("n", "s", "w1:p2")
        val theirs = Draft(boot = "boot-1", agent = "omp", attachments = listOf(picked.copy(id = "theirs")))
        store.put(to, theirs)

        assertEquals(MoveOutcome.Conflict, moveDraft(store, from, Draft(text = "mine"), false, to, "boot-1", "omp", MoveResolution.Ask, 9))
        assertEquals(theirs, store.get(to))
        moveDraft(store, from, Draft(text = "mine", attachments = listOf(picked)), false, to, "boot-1", "omp", MoveResolution.Append, 9)
        assertEquals(listOf("theirs", "a1"), store.get(to)?.attachments?.map { it.id })
        assertFalse(store.get(to)!!.blank)
    }
}
