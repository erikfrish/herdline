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
    fun kilos_endpoint_steers_into_a_turn_but_never_offers_a_queue() {
        val kilo = Harness.of("Kilo")
        assertEquals(SendRoute.Endpoint, kilo.send)
        assertFalse(kilo.readOnlyHistory)
        assertTrue("kilo" in Harness.endpointAgents)
        assertFalse("kilo" in Harness.queueAgents)
        assertTrue("kilo" in Harness.steerAgents)
        // Its server lists no waiting steer: only Kilo's queue is the app's own record.
        assertEquals(setOf("kilo"), Harness.unlistedSteerAgents)
        // S says what omp and codex say: steer, no queue.
        assertEquals(Harness.of("omp").cell(HarnessKey.SteerQueue), kilo.cell(HarnessKey.SteerQueue))
        for (name in listOf("omp", "codex", "opencode")) assertTrue(name, name in Harness.steerAgents)
    }

    @Test
    fun an_unread_or_unknown_agent_never_offers_chat_only_its_terminal() {
        for (name in listOf("agy", "Droid", "not-an-agent", "")) {
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
            assertEquals(Shown.CantCheck(CantCheckWhy.Offline), shown(null, key, live = false))
            assertEquals(Shown.CantCheck(CantCheckWhy.Identity), shown(null, key, live = true))
            // 4. A known program shows its cell only while live.
            assertEquals(Shown.CantCheck(CantCheckWhy.Offline), shown("omp", key, live = false))
            assertEquals(Shown.Claim(Harness.of("omp").cell(key)), shown("omp", key, live = true))
        }
    }

    @Test
    fun a_runtime_replacement_applies_only_to_its_program_and_keys() {
        val server = HarnessRuntime(server = ServerProbe.Missing)
        assertEquals(Shown.Claim(Cell.NotYet(Reason.NeedsServer, "Kilo")), shown("Kilo", HarnessKey.Stop, live = true, runtime = server))
        assertEquals(Shown.Claim(Harness.of("kilo").cell(HarnessKey.History)), shown("kilo", HarnessKey.History, live = true, runtime = server))
        assertEquals(Shown.Claim(Harness.of("omp").cell(HarnessKey.Send)), shown("omp", HarnessKey.Send, live = true, runtime = server))
        // Offline still wins over a replacement.
        assertEquals(Shown.CantCheck(CantCheckWhy.Offline), shown("opencode", HarnessKey.Send, live = false, runtime = server))
    }

    @Test
    fun a_server_programs_server_rows_follow_what_the_probe_said() {
        val serverKeys = listOf(HarnessKey.Send, HarnessKey.Stop, HarnessKey.SteerQueue, HarnessKey.Questions)
        for (program in listOf("opencode", "kilo")) {
            val claims = Harness.of(program)
            val named = if (program == "opencode") "OpenCode" else "Kilo"
            for (key in HarnessKey.entries) {
                val missing = shown(program, key, live = true, runtime = HarnessRuntime(server = ServerProbe.Missing))
                val unknown = shown(program, key, live = true, runtime = HarnessRuntime(server = ServerProbe.Unknown))
                val present = shown(program, key, live = true, runtime = HarnessRuntime(server = ServerProbe.Present))
                assertEquals("$program $key", Shown.Claim(claims.cell(key)), present)
                if (key in serverKeys) {
                    assertEquals("$program $key", Shown.Claim(Cell.NotYet(Reason.NeedsServer, named)), missing)
                    // A failed or missing answer is never read as a running server.
                    assertEquals("$program $key", Shown.CantCheck(CantCheckWhy.Server), unknown)
                } else {
                    assertEquals("$program $key", present, missing)
                    assertEquals("$program $key", present, unknown)
                }
            }
        }
    }

    @Test
    fun letta_offers_its_read_only_history_and_names_why_nothing_else() {
        val letta = Harness.of("Letta")
        assertTrue(letta.chat)
        assertEquals(SendRoute.None, letta.send)
        val read = setOf(HarnessKey.History, HarnessKey.Live, HarnessKey.Tools)
        val attach = setOf(HarnessKey.Send, HarnessKey.Stop, HarnessKey.SteerQueue, HarnessKey.Questions)
        for (key in HarnessKey.entries) {
            val expected = when (key) {
                in read -> Cell.Unverified()
                in attach -> Cell.NotYet(Reason.ServerUnattachable, "Letta")
                HarnessKey.Media -> Cell.NotYet(Reason.SendMedia)
                HarnessKey.Status -> Cell.NotYet(Reason.ScreenStatus)
                else -> Cell.NotYet(Reason.NotBuilt)
            }
            assertEquals("letta $key", expected, letta.cell(key))
        }
    }
}
