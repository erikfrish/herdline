package io.github.erikfrish.herdline.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class ChatDialogTest {
    private val choice = """{"kind":"choice","name":"db","label":[],"required":true,"multi":false,"select":false,"other":true,"options":[{"value":"sqlite","label":[{"text":"SQLite"}],"checked":true,"disabled":false},{"value":"redis","label":[{"text":"Redis"}],"checked":false,"disabled":false}]}"""
    private val count = """{"kind":"text","name":"days","label":[{"text":"Days"}],"required":true,"type":"number","multiline":false,"value":"30","placeholder":"","min":1,"max":90,"rows":1}"""
    private fun form() = ChatForm.parse(JSONObject("""{"sections":[{"title":[{"text":"Storage"}],"blocks":[{"kind":"text","heading":false,"spans":[{"text":"Choose a database"}]},{"kind":"field","field":$choice},{"kind":"field","field":$count}]}],"fields":[$choice,$count],"hidden":[["mode","private"]]}"""))

    @Test
    fun normalizedFormKeepsContextDefaultsAndTheSameFieldsInDocumentOrder() {
        val form = form()
        assertEquals("Choose a database", formText((form.sections.single().blocks.first() as FormBlock.Text).spans))
        assertSame(form.fields[0], (form.sections.single().blocks[1] as FormBlock.Field).field)
        val data = form.defaults()
        assertEquals("sqlite", data.getString("db"))
        assertEquals(30.0, data.getDouble("days"), 0.0)
        assertEquals("private", data.getString("mode"))
        val radio = form.fields[0] as FormField.Choice
        assertEquals("custom database", choiceValue(radio, emptyList(), " custom database "))
        assertNotNull(formFieldError(radio, null))
        val number = form.fields[1] as FormField.Text
        assertNotNull(formFieldError(number, 0))
        assertNotNull(formFieldError(number, "NaN"))
        assertNull(formFieldError(number, 90))
    }

    @Test
    fun recordedAnswerPreservesUnselectedOptionsAndAnExplicitSelection() {
        val answer = ChatFormAnswer.parse(JSONObject("""{"name":"db","question":"Database?","answer":"Redis","options":[{"label":"SQLite","selected":false},{"label":"Redis","selected":true}]}"""))
        assertEquals("Redis", answer.answer)
        assertEquals(listOf(false, true), answer.options.map { it.selected })
        assertEquals(listOf("SQLite", "Redis"), answer.options.map { it.label })
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateNamedFieldsAreRejectedRatherThanSendingAnAmbiguousAnswer() {
        ChatForm.parse(JSONObject("""{"sections":[],"fields":[$choice,$choice],"hidden":[]}"""))
    }
}
