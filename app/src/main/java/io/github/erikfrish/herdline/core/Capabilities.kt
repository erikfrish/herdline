package io.github.erikfrish.herdline.core

import io.github.erikfrish.herdline.data.Backend
import org.json.JSONObject

/** The frozen reason codes native gives for an action it does not perform; the UI localizes them (ReasonText.kt). */
object ReasonCode {
    /** Typing or resizing needs the phone to own the core's size: an explicit Enter interactive control first. */
    const val TAKES_SIZE = "takes_size"

    /** herdr-core is installed and updated by the user on the host, never by the app. */
    const val CORE_MANAGED = "core_managed"

    /** The pane's program never reported its own session identity, so Chat cannot read or send to it. */
    const val IDENTITY_UNKNOWN = "identity_unknown"

    /** A desktop JS mod panel: herdr-app draws it, the phone cannot. */
    const val MOD_PANEL = "mod_panel"

    /** The pane's terminal is gone on the core. */
    const val TERMINAL_LOST = "terminal_lost"

    /** The pane's program ended on the core; nothing runs there to type into. */
    const val TERMINAL_FINISHED = "terminal_finished"

    /** The core has yet to start or restart the pane's kept program. */
    const val TERMINAL_STARTING = "terminal_starting"

    /** The connected backend does not offer the action at all. */
    const val UNSUPPORTED_BACKEND = "unsupported_backend"
}

/** Capability action keys, as native names them in `Connected.capabilities`. */
object Action {
    /**
     * The frozen keys that are also the request method they gate; a request
     * with one of these names is never sent while its capability is off.
     */
    val REQUESTS = setOf(
        "workspace.create", "workspace.rename", "workspace.close",
        "tab.create", "tab.rename", "tab.close",
        "pane.split", "pane.close", "pane.focus", "pane.zoom",
    )
}

/**
 * What the connected backend does: each action key maps to null when it is
 * supported, or to the [ReasonCode] it is disabled for. A disabled action is
 * never shown as working.
 */
class Capabilities private constructor(private val reasons: Map<String, String?>, private val backend: Backend) {
    /**
     * Why [action] is off, or null when it is supported. An action herdr-core
     * leaves out of its map is not offered; Herdr predates the map and
     * supports everything it already did.
     */
    fun reason(action: String): String? = when {
        reasons.containsKey(action) -> reasons[action]
        backend == Backend.HerdrCore -> ReasonCode.UNSUPPORTED_BACKEND
        else -> null
    }

    fun supports(action: String): Boolean = reason(action) == null

    override fun equals(other: Any?) = other is Capabilities && other.reasons == reasons && other.backend == backend

    override fun hashCode() = reasons.hashCode() * 31 + backend.hashCode()

    override fun toString() = "Capabilities($backend, $reasons)"

    companion object {
        /** Herdr as it was before the map: everything it already offered. */
        val HERDR = Capabilities(emptyMap(), Backend.Herdr)

        fun parse(o: JSONObject?, backend: Backend): Capabilities {
            o ?: return Capabilities(emptyMap(), backend)
            val reasons = HashMap<String, String?>()
            for (key in o.keys()) reasons[key] = if (o.isNull(key)) null else o.optString(key)
            return Capabilities(reasons, backend)
        }
    }
}
