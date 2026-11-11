/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.settings

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.AppLanguage
import io.github.erikfrish.herdline.data.AppLanguages
import io.github.erikfrish.herdline.data.ResetScope
import io.github.erikfrish.herdline.data.Settings
import io.github.erikfrish.herdline.data.ThemeMode
import io.github.erikfrish.herdline.ui.components.ConfirmDialog
import io.github.erikfrish.herdline.ui.components.Group
import io.github.erikfrish.herdline.ui.components.ItemRow
import io.github.erikfrish.herdline.ui.components.Pill
import io.github.erikfrish.herdline.ui.components.SectionHeader
import io.github.erikfrish.herdline.ui.glass.GlassAlertDialog
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.GlassDivider
import io.github.erikfrish.herdline.ui.glass.GlassScreen
import io.github.erikfrish.herdline.ui.glass.GlassSegmented
import io.github.erikfrish.herdline.ui.glass.IconBadge
import io.github.erikfrish.herdline.ui.keys.KeysScreen
import io.github.erikfrish.herdline.ui.keys.KnownHostsScreen
import io.github.erikfrish.herdline.ui.terminal.FontChoice
import io.github.erikfrish.herdline.ui.theme.AppPalette
import io.github.erikfrish.herdline.ui.theme.AppPalettes
import io.github.erikfrish.herdline.ui.theme.LocalThemeTokens
import io.github.erikfrish.herdline.ui.theme.TerminalScheme
import io.github.erikfrish.herdline.ui.theme.TerminalSchemes
import io.github.erikfrish.herdline.ui.theme.ThemeTokens
import io.github.erikfrish.herdline.ui.theme.dynamicColor
import io.github.erikfrish.herdline.ui.theme.rememberDynamicTokens
import io.github.erikfrish.herdline.ui.theme.terminalScheme
import io.github.erikfrish.herdline.ui.theme.palette
import kotlinx.coroutines.launch

/** Shown in every font preview: letters that differ between fonts, then Nerd Font icons. */
private const val PREVIEW = "0O1lI {}[] =>     "

/** The pages of Settings, in index order; [reset] is what the page's own reset row restores. */
internal enum class SettingsSection(val title: Int, val summary: Int, val icon: ImageVector, val tint: Color, val reset: ResetScope?) {
    Appearance(R.string.section_appearance, R.string.summary_appearance, Icons.Default.Face, Color(0xFF4A8CF0), ResetScope.Appearance),
    Terminal(R.string.section_terminal, R.string.summary_terminal, Icons.Default.Settings, Color(0xFF8A8F98), ResetScope.Terminal),
    Composer(R.string.section_composer, R.string.summary_composer, Icons.Default.Edit, Color(0xFF2F9E9A), ResetScope.Composer),
    Layout(R.string.section_layout, R.string.summary_layout, Icons.Default.Menu, Color(0xFF9A6CF0), ResetScope.Layout),
    Notifications(R.string.section_notifications, R.string.summary_notifications, Icons.Default.Notifications, Color(0xFFF2A93B), ResetScope.Notifications),
    // Keys and known hosts are data, deleted one by one; About keeps the global reset.
    Security(R.string.section_security, R.string.summary_security, Icons.Default.Lock, Color(0xFF3FB868), null),
    About(R.string.section_about, R.string.summary_about, Icons.Default.Info, Color(0xFF8A8F98), null),
}

/** What a section's reset confirmation lists. */
private fun ResetScope.confirmText(): Int = when (this) {
    ResetScope.Appearance -> R.string.restore_appearance_confirm
    ResetScope.Terminal -> R.string.restore_terminal_confirm
    ResetScope.Composer -> R.string.restore_composer_confirm
    ResetScope.Layout -> R.string.restore_layout_confirm
    ResetScope.Notifications -> R.string.restore_notifications_confirm
}

/** Screens below a section: Security & hosts › Keys or Known hosts, Layout › Controls. */
private enum class ChildPage { Keys, KnownHosts, Controls, Updates }

/** From this window width the section index stays beside the open section. */
private val ListDetailWidth: Dp = 840.dp
private val IndexWidth: Dp = 360.dp

/**
 * Settings with its own stack: index → section → Keys or Known hosts. System
 * Back walks that stack, and [onBack] is called only from the index. A null
 * [onBack] is the root destination: no Up arrow, and Back from the index is
 * left to the app. A non-null [backLabel] means Settings was opened from that
 * session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(app: HerdlineApp, onBack: (() -> Unit)?, backLabel: String? = null) {
    val context = LocalContext.current
    val settings by app.settings.settings.collectAsState()
    val fontChanges by app.fonts.changes.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val progress = remember { mutableStateMapOf<String, Float>() }
    var licenses by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf(false) }
    var restoring by remember { mutableStateOf<SettingsSection?>(null) }
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    var child by rememberSaveable { mutableStateOf<ChildPage?>(null) }
    val updates = app.updates
    val stableDot by updates.stableDot.collectAsState()
    // The Hosts banner's Review update lands on About › App updates.
    val review by updates.review.collectAsState()
    LaunchedEffect(review) {
        if (review) {
            section = SettingsSection.About
            child = ChildPage.Updates
            updates.reviewShown()
        }
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "font.ttf"
        scope.launch {
            runCatching { app.fonts.importCustom(uri, name) }
                .onSuccess { id -> app.settings.update { s -> s.copy(font = id) } }
                .onFailure { snackbar.showSnackbar(context.getString(R.string.font_import_failed)) }
        }
    }

    fun LazyListScope.appearance() {
        item {
            Group {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.theme_mode), style = MaterialTheme.typography.titleMedium)
                    GlassSegmented(
                        options = ThemeMode.entries.map {
                            stringResource(when (it) {
                                ThemeMode.System -> R.string.theme_system
                                ThemeMode.Light -> R.string.theme_light
                                ThemeMode.Dark -> R.string.theme_dark
                            })
                        },
                        selected = ThemeMode.entries.indexOf(settings.themeMode),
                        onSelect = { index -> app.settings.update { it.copy(themeMode = ThemeMode.entries[index]) } },
                    )
                }
                GlassDivider()
                LanguagePicker()
            }
        }
            item { SectionHeader(stringResource(R.string.section_app_theme)) }
            item {
                Column {
                    ThemeRow(settings.appTheme) { id -> app.settings.update { it.copy(appTheme = id) } }
                    // A dark-only theme under Light shows Herdline's light variant; say so.
                    if (settings.palette.darkOnly && !LocalThemeTokens.current.dark) {
                        Text(
                            stringResource(R.string.app_theme_dark_only_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    if (settings.dynamicColor) {
                        Text(
                            stringResource(R.string.app_theme_dynamic_help),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.dark_scheme)) }
            item {
                SchemeRow(TerminalSchemes.all.filter { it.dark }, settings.copy(darkScheme = Settings.MATCH_THEME).terminalScheme(true), settings.darkScheme) { id ->
                    app.settings.update { it.copy(darkScheme = id) }
                }
            }
            item { SectionHeader(stringResource(R.string.light_scheme)) }
            item {
                SchemeRow(TerminalSchemes.all.filter { !it.dark }, settings.copy(lightScheme = Settings.MATCH_THEME).terminalScheme(false), settings.lightScheme) { id ->
                    app.settings.update { it.copy(lightScheme = id) }
                }
            }
        interfaceSettings(app.settings, settings)
        iconSettings(app.settings, settings)
    }

    fun LazyListScope.terminal() {
            item { SectionHeader(stringResource(R.string.section_font)) }
            item {
                val choices = remember(fontChanges) { app.fonts.choices() }
                Group {
                    choices.forEachIndexed { index, choice ->
                        if (index > 0) GlassDivider()
                        FontRow(
                            app = app,
                            choice = choice,
                            selected = settings.font == choice.id,
                            progress = progress[choice.id],
                            changes = fontChanges,
                            onSelect = { app.settings.update { it.copy(font = choice.id) } },
                            onDownload = {
                                val font = choice.catalog ?: return@FontRow
                                progress[choice.id] = 0f
                                scope.launch {
                                    runCatching { app.fonts.download(font) { p -> progress[choice.id] = p } }
                                        .onSuccess { app.settings.update { it.copy(font = choice.id) } }
                                        .onFailure { snackbar.showSnackbar(context.getString(R.string.font_download_failed, it.message.orEmpty())) }
                                    progress.remove(choice.id)
                                }
                            },
                            onDelete = {
                                if (choice.catalog != null) app.fonts.deleteDownloads(choice.catalog) else app.fonts.deleteCustom(choice.id)
                                if (settings.font == choice.id && choice.catalog?.bundled != true) {
                                    app.settings.update { it.copy(font = "jetbrains-mono") }
                                }
                            },
                        )
                    }
                    GlassDivider()
                    Box(Modifier.padding(14.dp)) {
                        GlassButton(
                            stringResource(R.string.import_font),
                            onClick = { fontPicker.launch(arrayOf("font/*", "application/octet-stream", "*/*")) },
                            icon = Icons.Default.Add,
                        )
                    }
                }
            }
            item {
                Group(Modifier.padding(top = 12.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text(stringResource(R.string.font_size, settings.fontSize.toInt()), style = MaterialTheme.typography.titleMedium)
                        Slider(
                            value = settings.fontSize,
                            onValueChange = { size -> app.settings.update { it.copy(fontSize = size.toInt().toFloat()) } },
                            valueRange = 8f..24f,
                            steps = 15,
                        )
                        Text(stringResource(R.string.font_size_help), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }
            item {
                Group {
                    ItemRow(
                        title = stringResource(R.string.vibrate_on_bell),
                        leading = { IconBadge(Icons.Default.Notifications, Color(0xFFF08A3C)) },
                        onClick = { app.settings.update { it.copy(vibrateOnBell = !it.vibrateOnBell) } },
                        trailing = { Switch(settings.vibrateOnBell, { on -> app.settings.update { it.copy(vibrateOnBell = on) } }) },
                    )
                    GlassDivider()
                    ItemRow(
                        title = stringResource(R.string.keep_screen_on),
                        detail = stringResource(R.string.keep_screen_on_help),
                        detailMaxLines = Int.MAX_VALUE,
                        leading = { IconBadge(Icons.Default.Face, Color(0xFF4A8CF0)) },
                        onClick = { app.settings.update { it.copy(keepScreenOn = !it.keepScreenOn) } },
                        trailing = { Switch(settings.keepScreenOn, { on -> app.settings.update { it.copy(keepScreenOn = on) } }) },
                    )
                }
        }
        extraKeySettings(app.settings, settings)
    }

    fun LazyListScope.security() {
        item {
            Group {
                ItemRow(
                    title = stringResource(R.string.keys),
                    detail = stringResource(R.string.security_keys_help),
                    detailMaxLines = Int.MAX_VALUE,
                    leading = { IconBadge(Icons.Default.Lock, Color(0xFF3FB868)) },
                    onClick = { child = ChildPage.Keys },
                    chevron = true,
                )
                GlassDivider()
                ItemRow(
                    title = stringResource(R.string.known_hosts),
                    detail = stringResource(R.string.security_known_help),
                    detailMaxLines = Int.MAX_VALUE,
                    leading = { IconBadge(Icons.Default.Check, Color(0xFF4A8CF0)) },
                    onClick = { child = ChildPage.KnownHosts },
                    chevron = true,
                )
            }
        }
    }

    fun LazyListScope.about() {
            item {
                Group {
                    ItemRow(
                        title = stringResource(R.string.app_name),
                        leading = { IconBadge(Icons.Default.Info, Color(0xFF2F9E9A)) },
                        // Name and versionCode, as the App updates page shows them.
                        detail = context.packageManager.getPackageInfo(context.packageName, 0).let { info ->
                            stringResource(R.string.about_text, stringResource(R.string.updates_version, info.versionName.orEmpty(), info.longVersionCode))
                        },
                        detailMaxLines = Int.MAX_VALUE,
                    )
                    GlassDivider()
                    ItemRow(
                        title = stringResource(R.string.app_updates),
                        leading = { IconBadge(Icons.Default.Refresh, Color(0xFF3FB868)) },
                        detail = updatesRowDetail(updates),
                        detailMaxLines = Int.MAX_VALUE,
                        onClick = { child = ChildPage.Updates },
                        chevron = true,
                        trailing = if (stableDot) {
                            { Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) }
                        } else {
                            null
                        },
                        modifier = Modifier.testTag("about:app_updates"),
                    )
                    GlassDivider()
                    ItemRow(
                        title = stringResource(R.string.licenses),
                        leading = { IconBadge(Icons.AutoMirrored.Filled.List, Color(0xFF8A8F98)) },
                        onClick = { licenses = true },
                        chevron = true,
                    )
                }
        }
        resetSettings(onReset = { reset = true })
    }

    @Composable
    fun Index(wide: Boolean, selected: SettingsSection?, modifier: Modifier = Modifier) {
        GlassScreen(
            title = stringResource(R.string.settings),
            subtitle = backLabel?.let { stringResource(R.string.settings_back_returns, it) },
            onBack = onBack,
            // A root destination's header, as Hosts and Agents; list-detail keeps both bars small.
            large = !wide,
            modifier = modifier,
        ) { padding ->
            LazyColumn(contentPadding = padding, modifier = Modifier.testTag("settings")) {
                item {
                    Group {
                        SettingsSection.entries.forEachIndexed { index, entry ->
                            if (index > 0) GlassDivider()
                            val dot = entry == SettingsSection.About && stableDot
                            val check = wide && entry == selected
                            ItemRow(
                                title = stringResource(entry.title),
                                // The dot is never the only cue: About says so in words.
                                detail = stringResource(if (dot) R.string.about_update_available else entry.summary),
                                leading = { IconBadge(entry.icon, entry.tint) },
                                onClick = { section = entry; child = null },
                                chevron = !wide,
                                trailing = if (dot || check) {
                                    {
                                        if (dot) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer)
                                        if (check) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                    }
                                } else {
                                    null
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun Detail(shown: SettingsSection, wide: Boolean, modifier: Modifier = Modifier) {
        when (child) {
            ChildPage.Keys -> Box(modifier) { KeysScreen(app, onBack = { child = null }) }
            ChildPage.KnownHosts -> Box(modifier) { KnownHostsScreen(app, onBack = { child = null }) }
            ChildPage.Controls -> Box(modifier) { ControlsScreen(app, onBack = { child = null }) }
            ChildPage.Updates -> Box(modifier) { AppUpdatesScreen(app, onBack = { child = null }) }
            null -> GlassScreen(
                title = stringResource(shown.title),
                onBack = if (wide) null else ({ section = null }),
                snackbar = snackbar,
                modifier = modifier,
            ) { padding ->
                // Appearance and Terminal pin the preview above their controls.
                val preview = shown == SettingsSection.Appearance || shown == SettingsSection.Terminal
                Column(Modifier.fillMaxSize()) {
                    if (preview) SettingsPreview(app, settings, tall = wide, Modifier.padding(top = padding.calculateTopPadding()))
                    LazyColumn(
                        contentPadding = if (preview) PaddingValues(top = 4.dp, bottom = padding.calculateBottomPadding()) else padding,
                        modifier = Modifier.weight(1f).testTag("settings:${shown.name.lowercase()}"),
                    ) {
                        when (shown) {
                            SettingsSection.Appearance -> appearance()
                            SettingsSection.Terminal -> terminal()
                            SettingsSection.Composer -> composerSettings(app.settings, settings)
                            SettingsSection.Layout -> layoutSettings(app.settings, settings) { child = ChildPage.Controls }
                            SettingsSection.Notifications -> notificationSettings(app.settings, settings)
                            SettingsSection.Security -> security()
                            SettingsSection.About -> about()
                        }
                        if (shown.reset != null) restoreSection(shown.title) { restoring = shown }
                    }
                }
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= ListDetailWidth
        // Keys and Known hosts handle Back themselves while they are shown;
        // from the root destination's index, Back is the app's to handle.
        BackHandler(enabled = child == null && ((!wide && section != null) || onBack != null)) {
            if (!wide && section != null) section = null else onBack?.invoke()
        }
        val open = section
        if (wide) {
            val shown = open ?: SettingsSection.Appearance
            Row(Modifier.fillMaxSize()) {
                Index(wide = true, selected = shown, modifier = Modifier.width(IndexWidth))
                Detail(shown, wide = true, modifier = Modifier.weight(1f))
            }
        } else if (open == null) {
            Index(wide = false, selected = null)
        } else {
            Detail(open, wide = false)
        }
    }

    if (reset) {
        ConfirmDialog(
            title = stringResource(R.string.reset_settings),
            text = stringResource(R.string.reset_settings_confirm),
            confirm = stringResource(R.string.reset),
            onConfirm = { reset = false; app.settings.reset() },
            onDismiss = { reset = false },
        )
    }
    restoring?.let { page ->
        val scope = page.reset ?: return@let
        ConfirmDialog(
            title = stringResource(R.string.restore_section, stringResource(page.title)),
            text = stringResource(scope.confirmText()),
            confirm = stringResource(R.string.restore),
            onConfirm = { restoring = null; app.settings.restore(scope) },
            onDismiss = { restoring = null },
        )
    }
    if (licenses) {
        val text = remember {
            listOf(
                "licenses/NOTICE.txt",
                "licenses/LICENSE.txt",
                "licenses/herdr-protocol-NOTICE.md",
                "fonts/JetBrainsMono-OFL.txt",
                "fonts/NerdFonts-LICENSE.txt",
                "fonts/SymbolsNerdFont-LICENSE.txt",
            ).joinToString("\n\n———\n\n") { path ->
                context.assets.open(path).bufferedReader().use { it.readText() }
            }
        }
        GlassAlertDialog(
            onDismissRequest = { licenses = false },
            title = { Text(stringResource(R.string.licenses)) },
            text = {
                LazyColumn { item { Text(text, style = MaterialTheme.typography.bodySmall) } }
            },
            confirmButton = { TextButton(onClick = { licenses = false }) { Text(stringResource(android.R.string.ok)) } },
        )
    }
}

/**
 * Every app theme as a card in its own colours for the mode on screen, and
 * last, on API 31+, Material You in the wallpaper's. A tap saves it at once
 * and the whole screen re-themes, which is the preview.
 */
@Composable
private fun ThemeRow(selected: String, onSelect: (String) -> Unit) {
    val dark = LocalThemeTokens.current.dark
    // Below 31 a stored Material You wears Herdline, so Herdline shows selected.
    val currentId = if (selected == AppPalettes.MaterialYouId && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        AppPalettes.MaterialYouId
    } else {
        AppPalettes.byId(selected).id
    }
    Row(
        Modifier.horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AppPalettes.all.forEach { palette -> ThemeCard(palette, palette.tokens(dark), palette.id == currentId) { onSelect(palette.id) } }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val you = AppPalettes.MaterialYou
            ThemeCard(you, rememberDynamicTokens(dark), you.id == currentId) { onSelect(you.id) }
        }
    }
}

private val ThemeSampleShape = RoundedCornerShape(12.dp) // = shapes.medium

/**
 * One theme: page, a surface strip, two text bars, an accent pill and the
 * status dots, with its name below. The stroke and check carry the
 * selection, not colour alone; TalkBack reads one radio button.
 */
@Composable
private fun ThemeCard(palette: AppPalette, tokens: ThemeTokens, selected: Boolean, onClick: () -> Unit) {
    val scheme = tokens.material
    val described = stringResource(
        if (palette.darkOnly) R.string.app_theme_description_dark_only else R.string.app_theme_description,
        palette.name,
    )
    val description = if (selected) stringResource(R.string.app_theme_selected, described) else described
    Column(
        Modifier
            .width(88.dp)
            .clip(ThemeSampleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description }
            .padding(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier
                .size(80.dp, 72.dp)
                .clip(ThemeSampleShape)
                .background(scheme.background)
                .border(
                    if (selected) 3.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    ThemeSampleShape,
                )
                .padding(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Box(Modifier.fillMaxWidth().height(12.dp).clip(MaterialTheme.shapes.extraSmall).background(scheme.surfaceContainerLow))
                Box(Modifier.fillMaxWidth(0.8f).height(4.dp).clip(CircleShape).background(scheme.onSurface))
                Box(Modifier.fillMaxWidth(0.5f).height(4.dp).clip(CircleShape).background(scheme.onSurfaceVariant))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(20.dp, 8.dp).clip(CircleShape).background(scheme.primary))
                    listOf(AgentStatus.Working, AgentStatus.Blocked, AgentStatus.Done, AgentStatus.Idle).forEach {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(tokens.status.of(it)))
                    }
                }
            }
            if (selected) {
                Icon(
                    Icons.Default.Check,
                    null,
                    tint = scheme.onPrimary,
                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp).clip(CircleShape).background(scheme.primary).padding(2.dp),
                )
            }
        }
        // At a large font scale the name wraps and the card grows rather than clipping.
        Text(palette.name, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center)
        if (palette.darkOnly) {
            Text(
                stringResource(R.string.app_theme_dark_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * The terminal schemes for one mode, after "Match theme", which is stored as
 * "" and shows [matched], the app theme's own scheme for that mode.
 */
@Composable
private fun SchemeRow(schemes: List<TerminalScheme>, matched: TerminalScheme, selected: String, onSelect: (String) -> Unit) {
    // An id no scheme has falls back to the theme's own scheme, so Match theme shows as chosen.
    val chosen = selected.takeIf { id -> schemes.any { it.id == id } } ?: Settings.MATCH_THEME
    Row(
        Modifier.horizontalScroll(rememberScrollState()).selectableGroup().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SchemeCard(matched, stringResource(R.string.scheme_match_theme), chosen == Settings.MATCH_THEME) { onSelect(Settings.MATCH_THEME) }
        schemes.forEach { scheme -> SchemeCard(scheme, scheme.name, scheme.id == chosen) { onSelect(scheme.id) } }
    }
}

private val SchemeShape = RoundedCornerShape(12.dp) // = shapes.medium

/** A tiny terminal in the scheme's colours, labelled [name]. */
@Composable
private fun SchemeCard(scheme: TerminalScheme, name: String, selected: Boolean, onClick: () -> Unit) {
    fun c(v: Long) = Color(0xff000000 or v)
    Column(
        Modifier
            .width(152.dp)
            .clip(SchemeShape)
            .border(
                if (selected) 2.dp else 1.dp,
                if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                SchemeShape,
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .background(c(scheme.background))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("~ ", color = c(scheme.ansi[4]), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("git ", color = c(scheme.ansi[2]), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Text("main", color = c(scheme.ansi[5]), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            Box(Modifier.padding(start = 2.dp).size(7.dp, 13.dp).background(c(scheme.cursor)))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (1..6).forEach { Box(Modifier.size(12.dp).clip(CircleShape).background(c(scheme.ansi[it]))) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = c(scheme.foreground), style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
            if (selected) Icon(Icons.Default.Check, null, tint = c(scheme.foreground), modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun FontRow(
    app: HerdlineApp,
    choice: FontChoice,
    selected: Boolean,
    progress: Float?,
    changes: Int,
    onSelect: () -> Unit,
    onDownload: () -> Unit,
    onDelete: () -> Unit,
) {
    // Spelled out rather than produceState, whose lint check misses this assignment.
    var family by remember { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(choice.id, choice.available, changes) {
        family = if (choice.available) FontFamily(app.fonts.typefaces(choice.id).regular) else null
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = choice.available) { onSelect() }
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = choice.available)
        Column(Modifier.weight(1f).padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(choice.name, style = MaterialTheme.typography.bodyLarge)
                if (choice.nerd) Pill("Nerd Font", MaterialTheme.colorScheme.tertiary)
            }
            if (family != null) {
                Text(PREVIEW, fontFamily = family, fontSize = 14.sp, maxLines = 1)
            } else {
                val size = (choice.catalog?.downloadSize ?: 0) / 1_000_000.0
                Text(
                    stringResource(R.string.font_download_size, "%.1f".format(size)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when {
            progress != null -> CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(28.dp).padding(2.dp))
            choice.catalog != null && !app.fonts.fullyDownloaded(choice.catalog) ->
                TextButton(onClick = onDownload) {
                    Text(stringResource(if (choice.available) R.string.font_get_styles else R.string.download))
                }
            choice.catalog?.bundled == false || choice.id.startsWith("custom:") ->
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, stringResource(R.string.delete)) }
        }
    }
}

/**
 * The display language. Language names are written in their own language so
 * a reader who cannot read the current one still finds theirs.
 */
@Composable
private fun LanguagePicker() {
    val context = LocalContext.current
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }.filterIsInstance<Activity>().firstOrNull()
    val current = remember(context) { AppLanguages.current(context) }
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.language), style = MaterialTheme.typography.titleMedium)
        GlassSegmented(
            options = AppLanguage.entries.map {
                stringResource(when (it) {
                    AppLanguage.System -> R.string.language_system
                    AppLanguage.English -> R.string.language_english
                    AppLanguage.Chinese -> R.string.language_chinese
                })
            },
            selected = AppLanguage.entries.indexOf(current),
            onSelect = { index ->
                val language = AppLanguage.entries[index]
                if (language != current) activity?.let { AppLanguages.set(it, language) }
            },
        )
    }
}
