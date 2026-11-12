package io.github.erikfrish.herdline.data

import io.github.erikfrish.herdline.core.TranscriptIdentity
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DraftsTest {
    private val written = Draft(text = "fix the build", boot = "boot-1", agent = "omp")
    private val unread = InstanceRead.Unread
    private val alice = TranscriptIdentity("w1:p4", "term_4", 4100, "/h/.omp/agent/sessions/w/a.jsonl")
    private val forAlice = written.copy(instance = alice)

    @Test
    fun aWrittenDraftNeedsConfirmingAfterHerdrRestarts() {
        assertEquals(Binding.Bound, forAlice.binding("boot-1", "omp", InstanceRead.Read(alice)))
        assertEquals(Binding.BootChanged, forAlice.binding("boot-2", "omp", InstanceRead.Read(alice)))
        assertEquals(Binding.Bound, forAlice.rebound("boot-2", "omp", alice).binding("boot-2", "omp", InstanceRead.Read(alice)))
    }

    @Test
    fun aWrittenDraftNeedsConfirmingWhenAnotherProgramRunsInThePane() {
        assertEquals(Binding.AgentChanged, written.binding("boot-1", null, unread))
        assertEquals(Binding.AgentChanged, written.binding("boot-1", "claude", unread))
        assertEquals(Binding.Bound, forAlice.binding("boot-1", "OMP", InstanceRead.Read(alice)))
    }

    @Test
    fun anEmptyDraftFollowsThePaneAndOfflineWins() {
        assertEquals(Binding.Bound, Draft(boot = "boot-1", agent = "omp").binding("boot-9", null, unread))
        assertEquals(Binding.Offline, written.binding(null, "omp", unread))
    }

    @Test
    fun aDraftGoesOnlyToTheAgentInstanceItWasWrittenFor() {
        assertEquals(Binding.Bound, forAlice.binding("boot-1", "omp", InstanceRead.Read(alice)))
        val newSession = alice.copy(pid = 4200, path = "/h/.omp/agent/sessions/w/b.jsonl")
        assertEquals(Binding.AgentRestarted, forAlice.binding("boot-1", "omp", InstanceRead.Read(newSession)))
        // The same session resumed by another process is another instance too.
        assertEquals(Binding.AgentRestarted, forAlice.binding("boot-1", "omp", InstanceRead.Read(alice.copy(pid = 4200))))
        // Fail closed: nothing goes while the instance cannot be read.
        assertEquals(Binding.InstanceUnknown, forAlice.binding("boot-1", "omp", unread))
        assertEquals(Binding.InstanceUnknown, forAlice.binding("boot-1", "omp", InstanceRead.Read(null)))
        assertFalse(forAlice.mayDeliver(forAlice.binding("boot-1", "omp", unread)))
        // Rebind takes the instance now in the pane.
        val rebound = forAlice.rebound("boot-1", "omp", newSession)
        assertEquals(Binding.Bound, rebound.binding("boot-1", "omp", InstanceRead.Read(newSession)))
    }

    @Test
    fun aDraftWrittenBeforeTheSessionWasReadIsNeverSentToTheSessionReadLater() {
        // Typed while the pane's session was still unread; then a new session starts there.
        val later = alice.copy(pid = 4200, path = "/h/.omp/agent/sessions/w/b.jsonl")
        assertNull(written.followed("boot-1", "omp", InstanceRead.Read(later)))
        assertEquals(Binding.InstanceUnknown, written.binding("boot-1", "omp", InstanceRead.Read(later)))
        assertEquals(Binding.InstanceUnknown, written.binding("boot-1", "omp", unread))
        assertFalse(written.mayDeliver(written.binding("boot-1", "omp", InstanceRead.Read(later))))
        // Only the user's Rebind binds it to the session read now.
        val rebound = written.rebound("boot-1", "omp", later)
        assertTrue(rebound.mayDeliver(rebound.binding("boot-1", "omp", InstanceRead.Read(later))))
        // A plain terminal has no session to read: its draft stays bound.
        val shell = Draft(text = "ls", boot = "boot-1", agent = null)
        assertTrue(shell.mayDeliver(shell.binding("boot-1", null, InstanceRead.Read(null))))
        // An empty draft follows the pane, instance included.
        val empty = Draft(boot = "boot-1", agent = "omp", instance = alice)
        assertEquals(empty.copy(instance = later), empty.followed("boot-1", "omp", InstanceRead.Read(later)))
        assertEquals(Draft(boot = "boot-2", agent = "claude"), empty.followed("boot-2", "claude", unread))
    }

    @Test
    fun anUnsettledDeliveryHoldsSendAndInsert() {
        val uncertain = SentRecord("fix the build", "r7", submit = true, SentState.Uncertain, at = 5)
        assertFalse(written.copy(sent = uncertain).mayDeliver(Binding.Bound))
        assertFalse(written.copy(sent = uncertain.copy(state = SentState.Queued)).mayDeliver(Binding.Bound))
        assertTrue(written.copy(sent = uncertain.copy(state = SentState.Confirmed)).mayDeliver(Binding.Bound))
        // Held across a restart: the record reads back as it was.
        val back = Draft.fromJson(JSONObject(written.copy(sent = uncertain).toJson().toString()))
        assertTrue(back.unsettled)
    }

    @Test
    fun theInstanceSurvivesStorage() {
        assertEquals(forAlice, Draft.fromJson(JSONObject(forAlice.toJson().toString())))
        // Drafts stored before instances read back without one.
        assertNull(Draft.fromJson(JSONObject().put("text", "old")).instance)
    }

    @Test
    fun aDeliveryStillQueuedWhenReadBackIsUncertain() {
        val queued = written.copy(sent = SentRecord("fix the build", "r7", submit = true, SentState.Queued, at = 5))
        val back = Draft.fromJson(JSONObject(queued.toJson().toString()))
        assertEquals(SentState.Uncertain, back.sent?.state)
        assertEquals(written.copy(sent = back.sent), back)
    }

    @Test
    fun selectionIsClampedToTheText() {
        val back = Draft.fromJson(JSONObject().put("text", "ab").put("start", 9).put("end", -1))
        assertEquals(2, back.selectionStart)
        assertEquals(0, back.selectionEnd)
    }

    @Test
    fun onlyAnEmptyDraftFollowsTheViewedPane() {
        assertEquals("w1:p2", composerDestination(bound = null, viewed = "w1:p2", boundHasText = false, boundHeld = false))
        assertEquals("w1:p2", composerDestination(bound = "w1:p1", viewed = "w1:p2", boundHasText = false, boundHeld = false))
        assertEquals("w1:p1", composerDestination(bound = "w1:p1", viewed = "w1:p2", boundHasText = true, boundHeld = false))
        // Nothing viewed yet, as while reconnecting: keep the pane rather than drop it.
        assertEquals("w1:p1", composerDestination(bound = "w1:p1", viewed = null, boundHasText = false, boundHeld = false))
    }

    @Test
    fun aHeldDraftLetsAnotherPaneHaveTheComposerAndComesBackWithItsPane() {
        // HB1: text whose delivery is queued or uncertain cannot be edited or sent, so it must not pin.
        val store = DraftStore(MemoryPrefs())
        val p1 = DraftKey("n", "s", "w1:p1")
        val held = Draft(text = "fix it", sent = SentRecord("fix it", "", submit = true, SentState.Uncertain, at = 1))
        store.put(p1, held)
        val heldNow = { pane: String -> store.releasesComposer(DraftKey("n", "s", pane)) }

        val away = composerDestination(bound = p1.pane, viewed = "w1:p2", boundHasText = true, boundHeld = heldNow(p1.pane))
        assertEquals("w1:p2", away)
        // Viewing p1 again brings its held draft back, unchanged.
        assertEquals(p1.pane, composerDestination(bound = away, viewed = p1.pane, boundHasText = false, boundHeld = heldNow(away!!)))
        assertEquals(held, store.get(p1))
        // Nothing viewed, as while reconnecting: the held draft keeps its pane.
        assertEquals(p1.pane, composerDestination(bound = p1.pane, viewed = null, boundHasText = true, boundHeld = true))
    }

    @Test
    fun aDeliveryInFlightKeepsTheComposerOnItsPaneUntilItSettles() {
        // QA HB2: the receipt is awaited only in p1's editor; leaving p1 then would drop it.
        val store = DraftStore(MemoryPrefs())
        val p1 = DraftKey("n", "s", "w1:p1")
        store.put(p1, Draft(text = "fix it", sent = SentRecord("fix it", "", submit = true, SentState.Queued, at = 1)))
        store.markInFlight(p1, true)
        val switched = { composerDestination(bound = p1.pane, viewed = "w1:p2", boundHasText = true, boundHeld = store.releasesComposer(p1)) }

        assertEquals(p1.pane, switched())
        // No answer came: the editor records Uncertain and stops waiting, which lets the composer go.
        store.put(p1, Draft(text = "fix it", sent = SentRecord("fix it", "r1", submit = true, SentState.Uncertain, at = 2)))
        store.markInFlight(p1, false)
        assertEquals("w1:p2", switched())
        assertEquals(emptySet<DraftKey>(), store.inFlight.value)
    }

    @Test
    fun onlyAnUnconfirmedDeliveryOfThisSessionIsHeld() {
        val store = DraftStore(MemoryPrefs())
        fun record(state: SentState) = Draft(text = "fix it", sent = SentRecord("fix it", "", submit = true, state, at = 1))
        SentState.entries.forEach { store.put(DraftKey("n", "s", it.name), record(it)) }
        store.put(DraftKey("n", "s", "none"), Draft(text = "fix it"))
        // The same held state elsewhere is not this session's.
        store.put(DraftKey("n", "other", "w1:p1"), record(SentState.Uncertain))
        store.put(DraftKey("m", "s", "w1:p2"), record(SentState.Queued))

        // In the input box but not in the history (P1) holds the pane too.
        assertEquals(setOf(SentState.Queued.name, SentState.InPane.name, SentState.Uncertain.name), store.held("n", "s"))
    }

    @Test
    fun unlockingPublishesTheHeldDraftChangeWithoutAHostUpdate() {
        val prefs = MemoryPrefs()
        val store = DraftStore(prefs)
        val key = DraftKey("n", "s", "w1:p1")
        val other = DraftKey("n", "other", "w1:p2")
        val queued = Draft(text = "fix it", sent = SentRecord("fix it", "r", true, SentState.Queued, 1))
        store.put(other, queued)
        assertTrue(store.writeAhead(key, queued))
        assertEquals(setOf(key, other), store.unconfirmed.value)

        val restored = DraftStore(prefs)
        assertEquals(setOf(key, other), restored.unconfirmed.value)
        val unlocked = restored.get(key)!!.let { it.copy(sent = it.sent!!.copy(state = SentState.Unlocked)) }
        restored.put(key, unlocked)
        assertEquals(setOf(other), restored.unconfirmed.value)
        assertEquals(emptySet<String>(), restored.held("n", "s"))
        assertEquals("fix it", restored.get(key)?.text)
        assertEquals(SentState.Unlocked, restored.get(key)?.sent?.state)
    }

    @Test
    fun aMovedDraftIsBoundToItsNewPaneAndNeverOverwritesOne() {
        val sent = SentRecord("old", "r1", submit = true, SentState.Confirmed, at = 1)
        val moved = written.copy(selectionStart = 3, selectionEnd = 3).movedOnto(Draft(sent = sent), "boot-2", "claude", now = 9)
        assertEquals(Draft("fix the build", 3, 3, "boot-2", "claude", 9, sent), moved)
        // Onto a chat agent its session is not known yet: held until Rebind.
        assertEquals(Binding.InstanceUnknown, moved?.binding("boot-2", "claude", unread))
        assertNull(written.movedOnto(Draft(text = "theirs"), "boot-1", "omp", now = 9))
    }

    @Test
    fun replacingPutsThisDraftOnThePaneAndKeepsItsReceipt() {
        val sent = SentRecord("old", "r1", submit = true, SentState.Confirmed, at = 1)
        val theirs = Draft(text = "theirs", boot = "boot-1", agent = "omp", sent = sent)
        val moved = written.replacedOnto(theirs, "boot-2", "claude", now = 9)
        assertEquals(Draft("fix the build", 0, 0, "boot-2", "claude", 9, sent), moved)
        assertEquals(Binding.InstanceUnknown, moved.binding("boot-2", "claude", unread))
    }

    @Test
    fun appendingAddsThisDraftAfterTheTargetOwn() {
        val theirs = Draft(text = "theirs", boot = "boot-1", agent = "omp")
        val moved = written.appendedOnto(theirs, "boot-2", "claude", now = 9)
        assertEquals("a\nb", appendText("a", "b"))
        assertEquals("only", appendText("", "only"))
        assertEquals("only", appendText("only", ""))
    }
}
