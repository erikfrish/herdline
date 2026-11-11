package io.github.erikfrish.herdline.ui.nodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddressPasteTest {
    @Test
    fun aPastedDestinationFillsTheFields() {
        assertEquals(PastedAddress("example.com", "bob", "2222"), splitPastedAddress("bob@example.com:2222"))
        assertEquals(PastedAddress("example.com", "bob", "2222"), splitPastedAddress(" ssh://bob@example.com:2222/ "))
        assertEquals(PastedAddress("::1", "bob", "2222"), splitPastedAddress("bob@[::1]:2222"))
        assertEquals(PastedAddress("example.com", "bob", null), splitPastedAddress("bob@example.com"))
        assertEquals(PastedAddress("10.0.0.2", null, "22"), splitPastedAddress("10.0.0.2:22"))
    }

    @Test
    fun aBadPortIsCarriedToPortNotDropped() {
        assertEquals(PastedAddress("example.com", "bob", "99999"), splitPastedAddress("bob@example.com:99999"))
        assertEquals(PastedAddress("example.com", null, ""), splitPastedAddress("example.com:"))
    }

    @Test
    fun aPlainHostOrNoDestinationStaysAsTyped() {
        assertNull(splitPastedAddress("example.com"))
        // A bare IPv6 address: its colons are not a port.
        assertNull(splitPastedAddress("fe80::1"))
        assertNull(splitPastedAddress("ssh bob@example.com"))
        assertNull(splitPastedAddress("@example.com"))
        assertNull(splitPastedAddress("bob@[::1"))
    }

    @Test
    fun aPortTypedIntoTheAddressBlocksSaving() {
        assertEquals(AddressProblem.PortInAddress, addressProblem("example.com:2222"))
        assertNull(addressProblem("fe80::1"))
        assertNull(addressProblem("example.com"))
        assertEquals(AddressProblem.Missing, addressProblem(""))
        assertEquals(AddressProblem.Invalid, addressProblem("bob@example.com"))
    }

    @Test
    fun aNodeSavedWithAPortInItsAddressStillSavesAndCanSplit() {
        assertNull(addressProblem("example.com:2222", saved = "example.com:2222"))
        // Edited into another host:port, the rule applies again.
        assertEquals(AddressProblem.PortInAddress, addressProblem("example.org:2222", saved = "example.com:2222"))
        assertEquals(PastedAddress("example.com", null, "2222"), portInAddress("example.com:2222"))
        assertNull(portInAddress("fe80::1"))
    }
}
