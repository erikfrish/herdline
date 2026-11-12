package io.github.erikfrish.herdline.ui.session

/** What a Chat tap or an agent change does to a pane Chat could not read (F-OBS1). */
internal enum class ChatRecheck {
    /** Read the pane once. */
    Read,
    /** Show Chat now on the history this connection holds for the pane; its own poll goes on from there. */
    Show,
    Nothing,
}

/**
 * A Chat tap on an unreadable pane always answers, even under a typed
 * refusal (FOBS1-R3): on the pane's history when one is [cached] (a read
 * came good since the fallback; Chat shows it under its own outage strip if
 * the next read fails), else by reading it once, since the refusal may have
 * gone (a session dir removed); a read that fails again says why once
 * (`tapAnswered`). An [agentChanged] update reads only while no composed
 * Chat polls the pane already ([polling]) and never under a typed refusal:
 * a status-only tick is not a change.
 */
internal fun chatRecheck(tap: Boolean, agentChanged: Boolean, unreadable: Boolean, lasting: Boolean, polling: Boolean, cached: Boolean): ChatRecheck = when {
    !unreadable -> ChatRecheck.Nothing
    tap && cached -> ChatRecheck.Show
    tap -> ChatRecheck.Read
    lasting -> ChatRecheck.Nothing
    agentChanged && !polling -> ChatRecheck.Read
    else -> ChatRecheck.Nothing
}

/**
 * Whether a read's outcome for [readPane], asked on daemon boot [readBoot],
 * may change the screen's Chat state (the fallback, the refusal, a pending
 * tap): only for the pane read now ([pane]) on the same [boot] (a restarted
 * daemon may reuse pane ids), never from a Chat still fading out
 * ([leaving]), and a report that the pane is [unreadable] only while no
 * history is [cached] for it: one queued before a read came good, or a
 * slow read that failed after the screen moved on, is stale.
 */
internal fun chatOutcomeApplies(unreadable: Boolean, readPane: String, readBoot: String?, pane: String?, boot: String?, leaving: Boolean, cached: Boolean): Boolean =
    !leaving && readPane == pane && readBoot == boot && !(unreadable && cached)

/** A Chat tap still waiting for its re-check: the pane and tab it was made on. */
internal data class PendingChatTap(val pane: String, val tab: String?)

/**
 * A good re-check switches to Chat only for a tap still [pending] on the
 * same pane and tab: Terminal input or a Terminal choice since clears it.
 */
internal fun switchesToChat(pending: PendingChatTap?, pane: String?, tab: String?): Boolean =
    pending != null && pending.pane == pane && pending.tab == tab

/**
 * Whether Chat's fallback to Terminal ends on a report that the pane is
 * [readable] (FOBS1-R): only for a tap still [pending] there, as
 * [switchesToChat] says. The hidden Chat keeps polling, and a read that comes
 * good on its own (the session dir it was refused for is gone) never switches
 * by itself: the user is in Terminal, perhaps typing.
 */
internal fun fallbackEnds(readable: Boolean, pending: PendingChatTap?, pane: String?, tab: String?): Boolean =
    readable && switchesToChat(pending, pane, tab)

/**
 * Whether a re-check of [pane] that failed answers a Chat tap: its own
 * ([tap]), or one still [pending] on that pane and tab, which found the
 * re-check in flight and waited on it (FOBS1-R2). That tap is then cleared and
 * told why once, so no later read can switch to Chat for it.
 */
internal fun tapAnswered(tap: Boolean, pending: PendingChatTap?, pane: String, tab: String?): Boolean =
    tap || switchesToChat(pending, pane, tab)

/**
 * The Chat tap still [pending] once the screen reads [pane] on [tab]: none
 * after a switch away, so a tap never outlives the pane and tab it was made
 * on, and no later re-check there can switch to Chat for it (FOBS1-R2).
 */
internal fun pendingAfterSwitch(pending: PendingChatTap?, pane: String?, tab: String?): PendingChatTap? =
    pending?.takeIf { switchesToChat(it, pane, tab) }
