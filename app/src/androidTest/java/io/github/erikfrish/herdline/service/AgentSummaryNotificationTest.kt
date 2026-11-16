package io.github.erikfrish.herdline.service

import android.app.Notification
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.test.platform.app.InstrumentationRegistry
import io.github.erikfrish.herdline.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The summary notification as Android builds it: on 37 three count metrics
 * with their labels and semantics, led by the most urgent nonzero count;
 * elsewhere, or with no host connected, big text. On both, the caller's
 * scope, chip and spoken text land in their slots, and the caller's
 * counts-only publicVersion, visibility and promotion request survive.
 */
class AgentSummaryNotificationTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun onApi37TheCountsAreThreeMetricsWithTheirLabelsAndSemantics() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN)
        val built = render(AgentSummary(working = 2, waiting = 1, idle = 4, done = 1, unknown = 0, connected = 2, unavailable = 1))
        val style = metricStyle(built)
        val metrics = style.metrics
        assertEquals(listOf(2, 1, 4), metrics.map { (it.value as Notification.Metric.FixedInt).value })
        assertEquals(listOf(WORKING, WAITING, IDLE), metrics.map { it.label.toString() })
        assertEquals(
            listOf(Notification.SEMANTIC_STYLE_INFO, Notification.SEMANTIC_STYLE_CAUTION, Notification.SEMANTIC_STYLE_SAFE),
            metrics.map { it.semanticStyle },
        )
        assertEquals(metrics[1], style.criticalMetric)
        assertCallerTexts(built)
    }

    @Test
    fun onApi37TheCriticalMetricIsWaitingThenWorkingThenIdle() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN)
        fun critical(working: Int, waiting: Int, idle: Int): CharSequence {
            val style = metricStyle(render(AgentSummary(working, waiting, idle, done = 0, unknown = 0, connected = 1, unavailable = 0)))
            return requireNotNull(style.criticalMetric).label
        }
        assertEquals(WAITING, critical(working = 3, waiting = 1, idle = 2))
        assertEquals(WORKING, critical(working = 3, waiting = 0, idle = 2))
        assertEquals(IDLE, critical(working = 0, waiting = 0, idle = 2))
        assertEquals(IDLE, critical(working = 0, waiting = 0, idle = 0))
    }

    @Test
    fun withNoHostConnectedItIsBigTextWithTheDetailAndAPlainAccent() {
        val built = render(AgentSummary(working = 0, waiting = 0, idle = 0, done = 0, unknown = 0, connected = 0, unavailable = 2))
        assertEquals(Notification.BigTextStyle::class.java.name, built.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(DETAIL, built.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
        assertNotEquals(Notification.COLOR_DEFAULT, built.color)
        assertFalse(built.extras.getBoolean(Notification.EXTRA_COLORIZED))
        assertCallerTexts(built)
    }

    @Test
    fun belowApi37ALiveSummaryIsBigTextToo() {
        assumeTrue(Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN)
        val built = render(AgentSummary(working = 1, waiting = 0, idle = 0, done = 0, unknown = 0, connected = 1, unavailable = 0))
        assertEquals(Notification.BigTextStyle::class.java.name, built.extras.getString(Notification.EXTRA_TEMPLATE))
        assertEquals(DETAIL, built.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString())
    }

    /** A builder set up as the caller does: private, a counts-only publicVersion, promotion asked. */
    private fun render(summary: AgentSummary): Notification {
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(PUBLIC_TITLE)
            .setContentText(PUBLIC_TEXT)
            .build()
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setRequestPromotedOngoing(true)
        return AgentSummaryNotification.build(
            context, builder, summary, TITLE, SCOPE, WORKING, WAITING, IDLE, DETAIL, CHIP, SPOKEN,
        )
    }

    private fun metricStyle(built: Notification): Notification.MetricStyle {
        val style = Notification.Builder.recoverBuilder(context, built).style
        assertTrue("style is $style", style is Notification.MetricStyle)
        return style as Notification.MetricStyle
    }

    private fun assertCallerTexts(built: Notification) {
        assertEquals(TITLE, built.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(DETAIL, built.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        assertEquals(SCOPE, built.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString())
        assertEquals(SPOKEN, built.tickerText?.toString())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            assertEquals(CHIP, built.shortCriticalText)
            assertTrue(built.extras.getBoolean(Notification.EXTRA_REQUEST_PROMOTED_ONGOING))
        }
        assertEquals(Notification.VISIBILITY_PRIVATE, built.visibility)
        // The lock screen: the caller's counts only, none of the main face's words.
        val public = built.publicVersion
        assertEquals(PUBLIC_TITLE, public?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertEquals(PUBLIC_TEXT, public?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString())
        val shown = public?.extras?.keySet().orEmpty().joinToString(" ") { public?.extras?.get(it).toString() }
        for (secret in listOf(TITLE, DETAIL, SCOPE, SPOKEN)) assertFalse("$secret on the lock screen", secret in shown)
    }

    private companion object {
        const val CHANNEL = "agent-summary-test"
        const val TITLE = "api-agent on lab-mini needs you"
        const val SCOPE = "lab-mini, workstation"
        const val WORKING = "Working"
        const val WAITING = "Waiting"
        const val IDLE = "Idle"
        const val DETAIL = "1 done, 1 host unavailable: counts may be stale"
        const val CHIP = "1 waiting"
        const val SPOKEN = "1 agent waiting, 2 working, 4 idle"
        const val PUBLIC_TITLE = "Herdline"
        const val PUBLIC_TEXT = "3 agents active"
    }
}
