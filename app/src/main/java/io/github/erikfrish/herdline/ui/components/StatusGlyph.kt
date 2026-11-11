package io.github.erikfrish.herdline.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.ui.theme.LocalThemeTokens

/** What TalkBack says for [status]; null for Unknown, which shows nothing. */
@Composable
@ReadOnlyComposable
fun statusSpoken(status: AgentStatus): String? = when (status) {
    AgentStatus.Working -> stringResource(R.string.status_working)
    AgentStatus.Blocked -> stringResource(R.string.status_blocked_spoken)
    AgentStatus.Done -> stringResource(R.string.status_done)
    AgentStatus.Idle -> stringResource(R.string.status_idle)
    AgentStatus.Unknown -> null
}

/** The outline a status or link is drawn as; each one tells itself apart in grayscale and when still. */
internal enum class GlyphShape { None, Arc, Disc, SmallDisc, Diamond, Ring, Cross }

internal fun AgentStatus.shape(): GlyphShape = when (this) {
    AgentStatus.Unknown -> GlyphShape.None
    AgentStatus.Working -> GlyphShape.Arc
    AgentStatus.Done -> GlyphShape.Disc
    AgentStatus.Idle -> GlyphShape.SmallDisc
    AgentStatus.Blocked -> GlyphShape.Diamond
}

/**
 * Whether going from [before] to [now] earns the one-off bump: only a real
 * change to a status that asks for a look (Blocked, Done), never the first
 * status a glyph shows ([before] null), so opening a list or scrolling a row
 * in does not bump everything already blocked.
 */
internal fun bumps(before: AgentStatus?, now: AgentStatus): Boolean =
    before != null && before != now && (now == AgentStatus.Blocked || now == AgentStatus.Done)

/**
 * An agent's status as a shape, never colour or motion alone: Working is an
 * open arc (turning while motion is allowed, still otherwise), Done a filled
 * circle, Idle a smaller neutral circle, Blocked a diamond, Unknown nothing.
 * A change to Blocked or Done bumps the shape once. It occupies [slot] (at
 * least [size]), so rows keep their 24 dp column with `slot = 24.dp`; motion
 * is drawn only (graphicsLayer), never laid out. On a selected row pass that
 * row's content colour as [tint]. The spoken status is its state description,
 * which a merged row or tab reads with its own label.
 */
@Composable
fun StatusGlyph(
    status: AgentStatus,
    size: Dp,
    modifier: Modifier = Modifier,
    slot: Dp = size,
    tint: Color = Color.Unspecified,
) {
    val spoken = statusSpoken(status)
    val text = LocalThemeTokens.current.statusText
    val color = tint.takeOrElse {
        when (status) {
            AgentStatus.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
            else -> text.of(status)
        }
    }
    val motion = motionAllowed()
    val bump = remember { Animatable(1f) }
    val last = remember { LastStatus() }
    LaunchedEffect(status) {
        val before = last.status
        last.status = status
        if (motion && bumps(before, status)) {
            bump.animateTo(1.4f, tween(120))
            bump.animateTo(1f, tween(180))
        } else {
            bump.snapTo(1f)
        }
    }
    Box(
        modifier
            .size(maxOf(slot, size))
            .then(if (spoken != null) Modifier.semantics { stateDescription = spoken } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val shape = Modifier.graphicsLayer {
            scaleX = bump.value
            scaleY = bump.value
        }
        when (status.shape()) {
            // A square rotated 45°: its 0.9 × size side gives the circle's visual weight.
            GlyphShape.Diamond -> Box(shape.size(size * 0.9f).rotate(45f).background(color))
            GlyphShape.SmallDisc -> Box(shape.size(size * 0.7f).clip(CircleShape).background(color))
            GlyphShape.Disc -> Box(shape.size(size).clip(CircleShape).background(color))
            GlyphShape.Arc -> Arc(color, shape.size(size), turning = motion)
            GlyphShape.None, GlyphShape.Ring, GlyphShape.Cross -> Unit
        }
    }
}

/** The status a glyph showed last, read only by its change effect, so it is no state of its own. */
private class LastStatus {
    var status: AgentStatus? = null
}

/**
 * A 270° arc: its 90° gap keeps it apart from Lost's full ring even when still.
 * The turn is read in the layer only, so it redraws without recomposing, and
 * its transition exists only while an arc is shown and may move. [onTurn]
 * hears each angle drawn, so a shape that follows can start where it stopped.
 */
@Composable
private fun Arc(color: Color, modifier: Modifier, turning: Boolean, onTurn: ((Float) -> Unit)? = null) {
    val turn = if (turning) {
        rememberInfiniteTransition(label = "arc").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
            label = "turn",
        )
    } else {
        null
    }
    Canvas(
        modifier.graphicsLayer {
            val angle = turn?.value ?: 0f
            onTurn?.invoke(angle)
            rotationZ = angle
        },
    ) {
        val stroke = (size.minDimension * 0.16f).coerceIn(1.5.dp.toPx(), 2.dp.toPx())
        val inset = stroke / 2
        drawArc(
            color,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(inset, inset),
            size = Size(size.width - stroke, size.height - stroke),
            // Butt ends: round caps would eat into the gap that tells it from a ring.
            style = Stroke(stroke, cap = StrokeCap.Butt),
        )
    }
}

internal enum class Link { Up, Trying, Lost, Failed }

private fun Connection.State.link(): Link = when (this) {
    is Connection.State.Connected -> Link.Up
    is Connection.State.Connecting, is Connection.State.SettingUp -> Link.Trying
    is Connection.State.Disconnected -> Link.Lost
    else -> Link.Failed
}

internal fun Link.shape(): GlyphShape = when (this) {
    Link.Up -> GlyphShape.Disc
    Link.Trying -> GlyphShape.Arc
    Link.Lost -> GlyphShape.Ring
    Link.Failed -> GlyphShape.Cross
}

/** What TalkBack says for the session's link. */
@Composable
@ReadOnlyComposable
fun connectionSpoken(state: Connection.State): String = when (state.link()) {
    Link.Up -> stringResource(R.string.connection_connected)
    Link.Trying -> stringResource(R.string.connection_connecting)
    Link.Lost -> stringResource(R.string.connection_lost)
    Link.Failed -> stringResource(R.string.connection_failed)
}

/**
 * Whether going from [before] to [now] closes the arc into the disc: only a
 * connection completing (Trying → Up), never a glyph that first shows Up
 * ([before] null), so opening a session already connected is still.
 */
internal fun closes(before: Link?, now: Link): Boolean = before == Link.Trying && now == Link.Up

/** The share of the close spent sweeping the arc shut; the rest fills it in. */
private const val CLOSE_SWEEP = 0.6f

/** The closing arc's sweep at [progress] (0..1): 270° grows to the full 360°, then holds. */
internal fun closeSweep(progress: Float): Float = 270f + 90f * (progress / CLOSE_SWEEP).coerceIn(0f, 1f)

/** The disc's fill alpha at [progress] (0..1): none while the arc closes, then up to opaque. */
internal fun closeFill(progress: Float): Float = ((progress - CLOSE_SWEEP) / (1f - CLOSE_SWEEP)).coerceIn(0f, 1f)

/** What a link glyph showed last, read only by its change effect and the close, so it is no state of its own. */
private class LastLink {
    var link: Link? = null
    /** The Working arc's last drawn turn, in degrees. */
    var angle = 0f
}

/**
 * The session's link, 12 dp: a filled circle when connected, the Working
 * arc (turning while motion is allowed) while connecting or setting up, a
 * hollow full ring when lost, ✕ when it cannot connect. When a connection
 * completes, the arc closes into the circle once (250 ms) while motion is
 * allowed, drawn only, in the same 12 dp; otherwise the circle is there at
 * once. Tagged `session:state:<State>`.
 */
@Composable
fun ConnectionGlyph(state: Connection.State, modifier: Modifier = Modifier) {
    val link = state.link()
    val text = LocalThemeTokens.current.statusText
    // statusText has no Lost colour, so the ring takes the neutral role, as Idle does.
    val color = when (link) {
        Link.Up -> text.done
        Link.Trying -> text.working
        Link.Lost -> MaterialTheme.colorScheme.onSurfaceVariant
        Link.Failed -> MaterialTheme.colorScheme.error
    }
    val spoken = connectionSpoken(state)
    val tagged = modifier
        .size(12.dp)
        .semantics { stateDescription = spoken }
        .testTag("session:state:" + state::class.simpleName)
    val motion = motionAllowed()
    val last = remember { LastLink() }
    // Decided in the composition that first shows Up, so its first frame is already the open arc, never a flash of the disc.
    val close = remember(link) { Animatable(if (motion && closes(last.link, link)) 0f else 1f) }
    // The close starts where the turning arc stopped, so its gap does not jump.
    val start = remember(link) { -90f + last.angle }
    LaunchedEffect(link) {
        last.link = link
        if (close.value < 1f) close.animateTo(1f, tween(250, easing = LinearEasing))
    }
    if (link.shape() == GlyphShape.Arc) {
        Arc(color, tagged, turning = motion, onTurn = { last.angle = it })
        return
    }
    Canvas(tagged) {
        when (link.shape()) {
            GlyphShape.Ring -> {
                val stroke = 1.5.dp.toPx()
                drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            }
            GlyphShape.Cross -> {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2 + 1.dp.toPx()
                val end = size.width - inset
                drawLine(color, Offset(inset, inset), Offset(end, end), stroke, StrokeCap.Round)
                drawLine(color, Offset(end, inset), Offset(inset, end), stroke, StrokeCap.Round)
            }
            else -> {
                // Read here, in the draw phase: the close redraws without recomposing or laying out.
                val progress = close.value
                if (progress >= 1f) {
                    drawCircle(color)
                } else {
                    // The Working arc's own stroke and start, so the close continues the shape that was there.
                    val stroke = (size.minDimension * 0.16f).coerceIn(1.5.dp.toPx(), 2.dp.toPx())
                    val inset = stroke / 2
                    drawArc(
                        color,
                        startAngle = start,
                        sweepAngle = closeSweep(progress),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke, cap = StrokeCap.Butt),
                    )
                    closeFill(progress).takeIf { it > 0f }?.let { drawCircle(color, alpha = it) }
                }
            }
        }
    }
}
