package io.github.erikfrish.herdline.ui

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import io.github.erikfrish.herdline.data.LayoutMode

/**
 * Material's window classes, one rule for the whole app: the root bar or
 * rail and the session sidebar both read it, so their thresholds never
 * disagree (COLLIE row 5).
 */
enum class WindowClass { Compact, Medium, Expanded }

internal const val MEDIUM_WIDTH_DP = 600
internal const val EXPANDED_WIDTH_DP = 840

/** Below this height a wide window is a phone held sideways, and stays compact. */
internal const val WIDE_MIN_HEIGHT_DP = 480

fun windowClass(widthDp: Int, heightDp: Int): WindowClass = when {
    heightDp < WIDE_MIN_HEIGHT_DP || widthDp < MEDIUM_WIDTH_DP -> WindowClass.Compact
    widthDp < EXPANDED_WIDTH_DP -> WindowClass.Medium
    else -> WindowClass.Expanded
}

/**
 * While the keyboard is up, a window never drops to a narrower class than
 * it had, so typing cannot swap the layout around the composer; it settles
 * once the keyboard closes. Growing wider is taken at once.
 */
fun heldWindowClass(measured: WindowClass, previous: WindowClass?, imeVisible: Boolean): WindowClass =
    if (imeVisible && previous != null && previous > measured) previous else measured

/** Whether a session keeps Herdr's sidebar (open or as a rail): the user's choice, else the window. */
fun sessionSidebar(mode: LayoutMode, window: WindowClass): Boolean = when (mode) {
    LayoutMode.Wide -> true
    LayoutMode.Compact -> false
    LayoutMode.Auto -> window == WindowClass.Expanded
}

/** The narrowest side composer: its one-line field beside 48 dp targets. */
internal const val SIDE_COMPOSER_MIN_DP = 280

/** The widest side composer: more width would only take it from the content. */
internal const val SIDE_COMPOSER_MAX_DP = 420

/** The side composer's share of the window's width. */
internal const val SIDE_COMPOSER_FRACTION = 0.36f

/** What the terminal or transcript keeps beside the side composer, at least. */
internal const val SIDE_CONTENT_MIN_DP = 360

/**
 * The width of the composer as a column beside the content, or null when it
 * stays below it (lab.8.1 J2, parent ruling S). Only a sideways phone that
 * is typing ([typingShort]) gives it the side: the keyboard leaves too
 * little height to stack the content over the composer. The column takes
 * [SIDE_COMPOSER_FRACTION] of [windowWidthDp], clamped to
 * [SIDE_COMPOSER_MIN_DP]..[SIDE_COMPOSER_MAX_DP], and only where the
 * content keeps [SIDE_CONTENT_MIN_DP] beside it.
 */
fun sideComposerWidthDp(windowWidthDp: Float, typingShort: Boolean): Float? {
    if (!typingShort) return null
    val width = (windowWidthDp * SIDE_COMPOSER_FRACTION).coerceIn(SIDE_COMPOSER_MIN_DP.toFloat(), SIDE_COMPOSER_MAX_DP.toFloat())
    return width.takeIf { windowWidthDp - it >= SIDE_CONTENT_MIN_DP }
}

/** The class [rememberWindowClass] computed at the root. */
val LocalWindowClass = staticCompositionLocalOf { WindowClass.Compact }

/** The window's class, held across the keyboard; call once, at the root. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberWindowClass(): WindowClass {
    val configuration = LocalConfiguration.current
    val measured = windowClass(configuration.screenWidthDp, configuration.screenHeightDp)
    val last = remember { arrayOfNulls<WindowClass>(1) }
    val current = heldWindowClass(measured, last[0], WindowInsets.isImeVisible)
    SideEffect { last[0] = current }
    return current
}
