package io.github.erikfrish.herdline.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

class ReleaseChannelTest {
    private val pkg = "io.github.erikfrish.herdline"
    private val signer = ReleaseChannel.SIGNER_SHA256
    private val sha = "ab".repeat(32)
    private val installed = 10024L

    private fun asset(name: String) = "https://github.com/erikfrish/herdline/releases/download/v$name/herdline-$name.apk"

    private fun entry(versionCode: Long, versionName: String, qa: String = "passed", reason: String? = null) = JSONObject()
        .put("versionCode", versionCode)
        .put("versionName", versionName)
        .put("url", asset(versionName))
        .put("sha256", sha)
        .put("size", 22_323_163)
        .put("minSdk", 29)
        .put("qa", JSONObject().put("state", qa).put("reason", reason ?: JSONObject.NULL))
        .put("accepted", false)
        .put("notes", JSONObject().put("en", "Notes for $versionName").put("zh", "说明"))

    private fun manifest(
        stable: JSONObject? = entry(10025, "0.1.1"),
        candidate: JSONObject? = entry(10026, "0.2.0-rc.1", qa = "not_tested"),
    ) = JSONObject()
        .put("schema", 1)
        .put("package", pkg)
        .put("signerSha256", signer)
        .put("stable", stable ?: JSONObject.NULL)
        .put("candidate", candidate ?: JSONObject.NULL)

    private fun parse(json: JSONObject, installedSigner: String? = signer) = ReleaseChannel.parse(json.toString(), pkg, installedSigner)

    private fun fault(json: String, installedSigner: String? = signer): ManifestFault? =
        try {
            ReleaseChannel.parse(json, pkg, installedSigner)
            null
        } catch (e: ReleaseManifestException) {
            e.fault
        }

    private fun fault(json: JSONObject, installedSigner: String? = signer) = fault(json.toString(), installedSigner)

    private fun withUrl(url: String) = manifest(stable = entry(10025, "0.1.1").put("url", url))

    @Test
    fun aWellFormedManifestOffersStableAndListsTheCandidate() {
        val m = parse(manifest())
        assertEquals(10025L, m.stable?.versionCode)
        assertEquals(QaState.NotTested, m.candidate?.qa)
        assertTrue(m.stable!!.installable(installed, 34))
    }

    @Test
    fun theFirstPublicManifestWithOnlyAnUntestedCandidateIsUsable() {
        val m = parse(manifest(stable = null, candidate = entry(10024, "0.1.0", qa = "not_tested")))
        assertNull(m.stable)
        assertEquals(QaState.NotTested, m.entry(10024)?.qa)
    }

    @Test
    fun aDownloadOtherThanTheReleasesOwnGithubAssetIsRefused() {
        val name = "0.1.1"
        listOf(
            "http://github.com/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://evil.example/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://github.com.evil.example/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://evilgithub.com/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://www.github.com/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://github.com:443/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://github.com:8443/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://user@github.com/erikfrish/herdline/releases/download/v$name/herdline-$name.apk",
            "https://github.com/erikfrish/herdline/releases/download/v$name/herdline-$name.apk?x=1",
            "https://github.com/erikfrish/herdline/releases/download/v$name/herdline-$name.apk#f",
            // Another repository, a fork, another tag or another file of the release.
            "https://github.com/someone/herdline/releases/download/v$name/herdline-$name.apk",
            "https://github.com/erikfrish/herdline/releases/download/v0.1.0/herdline-$name.apk",
            "https://github.com/erikfrish/herdline/releases/download/v$name/herdline-0.1.0.apk",
            "https://github.com/erikfrish/herdline/releases/download/v$name/other-$name.apk",
            "https://github.com/erikfrish/herdline/releases/download/v$name/../v0.1.0/herdline-$name.apk",
            // Straight to the CDN, without the release route.
            "https://objects.githubusercontent.com/herdline-$name.apk",
            "https://raw.githubusercontent.com/erikfrish/herdline/main/herdline-$name.apk",
        ).forEach { url -> assertEquals(url, ManifestFault.Field, fault(withUrl(url))) }
    }

    @Test
    fun onlyASemanticVersionNameIsAccepted() {
        listOf("0.1.1", "1.20.3", "0.2.0-rc.1", "0.2.0-beta").forEach { name ->
            assertEquals(name, 10025L, parse(manifest(stable = entry(10025, name))).stable?.versionCode)
        }
        val tooLong = "1.0.0-" + List(4) { "a".repeat(12) }.joinToString(".")
        listOf("v0.1.1", "0.1", "1.0", "01.0.0", "0.1.1+build", "0.1.1-", "0.1.1-rc..1", "../x", "0.1.1/x", tooLong).forEach { name ->
            // The URL follows the name, so only the name can be at fault.
            val bad = entry(10025, "0.1.1").put("versionName", name).put("url", asset(name))
            assertEquals(name, ManifestFault.Field, fault(manifest(stable = bad)))
        }
    }

    @Test
    fun aManifestNamingAnotherSignerIsRefused() {
        assertEquals(ManifestFault.Signer, fault(manifest().put("signerSha256", "cd".repeat(32))))
    }

    @Test
    fun aBuildNotSignedWithTheReleaseKeyGetsNoChannel() {
        assertEquals(ManifestFault.Signer, fault(manifest(), installedSigner = "cd".repeat(32)))
        assertEquals(ManifestFault.Signer, fault(manifest(), installedSigner = null))
    }

    @Test
    fun aManifestForAnotherPackageIsRefused() {
        assertEquals(ManifestFault.Package, fault(manifest().put("package", "app.bellterm.lab")))
        assertEquals(ManifestFault.Package, fault(manifest().put("package", "app.bellterm")))
    }

    @Test
    fun aMalformedManifestIsRefused() {
        assertEquals(ManifestFault.NotJson, fault("<html>"))
        assertEquals(ManifestFault.Schema, fault(manifest().put("schema", 2)))
        assertEquals(ManifestFault.Field, fault(manifest().apply { remove("signerSha256") }))
        assertEquals(ManifestFault.Field, fault(manifest(candidate = entry(10026, "0.2.0", qa = "maybe"))))
        assertEquals(ManifestFault.Field, fault(manifest(stable = entry(10025, "0.1.1").put("sha256", "12"))))
        assertEquals(ManifestFault.Field, fault(manifest(stable = entry(10025, "0.1.1").put("size", 0))))
        assertEquals(ManifestFault.Field, fault(manifest(stable = entry(10025, "0.1.1").put("size", 512L * 1024 * 1024))))
        assertEquals(ManifestFault.Field, fault(manifest(stable = entry(10025, "0.1.1").put("versionCode", 0))))
        assertEquals(ManifestFault.Field, fault(manifest(stable = entry(10025, "0.1.1").apply { remove("sha256") })))
    }

    @Test
    fun anEntryWithoutEnglishNotesIsNeverOffered() {
        val m = parse(manifest(stable = entry(10025, "0.1.1").put("notes", JSONObject().put("zh", "说明"))))
        assertNull(m.stable)
    }

    @Test
    fun aStableEntryThatDidNotPassQaIsAbsent() {
        assertNull(parse(manifest(stable = entry(10025, "0.1.1", qa = "not_tested"))).stable)
    }

    @Test
    fun aFailedBuildIsListedButNeverInstallable() {
        val failed = entry(10026, "0.2.0", qa = "failed", reason = "Crashes on attach").apply {
            remove("url"); remove("sha256"); remove("size")
        }
        val candidate = parse(manifest(candidate = failed)).candidate!!
        assertEquals("Crashes on attach", candidate.qaReason)
        assertFalse(candidate.installable(installed, 34))
        // Even with a download in the manifest, a Failed build is refused.
        val withFile = parse(manifest(candidate = entry(10026, "0.2.0", qa = "failed"))).candidate!!
        assertFalse(withFile.installable(installed, 34))
    }

    @Test
    fun anOlderOrEqualBuildIsNeverOffered() {
        val stable = parse(manifest()).stable!!
        assertFalse(stable.installable(10025, 34))
        assertFalse(stable.installable(10030, 34))
        assertFalse(stable.installable(installed, 28))
    }

    private val stable get() = parse(manifest()).stable!!

    private fun redirect(from: String, location: String?) = ReleaseChannel.redirect(stable, URI(from), location)

    @Test
    fun theReleaseMayBeHandedToGithubsAssetHosts() {
        val start = asset("0.1.1")
        val cdn = "https://release-assets.githubusercontent.com/github-production-release-asset/1/2?sp=r&sig=abc"
        assertEquals(URI(cdn), redirect(start, cdn))
        assertEquals(URI("https://objects.githubusercontent.com/x/y?token=t"), redirect(start, "https://objects.githubusercontent.com/x/y?token=t"))
        assertEquals(URI("https://objects.githubusercontent.com:443/x"), redirect(start, "https://objects.githubusercontent.com:443/x"))
        // From one asset host on to another, and a relative hop on the asset host.
        assertEquals(URI("https://objects.githubusercontent.com/z"), redirect(cdn, "https://objects.githubusercontent.com/z"))
        assertEquals(URI("https://release-assets.githubusercontent.com/other"), redirect(cdn, "/other"))
    }

    @Test
    fun aRedirectAnywhereElseStopsTheDownload() {
        val start = asset("0.1.1")
        listOf(
            null,
            "",
            "http://objects.githubusercontent.com/x",
            "https://evil.example/x",
            "https://objects.githubusercontent.com.evil.example/x",
            "https://evilobjects.githubusercontent.com/x",
            "https://githubusercontent.com/x",
            "https://raw.githubusercontent.com/erikfrish/herdline/main/x.apk",
            "https://objects.githubusercontent.com:8443/x",
            "https://user:pw@objects.githubusercontent.com/x",
            "https://objects.githubusercontent.com/x#frag",
            // Back to GitHub itself, or a relative hop that stays on github.com.
            "https://github.com/erikfrish/herdline/releases/download/v0.1.0/herdline-0.1.0.apk",
            "/erikfrish/herdline/releases/download/v0.1.0/herdline-0.1.0.apk",
            "javascript:alert(1)",
            "not a url %%",
        ).forEach { location -> assertNull(location.toString(), redirect(start, location)) }
    }

    @Test
    fun aRedirectFromAnywhereButTheReleaseOrAnAssetHostIsNotFollowed() {
        val cdn = "https://objects.githubusercontent.com/x"
        listOf(
            asset("0.1.0"),
            "https://github.com/erikfrish/herdline/releases/download/v0.1.1/herdline-0.1.1.apk?x=1",
            "https://evil.example/x",
            "http://objects.githubusercontent.com/x",
            "https://user@objects.githubusercontent.com/x",
            "https://objects.githubusercontent.com:8443/x",
        ).forEach { from -> assertNull(from, redirect(from, cdn)) }
    }

    private fun verify(
        size: Long = 22_323_163,
        hash: String = sha,
        archive: ArchiveFacts? = ArchiveFacts(pkg, 10025, setOf(signer)),
        installedSigners: Set<String>? = setOf(signer),
        installedVersion: Long = installed,
    ): Mismatch? = ReleaseChannel.verify(stable, pkg, installedVersion, installedSigners, size, hash, archive)

    @Test
    fun aMatchingDownloadPasses() {
        assertNull(verify())
    }

    @Test
    fun aDownloadOfTheWrongSizeIsRefused() {
        assertEquals(Mismatch.Size, verify(size = 22_323_162))
    }

    @Test
    fun aDownloadWithAnotherChecksumIsRefused() {
        assertEquals(Mismatch.Checksum, verify(hash = "cd".repeat(32)))
    }

    @Test
    fun anApkOfAnotherPackageOrUnreadableIsRefused() {
        assertEquals(Mismatch.Package, verify(archive = ArchiveFacts("app.bellterm.lab", 10025, setOf(signer))))
        assertEquals(Mismatch.Package, verify(archive = null))
    }

    @Test
    fun anApkOfAnotherVersionIsRefused() {
        assertEquals(Mismatch.Version, verify(archive = ArchiveFacts(pkg, 10024, setOf(signer))))
        assertEquals(Mismatch.Version, verify(installedVersion = 10025))
    }

    @Test
    fun anApkNotSignedWithTheReleaseKeyAloneIsRefused() {
        assertEquals(Mismatch.Signer, verify(archive = ArchiveFacts(pkg, 10025, setOf("cd".repeat(32)))))
        assertEquals(Mismatch.Signer, verify(archive = ArchiveFacts(pkg, 10025, setOf(signer, "cd".repeat(32)))))
        assertEquals(Mismatch.Signer, verify(archive = ArchiveFacts(pkg, 10025, null)))
        assertEquals(Mismatch.Signer, verify(installedSigners = setOf("cd".repeat(32))))
    }

    @Test
    fun aWrongManifestLeavesCheckNowUsableAndTheNextGoodOneIsUsed() {
        val bad = listOf(
            manifest().put("signerSha256", "cd".repeat(32)) to ManifestFault.Signer,
            manifest().put("package", "app.bellterm.lab") to ManifestFault.Package,
        )
        for ((json, fault) in bad) {
            val refused = ReleaseChannel.outcome(json.toString(), pkg, signer, now = 1)
            assertEquals(ReleaseCheck.Refused(fault), refused)
            assertTrue(refused.canCheck)
        }
        val good = ReleaseChannel.outcome(manifest().toString(), pkg, signer, now = 2)
        assertEquals(10025L, (good as ReleaseCheck.Checked).manifest.stable?.versionCode)
        assertEquals(ReleaseCheck.Failed, ReleaseChannel.outcome("<html>", pkg, signer, now = 3))
        // Only a build without the release key, and a check under way, have no Check now.
        assertFalse(ReleaseCheck.Unavailable.canCheck)
        assertFalse(ReleaseCheck.Checking.canCheck)
    }

    @Test
    fun checkNowEndsARefusalOrAStopButNotADownloadOrAVerifiedFile() {
        val release = stable
        assertEquals(ReleaseInstall.None, ReleaseInstall.Mismatched(release, Mismatch.Checksum).afterCheck())
        assertEquals(ReleaseInstall.None, ReleaseInstall.Mismatched(release, Mismatch.Signer).afterCheck())
        assertEquals(ReleaseInstall.None, ReleaseInstall.Stopped(release, StopReason.Cancelled).afterCheck())
        listOf(
            ReleaseInstall.Downloading(release, 1, 2),
            ReleaseInstall.Verifying(release),
            ReleaseInstall.NeedsPermission(release),
            ReleaseInstall.Handed(release),
            ReleaseInstall.NotInstalled(release),
        ).forEach { assertEquals(it, it.afterCheck()) }
    }

    @Test
    fun aFileWhoseSignerWasRefusedIsNeverOfferedAgain() {
        val release = stable
        assertFalse(release.installable(installed, 34, refused = setOf(sha)))
        assertTrue(release.installable(installed, 34, refused = setOf("cd".repeat(32))))
    }

    @Test
    fun automaticChecksRunDaily() {
        val day = ReleaseChannel.CHECK_INTERVAL
        assertTrue(ReleaseChannel.due(lastCheck = 0, now = day * 100))
        assertFalse(ReleaseChannel.due(lastCheck = day * 100, now = day * 100 + day - 1))
        assertTrue(ReleaseChannel.due(lastCheck = day * 100, now = day * 101))
        // A clock set back since the last check does not postpone the next one.
        assertTrue(ReleaseChannel.due(lastCheck = day * 100, now = day * 99))
    }

    @Test
    fun aCancelledDownloadSettlesItsStateAfterCleaningUpOnAnotherDispatcher() = runBlocking {
        val release = stable
        var state: ReleaseInstall = ReleaseInstall.Downloading(release, 1, 2)
        var cleaned = false
        val download = launch {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                settleAfter(Dispatchers.IO, { cleaned = true }) { state = ReleaseInstall.Stopped(release, StopReason.Cancelled) }
                throw e
            }
        }
        yield()
        download.cancelAndJoin()
        assertTrue(cleaned)
        assertEquals(ReleaseInstall.Stopped(release, StopReason.Cancelled), state)
    }
}
