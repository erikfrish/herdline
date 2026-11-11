package io.github.erikfrish.herdline.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** WCAG 2 contrast ratio of two opaque colours, 1 to 21. */
fun contrast(a: Color, b: Color): Float {
    val la = a.luminance() + 0.05f
    val lb = b.luminance() + 0.05f
    return if (la > lb) la / lb else lb / la
}

/** Whichever of black and white reads better on [background]. */
fun onColor(background: Color): Color =
    if (contrast(Color.Black, background) >= contrast(Color.White, background)) Color.Black else Color.White

/** [from] moved toward [to] by [t] in sRGB, as the design scripts mix (Compose's lerp mixes in Oklab). */
private fun mix(from: Color, to: Color, t: Float) = Color(
    red = from.red + (to.red - from.red) * t,
    green = from.green + (to.green - from.green) * t,
    blue = from.blue + (to.blue - from.blue) * t,
)

/**
 * [color], moved toward [toward] in 2% steps until it keeps [target] on every
 * one of [backgrounds] (`ensure()` in the design scripts). A
 * colour that already passes is returned unchanged.
 */
fun ensureContrast(color: Color, backgrounds: List<Color>, target: Float, toward: Color): Color {
    var step = 0
    var current = color
    while (step < 50 && backgrounds.any { contrast(current, it) < target }) {
        step++
        current = mix(color, toward, step * 0.02f)
    }
    return current
}
