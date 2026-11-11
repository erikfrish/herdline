package io.github.erikfrish.herdline.e2e

import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.erikfrish.herdline.data.LockDetail
import io.github.erikfrish.herdline.ui.session.PinSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The pin sheet (Parent I1 ruling): a user who saved
 * Hidden and never pinned starts on Hidden, and nothing is chosen until Pin.
 */
class PinSheetTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSavedHiddenIsPreselectedAndNothingIsWrittenUntilPin() {
        val pinned = mutableListOf<LockDetail>()
        compose.setContent {
            PinSheet(
                live = null,
                saved = LockDetail.Hidden,
                nodeId = "n",
                node = "lab",
                pane = "w1:p1",
                agent = "omp",
                onPin = { pinned += it },
                onDismiss = {},
            )
        }
        compose.onNodeWithTag("pin:level:Hidden").assertIsSelected()
        compose.onNodeWithTag("pin:level:StateOnly").assertIsNotSelected()
        compose.onNodeWithTag("pin:level:Full").performClick()
        compose.onNodeWithTag("pin:level:Full").assertIsSelected()
        compose.waitForIdle()
        assertTrue(pinned.isEmpty())
        compose.onNodeWithTag("pin:confirm").performClick()
        compose.waitForIdle()
        assertEquals(listOf(LockDetail.Full), pinned)
    }
}
