package io.github.erikfrish.herdline.data

import io.github.erikfrish.herdline.core.AttachmentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoWithdrawTest {
    private fun photo(id: String) = DraftAttachment(id, "content://p/$id", AttachmentKind.IMAGE, "$id.jpg", 10)

    @Test
    fun aWithdrawnSendGetsItsFilesBackOnceAndAcrossARestart() {
        val sent = Draft(text = "").queuedWith("r1", "look", listOf(photo("a"), photo("b")))
        val read = Draft.fromJson(JSONObject(sent.toJson().toString()))
        assertEquals(sent.queued, read.queued)

        val back = read.withdrawn("r1", "")
        assertEquals("look", back.text)
        assertEquals(listOf("a", "b"), back.attachments.map { it.id })
        assertEquals(emptyMap<String, QueuedSend>(), back.queued)
        // A file already back in the draft is not doubled; a second withdrawal finds nothing more.
        assertEquals(listOf("a", "b"), back.withdrawn("r1", "").attachments.map { it.id })
    }

    @Test
    fun eachWithdrawnRequestGetsItsOwnSentTextNeverTheQueuesPlaceholder() {
        // QA m43 C3: the queue lists a photo-only steer as "[Image]"; that text was never sent.
        // A user who really typed "[Image]" as a caption gets exactly that back.
        val sent = Draft().queuedWith("r1", "", listOf(photo("p"))).queuedWith("r2", "[Image]", listOf(photo("q")))
        val read = Draft.fromJson(JSONObject(sent.toJson().toString()))
        val first = read.withdrawn("r1", "")
        assertEquals("", first.text)
        assertEquals(listOf("p"), first.attachments.map { it.id })
        assertEquals("[Image]", read.withdrawn("r2", "").text)
    }

    @Test
    fun textTypedMeanwhileStaysFirstAndAnUnrecordedSendGuessesNoText() {
        val sent = Draft().queuedWith("r1", "fix it", emptyList())
        assertEquals("typed meanwhile\n\nfix it", sent.withdrawn("r1", "typed meanwhile").text)
        assertEquals("typed meanwhile", Draft().queuedWith("r1", "", listOf(photo("p"))).withdrawn("r1", "typed meanwhile").text)
        // A record written before 10064 has files but no text: unknown, so the editor keeps only what was typed.
        val old = JSONObject(Draft().toJson().toString()).put("queuedFiles", org.json.JSONArray().put(JSONObject().put("id", "r1").put("files", org.json.JSONArray().put(photo("p").toJson()))))
        val back = Draft.fromJson(old)
        assertEquals(null, back.queued.getValue("r1").text)
        assertEquals("", back.withdrawn("r1", "").text)
        assertEquals(listOf("p"), back.withdrawn("r1", "").attachments.map { it.id })
    }

    @Test
    fun anotherSendsRecordStaysAndOnlyTheNewestAreKept() {
        val two = Draft().queuedWith("r1", "", listOf(photo("a"))).queuedWith("r2", "", listOf(photo("b")))
        assertEquals(listOf("b"), two.withdrawn("r2", "").attachments.map { it.id })
        assertEquals(setOf("r1"), two.withdrawn("r2", "").queued.keys)

        val many = (1..QUEUED_FILES_KEPT + 2).fold(Draft()) { d, i -> d.queuedWith("r$i", "t$i", listOf(photo("p$i"))) }
        assertEquals(QUEUED_FILES_KEPT, many.queued.size)
        assertEquals("r3", many.queued.keys.first())
    }

    @Test
    fun aWithdrawWithNoAnswerThenNotWaitingGivesTheTextBackNeverLosesIt() {
        val sent = Draft().queuedWith("r1", "fix it", listOf(photo("p"))).queuedWith("r2", "other", emptyList())
        // No answer: it may have been taken; nothing moves yet, and that survives a restart.
        val doubted = Draft.fromJson(JSONObject(sent.afterWithdraw("r1", null, "").toJson().toString()))
        assertEquals("", doubted.text)
        assertEquals(sent.queued.keys, doubted.queued.keys)
        // The retry hears "not waiting": the first withdraw may be what took it, so text and files come back.
        val back = doubted.afterWithdraw("r1", false, "typed meanwhile")
        assertEquals("typed meanwhile\n\nfix it", back.text)
        assertEquals(listOf("p"), back.attachments.map { it.id })
        assertEquals(setOf("r2"), back.queued.keys)
        // Without an earlier doubt, "not waiting" means it left the queue as sent: the editor keeps its own text.
        val left = sent.afterWithdraw("r2", false, "typed")
        assertEquals("", left.text)
        assertEquals(sent.queued.keys, left.queued.keys)
        // A plain true takes it back as before.
        assertEquals("other", sent.afterWithdraw("r2", true, "").text)
    }
}
