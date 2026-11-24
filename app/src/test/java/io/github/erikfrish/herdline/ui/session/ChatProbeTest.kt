package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ReasonCode
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.TranscriptDetached
import io.github.erikfrish.herdline.core.TranscriptDetachment
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.TranscriptPage
import io.github.erikfrish.herdline.core.TranscriptPending
import io.github.erikfrish.herdline.core.TranscriptRefusal
import io.github.erikfrish.herdline.core.TranscriptRefused
import io.github.erikfrish.herdline.core.UnboundReason
import io.github.erikfrish.herdline.ui.chat.ChatKept
import io.github.erikfrish.herdline.ui.chat.detachedCauseRes
import io.github.erikfrish.herdline.ui.chat.refusalWords
import io.github.erikfrish.herdline.ui.chat.unboundRes
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ChatProbeTest {
    @Test
    fun `only a proved session opens Chat, every other answer keeps Terminal and says why`() {
        assertEquals(ProbeOutcome.Bound, probeOutcome(Result.success(page(probed("a")))))
        assertNull(probeHintRes(ProbeOutcome.Bound))
        for (reason in UnboundReason.entries) {
            val refused = probeOutcome(Result.failure(TranscriptRefused(TranscriptRefusal.Unbound(reason))))
            assertEquals(ProbeOutcome.Refused(reason), refused)
            assertEquals(unboundRes(reason), probeHintRes(refused))
        }
        assertEquals(R.string.chat_probe_fresh, probeHintRes(probeOutcome(Result.failure(TranscriptPending()))))
        assertEquals(R.string.chat_probe_failed, probeHintRes(probeOutcome(Result.failure(IOException("link")))))
        // Another refusal kind proves no session either: Terminal, not a refusal of the wrong kind.
        assertEquals(ProbeOutcome.Failed, probeOutcome(Result.failure(TranscriptRefused(TranscriptRefusal.OutsideHome))))
    }

    @Test
    fun `each unbound reason has its own words, never the general refusal`() {
        val words = UnboundReason.entries.map(::unboundRes)
        assertEquals(words.size, words.toSet().size)
        assertFalse(R.string.chat_refused_general in words)
        for (reason in UnboundReason.entries) {
            assertEquals(unboundRes(reason), refusalWords(TranscriptRefusal.Unbound(reason), "OMP").res)
            assertEquals(unboundRes(reason), detachedCauseRes(TranscriptDetachment.Refused(reason)))
        }
        assertNull(detachedCauseRes(TranscriptDetachment.Rebound))
    }

    @Test
    fun `a bound hookless pane opens Chat only in its boot and while still hookless`() {
        val hookless = view("b1", chat = ReasonCode.HOOKLESS_OMP)
        assertTrue(hooklessOmp(hookless, PANE))
        assertFalse(chatOpens(hookless, PANE, boundBoot = null))
        assertTrue(chatOpens(hookless, PANE, boundBoot = "b1"))
        // A restarted daemon may reuse the pane id.
        assertFalse(chatOpens(view("b2", chat = ReasonCode.HOOKLESS_OMP), PANE, boundBoot = "b1"))
        // Another reason against Chat: the binding says nothing.
        assertFalse(chatOpens(view("b1", chat = ReasonCode.IDENTITY_UNKNOWN), PANE, boundBoot = "b1"))
        // Hooked now: the full Chat path, whatever was bound.
        assertFalse(hooklessOmp(view("b1", chat = null), PANE))
        assertTrue(chatOpens(view("b1", chat = null), PANE, boundBoot = null))
        // Not OMP in the pane any more.
        assertFalse(chatOpens(view("b1", chat = ReasonCode.HOOKLESS_OMP, agent = "codex"), PANE, boundBoot = "b1"))
    }

    @Test
    fun `detached keeps the last confirmed page until a tap identifies anew`() = runBlocking {
        val kept = ChatKept()
        val first = probed("/s/one.jsonl")
        kept.identify({ Result.success(page(first)) }, PANE)
        assertEquals(first, kept.history?.identity)

        kept.readNext({ Result.failure(TranscriptDetached(TranscriptDetachment.Rebound)) }, PANE)
        assertEquals(TranscriptDetachment.Rebound, kept.read.detached)
        assertEquals(first, kept.history?.identity)
        assertNull(kept.read.problem)

        // A tap that still proves nothing: the frozen page stays, with the reason now.
        kept.identify({ Result.failure(TranscriptRefused(TranscriptRefusal.Unbound(UnboundReason.LeaseNotHeld))) }, PANE)
        assertEquals(TranscriptDetachment.Refused(UnboundReason.LeaseNotHeld), kept.read.detached)
        assertEquals(first, kept.history?.identity)

        // A tap that proves another session shows only that one, never merged with the frozen page.
        val second = probed("/s/two.jsonl")
        kept.identify({ request ->
            assertTrue(request.isNull("expect"))
            Result.success(page(second))
        }, PANE)
        assertNull(kept.read.detached)
        assertEquals(second, kept.history?.identity)
        assertNotEquals(first, kept.history?.identity)
    }

    private fun probed(path: String) = TranscriptIdentity(PANE, "t1", 42, path, source = TranscriptIdentity.PROBE_SOURCE, start = 7)

    private fun page(identity: TranscriptIdentity): TranscriptPage = TranscriptPage.parse(
        JSONObject()
            .put("identity", identity.toJson())
            .put("reset", true)
            .put("size", 10)
            .put("start", 0)
            .put("end", 10)
            .put("entries", org.json.JSONArray())
            .toString(),
    )

    private fun view(boot: String, chat: String?, agent: String = "omp") = ShellView(
        revision = 1, boot = boot, focusedWorkspace = "w", focusedTab = "t", focusedPane = PANE, updateAvailable = null,
        workspaces = emptyList(), tabs = emptyList(),
        panes = listOf(Pane(PANE, "w", "t", null, null, true, chat = chat)),
        agents = listOf(Agent(PANE, "w", "t", null, agent, null, AgentStatus.Idle, true)),
    )

    private companion object {
        const val PANE = "p1"
    }
}
