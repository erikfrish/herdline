/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.core

import io.github.erikfrish.herdline.data.Node
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.UUID

/**
 * One attached daemon. A reader thread drains the native event queue and
 * publishes the latest frame and shell view as state; the UI renders whatever
 * is current, so it never falls behind the daemon.
 */
class Connection(val node: Node) {
    sealed interface State {
        data object Connecting : State
        data class Connected(val serverVersion: String) : State
        /** [byUser]: the user disconnected ([close]), so this is no loss; [reason] is free native or server text. */
        data class Disconnected(val reason: String, val byUser: Boolean = false) : State {
            companion object {
                /** The [reason] when nothing named one: it says no more than the state itself. */
                const val UNNAMED = "Disconnected"
            }
        }
        /** [host] is `address:port` of the hop whose key is not trusted, [hop] its place in the route. */
        data class UntrustedHostKey(val hop: Int, val host: String, val fingerprint: String, val changed: Boolean) : State
        /** Hop number [hop] of the route, [host] as `address:port`, refused its key or password. */
        data class AuthRejected(val hop: Int, val host: String) : State
        /** The route could not be built, such as a password left unanswered. */
        data class Failed(val reason: String) : State
        /** The host closed the bridge before any Herdr answered: none is installed, or none works. */
        data object HerdrMissing : State
        /** Herdr is being installed or updated on the host, at the user's request. */
        data object SettingUp : State
        /** Installing or updating Herdr failed; [output] is the end of what the host printed. */
        data class SetupFailed(val exitStatus: Int?, val output: String) : State
        /**
         * Herdr on the host and this app cannot work together; updating one
         * fixes it, not retrying. [daemonOutdated] says which: the host's
         * Herdr when true, else this app. [minVersion] is the oldest Herdr
         * this app accepts.
         */
        data class Incompatible(val daemonOutdated: Boolean, val serverVersion: String?, val minVersion: String) : State {
            companion object {
                fun parse(event: JSONObject) = Incompatible(
                    daemonOutdated = event.optString("outdated") != "app",
                    serverVersion = event.optString("server_version").takeUnless { event.isNull("server_version") || it.isBlank() },
                    minVersion = event.optString("min_version"),
                )
            }
        }
    }

    sealed interface Event {
        data class Notification(val title: String, val body: String?, val attention: Boolean, val pane: String?) : Event
        data class Clipboard(val text: String) : Event
        data class Problem(val message: String) : Event
        data object Bell : Event
    }

    /** A composer destination: exactly one pane of one Herdr boot on this node. */
    data class ComposerTarget(
        val nodeId: String,
        val session: String,
        val boot: String,
        val pane: String,
        val agent: String?,
        /** The agent instance the draft was written for; null for a terminal or an older draft. */
        val instance: TranscriptIdentity? = null,
    )

    sealed interface Delivery {
        /** Handed to the daemon link; [receipt] settles it as a [DeliveryUpdate]. */
        data class Queued(val receipt: String) : Delivery
        data class Refused(val reason: String) : Delivery

        /** The call was made and no proof that nothing left came back: the text may be in the pane. Nothing is resent; the user unlocks. */
        data class Uncertain(val reason: String) : Delivery
    }

    /** The first step of a steer. */
    sealed interface SteerStart {
        /** The paste is queued alone; [receipt] settles it as a [DeliveryUpdate]. No Enter yet. */
        data class Staged(val receipt: String) : SteerStart
        data class Refused(val reason: String) : SteerStart

        /** The call failed or the core cannot tell whether the paste left: nothing is known, nothing is resent. */
        data class Uncertain(val reason: String) : SteerStart
    }

    /** The Enter step of a staged steer. */
    sealed interface SteerEnter {
        /** One Enter is queued; [receipt] settles it as a [DeliveryUpdate]. The steer is over. */
        data class Queued(val receipt: String) : SteerEnter

        /** Nothing was sent and the steer stays staged: ask again on a later frame. */
        data class Waiting(val reason: String) : SteerEnter

        /** The steer ended without Enter; its paste stays in the pane's input box. */
        data class Refused(val reason: String) : SteerEnter

        /** The call failed or the core cannot tell whether Enter left: the user checks the pane. */
        data class Uncertain(val reason: String) : SteerEnter
    }

    sealed interface DeliveryUpdate {
        val receipt: String

        /** Herdr answered a request queued behind the input, so it read the input. */
        data class Confirmed(override val receipt: String) : DeliveryUpdate

        /** The link ended or refused before that answer: the input may or may not have arrived. */
        data class Uncertain(override val receipt: String) : DeliveryUpdate
    }

    private val _state = MutableStateFlow<State>(State.Connecting)
    val state: StateFlow<State> = _state
    private val _view = MutableStateFlow<ShellView?>(null)
    val view: StateFlow<ShellView?> = _view
    private val _frame = MutableStateFlow<RenderFrame?>(null)
    val frame: StateFlow<RenderFrame?> = _frame
    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events
    private val _deliveries = MutableSharedFlow<DeliveryUpdate>(extraBufferCapacity = 16)
    val deliveries: SharedFlow<DeliveryUpdate> = _deliveries
    // Deliveries awaiting their receipt, and receipts answered before the
    // delivery that queued them got to register; both under [receipts].
    private val receipts = Any()
    private val pending = mutableSetOf<String>()
    private val early = LinkedHashMap<String, Boolean>()

    @Volatile
    private var id = 0L

    @Volatile
    private var closed = false

    /** The grid the view wants; re-sent once the daemon's boot is known. */
    @Volatile
    private var grid: IntArray? = null

    val isOpen: Boolean get() = !closed && _state.value.let { it is State.Connecting || it is State.Connected }

    /** Whether the user disconnected; a closed connection is not a lost one. */
    val isClosed: Boolean get() = closed

    /** [configJson] is the route document; it holds secrets and is not kept. */
    fun start(configJson: String) {
        _state.value = State.Connecting
        closed = false
        val session = try {
            Native.connect(configJson)
        } catch (e: IOException) {
            _state.value = State.Disconnected(e.message ?: "Could not connect")
            return
        }
        id = session
        Thread({ pump(session) }, "herdline-events").apply { isDaemon = true }.start()
    }

    fun fail(reason: String) {
        _state.value = State.Failed(reason)
    }

    fun settingUp() {
        _state.value = State.SettingUp
    }

    fun setupFailed(outcome: HerdrSetup) {
        _state.value = State.SetupFailed(outcome.exitStatus, outcome.output)
    }

    private fun pump(session: Long) {
        while (!closed && id == session) {
            val json = Native.nextEvent(session, 500) ?: continue
            val event = JSONObject(json)
            when (event.optString("type")) {
                "frame" -> Native.takeFrame(session)?.let { RenderFrame.parse(it) }?.let { _frame.value = it }
                "snapshot" -> Native.takeView(session)?.let {
                    val first = _view.value == null
                    _view.value = ShellView.parse(it)
                    // A resize before the first snapshot had no boot to carry.
                    if (first) grid?.let { g -> Native.resize(session, g[0], g[1], g[2], g[3]) }
                }
                "connected" -> _state.value = State.Connected(event.optString("server_version"))
                "notification" -> _events.tryEmit(
                    Event.Notification(
                        event.optString("title"),
                        event.optString("body").takeUnless { event.isNull("body") },
                        event.optBoolean("attention"),
                        event.optString("pane").takeUnless { event.isNull("pane") },
                    ),
                )
                "clipboard" -> _events.tryEmit(Event.Clipboard(event.optString("text")))
                "bell" -> _events.tryEmit(Event.Bell)
                "response" -> settle(event.optString("request_id"), confirmed = true, reason = null)
                "rejected" -> if (event.isNull("request_id")) {
                    _events.tryEmit(Event.Problem(event.optString("reason")))
                } else {
                    settle(event.optString("request_id"), confirmed = false, reason = event.optString("reason"))
                }
                "version_mismatch" -> _state.value = State.Incompatible.parse(event)
                "herdr_missing" -> {
                    _state.value = State.HerdrMissing
                    finish(session)
                    return
                }
                "auth_rejected" -> {
                    _state.value = State.AuthRejected(event.optInt("hop"), event.optString("host"))
                    finish(session)
                    return
                }
                "host_key" -> {
                    _state.value = State.UntrustedHostKey(
                        event.optInt("hop"),
                        event.optString("host"),
                        event.optString("fingerprint"),
                        event.optBoolean("changed"),
                    )
                    finish(session)
                    return
                }
                "disconnected", "closed" -> {
                    if (_state.value !is State.Incompatible) {
                        // The daemon's own "closed" can follow a close(); it stays the user's disconnect.
                        _state.value = State.Disconnected(event.optString("reason").ifBlank { State.Disconnected.UNNAMED }, byUser = closed)
                    }
                    finish(session)
                    return
                }
            }
        }
    }

    private fun finish(session: Long) {
        Native.disconnect(session)
        if (id == session) id = 0
        settleUncertain()
    }

    /** Every delivery still waiting for its receipt can no longer get one. */
    private fun settleUncertain() {
        val left = synchronized(receipts) { pending.toList().also { pending.clear(); early.clear() } }
        left.forEach { _deliveries.tryEmit(DeliveryUpdate.Uncertain(it)) }
    }

    /**
     * Settles the delivery behind a request's answer: a response confirms it,
     * a rejection leaves it uncertain. An answer no delivery waits for is kept
     * briefly, in case its delivery has not registered yet.
     */
    private fun settle(requestId: String, confirmed: Boolean, reason: String?) {
        val waiting = synchronized(receipts) {
            pending.remove(requestId).also { found ->
                if (!found) {
                    early[requestId] = confirmed
                    if (early.size > EARLY_ANSWERS) early.remove(early.keys.first())
                }
            }
        }
        when {
            waiting && confirmed -> _deliveries.tryEmit(DeliveryUpdate.Confirmed(requestId))
            waiting -> _deliveries.tryEmit(DeliveryUpdate.Uncertain(requestId))
            reason != null -> _events.tryEmit(Event.Problem(reason))
        }
    }

    /** The ID input and requests go to, or 0 when not connected. */
    private fun live(): Long = if (closed) 0 else id

    /** The native session to stage attachments on, or 0 when there is none. */
    internal fun link(): Long = live()

    fun close() {
        closed = true
        val session = id
        id = 0
        if (session != 0L) Native.disconnect(session)
        settleUncertain()
        if (_state.value is State.Connecting || _state.value is State.Connected) {
            _state.value = State.Disconnected(State.Disconnected.UNNAMED, byUser = true)
        }
    }

    fun key(pane: String?, keyCode: Int, metaState: Int, unicode: Int, repeat: Boolean): Boolean =
        live().let { it != 0L && Native.key(it, pane, keyCode, metaState, unicode, repeat) }

    fun chord(pane: String?, codepoint: Int, metaState: Int) {
        live().takeIf { it != 0L }?.let { Native.chord(it, pane, codepoint, metaState) }
    }

    fun text(pane: String?, text: String) {
        live().takeIf { it != 0L }?.let { Native.text(it, pane, text) }
    }

    fun paste(pane: String?, text: String) {
        live().takeIf { it != 0L }?.let { Native.paste(it, pane, text) }
    }

    fun touch(pane: String, kind: Int, column: Int, row: Int, lines: Int = 1) {
        live().takeIf { it != 0L }?.let { Native.touch(it, pane, kind, column, row, lines) }
    }

    fun resize(cols: Int, rows: Int, cellWidth: Int, cellHeight: Int) {
        grid = intArrayOf(cols, rows, cellWidth, cellHeight)
        live().takeIf { it != 0L }?.let { Native.resize(it, cols, rows, cellWidth, cellHeight) }
    }

    fun setTheme(themeJson: String) {
        live().takeIf { it != 0L }?.let { Native.setTheme(it, themeJson) }
    }

    fun selectionText(startColumn: Int, startRow: Int, endColumn: Int, endRow: Int): String =
        live().takeIf { it != 0L }?.let { Native.selectionText(it, startColumn, startRow, endColumn, endRow) } ?: ""

    /** Invokes a Herdr endpoint method; failures surface as [Event.Problem]. */
    fun request(method: String, params: JSONObject) {
        val session = live()
        if (session == 0L) return
        try {
            Native.request(session, method, params.toString())
        } catch (e: IOException) {
            _events.tryEmit(Event.Problem(e.message ?: "$method failed"))
        }
    }

    /**
     * What the daemon allows the composer to do with [pane] now, against the
     * [viewport] the view drew (null: none yet, which the native core
     * refuses), bound to the draft's [boot] and agent [instance] where it has
     * them; JSON from the native checks.
     */
    fun composerCheck(pane: String, viewport: Viewport?, boot: String? = null, instance: TranscriptIdentity? = null): ComposerCheck? {
        val session = live()
        if (session == 0L) return null
        val seen = viewportJson(node.id, node.session, boot, pane, instance, viewport).takeIf { it != JSONObject.NULL }
        return try {
            ComposerCheck.parse(Native.composerCheck(session, pane, seen?.toString()))
        } catch (e: IOException) {
            null
        }
    }

    /**
     * The OMP instance in [pane] now, read on the host: success with null when
     * the pane names no OMP session, failure when it could not be read.
     * Blocks on the host: call it off the main thread.
     */
    fun composerInstance(pane: String): Result<TranscriptIdentity?> {
        val session = live()
        if (session == 0L) return Result.failure(IOException("not connected"))
        return try {
            val o = JSONObject(Native.composerInstance(session, pane))
            Result.success(o.optJSONObject("instance")?.let(TranscriptIdentity::parse))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: org.json.JSONException) {
            Result.failure(IOException(e.message, e))
        }
    }

    /** Holds [pane] natively until [unlock]: its last delivery was never confirmed. */
    fun hold(nodeId: String, session: String, pane: String) =
        Native.composerHold(targetJson(nodeId, session, pane))

    /** The user settled the last delivery to that pane: Send may go there again. */
    fun unlock(nodeId: String, session: String, pane: String) =
        Native.composerUnlock(targetJson(nodeId, session, pane))

    private fun targetJson(nodeId: String, session: String, pane: String) =
        JSONObject().put("node", nodeId).put("session", session).put("pane", pane).toString()

    /**
     * The [ComposerTarget]'s JSON for the native core, with its agent
     * instance, [text] and the [viewport] captured at the tap.
     */
    private fun requestJson(target: ComposerTarget, text: String, viewport: Viewport?) = JSONObject()
        .put("node", target.nodeId)
        .put("session", target.session)
        .put("boot", target.boot)
        .put("pane", target.pane)
        .put("agent", target.agent ?: JSONObject.NULL)
        .put("instance", target.instance?.toJson() ?: JSONObject.NULL)
        .put("text", text)
        .put("viewport", targetViewport(target, viewport))

    private fun targetViewport(target: ComposerTarget, viewport: Viewport?) =
        viewportJson(target.nodeId, target.session, target.boot, target.pane, target.instance, viewport)

    /**
     * Waits for [receipt]'s answer on link [session], or settles it at once
     * when the answer came first or the link ended meanwhile.
     */
    private fun track(receipt: String, session: Long) {
        val answered = synchronized(receipts) {
            early.remove(receipt) ?: null.also { pending.add(receipt) }
        }
        when (answered) {
            true -> _deliveries.tryEmit(DeliveryUpdate.Confirmed(receipt))
            false -> _deliveries.tryEmit(DeliveryUpdate.Uncertain(receipt))
            // The link may have ended between queueing and now.
            null -> if (live() != session) settleUncertain()
        }
    }

    /**
     * One read of a pane's agent transcript; [request] and the answer are
     * JSON (see `transcript.rs`). Blocks on the host: call it off the main thread.
     */
    fun transcript(request: JSONObject): Result<TranscriptPage> {
        val session = live()
        if (session == 0L) return Result.failure(IOException("not connected"))
        return try {
            Result.success(TranscriptPage.parse(Native.transcript(session, request.toString())))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: org.json.JSONException) {
            Result.failure(IOException(e.message, e))
        }
    }

    /**
     * One image of a pane's transcript ([mediaRequest]). A refusal is a
     * success; a lost link or a bad reply is a failure. Blocks on the host:
     * call it off the main thread.
     */
    fun media(request: JSONObject): Result<MediaAnswer> {
        val session = live()
        if (session == 0L) return Result.failure(IOException("not connected"))
        return try {
            Result.success(MediaAnswer.parse(Native.transcript(session, request.toString())))
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: org.json.JSONException) {
            Result.failure(IOException(e.message, e))
        }
    }

    /**
     * Hands [text] to exactly [target]'s pane as one bracketed paste, and with
     * [submit] one Enter after it. Never retried: a lost link reports
     * [DeliveryUpdate.Uncertain] and the text stays with the user. Only a
     * check before the call (no link, another node or session) or the native
     * core's own refusal means nothing left; anything else once the call was
     * made is [Delivery.Uncertain] (see [deliveryOutcome]). The native side
     * refuses while the pane's last delivery is unsettled, and compares
     * [target]'s agent instance on the host: call it off the main thread.
     * [viewport] is what the view drew at the tap: the core refuses unless
     * the target's input box or cursor row lies wholly inside it.
     * [attachments] must all be staged for [target]; each image goes ahead
     * of the text as its own paste, each file is mentioned after the text
     * (docs/attachments.md). They are never resent either.
     */
    fun deliver(
        target: ComposerTarget,
        text: String,
        submit: Boolean,
        viewport: Viewport?,
        attachments: List<Attachment> = emptyList(),
        chat: Boolean = false,
    ): Delivery {
        val session = live()
        if (session == 0L || target.nodeId != node.id || target.session != node.session) {
            return Delivery.Refused("not_connected")
        }
        val request = requestJson(target, text, viewport)
            .put("submit", submit)
            .put("chat", chat)
            .put("attachments", org.json.JSONArray(attachments.map { it.toJson() }))
        val outcome = deliveryOutcome { Native.composerDeliver(session, request.toString()) }
        if (outcome is Delivery.Queued) track(outcome.receipt, session)
        return outcome
    }

    /**
     * Hands [text] and [attachments] to exactly [target]'s instance through
     * its endpoint, as request [id]; [deliverAs] is `auto` (an idle agent's
     * turn starts; a working OMP is steered, a working OpenCode refuses it),
     * `steer`, or `queue` (after the running turn, where the runtime has it). Only
     * [IngressSend.Absent] lets the caller use [deliver] instead: it is
     * answered when the instance publishes no endpoint, before anything is
     * sent. Never retried. Blocks on the host: call it off the main thread.
     */
    fun ingressSend(
        target: ComposerTarget,
        id: String,
        text: String,
        attachments: List<Attachment>,
        deliverAs: String,
    ): IngressSend {
        val session = live()
        if (session == 0L || target.nodeId != node.id || target.session != node.session) {
            return IngressSend.Refused("not_connected")
        }
        // Without the instance there is no endpoint to name.
        if (target.instance == null) return IngressSend.Absent
        val request = ingressRequest("send", target, id)
            .put("text", text)
            .put("attachments", org.json.JSONArray(attachments.map { it.toJson() }))
            .put("deliverAs", deliverAs)
        return ingressSendOutcome(id) { Native.ingress(session, request.toString()) }
    }

    /** What OMP recorded for request [id]; asks without sending anything. Blocks on the host. */
    fun ingressStatus(target: ComposerTarget, id: String): Result<IngressSend> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        return ingressStatusOutcome(id) {
            Native.ingress(session, ingressRequest("status", target, id).toString())
        }
    }

    /** Whether [target]'s instance has an endpoint, and its queue. Blocks on the host. */
    fun ingressState(target: ComposerTarget): Result<IngressProbe> {
        if (target.instance == null) return Result.success(IngressProbe.Absent)
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        // A status query for an id never sent records nothing and answers the queue.
        val request = ingressRequest("state", target, UUID.randomUUID().toString())
        return ingressProbe { Native.ingress(session, request.toString()) }
    }

    /** Takes queued entry [requestId] back out of OMP's queue; false when it had left it. Blocks on the host. */
    fun ingressWithdraw(target: ComposerTarget, requestId: String): Result<Boolean> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        return ingressWithdrawn {
            Native.ingress(session, ingressRequest("withdraw", target, requestId).toString())
        }
    }

    /** Steers queued follow-up [requestId] into the running turn; false when it had left the queue. Blocks on the host. */
    fun ingressPromote(target: ComposerTarget, requestId: String): Result<Boolean> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        return ingressPromoted {
            Native.ingress(session, ingressRequest("promote", target, requestId).toString())
        }
    }

    /**
     * The open dialogs on exactly [target]'s instance, its snapshot as given
     * (`{dialogId, kind, source, html?, form?, title?, options?, createdAt}`
     * each), and whether the server holds more than it shows. Blocks on the host.
     */
    fun ingressDialogs(target: ComposerTarget): Result<IngressDialogs> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        return ingressDialogsOutcome {
            Native.ingress(session, ingressRequest("dialogs", target, UUID.randomUUID().toString()).toString())
        }
    }

    /** The sessions exactly [target]'s session started, as its server lists them; it changes nothing. Blocks on the host. */
    fun ingressChildren(target: ComposerTarget): Result<IngressChildren> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        return ingressChildrenOutcome {
            Native.ingress(session, ingressRequest("children", target, UUID.randomUUID().toString()).toString())
        }
    }

    /**
     * Whether [target]'s program runs without its server, for the card's
     * runtime row (card §3.4). Asks the dialogs read, the one op both
     * OpenCode and Kilo take to their server; it changes nothing. Blocks on the host.
     */
    fun ingressNeedsServer(target: ComposerTarget): Result<Boolean> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        return ingressNeedsServerOutcome {
            Native.ingress(session, ingressRequest("dialogs", target, UUID.randomUUID().toString()).toString())
        }
    }

    /**
     * The user's [answer] to the one dialog [dialogId] on [target]'s
     * instance, as OMP takes it for that kind (a form's is `{data: …}`).
     * OMP's result comes back as it said it: `submitted`, `not_open`,
     * `invalid` or `kind_mismatch`. Anything less is a failure, never taken
     * for submitted. Blocks on the host.
     */
    fun ingressSubmitDialog(target: ComposerTarget, dialogId: String, answer: JSONObject): Result<String> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        val request = ingressRequest("dialog_submit", target, UUID.randomUUID().toString())
            .put("dialogId", dialogId)
            .put("answer", answer)
        return ingressSubmitOutcome(dialogId) { Native.ingress(session, request.toString()) }
    }

    /**
     * Decline the one question [dialogId] on [target]'s instance (OpenCode's
     * form, Kilo's question). `cancelled` or `not_open`, or
     * [DIALOG_ANSWERED_ELSEWHERE]; anything less fails. Blocks on the host.
     */
    fun ingressCancelDialog(target: ComposerTarget, dialogId: String): Result<String> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        val request = ingressRequest("dialog_cancel", target, UUID.randomUUID().toString()).put("dialogId", dialogId)
        return ingressCancelOutcome(dialogId) { Native.ingress(session, request.toString()) }
    }

    /**
     * Stop the turn running on exactly [target]'s instance: through the
     * agent's own server (OpenCode, Kilo), else with one key in its pane
     * ([StopRoute.Pty]). True when one was stopped, false when the server
     * found none running; an unconfirmed key fails. Blocks on the host.
     */
    fun ingressAbort(target: ComposerTarget): Result<Boolean> {
        val session = ingressSession(target) ?: return Result.failure(IOException("not connected"))
        return ingressAbortOutcome {
            Native.ingress(session, ingressRequest("abort", target, UUID.randomUUID().toString()).toString())
        }
    }

    private fun ingressSession(target: ComposerTarget): Long? {
        val session = live()
        if (session == 0L || target.nodeId != node.id || target.session != node.session || target.instance == null) {
            return null
        }
        return session
    }

    /**
     * A steer's first step: [text] to exactly [target]'s pane and agent
     * instance as one paste, alone; Enter waits for [steerEnter]. Never
     * retried. Gated on [viewport] as [deliver] is. Blocks on the host: call
     * it off the main thread.
     */
    fun steer(target: ComposerTarget, text: String, viewport: Viewport?): SteerStart {
        val session = live()
        if (session == 0L || target.nodeId != node.id || target.session != node.session) {
            return SteerStart.Refused("not_connected")
        }
        if (target.instance == null) return SteerStart.Refused("instance_unknown")
        val outcome = steerStartOutcome { Native.composerSteer(session, requestJson(target, text, viewport).toString()) }
        if (outcome is SteerStart.Staged) track(outcome.receipt, session)
        return outcome
    }

    /**
     * The one Enter of the steer staged on [target] whose paste receipt
     * [pasteReceipt] was confirmed. Blocks on the host: call it off the main
     * thread. With no live link nothing is asked and it is refused; a call
     * that fails midway is uncertain, since Enter may have left. [viewport] is
     * what the view drew when Enter was asked, gated as [deliver] is.
     */
    fun steerEnter(target: ComposerTarget, pasteReceipt: String, viewport: Viewport?): SteerEnter {
        val session = live()
        if (session == 0L || target.nodeId != node.id || target.session != node.session) {
            return SteerEnter.Refused("not_connected")
        }
        val seen = targetViewport(target, viewport).takeIf { it != JSONObject.NULL }?.toString()
        val outcome = steerEnterOutcome {
            Native.composerSteerEnter(session, targetJson(target.nodeId, target.session, target.pane), pasteReceipt, seen)
        }
        if (outcome is SteerEnter.Queued) track(outcome.receipt, session)
        return outcome
    }

    /** Ends the steer staged on that pane without Enter; its paste stays in the input box. */
    fun steerCancel(nodeId: String, session: String, pane: String) =
        Native.composerSteerCancel(targetJson(nodeId, session, pane))

    // Herdr's own verbs, as its other clients use them.
    fun focusWorkspace(id: String) = request("workspace.focus", JSONObject().put("workspace_id", id))
    fun focusTab(id: String) = request("tab.focus", JSONObject().put("tab_id", id))
    fun focusPane(id: String) = request("pane.focus", JSONObject().put("pane_id", id))
    /** A blank [label] leaves the name to Herdr, which numbers the tab. */
    fun newTab(workspace: String, label: String = "") = request(
        "tab.create",
        JSONObject().put("workspace_id", workspace).put("focus", true).apply {
            label.trim().takeIf { it.isNotEmpty() }?.let { put("label", it) }
        },
    )
    fun closeTab(id: String) = request("tab.close", JSONObject().put("tab_id", id))
    fun renameTab(id: String, label: String) = request("tab.rename", JSONObject().put("tab_id", id).put("label", label))
    fun split(pane: String, direction: String) =
        request("pane.split", JSONObject().put("target_pane_id", pane).put("direction", direction).put("focus", true))
    fun closePane(id: String) = request("pane.close", JSONObject().put("pane_id", id))
    fun toggleZoom(id: String) = request("pane.zoom", JSONObject().put("pane_id", id).put("mode", "toggle"))
    fun newWorkspace(source: String?) = request(
        "workspace.create",
        JSONObject().put("focus", true).apply { source?.let { put("source_workspace_id", it) } },
    )
    fun renameWorkspace(id: String, label: String) =
        request("workspace.rename", JSONObject().put("workspace_id", id).put("label", label))
    fun closeWorkspace(id: String) = request("workspace.close", JSONObject().put("workspace_id", id))
}

/**
 * What a `composerDeliver` [call] that was made amounts to. Only the native
 * core's own refusal, which it gives before any input is sent, says nothing
 * left. A call that throws, a reply that cannot be read, a queued reply with
 * no receipt and any other outcome may follow a queued paste: all are
 * uncertain, whatever the link's state is by now.
 */
internal fun deliveryOutcome(call: () -> String): Connection.Delivery {
    val reply = nativeReply(call) ?: return Connection.Delivery.Uncertain(UNCONFIRMED)
    val receipt = reply.optString("receipt")
    val reason = reply.optString("reason")
    return when (reply.optString("outcome")) {
        "queued" -> if (receipt.isEmpty()) Connection.Delivery.Uncertain(UNCONFIRMED) else Connection.Delivery.Queued(receipt)
        "refused" -> if (reason.isEmpty()) Connection.Delivery.Uncertain(UNCONFIRMED) else Connection.Delivery.Refused(reason)
        else -> Connection.Delivery.Uncertain(reason.ifEmpty { UNCONFIRMED })
    }
}

/**
 * What a `composerSteer` [call] that was made amounts to, by the rule of
 * [deliveryOutcome]: only a refusal with its reason says the paste did not
 * leave, and only a staged reply with a receipt is staged. Anything else may
 * follow a paste that left, so it is uncertain and nothing is resent.
 */
internal fun steerStartOutcome(call: () -> String): Connection.SteerStart {
    val reply = nativeReply(call) ?: return Connection.SteerStart.Uncertain(UNCONFIRMED)
    val receipt = reply.optString("receipt")
    val reason = reply.optString("reason")
    return when (reply.optString("outcome")) {
        "staged" -> if (receipt.isEmpty()) Connection.SteerStart.Uncertain(UNCONFIRMED) else Connection.SteerStart.Staged(receipt)
        "refused" -> if (reason.isEmpty()) Connection.SteerStart.Uncertain(UNCONFIRMED) else Connection.SteerStart.Refused(reason)
        else -> Connection.SteerStart.Uncertain(reason.ifEmpty { UNCONFIRMED })
    }
}

/**
 * What a `composerSteerEnter` [call] that was made amounts to. Waiting and
 * refused say Enter did not leave and need their reason; queued needs its
 * receipt. Anything else may follow an Enter that left: uncertain.
 */
internal fun steerEnterOutcome(call: () -> String): Connection.SteerEnter {
    val reply = nativeReply(call) ?: return Connection.SteerEnter.Uncertain(UNCONFIRMED)
    val receipt = reply.optString("receipt")
    val reason = reply.optString("reason")
    return when (reply.optString("outcome")) {
        "queued" -> if (receipt.isEmpty()) Connection.SteerEnter.Uncertain(UNCONFIRMED) else Connection.SteerEnter.Queued(receipt)
        "waiting" -> if (reason.isEmpty()) Connection.SteerEnter.Uncertain(UNCONFIRMED) else Connection.SteerEnter.Waiting(reason)
        "refused" -> if (reason.isEmpty()) Connection.SteerEnter.Uncertain(UNCONFIRMED) else Connection.SteerEnter.Refused(reason)
        else -> Connection.SteerEnter.Uncertain(reason.ifEmpty { UNCONFIRMED })
    }
}

/** The native core's JSON reply to a call that was made, or null when the call threw or the reply cannot be read. */
private fun nativeReply(call: () -> String): JSONObject? = try {
    JSONObject(call())
} catch (e: IOException) {
    null
} catch (e: JSONException) {
    null
}

/** The reason of a delivery whose fate the app cannot know, as the native core names it. */
private const val UNCONFIRMED = "unconfirmed"

/** Answers kept for deliveries that have not registered yet; each comes within milliseconds. */
private const val EARLY_ANSWERS = 32
