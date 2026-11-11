package io.github.erikfrish.herdline.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** An unconfirmed delivery's grid grows back to its pre-delivery height and never past it (S-4a-r, HG-1). */
class GridFreezeTest {
    private val cell = 40f
    private val width = 1080
    private val now = 10_000L

    /** The rows a view of [rows] cells at [cellPx] reports, as reportGrid computes them, with no hold. */
    private fun GridFreeze.rows(rows: Int, cellPx: Float = cell, at: Int = width): Int =
        gridRows((rows * cellPx).toInt(), cellPx, hold = false, holdHeightPx = 0, lastRows = 0, capPx = cap(at))

    private fun frozenAt(rows: Int) = GridFreeze().apply {
        tap(rows, cell, width, now)
        on(rows, cell, width, now + 50)
    }

    @Test
    fun aShrinkReportedBeforeTheFreezeIsNotItsCap() {
        val freeze = GridFreeze()
        freeze.tap(21, cell, width, now)
        // The keyboard-padded column reported 11 rows before the notice froze the grid.
        freeze.on(11, cell, width, now + 50)
        assertEquals(11, freeze.rows(11))
        assertEquals(21, freeze.rows(21))
        assertEquals(21, freeze.rows(25))
    }

    @Test
    fun fontAndKeyboardCyclesRegainThePreDeliveryRows() {
        val freeze = frozenAt(29)
        repeat(3) {
            assertEquals(5, freeze.rows(5, cellPx = 232f))
            assertEquals(29, freeze.rows(29))
            assertEquals(13, freeze.rows(13))
            assertEquals(29, freeze.rows(30))
        }
    }

    @Test
    fun aSmallerFontNeverGrowsThePanePastItsPreDeliveryHeight() {
        val freeze = frozenAt(29)
        for (small in listOf(30f, 33.3f, 25f)) {
            val rows = freeze.rows(60, cellPx = small)
            assertTrue("$rows rows at $small px", rows * small <= 29 * cell)
            assertEquals((29 * cell / small).toInt(), rows)
        }
    }

    @Test
    fun releaseFollowsTheView() {
        val freeze = frozenAt(21)
        freeze.off()
        assertEquals(30, freeze.rows(30))
    }

    @Test
    fun noGridYetMeansNoCap() {
        val freeze = GridFreeze()
        freeze.tap(0, cell, width, now)
        freeze.on(0, cell, width, now)
        assertEquals(30, freeze.rows(30))
    }

    @Test
    fun aRestartWithAnUnconfirmedDraftCapsAtTheGridInForce() {
        val freeze = GridFreeze()
        freeze.on(29, cell, width, now)
        assertEquals(29, freeze.rows(31))
    }

    @Test
    fun steerEnterKeepsThePreDeliveryCap() {
        val freeze = frozenAt(21)
        freeze.tap(11, cell, width, now + 500)
        assertEquals(21, freeze.rows(25))
    }

    @Test
    fun aTapWithNoFreezeCapsNothingAndTheNextTapReplacesIt() {
        val freeze = GridFreeze()
        freeze.tap(21, cell, width, now)
        assertEquals(30, freeze.rows(30))
        freeze.tap(15, cell, width, now + 100)
        freeze.on(11, cell, width, now + 150)
        assertEquals(15, freeze.rows(30))
    }

    @Test
    fun anOldOrOtherWidthTapNeverCapsALaterFreeze() {
        val rotated = GridFreeze()
        rotated.tap(21, cell, width, now)
        rotated.on(12, cell, 2340, now + 50)
        assertEquals(12, rotated.rows(20, at = 2340))

        val stale = GridFreeze()
        stale.tap(21, cell, width, now)
        stale.on(13, cell, width, now + GridFreeze.PENDING_MS + 1)
        assertEquals(13, stale.rows(30))
    }

    @Test
    fun aNewWidthUnderTheKeyboardOrAHoldRegainsTheNewOrientationsRows() {
        val freeze = frozenAt(29)
        // Rotated with the keyboard up: the view fits 6 rows there.
        assertEquals(6, freeze.rows(6, at = 2340))
        // Under a hold the rows come from the hold, not the cap taken in portrait.
        assertEquals(9, gridRows(240, cell, hold = true, holdHeightPx = 360, lastRows = 6, capPx = freeze.cap(2340)))
        // The keyboard goes down: the whole landscape view.
        assertEquals(15, freeze.rows(15, at = 2340))
    }

    @Test
    fun withoutAHoldTheRowsNeverExceedWhatTheViewDraws() {
        val freeze = frozenAt(29)
        for (view in 1..40) for (px in listOf(25f, 40f, 61.7f)) {
            val height = (view * px).toInt()
            val rows = gridRows(height, px, hold = false, holdHeightPx = 0, lastRows = 0, capPx = freeze.cap(width))
            assertTrue("$rows rows in $height px at $px", rows <= maxOf(1, (height / px).toInt()))
        }
    }
}
