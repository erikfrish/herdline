package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.ui.theme.HerdlineTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposerFitTest {
    @Test
    fun theFieldLineTheHeightIsComputedWithIsTheLineTheFieldIsDrawnWith() {
        // The field draws in the app theme's bodyLarge (M4): a change to its
        // line height would desynchronise heightDp from the drawn field (J2).
        assertEquals(ComposerFit.FIELD_LINE.toFloat(), HerdlineTypography.bodyLarge.lineHeight.value, 0f)
    }

    @Test
    fun onlyALandscapePhoneWithTheKeyboardUpGetsTheCompactComposer() {
        assertEquals(ComposerFit.Compact, composerFit(landscape = true, imeVisible = true, screenHeightDp = 411))
        // Keyboard down, portrait, or a landscape screen tall enough (a tablet).
        assertEquals(ComposerFit.Regular, composerFit(landscape = true, imeVisible = false, screenHeightDp = 411))
        assertEquals(ComposerFit.Regular, composerFit(landscape = false, imeVisible = true, screenHeightDp = 891))
        assertEquals(ComposerFit.Regular, composerFit(landscape = false, imeVisible = true, screenHeightDp = 400))
        assertEquals(ComposerFit.Regular, composerFit(landscape = true, imeVisible = true, screenHeightDp = 480))
        assertEquals(ComposerFit.Compact, composerFit(landscape = true, imeVisible = true, screenHeightDp = 479))
    }

    @Test
    fun theCompactComposerLeavesThreeChatLinesAtFontScale1And13() {
        // lab.8 CHECKS J2: 2400x1080 at 420 dpi (2.625 px/dp) with the IME up,
        // content 203 px plus a 190 px composer: about 150 dp for both.
        // A chat line is 22 sp; the chat type is never shrunk.
        val session = 393 / 2.625f
        for (scale in listOf(1.0f, 1.3f)) {
            for (lines in 0..3) {
                for (notice in listOf(false, true)) {
                    val composer = ComposerFit.Compact.heightDp(lines, notice, scale)
                    val left = session - composer
                    assertTrue("scale $scale, $lines lines, notice $notice: $composer dp leaves $left", left >= 3 * 22 * scale)
                }
            }
        }
        // The notice shares the field's top line: it costs no height.
        for (scale in listOf(1.0f, 1.3f)) {
            assertEquals(ComposerFit.Compact.heightDp(1, notice = false, scale), ComposerFit.Compact.heightDp(1, notice = true, scale))
        }
        assertEquals(52, ComposerFit.Compact.heightDp(1, notice = true, 1.0f))
        assertEquals(56, ComposerFit.Compact.heightDp(1, notice = true, 1.3f))
    }

    @Test
    fun theCompactBandStillHoldsItsTargetsAndOneFieldLine() {
        val compact = ComposerFit.Compact
        // The 48 dp + menu, the notice's actions and Send.
        assertTrue(compact.bandMin >= 48)
        // One line, scrolling inside; the larger editor or Insert stays in the + menu.
        assertEquals(1, compact.maxLines)
        // The top line and the field line fit the band unclipped at 1.3.
        assertTrue(compact.bandMax >= (compact.toLine + 24) * 1.3f)
    }

    @Test
    fun anEmptyFieldHasTheOneLineBandSoTheFirstKeystrokeChangesNothing() {
        // UR2 (design 528f5b8 §2, 4bced42 §5): the To-line slot is kept while
        // empty, so the band is the same empty and with one line. Regular holds
        // the text on the Send axis: max(64, 36 + 28·fs) dp, rounded up with
        // the 2 × 8 dp outer padding; Compact and Side centre the pair.
        val side = composerFit(landscape = true, imeVisible = true, screenHeightDp = 411, side = true)
        val scales = listOf(1.0f, 1.3f, 1.45f)
        val heights = mapOf(ComposerFit.Regular to listOf(80, 89, 93), ComposerFit.Compact to listOf(52, 56, 62), side to listOf(52, 56, 62))
        for ((fit, expected) in heights) {
            scales.zip(expected).forEach { (scale, height) ->
                for (lines in 0..1) {
                    assertEquals("$fit at $scale, $lines lines", height, fit.heightDp(lines, notice = false, scale))
                }
                assertEquals("$fit at $scale, notice", fit.heightDp(1, notice = true, scale), fit.heightDp(0, notice = true, scale))
            }
        }
    }

    @Test
    fun eachFurtherRegularLineAdds24spUntilTheBandMax() {
        // 4bced42 §5: extra lines push the earlier ones up, the last stays on the axis.
        for (scale in listOf(1.0f, 1.3f, 1.45f)) {
            val one = 36 + 28 * scale
            for (lines in 2..ComposerFit.Regular.maxLines) {
                val band = (one + 24 * scale * (lines - 1)).coerceAtMost(152f)
                assertEquals("$lines lines at $scale", kotlin.math.ceil(16 + band - 0.001f).toInt(), ComposerFit.Regular.heightDp(lines, notice = false, scale))
            }
        }
    }

    @Test
    fun theSideColumnComposerFits150DpAtItsNarrowestWidthUpToFontScale145() {
        val side = composerFit(landscape = true, imeVisible = true, screenHeightDp = 411, side = true)
        assertEquals(ComposerFit.Side, side)
        // J2 ruling S: about 150 dp tall, whatever the width; nothing shrinks the type.
        // At 1.45 (D-S1) the band would be cut unless the notice drops to one line.
        for (scale in listOf(1.0f, 1.3f, 1.45f)) {
            for (lines in 0..3) {
                for (notice in listOf(false, true)) {
                    val height = side.heightDp(lines, notice, scale)
                    assertTrue("scale $scale, $lines lines, notice $notice: $height dp", height <= 150)
                }
            }
        }
        // The notice keeps two lines up to 1.3, then one; a notice while typing
        // at 1.45 takes 138 dp instead of 161.
        assertEquals(2, side.noticeLines(1.3f))
        assertEquals(1, side.noticeLines(1.45f))
        assertEquals(138, side.heightDp(1, notice = true, 1.45f))
        // One field line, scrolling inside; 48 dp band, unclipped at 1.3.
        assertEquals(1, side.maxLines)
        assertTrue(side.bandMin >= 48)
        assertTrue(side.bandMax >= (side.toLine + ComposerFit.FIELD_LINE) * 1.3f)
        // The notice keeps its text and actions in a block of its own, so the
        // band row holds only + · field · Send, each target 48 dp.
        assertTrue(side.noticeBlock)
        val inner = SIDE_MIN_WIDTH_DP - 2 * 12
        val field = inner - 2 * 4 - 48 - 48 - 2 * 6
        assertTrue("field $field dp at $SIDE_MIN_WIDTH_DP dp", field >= 88)
        // Two notice actions side by side at Material's 58 dp minimum width.
        assertTrue(2 * 58 + 8 <= inner)
    }

    @Test
    fun attachmentsAddTheirRowOnlyToTheRegularComposerAndOnlyOnce() {
        // The chip row's text draws in bodySmall and labelMedium: their line
        // height is the one the row's budget counts.
        assertEquals(ATTACH_LABEL_LINE.toFloat(), HerdlineTypography.bodySmall.lineHeight.value, 0f)
        assertEquals(ATTACH_LABEL_LINE.toFloat(), HerdlineTypography.labelMedium.lineHeight.value, 0f)
        // Regular: the consent line's two lines, a chip of max(48, 12 + 32·fs)
        // dp (48 / 53.6 / 58.4), and the gaps (80 → 170 dp at 1.0; 89 → 194
        // at 1.3; 93 → 208 at 1.45).
        for ((scale, bare, withFiles) in listOf(Triple(1.0f, 80, 170), Triple(1.3f, 89, 194), Triple(1.45f, 93, 208))) {
            assertEquals(bare, ComposerFit.Regular.heightDp(1, notice = false, scale))
            assertEquals(withFiles, ComposerFit.Regular.heightDp(1, notice = false, scale, attachments = 1))
            // A second file scrolls sideways in the same row.
            assertEquals(withFiles, ComposerFit.Regular.heightDp(1, notice = false, scale, attachments = 5))
        }
        // Compact and Side count the files on the To-line: J2's budget is unchanged.
        for (fit in listOf(ComposerFit.Compact, ComposerFit.Side)) {
            for (scale in listOf(1.0f, 1.3f, 1.45f)) {
                for (notice in listOf(false, true)) {
                    assertEquals(fit.heightDp(1, notice, scale), fit.heightDp(1, notice, scale, attachments = 3))
                }
            }
        }
    }

    @Test
    fun aChipsBudgetIsItsTallestChildSoNothingClipsAndNothingIsWasted() {
        // Remove is an IconButton: Material's 48 dp touch target, never shrunk.
        val remove = 48f
        for (scale in listOf(0.85f, 1.0f, 1.1f, 1.125f, 1.15f, 1.3f, 1.45f, 2.0f)) {
            // The name over the state, each at its style's real line height, inside the text's padding.
            val text = 2 * CHIP_PAD + (HerdlineTypography.labelMedium.lineHeight.value + HerdlineTypography.bodySmall.lineHeight.value) * scale
            val chip = attachmentChipDp(scale)
            assertTrue("chip $chip dp clips Remove at $scale", chip >= remove)
            assertTrue("chip $chip dp clips its text ($text dp) at $scale", chip >= text - 0.001f)
            // No slack beyond the taller of the two: the field keeps every other dp.
            assertEquals("chip at $scale", maxOf(remove, text), chip, 0.001f)
        }
        // At the default font size a chip is exactly its Remove: the compact row stays 48 dp.
        assertEquals(48f, attachmentChipDp(1.0f), 0f)
    }

    @Test
    fun zoomIsOfferedOnlyWhileTheTabIsKnownUnzoomed() {
        assertEquals(listOf(ShortPaneOffer.Zoom), offers(tabZoomed = false))
        // Already zoomed and still short: only the reason; unknown: no guess.
        assertEquals(emptyList<ShortPaneOffer>(), offers(tabZoomed = true))
        assertEquals(emptyList<ShortPaneOffer>(), offers(tabZoomed = null))
    }

    @Test
    fun keyboardAndPortraitHintsShowOnlyWhereTheyReachTwelveRows() {
        // Landscape with the IME up, but the estimate says neither reaches 12.
        assertEquals(emptyList<ShortPaneOffer>(), offers(tabZoomed = true))
        assertEquals(
            listOf(ShortPaneOffer.HideKeyboard, ShortPaneOffer.Portrait),
            offers(tabZoomed = true, hideKeyboardReachesSend = true, portraitReachesSend = true),
        )
        // A hint for a state the phone is already in never shows.
        assertEquals(emptyList<ShortPaneOffer>(), offers(tabZoomed = true, imeVisible = false, hideKeyboardReachesSend = true))
        assertEquals(emptyList<ShortPaneOffer>(), offers(tabZoomed = true, landscape = false, portraitReachesSend = true))
    }

    private fun offers(
        tabZoomed: Boolean?,
        landscape: Boolean = true,
        imeVisible: Boolean = true,
        hideKeyboardReachesSend: Boolean = false,
        portraitReachesSend: Boolean = false,
    ) = shortPaneOffers(tabZoomed, landscape, imeVisible, hideKeyboardReachesSend, portraitReachesSend)
}
