package io.github.erikfrish.herdline.core

import java.util.Locale

/**
 * What the app does for each program Herdr reports in a pane, and what the
 * "What works here" card says about it.
 *
 * Two separate facts per program, never mixed:
 * - the route ([chat], [send], [readOnlyHistory]): only this gates a control;
 * - the claim ([cell]): the card's words. A claim never enables, disables
 *   or hides a control.
 *
 * Herdr names its agents itself (`herdr agent start --kind`, the hooks'
 * `agent` field); the name is kept exactly as reported. A name not in the
 * table is Terminal only and is never treated as another program.
 */

data class Harness(
    /** Herdr's name, exactly as Herdr reported it. */
    val name: String,
    /** Chat reads this program's existing session at all. */
    val chat: Boolean,
    val send: SendRoute,
    /**
     * Chat shows this program's history and never offers to type: the
     * composer gives way to a read-only footer. Only an explicit row sets
     * it; [SendRoute.None] alone hides nothing. It never hides Stop or the
     * permission dialogs (ChatView keys those on the program's own server).
     */
    val readOnlyHistory: Boolean = false,
    /**
     * While the program works, Send queues behind the running turn by the
     * runtime's own verb and says Queue before the tap; a queued entry can
     * be moved into the turn. Only rows with a pinned route set it.
     */
    val queue: Boolean = false,
    /**
     * While the program works, Chat offers Steer into the running turn by
     * its endpoint. A send route alone never implies it.
     */
    val steer: Boolean = false,
    /**
     * The program's server lists no waiting steer (Kilo): Chat's queue
     * shows the steers this app sent and the server took, until the server
     * says otherwise (a withdraw's answer, the session read idle).
     */
    val unlistedSteers: Boolean = false,
    /** The card's claims, one per [HarnessKey] in key order. */
    private val claims: List<Cell>,
) {
    init {
        require(claims.size == HarnessKey.entries.size) { "$name: ${claims.size} claims" }
    }

    /** The static claim for [key]; [shown] applies the runtime rules on top. */
    fun cell(key: HarnessKey): Cell = claims[key.ordinal]

    companion object {
        /** What any other program gets: its Terminal, nothing else. */
        fun terminalOnly(name: String) = Harness(
            name, chat = false, send = SendRoute.None, claims = List(HarnessKey.entries.size) { Cell.NotYet(Reason.UnknownProgram) },
        )

        /** [agent]'s row, matched case-insensitively; an unknown name is [terminalOnly] under that very name. */
        fun of(agent: String): Harness =
            KNOWN[agent.lowercase(Locale.ROOT)]?.copy(name = agent) ?: terminalOnly(agent)

        // Lazy: [KNOWN] builds Harness rows, so reading it while this
        // companion initialises would see it still null.

        /** Programs whose live queue can be read and withdrawn through the native core. */
        val endpointAgents: Set<String> by lazy { names { it.send == SendRoute.Endpoint } }

        /** Programs whose Send queues while they work, and whose queued entries can be steered. */
        val queueAgents: Set<String> by lazy { names { it.queue } }

        /** Programs whose endpoint takes a Steer into the running turn. */
        val steerAgents: Set<String> by lazy { names { it.steer } }

        /** Programs whose waiting steers only this app's own record shows. */
        val unlistedSteerAgents: Set<String> by lazy { names { it.unlistedSteers } }

        private fun names(keep: (Harness) -> Boolean): Set<String> =
            KNOWN.values.filter(keep).mapTo(linkedSetOf()) { it.name }
    }
}

/** How Chat's Stop reaches a program's running turn. */
enum class StopRoute {
    /** The program's own server aborts it (`native/src/agent_chat.rs`). */
    Server,

    /**
     * One key into the pane, where a pinned source or the runtime's own
     * working hint names it (`native/src/control/pty.rs`); stopped only when
     * the history records it, else unconfirmed.
     */
    Pty,

    /** Chat offers no Stop. */
    None,
}

/** Where Stop goes for this program; the native core still decides at the tap. */
val Harness.stop: StopRoute
    get() = when (name.lowercase(Locale.ROOT)) {
        "opencode", "kilo" -> StopRoute.Server
        "omp", "pi", "claude", "codex" -> StopRoute.Pty
        else -> StopRoute.None
    }


/** How Chat's Send reaches the program. */
enum class SendRoute {
    /**
     * The program's own live queue through the native core: OMP's ingress
     * (`native/src/ingress.rs`), Codex's daemon and OpenCode's server
     * (`native/src/agent_chat.rs`).
     */
    Endpoint,

    /** Typed into the pane, for an editor box the native composer reads. */
    Pty,

    /** Chat cannot send to it. */
    None,
}

/** The card's rows after Terminal, in the order H L P X S T M Q U A R. */
enum class HarnessKey { History, Live, Send, Stop, SteerQueue, Tools, Media, Questions, Status, Subagents, SessionChange }

/** What the card says about one (program, key). */
sealed interface Cell {
    /** Proved by QA on an exact APK: [evidence] names the recorded result and the versionCode it was on. */
    data class Works(val evidence: String) : Cell {
        init {
            require(evidence.isNotBlank()) { "Works needs its QA evidence" }
        }
    }

    /** The route exists and is reachable; QA has not proved it on this app version. */
    data class Unverified(val note: Note? = null) : Cell

    /** Upstream lacks it, by a pinned upstream source and version. */
    data class NotIn(val source: String) : Cell

    /** No route in Herdline yet. [subject] fills the reason's %1$s where it has one. */
    data class NotYet(val reason: Reason, val subject: String? = null) : Cell
}

/** Why a cell is not in Herdline yet (card §2.3, `harness_reason_*`). */
enum class Reason {
    NotBuilt, NoSource, NoRef, Closed, NoControl, Lock, FormatUnpinned,
    SendMedia, ScreenStatus, NeedsHook, NeedsServer, StaleId, Rewind, NoSessionYet, UnknownProgram, NoLiveAttach, CloudAuth,
    BlindKey, NoSocket, InputUnproved, AmbiguousSession, ServerUnattachable,
}

/** The missing half of an Unverified two-verb cell (card §2.4, `harness_note_*`). */
enum class Note { Typed, QueueMissing }

/** What the route reports right now, for the card's runtime replacements (card §3.4). */
data class HarnessRuntime(
    /**
     * opencode/kilo: the native core's answer for the program's server
     * (Connection.ingressNeedsServer). Unknown until it answers, and after
     * a probe that failed: never read as present.
     */
    val server: ServerProbe = ServerProbe.Unknown,
    /** mastracode: Herdr still reports the session as `session-init`. */
    val sessionInit: Boolean = false,
    /**
     * grok: Chat stands behind no one session for the pane and refuses: more than one candidate
     * (`grok_session_ambiguous`), or Grok's own open-session list names another (`grok_session_stale`)
     * or none (`grok_session_unregistered`).
     */
    val ambiguousSession: Boolean = false,
)

/** What the native core said of a program's server. */
enum class ServerProbe {
    /** It answered through the server. */
    Present,

    /** `unusable: needs_server`: none runs for this session. */
    Missing,

    /** No answer yet, or a probe that failed: nothing said either way. */
    Unknown,
}

/** One card row as shown: the claim, or why it can't be checked now (card §3.3; never stored). */
sealed interface Shown {
    data class Claim(val cell: Cell) : Shown

    data class CantCheck(val why: CantCheckWhy) : Shown
}

/** Why a row can't be checked now. */
enum class CantCheckWhy {
    /** The connection is not live. */
    Offline,

    /** Herdr gives no agent name for the pane. */
    Identity,

    /** The program's server could not be asked (a [ServerProbe.Unknown]). */
    Server,
}

/**
 * [key]'s row for [program] (null: Herdr names no agent in the pane), by
 * the card's precedence: an unknown program, then offline, then identity,
 * then the cell with the runtime replacements.
 */
fun shown(program: String?, key: HarnessKey, live: Boolean, runtime: HarnessRuntime = HarnessRuntime()): Shown {
    val harness = program?.let(Harness::of)
    val claim = harness?.cell(key)
    return when {
        claim is Cell.NotYet && claim.reason == Reason.UnknownProgram -> Shown.Claim(claim)
        !live -> Shown.CantCheck(CantCheckWhy.Offline)
        harness == null -> Shown.CantCheck(CantCheckWhy.Identity)
        else -> harness.name.lowercase(Locale.ROOT).let { name -> served(name, key, runtime) ?: Shown.Claim(replaced(name, key, runtime) ?: claim!!) }
    }
}

/** A server program's server keys, by what the probe said; null when the static claim stands. */
private fun served(name: String, key: HarnessKey, runtime: HarnessRuntime): Shown? = when {
    key !in SERVER_KEYS || name !in SERVER_PROGRAMS -> null
    runtime.server == ServerProbe.Missing -> Shown.Claim(Cell.NotYet(Reason.NeedsServer, SERVER_PROGRAMS.getValue(name)))
    runtime.server == ServerProbe.Unknown -> Shown.CantCheck(CantCheckWhy.Server)
    else -> null
}

private fun replaced(name: String, key: HarnessKey, runtime: HarnessRuntime): Cell? = when {
    runtime.sessionInit && key == HarnessKey.History && name == "mastracode" -> Cell.NotYet(Reason.NoSessionYet)
    // Nothing guessed: the rows that read the candidate session say they can't tell which (card §7.3).
    runtime.ambiguousSession && key in GROK_SESSION_KEYS && name == "grok" -> Cell.NotYet(Reason.AmbiguousSession, "Grok")
    else -> null
}

private val SERVER_KEYS = setOf(HarnessKey.Send, HarnessKey.Stop, HarnessKey.SteerQueue, HarnessKey.Questions)
private val SERVER_PROGRAMS = mapOf("opencode" to "OpenCode", "kilo" to "Kilo")
private val GROK_SESSION_KEYS = setOf(HarnessKey.History, HarnessKey.Live, HarnessKey.Tools, HarnessKey.Media)

// The claims, one plain table (card §4). Columns: H L P X S T M Q U A R.
private val V = Cell.Unverified()
private fun Vn(note: Note) = Cell.Unverified(note)
private fun no(reason: Reason, subject: String? = null) = Cell.NotYet(reason, subject)
private val NB = no(Reason.NotBuilt)
private val NS = no(Reason.NoSource)
private val NC = no(Reason.NoControl)
private val SM = no(Reason.SendMedia)
private val SS = no(Reason.ScreenStatus)

private fun card(vararg cells: Cell) = cells.toList()

private fun sessionless(all: Cell, stop: Cell, steer: Cell) = card(all, all, all, stop, steer, all, all, all, SS, all, all)

private fun routed(
    name: String, send: SendRoute, claims: List<Cell>, readOnlyHistory: Boolean = false, queue: Boolean = false, steer: Boolean = false,
    unlistedSteers: Boolean = false,
) = Harness(name, chat = true, send = send, readOnlyHistory = readOnlyHistory, claims = claims, queue = queue, steer = steer, unlistedSteers = unlistedSteers)

private fun unread(name: String, claims: List<Cell>) = Harness(name, chat = false, send = SendRoute.None, claims = claims)

/** Keyed by lower-case Herdr name. Columns of each card(…): H L P X S T M Q U A R. */
private val KNOWN: Map<String, Harness> = (
    listOf(
        routed("omp", SendRoute.Endpoint, card(V, V, V, V, Vn(Note.QueueMissing), V, V, V, V, V, V), steer = true),
        routed("pi", SendRoute.Pty, card(V, V, Vn(Note.Typed), V, NB, V, SM, NC, no(Reason.NeedsHook, "pi"), NB, V)),
        routed("claude", SendRoute.Pty, card(V, V, Vn(Note.Typed), V, NS, V, SM, NC, SS, NB, V)),
        routed("codex", SendRoute.Endpoint, card(V, V, V, V, Vn(Note.QueueMissing), V, SM, NC, V, NB, V), steer = true),
        // opencode: Send (Auto, Steer, Queue) with staged files, its inbox, withdraw, steer-a-queued-entry
        // and the session's children (by title, the cut list marked) go to the already-running server
        // (native agent_chat, opencode_ctl.py + opencode_media.py, pinned v2.2.7 fedc6f2); unit tests only.
        routed("opencode", SendRoute.Endpoint, card(V, V, V, V, V, V, V, V, V, V, V), queue = true, steer = true),
        // kilo: text Send with staged files (verified data: file parts, the native per-kind limits), Steer
        // into a turn at work, withdraw of the app's own waiting steer, status, state and the session's
        // children (by title) through its v1 server (kilo_ctl.py + opencode_media.py, pinned v7.8.27
        // 03a6f07); a plain send while it works is refused agent_working. Kilo has no queue behind the turn,
        // no promote and lists no waiting prompt, so Chat shows its own steers only.
        routed("kilo", SendRoute.Endpoint, card(V, V, V, V, Vn(Note.QueueMissing), V, V, V, V, V, no(Reason.StaleId)), steer = true, unlistedSteers = true),
        routed("kimi", SendRoute.None, card(V, V, NB, NB, NB, V, SM, NC, V, NB, V), readOnlyHistory = true),
        routed("mastracode", SendRoute.None, card(V, V, NB, NB, NB, V, SM, NC, V, NB, V), readOnlyHistory = true),
        // grok: the history of the candidate session only (card §7, PARENT, source 559751fd); no Not in, no Works.
        routed(
            "grok",
            SendRoute.None,
            card(V, V, no(Reason.InputUnproved, "Grok"), no(Reason.BlindKey, "Grok"), no(Reason.NoSocket, "Grok"), V, SM, no(Reason.NoSocket, "Grok"), SS, NB, no(Reason.StaleId)),
            readOnlyHistory = true,
        ),
        routed("copilot", SendRoute.None, card(V, V, NB, NB, NS, V, SM, NC, SS, NS, no(Reason.Rewind)), readOnlyHistory = true),
        routed("qwen", SendRoute.None, card(V, V, NB, NB, NB, V, SM, no(Reason.Lock), SS, NB, V), readOnlyHistory = true),
        routed("hermes", SendRoute.None, card(V, V, NC, NC, NC, V, SM, NC, SS, NB, V), readOnlyHistory = true),
        // gemini: read only through the owned launcher binding (native transcript/gemini, v0.68.0); an
        // unwrapped Gemini is refused. Fixtures only: no real SessionStart observed yet.
        routed("gemini", SendRoute.None, card(V, V, NB, NB, NB, V, NB, NB, SS, NB, V), readOnlyHistory = true),
        unread("agy", sessionless(no(Reason.FormatUnpinned, "Antigravity"), stop = NB, steer = NS)),
        no(Reason.CloudAuth, "Droid").let { cloud ->
            val attach = no(Reason.NoLiveAttach, "Droid")
            unread("droid", card(cloud, cloud, attach, attach, attach, cloud, cloud, attach, SS, cloud, cloud))
        },
        // letta: its exact local conversation, read only (native transcript/letta, letta-code 0.34.54
        // 5eb817d6); fixtures only. No safe attach to its server mode (card §8); no media fetch (SM).
        no(Reason.ServerUnattachable, "Letta").let { attach ->
            routed("letta", SendRoute.None, card(V, V, attach, attach, attach, V, SM, attach, SS, NB, NB), readOnlyHistory = true)
        },
    ) +
        listOf("cline", "maki", "muse").map { unread(it, sessionless(no(Reason.NoRef), stop = NB, steer = NB)) } +
        listOf("kiro", "amp").map { unread(it, sessionless(no(Reason.NoRef), stop = NB, steer = NS)) } +
        listOf("cursor", "devin", "qodercli").map { unread(it, sessionless(no(Reason.Closed), stop = NB, steer = NS)) }
    ).associateBy { it.name }
