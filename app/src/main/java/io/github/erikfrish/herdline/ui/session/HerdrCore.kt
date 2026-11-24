package io.github.erikfrish.herdline.ui.session

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.BAD_REPLY
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.REQUEST_FAILED
import io.github.erikfrish.herdline.core.ReasonCode
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.isChatAgent
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.GroupShape
import io.github.erikfrish.herdline.ui.glass.IconBadge

/** The localized words for a frozen native reason [code], or null for free text, which is shown as it came. */
@StringRes
internal fun coreReasonRes(code: String): Int? = when (code) {
    ReasonCode.TAKES_SIZE -> R.string.hc_reason_takes_size
    ReasonCode.CORE_MANAGED -> R.string.hc_reason_core_managed
    ReasonCode.IDENTITY_UNKNOWN -> R.string.hc_reason_identity_unknown
    ReasonCode.MOD_PANEL -> R.string.hc_reason_mod_panel
    ReasonCode.TERMINAL_LOST -> R.string.hc_reason_terminal_lost
    ReasonCode.UNSUPPORTED_BACKEND -> R.string.hc_reason_unsupported_backend
    Connection.State.Disconnected.INVALID_SESSION -> R.string.hc_reason_invalid_session
    Connection.State.Disconnected.CORE_RESTARTED -> R.string.hc_reason_core_restarted
    "conflict" -> R.string.hc_reason_conflict
    "uncertain" -> R.string.hc_reason_uncertain
    "unconfirmed" -> R.string.hc_reason_unconfirmed
    REQUEST_FAILED -> R.string.hc_reason_request_failed
    BAD_REPLY -> R.string.hc_reason_bad_reply
    else -> null
}

/** [code] in the user's language where it is a frozen code, else as native or the server said it. */
internal fun coreReasonText(context: Context, code: String): String = coreReasonRes(code)?.let(context::getString) ?: code

/** The composable form of [coreReasonText]. */
@Composable
internal fun coreReasonText(code: String): String = coreReasonRes(code)?.let { stringResource(it) } ?: code

/**
 * Whether Chat can read [pane] in [view]: Herdr names a Chat program there,
 * and herdr-core gives no reason against it ([Pane.chat] null; Herdr panes
 * never carry one). Recomputed from every view, so a hook that just
 * published turns Chat on and a stale one turns it off.
 */
internal fun chatReadable(view: ShellView?, pane: String): Boolean =
    view != null && isChatAgent(view.agentIn(pane)?.agent) && view.panes.firstOrNull { it.id == pane }?.chat == null

/**
 * Over a herdr-core pane that has no terminal to show ([Pane.unsupported]):
 * why, in place of a blank screen. It covers the terminal, takes its taps,
 * and changes no grid.
 */
@Composable
internal fun BoxScope.UnsupportedPaneCard(reason: String, accent: Color) {
    Box(
        Modifier
            .matchParentSize()
            .background(MaterialTheme.colorScheme.surface)
            .clickable(remember { MutableInteractionSource() }, indication = null) {}
            .padding(20.dp)
            .testTag("session:pane_unsupported"),
        contentAlignment = Alignment.Center,
    ) {
        CoreCard(
            icon = Icons.Default.Info,
            tone = accent,
            title = stringResource(if (reason == ReasonCode.MOD_PANEL) R.string.hc_mod_panel_title else R.string.hc_terminal_lost_title),
            body = coreReasonText(reason),
        )
    }
}

/**
 * Why Chat is off for this herdr-core agent pane.
 */
@Composable
internal fun BoxScope.IdentityCard(reason: String, accent: Color, omp: Boolean, onTerminal: () -> Unit, onClose: () -> Unit) {
    val hook = reason == ReasonCode.IDENTITY_UNKNOWN
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .padding(12.dp)
            .testTag("session:identity_card"),
    ) {
        CoreCard(
            icon = Icons.Default.Warning,
            tone = accent,
            title = stringResource(if (hook) R.string.hc_identity_title else R.string.hc_chat_off_title),
            body = coreReasonText(reason),
        ) {
            if (hook && omp) Text(stringResource(R.string.hc_identity_launch_hint), style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(stringResource(R.string.hc_identity_close), onClick = onClose, modifier = Modifier.weight(1f))
                GlassButton(
                    stringResource(R.string.hc_identity_terminal),
                    onClick = onTerminal,
                    primary = true,
                    accent = accent,
                    modifier = Modifier.weight(1.4f).testTag("session:identity_terminal"),
                )
            }
        }
    }
}

@Composable
private fun CoreCard(icon: androidx.compose.ui.graphics.vector.ImageVector, tone: Color, title: String, body: String, more: @Composable () -> Unit = {}) {
    Column(
        Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .shadow(1.dp, GroupShape)
            .clip(GroupShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, GroupShape)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            IconBadge(icon, tone, Modifier.size(40.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        more()
    }
}

