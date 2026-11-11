package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.service.NoteSurface
import io.github.erikfrish.herdline.service.routeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentBannerTest {
    private fun note(kind: NoteKind, pane: String?, id: Long) = AgentNote("omp finished", "Lab › Discussion", kind, pane, id)

    @Test
    fun aNotificationGoesToExactlyOnePlace() {
        // This host's session on screen and unlocked: the banner.
        assertEquals(NoteSurface.Banner, routeNote(viewing = true, locked = false, enabled = true))
        // Locked, or on Hosts, Settings or another host's session: the system notification, never lost.
        assertEquals(NoteSurface.System, routeNote(viewing = true, locked = true, enabled = true))
        assertEquals(NoteSurface.System, routeNote(viewing = false, locked = false, enabled = true))
        // Its switch off: nowhere, whatever the screen.
        assertEquals(NoteSurface.None, routeNote(viewing = true, locked = false, enabled = false))
        assertEquals(NoteSurface.None, routeNote(viewing = false, locked = true, enabled = false))
    }

    @Test
    fun attentionIsShownBeforeEarlierCompletionsWhichThenFollowInOrder() {
        val queue = BannerQueue()
            .add(note(NoteKind.Finished, "p1", 1))
            .add(note(NoteKind.Finished, "p2", 2))
            .add(note(NoteKind.Attention, "p3", 3))
        assertEquals(3L, queue.shown?.id)
        val next = queue.dismiss(3)
        assertEquals(1L, next.shown?.id)
        assertEquals(2L, next.dismiss(1).shown?.id)
        assertNull(next.dismiss(1).dismiss(2).shown)
    }

    @Test
    fun aNewerNoteForAPaneReplacesTheOlderOne() {
        val queue = BannerQueue()
            .add(note(NoteKind.Attention, "p1", 1))
            .add(note(NoteKind.Finished, "p2", 2))
            .add(note(NoteKind.Finished, "p1", 3))
        assertEquals(listOf(2L, 3L), queue.notes.map { it.id })
        // Two problems name no pane, so neither replaces the other.
        val problems = BannerQueue().add(note(NoteKind.Problem, null, 1)).add(note(NoteKind.Problem, null, 2))
        assertEquals(2, problems.notes.size)
    }

    @Test
    fun aCompletionOfThePaneOnScreenAndAnAnsweredQuestionGo() {
        val queue = BannerQueue()
            .add(note(NoteKind.Finished, "p1", 1))
            .add(note(NoteKind.Attention, "p2", 2))
            .add(note(NoteKind.Problem, null, 3))
            .add(note(NoteKind.Finished, null, 4))
        // p1 on screen, p2 still waiting: only p1's completion goes.
        assertEquals(listOf(2L, 3L, 4L), queue.settle(viewedPane = "p1", blocked = setOf("p2")).notes.map { it.id })
        // p2 left Blocked: its question goes too; a problem and an unnamed note stay.
        assertEquals(listOf(3L, 4L), queue.settle(viewedPane = "p1", blocked = emptySet()).notes.map { it.id })
        // Before the view is known, nothing is taken for answered.
        assertEquals(listOf(2L, 3L, 4L), queue.settle(viewedPane = "p1", blocked = null).notes.map { it.id })
    }
}
