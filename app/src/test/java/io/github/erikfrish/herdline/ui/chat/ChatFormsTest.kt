package io.github.erikfrish.herdline.ui.chat

import androidx.compose.runtime.saveable.SaverScope
import io.github.erikfrish.herdline.core.ChatForm
import io.github.erikfrish.herdline.core.FormField
import io.github.erikfrish.herdline.core.FormOption
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFormsTest {
    private fun option(value: String, checked: Boolean = false, disabled: Boolean = false) = FormOption(value, emptyList(), checked, disabled)

    private val radio = FormField.Choice("pick", emptyList(), required = false, multi = false, select = false, other = true,
        options = listOf(option("a"), option("b", checked = true)))
    private val checks = FormField.Choice("tags", emptyList(), required = false, multi = true, select = false, other = true,
        options = listOf(option("x", checked = true), option("y"), option("z", checked = true, disabled = true)))
    private fun text(name: String, type: String, value: String = "") =
        FormField.Text(name, emptyList(), required = false, type = type, multiline = false, value = value, placeholder = "",
            min = null, max = null, pattern = null, minLength = null, maxLength = null, rows = 1)
    private val count = text("count", "number", "3")
    private val secret = text("token", "password")
    private val form = ChatForm(emptyList(), listOf(radio, checks, count, secret), listOf("mode" to "plan"))

    @Test
    fun `defaults submit OMP's shape with hidden values and typed numbers`() {
        val data = formData(form, initialAnswers(form))
        assertEquals("plan", data.getString("mode"))
        assertEquals("b", data.getString("pick"))
        // The checked but disabled option is never sent.
        assertEquals(listOf("x"), data.getJSONArray("tags").strings())
        assertEquals(3.0, data.get("count") as Double, 0.0)
        assertEquals("", data.getString("token"))
    }

    @Test
    fun `an empty array field keeps its empty array and an unanswered radio is left out`() {
        val answers = initialAnswers(form).with(radio, ChoiceDraft(emptyList())).with(checks, ChoiceDraft(emptyList()))
        val data = formData(form, answers)
        assertFalse(data.has("pick"))
        assertEquals(0, data.getJSONArray("tags").length())
    }

    @Test
    fun `Other replaces the radio choice and joins the checked values`() {
        val answers = initialAnswers(form)
            .with(radio, ChoiceDraft(emptyList(), otherOn = true, otherText = " mine "))
            .with(checks, ChoiceDraft(listOf("x", "y"), otherOn = true, otherText = "w"))
        val data = formData(form, answers)
        assertEquals("mine", data.getString("pick"))
        assertEquals(listOf("x", "y", "w"), data.getJSONArray("tags").strings())
    }

    @Test
    fun `saved state never holds a password and restores it blank`() {
        val saver = answersSaver(secretFields(form))
        val answers = initialAnswers(form).with(secret, "hunter2").with(count, "7")
        val saved = with(saver) { SaverScope { true }.save(answers) }!!
        assertFalse(saved.contains("hunter2"))
        assertFalse(saved.contains("token"))
        val restored = saver.restore(saved)!!
        assertEquals("", restored.text(secret))
        assertEquals("7", restored.text(count))
        assertTrue(restored.choice(checks).selected == listOf("x"))
    }

    private fun JSONArray.strings() = (0 until length()).map(::getString)
}
