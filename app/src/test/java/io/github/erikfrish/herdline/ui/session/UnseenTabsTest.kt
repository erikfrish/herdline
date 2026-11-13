package io.github.erikfrish.herdline.ui.session

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
    fun anUnchangedSetIsHandedBackAsTheSameInstanceAndAChangedOneIsNot() {
        // SHOULD-2: a frame that changes no mark keeps the instance, so rows taking it can skip.
        val unseen = setOf("t1")
        val now = listOf(tab("t1", AgentStatus.Working), tab("t2", AgentStatus.Working))
        val before = mapOf("t1" to AgentStatus.Done, "t2" to AgentStatus.Working)
        assertSame(unseen, nextUnseen(before, now, selectedId = null, unseen = unseen))
        // t2 turning Done adds a mark: a new set with it.
        val bumped = listOf(tab("t1", AgentStatus.Working), tab("t2", AgentStatus.Done))
        assertEquals(setOf("t1", "t2"), nextUnseen(before, bumped, selectedId = null, unseen = unseen))
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

    private val ws = Workspace("w1", 1, "lab", null, null, null, null, false, "t5", true, AgentStatus.Idle)

    @Test
    fun onlyAChangedTabsRowCarriesTheMarkNeverAPaneRowOfIt() {
        val t6 = tab("t6", AgentStatus.Done)
        val unseen = setOf("t6")
        assertTrue(SwitchTarget.TabRow(ws, t6, panes = 1).changedIn(unseen))
        assertFalse(SwitchTarget.TabRow(ws, tab("t5", AgentStatus.Done), panes = 1).changedIn(unseen))
        assertFalse(SwitchTarget.PaneRow(ws, t6, Pane("p6", "w1", "t6", null, null, false), agent = null).changedIn(unseen))
        // Selecting t6 empties the set (nextUnseen), and the row's mark goes with it.
        assertFalse(SwitchTarget.TabRow(ws, t6, panes = 1).changedIn(emptySet()))
    }

    @Test
    fun aChangedRowSaysChangedFirstThenItsOtherStates() {
        assertEquals("changed, On desktop, Done", changedFirst("changed", listOf("On desktop", "Done")))
        assertEquals("changed", changedFirst("changed", listOf(null, null)))
        // An unmarked row keeps its states as before, and one with none has no state.
        assertEquals("On desktop, Done", changedFirst(null, listOf("On desktop", null, "Done")))
        assertNull(changedFirst(null, listOf(null)))
    }

    @Test
    fun theMarkSitsOnTheGlyphsTopEndCornerNotTheSlots() {
        // A 10 px glyph centred in a 24 px row slot: its corner, mirrored right to left.
        assertEquals(Offset(17f, 7f), unseenCorner(Size(24f, 24f), 10f, rtl = false))
        assertEquals(Offset(7f, 7f), unseenCorner(Size(24f, 24f), 10f, rtl = true))
        // The strip's slot is the glyph: the box's own corner, as in 10085.
        assertEquals(Offset(10f, 0f), unseenCorner(Size(10f, 10f), 10f, rtl = false))
        assertEquals(Offset(0f, 0f), unseenCorner(Size(10f, 10f), 10f, rtl = true))
    }
}
