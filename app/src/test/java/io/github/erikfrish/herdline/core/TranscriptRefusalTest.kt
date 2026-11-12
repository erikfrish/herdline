package io.github.erikfrish.herdline.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TranscriptRefusalTest {
    private fun parse(json: String) = TranscriptRefusal.parse(JSONObject(json))

    @Test
    fun a_version_refusal_carries_the_found_and_supported_formats() {
        assertEquals(
            TranscriptRefusal.UnsupportedVersion("hermes", 32, 33),
            parse("""{"kind":"unsupported_version","agent":"hermes","found":32,"supported":33}"""),
        )
    }

    @Test
    fun a_store_naming_no_single_format_has_no_found_whether_null_or_absent() {
        val unnamed = TranscriptRefusal.UnsupportedVersion("hermes", null, 33)
        assertEquals(unnamed, parse("""{"kind":"unsupported_version","agent":"hermes","found":null,"supported":33}"""))
        assertEquals(unnamed, parse("""{"kind":"unsupported_version","agent":"hermes","supported":33}"""))
    }

    @Test
    fun without_a_usable_supported_format_it_is_no_typed_refusal_so_the_general_text_is_said() {
        assertNull(parse("""{"kind":"unsupported_version","agent":"hermes","found":32}"""))
        assertNull(parse("""{"kind":"unsupported_version","agent":"hermes","found":32,"supported":-1}"""))
        assertNull(parse("""{"kind":"unsupported_version","agent":"hermes","found":32,"supported":null}"""))
    }
}
