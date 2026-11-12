package io.github.erikfrish.herdline.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.widget.Toast
import android.text.format.DateFormat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.github.erikfrish.herdline.HerdlineApp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.data.IconVariant
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.ui.MainActivity

/** What the live card is doing, as far as the platform reports it. */
enum class Promotion {
    /** The live card setting is off. */
    Off,

    /** No pinned agent is working or just finished, so the card shows the plain Attached content. */
    NoCard,

    /** The card is stale and is not offered for promotion. */
    Stale,

    /** Android before 16 has no promoted notifications: an ordinary ongoing card. */
    Unsupported,

    /** The user or the system does not allow Herdline promoted notifications. */
    NotAllowed,

    /** The posted card lacks what Android requires to promote it. */
    NotPromotable,

    /** Promotion requested and allowed; Android or the OEM has not promoted the posted card. */
    Requested,

    /** The system marked the posted card as promoted. */
    Promoted,
}

/**
 * Agent notifications: channels, one slot per (node, pane), the alert
 * policy, resolution, stale marking and the live card's promotion state.
 * Everything runs on the main thread.
 */
object AgentNotifications {
    const val CHANNEL_ATTENTION = "agent_attention"
    const val CHANNEL_DONE = "agent_done"
    const val CHANNEL_CONNECTION = "connection_status"
    const val CHANNEL_LIVE = "live_card"

    /** Channel IDs of earlier versions, removed on upgrade. */
    private val OBSOLETE_CHANNELS = listOf("agents", "connection")

    /** ID shared by every per-(node, pane) agent notification; the tag tells them apart. */
    private const val AGENT_ID = 2
    private const val SUMMARY_ID = 3

    /** One posted agent notification, kept to re-post it as stale or current. */
    private data class Note(
        val key: AlertKey,
        val kind: AlertKind,
        val node: String,
        val agent: String?,
        val title: String,
        val body: String?,
        val at: Long,
        /** When the link went down while this note was shown; null while current. */
        val staleSince: Long? = null,
    )

    /** What a child notification carries beyond its constant fields. */
    private data class ChildPost(val words: Redacted, val lock: LockDetail, val at: Long, val variant: IconVariant)
    private data class SummaryPost(val words: Redacted, val lock: LockDetail, val variant: IconVariant)

    private val alerts = AgentAlerts()
    private val notes = HashMap<String, Note>()
    private val children = Posted<ChildPost>()
    private val summaries = Posted<SummaryPost>()

    /** How long after a burst of re-posts [reassert] checks what the shade shows. */
    private const val REASSERT_MS = 1_500L
    private val main = Handler(Looper.getMainLooper())
    private var reassertLock: LockDetail? = null
    private val reassertNow = Runnable {
        val lock = reassertLock ?: return@Runnable
        reassertLock = null
        reassert(io.github.erikfrish.herdline.HerdlineApp.instance, lock)
    }

    /** What the service last did with the live card, for [promotion]. */
    @Volatile
    internal var card: Promotion = Promotion.NoCard
    @Volatile
    internal var promotionRequested: Boolean = false

    internal data class PromotionDetails(val requested: Boolean, val allowed: Boolean?, val eligible: Boolean?, val shown: Boolean?)

    internal fun promotionDetails(context: Context): PromotionDetails {
        val requested = promotionRequested
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return PromotionDetails(requested, null, null, null)
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = manager.activeNotifications.firstOrNull { it.id == ConnectionService.NOTIFICATION_ID && it.tag == null }?.notification
        return PromotionDetails(requested, manager.canPostPromotedNotifications(), posted?.hasPromotableCharacteristics(), posted?.let { it.flags and Notification.FLAG_PROMOTED_ONGOING != 0 } ?: false)
    }

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        OBSOLETE_CHANNELS.forEach(manager::deleteNotificationChannel)
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_ATTENTION, context.getString(R.string.channel_attention), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_DONE, context.getString(R.string.channel_done), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_CONNECTION, context.getString(R.string.channel_connection), NotificationManager.IMPORTANCE_LOW).apply { description = context.getString(R.string.attached_detail) },
                NotificationChannel(CHANNEL_LIVE, context.getString(R.string.channel_live), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    /**
     * Removes every notification an earlier process left behind. Their words
     * and redaction lived in that process's memory, so nothing would re-post
     * them on the next lock or privacy-setting change, and one posted while
     * unlocked would show its full content on the lock screen (lab.7.1 S2).
     * Called once at process start, before anything is posted.
     */
    fun dropOrphans(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancelAll()
        children.clear()
        summaries.clear()
    }

    fun channelOf(kind: AlertKind): String = when (kind) {
        AlertKind.NeedsInput -> CHANNEL_ATTENTION
        AlertKind.Done -> CHANNEL_DONE
    }

    internal fun canNotify(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * The tap target of a notification: [pane] of [nodeId]. [identity] makes
     * the PendingIntent unique to its notification, so extras of one never
     * replace another's.
     */
    internal fun open(context: Context, nodeId: String, pane: String?, identity: String): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        MainActivity.intent(context, nodeId, pane).setIdentifier(identity),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** A daemon notification for [pane] of [nodeId] arrived while Herdline is in the background. */
    fun post(
        context: Context,
        nodeId: String,
        node: String,
        pane: String?,
        agent: String?,
        title: String,
        body: String?,
        attention: Boolean,
        lock: LockDetail,
    ) {
        if (!canNotify(context)) return
        val key = AlertKey(nodeId, pane)
        val now = System.currentTimeMillis()
        val posted = notes.values.associate { it.key to it.at }
        if (HerdrEcho.isEcho(key, now, posted)) return
        val kind = if (attention) AlertKind.NeedsInput else AlertKind.Done
        val manager = context.getSystemService(NotificationManager::class.java)
        val alert = alerts.onEvent(key, kind, showing(manager, key.tag))
        if (alert == Alert.Skip) return
        val note = Note(key, kind, node, agent, title, body, now)
        notes[key.tag] = note
        publish(context, manager, note, lock, silent = alert == Alert.Silent, force = true)
        HerdrEcho.echoBefore(key, now, posted)?.let { cancel(manager, it) }
    }

    /**
     * A pinned agent of [pane] turned Blocked: its live update is demoted and
     * this is the one attention notification for that Blocked episode
     *. It shares the slot and the alert policy of
     * Herdr's own Needs input notification, so whichever comes first alerts
     * and the other never alerts a second time; this one never updates a note
     * already posted.
     */
    fun handoff(context: Context, nodeId: String, node: String, pane: String, agent: String?, title: String, body: String?, lock: LockDetail) {
        if (!canNotify(context)) return
        val key = AlertKey(nodeId, pane)
        val manager = context.getSystemService(NotificationManager::class.java)
        if (alerts.onEvent(key, AlertKind.NeedsInput, showing(manager, key.tag)) != Alert.Loud) return
        val note = Note(key, AlertKind.NeedsInput, node, agent, title, body, System.currentTimeMillis())
        notes[key.tag] = note
        publish(context, manager, note, lock, silent = false, force = true)
    }

    /**
     * The user is looking at [pane] of [nodeId]: its notification goes away.
     * A repeat of the same state does not bring it back; the next new
     * Needs input or Done does.
     */
    fun clear(context: Context, nodeId: String, pane: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        cancel(manager, AlertKey(nodeId, pane))
        tidy(manager, nodeId)
    }

    /** The daemon's [view] of [nodeId]: notifications whose state has passed are withdrawn. */
    fun resolve(context: Context, nodeId: String, view: ShellView) {
        val manager = context.getSystemService(NotificationManager::class.java)
        var changed = false
        for (agent in view.agents) {
            val key = AlertKey(nodeId, agent.pane)
            if (alerts.onStatus(key, agent.status) && key.tag in notes) {
                cancel(manager, key)
                changed = true
            }
        }
        if (changed) tidy(manager, nodeId)
    }

    /**
     * The link to [nodeId] went down at [at], or came back ([up]). Posted
     * notifications of that node are re-posted silently, marked as not
     * current while the link is down.
     */
    fun linkChanged(context: Context, nodeId: String, up: Boolean, at: Long, lock: LockDetail) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val prefix = AlertKey.prefix(nodeId)
        for (note in notes.values.filter { it.key.nodeId == nodeId }) {
            if (showing(manager, note.key.tag)) {
                val next = note.copy(staleSince = if (up) null else at)
                notes[note.key.tag] = next
                publish(context, manager, next, lock, silent = true, force = false)
            } else {
                forget(note.key.tag)
            }
        }
        // D13: a summary without children is never left behind, on any path
        // that removes a node's last notification.
        if (notes.keys.none { it.startsWith(prefix) }) cancelSummary(manager, nodeId)
        scheduleReassert(lock)
    }

    /**
     * The last connection is going: nothing keeps this process alive after
     * it, so no lock edge may re-post a note again. Every shown note takes
     * its locked words now, before the link-down re-posts, whatever order
     * those come in (lab.7.3 J1). A connection starting the service again
     * releases [LockGate.held].
     */
    fun hold(context: Context, lock: LockDetail) {
        LockGate.held = true
        republish(context, lock)
    }

    /**
     * Re-posts every shown notification on its own tag, silently. Called on
     * the lock and unlock edges and when the privacy setting changes: while
     * the device is locked the permitted content stands as the main content
     * (see [publish]), and when it unlocks the full content must return
     * without a second sound. A re-post of unchanged words is skipped.
     */
    fun republish(context: Context, lock: LockDetail) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        for (note in notes.values.filter { showing(manager, it.key.tag) }.sortedBy { it.at }) {
            publish(context, manager, note, lock, silent = true, force = false)
        }
        scheduleReassert(lock)
    }

    /** Checks the shade once the burst is over; a later burst moves the check. */
    private fun scheduleReassert(lock: LockDetail) {
        reassertLock = lock
        main.removeCallbacks(reassertNow)
        main.postDelayed(reassertNow, REASSERT_MS)
    }

    /**
     * Re-posts every shown note whose title or text in the shade differs from
     * the words last posted for it, or whose summary does: the platform drops
     * updates past its rate limit without telling the app.
     */
    private fun reassert(context: Context, lock: LockDetail) {
        if (!canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val active = manager.activeNotifications
        fun differs(id: Int, tag: String, want: NoteContent?): Boolean {
            val shown = active.firstOrNull { it.id == id && it.tag == tag }?.notification ?: return false
            if (want == null) return false
            val extras = shown.extras
            return extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() != want.title ||
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() != want.text
        }
        for (note in notes.values.sortedBy { it.at }) {
            val tag = note.key.tag
            val nodeId = note.key.nodeId
            if (differs(AGENT_ID, tag, children[tag]?.words?.main) || differs(SUMMARY_ID, summaryTag(nodeId), summaries[summaryTag(nodeId)]?.words?.main)) {
                publish(context, manager, note, lock, silent = true, force = true)
            }
        }
    }

    /** The live card's state: what Herdline did with it, then what the platform says about promotion. */
    fun promotion(context: Context, liveActivity: Boolean): Promotion {
        if (!liveActivity) return Promotion.Off
        val state = card
        if (state != Promotion.Requested) return state
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return Promotion.Unsupported
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.canPostPromotedNotifications()) return Promotion.NotAllowed
        val posted = manager.activeNotifications.firstOrNull { it.id == ConnectionService.NOTIFICATION_ID && it.tag == null }?.notification
            ?: return Promotion.NoCard
        if (posted.flags and Notification.FLAG_PROMOTED_ONGOING != 0) return Promotion.Promoted
        return if (posted.hasPromotableCharacteristics()) Promotion.Requested else Promotion.NotPromotable
    }

    /**
     * Opens the system page where the user allows or stops Herdline's
     * promoted notifications. Where this Android has no such page (API 36.0
     * answers -91, not resolved, without throwing: QA m43 C5), the app's
     * notification settings open instead; where neither opens, a message
     * says so. Never a silent no-op.
     */
    fun openSettings(context: Context) {
        val pages = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) add(android.provider.Settings.ACTION_APP_NOTIFICATION_PROMOTION_SETTINGS)
            add(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        }
        val opened = pages.any { action ->
            val intent = Intent(action).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            intent.resolveActivity(context.packageManager) != null &&
                runCatching { context.startActivity(intent) }.isSuccess
        }
        if (!opened) Toast.makeText(context, R.string.live_promotion_settings_missing, Toast.LENGTH_LONG).show()
    }

    internal fun statusText(context: Context, status: AgentStatus): String = context.getString(
        when (status) {
            AgentStatus.Working -> R.string.status_working
            AgentStatus.Blocked -> R.string.status_blocked
            AgentStatus.Done -> R.string.status_done
            AgentStatus.Idle -> R.string.status_idle
            AgentStatus.Unknown -> R.string.status_unknown
        },
    )

    /** PUBLIC for Full; PRIVATE otherwise, with a publicVersion for the system's own redaction. */
    internal fun visibility(lock: LockDetail): Int =
        if (lock == LockDetail.Full) NotificationCompat.VISIBILITY_PUBLIC else NotificationCompat.VISIBILITY_PRIVATE

    /** The publicVersion carrying [content]. */
    internal fun publicOf(context: Context, channel: String, content: NoteContent, icon: Int = R.drawable.ic_notification): NotificationCompat.Builder =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(icon)
            .setContentTitle(content.title)
            .setContentText(content.text)
            .setSubText(content.subText)

    private fun showing(manager: NotificationManager, tag: String): Boolean =
        manager.activeNotifications.any { it.id == AGENT_ID && it.tag == tag }

    private fun cancel(manager: NotificationManager, key: AlertKey) {
        manager.cancel(key.tag, AGENT_ID)
        forget(key.tag)
    }

    private fun forget(tag: String) {
        notes.remove(tag)
        children.forget(tag)
    }

    private fun cancelSummary(manager: NotificationManager, nodeId: String) {
        manager.cancel(summaryTag(nodeId), SUMMARY_ID)
        summaries.forget(summaryTag(nodeId))
    }

    /**
     * Drops the node's group summary once none of its agent notifications is
     * left (lab.3 finding D13): a summary alone in the shade reads as a
     * notification that goes nowhere. [cancel] forgets a note at once, while
     * the system still lists the cancelled notification for a moment, so only
     * notes still known here count, and of those only the ones still showing
     * (the user may have swiped one away).
     */
    private fun tidy(manager: NotificationManager, nodeId: String) {
        val prefix = AlertKey.prefix(nodeId)
        val left = notes.keys.any { it.startsWith(prefix) && showing(manager, it) }
        if (!left) cancelSummary(manager, nodeId)
    }

    private fun summaryTag(nodeId: String) = "agents\n$nodeId"

    private fun group(nodeId: String) = "agents:$nodeId"

    /**
     * Posts one agent notification and its group summary, with the words
     * [agentContent] and [summaryContent] choose for the lock state now.
     * [LockGate] re-posts both silently on the lock and unlock edges
     * ([republish]), so a notification posted unlocked is redacted when the
     * device locks and full again when it unlocks (D10, D11). Unless
     * [force], either is skipped when its content is the one already posted.
     */
    private fun publish(context: Context, manager: NotificationManager, note: Note, lock: LockDetail, silent: Boolean, force: Boolean) {
        val nodeId = note.key.nodeId
        val attention = note.kind == AlertKind.NeedsInput
        val stale = note.staleSince?.let { context.getString(R.string.live_stale, DateFormat.getTimeFormat(context).format(it)) }
        val locked = LockGate.isDeviceLocked(context)
        val texts = ResourceTexts(context)
        val channel = channelOf(note.kind)
        // The Needs input glyph: the same shape as the in-app Blocked diamond.
        val icon = if (attention) R.drawable.ic_needs_input else R.drawable.ic_notification
        val words = agentContent(texts, attention, note.node, note.agent, note.title, note.body, stale, lock, locked)
        val variant = (context.applicationContext as HerdlineApp).settings.settings.value.iconVariant
        if (children.put(note.key.tag, ChildPost(words, lock, note.at, variant)) || force) {
            val child = NotificationCompat.Builder(context, channel)
                .setSmallIcon(icon)
                .setWhen(note.at)
                .setShowWhen(true)
                .setCategory(if (attention) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
                .setPriority(if (attention) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(open(context, nodeId, note.key.pane, note.key.tag))
                .setAutoCancel(true)
                .setGroup(group(nodeId))
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                // Still a fresh alert while locked: a Done on a locked phone is the main case.
                .setSilent(silent)
                .setContentTitle(words.main.title)
                .setContentText(words.main.text)
                .setSubText(words.main.subText)
                .setVisibility(visibility(lock))
                .setPublicVersion(publicOf(context, channel, words.public, icon).setWhen(note.at).setShowWhen(true).build())
            words.main.big?.let { child.setStyle(NotificationCompat.BigTextStyle().bigText(it)) }
            manager.notify(note.key.tag, AGENT_ID, child.build())
        }

        val sum = summaryContent(texts, note.node, lock, locked)
        if (summaries.put(summaryTag(nodeId), SummaryPost(sum, lock, variant)) || force) {
            val summary = NotificationCompat.Builder(context, CHANNEL_DONE)
                .setSmallIcon(R.drawable.ic_notification)
                .setGroup(group(nodeId))
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentTitle(sum.main.title)
                .setVisibility(visibility(lock))
                .setPublicVersion(publicOf(context, CHANNEL_DONE, sum.public).build())
            manager.notify(summaryTag(nodeId), SUMMARY_ID, summary.build())
        }
    }
}

/**
 * Answers "is the screen locked" for the notification redaction (lab.3
 * finding D10), and drives the re-posts on the lock and unlock edges.
 *
 * Android shows publicVersion only when the system itself redacts, so with
 * the user's "show all notification content" on, VISIBILITY_PRIVATE plus
 * publicVersion leaks everything onto the lock screen. Instead, while the
 * screen is off or the keyguard holds, the permitted content is posted as the
 * main content. This object watches ACTION_SCREEN_OFF, ACTION_SCREEN_ON and
 * ACTION_USER_PRESENT (a runtime receiver, registered on the first locked
 * check), so the redaction is applied when the screen goes off and the full
 * content returns, silently, when the user is back.
 *
 * The screen going off counts as locked: with a lock delay ("lock after N
 * seconds") the keyguard engages later, on an alarm that sends no broadcast,
 * so checking the keyguard on SCREEN_OFF alone left the full content on the
 * lock screen (lab.7.1 S1). Waking inside the delay finds no keyguard on
 * SCREEN_ON, and the full content returns then.
 */
internal object LockGate {
    private var registered: Boolean = false

    /**
     * The user unlocked since the screen last went off. On the
     * ACTION_USER_PRESENT edge the keyguard can still report locked while it
     * animates away, which re-posted the redacted content instead of the full.
     */
    @Volatile
    private var present: Boolean = false

    /**
     * No connection service runs, so nothing keeps this process alive and it
     * may be killed at any moment; after that nothing re-posts a note on the
     * lock edge. Notes left in the shade then keep their locked words even
     * unlocked (lab.7.1 S2).
     */
    @Volatile
    internal var held: Boolean = false

    /** Re-posts the service's live card; set by [ConnectionService.watchCard]. */
    internal var onLockChange: (() -> Unit)? = null

    fun isDeviceLocked(context: Context): Boolean {
        // Registered on the first check, locked or not: content posted while
        // unlocked must be redacted on the next lock edge too.
        if (!registered) register(context.applicationContext)
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        return lockedNow(
            held = held,
            present = present,
            interactive = power?.isInteractive ?: true,
            keyguard = km != null && (km.isKeyguardLocked || km.isDeviceLocked),
        )
    }

    /**
     * Locked for redaction: notes are [held], the screen is off, or the
     * keyguard holds and the user has not unlocked since.
     */
    internal fun lockedNow(held: Boolean, present: Boolean, interactive: Boolean, keyguard: Boolean): Boolean =
        held || !interactive || (!present && keyguard)

    private fun register(context: Context) {
        registered = true
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(receiveContext: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_USER_PRESENT -> present = true
                        Intent.ACTION_SCREEN_OFF -> present = false
                    }
                    val app = receiveContext.applicationContext as? io.github.erikfrish.herdline.HerdlineApp ?: return
                    val lock = app.settings.settings.value.lockDetail
                    AgentNotifications.republish(app, lock)
                    onLockChange?.invoke()
                }
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF).apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            // SystemUI, not the system uid, sends USER_PRESENT, so a
            // not-exported receiver never hears the unlock. All three actions
            // are protected broadcasts that no app can send.
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
