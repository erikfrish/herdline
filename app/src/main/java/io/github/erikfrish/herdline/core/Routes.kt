/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.core

import io.github.erikfrish.herdline.data.AuthMethod
import io.github.erikfrish.herdline.data.Keychain
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.data.NodeStore
import io.github.erikfrish.herdline.ui.theme.TerminalScheme
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the route document `native/src/config.rs` reads: every jump host in
 * order, then the node, each with its pinned host key and credential. Private
 * keys and saved passwords are decrypted from the keychain here and go
 * straight to the core; a password typed for this connection wins for
 * password hops, and rides along on an implicit device-key hop when that
 * key was rejected and a password fallback is under way. It never
 * displaces a specifically chosen key.
 */
object Routes {
    /**
     * Whether [node] signs in with the implicit device key: key auth with no
     * specific key chosen. Explicit keys never fall back to a password.
     */
    fun usesDeviceKey(node: Node): Boolean = node.auth == AuthMethod.Key && node.identityId == null

    /**
     * Whether a typed rejection of [node]'s sign-in may fall back to its
     * password: only the implicit device key, never host-key problems, key
     * read failures, network failures, or a specifically chosen key.
     */
    fun fallbackEligible(node: Node): Boolean = usesDeviceKey(node)

    /**
     * The credential for node id [nodeId] on this route: a password typed
     * for this attempt when there is one, else the saved one looked up by
     * [saved].
     */
    fun usePassword(nodeId: String, passwords: Map<String, String>, saved: (String) -> String?): String =
        passwords[nodeId] ?: saved(nodeId).orEmpty()

    /** Nodes in [route] that sign in with a password. */
    fun passwordHops(route: List<Node>): List<Node> = route.filter { it.auth == AuthMethod.Password }

    /** Password nodes in [route] with no saved password, which must be asked for. */
    fun unsavedPasswordHops(route: List<Node>, saved: (Node) -> Boolean): List<Node> =
        passwordHops(route).filterNot(saved)

    fun document(
        route: List<Node>,
        nodes: NodeStore,
        keychain: Keychain,
        passwords: Map<String, String>,
        grid: IntArray,
        scheme: TerminalScheme,
    ): String {
        val target = route.last()
        val hops = JSONArray()
        for (node in route) {
            val auth = when {
                node.auth == AuthMethod.Password ->
                    JSONObject().put("type", "password").put("password", usePassword(node.id, passwords, keychain::password))
                // A typed password overrides the implicit device key only
                // while a fallback attempt is under way; a specific key is
                // strict and never displaced by a password.
                passwords.containsKey(node.id) && usesDeviceKey(node) ->
                    JSONObject().put("type", "password").put("password", passwords.getValue(node.id))
                else -> JSONObject()
                    .put("type", "key")
                    .put("private", keychain.privateKey(node.identityId))
                    .put("certificate", keychain.get(node.identityId)?.certificate)
            }
            hops.put(
                JSONObject()
                    .put("host", node.address)
                    .put("port", node.port)
                    .put("user", node.user)
                    .put("pinned", nodes.trustedKey(node.hostKeyName))
                    .put("auth", auth),
            )
        }
        return JSONObject()
            .put("session", target.session)
            .put("hops", hops)
            .put("keepalive_secs", target.keepAlive)
            .put("cols", grid[0])
            .put("rows", grid[1])
            .put("cell_width", grid[2])
            .put("cell_height", grid[3])
            .put("theme", scheme.toJson())
            .toString()
    }
}
