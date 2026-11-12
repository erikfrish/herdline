package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.data.NotificationFields
import org.junit.Assert.*
import org.junit.Test

/** Consumer-visible field selection, privacy and live-state transitions. */
class LiveFieldsTest {
    private val t = object : LiveTexts {
        override fun app() = "Herdline"
        override fun unlock() = "Unlock for details"
        override fun hiddenSpoken() = "Herdline live task"
        override fun status(status: AgentStatus) = "status:${status.name}"
        override fun notConnected() = "Not connected"
        override fun many(count: Int) = "$count live tasks"
        override fun minutes(count: Int) = "$count min"
        override fun total(elapsed: String) = "$elapsed total"
        override fun spokenMinutes(count: Int) = "$count minutes"
        override fun staleAt(time: String) = "Last seen $time"
        override fun agents(count: Int, needYou: Int) = "$count agents · $needYou need input"
        override fun more(count: Int) = "+$count more"
        override fun needYou(count: Int) = "$count need input"
        override fun notLive() = "Not live, last known"
    }
    private val now = 600_000L
    private val since = 360_000L
    private fun row(status: AgentStatus = AgentStatus.Working, start: Long? = since, pane: String = "p1") =
        LiveRow("n", "secret-host", pane, "agent-$pane", "private prompt", status, start, 240_000L, "secret-tab")
    private fun fields(card: LiveCard, level: LockDetail, ids: List<String> = NotificationFields.ALL, locked: Boolean = true) =
        liveFields(t, card, level, locked, now, ids) { "12:00" }!!
    private fun words(face: LiveFace) = listOfNotNull(face.title, face.text, face.big, face.chip, face.spoken).joinToString("\n")

    @Test
    fun privacyCapsEveryPostedSurfaceEvenUnlocked() {
        val card = LiveCard(listOf(row(), row(AgentStatus.Blocked, pane = "p2")), null)
        for (locked in listOf(true, false)) for (level in LockDetail.entries) {
            val shown = fields(card, level, locked = locked)
            assertEquals(shown.main, shown.public)
            val text = words(shown.main)
            assertFalse(text.contains("private prompt"))
            assertFalse(shown.main.chip.orEmpty().contains("agent-"))
            if (level != LockDetail.Full) {
                assertFalse(text.contains("secret-host"))
                assertFalse(text.contains("secret-tab"))
            }
            if (level == LockDetail.Hidden || level == LockDetail.StateOnly) assertFalse(text.contains("agent-"))
            if (level == LockDetail.Hidden || level == LockDetail.NamesOnly) assertNull(shown.main.since)
            if (level == LockDetail.Hidden) {
                assertFalse(text.contains("status:"))
                assertEquals("", shown.main.chip)
            }
        }
    }

    @Test
    fun orderAndVisibilityControlActualFieldsWithoutInventedTime() {
        val card = LiveCard(listOf(row()), null)
        val a = fields(card, LockDetail.Full, listOf("host", "tab", "state"))
        assertEquals("secret-host · secret-tab", a.main.title)
        assertEquals("status:Working", a.main.text)
        assertNull(a.main.since)
        val b = fields(card, LockDetail.Full, listOf("state", "agent", "tab", "elapsed"))
        assertEquals("status:Working · agent-p1", b.main.title)
        assertEquals("secret-tab", b.main.text)
        assertEquals(since, b.main.since)
        assertTrue(b.main.spoken.endsWith("4 minutes"))
        assertFalse(fields(card, LockDetail.NamesOnly).main.spoken.contains("minutes"))
        val unknown = fields(LiveCard(listOf(row(start = null)), null), LockDetail.Full)
        assertNull(unknown.main.since)
        assertFalse(unknown.main.spoken.contains("minutes"))
        assertFalse(words(unknown.main).contains("0 min"))
        val brief = fields(LiveCard(listOf(row(start = now - 30_000L)), null), LockDetail.StateOnly)
        assertFalse(brief.main.spoken.contains("minutes"))
        assertFalse(words(brief.main).contains("0 min"))
        val empty = fields(card, LockDetail.Full, emptyList())
        assertEquals("Herdline", empty.main.title)
        assertNull(empty.main.text)
    }

    @Test
    fun staleIsMandatoryPrivacyBoundedAndStopsPromotionAndClock() {
        val card = LiveCard(listOf(row()), 100L)
        for (level in LockDetail.entries) {
            val f = fields(card, level, emptyList())
            assertFalse(f.promote)
            assertNull(f.main.since)
            when (level) {
                LockDetail.Hidden -> assertFalse(words(f.main).contains("Last seen"))
                LockDetail.NamesOnly -> {
                    assertTrue(words(f.main).contains("Not live"))
                    assertFalse(words(f.main).contains("12:00"))
                }
                else -> assertTrue(words(f.main).contains("Last seen 12:00"))
            }
        }
    }

    @Test
    fun namesCountsOnlyLiveAgentsAndExpandedRowsAreBounded() {
        val card = LiveCard(listOf(row(), row(AgentStatus.Blocked, pane = "p2"), row(AgentStatus.Idle, pane = "p3")), null)
        assertTrue(fields(card, LockDetail.NamesOnly).main.title.startsWith("2 live tasks"))
        assertFalse(words(fields(card, LockDetail.NamesOnly).main).contains("3 agents"))
        assertFalse(fields(LiveCard(listOf(row(), row(AgentStatus.Idle, pane = "p2")), null), LockDetail.NamesOnly).main.title.contains("live tasks"))
        assertTrue(words(fields(card, LockDetail.Full).main).contains("3 agents · 1 need input"))
        val many = fields(LiveCard((1..8).map { row(pane = "p$it") }, null), LockDetail.Full)
        assertEquals(6, many.main.big!!.lines().size)
        assertTrue(many.main.big!!.endsWith("+3 more"))
    }

    @Test
    fun nonLivePinsNeverCreatePromotedCardAndWorkingOutranksDone() {
        for (status in listOf(AgentStatus.Blocked, AgentStatus.Idle, AgentStatus.Unknown))
            assertNull(liveFields(t, LiveCard(listOf(row(status)), null), LockDetail.Full, true, now) { "12:00" })
        assertNull(liveFields(t, null, LockDetail.Full, true, now) { "12:00" })
        val f = fields(LiveCard(listOf(row(AgentStatus.Done), row(pane = "p2")), null), LockDetail.Full)
        assertEquals(LiveState.Working, f.state)
        assertEquals("p2", f.top.pane)
        assertTrue(f.promote)
        val done = fields(LiveCard(listOf(row(AgentStatus.Done)), null), LockDetail.Full)
        assertNull(done.main.since)
        assertTrue(words(done.main).contains("4 min total"))
        assertTrue(done.unpin)
        assertTrue(f.unpin)
    }

    @Test
    fun dismissalAndPrivacyPreferenceKeepTheirOriginalBoundaries() {
        val working = LiveCard(listOf(row(), row(AgentStatus.Idle, pane = "p2")), null)
        assertEquals(setOf(MonitorKey("n", "p1"), MonitorKey("n", "p2")), dismissedPins(working))
        assertTrue(dismissedPins(LiveCard(listOf(row(AgentStatus.Idle)), null)).isEmpty())
        assertTrue(dismissedPins(LiveCard(listOf(row(AgentStatus.Blocked)), null)).isEmpty())
        assertTrue(dismissedPins(null).isEmpty())
        assertEquals(LockDetail.Hidden, pinPreset(null, LockDetail.Hidden))
        assertEquals(LockDetail.StateOnly, pinPreset(null, null))
        assertEquals(LockDetail.Full, pinPreset(LockDetail.Full, LockDetail.Hidden))
        assertEquals(LockDetail.Hidden, liveLevel(null, LockDetail.Hidden))
        assertEquals(LockDetail.Full, liveLevel(null, LockDetail.Full))
        assertEquals(LockDetail.StateOnly, liveLevel(LockDetail.StateOnly, LockDetail.Full))
    }
}
