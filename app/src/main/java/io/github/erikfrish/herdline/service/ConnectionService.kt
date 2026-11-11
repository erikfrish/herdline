/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.readableTitle
import io.github.erikfrish.herdline.data.AppLanguages
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.data.Settings
import io.github.erikfrish.herdline.ui.MainActivity
import io.github.erikfrish.herdline.ui.session.notificationBody
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/**
 * Keeps attached connections alive while the app is in the background, and
 * turns the daemon's own agent notifications (an agent needs input or
 * finished) into Android notifications. The daemon keeps running regardless.
 *
 * The foreground notification doubles as the live update of pinned agents
 *. It lives exactly as long as the service, so a killed
 * process never leaves a card behind that claims a status nobody is
 * watching anymore.
 */
class ConnectionService : Service() {
    private var watcher: Job? = null
    private var card: Job? = null

    /** What the foreground notification shows now, rebuilt only when it changes. */
    private var shown: Shown? = null

    /**
     * [lock] words the Attached content and [live] the live update; [minute]
     * changes once a minute while a shown row has a time, so "N min" refreshes.
     */
    private data class Shown(
        val count: Int,
        val title: String?,
        val card: LiveCard?,
        val lock: LockDetail,
        val live: LockDetail,
        val promote: Boolean,
        val minute: Long = 0,
    )

    /** Nodes whose connection-lost note is posted, to re-post it when the privacy setting changes. */
    private val lost = HashMap<String, Connection>()

    /** The lock-screen privacy last applied to the lost notes, to notice setting changes. */
    private var appliedLock: LockDetail? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(AppLanguages.wrap(base))

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as HerdlineApp
        if (intent?.action == ACTION_DISCONNECT_ALL) {
            app.disconnectAll()
            stopSelf()
            return START_NOT_STICKY
        }
        val count = app.connections.value.size
        if (count == 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_CARD_DISMISSED || intent?.action == ACTION_UNPIN) {
            // Swipe and Unpin both unpin what the live update showed, never
            // pins the plain Attached content left unnamed (NB-C2). The
            // pin change updates the foreground notification in place to the
            // unpromoted Attached content; a swiped one is not raised again.
            app.monitor.unpin(dismissedPins(shown?.card))
            return START_STICKY
        }
        val settings = app.settings.settings.value
        if (LockGate.held) {
            LockGate.held = false
            AgentNotifications.republish(this, settings.lockDetail)
        }
        val live = liveLevel(app.settings.liveLockDetail.value, settings.lockDetail)
        val state = (shown ?: Shown(count, null, null, settings.lockDetail, live, settings.liveActivity))
            .copy(count = count, title = app.connections.value.values.firstOrNull()?.node?.title)
        shown = state
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            build(state),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            },
        )
        if (watcher == null) {
            watcher = app.scope.launch {
                app.connections.collectLatest { connections ->
                    connections.values.forEach { connection -> launch { forward(app, connection) } }
                }
            }
        }
        if (card == null) card = watchCard(app)
        return START_STICKY
    }

    private suspend fun forward(app: HerdlineApp, connection: Connection) {
        connection.events.collect { event ->
            if (event is Connection.Event.Notification) {
                val settings = app.settings.settings.value
                // Unless this host's session screen is resumed and unlocked, whose banner shows it
                // instead: also on Hosts, Settings or another host, where nothing showed it (BN-1 §2).
                val route = routeNote(
                    viewing = app.viewingSession == connection.node.id,
                    locked = LockGate.isDeviceLocked(this),
                    enabled = if (event.attention) settings.notifyAttention else settings.notifyDone,
                )
                if (route == NoteSurface.System) {
                    val view = connection.view.value
                    AgentNotifications.post(
                        this,
                        nodeId = connection.node.id,
                        node = connection.node.title,
                        pane = event.pane,
                        agent = view?.agents?.firstOrNull { it.pane == event.pane }?.displayName,
                        title = readableTitle(event.title) ?: connection.node.title,
                        // Herdr's body names tabs by number, so two agents read the
                        // same (lab.3 D6); the Herdr path and the pane's task tell them apart.
                        body = event.pane?.let { pane -> view?.let { notificationBody(it, pane) } } ?: event.body,
                        attention = event.attention,
                        lock = settings.lockDetail,
                    )
                }
            }
        }
    }

    /** Follows every attached node's state and view, the pinned agents and the settings. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun watchCard(app: HerdlineApp): Job = app.scope.launch {
        val lastSeen = HashMap<String, Long>()
        val connected = HashMap<String, Boolean>()
        val clock = StatusClock()
        /** Done rows waiting to unpin themselves, cancelled if they leave Done. */
        val finishing = HashMap<MonitorKey, Job>()
        /** Pinned agents whose current Blocked episode was handed to an attention notification. */
        val handedOff = HashSet<MonitorKey>()
        val minutes = flow {
            while (true) {
                emit(System.currentTimeMillis() / 60_000)
                delay(60_000 - System.currentTimeMillis() % 60_000)
            }
        }
        val nodes = app.connections.flatMapLatest { connections ->
            if (connections.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(connections.values.map { c -> combine(c.state, c.view) { state, view -> Triple(c, state, view) } }) { it.toList() }
            }
        }
        // The lock edge (screen off / on / unlock) re-posts the card so the
        // lock screen shows only the permitted content, however the system's
        // own redaction is set (lab.3 finding D10).
        val service = this@ConnectionService
        LockGate.onLockChange = {
            shown?.let { latest ->
                if (AgentNotifications.canNotify(service)) {
                    NotificationManagerCompat.from(service).notify(NOTIFICATION_ID, build(latest))
                }
            }
        }
        val choices = combine(app.monitor.monitored, app.settings.settings, app.settings.liveLockDetail) { monitored, settings, live ->
            Triple(monitored, settings, liveLevel(live, settings.lockDetail))
        }
        combine(nodes, choices, minutes) { list, (monitored, settings, level), minute -> Choice(list, monitored, settings, level, minute) }
            .collect { (list, monitored, settings, level, minute) ->
                val now = System.currentTimeMillis()
                val context = this@ConnectionService
                // A privacy-setting change re-posts the lost note: it is not re-posted
                // on the lock edge, so a note posted under Names would keep the host
                // under Hidden (P8). HerdlineApp re-posts the agent notes.
                if (appliedLock != settings.lockDetail) {
                    appliedLock = settings.lockDetail
                    val active = context.getSystemService(NotificationManager::class.java).activeNotifications
                    lost.values.toList().forEach { connection ->
                        if (active.any { it.id == LOST_ID && it.tag == lostTag(connection) }) {
                            postLost(connection, settings.lockDetail)
                        } else {
                            lost.remove(connection.node.id)
                        }
                    }
                }
                // A node detached by the user: its notifications are no longer current either.
                val present = list.map { it.first.node.id }.toSet()
                (connected.keys - present).forEach { gone ->
                    AgentNotifications.linkChanged(context, gone, up = false, at = lastSeen[gone] ?: now, lock = settings.lockDetail)
                    clock.forget(gone)
                    lost[gone]?.let(::cancelLost)
                    connected.remove(gone)
                    lastSeen.remove(gone)
                }
                val snapshots = list.map { (connection, state, view) ->
                    val id = connection.node.id
                    val up = state is Connection.State.Connected
                    if (up) lastSeen[id] = now
                    val was = connected[id]
                    if (was != up) {
                        if (was == true && !connection.isClosed) noticeLost(connection, settings) else if (up) cancelLost(connection)
                        if (was != null) AgentNotifications.linkChanged(context, id, up, lastSeen[id] ?: now, settings.lockDetail)
                        if (!up) clock.forget(id)
                        connected[id] = up
                    }
                    if (up && view != null) {
                        AgentNotifications.resolve(context, id, view)
                        view.agents.forEach { clock.observe(MonitorKey(id, it.pane), it.status, now) }
                    }
                    NodeSnapshot(id, connection.node.title, up, view, lastSeen[id] ?: now)
                }
                val live = if (settings.liveActivity) liveCard(snapshots, monitored, clock::since, clock::worked) else null
                // While the link is lost the rows are a memory: Done timers and
                // hand-offs keep their state until it returns (NB-C3), so a row
                // still Done after a reconnect is neither shown nor timed twice.
                if (live?.staleSince == null) {
                    val rows = live?.rows.orEmpty()
                    finish(app, rows, finishing)
                    handOff(app, rows, list, settings, handedOff)
                }
                val next = Shown(
                    count = list.size,
                    title = list.firstOrNull()?.first?.node?.title,
                    card = live,
                    lock = settings.lockDetail,
                    live = level,
                    promote = settings.liveActivity,
                    minute = if (live?.rows?.any { it.since != null || it.worked != null } == true) minute else 0,
                )
                AgentNotifications.card = when (live?.let(::liveState)) {
                    null -> Promotion.NoCard
                    LiveState.Disconnected -> Promotion.Stale
                    else -> Promotion.Requested
                }
                if (next != shown && next.count > 0) {
                    shown = next
                    if (AgentNotifications.canNotify(context)) NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, build(next))
                }
            }
    }

    private data class Choice(
        val list: List<Triple<Connection, Connection.State, ShellView?>>,
        val monitored: Set<MonitorKey>,
        val settings: Settings,
        val level: LockDetail,
        val minute: Long,
    )

    /** A finished pin unpins itself shortly after, unless it starts working again first. */
    private fun finish(app: HerdlineApp, rows: List<LiveRow>, finishing: HashMap<MonitorKey, Job>) {
        val done = rows.filter { it.status == AgentStatus.Done }.map { it.key }.toSet()
        finishing.keys.retainAll { key -> (key in done).also { if (!it) finishing[key]?.cancel() } }
        done.forEach { key ->
            if (key !in finishing) {
                finishing[key] = app.scope.launch {
                    delay(DONE_UNPIN_MS)
                    finishing.remove(key)
                    app.monitor.unpin(setOf(key))
                }
            }
        }
    }

    /**
     * A pinned agent turned Blocked: the live update drops it, and one
     * attention notification takes over, deduplicated with Herdr's own
     *. An episode counts as handed off only once a note could
     * be posted: one that begins in the foreground is handed off when the app
     * goes to the background (NB-C1), on the card's next update, at the
     * latest the minute tick.
     */
    private fun handOff(
        app: HerdlineApp,
        rows: List<LiveRow>,
        list: List<Triple<Connection, Connection.State, ShellView?>>,
        settings: Settings,
        handedOff: HashSet<MonitorKey>,
    ) {
        val blocked = rows.filter { it.status == AgentStatus.Blocked }
        handedOff.retainAll(blocked.map { it.key }.toSet())
        blocked.forEach { row ->
            if (app.foreground || !handedOff.add(row.key) || !settings.notifyAttention) return@forEach
            val view = list.firstOrNull { it.first.node.id == row.nodeId }?.third
            AgentNotifications.handoff(
                this,
                nodeId = row.nodeId,
                node = row.node,
                pane = row.pane,
                agent = row.name,
                title = row.title ?: row.node,
                body = view?.let { notificationBody(it, row.pane) },
                lock = settings.lockDetail,
            )
        }
    }

    /** A quiet note that the SSH link dropped while the app was away; the daemon is unaffected. */
    private fun noticeLost(connection: Connection, settings: Settings) {
        val app = application as HerdlineApp
        if (app.foreground || !settings.notifyConnection || !AgentNotifications.canNotify(this)) return
        lost[connection.node.id] = connection
        postLost(connection, settings.lockDetail)
    }

    private fun postLost(connection: Connection, lock: LockDetail) {
        val tag = lostTag(connection)
        val words = lostContent(ResourceTexts(this), connection.node.title, lock)
        val notification = NotificationCompat.Builder(this, AgentNotifications.CHANNEL_CONNECTION)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(words.main.title)
            .setContentText(words.main.text)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(AgentNotifications.open(this, connection.node.id, null, tag))
            .setVisibility(AgentNotifications.visibility(lock))
            .setPublicVersion(AgentNotifications.publicOf(this, AgentNotifications.CHANNEL_CONNECTION, words.public).build())
        NotificationManagerCompat.from(this).notify(tag, LOST_ID, notification.build())
    }

    private fun cancelLost(connection: Connection) {
        lost.remove(connection.node.id)
        NotificationManagerCompat.from(this).cancel(lostTag(connection), LOST_ID)
    }

    private fun lostTag(connection: Connection) = "lost\n" + connection.node.id

    private fun build(shown: Shown): Notification {
        // Chosen per lock state; LockGate re-posts this card on the lock and
        // unlock edges (onLockChange), so the lock screen shows only the
        // permitted words whatever Android's own redaction is (D10, lab.6 I1).
        val locked = LockGate.isDeviceLocked(this)
        val time = DateFormat.getTimeFormat(this)
        val fields = liveFields(ResourceLiveTexts(this), shown.card, shown.live, locked, System.currentTimeMillis()) { time.format(it) }
        val top = fields?.top ?: shown.card?.rows?.firstOrNull()
        val content = if (top != null) {
            AgentNotifications.open(this, top.nodeId, top.pane, "live")
        } else {
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        // Android 14+ lets the user swipe the foreground notification away: that unpins.
        val dismiss = PendingIntent.getService(
            this, 2, Intent(this, ConnectionService::class.java).setAction(ACTION_CARD_DISMISSED), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, AgentNotifications.CHANNEL_LIVE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(content)
            .setDeleteIntent(dismiss)
        if (fields == null) {
            val disconnect = PendingIntent.getService(
                this, 1, Intent(this, ConnectionService::class.java).setAction(ACTION_DISCONNECT_ALL), PendingIntent.FLAG_IMMUTABLE,
            )
            val words = attachedContent(ResourceTexts(this), shown.count, shown.title, shown.lock, locked)
            return builder
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(words.main.title)
                .setContentText(words.main.text)
                .setShowWhen(false)
                .addAction(0, getString(R.string.disconnect), disconnect)
                .setVisibility(AgentNotifications.visibility(shown.lock))
                .setPublicVersion(AgentNotifications.publicOf(this, AgentNotifications.CHANNEL_LIVE, words.public).setOngoing(true).setShowWhen(false).build())
                .build()
        }
        val main = fields.main
        builder
            .setSmallIcon(glyphIcon(main.glyph))
            .setContentTitle(main.title)
            .setContentText(main.text)
            .setTicker(main.spoken)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(AgentNotifications.visibility(shown.live))
        main.liveTime(builder)
        if (Build.VERSION.SDK_INT >= API_METRIC_STYLE && main.metrics.isNotEmpty()) {
            builder.setStyle(metricStyle(main.metrics))
        } else {
            main.big?.let { builder.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
        }
        builder.setRequestPromotedOngoing(fields.promote && shown.promote)
        if (fields.unpin) {
            val unpin = PendingIntent.getService(
                this, 3, Intent(this, ConnectionService::class.java).setAction(ACTION_UNPIN), PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.live_unpin), unpin)
        }
        val public = fields.public
        val publicBuilder = AgentNotifications.publicOf(this, AgentNotifications.CHANNEL_LIVE, NoteContent(public.title, public.text), glyphIcon(public.glyph))
            .setOngoing(true)
        // The lock screen's own time and chip, as its level permits (NB-C4).
        public.liveTime(publicBuilder)
        builder.setPublicVersion(publicBuilder.build())
        return builder.build()
    }

    /**
     * This face's time: the status-bar chip text where the system shows one
     * (Android 16; null lets the chronometer show, "" the icon alone), and a
     * chronometer only from a time Herdline saw begin, never while the link
     * is down.
     */
    private fun LiveFace.liveTime(builder: NotificationCompat.Builder) {
        chip?.let { builder.setShortCriticalText(it) }
        since?.let { builder.setWhen(it).setShowWhen(true).setUsesChronometer(true) } ?: builder.setShowWhen(false)
    }

    private fun metricStyle(metrics: List<LiveMetric>): NotificationCompat.MetricStyle {
        val style = NotificationCompat.MetricStyle()
        metrics.forEach { metric ->
            val value = when (metric) {
                is LiveMetric.Elapsed -> NotificationCompat.Metric.TimeDifference.forStopwatch(
                    Instant.ofEpochMilli(metric.since),
                    NotificationCompat.Metric.TimeDifference.FORMAT_CHRONOMETER,
                )
                is LiveMetric.Count -> NotificationCompat.Metric.FixedInt(metric.count)
            }
            style.addMetric(NotificationCompat.Metric(value, metric.label))
        }
        return style.setCriticalMetric(0)
    }

    override fun onDestroy() {
        watcher?.cancel()
        watcher = null
        card?.cancel()
        card = null
        shown = null
        LockGate.onLockChange = null
        AgentNotifications.card = Promotion.NoCard
        // The lost notes belong to connections that are gone.
        lost.values.toList().forEach(::cancelLost)
        // Nothing keeps the process alive now: the notes left in the shade
        // take their locked words for good, until a connection starts again.
        AgentNotifications.hold(this, (application as HerdlineApp).settings.settings.value.lockDetail)
        super.onDestroy()
    }

    companion object {
        internal const val NOTIFICATION_ID = 1
        private const val LOST_ID = 4
        private const val ACTION_DISCONNECT_ALL = "io.github.erikfrish.herdline.DISCONNECT_ALL"
        private const val ACTION_CARD_DISMISSED = "io.github.erikfrish.herdline.CARD_DISMISSED"
        private const val ACTION_UNPIN = "io.github.erikfrish.herdline.UNPIN"

        /** A finished pin stays this long, so the user sees it finish, then unpins itself. */
        private const val DONE_UNPIN_MS = 10_000L

        /** MetricStyle is new in Android 17 (API 37). */
        private const val API_METRIC_STYLE = 37

        fun update(context: Context) {
            val app = context.applicationContext as HerdlineApp
            val intent = Intent(context, ConnectionService::class.java)
            if (app.connections.value.isEmpty()) {
                context.stopService(intent)
            } else {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
