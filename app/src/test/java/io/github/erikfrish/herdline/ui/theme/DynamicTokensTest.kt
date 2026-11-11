package io.github.erikfrish.herdline.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Material You keeps Herdline's meaning on any wallpaper. The
 * fixtures are roles from design/mockups/material/dynamic-sim.json.
 */
class DynamicTokensTest {
    // amber-desert/2021/dark/c1.0: High contrast, where raw status falls to 3.42.
    private val amberDarkHigh = darkColorScheme(
        primary = Color(0xFFFFEDD9),
        onPrimary = Color(0xFF000000),
        tertiary = Color(0xFFDFF8CA),
        error = Color(0xFFFFECE9),
        background = Color(0xFF18130B),
        onBackground = Color(0xFFFFFFFF),
        surface = Color(0xFF18130B),
        onSurface = Color(0xFFFFFFFF),
        onSurfaceVariant = Color(0xFFFFFFFF),
        outline = Color(0xFFFDEEDC),
        inverseSurface = Color(0xFFEDE1D4),
        inverseOnSurface = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF241F17),
        surfaceContainer = Color(0xFF362F27),
        surfaceContainerHigh = Color(0xFF413A32),
        surfaceContainerHighest = Color(0xFF4D463C),
    )

    // violet-night/2021/light/c1.0
    private val violetLightHigh = lightColorScheme(
        primary = Color(0xFF352157),
        onPrimary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF46212A),
        error = Color(0xFF600004),
        background = Color(0xFFFEF7FF),
        onBackground = Color(0xFF000000),
        surface = Color(0xFFFEF7FF),
        onSurface = Color(0xFF000000),
        onSurfaceVariant = Color(0xFF000000),
        outline = Color(0xFF2E2B33),
        inverseSurface = Color(0xFF322F35),
        inverseOnSurface = Color(0xFFFFFFFF),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF6EEF7),
        surfaceContainer = Color(0xFFE7E0E8),
        surfaceContainerHigh = Color(0xFFD9D2DA),
        surfaceContainerHighest = Color(0xFFCBC4CC),
    )

    // amber-desert/2025/light/c0.0: Standard contrast, already readable.
    private val amberLight = lightColorScheme(
        primary = Color(0xFF7C591C),
        onPrimary = Color(0xFFFFF8F2),
        tertiary = Color(0xFF865238),
        error = Color(0xFFA73B21),
        background = Color(0xFFFFF8F3),
        onBackground = Color(0xFF3A3125),
        surface = Color(0xFFFFF8F3),
        onSurface = Color(0xFF3A3125),
        onSurfaceVariant = Color(0xFF685D4F),
        outline = Color(0xFF85796A),
        inverseSurface = Color(0xFF120E08),
        inverseOnSurface = Color(0xFFA49B92),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFEF2E4),
        surfaceContainer = Color(0xFFFAECDD),
        surfaceContainerHigh = Color(0xFFF4E6D6),
        surfaceContainerHighest = Color(0xFFF0E0CE),
    )

    // red-sunset/2021/light/c0.0: a red wallpaper.
    private val redLight = lightColorScheme(
        primary = Color(0xFF904B40),
        onPrimary = Color(0xFFFFFFFF),
        tertiary = Color(0xFF705C2E),
        error = Color(0xFFBA1A1A),
        background = Color(0xFFFFF8F6),
        onBackground = Color(0xFF231918),
        surface = Color(0xFFFFF8F6),
        onSurface = Color(0xFF231918),
        onSurfaceVariant = Color(0xFF534341),
        outline = Color(0xFF857370),
        inverseSurface = Color(0xFF392E2C),
        inverseOnSurface = Color(0xFFFFEDEA),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFFFF0EE),
        surfaceContainer = Color(0xFFFCEAE7),
        surfaceContainerHigh = Color(0xFFF7E4E1),
        surfaceContainerHighest = Color(0xFFF1DFDC),
    )

    private fun ColorScheme.tiers() =
        listOf(surface, surfaceContainerLowest, surfaceContainerLow, surfaceContainer, surfaceContainerHigh, surfaceContainerHighest)

    /** HSV hue in degrees. */
    private fun hue(c: Color): Float {
        val hi = max(c.red, max(c.green, c.blue))
        val d = hi - min(c.red, min(c.green, c.blue))
        if (d == 0f) return 0f
        val h = when (hi) {
            c.red -> ((c.green - c.blue) / d).mod(6f)
            c.green -> (c.blue - c.red) / d + 2f
            else -> (c.red - c.green) / d + 4f
        }
        return h * 60f
    }

    private fun hueShift(a: Color, b: Color): Float = abs(hue(a) - hue(b)).let { min(it, 360f - it) }

    @Test
    fun statusReadsOnEveryTierAtHighContrast() {
        listOf(
            Triple("amber dark high", amberDarkHigh, true) to listOf(0xFF659DF7, 0xFFF2A93B, 0xFF3FB868, 0xFFF07D6D),
            Triple("violet light high", violetLightHigh, false) to listOf(0xFF2762C0, 0xFF8D5A00, 0xFF20723C, 0xFFB1392A),
        ).forEach { (fixture, raw) ->
            val (name, scheme, dark) = fixture
            val tokens = AppPalettes.dynamicTokens(scheme, dark)
            val text = tokens.statusText
            val words = listOf(text.working, text.blocked, text.done, tokens.chat.err)
            words.zip(raw).forEach { (word, from) ->
                scheme.tiers().forEach { tier ->
                    val ratio = contrast(word, tier)
                    assertTrue("$name $word is ${"%.2f".format(ratio)} on $tier", ratio >= 4.5f)
                }
                // Moved only along its own hue, so Working never drifts into another status.
                assertTrue("$name $word left the hue of ${Color(from)}", hueShift(word, Color(from)) <= 10f)
            }
            // Idle and Lost are the neutral glyph: the guarded onSurfaceVariant.
            scheme.tiers().forEach { tier -> assertTrue("$name idle on $tier", contrast(text.idle, tier) >= 3f) }
        }
    }

    @Test
    fun aSchemeThatAlreadyReadsIsLeftAlone() {
        val tokens = AppPalettes.dynamicTokens(amberLight, dark = false)
        assertEquals(amberLight.primary, tokens.material.primary)
        assertEquals(amberLight.onSurfaceVariant, tokens.material.onSurfaceVariant)
        assertEquals(amberLight.outline, tokens.material.outline)
        assertEquals(Color(0xFF2762C0), tokens.statusText.working)
        assertEquals(Color(0xFF8D5A00), tokens.statusText.blocked)
        assertEquals(Color(0xFF20723C), tokens.statusText.done)
        assertEquals(Color(0xFFB1392A), tokens.chat.err)
    }

    @Test
    fun meaningDoesNotFollowTheWallpaper() {
        val tokens = AppPalettes.dynamicTokens(redLight, dark = false)
        val herdline = AppPalettes.Herdline.tokens(false)
        assertEquals(herdline.chat.warnBg, tokens.chat.warnBg)
        assertEquals(herdline.chat.errBg, tokens.chat.errBg)
        assertEquals("herdline-light", tokens.terminalScheme)
        val blocked = tokens.statusText.blocked
        listOf(tokens.material.primary, tokens.material.tertiary, tokens.material.error).forEach { assertNotEquals(it, blocked) }
        // The page follows the wallpaper.
        assertEquals(redLight.surface, tokens.material.surface)
    }

    @Test
    fun aUserAccentStillWins() {
        val brass = NodeColors.of(6)
        listOf(amberDarkHigh to true, violetLightHigh to false, redLight to false).forEach { (scheme, dark) ->
            val material = AppPalettes.dynamicTokens(scheme, dark).material.withAccent(brass)
            listOf(material.surface, material.surfaceContainerLow, material.surfaceContainer, material.surfaceContainerHigh).forEach { tier ->
                assertTrue("dark=$dark primary on $tier", contrast(material.primary, tier) >= 4.5f)
            }
            assertTrue("dark=$dark onPrimary", contrast(material.onPrimary, material.primary) >= 4.5f)
            assertNotEquals(scheme.primary, material.primary)
        }
    }
}
