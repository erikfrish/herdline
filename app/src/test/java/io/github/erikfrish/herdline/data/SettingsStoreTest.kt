package io.github.erikfrish.herdline.data

import io.github.erikfrish.herdline.ui.theme.AppPalettes
import io.github.erikfrish.herdline.ui.theme.palette
import io.github.erikfrish.herdline.ui.theme.terminalScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** The stored theme and terminal schemes. */
class SettingsStoreTest {
    private fun store(vararg saved: kotlin.Pair<String, Any>) = SettingsStore(MemoryPrefs().apply { values.putAll(saved) })

    @Test
    fun aThemeAndSchemesSurviveARestart() {
        val prefs = MemoryPrefs()
        SettingsStore(prefs).update { it.copy(appTheme = "gruvbox", darkScheme = "dracula", lightScheme = Settings.MATCH_THEME) }
        val reloaded = SettingsStore(prefs).settings.value
        assertEquals("gruvbox", reloaded.appTheme)
        assertEquals("dracula", reloaded.darkScheme)
        assertEquals(Settings.MATCH_THEME, reloaded.lightScheme)
    }

    @Test
    fun aChosenSchemeStaysUnderAnyTheme() {
        val settings = store("dark_scheme" to "dracula", "light_scheme" to "solarized-light").settings.value
        assertEquals("dracula", settings.copy(appTheme = "catppuccin").terminalScheme(true).id)
        assertEquals("solarized-light", settings.copy(appTheme = "catppuccin").terminalScheme(false).id)
    }

    @Test
    fun aFreshInstallGetsHerdlineEverywhere() {
        val settings = store().settings.value
        assertEquals(Settings(), settings)
        assertEquals("herdline-dark", settings.terminalScheme(true).id)
        assertEquals("herdline-light", settings.terminalScheme(false).id)
    }

    @Test
    fun anUnknownThemeLooksLikeHerdlineAndIsKept() {
        // A theme from a newer build, after a downgrade: shown as Herdline, not overwritten.
        val prefs = MemoryPrefs().apply { values["app_theme"] = "from-the-future" }
        val store = SettingsStore(prefs)
        assertSame(AppPalettes.Herdline, store.settings.value.palette)
        store.update { it.copy(fontSize = 16f) }
        assertEquals("from-the-future", prefs.values["app_theme"])
    }

    @Test
    fun anUnknownSchemeShowsTheThemesOwn() {
        val settings = store("app_theme" to "everforest", "dark_scheme" to "removed", "light_scheme" to "removed").settings.value
        assertEquals("everforest-dark", settings.terminalScheme(true).id)
        assertEquals("everforest-light", settings.terminalScheme(false).id)
    }
}
