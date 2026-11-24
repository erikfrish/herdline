package io.github.erikfrish.herdline.ui.inbox

import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.ui.session.SwitcherFilter
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every row a status chip counts shows a word the chip's label names, in
 * every language: a Done agent is never counted under a chip that does not
 * say Done. Rows and notifications both use status_*.
 */
class StateWordsTest {
    private val rowWord = mapOf(
        AgentStatus.Blocked to "status_blocked",
        AgentStatus.Working to "status_working",
        AgentStatus.Done to "status_done",
        AgentStatus.Idle to "status_idle",
    )

    /** The chip labels, with the count, and the start filter's, without. */
    private val chip = mapOf(
        SwitcherFilter.NeedsYou to listOf("filter_needs_you", "start_filter_needs_you"),
        SwitcherFilter.Working to listOf("filter_working", "start_filter_working"),
        SwitcherFilter.Ready to listOf("filter_ready", "start_filter_ready"),
    )

    private fun strings(folder: String): Map<String, String> {
        val dir = File("src/main/res/$folder")
        return dir.listFiles { file -> file.extension == "xml" }.orEmpty().flatMap { file ->
            val nodes = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getElementsByTagName("string")
            List(nodes.length) { nodes.item(it) as Element }.map { it.getAttribute("name") to it.textContent }
        }.toMap()
    }

    private fun row(status: AgentStatus) = InboxRow("n", "host", "p", "t", "w", status, ScopeKey("n", "default", "w"))

    @Test
    fun eachChipNamesEveryRowWordItCounts() {
        val rows = AgentStatus.entries.map(::row)
        for (folder in listOf("values", "values-zh")) {
            val text = strings(folder)
            for ((filter, labels) in chip) {
                val counted = rows.withStatus(filter).map { it.status }
                assertTrue("$filter counts nothing", counted.isNotEmpty())
                for (label in labels) {
                    val shown = text.getValue(label).lowercase()
                    for (status in counted) {
                        val word = text.getValue(rowWord.getValue(status)).lowercase()
                        assertTrue("$folder $label \"$shown\" does not name \"$word\"", word in shown)
                    }
                }
            }
        }
    }

    @Test
    fun theChipsCountWhatTheyDidBefore() {
        val rows = AgentStatus.entries.map(::row)
        assertEquals(listOf(AgentStatus.Blocked), rows.withStatus(SwitcherFilter.NeedsYou).map { it.status })
        assertEquals(listOf(AgentStatus.Working), rows.withStatus(SwitcherFilter.Working).map { it.status })
        assertEquals(setOf(AgentStatus.Idle, AgentStatus.Done), rows.withStatus(SwitcherFilter.Ready).map { it.status }.toSet())
    }

    @Test
    fun theSpokenBlockedWordContainsTheVisibleOne() {
        for (folder in listOf("values", "values-zh")) {
            val text = strings(folder)
            assertTrue(folder, text.getValue("status_blocked").lowercase() in text.getValue("status_blocked_spoken").lowercase())
        }
    }
}
