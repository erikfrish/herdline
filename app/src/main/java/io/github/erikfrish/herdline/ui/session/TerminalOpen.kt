package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ShellView

/** How long an explicit Terminal waits for Herdr's focus to land on its pane before it says it couldn't. */
const val TERMINAL_LANDING_MS = 5_000L

/**
 * An explicit Terminal asked for in Chat on [pane], the pane read here, while
 * the desktop was on another pane. Terminal shows the desktop's pane, so Herdr
 * was asked once to focus [pane] (pane.focus, which has no receipt); Chat stays
 * on [pane] until the screen sees the focus land there (see [terminalLanding]).
 * It holds for one connection ([node], [session]) and daemon run ([boot]), and
 * for the user's [choice]-th choice on this screen: any later choice cancels
 * it. [keyboard]: the terminal keyboard was asked for too.
 */
data class TerminalOpening(
    val node: String,
    val session: String,
    val boot: String,
    val pane: String,
    val choice: Int,
    val keyboard: Boolean = false,
)

/** What the screen shows now, as a pending [TerminalOpening] reads it. */
data class TerminalSeen(
    val node: String,
    val session: String,
    /** The connection state is Connected. */
    val live: Boolean,
    val view: ShellView?,
    /** The pane the terminal's RenderFrame draws focused; it can lag the view. */
    val frameFocus: String?,
    /** The pane read here, and whether Chat is on screen. */
    val reading: String?,
    val chatShown: Boolean,
    /** The screen's count of user choices. */
    val choice: Int,
    /** [TERMINAL_LANDING_MS] passed since the request. */
    val timedOut: Boolean,
)

enum class TerminalLanding {
    /** Chat stays on the pane and says Terminal is opening. */
    Waiting,

    /** The desktop is on the pane in both the view and the frame: save Terminal for it and show it. */
    Landed,

    /** A newer choice of the user's: drop it without a word. */
    Cancelled,

    /** No landing on this connection and run: drop it, stay in Chat, say so, offer Try again. */
    Failed,
}

/**
 * Where [opening] stands. It lands only when the live view of its run and the
 * terminal's frame both put the desktop on its pane, so Terminal never draws
 * another pane's frame under it. A lost connection, another run, a closed pane
 * or [TERMINAL_LANDING_MS] without a landing fail it, and it is never replayed:
 * a focus that lands later has no request left to flip the mode.
 */
fun terminalLanding(opening: TerminalOpening, seen: TerminalSeen): TerminalLanding {
    val view = seen.view
    return when {
        seen.node != opening.node || seen.session != opening.session || !seen.live -> TerminalLanding.Failed
        view != null && (view.boot != opening.boot || view.panes.none { it.id == opening.pane }) -> TerminalLanding.Failed
        seen.choice != opening.choice || seen.reading != opening.pane || !seen.chatShown -> TerminalLanding.Cancelled
        view != null && view.focusedPane == opening.pane && seen.frameFocus == opening.pane -> TerminalLanding.Landed
        seen.timedOut -> TerminalLanding.Failed
        else -> TerminalLanding.Waiting
    }
}

enum class TerminalStart {
    /** The live view and the frame are both on the pane: Terminal at once, no request. */
    Immediate,

    /** The view is on the pane but the frame isn't yet: wait for it, asking Herdr nothing. */
    AwaitOnly,

    /** The desktop is elsewhere: ask Herdr once to focus the pane, then wait. */
    FocusAndAwait,

    /** Not live, or not this run: say so, with no request and no stale proof. */
    Failed,
}

/** How an explicit Terminal from Chat starts for [opening], by the same rules [terminalLanding] lands it. */
fun terminalStart(opening: TerminalOpening, seen: TerminalSeen): TerminalStart = when (terminalLanding(opening, seen)) {
    TerminalLanding.Landed -> TerminalStart.Immediate
    TerminalLanding.Waiting -> if (seen.view?.focusedPane == opening.pane) TerminalStart.AwaitOnly else TerminalStart.FocusAndAwait
    TerminalLanding.Failed, TerminalLanding.Cancelled -> TerminalStart.Failed
}
