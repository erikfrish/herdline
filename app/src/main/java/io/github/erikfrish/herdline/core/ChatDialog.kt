package io.github.erikfrish.herdline.core

import org.json.JSONArray
import org.json.JSONObject

/** OMP's parsed form, not a second interpretation of its HTML. */
data class ChatForm(val sections: List<FormSection>, val fields: List<FormField>, val hidden: List<Pair<String, String>>) {
    fun defaults(): JSONObject = active(JSONObject().also { data ->
        hidden.forEach { (name, value) -> data.put(name, value) }
        fields.forEach { field ->
            when (field) {
                is FormField.Text -> data.put(field.name, textValue(field, field.value))
                is FormField.Choice -> choiceValue(field, field.options.filter { it.checked && !it.disabled }.map { it.value })?.let { data.put(field.name, it) }
            }
        }
    })

    /** The fields whose conditions hold for [data], in order; an inactive one is neither shown, checked nor sent. */
    fun activeFields(data: JSONObject): List<FormField> {
        val answered = JSONObject()
        return fields.filter { field ->
            field.conditions.all { it.holds(answered) }.also { active ->
                if (active && data.has(field.name)) answered.put(field.name, data.get(field.name))
            }
        }
    }

    /** [data] without the answers of inactive fields. */
    fun active(data: JSONObject): JSONObject {
        val kept = activeFields(data).mapTo(HashSet()) { it.name }
        fields.filter { it.name !in kept }.forEach { data.remove(it.name) }
        return data
    }

    companion object {
        fun parse(json: JSONObject): ChatForm {
            val fields = json.getJSONArray("fields").objects().map(FormField::parse)
            require(fields.map { it.name }.distinct().size == fields.size) { "Duplicate form field" }
            val sections = json.getJSONArray("sections").objects().map { section ->
                FormSection(spans(section.optJSONArray("title")), section.getJSONArray("blocks").objects().map { block ->
                    when (val kind = block.getString("kind")) {
                        "text" -> FormBlock.Text(spans(block.optJSONArray("spans")), block.optBoolean("heading"))
                        "list" -> FormBlock.ListItems(block.getJSONArray("items").arrays().map(::spans), block.optBoolean("ordered"))
                        "pre" -> FormBlock.Pre(block.getString("text"))
                        "table" -> FormBlock.Table(block.getJSONArray("rows").arrays().map { row -> row.arrays().map(::spans) }, block.optInt("headerRows"))
                        "field" -> {
                            val name = block.getJSONObject("field").getString("name")
                            FormBlock.Field(fields.firstOrNull { it.name == name } ?: error("Unknown form field"))
                        }
                        "image" -> FormBlock.Picture(block.getString("src"), block.optString("alt"))
                        "link" -> FormBlock.Link(block.getString("url"), spans(block.optJSONArray("label")))
                        "svg" -> FormBlock.Svg(block.getString("markup"), block.optString("alt"))
                        else -> error("Unsupported form block: $kind")
                    }
                })
            }
            val hidden = json.optJSONArray("hidden")?.arrays().orEmpty().map { pair ->
                require(pair.length() == 2)
                pair.getString(0) to pair.getString(1)
            }
            return ChatForm(sections, fields, hidden)
        }
    }
}

/**
 * One visibility condition (OpenCode schema/form.ts:28-41): an unanswered or
 * inactive referenced field makes it false for eq and neq alike; against a
 * multi answer eq is "includes"; a value of another type matches neither way.
 */
data class FormCondition(val key: String, val equal: Boolean, val value: Any) {
    fun holds(answered: JSONObject): Boolean {
        val answer = answered.opt(key)?.takeUnless { it == JSONObject.NULL } ?: return false
        val hit = when (answer) {
            is JSONArray -> if (answer.length() == 0 || value !is String) return false else (0 until answer.length()).any { answer.opt(it) == value }
            is String -> when {
                answer.isBlank() -> return false
                value is String -> answer == value
                // Booleans arrive as a yes/no choice valued "true"/"false".
                value is Boolean && (answer == "true" || answer == "false") -> answer.toBoolean() == value
                value is Double && answer.toDoubleOrNull() != null -> answer.toDouble() == value
                else -> return false
            }
            is Boolean -> if (value is Boolean) answer == value else return false
            is Number -> if (value is Double) answer.toDouble() == value else return false
            else -> return false
        }
        return hit == equal
    }

    companion object {
        fun parse(json: JSONObject): FormCondition {
            val equal = when (json.getString("op")) {
                "eq" -> true
                "neq" -> false
                else -> error("Unsupported condition")
            }
            val value = when (val raw = json.get("value")) {
                is String, is Boolean -> raw
                is Number -> raw.toDouble().also { require(it.isFinite()) }
                else -> error("Unsupported condition value")
            }
            return FormCondition(json.getString("key"), equal, value)
        }
    }
}

data class FormSpan(val text: String, val style: String?)
data class FormOption(val value: String, val label: List<FormSpan>, val checked: Boolean, val disabled: Boolean)
data class FormSection(val title: List<FormSpan>, val blocks: List<FormBlock>)

data class ChatAnswerOption(val label: String, val selected: Boolean)
/** Display-only answers OMP wrote after a form settled; password values are masked by the host. */
data class ChatFormAnswer(val name: String, val question: String, val answer: String, val options: List<ChatAnswerOption> = emptyList()) {
    companion object {
        fun parse(json: JSONObject) = ChatFormAnswer(
            json.optString("name"), json.getString("question"), json.getString("answer"),
            json.optJSONArray("options")?.objects().orEmpty().map { ChatAnswerOption(it.getString("label"), it.getBoolean("selected")) },
        )
    }
}

sealed interface FormBlock {
    data class Text(val spans: List<FormSpan>, val heading: Boolean) : FormBlock
    data class ListItems(val items: List<List<FormSpan>>, val ordered: Boolean) : FormBlock
    data class Pre(val text: String) : FormBlock
    data class Table(val rows: List<List<List<FormSpan>>>, val headerRows: Int) : FormBlock
    data class Field(val field: FormField) : FormBlock
    data class Picture(val src: String, val alt: String) : FormBlock
    /** A page the question points to; opened only as http(s). */
    data class Link(val url: String, val label: List<FormSpan>) : FormBlock
    data class Svg(val markup: String, val alt: String) : FormBlock
}

sealed interface FormField {
    val name: String
    val label: List<FormSpan>
    val required: Boolean
    val help: List<FormSpan>
    val conditions: List<FormCondition>

    data class Choice(
        override val name: String,
        override val label: List<FormSpan>,
        override val required: Boolean,
        val multi: Boolean,
        val select: Boolean,
        val other: Boolean,
        val options: List<FormOption>,
        val minItems: Int? = null,
        val maxItems: Int? = null,
        override val help: List<FormSpan> = emptyList(),
        override val conditions: List<FormCondition> = emptyList(),
    ) : FormField {
        val arrayValue: Boolean get() = multi && options.size > 1
    }

    data class Text(
        override val name: String,
        override val label: List<FormSpan>,
        override val required: Boolean,
        val type: String,
        val multiline: Boolean,
        val value: String,
        val placeholder: String,
        val min: Double?,
        val max: Double?,
        val pattern: String?,
        val minLength: Int?,
        val maxLength: Int?,
        val rows: Int,
        override val help: List<FormSpan> = emptyList(),
        override val conditions: List<FormCondition> = emptyList(),
    ) : FormField

    companion object {
        fun parse(json: JSONObject): FormField {
            val name = json.getString("name")
            val label = spans(json.optJSONArray("label"))
            val required = json.optBoolean("required")
            val help = spans(json.optJSONArray("help"))
            val conditions = json.optJSONArray("when")?.objects().orEmpty().map(FormCondition::parse)
            return when (json.getString("kind")) {
                "choice" -> Choice(name, label, required, json.optBoolean("multi"), json.optBoolean("select"), json.optBoolean("other"),
                    json.getJSONArray("options").objects().map { option ->
                        FormOption(option.getString("value"), spans(option.optJSONArray("label")), option.optBoolean("checked"), option.optBoolean("disabled"))
                    }, json.integer("minItems"), json.integer("maxItems"), help, conditions)
                "text" -> Text(name, label, required, json.getString("type"), json.optBoolean("multiline"),
                    json.optString("value"), json.optString("placeholder"), json.number("min"), json.number("max"),
                    json.optString("pattern").takeIf { it.isNotEmpty() }, json.integer("minLength"), json.integer("maxLength"), json.optInt("rows", 1).coerceIn(1, 20),
                    help, conditions)
                else -> error("Unsupported form field")
            }
        }
    }
}

fun formText(spans: List<FormSpan>): String = spans.joinToString("") { it.text }

/** Mirrors OMP's data shape; the host validates again when the user explicitly submits. */
fun choiceValue(field: FormField.Choice, selected: List<String>, other: String? = null): Any? {
    val values = field.options.filter { !it.disabled && it.value in selected }.map { it.value }
    val typed = other?.trim()?.takeIf { field.other && it.isNotEmpty() }
    return if (field.arrayValue) JSONArray(values + listOfNotNull(typed)) else typed ?: values.firstOrNull()
}

fun textValue(field: FormField.Text, text: String): Any {
    if ((field.type == "number" || field.type == "range") && text.isNotBlank()) {
        text.toDoubleOrNull()?.takeIf { it.isFinite() }?.let { return it }
    }
    if (field.type == "integer" && text.isNotBlank()) {
        text.trim().toLongOrNull()?.let { return it }
    }
    return text
}

/** Error shown locally; OMP remains the authoritative validator, including JS pattern semantics. */
fun formFieldError(field: FormField, value: Any?): String? {
    if (field is FormField.Choice) {
        val count = when {
            value == null || value == JSONObject.NULL -> 0
            value is JSONArray -> value.length()
            else -> 1
        }
        if (count == 0) return if (field.required) "Required" else null
        if (field.minItems != null && count < field.minItems) return "Pick at least ${field.minItems}"
        if (field.maxItems != null && count > field.maxItems) return "Pick at most ${field.maxItems}"
        return null
    }
    field as FormField.Text
    val text = if (value == null || value == JSONObject.NULL) "" else value.toString()
    if (text.isBlank()) return if (field.required) "Required" else null
    if (field.type == "number" || field.type == "range" || field.type == "integer") {
        val number = text.trim().toDoubleOrNull()?.takeIf { it.isFinite() } ?: return if (field.type == "integer") "Enter a whole number" else "Enter a number"
        if (field.type == "integer" && text.trim().toLongOrNull() == null) return "Enter a whole number"
        if (field.min != null && number < field.min) return "Must be at least ${field.min}"
        if (field.max != null && number > field.max) return "Must be at most ${field.max}"
    }
    if (field.minLength != null && text.length < field.minLength) return "Needs at least ${field.minLength} characters"
    if (field.maxLength != null && text.length > field.maxLength) return "At most ${field.maxLength} characters"
    if (field.type == "email" && !Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(text)) return "Enter an email address"
    if (field.type == "url" && runCatching { java.net.URI(text).isAbsolute }.getOrDefault(false).not()) return "Enter a full URL"
    return null
}

/** Why a question arrived without a form: the host could not show it whole. */
data class FormUnsupported(val reason: String, val subject: String)

/** Snapshot identity is retained even when this host cannot render the dialog's kind. */
data class ChatDialog(val id: String, val kind: String, val title: String, val form: ChatForm?, val raw: JSONObject) {
    val parentId: String? get() = raw.optString("parentId").takeIf { it.isNotBlank() && !raw.isNull("parentId") }
    val submittable: Boolean get() = raw.optBoolean("submittable", false)
    /** The host takes a decline for this id. */
    val cancellable: Boolean get() = raw.optBoolean("cancellable", false)
    val unsupported: FormUnsupported? get() = raw.optJSONObject("unsupported")?.let { FormUnsupported(it.optString("reason"), it.optString("subject")) }
    companion object {
        fun parse(json: JSONObject): ChatDialog {
            val id = json.getString("dialogId")
            require(id.isNotBlank())
            return ChatDialog(id, json.getString("kind"), json.optString("title"), json.optJSONObject("form")?.let(ChatForm::parse), json)
        }
    }
}

/** Nested host surfaces represent the same question; only the outer open dialog is shown. */
fun visibleDialogs(dialogs: List<ChatDialog>): List<ChatDialog> {
    val ids = dialogs.mapTo(HashSet()) { it.id }
    return dialogs.filter { it.parentId !in ids }
}

private fun spans(array: JSONArray?): List<FormSpan> = array?.objects().orEmpty().map { FormSpan(it.getString("text"), it.optString("style").takeIf(String::isNotEmpty)) }
private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
private fun JSONArray.arrays(): List<JSONArray> = (0 until length()).map { getJSONArray(it) }
private fun JSONObject.number(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key).also { require(it.isFinite()) } else null
private fun JSONObject.integer(key: String): Int? = if (has(key) && !isNull(key)) getInt(key) else null
