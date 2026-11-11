package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Test

class HiddenOffersTest {
    @Test
    fun keyboardDownAndNothingHiddenOffersNoAction() {
        // The reason alone: no zoom, which would change the host and not the phone.
        assertEquals(emptyList<HiddenOffer>(), hiddenOffers(imeVisible = false, hiddenRows = 0))
    }

    @Test
    fun eachCauseOnScreenOffersItsOwnRemedy() {
        assertEquals(listOf(HiddenOffer.HideKeyboard), hiddenOffers(imeVisible = true, hiddenRows = 0))
        assertEquals(listOf(HiddenOffer.BackToBottom), hiddenOffers(imeVisible = false, hiddenRows = 3))
        assertEquals(listOf(HiddenOffer.HideKeyboard, HiddenOffer.BackToBottom), hiddenOffers(imeVisible = true, hiddenRows = 1))
    }
}
