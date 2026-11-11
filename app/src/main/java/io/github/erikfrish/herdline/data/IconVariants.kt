package io.github.erikfrish.herdline.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Switches the launcher icon by enabling exactly one activity alias. */
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
}
