package io.github.erikfrish.herdline.data

import android.content.Context
import android.content.SharedPreferences
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.isChatAgent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONException
import org.json.JSONArray
import org.json.JSONObject

/** Exactly one destination: a pane of one Herdr session on one saved node. */
data class DraftKey(val nodeId: String, val session: String, val pane: String) {
    /** Unambiguous: node IDs, session names and pane IDs never hold a NUL. */
    fun encode(): String = "$nodeId\u0000$session\u0000$pane"
}

/**
 * What became of the last text handed to the pane. [Unlocked] is an
 * unconfirmed delivery the user released: it may have landed, the pane is
 * free again, and the record stays until the next delivery replaces it.
 * [InPane] (P1): Chat typed it into an agent's pane and the pane's read
 * receipt came, which proves only that the text reached the input box; it
 * holds the pane like [Uncertain] until the session's history shows the
 * turn ([Confirmed]) or the user unlocks it. [Handed]: a
 * delivery nothing watches (Terminal Send and Insert, Chat Insert) got its
 * receipt, which proves only that Herdr processed the input: Herdr 0.9.5
 * answers success even when it dropped the write. It holds nothing, like
 * [Confirmed], and differs only in what the composer says.
 */
enum class SentState { Queued, InPane, Confirmed, Uncertain, Unlocked, Handed }

/**
 * [acknowledged]: the user took in that an [SentState.Unlocked] attempt may
 * have landed, by editing or dismissing, so the composer stops saying so.
 * The record and its state stay as they were. [historyFrom]: for an
 * [SentState.InPane] record, where the bound session's history ended
 * before the text went; null when that could not be read, so the turn can
 * never be proven. [steer]: an OMP steer typed into the pane and entered
 * while the agent worked, not a typed send; [submit] says only that Enter went.
 * [to]: the destination's name as the composer showed it at the tap, so a
 * notice about this delivery still names it once the pane is gone; null for
 * records that never captured it (steers, ingress, older drafts).
 */
data class SentRecord(
    val text: String,
    val receipt: String,
    val submit: Boolean,
    val state: SentState,
    val at: Long,
    val acknowledged: Boolean = false,
    val historyFrom: Long? = null,
    val steer: Boolean = false,
    val to: String? = null,
)

/**
 * The composer text for one [DraftKey]. [boot] is the daemon run, [agent]
 * the program and [instance] the agent process and session the draft was
 * bound to: pane IDs are unique only within one run, and the program in a
 * pane, or its process and session, can change, so any of them changing
 * needs the user to confirm the pane again before anything is delivered.
 * [instance] is null for a plain terminal, for a draft just moved onto
 * another pane, and for one written before the instance could be read;
 * such a draft, once written, stays held for a chat agent until the user
 * rebinds it; an empty one follows the pane.
 */
data class Draft(
    val text: String = "",
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val boot: String? = null,
    val agent: String? = null,
    val updatedAt: Long = 0,
    val sent: SentRecord? = null,
    val instance: TranscriptIdentity? = null,
    /** Files picked for this draft (UR3), kept with it across navigation and restarts. */
    val attachments: List<DraftAttachment> = emptyList(),
    /**
     * Each send the agent's endpoint holds in its queue, by request id,
     * newest last: a withdrawal puts its text and files back into the draft
     * (PHOTO-STEER-W). The files stay staged on the host, since only
     * removing them from the draft discards them. Bounded to
     * [QUEUED_FILES_KEPT]. An entry the agent already took can no longer be
     * withdrawn, so an older record is simply never used.
     */
    val queued: Map<String, QueuedSend> = emptyMap(),
) {
    /** Nothing written and nothing attached: the draft follows the pane as it is. */
    val blank: Boolean get() = text.isBlank() && attachments.isEmpty()

    /**
     * Whether a delivery may use this draft against daemon run [current]
     * running [currentAgent], whose agent instance reads as [now].
     */
    fun binding(current: String?, currentAgent: String?, now: InstanceRead): Binding = when {
        current == null -> Binding.Offline
        // Nothing written yet: follow the pane as it is.
        blank -> Binding.Bound
        boot != null && boot != current -> Binding.BootChanged
        !agent.equals(currentAgent, ignoreCase = true) -> Binding.AgentChanged
        // Written for a chat agent before its session could be read, so
        // no session vouches for it; held until the user rebinds it. A plain
        // terminal or a program without a session reader has no instance.
        instance == null -> if (isChatAgent(agent)) Binding.InstanceUnknown else Binding.Bound
        // Fail closed: the draft names an instance nobody can vouch for now.
        now !is InstanceRead.Read || now.identity == null -> Binding.InstanceUnknown
        now.identity != instance -> Binding.AgentRestarted
        else -> Binding.Bound
    }

    /** Bound to what the pane is now. */
    fun rebound(current: String?, currentAgent: String?, currentInstance: TranscriptIdentity?) =
        copy(boot = current, agent = currentAgent, instance = currentInstance)

    /**
     * This draft brought up to date with the pane as read now, or null when
     * nothing changes. An empty draft follows the pane, so the text written
     * next is bound to the agent the user saw. A written one never changes
     * binding here, nor gains an instance it lacks: the first one read may
     * already be a later session than the one it was written for.
     */
    fun followed(current: String?, currentAgent: String?, now: InstanceRead): Draft? {
        if (current == null || !blank) return null
        val sameProgram = boot == current && agent.equals(currentAgent, ignoreCase = true)
        val known = when (now) {
            is InstanceRead.Read -> now.identity
            // Not read yet: what this draft knew holds only for the same program.
            InstanceRead.Unread -> instance.takeIf { sameProgram }
        }
        return rebound(current, currentAgent, known).takeIf { it != this }
    }

    /** The last delivery's outcome is unknown: nothing may go to the pane until the user unlocks it. */
    val unsettled: Boolean get() = sent?.state == SentState.Queued || sent?.state == SentState.InPane || sent?.state == SentState.Uncertain

    /** Whether this draft may be delivered under [binding], before the native checks. */
    fun mayDeliver(binding: Binding): Boolean = binding == Binding.Bound && !blank && !unsettled

    /** This draft remembering what send [requestId] carried into the agent's queue: its [text] and [files]. */
    fun queuedWith(requestId: String, text: String, files: List<DraftAttachment>): Draft =
        copy(queued = (queued - requestId + (requestId to QueuedSend(text, files))).entries.toList().takeLast(QUEUED_FILES_KEPT).associate { it.toPair() })

    /** This draft with send [requestId] waiting in session [session], as a steer its server lists nowhere (Kilo) took it. */
    fun waitingSteer(requestId: String, session: String): Draft =
        queued[requestId]?.let { copy(queued = queued + (requestId to it.copy(waitingIn = session))) } ?: this

    /** This draft's steers waiting in session [session], oldest first: what Chat's queue shows for it. */
    fun waitingSteers(session: String): List<Pair<String, QueuedSend>> =
        queued.filter { it.value.waitingIn == session }.toList()

    /** This draft once the server said the steers [left] wait no more; their records stay, for a withdrawal's text. */
    fun steersLeft(left: Collection<String>): Draft {
        val ended = queued.filter { (id, send) -> send.waitingIn != null && id in left }
        return if (ended.isEmpty()) this else copy(queued = queued + ended.mapValues { it.value.copy(waitingIn = null) })
    }

    /**
     * The draft after the agent withdrew send [requestId]: the text that
     * send really carried after [editor] (typed meanwhile, kept first) and
     * its files back in the editor. Never the queue's display text, which
     * reads "[Image]" for a photo alone. Without a record of the send its
     * text is unknown and nothing is guessed: the editor keeps [editor].
     * The same staged files, so nothing is uploaded again; a file still in
     * the draft is not doubled.
     */
    fun withdrawn(requestId: String, editor: String): Draft {
        val send = queued[requestId]
        val files = send?.files.orEmpty().filter { file -> attachments.none { it.id == file.id } }
        val text = withdrawnText(editor, send?.text ?: "")
        return copy(
            text = text,
            selectionStart = text.length,
            selectionEnd = text.length,
            attachments = attachments + files,
            queued = queued - requestId,
        )
    }

    /**
     * The draft after a withdraw of send [requestId] answered [answer]; null is no
     * answer, so that withdraw may have taken it. Taken: [withdrawn]'s text and files.
     * Not waiting, after an earlier withdraw without an answer: that one may be what took
     * it, so its text and files come back too, never lost and never sent. Not waiting
     * otherwise: it left the queue as sent and the editor keeps [editor].
     */
    fun afterWithdraw(requestId: String, answer: Boolean?, editor: String): Draft = when {
        answer == null -> queued[requestId]?.let { copy(queued = queued + (requestId to it.copy(withdrawDoubt = true))) } ?: this
        answer || queued[requestId]?.withdrawDoubt == true -> withdrawn(requestId, editor)
        else -> steersLeft(listOf(requestId))
    }

    /**
     * This draft's text written onto another pane, bound to that pane as it
     * is now ([current], [currentAgent]): moving is the user confirming the
     * new destination. Null when [target] already holds text or files,
     * which a move never overwrites. Attachments go along, to be uploaded
     * again: each staged copy is bound to the pane it was staged for.
     */
    fun movedOnto(target: Draft?, current: String?, currentAgent: String?, now: Long): Draft? =
        if (target?.blank == false) {
            null
        } else {
            Draft(text, selectionStart, selectionEnd, current, currentAgent, now, sent = target?.sent, attachments = attachments.unstaged())
        }

    /**
     * This draft's text written onto [target] which already holds text,
     * replacing it: [Replace][MoveKind] keeps only this draft, bound to the
     * pane as it is now ([current], [currentAgent]).
     */
    fun replacedOnto(target: Draft?, current: String?, currentAgent: String?, now: Long): Draft =
        Draft(text, selectionStart, selectionEnd, current, currentAgent, now, sent = target?.sent, attachments = attachments.unstaged())

    /**
     * This draft's text written after [target]'s own on that pane: the
     * target's text, then a newline, then this draft's, and the target's
     * files, then this draft's. Nothing is lost.
     */
    fun appendedOnto(target: Draft?, current: String?, currentAgent: String?, now: Long): Draft =
        Draft(
            appendText(target?.text ?: "", text), 0, 0, current, currentAgent, now, sent = target?.sent,
            attachments = (target?.attachments.orEmpty() + attachments).unstaged(),
        )

    fun toJson(): JSONObject = JSONObject()
        .put("text", text)
        .put("start", selectionStart)
        .put("end", selectionEnd)
        .put("boot", boot ?: JSONObject.NULL)
        .put("agent", agent ?: JSONObject.NULL)
        .put("at", updatedAt)
        .put(
            "sent",
            sent?.let {
                JSONObject().put("text", it.text).put("receipt", it.receipt).put("submit", it.submit)
                    .put("state", it.state.name).put("at", it.at).put("acknowledged", it.acknowledged)
                    .put("historyFrom", it.historyFrom ?: JSONObject.NULL).put("steer", it.steer)
                    .put("to", it.to ?: JSONObject.NULL)
            } ?: JSONObject.NULL,
        )
        .put("instance", instance?.toJson() ?: JSONObject.NULL)
        .put("attachments", JSONArray(attachments.map { it.toJson() }))
        .put(
            "queuedFiles",
            JSONArray(
                queued.map { (id, send) ->
                    JSONObject().put("id", id).put("text", send.text ?: JSONObject.NULL).put("files", JSONArray(send.files.map { it.toJson() }))
                        .put("waitingIn", send.waitingIn ?: JSONObject.NULL).put("withdrawDoubt", send.withdrawDoubt)
                },
            ),
        )

    companion object {
        fun fromJson(o: JSONObject): Draft {
            val text = o.optString("text")
            val sent = o.optJSONObject("sent")?.let {
                SentRecord(
                    text = it.optString("text"),
                    receipt = it.optString("receipt"),
                    submit = it.optBoolean("submit"),
                    // A queued delivery read back after the app restarted never got its answer here.
                    state = runCatching { SentState.valueOf(it.optString("state")) }.getOrDefault(SentState.Uncertain)
                        .let { s -> if (s == SentState.Queued) SentState.Uncertain else s },
                    at = it.optLong("at"),
                    acknowledged = it.optBoolean("acknowledged"),
                    historyFrom = if (it.has("historyFrom") && !it.isNull("historyFrom")) it.getLong("historyFrom") else null,
                    to = if (it.has("to") && !it.isNull("to")) it.getString("to") else null,
                    steer = it.optBoolean("steer"),
                )
            }
            return Draft(
                text = text,
                selectionStart = o.optInt("start").coerceIn(0, text.length),
                selectionEnd = o.optInt("end").coerceIn(0, text.length),
                boot = if (o.isNull("boot")) null else o.optString("boot"),
                agent = if (o.isNull("agent")) null else o.optString("agent"),
                updatedAt = o.optLong("at"),
                sent = sent,
                instance = o.optJSONObject("instance")?.let { runCatching { TranscriptIdentity.parse(it) }.getOrNull() },
                attachments = o.optJSONArray("attachments")?.let { array ->
                    (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(DraftAttachment::fromJson) }
                }.orEmpty(),
                queued = o.optJSONArray("queuedFiles")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val entry = array.optJSONObject(i) ?: return@mapNotNull null
                        val files = entry.optJSONArray("files") ?: return@mapNotNull null
                        // A record from before 10064 holds no text: unknown, not empty.
                        val text = if (entry.has("text") && !entry.isNull("text")) entry.getString("text") else null
                        entry.optString("id").takeIf { it.isNotEmpty() }?.let { id ->
                            id to QueuedSend(
                                text,
                                (0 until files.length()).mapNotNull { j -> files.optJSONObject(j)?.let(DraftAttachment::fromJson) },
                                waitingIn = if (entry.has("waitingIn") && !entry.isNull("waitingIn")) entry.getString("waitingIn") else null,
                                withdrawDoubt = entry.optBoolean("withdrawDoubt"),
                            )
                        }
                    }.toMap()
                }.orEmpty(),
            )
        }
    }
}

/** How many queued sends a draft remembers for a withdrawal. */
const val QUEUED_FILES_KEPT = 16

/**
 * What one queued send carried: its [text] as sent (null when not recorded) and its [files].
 * [waitingIn]: the session whose server took it as a steer and lists it nowhere (Kilo), until
 * that server says it waits no more; null for every other send.
 * [withdrawDoubt]: a withdraw of it got no answer, so it may already have been taken back.
 */
data class QueuedSend(val text: String?, val files: List<DraftAttachment>, val waitingIn: String? = null, val withdrawDoubt: Boolean = false)

/**
 * The editor's text once a withdrawn request's [withdrawn] text comes back.
 * Text typed while the withdraw was on its way stays first; nothing is lost.
 */
fun withdrawnText(editor: String, withdrawn: String): String = when {
    editor.isEmpty() -> withdrawn
    withdrawn.isEmpty() -> editor
    else -> editor + "\n\n" + withdrawn
}

/** [ours] added after [theirs] on one pane: theirs, a newline, then ours. */
fun appendText(theirs: String, ours: String): String = when {
    theirs.isEmpty() -> ours
    ours.isEmpty() -> theirs
    else -> "$theirs\n$ours"
}

enum class Binding {
    /** The draft belongs to the daemon run attached now. */
    Bound,

    /** Not attached: nothing can be delivered, the draft waits. */
    Offline,

    /** The daemon restarted since: the user must confirm the pane again. */
    BootChanged,

    /** Another program runs in the pane now: the user must confirm the pane again. */
    AgentChanged,

    /** The same program runs as a new process or session: the user must confirm the pane again. */
    AgentRestarted,

    /** The draft names an agent instance and the one in the pane could not be read: nothing goes. */
    InstanceUnknown,
}

/** What the host said of the agent instance in a pane. */
sealed interface InstanceRead {
    /** Not read yet, or the last read failed. */
    data object Unread : InstanceRead

    /** Read; [identity] is null when the pane names no OMP session. */
    data class Read(val identity: TranscriptIdentity?) : InstanceRead
}

/**
 * The pane the composer writes to once the user views [viewed]. An empty
 * draft follows the view; a written one stays with [bound] until the user
 * moves it, so text never changes destination on its own. A held one
 * ([boundHeld]: its delivery is uncertain) cannot be edited or sent anyway,
 * so it does not pin the composer: the viewed pane gets its own draft, and
 * the held one waits on its pane, untouched, until it is viewed again. One
 * still in flight is not held: its receipt and steer steps live in the
 * bound pane's editor, so the composer stays there until they settle
 * ([DraftStore.releasesComposer]).
 */
fun composerDestination(bound: String?, viewed: String?, boundHasText: Boolean, boundHeld: Boolean): String? = when {
    bound == null -> viewed
    boundHasText && !boundHeld -> bound
    else -> viewed ?: bound
}

/**
 * Composer drafts, one per destination, kept across navigation, reconnects
 * and restarts. Writes go through [SharedPreferences.Editor.apply], so the
 * UI thread never waits on disk; only [writeAhead] waits for it.
 *
 * Each destination has a generation that [writeAhead] advances. A [put]
 * carrying an earlier generation, such as a debounced save scheduled before
 * the tap, is dropped, so it can never land over the write-ahead record.
 */
class DraftStore internal constructor(private val prefs: SharedPreferences) {
    private val generations = HashMap<DraftKey, Long>()
    private val flying = MutableStateFlow<Set<DraftKey>>(emptySet())
    private val heldKeys = MutableStateFlow(prefs.all.keys.mapNotNull { encoded ->
        val parts = encoded.split('\u0000')
        if (parts.size != 3) null else DraftKey(parts[0], parts[1], parts[2]).takeIf { get(it)?.unsettled == true }
    }.toSet())

    /** Stored unconfirmed drafts; Unlock and confirmation update their badges without a host event. */
    val unconfirmed: StateFlow<Set<DraftKey>> = heldKeys.asStateFlow()

    private fun recordHeld(key: DraftKey, draft: Draft) = heldKeys.update { keys ->
        when {
            draft.unsettled && key !in keys -> keys + key
            !draft.unsettled && key in keys -> keys - key
            else -> keys
        }
    }

    /**
     * Destinations whose delivery or steer a live editor is still waiting on,
     * in this process only: a stored Queued record reads back as Uncertain,
     * so the disk cannot tell a delivery in flight from one never answered.
     */
    val inFlight: StateFlow<Set<DraftKey>> = flying.asStateFlow()

    /** Set by [key]'s editor while it waits on a receipt or a steer step, cleared when it settles or goes. */
    fun markInFlight(key: DraftKey, waiting: Boolean) = flying.update { if (waiting) it + key else it - key }

    /**
     * Whether [key]'s held draft lets the composer follow another pane: its
     * delivery is unsettled and no editor is still waiting on it (QA HB2). A
     * delivery in flight pins the composer, so switching panes never drops
     * its receipt or leaves a steer's paste in the pane without its Enter.
     */
    fun releasesComposer(key: DraftKey): Boolean = key !in flying.value && get(key)?.unsettled == true

    /** The current generation of [key]; a deferred [put] takes it when it is scheduled. */
    fun generation(key: DraftKey): Long = synchronized(generations) { generations[key] ?: 0L }

    fun get(key: DraftKey): Draft? = prefs.getString(key.encode(), null)?.let {
        try {
            Draft.fromJson(JSONObject(it))
        } catch (e: JSONException) {
            null
        }
    }

    /**
     * Stores [draft] unless a [writeAhead] for [key] came after [generation]
     * was read; then it is dropped and the stored draft stays as it was.
     */
    fun put(key: DraftKey, draft: Draft, generation: Long = generation(key)) = synchronized(generations) {
        if ((generations[key] ?: 0L) != generation) return@synchronized
        if (draft.text.isEmpty() && draft.attachments.isEmpty() && draft.sent == null && draft.queued.isEmpty()) {
            prefs.edit().remove(key.encode()).apply()
        } else {
            prefs.edit().putString(key.encode(), draft.toJson().toString()).apply()
        }
        recordHeld(key, draft)
    }

    /**
     * Stores [draft], whose sent record is about to leave, on disk before
     * returning, so a force-stop or crash during the delivery still reads it
     * back. Every [put] scheduled before this call is dropped from now on;
     * one that ran before it is overwritten by it. False when the write
     * failed. Blocks on disk: never call it on the main thread.
     */
    fun writeAhead(key: DraftKey, draft: Draft): Boolean {
        synchronized(generations) { generations[key] = (generations[key] ?: 0L) + 1 }
        return prefs.edit().putString(key.encode(), draft.toJson().toString()).commit().also { stored ->
            if (stored) recordHeld(key, draft)
        }
    }

    /** Destinations on [nodeId] holding unsent text or files, for showing where drafts wait. */
    fun withText(nodeId: String): List<DraftKey> = keysOn(nodeId).filter { get(it)?.blank == false }

    /**
     * Panes of [session] on [nodeId] whose last delivery is unconfirmed
     * ([Draft.unsettled]: Queued or Uncertain, never Unlocked), for the held
     * badge (AUDIT R7). Read only.
     */
    fun held(nodeId: String, session: String): Set<String> =
        heldKeys.value.filter { it.nodeId == nodeId && it.session == session }.mapTo(HashSet()) { it.pane }

    private fun keysOn(nodeId: String): List<DraftKey> = prefs.all.keys.mapNotNull { encoded ->
        val parts = encoded.split('\u0000')
        if (parts.size != 3 || parts[0] != nodeId) null else DraftKey(parts[0], parts[1], parts[2])
    }

    companion object {
        @Volatile
        private var instance: DraftStore? = null

        fun get(context: Context): DraftStore = instance ?: synchronized(this) {
            instance ?: DraftStore(context.applicationContext.getSharedPreferences("drafts", Context.MODE_PRIVATE))
                .also { instance = it }
        }
    }
}
