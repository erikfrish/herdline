package io.github.erikfrish.herdline.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TranscriptMediaTest {
    private val agent = TranscriptIdentity("w1:p1", "term_1", 42, "/h/.omp/agent/sessions/x/a.jsonl")

    @Test
    fun a_media_request_names_the_line_part_and_only_a_blobs_hash() {
        val hash = "0f".repeat(32)
        val blob = mediaRequest("w1:p1", agent, TranscriptImage("r", 60, 90, 2, ImageSource.Blob(hash)))
        val media = blob.getJSONObject("media")
        assertEquals(agent, TranscriptIdentity.parse(blob.getJSONObject("expect")))
        assertEquals(listOf(1, "r", 60L, 90L, 2, hash), listOf(media.getInt("v"), media.getString("entry"), media.getLong("offset"), media.getLong("end"), media.getInt("part"), media.getString("blob")))
        assertFalse(blob.has("read"))

        val inline = mediaRequest("w1:p1", agent, TranscriptImage(null, 0, 40, 1, ImageSource.Inline(9))).getJSONObject("media")
        assertEquals(true, inline.isNull("entry"))
        assertFalse(inline.has("blob"))
    }

    @Test
    fun an_answer_is_an_image_or_a_refusal_and_another_version_is_refused() {
        assertEquals(MediaAnswer.Image("image/png", "iVBO"), MediaAnswer.parse("""{"media":{"v":1,"mime":"image/png","data":"iVBO"}}"""))
        assertEquals(MediaAnswer.Refused(MediaRefusal.UnknownStore), MediaAnswer.parse("""{"media":{"v":1,"error":"unknown_store"}}"""))
        assertEquals(MediaAnswer.Refused(MediaRefusal.TooLarge), MediaAnswer.parse("""{"media":{"v":1,"error":"too_large"}}"""))
        assertEquals("a newer refusal", MediaAnswer.Refused(MediaRefusal.Version), MediaAnswer.parse("""{"media":{"v":1,"error":"later"}}"""))
        assertEquals("a newer shape", MediaAnswer.Refused(MediaRefusal.Version), MediaAnswer.parse("""{"media":{"v":2,"mime":"image/png","data":"iVBO"}}"""))
    }

    @Test
    fun an_image_source_the_app_cannot_use_is_unreadable() {
        fun source(json: String) = ImageSource.parse(JSONObject(json))
        assertEquals(ImageSource.Inline(9), source("""{"kind":"inline","bytes":9}"""))
        assertEquals(ImageSource.Unreadable, source("""{"kind":"inline"}"""))
        assertEquals(ImageSource.Unreadable, source("""{"kind":"blob","hash":"../../etc/passwd"}"""))
        assertEquals(ImageSource.Unreadable, source("""{"kind":"url","href":"https://x"}"""))
        assertEquals(ImageSource.Unreadable, ImageSource.parse(null))
    }
}
