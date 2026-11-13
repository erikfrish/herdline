package io.github.erikfrish.herdline.ui.session

import io.github.erikfrish.herdline.core.ComposerCheck
import io.github.erikfrish.herdline.data.AttachmentsState
import io.github.erikfrish.herdline.data.Binding
import io.github.erikfrish.herdline.data.Draft
import io.github.erikfrish.herdline.data.SentRecord
import io.github.erikfrish.herdline.data.SentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Insert's + menu item names the gate that holds it, and only an open Insert shows its help. */
class InsertBlockTest {
    private val written = Draft(text = "fix the build")
    private val ok = ComposerCheck.Gate(true, null)

    private fun block(
        sending: Boolean = false,
        withdrawing: Boolean = false,
        draft: Draft = written,
        binding: Binding = Binding.Bound,
        attachments: AttachmentsState = AttachmentsState.None,
        insert: ComposerCheck.Gate? = ok,
        desktopElsewhere: Boolean = false,
    ) = insertBlock(sending, withdrawing, draft, binding, attachments, insert, desktopElsewhere)

    @Test
    fun anOpenInsertHasNoReason() {
        assertNull(block())
        assertNull(block(attachments = AttachmentsState.Ready))
    }

    @Test
    fun eachLocalGateNamesItself() {
        assertEquals(InsertBlock.Sending, block(sending = true))
        val held = written.copy(sent = SentRecord(written.text, "r1", submit = false, SentState.Uncertain, at = 1))
        assertEquals(InsertBlock.Held, block(draft = held))
        assertEquals(InsertBlock.Withdrawing, block(withdrawing = true))
        for (binding in Binding.entries - Binding.Bound) assertEquals(InsertBlock.Unbound(binding), block(binding = binding))
        assertEquals(InsertBlock.Empty, block(draft = Draft()))
        assertEquals(InsertBlock.Uploading, block(attachments = AttachmentsState.Uploading))
        for (state in listOf(AttachmentsState.Failed, AttachmentsState.NotUploaded, AttachmentsState.OtherTarget)) {
            assertEquals(InsertBlock.FilesNotReady, block(attachments = state))
        }
        assertEquals(InsertBlock.Unchecked, block(insert = null))
    }

    @Test
    fun theNativeRefusalIsShownAndOffDesktopOnlyForNotVisibleElsewhere() {
        val notVisible = ComposerCheck.Gate(false, "not_visible")
        assertEquals(InsertBlock.OffDesktop, block(insert = notVisible, desktopElsewhere = true))
        assertEquals(InsertBlock.Refused("not_visible"), block(insert = notVisible))
        assertEquals(InsertBlock.Refused("scrolled"), block(insert = ComposerCheck.Gate(false, "scrolled"), desktopElsewhere = true))
    }

    @Test
    fun anInFlightDeliveryReadsAsSendingNotLocked() {
        // The draft is unsettled from the tap; the band shows it sending, not held (QA HB2-w).
        val inFlight = written.copy(sent = SentRecord(written.text, "", submit = false, SentState.Queued, at = 1))
        assertEquals(InsertBlock.Sending, block(sending = true, draft = inFlight))
        assertEquals(InsertBlock.Held, block(draft = inFlight, withdrawing = true))
    }
}
