package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.data.TargetOrder
import io.github.erikfrish.herdline.ui.inbox.hostLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** W10152: the node's name leads the header, the To-line and the Tabs group; the workspace path follows, cut at its start. */
class HostPlaceTest {
    private val path = "P1QA/home/erikfrish/.cache/herdline-hc-p1/work"

    private fun pane(host: String, ws: String = path, tab: String = "work", state: String? = "Working") =
        Destination("w1:p1", "t1", tab, ws, number = 1, state = state, host = host)

    private fun shown(d: Destination, maxColumns: Int) = destinationLabel(d, emptyList(), maxColumns).shown

    @Test
    fun the_host_comes_before_the_path_and_the_path_keeps_its_end() {
        assertEquals("work · Working · qa-p1-core · …hc-p1/work", shown(pane("qa-p1-core"), 41))
    }

    @Test
    fun the_whole_line_shows_when_it_fits() {
        assertEquals("work · Working · qa-p1-core · $path", shown(pane("qa-p1-core"), 200))
    }

    @Test
    fun a_path_with_under_eight_columns_left_is_dropped_but_never_the_host() {
        // who 4 + sep 3 + state 7 + sep 3 = 17; host 10 leaves 4 after its separator.
        assertEquals("work · Working · qa-p1-core", shown(pane("qa-p1-core"), 34))
    }

    @Test
    fun an_empty_path_or_one_equal_to_the_host_shows_the_host_alone() {
        assertEquals("work · Working · qa-p1-core", shown(pane("qa-p1-core", ws = ""), 80))
        assertEquals("work · Working · lab", shown(pane("lab", ws = "lab"), 80))
    }

    @Test
    fun the_spoken_place_is_host_then_workspace_uncut() {
        val label = destinationLabel(pane("qa-p1-core"), emptyList(), 20)
        assertEquals("qa-p1-core · $path", label.place)
        assertEquals("qa-p1-core", destinationLabel(pane("qa-p1-core", ws = ""), emptyList(), 20).place)
    }

    @Test
    fun two_nodes_with_one_name_stay_apart_on_the_line() {
        val a = Node(id = "a", name = "lab", address = "10.0.0.1", user = "me")
        val b = Node(id = "b", name = "lab", address = "10.0.0.2", user = "me")
        val labels = hostLabels(listOf(a, b))
        val lineA = shown(pane(labels.getValue("a")), 200)
        val lineB = shown(pane(labels.getValue("b")), 200)
        assertTrue(lineA.contains("lab"))
        assertTrue(lineA != lineB)
        assertEquals(labels.getValue("a"), groupLabel(labels.getValue("a"), path).first)
    }

    @Test
    fun wide_characters_count_twice_in_the_start_cut() {
        // "工作区/项目/前端" is 16 columns; in 6, "…" and 5 columns of its end; a wide character is never split.
        assertEquals("…/前端", startEllipsis("工作区/项目/前端", 6))
        assertEquals("…目/前端", startEllipsis("工作区/项目/前端", 8))
        assertEquals("工作区", startEllipsis("工作区", 6))
    }

    @Test
    fun a_zh_host_is_kept_whole_before_the_path() {
        // The host's 8 columns stay whole; the path gets the 9 columns left after it.
        assertEquals("work · Working · 测试主机 · …-p1/work", shown(pane("测试主机"), 37))
    }

    @Test
    fun the_group_label_never_upper_cases_and_drops_a_blank_or_repeated_workspace() {
        assertEquals("qa-p1-core" to path, groupLabel("qa-p1-core", path))
        assertEquals("qa-p1-core" to null, groupLabel("qa-p1-core", "  "))
        assertEquals("lab" to null, groupLabel("lab", "lab"))
        assertEquals(path to null, groupLabel(null, path))
    }

    @Test
    fun the_filter_line_names_only_what_is_active() {
        val counts = FilterCounts(needsYou = 2, working = 3, ready = 1)
        val none = filterSummary(SwitcherFilter.All, TargetOrder.Workspace, counts)
        assertFalse(none.active)
        assertNull(none.status)
        assertEquals(FilterSummary(SwitcherFilter.NeedsYou, 2, false), filterSummary(SwitcherFilter.NeedsYou, TargetOrder.Workspace, counts))
        assertEquals(FilterSummary(SwitcherFilter.Ready, 1, true), filterSummary(SwitcherFilter.Ready, TargetOrder.LastActivity, counts))
        assertEquals(FilterSummary(null, null, true), filterSummary(SwitcherFilter.All, TargetOrder.LastActivity, counts))
        // Two columns has no order choice: no order is ever named.
        assertFalse(filterSummary(SwitcherFilter.All, null, counts).active)
    }
}
