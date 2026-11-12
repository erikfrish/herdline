package io.github.erikfrish.herdline.core

import java.util.Locale

/**
 * What the app can do for each program Herdr reports in a pane, in one
 * table. Every cell's evidence is a row of `team/HARNESS-MATRIX.md`
 * (herdr-mobile-lab); a level here never says more than that row proves.
 *
 * Herdr names its agents itself (`herdr agent start --kind`, the hooks'
 * `agent` field); the name is kept exactly as reported. A name not in the
 * table is Terminal only and is never treated as another program.
 */
enum class Proof {
    /** Proved by a test or a recorded run; the app may show it as working. */
    Verified,

    /** The code path exists, but nothing in the matrix proves it yet. */
    Unverified,

    /** There is no path for this program. */
    None,
}

/** How Chat's Send reaches the program. */
enum class SendRoute {
    /** OMP's own ingress queue (`native/src/ingress.rs`). */
    Endpoint,

    /** The program's own API over the host's exec channel (`native/src/agent_chat.rs`). */
    Api,

    /** Typed into the pane, for an editor box the native composer reads. */
    Pty,

    /** Chat cannot send to it. */
    None,
}

data class Harness(
    /** Herdr's name, exactly as Herdr reported it. */
    val name: String,
    val history: Proof,
    val send: SendRoute,
    val sendProof: Proof,
    val steer: Proof,
    val status: Proof,
    val questions: Proof,
    val subagents: Proof,
    val media: Proof,
    /**
     * Chat shows this program's history and never offers to type: the
     * composer gives way to a read-only footer. Only an explicit row sets
     * it; [SendRoute.None] alone hides nothing.
     */
    val readOnlyHistory: Boolean = false,
) {
    /** Chat reads this program's existing session at all. */
    val chat: Boolean get() = history != Proof.None

    companion object {
        /** What any other program gets: its Terminal, nothing else. */
        fun terminalOnly(name: String) = Harness(
            name, Proof.None, SendRoute.None, Proof.None, Proof.None, Proof.None, Proof.None, Proof.None, Proof.None,
        )

        /** [agent]'s row, matched case-insensitively; an unknown name is [terminalOnly] under that very name. */
        fun of(agent: String): Harness =
            KNOWN[agent.lowercase(Locale.ROOT)]?.copy(name = agent) ?: terminalOnly(agent)

        // Lazy: [KNOWN] builds Harness rows, so reading it while this
        // companion initialises would see it still null.

        /** Programs whose live queue can be read and withdrawn through ingress. */
        val endpointAgents: Set<String> by lazy { names { it.send == SendRoute.Endpoint } }

        /** Programs Chat asks through their own API instead of typing into the pane. */
        val apiAgents: Set<String> by lazy { names { it.send == SendRoute.Api } }

        private fun names(keep: (Harness) -> Boolean): Set<String> =
            KNOWN.values.filter(keep).mapTo(linkedSetOf()) { it.name }
    }
}

/** One row of "What works here", in the order the card lists them. */
enum class HarnessItem { Terminal, History, Send, Steer, Status, Questions, Subagents, Media }

/**
 * Each item with whether it is available: Terminal always, the rest only
 * when [Proof.Verified]. Available items first, each group in [HarnessItem]
 * order, so Terminal is always the first row.
 */
fun Harness.worksHere(): List<Pair<HarnessItem, Boolean>> {
    val proofs = mapOf(
        HarnessItem.Terminal to Proof.Verified,
        HarnessItem.History to history,
        HarnessItem.Send to sendProof,
        HarnessItem.Steer to steer,
        HarnessItem.Status to status,
        HarnessItem.Questions to questions,
        HarnessItem.Subagents to subagents,
        HarnessItem.Media to media,
    )
    return HarnessItem.entries.map { it to (proofs.getValue(it) == Proof.Verified) }.sortedByDescending { it.second }
}

/** Keyed by lower-case Herdr name. Sources are HARNESS-MATRIX.md rows; file:line there. */
private val KNOWN: Map<String, Harness> = listOf(
    // omp: transcript tests (identity, blob store), ingress endpoint,
    // composer steer gate (OMP only), form answers, task subagents.
    Harness(
        "omp",
        history = Proof.Verified,
        send = SendRoute.Endpoint,
        sendProof = Proof.Verified,
        steer = Proof.Verified,
        status = Proof.Verified,
        questions = Proof.Verified,
        subagents = Proof.Verified,
        media = Proof.Verified,
    ),
    // pi: transcript/pi.rs tests incl. inline images; composer reads Pi's box;
    // Herdr's pi hook is not installed on the lab host, so status is unproved.
    Harness(
        "pi",
        history = Proof.Verified,
        send = SendRoute.Pty,
        sendProof = Proof.Verified,
        steer = Proof.None,
        status = Proof.Unverified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.Verified,
    ),
    // claude: transcript/claude.rs tests incl. nested images; composer reads
    // Claude Code's bare prompt; Herdr's claude hook is current.
    Harness(
        "claude",
        history = Proof.Verified,
        send = SendRoute.Pty,
        sendProof = Proof.Verified,
        steer = Proof.None,
        status = Proof.Verified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.Verified,
    ),
    // codex: one transcript/codex.rs test; the API send is routed by unit
    // tests only, never run end to end; images have code but no test.
    Harness(
        "codex",
        history = Proof.Verified,
        send = SendRoute.Api,
        sendProof = Proof.Unverified,
        steer = Proof.None,
        status = Proof.Verified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.Unverified,
    ),
    // opencode: transcript/opencode.rs has no test yet; API send routed by
    // unit tests only; Herdr's opencode hook is current.
    Harness(
        "opencode",
        history = Proof.Unverified,
        send = SendRoute.Api,
        sendProof = Proof.Unverified,
        steer = Proof.None,
        status = Proof.Verified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.Unverified,
    ),
    // qwen: transcript/qwen.rs fixture tests only (legacy chats journal,
    // managed journals refused); no run against a real Qwen Code, nothing sends.
    Harness(
        "qwen",
        history = Proof.Unverified,
        send = SendRoute.None,
        sendProof = Proof.None,
        steer = Proof.None,
        status = Proof.None,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.None,
        readOnlyHistory = true,
    ),
    // hermes: transcript/hermes.rs fixture tests only (state.db schema 33,
    // pinned v0.23.1); no run against a real Hermes, nothing sends.
    Harness(
        "hermes",
        history = Proof.Unverified,
        send = SendRoute.None,
        sendProof = Proof.None,
        steer = Proof.None,
        status = Proof.Unverified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.None,
        readOnlyHistory = true,
    ),
    // kilo: transcript/kilo.rs fixture tests only (v1 message/part tables of
    // kilo.db, pinned 7.8.27); no run against a real Kilo, nothing sends.
    Harness(
        "kilo",
        history = Proof.Unverified,
        send = SendRoute.None,
        sendProof = Proof.None,
        steer = Proof.None,
        status = Proof.Unverified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.None,
        readOnlyHistory = true,
    ),
    // grok: transcript/grok.rs fixture tests only (updates.jsonl, pinned
    // grok-build 2bdd1d6); no run against a real Grok, nothing sends.
    Harness(
        "grok",
        history = Proof.Unverified,
        send = SendRoute.None,
        sendProof = Proof.None,
        steer = Proof.None,
        status = Proof.Unverified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.None,
        readOnlyHistory = true,
    ),
    // copilot: transcript/copilot.rs fixture tests only (events.jsonl,
    // pinned 1.0.109); no run against a real Copilot CLI, nothing sends.
    Harness(
        "copilot",
        history = Proof.Unverified,
        send = SendRoute.None,
        sendProof = Proof.None,
        steer = Proof.None,
        status = Proof.Unverified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.None,
        readOnlyHistory = true,
    ),
    // mastracode: transcript/mastracode.rs fixture tests only (mastra_threads/
    // mastra_messages of mastra.db, mastra-ai/mastra d432f1e); no run against
    // a real Mastra Code, nothing sends.
    Harness(
        "mastracode",
        history = Proof.Unverified,
        send = SendRoute.None,
        sendProof = Proof.None,
        steer = Proof.None,
        status = Proof.Unverified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.None,
        readOnlyHistory = true,
    ),
    // kimi: transcript/kimi.rs fixture tests only (main agent wire.jsonl,
    // pinned kimi-code 482936af); no run against a real Kimi Code CLI,
    // nothing sends.
    Harness(
        "kimi",
        history = Proof.Unverified,
        send = SendRoute.None,
        sendProof = Proof.None,
        steer = Proof.None,
        status = Proof.Unverified,
        questions = Proof.None,
        subagents = Proof.None,
        media = Proof.None,
        readOnlyHistory = true,
    ),
).associateBy { it.name }
