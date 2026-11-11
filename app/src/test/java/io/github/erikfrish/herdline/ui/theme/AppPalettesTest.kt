package io.github.erikfrish.herdline.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AppPalettesTest {
    @Test
    fun aMissingOrUnknownIdIsTheDefault() {
        // No stored choice (lab.8 and earlier) and a removed palette both get the default look.
        assertSame(AppPalettes.Herdline, AppPalettes.byId(null))
        assertSame(AppPalettes.Herdline, AppPalettes.byId("removed-palette"))
        AppPalettes.all.forEach { assertSame(it, AppPalettes.byId(it.id)) }
    }

    @Test
    fun everyPaletteIdIsDistinct() {
        // A stored id must name exactly one palette.
        assertEquals(AppPalettes.all.size, AppPalettes.all.map { it.id }.toSet().size)
    }
}
