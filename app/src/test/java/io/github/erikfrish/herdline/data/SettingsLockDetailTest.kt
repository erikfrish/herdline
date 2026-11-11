package io.github.erikfrish.herdline.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pin sheet preselection (parent ruling): a saved notification lock level
 * always wins, Hidden included; only a level never written leaves the
 * choice open.
 */
class SettingsLockDetailTest {
    @Test
    fun aLockLevelNeverWrittenIsNotSaved() {
        assertNull(SettingsStore(MemoryPrefs()).savedLockDetail())
    }

    @Test
    fun aWrittenHiddenIsSavedEvenThoughItIsTheDefault() {
        val prefs = MemoryPrefs().apply { values["lock_detail"] = LockDetail.Hidden.name }
        assertEquals(LockDetail.Hidden, SettingsStore(prefs).savedLockDetail())
    }

    @Test
    fun savingAnyOtherSettingSavesTheCurrentLockLevel() {
        val prefs = MemoryPrefs()
        SettingsStore(prefs).update { it.copy(hideShells = !it.hideShells) }
        // A fresh store, as after a restart: the write is in the preferences, not only in memory.
        assertEquals(LockDetail.Hidden, SettingsStore(prefs).savedLockDetail())
    }

    @Test
    fun theAlertPickerRadiosAreHiddenNamesFullAndNeverStateOnly() {
        // CustomSettings maps radio i to ALERTS[i]; entries[i] would make the second radio StateOnly.
        assertEquals(listOf(LockDetail.Hidden, LockDetail.NamesOnly, LockDetail.Full), LockDetail.ALERTS)
    }
}
