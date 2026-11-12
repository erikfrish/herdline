package io.github.erikfrish.herdline.ui.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The worked examples of night-u1-specs.md §B, names from the QA B fixture. */
class DestinationTest {
    private val workspace = "qa-visual-long-workspace-name-for-wrapping-and-ellipsis"
    private val longTab = "qa-visual-a-very-long-tab-name-that-should-ellipsize-cleanly"

    private fun pane(
        n: Int,
        tab: String,
        tabId: String = "t$n",
        state: String? = null,
        label: String? = null,
        program: String? = null,
        ws: String = workspace,
    ) = Destination("w1:p$n", tabId, tab, ws, label, program, n, state = state)

    private fun shown(d: Destination, maxColumns: Int, vararg siblings: Destination) =
        destinationLabel(d, siblings.toList(), maxColumns).shown

    @Test
    fun a_long_tab_takes_the_room_and_the_workspace_is_dropped_first() {
        val line = shown(pane(2, longTab, state = "Needs input"), 41, pane(1, "work"))
        assertEquals("qa-visual-a-v…psize-cleanly · Needs input", line)
        assertEquals(41, columns(line))
    }

    @Test
    fun a_whole_tab_with_under_eight_columns_left_drops_the_workspace() {
        assertEquals("work · Working", shown(pane(1, "work", state = "Working"), 24, pane(2, longTab)))
    }

    @Test
    fun a_whole_tab_with_eight_columns_left_shows_the_workspace_cut_in_the_middle() {
        val line = shown(pane(1, "work", state = "Working"), 25, pane(2, longTab))
        assertEquals("work · Working · qa-v…sis", line)
        assertEquals(25, columns(line))
    }

    @Test
    fun a_short_tab_leaves_its_room_to_the_workspace() {
        val line = shown(pane(1, "work", state = "Working"), 41, pane(2, longTab))
        assertEquals("work · Working · qa-visual-lo…nd-ellipsis", line)
        assertEquals(41, columns(line))
    }

    @Test
    fun a_tab_cut_below_twelve_columns_drops_the_workspace_instead() {
        val line = shown(pane(2, longTab, state = "Needs input"), 31, pane(1, "work"))
        assertEquals("qa-visua…-cleanly · Needs input", line)
        assertEquals(31, columns(line))
    }

    @Test
    fun a_line_that_fits_is_left_as_it_is() {
        assertEquals("alice · Idle · default", shown(pane(1, "alice", state = "Idle", ws = "default"), 41))
    }

    @Test
    fun the_spoken_description_is_never_cut() {
        val label = destinationLabel(pane(2, longTab, state = "Needs input").copy(title = "fix the login"), emptyList(), 20)
        assertEquals("$longTab · fix the login", label.who)
        assertEquals(workspace, label.workspace)
        assertEquals("Needs input", label.state)
    }

    @Test
    fun panes_of_one_tab_are_told_apart_by_label_then_program_then_number() {
        val build = pane(1, "work", tabId = "t1", label = "build")
        val agent = pane(2, "work", tabId = "t1", program = "claude")
        val twin = pane(3, "work", tabId = "t1", program = "claude")
        val lines = destinationLabels(listOf(build, agent, twin), 80).mapValues { it.value.shown }
        assertEquals("work › build · $workspace", lines.getValue(build.pane))
        assertEquals("work › pane 2 · $workspace", lines.getValue(agent.pane))
        assertEquals("work › pane 3 · $workspace", lines.getValue(twin.pane))
    }

    @Test
    fun no_two_panes_of_a_workspace_ever_read_alike_at_any_width() {
        val five = listOf(
            pane(1, "qa-visual-a-very-long-tab-name-one"),
            pane(2, "qa-visual-a-very-long-tab-name-two"),
            pane(3, "qa-visual-a-very-long-middle-x-tab-end"),
            pane(4, "qa-visual-a-very-long-middle-y-tab-end", tabId = "t4"),
            pane(5, "qa-visual-a-very-long-middle-y-tab-end", tabId = "t4", program = "omp"),
        )
        for (width in 4..90) {
            val who = destinationLabels(five, width).values.map { it.shown.substringBefore(DESTINATION_SEPARATOR) }
            assertEquals("width $width: $who", who.size, who.toSet().size)
        }
    }

    @Test
    fun wide_characters_count_twice_and_are_never_split() {
        val line = shown(pane(1, "测试", ws = "工作区名称很长很长"), 20)
        assertEquals("测试 · 工作区…长很长", line)
        assertEquals(20, columns(line))
        assertEquals("数据…队列", middleEllipsis("数据库迁移任务队列", 9))
        val coder = "\uD83D\uDC69\u200D\uD83D\uDCBB" // woman technologist, one ZWJ sequence
        assertEquals(2, columns(coder))
        val emoji = middleEllipsis("$coder-release-notes-$coder", 8)
        assertTrue(emoji, emoji.startsWith(coder) && emoji.endsWith(coder) && columns(emoji) <= 8)
    }
}
