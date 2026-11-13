package io.github.erikfrish.herdline.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R

/**
 * The visible way out of a modal bottom sheet (U-1 #7), at the trailing end
 * of the sheet's header row, after its other header actions.
 *
 * This is an ✕ icon spoken as "Close", not a "Done" text button. In Material 3
 * a sheet's "Done" or "Save" confirms what was edited in it. The Tabs sheet
 * edits nothing; it only navigates, so leaving it is closing, the same as the
 * drag handle, a tap on the scrim or Back, which all keep working.
 *
 * [onClose] must hide the sheet first and drop it after, as a swipe does, also
 * when the hide ends short of the Hidden anchor (INVIS-SHEET):
 * `scope.launch { state.hide() }.invokeOnCompletion { cause -> if (cause == null || !state.isVisible) open = false }`.
 * A hide cut short with the sheet still up keeps it; then the sheet must also be
 * dropped once it reaches Hidden after it has shown, since M3's anchor-change
 * snap calls no onDismissRequest (INVIS-SHEET-2; see the Tabs sheet in SessionScreen).
 * Both rely on a `confirmValueChange` that never vetoes Hidden: a vetoed `hide()`
 * returns at once with the sheet still up, and this would drop it.
 * It takes no focus by itself; TalkBack reaches it as the last action of the header.
 */
@Composable
fun SheetCloseButton(onClose: () -> Unit, modifier: Modifier = Modifier, tag: String = "sheet:close") {
    IconButton(onClick = onClose, modifier = modifier.size(48.dp).testTag(tag)) {
        Icon(Icons.Default.Close, stringResource(R.string.close), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
