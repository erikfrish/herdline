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

    @Test
    fun anObsoleteGridTabsViewBecomesListAndIsWrittenBackOnce() {
        val prefs = MemoryPrefs().apply { values["tabs_view"] = "Grid" }
        assertEquals(TabsView.List, SettingsStore(prefs).settings.value.tabsView)
        // The write-once migration leaves one remembered choice: a later TwoColumns is never touched.
        assertEquals("List", prefs.values["tabs_view"])
        SettingsStore(prefs).update { it.copy(tabsView = TabsView.TwoColumns) }
        assertEquals(TabsView.TwoColumns, SettingsStore(prefs).settings.value.tabsView)
    }

    @Test
    fun anOldDefaultExtraKeyRowTakesTheNewDefaultAndACustomOneStays() {
        // The sixteen-key default and 0.6.22's flat fourteen: each is the default again, written back once.
        for (old in listOf(
            "esc,tab,ctrl,alt,left,down,up,right,pipe,dash,slash,tilde,home,end,pgup,pgdn",
            "esc,tab,ctrl,left,down,up,right,alt,enter,backspace,pipe,slash,home,end",
        )) {
            val prefs = MemoryPrefs().apply { values["extra_keys"] = old }
            assertEquals(old, Settings().extraKeys, SettingsStore(prefs).settings.value.extraKeys)
            assertEquals(old, Settings().extraKeys.joinToString(","), prefs.values["extra_keys"])
        }
        // A row the user changed keeps its order and contents, even the 0.6.22 keys with two swapped.
        for (custom in listOf("pgup,pgdn,esc", "tab,esc,ctrl,left,down,up,right,alt,enter,backspace,pipe,slash,home,end")) {
            val customPrefs = MemoryPrefs().apply { values["extra_keys"] = custom }
            assertEquals(custom.split(","), SettingsStore(customPrefs).settings.value.extraKeys)
            assertEquals(custom, customPrefs.values["extra_keys"])
        }
    }

    @Test
    fun notificationContentKeepsItsOrderAndPrivacyAcrossRestartAndReset() {
        val prefs = MemoryPrefs()
        val store = SettingsStore(prefs)
        store.chooseLiveLockDetail(LockDetail.NamesOnly)
        store.update { it.copy(notificationFields = listOf("host", "state", "host", "unknown"), lockDetail = LockDetail.Full) }
        val reloaded = SettingsStore(prefs)
        assertEquals(listOf("host", "state"), reloaded.settings.value.notificationFields)
        assertEquals(LockDetail.NamesOnly, reloaded.liveLockDetail.value)
        reloaded.update { it.copy(notificationFields = NotificationFields.DEFAULT) }
        assertEquals(LockDetail.Full, reloaded.settings.value.lockDetail)
        assertEquals(LockDetail.NamesOnly, reloaded.liveLockDetail.value)
    }
}
