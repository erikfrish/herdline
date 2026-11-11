package io.github.erikfrish.herdline.core

import org.json.JSONObject

/**
 * What the daemon state allows the composer to do with one pane, from the
 * native checks (`composer.rs`), recomputed on every frame and snapshot.
 */
data class ComposerCheck(
    val boot: String?,
    val paneExists: Boolean,
    val visible: Boolean,
    /** The program Herdr recognised in the pane; null for a plain terminal. */
    val agent: String?,
    val status: AgentStatus?,
    /** "omp", "terminal", or "other_agent". */
    val program: String,
    /** "empty", "draft", "unrecognized", or "not_applicable". */
    val editor: String,
    val insert: Gate,
    val send: Gate,
    /** Steer's paste gate; only while it is ok does the Steer action show. */
    val steer: Gate,
) {
    data class Gate(val ok: Boolean, val reason: String?)

    companion object {
        fun parse(json: String): ComposerCheck {
            val o = JSONObject(json)
            fun gate(key: String) = o.optJSONObject(key)?.let {
                Gate(it.optBoolean("ok"), if (it.isNull("reason")) null else it.optString("reason"))
            } ?: Gate(false, "not_connected")
            fun str(key: String) = if (o.isNull(key)) null else o.optString(key)
            return ComposerCheck(
                boot = str("boot"),
                paneExists = o.optBoolean("pane_exists"),
                visible = o.optBoolean("visible"),
                agent = str("agent"),
                status = str("status")?.let { AgentStatus.parse(it) },
                program = o.optString("program"),
                editor = o.optString("editor"),
                insert = gate("insert"),
                send = gate("send"),
                steer = gate("steer"),
            )
        }
    }
}
