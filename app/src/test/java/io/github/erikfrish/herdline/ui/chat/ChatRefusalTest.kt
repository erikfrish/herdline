package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptRefusal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRefusalTest {
    @Test
    fun a_named_hermes_format_says_both_formats_in_the_apps_own_words() {
        // Only the numbers are formatted in: no text from the core (its
        // "unsupported Hermes history format" message) can reach the screen.
        assertEquals(
            RefusalWords(R.string.chat_refused_hermes_version, listOf(32, 33)),
            refusalWords(TranscriptRefusal.UnsupportedVersion("hermes", 32, 33), "Hermes"),
        )
    }

    @Test
    fun an_unnamed_hermes_format_is_said_without_a_number_never_as_unknown() {
        assertEquals(
            RefusalWords(R.string.chat_refused_hermes_format_unknown, listOf(33)),
            refusalWords(TranscriptRefusal.UnsupportedVersion("hermes", null, 33), "Hermes"),
        )
    }

    @Test
    fun another_agents_version_refusal_falls_back_to_the_general_text() {
        assertNull(refusalWords(TranscriptRefusal.UnsupportedVersion("qwen", 2, 1), "Qwen Code"))
    }
}
