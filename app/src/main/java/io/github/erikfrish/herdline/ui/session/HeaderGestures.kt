/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * One intentional swipe recognised on the session header's place (title)
 * button. Directions are physical: [Left] and [Right] are the pixels' X axis
 * whatever the layout direction, [Down] is a finger travelling down the
 * screen.
 */
internal enum class HeaderSwipe { Left, Right, Up, Down }

/**
 * How far the finger must travel, in its dominant axis, before a swipe is
 * intentional: past any touch slop, but kind to the narrow header (the
 * button can be 96 dp wide and 48 dp high). A movement reaching it that is
 * not clearly dominant (a diagonal) navigates nowhere and, being a failed
 * swipe, never clicks either. Below it the press is an ordinary tap or long
 * press.
 */
private val SwipeTravel = 24.dp

/**
 * Single-finger swipes on the session header's place button: [HeaderSwipe.Left]
 * / [Right] change tab, [HeaderSwipe.Down] opens Tabs, [HeaderSwipe.Up] hides
 * the keyboard. This sits on the button's modifier before `combinedClickable`,
 * which keeps its tap (Tabs) and long press (Rename / Close) untouched.
 *
 * A swipe fires exactly once, on release, and only then. The click of a
 * claimed swipe is cancelled by consuming movement changes from the claim on,
 * on the [PointerEventPass.Initial] pass, so the click sees the consumption in
 * the very same event. Recognition dies — nothing fires — after a second
 * finger touches even briefly, after the long press time (which belongs to
 * the menu), when some other handler consumes the gesture, or when [target]
 * changes while the finger is down. Such a release is consumed too, so it
 * never falls through as a click. With [enabled] false or a null [target],
 * navigation stays off but drags still cancel the title click. Ordinary
 * taps and long presses keep their existing actions.
 *
 * [target] identifies the host / session / workspace / tab the press belongs
 * to. It is captured at touch down and compared by identity, so any change —
 * a different token, or none at all — aborts the in-flight swipe. [onSwipe]
 * is always read through `rememberUpdatedState`, so the recognition coroutine
 * is never restarted (and never races) by a recomposition.
 */
internal fun Modifier.headerSwipes(
    enabled: Boolean,
    target: Any?,
    onSwipe: (HeaderSwipe) -> Unit,
): Modifier = composed {
    val enabledNow by rememberUpdatedState(enabled)
    val targetNow by rememberUpdatedState(target)
    val onSwipeNow by rememberUpdatedState(onSwipe)
    Modifier.pointerInput(Unit) {
        val travel = maxOf(SwipeTravel.toPx(), viewConfiguration.touchSlop)
        val longPressMs = viewConfiguration.longPressTimeoutMillis
        awaitEachGesture {
            // One pointer, one gesture: wait for an event carrying a fresh press.
            var event = awaitPointerEvent(PointerEventPass.Initial)
            while (event.changes.none { it.pressed && !it.previousPressed }) {
                event = awaitPointerEvent(PointerEventPass.Initial)
            }
            val down = event.changes.first { it.pressed && !it.previousPressed }
            val id = down.id
            val startX = down.position.x
            val startY = down.position.y
            val startTime = down.uptimeMillis
            // Captured when the finger lands; changing either aborts the swipe.
            val captured = targetNow
            val armed = enabledNow && captured != null
            var navigate: HeaderSwipe? = null
            var swallow = false
            var dead = false
            if (event.changes.size > 1) {
                dead = true // fingers landing together: multi-touch, no recognition
                swallow = true
            }
            if (swallow) event.changes.forEach { it.consume() }

            while (true) {
                event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == id }
                if (event.changes.size > 1) {
                    dead = true // multi-touch ends recognition for good
                    swallow = true
                }
                if (change == null || change.isConsumed) dead = true
                if (armed && (!enabledNow || targetNow !== captured)) {
                    dead = true
                    swallow = true
                }
                if (!dead && navigate == null && change != null) {
                    val dx = change.position.x - startX
                    val dy = change.position.y - startY
                    if (maxOf(abs(dx), abs(dy)) >= travel) {
                        if (change.uptimeMillis - startTime >= longPressMs) {
                            swallow = true // the long press's tail belongs to the menu, its end is no swipe
                            dead = true
                        } else {
                            swallow = true // a failed swipe never clicks either
                            navigate = when {
                                !armed -> { dead = true; null }
                                abs(dx) >= 2 * abs(dy) -> if (dx > 0) HeaderSwipe.Right else HeaderSwipe.Left
                                abs(dy) >= 2 * abs(dx) -> if (dy > 0) HeaderSwipe.Down else HeaderSwipe.Up
                                else -> { dead = true; null }
                            }
                        }
                    }
                }
                if (change == null || !change.pressed) {
                    val stable = enabledNow && targetNow === captured
                    when {
                        navigate != null && !dead && stable -> {
                            onSwipeNow(navigate)
                            event.changes.forEach { it.consume() }
                        }
                        swallow || (armed && !stable) -> event.changes.forEach { it.consume() }
                    }
                    break
                }
                if (swallow) event.changes.forEach { it.consume() }
            }
        }
    }
}
