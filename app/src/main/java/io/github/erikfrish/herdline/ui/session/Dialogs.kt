/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.AgentLauncher
import io.github.erikfrish.herdline.core.CreatedWorkspace
import io.github.erikfrish.herdline.core.WorkspaceCreate
import io.github.erikfrish.herdline.core.WorkspaceCreateError
import io.github.erikfrish.herdline.core.WorkspaceCreateException
import io.github.erikfrish.herdline.core.createTab
import io.github.erikfrish.herdline.core.createWorkspace
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
        is Prompt.NewTab -> key(connection, prompt.workspaceId) { NewTabDialog(connection, prompt.workspaceId, prompt.kind, onDone, onCreated) }
        is Prompt.NewWorkspace -> key(connection) { NewWorkspaceDialog(connection, prompt.current, onDone, onCreated) }
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

/** The New tab launcher that stages Herdline's Chat extensions and starts OMP with them. */
internal const val HERDLINE_OMP = "herdline-omp"

/** Creates exactly once, then opens the reply's pane before any explicitly selected agent starts; starts on [kind] if offered. */
@Composable
private fun NewTabDialog(
    connection: Connection, workspace: String, kind: String?, onDone: () -> Unit,
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
            .onSuccess { found ->
                launchers = found
                if (selected == null && !attempted && found.any { it.kind == kind }) selected = kind
            }
            .onFailure { discoveryError = it.message }
        discovering = false
    }
    @Composable
    fun launcherLabel(launcher: AgentLauncher): String = if (launcher.kind == HERDLINE_OMP) {
        stringResource(R.string.new_tab_omp_chat)
    } else launcher.label
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
                        Text(launchers.firstOrNull { it.kind == selected }?.let { launcherLabel(it) } ?: stringResource(R.string.new_tab_shell))
                    }
                    GlassMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_tab_shell)) },
                            onClick = { selected = null; choosing = false },
                            modifier = Modifier.testTag("new_tab:shell"),
                        )
                        launchers.forEach { launcher ->
                            DropdownMenuItem(
                                text = { Text(launcherLabel(launcher)) },
                                onClick = { selected = launcher.kind; choosing = false },
                                modifier = Modifier.testTag("new_tab:kind:${launcher.kind}"),
                            )
                        }
                    }
                }
                Text(
                    stringResource(if (selected == HERDLINE_OMP) R.string.new_tab_omp_chat_help else R.string.new_tab_program_help),
                    style = MaterialTheme.typography.bodySmall,
                )
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

/** One environment row of the New workspace form, as typed. */
internal data class EnvRow(val key: String = "", val value: String = "")

/**
 * What the New workspace form sends: name and folder exactly as typed, or
 * omitted when blank so the daemon picks its own; rows left wholly empty
 * are dropped. Null when two rows name one key, since a map would keep
 * only one of them without a word.
 */
internal fun workspaceRequest(name: String, folder: String, source: String?, focus: Boolean, env: List<EnvRow>): WorkspaceCreate? {
    val rows = env.filterNot { it.key.isEmpty() && it.value.isEmpty() }
    val vars = rows.associate { it.key to it.value }
    if (vars.size != rows.size) return null
    return WorkspaceCreate(
        label = name.takeIf { it.isNotBlank() },
        cwd = folder.takeIf { it.isNotBlank() },
        sourceWorkspaceId = source,
        focus = focus,
        env = vars,
    )
}

/** Where one New workspace form stands; only the user's tap moves it to [Creating]. */
internal sealed interface CreatePhase {
    data object Editing : CreatePhase
    data object Creating : CreatePhase

    /** Definitely not created: the form keeps its values and may be sent again by hand. */
    data class Refused(val code: String, val message: String?) : CreatePhase

    /** It may or may not exist: nothing resends it; the user checks the list. */
    data object Unknown : CreatePhase
    data class Created(val workspace: CreatedWorkspace) : CreatePhase
}

/** The phase one tap of Create leads to, or null when that tap must do nothing (in flight, unknown, done). */
internal fun CreatePhase.submit(): CreatePhase? =
    if (this is CreatePhase.Editing || this is CreatePhase.Refused) CreatePhase.Creating else null

/** A reply as the form shows it: only a definite refusal lets the user send again. */
internal fun createdPhase(result: Result<CreatedWorkspace>): CreatePhase = result.fold(
    onSuccess = { CreatePhase.Created(it) },
    onFailure = { failure ->
        when (val error = (failure as? WorkspaceCreateException)?.error) {
            is WorkspaceCreateError.Refused -> CreatePhase.Refused(error.code, error.message)
            WorkspaceCreateError.NotReady -> CreatePhase.Refused("not_ready", null)
            WorkspaceCreateError.NotConnected -> CreatePhase.Refused("not_connected", null)
            // Answered without its ids, cut off, or an unknown failure: it may exist.
            is WorkspaceCreateError.OutcomeUnknown, null -> CreatePhase.Unknown
        }
    },
)

/** Whether a reply opens the new workspace at once: only when the user asked for focus. */
internal fun opensAtOnce(phase: CreatePhase, focus: Boolean) = focus && phase is CreatePhase.Created

/**
 * Asks for a workspace's name, folder, source, focus and environment, and
 * creates it at most once per tap; an uncertain outcome is never resent.
 * With focus it opens the reply's root tab; without, it offers Open.
 */
@Composable
private fun NewWorkspaceDialog(
    connection: Connection, current: String?, onDone: () -> Unit,
    onCreated: (Connection.CreatedTab, String?) -> Unit,
) {
    val view by connection.view.collectAsState()
    val workspaces = view?.workspaces.orEmpty()
    val suffixes = remember(workspaces) { workspaceSuffixes(workspaces) }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    // Null is the daemon's own choice; the current workspace is offered first.
    var source by remember { mutableStateOf<String?>(null) }
    var choosing by remember { mutableStateOf(false) }
    var focus by remember { mutableStateOf(true) }
    var advanced by remember { mutableStateOf(false) }
    var env by remember { mutableStateOf(emptyList<EnvRow>()) }
    var phase by remember { mutableStateOf<CreatePhase>(CreatePhase.Editing) }
    val scope = rememberCoroutineScope()
    val request = workspaceRequest(name, folder, source, focus, env)
    val editable = phase.submit() != null
    val submit: () -> Unit = submit@{
        val sent = request ?: return@submit
        // Set before the coroutine starts: a second tap or IME Done finds it in flight.
        phase = phase.submit() ?: return@submit
        scope.launch {
            val next = createdPhase(connection.createWorkspace(sent))
            if (next is CreatePhase.Created && opensAtOnce(next, sent.focus)) {
                onCreated(next.workspace.rootTab(), null)
                onDone()
            } else {
                phase = next
            }
        }
    }
    val sourceName = when (val picked = source) {
        null -> stringResource(R.string.new_workspace_source_default)
        current -> stringResource(R.string.new_workspace_source_current)
        else -> workspaces.firstOrNull { it.id == picked }?.let { workspaceName(it, suffixes[it.id]) } ?: picked
    }
    GlassAlertDialog(
        onDismissRequest = { if (phase != CreatePhase.Creating) onDone() },
        title = { Text(stringResource(R.string.new_workspace)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                GlassField(
                    name, { name = it },
                    enabled = editable,
                    label = { Text(stringResource(R.string.tab_name)) },
                    placeholder = { Text(stringResource(R.string.new_workspace_name_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().testTag("new_workspace:name"),
                )
                GlassField(
                    folder, { folder = it },
                    enabled = editable,
                    label = { Text(stringResource(R.string.new_workspace_folder)) },
                    placeholder = { Text(stringResource(R.string.new_workspace_folder_hint)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth().testTag("new_workspace:folder"),
                )
                Text(stringResource(R.string.new_workspace_source), style = MaterialTheme.typography.labelLarge)
                Box {
                    TextButton(
                        onClick = { choosing = true }, enabled = editable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("new_workspace:source"),
                    ) { Text(sourceName) }
                    GlassMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.new_workspace_source_default)) },
                            onClick = { source = null; choosing = false },
                            modifier = Modifier.testTag("new_workspace:source:default"),
                        )
                        if (current != null && workspaces.any { it.id == current }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.new_workspace_source_current)) },
                                onClick = { source = current; choosing = false },
                                modifier = Modifier.testTag("new_workspace:source:current"),
                            )
                        }
                        workspaces.filter { it.id != current }.forEach { workspace ->
                            DropdownMenuItem(
                                text = { Text(workspaceName(workspace, suffixes[workspace.id])) },
                                onClick = { source = workspace.id; choosing = false },
                                modifier = Modifier.testTag("new_workspace:source:${workspace.id}"),
                            )
                        }
                    }
                }
                Text(
                    stringResource(if (folder.isNotBlank()) R.string.new_workspace_folder_wins else R.string.new_workspace_source_help),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.testTag("new_workspace:source_help"),
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(focus, enabled = editable, role = Role.Switch) { focus = it }
                        .testTag("new_workspace:focus"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.new_workspace_focus), Modifier.weight(1f))
                    Switch(checked = focus, onCheckedChange = null, enabled = editable)
                }
                TextButton(
                    onClick = { advanced = !advanced },
                    modifier = Modifier.heightIn(min = 48.dp).testTag("new_workspace:advanced"),
                ) { Text(stringResource(if (advanced) R.string.new_workspace_advanced_hide else R.string.new_workspace_advanced)) }
                if (advanced) {
                    Text(stringResource(R.string.new_workspace_env_help), style = MaterialTheme.typography.bodySmall)
                    env.forEachIndexed { i, row ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            GlassField(
                                row.key, { k -> env = env.toMutableList().also { it[i] = row.copy(key = k) } },
                                enabled = editable,
                                label = { Text(stringResource(R.string.new_workspace_env_key)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = Modifier.weight(1f).testTag("new_workspace:env:$i:key"),
                            )
                            GlassField(
                                row.value, { v -> env = env.toMutableList().also { it[i] = row.copy(value = v) } },
                                enabled = editable,
                                label = { Text(stringResource(R.string.new_workspace_env_value)) },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
                                modifier = Modifier.weight(1f).testTag("new_workspace:env:$i:value"),
                            )
                            IconButton(
                                onClick = { env = env.filterIndexed { j, _ -> j != i } }, enabled = editable,
                                modifier = Modifier.testTag("new_workspace:env:$i:remove"),
                            ) { Icon(Icons.Default.Close, stringResource(R.string.new_workspace_env_remove)) }
                        }
                    }
                    TextButton(
                        onClick = { env = env + EnvRow() }, enabled = editable,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("new_workspace:env:add"),
                    ) { Text(stringResource(R.string.new_workspace_env_add)) }
                    if (request == null) {
                        Text(stringResource(R.string.new_workspace_env_duplicate), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("new_workspace:env:duplicate"))
                    }
                }
                when (val shown = phase) {
                    CreatePhase.Editing -> Unit
                    CreatePhase.Creating -> Text(stringResource(R.string.new_workspace_creating), modifier = Modifier.testTag("new_workspace:creating"))
                    is CreatePhase.Refused -> Text(
                        shown.message?.takeIf { it.isNotEmpty() }
                            ?.let { stringResource(R.string.new_workspace_refused, shown.code, it) }
                            ?: stringResource(R.string.new_workspace_refused_code, shown.code),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("new_workspace:error"),
                    )
                    CreatePhase.Unknown -> Text(
                        stringResource(R.string.new_workspace_unknown),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("new_workspace:unknown"),
                    )
                    is CreatePhase.Created -> Text(
                        stringResource(R.string.new_workspace_created, shown.workspace.label.ifEmpty { shown.workspace.workspace }),
                        modifier = Modifier.testTag("new_workspace:created"),
                    )
                }
            }
        },
        confirmButton = {
            when (val shown = phase) {
                is CreatePhase.Created -> TextButton(
                    onClick = { onCreated(shown.workspace.rootTab(), null); onDone() },
                    modifier = Modifier.testTag("new_workspace:open"),
                ) { Text(stringResource(R.string.new_workspace_open)) }
                // An unknown outcome offers only Close: Create would be a retry.
                CreatePhase.Unknown -> TextButton(onClick = onDone, modifier = Modifier.testTag("new_workspace:close")) {
                    Text(stringResource(R.string.close))
                }
                else -> TextButton(
                    onClick = submit, enabled = editable && request != null,
                    modifier = Modifier.testTag("new_workspace:create"),
                ) { Text(stringResource(R.string.create)) }
            }
        },
        dismissButton = {
            when (phase) {
                CreatePhase.Unknown -> Unit
                is CreatePhase.Created -> TextButton(onClick = onDone) { Text(stringResource(R.string.close)) }
                else -> TextButton(onClick = onDone, enabled = phase != CreatePhase.Creating) { Text(stringResource(android.R.string.cancel)) }
            }
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
