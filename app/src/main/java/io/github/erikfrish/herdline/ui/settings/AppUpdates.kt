package io.github.erikfrish.herdline.ui.settings

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.data.ManifestFault
import io.github.erikfrish.herdline.data.Mismatch
import io.github.erikfrish.herdline.data.QaState
import io.github.erikfrish.herdline.data.Release
import io.github.erikfrish.herdline.data.ReleaseChannel
import io.github.erikfrish.herdline.data.ReleaseCheck
import io.github.erikfrish.herdline.data.ReleaseInstall
import io.github.erikfrish.herdline.data.StopReason
import io.github.erikfrish.herdline.data.UpdateChecker
import io.github.erikfrish.herdline.ui.components.Group
import io.github.erikfrish.herdline.ui.components.ItemRow
import io.github.erikfrish.herdline.ui.components.SectionHeader
import io.github.erikfrish.herdline.ui.glass.GlassAlertDialog
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.GlassDivider
import io.github.erikfrish.herdline.ui.glass.GlassScreen
import io.github.erikfrish.herdline.ui.theme.LocalThemeTokens
import kotlinx.coroutines.launch

/*
 * Settings › About › App updates: Herdline's GitHub release channel. Every
 * action is a tap: the page never downloads or installs on its own, and
 * Android's installer asks again before anything is replaced.
 */

/** The About row's one-line state, or null before the first check. */
@Composable
internal fun updatesRowDetail(updates: UpdateChecker): String? {
    val check by updates.check.collectAsState()
    val install by updates.install.collectAsState()
    val updated by updates.justUpdated.collectAsState()
    val refused by updates.refused.collectAsState()
    return when (val i = install) {
        is ReleaseInstall.Downloading -> stringResource(R.string.updates_downloading_row, percent(i.read, i.total))
        is ReleaseInstall.Verifying -> stringResource(R.string.updates_verifying)
        is ReleaseInstall.Mismatched -> stringResource(if (i.reason == Mismatch.Signer) R.string.updates_signer_row else R.string.updates_mismatch_row)
        is ReleaseInstall.Stopped -> stringResource(R.string.updates_stopped_row)
        is ReleaseInstall.NeedsPermission -> stringResource(R.string.updates_permission_row)
        is ReleaseInstall.Handed -> stringResource(R.string.updates_waiting_row)
        is ReleaseInstall.NotInstalled -> stringResource(R.string.updates_not_installed)
        ReleaseInstall.None -> updated?.let { stringResource(R.string.updates_updated_row, it) } ?: when (val c = check) {
            ReleaseCheck.Idle -> null
            ReleaseCheck.Checking -> stringResource(R.string.update_checking)
            ReleaseCheck.Offline -> stringResource(R.string.updates_offline_row)
            ReleaseCheck.Failed -> stringResource(R.string.updates_failed_row)
            ReleaseCheck.Unavailable -> stringResource(R.string.updates_unavailable)
            is ReleaseCheck.Refused -> stringResource(R.string.updates_refused_row)
            is ReleaseCheck.Checked -> {
                val installed = updates.installedVersion()
                val stable = c.manifest.stable
                val candidate = c.manifest.candidate
                when {
                    stable != null && stable.installable(installed, sdk(), refused) -> stringResource(R.string.updates_stable_available, stable.versionName)
                    stable != null && stable.versionCode < installed -> stringResource(R.string.updates_ahead)
                    candidate != null && candidate.installable(installed, sdk(), refused) -> stringResource(R.string.updates_no_stable_candidate_row)
                    else -> stringResource(R.string.updates_on_stable)
                }
            }
        }
    }
}

private fun percent(read: Long, total: Long): Int = if (total <= 0) 0 else (read * 100 / total).toInt().coerceIn(0, 100)

private fun sdk() = android.os.Build.VERSION.SDK_INT

private fun checkedAt(context: android.content.Context, millis: Long): String =
    DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_ALL)

/** The installed build, the stable and candidate cards, and Check now. */
@Composable
internal fun AppUpdatesScreen(app: HerdlineApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val updates = app.updates
    val context = LocalContext.current
    val check by updates.check.collectAsState()
    val install by updates.install.collectAsState()
    val updated by updates.justUpdated.collectAsState()
    val refused by updates.refused.collectAsState()
    val language = context.resources.configuration.locales[0].language
    val installed = updates.installedVersion()
    val versionName = remember { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty() }
    val manifest = (check as? ReleaseCheck.Checked)?.manifest
    var confirm by remember { mutableStateOf<Release?>(null) }
    var permissionDismissed by rememberSaveable { mutableStateOf(false) }
    val busy = install is ReleaseInstall.Downloading || install is ReleaseInstall.Verifying
    val status = installStatus(install)
    val download = { release: Release -> permissionDismissed = false; updates.download(release, app.scope) }

    BackHandler(onBack = onBack)
    // "Updated to X" shows once: leaving the page settles it.
    DisposableEffect(updates) { onDispose { updates.acknowledgeUpdate() } }
    // Back from Android's install-sources page: hand on if allowed, else stay (no loop).
    LifecycleResumeEffect(updates) {
        updates.permissionReturned(app.scope)
        onPauseOrDispose {}
    }

    GlassScreen(title = stringResource(R.string.app_updates), onBack = onBack, modifier = modifier) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.testTag("app_updates")) {
            updated?.let { name ->
                item(key = "updated") {
                    Group {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
                            Text(stringResource(R.string.updates_updated_row, name), style = MaterialTheme.typography.titleMedium)
                            manifest?.entry(installed)?.let { entry ->
                                Text(
                                    stringResource(R.string.updates_what_changed, name),
                                    style = MaterialTheme.typography.labelLarge,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                                Text(entry.notesFor(language), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
            item(key = "phone") {
                Group {
                    ItemRow(
                        title = stringResource(R.string.updates_this_phone),
                        detail = stringResource(R.string.updates_version, versionName, installed),
                        trailing = { QaLabel(manifest?.entry(installed)) },
                    )
                }
            }
            status?.let { shown ->
                item(key = "status") {
                    StatusCard(shown, install, updates, app, download, onOpenSettings = {
                        openInstallSources(context)
                    })
                }
            }
            when (val c = check) {
                ReleaseCheck.Offline -> item(key = "offline") { Note(stringResource(R.string.updates_offline_body)) }
                ReleaseCheck.Failed -> item(key = "failed") { Note(stringResource(R.string.updates_failed_body)) }
                ReleaseCheck.Unavailable -> item(key = "unavailable") { Note(stringResource(R.string.updates_unavailable)) }
                is ReleaseCheck.Refused -> item(key = "refused") {
                    // The list is at fault, not this build: only a Package or Signer fault refuses.
                    Note(stringResource(if (c.fault == ManifestFault.Signer) R.string.updates_refused_signer else R.string.updates_refused_package))
                }
                ReleaseCheck.Idle -> item(key = "idle") { Note(stringResource(R.string.updates_not_checked)) }
                ReleaseCheck.Checking -> Unit
                is ReleaseCheck.Checked -> {
                    val stable = c.manifest.stable
                    item(key = "stable_header") { SectionHeader(stringResource(R.string.updates_stable_title)) }
                    item(key = "stable") {
                        Group {
                            when {
                                stable == null -> Note(stringResource(R.string.updates_no_stable_newer))
                                else -> ReleaseCard(stable, language, collapsed = false) {
                                    when {
                                        stable.installable(installed, sdk(), refused) -> GlassButton(
                                            stringResource(R.string.updates_download_install),
                                            onClick = { download(stable) },
                                            primary = true,
                                            enabled = !busy,
                                            modifier = Modifier.testTag("updates:install_stable"),
                                        )
                                        stable.versionCode < installed -> Text(
                                            stringResource(R.string.updates_ahead_body, versionName, stable.versionName),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        stable.versionCode == installed -> Text(stringResource(R.string.updates_on_stable), style = MaterialTheme.typography.bodyMedium)
                                        // Its signer was refused: no action, a retry can't fix it.
                                        stable.sha256 in refused -> Text(stringResource(R.string.updates_signer_row), style = MaterialTheme.typography.bodyMedium)
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                    // A test build is listed when it is newer than stable, and never pre-selected.
                    c.manifest.candidate?.takeIf { it.versionCode > (stable?.versionCode ?: 0) }?.let { candidate ->
                        item(key = "candidate_header") { SectionHeader(stringResource(R.string.updates_candidate_title)) }
                        item(key = "candidate") {
                            Group {
                                ReleaseCard(candidate, language, collapsed = true) {
                                    when {
                                        candidate.qa == QaState.Failed -> Text(
                                            stringResource(R.string.updates_not_offered),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        candidate.installable(installed, sdk(), refused) -> GlassButton(
                                            stringResource(R.string.updates_install_test),
                                            onClick = { confirm = candidate },
                                            enabled = !busy,
                                            modifier = Modifier.testTag("updates:install_candidate"),
                                        )
                                        candidate.versionCode > installed && candidate.sha256 in refused -> Text(
                                            stringResource(R.string.updates_signer_row),
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        else -> Unit
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item(key = "check") {
                Group(Modifier.padding(top = 12.dp)) {
                    ItemRow(
                        title = when (val c = check) {
                            is ReleaseCheck.Checked -> stringResource(R.string.updates_last_checked, checkedAt(context, c.at))
                            // A check that ran and found nothing usable never reads as "not checked".
                            ReleaseCheck.Failed, ReleaseCheck.Offline, is ReleaseCheck.Refused -> updates.lastGoodCheck().takeIf { it > 0 }
                                ?.let { stringResource(R.string.updates_last_check_failed_since, checkedAt(context, it)) }
                                ?: stringResource(R.string.updates_last_check_failed)
                            ReleaseCheck.Idle, ReleaseCheck.Checking, ReleaseCheck.Unavailable -> stringResource(R.string.updates_not_checked)
                        },
                        detail = stringResource(R.string.updates_source),
                        detailMaxLines = Int.MAX_VALUE,
                        trailing = {
                            if (check == ReleaseCheck.Checking) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            } else {
                                GlassButton(
                                    stringResource(R.string.updates_check_now),
                                    // The app's scope, so leaving the page does not cancel the check midway.
                                    onClick = { app.scope.launch { updates.check(force = true) } },
                                    enabled = check.canCheck,
                                    modifier = Modifier.testTag("updates:check"),
                                )
                            }
                        },
                    )
                }
            }
        }
    }

    confirm?.let { candidate ->
        GlassAlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(stringResource(R.string.updates_candidate_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.updates_candidate_confirm_body,
                        candidate.versionName,
                        stringResource(if (candidate.qa == QaState.Passed) R.string.candidate_passed else R.string.candidate_untested),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = { confirm = null; download(candidate) }) { Text(stringResource(R.string.updates_install_test)) }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    if (install is ReleaseInstall.NeedsPermission && !permissionDismissed) {
        GlassAlertDialog(
            onDismissRequest = { permissionDismissed = true },
            title = { Text(stringResource(R.string.updates_permission_title)) },
            text = { Text(stringResource(R.string.updates_permission_body)) },
            confirmButton = {
                TextButton(onClick = { permissionDismissed = true; openInstallSources(context) }) { Text(stringResource(R.string.updates_open_settings)) }
            },
            dismissButton = { TextButton(onClick = { permissionDismissed = true }) { Text(stringResource(R.string.updates_not_now)) } },
        )
    }
}

/** Android's per-app "Install unknown apps" page for this package. */
private fun openInstallSources(context: android.content.Context) {
    val intent = Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No such page on this device: the state stays "Allow installs to continue".
    }
}

/** The download's own line on the page: a title and a body, or null when there is none. */
private data class Status(val title: Int, val body: String)

@Composable
private fun installStatus(install: ReleaseInstall): Status? = when (install) {
    ReleaseInstall.None -> null
    is ReleaseInstall.Downloading -> Status(
        R.string.updates_downloading_title,
        stringResource(
            R.string.updates_progress,
            Formatter.formatShortFileSize(LocalContext.current, install.read),
            Formatter.formatShortFileSize(LocalContext.current, install.total),
        ),
    )
    is ReleaseInstall.Verifying -> Status(R.string.updates_verifying, "")
    is ReleaseInstall.Mismatched -> if (install.reason == Mismatch.Signer) {
        Status(R.string.updates_signer_row, stringResource(R.string.updates_signer_body))
    } else {
        Status(R.string.updates_mismatch_row, stringResource(R.string.updates_mismatch_body, stringResource(mismatchWord(install.reason))))
    }
    is ReleaseInstall.Stopped -> Status(R.string.updates_stopped_row, stringResource(R.string.updates_stopped_body, stringResource(stopWord(install.reason))))
    is ReleaseInstall.NeedsPermission -> Status(R.string.updates_permission_row, stringResource(R.string.updates_permission_body))
    is ReleaseInstall.Handed -> Status(R.string.updates_waiting_row, stringResource(R.string.updates_waiting_body))
    is ReleaseInstall.NotInstalled -> Status(R.string.updates_not_installed, stringResource(R.string.updates_installer_failed))
}

private fun mismatchWord(reason: Mismatch) = when (reason) {
    Mismatch.Size -> R.string.updates_reason_size
    Mismatch.Checksum -> R.string.updates_reason_checksum
    Mismatch.Package -> R.string.updates_reason_package
    Mismatch.Version -> R.string.updates_reason_version
    Mismatch.Signer -> R.string.updates_reason_signer
}

private fun stopWord(reason: StopReason) = when (reason) {
    StopReason.Cancelled -> R.string.updates_reason_cancelled
    StopReason.Offline -> R.string.updates_reason_offline
    StopReason.Timeout -> R.string.updates_reason_timeout
    StopReason.Server -> R.string.updates_reason_server
}

@Composable
private fun StatusCard(
    status: Status,
    install: ReleaseInstall,
    updates: UpdateChecker,
    app: HerdlineApp,
    download: (Release) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Group(Modifier.padding(top = 12.dp)) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(status.title), style = MaterialTheme.typography.titleMedium)
            when (install) {
                is ReleaseInstall.Downloading -> LinearProgressIndicator(
                    progress = { if (install.total > 0) install.read.toFloat() / install.total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                is ReleaseInstall.Verifying -> LinearProgressIndicator(Modifier.fillMaxWidth())
                else -> Unit
            }
            if (status.body.isNotEmpty()) Text(status.body, style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                when (install) {
                    is ReleaseInstall.Downloading -> GlassButton(stringResource(android.R.string.cancel), onClick = updates::cancel, modifier = Modifier.testTag("updates:cancel"))
                    // A retry can't fix a signer mismatch; every other mismatch gets one re-download.
                    is ReleaseInstall.Mismatched -> if (install.reason != Mismatch.Signer) {
                        GlassButton(stringResource(R.string.updates_try_again), onClick = { download(install.release) })
                    }
                    is ReleaseInstall.Stopped -> GlassButton(stringResource(R.string.updates_try_again), onClick = { download(install.release) })
                    is ReleaseInstall.NeedsPermission -> GlassButton(stringResource(R.string.updates_open_settings), onClick = onOpenSettings, primary = true)
                    is ReleaseInstall.Handed, is ReleaseInstall.NotInstalled -> GlassButton(
                        stringResource(R.string.updates_install),
                        onClick = { updates.installAgain(app.scope) },
                        primary = true,
                        modifier = Modifier.testTag("updates:install_again"),
                    )
                    ReleaseInstall.None, is ReleaseInstall.Verifying -> Unit
                }
            }
        }
    }
}

/** A release: version and QA state, size and source, its notes, then [action]. */
@Composable
private fun ReleaseCard(release: Release, language: String, collapsed: Boolean, action: @Composable () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable(release.versionCode) { mutableStateOf(!collapsed) }
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.updates_version, release.versionName, release.versionCode),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            QaLabel(release)
        }
        if (release.accepted) Text(stringResource(R.string.qa_accepted), style = MaterialTheme.typography.labelMedium)
        release.qaReason?.takeIf { release.qa == QaState.Failed }?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        release.size?.let {
            Text(
                stringResource(R.string.updates_size_from, Formatter.formatShortFileSize(context, it), ReleaseChannel.RELEASE_HOST),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(stringResource(R.string.updates_whats_new), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        // Plain text only, as the manifest gives it.
        Text(
            release.notesFor(language),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = if (expanded) Int.MAX_VALUE else 3,
            overflow = TextOverflow.Ellipsis,
        )
        if (collapsed) {
            TextButton(onClick = { expanded = !expanded }) {
                Text(stringResource(if (expanded) R.string.updates_less else R.string.updates_more))
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) { action() }
    }
}

/** A QA state as glyph and word, never colour alone; "QA state unknown" without an entry. */
@Composable
private fun QaLabel(release: Release?) {
    val status = LocalThemeTokens.current.statusText
    val (text, color) = when (release?.qa) {
        QaState.Passed -> stringResource(R.string.qa_passed) to status.done
        QaState.NotTested -> stringResource(R.string.qa_not_tested) to status.working
        QaState.Failed -> stringResource(R.string.qa_failed) to MaterialTheme.colorScheme.error
        null -> stringResource(R.string.qa_unknown) to Color.Unspecified
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = color.takeOrElseVariant())
}

@Composable
private fun Color.takeOrElseVariant(): Color = if (this == Color.Unspecified) MaterialTheme.colorScheme.onSurfaceVariant else this

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
    )
}
