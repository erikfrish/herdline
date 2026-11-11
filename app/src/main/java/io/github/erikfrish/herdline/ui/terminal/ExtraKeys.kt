/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.terminal

import android.view.KeyEvent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What one extra key does. */
sealed interface ExtraKey {
    val label: String

    data class Key(override val label: String, val keyCode: Int, val repeats: Boolean = false) : ExtraKey
    data class Text(override val label: String, val text: String) : ExtraKey
    data class Modifier(override val label: String, val meta: Int) : ExtraKey
    data class Action(override val label: String, val run: () -> Unit) : ExtraKey
}

/** Every key the row can hold, by a stable ID saved in settings. */
val AllExtraKeys: Map<String, ExtraKey> = linkedMapOf(
    "esc" to ExtraKey.Key("Esc", KeyEvent.KEYCODE_ESCAPE),
    "tab" to ExtraKey.Key("Tab", KeyEvent.KEYCODE_TAB),
    "ctrl" to ExtraKey.Modifier("Ctrl", KeyEvent.META_CTRL_ON),
    "alt" to ExtraKey.Modifier("Alt", KeyEvent.META_ALT_ON),
    "shift" to ExtraKey.Modifier("Shift", KeyEvent.META_SHIFT_ON),
    "left" to ExtraKey.Key("←", KeyEvent.KEYCODE_DPAD_LEFT, repeats = true),
    "down" to ExtraKey.Key("↓", KeyEvent.KEYCODE_DPAD_DOWN, repeats = true),
    "up" to ExtraKey.Key("↑", KeyEvent.KEYCODE_DPAD_UP, repeats = true),
    "right" to ExtraKey.Key("→", KeyEvent.KEYCODE_DPAD_RIGHT, repeats = true),
    "enter" to ExtraKey.Key("⏎", KeyEvent.KEYCODE_ENTER),
    "backspace" to ExtraKey.Key("⌫", KeyEvent.KEYCODE_DEL, repeats = true),
    "del" to ExtraKey.Key("Del", KeyEvent.KEYCODE_FORWARD_DEL, repeats = true),
    "pipe" to ExtraKey.Text("|", "|"),
    "dash" to ExtraKey.Text("-", "-"),
    "slash" to ExtraKey.Text("/", "/"),
    "tilde" to ExtraKey.Text("~", "~"),
    "underscore" to ExtraKey.Text("_", "_"),
    "backtick" to ExtraKey.Text("`", "`"),
    "home" to ExtraKey.Key("Home", KeyEvent.KEYCODE_MOVE_HOME),
    "end" to ExtraKey.Key("End", KeyEvent.KEYCODE_MOVE_END),
    "pgup" to ExtraKey.Key("PgUp", KeyEvent.KEYCODE_PAGE_UP, repeats = true),
    "pgdn" to ExtraKey.Key("PgDn", KeyEvent.KEYCODE_PAGE_DOWN, repeats = true),
)

/**
 * The panel's default: fourteen keys, seven a row. The first row holds the
 * keys taken from the hardware keyboard (Esc, Tab, Ctrl) with the four arrows
 * kept together and first; the second holds Alt, Enter and Backspace beside the
 * shell characters and Home/End. Two Gboard-sized rows, nothing hidden behind
 * a scroll on a phone (HG-EXK).
 */
val DEFAULT_EXTRA_KEY_IDS: List<String> = listOf(
    "esc", "tab", "ctrl", "left", "down", "up", "right",
    "alt", "enter", "backspace", "pipe", "slash", "home", "end",
)


/** The keys for saved [ids], in their order; unknown IDs from older builds are skipped. */
fun extraKeysFrom(ids: List<String>): List<ExtraKey> = ids.mapNotNull(AllExtraKeys::get)


/**
 * The panel's rows: [rowsOf] rows of as even a length as keys allow, filled in
 * saved order. The default takes 7 + 7; a custom set of many keys stays visible
 * in the same two rows and lets the wider one scroll (HG-EXK).
 */
internal fun extraKeyRows(keys: List<ExtraKey>, rowsOf: Int = 2): List<List<ExtraKey>> {
    if (keys.isEmpty()) return emptyList()
    val rows = rowsOf.coerceIn(1, keys.size)
    val short = keys.size % rows
    val per = keys.size / rows
    var at = 0
    return (0 until rows).map { row ->
        keys.subList(at, at + per + if (row < short) 1 else 0).also { at += per + if (row < short) 1 else 0 }
    }
}

private val KeyShape = RoundedCornerShape(12.dp) // = shapes.medium

/** Every row a hand can thumb without peeking, whatever the cap shrinks to. */
private val KeyRowHeight = 48.dp
/** The gaps and outer padding of the panel, in dp. */
private val KeyGap = 5.dp
private val KeyRowPad = 6.dp

/** How TalkBack reads the caps whose glyphs name nothing. */
private val Spoken: Map<String, String> = mapOf(
    "←" to "Arrow left", "↓" to "Arrow down", "↑" to "Arrow up", "→" to "Arrow right",
    "⏎" to "Enter", "⌫" to "Backspace",
)

/**
 * The panel of keys a phone keyboard lacks, drawn as two compact rows —
 * Gboard-sized caps on 48dp rows — above the keyboard in raw input. Ctrl and
 * Alt latch for the next key or character, as on Termux; arrows and paging
 * keys repeat while held and stop at the moment the cap is released. A latched
 * modifier fills with the session's [accent].
 */
@Composable
fun ExtraKeysRow(
    keys: List<ExtraKey>,
    latched: Int,
    accent: Color,
    background: Color,
    onLatch: (Int) -> Unit,
    onKey: (keyCode: Int, repeat: Boolean) -> Unit,
    onText: (String) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    val onSurface = MaterialTheme.colorScheme.onSurface
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .background(background)
            .drawBehind { drawRect(onSurface.copy(alpha = 0.08f), size = size.copy(height = 1.dp.toPx())) }
            .then(modifier)
            .testTag("extra_keys"),
    ) {
        val panelWidth = maxWidth
        val rows = remember(keys) { extraKeyRows(keys) }
        Column(Modifier.fillMaxWidth().padding(horizontal = KeyRowPad, vertical = 4.dp)) {
            for (row in rows) {
                // Caps fill the row when it holds few keys; a row too wide for
                // the screen (a long custom set) scrolls sideways, never taller.
                val width = ((panelWidth - KeyRowPad * 2 - KeyGap * (row.size - 1)).value / row.size).dp
                    .coerceAtLeast(if (compact) 40.dp else 44.dp)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(KeyGap),
                ) {
                    for (key in row) {
                        val active = key is ExtraKey.Modifier && latched and key.meta != 0
                        var down by remember { mutableStateOf(false) }
                        val scale by animateFloatAsState(if (down) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = 900f), label = "key")
                        val fill by animateColorAsState(
                            when {
                                active -> accent
                                down -> onSurface.copy(alpha = 0.16f)
                                else -> onSurface.copy(alpha = 0.07f)
                            },
                            label = "keyFill",
                        )
                        val hit = Modifier
                            .width(width)
                            .height(KeyRowHeight)
                            .testTag("key:${key.label}")
                            .semantics {
                                contentDescription = Spoken[key.label] ?: key.label
                                role = Role.Button
                                onClick {
                                    when (key) {
                                        is ExtraKey.Key -> onKey(key.keyCode, false)
                                        is ExtraKey.Text -> onText(key.text)
                                        is ExtraKey.Modifier -> onLatch(latched xor key.meta)
                                        is ExtraKey.Action -> key.run()
                                    }
                                    true
                                }
                            }
                        val gesture = when (key) {
                            is ExtraKey.Key -> if (key.repeats) {
                                hit.pointerInput(key) {
                                    awaitEachGesture {
                                        awaitFirstDown()
                                        down = true
                                        try {
                                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                            onKey(key.keyCode, false)
                                            val repeat = scope.launch {
                                                delay(400)
                                                while (true) {
                                                    onKey(key.keyCode, true)
                                                    delay(60)
                                                }
                                            }
                                            try {
                                                waitForUpOrCancellation()
                                            } finally {
                                                // Up, a scroll stealing the gesture, or this row
                                                // leaving composition: the held job never outlives the hold.
                                                repeat.cancel()
                                            }
                                        } finally {
                                            down = false
                                        }
                                    }
                                }
                            } else {
                                hit.press(key, { down = it }) { onKey(key.keyCode, false) }
                            }
                            is ExtraKey.Text -> hit.press(key, { down = it }) { onText(key.text) }
                            is ExtraKey.Modifier -> hit.press(key to latched, { down = it }) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onLatch(latched xor key.meta)
                            }
                            is ExtraKey.Action -> hit.press(key, { down = it }) { key.run() }
                        }
                        Box(gesture, contentAlignment = Alignment.Center) {
                            Box(
                                Modifier
                                    .height(if (compact) 36.dp else 40.dp)
                                    .width(width)
                                    .graphicsLayer { scaleX = scale; scaleY = scale }
                                    .clip(KeyShape)
                                    .background(fill)
                                    .border(0.5.dp, onSurface.copy(alpha = if (active) 0f else 0.08f), KeyShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    key.label,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 14.sp,
                                    color = if (active) (if (accent.luminance() > 0.5f) Color(0xFF10151C) else Color.White) else onSurface,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A tap that reports press state, for the cap's shrink, without a ripple. Redone on a key change. */
private fun Modifier.press(key: Any, onPressed: (Boolean) -> Unit, onTap: () -> Unit): Modifier = pointerInput(key) {
    detectTapGestures(
        onPress = {
            onPressed(true)
            val released = tryAwaitRelease()
            onPressed(false)
            // A cap that lost its press to a scroll sends nothing.
            if (released) onTap()
        },
    )
}
