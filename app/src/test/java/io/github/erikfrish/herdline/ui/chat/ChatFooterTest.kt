package io.github.erikfrish.herdline.ui.chat

import io.github.erikfrish.herdline.core.TranscriptEntry
import io.github.erikfrish.herdline.core.TranscriptKind
import io.github.erikfrish.herdline.core.TranscriptUsage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.util.Locale

class ChatFooterTest {
    private fun entry(id: String, parent: String?, offset: Long, role: String, model: String? = null, cost: Double? = null) = TranscriptEntry(
        id, parent, offset, offset + 10, TranscriptKind.Message, role, emptyList(),
        usage = cost?.let { TranscriptUsage(100, 20, 120, it) }, model = model, provider = model?.let { "sample-provider" },
    )

    @Test fun footerUsesTheCurrentBranchAndNeverAddsReplyCosts() {
        val entries = listOf(
            entry("user", null, 0, "user"),
            entry("old", "user", 10, "assistant", "old-model", 9.0),
            entry("old-user", "old", 20, "user"),
            entry("answer", "user", 30, "assistant", "new-model", 0.125),
            entry("next", "answer", 40, "user", "untrusted-user-model", 99.0),
        )
        val data = chatFooterData(entries)
        assertEquals("new-model", data.model)
        assertEquals("sample-provider", data.provider)
        assertEquals(0.125, data.usage?.cost)
        assertEquals(120L, data.usage?.totalTokens)
    }

    @Test fun missingMetadataOnTheLatestAssistantDoesNotBorrowAnOldModelOrCost() {
        val entries = listOf(entry("old", null, 0, "assistant", "old-model", 1.0), entry("latest", "old", 10, "assistant"))
        assertEquals(ChatFooterData(null, null, null), chatFooterData(entries))
        assertEquals(ChatFooterData(null, null, null), chatFooterData(emptyList()))
    }

    @Test fun unknownOrInvalidCostIsNotShownAsZero() {
        for (cost in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -1.0)) assertNull(replyCost(cost, Locale.US))
        assertEquals("$0.00", replyCost(0.0, Locale.US))
        assertNotEquals("$0.00", replyCost(0.0001, Locale.US))
    }
}
