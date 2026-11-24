package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionMotionTest {
    private val wide = SegmentGeometry(Edges(0f, 120f), Edges(120f, 260f))
    private val glyphs = SegmentGeometry(Edges(0f, 96f), Edges(96f, 192f))

    @Test
    fun pillGlidesOnlyForATapAcrossAnUnchangedRow() {
        assertFalse(indicatorSnaps(wide, wide, tapped = true))
        // First placement, harness or restore, and any geometry change jump.
        assertTrue(indicatorSnaps(null, wide, tapped = true))
        assertTrue(indicatorSnaps(wide, wide, tapped = false))
        assertTrue(indicatorSnaps(wide, glyphs, tapped = true))
    }

    @Test
    fun sendPopsOnlyAsItTurnsOn() {
        assertTrue(popsOnEnable(false, true))
        assertFalse(popsOnEnable(null, true))
        assertFalse(popsOnEnable(true, true))
        assertFalse(popsOnEnable(true, false))
        assertFalse(popsOnEnable(false, false))
    }

    @Test
    fun onlyATapThatStartedADeliveryCounts() {
        assertEquals(3, sendsAfterTap(2, wasSending = false, nowSending = true))
        // Refused by the precheck: nothing left.
        assertEquals(2, sendsAfterTap(2, wasSending = false, nowSending = false))
        // A second tap while the first is still going.
        assertEquals(2, sendsAfterTap(2, wasSending = true, nowSending = true))
    }

    @Test
    fun iconFlicksOncePerDeliveryAfterItWasShown() {
        assertTrue(flicksOnSend(2, 3))
        assertFalse(flicksOnSend(null, 3))
        assertFalse(flicksOnSend(3, 3))
        // Another draft's count starts again from 0.
        assertFalse(flicksOnSend(3, 0))
    }
}
