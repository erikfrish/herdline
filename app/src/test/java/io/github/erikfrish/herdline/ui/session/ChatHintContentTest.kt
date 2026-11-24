package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.OmpChatIntegration
import io.github.erikfrish.herdline.core.OmpChatIntegration.Action
import io.github.erikfrish.herdline.core.OmpChatIntegration.State
import io.github.erikfrish.herdline.core.ReasonCode
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHintContentTest {
    private fun omp(setup: ChatSetup) = chatHintContent(ReasonCode.IDENTITY_UNKNOWN, omp = true, setup = setup)
    private fun reply(state: State, message: String? = null) = Result.success(OmpChatIntegration(state, "/home/u/.omp/agent/extensions", emptyList(), message))
    private val unreachable = Result.failure<OmpChatIntegration>(IOException("not_connected"))

    /** A host that answers [answers] in turn and records what it was asked. */
    private class FakeHost(answers: List<Result<OmpChatIntegration>>) {
        private val left = ArrayDeque(answers)
        val asked = mutableListOf<Action>()
        fun call(action: Action): Result<OmpChatIntegration> {
            asked += action
            return left.removeFirst()
        }
    }

    @Test
    fun theHostsStatusMapsToTheHintAndAnUnreachableHostIsNeverAbsent() {
        assertEquals(ChatSetup.Absent, chatSetupOf(reply(State.Absent)))
        assertEquals(ChatSetup.Outdated, chatSetupOf(reply(State.Outdated)))
        assertEquals(ChatSetup.Installed, chatSetupOf(reply(State.Installed)))
        assertEquals(ChatSetup.Failed("mkdir: Permission denied"), chatSetupOf(reply(State.Error, "mkdir: Permission denied")))
        assertEquals(ChatSetup.Unknown, chatSetupOf(reply(State.Error, null)))
        assertEquals(ChatSetup.Unknown, chatSetupOf(unreachable))
    }

    @Test
    fun showingTheHintOnlyAsksForStatusNeverInstalls() {
        val host = FakeHost(listOf(reply(State.Absent)))
        assertEquals(ChatSetup.Absent, askChatSetup(install = false, host::call))
        assertEquals(listOf(Action.Status), host.asked)
    }

    @Test
    fun anInstallThatLostTheLinkShowsWhatTheHostNowHolds() {
        // Installed, then the reply was lost: the next status tells the truth.
        val landed = FakeHost(listOf(unreachable, reply(State.Installed)))
        assertEquals(ChatSetup.Installed, askChatSetup(install = true, landed::call))
        assertEquals(listOf(Action.Install, Action.Status), landed.asked)
        // Still unreachable: no stuck Installing, New tab stays.
        assertEquals(ChatSetup.Unknown, askChatSetup(install = true, FakeHost(listOf(unreachable, unreachable))::call))
        // The host's own refusal is shown as it came, with no second ask.
        val refused = FakeHost(listOf(reply(State.Error, "python3: not found")))
        assertEquals(ChatSetup.Failed("python3: not found"), askChatSetup(install = true, refused::call))
        assertEquals(listOf(Action.Install), refused.asked)
    }

    @Test
    fun anUnknownOrFailedStatusKeepsNewTabAsTheWayForward() {
        assertEquals(ChatHintContent(R.string.hc_chat_hint_omp, null, ChatHintAction.NewTab), omp(ChatSetup.Unknown))
        // The host's error, word for word, and still New tab.
        assertEquals(ChatHintContent(null, "python3: not found", ChatHintAction.NewTab), omp(ChatSetup.Failed("python3: not found")))
    }

    @Test
    fun absentOrOutdatedOffersInstallInsteadOfNewTab() {
        val install = ChatHintContent(R.string.hc_chat_hint_omp_install, null, ChatHintAction.Install)
        assertEquals(install, omp(ChatSetup.Absent))
        assertEquals(install, omp(ChatSetup.Outdated))
    }

    @Test
    fun installedOrInstallingOffersNoActionOnlyTheNextStep() {
        assertEquals(ChatHintContent(R.string.hc_chat_hint_restart, null, null), omp(ChatSetup.Installed))
        assertEquals(ChatHintContent(R.string.hc_chat_hint_installing, null, null), omp(ChatSetup.Installing))
    }

    @Test
    fun otherAgentsAndOtherReasonsNeverOfferSetup() {
        assertEquals(
            ChatHintContent(R.string.hc_chat_hint_other, null, null),
            chatHintContent(ReasonCode.IDENTITY_UNKNOWN, omp = false, setup = ChatSetup.Absent),
        )
        assertEquals(
            ChatHintContent(R.string.hc_reason_terminal_lost, null, null),
            chatHintContent(ReasonCode.TERMINAL_LOST, omp = true, setup = ChatSetup.Absent),
        )
        // Free text from the host is shown as it came.
        assertEquals(ChatHintContent(null, "agent busy", null), chatHintContent("agent busy", omp = true, setup = ChatSetup.Absent))
    }
}
