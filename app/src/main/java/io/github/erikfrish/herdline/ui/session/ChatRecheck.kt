package io.github.erikfrish.herdline.ui.session

/** What a Chat tap or an agent change does to a pane Chat could not read (F-OBS1). */
internal enum class ChatRecheck {
    /** Read the pane once. */
    Read,
    /** Say why Chat is unreadable, as before; no read. */
    Notice,
    Nothing,
}

/**
 * A Chat tap on an unreadable pane reads it once, unless the reason is a
 * typed refusal, which no read ends. An [agentChanged] update reads only
 * while no composed Chat polls the pane already ([polling]): a status-only
 * tick is not a change.
 */
internal fun chatRecheck(tap: Boolean, agentChanged: Boolean, unreadable: Boolean, lasting: Boolean, polling: Boolean): ChatRecheck = when {
    !unreadable -> ChatRecheck.Nothing
    lasting -> if (tap) ChatRecheck.Notice else ChatRecheck.Nothing
    tap -> ChatRecheck.Read
    agentChanged && !polling -> ChatRecheck.Read
    else -> ChatRecheck.Nothing
}

/** A Chat tap still waiting for its re-check: the pane and tab it was made on. */
internal data class PendingChatTap(val pane: String, val tab: String?)

/**
 * A good re-check switches to Chat only for a tap still [pending] on the
 * same pane and tab: Terminal input or a Terminal choice since clears it.
 */
internal fun switchesToChat(pending: PendingChatTap?, pane: String?, tab: String?): Boolean =
    pending != null && pending.pane == pane && pending.tab == tab
