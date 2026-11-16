package io.github.erikfrish.herdline.service

import android.app.Notification
import android.content.Context
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat

/**
 * Renders the all-agents summary onto a live notification the caller has
 * already set up (intents, small icon, visibility, publicVersion, actions,
 * promotion); none of those are touched here. Every text shown is one the
 * caller passed, and every number is one of [AgentSummary]'s counts.
 *
 * On Android 37 with a live summary it is a [Notification.MetricStyle] of
 * three counts, working, waiting and idle, whose critical metric is the
 * most urgent nonzero one. Otherwise it is a big-text notification carrying
 * [detail]. Neither path colourises, draws RemoteViews, fakes progress or
 * updates on a timer: any motion is the system's own, between updates.
 */
internal object AgentSummaryNotification {
    /** The metrics' order, which [criticalMetric] indexes. */
    internal const val METRIC_WORKING = 0
    internal const val METRIC_WAITING = 1
    internal const val METRIC_IDLE = 2

    /**
     * Fills [builder] with [summary] and returns the notification.
     *
     * - [title] and [detail] are the content title and text, and [detail] the
     *   expanded text where the platform shows one.
     * - [scope] is the subtext: which hosts the counts cover.
     * - [chip] is the status-bar chip, `setShortCriticalText`.
     * - [spoken] is what accessibility services read, `setTicker`; Android
     *   has not drawn ticker text since API 21.
     */
    fun build(
        context: Context,
        builder: NotificationCompat.Builder,
        summary: AgentSummary,
        title: String,
        scope: String,
        workingLabel: String,
        waitingLabel: String,
        idleLabel: String,
        detail: String,
        chip: String,
        spoken: String,
    ): Notification {
        builder
            .setContentTitle(title)
            .setContentText(detail)
            .setSubText(scope)
            .setShortCriticalText(chip)
            .setTicker(spoken)
            .setColor(accent(criticalMetric(summary)))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN && summary.available) {
            return metrics(context, builder, summary, workingLabel, waitingLabel, idleLabel)
        }
        return builder.setStyle(NotificationCompat.BigTextStyle().bigText(detail)).build()
    }

    /** The metric the Now Bar leads with: waiting if any, else working if any, else idle. */
    internal fun criticalMetric(summary: AgentSummary): Int = when {
        summary.waiting > 0 -> METRIC_WAITING
        summary.working > 0 -> METRIC_WORKING
        else -> METRIC_IDLE
    }

    /**
     * The compat builder has no MetricStyle, so its notification is turned
     * back into a platform builder, which keeps every field set before, and
     * only the style is added.
     */
    @RequiresApi(Build.VERSION_CODES.CINNAMON_BUN)
    private fun metrics(
        context: Context,
        builder: NotificationCompat.Builder,
        summary: AgentSummary,
        workingLabel: String,
        waitingLabel: String,
        idleLabel: String,
    ): Notification {
        val metrics = listOf(
            Notification.Metric(Notification.Metric.FixedInt(summary.working), workingLabel, Notification.SEMANTIC_STYLE_INFO),
            Notification.Metric(Notification.Metric.FixedInt(summary.waiting), waitingLabel, Notification.SEMANTIC_STYLE_CAUTION),
            Notification.Metric(Notification.Metric.FixedInt(summary.idle), idleLabel, Notification.SEMANTIC_STYLE_SAFE),
        )
        val style = Notification.MetricStyle()
            .setMetrics(metrics)
            .setCriticalMetric(criticalMetric(summary))
        return Notification.Builder.recoverBuilder(context, builder.build())
            .setStyle(style)
            .build()
    }

    /**
     * The plain accent of the leading state, never colourised: the app's
     * status colours (`AppPalettes.HerdlineStatus`), which are private to
     * the theme.
     */
    @ColorInt
    private fun accent(metric: Int): Int = when (metric) {
        METRIC_WAITING -> 0xFFF2A93B.toInt()
        METRIC_WORKING -> 0xFF4C8DF6.toInt()
        else -> 0xFF8A8F98.toInt()
    }
}
