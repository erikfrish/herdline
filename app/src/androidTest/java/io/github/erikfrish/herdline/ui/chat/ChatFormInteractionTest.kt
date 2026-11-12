package io.github.erikfrish.herdline.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import io.github.erikfrish.herdline.core.ChatDialog
import io.github.erikfrish.herdline.core.ChatFormAnswer
import io.github.erikfrish.herdline.core.ChatAnswerOption
import io.github.erikfrish.herdline.core.visibleDialogs
import io.github.erikfrish.herdline.ui.theme.HerdlineTheme
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.File

/** One visible question, one explicit answer; non-submittable snapshots never offer Submit. */
class ChatFormInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun anOuterQuestionSubmitsOtherAndHiddenValuesWhileItsChildIsHidden() {
        val choice = """{"kind":"choice","name":"store","label":[],"required":true,"multi":false,"select":false,"other":true,"options":[{"value":"sqlite","label":[{"text":"SQLite"}],"checked":true,"disabled":false},{"value":"redis","label":[{"text":"Redis"}],"checked":false,"disabled":false}]}"""
        val form = """{"sections":[{"title":[{"text":"Store"}],"blocks":[{"kind":"field","field":$choice}]}],"fields":[$choice],"hidden":[["mode","private"]]}"""
        val outer = ChatDialog.parse(JSONObject("""{"dialogId":"outer","kind":"ask","title":"Database","submittable":true,"form":$form}"""))
        val child = ChatDialog.parse(JSONObject("""{"dialogId":"child","parentId":"outer","kind":"formDialog","submittable":true,"title":"Duplicate question","form":$form}"""))
        var current by mutableStateOf(listOf(outer, child))
        var answer: JSONObject? = null
        compose.setContent {
            HerdlineTheme(dark = true) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    visibleDialogs(current).forEach { dialog ->
                        ChatFormCard(dialog, chatColors(Color(0xFF57C6BC)), false, null, { submitted, _ -> answer = submitted })
                    }
                }
            }
        }
        compose.onNodeWithText("Database").assertIsDisplayed()
        compose.onNodeWithText("Store").assertIsDisplayed()
        compose.onNodeWithText("store").assertDoesNotExist()
        compose.onNodeWithText("Duplicate question").assertDoesNotExist()
        compose.runOnIdle { assertNull(answer) }
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithTag("chat:form:other").performTextReplacement("mongo")
        compose.onNodeWithTag("chat:form:submit").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals("mongo", answer!!.getJSONObject("data").getString("store"))
            assertEquals("private", answer!!.getJSONObject("data").getString("mode"))
            current = listOf(ChatDialog.parse(JSONObject(outer.raw.toString()).put("submittable", false)))
        }
        compose.onNodeWithTag("chat:form:submit").assertDoesNotExist()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = requireNotNull(UiDevice.getInstance(instrumentation).takeScreenshot())
        val target = File(instrumentation.targetContext.getExternalFilesDir(null), "e2e/forms-read-only.png")
        target.parentFile!!.mkdirs()
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }

    @Test fun recordedChoicesShowTheirSelectionOnceAndKeepOtherText() {
        val answers = listOf(
            ChatFormAnswer("checks", "Checks", "Lint", listOf(ChatAnswerOption("Lint", true), ChatAnswerOption("Tests", false))),
            ChatFormAnswer("tone", "Tone", "Blue", listOf(ChatAnswerOption("Red", false), ChatAnswerOption("Blue", true))),
            ChatFormAnswer("colour", "Colour", "Other: custom", listOf(ChatAnswerOption("Green", false), ChatAnswerOption("Other", true))),
        )
        compose.setContent {
            HerdlineTheme(dark = false) {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ChatFormAnswers(answers, false, chatColors(Color(0xFF00696A)))
                }
            }
        }
        compose.onAllNodesWithText("Lint", useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Blue", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithText("Tests", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Red", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Other: custom", useUnmergedTree = true).assertExists()
    }
}
