package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.Attachment
import io.github.erikfrish.herdline.core.AttachmentKind
import io.github.erikfrish.herdline.core.Connection
import io.github.erikfrish.herdline.core.NAME_MAX
import io.github.erikfrish.herdline.core.TranscriptIdentity
import io.github.erikfrish.herdline.core.attachmentKind
import io.github.erikfrish.herdline.core.limit
import io.github.erikfrish.herdline.core.limitFor
import io.github.erikfrish.herdline.core.safeAttachmentName
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.DraftAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentsTest {
    private val alice = TranscriptIdentity("w1:p1", "term_1", 4100, "/h/.omp/agent/sessions/w/a.jsonl")
    private val omp = Connection.ComposerTarget("n", "s", "boot-1", "w1:p1", "omp", alice)

    @Test
    fun aHostileNameNeverLeavesTheStagingDirectory() {
        assertEquals("etc_passwd", safeAttachmentName("../../etc/passwd", AttachmentKind.FILE, null))
        assertEquals("bashrc", safeAttachmentName(".bashrc", AttachmentKind.FILE, null))
        assertEquals("attachment", safeAttachmentName(null, AttachmentKind.FILE, null))
    }

    @Test
    fun anImageEndsInTheExtensionOmpRecognises() {
        assertEquals("photo_heic.png", safeAttachmentName("photo.heic", AttachmentKind.IMAGE, "image/png"))
        assertEquals("cat.jpeg", safeAttachmentName("cat.jpeg", AttachmentKind.IMAGE, "image/jpeg"))
        assertEquals("cat.jpg", safeAttachmentName("cat", AttachmentKind.IMAGE, "image/jpeg"))
    }

    @Test
    fun aLongNameIsCutKeepingItsExtension() {
        val name = safeAttachmentName("a".repeat(200) + ".pdf", AttachmentKind.FILE, "application/pdf")
        assertEquals(NAME_MAX, name.length)
        assertTrue(name.endsWith(".pdf"))
    }

    @Test
    fun onlyImagesOmpCanShowAreSentAsImagesWithTheirLimits() {
        assertEquals(AttachmentKind.IMAGE, attachmentKind("IMAGE/PNG"))
        assertEquals(AttachmentKind.FILE, attachmentKind("image/heic"))
        assertEquals(AttachmentKind.FILE, attachmentKind(null))
        assertEquals(20L * 1024 * 1024, AttachmentKind.IMAGE.limit)
        assertEquals(64L * 1024 * 1024, AttachmentKind.FILE.limit)
        // OpenCode's own 20 MiB per file holds for every kind sent to it, and only to it.
        assertEquals(20L * 1024 * 1024, AttachmentKind.FILE.limitFor("OpenCode"))
        assertEquals(20L * 1024 * 1024, AttachmentKind.IMAGE.limitFor("opencode"))
        assertEquals(64L * 1024 * 1024, AttachmentKind.FILE.limitFor("omp"))
        // Kilo v7.8.27 caps no data: file part: the native limit holds.
        assertEquals(64L * 1024 * 1024, AttachmentKind.FILE.limitFor("kilo"))
    }

    @Test
    fun filesAreOfferedOnlyForAnIdleOmpOpenCodeOrKiloWhoseInstanceWasRead() {
        assertTrue(attachOffered(busy = false, omp, count = 0, max = 10))
        assertTrue(attachOffered(busy = false, omp.copy(agent = "OMP"), count = 9, max = 10))
        assertTrue(attachOffered(busy = false, omp.copy(agent = "opencode"), count = 0, max = 10))
        assertTrue(attachOffered(busy = false, omp.copy(agent = "Kilo"), 0, 10))
        assertFalse(attachOffered(busy = false, omp.copy(agent = "codex"), 0, 10))
        assertFalse(attachOffered(busy = true, omp, 0, 10))
        assertFalse(attachOffered(busy = false, omp.copy(instance = null), 0, 10))
        assertFalse(attachOffered(busy = false, omp.copy(agent = "claude"), 0, 10))
        assertFalse(attachOffered(busy = false, null, 0, 10))
        assertFalse(attachOffered(busy = false, omp, count = 10, max = 10))
    }

    @Test
    fun progressIsUnknownUntilTheProviderGivesASize() {
        assertNull(uploadFraction(512, null))
        assertNull(uploadFraction(512, 0))
        assertEquals(0.5f, uploadFraction(512, 1024)!!, 0f)
        // A provider that understated the size never shows more than done.
        assertEquals(1f, uploadFraction(2048, 1024)!!, 0f)
    }

    @Test
    fun aFailedUploadNeverClaimsACauseThisBuildCannotVouchFor() {
        assertEquals(R.string.attachment_reason_too_large, stagingReasonRes("too_large"))
        assertEquals(R.string.attachment_reason_target_changed, stagingReasonRes("target_changed"))
        assertEquals(R.string.attachment_reason_failed, stagingReasonRes("failed"))
        assertEquals(R.string.attachment_reason_failed, stagingReasonRes("something_new"))
        assertEquals(R.string.reason_attachment_steer, reasonRes("attachment_steer"))
    }

    @Test
    fun aSizeIsNeverShownAtOrUnderALimitItExceeds() {
        val mib = 1024L * 1024
        assertEquals("512 B", fileSizeText(512))
        assertEquals("1.0 KiB", fileSizeText(1024))
        assertEquals("19.9 MiB", fileSizeText(20 * mib - 1))
        assertEquals("20.0 MiB", fileSizeText(20 * mib))
        assertEquals("64.0 MiB", fileSizeText(64 * mib + 1))
        assertEquals("100 MiB", fileSizeText(100 * mib + mib / 2))
    }

    @Test
    fun uploadProgressIsSpokenInQuarterSteps() {
        assertEquals(0, spokenPercent(0.24f))
        assertEquals(25, spokenPercent(0.25f))
        assertEquals(75, spokenPercent(0.99f))
        assertEquals(100, spokenPercent(1f))
    }

    @Test
    fun theMenuNamesTheFirstReasonFilesCantBeAttachedAndOtherwiseMatchesTheGate() {
        assertEquals(AttachBlock.NotConnected, attachBlock(Binding.Offline, sending = true, unsettled = true, null, 10, 10))
        assertEquals(AttachBlock.Held, attachBlock(Binding.Bound, sending = true, unsettled = true, omp, 0, 10))
        assertEquals(AttachBlock.Sending, attachBlock(Binding.Bound, sending = true, unsettled = false, omp, 0, 10))
        assertEquals(AttachBlock.Rebind, attachBlock(Binding.AgentRestarted, false, false, null, 0, 10))
        assertEquals(AttachBlock.NeedsAgent, attachBlock(Binding.InstanceUnknown, false, false, omp, 0, 10))
        assertEquals(AttachBlock.NeedsAgent, attachBlock(Binding.Bound, false, false, omp.copy(agent = "claude"), 0, 10))
        assertEquals(null, attachBlock(Binding.Bound, false, false, omp.copy(agent = "opencode"), 0, 10))
        assertEquals(AttachBlock.Full, attachBlock(Binding.Bound, false, false, omp, 10, 10))
        // Null exactly where the old gate offered the items.
        val targets = listOf(omp, omp.copy(agent = "OMP"), omp.copy(instance = null), omp.copy(agent = "claude"), null)
        for (binding in Binding.entries) for (sending in listOf(false, true)) for (unsettled in listOf(false, true))
            for (target in targets) for (count in listOf(0, 9, 10)) {
                assertEquals(
                    binding == Binding.Bound && attachOffered(sending || unsettled, target, count, 10),
                    attachBlock(binding, sending, unsettled, target, count, 10) == null,
                )
            }
    }

    @Test
    fun theToLineSaysWhatHoldsTheFilesBack() {
        val other = omp.copy(boot = "boot-2")
        fun file(id: String, target: Connection.ComposerTarget? = omp, failure: String? = null) = DraftAttachment(
            id, "content://docs/$id", AttachmentKind.FILE, "$id.txt", 3,
            staged = target?.let { Attachment(AttachmentKind.FILE, "$id.txt", "/s/$id.txt", 3, "00", it) },
            failure = failure,
        )
        assertNull(attachmentsLine(emptyList(), emptySet(), omp))
        assertEquals(AttachmentsLine.Count(2), attachmentsLine(listOf(file("a"), file("b")), emptySet(), omp))
        // The second of three uploads while the first is done.
        assertEquals(
            AttachmentsLine.Uploading(2, 3),
            attachmentsLine(listOf(file("a"), file("b", null), file("c", null)), setOf("b", "c"), omp),
        )
        assertEquals(
            AttachmentsLine.Failed(1),
            attachmentsLine(listOf(file("a"), file("b", null, "failed"), file("c", other)), emptySet(), omp),
        )
        assertEquals(AttachmentsLine.NotHere(2), attachmentsLine(listOf(file("a", other), file("b", null)), emptySet(), omp))
        assertEquals(AttachmentsLine.NotHere(1), attachmentsLine(listOf(file("a")), emptySet(), null))
    }

    @Test
    fun aChipFlagsOnlyARefusalAsAnErrorAndAFileForAnotherPaneAsAMove() {
        val other = omp.copy(pane = "w1:p2")
        fun file(target: Connection.ComposerTarget?, failure: String? = null) = DraftAttachment(
            "a", "content://docs/a", AttachmentKind.FILE, "a.txt", 3,
            staged = target?.let { Attachment(AttachmentKind.FILE, "a.txt", "/s/a.txt", 3, "00", it) },
            failure = failure,
        )
        assertEquals(ChipProblem.None, chipProblem(file(omp), uploading = false, omp))
        assertEquals(ChipProblem.Failed, chipProblem(file(null, "no_exec"), uploading = false, omp))
        // A refusal outranks a copy left for another pane.
        assertEquals(ChipProblem.Failed, chipProblem(file(other, "target_changed"), uploading = false, omp))
        // New, interrupted by process death, or gone from the host.
        assertEquals(ChipProblem.NotUploaded, chipProblem(file(null), uploading = false, omp))
        assertEquals(ChipProblem.OtherTarget, chipProblem(file(other), uploading = false, omp))
        assertEquals(ChipProblem.OtherTarget, chipProblem(file(omp), uploading = false, null))
        // While it uploads, the ring shows instead: an old refusal or copy is being replaced.
        assertEquals(ChipProblem.None, chipProblem(file(other, "failed"), uploading = true, omp))
    }
}
