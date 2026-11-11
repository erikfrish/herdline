package io.github.erikfrish.herdline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessTest {
    @Test
    fun derived_sets_keep_the_routes_the_app_had_before_the_table() {
        // The literals Transcript.kt CHAT_AGENTS, ChatIngress.kt ENDPOINT_AGENTS /
        // API_AGENTS and the native steer gate (composer.rs steer_status) held.
        assertEquals(setOf("omp", "pi", "opencode", "codex", "claude"), Harness.chatAgents)
        assertEquals(setOf("omp"), Harness.endpointAgents)
        assertEquals(setOf("codex", "opencode"), Harness.apiAgents)
        assertEquals(setOf("omp"), Harness.steerWhileWorking)
    }

    @Test
    fun a_name_matches_in_any_case_and_keeps_herdrs_spelling() {
        val shown = Harness.of("OpenCode")
        assertEquals("OpenCode", shown.name)
        assertEquals(SendRoute.Api, shown.send)
        assertTrue(shown.chat)
    }

    @Test
    fun an_unknown_name_is_terminal_only_under_its_own_name() {
        for (name in listOf("hermes", "agy", "copilot", "Gemini", "not-an-agent", "")) {
            val other = Harness.of(name)
            assertEquals(Harness.terminalOnly(name), other)
            assertFalse(name, other.chat)
            assertEquals(listOf(HarnessItem.Terminal), other.worksHere().filter { it.second }.map { it.first })
        }
    }

    @Test
    fun terminal_comes_first_and_only_verified_items_show_as_available() {
        val rows = Harness.of("opencode").worksHere()
        assertEquals(HarnessItem.Terminal to true, rows.first())
        assertEquals(listOf(HarnessItem.Terminal, HarnessItem.Status), rows.filter { it.second }.map { it.first })
        assertEquals(HarnessItem.entries.toSet(), rows.map { it.first }.toSet())
        // Not yet: in the fixed order after every available one.
        assertEquals(
            listOf(HarnessItem.History, HarnessItem.Send, HarnessItem.Steer, HarnessItem.Questions, HarnessItem.Subagents, HarnessItem.Media),
            rows.filterNot { it.second }.map { it.first },
        )
    }
}
