package io.github.erikfrish.herdline.ui.nodes

import io.github.erikfrish.herdline.data.Node

/**
 * The parts of a destination pasted into the address field. Null [user] or
 * [port] = not in it; [port] is the text as pasted, so a bad one lands in the
 * Port field and is named there instead of being dropped.
 */
internal data class PastedAddress(val address: String, val user: String?, val port: String?)

/**
 * Splits a pasted `ssh://user@host:port`, `user@host:port`, `host:port` or
 * `[v6]:port` into the editor's fields, as people copy them from a shell or
 * a README. Null when [text] is a plain host already, or not one destination:
 * then it is kept as typed and the field explains what is wrong.
 */
internal fun splitPastedAddress(text: String): PastedAddress? {
    var rest = text.trim()
    if (rest.startsWith("ssh://", ignoreCase = true)) rest = rest.substring("ssh://".length).removeSuffix("/")
    if (rest.isEmpty() || rest.any { it.isWhitespace() || it == '/' }) return null

    val at = rest.indexOf('@')
    val user = if (at < 0) null else rest.substring(0, at).takeIf { Node.validUser(it) } ?: return null
    val host = if (at < 0) rest else rest.substring(at + 1)

    val address: String
    val port: String?
    if (host.startsWith('[')) {
        val close = host.indexOf(']')
        if (close < 0) return null
        address = host.substring(1, close)
        val after = host.substring(close + 1)
        port = when {
            after.isEmpty() -> null
            after.startsWith(':') -> after.substring(1)
            else -> return null
        }
    } else if (host.count { it == ':' } == 1) {
        address = host.substringBefore(':')
        port = host.substringAfter(':')
    } else {
        // No colon, or a bare IPv6 address whose colons are not a port.
        address = host
        port = null
    }
    if (address.isEmpty() || !Node.validAddress(address)) return null
    // Nothing to split: the text is the address as it is.
    if (user == null && port == null && address == text.trim()) return null
    return PastedAddress(address, user, port)
}

/**
 * Why [address] cannot be saved as typed, or null. One colon is a port typed
 * into the address (a bare IPv6 address has at least two), which SSH would
 * read as part of the host name. A node saved that way before the rule keeps
 * saving while its address is [saved] unchanged: the rule must not lock
 * anyone out of their own node; the editor offers the split instead
 * ([portInAddress]).
 */
internal fun addressProblem(address: String, saved: String? = null): AddressProblem? = when {
    address.isBlank() -> AddressProblem.Missing
    !Node.validAddress(address) -> AddressProblem.Invalid
    portInAddress(address) != null && address != saved -> AddressProblem.PortInAddress
    else -> null
}

internal enum class AddressProblem { Missing, Invalid, PortInAddress }

/** [address] as host and port when it holds exactly one colon (the one-tap split), else null. */
internal fun portInAddress(address: String): PastedAddress? =
    if (address.count { it == ':' } == 1) splitPastedAddress(address) else null
