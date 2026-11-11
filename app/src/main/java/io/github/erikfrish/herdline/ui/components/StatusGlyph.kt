package io.github.erikfrish.herdline.ui.components

import androidx.compose.animation.core.RepeatMode
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
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
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

/**
 * An agent's status as a shape, never colour alone: Blocked is a
 * diamond, Idle a smaller neutral circle, Working and Done circles in the
 * status text colours, Unknown nothing. It occupies [slot] (at least [size]),
 * so rows keep their 24 dp column with `slot = 24.dp`. On a selected row pass
 * that row's content colour as [tint]. The spoken status is its state
 * description, which a merged row or tab reads with its own label.
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
    Box(
        modifier
            .size(maxOf(slot, size))
            .then(if (spoken != null) Modifier.semantics { stateDescription = spoken } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        when (status) {
            AgentStatus.Unknown -> Unit
            // A square rotated 45°: its 0.9 × size side gives the circle's visual weight.
            AgentStatus.Blocked -> Box(Modifier.size(size * 0.9f).rotate(45f).background(color))
            AgentStatus.Idle -> Box(Modifier.size(size * 0.7f).clip(CircleShape).background(color))
            AgentStatus.Working, AgentStatus.Done -> Box(Modifier.size(size).clip(CircleShape).background(color))
        }
    }
}

private enum class Link { Up, Trying, Lost, Failed }

private fun Connection.State.link(): Link = when (this) {
    is Connection.State.Connected -> Link.Up
    is Connection.State.Connecting, is Connection.State.SettingUp -> Link.Trying
    is Connection.State.Disconnected -> Link.Lost
    else -> Link.Failed
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
 * The session's link, 12 dp: a filled circle when connected,
 * the same circle pulsing while connecting or setting up, a hollow ring when
 * lost, ✕ when it cannot connect. Tagged `session:state:<State>`.
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
    val pulse = if (link == Link.Trying) {
        rememberInfiniteTransition(label = "pulse").animateFloat(
            initialValue = 0.35f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
            label = "alpha",
        ).value
    } else {
        1f
    }
    val spoken = connectionSpoken(state)
    Canvas(
        modifier
            .size(12.dp)
            .graphicsLayer { alpha = pulse }
            .semantics { stateDescription = spoken }
            .testTag("session:state:" + state::class.simpleName),
    ) {
        when (link) {
            Link.Up, Link.Trying -> drawCircle(color)
            Link.Lost -> {
                val stroke = 1.5.dp.toPx()
                drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            }
            Link.Failed -> {
                val stroke = 2.dp.toPx()
                val inset = stroke / 2 + 1.dp.toPx()
                val end = size.width - inset
                drawLine(color, Offset(inset, inset), Offset(end, end), stroke, StrokeCap.Round)
                drawLine(color, Offset(end, inset), Offset(inset, end), stroke, StrokeCap.Round)
            }
        }
    }
}
