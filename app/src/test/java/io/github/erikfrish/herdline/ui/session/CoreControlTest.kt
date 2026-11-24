package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreControlTest {
    @Test
    fun `Terminal holds the pane it shows, as before, on either backend`() {
        assertEquals("p1", control(chatMode = false, core = true))
        assertEquals("p1", control(chatMode = false, core = false))
        assertEquals("p1", control(chatMode = false, desktopInteractive = false))
        assertNull(control(chatMode = false, desktopInteractive = null))
        assertNull(control(chatMode = false, closed = true))
        assertNull(control(chatMode = false, connected = false))
    }

    @Test
    fun `Chat holds only the read pane a Fit tap named, on herdr-core`() {
        assertEquals("p2", control(fitPane = "p2"))
        assertNull(control(fitPane = null))
        assertNull(control(fitPane = "p3"))
        assertNull(control(fitPane = "p2", core = false))
        assertNull(control(fitPane = "p2", closed = true))
        assertNull(control(fitPane = "p2", connected = false))
        assertNull(control(fitPane = "p2", readInteractive = null))
    }

    @Test
    fun `a Fit tap on Herdr holds nothing`() {
        assertNull(fitAfterTap(core = false, pane = "p2"))
        assertEquals("p2", fitAfterTap(core = true, pane = "p2"))
    }

    @Test
    fun `a released Fit is gone, and the same conditions back do not take the pane again`() {
        // Another read pane, a lost connection, a closed pane, or Chat left: each releases and clears the Fit.
        for (released in listOf(
            control(fitPane = "p2", readPane = "p3"),
            control(fitPane = "p2", connected = false),
            control(fitPane = "p2", closed = true),
        )) {
            val fit = keptFit(chatMode = true, fitPane = "p2", controlPane = released)
            assertNull(fit)
            assertNull(control(fitPane = fit))
        }
        assertNull(keptFit(chatMode = false, fitPane = "p2", controlPane = control(chatMode = false, desktopPane = "p2")))
        assertEquals("p2", keptFit(chatMode = true, fitPane = "p2", controlPane = "p2"))
    }

    @Test
    fun `a resume takes Terminal's pane again, but never a Fit cleared on pause`() {
        assertTrue(resumeTakes(chatMode = false, controlPane = "p1", fitPane = null))
        assertTrue(resumeTakes(chatMode = true, controlPane = "p2", fitPane = "p2"))
        // The pause cleared fitPane; the hold may still be composed when the resume comes.
        assertFalse(resumeTakes(chatMode = true, controlPane = "p2", fitPane = null))
        assertNull(control(fitPane = null))
    }

    private fun control(
        chatMode: Boolean = true,
        closed: Boolean = false,
        connected: Boolean = true,
        desktopPane: String? = "p1",
        desktopInteractive: Boolean? = true,
        core: Boolean = true,
        readPane: String? = "p2",
        readInteractive: Boolean? = false,
        fitPane: String? = null,
    ) = coreControlPane(chatMode, closed, connected, desktopPane, desktopInteractive, core, readPane, readInteractive, fitPane)
}
