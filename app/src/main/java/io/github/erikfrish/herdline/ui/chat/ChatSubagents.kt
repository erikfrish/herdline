package io.github.erikfrish.herdline.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.collapse
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.expand
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.TranscriptSubagent
import io.github.erikfrish.herdline.core.TranscriptSubagentEnd

/**
 * A subagent a `task` call spawned, as the UI holds it: one row per child
 * under the tool row. The wire is the source of truth; nothing here figures
 * a child's state out of result text. [status] is the value the wire
 * carried — a tool result arriving never marks a child "done".
 *
 * Three wires carry children, all read exactly as given:
 * - the tool result's `subagents[]` snapshot
 *   ([TranscriptBlock.ToolResult.subagents]), the canvas contract below,
 *   bounded at `MAX_SUBAGENTS` (64) entries with each `output` cut to
 *   `MAX_SUBAGENT_OUTPUT_CHARS` (4096) chars at the wire parser;
 * - a settled end: the entry's `subagent_ends` field
 *   ([TranscriptEntry.subagentEnds]), each `{id,status,durationMs}` for the
 *   child with that exact id, terminal only, matched by id alone;
 * - the ingress endpoint's live `state.subagents[]` ([IngressSubagent],
 *   `parentCallId` naming the spawning tool call id), folded into that
 *   call's rows by exact parent call id, else exact child id.
 *
 * ```
 * {"id":"a1","agent":"explorer","type":"researcher","status":"running",
 *  "task":"Find the parser","stage":"reading parser.ts","output":"…tail…",
 *  "durationMs":5210,"requests":3}
 * ```
 * All fields but `id` and `status` are optional; `agent` is the child's
 * name (OMP child names are unique) and `type` its role, never raw JSON
 * argument lines; `durationMs`/`requests` are numbers. Statuses are the
 * task-progress values `pending`, `running`, `completed`, `failed`,
 * `aborted`, plus `cancelled` for an ended async job. Matching keys on `id`
 * alone; within one source only the newest snapshot per id is kept.
 */
@Immutable
data class Subagent(
    val id: String,
    val name: String? = null,
    val type: String? = null,
    val status: SubagentStatus,
    val task: String? = null,
    val stage: String? = null,
    val output: String? = null,
    val error: String? = null,
    val durationMs: Long? = null,
    val requests: Int? = null,
    /** End of a settled run: exit code when one was reported. */
    val exitCode: Int? = null,
)

enum class SubagentStatus { Pending, Running, Completed, Failed, Aborted, Cancelled }

/** One subagent's row: how its glyph draws. The chip below names the exact status. */
enum class SubagentGraphic { Spinner, Done, Failed, Stopped }

/** What a settled end says: one close for the child [id] matches. */
data class SubagentCompletion(val id: String, val status: SubagentStatus, val durationMs: Long? = null)

/** Labels fall back to the id, so they are cut to something a head row can hold. */
const val SUBAGENT_LABEL_CHARS = 48

/**
 * The child this snapshot states, else null: [TranscriptSubagent.id] and a
 * status are required, and an unknown status is not guessed into anything.
 */
internal fun subagentOf(wire: TranscriptSubagent): Subagent? = subagentStatus(wire.status)?.let { known ->
    Subagent(
        id = wire.id,
        name = wire.agent?.takeIf(String::isNotBlank),
        type = wire.type?.takeIf(String::isNotBlank),
        status = known,
        task = wire.task?.takeIf(String::isNotBlank),
        stage = wire.stage?.takeIf(String::isNotBlank),
        output = wire.output?.takeIf(String::isNotBlank),
        error = wire.error?.takeIf(String::isNotBlank),
        durationMs = wire.durationMs,
        requests = wire.requests,
        exitCode = wire.exitCode,
    )
}

/** The children [snapshot] states; a child without a known id or status is left out entirely. */
internal fun subagentsOf(snapshot: List<TranscriptSubagent>): List<Subagent> = snapshot.mapNotNull(::subagentOf)

/**
 * The settled outcome this end states for the child it names, or null: an
 * end is terminal only ([SubagentStatus.Completed], [SubagentStatus.Failed],
 * [SubagentStatus.Aborted], [SubagentStatus.Cancelled]), so a `pending` or
 * `running` one settles nothing, and an unknown status settles nothing at
 * all — even when the model was built without the JSON parser.
 */
internal val TranscriptSubagentEnd.completion: SubagentCompletion?
    get() = subagentStatus(status)?.takeIf { it != SubagentStatus.Pending && it != SubagentStatus.Running }
        ?.let { SubagentCompletion(id, it, durationMs = durationMs) }

/** The settled outcomes [ends] state; one that settles nothing is left out entirely. */
internal fun completionsOf(ends: List<TranscriptSubagentEnd>): List<SubagentCompletion> = ends.mapNotNull { it.completion }

/** The status the wire named, or null: a value this app knows or nothing. */
internal fun subagentStatus(raw: String): SubagentStatus? = when (raw) {
    "pending" -> SubagentStatus.Pending
    "running" -> SubagentStatus.Running
    "completed" -> SubagentStatus.Completed
    "failed" -> SubagentStatus.Failed
    "aborted" -> SubagentStatus.Aborted
    "cancelled" -> SubagentStatus.Cancelled
    else -> null
}

/**
 * The children one call shows: [held] merged with a newer tool-result
 * [snapshot] and any settled [ends]. Every `id` keeps at most one row — the
 * newest snapshot wins field by field, and a settled end only moves a row to
 * its terminal status. A settled id nothing shows yet appears (as running
 * that just closed) only when the end names that id and [claimed] has it: a
 * `task` tool can return its result before its children finish, so a result
 * never closes them and one result never discards an earlier result's
 * children. Rows the newest snapshot no longer carries stay with their last
 * known state and are never relabelled or closed from text.
 */
internal fun mergeSubagents(
    held: List<Subagent>,
    snapshot: List<Subagent>,
    ends: List<SubagentCompletion> = emptyList(),
    claimed: List<String> = emptyList(),
): List<Subagent> {
    val merged = LinkedHashMap<String, Subagent>()
    for (child in held + snapshot) merged[child.id] = merged[child.id]?.mergedWith(child) ?: child
    for (end in ends) {
        val row = merged[end.id]
        when {
            row != null -> merged[end.id] = row.settledBy(end)
            end.id in claimed -> merged[end.id] = Subagent(end.id, status = SubagentStatus.Running).settledBy(end)
        }
    }
    return merged.values.toList()
}

/** [update] merged over this child: newest wins field by field. A settled row is not reopened to running by a slower snapshot. */
private fun Subagent.mergedWith(update: Subagent): Subagent = Subagent(
    id = id,
    name = update.name ?: name,
    type = update.type ?: type,
    status = if (alive || !update.alive) update.status else status,
    task = update.task ?: task,
    stage = update.stage ?: stage,
    output = update.output ?: output,
    error = update.error ?: error,
    durationMs = update.durationMs ?: durationMs,
    requests = update.requests ?: requests,
    exitCode = update.exitCode ?: exitCode,
)

/** This child as [end] left it. An end is the newest channel and only ever a terminal close, so it applies. */
private fun Subagent.settledBy(end: SubagentCompletion): Subagent = copy(
    status = end.status,
    durationMs = end.durationMs ?: durationMs,
)

/** Whether the wire still runs this child. Only its own snapshot says so; result text never does. */
val Subagent.alive: Boolean get() = status == SubagentStatus.Pending || status == SubagentStatus.Running

/** How the child's row draws. Failed reads apart from ended-and-cancelled, like tool rows. */
val Subagent.graphic: SubagentGraphic
    get() = when (status) {
        SubagentStatus.Pending, SubagentStatus.Running -> SubagentGraphic.Spinner
        SubagentStatus.Completed -> SubagentGraphic.Done
        SubagentStatus.Failed -> SubagentGraphic.Failed
        SubagentStatus.Aborted, SubagentStatus.Cancelled -> SubagentGraphic.Stopped
    }

/** What the head line previews: the stage when the child says one, else the task itself. */
val Subagent.headline: String? get() = stage ?: task

/** What the output block shows: the child's output, else its error. */
val Subagent.outputText: String? get() = output ?: error

/** The label a row shows and TalkBack hears: agent name, else task's first line, else the type. */
val Subagent.displayName: String
    get() = (
        name?.takeIf(String::isNotBlank)
            ?: task?.lineSequence()?.firstOrNull(String::isNotBlank)?.trim()
            ?: type?.takeIf(String::isNotBlank)
            ?: id
        ).take(SUBAGENT_LABEL_CHARS)

/** A millisecond span as `"3s"`, `"2:05"`, `"1:02:05"` — short enough for a meta line. */
internal fun subagentDuration(ms: Long?): String {
    val s = (ms ?: 0) / 1000
    val h = s / 3600
    val m = s % 3600 / 60
    val sec = s % 60
    return when {
        h > 0 -> "%d:%02d:%02d".format(h, m, sec)
        m > 0 -> "%d:%02d".format(m, sec)
        else -> "${sec}s"
    }
}

/**
 * Subagent rows under a task tool's own body: one 48 dp header per child,
 * output collapsed unless [expanded] holds it open. The children a live call
 * shows — its [Subagent.alive] rows — are what the parent draws even while
 * the tool row is collapsed. TalkBack hears each header whole: name, task,
 * status, then the expanded state. Names are never raw JSON ([Subagent.displayName]).
 */
@Composable
internal fun SubagentSection(subagents: List<Subagent>, colors: ChatColors, expanded: MutableMap<String, Boolean>, key: String) {
    if (subagents.isEmpty()) return
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        subagents.forEach { child -> SubagentRow(child, colors, expanded, "$key:sub:" + child.id) }
    }
}

@Composable
private fun SubagentRow(child: Subagent, colors: ChatColors, expanded: MutableMap<String, Boolean>, key: String) {
    val open = expanded[key] == true
    val toggle = { expanded[key] = !open }
    val statusWord = stringResource(
        when (child.status) {
            SubagentStatus.Pending -> R.string.chat_subagents_pending
            SubagentStatus.Running -> R.string.chat_tool_running
            SubagentStatus.Completed -> R.string.chat_tool_done
            SubagentStatus.Failed -> R.string.chat_tool_failed
            SubagentStatus.Aborted -> R.string.chat_subagents_aborted
            SubagentStatus.Cancelled -> R.string.chat_subagents_cancelled
        },
    )
    val stateWord = stringResource(if (open) R.string.chat_state_expanded else R.string.chat_state_collapsed)
    val spoken = listOfNotNull(child.displayName, child.task, statusWord).joinToString(", ")
    Column(Modifier.fillMaxWidth().testTag("subagent:" + key)) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClick = toggle)
                .clearAndSetSemantics {
                    contentDescription = spoken
                    stateDescription = stateWord
                    if (open) collapse { toggle(); true } else expand { toggle(); true }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SubagentGlyph(child, statusWord, colors)
            Text(child.displayName, style = ChatType.toolName.copy(color = colors.text), maxLines = 1)
            val headline = child.headline
            if (headline != null) {
                Text(headline, style = ChatType.toolArg.copy(color = colors.muted), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            } else {
                Box(Modifier.weight(1f))
            }
            when (child.graphic) {
                SubagentGraphic.Failed -> SubagentChip(stringResource(R.string.chat_subagents_failed_chip), colors.err)
                SubagentGraphic.Stopped -> SubagentChip(
                    stringResource(if (child.status == SubagentStatus.Cancelled) R.string.chat_subagents_cancelled else R.string.chat_subagents_aborted),
                    colors.blocked,
                )
                else -> Unit
            }
            Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(16.dp), tint = colors.dim)
        }
        if (open) {
            Column(Modifier.padding(start = SubagentBodyIndent, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                child.task?.let { Text(it, style = ChatType.body.copy(color = colors.text)) }
                when {
                    child.requests != null -> Text(
                        stringResource(R.string.chat_subagents_calls, child.requests, subagentDuration(child.durationMs)),
                        style = ChatType.meta.copy(color = colors.dim),
                    )
                    child.durationMs != null -> Text(subagentDuration(child.durationMs), style = ChatType.meta.copy(color = colors.dim))
                }
                val output = child.outputText
                if (!output.isNullOrBlank()) {
                    val all = expanded["more:$key"] == true
                    val (tail, earlier) = remember(output) { outputTail(output, TOOL_TAIL_LINES) }
                    SubagentOutput(if (all) output.trimEnd('\n') else tail, colors)
                    if (!all && earlier > 0) {
                        val total = earlier + TOOL_TAIL_LINES
                        SubagentDisclosure(pluralStringResource(R.plurals.chat_show_all_lines, total, total), colors) { expanded["more:$key"] = true }
                    }
                }
            }
        }
    }
}

/** Where a subagent's body starts: under its name, past the 16 dp glyph and the 8 dp gap. */
private val SubagentBodyIndent = 24.dp

@Composable
private fun SubagentGlyph(child: Subagent, statusWord: String, colors: ChatColors) {
    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
        when (child.graphic) {
            // A child not run yet moves nothing; its chip says pending.
            SubagentGraphic.Spinner -> if (child.status == SubagentStatus.Pending) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, statusWord, Modifier.size(14.dp), tint = colors.dim)
            } else {
                CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = colors.working)
            }
            SubagentGraphic.Failed -> Icon(Icons.Default.Close, statusWord, Modifier.size(14.dp), tint = colors.err)
            SubagentGraphic.Stopped -> Icon(Icons.Default.Close, statusWord, Modifier.size(14.dp), tint = colors.blocked)
            SubagentGraphic.Done -> Icon(Icons.Default.Check, statusWord, Modifier.size(14.dp), tint = colors.done)
        }
    }
}

/**
 * The state at the end of a child's head row, drawn like a small outlined
 * chip and not a target of its own: the header is. TalkBack hears the state
 * from that one node, so the chip is hidden from it.
 */
@Composable
private fun SubagentChip(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, color),
        modifier = Modifier.clearAndSetSemantics {},
    ) {
        Text(label, style = ChatType.meta.copy(color = color), maxLines = 1, modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp))
    }
}

/** A child's output in its one frame, scrolling sideways. */
@Composable
private fun SubagentOutput(text: String, colors: ChatColors) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(colors.codeBg).border(1.dp, colors.border, shape)) {
        Text(
            text,
            style = ChatType.toolOutput.copy(color = colors.text),
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SubagentDisclosure(label: String, colors: ChatColors, onClick: () -> Unit) {
    Row(
        Modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(14.dp), tint = colors.accentText)
        Text(label, style = ChatType.action.copy(color = colors.accentText))
    }
}
