package io.github.erikfrish.herdline.service

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Pins kept by the real Android storage: a new [AgentMonitor] over the same
 * storage reads every pin back; unpinning one host's pane leaves another
 * host's pane of the same ID pinned; forgetting a host drops only its pins.
 * Every preferences file the monitor opens is renamed to this test's own and
 * deleted afterwards, so the app's real pins are never read or written.
 */
class AgentMonitorPersistenceTest {
    private val base: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefix = "agent-monitor-test-${UUID.randomUUID()}-"
    private val opened = mutableSetOf<String>()
    private val storage = object : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val own = prefix + name
            synchronized(opened) { opened += own }
            return base.getSharedPreferences(own, mode)
        }
    }

    @After
    fun deleteOwnStorage() {
        for (name in synchronized(opened) { opened.toList() }) {
            base.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            base.deleteSharedPreferences(name)
        }
    }

    @Test
    fun pinsSurviveRecreationAndUnpinningOneHostLeavesTheOtherPinned() {
        val lab = MonitorKey("lab", "w1:p1")
        val labOther = MonitorKey("lab", "w2:p3")
        val mini = MonitorKey("mini", "w1:p1")

        val first = AgentMonitor(storage)
        assertTrue("its own storage starts empty", first.monitored.value.isEmpty())
        assertTrue("the monitor opened no storage of its own", opened.isNotEmpty())
        first.pin(lab.nodeId, lab.pane)
        first.pin(mini.nodeId, mini.pane)
        first.pin(labOther.nodeId, labOther.pane)

        val recreated = AgentMonitor(storage)
        assertEquals(setOf(lab, mini, labOther), recreated.monitored.value)
        assertTrue(recreated.isMonitored(mini.nodeId, mini.pane))

        recreated.unpin(listOf(lab))
        val afterUnpin = AgentMonitor(storage)
        assertEquals(setOf(mini, labOther), afterUnpin.monitored.value)
        assertFalse(afterUnpin.isMonitored(lab.nodeId, lab.pane))
        assertTrue("the other host's same pane ID", afterUnpin.isMonitored(mini.nodeId, mini.pane))

        afterUnpin.forget(lab.nodeId)
        assertEquals(setOf(mini), AgentMonitor(storage).monitored.value)
    }
}
