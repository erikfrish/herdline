package io.github.erikfrish.herdline.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReadableTitleTest {
    @Test
    fun dropsIconGlyphsSpinnerAndOmpPrefix() {
        assertEquals("PONG: reply", readableTitle("π > \uDB80\uDF68 PONG: reply", "omp"))
        assertEquals("PONG: reply", readableTitle("π > ⠋ PONG: reply", "omp"))
        // While OMP works its title is "π ⠹ ‹icon› task", without the ">".
        assertEquals("PONG: reply", readableTitle("π ⠹ \uDB80\uDF68 PONG: reply", "omp"))
    }

    @Test
    fun everyOmpStatusMarkIsCut() {
        // lab.4 G3: a waiting agent's "π ! …" became the placeholder "Message ! …".
        assertEquals("Reply with SFOUR", readableTitle("π > Reply with SFOUR", "omp"))
        assertEquals("Reply with SFOUR", readableTitle("π ! Reply with SFOUR", "omp"))
        assertEquals("Reply with SFOUR", readableTitle("π ✻ Reply with SFOUR", "omp"))
        assertEquals("Reply with SFOUR", readableTitle("π ! \uDB80\uDF68 Reply with SFOUR", null))
        assertEquals("πfeed", readableTitle("πfeed", "omp"))
    }

    @Test
    fun anUnrecognisedPaneLabelLosesTheOmpPrefixAndSpinners() {
        // lab.3: a tab named by its pane label read "π > ⟦tofu⟧ PONG: Reply with th…".
        assertEquals("PONG: Reply", readableTitle("π > \uDB80\uDF68 PONG: Reply", null))
        assertEquals("Thinking hard", readableTitle("✻ Thinking  hard", "claude"))
    }

    @Test
    fun keepsPromptLikeTextOfOtherPrograms() {
        assertEquals("π > notes", readableTitle("π > notes", "claude"))
        assertEquals("a b", readableTitle("a \uE0B0  b", null))
    }

    @Test
    fun nothingLeftIsNoTitle() {
        assertNull(readableTitle("π > \uE0B0 ⠋", "omp"))
        assertNull(readableTitle(null, "omp"))
    }
}
