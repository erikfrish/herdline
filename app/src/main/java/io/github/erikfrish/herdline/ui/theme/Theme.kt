/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.theme

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.core.AgentStatus

// The M3 corner scale.
val HerdlineShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

// The M3 default type scale, named so a test can hold the composer's field
// line to its bodyLarge (ComposerFit.FIELD_LINE).
val HerdlineTypography = Typography()

/** Whether the user chose compact density: tighter rows, keys and header. */
val LocalCompact = staticCompositionLocalOf { false }

/**
 * Every screen draws from [palette]'s tokens for [dark]: the Material scheme,
 * Chat's roles, status colours and glass. [dynamic] (Material You, API 31+)
 * takes them from the wallpaper instead, with fixed meaning on top.
 * [accent], when the user picked one, replaces the primary colour everywhere.
 */
@Composable
fun HerdlineTheme(
    dark: Boolean,
    palette: AppPalette = AppPalettes.Herdline,
    accent: Color? = null,
    dynamic: Boolean = false,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    val tokens = if (dynamic && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) rememberDynamicTokens(dark) else palette.tokens(dark)
    val scheme = accent?.let { tokens.material.withAccent(it) } ?: tokens.material
    CompositionLocalProvider(LocalCompact provides compact, LocalThemeTokens provides tokens) {
        MaterialTheme(colorScheme = scheme, shapes = HerdlineShapes, typography = HerdlineTypography) {
            // Screens draw straight on the page with no Surface to set it, so text would default to black;
            // a Surface here would also paint the Appearance preview's corners.
            CompositionLocalProvider(LocalContentColor provides scheme.onBackground, content = content)
        }
    }
}

/**
 * The device's wallpaper scheme for [dark] as Herdline tokens, guarded
 *. A wallpaper change recreates the activity, so only the
 * mode, handled in place, is a key.
 */
@RequiresApi(Build.VERSION_CODES.S)
@Composable
fun rememberDynamicTokens(dark: Boolean): ThemeTokens {
    val context = LocalContext.current
    return remember(context, dark) {
        AppPalettes.dynamicTokens(if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context), dark)
    }
}

/**
 * This scheme wearing an [accent] the palette does not own.
 * Primary is also text (TextButton labels, links, the active tab), so the hue
 * is kept and moved until it reads at 4.5:1 on the page tiers up to
 * surfaceContainerHigh; black or white, whichever is stronger, goes on it.
 */
internal fun ColorScheme.withAccent(accent: Color): ColorScheme {
    val toward = towardText()
    val tiers = listOf(surface, surfaceContainerLow, surfaceContainer, surfaceContainerHigh)
    val primary = ensureContrast(accent, tiers, 4.5f, toward)
    return copy(primary = primary, onPrimary = onColor(primary))
}

/**
 * The selected tab's underline in a session's [accent]: a
 * non-text cue, so the hue is moved only until it keeps 3:1 on
 * [ColorScheme.surface]. Node colours that already pass are unchanged.
 */
fun ColorScheme.indicatorAccent(accent: Color): Color = ensureContrast(accent, listOf(surface), 3f, towardText())

/** The pole a colour moves toward to read on this scheme's page: white on a dark page, else black. */
private fun ColorScheme.towardText(): Color = if (background.luminance() < 0.5f) Color.White else Color.Black

/** The colour a session wears: the user's chosen accent, else its node's label. */
fun sessionAccent(choice: Int, nodeColor: Int): Color = if (choice == 0) NodeColors.of(nodeColor) else NodeColors.of(choice - 1)

/** The daemon's agent status as the current palette's colour. */
@Composable
@ReadOnlyComposable
fun AgentStatus.color(): Color = LocalThemeTokens.current.status.of(this)

/** Colour labels a node can wear, shown on its avatar and session bar. */
object NodeColors {
    val all: List<Color> = AppPalettes.NodeLabels

    fun of(index: Int): Color = all[index.mod(all.size)]
}
