package io.github.erikfrish.herdline.core

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject

/** An image reaches OMP as `[Image #N]`; any other file is mentioned as `@"path"`. */
enum class AttachmentKind(val wire: String) {
    IMAGE("image"),
    FILE("file"),
    ;

    companion object {
        fun parse(wire: String) = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * A file staged on the host for exactly [target]: its node, Herdr session,
 * daemon run, pane and agent instance. [path] is absolute and made only of
 * `[A-Za-z0-9._/-]`, so it pastes and mentions without quoting trouble.
 */
data class Attachment(
    val kind: AttachmentKind,
    val name: String,
    val path: String,
    val size: Long,
    val sha256: String,
    val target: Connection.ComposerTarget,
) {
    fun toJson(): JSONObject = bindingJson(target)
        .put("kind", kind.wire)
        .put("name", name)
        .put("path", path)
        .put("size", size)
        .put("sha256", sha256)
}

sealed interface Staging {
    data class Staged(val attachment: Attachment) : Staging

    /** Nothing is left on the host; [reason] is the native core's (see docs/attachments.md). */
    data class Refused(val reason: String) : Staging
}

/**
 * Stages one picked file on the host over the open SSH session, never
 * through a third party (docs/attachments.md). Bytes stream from the
 * content URI in [CHUNK] pieces and are hashed on the way, so no file is
 * held in memory whole. Cancelling the calling coroutine cancels the
 * upload at once and the host removes what it wrote. Nothing here retries:
 * a failed upload is reported and the user decides.
 */
class AttachmentStager(private val connection: Connection, private val resolver: ContentResolver) {

    /**
     * Stages [uri] for [target]; [onProgress] gets the bytes sent and the
     * size the provider reported, when it did. Runs on the IO dispatcher.
     */
    suspend fun stage(
        uri: Uri,
        target: Connection.ComposerTarget,
        onProgress: (sent: Long, total: Long?) -> Unit,
    ): Staging = withContext(Dispatchers.IO) {
        val session = connection.link()
        if (session == 0L) return@withContext Staging.Refused("not_connected")
        val (display, declared) = describe(uri)
        val mime = resolver.getType(uri)
        val kind = attachmentKind(mime)
        val name = safeAttachmentName(display, kind, mime)
        if (declared != null && declared > kind.limit) return@withContext Staging.Refused("too_large")
        val request = bindingJson(target)
            .put("name", name)
            .put("kind", kind.wire)
            .put("size", declared ?: JSONObject.NULL)
        val opened = reply { Native.attachmentBegin(session, request.toString()) }
            ?: return@withContext Staging.Refused("not_connected")
        if (opened.optString("outcome") != "open") return@withContext refused(opened)
        val upload = opened.getLong("upload")
        val settled = AtomicBoolean(false)
        coroutineScope {
            // Runs on its own thread while a write may block in the core.
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    if (settled.compareAndSet(false, true)) Native.attachmentCancel(upload)
                }
            }
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                var sent = 0L
                val input = resolver.openInputStream(uri) ?: throw IOException("unreadable")
                input.use {
                    val buffer = ByteArray(CHUNK)
                    while (true) {
                        ensureActive()
                        val read = it.read(buffer)
                        if (read < 0) break
                        sent += read
                        if (sent > kind.limit) throw TooLarge()
                        digest.update(buffer, 0, read)
                        Native.attachmentWrite(upload, buffer, read)
                        onProgress(sent, declared)
                    }
                }
                val sha256 = digest.digest().joinToString("") { "%02x".format(it) }
                if (!settled.compareAndSet(false, true)) return@coroutineScope Staging.Refused("cancelled")
                val finished = reply { Native.attachmentFinish(upload, sha256) }
                    ?: return@coroutineScope Staging.Refused("not_connected")
                staged(finished, target) ?: refused(finished)
            } catch (e: TooLarge) {
                cancel(upload, settled)
                Staging.Refused("too_large")
            } catch (e: IOException) {
                cancel(upload, settled)
                Staging.Refused("unreadable")
            } catch (e: SecurityException) {
                cancel(upload, settled)
                Staging.Refused("unreadable")
            } finally {
                watcher.cancel()
            }
        }
    }

    /** Removes [attachment] from the host, as when the user removes it from the draft. */
    suspend fun discard(attachment: Attachment): Result<Unit> = withContext(Dispatchers.IO) {
        val session = connection.link()
        if (session == 0L) return@withContext Result.failure(IOException("not connected"))
        try {
            Native.attachmentDiscard(session, attachment.toJson().toString())
            Result.success(Unit)
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun describe(uri: Uri): Pair<String?, Long?> =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
            if (!it.moveToFirst()) return@use null to null
            val nameColumn = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeColumn = it.getColumnIndex(OpenableColumns.SIZE)
            val name = if (nameColumn >= 0 && !it.isNull(nameColumn)) it.getString(nameColumn) else null
            val size = if (sizeColumn >= 0 && !it.isNull(sizeColumn)) it.getLong(sizeColumn).takeIf { s -> s >= 0 } else null
            name to size
        } ?: (null to null)

    private fun cancel(upload: Long, settled: AtomicBoolean) {
        if (settled.compareAndSet(false, true)) Native.attachmentCancel(upload)
    }

    private class TooLarge : Exception()

    companion object {
        const val CHUNK = 64 * 1024
    }
}

/** OMP's own limit for a pasted image (`MAX_IMAGE_INPUT_BYTES`), and ours for other files. */
val AttachmentKind.limit: Long
    get() = when (this) {
        AttachmentKind.IMAGE -> 20L * 1024 * 1024
        AttachmentKind.FILE -> 64L * 1024 * 1024
    }

/** The image types OMP turns into `[Image #N]`, with the extension its path check needs. */
private val IMAGE_EXTENSIONS = mapOf(
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/gif" to "gif",
    "image/webp" to "webp",
)

internal fun attachmentKind(mime: String?): AttachmentKind =
    if (mime?.lowercase() in IMAGE_EXTENSIONS) AttachmentKind.IMAGE else AttachmentKind.FILE

/**
 * [display] reduced to `[A-Za-z0-9._-]`, no leading dot, at most
 * [NAME_MAX] characters with its extension kept. An image always ends in
 * the extension of its [mime], which OMP's image path check requires.
 */
internal fun safeAttachmentName(display: String?, kind: AttachmentKind, mime: String?): String {
    val cleaned = (display ?: "")
        .map { if (it.isLetterOrDigit() && it.code < 128 || it == '.' || it == '-' || it == '_') it else '_' }
        .joinToString("")
        .trimStart('.', '_')
    val dot = cleaned.lastIndexOf('.')
    var stem = if (dot > 0) cleaned.substring(0, dot) else cleaned
    var extension = if (dot > 0) cleaned.substring(dot + 1).lowercase() else ""
    val wanted = IMAGE_EXTENSIONS[mime?.lowercase()]
    if (kind == AttachmentKind.IMAGE && wanted != null) {
        if (extension != wanted && !(wanted == "jpg" && extension == "jpeg")) {
            if (extension.isNotEmpty()) stem = "${stem}_$extension"
            extension = wanted
        }
    }
    stem = stem.trimEnd('.').ifEmpty { "attachment" }
    val suffix = if (extension.isEmpty()) "" else ".$extension"
    return stem.take((NAME_MAX - suffix.length).coerceAtLeast(1)) + suffix
}

internal const val NAME_MAX = 80

private fun bindingJson(target: Connection.ComposerTarget) = JSONObject()
    .put("node", target.nodeId)
    .put("session", target.session)
    .put("boot", target.boot)
    .put("pane", target.pane)
    .put("instance", target.instance?.toJson() ?: JSONObject.NULL)

private fun staged(reply: JSONObject, target: Connection.ComposerTarget): Staging.Staged? {
    if (reply.optString("outcome") != "staged") return null
    val a = reply.optJSONObject("attachment") ?: return null
    return try {
        Staging.Staged(
            Attachment(
                kind = AttachmentKind.parse(a.getString("kind")) ?: return null,
                name = a.getString("name"),
                path = a.getString("path"),
                size = a.getLong("size"),
                sha256 = a.getString("sha256"),
                target = target,
            ),
        )
    } catch (e: JSONException) {
        null
    }
}

private fun refused(reply: JSONObject) = Staging.Refused(reply.optString("reason").ifEmpty { "failed" })

private fun reply(call: () -> String): JSONObject? = try {
    JSONObject(call())
} catch (e: IOException) {
    null
} catch (e: JSONException) {
    null
}
