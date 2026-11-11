package io.github.erikfrish.herdline.ui.session

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.Draft
import io.github.erikfrish.herdline.data.DraftKey
import io.github.erikfrish.herdline.data.DraftStore
import io.github.erikfrish.herdline.data.MemoryPrefs
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** lab.8.1: what may change a draft whose delivery may already be in its pane. */
class DraftWritesTest {
    private val a = DraftKey("node", "main", "A")
    private val b = DraftKey("node", "main", "B")
    private val c = DraftKey("node", "main", "C")
    private val written = Draft("fix the build", 13, 13, boot = "boot1", agent = "omp", updatedAt = 1)

    private fun sent(state: SentState) = SentRecord("fix the build", "", submit = true, state, at = 2)

    @Test
    fun aSaveScheduledBeforeTheWriteAheadNeverLandsOverIt() {
        val prefs = MemoryPrefs()
        val store = DraftStore(prefs)
        // The debounce took its generation, then the tap stored the record.
        val scheduled = store.generation(a)
        assertTrue(store.writeAhead(a, written.copy(sent = sent(SentState.Queued))))
        store.put(a, written.copy(text = "fix the build!", sent = null), scheduled)

        val back = store.get(a)
        assertEquals("fix the build", back?.text)
        // Read back after a restart, the record holds the pane.
        assertEquals(SentState.Uncertain, back?.sent?.state)

        // A save scheduled after the record still lands, and other panes never wait on it.
        store.put(a, back!!.copy(sent = sent(SentState.Uncertain)), store.generation(a))
        assertEquals(SentState.Uncertain, store.get(a)?.sent?.state)
        val other = store.generation(b)
        store.writeAhead(a, written.copy(sent = sent(SentState.Queued)))
        store.put(b, written.copy(text = "other pane"), other)
        assertEquals("other pane", store.get(b)?.text)
    }

    @Test
    fun aFrozenDraftIgnoresLateEditsAndImeCommits() {
        val frozen = listOf(
            "sending" to Pair(written, true),
            "queued" to Pair(written.copy(sent = sent(SentState.Queued)), false),
            "uncertain" to Pair(written.copy(sent = sent(SentState.Uncertain)), false),
        )
        for ((case, state) in frozen) {
            assertNull(case, editedDraft(state.first, state.second, "fix the build!", 14, 14))
        }
        for (settled in listOf(null, sent(SentState.Confirmed), sent(SentState.Unlocked))) {
            val edited = editedDraft(written.copy(sent = settled), false, "fix the build!", 14, 14)
            assertEquals("fix the build!", edited?.text)
            assertEquals(14, edited?.selectionStart)
        }
    }

    @Test
    fun unlockReleasesThePaneButKeepsTheAttemptAndSaysItMayHaveLanded() {
        val held = Draft.fromJson(written.copy(sent = sent(SentState.Queued)).toJson())
        val released = unlocked(held)

        assertEquals(held.text, released.text)
        assertEquals(held.boot, released.boot)
        assertEquals(held.agent, released.agent)
        assertEquals(held.sent?.text, released.sent?.text)
        assertTrue(released.mayDeliver(Binding.Bound))
        // The note outlives a restart: only the next delivery replaces it.
        assertEquals(SentState.Unlocked, Draft.fromJson(released.toJson()).sent?.state)
        // Nothing held, nothing to release.
        val confirmed = written.copy(sent = sent(SentState.Confirmed))
        assertEquals(confirmed, unlocked(confirmed))
    }

    @Test
    fun composerPasteEditsOnlyTheLocalDraftAndNeverAHeldOne() {
        val value = TextFieldValue("fix  build", TextRange(4))
        assertNull(pasteInto(value, "the", held = true))
        assertNull(pasteInto(value, "", held = false))

        val inserted = pasteInto(value, " the", held = false)
        assertEquals("fix  the build", inserted?.text)
        assertEquals(TextRange(8), inserted?.selection)

        // A selection, either way round, is replaced.
        val replaced = pasteInto(TextFieldValue("fix the build", TextRange(7, 4)), "a", held = false)
        assertEquals("fix a build", replaced?.text)
        assertEquals(TextRange(5), replaced?.selection)
    }

    @Test
    fun aMoveIsRefusedOnlyForAHeldSourceOrTarget() {
        val store = DraftStore(MemoryPrefs())
        store.writeAhead(a, written.copy(sent = sent(SentState.Queued)))

        assertTrue(moveRefused(sourceBusy = true, target = null))
        assertTrue(moveRefused(false, written.copy(sent = sent(SentState.Queued))))
        // A, read back as after a restart, is a held target.
        assertTrue(moveRefused(false, store.get(a)))
        assertFalse(moveRefused(false, written.copy(sent = sent(SentState.Confirmed))))
        assertFalse(moveRefused(false, written.copy(sent = sent(SentState.Unlocked))))
        assertFalse(moveRefused(false, null))
        // A held A never refuses a move from B onto C.
        assertFalse(moveRefused(false, store.get(c)))
    }

    @Test
    fun moveHereOfferSaysWhyAndAsksBeforeMovingUnlockedText() {
        val heldTarget = written.copy(sent = sent(SentState.Uncertain))
        // A held target is said as such, whatever the source is doing.
        assertEquals(MoveHereOffer.TargetHeld, moveHereOffer(sourceBusy = false, target = heldTarget, sourceUnlocked = false))
        assertEquals(MoveHereOffer.TargetHeld, moveHereOffer(sourceBusy = true, target = heldTarget, sourceUnlocked = true))
        // A busy source offers nothing, never the confirmation either.
        assertEquals(MoveHereOffer.Hidden, moveHereOffer(sourceBusy = true, target = null, sourceUnlocked = true))
        // Unlocked text may already be in its pane: asked first, onto an empty or settled pane.
        assertEquals(MoveHereOffer.ConfirmUnlocked, moveHereOffer(sourceBusy = false, target = null, sourceUnlocked = true))
        assertEquals(
            MoveHereOffer.ConfirmUnlocked,
            moveHereOffer(sourceBusy = false, target = written.copy(sent = sent(SentState.Confirmed)), sourceUnlocked = true),
        )
        assertEquals(MoveHereOffer.Offered, moveHereOffer(sourceBusy = false, target = null, sourceUnlocked = false))
    }

    @Test
    fun aRefusedMoveLeavesBothStoredDraftsByteIdentical() {
        val prefs = MemoryPrefs()
        val store = DraftStore(prefs)
        val held = written.copy(sent = sent(SentState.Uncertain))
        val settled = Draft("other text", 10, 10, boot = "boot1", agent = "omp", updatedAt = 3)

        // Held source A onto B; settled source B onto held A; every resolution.
        store.put(a, held)
        store.put(b, settled)
        val before = HashMap(prefs.values)
        for (resolution in MoveResolution.entries) {
            assertEquals(MoveOutcome.Refused, moveDraft(store, a, held, false, b, "boot1", "omp", resolution, now = 9))
            assertEquals(MoveOutcome.Refused, moveDraft(store, b, settled, false, a, "boot1", "omp", resolution, now = 9))
            // A source in flight, not yet recorded, is refused too.
            assertEquals(MoveOutcome.Refused, moveDraft(store, b, settled, true, c, "boot1", "omp", resolution, now = 9))
        }
        assertEquals(before, prefs.values)
    }

    @Test
    fun aHeldPaneDoesNotStopAMoveBetweenTwoOthers() {
        val store = DraftStore(MemoryPrefs())
        store.put(a, written.copy(sent = sent(SentState.Uncertain)))
        val moving = Draft("other text", 10, 10, boot = "boot1", agent = "omp", updatedAt = 3)
        store.put(b, moving)

        val outcome = moveDraft(store, b, moving, false, c, "boot1", "omp", MoveResolution.Ask, now = 9)

        assertTrue(outcome is MoveOutcome.Moved)
        assertEquals("other text", store.get(c)?.text)
        assertNull(store.get(b))
        assertEquals(SentState.Uncertain, store.get(a)?.sent?.state)
    }
}
