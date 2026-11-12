package io.github.erikfrish.herdline.ui.chat

import android.content.Context
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptRefusal

/**
 * What Chat says for a typed refusal, used whole in place of
 * `chat_unavailable` and `session_chat_unreadable`, whose "switches back"
 * promise is false for these causes. Null for a format this app has no
 * words for; the caller then says the general text. [agent] is the
 * program's display name.
 */
fun Context.refusalText(refusal: TranscriptRefusal, agent: String): String? = when (refusal) {
    TranscriptRefusal.OutsideHome -> getString(R.string.chat_refused_outside_home, agent)
    is TranscriptRefusal.UnsupportedFormat -> when (refusal.format) {
        "managed_session_journal" -> getString(R.string.chat_refused_qwen_managed)
        else -> null
    }
    // A format Chat can't name is said without a number, never as "unknown".
    is TranscriptRefusal.UnsupportedVersion -> when (refusal.agent) {
        "hermes" -> refusal.found?.let { getString(R.string.chat_refused_hermes_version, it, refusal.supported) }
            ?: getString(R.string.chat_refused_hermes_format_unknown, refusal.supported)
        else -> null
    }
}
