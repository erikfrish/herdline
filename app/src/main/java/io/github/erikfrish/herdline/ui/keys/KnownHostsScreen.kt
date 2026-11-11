/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.ui.keys

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.ui.components.ConfirmDialog
import io.github.erikfrish.herdline.ui.components.Group
import io.github.erikfrish.herdline.ui.components.ItemRow
import io.github.erikfrish.herdline.ui.glass.GlassDivider
import io.github.erikfrish.herdline.ui.glass.GlassScreen
import io.github.erikfrish.herdline.ui.glass.IconBadge

/** Host keys the user trusted, by `host:port`; forgetting one re-prompts on the next connection. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsScreen(app: HerdlineApp, onBack: () -> Unit) {
    val known by app.nodes.knownHosts.collectAsState()
    val nodes by app.nodes.nodes.collectAsState()
    var forgetting by remember { mutableStateOf<String?>(null) }
    BackHandler(onBack = onBack)
    GlassScreen(title = stringResource(R.string.known_hosts), onBack = onBack) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.testTag("known_hosts")) {
            item {
                Text(
                    stringResource(if (known.isEmpty()) R.string.known_hosts_empty else R.string.known_hosts_help),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
                )
            }
            if (known.isNotEmpty()) {
                item {
                    Group {
                        known.entries.sortedBy { it.key }.forEachIndexed { index, (host, fingerprint) ->
                            if (index > 0) GlassDivider(start = 66.dp)
                            val names = nodes.filter { it.hostKeyName == host }.joinToString(", ") { it.title }
                            ItemRow(
                                title = names.ifEmpty { host },
                                detail = (if (names.isEmpty()) "" else "$host\n") + fingerprint,
                                leading = { IconBadge(Icons.Default.Lock, MaterialTheme.colorScheme.primary) },
                                trailing = {
                                    IconButton(onClick = { forgetting = host }) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.forget), tint = MaterialTheme.colorScheme.error)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
    forgetting?.let { host ->
        ConfirmDialog(
            title = stringResource(R.string.forget_host_title, host),
            text = stringResource(R.string.forget_host_text),
            confirm = stringResource(R.string.forget),
            onConfirm = { app.nodes.forget(host); forgetting = null },
            onDismiss = { forgetting = null },
        )
    }
}
