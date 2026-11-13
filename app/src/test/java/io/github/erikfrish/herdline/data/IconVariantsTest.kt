package io.github.erikfrish.herdline.data

import io.github.erikfrish.herdline.R
import org.junit.Assert.*
import org.junit.Test

/** The picker's disclosure: which variant the installed application icon (the notification badge) is. */
class IconVariantsTest {
    @Test
    fun installedWoodIsTheDefaultVariantAndOnlyItFollowsTheBadge() {
        assertEquals(IconVariant.Default, installedOf(R.mipmap.ic_launcher))
        for (variant in IconVariant.entries) assertEquals(variant == IconVariant.Default, installedOf(R.mipmap.ic_launcher) == variant)
    }

    @Test
    fun everyVariantIsRecognisedAsItselfAndAnUnknownIconAsNone() {
        for (variant in IconVariant.entries) assertEquals(variant, installedOf(variant.drawableRes))
        assertNull(installedOf(0))
        assertNull(installedOf(R.drawable.ic_notification))
    }
}
