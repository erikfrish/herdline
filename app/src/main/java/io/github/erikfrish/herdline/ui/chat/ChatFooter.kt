package io.github.erikfrish.herdline.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptUsage
import io.github.erikfrish.herdline.ui.glass.GlassAlertDialog
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Recorded metadata of the latest assistant message, never a guessed live model or session total. */
internal data class ChatFooterData(val model: String?, val provider: String?, val usage: TranscriptUsage?)

internal fun chatFooterData(entries: List<TranscriptEntry>): ChatFooterData {
    val latest = currentBranch(entries).lastOrNull { it.kind == TranscriptKind.Message && it.role == "assistant" }
    return ChatFooterData(latest?.model, latest?.provider, latest?.usage)
}

internal fun replyCost(cost: Double?, locale: Locale = Locale.getDefault()): String? {
    if (cost == null || !cost.isFinite() || cost < 0) return null
    return NumberFormat.getCurrencyInstance(locale).apply {
        currency = Currency.getInstance("USD")
        minimumFractionDigits = if (cost > 0 && cost < 0.01) 4 else 2
        maximumFractionDigits = minimumFractionDigits
    }.format(cost)
}

/** Compact metadata immediately above the editor; the whole strip remains a 48 dp details target. */
@Composable
internal fun ChatFooter(data: ChatFooterData, cwd: String?, cached: Boolean, colors: ChatColors) {
    val model = listOfNotNull(data.provider?.takeIf { it.isNotBlank() }, data.model?.takeIf { it.isNotBlank() }).joinToString("/")
    val cost = remember(data.usage?.cost) { replyCost(data.usage?.cost) }
    val directory = cwd?.takeIf { it.isNotBlank() }
    if (model.isEmpty() && directory == null && cost == null && data.usage == null) return
    var details by remember { mutableStateOf(false) }
    val lastReply = stringResource(R.string.chat_footer_last_reply)
    val lastCost = cost?.let { stringResource(R.string.chat_footer_reply_cost, it) }
    val cachedWord = stringResource(R.string.chat_footer_cached)
    val spoken = listOfNotNull(cachedWord.takeIf { cached }, lastReply.takeIf { model.isNotEmpty() }, model.takeIf { it.isNotEmpty() }, directory, lastCost).joinToString(", ")
    val compact = ChatType.meta.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button) { details = true }
            .semantics { contentDescription = spoken }
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 3.dp).testTag("chat:footer"),
        verticalArrangement = Arrangement.Center,
    ) {
        if (model.isNotEmpty() || cached) Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cached) Text(cachedWord, style = compact.copy(color = colors.blocked), maxLines = 1)
            if (model.isNotEmpty()) Text(model, style = compact.copy(color = colors.accentText), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        if (directory != null || lastCost != null || data.usage != null) Row(
            Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            if (directory != null) Text(directory, style = compact.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (directory == null && lastCost == null) data.usage?.let {
                Text(stringResource(R.string.chat_footer_token_total, it.totalTokens), style = compact.copy(color = colors.muted), maxLines = 1, modifier = Modifier.weight(1f))
            }
            if (lastCost != null) Text(lastCost, style = compact.copy(color = colors.done), maxLines = 1)
        }
    }
    if (details) GlassAlertDialog(
        onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.chat_footer_details)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.chat_footer_provenance), style = ChatType.meta.copy(color = colors.muted))
                if (cached) Text(cachedWord, color = colors.blocked)
                data.provider?.let { Text(stringResource(R.string.chat_footer_provider, it)) }
                data.model?.let { Text(stringResource(R.string.chat_footer_model, it)) }
                directory?.let { Text(stringResource(R.string.chat_footer_directory, it)) }
                lastCost?.let { Text(it) }
                data.usage?.let { Text(stringResource(R.string.chat_footer_tokens, it.input, it.output, it.totalTokens)) }
            }
        },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.close)) } },
    )
}

/** Reuses Chat's existing pane memory; this composable performs no transcript read or input. */
@Composable
internal fun ChatSessionFooter(connection: Connection, pane: String, memory: ChatMemory, colors: ChatColors) {
    val kept = memory.of(pane)
    val history = kept.history ?: return
    val view by connection.view.collectAsState()
    val state by connection.state.collectAsState()
    val data = remember(history.entries) { chatFooterData(history.entries) }
    ChatFooter(data, view?.panes?.firstOrNull { it.id == pane }?.cwd,
        state !is Connection.State.Connected || kept.read.problem != null, colors)
}
