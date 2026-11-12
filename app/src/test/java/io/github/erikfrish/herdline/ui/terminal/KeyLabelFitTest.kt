package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cap label's form: full while its measured width
 * fits the cap less 1 dp a side, else short for its whole group, the keycap
 * glyph unless the font lacks one of the group's. Measurement itself is QA's.
 */
class KeyLabelFitTest {

    // A 136 px cap and a 126 px box, a 1 px inset: full fits up to 134 px wide.
    private fun shown(widths: Map<String, Float>, hasGlyph: (String) -> Boolean = { true }): Map<String, String> {
        val short = shortGroups(widths.keys) { fitsFull(widths.getValue(it), 50f, 136f, 126f, 1f) }
        return widths.keys.associateWith { labelFor(it, short, hasGlyph) }
    }

    @Test
    fun aLabelExactlyAtTheBoundaryStaysFullAndOnePixelMoreTurnsItsGroupShort() {
        val at = mapOf("Home" to 134f, "End" to 100f, "Tab" to 100f, "↑" to 400f)
        assertEquals(mapOf("Home" to "Home", "End" to "End", "Tab" to "Tab", "↑" to "↑"), shown(at))
        // One px over: Home and its group mate End go short together; Tab's group
        // and the arrow, which has no short form, are untouched.
        val over = at + ("Home" to 135f)
        assertEquals(mapOf("Home" to "⇱", "End" to "⇲", "Tab" to "Tab", "↑" to "↑"), shown(over))
    }

    @Test
    fun aGroupWithAGlyphTheFontLacksTakesTheAlternativeTextWhole() {
        val wide = mapOf("Ctrl" to 176f, "Alt" to 132f)
        assertEquals(mapOf("Ctrl" to "^", "Alt" to "Al"), shown(wide) { it != "⌥" })
    }
}
