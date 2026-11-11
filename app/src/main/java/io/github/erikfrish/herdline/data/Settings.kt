/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.data

import android.content.Context
import android.content.SharedPreferences
import io.github.erikfrish.herdline.ui.terminal.DEFAULT_EXTRA_KEY_IDS
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class ThemeMode { System, Light, Dark }

/** How tightly rows, keys and the header are packed. */
enum class Density { Comfortable, Compact }

/** Phone layout with a drawer, or a persistent sidebar; Auto picks by window width. */
enum class LayoutMode { Auto, Compact, Wide }

/**
 * Switcher order: Herdr's workspaces and tabs, or the order of each agent's
 * last state change (Herdr's `state_change_seq`, no time); see docs/ordering.md.
 */
enum class TargetOrder { Workspace, LastActivity }

/**
 * What a locked screen shows. Agent, done and connection notifications use
 * [ALERTS]; [StateOnly] exists only for the pinned live update, chosen in
 * the pin sheet. Saved by name.
 */
enum class LockDetail {
    Hidden,
    StateOnly,
    NamesOnly,
    Full,
    ;

    /** Whether this level may name hosts, nodes and agents. */
    val showsNames: Boolean get() = this == NamesOnly || this == Full

    companion object {
        /** The levels of the notification lock detail; the live update has all of [entries]. */
        val ALERTS = listOf(Hidden, NamesOnly, Full)
    }
}

/** Launcher icon variants, each an activity alias. */
enum class IconVariant { Default, Mono, Contrast }

/** Which filter the Tabs list and Agents open with: Herdr's order under each. */
enum class StartFilter { All, NeedsYou, Working }

/** How the phone Tabs sheet opens: the card overview, or the Switcher list. */
enum class TabsView { Grid, List }

/** A settings page that can go back to its defaults on its own. */
enum class ResetScope { Appearance, Terminal, Composer, Layout, Notifications }

/** App preferences, observed by the UI and applied live. */
data class Settings(
    val themeMode: ThemeMode = ThemeMode.System,
    /** An AppPalettes id; an unknown one shows Herdline. */
    val appTheme: String = "herdline",
    /** A TerminalSchemes id, or "" to wear the app theme's own dark scheme. */
    val darkScheme: String = MATCH_THEME,
    /** A TerminalSchemes id, or "" to wear the app theme's own light scheme. */
    val lightScheme: String = MATCH_THEME,
    val font: String = "jetbrains-mono",
    val fontSize: Float = 13f,
    val vibrateOnBell: Boolean = true,
    val keepScreenOn: Boolean = false,
    /** Size of the app's own interface, independent of the terminal [fontSize]. */
    val uiScale: Float = 1f,
    val density: Density = Density.Comfortable,
    /** 0 follows each node's colour; otherwise 1 + an index into NodeColors. */
    val accent: Int = 0,
    val extraKeys: List<String> = DEFAULT_EXTRA_KEY_IDS,
    val showExtraKeys: Boolean = true,
    val layoutMode: LayoutMode = LayoutMode.Auto,
    /** Herdr's sidebar on a wide session: open as the list, or folded to a rail. */
    val sidebarOpen: Boolean = true,
    val targetOrder: TargetOrder = TargetOrder.Workspace,
    val startFilter: StartFilter = StartFilter.All,
    /** The phone Tabs sheet's view; the tablet sidebar ignores it. */
    val tabsView: TabsView = TabsView.Grid,
    /** Hide tabs without an agent from the Tabs list; never from the phone tab row or Tabs n/N. */
    val hideShells: Boolean = false,
    /** Hide tabs whose agents are all idle or done from the Tabs list; a tab that needs you stays. */
    val hideIdleDone: Boolean = false,
    /** Shown + menu entries in their order; see [ComposerMenu] for what always stays. */
    val composerMenu: List<String> = ComposerMenu.DEFAULT,
    /** Shown Tabs sheet and sidebar sections; their order is fixed. */
    val sheetSections: List<String> = SheetSections.DEFAULT,
    /** Keyboard suggestions while typing straight into the terminal; the composer always has them. */
    val terminalSuggestions: Boolean = false,
    val notifyAttention: Boolean = true,
    val notifyDone: Boolean = true,
    val notifyConnection: Boolean = true,
    val liveActivity: Boolean = true,
    val lockDetail: LockDetail = LockDetail.Hidden,
    val iconVariant: IconVariant = IconVariant.Default,
) {
    /**
     * These settings with the fields of [scope] back at their defaults and
     * every other field kept. The scopes together cover every field, so the
     * global reset is all of them at once.
     */
    fun restored(scope: ResetScope): Settings {
        val d = Settings()
        return when (scope) {
            ResetScope.Appearance -> copy(
                themeMode = d.themeMode,
                appTheme = d.appTheme,
                darkScheme = d.darkScheme,
                lightScheme = d.lightScheme,
                uiScale = d.uiScale,
                density = d.density,
                accent = d.accent,
                iconVariant = d.iconVariant,
            )
            ResetScope.Terminal -> copy(
                font = d.font,
                fontSize = d.fontSize,
                vibrateOnBell = d.vibrateOnBell,
                keepScreenOn = d.keepScreenOn,
                extraKeys = d.extraKeys,
                showExtraKeys = d.showExtraKeys,
            )
            ResetScope.Composer -> copy(terminalSuggestions = d.terminalSuggestions)
            ResetScope.Layout -> copy(
                layoutMode = d.layoutMode,
                sidebarOpen = d.sidebarOpen,
                targetOrder = d.targetOrder,
                startFilter = d.startFilter,
                tabsView = d.tabsView,
                hideShells = d.hideShells,
                hideIdleDone = d.hideIdleDone,
                composerMenu = d.composerMenu,
                sheetSections = d.sheetSections,
            )
            ResetScope.Notifications -> copy(
                notifyAttention = d.notifyAttention,
                notifyDone = d.notifyDone,
                notifyConnection = d.notifyConnection,
                liveActivity = d.liveActivity,
                lockDetail = d.lockDetail,
            )
        }
    }

    companion object {
        val UI_SCALES = 0.85f..1.3f

        /** The terminal scheme sentinel that follows the app theme. */
        const val MATCH_THEME = ""
    }
}

class SettingsStore internal constructor(private val prefs: SharedPreferences) {
    constructor(context: Context) : this(context.getSharedPreferences("settings", Context.MODE_PRIVATE))

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<Settings> = _settings

    private inline fun <reified E : Enum<E>> enumOf(key: String, default: E): E =
        prefs.getString(key, null)?.let { name -> enumValues<E>().firstOrNull { it.name == name } } ?: default

    private val _liveLockDetail = MutableStateFlow(
        prefs.getString(LIVE_LOCK_DETAIL, null)?.let { name -> LockDetail.entries.firstOrNull { it.name == name } },
    )

    /**
     * The live update's lock detail, null until the user first taps Pin in
     * the pin sheet. Kept outside [Settings] so no default, reset or
     * migration ever writes it; until then the live update uses [Settings.lockDetail].
     */
    val liveLockDetail: StateFlow<LockDetail?> = _liveLockDetail

    /** The notification lock detail if it was ever saved, or null on a fresh install. */
    fun savedLockDetail(): LockDetail? = if (prefs.contains("lock_detail")) _settings.value.lockDetail else null

    /** The level the user picked in the pin sheet; the only writer of [liveLockDetail]. */
    fun chooseLiveLockDetail(level: LockDetail) {
        prefs.edit().putString(LIVE_LOCK_DETAIL, level.name).apply()
        _liveLockDetail.value = level
    }

    private fun load(): Settings {
        val d = Settings()
        return Settings(
            themeMode = enumOf("theme_mode", d.themeMode),
            appTheme = prefs.getString("app_theme", null) ?: d.appTheme,
            darkScheme = prefs.getString("dark_scheme", null) ?: d.darkScheme,
            lightScheme = prefs.getString("light_scheme", null) ?: d.lightScheme,
            font = prefs.getString("font", null) ?: d.font,
            fontSize = prefs.getFloat("font_size", d.fontSize),
            vibrateOnBell = prefs.getBoolean("vibrate_on_bell", d.vibrateOnBell),
            keepScreenOn = prefs.getBoolean("keep_screen_on", d.keepScreenOn),
            uiScale = prefs.getFloat("ui_scale", d.uiScale).coerceIn(Settings.UI_SCALES),
            density = enumOf("density", d.density),
            accent = prefs.getInt("accent", d.accent),
            extraKeys = prefs.getString("extra_keys", null)?.let { saved ->
                saved.split(',').filter { it.isNotEmpty() }
            } ?: d.extraKeys,
            showExtraKeys = prefs.getBoolean("show_extra_keys", d.showExtraKeys),
            layoutMode = enumOf("layout_mode", d.layoutMode),
            sidebarOpen = prefs.getBoolean("sidebar_open", d.sidebarOpen),
            targetOrder = enumOf("target_order", d.targetOrder),
            startFilter = enumOf("start_filter", d.startFilter),
            tabsView = enumOf("tabs_view", d.tabsView),
            hideShells = prefs.getBoolean("hide_shells", d.hideShells),
            hideIdleDone = prefs.getBoolean("hide_idle_done", d.hideIdleDone),
            composerMenu = ComposerMenu.repaired(savedControls(ids("composer_menu"), ids("composer_menu_known"), ComposerMenu.DEFAULT)),
            sheetSections = savedControls(ids("sheet_sections"), ids("sheet_sections_known"), SheetSections.DEFAULT),
            terminalSuggestions = prefs.getBoolean("terminal_suggestions", d.terminalSuggestions),
            notifyAttention = prefs.getBoolean("notify_attention", d.notifyAttention),
            notifyDone = prefs.getBoolean("notify_done", d.notifyDone),
            notifyConnection = prefs.getBoolean("notify_connection", d.notifyConnection),
            liveActivity = prefs.getBoolean("live_activity", d.liveActivity),
            lockDetail = enumOf("lock_detail", d.lockDetail),
            iconVariant = enumOf("icon_variant", d.iconVariant),
        )
    }

    /** A saved id list; an empty string is an empty list, a missing key null. */
    private fun ids(key: String): List<String>? = prefs.getString(key, null)?.split(',')?.filter { it.isNotEmpty() }

    fun update(change: (Settings) -> Settings) {
        // Insert without Enter keeps its route whatever the caller wrote (parent Q-2).
        val next = change(_settings.value).let { it.copy(composerMenu = ComposerMenu.repaired(it.composerMenu)) }
        prefs.edit()
            .putString("theme_mode", next.themeMode.name)
            .putString("app_theme", next.appTheme)
            .putString("dark_scheme", next.darkScheme)
            .putString("light_scheme", next.lightScheme)
            .putString("font", next.font)
            .putFloat("font_size", next.fontSize)
            .putBoolean("vibrate_on_bell", next.vibrateOnBell)
            .putBoolean("keep_screen_on", next.keepScreenOn)
            .putFloat("ui_scale", next.uiScale.coerceIn(Settings.UI_SCALES))
            .putString("density", next.density.name)
            .putInt("accent", next.accent)
            .putString("extra_keys", next.extraKeys.joinToString(","))
            .putBoolean("show_extra_keys", next.showExtraKeys)
            .putString("layout_mode", next.layoutMode.name)
            .putBoolean("sidebar_open", next.sidebarOpen)
            .putString("target_order", next.targetOrder.name)
            .putString("start_filter", next.startFilter.name)
            .putString("tabs_view", next.tabsView.name)
            .putBoolean("hide_shells", next.hideShells)
            .putBoolean("hide_idle_done", next.hideIdleDone)
            .putString("composer_menu", next.composerMenu.joinToString(","))
            .putString("composer_menu_known", ComposerMenu.DEFAULT.joinToString(","))
            .putString("sheet_sections", next.sheetSections.joinToString(","))
            .putString("sheet_sections_known", SheetSections.DEFAULT.joinToString(","))
            .putBoolean("terminal_suggestions", next.terminalSuggestions)
            .putBoolean("notify_attention", next.notifyAttention)
            .putBoolean("notify_done", next.notifyDone)
            .putBoolean("notify_connection", next.notifyConnection)
            .putBoolean("live_activity", next.liveActivity)
            .putString("lock_detail", next.lockDetail.name)
            .putString("icon_variant", next.iconVariant.name)
            .apply()
        _settings.value = next
    }

    /**
     * Back to the defaults for everything on the settings screen. The
     * terminal font and colour schemes are reset too; downloaded fonts stay.
     */
    fun reset() = update { Settings() }

    /** Back to the defaults for one settings page only; see [Settings.restored]. */
    fun restore(scope: ResetScope) = update { it.restored(scope) }

    /** The terminal size last laid out, so a new connection starts close to it. */
    var lastGrid: IntArray
        get() = intArrayOf(prefs.getInt("cols", 80), prefs.getInt("rows", 24), prefs.getInt("cw", 0), prefs.getInt("ch", 0))
        set(value) {
            prefs.edit().putInt("cols", value[0]).putInt("rows", value[1]).putInt("cw", value[2]).putInt("ch", value[3]).apply()
        }

    private companion object {
        const val LIVE_LOCK_DETAIL = "live_lock_detail"
    }
}
