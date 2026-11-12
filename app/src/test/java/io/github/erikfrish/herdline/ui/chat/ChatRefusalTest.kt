package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptRefusal
import org.junit.Assert.assertEquals
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
    fun another_agents_version_refusal_says_the_general_refusal_never_a_switch_back() {
        assertEquals(
            RefusalWords(R.string.chat_refused_general, listOf("Qwen Code")),
            refusalWords(TranscriptRefusal.UnsupportedVersion("qwen", 2, 1), "Qwen Code"),
        )
    }

    @Test
    fun two_matching_sessions_and_a_session_mismatch_say_their_own_cause() {
        assertEquals(
            RefusalWords(R.string.chat_refused_ambiguous, listOf("Grok")),
            refusalWords(TranscriptRefusal.UnsupportedFormat("grok_session_ambiguous"), "Grok"),
        )
        assertEquals(
            RefusalWords(R.string.chat_refused_mismatch, listOf("Copilot")),
            refusalWords(TranscriptRefusal.UnsupportedFormat("copilot_session_mismatch"), "Copilot"),
        )
        assertEquals(
            RefusalWords(R.string.chat_refused_general, listOf("Grok")),
            refusalWords(TranscriptRefusal.UnsupportedFormat("grok_session_too_large"), "Grok"),
        )
    }
}
