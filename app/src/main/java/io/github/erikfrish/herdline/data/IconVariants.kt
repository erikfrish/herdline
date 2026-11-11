package io.github.erikfrish.herdline.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import java.util.EnumMap

/**
 * The app's icon identity outside the launcher. [apply] switches the launcher
 * entry; [icon] gives the same variant as one bitmap for Recents task icons,
 * notification large icons and in-app logos. The icon Android Settings and the
 * system's package views show stays the manifest's static one: the platform
 * reads it from the installed manifest and no app can change it at runtime.
 */
object IconVariants {
    private fun alias(variant: IconVariant) = "io.github.erikfrish.herdline.ui.Launcher" + variant.name

    /** Makes [variant] the launcher entry. Binder calls: run off the main thread. */
    fun apply(context: Context, variant: IconVariant) {
        val packages = context.packageManager
        // Enable the new entry first, so there is never a moment without one.
        val ordered = IconVariant.entries.sortedBy { it != variant }
        for (each in ordered) {
            val component = ComponentName(context.packageName, alias(each))
            val wanted = if (each == variant) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            val current = packages.getComponentEnabledSetting(component)
            val effective = if (current == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
                if (each == IconVariant.Default) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                current
            }
            if (effective != wanted) packages.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
        }
    }

    /**
     * The full-colour icon of [variant] as one bitmap, drawn from the variant's
     * launcher-ready adaptive icon. Cached per variant for the life of the
     * process, bounded to the enum's entries: a posted notification or a Recents
     * [android.app.ActivityManager.TaskDescription] holds the instance, so it is
     * never recycled and never re-drawn per notification.
     */
    @Synchronized
    fun icon(context: Context, variant: IconVariant): Bitmap = icons.getOrPut(variant) {
        val size = 256 // large icons and task icons never show more detail
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val glyph = checkNotNull(context.getDrawable(variant.drawableRes)) { variant }
        glyph.setBounds(0, 0, size, size)
        glyph.draw(Canvas(bitmap))
        bitmap
    }

    /** [icon] of the selected setting: the current identity of every surface. */
    fun currentIcon(context: Context): Bitmap =
        icon(context, (context.applicationContext as HerdlineApp).settings.settings.value.iconVariant)

    private val icons = EnumMap<IconVariant, Bitmap>(IconVariant::class.java)
}

/** The adaptive launcher icon of this variant, shown by the settings pickers. */
val IconVariant.drawableRes: Int
    get() = when (this) {
        IconVariant.Default -> R.mipmap.ic_launcher
        IconVariant.Mono -> R.mipmap.ic_launcher_mono
        IconVariant.Contrast -> R.mipmap.ic_launcher_contrast
        IconVariant.Ember -> R.mipmap.ic_launcher_ember
        IconVariant.Signal -> R.mipmap.ic_launcher_signal
        IconVariant.Pasture -> R.mipmap.ic_launcher_pasture
        IconVariant.Meadow -> R.mipmap.ic_launcher_meadow
        IconVariant.Dusk -> R.mipmap.ic_launcher_dusk
    }

/** The localized name of this variant, as shown by the settings pickers. */
val IconVariant.labelRes: Int
    get() = when (this) {
        IconVariant.Default -> R.string.icon_label_wood
        IconVariant.Mono -> R.string.icon_label_mono
        IconVariant.Contrast -> R.string.icon_label_contrast
        IconVariant.Ember -> R.string.icon_label_ember
        IconVariant.Signal -> R.string.icon_label_signal
        IconVariant.Pasture -> R.string.icon_label_pasture
        IconVariant.Meadow -> R.string.icon_label_meadow
        IconVariant.Dusk -> R.string.icon_label_dusk
    }
