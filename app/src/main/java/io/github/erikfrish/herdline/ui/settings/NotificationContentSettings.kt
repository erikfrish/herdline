package io.github.erikfrish.herdline.ui.settings

import android.os.SystemClock
import android.text.format.DateFormat
import android.widget.Chronometer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.NotificationFields
import io.github.erikfrish.herdline.data.Settings
import io.github.erikfrish.herdline.data.SettingsStore
import io.github.erikfrish.herdline.service.AgentNotifications
import io.github.erikfrish.herdline.service.ConnectionService
import io.github.erikfrish.herdline.service.LiveCard
import io.github.erikfrish.herdline.service.LiveRow
import io.github.erikfrish.herdline.service.NotificationCardPreview
import io.github.erikfrish.herdline.service.ResourceLiveTexts
import io.github.erikfrish.herdline.service.ResourceTexts
import io.github.erikfrish.herdline.service.attachedContent
import io.github.erikfrish.herdline.service.liveFields
import io.github.erikfrish.herdline.service.liveLevel
import io.github.erikfrish.herdline.service.attachedTitle
import io.github.erikfrish.herdline.ui.components.Group
import io.github.erikfrish.herdline.ui.components.ItemRow
import io.github.erikfrish.herdline.ui.components.SectionHeader
import io.github.erikfrish.herdline.ui.glass.GlassButton

/** Finite controls and the same projection the foreground service posts. */
internal fun LazyListScope.notificationContentSettings(store: SettingsStore, settings: Settings) {
    item { SectionHeader(stringResource(R.string.notification_content)) }
    item {
        val active by ConnectionService.preview.collectAsState()
        val savedLevel by store.liveLockDetail.collectAsState()
        val level = active?.level ?: liveLevel(savedLevel, settings.lockDetail)
        Group {
            Text(stringResource(R.string.notification_content_help), Modifier.padding(18.dp), style = MaterialTheme.typography.bodySmall)
            val selected = settings.notificationFields
            val ordered = selected + NotificationFields.ALL.filterNot { it in selected }
            ordered.forEach { id ->
                val label = stringResource(fieldLabel(id))
                val allowed = NotificationFields.allowed(id, level)
                val toggle = { store.update { it.copy(notificationFields = NotificationFields.toggle(it.notificationFields, id)) }; Unit }
                val help = when (id) {
                    "agent" -> R.string.notification_requires_names
                    "elapsed" -> R.string.notification_requires_elapsed
                    "state" -> R.string.notification_requires_state
                    else -> R.string.notification_requires_full
                }
                ItemRow(title = label, detail = if (allowed) null else stringResource(help), onClick = if (allowed) toggle else null, trailing = {
                    Row {
                        if (selected.indexOf(id) > 0) {
                            IconButton(enabled = allowed, onClick = { store.update { it.copy(notificationFields = NotificationFields.moveUp(it.notificationFields, id)) } }, modifier = Modifier.testTag("settings:notification:up:$id")) {
                                Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.notification_field_up, label))
                            }
                        }
                        Switch(id in selected, { toggle() }, enabled = allowed, modifier = Modifier.testTag("settings:notification:field:$id"))
                    }
                })
            }
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                NotificationPreview(settings, active, level)
                PromotionDetails()
            }
            GlassButton(stringResource(R.string.notification_content_reset), onClick = { store.update { it.copy(notificationFields = NotificationFields.DEFAULT) } }, modifier = Modifier.padding(14.dp))
        }
    }
}

@Composable
private fun NotificationPreview(settings: Settings, active: NotificationCardPreview?, level: io.github.erikfrish.herdline.data.LockDetail) {
    val context = LocalContext.current
    val now = System.currentTimeMillis()
    val sample = remember {
        // Labelled generic sample only; never personal data or a pretend live agent.
        LiveCard(listOf(LiveRow("preview", "host", "preview:p1", "agent", null, AgentStatus.Working, now - 240_000L, tab = "tab")), null)
    }
    val time = DateFormat.getTimeFormat(context)
    val projected = liveFields(ResourceLiveTexts(context), if (active == null) sample else active.card, level, locked = true, now, settings.notificationFields) { time.format(it) }?.main
    val attached = if (projected == null && active != null) attachedContent(ResourceTexts(context), active.nodes, active.host, level, locked = true, settings.notificationFields, active.agents, active.needYou, active.connection, attachedTitle(context, active.connection)).main else null
    Column(Modifier.fillMaxWidth().testTag("settings:notification:preview")) {
        Text(stringResource(if (active == null) R.string.notification_preview_sample else R.string.notification_preview_actual), style = MaterialTheme.typography.labelMedium)
        Text(projected?.title ?: attached?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
        (projected?.text ?: attached?.text)?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        projected?.since?.let { since ->
            AndroidView(factory = { Chronometer(it) }, update = {
                it.base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - since)
                it.start()
            }, onRelease = { it.stop() })
        }
    }
}

@Composable
private fun PromotionDetails() {
    val context = LocalContext.current
    var details by remember { mutableStateOf(AgentNotifications.promotionDetails(context)) }
    LifecycleResumeEffect(Unit) {
        details = AgentNotifications.promotionDetails(context)
        onPauseOrDispose { }
    }
    val yes = stringResource(R.string.notification_yes)
    val no = stringResource(R.string.notification_no)
    Text(
        if (details.allowed == null) stringResource(R.string.notification_promotion_unavailable) else stringResource(
            R.string.notification_promotion_details,
            if (details.requested) yes else no, if (details.allowed == true) yes else no,
            if (details.eligible == true) yes else no, if (details.shown == true) yes else no,
        ),
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("settings:notification:promotion"),
    )
}

private fun fieldLabel(id: String): Int = when (id) {
    "agent" -> R.string.notification_field_agent
    "tab" -> R.string.notification_field_tab
    "host" -> R.string.notification_field_host
    "state" -> R.string.notification_field_state
    "elapsed" -> R.string.notification_field_elapsed
    else -> R.string.notification_field_counts
}
