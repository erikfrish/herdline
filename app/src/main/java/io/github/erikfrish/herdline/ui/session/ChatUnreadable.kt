package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptRefusal
import io.github.erikfrish.herdline.ui.chat.RefusalWords
import io.github.erikfrish.herdline.ui.chat.refusalWords

/** What the Chat-unreadable snackbar says, and whether it offers Help. */
internal data class UnreadableNotice(val words: RefusalWords, val help: Boolean = false)

/**
 * The words for Chat falling back to Terminal on [agent] (its display name).
 * A typed refusal names its own cause, so it is said as such and never
 * blamed on the host. An untyped failure (a timeout, a lost link, a session
 * Herdr has not reported) names no cause: if [everRead] (a transcript of
 * this agent kind loaded on this connection) the passing text; if never,
 * the agent's current session cannot be read yet, with Help as diagnosis.
 * Nothing here can tell that an integration is missing (C9).
 */
internal fun unreadableNotice(refusal: TranscriptRefusal?, agent: String, everRead: Boolean): UnreadableNotice = when {
    refusal != null -> UnreadableNotice(refusalWords(refusal, agent))
    everRead -> UnreadableNotice(RefusalWords(R.string.session_chat_unreadable))
    else -> UnreadableNotice(RefusalWords(R.string.session_chat_unreadable_host, listOf(agent)), help = true)
}
