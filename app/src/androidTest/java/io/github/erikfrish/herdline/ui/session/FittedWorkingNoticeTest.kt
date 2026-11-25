package io.github.erikfrish.herdline.ui.session

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FittedWorkingNoticeTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun gateChangesDoNotMoveHistoryButTheKeyboardStillDoes() {
        val allowed = mutableStateOf(false)
        val keyboardShown = mutableStateOf(true)
        var inserts = 0
        lateinit var working: String
        lateinit var steering: String
        compose.setContent {
            HerdlineTheme(dark = false) {
                working = stringResource(R.string.reason_agent_working)
                steering = stringResource(R.string.steer_help_short)
                val action = if (allowed.value) NoticeAction(
                    stringResource(R.string.composer_insert_no_enter), tag = "composer:insert_no_enter",
                ) { inserts++ } else null
                Column(Modifier.width(320.dp).height(if (keyboardShown.value) 320.dp else 400.dp)) {
                    Box(Modifier.fillMaxWidth().weight(1f).testTag("history"))
                    FittedWorkingNotice(
                        text = if (allowed.value) steering else working,
                        alternatives = listOf(working, steering),
                        insert = action,
                        accent = Color.Blue,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val history = compose.onNodeWithTag("history")
        val insert = compose.onNodeWithTag("composer:insert_no_enter")
        val before = history.getUnclippedBoundsInRoot()
        compose.onNodeWithText(working).assertExists()
        insert.assertDoesNotExist()

        compose.runOnIdle { allowed.value = true }
        compose.onNodeWithText(steering).assertExists()
        insert.assertIsEnabled()
        assertEquals(before, history.getUnclippedBoundsInRoot())
        compose.runOnIdle { assertEquals(0, inserts) }
        insert.performClick()
        compose.runOnIdle { assertEquals(1, inserts); allowed.value = false }
        insert.assertDoesNotExist()
        assertEquals(before, history.getUnclippedBoundsInRoot())

        compose.runOnIdle { keyboardShown.value = false }
        val after = history.getUnclippedBoundsInRoot()
        assertEquals(80f, (after.bottom - before.bottom).value, 1f)
        compose.runOnIdle { assertEquals(1, inserts) }
    }
}
