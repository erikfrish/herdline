package io.github.erikfrish.herdline.data

import android.content.Context
import android.content.SharedPreferences
import io.github.erikfrish.herdline.core.TranscriptIdentity
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
 */
enum class SentState { Queued, Confirmed, Uncertain, Unlocked }

/**
 * [acknowledged]: the user took in that an [SentState.Unlocked] attempt may
 * have landed, by editing or dismissing, so the composer stops saying so.
 * The record and its state stay as they were.
 */
data class SentRecord(
    val text: String,
    val receipt: String,
    val submit: Boolean,
    val state: SentState,
    val at: Long,
    val acknowledged: Boolean = false,
)

/**
 * The composer text for one [DraftKey]. [boot] is the daemon run, [agent]
 * the program and [instance] the agent process and session the draft was
 * bound to: pane IDs are unique only within one run, and the program in a
 * pane, or its process and session, can change, so any of them changing
 * needs the user to confirm the pane again before anything is delivered.
 * [instance] is null for a plain terminal, for a draft just moved onto
 * another pane, and for one written before the instance could be read;
 * such a draft gains the instance at the pane's next read.
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
        instance == null -> Binding.Bound
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
     * binding here; it only gains the instance it lacks, while it is bound
     * to the same program of the same run.
     */
    fun followed(current: String?, currentAgent: String?, now: InstanceRead): Draft? {
        if (current == null) return null
        val read = (now as? InstanceRead.Read)?.identity
        val next = when {
            blank -> {
                val sameProgram = boot == current && agent.equals(currentAgent, ignoreCase = true)
                val known = when (now) {
                    is InstanceRead.Read -> now.identity
                    // Not read yet: what this draft knew holds only for the same program.
                    InstanceRead.Unread -> instance.takeIf { sameProgram }
                }
                rebound(current, currentAgent, known)
            }
            instance == null && read != null && binding(current, currentAgent, now) == Binding.Bound -> copy(instance = read)
            else -> this
        }
        return next.takeIf { it != this }
    }

    /** The last delivery's outcome is unknown: nothing may go to the pane until the user unlocks it. */
    val unsettled: Boolean get() = sent?.state == SentState.Queued || sent?.state == SentState.Uncertain

    /** Whether this draft may be delivered under [binding], before the native checks. */
    fun mayDeliver(binding: Binding): Boolean = binding == Binding.Bound && !blank && !unsettled

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
            } ?: JSONObject.NULL,
        )
        .put("instance", instance?.toJson() ?: JSONObject.NULL)
        .put("attachments", JSONArray(attachments.map { it.toJson() }))

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
            )
        }
    }
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
        if (draft.text.isEmpty() && draft.attachments.isEmpty() && draft.sent == null) {
            prefs.edit().remove(key.encode()).apply()
        } else {
            prefs.edit().putString(key.encode(), draft.toJson().toString()).apply()
        }
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
        return prefs.edit().putString(key.encode(), draft.toJson().toString()).commit()
    }

    /** Destinations on [nodeId] holding unsent text or files, for showing where drafts wait. */
    fun withText(nodeId: String): List<DraftKey> = keysOn(nodeId).filter { get(it)?.blank == false }

    /**
     * Panes of [session] on [nodeId] whose last delivery is unconfirmed
     * ([Draft.unsettled]: Queued or Uncertain, never Unlocked), for the held
     * badge (AUDIT R7). Read only.
     */
    fun held(nodeId: String, session: String): Set<String> =
        keysOn(nodeId).filter { it.session == session && get(it)?.unsettled == true }.mapTo(HashSet()) { it.pane }

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
