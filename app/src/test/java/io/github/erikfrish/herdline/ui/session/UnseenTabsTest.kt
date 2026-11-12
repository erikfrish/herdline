package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Tab
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Test

class UnseenTabsTest {
    private fun tab(id: String, status: AgentStatus) = Tab(id, "w1", 1, id, false, false, status)

    @Test
    fun aBackgroundTabTurningBlockedOrDoneIsMarked() {
        val before = mapOf("t1" to AgentStatus.Working, "t2" to AgentStatus.Working, "t3" to AgentStatus.Working)
        val now = listOf(tab("t1", AgentStatus.Blocked), tab("t2", AgentStatus.Done), tab("t3", AgentStatus.Idle))
        assertEquals(setOf("t1", "t2"), nextUnseen(before, now, selectedId = null, unseen = emptySet()))
    }

    @Test
    fun theSelectedTabIsNeverMarkedAndSelectingClearsTheMark() {
        val before = mapOf("t1" to AgentStatus.Working)
        assertEquals(emptySet<String>(), nextUnseen(before, listOf(tab("t1", AgentStatus.Done)), selectedId = "t1", unseen = emptySet()))
        assertEquals(emptySet<String>(), nextUnseen(mapOf("t1" to AgentStatus.Done), listOf(tab("t1", AgentStatus.Done)), "t1", setOf("t1")))
    }

    @Test
    fun aMarkStaysUntilSelectedEvenWhenTheStatusMovesOn() {
        val before = mapOf("t1" to AgentStatus.Done)
        assertEquals(setOf("t1"), nextUnseen(before, listOf(tab("t1", AgentStatus.Working)), selectedId = "t2", unseen = setOf("t1")))
    }

    @Test
    fun aFirstStatusOrAnUnchangedOneIsNotAChange() {
        val now = listOf(tab("t1", AgentStatus.Done), tab("t2", AgentStatus.Blocked))
        assertEquals(emptySet<String>(), nextUnseen(mapOf("t2" to AgentStatus.Blocked), now, selectedId = null, unseen = emptySet()))
    }

    @Test
    fun aClosedTabLosesItsMark() {
        assertEquals(emptySet<String>(), nextUnseen(mapOf("t1" to AgentStatus.Done), emptyList(), selectedId = null, unseen = setOf("t1")))
    }

    @Test
    fun aMarkSurvivesSavedStateAndStillClearsWhenSelected() {
        val seen = SeenTabs(mapOf("t1" to AgentStatus.Done, "t2" to AgentStatus.Working), setOf("t1"))
        val saved = with(SeenTabsSaver) { SaverScope { true }.save(seen) }!!
        val back = SeenTabsSaver.restore(saved)!!
        val now = listOf(tab("t1", AgentStatus.Done), tab("t2", AgentStatus.Done))
        // Restored: t1 stays marked, and t2's restored Working makes its Done a change.
        assertEquals(setOf("t1", "t2"), nextUnseen(back.statuses, now, selectedId = null, unseen = back.unseen))
        assertEquals(setOf("t2"), nextUnseen(back.statuses, now, selectedId = "t1", unseen = back.unseen))
    }
}
