/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: renamed to the Herdline package and identity. */
package io.github.erikfrish.herdline.data

import org.json.JSONObject
import java.util.UUID

enum class AuthMethod { Key, Password }

/**
 * Which server a node's SSH route ends at: the classic Herdr daemon, or
 * herdr-core shared with herdr-app. [key] is the frozen string saved with the
 * node and passed to the native config.
 */
enum class Backend(val key: String) {
    Herdr("herdr"),
    HerdrCore("herdr_core"),
    ;

    companion object {
        /**
         * The backend saved or reported as [key]; null for a key this app does
         * not know, which is never taken for another backend. Only a missing or
         * null key, from before the choice existed, means Herdr: see [Node.fromJson].
         */
        fun fromKey(key: String): Backend? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A saved SSH server: a machine whose Herdr daemon Herdline attaches to, or a
 * jump host on the way to one. A node reached through another names it in
 * [jumpId]; chains are resolved by [NodeStore.route].
 */
data class Node(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val address: String,
    val port: Int = 22,
    val user: String,
    val session: String = "default",
    val auth: AuthMethod = AuthMethod.Key,
    /** The keychain identity to sign in with; null means the device key, which may fall back to a password. */
    val identityId: String? = null,
    val jumpId: String? = null,
    val group: String = "",
    val color: Int = 0,
    /** SSH keepalive in seconds; 0 turns it off. */
    val keepAlive: Int = 15,
    val lastUsed: Long = 0,
    /** The saved [Backend.key], kept as saved even when this app does not know it. */
    val backendKey: String = Backend.Herdr.key,
) {
    /** The server this node attaches to; null for an unknown [backendKey], which is never connected. */
    val backend: Backend? get() = Backend.fromKey(backendKey)
    val title: String get() = name.ifBlank { address }
    val destination: String get() = if (port == 22) "$user@$address" else "$user@$address:$port"

    /** How the known-hosts list names this server, as the SSH layer does. */
    val hostKeyName: String get() = "$address:$port"

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("address", address)
        .put("port", port)
        .put("user", user)
        .put("session", session)
        .put("auth", auth.name)
        .put("identity", identityId)
        .put("jump", jumpId)
        .put("group", group)
        .put("color", color)
        .put("keepalive", keepAlive)
        .put("last_used", lastUsed)
        .put("backend", backendKey)

    companion object {
        fun fromJson(o: JSONObject) = Node(
            id = o.getString("id"),
            name = o.optString("name", o.optString("label")),
            address = o.getString("address"),
            port = o.optInt("port", 22),
            user = o.getString("user"),
            session = o.optString("session", "default").ifBlank { "default" },
            auth = runCatching { AuthMethod.valueOf(o.optString("auth")) }.getOrDefault(AuthMethod.Key),
            identityId = o.optString("identity").takeUnless { o.isNull("identity") || it.isEmpty() },
            jumpId = o.optString("jump").takeUnless { o.isNull("jump") || it.isEmpty() },
            group = o.optString("group"),
            color = o.optInt("color"),
            keepAlive = o.optInt("keepalive", 15),
            lastUsed = o.optLong("last_used"),
            backendKey = if (o.isNull("backend")) Backend.Herdr.key else o.optString("backend"),
        )

        /**
         * hc session names, as herdr-core accepts them: blank means its default
         * session; `.`, `..`, control characters and the characters core's own
         * path rule refuses are rejected. Native shell-quotes the rest.
         */
        fun validCoreSession(name: String) = name.trim().let { s ->
            s != "." && s != ".." && s.none { it.isISOControl() || it in "/\\:*?\"<>|" }
        }

        fun validSession(backend: Backend?, name: String) =
            if (backend == Backend.HerdrCore) validCoreSession(name) else validSession(name)

        /** Herdr session names are plain path components. */
        fun validSession(name: String) =
            name.isNotEmpty() && name.length <= 64 && name.all { it.isLetterOrDigit() || it in "-_." } &&
                name != "." && name != ".."

        fun validAddress(address: String) =
            address.isNotBlank() && address.none { it.isWhitespace() || it == '@' || it == '/' }

        fun validUser(user: String) = user.isNotBlank() && user.none { it.isWhitespace() || it == '@' || it == ':' }

        /** Longest jump chain followed before giving up. */
        const val MAX_HOPS = 8
    }
}
