package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/** TalkBack names: a localized one for each glyph-only key, the printed label for the rest. */
class SpokenNamesTest {

    @Test
    fun everyGlyphOnlyKeyHasItsOwnNameAndNamedOrTextKeysReadTheirLabel() {
        val glyphOnly = AllExtraKeys.values
            .filter { it is ExtraKey.Key && it.label.none(Char::isLetterOrDigit) }
            .map { it.label }
        // Exactly those, each with a resource of its own: a key added later needs a name, and no two read alike.
        assertEquals(glyphOnly.toSet(), SpokenNames.keys)
        assertEquals(glyphOnly.size, SpokenNames.values.toSet().size)
    }
}
