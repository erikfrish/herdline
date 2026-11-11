package io.github.erikfrish.herdline.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** A user accent on any theme stays readable as text and under its label. */
class AccentGuardTest {
    private fun schemes() = AppPalettes.all.flatMap { p -> listOf(true, false).map { "${p.id} $it" to p.tokens(it).material } }

    @Test
    fun everyAccentReadsOnThePageTiersWithReadableTextOnIt() {
        schemes().forEach { (name, scheme) ->
            val tiers = listOf(scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer, scheme.surfaceContainerHigh)
            NodeColors.all.forEach { accent ->
                val guarded = scheme.withAccent(accent)
                tiers.forEach { tier ->
                    val ratio = contrast(guarded.primary, tier)
                    assertTrue("$name $accent primary is ${"%.2f".format(ratio)} on $tier", ratio >= 4.5f)
                }
                val on = contrast(guarded.onPrimary, guarded.primary)
                assertTrue("$name $accent onPrimary is ${"%.2f".format(on)}", on >= 4.5f)
            }
        }
    }

    @Test
    fun theTabUnderlineKeepsThreeToOneOnSurfaceAndOnlyMovesWhenItMust() {
        // M5 S8: raw node colours fall to 2.59:1 (Brass on gruvbox light).
        schemes().forEach { (name, scheme) ->
            NodeColors.all.forEach { accent ->
                val underline = scheme.indicatorAccent(accent)
                val ratio = contrast(underline, scheme.surface)
                assertTrue("$name $accent underline is ${"%.2f".format(ratio)}", ratio >= 3f)
                if (contrast(accent, scheme.surface) >= 3f) assertEquals("$name $accent", accent, underline)
            }
        }
    }

    @Test
    fun anAccentThatAlreadyReadsIsLeftAlone() {
        // Herdline's own primaries pass on every tier, so wearing them changes nothing.
        listOf(true, false).forEach { dark ->
            val scheme = AppPalettes.Herdline.tokens(dark).material
            assertEquals(scheme.primary, scheme.withAccent(scheme.primary).primary)
        }
    }

    @Test
    fun aLowContrastAccentMovesTowardTheTextSide() {
        // Teal is below 4.5:1 on a page tier in both Herdline modes: it darkens in light and lightens in dark.
        val teal = NodeColors.all.first()
        val light = AppPalettes.Herdline.tokens(false).material.withAccent(teal).primary
        val dark = AppPalettes.Herdline.tokens(true).material.withAccent(teal).primary
        assertTrue(light.red < teal.red && light.green < teal.green && light.blue < teal.blue)
        assertTrue(dark.red > teal.red && dark.green > teal.green && dark.blue > teal.blue)
    }
}
