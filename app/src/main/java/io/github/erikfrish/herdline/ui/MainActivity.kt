/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui

import android.Manifest
import android.content.Context
import android.app.ActivityManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Badge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.semantics.testTagsAsResourceId
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.data.AppLanguages
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.data.IconVariants
import io.github.erikfrish.herdline.data.drawableRes
import io.github.erikfrish.herdline.data.Density as UiDensity
import io.github.erikfrish.herdline.ui.components.AgentsIconFilled
import io.github.erikfrish.herdline.ui.components.AgentsIconOutlined
import io.github.erikfrish.herdline.ui.components.PasswordsDialog
import io.github.erikfrish.herdline.ui.inbox.InboxScreen
import io.github.erikfrish.herdline.ui.nodes.ImportConfigScreen
import io.github.erikfrish.herdline.ui.nodes.NodeEditor
import io.github.erikfrish.herdline.ui.nodes.NodesNavigation
import io.github.erikfrish.herdline.ui.nodes.NodesScreen
import io.github.erikfrish.herdline.ui.session.SessionScreen
import io.github.erikfrish.herdline.ui.settings.SettingsScreen
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import io.github.erikfrish.herdline.ui.theme.NodeColors
import io.github.erikfrish.herdline.ui.theme.dynamicColor
import io.github.erikfrish.herdline.ui.theme.isDark
import io.github.erikfrish.herdline.ui.theme.palette
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val opened = mutableStateOf<Pair<String, String?>?>(null)

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguages.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        val app = application as HerdlineApp
        setContent {
            val settings by app.settings.settings.collectAsState()
            // Recents follows the selected icon: this re-runs on every variant
            // change and on recreation, so in-flight tasks change with it too.
            DisposableEffect(settings.iconVariant) {
                val task = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    ActivityManager.TaskDescription.Builder().setLabel(getString(R.string.app_name)).setIcon(settings.iconVariant.drawableRes).build()
                } else {
                    ActivityManager.TaskDescription(getString(R.string.app_name), IconVariants.currentIcon(app), 0)
                }
                setTaskDescription(task)
                onDispose {}
            }
            val dark = settings.themeMode.isDark(isSystemInDarkTheme())
            // System bar icons follow the app's theme, not the system's, so a
            // light theme on a dark system keeps dark, readable icons.
            DisposableEffect(dark) {
                val style = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose {}
            }
            val accent = settings.accent.takeIf { it != 0 }?.let { NodeColors.of(it - 1) }
            HerdlineTheme(dark = dark, palette = settings.palette, accent = accent, dynamic = settings.dynamicColor, compact = settings.density == UiDensity.Compact) {
                // The interface scales on its own; the terminal sizes its text
                // in sp from the view's resources, which this does not touch.
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density * settings.uiScale, base.fontScale)) {
                    // Test tags double as resource ids, so UiAutomator end-to-end tests can find them.
                    Box(Modifier.semantics { testTagsAsResourceId = true }) {
                        Root(app, dark, opened)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val node = intent?.getStringExtra(EXTRA_NODE) ?: return
        opened.value = node to intent.getStringExtra(EXTRA_PANE)
    }

    override fun onStart() {
        super.onStart()
        val app = application as HerdlineApp
        app.foreground = true
        // At most daily; a process kept alive by a connection still rechecks.
        app.scope.launch { app.updates.check(force = false) }
    }

    override fun onStop() {
        (application as HerdlineApp).foreground = false
        super.onStop()
    }

    companion object {
        private const val EXTRA_NODE = "node"
        private const val EXTRA_PANE = "pane"

        fun intent(context: Context, nodeId: String, pane: String?) = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_NODE, nodeId)
            .putExtra(EXTRA_PANE, pane)
    }
}

private const val START = "hosts"

/**
 * The navigation shell.
 *
 * History is a small saveable back stack of route strings: root destinations
 * (Hosts, Agents, Settings) replace the stack with one or two entries, pushed
 * levels (session, pushed session-settings, keys, known hosts, import, editor)
 * stack on top of their parent, and system Back pops one level. A deep link
 * builds the synthetic stack Hosts → Session, so Back from a notification
 * lands on Hosts, not the launcher. A notification intent is applied once:
 * re-applying it on later recompositions would rebuild Hosts → Session and
 * discard anything pushed on top, such as Settings.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Root(app: HerdlineApp, dark: Boolean, opened: MutableState<Pair<String, String?>?>) {
    var stack by rememberSaveable(
        stateSaver = Saver(
            save = { routes -> routes.joinToString(" ") },
            restore = { saved -> saved.split(" ") },
        ),
    ) { mutableStateOf(listOf(START)) }
    val saved = rememberSaveableStateHolder()
    var askPasswords by rememberSaveable { mutableStateOf<String?>(null) }
    val connections by app.connections.collectAsState()
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    val windowClass = rememberWindowClass()
    val wide = windowClass != WindowClass.Compact

    fun push(route: String) {
        stack = stack + route
    }

    fun pop() {
        if (stack.size > 1) stack = stack.dropLast(1)
    }

    fun switch(destination: String) {
        // Settings from a session (the rail at wide widths) sits above it, so Back returns there.
        if (destination == "settings" && stack.last().startsWith("session:")) return push("session-settings")
        // M3 root destinations: Back from a non-start destination goes to Hosts.
        stack = listOf(START) + listOfNotNull(destination.takeIf { it != START })
    }

    fun openSession(nodeId: String, pane: String?) {
        if (pane != null) opened.value = nodeId to pane
        stack = listOf(START, "session:$nodeId")
    }

    // The notification intent is remembered once it has opened its session:
    // every later recomposition would otherwise see the stale pair, find
    // pushed Settings on top of the stack and rebuild Hosts → Session,
    // silently discarding the push (lab.3 finding D8).
    var appliedOpened by rememberSaveable(
        stateSaver = Saver(
            save = { it?.let { (n, p) -> "$n\u0000${p.orEmpty()}" }.orEmpty() },
            restore = { saved -> saved.takeIf { it.isNotEmpty() }?.let { s -> s.substringBefore("\u0000") to s.substringAfter("\u0000", "").ifEmpty { null } } },
        ),
    ) { mutableStateOf<Pair<String, String?>?>(null) }

    // A notification or deep link opens the pane as a session pushed on Hosts.
    // Once its session has left the stack, the same notification opens it again.
    // A pair that arrives while its session already shows is applied too (the
    // session focuses the pane itself); left pending, the next push would
    // rebuild the stack under it.
    opened.value?.let { (nodeId, pane) ->
        val pair = nodeId to pane
        val inStack = "session:$nodeId" in stack
        if (connections.containsKey(nodeId) && (!inStack || appliedOpened != pair)) {
            appliedOpened = pair
            if (stack.lastOrNull() != "session:$nodeId") openSession(nodeId, pane)
        }
    }

    fun open(node: Node) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val live = app.connection(node.id)?.isOpen == true
        if (!live && app.passwordsToAsk(node).isNotEmpty()) {
            askPasswords = node.id
            return
        }
        app.connect(node, emptyMap(), dark)
        openSession(node.id, null)
    }

    val navigation = object : NodesNavigation {
        override fun open(node: Node) = open(node)
        override fun edit(node: Node?) { push("edit:${node?.id.orEmpty()}") }
        override fun importConfig() { push("import") }
        override fun openFocused(node: Node, pane: String) = openSession(node.id, pane)
        override fun reviewUpdate() = switch("settings")
    }

    val route = stack.last()
    val sessionLevel = route.startsWith("session:")
    // At wide widths the rail is always visible, also in a session and in
    // pushed Settings; the phone bar shows on root destinations only and
    // hides while the IME is up.
    val railVisible = wide
    val barVisible = !wide && !sessionLevel && !(route == "session-settings")
    val showBar = barVisible && !WindowInsets.isImeVisible

    // Levels that do not handle Back themselves pop here. SessionScreen
    // handles Back itself (onLeave), returning to Hosts with the connection
    // still attached.
    if (stack.size > 1 && !sessionLevel) {
        BackHandler { pop() }
    }

    @Composable
    fun needsYou(): Int = connections.values.sumOf { connection ->
        connection.view.collectAsState().value?.agents?.count { it.status == AgentStatus.Blocked } ?: 0
    }

    val needsBadge = needsYou()
    val updateDot by app.updates.stableDot.collectAsState()

    @Composable
    fun Screen(target: String) {
        when {
            // The root destination: no Up arrow, and Back from its index pops here.
            target == "settings" -> SettingsScreen(app, onBack = null)
            // Settings pushed over a session uses its own route key, so the
            // state holder never restores the root destination's open section
            // or deep page (lab.3 finding D3); it always starts at the index.
            target == "session-settings" -> SettingsScreen(
                app,
                onBack = { pop() },
                backLabel = stack.getOrNull(stack.size - 2)
                    ?.takeIf { it.startsWith("session:") }
                    ?.let { app.nodes.get(it.removePrefix("session:"))?.name },
            )
            target == "import" -> ImportConfigScreen(app, onDone = { pop() })
            target.startsWith("edit:") -> NodeEditor(app, app.nodes.get(target.removePrefix("edit:").ifEmpty { null }), onDone = { pop() })
            target.startsWith("session:") -> {
                val nodeId = target.removePrefix("session:")
                val connection = connections[nodeId]
                if (connection == null) {
                    pop()
                } else {
                    SessionScreen(
                        app = app,
                        connection = connection,
                        focusPane = opened.value?.takeIf { it.first == nodeId }?.second,
                        dark = dark,
                        onLeave = {
                            opened.value = null
                            stack = listOf(START)
                        },
                        onSettings = { push("session-settings") },
                    )
                }
            }
            // "inbox" stays the route key of Agents, so saved stacks and deep links keep landing here.
            target == "inbox" -> InboxScreen(app, onOpen = { nodeId, pane -> openSession(nodeId, pane) })
            else -> NodesScreen(app, navigation)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f)) {
            if (railVisible) {
                AppRail(
                    selected = when {
                        route == "settings" || route == "session-settings" -> "settings"
                        sessionLevel -> null
                        route == "inbox" -> "inbox"
                        else -> START
                    },
                    needsYou = needsBadge,
                    updateDot = updateDot,
                    onDestination = { switch(it) },
                    modifier = Modifier,
                )
            }
            // Screens cross-fade with a slight zoom, as COUI pages do.
            Box(Modifier.weight(1f)) {
                AnimatedContent(
                    targetState = route,
                    transitionSpec = {
                        (fadeIn(tween(220, delayMillis = 40)) + scaleIn(tween(260), initialScale = 0.97f)) togetherWith
                            (fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 1.02f))
                    },
                    label = "screen",
                ) { target ->
                    saved.SaveableStateProvider(target) {
                        CompositionLocalProvider(LocalWindowClass provides windowClass) { Screen(target) }
                    }
                }
            }
        }
        if (showBar) {
            AppBottomBar(
                selected = if (route == "inbox" || route == "settings" || route == "session-settings") route else START,
                needsYou = needsBadge,
                updateDot = updateDot,
                onDestination = { switch(it) },
            )
        }
    }

    askPasswords?.let { nodeId ->
        val node = app.nodes.get(nodeId)
        val hops = node?.let(app::passwordsToAsk)
        if (node == null || hops.isNullOrEmpty()) {
            askPasswords = null
        } else {
            PasswordsDialog(
                hops = hops,
                onConnect = { secrets, remember ->
                    askPasswords = null
                    app.connect(node, secrets, dark, remember)
                    openSession(nodeId, null)
                },
                onDismiss = { askPasswords = null },
            )
        }
    }
}

@Composable
private fun AppBottomBar(selected: String, needsYou: Int, updateDot: Boolean, onDestination: (String) -> Unit) {
    NavigationBar {
        NavigationBarItem(
            selected = selected == START,
            onClick = { onDestination(START) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.dest_hosts)) },
        )
        NavigationBarItem(
            selected = selected == "inbox",
            onClick = { onDestination("inbox") },
            icon = {
                BadgedBox(badge = { if (needsYou > 0) Badge { Text(needsYou.toString()) } }) {
                    Icon(if (selected == "inbox") AgentsIconFilled else AgentsIconOutlined, contentDescription = null)
                }
            },
            label = { Text(stringResource(R.string.dest_agents)) },
        )
        NavigationBarItem(
            selected = selected == "settings" || selected == "session-settings",
            onClick = { onDestination("settings") },
            icon = { SettingsIcon(updateDot) },
            label = { Text(stringResource(R.string.settings)) },
            modifier = settingsDescription(updateDot),
        )
    }
}

@Composable
private fun AppRail(selected: String?, needsYou: Int, updateDot: Boolean, onDestination: (String) -> Unit, modifier: Modifier) {
    NavigationRail(modifier = modifier) {
        Spacer(Modifier.weight(1f))
        NavigationRailItem(
            selected = selected == START,
            onClick = { onDestination(START) },
            icon = { Icon(Icons.Filled.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.dest_hosts)) },
        )
        NavigationRailItem(
            selected = selected == "inbox",
            onClick = { onDestination("inbox") },
            icon = {
                BadgedBox(badge = { if (needsYou > 0) Badge { Text(needsYou.toString()) } }) {
                    Icon(if (selected == "inbox") AgentsIconFilled else AgentsIconOutlined, contentDescription = null)
                }
            },
            label = { Text(stringResource(R.string.dest_agents)) },
        )
        NavigationRailItem(
            selected = selected == "settings" || selected == "session-settings",
            onClick = { onDestination("settings") },
            icon = { SettingsIcon(updateDot) },
            label = { Text(stringResource(R.string.settings)) },
            modifier = settingsDescription(updateDot),
        )
        Spacer(Modifier.weight(1f))
    }
}

/** Settings' icon, with the stable-update dot while one is offered; a candidate never raises it. */
@Composable
private fun SettingsIcon(updateDot: Boolean) {
    BadgedBox(badge = { if (updateDot) Badge(containerColor = MaterialTheme.colorScheme.primaryContainer) }) {
        Icon(Icons.Filled.Settings, contentDescription = null)
    }
}

/** The dot is never the only cue: TalkBack reads "Settings, stable update available". */
@Composable
private fun settingsDescription(updateDot: Boolean): Modifier {
    val description = stringResource(R.string.settings_update_available)
    return if (updateDot) Modifier.semantics { contentDescription = description } else Modifier
}
