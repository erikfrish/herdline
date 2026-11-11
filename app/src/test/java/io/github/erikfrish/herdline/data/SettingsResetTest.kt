package io.github.erikfrish.herdline.data

import java.lang.reflect.Modifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SettingsResetTest {
    // Every field off its default, so a restore that misses or overreaches shows.
    private val changed = Settings(
        themeMode = ThemeMode.Dark,
        appTheme = "nord",
        darkScheme = "dracula",
        lightScheme = "solarized-light",
        font = "custom:mine",
        fontSize = 18f,
        vibrateOnBell = false,
        keepScreenOn = true,
        uiScale = 1.2f,
        density = Density.Compact,
        accent = 3,
        extraKeys = listOf("esc"),
        showExtraKeys = false,
        layoutMode = LayoutMode.Wide,
        sidebarOpen = false,
        targetOrder = TargetOrder.LastActivity,
        startFilter = StartFilter.NeedsYou,
        tabsView = TabsView.TwoColumns,
        hideShells = true,
        hideIdleDone = true,
        composerMenu = listOf("larger_editor", "insert"),
        sheetSections = listOf("filters"),
        terminalSuggestions = true,
        notifyAttention = false,
        notifyDone = false,
        notifyConnection = false,
        liveActivity = false,
        lockDetail = LockDetail.Full,
        iconVariant = IconVariant.Mono,
    )

    private val fields: Map<String, (Settings) -> Any?> = mapOf(
        "themeMode" to { it.themeMode },
        "appTheme" to { it.appTheme },
        "darkScheme" to { it.darkScheme },
        "lightScheme" to { it.lightScheme },
        "font" to { it.font },
        "fontSize" to { it.fontSize },
        "vibrateOnBell" to { it.vibrateOnBell },
        "keepScreenOn" to { it.keepScreenOn },
        "uiScale" to { it.uiScale },
        "density" to { it.density },
        "accent" to { it.accent },
        "extraKeys" to { it.extraKeys },
        "showExtraKeys" to { it.showExtraKeys },
        "layoutMode" to { it.layoutMode },
        "sidebarOpen" to { it.sidebarOpen },
        "targetOrder" to { it.targetOrder },
        "startFilter" to { it.startFilter },
        "tabsView" to { it.tabsView },
        "hideShells" to { it.hideShells },
        "composerMenu" to { it.composerMenu },
        "sheetSections" to { it.sheetSections },
        "hideIdleDone" to { it.hideIdleDone },
        "terminalSuggestions" to { it.terminalSuggestions },
        "notifyAttention" to { it.notifyAttention },
        "notifyDone" to { it.notifyDone },
        "notifyConnection" to { it.notifyConnection },
        "liveActivity" to { it.liveActivity },
        "lockDetail" to { it.lockDetail },
        "iconVariant" to { it.iconVariant },
    )

    // What each section page promises its confirmation restores.
    private val scopes = mapOf(
        ResetScope.Appearance to setOf("themeMode", "appTheme", "darkScheme", "lightScheme", "uiScale", "density", "accent", "iconVariant"),
        ResetScope.Terminal to setOf("font", "fontSize", "vibrateOnBell", "keepScreenOn", "extraKeys", "showExtraKeys"),
        ResetScope.Composer to setOf("terminalSuggestions"),
        ResetScope.Layout to setOf("layoutMode", "sidebarOpen", "targetOrder", "startFilter", "tabsView", "hideShells", "hideIdleDone", "composerMenu", "sheetSections"),
        ResetScope.Notifications to setOf("notifyAttention", "notifyDone", "notifyConnection", "liveActivity", "lockDetail"),
    )

    @Test
    fun everySettingBelongsToExactlyOneSectionReset() {
        // A new setting must be placed in a section, or no page can restore it.
        val declared = Settings::class.java.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(declared, fields.keys)
        assertEquals(fields.keys, scopes.values.flatten().toSet())
        assertEquals(fields.size, scopes.values.sumOf { it.size })
        for ((name, of) in fields) assertNotEquals(name, of(Settings()), of(changed))
    }

    @Test
    fun aSectionResetRestoresItsOwnFieldsAndKeepsEveryOther() {
        val defaults = Settings()
        for ((scope, names) in scopes) {
            val restored = changed.restored(scope)
            for ((name, of) in fields) {
                val expected = if (name in names) of(defaults) else of(changed)
                assertEquals("$scope › $name", expected, of(restored))
            }
        }
    }
}
