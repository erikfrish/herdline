package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.StopRefused
import java.io.IOException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class StopOutcomeTest {
    private fun said(waitMs: Long, call: suspend () -> Result<Boolean>): List<String?> {
        val words = mutableListOf<String?>()
        runBlocking { stopOutcome(waitMs, call) { words += it } }
        return words
    }

    @Test
    fun a_host_slower_than_the_wait_is_not_confirmed_first_then_its_own_answer_replaces_it() {
        assertEquals(listOf("unconfirmed", "key_unsent"), said(20) { delay(300); Result.failure(StopRefused("key_unsent")) })
        assertEquals(listOf("unconfirmed", null), said(20) { delay(300); Result.success(true) })
    }

    @Test
    fun a_prompt_answer_is_said_once_and_never_as_not_confirmed() {
        assertEquals(listOf<String?>(null), said(5_000) { Result.success(true) })
        assertEquals(listOf<String?>("agent_changed"), said(5_000) { Result.failure(StopRefused("agent_changed")) })
    }

    @Test
    fun a_server_with_nothing_running_and_a_lost_answer_each_leave_words() {
        assertEquals(listOf<String?>("none_running"), said(5_000) { Result.success(false) })
        assertEquals(listOf<String?>("unconfirmed"), said(5_000) { Result.failure(IOException("no answer")) })
    }
}
