package io.github.erikfrish.herdline.data

import io.github.erikfrish.herdline.core.Attachment
import io.github.erikfrish.herdline.core.AttachmentKind
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.TranscriptIdentity
import org.json.JSONException
import org.json.JSONObject

/**
 * One file the user attached to a draft (UR3, docs/attachments.md). [uri]
 * is the content URI it is read from, with a persisted read grant where the
 * provider gives one; [name] and [size] are what the provider said. [staged]
 * is its copy on the host, bound to the target it was staged for, and
 * [failure] the refusal of its last upload. An upload under way is the
 * editor's alone and never stored: read back, it is simply not uploaded.
 */
data class DraftAttachment(
    val id: String,
    val uri: String,
    val kind: AttachmentKind,
    val name: String,
    val size: Long?,
    val staged: Attachment? = null,
    val failure: String? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("uri", uri)
        .put("kind", kind.wire)
        .put("name", name)
        .put("size", size ?: JSONObject.NULL)
        .put("staged", staged?.let(::stagedJson) ?: JSONObject.NULL)
        .put("failure", failure ?: JSONObject.NULL)

    companion object {
        /** Null for a record this build can't read, which then drops out of the draft. */
        fun fromJson(o: JSONObject): DraftAttachment? = try {
            DraftAttachment(
                id = o.getString("id"),
                uri = o.getString("uri"),
                kind = AttachmentKind.parse(o.getString("kind")) ?: return null,
                name = o.getString("name"),
                size = if (o.isNull("size")) null else o.getLong("size"),
                staged = o.optJSONObject("staged")?.let(::parseStaged),
                failure = if (o.isNull("failure")) null else o.optString("failure").ifEmpty { null },
            )
        } catch (e: JSONException) {
            null
        }
    }
}

/** These attachments with their host copies set aside: they are uploaded again for a new target. */
internal fun List<DraftAttachment>.unstaged(): List<DraftAttachment> = map { it.copy(staged = null, failure = null) }

/**
 * What a draft's attachments allow, for its target as it is now. Only
 * [Ready] and [None] let a delivery go: one still uploading, failed, never
 * uploaded or uploaded for another target (another pane, daemon run or agent
 * instance) keeps the whole draft back. Nothing is uploaded or sent on its own.
 */
enum class AttachmentsState { None, Ready, Uploading, Failed, NotUploaded, OtherTarget }

/**
 * [attachments]' state for [target], with [uploading] the IDs whose upload
 * is under way. The worst one decides, in this order: uploading, failed, not
 * uploaded, uploaded for another target.
 */
fun attachmentsState(attachments: List<DraftAttachment>, uploading: Set<String>, target: Connection.ComposerTarget?): AttachmentsState = when {
    attachments.isEmpty() -> AttachmentsState.None
    attachments.any { it.id in uploading } -> AttachmentsState.Uploading
    attachments.any { it.failure != null } -> AttachmentsState.Failed
    attachments.any { it.staged == null } -> AttachmentsState.NotUploaded
    target == null || attachments.any { it.staged?.target != target } -> AttachmentsState.OtherTarget
    else -> AttachmentsState.Ready
}

/** The most files one draft carries. */
const val MAX_ATTACHMENTS = 10

private fun stagedJson(a: Attachment): JSONObject = a.toJson().put("agent", a.target.agent ?: JSONObject.NULL)

private fun parseStaged(o: JSONObject): Attachment? = try {
    Attachment(
        kind = AttachmentKind.parse(o.getString("kind")) ?: return null,
        name = o.getString("name"),
        path = o.getString("path"),
        size = o.getLong("size"),
        sha256 = o.getString("sha256"),
        target = Connection.ComposerTarget(
            nodeId = o.getString("node"),
            session = o.getString("session"),
            boot = o.getString("boot"),
            pane = o.getString("pane"),
            agent = if (o.isNull("agent")) null else o.getString("agent"),
            instance = o.optJSONObject("instance")?.let { TranscriptIdentity.parse(it) },
        ),
    )
} catch (e: JSONException) {
    null
}
