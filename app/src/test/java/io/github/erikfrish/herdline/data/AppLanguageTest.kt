/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun noTagFollowsTheSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag(null))
    }

    @Test
    fun shippedLanguagesAreMatchedByLanguageNotRegion() {
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en"))
        assertEquals(AppLanguage.English, AppLanguage.fromTag("en-GB"))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromTag("zh-CN"))
        assertEquals(AppLanguage.Chinese, AppLanguage.fromTag("zh-Hans-TW"))
    }

    @Test
    fun languagesHerdlineDoesNotShipFollowTheSystem() {
        assertEquals(AppLanguage.System, AppLanguage.fromTag("fr-FR"))
        assertEquals(AppLanguage.System, AppLanguage.fromTag(""))
    }

    @Test
    fun everyPinnedLanguageRoundTripsThroughItsTag() {
        AppLanguage.entries.filter { it.tag != null }.forEach { assertEquals(it, AppLanguage.fromTag(it.tag)) }
    }
}
