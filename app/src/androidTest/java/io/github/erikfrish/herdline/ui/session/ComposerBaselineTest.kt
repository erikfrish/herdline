package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

/**
 * UR2 (design 528f5b8 §4, 4bced42 §5): the field, its To-line row and its
 * text row keep their bounds empty, after the first character and after
 * deleting it, so the placeholder and the typed text share one baseline.
 * In portrait (Regular, 360 dp wide) the text row's centre is on Send's,
 * within 2 dp; the side column (Side, 305 dp) centres the pair instead. At
 * font scales 1.0, 1.3 and 1.45.
 */
class ComposerBaselineTest {
    @get:Rule
    val compose = createComposeRule()

    private val view = ShellView(
        revision = 1, boot = "b", focusedWorkspace = "w", focusedTab = "t", focusedPane = "p", updateAvailable = null,
        workspaces = listOf(Workspace("w", 1, "Herdline Dev", null, null, null, null, false, "t", true, AgentStatus.Idle)),
        tabs = listOf(Tab("t", "w", 1, "Discussion", zoomed = false, focused = true, status = AgentStatus.Idle)),
        panes = listOf(Pane("p", "w", "t", null, null, focused = true)),
        agents = emptyList(),
    )

    private var fit by mutableStateOf(ComposerFit.Regular)
    private var width by mutableStateOf(360)
    private var scale by mutableFloatStateOf(1f)
    private var text by mutableStateOf("")

    @Test
    fun theFieldAndItsRowsDoNotMoveOnTheFirstKeystrokeOrTheLastDelete() {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, scale)) {
                HerdlineTheme(dark = false) {
                    Box(Modifier.width(width.dp)) { Band(fit, TextFieldValue(text, TextRange(text.length))) }
                }
            }
        }
        val side = composerFit(landscape = true, imeVisible = true, screenHeightDp = 411, side = true)
        for ((fitNow, widthNow) in listOf(ComposerFit.Regular to 360, side to 305)) {
            for (scaleNow in listOf(1.0f, 1.3f, 1.45f)) {
                fit = fitNow
                width = widthNow
                scale = scaleNow
                text = ""
                val empty = bounds()
                text = "a"
                val typed = bounds()
                text = ""
                val deleted = bounds()
                for ((name, before) in empty) {
                    assertWithinAPixel("$fitNow at $scaleNow: $name typed", before, typed.getValue(name))
                    assertWithinAPixel("$fitNow at $scaleNow: $name deleted", before, deleted.getValue(name))
                }
                if (fitNow.onAxis) {
                    for ((state, rows) in listOf("empty" to empty, "typed" to typed)) {
                        val row = rows.getValue("composer:text_row")
                        val send = rows.getValue("composer:send")
                        val off = (row.top.value + row.bottom.value) / 2 - (send.top.value + send.bottom.value) / 2
                        assertTrue("$fitNow at $scaleNow, $state: text row centre $off dp off Send's", abs(off) <= 2f)
                    }
                }
            }
        }
    }

    @Composable
    private fun Band(fit: ComposerFit, value: TextFieldValue) = ComposerBand(
        value = value, onValueChange = {}, accent = Color.Blue, title = "HERDR: herdr-mobile", view = view, agentStatus = AgentStatus.Idle,
        readOnly = false, menu = false, menuItems = emptyList(), fit = fit, noticeActions = emptyList(), inlineNotice = null,
        onMenu = {}, insertEnabled = true, sendEnabled = true, onInsert = {}, onSend = {}, destinationMismatch = false,
        onTerminalKeyboard = {}, onLargerEditor = {}, onFieldFocus = {}, locked = false, inFlight = false,
        outputFocus = null, onOutputFocus = {},
    )

    private fun bounds(): Map<String, DpRect> {
        compose.waitForIdle()
        return listOf("composer:field", "composer:to_row", "composer:text_row", "composer:send")
            .associateWith { compose.onNodeWithTag(it, useUnmergedTree = true).getUnclippedBoundsInRoot() }
    }

    private fun assertWithinAPixel(message: String, expected: DpRect, actual: DpRect) {
        val pixel = 1f / compose.density.density
        val edges = listOf(expected.left to actual.left, expected.top to actual.top, expected.right to actual.right, expected.bottom to actual.bottom)
        assertTrue("$message: $expected vs $actual", edges.all { (e, a) -> abs(e.value - a.value) <= pixel })
    }
}
