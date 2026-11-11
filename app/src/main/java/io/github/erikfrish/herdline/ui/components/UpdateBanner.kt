/*
 * Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0.
 * Modified for Herdline, 2026: the banner offers a stable release of the
 * GitHub channel and opens the App updates page instead of a download link.
 */
package io.github.erikfrish.herdline.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.data.ReleaseCheck
import io.github.erikfrish.herdline.data.UpdateChecker
import io.github.erikfrish.herdline.ui.glass.GlassButton
import io.github.erikfrish.herdline.ui.glass.GlassGroup

/**
 * A newer stable release, unless the user skipped it: its notes, a way to
 * skip it, and "Review update", which opens the App updates page through
 * [onReview] and never starts a download itself. A test candidate never
 * shows here.
 */
@Composable
fun UpdateBanner(updates: UpdateChecker, modifier: Modifier = Modifier, onReview: () -> Unit = {}) {
    val language = LocalContext.current.resources.configuration.locales[0].language
    val dot by updates.stableDot.collectAsState()
    val check by updates.check.collectAsState()
    val stable = (check as? ReleaseCheck.Checked)?.manifest?.stable?.takeIf { dot } ?: return
    GlassGroup(modifier.padding(vertical = 8.dp)) {
        Column(Modifier.padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 16.dp)) {
            Text(stringResource(R.string.update_available_title, stable.versionName), style = MaterialTheme.typography.titleMedium)
            stable.notesFor(language).takeIf { it.isNotEmpty() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp, end = 8.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                GlassButton(stringResource(R.string.update_skip), onClick = { updates.skip(stable) })
                GlassButton(stringResource(R.string.updates_review), onClick = { updates.requestReview(); onReview() }, primary = true)
            }
        }
    }
}
