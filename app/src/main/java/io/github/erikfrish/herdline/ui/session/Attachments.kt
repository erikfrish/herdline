package io.github.erikfrish.herdline.ui.session

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Size
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.AttachmentKind
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.attachmentKind
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.DraftAttachment
import java.io.IOException
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Whether the composer offers to attach files: the draft may be edited, its
 * target is an OMP whose instance was read (only OMP turns a pasted path
 * into `[Image #N]`, and a staged file is bound to that instance), and the
 * draft has room for another.
 */
internal fun attachOffered(busy: Boolean, target: Connection.ComposerTarget?, count: Int, max: Int): Boolean =
    !busy && target?.instance != null && target.agent.equals("omp", ignoreCase = true) && count < max

/** Why the + menu's attach items are disabled; the first that applies is named. */
internal enum class AttachBlock { NotConnected, Held, Sending, Rebind, NeedsAgent, Full }

/**
 * Why files can't be attached now, or null when they can: exactly when
 * [binding] is Bound and [attachOffered] holds for the draft.
 */
internal fun attachBlock(
    binding: Binding,
    sending: Boolean,
    unsettled: Boolean,
    target: Connection.ComposerTarget?,
    count: Int,
    max: Int,
): AttachBlock? = when {
    binding == Binding.Offline -> AttachBlock.NotConnected
    unsettled -> AttachBlock.Held
    sending -> AttachBlock.Sending
    binding == Binding.BootChanged || binding == Binding.AgentChanged || binding == Binding.AgentRestarted -> AttachBlock.Rebind
    binding == Binding.InstanceUnknown || target?.instance == null || !target.agent.equals("omp", ignoreCase = true) -> AttachBlock.NeedsAgent
    count >= max -> AttachBlock.Full
    else -> null
}

/** The draft's files on the To-line of a fit without chips: the worst state decides, as in [attachmentsState]. */
internal sealed interface AttachmentsLine {
    data class Count(val n: Int) : AttachmentsLine

    /** The [k]th of [n] is uploading: those before it are uploaded for this target. */
    data class Uploading(val k: Int, val n: Int) : AttachmentsLine
    data class Failed(val k: Int) : AttachmentsLine

    /** [k] aren't uploaded for this target, and none failed. */
    data class NotHere(val k: Int) : AttachmentsLine
}

internal fun attachmentsLine(
    attachments: List<DraftAttachment>,
    uploading: Set<String>,
    target: Connection.ComposerTarget?,
): AttachmentsLine? {
    if (attachments.isEmpty()) return null
    val n = attachments.size
    val idle = attachments.filterNot { it.id in uploading }
    val ready = idle.count { it.failure == null && target != null && it.staged?.target == target }
    val failed = idle.count { it.failure != null }
    return when {
        idle.size < n -> AttachmentsLine.Uploading(minOf(ready + 1, n), n)
        failed > 0 -> AttachmentsLine.Failed(failed)
        ready < n -> AttachmentsLine.NotHere(n - ready)
        else -> AttachmentsLine.Count(n)
    }
}

@Composable
internal fun attachmentsLineText(line: AttachmentsLine): String = when (line) {
    is AttachmentsLine.Count -> pluralStringResource(R.plurals.attachment_count, line.n, line.n)
    is AttachmentsLine.Uploading -> stringResource(R.string.attach_uploading_n, line.k, line.n)
    is AttachmentsLine.Failed -> pluralStringResource(R.plurals.attach_failed_n, line.k, line.k)
    is AttachmentsLine.NotHere -> pluralStringResource(R.plurals.attach_not_here_n, line.k, line.k)
}

/** An upload's progress as 0..1, or null while the provider didn't say how big the file is. */
internal fun uploadFraction(sent: Long, total: Long?): Float? =
    total?.takeIf { it > 0 }?.let { (sent.toFloat() / it).coerceIn(0f, 1f) }

/** The words for an upload's refusal [reason] (docs/attachments.md), never a cause it can't vouch for. */
@StringRes
internal fun stagingReasonRes(reason: String): Int = when (reason) {
    "not_connected" -> R.string.attachment_reason_not_connected
    "no_exec" -> R.string.attachment_reason_no_exec
    "too_large" -> R.string.attachment_reason_too_large
    "bad_name" -> R.string.attachment_reason_bad_name
    "target_changed" -> R.string.attachment_reason_target_changed
    "needs_agent" -> R.string.attachment_reason_needs_agent
    "staging_unsafe" -> R.string.attachment_reason_staging_unsafe
    "mismatch" -> R.string.attachment_reason_mismatch
    "no_tools" -> R.string.attachment_reason_no_tools
    "cancelled" -> R.string.attachment_reason_cancelled
    "unreadable" -> R.string.attachment_reason_unreadable
    // "failed", the host's unreadable answer, and any code this build doesn't know.
    else -> R.string.attachment_reason_failed
}

/**
 * [uri] as a new attachment: a persisted read grant where the provider
 * offers one, so the draft can read it again after a restart, and the name,
 * size and kind the provider reports. Reads the provider: call off the main thread.
 */
internal fun pickedAttachment(resolver: ContentResolver, uri: Uri): DraftAttachment {
    try {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    } catch (e: SecurityException) {
        // Not persistable here: it is read while the grant lasts, and asks for a new pick after.
    }
    var name: String? = null
    var size: Long? = null
    try {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (it.moveToFirst()) {
                val n = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = it.getColumnIndex(OpenableColumns.SIZE)
                if (n >= 0 && !it.isNull(n)) name = it.getString(n)
                if (s >= 0 && !it.isNull(s)) size = it.getLong(s).takeIf { v -> v >= 0 }
            }
        }
    } catch (e: SecurityException) {
        // Named by its URI; the upload reports it unreadable.
    }
    val kind = attachmentKind(runCatching { resolver.getType(uri) }.getOrNull())
    return DraftAttachment(
        id = UUID.randomUUID().toString(),
        uri = uri.toString(),
        kind = kind,
        name = name ?: uri.lastPathSegment ?: uri.toString(),
        size = size,
    )
}

/** The edge of a chip's thumbnail, in dp: a two-line, 48 dp chip, never a large preview. */
private const val THUMB_DP = 36

/** The upload ring over the thumbnail, and the `!` badge on its corner, in dp. */
private const val RING_DP = 20
private const val BADGE_DP = 14

// [AttachmentChips]' layout, shared with its budget in [attachmentsRowDp], in
// dp: the consent line (bodySmall, 16 sp a line, at most two lines) and the
// gap under it, a chip ([attachmentChipDp]) and the gap under the chip row.
internal const val ATTACH_LABEL_LINE = 16
private const val CONSENT_LINES = 2
private const val CONSENT_GAP = 4
private const val CHIP_MIN = 48

/** Above and below a chip's two text lines only; Remove and the thumbnail take the chip's height as it is. */
internal const val CHIP_PAD = 6
private const val CHIP_ROW_GAP = 6
private const val CHIP_MAX = 260

/** Refusals a tap can't fix: the same file is refused again. */
private val NOT_RETRYABLE = setOf("too_large")

/**
 * What a chip that isn't ready for this target shows besides its words: a
 * refusal is an error; a file not uploaded (new, interrupted, gone from the
 * host) gets the error badge alone; one uploaded for another pane, the
 * tertiary badge, as it only needs moving.
 */
internal enum class ChipProblem { None, Failed, NotUploaded, OtherTarget }

internal fun chipProblem(a: DraftAttachment, uploading: Boolean, target: Connection.ComposerTarget?): ChipProblem = when {
    uploading -> ChipProblem.None
    a.failure != null -> ChipProblem.Failed
    a.staged == null -> ChipProblem.NotUploaded
    target == null || a.staged.target != target -> ChipProblem.OtherTarget
    else -> ChipProblem.None
}

/**
 * [bytes] in binary units, as the limits are written (20 MiB, 64 MiB), so a
 * file the size line shows under a limit is under it.
 */
internal fun fileSizeText(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    var value = bytes / 1024.0
    var unit = 0
    while (value >= 1024 && unit < SIZE_UNITS.lastIndex) {
        value /= 1024
        unit++
    }
    // Truncated, never rounded up: 20.99 MiB reads 20.9, not 21.
    val shown = if (value < 100) "%.1f".format(Locale.ROOT, Math.floor(value * 10) / 10) else "%.0f".format(Locale.ROOT, Math.floor(value))
    return "$shown ${SIZE_UNITS[unit]}"
}

private val SIZE_UNITS = listOf("KiB", "MiB", "GiB")

/** An upload's progress as spoken: in 25 % steps, so TalkBack is not read every tick. */
internal fun spokenPercent(fraction: Float): Int = (fraction * 4).toInt().coerceIn(0, 4) * 25

/** [AttachmentChips] at its tallest at [fontScale], in dp: what a draft with files adds above the band. */
internal fun attachmentsRowDp(fontScale: Float): Float =
    CONSENT_LINES * ATTACH_LABEL_LINE * fontScale + CONSENT_GAP + attachmentChipDp(fontScale) + CHIP_ROW_GAP

/**
 * A chip's height at [fontScale], in dp: the tallest of its children laid
 * side by side, the 48 dp Remove (also the chip's floor), the 36 dp
 * thumbnail, and the name over the state inside [CHIP_PAD] above and below.
 * The padding sits on the text alone, so a chip is 48 dp up to font scale
 * 1.125, then grows with its text.
 */
internal fun attachmentChipDp(fontScale: Float): Float =
    maxOf(CHIP_MIN.toFloat(), THUMB_DP.toFloat(), 2 * CHIP_PAD + 2 * ATTACH_LABEL_LINE * fontScale)

/**
 * The draft's attachments as a row of chips over the field: a small
 * preview, the name, where its upload stands, and Remove. A chip that isn't
 * uploaded for this target uploads again when tapped. Remove of one under
 * way cancels its upload. Each chip is one TalkBack node with its actions,
 * and a change of its state is announced.
 */
@Composable
internal fun AttachmentChips(
    attachments: List<DraftAttachment>,
    progress: Map<String, Float?>,
    target: Connection.ComposerTarget?,
    host: String,
    enabled: Boolean,
    onRemove: (DraftAttachment) -> Unit,
    onRetry: (DraftAttachment) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val stateStyle = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
    BoxWithConstraints(modifier.fillMaxWidth().testTag("composer:attachments")) {
        // As wide as its content, up to 260 dp, and never the whole row.
        val chipMax = minOf(CHIP_MAX.dp, maxWidth - 16.dp)
        Column {
            // What leaves the phone and where to (no third party sees it).
            Text(
                stringResource(R.string.attachment_consent, host),
                style = stateStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = CONSENT_LINES,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = CONSENT_GAP.dp),
            )
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(bottom = CHIP_ROW_GAP.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { a ->
                    val uploading = a.id in progress
                    val fraction = progress[a.id]
                    val problem = chipProblem(a, uploading, target)
                    val status = when {
                        uploading -> fraction?.let { stringResource(R.string.attachment_uploading, (it * 100).toInt()) }
                            ?: stringResource(R.string.attachment_uploading_unknown)
                        a.failure != null -> stringResource(stagingReasonRes(a.failure))
                        a.staged == null -> stringResource(R.string.attachment_not_uploaded)
                        a.staged.target != target -> stringResource(R.string.attachment_other_target)
                        else -> attachmentMeta(a)
                    }
                    AttachmentChip(
                        attachment = a,
                        status = status,
                        spokenStatus = fraction?.takeIf { uploading }
                            ?.let { stringResource(R.string.attachment_uploading, spokenPercent(it)) } ?: status,
                        stateStyle = stateStyle,
                        maxWidth = chipMax,
                        problem = problem,
                        retryable = problem != ChipProblem.None && a.failure !in NOT_RETRYABLE,
                        uploading = uploading,
                        fraction = fraction,
                        enabled = enabled,
                        onRemove = { onRemove(a) },
                        onRetry = { onRetry(a) },
                    )
                }
            }
        }
    }
}

/** A ready chip's second line: the size, when known, and the file's type from its name. */
private fun attachmentMeta(a: DraftAttachment): String {
    val size = (a.staged?.size ?: a.size)?.let(::fileSizeText)
    val type = a.name.substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 }?.uppercase()
    return listOfNotNull(size, type).joinToString(" · ")
}

@Composable
private fun AttachmentChip(
    attachment: DraftAttachment,
    status: String,
    /** [status] as announced: an upload's percentage in 25 % steps. */
    spokenStatus: String,
    stateStyle: TextStyle,
    maxWidth: Dp,
    problem: ChipProblem,
    /** A tap on the chip uploads it again. */
    retryable: Boolean,
    uploading: Boolean,
    /** The upload's progress, or null while its size is unknown. */
    fraction: Float?,
    enabled: Boolean,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    val shape = MaterialTheme.shapes.small
    val failed = problem == ChipProblem.Failed
    val spoken = stringResource(
        if (attachment.kind == AttachmentKind.IMAGE) R.string.attachment_image_description else R.string.attachment_file_description,
        attachment.name,
        spokenStatus,
    )
    val retryLabel = stringResource(R.string.attachment_retry, attachment.name)
    Row(
        Modifier
            .widthIn(max = maxWidth)
            .heightIn(min = CHIP_MIN.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant, shape)
            .then(
                if (retryable) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClickLabel = retryLabel, onClick = onRetry)
                } else {
                    Modifier
                },
            )
            .padding(start = 4.dp)
            .testTag("composer:attachment"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box {
            Thumbnail(attachment)
            // Each repeats what line 2 says in words; neither is read out.
            if (uploading) {
                val ring = Modifier.align(Alignment.Center).size(RING_DP.dp)
                if (fraction != null) {
                    CircularProgressIndicator(progress = { fraction }, modifier = ring, strokeWidth = 2.dp)
                } else {
                    CircularProgressIndicator(modifier = ring, strokeWidth = 2.dp)
                }
            }
            if (problem != ChipProblem.None) ProblemBadge(problem, Modifier.align(Alignment.TopEnd))
        }
        Column(
            Modifier
                .weight(1f, fill = false)
                .padding(horizontal = 8.dp, vertical = CHIP_PAD.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = spoken
                    liveRegion = LiveRegionMode.Polite
                },
        ) {
            Text(
                attachment.name,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                status,
                style = stateStyle,
                color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Remove stays reachable during an upload: it is also how one is cancelled.
        IconButton(onClick = onRemove, enabled = enabled || uploading, modifier = Modifier.testTag("composer:attachment_remove")) {
            Icon(Icons.Default.Close, stringResource(R.string.attachment_remove, attachment.name))
        }
    }
}

/** The `!` on a chip's thumbnail: error, or tertiary for a file that only needs moving. */
@Composable
private fun ProblemBadge(problem: ChipProblem, modifier: Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (fill, mark) = if (problem == ChipProblem.OtherTarget) scheme.tertiary to scheme.onTertiary else scheme.error to scheme.onError
    Box(modifier.size(BADGE_DP.dp).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
        Text("!", color = mark, style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold), maxLines = 1)
    }
}

/**
 * A small preview: an image's thumbnail, decoded by the provider at chip
 * size on a background thread and held only while the chip shows; a plain
 * file glyph for anything else, or while the image can't be read.
 */
@Composable
private fun Thumbnail(attachment: DraftAttachment) {
    val context = LocalContext.current
    val px = with(LocalDensity.current) { THUMB_DP.dp.roundToPx() }
    var bitmap by remember(attachment.uri) { mutableStateOf<ImageBitmap?>(null) }
    if (attachment.kind == AttachmentKind.IMAGE) {
        LaunchedEffect(attachment.uri, px) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.loadThumbnail(Uri.parse(attachment.uri), Size(px, px), null).asImageBitmap()
                } catch (e: IOException) {
                    null
                } catch (e: SecurityException) {
                    null
                }
            }
        }
    }
    val glyph = bitmap == null
    Box(
        Modifier
            .size(THUMB_DP.dp)
            .clip(MaterialTheme.shapes.extraSmall)
            .then(if (glyph) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val image = bitmap
        if (image != null) {
            Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(THUMB_DP.dp))
        } else {
            Icon(
                painterResource(if (attachment.kind == AttachmentKind.IMAGE) R.drawable.ic_attachment_image else R.drawable.ic_attachment_file),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
