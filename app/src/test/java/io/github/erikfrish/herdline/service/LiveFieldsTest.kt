package io.github.erikfrish.herdline.service

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live update's fields (Parent I1 ruling) across
 * the four lock levels and every agent state: what each level may name,
 * when a time shows, and what is promoted.
 */
class LiveFieldsTest {
    private val t = object : LiveTexts {
        override fun app() = "Herdline"
        override fun unlock() = "Unlock for details"
        override fun hiddenSpoken() = "Herdline live task"
        override fun status(status: AgentStatus) = "status:${status.name}"
        override fun notConnected() = "Not connected"
        override fun pair(first: String, second: String) = "$first · $second"
        override fun row(name: String, status: String) = "$name — $status"
        override fun rowTitled(name: String, status: String, title: String) = "$name — $status · $title"
        override fun many(count: Int) = "$count live task(s)"
        override fun place(node: String, name: String) = "$node › $name"
        override fun minutes(count: Int) = "$count min"
        override fun total(elapsed: String) = "$elapsed total"
        override fun spokenMinutes(count: Int) = "$count minute(s)"
        override fun staleAt(time: String) = "Not connected · as of $time"
        override fun elapsed() = "Elapsed"
    }

    private val node = "secret-host"
    private val agent = "bob"
    private val other = "alice"
    private val task = "fix the payroll bug"
    private val now = 10 * 60_000L
    private val since = now - 4 * 60_000L

    private fun row(name: String, status: AgentStatus, since: Long? = this.since, worked: Long? = null, pane: String = "p1") =
        LiveRow("n", node, pane, name, task, status, since, worked)

    private fun card(vararg rows: LiveRow, stale: Long? = null) = LiveCard(rows.toList(), stale)

    private fun fields(card: LiveCard, level: LockDetail, locked: Boolean = true) = liveFields(t, card, level, locked, now) { "12:00" }

    private fun LiveFace.words() = listOfNotNull(title, text, big, chip, spoken) + metrics.map { it.label }

    private fun assertNone(label: String, face: LiveFace, secrets: List<String>) {
        for (w in face.words()) for (s in secrets) assertFalse("$label: \"$w\" contains \"$s\"", w.contains(s))
    }

    /** One card per state the live update can show, two agents where the count matters. */
    private val shown = mapOf(
        "working" to card(row(agent, AgentStatus.Working), row(other, AgentStatus.Blocked, pane = "p2")),
        "done" to card(row(agent, AgentStatus.Done, worked = 7 * 60_000L)),
        "disconnected" to card(row(agent, AgentStatus.Working), stale = 500),
    )

    @Test
    fun hiddenNamesNothingAndShowsNoStateOrTime() {
        shown.forEach { (label, card) ->
            val f = fields(card, LockDetail.Hidden)!!
            for (face in listOf(f.main, f.public)) {
                assertEquals(label, LiveFace(LiveGlyph.Live, "Herdline", "Unlock for details", null, "", null, emptyList(), "Herdline live task"), face)
                assertNone(label, face, listOf(node, agent, other, task, "status:", "min", "Not connected", "12:00"))
            }
        }
    }

    @Test
    fun stateOnlyShowsTheStateAndItsTimeButNoNamesOrCounts() {
        val working = fields(shown.getValue("working"), LockDetail.StateOnly)!!
        assertEquals(LiveGlyph.Working, working.main.glyph)
        assertEquals("Herdline · status:Working", working.main.title)
        assertEquals("status:Working · 4 min", working.main.text)
        assertEquals("Herdline · status:Working, 4 minute(s)", working.main.spoken)
        // The chronometer counts in the chip.
        assertNull(working.main.chip)
        assertEquals(since, working.main.since)
        shown.forEach { (label, card) ->
            val f = fields(card, LockDetail.StateOnly)!!
            assertNone(label, f.main, listOf(node, agent, other, task, "2", "live task"))
            assertTrue(label, f.main.metrics.isEmpty())
        }
    }

    @Test
    fun stateOnlyDoneSaysHowLongTheRunTookWithoutAClock() {
        val done = fields(shown.getValue("done"), LockDetail.StateOnly)!!.main
        assertEquals(LiveGlyph.Done, done.glyph)
        assertEquals("status:Done · 7 min total", done.text)
        assertEquals("status:Done", done.chip)
        assertNull(done.since)
    }

    @Test
    fun namesOnlyNamesAgentsButNeverATaskOrATime() {
        val working = fields(shown.getValue("working"), LockDetail.NamesOnly)!!.main
        assertEquals("2 live task(s) · bob — status:Working", working.title)
        assertEquals("bob — status:Working · alice — status:Blocked", working.text)
        assertEquals("", working.chip)
        shown.forEach { (label, card) ->
            val f = fields(card, LockDetail.NamesOnly)!!
            assertNone(label, f.main, listOf(task, node, "min", "12:00"))
            assertNull(label, f.main.since)
            assertTrue(label, f.main.metrics.isEmpty())
        }
    }

    @Test
    fun anIdleOrDonePinIsNotCountedAsALiveTask() {
        // QA LU-C: alice Idle beside bob Working is one live task, not two.
        val idle = fields(card(row(agent, AgentStatus.Working), row(other, AgentStatus.Idle, pane = "p2")), LockDetail.NamesOnly)!!.main
        assertEquals("bob · status:Working", idle.title)
        val done = fields(card(row(agent, AgentStatus.Working), row(other, AgentStatus.Done, pane = "p2")), LockDetail.Full, locked = false)!!.main
        assertEquals("bob · status:Working", done.title)
        val two = fields(card(row(agent, AgentStatus.Working), row(other, AgentStatus.Working, pane = "p2"), row("carol", AgentStatus.Idle, pane = "p3")), LockDetail.NamesOnly)!!.main
        assertEquals("2 live task(s) · bob — status:Working", two.title)
    }

    @Test
    fun fullWhileLockedShowsPlaceTimesAndCountsButNoTask() {
        val f = fields(card(row(agent, AgentStatus.Working)), LockDetail.Full)!!
        assertEquals("bob · status:Working", f.main.title)
        assertEquals("secret-host › bob", f.main.text)
        assertEquals(since, f.main.since)
        assertEquals(
            listOf(LiveMetric.Elapsed("Elapsed", since), LiveMetric.Count("status:Working", 1)),
            f.main.metrics,
        )
        assertNone("full locked", f.main, listOf(task))
    }

    @Test
    fun fullCountsWhoIsWaitingWhileOthersWork() {
        val metrics = fields(shown.getValue("working"), LockDetail.Full)!!.main.metrics
        assertEquals(LiveMetric.Count("status:Blocked", 1), metrics.last())
    }

    @Test
    fun theTaskTitleShowsOnlyUnlockedAndNeverInThePublicVersion() {
        for (level in LockDetail.entries) {
            val f = fields(card(row(agent, AgentStatus.Working)), level, locked = false)!!
            assertTrue("$level main", f.main.words().any { it.contains(task) })
            assertNone("$level public", f.public, listOf(task))
        }
    }

    @Test
    fun thePublicVersionIsTheLockedMainContentAtEveryLevel() {
        for (level in LockDetail.entries) {
            shown.forEach { (label, card) ->
                val f = fields(card, level)!!
                assertEquals("$level $label", f.main, f.public)
            }
        }
    }

    @Test
    fun aDisconnectedPinShowsNoClockAndIsNotPromoted() {
        for (level in LockDetail.entries) {
            val f = fields(shown.getValue("disconnected"), level)!!
            assertEquals(LiveState.Disconnected, f.state)
            assertFalse(level.name, f.promote)
            assertNull(level.name, f.main.since)
            assertTrue(level.name, f.main.metrics.isEmpty())
            assertNone(level.name, f.main, listOf("min", "status:Working"))
        }
        assertEquals("Not connected · as of 12:00", fields(shown.getValue("disconnected"), LockDetail.Full)!!.main.text)
    }

    @Test
    fun aStatusWithNoSeenStartShowsNoTime() {
        for (level in LockDetail.entries) {
            val f = fields(card(row(agent, AgentStatus.Working, since = null)), level)!!
            assertNull(level.name, f.main.since)
            assertNone(level.name, f.main, listOf("min"))
            assertTrue(level.name, f.main.metrics.none { it is LiveMetric.Elapsed })
        }
    }

    @Test
    fun underAMinuteSaysNoMinutesRatherThanZero() {
        val f = fields(card(row(agent, AgentStatus.Working, since = now - 30_000)), LockDetail.StateOnly)!!
        assertEquals("status:Working", f.main.text)
    }

    @Test
    fun blockedIdleAndUnknownPinsShowNoLiveUpdate() {
        for (status in listOf(AgentStatus.Blocked, AgentStatus.Idle, AgentStatus.Unknown)) {
            for (level in LockDetail.entries) assertNull("$status $level", fields(card(row(agent, status)), level))
        }
        assertNull(liveFields(t, null, LockDetail.Full, locked = true, now) { "12:00" })
    }

    @Test
    fun workingIsPromotedAndOffersUnpinWhileDoneUnpinsItself() {
        val working = fields(shown.getValue("working"), LockDetail.StateOnly)!!
        assertTrue(working.promote)
        assertTrue(working.unpin)
        val done = fields(shown.getValue("done"), LockDetail.StateOnly)!!
        assertTrue(done.promote)
        assertFalse(done.unpin)
    }

    @Test
    fun workingOutranksDoneForTheGlyphAndTheTopRow() {
        val f = fields(card(row(other, AgentStatus.Done, pane = "p2"), row(agent, AgentStatus.Working)), LockDetail.Full)!!
        assertEquals(LiveGlyph.Working, f.main.glyph)
        assertEquals(agent, f.top.name)
    }

    @Test
    fun thePinSheetPreselectsTheSavedLevelEvenHidden() {
        assertEquals(LockDetail.Hidden, pinPreset(live = null, saved = LockDetail.Hidden))
        assertEquals(LockDetail.StateOnly, pinPreset(live = null, saved = null))
        assertEquals(LockDetail.Full, pinPreset(live = LockDetail.Full, saved = LockDetail.Hidden))
    }

    @Test
    fun untilAPinChoosesOneTheLiveUpdateKeepsTheNotificationLevel() {
        assertEquals(LockDetail.Hidden, liveLevel(live = null, alerts = LockDetail.Hidden))
        assertEquals(LockDetail.StateOnly, liveLevel(live = LockDetail.StateOnly, alerts = LockDetail.Full))
    }

    @Test
    fun dismissingTheCardUnpinsOnlyWhatTheLiveUpdateShowed() {
        // A live update names every row, the Idle one beside the Working one.
        val working = card(row(agent, AgentStatus.Working), row(other, AgentStatus.Idle, pane = "p2"))
        assertEquals(setOf(MonitorKey("n", "p1"), MonitorKey("n", "p2")), dismissedPins(working))
        // The plain Attached content names no pin: a swipe of it keeps them all (NB-C2).
        assertEquals(emptySet<MonitorKey>(), dismissedPins(card(row(agent, AgentStatus.Idle), row(other, AgentStatus.Blocked, pane = "p2"))))
        assertEquals(emptySet<MonitorKey>(), dismissedPins(null))
    }
}
