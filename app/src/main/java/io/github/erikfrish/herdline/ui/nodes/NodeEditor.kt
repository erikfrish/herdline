/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.nodes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.data.AuthMethod
import io.github.erikfrish.herdline.data.Keychain
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.ui.components.ColorPicker
import io.github.erikfrish.herdline.ui.components.Group
import io.github.erikfrish.herdline.ui.components.NodeAvatar
import io.github.erikfrish.herdline.ui.components.SectionHeader
import io.github.erikfrish.herdline.ui.glass.GlassAlertDialog
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.GlassField
import io.github.erikfrish.herdline.ui.glass.GlassScreen
import io.github.erikfrish.herdline.ui.glass.GlassSegmented
import io.github.erikfrish.herdline.ui.glass.MenuShape
import io.github.erikfrish.herdline.ui.glass.menuColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Adds or edits a node: where it is, how to sign in, how to reach it, how it looks. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeEditor(app: HerdlineApp, initial: Node?, onDone: () -> Unit) {
    val identities by app.keychain.identities.collectAsState()
    val nodes by app.nodes.nodes.collectAsState()
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var address by rememberSaveable { mutableStateOf(initial?.address ?: "") }
    var port by rememberSaveable { mutableStateOf((initial?.port ?: 22).toString()) }
    var user by rememberSaveable { mutableStateOf(initial?.user ?: "") }
    var session by rememberSaveable { mutableStateOf(initial?.session ?: "default") }
    var auth by rememberSaveable { mutableStateOf(initial?.auth ?: AuthMethod.Key) }
    var identityId by rememberSaveable { mutableStateOf(initial?.identityId) }
    var jumpId by rememberSaveable { mutableStateOf(initial?.jumpId) }
    var group by rememberSaveable { mutableStateOf(initial?.group ?: "") }
    // The colour a new node starts with, fixed so a node added elsewhere does not make this one dirty.
    val startColor = rememberSaveable { initial?.color ?: nodes.size % 8 }
    var color by rememberSaveable { mutableStateOf(startColor) }
    var keepAlive by rememberSaveable { mutableStateOf((initial?.keepAlive ?: 15).toString()) }
    // Blank keeps whatever is saved; the saved password itself is never shown.
    var password by rememberSaveable { mutableStateOf("") }
    var forgetPassword by rememberSaveable { mutableStateOf(false) }
    val hasSaved = initial?.let { app.keychain.hasPassword(it.id) } == true && !forgetPassword

    val portValue = port.toIntOrNull()?.takeIf { it in 1..65535 }
    val keepAliveValue = keepAlive.toIntOrNull()?.takeIf { it in 0..3600 }
    val addressIssue = addressProblem(address, saved = initial?.address)
    val valid = addressIssue == null && Node.validUser(user) && portValue != null &&
        Node.validSession(session) && keepAliveValue != null
    val candidates = app.nodes.jumpCandidates(initial)
    // Anything Back would lose: what the fields say against what was opened.
    val dirty = name != (initial?.name ?: "") || address != (initial?.address ?: "") ||
        port != (initial?.port ?: 22).toString() || user != (initial?.user ?: "") ||
        session != (initial?.session ?: "default") || auth != (initial?.auth ?: AuthMethod.Key) ||
        identityId != initial?.identityId || jumpId != initial?.jumpId || group != (initial?.group ?: "") ||
        color != startColor || keepAlive != (initial?.keepAlive ?: 15).toString() ||
        password.isNotEmpty() || forgetPassword
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (dirty) {
            confirmDiscard = true
        } else {
            onDone()
        }
    }
    // Why Save is off, so a disabled button is never the only answer.
    val blocked = listOfNotNull(
        stringResource(R.string.host_address).takeIf { addressIssue != null },
        stringResource(R.string.host_user).takeUnless { Node.validUser(user) },
        stringResource(R.string.host_port).takeIf { portValue == null },
        stringResource(R.string.host_session).takeUnless { Node.validSession(session) },
        stringResource(R.string.keepalive).takeIf { keepAliveValue == null },
    )
    val blockedText = if (blocked.isEmpty()) null else stringResource(R.string.editor_save_needs, blocked.joinToString(", "))

    fun save() {
        val saved = (initial ?: Node(name = "", address = "", user = "")).copy(
            name = name.trim(),
            address = address.trim(),
            port = portValue ?: 22,
            user = user.trim(),
            session = session.trim(),
            auth = auth,
            identityId = identityId.takeIf { auth == AuthMethod.Key },
            jumpId = jumpId,
            group = group.trim(),
            color = color,
            keepAlive = keepAliveValue ?: 15,
        )
        app.nodes.upsert(saved)
        val secret = password.takeIf { auth == AuthMethod.Password && it.isNotEmpty() }
        val forget = auth != AuthMethod.Password || forgetPassword
        app.scope.launch(Dispatchers.IO) {
            when {
                secret != null -> app.keychain.savePassword(saved.id, secret)
                forget -> app.keychain.forgetPassword(saved.id)
            }
        }
        onDone()
    }

    if (confirmDiscard) {
        GlassAlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.editor_discard_title)) },
            text = { Text(stringResource(R.string.editor_discard_text)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDone() }) {
                    Text(stringResource(R.string.editor_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.editor_keep_editing)) }
            },
        )
    }
    BackHandler(onBack = leave)
    GlassScreen(
        title = stringResource(if (initial == null) R.string.add_node else R.string.edit_node),
        onBack = leave,
        actions = {
            GlassButton(
                stringResource(R.string.save),
                onClick = ::save,
                primary = true,
                enabled = valid,
                icon = Icons.Default.Check,
                // The bottom reason hides under the keyboard; TalkBack hears it on this one too.
                modifier = Modifier
                    .testTag("save")
                    .then(if (blockedText != null) Modifier.semantics { stateDescription = blockedText } else Modifier),
            )
        },
        // Save again within thumb reach (M8); the keyboard covers it, the header's stays.
        bottomBar = {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (blockedText != null) {
                    Text(
                        blockedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .testTag("save:blocked")
                            .semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
                GlassButton(
                    stringResource(R.string.save),
                    onClick = ::save,
                    primary = true,
                    enabled = valid,
                    icon = Icons.Default.Check,
                    modifier = Modifier.fillMaxWidth().testTag("save:bottom"),
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                // Scaffold's bottom padding is the Save bar's measured height,
                // nav bar inset included; 16 dp more so the last field ends
                // fully above it at scroll end, not flush under its edge.
                .padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 16.dp),
        ) {
            Group {
                Row(
                    Modifier.padding(18.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    NodeAvatar(name.ifBlank { address.ifBlank { "?" } }, color, size = 64.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(stringResource(R.string.color_label), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ColorPicker(color) { color = it }
                    }
                }
            }

            SectionHeader(stringResource(R.string.section_connection))
            Fields {
                GlassField(name, { name = it }, label = { Text(stringResource(R.string.node_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("field:name"))
                GlassField(
                    address,
                    { typed ->
                        // Only a paste splits: typing `host:2` on the way to `host:22` must not move the 2.
                        val pasted = splitPastedAddress(typed).takeIf { typed.length > address.length + 1 }
                        if (pasted == null) {
                            address = typed.trim()
                        } else {
                            address = pasted.address
                            pasted.user?.let { user = it }
                            pasted.port?.let { port = it }
                        }
                    },
                    label = { Text(stringResource(R.string.host_address)) },
                    supportingText = when (addressIssue.takeIf { address.isNotEmpty() }) {
                        AddressProblem.Invalid -> { { Text(stringResource(R.string.editor_address_invalid)) } }
                        AddressProblem.PortInAddress -> { { Text(stringResource(R.string.editor_address_port)) } }
                        else -> null
                    },
                    singleLine = true,
                    isError = address.isNotEmpty() && addressIssue != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth().testTag("field:address"),
                )
                portInAddress(address)?.let { split ->
                    // One tap fixes it, for a typed port and for a node saved this way before.
                    TextButton(
                        onClick = {
                            address = split.address
                            split.port?.let { port = it }
                        },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("field:address:split"),
                    ) {
                        Text(stringResource(R.string.editor_move_port, split.port.orEmpty()))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassField(
                        user, { user = it.trim() },
                        label = { Text(stringResource(R.string.host_user)) },
                        supportingText = if (user.isNotEmpty() && !Node.validUser(user)) {
                            { Text(stringResource(R.string.editor_user_invalid)) }
                        } else {
                            null
                        },
                        singleLine = true,
                        isError = user.isNotEmpty() && !Node.validUser(user),
                        modifier = Modifier.weight(2f).testTag("field:user"),
                    )
                    GlassField(
                        // Typed: digits only. A pasted bad port is kept as pasted, so it is named, not lost.
                        port, { port = it.filter(Char::isDigit).take(5) },
                        label = { Text(stringResource(R.string.host_port)) },
                        singleLine = true,
                        isError = portValue == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f).testTag("field:port"),
                    )
                }
                GlassField(
                    session, { session = it.trim() },
                    label = { Text(stringResource(R.string.host_session)) },
                    supportingText = { Text(stringResource(R.string.host_session_help)) },
                    singleLine = true,
                    isError = !Node.validSession(session),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SectionHeader(stringResource(R.string.section_sign_in))
            Fields {
                GlassSegmented(
                    options = AuthMethod.entries.map { stringResource(if (it == AuthMethod.Key) R.string.auth_key else R.string.auth_password) },
                    selected = AuthMethod.entries.indexOf(auth),
                    onSelect = { auth = AuthMethod.entries[it] },
                )
                if (auth == AuthMethod.Key) {
                    Picker(
                        label = stringResource(R.string.identity),
                        selected = identities.firstOrNull { it.id == (identityId ?: Keychain.DEVICE) }?.let { identity ->
                            identity.name + if (identity.certificate != null) " · " + stringResource(R.string.certificate) else ""
                        } ?: stringResource(R.string.device_key_name),
                        options = identities.map { identity ->
                            identity.id to identity.name + "  " + identity.algorithm.removePrefix("ssh-") +
                                if (identity.certificate != null) " · " + stringResource(R.string.certificate) else ""
                        },
                        onSelect = { identityId = it.takeUnless { id -> id == Keychain.DEVICE } },
                    )
                    Hint(stringResource(R.string.auth_key_help))
                } else {
                    GlassField(
                        password, { password = it },
                        label = { Text(stringResource(R.string.auth_password)) },
                        placeholder = { if (hasSaved) Text(stringResource(R.string.password_saved_placeholder)) },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Hint(stringResource(if (hasSaved) R.string.password_saved_help else R.string.password_unsaved_help))
                    if (hasSaved && password.isEmpty()) {
                        GlassButton(stringResource(R.string.forget_password), onClick = { forgetPassword = true }, destructive = true)
                    }
                }
            }

            SectionHeader(stringResource(R.string.section_route))
            Fields {
                val none = stringResource(R.string.jump_none)
                Picker(
                    label = stringResource(R.string.jump_host),
                    selected = nodes.firstOrNull { it.id == jumpId }?.title ?: none,
                    options = listOf<Pair<String?, String>>(null to none) + candidates.map { it.id to "${it.title}  (${it.destination})" },
                    onSelect = { jumpId = it },
                )
                Hint(stringResource(R.string.jump_help))
            }

            SectionHeader(stringResource(R.string.section_more))
            Fields {
                GroupField(
                    group,
                    groups = nodes.map { it.group.trim() }.filter { it.isNotEmpty() }.distinct().sortedBy { it.lowercase() },
                    onChange = { group = it },
                )
                GlassField(
                    keepAlive, { keepAlive = it.filter(Char::isDigit).take(4) },
                    label = { Text(stringResource(R.string.keepalive)) },
                    supportingText = { Text(stringResource(R.string.keepalive_help)) },
                    singleLine = true,
                    isError = keepAliveValue == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Fields(content: @Composable () -> Unit) {
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

@Composable
private fun Hint(text: String) {
    // Indented like a text field's supporting text, so every hint lines up.
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
}

/** A group typed in, picked from the [groups] already in use, or left blank for none. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GroupField(value: String, groups: List<String>, onChange: (String) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && groups.isNotEmpty(), onExpandedChange = { expanded = it }) {
        GlassField(
            value, onChange,
            label = { Text(stringResource(R.string.group)) },
            supportingText = { Text(stringResource(R.string.group_help)) },
            trailingIcon = { if (groups.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = expanded && groups.isNotEmpty(), onDismissRequest = { expanded = false }, shape = MenuShape, containerColor = menuColor()) {
            DropdownMenuItem(text = { Text(stringResource(R.string.no_group)) }, onClick = { onChange(""); expanded = false })
            groups.forEach { group ->
                DropdownMenuItem(text = { Text(group) }, onClick = { onChange(group); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> Picker(label: String, selected: String, options: List<Pair<T, String>>, onSelect: (T) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        GlassField(
            selected, {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, shape = MenuShape, containerColor = menuColor()) {
            options.forEach { (value, text) ->
                DropdownMenuItem(text = { Text(text) }, onClick = { onSelect(value); expanded = false })
            }
        }
    }
}
