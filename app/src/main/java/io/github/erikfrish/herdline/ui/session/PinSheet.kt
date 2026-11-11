package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.service.LiveCard
import io.github.erikfrish.herdline.service.LiveRow
import io.github.erikfrish.herdline.service.ResourceLiveTexts
import io.github.erikfrish.herdline.service.glyphIcon
import io.github.erikfrish.herdline.service.liveFields
import io.github.erikfrish.herdline.service.pinPreset
import io.github.erikfrish.herdline.ui.glass.menuColor

/**
 * Pins one agent pane as a live update. The user picks
 * what the locked screen may show; nothing is saved until Pin, and Pin is the
 * only writer of the live level. Preselected: the live level chosen before,
 * else the notification lock detail the user saved, else State only, which is
 * then marked as suggested.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PinSheet(
    live: LockDetail?,
    saved: LockDetail?,
    nodeId: String,
    node: String,
    pane: String,
    agent: String,
    onPin: (LockDetail) -> Unit,
    onDismiss: () -> Unit,
) {
    var level by rememberSaveable { mutableStateOf(pinPreset(live, saved)) }
    val suggested = live == null && saved == null
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = menuColor(),
        modifier = Modifier.testTag("pin:sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.pin_title), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.selectableGroup()) {
                LockDetail.entries.forEach { option ->
                    LevelRow(option, selected = option == level, suggested = suggested && option == LockDetail.StateOnly) { level = option }
                }
            }
            Spacer(Modifier.size(12.dp))
            Preview(level, nodeId, node, pane, agent)
            Spacer(Modifier.size(12.dp))
            Text(
                stringResource(R.string.pin_disclosure),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("pin:cancel")) {
                    Text(stringResource(android.R.string.cancel))
                }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onPin(level) }, modifier = Modifier.heightIn(min = 48.dp).testTag("pin:confirm")) {
                    Text(stringResource(R.string.pin_confirm))
                }
            }
        }
    }
}

@Composable
private fun LevelRow(level: LockDetail, selected: Boolean, suggested: Boolean, onSelect: () -> Unit) {
    val (label, help) = when (level) {
        LockDetail.Hidden -> R.string.pin_level_hidden to R.string.pin_level_hidden_help
        LockDetail.StateOnly -> R.string.pin_level_state to R.string.pin_level_state_help
        LockDetail.NamesOnly -> R.string.pin_level_names to R.string.pin_level_names_help
        LockDetail.Full -> R.string.pin_level_full to R.string.pin_level_full_help
    }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp)
            .testTag("pin:level:${level.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The row is the target; the button only draws the state.
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(label), style = MaterialTheme.typography.bodyLarge)
                if (suggested) {
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.pin_suggested), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Text(stringResource(help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** What the locked screen would show for this agent while it works, built by the notification's own mapping. */
@Composable
private fun Preview(level: LockDetail, nodeId: String, node: String, pane: String, agent: String) {
    val context = LocalContext.current
    val face = remember(level, nodeId, node, pane, agent) {
        val now = System.currentTimeMillis()
        val card = LiveCard(listOf(LiveRow(nodeId, node, pane, agent, null, AgentStatus.Working, now - PREVIEW_ELAPSED_MS)), null)
        liveFields(ResourceLiveTexts(context), card, level, locked = true, now = now) { "" }?.main
    } ?: return
    Text(stringResource(R.string.pin_preview), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.size(6.dp))
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = face.spoken },
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(glyphIcon(face.glyph)), null, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(face.title, style = MaterialTheme.typography.bodyMedium)
                face.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // The status-bar chip: its text, or the minutes the chronometer would show.
            val chip = face.chip ?: face.since?.let { stringResource(R.string.pin_preview_chip) }
            if (!chip.isNullOrEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(chip, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

/** The preview's made-up run: long enough to show "N min". */
private const val PREVIEW_ELAPSED_MS = 4 * 60_000L
