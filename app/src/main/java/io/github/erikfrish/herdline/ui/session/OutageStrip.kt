package io.github.erikfrish.herdline.ui.session

import android.text.format.DateFormat
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.ui.components.motionAllowed
import kotlinx.coroutines.delay
import java.util.Date

/**
 * Follows [live] (null: nothing known yet) through an [Outage]: the loss is
 * stamped when seen, and its strip comes up once it outlasts a blip.
 * [onDrop] hears each new outage and [onReconnected] the recovery from one
 * whose strip was shown.
 */
@Composable
internal fun rememberOutage(
    live: Boolean?,
    onDrop: () -> Unit = {},
    onReconnected: () -> Unit = {},
    // A holder that outlives this composition keeps the outage across a re-entry.
    state: MutableState<Outage> = remember { mutableStateOf(Outage()) },
): MutableState<Outage> {
    val outage = state
    val drop by rememberUpdatedState(onDrop)
    val reconnected by rememberUpdatedState(onReconnected)
    LaunchedEffect(live) {
        if (live == null) return@LaunchedEffect
        val step = outage.value.observe(live, System.currentTimeMillis())
        outage.value = step.outage
        if (step.dropped) drop()
        if (step.reconnected) reconnected()
    }
    val since = outage.value.since
    val shown = outage.value.shown
    LaunchedEffect(since, shown) {
        if (since == null || shown) return@LaunchedEffect
        delay((since + OUTAGE_SHOW_AFTER_MS - System.currentTimeMillis()).coerceAtLeast(0))
        outage.value = outage.value.tick(System.currentTimeMillis())
    }
    return outage
}

/** M3's emphasized-decelerate curve, as AgentBanner slides in with. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * The thin strip under the header while a surface shows what it saved
 * (Collie row 4). [title] says what the reader is looking at, [detail] what
 * is known about the loss; neither guesses a cause. Retry shows a spinner
 * while [retrying]; without [onRetry] there is none, for a loss no retry
 * ends. ✕ closes the strip for this outage; without [onDismiss] there is
 * none, for a strip that must stay.
 *
 * It slides in from above the moment it is composed and crossfades a new
 * title or detail, so the loss and each change of it are seen. Both are
 * drawn over what is under it, which keeps its place (the strip is an
 * overlay; its height never animates). With motion off it is simply there.
 * Its going is the caller's: it leaves composition at once.
 */
@Composable
internal fun OutageStrip(
    title: String,
    detail: String?,
    retrying: Boolean,
    onRetry: (() -> Unit)?,
    onDismiss: (() -> Unit)?,
    modifier: Modifier = Modifier,
    tag: String = "outage",
) {
    val motion = motionAllowed()
    val shown = remember { MutableTransitionState(!motion).apply { targetState = true } }
    AnimatedVisibility(
        visibleState = shown,
        enter = if (motion) {
            slideInVertically(tween(200, easing = EmphasizedDecelerate)) { -it } + fadeIn(tween(200))
        } else {
            EnterTransition.None
        },
        modifier = modifier.fillMaxWidth(),
    ) {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.fillMaxWidth().testTag(tag),
        ) {
            Row(
                // Without buttons the words keep the start's 16 dp from the edge too.
                Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = if (onRetry == null && onDismiss == null) 16.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AnimatedContent(
                    targetState = title to detail,
                    transitionSpec = {
                        // No size transform: the strip keeps its height while the words change.
                        if (motion) {
                            (fadeIn(tween(150)) togetherWith fadeOut(tween(150))).using(null)
                        } else {
                            (EnterTransition.None togetherWith ExitTransition.None).using(null)
                        }
                    },
                    label = "outage-text",
                    modifier = Modifier.weight(1f),
                ) { (shownTitle, shownDetail) ->
                    // The words fading out are not read again: only the new ones are announced,
                    // in full even where the strip cuts them, since the cause is at the end. Text
                    // and the polite live region sit on this one node, not on the strip around the
                    // buttons, which has no words of its own to announce (QA m14, QF-4).
                    val leaving = transition.targetState == EnterExitState.PostExit
                    val spoken = listOfNotNull(shownTitle, shownDetail?.takeIf { it.isNotBlank() }).joinToString("\n")
                    Column(
                        Modifier
                            .padding(vertical = 6.dp)
                            .clearAndSetSemantics {
                                if (!leaving) {
                                    text = AnnotatedString(spoken)
                                    liveRegion = LiveRegionMode.Polite
                                }
                            },
                    ) {
                        Text(shownTitle, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (!shownDetail.isNullOrBlank()) {
                            // Two lines: a reason such as an SSH error reads to its end.
                            Text(
                                shownDetail,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (onRetry != null) {
                    TextButton(
                        onClick = onRetry,
                        enabled = !retrying,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("$tag:retry"),
                    ) {
                        if (retrying) {
                            val label = stringResource(R.string.outage_retrying)
                            CircularProgressIndicator(
                                Modifier.size(16.dp).semantics { contentDescription = label },
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        } else {
                            Text(stringResource(R.string.outage_retry))
                        }
                    }
                }
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.testTag("$tag:dismiss")) {
                        Icon(Icons.Default.Close, stringResource(R.string.outage_dismiss))
                    }
                }
            }
        }
    }
}

/** [at], epoch milliseconds, as the device's own clock reads it (12 or 24 hour). */
@Composable
internal fun outageTime(at: Long): String {
    val context = LocalContext.current
    return remember(at) { DateFormat.getTimeFormat(context).format(Date(at)) }
}
