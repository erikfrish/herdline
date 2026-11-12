package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptRefusal
import io.github.erikfrish.herdline.ui.chat.RefusalWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ChatUnreadableTest {
    private val host = UnreadableNotice(RefusalWords(R.string.session_chat_unreadable_host, listOf("Claude Code")), help = true)

    @Test
    fun a_kind_never_read_on_this_connection_names_the_missing_integration_and_offers_help() {
        assertEquals(host, unreadableNotice(null, "Claude Code", everRead = false))
    }

    @Test
    fun a_kind_read_before_keeps_the_passing_text_without_help() {
        assertEquals(
            UnreadableNotice(RefusalWords(R.string.session_chat_unreadable)),
            unreadableNotice(null, "Claude Code", everRead = true),
        )
    }

    @Test
    fun a_typed_refusal_keeps_its_own_words_even_for_a_kind_never_read() {
        assertEquals(
            UnreadableNotice(RefusalWords(R.string.chat_refused_hermes_version, listOf(32, 33))),
            unreadableNotice(TranscriptRefusal.UnsupportedVersion("hermes", 32, 33), "Hermes", everRead = false),
        )
    }

    @Test
    fun kilo_refusals_say_why_never_the_host_text_or_a_switch_back() {
        val outside = unreadableNotice(TranscriptRefusal.UnsupportedFormat("kilo_store_outside_home"), "Kilo", everRead = false)
        val event = unreadableNotice(TranscriptRefusal.UnsupportedFormat("kilo_event_store"), "Kilo", everRead = false)
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_outside_home, listOf("Kilo"))), outside)
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_general, listOf("Kilo"))), event)
        for (notice in listOf(outside, event)) {
            assertNotEquals(R.string.session_chat_unreadable_host, notice.words.res)
            assertNotEquals(R.string.session_chat_unreadable, notice.words.res)
        }
    }

    @Test
    fun grok_refusals_say_why_with_the_existing_words() {
        val notice = { format: String -> unreadableNotice(TranscriptRefusal.UnsupportedFormat(format), "Grok", everRead = false) }
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_outside_home, listOf("Grok"))), notice("grok_store_outside_home"))
        for (format in listOf("grok_store_unreadable", "grok_session_too_large")) {
            assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_general, listOf("Grok"))), notice(format))
        }
    }

    @Test
    fun copilot_refusals_say_why_never_the_host_text_or_a_switch_back() {
        val outside = unreadableNotice(TranscriptRefusal.UnsupportedFormat("copilot_store_outside_home"), "Copilot", everRead = false)
        val large = unreadableNotice(TranscriptRefusal.UnsupportedFormat("copilot_session_too_large"), "Copilot", everRead = true)
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_outside_home, listOf("Copilot"))), outside)
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_general, listOf("Copilot"))), large)
        for (notice in listOf(outside, large)) {
            assertNotEquals(R.string.session_chat_unreadable_host, notice.words.res)
            assertNotEquals(R.string.session_chat_unreadable, notice.words.res)
        }
    }

    @Test
    fun mastracode_outside_home_is_said_as_such_and_its_other_refusals_generally() {
        val outside = unreadableNotice(TranscriptRefusal.UnsupportedFormat("mastracode_store_outside_home"), "Mastra Code", everRead = false)
        val large = unreadableNotice(TranscriptRefusal.UnsupportedFormat("mastracode_session_too_large"), "Mastra Code", everRead = true)
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_outside_home, listOf("Mastra Code"))), outside)
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_general, listOf("Mastra Code"))), large)
    }

    @Test
    fun kimi_refusals_say_why_with_the_existing_words() {
        val notice = { format: String -> unreadableNotice(TranscriptRefusal.UnsupportedFormat(format), "Kimi", everRead = false) }
        assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_outside_home, listOf("Kimi"))), notice("kimi_store_outside_home"))
        for (format in listOf("kimi_session_ambiguous", "kimi_store_unreadable", "kimi_session_too_large", "kimi_wire_version", "kimi_process_unnamed")) {
            assertEquals(UnreadableNotice(RefusalWords(R.string.chat_refused_general, listOf("Kimi"))), notice(format))
        }
    }
}
