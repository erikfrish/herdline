package io.github.erikfrish.herdline.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.ui.glass.GlassAlertDialog

/**
 * What the stale-history strip over Chat holds (R-F1b): its text lines and
 * buttons. Both kinds keep the same lines, so a change between them never
 * moves the list. Neither has a close, since cached history must stay
 * marked. Only a [lasting] refusal has no Retry, because no read ends it
 * (HH-1); it has Open Terminal in that slot instead ([terminal], R-UX4),
 * since every lasting cause ends in Terminal still working, and the line cut
 * that off. Never both, so the button count stays. [dense] is one 48 dp row.
 */
internal data class StaleStripShape(
    val lines: Int,
    val retry: Boolean,
    val more: Boolean,
    val close: Boolean,
    val terminal: Boolean = false,
)

internal fun staleStripShape(lasting: Boolean, dense: Boolean) =
    StaleStripShape(lines = if (dense) 1 else 2, retry = !lasting, more = true, close = false, terminal = lasting)

/**
 * The strip over Chat while it shows saved history. [savedAt] is the saved
 * time and [cause] the full sentence. Line 1 is the title with that time;
 * line 2 is [cause] cut to one line. Dense is one row: an icon, the time
 * with [shortCause], Retry for a transient loss or Open Terminal for a
 * lasting one ([onTerminal]), and More. TalkBack reads the whole title and
 * [cause]. More calls [onMore]; the caller holds the
 * dialog ([StaleCauseDialog]), so a good read never closes it. No motion.
 */
@Composable
internal fun StaleStrip(
    savedAt: String,
    cause: String,
    shortCause: String,
    lasting: Boolean,
    dense: Boolean,
    onRetry: () -> Unit,
    onTerminal: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    tag: String = "chat:offline",
) {
    val shape = staleStripShape(lasting, dense)
    val title = stringResource(R.string.outage_saved_at, savedAt)
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier.fillMaxWidth().testTag(tag),
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(start = if (dense) 12.dp else 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val words = Modifier.weight(1f).clearAndSetSemantics {
                contentDescription = "$title\n$cause"
                liveRegion = LiveRegionMode.Polite
            }
            if (shape.lines == 1) {
                Icon(Icons.Default.Info, null, Modifier.size(18.dp))
                Text(
                    stringResource(R.string.chat_strip_saved_short, savedAt, shortCause),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = words.padding(start = 4.dp),
                )
            } else {
                Column(words.padding(vertical = 6.dp)) {
                    Text(title, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(cause, style = MaterialTheme.typography.bodySmall, minLines = 1, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (shape.retry) {
                TextButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag:retry")) {
                    Text(stringResource(R.string.outage_retry))
                }
            }
            if (shape.terminal) {
                TextButton(onClick = onTerminal, modifier = Modifier.heightIn(min = 48.dp).testTag("$tag:terminal")) {
                    Text(stringResource(R.string.chat_open_terminal))
                }
            }
            TextButton(
                onClick = onMore,
                modifier = Modifier.heightIn(min = 48.dp).testTag("$tag:more"),
            ) {
                Text(stringResource(R.string.chat_strip_more))
            }
        }
    }
}

/**
 * [cause] whole, with one OK. Back or OK calls [onDismiss]; the dialog's own
 * dismiss returns focus, so nothing is moved to another field (R-F1c).
 */
@Composable
internal fun StaleCauseDialog(cause: String, onDismiss: () -> Unit, tag: String = "chat:offline") {
    GlassAlertDialog(
        onDismissRequest = onDismiss,
        text = { Text(cause, style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.testTag("$tag:ok")) {
                Text(stringResource(android.R.string.ok))
            }
        },
    )
}
