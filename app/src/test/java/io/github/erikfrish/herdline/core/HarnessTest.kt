package io.github.erikfrish.herdline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTest {
    @Test
    fun a_name_matches_in_any_case_and_keeps_herdrs_spelling() {
        val shown = Harness.of("OpenCode")
        assertEquals("OpenCode", shown.name)
        assertEquals(SendRoute.Endpoint, shown.send)
        assertTrue(shown.chat)
    }

    @Test
    fun an_unread_or_unknown_agent_never_offers_chat_only_its_terminal() {
        for (name in listOf("agy", "Gemini", "not-an-agent", "")) {
            val other = Harness.of(name)
            assertEquals(name, other.name)
            assertFalse(name, other.chat)
            assertFalse(name, isChatAgent(name))
            assertEquals(SendRoute.None, other.send)
        }
    }

    @Test
    fun cant_check_now_yields_to_an_unknown_program_and_offline_wins_over_identity() {
        val unknown = Shown.Claim(Cell.NotYet(Reason.UnknownProgram))
        for (key in HarnessKey.entries) {
            // 1. An unknown program says so even offline.
            assertEquals(unknown, shown("not-an-agent", key, live = false))
            // 2. Offline over a missing name; 3. a missing name while live.
            assertEquals(Shown.CantCheck(offline = true), shown(null, key, live = false))
            assertEquals(Shown.CantCheck(offline = false), shown(null, key, live = true))
            // 4. A known program shows its cell only while live.
            assertEquals(Shown.CantCheck(offline = true), shown("omp", key, live = false))
            assertEquals(Shown.Claim(Harness.of("omp").cell(key)), shown("omp", key, live = true))
        }
    }

    @Test
    fun a_runtime_replacement_applies_only_to_its_program_and_keys() {
        val server = HarnessRuntime(noServer = true)
        assertEquals(Shown.Claim(Cell.NotYet(Reason.NeedsServer, "Kilo")), shown("Kilo", HarnessKey.Stop, live = true, runtime = server))
        assertEquals(Shown.Claim(Harness.of("kilo").cell(HarnessKey.History)), shown("kilo", HarnessKey.History, live = true, runtime = server))
        assertEquals(Shown.Claim(Harness.of("omp").cell(HarnessKey.Send)), shown("omp", HarnessKey.Send, live = true, runtime = server))
        // Offline still wins over a replacement.
        assertEquals(Shown.CantCheck(offline = true), shown("opencode", HarnessKey.Send, live = false, runtime = server))
    }
}
