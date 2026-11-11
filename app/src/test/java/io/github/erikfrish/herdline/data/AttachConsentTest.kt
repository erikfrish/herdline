package io.github.erikfrish.herdline.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachConsentTest {
    private val work = Node(id = "n1", name = "work", address = "work.lan", user = "me")
    private val home = Node(id = "n2", name = "home", address = "home.lan", user = "me")

    @Test
    fun agreeingForOneHostLeavesEveryOtherHostAsking() {
        val consent = AttachConsent(MemoryPrefs())
        assertFalse(consent.granted(work))
        consent.grant(work)
        assertTrue(consent.granted(work))
        assertFalse(consent.granted(home))
        // Same address and account under another saved entry is still another host's choice.
        assertFalse(consent.granted(work.copy(id = "n3")))
    }

    @Test
    fun theAgreementSurvivesARestart() {
        val prefs = MemoryPrefs()
        AttachConsent(prefs).grant(work)
        assertTrue(AttachConsent(prefs).granted(work))
    }

    @Test
    fun editingWhereTheFilesWouldGoAsksAgain() {
        val consent = AttachConsent(MemoryPrefs())
        consent.grant(work)
        assertFalse(consent.granted(work.copy(address = "other.lan")))
        assertFalse(consent.granted(work.copy(user = "root")))
        assertFalse(consent.granted(work.copy(port = 2222)))
        assertFalse(consent.granted(work.copy(jumpId = "bastion")))
        // A rename moves no file anywhere else.
        assertTrue(consent.granted(work.copy(name = "office")))
    }
}
