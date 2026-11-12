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
        assertEquals(SendRoute.Api, shown.send)
        assertTrue(shown.chat)
    }

    @Test
    fun an_unknown_agent_never_offers_chat_only_its_terminal() {
        for (name in listOf("agy", "Gemini", "not-an-agent", "")) {
            val other = Harness.of(name)
            assertEquals(name, other.name)
            assertFalse(name, other.chat)
            assertFalse(name, isChatAgent(name))
            assertEquals(SendRoute.None, other.send)
            assertEquals(listOf(HarnessItem.Terminal), other.worksHere().filter { it.second }.map { it.first })
        }
    }

    @Test
    fun opencode_never_shows_steer_while_working() {
        // Its prompt API sends an ordinary prompt; steering a working turn is unproved (N1-CORRECTION).
        val steer = Harness.of("opencode").worksHere().first { it.first == HarnessItem.Steer }
        assertFalse(steer.second)
    }

    @Test
    fun terminal_comes_first_and_an_unproved_item_never_shows_as_available() {
        val fixture = Harness(
            "fixture",
            history = Proof.Verified,
            send = SendRoute.Api,
            sendProof = Proof.Unverified,
            steer = Proof.None,
            status = Proof.Verified,
            questions = Proof.None,
            subagents = Proof.None,
            media = Proof.Unverified,
        )
        val rows = fixture.worksHere()
        assertEquals(HarnessItem.Terminal to true, rows.first())
        assertEquals(HarnessItem.entries.size, rows.size)
        assertEquals(HarnessItem.entries.toSet(), rows.map { it.first }.toSet())
        val available = rows.filter { it.second }.map { it.first }.toSet()
        assertFalse("an unverified Send must not read as working", HarnessItem.Send in available)
        assertFalse(HarnessItem.Media in available)
        assertTrue(HarnessItem.History in available)
    }
}
