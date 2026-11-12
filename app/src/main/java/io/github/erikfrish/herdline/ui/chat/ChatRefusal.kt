package io.github.erikfrish.herdline.ui.chat

import android.content.Context
import androidx.annotation.StringRes
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptRefusal

/** A refusal's string and the arguments it is formatted with. */
internal data class RefusalWords(@StringRes val res: Int, val args: List<Any> = emptyList())

/**
 * Which words say [refusal]: the app's own strings only, never the core's
 * message. A cause this app has no words of its own for gets the general
 * refusal, never the "switches back" texts, which are false for every typed
 * refusal. [agent] is the program's display name.
 */
internal fun refusalWords(refusal: TranscriptRefusal, agent: String): RefusalWords = when (refusal) {
    TranscriptRefusal.OutsideHome -> RefusalWords(R.string.chat_refused_outside_home, listOf(agent))
    is TranscriptRefusal.UnsupportedFormat -> when (refusal.format) {
        "managed_session_journal" -> RefusalWords(R.string.chat_refused_qwen_managed)
        // Kilo's, Grok's, Copilot's, Mastra Code's or Kimi's store resolved
        // outside the home folder (Kimi also when its store is not an absolute
        // path): the same cause as OutsideHome.
        "kilo_store_outside_home", "grok_store_outside_home", "copilot_store_outside_home",
        "mastracode_store_outside_home", "kimi_store_outside_home" ->
            RefusalWords(R.string.chat_refused_outside_home, listOf(agent))
        // Not a form Chat can't read: the store holds no one session Chat can stand behind.
        GROK_SESSION_AMBIGUOUS -> RefusalWords(R.string.chat_refused_ambiguous, listOf(agent))
        // Grok's own list of open sessions names another session for the pane's
        // process than Herdr does, or none: either way Chat stands behind neither.
        "grok_session_stale" -> RefusalWords(R.string.chat_refused_stale, listOf(agent))
        "grok_session_unregistered" -> RefusalWords(R.string.chat_refused_unregistered, listOf(agent))
        "copilot_session_mismatch" -> RefusalWords(R.string.chat_refused_mismatch, listOf(agent))
        else -> RefusalWords(R.string.chat_refused_general, listOf(agent))
    }
    // A format Chat can't name is said without a number, never as "unknown".
    is TranscriptRefusal.UnsupportedVersion -> when (refusal.agent) {
        "hermes" -> refusal.found?.let { RefusalWords(R.string.chat_refused_hermes_version, listOf(it, refusal.supported)) }
            ?: RefusalWords(R.string.chat_refused_hermes_format_unknown, listOf(refusal.supported))
        else -> RefusalWords(R.string.chat_refused_general, listOf(agent))
    }
}

/** Grok's store holds no one session Chat can stand behind: Chat refuses, and the card says so. */
private const val GROK_SESSION_AMBIGUOUS = "grok_session_ambiguous"

/**
 * The Grok refusals where no one session for the pane can be stood behind:
 * two candidates, or Grok's own open-session list naming another or none.
 */
private val GROK_NO_ONE_SESSION = setOf(GROK_SESSION_AMBIGUOUS, "grok_session_stale", "grok_session_unregistered")

/** Whether [refusal] leaves the card's Grok session rows NotYet AmbiguousSession. */
internal fun ambiguousSession(refusal: TranscriptRefusal?): Boolean =
    (refusal as? TranscriptRefusal.UnsupportedFormat)?.format in GROK_NO_ONE_SESSION

/**
 * What Chat says for a typed refusal, used whole in place of
 * `chat_unavailable` and `session_chat_unreadable`, whose "switches back"
 * promise is false for these causes.
 */
fun Context.refusalText(refusal: TranscriptRefusal, agent: String): String =
    refusalWords(refusal, agent).let { getString(it.res, *it.args.toTypedArray()) }
