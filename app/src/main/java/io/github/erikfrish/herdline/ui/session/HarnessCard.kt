package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.HorizontalAlignmentLine
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Harness
import io.github.erikfrish.herdline.core.HarnessItem
import io.github.erikfrish.herdline.core.worksHere
import kotlin.math.max
import kotlin.math.min

/**
 * Why Chat is off for the pane's program, over the top edge of the box it
 * is placed in, and what does work there (team/assignments/night-d2-harness-card.md).
 *
 * Like AgentBannerSlot it is an overlay: the box's other children keep
 * their size, so the terminal grid never changes and no resize is sent.
 * It never takes focus and has no timer; the caller drops it on ✕, on
 * Open Terminal, on a pane or tab switch, or when the program changes.
 *
 * [program] is the Herdr name exactly as reported, null for a shell.
 * [keyboardUp]: while the IME is up and the slot cannot hold the padding,
 * title and both buttons, the card is not placed at all (QA N1-g: landscape,
 * font 1.3, the header gone, a 46 dp slot with nothing above it). It is kept,
 * not dropped, and comes back whole when the keyboard goes: hidden beats a
 * card whose buttons are out of the frame, and growing it would cover the
 * extra keys the user just opened the keyboard to use.
 */
@Composable
internal fun BoxScope.HarnessCardSlot(
    program: String?,
    tabName: String,
    keyboardUp: Boolean,
    onTerminal: () -> Unit,
    onClose: () -> Unit,
) {
    val chat = stringResource(R.string.chat)
    val terminal = stringResource(R.string.chat_terminal)
    val title = if (program == null) {
        stringResource(R.string.harness_card_shell_title, chat)
    } else {
        stringResource(R.string.harness_card_title, program, chat, terminal, tabName)
    }
    val body = if (program == null) {
        stringResource(R.string.harness_card_shell_body, chat, tabName, terminal)
    } else {
        stringResource(R.string.harness_card_body, program, chat, terminal, tabName)
    }
    val rows = Harness.of(program.orEmpty()).worksHere()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    // Announced once per card (the caller keys it): after the keyboard has come up while
    // it is open it may have been hidden, and its return must not read it again.
    val keyboardAtOpen = remember { keyboardUp }
    var announce by remember { mutableStateOf(true) }
    LaunchedEffect(keyboardUp) { if (keyboardUp != keyboardAtOpen) announce = false }
    Surface(
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val card = measurable.measure(constraints)
                // Both lines move with the scroll, so their distance is the head's height.
                val top = card[HeadTop]
                val bottom = card[HeadBottom]
                val need = if (top == AlignmentLine.Unspecified || bottom == AlignmentLine.Unspecified) 0 else bottom - top + 2 * 16.dp.roundToPx()
                if (keyboardUp && constraints.hasBoundedHeight && need > constraints.maxHeight) {
                    layout(0, 0) {}
                } else {
                    layout(card.width, card.height) { card.place(0, 0) }
                }
            }
            .semantics { if (announce) paneTitle = title },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        // Title and actions stay at the top; body, list and footnote are the one
        // scroll region under them, so the cap (CardCap of the slot) shortens the
        // body, never the buttons (QA N1-g: landscape at font 1.3). Where title and
        // actions alone leave less than [MinScrollRegion] under that cap, the cap
        // goes and the whole column scrolls in the full slot instead, so both
        // buttons fit whole: chosen from the measured heights, not orientation.
        BoxWithConstraints {
            val slot = constraints.maxHeight
            Layout(
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.semantics { if (announce) liveRegion = LiveRegionMode.Polite },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            FilledTonalButton(
                                onClick = onTerminal,
                                modifier = Modifier.heightIn(min = 48.dp).semantics { role = Role.Button },
                            ) { Text(stringResource(R.string.harness_open_terminal, terminal)) }
                            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) {
                                Text(stringResource(R.string.harness_close))
                            }
                        }
                    }
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(body, style = MaterialTheme.typography.bodyMedium, color = muted)
                        Text(stringResource(R.string.harness_works_here), style = MaterialTheme.typography.labelMedium, color = muted)
                        rows.forEach { (item, available) ->
                            val name = itemName(item, terminal)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Shape and words carry the difference, never colour: both icons are muted.
                                Icon(if (available) Icons.Filled.Check else ClockIcon, null, Modifier.size(18.dp), tint = muted)
                                Text(
                                    if (available) name else stringResource(R.string.harness_not_yet, name),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                        Text(stringResource(R.string.harness_checked_only), style = MaterialTheme.typography.bodySmall, color = muted)
                    }
                },
                // The outer scroll only ever has range in the fallback: when the
                // split fits, the column is no taller than the cap. In the fallback
                // it is bounded by the full slot.
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            ) { (headPart, restPart), outer ->
                val loose = outer.copy(minHeight = 0)
                val head = headPart.measure(loose)
                val gap = 8.dp.roundToPx()
                // What the split leaves the scroll region: the cap less padding, head and gap.
                val budget = if (slot == Constraints.Infinity) -1 else (slot * CardCap).toInt() - 2 * 16.dp.roundToPx() - head.height - gap
                val rest = if (budget >= MinScrollRegion.roundToPx()) {
                    restPart.measure(loose.copy(maxHeight = budget))
                } else {
                    // Fallback: the region at its full height, so only the outer column scrolls
                    // (an inner scroll under an unbounded one must get a finite height).
                    val full = restPart.maxIntrinsicHeight(outer.maxWidth)
                    restPart.measure(loose.copy(minHeight = full, maxHeight = full))
                }
                layout(outer.maxWidth, head.height + gap + rest.height, mapOf(HeadTop to 0, HeadBottom to head.height)) {
                    head.place(0, 0)
                    rest.place(0, head.height + gap)
                }
            }
        }
    }
}

@Composable
private fun itemName(item: HarnessItem, terminal: String): String = when (item) {
    HarnessItem.Terminal -> stringResource(R.string.harness_item_terminal, terminal)
    HarnessItem.History -> stringResource(R.string.harness_item_history)
    HarnessItem.Send -> stringResource(R.string.harness_item_send)
    HarnessItem.Steer -> stringResource(R.string.harness_item_steer)
    HarnessItem.Status -> stringResource(R.string.harness_item_status)
    HarnessItem.Questions -> stringResource(R.string.harness_item_questions)
    HarnessItem.Subagents -> stringResource(R.string.harness_item_subagents)
    HarnessItem.Media -> stringResource(R.string.harness_item_media)
}

/** The least a split scroll region may get under the fixed head: one list row and its gap. */
private val MinScrollRegion = 48.dp

/** The card's height while the split fits, as a share of its slot, so the terminal stays in view. */
private const val CardCap = 0.6f

/** The head's (title and buttons) top and bottom, read by the slot to decide whether the card is placed. */
private val HeadTop = HorizontalAlignmentLine(::min)
private val HeadBottom = HorizontalAlignmentLine(::max)

/*
 * Material Symbols `schedule` (outlined), path data copied from
 * material-icons-extended (Icons.Outlined.Schedule, Copyright The Android
 * Open Source Project, Apache-2.0), as AgentsIcon.kt does, so the app keeps
 * only the core icons dependency.
 */
private val ClockIcon: ImageVector by lazy {
    ImageVector.Builder(name = "Clock", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(
                "M11.99,2C6.47,2 2,6.48 2,12s4.47,10 9.99,10C17.52,22 22,17.52 22,12S17.52,2 11.99,2z" +
                    "M12,20c-4.42,0 -8,-3.58 -8,-8s3.58,-8 8,-8 8,3.58 8,8 -3.58,8 -8,8z" +
                    "M12.5,7H11v6l5.25,3.15 0.75,-1.23 -4.5,-2.67z",
            ),
            pathFillType = PathFillType.NonZero,
            fill = SolidColor(Color.Black),
        )
        .build()
}
