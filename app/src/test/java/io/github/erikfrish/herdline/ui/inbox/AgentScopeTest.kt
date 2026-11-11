package io.github.erikfrish.herdline.ui.inbox

import io.github.erikfrish.herdline.core.Agent
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Pane
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.data.Node
import io.github.erikfrish.herdline.ui.session.SwitcherFilter
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentScopeTest {
    private val labA = Node(id = "a", name = "lab", address = "192.0.2.145", user = "alice")
    private val labB = Node(id = "b", name = "lab", address = "198.51.100.4", port = 2222, user = "bob", session = "work")
    private val talos = Node(id = "t", name = "talos2", address = "192.0.2.2", user = "alice")

    private fun workspace(id: String, number: Int, label: String) =
        Workspace(id, number, label, null, null, null, null, false, "", false, AgentStatus.Idle)

    /** One workspace per entry, one tab and one agent pane per status. */
    private fun view(vararg workspaces: Pair<Workspace, List<AgentStatus>>): ShellView {
        val tabs = mutableListOf<Tab>()
        val panes = mutableListOf<Pane>()
        val agents = mutableListOf<Agent>()
        for ((ws, statuses) in workspaces) {
            statuses.forEachIndexed { i, status ->
                val tab = "${ws.id}-t$i"
                val pane = "${ws.id}-p$i"
                tabs += Tab(tab, ws.id, i + 1, "$i", false, false, status)
                panes += Pane(pane, ws.id, tab, null, null, false)
                agents += Agent(pane, ws.id, tab, null, "omp", null, status, false)
            }
        }
        return ShellView(1, "boot", null, null, null, null, workspaces.map { it.first }, tabs, panes, agents)
    }

    private val defaultA = workspace("w1", 1, "default")
    private val defaultT = workspace("w9", 1, "default")
    private val herdline = workspace("w2", 2, "herdline")

    @Test
    fun hostsSharingATitleAreQualifiedAndAnUnsharedOneIsNot() {
        val labels = hostLabels(listOf(labA, labB, talos))
        assertEquals("lab · alice@192.0.2.145", labels["a"])
        assertEquals("lab · bob@198.51.100.4:2222 · work", labels["b"])
        assertEquals("talos2", labels["t"])
    }

    @Test
    fun twoSavedCopiesOfOneServerStillReadApart() {
        val copy = labA.copy(id = "a2")
        val labels = hostLabels(listOf(labA, copy, labB))
        assertEquals("lab · alice@192.0.2.145", labels["a"])
        assertEquals("lab · alice@192.0.2.145 · 2", labels["a2"])
        assertEquals("lab · bob@198.51.100.4:2222 · work", labels["b"])
    }

    @Test
    fun aLabelOnTwoHostsNamesItsHostOnceChosen() {
        val hosts = listOf(
            HostSnapshot(talos, view(defaultT to listOf(AgentStatus.Blocked))),
            HostSnapshot(labA, view(herdline to listOf(AgentStatus.Working))),
            HostSnapshot(labB, view(defaultA to listOf(AgentStatus.Idle))),
        )
        val groups = scopeGroups(hosts, agentRows(hosts))
        val byKey = groups.flatMap { it.items }.associateBy { it.key.nodeId }
        // "default" is on talos2 and on a lab: both name their host.
        assertEquals("talos2", byKey.getValue("t").onHost)
        // A qualified host follows the label in brackets, never as a third `·` field.
        assertEquals("lab (bob@198.51.100.4:2222 · work)", byKey.getValue("b").onHost)
        // "herdline" is unique, but its header is qualified, so it still says which lab.
        assertEquals("lab (alice@192.0.2.145)", byKey.getValue("a").onHost)
    }

    @Test
    fun aUniqueLabelUnderAPlainHeaderStandsAlone() {
        val hosts = listOf(
            HostSnapshot(talos, view(defaultT to listOf(AgentStatus.Blocked))),
            HostSnapshot(labA, view(herdline to listOf(AgentStatus.Working))),
        )
        val items = scopeGroups(hosts, agentRows(hosts)).flatMap { it.items }
        assertEquals(listOf(null, null), items.map { it.onHost })
    }

    @Test
    fun aChosenWorkspaceCountsOnlyItsAgentsWhileTheBadgeCountsEveryHost() {
        val hosts = listOf(
            HostSnapshot(talos, view(defaultT to listOf(AgentStatus.Blocked, AgentStatus.Working))),
            HostSnapshot(labA, view(defaultA to listOf(AgentStatus.Blocked, AgentStatus.Blocked), herdline to listOf(AgentStatus.Working))),
        )
        val rows = agentRows(hosts)
        val chosen = ScopeKey("t", "default", "w9")
        val state = scopeState(chosen, hosts, scopeGroups(hosts, rows, chosen))
        val scoped = rows.inScope(state)
        assertEquals(listOf("w9-p0", "w9-p1"), scoped.map { it.paneId })
        assertEquals(1, scoped.needsYou())
        assertEquals(1, scoped.working())
        assertEquals(listOf("w9-p0"), scoped.withStatus(SwitcherFilter.NeedsYou).map { it.paneId })
        assertEquals(3, rows.needsYou())
    }

    @Test
    fun readyIsIdleOrDoneAndCountsWhatItLists() {
        val hosts = listOf(
            HostSnapshot(talos, view(defaultT to listOf(AgentStatus.Idle, AgentStatus.Done, AgentStatus.Working, AgentStatus.Blocked))),
        )
        val rows = agentRows(hosts)
        assertEquals(2, rows.ready())
        assertEquals(listOf("w9-p0", "w9-p1"), rows.withStatus(SwitcherFilter.Ready).map { it.paneId })
    }

    @Test
    fun aNewViewOnTheChosenHostUpdatesItsCounts() {
        val chosen = ScopeKey("t", "default", "w9")
        fun counts(statuses: List<AgentStatus>): Pair<Int, Int> {
            val hosts = listOf(HostSnapshot(talos, view(defaultT to statuses)))
            val rows = agentRows(hosts)
            val scoped = rows.inScope(scopeState(chosen, hosts, scopeGroups(hosts, rows, chosen)))
            return scoped.needsYou() to scoped.working()
        }
        assertEquals(0 to 1, counts(listOf(AgentStatus.Working)))
        assertEquals(1 to 0, counts(listOf(AgentStatus.Blocked)))
    }

    @Test
    fun aClosedWorkspaceOrADetachedHostFallsBackToAll() {
        val chosen = ScopeKey("t", "default", "w9")
        val closed = listOf(HostSnapshot(talos, view(herdline to listOf(AgentStatus.Working))))
        assertEquals(ScopeState.All, scopeState(chosen, closed, scopeGroups(closed, agentRows(closed), chosen)))
        val detached = listOf(HostSnapshot(labA, view(defaultA to listOf(AgentStatus.Working))))
        assertEquals(ScopeState.All, scopeState(chosen, detached, scopeGroups(detached, agentRows(detached), chosen)))
        // The same id on a host now attached to another session is a different scope.
        val moved = listOf(HostSnapshot(talos.copy(session = "other"), view(defaultT to listOf(AgentStatus.Working))))
        assertEquals(ScopeState.All, scopeState(chosen, moved, scopeGroups(moved, agentRows(moved), chosen)))
    }

    @Test
    fun aHostStillLoadingKeepsTheChoiceButShowsEveryRow() {
        val chosen = ScopeKey("t", "default", "w9")
        val hosts = listOf(
            HostSnapshot(talos, null),
            HostSnapshot(labA, view(defaultA to listOf(AgentStatus.Working))),
        )
        val rows = agentRows(hosts)
        val state = scopeState(chosen, hosts, scopeGroups(hosts, rows, chosen))
        assertEquals(ScopeState.Pending, state)
        assertEquals(rows, rows.inScope(state))
    }

    @Test
    fun aChosenWorkspaceWhoseLastAgentLeftStaysChosenAndListed() {
        val chosen = ScopeKey("t", "default", "w9")
        val idle = workspace("w9", 1, "default")
        val hosts = listOf(HostSnapshot(talos, view(idle to emptyList(), herdline to listOf(AgentStatus.Working))))
        val rows = agentRows(hosts)
        val groups = scopeGroups(hosts, rows, chosen)
        val state = scopeState(chosen, hosts, groups)
        assertEquals(chosen, (state as ScopeState.Chosen).item.key)
        assertEquals(emptyList<InboxRow>(), rows.inScope(state))
        // An unchosen workspace without agents is not offered.
        assertEquals(listOf("w9", "w2"), scopeGroups(hosts, rows, chosen).single().items.map { it.key.workspaceId })
        assertEquals(listOf("w2"), scopeGroups(hosts, rows).single().items.map { it.key.workspaceId })
    }

    @Test
    fun membershipComesFromHerdrIdsNotLabels() {
        // Two workspaces on one host share the label; each keeps its own agents.
        val first = workspace("w1", 1, "herdline")
        val fourth = workspace("w4", 4, "herdline")
        val hosts = listOf(HostSnapshot(talos, view(first to listOf(AgentStatus.Working), fourth to listOf(AgentStatus.Blocked, AgentStatus.Idle))))
        val items = scopeGroups(hosts, agentRows(hosts)).single().items
        assertEquals(listOf(1 to 1, 4 to 2), items.map { it.number to it.agents })
    }
}
