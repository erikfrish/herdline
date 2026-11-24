package io.github.erikfrish.herdline.core

import java.io.IOException
import org.json.JSONException
import org.json.JSONObject

/**
 * The Chat integration in OMP's own extension discovery on the host, from
 * [Native.ompChatIntegration]. [path] is the folder the host uses, when it
 * got that far; [message] is the host's exact error, set only for [State.Error].
 */
data class OmpChatIntegration(
    val state: State,
    val path: String?,
    val files: List<String>,
    val message: String?,
) {
    enum class State { Absent, Installed, Outdated, Error }

    /** What the user asked the host for. */
    enum class Action(val wire: String) { Status("status"), Install("install"), Uninstall("uninstall") }

    companion object {
        fun parse(json: String): OmpChatIntegration = JSONObject(json).let { reply ->
            val state = when (val word = reply.getString("state")) {
                "absent" -> State.Absent
                "installed" -> State.Installed
                "outdated" -> State.Outdated
                "error" -> State.Error
                else -> throw JSONException("unknown state $word")
            }
            val files = reply.optJSONArray("files")
            OmpChatIntegration(
                state = state,
                path = if (reply.isNull("path")) null else reply.getString("path"),
                files = files?.let { array -> List(array.length()) { array.getString(it) } }.orEmpty(),
                message = if (reply.isNull("message")) null else reply.getString("message"),
            )
        }
    }
}

/**
 * Runs [action] on the connected host. Status is a read; install and
 * uninstall run only because the user asked. A running OMP is not changed:
 * it loads the integration at its next start. Blocks on the host: call it
 * off the main thread. A failure to reach the host is a [Result.failure],
 * never taken for [OmpChatIntegration.State.Absent].
 */
fun Connection.ompChatIntegration(action: OmpChatIntegration.Action): Result<OmpChatIntegration> {
    val session = link()
    if (session == 0L) return Result.failure(IOException("not_connected"))
    return try {
        Result.success(OmpChatIntegration.parse(Native.ompChatIntegration(session, action.wire)))
    } catch (e: IOException) {
        Result.failure(e)
    } catch (e: JSONException) {
        Result.failure(IOException(e.message, e))
    }
}
