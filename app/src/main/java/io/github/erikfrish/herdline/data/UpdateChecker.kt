/*
 * Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0.
 * Modified for Herdline, 2026: checks Herdline's GitHub release channel and
 * downloads, verifies and hands a release to Android's installer, in place of
 * the download-link channel.
 */
package io.github.erikfrish.herdline.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.coroutineContext

/**
 * The release channel for [context]'s package. Checks run when asked, or at
 * most daily from the app's start; downloads only on a tap, and the handoff
 * only for a file that passed [ReleaseChannel.verify]. Only Herdline signed
 * with the release key alone has a channel; any other build is Unavailable.
 */
class UpdateChecker(private val context: Context) {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val installed: Long = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
    private val sdk = Build.VERSION.SDK_INT
    private val installedSigners: Set<String>? = runCatching {
        signerSet(context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES).signingInfo)
    }.getOrNull()

    private val channel = context.packageName == ReleaseChannel.PACKAGE && installedSigners == setOf(ReleaseChannel.SIGNER_SHA256)
    private val installedSigner = installedSigners?.singleOrNull()

    private val _check = MutableStateFlow<ReleaseCheck>(if (!channel) ReleaseCheck.Unavailable else cached() ?: ReleaseCheck.Idle)
    val check: StateFlow<ReleaseCheck> = _check

    /** When a check last read a usable list, in millis; 0 before the first. */
    fun lastGoodCheck(): Long = prefs.getLong("last_check", 0)

    private val _install = MutableStateFlow<ReleaseInstall>(ReleaseInstall.None)
    val install: StateFlow<ReleaseInstall> = _install

    private val _skipped = MutableStateFlow(prefs.getLong("skipped", 0))
    val skipped: StateFlow<Long> = _skipped

    private val _refused = MutableStateFlow(prefs.getStringSet("signer_refused", null)?.toSet().orEmpty())

    /** SHA-256s of downloads whose signer was refused: never offered again. */
    val refused: StateFlow<Set<String>> = _refused

    private val _stableDot = MutableStateFlow(false)

    /** A QA-passed stable build newer than this one, not skipped and not refused; a candidate never. */
    val stableDot: StateFlow<Boolean> = _stableDot

    private val _review = MutableStateFlow(false)

    /** The Hosts banner asked to show the App updates page. */
    val review: StateFlow<Boolean> = _review

    /** The versionName this launch first runs after an update, until acknowledged. */
    private val _justUpdated = MutableStateFlow<String?>(null)
    val justUpdated: StateFlow<String?> = _justUpdated

    private var job: Job? = null

    init {
        val seen = prefs.getLong("seen", 0)
        if (seen in 1 until installed) {
            _justUpdated.value = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }
        if (seen != installed) prefs.edit().putLong("seen", installed).apply()
        publishDot()
    }

    fun installedVersion(): Long = installed

    fun requestReview() {
        _review.value = true
    }

    fun reviewShown() {
        _review.value = false
    }

    fun acknowledgeUpdate() {
        _justUpdated.value = null
    }

    fun skip(release: Release) {
        prefs.edit().putLong("skipped", release.versionCode).apply()
        _skipped.value = release.versionCode
        publishDot()
    }

    /**
     * Checks now when [force]d, otherwise only once a day has passed; files
     * left by an earlier run go first. Check now also ends a refused or
     * stopped download, so its notice never outlives the new result.
     */
    suspend fun check(force: Boolean) {
        if (!channel) return
        if (force && _check.value != ReleaseCheck.Checking) _install.value = _install.value.afterCheck()
        // Only while nothing is downloading or waiting on the installer, so its verified file stays.
        if (_install.value == ReleaseInstall.None) withContext(Dispatchers.IO) { clearFiles() }
        if (!force && !ReleaseChannel.due(prefs.getLong("last_check", 0), System.currentTimeMillis())) return
        if (_check.value == ReleaseCheck.Checking) return
        val previous = _check.value
        _check.value = ReleaseCheck.Checking
        val next = try {
            val text = withContext(Dispatchers.IO) { fetchManifest() }
            val now = System.currentTimeMillis()
            ReleaseChannel.outcome(text, context.packageName, installedSigner, now).also {
                if (it is ReleaseCheck.Checked) prefs.edit().putLong("last_check", now).putString("manifest", text).apply()
            }
        } catch (e: IOException) {
            if (offline(e)) ReleaseCheck.Offline else ReleaseCheck.Failed
        }
        // A failed background check keeps whatever was known before.
        _check.value = if (force || next is ReleaseCheck.Checked) next else previous
        publishDot()
    }

    /** Downloads [release] in [scope], verifies it and hands it on; nothing for a release that isn't installable. */
    fun download(release: Release, scope: CoroutineScope) {
        // Until the last download has settled its state and files, even while it is being cancelled.
        if (!channel || job?.isCompleted == false || !release.installable(installed, sdk, _refused.value)) return
        val total = release.size ?: return
        job = scope.launch {
            _install.value = ReleaseInstall.Downloading(release, 0, total)
            val dir = File(context.cacheDir, ReleaseChannel.CACHE_DIR)
            val part = File(dir, "${release.versionCode}.apk.part")
            val apk = File(dir, "${release.versionCode}.apk")
            try {
                val sha = withContext(Dispatchers.IO) {
                    clearFiles()
                    dir.mkdirs()
                    fetchApk(release, total, part)
                }
                _install.value = ReleaseInstall.Verifying(release)
                val mismatch = withContext(Dispatchers.IO) {
                    if (!part.renameTo(apk)) throw IOException("rename")
                    ReleaseChannel.verify(release, context.packageName, installed, installedSigners, apk.length(), sha, archiveFacts(apk))
                }
                if (mismatch != null) {
                    settleAfter(Dispatchers.IO, { apk.delete() }) {
                        if (mismatch == Mismatch.Signer) refuse(release)
                        _install.value = ReleaseInstall.Mismatched(release, mismatch)
                    }
                    return@launch
                }
                handOffOrAsk(release)
            } catch (e: CancellationException) {
                settleAfter(Dispatchers.IO, { part.delete(); apk.delete() }) {
                    _install.value = ReleaseInstall.Stopped(release, StopReason.Cancelled)
                }
                throw e
            } catch (e: TooLarge) {
                settleAfter(Dispatchers.IO, { part.delete() }) { _install.value = ReleaseInstall.Mismatched(release, Mismatch.Size) }
            } catch (e: IOException) {
                // A stream that fails after Cancel was cancelled, not stopped by the server.
                val reason = if (isActive) stopReason(e) else StopReason.Cancelled
                settleAfter(Dispatchers.IO, { part.delete(); apk.delete() }) { _install.value = ReleaseInstall.Stopped(release, reason) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** Back from the install-sources settings: hands on if allowed, else stays asking. */
    fun permissionReturned(scope: CoroutineScope) {
        val state = _install.value as? ReleaseInstall.NeedsPermission ?: return
        if (context.packageManager.canRequestPackageInstalls()) scope.launch { handOff(state.release) }
    }

    /** Opens Android's installer again for the verified file, re-hashed first. */
    fun installAgain(scope: CoroutineScope) {
        val release = when (val state = _install.value) {
            is ReleaseInstall.Handed -> state.release
            is ReleaseInstall.NotInstalled -> state.release
            else -> return
        }
        scope.launch { handOffOrAsk(release) }
    }

    private suspend fun handOffOrAsk(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            _install.value = ReleaseInstall.NeedsPermission(release)
            return
        }
        handOff(release)
    }

    /** The verified file, re-hashed just before the handoff, opened in Android's installer. */
    private suspend fun handOff(release: Release) {
        val apk = File(File(context.cacheDir, ReleaseChannel.CACHE_DIR), "${release.versionCode}.apk")
        val sha = withContext(Dispatchers.IO) { if (apk.isFile) hashFile(apk) else null }
        if (sha == null || sha != release.sha256) {
            withContext(Dispatchers.IO) { apk.delete() }
            _install.value = ReleaseInstall.Mismatched(release, Mismatch.Checksum)
            return
        }
        _install.value = try {
            val uri = FileProvider.getUriForFile(context, context.packageName + ReleaseChannel.AUTHORITY_SUFFIX, apk)
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ReleaseInstall.Handed(release)
        } catch (e: ActivityNotFoundException) {
            ReleaseInstall.NotInstalled(release)
        } catch (e: IllegalArgumentException) {
            // No FileProvider for the updates directory in this build.
            ReleaseInstall.NotInstalled(release)
        }
    }

    private fun publishDot() {
        val stable = (_check.value as? ReleaseCheck.Checked)?.manifest?.stable
        _stableDot.value = stable != null && stable.installable(installed, sdk, _refused.value) && stable.versionCode != _skipped.value
    }

    /** A retry can't fix a signer mismatch: that file is never offered again. */
    private fun refuse(release: Release) {
        val sha = release.sha256 ?: return
        val all = _refused.value + sha
        prefs.edit().putStringSet("signer_refused", all).apply()
        _refused.value = all
        publishDot()
    }

    private fun cached(): ReleaseCheck? {
        val text = prefs.getString("manifest", null) ?: return null
        val manifest = runCatching { ReleaseChannel.parse(text, context.packageName, installedSigner) }.getOrNull() ?: return null
        return ReleaseCheck.Checked(manifest, prefs.getLong("last_check", 0))
    }

    private fun clearFiles() {
        File(context.cacheDir, ReleaseChannel.CACHE_DIR).listFiles()?.forEach { it.delete() }
    }

    private fun archiveFacts(apk: File): ArchiveFacts? {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return null
        return ArchiveFacts(info.packageName, info.longVersionCode, signerSet(info.signingInfo))
    }

    /**
     * A GET of [uri] over the platform's HTTPS stack, its hostname verifier
     * and trust never replaced here. No redirect is followed by the platform
     * and no cache used; the request sets no Authorization or Cookie, and the
     * app installs no CookieHandler or Authenticator that could add one.
     */
    private fun open(uri: URI): HttpsURLConnection {
        val connection = uri.toURL().openConnection() as? HttpsURLConnection ?: throw IOException("not HTTPS")
        return connection.apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
        }
    }

    /** The fixed manifest, at most [ReleaseChannel.MAX_MANIFEST] bytes; a redirect is a failure. */
    private fun fetchManifest(): String {
        val connection = open(URI(ReleaseChannel.MANIFEST_URL))
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw ServerError()
            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > ReleaseChannel.MAX_MANIFEST) throw ServerError()
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The release's asset, connected: its GitHub URL, then at most
     * [ReleaseChannel.MAX_REDIRECTS] redirects, each only where
     * [ReleaseChannel.redirect] allows. One more redirect is a failure.
     */
    private fun connectApk(release: Release): HttpsURLConnection {
        var uri = URI(release.url ?: throw ServerError())
        var redirects = 0
        while (true) {
            val connection = open(uri)
            val next = try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> return connection
                    HttpURLConnection.HTTP_MOVED_PERM, HttpURLConnection.HTTP_MOVED_TEMP, HttpURLConnection.HTTP_SEE_OTHER, 307, 308 ->
                        ReleaseChannel.redirect(release, uri, connection.getHeaderField("Location"))
                    else -> null
                }
            } catch (e: IOException) {
                connection.disconnect()
                throw e
            }
            connection.disconnect()
            if (next == null || redirects == ReleaseChannel.MAX_REDIRECTS) throw ServerError()
            redirects++
            uri = next
        }
    }

    /** Streams the APK to [part], never past [total] bytes, and returns its SHA-256. */
    private suspend fun fetchApk(release: Release, total: Long, part: File): String {
        val connection = connectApk(release)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var read = 0L
            var shown = -1L
            part.outputStream().use { out ->
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        read += n
                        if (read > total) throw TooLarge()
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        val percent = read * 100 / total
                        if (percent != shown) {
                            shown = percent
                            _install.value = ReleaseInstall.Downloading(release, read, total)
                        }
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun hashFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun offline(e: IOException) = e is UnknownHostException || e is ConnectException || e is NoRouteToHostException || e is SocketTimeoutException

    private fun stopReason(e: IOException) = when (e) {
        is SocketTimeoutException -> StopReason.Timeout
        is UnknownHostException, is ConnectException, is NoRouteToHostException -> StopReason.Offline
        else -> StopReason.Server
    }

    private class ServerError : IOException("server")

    private class TooLarge : IOException("larger than the manifest says")
}
