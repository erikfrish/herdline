package io.github.erikfrish.herdline.data

import io.github.erikfrish.herdline.core.AttachmentKind
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotoWithdrawTest {
    private fun photo(id: String) = DraftAttachment(id, "content://p/$id", AttachmentKind.IMAGE, "$id.jpg", 10)

    @Test
    fun aWithdrawnSendGetsItsFilesBackOnceAndAcrossARestart() {
        val sent = Draft(text = "").queuedWith("r1", listOf(photo("a"), photo("b")))
        val read = Draft.fromJson(JSONObject(sent.toJson().toString()))
        assertEquals(sent.queuedFiles, read.queuedFiles)

        val back = read.withdrawn("r1", "look")
        assertEquals("look", back.text)
        assertEquals(listOf("a", "b"), back.attachments.map { it.id })
        assertEquals(emptyMap<String, List<DraftAttachment>>(), back.queuedFiles)
        // A file already back in the draft is not doubled; a second withdrawal finds nothing more.
        assertEquals(listOf("a", "b"), back.withdrawn("r1", "look").attachments.map { it.id })
    }

    @Test
    fun anotherSendsFilesStayAndOnlyTheNewestAreKept() {
        val two = Draft().queuedWith("r1", listOf(photo("a"))).queuedWith("r2", listOf(photo("b")))
        assertEquals(listOf("b"), two.withdrawn("r2", "").attachments.map { it.id })
        assertEquals(setOf("r1"), two.withdrawn("r2", "").queuedFiles.keys)
        assertEquals(two, two.queuedWith("r3", emptyList()))

        val many = (1..QUEUED_FILES_KEPT + 2).fold(Draft()) { d, i -> d.queuedWith("r$i", listOf(photo("p$i"))) }
        assertEquals(QUEUED_FILES_KEPT, many.queuedFiles.size)
        assertEquals("r3", many.queuedFiles.keys.first())
    }
}
