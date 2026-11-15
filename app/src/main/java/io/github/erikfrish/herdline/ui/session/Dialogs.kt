/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.AgentLauncher
import io.github.erikfrish.herdline.core.createTab
import io.github.erikfrish.herdline.core.agentLaunchers
import io.github.erikfrish.herdline.ui.components.ConfirmDialog
import io.github.erikfrish.herdline.ui.glass.GlassAlertDialog
import io.github.erikfrish.herdline.ui.glass.GlassField
import io.github.erikfrish.herdline.ui.glass.GlassMenu

/** Renames ask for a label; closing anything Herdr runs, or leaving the host, asks first. */
@Composable
internal fun PromptDialog(
    prompt: Prompt, connection: Connection, onDisconnect: () -> Unit,
    onCreated: (Connection.CreatedTab, String?) -> Unit, onDone: () -> Unit,
) {
    when (prompt) {
        is Prompt.NewTab -> key(connection, prompt.workspaceId) { NewTabDialog(connection, prompt.workspaceId, onDone, onCreated) }
        is Prompt.RenameTab -> RenameDialog(stringResource(R.string.rename_tab), prompt.label, onDone) {
            connection.renameTab(prompt.id, it)
        }
        is Prompt.RenameWorkspace -> RenameDialog(stringResource(R.string.rename_workspace), prompt.label, onDone) {
            connection.renameWorkspace(prompt.id, it)
        }
        is Prompt.CloseTab -> ConfirmDialog(
            title = stringResource(R.string.close_tab_title, prompt.label),
            text = stringResource(R.string.close_tab_text),
            confirm = stringResource(R.string.close),
            onConfirm = { connection.closeTab(prompt.id); onDone() },
            onDismiss = onDone,
        )
        is Prompt.ClosePane -> ConfirmDialog(
            title = stringResource(R.string.close_pane_title),
            text = stringResource(R.string.close_pane_text),
            confirm = stringResource(R.string.close),
            onConfirm = { connection.closePane(prompt.id); onDone() },
            onDismiss = onDone,
        )
        is Prompt.CloseWorkspace -> ConfirmDialog(
            title = stringResource(R.string.close_workspace_title, prompt.label),
            text = stringResource(R.string.close_workspace_text),
            confirm = stringResource(R.string.close),
            onConfirm = { connection.closeWorkspace(prompt.id); onDone() },
            onDismiss = onDone,
        )
        is Prompt.Disconnect -> ConfirmDialog(
            title = stringResource(R.string.session_disconnect_title, prompt.host),
            text = stringResource(R.string.session_disconnect_text),
            confirm = stringResource(R.string.disconnect),
            onConfirm = { onDone(); onDisconnect() },
            onDismiss = onDone,
        )
    }
}

/** Creates exactly once, then opens the reply's pane before any explicitly selected agent starts. */
@Composable
private fun NewTabDialog(
    connection: Connection, workspace: String, onDone: () -> Unit,
    onCreated: (Connection.CreatedTab, String?) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    var launchers by remember { mutableStateOf<List<AgentLauncher>>(emptyList()) }
    var selected by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var discovering by remember { mutableStateOf(true) }
    var discoveryError by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    var attempted by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(connection) {
        withContext(Dispatchers.IO) { connection.agentLaunchers() }
            .onSuccess { launchers = it }
            .onFailure { discoveryError = it.message }
        discovering = false
    }
    val create = {
        if (!attempted) {
            attempted = true
            creating = true
            scope.launch {
                connection.createTab(workspace, label).onSuccess { created ->
                    onCreated(created, selected)
                    onDone()
                }.onFailure { failure -> error = failure.message }
                creating = false
            }
        }
    }
    GlassAlertDialog(
        onDismissRequest = { if (!creating) onDone() },
        title = { Text(stringResource(R.string.new_tab)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassField(
                    label, { label = it },
                    enabled = !attempted,
                    label = { Text(stringResource(R.string.tab_name)) },
                    placeholder = { Text(stringResource(R.string.tab_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { create() }),
                    modifier = Modifier.fillMaxWidth().testTag("new_tab:label"),
                )
                Text(stringResource(R.string.new_tab_program), style = MaterialTheme.typography.labelLarge)
                Box {
                    TextButton(onClick = { choosing = true }, enabled = !attempted, modifier = Modifier.testTag("new_tab:program")) {
                        Text(launchers.firstOrNull { it.kind == selected }?.label ?: stringResource(R.string.new_tab_shell))
                    }
                    GlassMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_tab_shell)) },
                            onClick = { selected = null; choosing = false },
                            modifier = Modifier.testTag("new_tab:shell"),
                        )
                        launchers.forEach { launcher ->
                            DropdownMenuItem(
                                text = { Text(launcher.label) },
                                onClick = { selected = launcher.kind; choosing = false },
                                modifier = Modifier.testTag("new_tab:kind:${launcher.kind}"),
                            )
                        }
                    }
                }
                Text(stringResource(R.string.new_tab_program_help), style = MaterialTheme.typography.bodySmall)
                if (discovering) Text(stringResource(R.string.new_tab_discovering), style = MaterialTheme.typography.bodySmall)
                discoveryError?.let { Text(stringResource(R.string.new_tab_discovery_failed, it), color = MaterialTheme.colorScheme.error) }
                if (creating) Text(stringResource(R.string.new_tab_creating), modifier = Modifier.testTag("new_tab:creating"))
                error?.let { Text(stringResource(R.string.new_tab_create_failed, it), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("new_tab:error")) }
            }
        },
        confirmButton = {
            TextButton(onClick = { create() }, enabled = !attempted, modifier = Modifier.testTag("new_tab:create")) {
                Text(stringResource(R.string.create))
            }
        },
        dismissButton = {
            TextButton(onClick = onDone, enabled = !creating) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun RenameDialog(title: String, initial: String, onDone: () -> Unit, onRename: (String) -> Unit) {
    var label by remember { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    GlassAlertDialog(
        onDismissRequest = onDone,
        title = { Text(title) },
        text = {
            GlassField(label, { label = it }, singleLine = true, modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("prompt_field"))
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = { onRename(label.trim()); onDone() }) {
                Text(stringResource(R.string.rename))
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(android.R.string.cancel)) } },
    )
}
