package io.github.erikfrish.herdline.data

import android.content.Context
import android.content.SharedPreferences

/**
 * UR3: whether the user agreed, once per host, that Herdline copies picked
 * files to that host's ~/.cache/herdline/attachments. Asked
 * before the first upload; nothing is uploaded to a host without it.
 *
 * The agreement belongs to one saved server as it is now: its id together
 * with the account, address, port and jump route the files would go to. An
 * edited address, user or route names another machine or home, so it asks
 * again; it never carries over to another host.
 */
class AttachConsent internal constructor(private val prefs: SharedPreferences) {
    fun granted(node: Node): Boolean = prefs.getBoolean(key(node), false)

    fun grant(node: Node) {
        prefs.edit().putBoolean(key(node), true).apply()
    }

    companion object {
        @Volatile
        private var instance: AttachConsent? = null

        fun get(context: Context): AttachConsent = instance ?: synchronized(this) {
            instance ?: AttachConsent(context.applicationContext.getSharedPreferences("attach_consent", Context.MODE_PRIVATE))
                .also { instance = it }
        }

        private fun key(node: Node): String =
            listOf(node.id, node.user, node.address, node.port.toString(), node.jumpId.orEmpty()).joinToString("\n")
    }
}
