package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.ShellView
import io.github.erikfrish.herdline.core.Workspace
import io.github.erikfrish.herdline.ui.components.SheetCloseButton

/** The phone Tabs sheet's side inset: every view lays its header, and the List its rows, this far from the sheet edges. */
internal val TABS_SHEET_INSET = 12.dp

/** The phone Tabs sheet's switch to the other view: only its icon, words and target differ between views. */
internal class TabsViewToggle(val icon: ImageVector, val label: String, val tag: String, val onClick: () -> Unit)

/** The phone Tabs sheet's search pill height in every view: 48 dp, growing with the font (TB1 §4). */
@Composable
internal fun tabsSearchMinHeight(): Dp = maxOf(48f, 16f * LocalDensity.current.fontScale + 24f).dp

/**
 * The one phone Tabs sheet header (H1), the same in List, Two columns and
 * Rails: "Tabs" with the host on one line under it, then its tab and
 * workspace totals (or the connection state while there is no live view);
 * the host gives way first, the totals wrap before they truncate (H1-O1). Then the view toggle
 * and Close; under that row, always, the labelled New tab, right-aligned,
 * before Search. No width decides where anything goes. The caller lays it
 * [TABS_SHEET_INSET] from both sheet edges. It reads [view] only; nothing
 * here reaches the host until New tab's dialog creates a tab. [tag] keeps
 * each view's existing test tags (switcher:… / overview:…) for device QA.
 * The tablet and sidebar list keep [HostLine].
 */
@Composable
internal fun TabsHeader(
    host: String,
    view: ShellView?,
    state: Connection.State,
    chosen: String?,
    gone: Boolean,
    newTab: (Workspace) -> Unit,
    toggle: TabsViewToggle,
    onClose: () -> Unit,
    tag: String,
) {
    val live = state is Connection.State.Connected
    val stateLine = if (live && view != null) {
        stringResource(
            R.string.overview_counts,
            pluralStringResource(R.plurals.overview_tabs, view.tabs.size, view.tabs.size),
            pluralStringResource(R.plurals.overview_workspaces, view.workspaces.size, view.workspaces.size),
        )
    } else {
        when (state) {
            is Connection.State.Connecting -> stringResource(R.string.connecting)
            is Connection.State.SettingUp -> stringResource(R.string.herdr_setup_pill)
            // Live, before the first view arrives.
            is Connection.State.Connected -> stringResource(R.string.connected_empty)
            else -> stringResource(R.string.disconnected)
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("$tag:header")) {
        val width = maxWidth
        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.overview_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                    Text(
                        host,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        stateLine,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = toggle.onClick, modifier = Modifier.testTag(toggle.tag)) {
                    Icon(toggle.icon, toggle.label, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SheetCloseButton(onClose, tag = "$tag:close")
            }
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                NewTabButton(view, live, chosen, gone, newTab, menuWidth = width, tag = "$tag:new-tab")
            }
        }
    }
}
