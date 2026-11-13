/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.data

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's display language. [System] follows the device; the others pin
 * one of the languages Herdline ships, by BCP 47 [tag].
 */
enum class AppLanguage(val tag: String?) {
    System(null),
    English("en"),
    Chinese("zh-CN"),
    ;

    companion object {
        /** The language a stored or system-reported tag names, else [System]. */
        fun fromTag(tag: String?): AppLanguage {
            val language = tag?.substringBefore('-')?.lowercase() ?: return System
            return entries.firstOrNull { it.tag?.substringBefore('-') == language } ?: System
        }
    }
}

/**
 * Applies the chosen [AppLanguage]. From Android 13 the system keeps the
 * per-app language, so it also shows under the app's settings there; before
 * that the choice is a preference applied to each context the app creates.
 */
object AppLanguages {
    private const val PREFS = "language"
    private const val KEY = "tag"

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.System else AppLanguage.fromTag(locales[0].toLanguageTag())
        } else {
            AppLanguage.fromTag(stored(context))
        }

    /**
     * Bumped when [set] changes the language below Android 13, where no
     * configuration change reaches the app or the running service: on each
     * bump HerdlineApp re-words the channels and agent notes, and
     * ConnectionService its live and lost cards. From Android 13 the
     * per-app locale arrives as a configuration change instead.
     */
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes

    /** Switches the app to [language]; the activity is recreated to show it. */
    fun set(activity: Activity, language: AppLanguage) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The system recreates the activity itself.
            activity.getSystemService(LocaleManager::class.java).applicationLocales =
                language.tag?.let(LocaleList::forLanguageTags) ?: LocaleList.getEmptyLocaleList()
        } else {
            activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, language.tag).commit()
            _changes.value++
            activity.recreate()
        }
    }

    /**
     * [base] with the chosen language below Android 13, for attachBaseContext
     * of the application, activities, and services; unchanged otherwise.
     */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = AppLanguage.fromTag(stored(base)).tag ?: return base
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList.forLanguageTags(tag))
        return base.createConfigurationContext(configuration)
    }

    /** The last [localized] context, with the stored tag and system locales it was made for. */
    private class Localized(val tag: String?, val system: LocaleList, val context: Context)

    @Volatile
    private var localizedCache: Localized? = null

    /**
     * A context whose resources speak the language in effect now, for text
     * built outside an activity (notifications). From Android 13 the system
     * applies the per-app locale to every context, so [context] itself.
     * Before that the language of a context wrapped at its creation never
     * changes, so this one follows the stored choice, else the current
     * system locales, and is rebuilt only when either changed.
     */
    fun localized(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return context
        val app = context.applicationContext
        val tag = AppLanguage.fromTag(stored(app)).tag
        val system = Resources.getSystem().configuration.locales
        localizedCache?.let { if (it.tag == tag && it.system == system) return it.context }
        val configuration = Configuration(app.resources.configuration)
        configuration.setLocales(tag?.let(LocaleList::forLanguageTags) ?: system)
        return app.createConfigurationContext(configuration).also { localizedCache = Localized(tag, system, it) }
    }

    private fun stored(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)
}
