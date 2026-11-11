package io.github.erikfrish.herdline.ui.terminal

import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection

/** What turns the pane's copy of a line into the keyboard's: erase this many characters from its end, then type this. */
data class LineEdit(val erase: Int, val type: String) {
    val isEmpty: Boolean get() = erase == 0 && type.isEmpty()
}

/**
 * The edit from [sent], what the pane was given of the line, to [target],
 * what the keyboard now holds. Everything after the first difference is
 * retyped, so a correction of an earlier word replaces exactly that word and
 * what follows it, wherever the keyboard's cursor is. Erasing counts code
 * points, as a terminal line editor's Backspace does.
 */
fun lineEdit(sent: String, target: String): LineEdit {
    var common = 0
    val limit = minOf(sent.length, target.length)
    while (common < limit && sent[common] == target[common]) common++
    // Never split a surrogate pair: retype the whole character.
    if (common > 0 && Character.isHighSurrogate(sent[common - 1])) common--
    return LineEdit(sent.codePointCount(common, sent.length), target.substring(common))
}

/**
 * The line being typed in the terminal as the keyboard sees it. A soft
 * keyboard corrects, completes, and dictates by rewriting text it believes
 * is in the field; without this copy it would see an empty field and append
 * its replacement instead. [sent] is what the pane has been given of it.
 */
internal class LineMirror {
    val editable: Editable = SpannableStringBuilder()
    var sent = ""
        private set

    init {
        Selection.setSelection(editable, 0)
    }

    val isEmpty: Boolean get() = editable.isEmpty() && sent.isEmpty()

    fun pending(): LineEdit = lineEdit(sent, editable.toString())

    fun settled() {
        sent = editable.toString()
    }

    /** Forgets the line: the pane has moved on (Enter, a cursor key, a tap, another pane). */
    fun reset() {
        editable.clearSpans()
        editable.clear()
        Selection.setSelection(editable, 0)
        sent = ""
    }
}

/**
 * The keyboard's connection to the terminal: a real editor over [mirror],
 * so composing regions, corrections, and deletions apply to the right text,
 * with [onEdit] after each edit or batch of edits to carry the change to the
 * pane, and key events handed to [onKey].
 */
internal class TerminalInputConnection(
    view: View,
    private val mirror: LineMirror,
    private val onEdit: () -> Unit,
    private val onKey: (KeyEvent) -> Unit,
) : BaseInputConnection(view, true) {
    private var depth = 0

    override fun getEditable(): Editable = mirror.editable

    override fun beginBatchEdit(): Boolean {
        depth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (depth > 0) depth--
        if (depth == 0) onEdit()
        return depth > 0
    }

    private fun edited(result: Boolean): Boolean {
        if (depth == 0) onEdit()
        return result
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int) = edited(super.commitText(text, newCursorPosition))

    override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
        edited(super.setComposingText(text, newCursorPosition))

    override fun setComposingRegion(start: Int, end: Int) = edited(super.setComposingRegion(start, end))

    override fun finishComposingText() = edited(super.finishComposingText())

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int) =
        edited(super.deleteSurroundingText(beforeLength, afterLength))

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int) =
        edited(super.deleteSurroundingTextInCodePoints(beforeLength, afterLength))

    override fun setSelection(start: Int, end: Int) = edited(super.setSelection(start, end))

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) onKey(event)
        return true
    }
}
