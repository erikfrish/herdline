package io.github.erikfrish.herdline.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.ui.chat.chatPalette
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** WCAG contrast of every theme in both of its modes. */
class PaletteContrastTest {
    private class Pair(val label: String, val fg: (ColorScheme) -> Color, val bg: (ColorScheme) -> Color, val min: Float)

    // The role pairs the M3 components draw text and controls with.
    private val pairs = listOf(
        Pair("onPrimary / primary", { it.onPrimary }, { it.primary }, 4.5f),
        Pair("primary / surface", { it.primary }, { it.surface }, 4.5f),
        Pair("primary / surfaceContainerLow", { it.primary }, { it.surfaceContainerLow }, 3f),
        Pair("onPrimaryContainer / primaryContainer", { it.onPrimaryContainer }, { it.primaryContainer }, 4.5f),
        Pair("onSecondaryContainer / secondaryContainer", { it.onSecondaryContainer }, { it.secondaryContainer }, 4.5f),
        Pair("onSecondary / secondary", { it.onSecondary }, { it.secondary }, 4.5f),
        Pair("tertiary / surface", { it.tertiary }, { it.surface }, 4.5f),
        Pair("onTertiaryContainer / tertiaryContainer", { it.onTertiaryContainer }, { it.tertiaryContainer }, 4.5f),
        Pair("onError / error", { it.onError }, { it.error }, 4.5f),
        Pair("onErrorContainer / errorContainer", { it.onErrorContainer }, { it.errorContainer }, 4.5f),
        Pair("onSurface / surfaceContainerHighest", { it.onSurface }, { it.surfaceContainerHighest }, 4.5f),
        Pair("onSurfaceVariant / surface", { it.onSurfaceVariant }, { it.surface }, 4.5f),
        Pair("onSurfaceVariant / surfaceContainerHigh", { it.onSurfaceVariant }, { it.surfaceContainerHigh }, 4.5f),
        Pair("onSurfaceVariant / surfaceContainerHighest", { it.onSurfaceVariant }, { it.surfaceContainerHighest }, 4.5f),
        Pair("outline / surface", { it.outline }, { it.surface }, 3f),
        Pair("outline / surfaceContainerHigh", { it.outline }, { it.surfaceContainerHigh }, 3f),
        Pair("inverseOnSurface / inverseSurface", { it.inverseOnSurface }, { it.inverseSurface }, 4.5f),
        Pair("inversePrimary / inverseSurface", { it.inversePrimary }, { it.inverseSurface }, 4.5f),
    )

    private val shown = listOf(AgentStatus.Working, AgentStatus.Blocked, AgentStatus.Done)

    /** Every palette and mode a user can see; a dark-only theme's light mode is Herdline's, checked once. */
    private fun variants() = AppPalettes.all.flatMap { p ->
        listOf(true, false).filter { it || !p.darkOnly }.map { dark -> "${p.id} ${if (dark) "dark" else "light"}" to p.tokens(dark) }
    }

    private fun assertContrast(label: String, fg: Color, bg: Color, min: Float) {
        val ratio = contrast(fg, bg)
        assertTrue("$label is ${"%.2f".format(ratio)}, below $min", ratio >= min)
    }

    @Test
    fun everyThemeMeetsTheRolePairs() {
        variants().forEach { (name, tokens) ->
            pairs.forEach { assertContrast("$name ${it.label}", it.fg(tokens.material), it.bg(tokens.material), it.min) }
        }
    }

    @Test
    fun statusWordsReadOnThePageAndRows() {
        variants().forEach { (name, tokens) ->
            val m = tokens.material
            shown.forEach { status ->
                assertContrast("$name $status text on background", tokens.statusText.of(status), m.background, 4.5f)
                assertContrast("$name $status text on surfaceContainerLow", tokens.statusText.of(status), m.surfaceContainerLow, 4.5f)
            }
        }
    }

    @Test
    fun statusDotsStandOutFromThePage() {
        variants().forEach { (name, tokens) ->
            shown.forEach { assertContrast("$name $it dot", tokens.status.of(it), tokens.material.background, 3f) }
        }
    }

    @Test
    fun herdlineLightStatusReadsOnEverySurfaceTier() {
        // F1: dots and glyphs keep 3:1 on all six tiers; status-coloured words keep
        // 4.5 up to surfaceContainer, and High/Highest carry an onSurface word with the glyph instead.
        val tokens = AppPalettes.Herdline.tokens(false)
        val m = tokens.material
        val words = listOf(m.background, m.surfaceContainerLowest, m.surfaceContainerLow, m.surfaceContainer)
        val tiers = words + listOf(m.surface, m.surfaceContainerHigh, m.surfaceContainerHighest)
        (shown + AgentStatus.Idle).forEach { status ->
            tiers.forEach { assertContrast("Herdline light $status dot on $it", tokens.status.of(status), it, 3f) }
        }
        shown.forEach { status ->
            words.forEach { assertContrast("Herdline light $status text on $it", tokens.statusText.of(status), it, 4.5f) }
        }
    }

    @Test
    fun everyThemeNamesAShippedTerminalScheme() {
        // A misspelt id would fall back to Herdline's terminal without a word.
        AppPalettes.all.forEach { p ->
            listOf(true, false).forEach { dark ->
                val id = p.tokens(dark).terminalScheme
                val scheme = TerminalSchemes.all.firstOrNull { it.id == id }
                assertTrue("${p.id} names unknown scheme $id", scheme != null)
                // A dark-only theme under Light wears Herdline Light and its light terminal.
                assertEquals("${p.id} $id mode", dark, scheme!!.dark)
            }
        }
    }

    @Test
    fun terminalTextReadsOnItsBackground() {
        TerminalSchemes.all.forEach {
            assertContrast("${it.id} foreground", Color(0xff000000 or it.foreground), Color(0xff000000 or it.background), 4.5f)
        }
    }

    @Test
    fun chatAccentTextReadsOnEveryChatSurface() {
        // Chat's accent tint sits on the page, rows, the user's bubble and code chips.
        variants().forEach { (name, tokens) ->
            NodeColors.all.forEach { accent ->
                val c = chatPalette(tokens.chat, accent)
                listOf(c.bg, c.surf, c.userBubble, c.codeChip).forEach { assertContrast("$name accentText($accent)", c.accentText, it, 4.5f) }
            }
        }
    }
}
