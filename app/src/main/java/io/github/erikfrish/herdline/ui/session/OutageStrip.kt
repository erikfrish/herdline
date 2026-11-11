package io.github.erikfrish.herdline.ui.session

import android.text.format.DateFormat
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
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
): MutableState<Outage> {
    val outage = remember { mutableStateOf(Outage()) }
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

/**
 * The thin strip under the header while a surface shows what it saved
 * (Collie row 4). [title] says what the reader is looking at, [detail] what
 * is known about the loss; neither guesses a cause. Retry shows a spinner
 * while [retrying]; ✕ closes the strip for this outage.
 */
@Composable
internal fun OutageStrip(
    title: String,
    detail: String?,
    retrying: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "outage",
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(tag),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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
            IconButton(onClick = onDismiss, modifier = Modifier.testTag("$tag:dismiss")) {
                Icon(Icons.Default.Close, stringResource(R.string.outage_dismiss))
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
