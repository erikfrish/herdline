package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.ui.theme.AppPalettes
import io.github.erikfrish.herdline.ui.theme.ChatTokens
import io.github.erikfrish.herdline.ui.theme.NodeColors
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG 2.x contrast of chat's roles in every palette and mode. */
class ChatContrastTest {
    private fun channel(c: Float) = if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    private fun luminance(c: Color) = 0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun assertContrast(min: Double, fg: Map<String, Color>, bg: Map<String, Color>) {
        for ((f, fc) in fg) for ((b, bc) in bg) {
            val ratio = contrast(fc, bc)
            assertTrue("$f on $b is ${"%.2f".format(ratio)}, below $min", ratio >= min)
        }
    }

    private fun check(tokens: ChatTokens) {
        val c = chatPalette(tokens, NodeColors.all.first())
        val pages = mapOf("bg" to c.bg, "surf" to c.surf)
        assertContrast(
            4.5,
            mapOf("text" to c.text, "muted" to c.muted, "dim" to c.dim, "working" to c.working, "blocked" to c.blocked, "done" to c.done, "err" to c.err),
            pages,
        )
        // Every session accent's text tint, not just the default one.
        NodeColors.all.forEach { accent ->
            assertContrast(4.5, mapOf("accentText($accent)" to chatPalette(tokens, accent).accentText), pages)
        }
        // Text set on the Needs input, Error and offline tints.
        assertContrast(4.5, mapOf("text" to c.text, "blocked" to c.blocked), mapOf("warnBg" to c.warnBg))
        assertContrast(4.5, mapOf("text" to c.text, "err" to c.err), mapOf("errBg" to c.errBg))
        // Text inside a bubble or a code block.
        assertContrast(4.5, mapOf("text" to c.text, "muted" to c.muted), mapOf("userBubble" to c.userBubble, "codeBg" to c.codeBg, "codeChip" to c.codeChip))
        // Status bars are non-text graphics: 3:1.
        assertContrast(3.0, mapOf("working" to c.working, "blocked" to c.blocked, "err" to c.err), pages)
    }

    @Test
    fun dark_roles_meet_aa() = AppPalettes.all.forEach { check(it.tokens(true).chat) }

    @Test
    fun light_roles_meet_aa() = AppPalettes.all.forEach { check(it.tokens(false).chat) }

    @Test
    fun aUserBubbleTooCloseToThePageGetsAnOutlineAndOnlyThen() {
        AppPalettes.all.flatMap { listOf(it.tokens(false), it.tokens(true)) }.forEach { theme ->
            val c = chatPalette(theme.chat, NodeColors.all.first())
            val fill = contrast(c.userBubble, c.bg)
            if (fill < 1.15) {
                assertTrue("a ${"%.2f".format(fill)}:1 bubble needs an outline", c.bubbleEdge.alpha > 0f && c.bubbleEdge != c.bg)
            } else {
                assertEquals("a ${"%.2f".format(fill)}:1 bubble reads alone", Color.Transparent, c.bubbleEdge)
            }
        }
        // Herdline's own pair, the one the outline was made for: light 1.07:1, dark 1.20:1.
        assertEquals(AppPalettes.Herdline.tokens(false).chat.border, chatPalette(AppPalettes.Herdline.tokens(false).chat, NodeColors.all.first()).bubbleEdge)
        assertEquals(Color.Transparent, chatPalette(AppPalettes.Herdline.tokens(true).chat, NodeColors.all.first()).bubbleEdge)
    }
}
