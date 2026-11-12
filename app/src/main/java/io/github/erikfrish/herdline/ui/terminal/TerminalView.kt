/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Bundle
import android.os.SystemClock
import android.text.InputType
import android.text.Selection
import android.util.TypedValue
import android.view.ActionMode
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.RenderFrame
import io.github.erikfrish.herdline.core.Viewport
import io.github.erikfrish.herdline.ui.theme.TerminalScheme
import io.github.erikfrish.herdline.ui.theme.TerminalSchemes
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws the frames libghostty produced and turns touch and keyboard input into
 * Herdr input. Rendering reads only the latest prepared [frame]; nothing here
 * talks to the daemon except through [listener].
 */
class TerminalView(context: Context) : View(context) {
    interface Listener {
        /** The grid that fits the view, in cells and cell pixels. */
        fun onGrid(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int)
        fun onFocusPane(pane: RenderFrame.PaneArea)
        /** A tap at a cell, relative to the pane or the open popup. */
        fun onTap(pane: RenderFrame.PaneArea?, column: Int, row: Int)
        fun onScroll(pane: RenderFrame.PaneArea?, column: Int, row: Int, up: Boolean, lines: Int)
        fun onKey(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean): Boolean
        fun onText(text: String)
        fun onChord(codepoint: Int, metaState: Int)
        fun onCopy(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int)
        fun onPaste()
        fun onFontSize(sp: Float)
        /** Ctrl or Alt latched by the extra-keys row was used up. */
        fun onModifiersConsumed()
        /**
         * A drag of [towardBottom] lines (negative: toward scrollback) before
         * Herdr sees it; returns how many lines the output-focus window used.
         */
        fun onWindowDrag(towardBottom: Int): Int = 0
        /**
         * What the view drew changed ([viewport]); posted after the draw,
         * never from inside it. Null until a frame with a whole row is drawn.
         */
        fun onViewport(viewport: Viewport?) {}
    }

    var listener: Listener? = null

    /** The active scheme: the fill before the first frame and the selection wash. */
    var scheme: TerminalScheme = TerminalSchemes.byId("herdline-dark", dark = true)
        set(value) {
            if (value == field) return
            field = value
            invalidate()
        }

    var frame: RenderFrame? = null
        set(value) {
            // Another pane now takes the keys: the line the keyboard holds was the old one's.
            val pane = value?.panes?.firstOrNull { it.focused }?.id
            if (pane != focusedPane) {
                focusedPane = pane
                resetLine()
            }
            field = value
            invalidate()
        }
    private var focusedPane: String? = null

    /**
     * Whether the keyboard may offer suggestions and corrections here. Off,
     * the terminal asks for raw keys; on, picked corrections still replace
     * exactly the word they belong to (see [LineMirror]).
     */
    var keyboardSuggestions = false
        set(value) {
            if (field == value) return
            field = value
            context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
        }

    /** Modifiers latched by the extra-keys row, as KeyEvent meta-state bits. */
    var latchedMeta = 0

    /**
     * While set, a shorter view does not shrink the remote grid's rows: the
     * daemon keeps the pane at its full height and this view shows the
     * grid's bottom rows. Set while the composer has the keyboard, so the
     * agent's input box stays whole on the host and the composer's checks
     * can pass. The rows are those of [holdHeightPx] when it is set, else
     * the ones the grid had when the hold began. Columns still follow the
     * width (the side composer, J2), so no column the view cannot show is
     * ever kept.
     */
    var holdGrid = false
        set(value) {
            if (field == value) return
            field = value
            reportGrid(fit = true)
            invalidate()
        }

    /**
     * The height, in px, whose rows a [holdGrid] keeps; 0 keeps the rows
     * the grid had. With the side composer this is the column's height with
     * the keyboard down, which the composer below the terminal no longer
     * takes (S-4a).
     */
    var holdHeightPx = 0
        set(value) {
            if (field == value) return
            field = value
            if (holdGrid) reportGrid(fit = true)
        }

    /**
     * While set, a shorter view does not shrink the remote grid's rows, but
     * a taller one still grows them; the view shows the grid's bottom rows.
     * Set while the composer holds a draft (V4-a): its notice slot follows
     * the native checks, so a grid that followed the composer's height would
     * resize the pane each time the notice came or went, and each resize
     * refuses Send until Herdr draws it, which brings the notice back.
     * A new width or font size still resizes the grid to what the view fits.
     * The visibility checks still use the rows actually drawn.
     */
    var keepRows = false
        set(value) {
            if (field == value) return
            field = value
            reportGrid(fit = true)
        }

    /**
     * While set, the remote grid never grows past the height it had before
     * the delivery: set while a delivery is unconfirmed, so the keyboard
     * opening never resizes the pane the agent is taking a paste in. See
     * [GridFreeze]: the cap is a height in pixels taken by [freezeAt] at the
     * tap, so a font change or a transient shrink under it grows back to
     * that height and never past it. Rows still shrink to what the view
     * fits, unless [keepRows] keeps them. Columns follow the width either way.
     */
    var freezeRows = false
        set(value) {
            if (field == value) return
            field = value
            if (value) freeze.on(lastGrid[1], cellHeight, width, SystemClock.uptimeMillis()) else freeze.off()
            reportGrid(fit = true)
        }
    private val freeze = GridFreeze()

    /**
     * What the window gives the session: its size, the keyboard's height as
     * it animates, and the orientation. Set in the Compose update pass, so a
     * change reaches this view before the layout it causes. While it stays
     * the same, a new view height comes only from transient chrome (a
     * composer or receipt notice, the sent confirmation, the
     * short-window header), and the grid keeps its rows: each resize refuses
     * Send until Herdr draws it, and the chrome then comes or goes again.
     */
    var windowKey: Any? = null
        set(value) {
            if (field == value) return
            field = value
            windowMoved = true
        }

    /**
     * Set by a new [windowKey] until the size change it causes is reported,
     * so that layout is fitted and the chrome moving afterwards is not.
     */
    private var windowMoved = true

    /**
     * Whether this view's grid is Herdr's: set while Terminal is on screen.
     * Under Chat the view still measures and draws, but only one grid goes
     * to Herdr per visit, once [chatSettled] says Chat's layout is done
     * ([GridTurn]): so a pane bar, a local read, the keyboard or the
     * composer there never resizes the shared panes, and neither Terminal's
     * grid nor a first layout still resolving sticks under Chat. Set again,
     * it fits the layout Terminal brings and reports that grid once, even
     * an unchanged one.
     */
    var sharesGrid = true
        set(value) {
            if (field == value) return
            field = value
            turn.show(value)
            if (!value) {
                reportChat()
                return
            }
            windowMoved = true
            owed = true
            post {
                if (owed) reportGrid(fit = true)
                windowMoved = false
            }
        }

    /**
     * Under Chat: its mode and pane resolved and the keyboard down, so the
     * layout laid out now is Chat's own. Set it after [sharesGrid].
     */
    var chatSettled = false
        set(value) {
            if (field == value) return
            field = value
            turn.settled = value
            reportChat()
        }

    /**
     * The height of the session's column under its header, set as it is
     * measured, before this view is laid out in it. Chat's one grid takes
     * its rows: under Chat this view is only the space the composer leaves,
     * which is not the pane's.
     */
    var chatRoomPx = 0
        set(value) {
            if (field == value) return
            field = value
            turn.roomPx = value
            reportChat()
        }

    private val turn = GridTurn()

    /** Chat's one grid, once the layout under way is done; a report before then takes it instead. */
    private fun reportChat() {
        if (turn.chatDue()) post { if (turn.chatDue()) reportGrid(fit = true) }
    }

    /** A Terminal return whose grid is still to be reported, changed or not. */
    private var owed = false

    /**
     * A delivery is about to start: take the grid in force now, on the main
     * thread before anything the delivery changes is laid out, as the cap
     * [freezeRows] will hold. Kept while already frozen.
     */
    fun freezeAt() = freeze.tap(lastGrid[1], cellHeight, width, SystemClock.uptimeMillis())


    /**
     * Output focus: [hiddenRows] rows at the grid's bottom are
     * scrolled past, never removed, and a drag toward the bottom shows them
     * again through [Listener.onWindowDrag]; [clipBottomPx] at the view's
     * bottom is left to the strip drawn over it. The grid itself stays the
     * one [Listener.onGrid] reported for the whole view, so Herdr is not told.
     */
    fun showWindow(hiddenRows: Int, clipBottomPx: Int) {
        if (hiddenRows == this.hiddenRows && clipBottomPx == this.clipBottomPx) return
        this.hiddenRows = hiddenRows
        this.clipBottomPx = clipBottomPx
        invalidate()
    }

    var hiddenRows = 0
        private set
    private var clipBottomPx = 0

    /** Whether a selection is up: output focus then holds its window still. */
    val hasSelection: Boolean get() = selection != null

    /**
     * How far the grid is drawn above the view's top: while a held grid is
     * taller than the view, or by whole rows while output focus shows a
     * window over it, so the top visible row is never cut. The row
     * [shownAnchorRow] picks (the cursor drawCursor draws, or the focused
     * pane's top) is passed so the window keeps it (A-27P, QF9-U2); the native
     * core takes the same row from the same frame.
     */
    private val offsetY: Float
        get() {
            val frame = frame ?: return 0f
            val anchorRow = shownAnchorRow(frame, height, clipBottomPx, hiddenRows, cellHeight)
            return viewOffset(height, clipBottomPx, hiddenRows, cellHeight, frame.rows, anchorRow)
        }

    /** What [onDraw] last drew, as the native core checks it (HG-VIS). */
    private var drawn: Viewport? = null

    /**
     * The frame rows this view last drew wholly, with the metrics they were
     * drawn with, for the composer's checks and deliveries. Null before a
     * frame with a whole row is drawn; the native core refuses that.
     */
    fun viewport(): Viewport? = drawn

    private fun record(frame: RenderFrame?) {
        val seen = frame?.let {
            val offset = offsetY
            val area = height - clipBottomPx
            drawnRows(area.toFloat(), offset, cellHeight, it.rows)?.let { rows ->
                Viewport(
                    frameRows = it.rows,
                    frameCols = it.cols,
                    gridRows = lastGrid[1],
                    gridCols = lastGrid[0],
                    first = rows.first,
                    last = rows.last,
                    rect = Viewport.Rect(0, 0, width, area),
                    offsetYPx = offset,
                    hiddenRows = hiddenRows,
                    clipBottomPx = clipBottomPx,
                    cellWidthPx = cellWidth,
                    cellHeightPx = cellHeight,
                )
            }
        }
        if (seen == drawn) return
        drawn = seen
        post { listener?.onViewport(seen) }
    }

    var fontSizeSp = 13f
        set(value) {
            val clamped = value.coerceIn(MIN_FONT_SP, MAX_FONT_SP)
            if (clamped == field && cellWidth > 0f) return
            field = clamped
            measureCells()
            reportGrid(fit = true)
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        typeface = Typeface.MONOSPACE
    }
    private val fill = Paint()
    private var regular: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private var bold: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private var italic: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC)
    private var boldItalic: Typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC)
    private var symbols: Typeface? = null
    private var typefaces: TerminalTypefaces? = null

    /** The font to draw with; the grid is re-measured to its cell size. */
    fun setTypefaces(faces: TerminalTypefaces) {
        if (faces == typefaces) return
        typefaces = faces
        turn.fonts = true
        regular = faces.regular
        bold = faces.bold
        italic = faces.italic
        boldItalic = faces.boldItalic
        symbols = faces.symbols
        measureCells()
        reportGrid(fit = true)
        invalidate()
    }

    private var cellWidth = 0f
    private var cellHeight = 0f
    private var baseline = 0f
    private var lastGrid = IntArray(4)

    private var selection: Pair<IntArray, IntArray>? = null
    private var actionMode: ActionMode? = null

    private val scroller = OverScroller(context)
    private var scrollRemainder = 0f
    private var flingLast = 0

    /**
     * Where the current scroll gesture and its fling go: the pane (null for
     * the popup) and the cell within it under the finger at touch-down. The
     * finger may cross into another pane or the footer; the steps still go
     * here, and end when this pane or popup leaves the frame.
     */
    private class ScrollPin(val pane: String?, val column: Int, val row: Int)
    private var scrollPin: ScrollPin? = null

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        measureCells()
    }

    private fun measureCells() {
        paint.textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, fontSizeSp, resources.displayMetrics)
        paint.typeface = regular
        cellWidth = paint.measureText("M")
        val metrics = paint.fontMetrics
        cellHeight = (metrics.descent - metrics.ascent).let { it + it * 0.1f }
        baseline = -metrics.ascent + (cellHeight - (metrics.descent - metrics.ascent)) / 2
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reportGrid()
        windowMoved = false
    }

    /** Reports the grid; [fit] fits the view even if only chrome moved it, for a change to the grid's inputs. */
    private fun reportGrid(fit: Boolean = false) {
        if (width == 0 || height == 0 || cellWidth <= 0f) return
        if (!turn.reports()) return
        val chat = !turn.terminal
        val cols = max(1, floor(width / cellWidth).toInt())
        // Chat's one grid is its layout's own: no rows kept from the grid Terminal left.
        val keep = !chat && keepsRows(keepRows, cols, cellWidth.toInt(), cellHeight.toInt(), lastGrid)
        // A new width or cell size is never chrome: keepsRows checks both.
        val chrome = !chat && !fit && !windowMoved && keepsRows(true, cols, cellWidth.toInt(), cellHeight.toInt(), lastGrid)
        val rows = gridRows(turn.heightPx(height), cellHeight, holdGrid, holdHeightPx, lastGrid[1], freeze.cap(width), keep, chrome)
        val grid = intArrayOf(cols, rows, cellWidth.toInt(), cellHeight.toInt())
        if (!owed && !chat && grid.contentEquals(lastGrid)) return
        owed = false
        turn.reported()
        lastGrid = grid
        listener?.onGrid(grid[0], grid[1], grid[2], grid[3])
    }

    // Drawing

    override fun onDraw(canvas: Canvas) {
        val frame = frame
        if (frame == null) {
            canvas.drawColor((0xff000000 or scheme.background).toInt())
            record(null)
            return
        }
        canvas.drawColor(frame.background)
        canvas.save()
        if (clipBottomPx > 0) canvas.clipRect(0f, 0f, width.toFloat(), (height - clipBottomPx).toFloat())
        canvas.translate(0f, offsetY)
        for (run in frame.runs) {
            if (run.has(RenderFrame.BACKGROUND)) {
                fill.color = run.bg
                canvas.drawRect(x(run.col), y(run.row), x(run.col + run.cells), y(run.row + 1), fill)
            }
        }
        drawSelection(canvas)
        for (run in frame.runs) drawRun(canvas, run, run.fg)
        drawCursor(canvas, frame)
        canvas.restore()
        record(frame)
    }

    private fun x(col: Int) = col * cellWidth
    private fun y(row: Int) = row * cellHeight

    private fun drawRun(canvas: Canvas, run: RenderFrame.Run, color: Int) {
        if (run.has(RenderFrame.INVISIBLE)) return
        paint.color = if (run.has(RenderFrame.FAINT)) (color and 0x00ffffff) or 0x99000000.toInt() else color
        paint.typeface = when {
            run.has(RenderFrame.BOLD) && run.has(RenderFrame.ITALIC) -> boldItalic
            run.has(RenderFrame.BOLD) -> bold
            run.has(RenderFrame.ITALIC) -> italic
            else -> regular
        }
        val left = x(run.col)
        val top = y(run.row)
        if (run.text.isNotBlank()) {
            if (run.has(RenderFrame.ASCII)) {
                paint.textScaleX = 1f
                canvas.drawText(run.text, left, top + baseline, paint)
            } else {
                // Nerd Font icons live in the Private Use Area, which no system
                // font covers: draw them from the symbols font when the chosen
                // face lacks them.
                if (!paint.hasGlyph(run.text)) symbols?.takeIf { it != paint.typeface }?.let { face ->
                    val previous = paint.typeface
                    paint.typeface = face
                    if (!paint.hasGlyph(run.text)) paint.typeface = previous
                }
                // A cluster gets exactly its cells: centred, and narrowed if the
                // fallback font draws it wider than the grid allows.
                val room = run.cells * cellWidth
                val natural = paint.measureText(run.text)
                paint.textScaleX = if (natural > room && natural > 0f) room / natural else 1f
                val drawn = min(natural, room)
                canvas.drawText(run.text, left + (room - drawn) / 2, top + baseline, paint)
                paint.textScaleX = 1f
            }
        }
        val right = left + run.cells * cellWidth
        if (run.has(RenderFrame.UNDERLINE)) {
            val line = top + cellHeight - max(1f, cellHeight / 14f)
            canvas.drawRect(left, line, right, line + max(1f, cellHeight / 16f), paint)
        }
        if (run.has(RenderFrame.STRIKETHROUGH)) {
            val line = top + cellHeight / 2
            canvas.drawRect(left, line, right, line + max(1f, cellHeight / 16f), paint)
        }
    }

    private fun drawCursor(canvas: Canvas, frame: RenderFrame) {
        val cursor = frame.cursor
        if (!cursor.visible || cursor.x >= frame.cols || cursor.y >= frame.rows) return
        val left = x(cursor.x)
        val top = y(cursor.y)
        fill.color = frame.cursorColor
        val stroke = max(2f, cellWidth / 6f)
        when (cursor.shape) {
            RenderFrame.CURSOR_BAR -> canvas.drawRect(left, top, left + stroke, top + cellHeight, fill)
            RenderFrame.CURSOR_UNDERLINE -> canvas.drawRect(left, top + cellHeight - stroke, left + cellWidth, top + cellHeight, fill)
            RenderFrame.CURSOR_HOLLOW -> {
                fill.style = Paint.Style.STROKE
                fill.strokeWidth = stroke / 2
                canvas.drawRect(left, top, left + cellWidth, top + cellHeight, fill)
                fill.style = Paint.Style.FILL
            }
            else -> {
                val under = frame.runs.firstOrNull { it.row == cursor.y && cursor.x >= it.col && cursor.x < it.col + it.cells }
                val wide = under?.has(RenderFrame.WIDE) == true
                canvas.drawRect(left, top, left + cellWidth * (if (wide) 2 else 1), top + cellHeight, fill)
                if (under != null) {
                    val text = if (under.has(RenderFrame.ASCII)) under.text[cursor.x - under.col].toString() else under.text
                    drawRun(
                        canvas,
                        RenderFrame.Run(cursor.y, if (wide) under.col else cursor.x, if (wide) 2 else 1, under.flags and RenderFrame.BACKGROUND.inv(), 0, 0, text),
                        frame.background,
                    )
                }
            }
        }
    }

    private fun drawSelection(canvas: Canvas) {
        val (start, end) = ordered() ?: return
        fill.color = scheme.selection.toInt()
        for (row in start[1]..end[1]) {
            val from = if (row == start[1]) start[0] else 0
            val to = if (row == end[1]) end[0] else (frame?.cols ?: 0) - 1
            canvas.drawRect(x(from), y(row), x(to + 1), y(row + 1), fill)
        }
    }

    private fun ordered(): Pair<IntArray, IntArray>? {
        val (a, b) = selection ?: return null
        return if (a[1] < b[1] || (a[1] == b[1] && a[0] <= b[0])) a to b else b to a
    }

    // Touch

    private fun cellAt(ex: Float, ey: Float): IntArray? {
        val frame = frame ?: return null
        val column = floor(ex / cellWidth).toInt()
        if (ey >= height - clipBottomPx) return null
        val row = floor((ey - offsetY) / cellHeight).toInt()
        if (column < 0 || row < 0 || column >= frame.cols || row >= frame.rows) return null
        return intArrayOf(column, row)
    }

    /** The pane under a cell and the cell relative to it, or to the popup. */
    private fun target(column: Int, row: Int): Triple<RenderFrame.PaneArea?, Int, Int>? {
        val frame = frame ?: return null
        frame.popup?.let { popup ->
            if (!popup.contains(column, row)) return null
            return Triple(null, column - popup.x, row - popup.y)
        }
        val pane = frame.paneAt(column, row) ?: return null
        return Triple(pane, column - pane.rect.x, row - pane.rect.y)
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            scrollRemainder = 0f
            scrollPin = cellAt(e.x, e.y)?.let { target(it[0], it[1]) }?.let { (pane, column, row) ->
                ScrollPin(pane?.id, column, row)
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            if (selection != null) {
                clearSelection()
                return true
            }
            val cell = cellAt(e.x, e.y)
            val target = cell?.let { target(it[0], it[1]) }
            val pane = target?.first
            when {
                target == null -> {}
                pane != null && !pane.focused -> listener?.onFocusPane(pane)
                pane == null || pane.mouseReporting -> {
                    // A click may move the pane's cursor away from the line the keyboard holds.
                    resetLine()
                    listener?.onTap(pane, target.second, target.third)
                }
            }
            showKeyboard()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (selection != null || e2.pointerCount > 1) return false
            scrollBy(distanceY)
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (selection != null || abs(velocityY) < abs(velocityX) || scrollPin == null) return false
            flingLast = 0
            scroller.fling(0, 0, 0, -velocityY.toInt(), 0, 0, Int.MIN_VALUE, Int.MAX_VALUE)
            postOnAnimation(::stepFling)
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            val cell = cellAt(e.x, e.y) ?: return
            performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            selection = wordAt(cell) ?: (cell to cell.copyOf())
            selecting = true
            invalidate()
        }
    })

    private var selecting = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            fontSizeSp *= detector.scaleFactor
            listener?.onFontSize(fontSizeSp)
            return true
        }
    })

    /** The pinned pane as the frame shows it now; null once it left, or a popup came or went. */
    private fun pinned(pin: ScrollPin): Triple<RenderFrame.PaneArea?, Int, Int>? {
        val frame = frame ?: return null
        if (pin.pane == null) {
            val popup = frame.popup ?: return null
            return Triple(null, pin.column.coerceAtMost(popup.width - 1), pin.row.coerceAtMost(popup.height - 1))
        }
        if (frame.popup != null) return null
        val pane = frame.panes.firstOrNull { it.id == pin.pane } ?: return null
        return Triple(pane, pin.column.coerceAtMost(pane.rect.width - 1), pin.row.coerceAtMost(pane.rect.height - 1))
    }

    private fun scrollBy(distanceY: Float) {
        val pin = scrollPin ?: return
        val target = pinned(pin) ?: run {
            // The pane was replaced or closed under the gesture: it ends here.
            scrollPin = null
            scroller.forceFinished(true)
            return
        }
        scrollRemainder += distanceY
        val lines = (scrollRemainder / cellHeight).toInt()
        if (lines == 0) return
        scrollRemainder -= lines * cellHeight
        val rest = lines - (listener?.onWindowDrag(lines) ?: 0)
        if (rest == 0) return
        // Dragging the content up reveals what is below it.
        listener?.onScroll(target.first, target.second, target.third, up = rest < 0, lines = abs(rest))
    }

    private fun stepFling() {
        if (!scroller.computeScrollOffset()) return
        val y = scroller.currY
        scrollBy((y - flingLast).toFloat())
        flingLast = y
        if (scrollPin != null) postOnAnimation(::stepFling)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaler.onTouchEvent(event)
        if (selecting) {
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> cellAt(event.x, event.y)?.let { cell ->
                    selection = selection?.first?.let { it to cell }
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    selecting = false
                    startSelectionMode()
                }
            }
            return true
        }
        if (scaler.isInProgress) return true
        return gestures.onTouchEvent(event) || super.onTouchEvent(event)
    }

    /** The run of non-blank characters around a cell, as a double-tap would pick. */
    private fun wordAt(cell: IntArray): Pair<IntArray, IntArray>? {
        val frame = frame ?: return null
        val chars = CharArray(frame.cols) { ' ' }
        frame.runs.filter { it.row == cell[1] && it.has(RenderFrame.ASCII) }.forEach { run ->
            run.text.forEachIndexed { i, c -> if (run.col + i < chars.size) chars[run.col + i] = c }
        }
        if (chars[cell[0]].isWhitespace()) return null
        var start = cell[0]
        var end = cell[0]
        while (start > 0 && !chars[start - 1].isWhitespace()) start--
        while (end < chars.size - 1 && !chars[end + 1].isWhitespace()) end++
        return intArrayOf(start, cell[1]) to intArrayOf(end, cell[1])
    }

    private fun startSelectionMode() {
        actionMode?.finish()
        actionMode = startActionMode(object : ActionMode.Callback2() {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
                menu.add(0, android.R.id.copy, 0, android.R.string.copy)
                menu.add(0, android.R.id.paste, 1, android.R.string.paste)
                return true
            }

            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false

            override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
                when (item.itemId) {
                    android.R.id.copy -> ordered()?.let { (a, b) -> listener?.onCopy(a[0], a[1], b[0], b[1]) }
                    android.R.id.paste -> listener?.onPaste()
                }
                mode.finish()
                return true
            }

            override fun onDestroyActionMode(mode: ActionMode) {
                actionMode = null
                selection = null
                invalidate()
            }

            override fun onGetContentRect(mode: ActionMode, view: View, outRect: android.graphics.Rect) {
                val (a, b) = ordered() ?: return
                outRect.set(x(a[0]).toInt(), y(a[1]).toInt(), x(b[0] + 1).toInt(), y(b[1] + 1).toInt())
            }
        }, ActionMode.TYPE_FLOATING)
    }

    fun clearSelection() {
        actionMode?.finish()
        selection = null
        invalidate()
    }

    // Keyboard

    fun showKeyboard() {
        requestFocus()
        context.getSystemService(InputMethodManager::class.java)?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        context.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onCheckIsTextEditor() = true

    // TalkBack's double-tap is a click: name it and open the keyboard, as a tap does. No click
    // listener, so touch handling and focus stay the gesture detector's.
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, context.getString(R.string.terminal_show_keyboard)))
        info.isClickable = true
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action != AccessibilityNodeInfo.ACTION_CLICK) return super.performAccessibilityAction(action, arguments)
        showKeyboard()
        return true
    }

    private val line = LineMirror()

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // A fresh connection starts on an empty line; what came before is the pane's.
        line.reset()
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or
            if (keyboardSuggestions) 0 else InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return TerminalInputConnection(this, line, ::syncLine) { event -> handleKey(event.keyCode, event) }
    }

    /**
     * Carries the keyboard's latest edit of the line to the pane: Backspace
     * over what changed, then the new text. Composing text goes too, so the
     * pane always shows exactly what the keyboard holds.
     */
    private fun syncLine() {
        val edit = line.pending()
        if (!edit.isEmpty) {
            val meta = latchedMeta
            if (meta and (KEY_CTRL or KEY_ALT) != 0 && edit.erase == 0 && edit.type.codePointCount(0, edit.type.length) == 1) {
                // A latched Ctrl or Alt turns the next character into a chord, which is not part of the line.
                listener?.onChord(edit.type.codePointAt(0), meta)
                consumeLatch()
                resetLine()
                return
            }
            repeat(edit.erase) { listener?.onKey(KeyEvent.KEYCODE_DEL, 0, 0, false) }
            if (edit.type.isNotEmpty()) listener?.onText(edit.type)
            line.settled()
            if (line.editable.length > MAX_LINE) {
                resetLine()
                return
            }
        }
        reportSelection()
    }

    /** Tells the keyboard where its cursor and composing region are, as an editor would after any change. */
    private fun reportSelection() {
        val text = line.editable
        context.getSystemService(InputMethodManager::class.java)?.updateSelection(
            this,
            Selection.getSelectionStart(text),
            Selection.getSelectionEnd(text),
            BaseInputConnection.getComposingSpanStart(text),
            BaseInputConnection.getComposingSpanEnd(text),
        )
    }

    /** Starts the keyboard on a new line after the pane's cursor moved away from the one it held. */
    private fun resetLine() {
        if (line.isEmpty) return
        line.reset()
        context.getSystemService(InputMethodManager::class.java)?.restartInput(this)
    }

    /** A special key from the extra-keys row, with any latched modifiers. */
    fun sendKey(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean) {
        sendKeyNow(keyCode, metaState, unicode, repeat)
        resetLine()
    }

    private fun sendKeyNow(keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean) {
        listener?.onKey(keyCode, metaState or latchedMeta, unicode, repeat)
        consumeLatch()
    }

    private fun consumeLatch() {
        if (latchedMeta != 0) {
            latchedMeta = 0
            listener?.onModifiersConsumed()
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        ) {
            return super.onKeyDown(keyCode, event)
        }
        handleKey(keyCode, event)
        return true
    }

    /** A key event, from a hardware keyboard or a soft key the keyboard sends as one (Enter, Backspace on an empty line). */
    private fun handleKey(keyCode: Int, event: KeyEvent) {
        val meta = event.metaState or latchedMeta
        val unicode = event.getUnicodeChar(meta and (KEY_CTRL or KEY_ALT).inv())
        val sent = listener?.onKey(keyCode, meta, unicode, event.repeatCount > 0) == true
        if (!sent && unicode != 0 && unicode and KeyCharacterMapCombining == 0) {
            listener?.onText(String(Character.toChars(unicode)))
        }
        consumeLatch()
        resetLine()
    }

    companion object {
        const val MIN_FONT_SP = 7f
        const val MAX_FONT_SP = 32f
        private const val KEY_CTRL = KeyEvent.META_CTRL_ON
        private const val KEY_ALT = KeyEvent.META_ALT_ON

        /** Past this the line is left to the pane, so a long dictation is not retyped on every correction. */
        private const val MAX_LINE = 2000
        private const val KeyCharacterMapCombining = android.view.KeyCharacterMap.COMBINING_ACCENT
    }
}
