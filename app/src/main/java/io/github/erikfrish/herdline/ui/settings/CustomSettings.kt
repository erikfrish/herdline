package io.github.erikfrish.herdline.ui.settings

import android.content.ActivityNotFoundException
import android.view.View
import android.widget.ImageView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.viewinterop.AndroidView
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.data.ComposerAction
import io.github.erikfrish.herdline.data.ComposerMenu
import io.github.erikfrish.herdline.data.Density
import io.github.erikfrish.herdline.data.IconVariant
import io.github.erikfrish.herdline.data.drawableRes
import io.github.erikfrish.herdline.data.labelRes
import io.github.erikfrish.herdline.data.LayoutMode
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.data.Settings
import io.github.erikfrish.herdline.data.SettingsStore
import io.github.erikfrish.herdline.data.SheetSection
import io.github.erikfrish.herdline.data.SheetSections
import io.github.erikfrish.herdline.data.StartFilter
import io.github.erikfrish.herdline.data.hiddenCount
import io.github.erikfrish.herdline.data.TargetOrder
import io.github.erikfrish.herdline.data.ThemeMode
import io.github.erikfrish.herdline.service.AgentNotifications
import io.github.erikfrish.herdline.service.NoteContent
import io.github.erikfrish.herdline.service.NoteTexts
import io.github.erikfrish.herdline.service.ResourceTexts
import io.github.erikfrish.herdline.service.agentContent
import io.github.erikfrish.herdline.service.Promotion
import io.github.erikfrish.herdline.service.liveLevel
import io.github.erikfrish.herdline.ui.components.ColorPicker
import io.github.erikfrish.herdline.ui.components.ConfirmDialog
import io.github.erikfrish.herdline.ui.components.Group
import io.github.erikfrish.herdline.ui.components.ItemRow
import io.github.erikfrish.herdline.ui.components.SectionHeader
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.GlassDivider
import io.github.erikfrish.herdline.ui.glass.GlassSegmented
import io.github.erikfrish.herdline.ui.glass.GlassScreen
import io.github.erikfrish.herdline.ui.glass.IconBadge
import io.github.erikfrish.herdline.ui.terminal.AllExtraKeys
import io.github.erikfrish.herdline.ui.terminal.DEFAULT_EXTRA_KEY_IDS
import io.github.erikfrish.herdline.ui.chat.chatPalette
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import io.github.erikfrish.herdline.ui.theme.LocalCompact
import io.github.erikfrish.herdline.ui.theme.LocalThemeTokens
import io.github.erikfrish.herdline.ui.theme.NodeColors
import io.github.erikfrish.herdline.ui.theme.TerminalScheme
import io.github.erikfrish.herdline.ui.theme.dynamicColor
import io.github.erikfrish.herdline.ui.theme.isDark
import io.github.erikfrish.herdline.ui.theme.palette
import io.github.erikfrish.herdline.ui.theme.terminalScheme

/** Interface size, density and accent: part of Appearance. */
internal fun LazyListScope.interfaceSettings(store: SettingsStore, settings: Settings) {
    item { SectionHeader(stringResource(R.string.section_interface)) }
    item {
        Group {
            Column(Modifier.padding(18.dp)) {
                // Applied when the finger lifts: the slider itself would resize under it.
                var scale by remember(settings.uiScale) { mutableFloatStateOf(settings.uiScale) }
                Text(stringResource(R.string.ui_scale, Math.round(scale * 100)), style = MaterialTheme.typography.titleMedium)
                Slider(
                    value = scale,
                    onValueChange = { scale = Math.round(it * 20) / 20f },
                    onValueChangeFinished = { store.update { it.copy(uiScale = scale) } },
                    valueRange = Settings.UI_SCALES,
                    steps = 8,
                    modifier = Modifier.testTag("settings:ui_scale"),
                )
                Help(stringResource(R.string.ui_scale_help))
            }
            GlassDivider()
            Choice(
                stringResource(R.string.density),
                listOf(stringResource(R.string.density_comfortable), stringResource(R.string.density_compact)),
                Density.entries.indexOf(settings.density),
            ) { index -> store.update { it.copy(density = Density.entries[index]) } }
            GlassDivider()
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.accent), style = MaterialTheme.typography.titleMedium)
                ColorPicker(selected = settings.accent - 1) { index -> store.update { it.copy(accent = index + 1) } }
                Help(stringResource(R.string.accent_help))
                if (settings.dynamicColor && settings.accent != 0) Help(stringResource(R.string.accent_dynamic_help))
            }
            ItemRow(
                title = stringResource(R.string.accent_node),
                onClick = { store.update { it.copy(accent = 0) } },
                trailing = { RadioButton(selected = settings.accent == 0, onClick = { store.update { it.copy(accent = 0) } }) },
            )
        }
    }
}

/** The sidebar layout, the switcher's session order, how the Tabs list opens, and the Controls page. */
internal fun LazyListScope.layoutSettings(store: SettingsStore, settings: Settings, onControls: () -> Unit) {
    item {
        Group {
            Choice(
                stringResource(R.string.layout_mode),
                listOf(stringResource(R.string.layout_auto), stringResource(R.string.layout_compact), stringResource(R.string.layout_wide)),
                LayoutMode.entries.indexOf(settings.layoutMode),
                help = stringResource(R.string.layout_help),
            ) { index -> store.update { it.copy(layoutMode = LayoutMode.entries[index]) } }
            GlassDivider()
            Choice(
                stringResource(R.string.order_title),
                listOf(stringResource(R.string.order_workspace), stringResource(R.string.order_last_activity)),
                TargetOrder.entries.indexOf(settings.targetOrder),
                help = stringResource(R.string.order_help),
            ) { index -> store.update { it.copy(targetOrder = TargetOrder.entries[index]) } }
        }
    }
    item { SectionHeader(stringResource(R.string.section_tabs_list)) }
    item {
        Group {
            Choice(
                stringResource(R.string.start_filter),
                listOf(stringResource(R.string.filter_all), stringResource(R.string.start_filter_needs_you), stringResource(R.string.start_filter_working)),
                StartFilter.entries.indexOf(settings.startFilter),
                help = stringResource(R.string.start_filter_help),
            ) { index -> store.update { it.copy(startFilter = StartFilter.entries[index]) } }
            GlassDivider()
            Toggle(stringResource(R.string.hide_shells), null, settings.hideShells) { on -> store.update { it.copy(hideShells = on) } }
            GlassDivider()
            Toggle(stringResource(R.string.hide_idle_done), null, settings.hideIdleDone) { on -> store.update { it.copy(hideIdleDone = on) } }
            Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) { Help(stringResource(R.string.hide_help)) }
        }
    }
    item {
        Group(Modifier.padding(top = 16.dp)) {
            val hidden = hiddenCount(settings.composerMenu, ComposerMenu.DEFAULT) +
                hiddenCount(settings.sheetSections, SheetSections.DEFAULT)
            ItemRow(
                title = stringResource(R.string.controls_title),
                detail = if (hidden == 0) stringResource(R.string.controls_all_shown) else pluralStringResource(R.plurals.controls_hidden, hidden, hidden),
                detailMaxLines = Int.MAX_VALUE,
                onClick = onControls,
                chevron = true,
            )
        }
    }
}

/**
 * Settings › Layout › Controls: which + menu entries
 * show and in what order, and whether the Tabs list shows its places and
 * status filters. Send, the mode control, Search and the order selector are
 * fixed. Insert without Enter or the larger editor (which has its own
 * Insert) always stays in the + menu (parent Q-2): the switch that would
 * hide the last of the two is off-limits and says why.
 */
@Composable
internal fun ControlsScreen(app: HerdlineApp, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val store = app.settings
    val settings by store.settings.collectAsState()
    var restoring by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    GlassScreen(title = stringResource(R.string.controls_title), onBack = onBack, modifier = modifier) { padding ->
        LazyColumn(contentPadding = padding, modifier = Modifier.fillMaxSize().testTag("settings:controls")) {
            item { SectionHeader(stringResource(R.string.section_composer_menu)) }
            item {
                Group {
                    val shown = settings.composerMenu
                    // Shown entries in their order, then the hidden ones in catalogue order.
                    ComposerMenu.actions(shown + ComposerMenu.DEFAULT.filter { it !in shown }).forEach { action ->
                        val label = stringResource(action.label())
                        val position = shown.indexOf(action.id)
                        val locked = position >= 0 && !ComposerMenu.mayHide(shown, action.id)
                        val toggle = { store.update { it.copy(composerMenu = ComposerMenu.toggled(it.composerMenu, action.id)) } }
                        ItemRow(
                            title = label,
                            detail = if (locked) stringResource(R.string.controls_keeps_insert) else null,
                            detailMaxLines = Int.MAX_VALUE,
                            onClick = if (locked) null else toggle,
                            trailing = {
                                if (position > 0) {
                                    IconButton(onClick = { store.update { it.copy(composerMenu = movedUp(it.composerMenu, action.id)) } }) {
                                        Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.controls_move_up, label))
                                    }
                                }
                                Switch(
                                    checked = position >= 0,
                                    onCheckedChange = { toggle() },
                                    enabled = !locked,
                                    modifier = Modifier.testTag("settings:control:${action.id}"),
                                )
                            },
                        )
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.section_sheet_sections)) }
            item {
                Group {
                    SheetSection.entries.forEach { section ->
                        Toggle(
                            stringResource(section.label()),
                            if (section == SheetSection.Filters) stringResource(R.string.controls_filters_help) else null,
                            SheetSections.shows(settings.sheetSections, section),
                        ) { store.update { it.copy(sheetSections = toggled(it.sheetSections, section.id)) } }
                    }
                    Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) { Help(stringResource(R.string.controls_fixed)) }
                }
            }
            restoreSection(R.string.controls_title) { restoring = true }
        }
    }
    if (restoring) {
        ConfirmDialog(
            title = stringResource(R.string.restore_section, stringResource(R.string.controls_title)),
            text = stringResource(R.string.restore_controls_confirm),
            confirm = stringResource(R.string.restore),
            onConfirm = {
                restoring = false
                store.update { it.copy(composerMenu = ComposerMenu.DEFAULT, sheetSections = SheetSections.DEFAULT) }
            },
            onDismiss = { restoring = false },
        )
    }
}

private fun ComposerAction.label(): Int = when (this) {
    ComposerAction.Insert -> R.string.composer_insert_no_enter
    ComposerAction.LargerEditor -> R.string.composer_larger_editor
    ComposerAction.TerminalKeyboard -> R.string.composer_terminal_keyboard
    ComposerAction.Paste -> R.string.composer_paste_clipboard
}

private fun SheetSection.label(): Int = when (this) {
    SheetSection.Places -> R.string.controls_places
    SheetSection.Filters -> R.string.controls_filters
}

/** The composer in Terminal, and keyboard suggestions outside the composer. */
internal fun LazyListScope.composerSettings(store: SettingsStore, settings: Settings) {
    item {
        Group {
            Toggle(stringResource(R.string.terminal_composer), stringResource(R.string.terminal_composer_help), settings.terminalComposer) { on ->
                store.update { it.copy(terminalComposer = on) }
            }
            GlassDivider()
            Toggle(stringResource(R.string.terminal_suggestions), stringResource(R.string.terminal_suggestions_help), settings.terminalSuggestions) { on ->
                store.update { it.copy(terminalSuggestions = on) }
            }
        }
    }
}

/** Which extra keys show above the keyboard, and in what order. */
internal fun LazyListScope.extraKeySettings(store: SettingsStore, settings: Settings) {
    item { SectionHeader(stringResource(R.string.section_extra_keys)) }
    item {
        Group {
            Toggle(stringResource(R.string.show_extra_keys), null, settings.showExtraKeys) { on -> store.update { it.copy(showExtraKeys = on) } }
            if (settings.showExtraKeys) {
                GlassDivider()
                Box(Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) { Help(stringResource(R.string.extra_keys_help)) }
                // Enabled keys in their order, then the rest in catalogue order.
                val ids = settings.extraKeys + AllExtraKeys.keys.filter { it !in settings.extraKeys }
                ids.forEach { id ->
                    val key = AllExtraKeys[id] ?: return@forEach
                    val position = settings.extraKeys.indexOf(id)
                    ItemRow(
                        title = key.label,
                        onClick = { store.update { it.copy(extraKeys = toggled(it.extraKeys, id)) } },
                        trailing = {
                            if (position > 0) {
                                IconButton(onClick = { store.update { it.copy(extraKeys = movedUp(it.extraKeys, id)) } }) {
                                    Icon(Icons.Default.KeyboardArrowUp, stringResource(R.string.extra_key_up, key.label))
                                }
                            }
                            Switch(position >= 0, { store.update { it.copy(extraKeys = toggled(it.extraKeys, id)) } }, Modifier.testTag("settings:key:$id"))
                        },
                    )
                }
                GlassDivider()
                Box(Modifier.padding(14.dp)) {
                    GlassButton(stringResource(R.string.extra_keys_default), onClick = { store.update { it.copy(extraKeys = DEFAULT_EXTRA_KEY_IDS) } }, icon = Icons.Default.Refresh)
                }
            }
        }
    }
}

/** Agent and connection notifications, the live card, and what a locked screen shows. */
internal fun LazyListScope.notificationSettings(store: SettingsStore, settings: Settings) {
    item {
        Group {
            Toggle(stringResource(R.string.notify_attention), null, settings.notifyAttention, Icons.Default.Warning, Color(0xFFF2A93B)) { on ->
                store.update { it.copy(notifyAttention = on) }
            }
            GlassDivider()
            Toggle(stringResource(R.string.notify_done), null, settings.notifyDone, Icons.Default.Notifications, Color(0xFF3FB868)) { on ->
                store.update { it.copy(notifyDone = on) }
            }
            GlassDivider()
            Toggle(stringResource(R.string.notify_connection), stringResource(R.string.notify_connection_help), settings.notifyConnection) { on ->
                store.update { it.copy(notifyConnection = on) }
            }
            GlassDivider()
            Toggle(stringResource(R.string.live_activity), stringResource(R.string.live_activity_help), settings.liveActivity, Icons.Default.Star, Color(0xFF4C8DF6)) { on ->
                store.update { it.copy(liveActivity = on) }
            }
            if (settings.liveActivity) {
                GlassDivider()
                PromotionRows()
            }
            GlassDivider()
            Choice(
                stringResource(R.string.lock_detail),
                listOf(stringResource(R.string.lock_hidden), stringResource(R.string.lock_names), stringResource(R.string.lock_full)),
                LockDetail.ALERTS.indexOf(settings.lockDetail),
                help = stringResource(R.string.lock_detail_help),
            ) { index -> store.update { it.copy(lockDetail = LockDetail.ALERTS[index]) } }
            LockPreview(settings.lockDetail)
            if (settings.liveActivity) {
                GlassDivider()
                LiveLevelRow(store, settings)
            }
        }
    }
}

/** The live update's lock level; shown here, chosen only in the pin sheet. */
@Composable
private fun LiveLevelRow(store: SettingsStore, settings: Settings) {
    val live by store.liveLockDetail.collectAsState()
    val level = when (liveLevel(live, settings.lockDetail)) {
        LockDetail.Hidden -> R.string.lock_hidden
        LockDetail.StateOnly -> R.string.lock_state_only
        LockDetail.NamesOnly -> R.string.lock_names
        LockDetail.Full -> R.string.lock_full
    }
    ItemRow(
        title = stringResource(R.string.live_lock_detail),
        detail = stringResource(level) + "\n" + stringResource(R.string.live_lock_detail_help),
        detailMaxLines = Int.MAX_VALUE,
    )
}

/** Actual adaptive launcher artwork, with one selectable picture per persisted alias. */
internal fun LazyListScope.iconSettings(store: SettingsStore, settings: Settings) {
    item { SectionHeader(stringResource(R.string.section_icon)) }
    item {
        Group {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FlowRow(
                    Modifier.fillMaxWidth().selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    IconVariant.entries.forEach { variant ->
                        LauncherIconChoice(variant, settings.iconVariant == variant) {
                            store.update { it.copy(iconVariant = variant) }
                        }
                    }
                }
                Help(stringResource(R.string.icon_help))
            }
        }
    }
}

@Composable
private fun LauncherIconChoice(variant: IconVariant, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(variant.labelRes)
    val context = LocalContext.current
    val drawable = remember(context, variant) { context.getDrawable(variant.drawableRes) }
    Column(
        Modifier.width(88.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .border(if (selected) 2.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.medium)
            .selectable(selected, role = Role.RadioButton, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = label }
            .testTag("settings:icon:${variant.name}")
            .padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(72.dp)) {
            AndroidView(
                factory = { ctx -> ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO } },
                update = { it.setImageDrawable(drawable) },
                modifier = Modifier.fillMaxSize(),
            )
            if (selected) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.align(Alignment.BottomEnd).size(22.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).padding(3.dp))
        }
        Text(label, style = MaterialTheme.typography.labelMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Reset every setting to its default, after confirmation by the caller. */
internal fun LazyListScope.resetSettings(onReset: () -> Unit) {
    item {
        Group(Modifier.padding(top = 24.dp)) {
            ItemRow(
                title = stringResource(R.string.reset_settings),
                detail = stringResource(R.string.reset_settings_detail),
                detailMaxLines = Int.MAX_VALUE,
                leading = { IconBadge(Icons.Default.Build, Color(0xFFE5674F)) },
                onClick = onReset,
            )
        }
    }
}

/**
 * The last row of a section page: a muted "Restore {section} defaults" line,
 * 48 dp, without button chrome. [onRestore] asks for confirmation first.
 */
internal fun LazyListScope.restoreSection(section: Int, onRestore: () -> Unit) {
    item(key = "restore") {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 16.dp)
                .heightIn(min = 48.dp)
                .clip(MaterialTheme.shapes.medium)
                .clickable(role = Role.Button, onClick = onRestore)
                .padding(horizontal = 16.dp)
                .testTag("settings:restore"),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                stringResource(R.string.restore_section, stringResource(section)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The terminal line in the preview; output, so never translated. */
private const val PREVIEW_COMMAND = "\$ cargo test"
private const val PREVIEW_OUTPUT = "  · 364 passed"
private const val PREVIEW_CODE = "expires_at"
private const val PREVIEW_BLOCK = "return token.Refresh(ctx)"

/**
 * A static sample of a session in the current settings, pinned above
 * Appearance and Terminal: built-in text only, never host data. It wears the
 * chosen theme, interface size, density and accent as the screen around it
 * does; the terminal strip uses the terminal font, size and scheme. TalkBack
 * reads the card as one node and skips the sample. With the System mode,
 * Light | Dark previews either variant of the theme and saves nothing.
 */
@Composable
internal fun SettingsPreview(app: HerdlineApp, settings: Settings, tall: Boolean, modifier: Modifier = Modifier) {
    val systemDark = isSystemInDarkTheme()
    var shownDark by remember(settings.themeMode, systemDark) { mutableStateOf(systemDark) }
    val dark = if (settings.themeMode == ThemeMode.System) shownDark else settings.themeMode.isDark(systemDark)
    val scheme = settings.terminalScheme(dark)
    val fontChanges by app.fonts.changes.collectAsState()
    // Spelled out rather than produceState, as in FontRow.
    var family by remember { mutableStateOf<FontFamily?>(null) }
    LaunchedEffect(settings.font, fontChanges) { family = FontFamily(app.fonts.typefaces(settings.font).regular) }
    // The interface size scales this screen's sp; the terminal's text does not follow it.
    val terminal = PreviewTerminal(scheme, family ?: FontFamily.Monospace, (settings.fontSize / settings.uiScale).sp)
    val description = stringResource(R.string.preview_description)
    val accent = settings.accent.takeIf { it != 0 }?.let { NodeColors.of(it - 1) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 40.dp).padding(start = 32.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(R.string.preview_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            if (settings.themeMode == ThemeMode.System) {
                GlassSegmented(
                    options = listOf(stringResource(R.string.theme_light), stringResource(R.string.theme_dark)),
                    selected = if (shownDark) 1 else 0,
                    onSelect = { shownDark = it == 1 },
                    modifier = Modifier.width(168.dp),
                )
            }
        }
        // The variant shown, which under System may be the one not on screen.
        HerdlineTheme(dark = dark, palette = settings.palette, accent = accent, dynamic = settings.dynamicColor, compact = LocalCompact.current) {
            Group(Modifier.padding(top = 4.dp).height(if (tall) 400.dp else 168.dp)) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .clipToBounds()
                        .clearAndSetSemantics { contentDescription = description }
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(if (LocalCompact.current) 4.dp else 6.dp),
                ) {
                    PreviewSample(terminal, tall)
                }
            }
        }
    }
}

private class PreviewTerminal(val scheme: TerminalScheme, val font: FontFamily, val size: TextUnit)

/** The rows of [SettingsPreview], in the theme around them. */
@Composable
private fun PreviewSample(terminal: PreviewTerminal, tall: Boolean) {
    val colors = MaterialTheme.colorScheme
    val tokens = LocalThemeTokens.current
    val chat = chatPalette(tokens.chat, colors.primary)
    val small = MaterialTheme.typography.bodySmall
    val replyText = stringResource(R.string.preview_reply)
    val link = stringResource(R.string.preview_link)
    val reply = buildAnnotatedString {
        append(replyText)
        val at = replyText.indexOf(PREVIEW_CODE)
        if (at >= 0) addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = chat.codeChip), at, at + PREVIEW_CODE.length)
        if (tall) {
            append(' ')
            withStyle(SpanStyle(color = chat.accentText, textDecoration = TextDecoration.Underline)) { append(link) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.preview_header),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(stringResource(R.string.preview_modes), style = MaterialTheme.typography.labelSmall, color = colors.primary, maxLines = 1)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹  " + stringResource(R.string.preview_tabs) + "  ›",
            style = small,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text("1/3", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
    }
    Box(Modifier.fillMaxWidth(0.3f).height(2.dp).background(colors.primary))
    Text(
        stringResource(R.string.preview_user),
        style = small,
        color = chat.text,
        modifier = Modifier.clip(MaterialTheme.shapes.medium).background(chat.userBubble).padding(horizontal = 10.dp, vertical = 4.dp),
    )
    if (tall) Text(stringResource(R.string.preview_thinking), style = small, color = chat.muted, fontStyle = FontStyle.Italic, maxLines = 1)
    Text(reply, style = small, color = chat.text, maxLines = 1)
    if (tall) {
        Text(
            stringResource(R.string.preview_tool),
            style = small,
            color = chat.text,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(chat.surf).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Text(
            PREVIEW_BLOCK,
            style = small,
            fontFamily = FontFamily.Monospace,
            color = chat.text,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(chat.codeBg).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                AgentStatus.Idle to R.string.status_idle,
                AgentStatus.Working to R.string.status_working,
                AgentStatus.Done to R.string.status_done,
                AgentStatus.Blocked to R.string.status_blocked,
            ).forEach { (status, label) -> StatusChip(stringResource(label), tokens.status.of(status), tokens.statusText.of(status)) }
            StatusChip(stringResource(R.string.status_error), colors.error, colors.error)
        }
    }
    PreviewTerminalStrip(terminal, tall)
    if (tall) {
        Text(
            stringResource(R.string.preview_notice),
            style = small,
            color = chat.blocked,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().clip(MaterialTheme.shapes.medium).background(chat.warnBg).padding(horizontal = 10.dp, vertical = 4.dp),
        )
        val pill = CircleShape
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.preview_composer),
                style = small,
                color = colors.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.weight(1f).clip(pill).background(colors.surfaceContainerLow).padding(horizontal = 14.dp, vertical = 6.dp),
            )
            Text(
                stringResource(R.string.preview_insert),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSecondaryContainer,
                modifier = Modifier.clip(pill).background(colors.secondaryContainer).padding(horizontal = 12.dp, vertical = 6.dp),
            )
            // Disabled: the label stays readable, so the reason it cannot send does too.
            Text(
                stringResource(R.string.preview_send),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.clip(pill).background(colors.surfaceContainerHighest).padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** A status word with its dot, the dot never the only cue. */
@Composable
private fun StatusChip(label: String, dot: Color, text: Color) {
    Row(
        Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(dot))
        Text(label, style = MaterialTheme.typography.labelSmall, color = text, maxLines = 1)
    }
}

/** The terminal in its own scheme: a prompt with the cursor, and, tall, a selection and the 16 ANSI colours. */
@Composable
private fun PreviewTerminalStrip(terminal: PreviewTerminal, tall: Boolean) {
    val scheme = terminal.scheme
    fun c(v: Long) = Color(0xff000000 or v)
    Column(
        Modifier.fillMaxWidth().clip(MaterialTheme.shapes.small).background(c(scheme.background)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(3.dp, 14.dp).background(c(scheme.cursor)))
            Text(PREVIEW_COMMAND, color = c(scheme.foreground), fontFamily = terminal.font, fontSize = terminal.size, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
            Text(
                PREVIEW_OUTPUT,
                color = c(scheme.ansi[2]),
                fontFamily = terminal.font,
                fontSize = terminal.size,
                maxLines = 1,
                modifier = if (tall) Modifier.background(Color(scheme.selection)) else Modifier,
            )
        }
        if (tall) {
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                scheme.ansi.forEach { Box(Modifier.weight(1f).height(8.dp).background(c(it))) }
            }
        }
    }
}

/** [ids] with [id] removed, or appended when it was off. */
internal fun toggled(ids: List<String>, id: String): List<String> = if (id in ids) ids - id else ids + id

/** [ids] with [id] one place earlier. */
internal fun movedUp(ids: List<String>, id: String): List<String> {
    val index = ids.indexOf(id)
    if (index <= 0) return ids
    return ids.toMutableList().also { it[index] = it[index - 1]; it[index - 1] = id }
}

@Composable
private fun Help(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Fixed sample names for the lock-screen preview: never the user's own hosts. */
private const val PREVIEW_NODE = "my-host"
private const val PREVIEW_AGENT = "agent"
private const val PREVIEW_TITLE = "omp finished"

/**
 * What a locked phone shows for a finished agent under [lock]: the publicVersion
 * of the real notification, from the same redaction the service posts with.
 */
internal fun lockPreview(t: NoteTexts, lock: LockDetail, sampleBody: String): NoteContent = agentContent(
    t, attention = false, node = PREVIEW_NODE, agent = PREVIEW_AGENT, title = PREVIEW_TITLE,
    body = sampleBody, stale = null, lock = lock, locked = true,
).public

/** A non-interactive notification row showing [lock]'s locked card; nothing is posted. */
@Composable
private fun LockPreview(lock: LockDetail) {
    val context = LocalContext.current
    val sample = stringResource(R.string.lock_preview_sample)
    val note = remember(lock, sample) { lockPreview(ResourceTexts(context), lock, sample) }
    val spoken = stringResource(R.string.lock_preview_description, listOfNotNull(note.title, note.subText, note.text).joinToString(", "))
    Column(
        Modifier.padding(start = 18.dp, end = 18.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Help(stringResource(R.string.lock_preview_title))
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .clearAndSetSemantics { contentDescription = spoken }
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("settings:lock_preview"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                listOfNotNull(stringResource(R.string.app_name), note.subText).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(note.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            note.text?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Choice(title: String?, options: List<String>, selected: Int, help: String? = null, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        title?.let { Text(it, style = MaterialTheme.typography.titleMedium) }
        GlassSegmented(options = options, selected = selected, onSelect = onSelect)
        help?.let { Help(it) }
    }
}

@Composable
private fun Toggle(
    title: String,
    detail: String?,
    checked: Boolean,
    icon: ImageVector? = null,
    tint: Color = Color(0xFF8A8F98),
    onChange: (Boolean) -> Unit,
) {
    ItemRow(
        title = title,
        detail = detail,
        detailMaxLines = Int.MAX_VALUE,
        leading = icon?.let { { IconBadge(it, tint) } },
        onClick = { onChange(!checked) },
        trailing = { Switch(checked, onChange) },
    )
}

/** The live card's promotion state as Android reports it, re-read whenever Settings resumes. */
@Composable
private fun PromotionRows() {
    val context = LocalContext.current
    var state by remember { mutableStateOf(AgentNotifications.promotion(context, liveActivity = true)) }
    LifecycleResumeEffect(Unit) {
        state = AgentNotifications.promotion(context, liveActivity = true)
        onPauseOrDispose { }
    }
    ItemRow(title = stringResource(R.string.live_promotion), detail = stringResource(promotionText(state)), detailMaxLines = Int.MAX_VALUE)
    GlassDivider()
    ItemRow(
        title = stringResource(R.string.live_promotion_settings),
        chevron = true,
        onClick = {
            try {
                context.startActivity(AgentNotifications.settingsIntent(context))
            } catch (e: ActivityNotFoundException) {
                // No such settings page on this build of Android; the state above still stands.
            }
        },
    )
}

private fun promotionText(state: Promotion): Int = when (state) {
    Promotion.Off -> R.string.promotion_off
    Promotion.NoCard -> R.string.promotion_no_card
    Promotion.Stale -> R.string.promotion_stale
    Promotion.Unsupported -> R.string.promotion_unsupported
    Promotion.NotAllowed -> R.string.promotion_not_allowed
    Promotion.NotPromotable -> R.string.promotion_not_promotable
    Promotion.Requested -> R.string.promotion_requested
    Promotion.Promoted -> R.string.promotion_promoted
}
