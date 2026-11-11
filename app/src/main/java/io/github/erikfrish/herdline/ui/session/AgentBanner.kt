package io.github.erikfrish.herdline.ui.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.ui.theme.LocalThemeTokens
import kotlinx.coroutines.delay

/** What a banner is about (BN-1 §3): its glyph, its precedence and how long it stays. */
internal enum class NoteKind { Finished, Attention, Problem }

/**
 * One banner: Herdr's notification, or a problem the host reported
 * unprompted. [title] is the system notification's title and [location]
 * its body; [pane] the pane it names, if any. [id] tells two equal notes apart.
 */
internal data class AgentNote(
    val title: String,
    val location: String?,
    val kind: NoteKind,
    val pane: String?,
    val id: Long,
)

/**
 * The banners waiting, one shown at a time (BN-1 §3): attention and problems
 * before completions, the oldest first within each, and a newer note for a
 * pane replacing the older one for it.
 */
internal data class BannerQueue(val notes: List<AgentNote> = emptyList()) {
    val shown: AgentNote? get() = notes.firstOrNull { it.kind != NoteKind.Finished } ?: notes.firstOrNull()

    fun add(note: AgentNote) = BannerQueue(notes.filterNot { note.pane != null && it.pane == note.pane } + note)

    fun dismiss(id: Long) = BannerQueue(notes.filterNot { it.id == id })

    /**
     * What is left once [viewedPane] is on screen and [blocked] (null: not
     * known yet) are the panes still waiting for input: a completion of the
     * pane on screen says nothing new, and an attention note goes when its
     * pane leaves Blocked.
     */
    fun settle(viewedPane: String?, blocked: Set<String>?) = BannerQueue(
        notes.filterNot {
            when (it.kind) {
                NoteKind.Finished -> it.pane != null && it.pane == viewedPane
                NoteKind.Attention -> it.pane != null && blocked != null && it.pane !in blocked
                NoteKind.Problem -> false
            }
        },
    )
}

/** How long a completion stays before the reader's accessibility timeout stretches it. */
private const val FINISHED_MILLIS = 8_000L

/** M3's emphasized-decelerate curve, for the slide in. */
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

/**
 * [note]'s banner over the top edge of the box it is placed in (BN-1),
 * nothing while [note] is null. The banner is an overlay: a box's other
 * children are measured with the box's whole size whether a banner shows or
 * not, so the terminal grid under it never changes and no resize is sent.
 * It never takes focus, and only its own bounds take touches.
 *
 * It goes on a horizontal swipe, an upward fling, ✕ (also a screen-reader
 * action), Open, and, for a completion, after its time. [openable] says
 * whether Open is offered: the note's pane still exists and is not on
 * screen. [onOpen] focuses exactly that pane and answers false when it has
 * closed meanwhile, which the banner then says instead of its location.
 */
@Composable
internal fun BoxScope.AgentBannerSlot(
    note: AgentNote?,
    openable: Boolean,
    openLabel: String?,
    onOpen: (String) -> Boolean,
    onDismiss: (Long) -> Unit,
) {
    if (note == null) return
    // A new note starts fresh: its own swipe state, time and slide in.
    key(note.id) {
        val visible = remember { MutableTransitionState(false).apply { targetState = true } }
        AnimatedVisibility(
            visibleState = visible,
            enter = slideInVertically(tween(200, easing = EmphasizedDecelerate)) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            AgentBanner(note, openable, openLabel, onOpen) { onDismiss(note.id) }
        }
    }
}

@Composable
private fun AgentBanner(note: AgentNote, openable: Boolean, openLabel: String?, onOpen: (String) -> Boolean, onDismiss: () -> Unit) {
    val dismiss by rememberUpdatedState(onDismiss)
    var closed by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    val swipe = rememberSwipeToDismissBoxState(positionalThreshold = { it * 0.4f })
    val accessibility = LocalAccessibilityManager.current
    // Only a completion times out; the time starts again once a press lets go.
    if (note.kind == NoteKind.Finished) {
        LaunchedEffect(pressed) {
            if (pressed) return@LaunchedEffect
            delay(accessibility?.calculateRecommendedTimeoutMillis(FINISHED_MILLIS, containsIcons = true, containsText = true, containsControls = true) ?: FINISHED_MILLIS)
            dismiss()
        }
    }
    val flingPx = with(LocalDensity.current) { 600.dp.toPx() }
    val tokens = LocalThemeTokens.current
    val dismissLabel = stringResource(R.string.banner_dismiss)
    val location = if (closed) stringResource(R.string.banner_pane_closed) else note.location
    val announcement = location?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.banner_announcement, note.title, it) }
        ?: stringResource(R.string.banner_announcement_title, note.title)
    val pane = note.pane.takeIf { openable && !closed }
    val open = {
        if (pane != null) {
            if (onOpen(pane)) dismiss() else closed = true
        }
    }
    val openAction = pane?.let { stringResource(R.string.banner_open_action, openLabel ?: note.title) }
    SwipeToDismissBox(
        state = swipe,
        backgroundContent = {},
        onDismiss = { dismiss() },
        modifier = Modifier
            .padding(8.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .testTag("banner"),
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            shadowElevation = 3.dp,
            modifier = Modifier
                .fillMaxWidth()
                .draggable(
                    rememberDraggableState {},
                    Orientation.Vertical,
                    onDragStopped = { velocity -> if (velocity < -flingPx) dismiss() },
                )
                .pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        pressed = true
                        waitForUpOrCancellation(PointerEventPass.Initial)
                        pressed = false
                    }
                }
                .semantics(mergeDescendants = true) {
                    contentDescription = announcement
                    liveRegion = LiveRegionMode.Polite
                    customActions = listOfNotNull(
                        openAction?.let { label -> CustomAccessibilityAction(label) { open(); true } },
                        CustomAccessibilityAction(dismissLabel) { dismiss(); true },
                    )
                },
        ) {
            Row(
                Modifier.heightIn(min = 64.dp).padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The shape carries the kind; the colour only repeats it. material-icons-core has no
                // Error glyph, so attention uses the app's own needs-input diamond, as its notification does.
                val (glyph, tint) = when (note.kind) {
                    NoteKind.Finished -> rememberVectorPainter(Icons.Default.CheckCircle) to tokens.statusText.of(AgentStatus.Done)
                    NoteKind.Attention -> painterResource(R.drawable.ic_needs_input) to tokens.statusText.of(AgentStatus.Blocked)
                    NoteKind.Problem -> rememberVectorPainter(Icons.Default.Warning) to MaterialTheme.colorScheme.error
                }
                Icon(glyph, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.Center) {
                    Text(note.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (!location.isNullOrBlank()) {
                        Text(
                            location,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (pane != null) {
                    TextButton(onClick = open, modifier = Modifier.heightIn(min = 48.dp).testTag("banner:open")) {
                        Text(stringResource(R.string.banner_open))
                    }
                }
                IconButton(onClick = { dismiss() }, modifier = Modifier.testTag("banner:dismiss")) {
                    Icon(Icons.Default.Close, dismissLabel, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
