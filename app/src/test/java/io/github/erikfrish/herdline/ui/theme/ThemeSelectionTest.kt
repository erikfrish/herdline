package io.github.erikfrish.herdline.ui.theme

import io.github.erikfrish.herdline.data.Settings
import io.github.erikfrish.herdline.data.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which variant of the chosen theme is on screen. */
class ThemeSelectionTest {
    @Test
    fun systemFollowsTheDeviceAndTheOthersDoNot() {
        assertTrue(ThemeMode.System.isDark(systemDark = true))
        assertFalse(ThemeMode.System.isDark(systemDark = false))
        listOf(true, false).forEach { system ->
            assertTrue(ThemeMode.Dark.isDark(system))
            assertFalse(ThemeMode.Light.isDark(system))
        }
    }

    @Test
    fun aDarkOnlyThemeInLightWearsHerdlineLight() {
        AppPalettes.all.filter { it.darkOnly }.forEach { palette ->
            assertSame(AppPalettes.Herdline.tokens(false), palette.tokens(false))
            val settings = Settings(appTheme = palette.id)
            assertEquals("herdline-light", settings.terminalScheme(false).id)
            assertEquals(palette.tokens(true).terminalScheme, settings.terminalScheme(true).id)
        }
    }

    @Test
    fun materialYouWithoutDynamicColourWearsHerdline() {
        // Below API 31 (and on the JVM) a stored Material You is Herdline, terminal included; the id is kept.
        val settings = Settings(appTheme = AppPalettes.MaterialYouId)
        assertFalse(settings.dynamicColor)
        assertSame(AppPalettes.Herdline, settings.palette)
        assertEquals("herdline-dark", settings.terminalScheme(true).id)
    }
}
