package io.github.erikfrish.herdline.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R
import io.github.erikfrish.herdline.core.ChatDialog
import io.github.erikfrish.herdline.core.ChatForm
import io.github.erikfrish.herdline.core.ChatFormAnswer
import io.github.erikfrish.herdline.core.FormBlock
import io.github.erikfrish.herdline.core.FormField
import io.github.erikfrish.herdline.core.FormOption
import io.github.erikfrish.herdline.core.FormSpan
import io.github.erikfrish.herdline.core.MediaRefusal
import io.github.erikfrish.herdline.core.choiceValue
import io.github.erikfrish.herdline.core.formFieldError
import io.github.erikfrish.herdline.core.formText
import io.github.erikfrish.herdline.core.textValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** The host's result codes for a form answer that was not taken (`submitError`). */
internal const val FORM_NOT_OPEN = "not_open"
internal const val FORM_INVALID = "invalid"
internal const val FORM_KIND_MISMATCH = "kind_mismatch"

/** One choice field's draft: the picked option values, and the "Other" answer when [otherOn]. */
internal data class ChoiceDraft(val selected: List<String>, val otherOn: Boolean = false, val otherText: String = "")

/** A form's draft: per field name, the choice state or the typed text. */
internal data class FormAnswers(val choices: Map<String, ChoiceDraft>, val texts: Map<String, String>) {
    fun choice(field: FormField.Choice): ChoiceDraft = choices[field.name] ?: ChoiceDraft(emptyList())
    fun text(field: FormField.Text): String = texts[field.name] ?: field.value
    fun with(field: FormField.Choice, draft: ChoiceDraft) = copy(choices = choices + (field.name to draft))
    fun with(field: FormField.Text, text: String) = copy(texts = texts + (field.name to text))
}

/** The form as OMP opened it: checked options and given values, never a disabled option. */
internal fun initialAnswers(form: ChatForm): FormAnswers = FormAnswers(
    form.fields.filterIsInstance<FormField.Choice>().associate { field ->
        field.name to ChoiceDraft(field.options.filter { it.checked && !it.disabled }.map { it.value })
    },
    form.fields.filterIsInstance<FormField.Text>().associate { it.name to it.value },
)

/** [field]'s value in OMP's shape, through the parent's helpers. */
internal fun fieldValue(field: FormField, answers: FormAnswers): Any? = when (field) {
    is FormField.Text -> textValue(field, answers.text(field))
    is FormField.Choice -> answers.choice(field).let { choiceValue(field, it.selected, it.otherText.takeIf { _ -> it.otherOn }) }
}

/**
 * The submitted `data`: hidden values, every text field, and each choice
 * as [choiceValue] gives it. An array field keeps its `[]` when nothing is
 * picked; a single choice with no answer is left out, as a browser leaves
 * an unchecked radio group out of its form data.
 */
internal fun formData(form: ChatForm, answers: FormAnswers): JSONObject = JSONObject().also { data ->
    form.hidden.forEach { (name, value) -> data.put(name, value) }
    form.fields.forEach { field ->
        fieldValue(field, answers)?.let { data.put(field.name, it) }
    }
}

/** The local reason per field name; OMP validates again on submit. */
internal fun formErrors(form: ChatForm, answers: FormAnswers): Map<String, String> =
    form.fields.mapNotNull { field -> formFieldError(field, fieldValue(field, answers))?.let { field.name to it } }.toMap()

/** [value] without a trailing ".0" when it is whole. */
internal fun plainNumber(value: Double): String =
    if (value == Math.floor(value) && Math.abs(value) < 1e15) value.toLong().toString() else value.toString()

/** Names of [form]'s password fields, whose text never leaves memory. */
internal fun secretFields(form: ChatForm): Set<String> =
    form.fields.filter { it is FormField.Text && it.type == "password" }.map { it.name }.toSet()

/**
 * The draft as saved state, without the [secret] fields: no value and no
 * length of a password is written; they come back blank.
 */
internal fun answersSaver(secret: Set<String>) = Saver<FormAnswers, String>(
    save = { answers ->
        JSONObject()
            .put("choices", JSONObject().also { all ->
                answers.choices.forEach { (name, draft) ->
                    all.put(name, JSONObject().put("selected", JSONArray(draft.selected)).put("otherOn", draft.otherOn).put("otherText", draft.otherText))
                }
            })
            .put("texts", JSONObject(answers.texts.filterKeys { it !in secret }))
            .toString()
    },
    restore = { saved ->
        val json = JSONObject(saved)
        val choices = json.getJSONObject("choices")
        val texts = json.getJSONObject("texts")
        FormAnswers(
            choices.keys().asSequence().associateWith { name ->
                val draft = choices.getJSONObject(name)
                val selected = draft.getJSONArray("selected")
                ChoiceDraft((0 until selected.length()).map(selected::getString), draft.getBoolean("otherOn"), draft.getString("otherText"))
            },
            texts.keys().asSequence().associateWith(texts::getString) + secret.associateWith { "" },
        )
    },
)

private val CardShape = RoundedCornerShape(16.dp)

/**
 * An agent's question as a native form: every section, its context and its
 * fields, with an explicit Submit that hands `{data}` to [onSubmit]. Nothing
 * is sent on selection, by a key, or by a default. The draft lives per
 * [ChatDialog.id], so a new dialog never inherits an old answer, and it
 * survives a failed submit. While [submitting], or once the host says the
 * dialog is no longer open, the form shows the answer but takes no input.
 */
@Composable
fun ChatFormCard(
    dialog: ChatDialog,
    colors: ChatColors,
    submitting: Boolean,
    submitError: String?,
    onSubmit: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    FormFrame(colors, modifier.testTag("chat:form")) {
        Text(
            stringResource(R.string.form_from_agent),
            style = ChatType.label.copy(color = colors.muted),
        )
        val title = dialog.title.ifBlank { stringResource(R.string.form_untitled) }
        Text(title, style = ChatType.heading2.copy(color = colors.text), modifier = Modifier.semantics { heading() })
        val form = dialog.form
        if (form == null) {
            Unsupported(dialog.kind, colors)
        } else {
            FormEditor(dialog.id, form, colors, submitting, submitError, onSubmit, dialog.submittable)
        }
    }
}

/**
 * A settled form as OMP recorded it: each question with its answer marked
 * as the chosen one, read-only. A password answer arrives masked by OMP and
 * is shown as it came. [timedOut]: the form closed before an answer was sent.
 */
@Composable
fun ChatFormAnswers(answers: List<ChatFormAnswer>, timedOut: Boolean, colors: ChatColors, modifier: Modifier = Modifier) {
    FormFrame(colors, modifier.testTag("chat:form:answers")) {
        Text(
            stringResource(if (timedOut) R.string.form_timed_out_label else R.string.form_answered),
            style = ChatType.label.copy(color = if (timedOut) colors.blocked else colors.done),
        )
        if (timedOut) Notice(stringResource(R.string.form_timed_out), colors, warn = true, modifier = Modifier.testTag("chat:form:timed-out"))
        answers.forEach { AnswerRow(it, colors) }
    }
}

@Composable
private fun AnswerRow(answer: ChatFormAnswer, colors: ChatColors) {
    val question = answer.question.ifBlank { answer.name }
    val empty = answer.answer.isBlank()
    val spoken = if (empty) {
        stringResource(R.string.form_answer_none_spoken, question)
    } else {
        stringResource(R.string.form_answer_spoken, question, answer.answer)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = ChatSpace.block)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .testTag("chat:form:answer:${answer.name}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(question, style = ChatType.body.copy(color = colors.text, fontWeight = FontWeight.Medium))
        answer.options.forEach { option ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                if (option.selected) {
                    Icon(Icons.Filled.CheckCircle, null, tint = colors.accentText, modifier = Modifier.size(20.dp))
                } else {
                    Box(Modifier.size(20.dp).border(1.dp, colors.dim, CircleShape))
                }
                Spacer(Modifier.width(12.dp))
                Text(option.label, style = ChatType.body.copy(color = if (option.selected) colors.text else colors.muted), modifier = Modifier.weight(1f))
            }
        }
        Row(Modifier.heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically) {
            if (empty) {
                Text(stringResource(R.string.form_no_answer), style = ChatType.body.copy(color = colors.muted, fontStyle = FontStyle.Italic))
            } else {
                Icon(Icons.Filled.CheckCircle, null, tint = colors.accentText, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(answer.answer, style = ChatType.body.copy(color = colors.text), modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun FormFrame(colors: ChatColors, modifier: Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .widthIn(max = ProseMaxWidth)
            .fillMaxWidth()
            .clip(CardShape)
            .background(colors.surf)
            .border(1.dp, colors.border, CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(ChatSpace.block),
    ) { content() }
}

@Composable
private fun Unsupported(kind: String, colors: ChatColors) {
    Text(stringResource(R.string.form_kind, kind), style = ChatType.meta.copy(color = colors.muted))
    Notice(stringResource(R.string.form_unsupported), colors, warn = true, modifier = Modifier.testTag("chat:form:unsupported"))
}

@Composable
private fun FormEditor(
    id: String,
    form: ChatForm,
    colors: ChatColors,
    submitting: Boolean,
    submitError: String?,
    onSubmit: (JSONObject) -> Unit,
    submittable: Boolean,
) {
    val saver = remember(form) { answersSaver(secretFields(form)) }
    var answers by rememberSaveable(id, stateSaver = saver) { mutableStateOf(initialAnswers(form)) }
    var tried by rememberSaveable(id) { mutableStateOf(false) }
    var touched by rememberSaveable(id) { mutableStateOf(listOf<String>()) }
    val closed = submitError == FORM_NOT_OPEN
    val editable = submittable && !submitting && !closed
    val errors = formErrors(form, answers).filterKeys { tried || it in touched }
    FormContent(form, answers, colors, editable, errors) { change ->
        answers = change.answers
        if (change.name !in touched) touched = touched + change.name
    }
    if (submitError != null) {
        val text = when (submitError) {
            FORM_NOT_OPEN -> stringResource(R.string.form_result_not_open)
            FORM_INVALID -> stringResource(R.string.form_result_invalid)
            FORM_KIND_MISMATCH -> stringResource(R.string.form_result_kind_mismatch)
            else -> stringResource(R.string.form_result_failed, submitError)
        }
        Notice(text, colors, warn = closed, modifier = Modifier.testTag("chat:form:result"))
    }
    if (tried && errors.isNotEmpty() && editable) {
        Text(
            pluralStringResource(R.plurals.form_fix_fields, errors.size, errors.size),
            style = ChatType.meta.copy(color = colors.err),
        )
    }
    if (submittable && !closed) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = {
                    tried = true
                    if (formErrors(form, answers).isEmpty()) onSubmit(JSONObject().put("data", formData(form, answers)))
                },
                enabled = editable,
                colors = ButtonDefaults.buttonColors(containerColor = colors.accentText, contentColor = colors.bg),
                modifier = Modifier.heightIn(min = 48.dp).testTag("chat:form:submit"),
            ) {
                Text(stringResource(if (submitError != null) R.string.form_submit_again else R.string.form_submit), style = ChatType.button)
            }
            if (submitting) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
                Text(stringResource(R.string.form_sending), style = ChatType.meta.copy(color = colors.muted))
            }
        }
    }
}

/** One edit: the whole new draft, and the field it came from. */
private class FormChange(val name: String, val answers: FormAnswers)

/** Every section's blocks in order, then any field no block placed, so none is lost. */
@Composable
private fun FormContent(
    form: ChatForm,
    answers: FormAnswers,
    colors: ChatColors,
    editable: Boolean,
    errors: Map<String, String>,
    onChange: (FormChange) -> Unit,
) {
    val placed = remember(form) { form.sections.flatMap { s -> s.blocks.filterIsInstance<FormBlock.Field>().map { it.field.name } }.toSet() }
    val field = @Composable { f: FormField ->
        FieldView(f, answers, colors, editable, errors[f.name]) { onChange(FormChange(f.name, it)) }
    }
    form.sections.forEach { section ->
        if (section.title.isNotEmpty() && formText(section.title).isNotBlank()) {
            Text(
                spanned(section.title, colors),
                style = ChatType.heading2.copy(color = colors.text),
                modifier = Modifier.padding(top = ChatSpace.block).semantics { heading() },
            )
        }
        section.blocks.forEach { block ->
            when (block) {
                is FormBlock.Text -> Text(
                    spanned(block.spans, colors),
                    style = (if (block.heading) ChatType.heading2 else ChatType.prose(false)).copy(color = colors.text),
                    modifier = if (block.heading) Modifier.semantics { heading() } else Modifier,
                )
                is FormBlock.ListItems -> FormList(block, colors)
                is FormBlock.Pre -> CodeBlock(block.text, colors)
                is FormBlock.Table -> FormTable(block, colors)
                is FormBlock.Field -> field(block.field)
                is FormBlock.Picture -> FormPicture(block, colors)
                is FormBlock.Svg -> FormSvg(block, colors)
            }
        }
    }
    form.fields.filterNot { it.name in placed }.forEach { field(it) }
}

/** OMP's span styles: `code`, `strong`, `em`, alone or together. */
private fun spanned(spans: List<FormSpan>, colors: ChatColors): AnnotatedString = buildAnnotatedString {
    spans.forEach { span ->
        val styles = span.style?.split(' ', ',')?.filter(String::isNotBlank).orEmpty()
        var style = SpanStyle()
        if ("code" in styles) style = style.merge(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = ChatType.inlineCode, background = colors.codeChip))
        if ("strong" in styles) style = style.merge(SpanStyle(fontWeight = FontWeight.SemiBold))
        if ("em" in styles) style = style.merge(SpanStyle(fontStyle = FontStyle.Italic))
        withStyle(style) { append(span.text) }
    }
}

@Composable
private fun FormList(block: FormBlock.ListItems, colors: ChatColors) {
    val body = ChatType.prose(false).copy(color = colors.text)
    val markerLine = with(LocalDensity.current) { body.lineHeight.toDp() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        block.items.forEachIndexed { index, item ->
            Row {
                Box(Modifier.width(if (block.ordered) 28.dp else 16.dp).heightIn(min = markerLine), contentAlignment = Alignment.CenterStart) {
                    if (block.ordered) {
                        Text("${index + 1}.", style = body.copy(color = colors.muted))
                    } else {
                        Box(Modifier.size(4.8.dp).clip(CircleShape).background(colors.muted))
                    }
                }
                Text(spanned(item, colors), style = body)
            }
        }
    }
}

/**
 * A table in a hairline outline. Wider than the card, it scrolls sideways;
 * cells keep one line each so columns stay aligned, and nothing is cut.
 */
@Composable
private fun FormTable(block: FormBlock.Table, colors: ChatColors) {
    val shape = MaterialTheme.shapes.small
    val columns = block.rows.maxOfOrNull { it.size } ?: 0
    if (columns == 0) return
    val count = block.rows.size
    Box(Modifier.clip(shape).border(1.dp, colors.border, shape).testTag("chat:form:table")) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            for (column in 0 until columns) {
                Column(Modifier.width(IntrinsicSize.Max)) {
                    for (row in 0 until count) {
                        val cell = block.rows[row].getOrNull(column).orEmpty()
                        val header = row < block.headerRows
                        val rule = colors.border
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 30.dp)
                                .drawBehind {
                                    if (row < count - 1) drawLine(rule, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                spanned(cell.map { it.copy(text = it.text.replace('\n', ' ')) }, colors),
                                style = ChatType.table.copy(color = colors.text, fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal),
                                softWrap = false,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A form image as Herdline can show it: decoded from an inline `data:` URI only, never fetched. */
internal sealed interface FormMedia {
    data object Loading : FormMedia
    data class Shown(val bitmap: Bitmap) : FormMedia
    /** Not a `data:` URI: showing it would mean a network or file read. */
    data object External : FormMedia
    data object Vector : FormMedia
    data object TooLarge : FormMedia
    data object Unsupported : FormMedia
}

/** The longest edge and the most pixels of a decoded form image. */
private const val FORM_IMAGE_EDGE = 1024
private const val FORM_IMAGE_PIXELS = 1024L * 1024

/** [src] decoded within the form image bounds. Call it off the main thread. */
internal fun formMedia(src: String): FormMedia {
    if (!src.startsWith("data:", ignoreCase = true)) return FormMedia.External
    val comma = src.indexOf(',')
    if (comma < 0) return FormMedia.Unsupported
    val meta = src.substring(5, comma).lowercase().split(';').map(String::trim)
    if (meta.first() == "image/svg+xml") return FormMedia.Vector
    if (!meta.first().startsWith("image/") || "base64" !in meta) return FormMedia.Unsupported
    val payload = src.substring(comma + 1).filterNot(Char::isWhitespace)
    return when (val load = decodeImage(payload, FORM_IMAGE_EDGE, FORM_IMAGE_PIXELS)) {
        is ImageLoad.Shown -> FormMedia.Shown(load.bitmap)
        is ImageLoad.Failed -> if (load.refusal == MediaRefusal.TooLarge) FormMedia.TooLarge else FormMedia.Unsupported
        ImageLoad.Loading -> FormMedia.Unsupported
    }
}

@Composable
private fun FormPicture(block: FormBlock.Picture, colors: ChatColors) {
    val media by produceState<FormMedia>(FormMedia.Loading, block.src) {
        value = withContext(Dispatchers.Default) { formMedia(block.src) }
    }
    val alt = block.alt.takeIf(String::isNotBlank)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("chat:form:image")) {
        when (val now = media) {
            FormMedia.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = colors.accent)
            }
            is FormMedia.Shown -> Image(
                now.bitmap.asImageBitmap(),
                alt ?: stringResource(R.string.chat_image),
                Modifier
                    .fillMaxWidth()
                    .aspectRatio((now.bitmap.width.toFloat() / now.bitmap.height).coerceAtLeast(0.5f))
                    .clip(MaterialTheme.shapes.small),
                contentScale = ContentScale.Fit,
            )
            else -> Notice(
                stringResource(
                    when (now) {
                        FormMedia.External -> R.string.form_image_external
                        FormMedia.Vector -> R.string.form_svg_unsupported
                        FormMedia.TooLarge -> R.string.form_image_too_large
                        else -> R.string.form_image_unsupported
                    },
                ),
                colors,
                warn = true,
                modifier = Modifier.testTag("chat:form:media-error"),
            )
        }
        if (alt != null) Text(stringResource(R.string.form_media_alt, alt), style = ChatType.meta.copy(color = colors.muted))
    }
}

/** SVG is not drawn: no renderer here runs without a WebView. Its description and source stay readable. */
@Composable
private fun FormSvg(block: FormBlock.Svg, colors: ChatColors) {
    var source by rememberSaveable(block.markup) { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.testTag("chat:form:svg")) {
        Notice(stringResource(R.string.form_svg_unsupported), colors, warn = true, modifier = Modifier.testTag("chat:form:media-error"))
        block.alt.takeIf(String::isNotBlank)?.let {
            Text(stringResource(R.string.form_media_alt, it), style = ChatType.meta.copy(color = colors.muted))
        }
        Text(
            stringResource(if (source) R.string.form_svg_hide_source else R.string.form_svg_show_source),
            style = ChatType.action.copy(color = colors.accentText),
            modifier = Modifier
                .heightIn(min = 48.dp)
                .clickable(role = Role.Button) { source = !source }
                .padding(vertical = 14.dp),
        )
        if (source) CodeBlock(block.markup, colors, "svg")
    }
}

@Composable
private fun Notice(text: String, colors: ChatColors, warn: Boolean, modifier: Modifier = Modifier) {
    Text(
        text,
        style = ChatType.aside.copy(color = colors.text),
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .background(if (warn) colors.warnBg else colors.errBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    )
}

@Composable
private fun FieldLabel(field: FormField, colors: ChatColors) {
    val label = formText(field.label).ifBlank { field.name }
    val required = stringResource(R.string.form_required)
    val spoken = if (field.required) stringResource(R.string.form_label_required_spoken, label) else label
    Row(
        Modifier.semantics(mergeDescendants = true) { contentDescription = spoken; heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (field.label.isEmpty()) AnnotatedString(label) else spanned(field.label, colors),
            style = ChatType.body.copy(color = colors.text, fontWeight = FontWeight.Medium),
            modifier = Modifier.weight(1f, fill = false),
        )
        if (field.required) {
            Spacer(Modifier.width(8.dp))
            Text(required, style = ChatType.meta.copy(color = colors.muted))
        }
    }
}

@Composable
private fun FieldView(
    field: FormField,
    answers: FormAnswers,
    colors: ChatColors,
    editable: Boolean,
    error: String?,
    onChange: (FormAnswers) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(top = ChatSpace.block).testTag("chat:form:field:${field.name}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FieldLabel(field, colors)
        when (field) {
            is FormField.Choice -> {
                val draft = answers.choice(field)
                val set = { next: ChoiceDraft -> onChange(answers.with(field, next)) }
                when {
                    field.multi -> Checks(field, draft, colors, editable, set)
                    field.select -> Select(field, draft, colors, editable, set)
                    else -> Radios(field, draft, colors, editable, set)
                }
                if (field.other && draft.otherOn) {
                    OtherField(draft, colors, editable) { text -> set(draft.copy(otherOn = true, otherText = text)) }
                }
                if (error != null) Text(error, style = ChatType.meta.copy(color = colors.err))
            }
            is FormField.Text -> TextInput(field, answers.text(field), colors, editable, error) { onChange(answers.with(field, it)) }
        }
    }
}

private fun optionLabel(option: FormOption): List<FormSpan> =
    option.label.takeIf { formText(it).isNotBlank() } ?: listOf(FormSpan(option.value, null))

@Composable
private fun Radios(field: FormField.Choice, draft: ChoiceDraft, colors: ChatColors, editable: Boolean, set: (ChoiceDraft) -> Unit) {
    val radio = RadioButtonDefaults.colors(selectedColor = colors.accentText, unselectedColor = colors.muted)
    Column(Modifier.selectableGroup()) {
        field.options.forEach { option ->
            val chosen = !draft.otherOn && option.value in draft.selected
            OptionRow(
                spanned(optionLabel(option), colors), colors, enabled = !option.disabled,
                Modifier.selectable(chosen, enabled = editable && !option.disabled, role = Role.RadioButton) {
                    set(ChoiceDraft(listOf(option.value), otherOn = false, otherText = draft.otherText))
                },
            ) { RadioButton(chosen, onClick = null, enabled = !option.disabled, colors = radio) }
        }
        if (field.other) {
            OptionRow(
                AnnotatedString(stringResource(R.string.form_other)), colors, enabled = true,
                Modifier.selectable(draft.otherOn, enabled = editable, role = Role.RadioButton) {
                    set(draft.copy(selected = emptyList(), otherOn = true))
                },
            ) { RadioButton(draft.otherOn, onClick = null, colors = radio) }
        }
    }
}

@Composable
private fun Checks(field: FormField.Choice, draft: ChoiceDraft, colors: ChatColors, editable: Boolean, set: (ChoiceDraft) -> Unit) {
    val box = CheckboxDefaults.colors(checkedColor = colors.accentText, uncheckedColor = colors.muted, checkmarkColor = colors.bg)
    Column {
        field.options.forEach { option ->
            val chosen = option.value in draft.selected
            OptionRow(
                spanned(optionLabel(option), colors), colors, enabled = !option.disabled,
                Modifier.toggleable(chosen, enabled = editable && !option.disabled, role = Role.Checkbox) { on ->
                    // Kept in the options' order, as OMP's form data would list them.
                    val next = field.options.map { it.value }.filter { if (it == option.value) on else it in draft.selected }
                    set(draft.copy(selected = next))
                },
            ) { Checkbox(chosen, onCheckedChange = null, enabled = !option.disabled, colors = box) }
        }
        if (field.other) {
            OptionRow(
                AnnotatedString(stringResource(R.string.form_other)), colors, enabled = true,
                Modifier.toggleable(draft.otherOn, enabled = editable, role = Role.Checkbox) { on -> set(draft.copy(otherOn = on)) },
            ) { Checkbox(draft.otherOn, onCheckedChange = null, colors = box) }
        }
    }
}

@Composable
private fun OptionRow(label: AnnotatedString, colors: ChatColors, enabled: Boolean, modifier: Modifier, control: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        control()
        Spacer(Modifier.width(12.dp))
        Text(label, style = ChatType.body.copy(color = if (enabled) colors.text else colors.dim), modifier = Modifier.weight(1f))
    }
}

/** A long single-choice list as a dropdown; "No answer" clears an optional one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Select(field: FormField.Choice, draft: ChoiceDraft, colors: ChatColors, editable: Boolean, set: (ChoiceDraft) -> Unit) {
    var expanded by rememberSaveable(field.name) { mutableStateOf(false) }
    val other = stringResource(R.string.form_other)
    val shown = when {
        draft.otherOn -> other
        else -> field.options.firstOrNull { it.value in draft.selected }?.let { formText(optionLabel(it)) }.orEmpty()
    }
    ExposedDropdownMenuBox(expanded = expanded && editable, onExpandedChange = { if (editable) expanded = it }) {
        OutlinedTextField(
            shown, {},
            readOnly = true,
            placeholder = { Text(stringResource(R.string.form_choose)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            textStyle = ChatType.body,
            colors = fieldColors(colors),
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, editable),
        )
        ExposedDropdownMenu(expanded = expanded && editable, onDismissRequest = { expanded = false }) {
            if (!field.required) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.form_no_answer), color = colors.muted) },
                    onClick = { set(draft.copy(selected = emptyList(), otherOn = false)); expanded = false },
                )
            }
            field.options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(spanned(optionLabel(option), colors)) },
                    enabled = !option.disabled,
                    onClick = { set(draft.copy(selected = listOf(option.value), otherOn = false)); expanded = false },
                )
            }
            if (field.other) {
                DropdownMenuItem(
                    text = { Text(other) },
                    onClick = { set(draft.copy(selected = emptyList(), otherOn = true)); expanded = false },
                )
            }
        }
    }
}

@Composable
private fun OtherField(draft: ChoiceDraft, colors: ChatColors, editable: Boolean, onText: (String) -> Unit) {
    OutlinedTextField(
        draft.otherText, onText,
        readOnly = !editable,
        label = { Text(stringResource(R.string.form_other_answer)) },
        textStyle = ChatType.body,
        colors = fieldColors(colors),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(start = 36.dp).testTag("chat:form:other"),
    )
}

@Composable
private fun TextInput(field: FormField.Text, text: String, colors: ChatColors, editable: Boolean, error: String?, onText: (String) -> Unit) {
    val numeric = field.type == "number" || field.type == "range"
    val hint = when {
        !numeric -> null
        field.min != null && field.max != null -> stringResource(R.string.form_between, plainNumber(field.min), plainNumber(field.max))
        field.min != null -> stringResource(R.string.form_at_least, plainNumber(field.min))
        field.max != null -> stringResource(R.string.form_at_most, plainNumber(field.max))
        else -> null
    }
    val counter = field.maxLength?.let { stringResource(R.string.form_count, text.length, it) }
    val supporting = listOfNotNull(error, hint, counter).joinToString(" · ").takeIf(String::isNotEmpty)
    OutlinedTextField(
        text, onText,
        readOnly = !editable,
        isError = error != null,
        placeholder = if (field.placeholder.isNotBlank()) {
            { Text(field.placeholder) }
        } else {
            null
        },
        supportingText = if (supporting != null) {
            { Text(supporting) }
        } else {
            null
        },
        textStyle = if (field.multiline) ChatType.prose(false) else ChatType.body,
        colors = fieldColors(colors),
        singleLine = !field.multiline,
        minLines = if (field.multiline) field.rows.coerceAtLeast(2) else 1,
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                numeric -> KeyboardType.Decimal
                field.type == "email" -> KeyboardType.Email
                field.type == "url" -> KeyboardType.Uri
                field.type == "tel" -> KeyboardType.Phone
                field.type == "password" -> KeyboardType.Password
                else -> KeyboardType.Text
            },
            // A key never submits: single lines move on, a multiline field takes new lines.
            imeAction = if (field.multiline) ImeAction.Default else ImeAction.Next,
        ),
        visualTransformation = if (field.type == "password") PasswordVisualTransformation() else VisualTransformation.None,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun fieldColors(colors: ChatColors): TextFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = colors.text,
    unfocusedTextColor = colors.text,
    focusedBorderColor = colors.accentText,
    unfocusedBorderColor = colors.border,
    cursorColor = colors.accentText,
    focusedPlaceholderColor = colors.muted,
    unfocusedPlaceholderColor = colors.muted,
    focusedLabelColor = colors.accentText,
    unfocusedLabelColor = colors.muted,
    focusedSupportingTextColor = colors.muted,
    unfocusedSupportingTextColor = colors.muted,
    errorBorderColor = colors.err,
    errorSupportingTextColor = colors.err,
    errorLabelColor = colors.err,
    errorCursorColor = colors.err,
)
