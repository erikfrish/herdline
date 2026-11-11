/*
 * Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0.
 * Modified for Herdline, 2026: one release channel on GitHub (fixed manifest,
 * release APKs checked before Android's installer) replaces the download-link
 * channel.
 */
package io.github.erikfrish.herdline.data

import android.content.pm.SigningInfo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/*
 * Herdline's update channel. The list is one fixed file in the public
 * repository, read from raw.githubusercontent.com; a release's APK is the one
 * fixed asset of its GitHub release, which GitHub may hand to its asset CDN.
 * The platform's HTTPS stack does the rest: system trust anchors, the
 * platform hostname verifier, no credentials. Nothing here installs: a
 * verified APK goes to Android's own installer only after the user taps, and
 * the user confirms it there.
 */

/** A release's QA state, as recorded in the manifest. */
enum class QaState { Passed, NotTested, Failed }

/** One entry of the manifest; a Failed one carries no download. */
data class Release(
    val versionCode: Long,
    val versionName: String,
    val url: String?,
    val sha256: String?,
    val size: Long?,
    val minSdk: Int,
    val qa: QaState,
    /** One plain-text line on why QA failed it, or null. */
    val qaReason: String?,
    /** Accepted by the maintainer: a flag of its own, never implied by a QA pass. */
    val accepted: Boolean,
    /** Plain-text notes by language; English is always present. */
    val notes: Map<String, String>,
) {
    fun notesFor(language: String): String = notes[language] ?: notes["en"].orEmpty()

    /**
     * Whether this release may be offered to a device running [installed] on
     * API [sdk]: never a Failed build, never the same or an older versionCode,
     * never a file in [refused] (SHA-256s whose signer was refused: a retry
     * can't fix that).
     */
    fun installable(installed: Long, sdk: Int, refused: Set<String> = emptySet()): Boolean =
        qa != QaState.Failed && url != null && sha256 != null && size != null && versionCode > installed && minSdk <= sdk &&
            sha256 !in refused
}

/** The manifest: a QA-passed stable entry and an optional newer candidate. */
data class ReleaseManifest(val stable: Release?, val candidate: Release?) {
    /** The entry describing [versionCode], for the installed build's own QA state. */
    fun entry(versionCode: Long): Release? = listOfNotNull(stable, candidate).firstOrNull { it.versionCode == versionCode }
}

/** Why a manifest was not used. */
enum class ManifestFault {
    /** Not JSON at all. */
    NotJson,

    /** A schema this client doesn't know. */
    Schema,

    /** Written for another package. */
    Package,

    /** The manifest's or this build's signer is not Herdline's release key. */
    Signer,

    /** A field is missing, of the wrong type or out of range, or a URL is not the fixed one. */
    Field,
}

class ReleaseManifestException(val fault: ManifestFault, message: String, cause: Throwable? = null) : IOException(message, cause)

/** Why a downloaded file was refused before the installer. */
enum class Mismatch { Size, Checksum, Package, Version, Signer }

/** What Android reads from a downloaded APK; null fields could not be read. */
data class ArchiveFacts(val packageName: String?, val versionCode: Long?, val signers: Set<String>?)

object ReleaseChannel {
    const val PACKAGE = "io.github.erikfrish.herdline"
    const val REPOSITORY = "erikfrish/herdline"
    const val MANIFEST_HOST = "raw.githubusercontent.com"
    const val MANIFEST_URL = "https://$MANIFEST_HOST/$REPOSITORY/main/updates/latest.json"
    const val RELEASE_HOST = "github.com"

    /** The hosts GitHub hands a release asset to; a download may be redirected to these only. */
    val ASSET_HOSTS = setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")

    /** Redirects one APK download may follow; the manifest follows none. */
    const val MAX_REDIRECTS = 3

    /**
     * SHA-256 of the release signing certificate. A build signed with any
     * other key, a build of your own included, has no channel: it would be
     * offered APKs Android refuses to install over it.
     */
    const val SIGNER_SHA256 = "016e2d4c697bf40ce13786792318d40866a3cf21de37168a6616dbbada22c794"

    /** The FileProvider authority is the package name plus this; the app manifest declares it. */
    const val AUTHORITY_SUFFIX = ".updates"

    /** The cacheDir subdirectory the FileProvider's `<cache-path name="updates" path="updates/"/>` exposes. */
    const val CACHE_DIR = "updates"

    const val MAX_MANIFEST = 64 * 1024
    const val MAX_APK = 256L * 1024 * 1024

    /** How long an automatic check waits after the last one. */
    const val CHECK_INTERVAL = 24L * 60 * 60 * 1000
    private const val MAX_NOTES = 2000
    private const val MAX_REASON = 120

    /** Semantic version, optional dotted pre-release; no build metadata, no leading zeros. */
    private val VERSION_NAME = Regex("(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})\\.(0|[1-9][0-9]{0,4})(-[0-9A-Za-z]{1,12}(\\.[0-9A-Za-z]{1,12}){0,3})?")
    private val SHA256 = Regex("[0-9a-f]{64}")

    /** The one URL a release's APK may come from: its GitHub release's asset. */
    fun apkUrl(versionName: String): String =
        "https://$RELEASE_HOST/$REPOSITORY/releases/download/v$versionName/herdline-$versionName.apk"

    /** Whether an automatic check, last run at [lastCheck] millis, is due at [now]; a clock set back never postpones it. */
    fun due(lastCheck: Long, now: Long): Boolean = now - lastCheck !in 0 until CHECK_INTERVAL

    /**
     * Parses `latest.json` for [packageName], installed with signer
     * [installedSigner]. A stable entry that isn't QA-passed is dropped, as
     * is any entry without English notes; anything else wrong refuses the
     * whole manifest.
     */
    fun parse(text: String, packageName: String, installedSigner: String?): ReleaseManifest {
        val json = try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw ReleaseManifestException(ManifestFault.NotJson, "not JSON", e)
        }
        try {
            if (json.getInt("schema") != 1) throw ReleaseManifestException(ManifestFault.Schema, "unknown schema")
            if (json.getString("package") != packageName) throw ReleaseManifestException(ManifestFault.Package, "another package")
            // Both must be the pinned key: never the manifest's value alone.
            if (json.getString("signerSha256").lowercase() != SIGNER_SHA256 || installedSigner != SIGNER_SHA256) {
                throw ReleaseManifestException(ManifestFault.Signer, "not Herdline's signer")
            }
            val stable = entry(json, "stable")?.takeIf { it.qa == QaState.Passed }
            val candidate = entry(json, "candidate")
            return ReleaseManifest(stable, candidate)
        } catch (e: JSONException) {
            throw ReleaseManifestException(ManifestFault.Field, "bad field", e)
        }
    }

    /**
     * What a fetched manifest [text] makes the check, at [now]: Checked; or
     * Refused when it is written for another package or signer, so nothing
     * in it is used but Check now stays usable and a later good manifest
     * replaces it; or Failed for anything else wrong with it.
     */
    fun outcome(text: String, packageName: String, installedSigner: String?, now: Long): ReleaseCheck = try {
        ReleaseCheck.Checked(parse(text, packageName, installedSigner), now)
    } catch (e: ReleaseManifestException) {
        if (e.fault == ManifestFault.Package || e.fault == ManifestFault.Signer) ReleaseCheck.Refused(e.fault) else ReleaseCheck.Failed
    }

    /**
     * Where the download of [release], now at [from], may go on to for a
     * redirect to [location], or null when it must stop. [from] must be the
     * release's own GitHub URL or an asset host GitHub sent it to; the next
     * hop only an HTTPS asset host on the default port, without user info or
     * fragment. The asset host's query (its signed, short-lived grant) is
     * kept; no header of the request goes along.
     */
    fun redirect(release: Release, from: URI, location: String?): URI? {
        location ?: return null
        val url = release.url ?: return null
        if (from.toString() != url && !isAsset(from)) return null
        val to = runCatching { from.resolve(URI(location)) }.getOrNull() ?: return null
        return to.takeIf(::isAsset)
    }

    private fun isAsset(uri: URI): Boolean =
        uri.isAbsolute && uri.scheme == "https" && uri.host in ASSET_HOSTS && uri.port in setOf(-1, 443) &&
            uri.rawUserInfo == null && uri.rawFragment == null && !uri.rawPath.isNullOrEmpty()

    private fun entry(json: JSONObject, key: String): Release? {
        if (json.isNull(key)) return null
        return release(json.optJSONObject(key) ?: throw field("$key is not an object"))
    }

    private fun release(o: JSONObject): Release? {
        val versionCode = o.getLong("versionCode")
        if (versionCode <= 0) throw field("versionCode")
        val versionName = o.getString("versionName")
        if (versionName.length > 32 || !VERSION_NAME.matches(versionName)) throw field("versionName")
        val qa = o.getJSONObject("qa")
        val state = when (qa.getString("state")) {
            "passed" -> QaState.Passed
            "not_tested" -> QaState.NotTested
            "failed" -> QaState.Failed
            else -> throw field("qa.state")
        }
        val reason = if (qa.isNull("reason")) null else plain(qa.getString("reason")).take(MAX_REASON).ifEmpty { null }
        val url = if (o.isNull("url")) null else o.getString("url")
        val sha256 = if (o.isNull("sha256")) null else o.getString("sha256").lowercase()
        val size = if (o.isNull("size")) null else o.getLong("size")
        // A failed build has no public APK; any other must say where, what and how big.
        if (state != QaState.Failed && (url == null || sha256 == null || size == null)) throw field("download")
        if (url != null && !isApkUrl(url, versionName)) throw field("url")
        if (sha256 != null && !SHA256.matches(sha256)) throw field("sha256")
        if (size != null && size !in 1..MAX_APK) throw field("size")
        val minSdk = o.optInt("minSdk", 0)
        if (minSdk < 0) throw field("minSdk")
        val notes = o.optJSONObject("notes")?.let { byLanguage ->
            byLanguage.keys().asSequence()
                .filter { lang -> lang.length in 2..8 && lang.all { it.isLetter() || it == '-' } }
                .associateWith { lang -> byLanguage.optString(lang).trim().take(MAX_NOTES) }
                .filterValues { it.isNotEmpty() }
        }.orEmpty()
        // Never offered blind.
        if (notes["en"] == null) return null
        return Release(versionCode, versionName, url, sha256, size, minSdk, state, reason, o.optBoolean("accepted", false), notes)
    }

    /** The release's own asset on GitHub and nothing else: no port, user, query or fragment. */
    private fun isApkUrl(url: String, versionName: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        return uri.scheme == "https" && uri.host == RELEASE_HOST && uri.port == -1 && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && url == apkUrl(versionName)
    }

    private fun plain(text: String): String = text.filterNot { it.isISOControl() }.trim()

    private fun field(what: String) = ReleaseManifestException(ManifestFault.Field, "bad $what")

    /**
     * Why the downloaded file must not reach the installer, or null when it
     * may: its [size] and [sha256] are the manifest's, and Android reads it
     * as this [packageName] at the manifest's versionCode, newer than
     * [installed], signed by exactly the installed key, which is the pinned one.
     */
    fun verify(
        release: Release,
        packageName: String,
        installed: Long,
        installedSigners: Set<String>?,
        size: Long,
        sha256: String,
        archive: ArchiveFacts?,
    ): Mismatch? {
        val pinned = setOf(SIGNER_SHA256)
        if (size != release.size) return Mismatch.Size
        if (sha256 != release.sha256) return Mismatch.Checksum
        if (archive == null || archive.packageName != packageName) return Mismatch.Package
        if (archive.versionCode != release.versionCode || release.versionCode <= installed) return Mismatch.Version
        if (archive.signers != pinned || installedSigners != pinned) return Mismatch.Signer
        return null
    }
}

/** What the last check of the manifest found. */
sealed interface ReleaseCheck {
    data object Idle : ReleaseCheck
    data object Checking : ReleaseCheck

    /** GitHub can't be reached: no connection, or it is down. */
    data object Offline : ReleaseCheck

    /** A server error, a TLS failure or a manifest that couldn't be read. */
    data object Failed : ReleaseCheck

    /** The manifest is written for another package or signer: nothing in it is used. */
    data class Refused(val fault: ManifestFault) : ReleaseCheck

    /** This build is not Herdline's, signed with the release key alone: it has no channel. */
    data object Unavailable : ReleaseCheck
    data class Checked(val manifest: ReleaseManifest, val at: Long) : ReleaseCheck

    /** Whether Check now can run: always, except without a channel or while a check runs. */
    val canCheck: Boolean get() = this != Unavailable && this != Checking
}

/** Why a download stopped. */
enum class StopReason { Cancelled, Offline, Timeout, Server }

/** Where a download and its handoff to Android's installer stand. */
sealed interface ReleaseInstall {
    data object None : ReleaseInstall
    data class Downloading(val release: Release, val read: Long, val total: Long) : ReleaseInstall
    data class Verifying(val release: Release) : ReleaseInstall
    data class Mismatched(val release: Release, val reason: Mismatch) : ReleaseInstall
    data class Stopped(val release: Release, val reason: StopReason) : ReleaseInstall
    data class NeedsPermission(val release: Release) : ReleaseInstall

    /** Handed to Android's installer; Install opens it again. */
    data class Handed(val release: Release) : ReleaseInstall

    /** Android's installer could not be opened. */
    data class NotInstalled(val release: Release) : ReleaseInstall

    /**
     * What Check now leaves: a refused or stopped download is over, its file
     * already deleted, so the new check's result shows alone. A download
     * under way and a verified file waiting on the installer stay.
     */
    fun afterCheck(): ReleaseInstall = if (this is Mismatched || this is Stopped) None else this
}

/**
 * Runs [cleanup] on [io], then [settle], even in a cancelled coroutine. A
 * withContext that changes dispatcher resumes its caller cancellably, so in
 * a cancelled caller the code after it never ran and Cancel left the page
 * on Downloading; NonCancellable around both keeps them together.
 */
internal suspend fun settleAfter(io: CoroutineContext, cleanup: () -> Unit, settle: () -> Unit) {
    withContext(NonCancellable) {
        withContext(io) { cleanup() }
        settle()
    }
}

/** SHA-256 of [bytes] as lowercase hex. */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** The signer set: every signer when there are several, else the current one; null when unreadable. */
internal fun signerSet(info: SigningInfo?): Set<String>? {
    info ?: return null
    val certs = if (info.hasMultipleSigners()) info.apkContentsSigners?.toList() else listOfNotNull(info.signingCertificateHistory?.lastOrNull())
    return certs?.takeIf { it.isNotEmpty() }?.map { sha256Hex(it.toByteArray()) }?.toSet()
}
