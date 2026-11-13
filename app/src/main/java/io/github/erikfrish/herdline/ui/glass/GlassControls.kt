/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.glass

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FabPosition
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.ui.theme.contrast
import io.github.erikfrish.herdline.ui.theme.ensureContrast
import io.github.erikfrish.herdline.ui.theme.onColor

/** M3 `shapes.large`; a val, so it needs no composable context. */
val GroupShape = RoundedCornerShape(16.dp)

/** M3 `shapes.extraSmall`, the OutlinedTextField default. */
val FieldShape = RoundedCornerShape(4.dp)

/**
 * A Material 3 screen: a top app bar (large and collapsing when [large]) over
 * the page, an optional [bottomBar] above the navigation bar on an opaque
 * surfaceContainer bar with a top divider (content scrolls under it), an optional
 * [floatingActionButton] at the bottom end, and [snackbar]. [content] gets
 * Scaffold's inner padding.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassScreen(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    large: Boolean = false,
    titleAccessory: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    bottomBar: (@Composable () -> Unit)? = null,
    snackbar: SnackbarHostState? = null,
    /** The screen's primary action within thumb reach (M8); none by default. */
    floatingActionButton: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val scroll = if (large) TopAppBarDefaults.exitUntilCollapsedScrollBehavior() else TopAppBarDefaults.pinnedScrollBehavior()
    val heading = @Composable {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                titleAccessory?.invoke()
            }
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    val navigation = @Composable {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back))
            }
        }
    }
    Scaffold(
        modifier = modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            if (large) {
                LargeTopAppBar(title = heading, navigationIcon = navigation, actions = actions, scrollBehavior = scroll)
            } else {
                TopAppBar(title = heading, navigationIcon = navigation, actions = actions, scrollBehavior = scroll)
            }
        },
        bottomBar = {
            bottomBar?.let {
                // QF-8b: opaque, so content scrolled under it never shows through; the fill runs under the nav bar.
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxWidth()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { it() }
                    }
                }
            }
        },
        snackbarHost = { snackbar?.let { GlassSnackbarHost(it) } },
        floatingActionButton = floatingActionButton,
        floatingActionButtonPosition = FabPosition.End,
        containerColor = MaterialTheme.colorScheme.background,
        content = content,
    )
}

/**
 * Material snackbars in the app's own surface, with an outline edge so they
 * stand clear of a terminal behind them: never the white inverseSurface card
 * over a dark terminal (BN-1 §6).
 */
@Composable
fun GlassSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier) { data ->
        val scheme = MaterialTheme.colorScheme
        Snackbar(
            data,
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .border(1.dp, scheme.outline, SnackbarDefaults.shape),
            containerColor = scheme.surfaceContainerHighest,
            contentColor = scheme.onSurface,
            actionColor = scheme.primary,
            actionContentColor = scheme.primary,
            dismissActionContentColor = scheme.onSurfaceVariant,
        )
    }
}

/**
 * One icon as a standard [IconButton]; a caller that passes a [tint] gets a
 * [FilledTonalIconButton]. [size] is the visual container; the touch target
 * stays 48 dp.
 */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    tint: Color = Color.Unspecified,
    contentColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    val sized = if (size == 40.dp) modifier else modifier.size(size)
    if (tint == Color.Unspecified) {
        IconButton(onClick, sized, colors = IconButtonDefaults.iconButtonColors(contentColor = contentColor)) {
            Icon(icon, contentDescription)
        }
    } else {
        FilledTonalIconButton(onClick, sized) { Icon(icon, contentDescription) }
    }
}

/** A surfaceContainer capsule grouping several [CapsuleAction]s. */
@Composable
fun GlassCapsule(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Surface(modifier, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer) {
        Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@Composable
fun CapsuleAction(icon: ImageVector, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier) {
        Icon(icon, contentDescription, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A full-pill Material button: [primary] is filled with [accent], [destructive]
 * is an error-tonal button, anything else is tonal. An accent other than the
 * scheme's primary gets black or white text, the accent moved until it reads.
 */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    accent: Color = MaterialTheme.colorScheme.primary,
    destructive: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    val label: @Composable RowScope.() -> Unit = {
        icon?.let {
            Icon(it, null, Modifier.size(ButtonDefaults.IconSize))
            Spacer(Modifier.size(ButtonDefaults.IconSpacing))
        }
        Text(text, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    when {
        destructive -> FilledTonalButton(
            onClick,
            modifier,
            enabled = enabled,
            colors = ButtonDefaults.filledTonalButtonColors(containerColor = scheme.errorContainer, contentColor = scheme.onErrorContainer),
            content = label,
        )
        primary -> {
            val colors = if (accent == scheme.primary) {
                ButtonDefaults.buttonColors()
            } else {
                val on = onColor(accent)
                val container = if (contrast(on, accent) >= 4.5f) accent else ensureContrast(accent, listOf(on), 4.5f, onColor(on))
                ButtonDefaults.buttonColors(containerColor = container, contentColor = on)
            }
            Button(onClick, modifier, enabled = enabled, colors = colors, content = label)
        }
        else -> FilledTonalButton(onClick, modifier, enabled = enabled, content = label)
    }
}

/** A single-choice segmented button row; each segment keeps its `segment:<label>` tag. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassSegmented(
    options: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, label ->
            SegmentedButton(
                selected = index == selected,
                onClick = { onSelect(index) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                modifier = Modifier.testTag("segment:$label"),
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** A surfaceContainerLow group of rows, the container every settings-like list uses. */
@Composable
fun GlassGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier.fillMaxWidth().padding(horizontal = 16.dp),
        shape = GroupShape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(content = content)
    }
}

/** A divider between the rows of a [GlassGroup], inset past their leading content. */
@Composable
fun GlassDivider(start: Dp = 16.dp) {
    HorizontalDivider(Modifier.padding(start = start, end = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

/** A section caption above a group, in titleSmall primary. */
@Composable
fun GlassSectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

/**
 * A round primaryContainer tile with an icon, leading a row. [color] is
 * ignored: colour tiles are removed; leading icons use primaryContainer.
 */
@Composable
fun IconBadge(icon: ImageVector, @Suppress("UNUSED_PARAMETER") color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(24.dp))
    }
}

@Composable
fun glassFieldColors(): TextFieldColors = OutlinedTextFieldDefaults.colors()

/** A Material [OutlinedTextField]; editors and Settings only, never the composer. */
@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    readOnly: Boolean = false,
    textStyle: TextStyle = LocalTextStyle.current,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = label,
        placeholder = placeholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        shape = FieldShape,
        colors = glassFieldColors(),
    )
}

/** M3 `shapes.extraSmall`, the menu default. */
val MenuShape = RoundedCornerShape(4.dp)

/** Popups, drawers and dialogs: surfaceContainer. */
@Composable
fun menuColor(): Color = MaterialTheme.colorScheme.surfaceContainer

/** A Material dropdown menu on surfaceContainer. */
@Composable
fun GlassMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 4.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // A popup is its own window, outside the root that exposes test tags.
        modifier = modifier.semantics { testTagsAsResourceId = true },
        offset = offset,
        shape = MenuShape,
        containerColor = menuColor(),
        content = content,
    )
}

/** One action in a [GlassMenu]; [destructive] ones are drawn in the error colour. */
@Composable
fun GlassMenuItem(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val text = if (destructive) scheme.error else scheme.onSurface
    val glyph = if (destructive) scheme.error else scheme.onSurfaceVariant
    DropdownMenuItem(
        text = { Text(label, color = text) },
        leadingIcon = { Icon(icon, null, tint = glyph) },
        onClick = onClick,
    )
}

/**
 * A small label: a route, a certificate, a status. The text is onSurface;
 * [color] paints only the [dot], so the word carries the meaning.
 */
@Composable
fun GlassPill(text: String, color: Color, modifier: Modifier = Modifier, dot: Boolean = false) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (dot) Box(Modifier.size(8.dp).background(color, CircleShape))
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Space that keeps the last item of a list clear of a bottom bar. */
@Composable
fun BottomSpace(padding: PaddingValues) = Spacer(Modifier.height(padding.calculateBottomPadding()))

/** A Material [AlertDialog] with the M3 default shape and container. */
@Composable
fun GlassAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier,
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        // A dialog is its own window, outside the root that exposes test tags.
        text = text?.let { { Box(Modifier.semantics { testTagsAsResourceId = true }) { it() } } },
    )
}
