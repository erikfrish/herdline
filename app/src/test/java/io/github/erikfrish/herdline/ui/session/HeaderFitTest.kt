package io.github.erikfrish.herdline.ui.session

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The phone header's words / glyphs / second-row choice (SESSION-HDR-WRAP). Widths are in dp:
 * Chat | Terminal in English is about 130 dp at font 1.0 and 210 dp at font 2.0; the need-you
 * chip with one digit is about 53 dp at font 2.0.
 */
class HeaderFitTest {
    private val words = HeaderFit(glyphs = false, wrapped = false)
    private val glyphs = HeaderFit(glyphs = true, wrapped = false)
    private val wrappedGlyphs = HeaderFit(glyphs = true, wrapped = true)

    @Test
    fun wordsStayOnOneRowWhileThePlaceKeepsItsReadableWidth() {
        // 360 - 108 - 12 - 130 = 110 dp for the place.
        assertEquals(words, headerFit(360.dp, 130.dp, null))
        // With a 1-digit chip at its 48 dp floor: 360 - 108 - 12 - 52 - 130 = 58, so glyphs.
        assertEquals(glyphs, headerFit(360.dp, 130.dp, 46.dp))
        // Exactly 96 dp left is still enough.
        assertEquals(words, headerFit(346.dp, 130.dp, null))
        assertEquals(glyphs, headerFit(345.dp, 130.dp, null))
    }

    @Test
    fun withoutTheChipATightHeaderKeepsOneGlyphRow() {
        // Font 2.0 at 308 dp: the place keeps 308 - 108 - 2 - 96 - 1 = 101 dp; at 250 dp it is below
        // 96 and still one row, as before (its 48 dp floor holds).
        assertEquals(glyphs, headerFit(308.dp, 210.dp, null))
        assertEquals(glyphs, headerFit(250.dp, 210.dp, null))
    }

    @Test
    fun withTheChipATightHeaderWrapsInsteadOfShorteningThePlaceOrTheCount() {
        // QA1 on 10100, font 2.0: one glyph row leaves the place 308 - 108 - 4 - 53 - 96 = 47 dp
        // and 320 - ... = 59 dp, both under 96, so the controls take a second row.
        assertEquals(wrappedGlyphs, headerFit(308.dp, 210.dp, 53.dp))
        assertEquals(wrappedGlyphs, headerFit(320.dp, 210.dp, 53.dp))
        // 412 dp at font 2.0: 412 - 108 - 4 - 53 - 96 = 151, one glyph row.
        assertEquals(glyphs, headerFit(412.dp, 210.dp, 53.dp))
        // A 3-digit chip (99+, about 83 dp) at 360 dp: 360 - 108 - 4 - 83 - 96 = 69, wrapped.
        assertEquals(wrappedGlyphs, headerFit(360.dp, 210.dp, 83.dp))
    }

    @Test
    fun theSecondRowUsesWordsWhenTheyFitThere() {
        // 340 dp, words 130, a 110 dp chip: one glyph row leaves 22 dp, wrapped; the second row needs
        // 110 + 4 + 130 + 4 + 48 = 296 <= 328, so words.
        // At 308 dp the second row has 296 dp: 296 needed, words; at 307 dp, glyphs.
        assertEquals(HeaderFit(glyphs = false, wrapped = true), headerFit(308.dp, 130.dp, 110.dp))
        assertEquals(wrappedGlyphs, headerFit(307.dp, 130.dp, 110.dp))
    }
}
