package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.ImageSource
import io.github.erikfrish.herdline.core.MAX_IMAGE_BYTES
import io.github.erikfrish.herdline.core.MediaRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMediaTest {
    @Test
    fun a_sample_brings_any_source_within_the_edge_and_the_pixel_cap() {
        listOf(1 to 1, 512 to 512, 513 to 1, 4000 to 3000, 10_000 to 10_000, 100_000 to 1, 3 to 99_999).forEach { (w, h) ->
            listOf(THUMB_EDGE to THUMB_PIXELS, FULL_EDGE to FULL_PIXELS).forEach { (edge, pixels) ->
                val s = sampleSize(w, h, edge, pixels)
                val sw = (w + s - 1L) / s
                val sh = (h + s - 1L) / s
                assertTrue("$w x $h at $s", sw <= edge && sh <= edge && sw * sh <= pixels)
                // Half the sample would not fit: no more detail is thrown away than needed.
                if (s > 1) assertTrue("$w x $h at ${s / 2}", (w + s / 2 - 1L) / (s / 2) > edge || (h + s / 2 - 1L) / (s / 2) > edge || ((w + s / 2 - 1L) / (s / 2)) * ((h + s / 2 - 1L) / (s / 2)) > pixels)
            }
        }
        assertEquals(1, sampleSize(512, 512, THUMB_EDGE, THUMB_PIXELS))
    }

    @Test
    fun a_source_that_is_no_image_or_too_many_pixels_is_not_decoded() {
        assertEquals(MediaRefusal.Unsupported, sourceRefusal(-1, -1))
        assertEquals(MediaRefusal.Unsupported, sourceRefusal(0, 10))
        assertEquals(MediaRefusal.TooLarge, sourceRefusal(20_000, 20_000))
        assertNull(sourceRefusal(10_000, 10_000))
    }

    @Test
    fun an_oversized_or_unreadable_image_is_refused_before_any_read() {
        assertEquals(MediaRefusal.TooLarge, refusalBeforeFetch(ImageSource.Inline(MAX_IMAGE_BYTES + 1)))
        assertNull(refusalBeforeFetch(ImageSource.Inline(MAX_IMAGE_BYTES)))
        assertNull(refusalBeforeFetch(ImageSource.Blob("ab".repeat(32))))
        assertEquals(MediaRefusal.Unsupported, refusalBeforeFetch(ImageSource.Unreadable))
    }

    @Test
    fun an_answer_over_the_encoded_cap_or_not_base64_fails_safely() {
        assertEquals(ImageLoad.Failed(MediaRefusal.TooLarge), decodeImage("A".repeat(MAX_IMAGE_BASE64.toInt() + 4), THUMB_EDGE, THUMB_PIXELS))
        assertEquals(ImageLoad.Failed(MediaRefusal.Unsupported), decodeImage("not base64!", THUMB_EDGE, THUMB_PIXELS))
    }
}
