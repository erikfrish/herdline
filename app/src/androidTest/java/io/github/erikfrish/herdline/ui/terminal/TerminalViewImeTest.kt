package io.github.erikfrish.herdline.ui.terminal

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.erikfrish.herdline.core.RenderFrame
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * The real keyboard over the real Terminal view: a one-finger drag that is
 * mostly vertical and past touch slop hides it, as Chat's scroll does; a tap
 * still opens it; a tap that jitters under slop, a horizontal drag (also one
 * that ends in a small vertical wobble), a pinch (and the finger left after
 * it, until the next touch), and a long-press selection drag leave it up.
 * One 80 x 40 frame with one focused pane over the whole grid gives every
 * touch a pane to scroll. Touches are injected in the view's upper part,
 * which the keyboard never covers.
 */
class TerminalViewImeTest {
    @get:Rule
    val compose = createComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val config = ViewConfiguration.get(instrumentation.targetContext)
    private val slop = config.scaledTouchSlop.toFloat()
    private lateinit var view: TerminalView

    private val frame = RenderFrame(
        cols = 80,
        rows = 40,
        cursor = RenderFrame.Cursor(0, 39, true, RenderFrame.CURSOR_BLOCK),
        foreground = 0xffeeeeee.toInt(),
        background = 0xff111111.toInt(),
        cursorColor = 0xffeeeeee.toInt(),
        popup = null,
        panes = listOf(
            RenderFrame.PaneArea(
                id = "p1",
                rect = RenderFrame.CellRect(0, 0, 80, 40),
                focused = true,
                mouseReporting = false,
                alternateScreen = false,
            ),
        ),
        runs = (0 until 40).map { row ->
            val text = "line-$row-of-the-terminal"
            RenderFrame.Run(row, 0, text.length, RenderFrame.ASCII, 0, 0, text)
        },
    )

    private val quiet = object : TerminalView.Listener {
        override fun onGrid(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) {}
        override fun onFocusPane(pane: RenderFrame.PaneArea) {}
        override fun onTap(pane: RenderFrame.PaneArea?, column: Int, row: Int) {}
        override fun onScroll(pane: RenderFrame.PaneArea?, column: Int, row: Int, up: Boolean, lines: Int) {}
        override fun onKey(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean) = false
        override fun onText(text: String) {}
        override fun onChord(codepoint: Int, metaState: Int) {}
        override fun onCopy(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int) {}
        override fun onPaste() {}
        override fun onFontSize(sp: Float) {}
        override fun onModifiersConsumed() {}
    }

    @Before
    fun showTerminal() {
        compose.setContent {
            AndroidView(
                factory = { context ->
                    TerminalView(context).also {
                        it.listener = quiet
                        it.frame = frame
                        view = it
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
        compose.waitUntil(TIMEOUT) { compose.runOnIdle { view.isAttachedToWindow && view.height > 0 } }
    }

    /** The keyboard and any selection go with this test's view, so the next test starts with neither. */
    @After
    fun hideKeyboard() {
        if (!::view.isInitialized) return
        compose.runOnIdle {
            view.clearSelection()
            view.hideKeyboard()
        }
        compose.waitUntil(TIMEOUT) { !keyboardShown() }
    }

    @Test
    fun aSingleTapOpensTheKeyboard() {
        compose.runOnIdle { view.hideKeyboard() }
        compose.waitUntil(TIMEOUT) { !keyboardShown() }
        tap(at(0.5f, 0.2f))
        compose.waitUntil(TIMEOUT) { keyboardShown() }
    }

    @Test
    fun aVerticalDragHidesTheShownKeyboard() {
        openKeyboard()
        val start = at(0.5f, 0.35f)
        drag(start, dx = slop / 2, dy = -6 * slop)
        compose.waitUntil(TIMEOUT) { !keyboardShown() }
    }

    @Test
    fun aTapWithJitterBelowSlopKeepsTheKeyboard() {
        openKeyboard()
        val (x, y) = at(0.5f, 0.25f)
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, floatArrayOf(x, y))
        send(down, MotionEvent.ACTION_MOVE, floatArrayOf(x + slop / 3, y - slop / 3))
        send(down, MotionEvent.ACTION_MOVE, floatArrayOf(x - slop / 3, y + slop / 4))
        send(down, MotionEvent.ACTION_UP, floatArrayOf(x - slop / 3, y + slop / 4))
        assertKeyboardStays()
    }

    @Test
    fun aHorizontalDragKeepsTheKeyboard() {
        openKeyboard()
        drag(at(0.75f, 0.3f), dx = -6 * slop, dy = slop / 2)
        assertKeyboardStays()
    }

    @Test
    fun aPinchKeepsTheKeyboard() {
        openKeyboard()
        val down = pinch()
        val (x0, y0) = pinchFinger(0, PINCH_STEPS)
        val (x1, y1) = pinchFinger(1, PINCH_STEPS)
        send(down, pointer(MotionEvent.ACTION_POINTER_UP, 1), floatArrayOf(x0, y0), floatArrayOf(x1, y1))
        send(down, MotionEvent.ACTION_UP, floatArrayOf(x0, y0))
        assertKeyboardStays()
    }

    @Test
    fun theFingerLeftAfterAPinchKeepsTheKeyboard() {
        openKeyboard()
        val down = pinch()
        var (x0, y0) = pinchFinger(0, PINCH_STEPS)
        val (x1, y1) = pinchFinger(1, PINCH_STEPS)
        send(down, pointer(MotionEvent.ACTION_POINTER_UP, 1), floatArrayOf(x0, y0), floatArrayOf(x1, y1))
        repeat(STEPS) {
            y0 += 6 * slop / STEPS
            send(down, MotionEvent.ACTION_MOVE, floatArrayOf(x0, y0))
        }
        send(down, MotionEvent.ACTION_UP, floatArrayOf(x0, y0))
        assertKeyboardStays()
    }

    @Test
    fun aLongPressSelectionKeepsTheKeyboard() {
        openKeyboard()
        // On the text of an upper row: a word to select.
        val (x, y) = at(0.02f, 0.2f)
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, floatArrayOf(x, y))
        compose.waitUntil(TIMEOUT) { compose.runOnIdle { view.hasSelection } }
        var moved = y
        repeat(STEPS) {
            moved += 6 * slop / STEPS
            send(down, MotionEvent.ACTION_MOVE, floatArrayOf(x, moved))
        }
        send(down, MotionEvent.ACTION_UP, floatArrayOf(x, moved))
        assertTrue(compose.runOnIdle { view.hasSelection })
        assertKeyboardStays()
    }

    @Test
    fun aHorizontalDragWithALateVerticalWobbleKeepsTheKeyboard() {
        openKeyboard()
        var (x, y) = at(0.75f, 0.3f)
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, floatArrayOf(x, y))
        // Well past slop along x, y still.
        repeat(10) {
            x -= 20f
            send(down, MotionEvent.ACTION_MOVE, floatArrayOf(x, y))
        }
        // Then the finger only wobbles a few pixels down: each step is vertical, the drag is not.
        for (dy in floatArrayOf(2f, 3f, 4f, 3f)) {
            y += dy
            send(down, MotionEvent.ACTION_MOVE, floatArrayOf(x, y))
        }
        send(down, MotionEvent.ACTION_UP, floatArrayOf(x, y))
        assertKeyboardStays()
    }

    // The keyboard

    private fun keyboardShown(): Boolean = compose.runOnIdle {
        ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
    }

    /** Opens it with a real tap, then lets the double-tap window pass so the next touch starts fresh. */
    private fun openKeyboard() {
        tap(at(0.5f, 0.2f))
        compose.waitUntil(TIMEOUT) { keyboardShown() }
        val fresh = SystemClock.uptimeMillis() + ViewConfiguration.getDoubleTapTimeout() + 100
        compose.waitUntil(TIMEOUT) { SystemClock.uptimeMillis() > fresh }
    }

    /** Fails if the keyboard goes down within a bounded window after the gesture. */
    private fun assertKeyboardStays() {
        try {
            compose.waitUntil(SETTLE) { !keyboardShown() }
        } catch (_: ComposeTimeoutException) {
            // The window ran out with the keyboard still up.
            return
        }
        fail("the keyboard was dismissed")
    }

    // Touches, in screen pixels

    private fun onScreen(): IntArray = IntArray(2).also { view.getLocationOnScreen(it) }

    /** A point at fractions of the view's width and height. */
    private fun at(fx: Float, fy: Float): FloatArray = compose.runOnIdle {
        val origin = onScreen()
        floatArrayOf(origin[0] + view.width * fx, origin[1] + view.height * fy)
    }

    private fun tap(point: FloatArray) {
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, point)
        send(down, MotionEvent.ACTION_UP, point)
    }

    private fun drag(start: FloatArray, dx: Float, dy: Float) {
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, start)
        for (step in 1..STEPS) {
            send(down, MotionEvent.ACTION_MOVE, floatArrayOf(start[0] + dx * step / STEPS, start[1] + dy * step / STEPS))
        }
        send(down, MotionEvent.ACTION_UP, floatArrayOf(start[0] + dx, start[1] + dy))
    }

    /** Two fingers spreading apart vertically around the upper third; returns the down time. */
    private fun pinch(): Long {
        val down = SystemClock.uptimeMillis()
        send(down, MotionEvent.ACTION_DOWN, pinchFinger(0, 0))
        send(down, pointer(MotionEvent.ACTION_POINTER_DOWN, 1), pinchFinger(0, 0), pinchFinger(1, 0))
        for (step in 1..PINCH_STEPS) {
            send(down, MotionEvent.ACTION_MOVE, pinchFinger(0, step), pinchFinger(1, step))
        }
        return down
    }

    private fun pinchFinger(finger: Int, step: Int): FloatArray {
        val (cx, cy) = at(0.5f, 0.3f)
        val spread = 3 * slop + 12 * slop * step / PINCH_STEPS
        return floatArrayOf(cx, if (finger == 0) cy - spread else cy + spread)
    }

    private fun pointer(action: Int, index: Int) = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

    /** One touchscreen event, pointer ids by position; injected and waited for. */
    private fun send(downTime: Long, action: Int, vararg points: FloatArray) {
        val properties = Array(points.size) { id ->
            MotionEvent.PointerProperties().apply {
                this.id = id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = points[i][0]
                y = points[i][1]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, points.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            instrumentation.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private companion object {
        const val TIMEOUT = 5_000L
        /** How long a kept keyboard must stay up after the gesture. */
        const val SETTLE = 1_500L
        const val STEPS = 12
        const val PINCH_STEPS = 10
    }
}
