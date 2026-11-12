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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
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
import io.github.erikfrish.herdline.core.CantCheckWhy
import io.github.erikfrish.herdline.core.Cell
import io.github.erikfrish.herdline.core.Harness
import io.github.erikfrish.herdline.core.HarnessKey
import io.github.erikfrish.herdline.core.HarnessRuntime
import io.github.erikfrish.herdline.core.Note
import io.github.erikfrish.herdline.core.Reason
import io.github.erikfrish.herdline.core.Shown
import io.github.erikfrish.herdline.core.shown
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * What works for the pane's program, over the top edge of the box it is
 * placed in: Terminal, then one row per
 * [HarnessKey] by what the card can say about it.
 *
 * Like AgentBannerSlot it is an overlay: the box's other children keep
 * their size, so the terminal grid never changes and no resize is sent.
 * It never takes focus and has no timer; the caller drops it on ✕, on
 * Open Terminal, on a pane or tab switch, or when the program changes.
 *
 * [program] is the Herdr name exactly as reported, null when Herdr names
 * none. [agentHere]: Herdr reports an agent in the pane; with no [program]
 * the rows read "can't check now", without an agent it is the shell card.
 * [live]: the connection is up; while it is not every row but Terminal
 * reads "can't check now" and comes back with it. [runtime] is what the
 * route reports now (card §3.4).
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
    live: Boolean,
    agentHere: Boolean,
    onTerminal: () -> Unit,
    onClose: () -> Unit,
    runtime: HarnessRuntime = HarnessRuntime(),
) {
    val chat = stringResource(R.string.chat)
    val terminal = stringResource(R.string.chat_terminal)
    val shell = program == null && !agentHere
    val chatOn = program != null && Harness.of(program).chat
    val title = when {
        shell -> stringResource(R.string.harness_card_shell_title, chat)
        program == null -> stringResource(R.string.harness_works_here)
        chatOn -> stringResource(R.string.harness_card_title_works, program)
        else -> stringResource(R.string.harness_card_title, program, chat, terminal, tabName)
    }
    val body = when {
        shell -> stringResource(R.string.harness_card_shell_body, chat, tabName, terminal)
        program == null || chatOn -> null
        else -> stringResource(R.string.harness_card_body, program, chat, terminal, tabName)
    }
    val rows = if (shell) emptyList() else cardRows(program, live, runtime)
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
                        body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = muted) }
                        if (body != null || program == null) {
                            Text(stringResource(R.string.harness_works_here), style = MaterialTheme.typography.labelMedium, color = muted)
                        }
                        CardRow(Icons.Filled.Check, stringResource(R.string.harness_item_terminal, terminal), null)
                        rows.forEach { (key, row) ->
                            val name = keyName(key)
                            when (row) {
                                is Shown.CantCheck -> CardRow(
                                    ClockIcon,
                                    stringResource(R.string.harness_state_cant_check, name),
                                    stringResource(
                                        when (row.why) {
                                            CantCheckWhy.Offline -> R.string.harness_cant_check_offline
                                            CantCheckWhy.Identity -> R.string.harness_cant_check_identity
                                            CantCheckWhy.Server -> R.string.harness_cant_check_server
                                        },
                                    ),
                                )
                                is Shown.Claim -> when (val cell = row.cell) {
                                    is Cell.Works -> CardRow(Icons.Filled.Check, name, null)
                                    is Cell.Unverified -> CardRow(
                                        DashedCheckIcon,
                                        stringResource(R.string.harness_state_unverified, name),
                                        listOfNotNull(stringResource(R.string.harness_unverified_detail), cell.note?.let { noteText(it) }).joinToString(" "),
                                    )
                                    is Cell.NotYet -> CardRow(ClockIcon, stringResource(R.string.harness_state_not_yet, name), reasonText(cell))
                                    is Cell.NotIn -> CardRow(
                                        DashCircleIcon,
                                        stringResource(R.string.harness_state_not_in, name, program.orEmpty()),
                                        stringResource(R.string.harness_source, cell.source),
                                    )
                                }
                            }
                        }
                        Text(stringResource(R.string.harness_tested_only), style = MaterialTheme.typography.bodySmall, color = muted)
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

/**
 * The card's rows after Terminal: what the pane's own server decides now
 * (an OpenCode or Kilo server that is missing, or could not be asked), then
 * Works, then Unverified, then Not in Herdline yet (and Can't check now),
 * then Not in, each group in key order. The server's rows lead because the
 * card's list is capped and scrolls: below the static claims they were out
 * of view, and the card read as if Send, Stop, Steer and Questions worked.
 */
internal fun cardRows(program: String?, live: Boolean, runtime: HarnessRuntime): List<Pair<HarnessKey, Shown>> =
    HarnessKey.entries.map { it to shown(program, it, live, runtime) }.sortedBy { (_, row) ->
        when (row) {
            is Shown.CantCheck -> if (row.why == CantCheckWhy.Server) -1 else 2
            is Shown.Claim -> when (val cell = row.cell) {
                is Cell.Works -> 0
                is Cell.Unverified -> 1
                is Cell.NotYet -> if (cell.reason == Reason.NeedsServer) -1 else 2
                is Cell.NotIn -> 3
            }
        }
    }

/** One non-interactive row: [line1], then a muted [line2]; TalkBack reads both as one. */
@Composable
private fun CardRow(icon: ImageVector, line1: String, line2: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().heightIn(min = 40.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Shape and words carry the difference, never colour: every icon is muted.
        Icon(icon, null, Modifier.size(18.dp), tint = muted)
        Column {
            Text(line1, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            line2?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = muted) }
        }
    }
}

@Composable
private fun keyName(key: HarnessKey): String = stringResource(
    when (key) {
        HarnessKey.History -> R.string.harness_key_history
        HarnessKey.Live -> R.string.harness_key_live
        HarnessKey.Send -> R.string.harness_key_send
        HarnessKey.Stop -> R.string.harness_key_stop
        HarnessKey.SteerQueue -> R.string.harness_key_steer_queue
        HarnessKey.Tools -> R.string.harness_key_tools
        HarnessKey.Media -> R.string.harness_key_media
        HarnessKey.Questions -> R.string.harness_key_questions
        HarnessKey.Status -> R.string.harness_key_status
        HarnessKey.Subagents -> R.string.harness_key_subagents
        HarnessKey.SessionChange -> R.string.harness_key_session_change
    },
)

@Composable
private fun noteText(note: Note): String = stringResource(
    when (note) {
        Note.Typed -> R.string.harness_note_typed
        Note.QueueMissing -> R.string.harness_note_queue_missing
    },
)

/**
 * A disabled control's §3.5 text for [agent]'s [key], from [unavailable] (a
 * `control_*_unavailable` id), or null when the claim gives no reason: the
 * caller then keeps its own words. Text only; it never decides whether the
 * control is enabled.
 */
@Composable
internal fun controlReason(agent: String?, key: HarnessKey, unavailable: Int): String? {
    val cell = agent?.let { Harness.of(it).cell(key) } as? Cell.NotYet ?: return null
    return stringResource(unavailable, agent, reasonText(cell))
}

/** [cell]'s reason line (card §2.3), also the %2$s of a disabled control's text (§3.5). */
@Composable
internal fun reasonText(cell: Cell.NotYet): String {
    val id = when (cell.reason) {
        Reason.NotBuilt -> R.string.harness_reason_not_built
        Reason.NoSource -> R.string.harness_reason_no_source
        Reason.NoRef -> R.string.harness_reason_no_ref
        Reason.Closed -> R.string.harness_reason_closed
        Reason.NoControl -> R.string.harness_reason_no_control
        Reason.Lock -> R.string.harness_reason_lock
        Reason.FormatUnpinned -> R.string.harness_reason_format_unpinned
        Reason.SendMedia -> R.string.harness_reason_send_media
        Reason.ScreenStatus -> R.string.harness_reason_screen_status
        Reason.NeedsHook -> R.string.harness_reason_needs_hook
        Reason.NeedsServer -> R.string.harness_reason_needs_server
        Reason.StaleId -> R.string.harness_reason_stale_id
        Reason.Rewind -> R.string.harness_reason_rewind
        Reason.NoSessionYet -> R.string.harness_reason_no_session_yet
        Reason.UnknownProgram -> R.string.harness_reason_unknown_program
        Reason.NoLiveAttach -> R.string.harness_reason_no_live_attach
        Reason.CloudAuth -> R.string.harness_reason_cloud_auth
        Reason.BlindKey -> R.string.harness_reason_blind_key
        Reason.NoSocket -> R.string.harness_reason_no_socket
        Reason.InputUnproved -> R.string.harness_reason_input_unproved
        Reason.AmbiguousSession -> R.string.harness_reason_ambiguous_session
        Reason.ServerUnattachable -> R.string.harness_reason_server_unattachable
    }
    return if (cell.subject != null) stringResource(id, cell.subject) else stringResource(id)
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

/** Unverified: a check inside a dashed circle (8 strokes of 30° with 15° gaps). */
private val DashedCheckIcon: ImageVector by lazy {
    ImageVector.Builder(name = "DashedCheck", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f).apply {
        repeat(8) { i ->
            val from = Math.toRadians(i * 45.0 + 7.5)
            val to = Math.toRadians(i * 45.0 + 37.5)
            val arc = PathBuilder()
                .moveTo(12f + 9f * cos(from).toFloat(), 12f + 9f * sin(from).toFloat())
                .arcTo(9f, 9f, 0f, isMoreThanHalf = false, isPositiveArc = true, x1 = 12f + 9f * cos(to).toFloat(), y1 = 12f + 9f * sin(to).toFloat())
            addPath(arc.nodes, stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        }
        addPath(addPathNodes("M8,12.5L11,15.5L16.5,9.5"), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round)
    }.build()
}

/** Not in <agent>: a dash in a circle. */
private val DashCircleIcon: ImageVector by lazy {
    ImageVector.Builder(name = "DashCircle", defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(addPathNodes("M3,12A9,9 0 1,1 21,12A9,9 0 1,1 3,12z"), stroke = SolidColor(Color.Black), strokeLineWidth = 2f)
        .addPath(addPathNodes("M8,12H16"), stroke = SolidColor(Color.Black), strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round)
        .build()
}
