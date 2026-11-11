package io.github.erikfrish.herdline.ui.theme

import android.os.Build
import io.github.erikfrish.herdline.data.Settings
import io.github.erikfrish.herdline.data.ThemeMode

/** Whether [this] mode shows the dark variant; System follows [systemDark]. */
fun ThemeMode.isDark(systemDark: Boolean): Boolean = when (this) {
    ThemeMode.System -> systemDark
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
}

/** The chosen app theme; an unknown id is Herdline. */
val Settings.palette: AppPalette get() = AppPalettes.byId(appTheme)

/**
 * Whether the app wears the wallpaper's colours (M7). Below API 31, e.g.
 * after a restore onto an older device, it wears [palette] (Herdline) and
 * the stored id survives.
 */
val Settings.dynamicColor: Boolean
    get() = appTheme == AppPalettes.MaterialYouId && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

/**
 * The terminal scheme for a [dark] or light screen: the explicit override
 * when one is set and known, else the app theme's own.
 */
fun Settings.terminalScheme(dark: Boolean): TerminalScheme =
    TerminalSchemes.byId(if (dark) darkScheme else lightScheme, dark, fallback = palette.tokens(dark).terminalScheme)
