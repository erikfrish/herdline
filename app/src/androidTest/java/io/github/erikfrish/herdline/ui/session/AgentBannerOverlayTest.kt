package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * BN-1 §4: the banner lies over the terminal; the box under it keeps one
 * size while a banner comes and goes, so no resize can follow. A swipe
 * either way or ✕ takes it away, and Open names exactly the note's pane.
 */
class AgentBannerOverlayTest {
    @get:Rule
    val compose = createComposeRule()

    private var note by mutableStateOf<AgentNote?>(null)
    private val sizes = mutableListOf<IntSize>()
    private val opened = mutableListOf<String>()

    private fun host(openable: Boolean = true, paneThere: Boolean = true) = compose.setContent {
        HerdlineTheme(dark = true) {
            Box(Modifier.size(360.dp, 640.dp)) {
                Box(Modifier.fillMaxSize().onSizeChanged { sizes += it })
                AgentBannerSlot(
                    note,
                    openable = openable,
                    openLabel = "Discussion",
                    onOpen = { pane -> if (paneThere) opened += pane; paneThere },
                    onDismiss = { note = null },
                )
            }
        }
    }

    private fun post(kind: NoteKind, pane: String?, id: Long) {
        note = AgentNote("omp finished", "Lab › Discussion", kind, pane, id)
        compose.waitForIdle()
        compose.onNodeWithTag("banner").assertIsDisplayed()
    }

    private fun assertGone() {
        compose.waitForIdle()
        assertEquals(null, note)
        compose.onNodeWithTag("banner").assertDoesNotExist()
    }

    @Test
    fun theTerminalBoxKeepsItsSizeWhileABannerComesAndGoes() {
        host()
        post(NoteKind.Attention, "p2", 1)
        compose.onNodeWithTag("banner").performTouchInput { swipeLeft() }
        assertGone()
        post(NoteKind.Attention, "p2", 2)
        compose.onNodeWithTag("banner").performTouchInput { swipeRight() }
        assertGone()
        post(NoteKind.Attention, "p2", 3)
        compose.onNodeWithTag("banner:dismiss").performClick()
        assertGone()
        assertEquals(1, sizes.distinct().size)
    }

    @Test
    fun openFocusesExactlyTheNotesPaneAndClosesTheBanner() {
        host()
        post(NoteKind.Finished, "p2", 1)
        compose.onNodeWithTag("banner:open").performClick()
        assertGone()
        assertEquals(listOf("p2"), opened)
    }

    @Test
    fun openingAPaneThatClosedMeanwhileSaysSoAndFocusesNothing() {
        host(paneThere = false)
        post(NoteKind.Attention, "p2", 1)
        compose.onNodeWithTag("banner:open").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("That pane has closed").assertIsDisplayed()
        compose.onNodeWithTag("banner:open").assertDoesNotExist()
        assertEquals(emptyList<String>(), opened)
    }

    @Test
    fun aBannerItCannotOpenOffersNoOpen() {
        host(openable = false)
        post(NoteKind.Problem, null, 1)
        compose.onNodeWithTag("banner:open").assertDoesNotExist()
    }
}
