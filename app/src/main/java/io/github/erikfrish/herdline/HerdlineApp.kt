/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline

import android.app.Application
import android.content.Context
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.HerdrSetup
import io.github.erikfrish.herdline.core.Native
import io.github.erikfrish.herdline.core.Routes
import io.github.erikfrish.herdline.data.AppLanguages
import io.github.erikfrish.herdline.data.Keychain
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.data.NodeStore
import io.github.erikfrish.herdline.data.RouteException
import io.github.erikfrish.herdline.data.RouteProblem
import io.github.erikfrish.herdline.data.IconVariants
import io.github.erikfrish.herdline.data.SettingsStore
import io.github.erikfrish.herdline.data.TargetStore
import io.github.erikfrish.herdline.data.UpdateChecker
import io.github.erikfrish.herdline.service.AgentMonitor
import io.github.erikfrish.herdline.service.AgentNotifications
import io.github.erikfrish.herdline.service.ConnectionService
import io.github.erikfrish.herdline.ui.terminal.FontManager
import io.github.erikfrish.herdline.ui.theme.sessionAccent
import io.github.erikfrish.herdline.ui.theme.terminalScheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Process-wide state. Connections outlive activities, as Herdr sessions
 * outlive clients: leaving the screen keeps the attachment, and only an
 * explicit disconnect ends it. Disconnecting never stops the daemon.
 */
class HerdlineApp : Application() {
    lateinit var nodes: NodeStore
        private set
    lateinit var keychain: Keychain
        private set
    lateinit var settings: SettingsStore
        private set
    lateinit var fonts: FontManager
        private set
    lateinit var updates: UpdateChecker
        private set
    lateinit var monitor: AgentMonitor
        private set
    lateinit var targets: TargetStore
        private set

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _connections = MutableStateFlow<Map<String, Connection>>(emptyMap())
    val connections: StateFlow<Map<String, Connection>> = _connections

    /** Whether an activity is showing, so notifications only go out when not. */
    @Volatile
    var foreground = false

    /** The node whose session screen is resumed, if any: its notifications are its banner, not the system's (BN-1). */
    @Volatile
    var viewingSession: String? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguages.wrap(base))

    override fun onCreate() {
        super.onCreate()
        instance = this
        nodes = NodeStore(this)
        keychain = Keychain(this)
        settings = SettingsStore(this)
        fonts = FontManager(this)
        updates = UpdateChecker(this)
        monitor = AgentMonitor(this)
        targets = TargetStore(this)
        scope.launch {
            settings.settings.map { it.iconVariant }.distinctUntilChanged().collect { variant ->
                withContext(Dispatchers.IO) { IconVariants.apply(this@HerdlineApp, variant) }
                // Re-posts held notes with the new large icon, like the
                // privacy re-word below. Never ConnectionService.update:
                // foreground start limits would refuse the call.
                AgentNotifications.republish(this@HerdlineApp, settings.settings.value.lockDetail)
            }
        }
        // The privacy setting re-words the agent notes even with no service
        // running: notes held after a disconnect take the new setting's
        // locked words too (lab.7.3 J1b).
        scope.launch {
            settings.settings.map { it.lockDetail }.distinctUntilChanged().collect { AgentNotifications.republish(this@HerdlineApp, it) }
        }
        AgentNotifications.createChannels(this)
        AgentNotifications.dropOrphans(this)
        scope.launch(Dispatchers.IO) { keychain.device(getString(R.string.device_key_name)) }
    }

    fun connection(nodeId: String): Connection? = _connections.value[nodeId]

    /**
     * Attach to [node] along its jump hosts, reusing a live connection.
     * [passwords] maps node IDs on the route to passwords typed for this
     * connection; [remember] saves them to the keychain, else any saved
     * password for those hops is forgotten. Hops without one use their saved
     * password.
     */
    fun connect(node: Node, passwords: Map<String, String>, dark: Boolean, remember: Boolean = false): Connection {
        _connections.value[node.id]?.takeIf { it.isOpen }?.let { return it }
        _connections.value[node.id]?.close()
        val connection = Connection(node)
        _connections.value = _connections.value + (node.id to connection)
        nodes.touch(node)
        scope.launch(Dispatchers.IO) {
            routeDocument(node, passwords, dark, remember).fold(connection::start) { connection.fail(it.message.orEmpty()) }
        }
        ConnectionService.update(this)
        return connection
    }

    /** The route document for [node], or a failure whose message says why not. Call off the main thread. */
    private fun routeDocument(node: Node, passwords: Map<String, String>, dark: Boolean, remember: Boolean): Result<String> {
        val route = nodes.route(node).getOrElse {
            return Result.failure(IllegalStateException(routeMessage((it as? RouteException)?.problem)))
        }
        keychain.device(getString(R.string.device_key_name))
        // A password typed but not remembered also clears any saved one,
        // which is how a rejected saved password is replaced or dropped.
        if (remember) passwords.forEach(keychain::savePassword) else passwords.keys.forEach(keychain::forgetPassword)
        val settings = settings.settings.value
        val scheme = settings.terminalScheme(dark)
            .withCursor(sessionAccent(settings.accent, node.color))
        return runCatching { Routes.document(route, nodes, keychain, passwords, this.settings.lastGrid, scheme) }
            .recoverCatching { throw IllegalStateException(getString(R.string.key_unreadable)) }
    }

    /**
     * Install or update Herdr on [node]'s host, only because the user asked,
     * then reconnect. The node's connection shows the progress and any failure.
     */
    fun setupHerdr(node: Node, passwords: Map<String, String>, dark: Boolean, remember: Boolean = false) {
        val connection = _connections.value[node.id] ?: return
        val current = nodes.get(node.id) ?: node
        connection.settingUp()
        scope.launch(Dispatchers.IO) {
            val document = routeDocument(current, passwords, dark, remember).getOrElse {
                return@launch connection.setupFailed(HerdrSetup(null, it.message.orEmpty()))
            }
            val setup = try {
                HerdrSetup.parse(Native.setupHerdr(document))
            } catch (e: IOException) {
                HerdrSetup(null, e.message.orEmpty())
            }
            if (!setup.succeeded) return@launch connection.setupFailed(setup)
            withContext(Dispatchers.Main) {
                // Leaving the screen meanwhile disconnects; then there is nothing to resume.
                if (_connections.value[node.id] === connection) reconnect(current, passwords, dark, remember)
            }
        }
    }

    /** Nodes on [node]'s route that sign in with a password and have none saved. */
    fun passwordsToAsk(node: Node): List<Node> =
        nodes.route(node).getOrNull()?.let { route -> Routes.unsavedPasswordHops(route) { keychain.hasPassword(it.id) } }.orEmpty()

    private fun routeMessage(problem: RouteProblem?): String = when (problem) {
        is RouteProblem.MissingJump -> getString(R.string.route_missing_jump, problem.node.title)
        is RouteProblem.Loop -> getString(R.string.route_loop, problem.node.title)
        RouteProblem.TooLong, null -> getString(R.string.route_too_long, Node.MAX_HOPS)
    }

    /** Retry after the user trusted a host key or changed credentials. */
    fun reconnect(node: Node, passwords: Map<String, String>, dark: Boolean, remember: Boolean = false): Connection {
        drop(node.id)
        return connect(nodes.get(node.id) ?: node, passwords, dark, remember)
    }

    fun disconnect(nodeId: String) {
        if ((_connections.value - nodeId).isEmpty()) holdNotes()
        drop(nodeId)
    }

    fun disconnectAll() {
        holdNotes()
        _connections.value.values.forEach { it.close() }
        _connections.value = emptyMap()
        ConnectionService.update(this)
    }

    private fun drop(nodeId: String) {
        _connections.value[nodeId]?.close()
        _connections.value = _connections.value - nodeId
        ConnectionService.update(this)
    }

    /**
     * The last connection is going: the notes take their locked words before
     * the link closes, so no re-post that follows can carry the full ones
     * (lab.7.3 J1).
     */
    private fun holdNotes() = AgentNotifications.hold(this, settings.settings.value.lockDetail)

    companion object {
        lateinit var instance: HerdlineApp
            private set
    }
}
