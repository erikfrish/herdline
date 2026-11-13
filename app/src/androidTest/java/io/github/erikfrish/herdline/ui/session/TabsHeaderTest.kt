package io.github.erikfrish.herdline.ui.session

import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTouchHeightIsEqualTo
import androidx.compose.ui.test.assertTouchWidthIsEqualTo
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AgentStatus
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Tab
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/**
 * The phone Tabs header at font 2.0 on 308 and 320 dp screens, in English and
 * Chinese, with 10 and 100 tabs: the host keeps at least a third of the line
 * less its 8 dp gap, the counts stay within two thirds and two lines and show
 * whole, every action is a 48 dp target with its full label, and New tab
 * opens for this host's chosen workspace only. Two headers (two hosts) share
 * the screen so a tap on one is seen not to reach the other.
 */
class TabsHeaderTest {
    @get:Rule
    val compose = createComposeRule()

    private val base: Context = InstrumentationRegistry.getInstrumentation().targetContext

    private var width by mutableIntStateOf(308)
    private var locale by mutableStateOf(Locale.ENGLISH)
    private var tabCount by mutableIntStateOf(10)

    private val hostA = "erikfrish-workstation.lan"
    private val hostB = "lab-mini"
    private val workspacesA = listOf(
        workspace("a1", 1, "Herdline Dev"),
        workspace("a2", 2, "Bellterm release"),
        workspace("a3", 3, "Notes"),
    )
    private val workspaceB = workspace("b1", 1, "Lab")

    @Test
    fun hostCountsAndActionsKeepTheirRoomAndNewTabStaysOnItsHost() {
        val openedA = mutableListOf<Workspace>()
        val openedB = mutableListOf<Workspace>()
        var toggled = 0
        var closed = 0
        compose.setContent {
            val config = remember(locale) {
                Configuration(base.resources.configuration).apply {
                    setLocale(this@TabsHeaderTest.locale)
                    fontScale = FONT_SCALE
                }
            }
            val localized = remember(config) { base.createConfigurationContext(config) }
            val density = LocalDensity.current.density
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides config,
                LocalResources provides localized.resources,
                LocalDensity provides Density(density, FONT_SCALE),
            ) {
                HerdlineTheme(dark = false) {
                    Box(Modifier.width(width.dp)) {
                        Column {
                            val toggleLabel = localized.getString(R.string.overview_as_list)
                            TabsHeader(
                                host = hostA, view = viewA(tabCount), state = LIVE, chosen = "a2", gone = false,
                                newTab = { openedA += it },
                                toggle = TabsViewToggle(Icons.Default.Add, toggleLabel, "a:toggle") { toggled++ },
                                onClose = { closed++ }, tag = "a",
                            )
                            TabsHeader(
                                host = hostB, view = viewB(), state = LIVE, chosen = null, gone = false,
                                newTab = { openedB += it },
                                toggle = TabsViewToggle(Icons.Default.Add, toggleLabel, "b:toggle") { toggled++ },
                                onClose = { closed++ }, tag = "b",
                            )
                        }
                    }
                }
            }
        }
        for (widthNow in listOf(308, 320)) {
            for (localeNow in listOf(Locale.ENGLISH, Locale.SIMPLIFIED_CHINESE)) {
                for (tabsNow in listOf(10, 100)) {
                    width = widthNow
                    locale = localeNow
                    tabCount = tabsNow
                    compose.waitForIdle()
                    val case = "$widthNow dp, ${localeNow.toLanguageTag()}, $tabsNow tabs"
                    val res = base.createConfigurationContext(
                        Configuration(base.resources.configuration).apply { setLocale(localeNow) },
                    ).resources
                    val pixel = 1f / compose.density.density

                    val header = bounds(compose.onNodeWithTag("a:header", useUnmergedTree = true))
                    assertEquals("$case: header width", widthNow.toFloat(), header.right.value - header.left.value, pixel)

                    // The host: at least a third of the line less its 8 dp gap, never 0 or a sliver.
                    val host = bounds(inHeaderA(hostA))
                    val hostWidth = host.right.value - host.left.value
                    assertTrue("$case: host $hostWidth dp", hostWidth >= widthNow / 3f - 8f - pixel)

                    // The counts: within two thirds of the line, at most two lines, every glyph shown.
                    val countsText = res.getString(
                        R.string.overview_counts,
                        res.getQuantityString(R.plurals.overview_tabs, tabsNow, tabsNow),
                        res.getQuantityString(R.plurals.overview_workspaces, workspacesA.size, workspacesA.size),
                    )
                    val countsNode = inHeaderA(countsText)
                    val counts = bounds(countsNode)
                    val countsWidth = counts.right.value - counts.left.value
                    assertTrue("$case: counts $countsWidth dp", countsWidth <= widthNow * 2f / 3f + pixel)
                    assertTrue("$case: counts and host overlap", counts.left.value >= host.right.value - pixel)
                    val layout = textLayout(countsNode)
                    assertTrue("$case: counts in ${layout.lineCount} lines", layout.lineCount <= 2)
                    assertTrue("$case: counts cut", !layout.hasVisualOverflow)

                    // Every action a 48 dp target inside the line, with its full label.
                    val newTabLabel = res.getString(R.string.new_tab_in, workspacesA[1].label)
                    assertNull("$case: workspace labels are unique", workspaceSuffixes(workspacesA)[workspacesA[1].id])
                    val actions = listOf(
                        "a:new-tab" to newTabLabel,
                        "a:toggle" to res.getString(R.string.overview_as_list),
                        "a:close" to res.getString(R.string.close),
                    )
                    for ((tag, label) in actions) {
                        // Touch bounds include Material's minimum interactive size, not just its 40 dp state layer.
                        val node = compose.onNodeWithTag(tag)
                        val box = bounds(compose.onNodeWithTag(tag, useUnmergedTree = true))
                        node.assertTouchWidthIsEqualTo(48.dp)
                        node.assertTouchHeightIsEqualTo(48.dp)
                        assertTrue("$case: $tag past the edge", box.right.value <= widthNow + pixel && box.left.value >= -pixel)
                        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf(label)))
                    }

                    // New tab: this host's chosen workspace, once; the other host and the other actions untouched.
                    val before = openedA.size
                    compose.onNodeWithTag("a:new-tab").performClick()
                    compose.waitForIdle()
                    assertEquals("$case: New tab calls", before + 1, openedA.size)
                    assertEquals("$case: New tab workspace", workspacesA[1], openedA.last())
                    assertTrue("$case: other host reached", openedB.isEmpty())
                    assertEquals("$case: toggle reached", 0, toggled)
                    assertEquals("$case: close reached", 0, closed)
                }
            }
        }
    }

    private fun inHeaderA(text: String): SemanticsNodeInteraction =
        compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag("a:header")), useUnmergedTree = true)

    private fun bounds(node: SemanticsNodeInteraction): DpRect = node.getUnclippedBoundsInRoot()

    private fun textLayout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        return results.single()
    }

    private fun viewA(tabs: Int) = ShellView(
        revision = tabs.toLong(), boot = "a", focusedWorkspace = "a1", focusedTab = "a1t1", focusedPane = null, updateAvailable = null,
        workspaces = workspacesA,
        tabs = (1..tabs).map { n ->
            val ws = workspacesA[(n - 1) % workspacesA.size]
            Tab("${ws.id}t$n", ws.id, n, "Tab $n", zoomed = false, focused = n == 1, status = AgentStatus.Idle)
        },
        panes = emptyList(),
        agents = emptyList(),
    )

    private fun viewB() = ShellView(
        revision = 1, boot = "b", focusedWorkspace = "b1", focusedTab = "b1t1", focusedPane = null, updateAvailable = null,
        workspaces = listOf(workspaceB),
        tabs = listOf(Tab("b1t1", "b1", 1, "Shell", zoomed = false, focused = true, status = AgentStatus.Idle)),
        panes = emptyList(),
        agents = emptyList(),
    )

    private companion object {
        const val FONT_SCALE = 2f
        val LIVE = Connection.State.Connected("0.9.5")

        fun workspace(id: String, number: Int, label: String) =
            Workspace(id, number, label, null, null, null, null, false, "${id}t1", false, AgentStatus.Idle)
    }
}
