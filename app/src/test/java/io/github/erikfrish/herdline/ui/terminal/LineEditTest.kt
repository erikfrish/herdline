package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class LineEditTest {
    @Test
    fun aCorrectionOfAnEarlierWordRetypesFromTheFirstChangedCharacter() {
        // The keyboard swapped "teh" for "the" with the cursor at the end of the line:
        // "git t" stays, the rest of the line is erased and retyped.
        assertEquals(LineEdit(erase = 12, type = "he quick fox"), lineEdit("git teh quick fox", "git the quick fox"))
    }

    @Test
    fun typingAtTheEndOnlyAppendsAndDeletingOnlyErases() {
        assertEquals(LineEdit(0, "o"), lineEdit("hell", "hello"))
        assertEquals(LineEdit(2, ""), lineEdit("hello", "hel"))
        assertEquals(LineEdit(0, ""), lineEdit("same", "same"))
    }

    @Test
    fun erasingCountsCharactersNotUtf16UnitsAndNeverSplitsAPair() {
        // "👍" is one character in two UTF-16 units; "👎" shares its high surrogate.
        assertEquals(LineEdit(1, "👎"), lineEdit("ok 👍", "ok 👎"))
        assertEquals(LineEdit(2, "é"), lineEdit("caf👍e", "café"))
    }
}
