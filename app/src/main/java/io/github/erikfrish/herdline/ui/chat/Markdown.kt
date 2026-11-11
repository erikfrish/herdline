package io.github.erikfrish.herdline.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.erikfrish.herdline.R

/** The Markdown an agent writes, reduced to what reads well on a phone. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    /** A fenced block; one still being written has no closing fence yet and shows as code all the same. */
    data class Code(val language: String, val code: String) : MdBlock
    /** A pipe table: a header row, a `---` separator, then [rows], each padded to the header's width. */
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock
    data object Rule : MdBlock
}

private val HEADING = Regex("^(#{1,6})\\s+(.*)$")
private val BULLET = Regex("^(\\s*)([-*+]|\\d{1,3}[.)])\\s+(.*)$")
private val RULE = Regex("^\\s*([-*_])(\\s*\\1){2,}\\s*$")
private val TABLE_SEPARATOR = Regex("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$")

private fun cells(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

fun markdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val paragraph = StringBuilder()
    fun flush() {
        if (paragraph.isNotBlank()) blocks += MdBlock.Paragraph(paragraph.toString().trim())
        paragraph.clear()
    }
    val lines = text.lines()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val fence = line.trimStart()
        if (fence.startsWith("```") || fence.startsWith("~~~")) {
            flush()
            val marker = fence.take(3)
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith(marker)) code += lines[index++]
            blocks += MdBlock.Code(fence.drop(3).trim(), code.joinToString("\n"))
            index++
            continue
        }
        // A table needs its separator line; pipes without one stay a paragraph.
        if (line.trimStart().startsWith("|") && index + 1 < lines.size && TABLE_SEPARATOR.matches(lines[index + 1])) {
            flush()
            val header = cells(line)
            val rows = mutableListOf<List<String>>()
            index += 2
            while (index < lines.size && lines[index].trimStart().startsWith("|")) {
                val row = cells(lines[index++])
                rows += List(header.size) { row.getOrElse(it) { "" } }
            }
            blocks += MdBlock.Table(header, rows)
            continue
        }
        val heading = HEADING.matchEntire(line)
        val bullet = BULLET.matchEntire(line)
        when {
            line.isBlank() -> flush()
            heading != null -> { flush(); blocks += MdBlock.Heading(heading.groupValues[1].length, heading.groupValues[2]) }
            RULE.matches(line) -> { flush(); blocks += MdBlock.Rule }
            bullet != null -> {
                flush()
                val marker = bullet.groupValues[2].let { if (it[0].isDigit()) it else "•" }
                blocks += MdBlock.Bullet(marker, bullet.groupValues[3], bullet.groupValues[1].length / 2)
            }
            line.startsWith(">") -> { flush(); blocks += MdBlock.Quote(line.removePrefix(">").trimStart()) }
            else -> paragraph.append(line).append('\n')
        }
        index++
    }
    flush()
    return blocks
}

/** Whether a link target may open the browser: `http` and `https` only. */
fun openableLink(target: String): Boolean =
    target.startsWith("https://", ignoreCase = true) || target.startsWith("http://", ignoreCase = true)

/** Marks inline code in [inlineMarkdown]'s result; [InlineText] draws its chip. */
const val INLINE_CODE = "chat:code"

/**
 * `code`, **bold**, *italic* and [links](…) inside a line. A link opens the
 * browser only for http(s), styled by [link]; any other target stays its
 * plain label. Code is styled by [code] and annotated [INLINE_CODE].
 */
fun inlineMarkdown(text: String, code: SpanStyle, link: TextLinkStyles? = null): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val c = text[i]
        val closeAt = { token: String -> text.indexOf(token, i + token.length).takeIf { it > i + token.length - 1 } }
        when {
            c == '`' -> {
                val end = closeAt("`")
                if (end != null) {
                    pushStringAnnotation(INLINE_CODE, "")
                    withStyle(code) { append(text, i + 1, end) }
                    pop()
                    i = end + 1
                } else { append(c); i++ }
            }
            text.startsWith("**", i) -> {
                val end = closeAt("**")
                if (end != null && end > i + 2) { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text, i + 2, end) }; i = end + 2 } else { append(c); i++ }
            }
            (c == '*' || c == '_') && i + 1 < text.length && !text[i + 1].isWhitespace() -> {
                val end = closeAt(c.toString())
                if (end != null && end > i + 1 && (c == '*' || text.getOrNull(end + 1)?.isLetterOrDigit() != true)) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(text, i + 1, end) }; i = end + 1
                } else { append(c); i++ }
            }
            c == '[' -> {
                val close = text.indexOf("](", i)
                val end = if (close > i) text.indexOf(')', close) else -1
                if (close > i && end > close) {
                    val target = text.substring(close + 2, end).trim()
                    if (link != null && openableLink(target)) {
                        withLink(LinkAnnotation.Url(target, link)) { append(text, i + 1, close) }
                    } else {
                        append(text, i + 1, close)
                    }
                    i = end + 1
                } else { append(c); i++ }
            }
            else -> { append(c); i++ }
        }
    }
}

/** A table column reads as identifiers when every body cell is one word of path or name characters. */
private val IDENTIFIER = Regex("^[\\w./:@#-]+$")

/**
 * Text whose [INLINE_CODE] ranges sit on a chip (§16.3): codeChip fill, a
 * 1 dp border and 4 dp corners, drawn behind the glyphs line by line so a
 * wrapped span gets one chip per line.
 */
@Composable
private fun InlineText(
    text: AnnotatedString,
    style: TextStyle,
    colors: ChatColors,
    modifier: Modifier = Modifier,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
) {
    val chips = remember(text) { text.getStringAnnotations(INLINE_CODE, 0, text.length) }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fill = colors.codeChip
    val stroke = colors.border
    val chipped = if (chips.isEmpty()) modifier else modifier.drawBehind {
        val laid = layout?.takeIf { it.layoutInput.text == text } ?: return@drawBehind
        val pad = 2.dp.toPx()
        val inset = 2.dp.toPx()
        val width = 1.dp.toPx()
        val corner = CornerRadius(4.dp.toPx())
        chips.filter { it.end > it.start }.forEach { chip ->
            val first = laid.getLineForOffset(chip.start)
            val last = laid.getLineForOffset(chip.end - 1)
            for (line in first..last) {
                val left = if (line == first) laid.getBoundingBox(chip.start).left else laid.getLineLeft(line)
                val right = if (line == last) laid.getBoundingBox(chip.end - 1).right else laid.getLineRight(line)
                val top = laid.getLineTop(line) + inset
                val bottom = laid.getLineBottom(line) - inset
                val origin = Offset(left - pad, top)
                val box = Size(right - left + 2 * pad, bottom - top)
                drawRoundRect(fill, origin, box, corner)
                drawRoundRect(stroke, origin + Offset(width / 2, width / 2), Size(box.width - width, box.height - width), corner, Stroke(width))
            }
        }
    }
    Text(text, style = style, softWrap = softWrap, maxLines = maxLines, onTextLayout = { layout = it }, modifier = chipped)
}

@Composable
fun Markdown(text: String, colors: ChatColors, modifier: Modifier = Modifier, color: Color = colors.text) {
    val blocks = remember(text) { markdownBlocks(text) }
    val code = SpanStyle(fontFamily = FontFamily.Monospace, fontSize = ChatType.inlineCode, color = colors.text)
    val link = TextLinkStyles(SpanStyle(color = colors.accentText, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline))
    val body = ChatType.body.copy(color = color)
    SelectionContainer(modifier) {
        Column {
            blocks.forEachIndexed { index, block ->
                val previous = blocks.getOrNull(index - 1)
                val top = when {
                    previous == null -> 0.dp
                    block is MdBlock.Heading && block.level == 1 -> 12.dp
                    previous is MdBlock.Heading -> 4.dp
                    previous is MdBlock.Bullet && block is MdBlock.Bullet -> 4.dp
                    previous is MdBlock.Quote && block is MdBlock.Quote -> 0.dp
                    else -> ChatSpace.block
                }
                val gap = Modifier.padding(top = top)
                // Prose keeps a readable line; code and tables keep the full width.
                val prose = gap.widthIn(max = ProseMaxWidth)
                when (block) {
                    is MdBlock.Paragraph -> InlineText(inlineMarkdown(block.text, code, link), body, colors, prose)
                    is MdBlock.Heading -> InlineText(
                        inlineMarkdown(block.text, code, link),
                        (if (block.level == 1) ChatType.heading1 else ChatType.heading2).copy(color = color),
                        colors,
                        prose,
                    )
                    is MdBlock.Bullet -> Row(prose.padding(start = (block.depth * 16).dp)) {
                        // A hanging indent: wrapped lines run under the text, not under the dot.
                        Box(Modifier.width(16.dp).height(22.dp), contentAlignment = Alignment.CenterStart) {
                            if (block.marker == "•") {
                                Box(Modifier.size(4.8.dp).clip(CircleShape).background(colors.muted))
                            } else {
                                Text(block.marker, style = body.copy(color = colors.muted))
                            }
                        }
                        InlineText(inlineMarkdown(block.text, code, link), body, colors)
                    }
                    is MdBlock.Quote -> Row(prose.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.rule))
                        Spacer(Modifier.width(11.dp))
                        InlineText(inlineMarkdown(block.text, code, link), ChatType.quote.copy(color = colors.muted), colors)
                    }
                    is MdBlock.Code -> CodeBlock(block.code, colors, block.language, gap)
                    is MdBlock.Table -> Table(block, colors, code, link, gap)
                    MdBlock.Rule -> Box(prose.padding(vertical = 12.dp).fillMaxWidth().height(1.dp).background(colors.border))
                }
            }
        }
    }
}

/** Whether a code block starts cut to [CODE_COLLAPSED_LINES]: past [CODE_COLLAPSE_OVER] lines, a trailing newline not counted. */
fun codeStartsCollapsed(code: String): Boolean = code.trimEnd('\n').lines().size > CODE_COLLAPSE_OVER

/**
 * A code block: a bar with the language and Copy, then the code, which
 * scrolls sideways and never wraps. Past [CODE_COLLAPSE_OVER] lines it starts
 * cut to [CODE_COLLAPSED_LINES] with "Show all N lines". The bar is 28 dp;
 * Copy's touch area is 48 dp square, laid over the bar and the code's first
 * line, where a tap does nothing else.
 */
@Composable
fun CodeBlock(code: String, colors: ChatColors, language: String = "", modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val total = remember(code) { code.trimEnd('\n').lines().size }
    var all by rememberSaveable(code) { mutableStateOf(!codeStartsCollapsed(code)) }
    val shown = remember(code, all) { if (all) code.trimEnd('\n') else capLines(code, CODE_COLLAPSED_LINES).first }
    val shape = MaterialTheme.shapes.small
    Box(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.codeBg)
                .border(1.dp, colors.border, shape)
                .testTag("chat:code"),
        ) {
            Row(Modifier.fillMaxWidth().height(28.dp).padding(start = 10.dp, end = 58.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(language, style = ChatType.codeBar.copy(color = colors.muted), maxLines = 1, modifier = Modifier.weight(1f))
            }
            Text(
                shown,
                style = ChatType.code.copy(color = colors.text),
                softWrap = false,
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(start = 10.dp, end = 10.dp, bottom = 8.dp),
            )
            if (!all) {
                Text(
                    pluralStringResource(R.plurals.chat_show_all_lines, total, total),
                    style = ChatType.action.copy(color = colors.accentText),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable(role = Role.Button) { all = true }
                        .padding(horizontal = 10.dp, vertical = 14.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .heightIn(min = 48.dp)
                .widthIn(min = 48.dp)
                .clickable(role = Role.Button) {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("code", code))
                }
                .padding(horizontal = 10.dp)
                .testTag("chat:code:copy"),
            contentAlignment = Alignment.TopCenter,
        ) {
            Box(Modifier.height(28.dp), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.chat_copy), style = ChatType.action.copy(color = colors.accentText))
            }
        }
    }
}

/** A pipe table in a hairline outline; wider than the column, it scrolls sideways and never wraps a cell. */
@Composable
private fun Table(table: MdBlock.Table, colors: ChatColors, code: SpanStyle, link: TextLinkStyles, modifier: Modifier) {
    val shape = MaterialTheme.shapes.small
    val idColumn = remember(table) { table.rows.isNotEmpty() && table.rows.all { IDENTIFIER.matches(it.first()) } }
    val count = table.rows.size + 1
    Box(modifier.clip(shape).border(1.dp, colors.border, shape).testTag("chat:table")) {
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            table.header.indices.forEach { column ->
                Column(Modifier.width(IntrinsicSize.Max)) {
                    for (row in 0 until count) {
                        val cell = if (row == 0) table.header[column] else table.rows[row - 1][column]
                        val style = ChatType.table.copy(
                            color = colors.text,
                            fontWeight = if (row == 0) FontWeight.SemiBold else FontWeight.Normal,
                            fontFamily = if (row > 0 && column == 0 && idColumn) FontFamily.Monospace else null,
                        )
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
                            InlineText(inlineMarkdown(cell, code, link), style, colors, softWrap = false, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}
