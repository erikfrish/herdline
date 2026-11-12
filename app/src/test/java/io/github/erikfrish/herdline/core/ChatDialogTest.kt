package io.github.erikfrish.herdline.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
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

    private fun pick(name: String, values: List<String>, multi: Boolean = false, checked: String? = null, extra: String = "") =
        """{"kind":"choice","name":"$name","label":[],"required":false,"multi":$multi,"select":false,"other":false$extra,"options":[${values.joinToString(",") { """{"value":"$it","label":[],"checked":${it == checked},"disabled":false}""" }}]}"""

    private fun conditional(fields: List<String>) = ChatForm.parse(JSONObject("""{"sections":[],"fields":[${fields.joinToString(",")}],"hidden":[]}"""))

    @Test
    fun aFieldIsShownOnlyWhileItsConditionHoldsAndAnInactiveOneIsNeverSent() {
        val kind = pick("kind", listOf("pg", "lite"), checked = "pg")
        val host = """{"kind":"text","name":"host","label":[],"required":true,"type":"text","value":"db.local","when":[{"key":"kind","op":"eq","value":"pg"}]}"""
        val port = """{"kind":"text","name":"port","label":[],"required":false,"type":"integer","value":"5432","when":[{"key":"host","op":"neq","value":""}]}"""
        val file = """{"kind":"text","name":"file","label":[],"required":true,"type":"text","value":"a.db","when":[{"key":"kind","op":"neq","value":"pg"}]}"""
        val form = conditional(listOf(kind, host, port, file))
        val pg = form.defaults()
        assertEquals(setOf("kind", "host", "port"), pg.keys().asSequence().toSet())
        assertEquals(5432L, pg.get("port"))
        // A chain: once host is inactive, port that depends on it is inactive too.
        val lite = form.active(JSONObject().put("kind", "lite").put("host", "x").put("port", 1).put("file", "a.db"))
        assertEquals(setOf("kind", "file"), lite.keys().asSequence().toSet())
        // Unanswered: false for eq and neq alike, so neither branch shows.
        assertEquals(listOf("kind"), form.activeFields(JSONObject()).map { it.name })
    }

    @Test
    fun aConditionMatchesAMultiAnswerByInclusionAndNeverAcrossTypes() {
        val includes = FormCondition("tags", true, "a")
        assertTrue(includes.holds(JSONObject().put("tags", JSONArray(listOf("b", "a")))))
        assertFalse(includes.holds(JSONObject().put("tags", JSONArray())))
        assertFalse(FormCondition("tags", false, "a").holds(JSONObject().put("tags", JSONArray())))
        assertTrue(FormCondition("on", true, true).holds(JSONObject().put("on", "true")))
        assertFalse(FormCondition("on", false, true).holds(JSONObject().put("on", "maybe")))
        assertFalse(FormCondition("n", false, 3.0).holds(JSONObject().put("n", "three")))
        assertTrue(FormCondition("n", true, 3.0).holds(JSONObject().put("n", 3)))
    }

    @Test
    fun aMultiChoiceKeepsItsCountBoundsAndAnIntegerTakesOnlyWholeNumbers() {
        val form = conditional(listOf(pick("tags", listOf("a", "b", "c"), multi = true, extra = ""","minItems":2,"maxItems":2"""),
            """{"kind":"text","name":"n","label":[],"required":true,"type":"integer","value":""}"""))
        val tags = form.fields[0] as FormField.Choice
        assertNotNull(formFieldError(tags, JSONArray(listOf("a"))))
        assertNull(formFieldError(tags, JSONArray(listOf("a", "b"))))
        assertNotNull(formFieldError(tags, JSONArray(listOf("a", "b", "c"))))
        val n = form.fields[1] as FormField.Text
        assertNotNull(formFieldError(n, "2.5"))
        assertNull(formFieldError(n, 2L))
    }

    @Test
    fun aQuestionChatCannotShowWholeIsRefusedInWordsAndStillDeclinable() {
        val dialog = ChatDialog.parse(JSONObject("""{"dialogId":"frm_1","kind":"form","title":"Deploy","submittable":false,"cancellable":true,"unsupported":{"reason":"field","subject":"file upload"}}"""))
        assertNull(dialog.form)
        assertTrue(dialog.cancellable)
        assertEquals(FormUnsupported("field", "file upload"), dialog.unsupported)
        val link = ChatForm.parse(JSONObject("""{"sections":[{"title":[],"blocks":[{"kind":"link","url":"https://example.org/x","label":[{"text":"Docs"}]}]}],"fields":[],"hidden":[]}"""))
        val block = link.sections.single().blocks.single() as FormBlock.Link
        assertEquals("https://example.org/x" to "Docs", block.url to formText(block.label))
    }
}
