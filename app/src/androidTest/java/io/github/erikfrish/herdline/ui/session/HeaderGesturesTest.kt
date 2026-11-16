/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Real-touch regression coverage for [headerSwipes], on the place button's own
 * shape: a 96 dp wide, 48 dp high target with `combinedClickable` after the
 * detector, exactly as the header uses it. Every gesture below is injected as
 * touch events, never invoked as a callback.
 *
 * Pinned behaviour: an intentional swipe (past [SwipeTravel = 24] dp, clearly
 * horizontal or vertical) fires exactly one action, on release only, and
 * cancels the click; taps and long presses keep their clicks and menus; a
 * diagonal, a second finger, a cancelled gesture or a changed target
 * navigates nowhere and never falls through as a click.
 */
@OptIn(ExperimentalFoundationApi::class)
class HeaderGesturesTest {
    @get:Rule
    val compose = createComposeRule()

    private val swipes = mutableListOf<HeaderSwipe>()
    private val presses = mutableListOf<String>()
    private var enabled by mutableStateOf(true)
    private var target by mutableStateOf<Any?>(Any())

    private fun host() = compose.setContent {
        Box(Modifier.size(360.dp, 640.dp).padding(start = 96.dp, top = 120.dp)) {
            Box(
                Modifier
                    .size(96.dp, 48.dp)
                    .headerSwipes(enabled, target) { swipes += it }
                    .combinedClickable(onClick = { presses += "click" }, onLongClick = { presses += "long" })
                    .testTag("place"),
            )
        }
    }

    private fun place() = compose.onNodeWithTag("place")

    /** The button's centre and one travel step, in pixels at this density. */
    private fun within(block: TouchInjectionScope.(Offset, Float) -> Unit) {
        place().performTouchInput {
            block(center, 24.dp.toPx())
        }
        compose.waitForIdle()
    }

    @Test
    fun aSwipeNavigatesExactlyOnceOnReleaseAndNeverOpensTabs() {
        host()
        for ((dx, expected) in listOf(-1f to HeaderSwipe.Left, 1f to HeaderSwipe.Right)) {
            swipes.clear()
            presses.clear()
            within { c, travel ->
                down(c)
                moveTo(c + Offset(dx * 2 * travel, 0f))
                // Fires on release only, never in flight.
                assertEquals(emptyList<HeaderSwipe>(), swipes.toList())
                up()
            }
            assertEquals(listOf(expected), swipes.toList())
            assertEquals(emptyList<String>(), presses.toList())
        }
    }

    @Test
    fun tapsAndLongPressKeepThePlacesOwnClickAndMenu() {
        host()
        place().performTouchInput { click(center) }
        assertEquals(listOf("click"), presses.toList())
        assertEquals(emptyList<HeaderSwipe>(), swipes.toList())

        presses.clear()
        place().performTouchInput { longClick(center) }
        assertEquals(listOf("long"), presses.toList())
        assertEquals(emptyList<HeaderSwipe>(), swipes.toList())

        // Disabled navigation must not turn an intentional in-bounds drag into the title's click.
        enabled = false
        compose.waitForIdle()
        presses.clear()
        for (direction in listOf(-1f, 1f)) {
            within { c, travel ->
                down(c - Offset(direction * travel, 0f))
                moveTo(c + Offset(direction * travel, 0f))
                up()
            }
            assertEquals(emptyList<HeaderSwipe>(), swipes.toList())
            assertEquals(emptyList<String>(), presses.toList())
        }
        place().performTouchInput { click(center) }
        assertEquals(listOf("click"), presses.toList())
        presses.clear()
        place().performTouchInput { longClick(center) }
        assertEquals(listOf("long"), presses.toList())
    }

    @Test
    fun aFingerEndingOutsideThe48dpTitleStillNavigates() {
        host()
        within { c, travel ->
            down(c)
            moveTo(c + Offset(0f, 3 * travel)) // ends well below the 48 dp button
            assertEquals(emptyList<HeaderSwipe>(), swipes.toList())
            up()
        }
        assertEquals(listOf(HeaderSwipe.Down), swipes.toList())

        swipes.clear()
        within { c, travel ->
            down(c)
            moveTo(c - Offset(0f, 3 * travel)) // ends above it
            up()
        }
        assertEquals(listOf(HeaderSwipe.Up), swipes.toList())
        assertEquals(emptyList<String>(), presses.toList())
    }

    @Test
    fun aDiagonalASecondFingerATargetChangeAndACancelNeverNavigateOrClick() {
        host()
        // A diagonal past the travel is a failed swipe: no action, no click.
        within { c, travel ->
            down(c + Offset(-travel, -travel))
            moveTo(c + Offset(travel, travel))
            up()
        }
        assertEquals(emptyList<HeaderSwipe>(), swipes.toList())
        presses.clear()

        // A second finger ends recognition for the whole sequence.
        within { c, travel ->
            down(c)
            moveTo(c + Offset(2 * travel, 0f))
            down(1, c - Offset(2 * travel, 0f))
            up(1)
            up()
        }
        assertEquals(emptyList<HeaderSwipe>(), swipes.toList())
        assertEquals(emptyList<String>(), presses.toList())

        // A changing target aborts the in-flight swipe, and its release must
        // not fall through as a click.
        within { c, travel -> down(c); moveTo(c + Offset(2 * travel, 0f)) }
        target = Any()
        compose.waitForIdle()
        within { _, _ -> up() }
        assertEquals(emptyList<HeaderSwipe>(), swipes.toList())
        assertEquals(emptyList<String>(), presses.toList())

        // A cancelled gesture is not a swipe but stops the click as well.
        within { c, travel ->
            down(c)
            moveTo(c + Offset(2 * travel, 0f))
            cancel()
        }
        compose.waitForIdle()
        assertTrue(swipes.isEmpty())
        assertTrue(presses.isEmpty())
    }
    @Test
    fun chatHorizontalSwipesNavigateWhileVerticalDragsStillScroll() {
        var scroll: androidx.compose.foundation.ScrollState? = null
        var detector by mutableStateOf(false)
        compose.setContent {
            val state = rememberScrollState()
            scroll = state
            Box(
                Modifier.size(300.dp, 400.dp)
                    .then(if (detector) Modifier.headerSwipes(true, target, horizontalOnly = true) { swipes += it } else Modifier)
                    .testTag("chat"),
            ) {
                Text(
                    (1..200).joinToString("\n") { "Message $it" },
                    Modifier.fillMaxSize().verticalScroll(state).testTag("chat:scroll"),
                )
            }
        }
        val parentBounds = compose.onNodeWithTag("chat").fetchSemanticsNode().boundsInRoot
        val scrollBounds = compose.onNodeWithTag("chat:scroll").fetchSemanticsNode().boundsInRoot
        assertEquals(parentBounds.width, scrollBounds.width, 1f)
        assertEquals(parentBounds.height, scrollBounds.height, 1f)
        compose.onNodeWithTag("chat:scroll").performTouchInput { swipeUp() }
        compose.runOnIdle { assertTrue("The fixture itself must scroll", scroll!!.value > 0) }
        compose.runOnIdle { detector = true }
        compose.onNodeWithTag("chat").performTouchInput {
            down(center)
            moveTo(center + Offset(-120.dp.toPx(), 0f), 100)
            up()
        }
        compose.runOnIdle { assertEquals(listOf(HeaderSwipe.Left), swipes.toList()) }
        val before = compose.runOnIdle { scroll!!.value }
        compose.onNodeWithTag("chat:scroll").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertTrue("Chat must retain vertical scrolling", scroll!!.value > before)
            assertEquals(listOf(HeaderSwipe.Left), swipes.toList())
        }
    }
}
