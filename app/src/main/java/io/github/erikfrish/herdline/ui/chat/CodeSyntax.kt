package io.github.erikfrish.herdline.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import io.github.erikfrish.herdline.ui.theme.LocalThemeTokens
import io.github.erikfrish.herdline.ui.theme.ensureContrast

/**
 * Syntax colouring for fenced code: a tiny character scanner over the
 * explicit fence labels people write, with no grammar and no dependencies.
 * The source string is never changed: roles are ranges over it, so the
 * selection and Copy keep the exact bytes the agent wrote.
 */
internal enum class CodeRole { Keyword, Type, String, Comment, Number, Operator, DiffAdd, DiffDel }

/** [start, end) of one readable slice of code carrying a single [CodeRole]. */
internal data class CodeToken(val start: Int, val end: Int, val role: CodeRole)

/** Inline-code ink and per-role spans for a code ground, valid for one palette. */
@Immutable
internal data class CodeStyle(val inline: SpanStyle, val roles: Map<CodeRole, SpanStyle>)

/** Syntax text keeps the same WCAG AA (4.5:1) as chat's other text roles. */
private const val CODE_TEXT_CONTRAST = 4.5f

/**
 * Above this many characters a block reads as plain code rather than being
 * scanned — and never as less code: the whole block is still shown.
 */
internal const val CODE_SCAN_LIMIT = 40_000

internal enum class SyntaxKind { SHELL, JSON, PYTHON, JS, KOTLIN, RUST, YAML, TOML, DIFF }

/**
 * Roles over [code] as marked by [language]'s fence label: sorted, never
 * overlapping, always inside the string, and empty for a label without a
 * grammar or a block past [CODE_SCAN_LIMIT]. Where the scanner is unsure it
 * leaves the text plain instead of guessing.
 */
internal fun codeTokens(code: String, language: String): List<CodeToken> {
    val kind = syntaxKind(language) ?: return emptyList()
    if (code.isEmpty() || code.length > CODE_SCAN_LIMIT) return emptyList()
    if (kind == SyntaxKind.DIFF) return scanDiff(code)
    val out = mutableListOf<CodeToken>()
    scan(code, kind, out)
    return out
}

/**
 * [code] styled by [roles]: plain strings join back to [code] exactly, so a
 * selection or a copy from the styled text yields the original bytes. A range
 * the scanner misplaced is skipped whole, never cut into a stray half-style.
 */
internal fun highlight(code: String, language: String, roles: Map<CodeRole, SpanStyle>): AnnotatedString = buildAnnotatedString {
    var at = 0
    for (token in codeTokens(code, language)) {
        if (token.start < at || token.end > code.length || token.end <= token.start) continue
        if (token.start > at) append(code, at, token.start)
        withStyle(roles.getValue(token.role)) { append(code, token.start, token.end) }
        at = token.end
    }
    if (at < code.length) append(code, at, code.length)
}

/**
 * The colours for inline code and fenced code on [colors]' code grounds.
 * Bases are the classic editor hues; each is lifted toward black or white
 * until it keeps [CODE_TEXT_CONTRAST] on both the block and the chip ground.
 */
@Composable
internal fun rememberCodeStyle(colors: ChatColors): CodeStyle {
    val dark = LocalThemeTokens.current.dark
    return remember(colors, dark) {
        val grounds = listOf(colors.codeBg, colors.codeChip)
        val toward = if (dark) Color.White else Color.Black
        fun lift(base: Color) = ensureContrast(base, grounds, CODE_TEXT_CONTRAST, toward)
        CodeStyle(
            inline = SpanStyle(color = lift(if (dark) INLINE_CODE_DARK else INLINE_CODE_LIGHT)),
            roles = CodeRole.values().associateWith { SpanStyle(color = lift(roleBase(it, dark, colors))) },
        )
    }
}

/** Teal-cyan inline-code ink, one per mode, lifted to the code grounds as the roles are. */
private val INLINE_CODE_DARK = Color(0xFF4EC9B0)
private val INLINE_CODE_LIGHT = Color(0xFF0E7A8A)

private fun roleBase(role: CodeRole, dark: Boolean, colors: ChatColors): Color = when {
    dark -> when (role) {
        CodeRole.Keyword -> Color(0xFFC792EA)
        CodeRole.Type -> Color(0xFF82AAFF)
        CodeRole.String -> Color(0xFFA5D6A7)
        CodeRole.Comment -> Color(0xFF8B949E)
        CodeRole.Number -> Color(0xFFF5A97F)
        CodeRole.Operator -> Color(0xFF89DDFF)
        CodeRole.DiffAdd -> Color(0xFF7EC98F)
        CodeRole.DiffDel -> Color(0xFFE06C75)
    }
    else -> when (role) {
        CodeRole.Keyword -> Color(0xFF8E44AD)
        CodeRole.Type -> Color(0xFF0550AE)
        CodeRole.String -> Color(0xFF1A7F37)
        CodeRole.Comment -> colors.dim
        CodeRole.Number -> Color(0xFFB35900)
        CodeRole.Operator -> Color(0xFF6B4E9E)
        CodeRole.DiffAdd -> colors.done
        CodeRole.DiffDel -> colors.err
    }
}

/** The fence labels the scanner knows; anything else is left plain, deliberately. */
private fun syntaxKind(language: String): SyntaxKind? = when (language.trim().lowercase().substringBefore(' ').substringBefore(',')) {
    "sh", "bash", "zsh", "shell", "ksh", "console" -> SyntaxKind.SHELL
    "json", "jsonc" -> SyntaxKind.JSON
    "py", "python", "python3" -> SyntaxKind.PYTHON
    "js", "jsx", "mjs", "javascript", "ts", "tsx", "typescript" -> SyntaxKind.JS
    "kotlin", "kt", "kts", "java" -> SyntaxKind.KOTLIN
    "rust", "rs" -> SyntaxKind.RUST
    "yaml", "yml" -> SyntaxKind.YAML
    "toml" -> SyntaxKind.TOML
    "diff", "patch", "udiff" -> SyntaxKind.DIFF
    else -> null
}

// Grammar support, flat sets: a word not in one of them is simply a word.

private val SHELL_KEYWORDS = setOf(
    "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "function",
    "in", "select", "time", "coproc", "local", "export", "declare", "typeset", "readonly", "alias", "unset",
    "return", "exit", "source", "eval", "exec", "set", "shift", "trap", "wait", "echo", "printf", "read", "cd",
)
private val JSON_KEYWORDS = setOf("true", "false", "null")
private val PYTHON_KEYWORDS = setOf(
    "def", "class", "return", "pass", "lambda", "import", "from", "as", "if", "elif", "else", "for", "while",
    "break", "continue", "try", "except", "finally", "raise", "with", "global", "nonlocal", "assert", "del",
    "yield", "async", "await", "match", "case", "None", "True", "False",
)
private val PYTHON_TYPES = setOf("int", "float", "str", "list", "dict", "set", "tuple", "bool", "bytes", "range", "object", "type")
private val JS_KEYWORDS = setOf(
    "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "break", "continue",
    "switch", "case", "default", "try", "catch", "finally", "throw", "new", "delete", "typeof", "instanceof",
    "void", "this", "super", "class", "extends", "import", "export", "from", "as", "async", "await", "yield",
    "in", "of", "static", "get", "set", "null", "undefined", "true", "false",
)
private val JS_TYPES = setOf("String", "Number", "Boolean", "Object", "Array", "Promise", "Map", "Set", "RegExp", "Error", "Math", "JSON", "console")
private val KOTLIN_KEYWORDS = setOf(
    "fun", "val", "var", "class", "object", "interface", "data", "sealed", "enum", "when", "is", "in", "as",
    "if", "else", "for", "while", "do", "return", "break", "continue", "throw", "try", "catch", "finally",
    "import", "package", "private", "public", "internal", "protected", "override", "open", "abstract",
    "companion", "suspend", "operator", "inline", "lateinit", "const", "constructor", "init", "by", "this",
    "super", "null", "true", "false", "typealias", "vararg", "out",
)
private val KOTLIN_TYPES = setOf("Int", "Long", "Short", "Byte", "Float", "Double", "Boolean", "Char", "String", "Any", "Nothing", "Unit", "List", "MutableList", "Set", "Map", "Pair", "Triple", "Array")
private val RUST_KEYWORDS = setOf(
    "fn", "let", "mut", "pub", "struct", "enum", "impl", "trait", "for", "while", "loop", "if", "else",
    "match", "return", "break", "continue", "use", "mod", "crate", "self", "Self", "super", "where", "as",
    "in", "move", "ref", "dyn", "unsafe", "const", "static", "type", "true", "false",
)
private val RUST_TYPES = setOf("Option", "Result", "Some", "None", "Ok", "Err", "String", "Vec", "Box", "Rc", "Arc", "HashMap", "BTreeMap")
private val TOML_VALUES = setOf("true", "false")

/** Word characters: a name is never split mid-word, so `endif` stays itself. */
private fun isWord(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

/** A diff is whole lines: add, drop, hunk or file header. */
private fun scanDiff(code: String): List<CodeToken> {
    val out = mutableListOf<CodeToken>()
    var start = 0
    while (start < code.length) {
        val end = code.indexOf('\n', start).let { if (it < 0) code.length else it }
        when {
            code.startsWith("+++", start) || code.startsWith("---", start) -> out += CodeToken(start, end, CodeRole.Comment)
            code[start] == '+' -> out += CodeToken(start, end, CodeRole.DiffAdd)
            code[start] == '-' -> out += CodeToken(start, end, CodeRole.DiffDel)
            code.startsWith("@@", start) -> out += CodeToken(start, end, CodeRole.Keyword)
        }
        start = end + 1
    }
    return out
}

/**
 * One forward pass over the code: comments and strings first (so words inside
 * a string or a heredoc are never keywords), then keys, numbers, names and
 * operators. Every branch consumes at least one character, so the loop ends.
 */
private fun scan(code: String, kind: SyntaxKind, out: MutableList<CodeToken>) {
    val n = code.length
    val markedComments = kind == SyntaxKind.YAML || kind == SyntaxKind.TOML
    val lineComment = when (kind) {
        SyntaxKind.SHELL, SyntaxKind.PYTHON, SyntaxKind.YAML, SyntaxKind.TOML -> "#"
        SyntaxKind.JS, SyntaxKind.KOTLIN, SyntaxKind.RUST -> "//"
        else -> null
    }
    val blockComment = kind == SyntaxKind.JS || kind == SyntaxKind.KOTLIN || kind == SyntaxKind.RUST
    val triple = kind == SyntaxKind.PYTHON || kind == SyntaxKind.KOTLIN || kind == SyntaxKind.TOML

    var i = 0
    /** Only whitespace since the line's start (or since a YAML list marker): a key may begin. */
    var keyOk = true
    var heredoc: String? = null

    fun emit(from: Int, to: Int, role: CodeRole) {
        if (to > from) out += CodeToken(from, to, role)
    }

    fun lineEnd(from: Int): Int = code.indexOf('\n', from).let { if (it < 0) n else it }

    /** A number: digits with `_`, `0x…`, one dot before a digit, an exponent. */
    fun numberEnd(from: Int): Int {
        var j = from
        if (code[j] == '0' && (code.getOrNull(j + 1) == 'x' || code.getOrNull(j + 1) == 'X')) {
            j += 2
            while (j < n && (code[j].isDigit() || code[j] in 'a'..'f' || code[j] in 'A'..'F' || code[j] == '_')) j++
            return j
        }
        while (j < n && (code[j].isDigit() || code[j] == '_')) j++
        if (code.getOrNull(j) == '.' && (code.getOrNull(j + 1)?.isDigit() == true)) {
            j++
            while (j < n && (code[j].isDigit() || code[j] == '_')) j++
        }
        if (code.getOrNull(j) == 'e' || code.getOrNull(j) == 'E') {
            var k = j + 1
            if (code.getOrNull(k) == '+' || code.getOrNull(k) == '-') k++
            if (code.getOrNull(k)?.isDigit() == true) {
                while (k < n && code[k].isDigit()) k++
                j = k
            }
        }
        return j
    }

    /**
     * A quoted run: past its closing quote, or `null` for a quote the line
     * never closes (a lone apostrophe must not swallow the rest of the block).
     * [multiline] covers backtick templates and `"""`/`'''` runs, which close
     * on their own marks; a lone quote inside a triple run is just text.
     */
    fun stringEnd(from: Int, quote: Char, multiline: Boolean): Int {
        var j = from + 1
        while (j < n) {
            val c = code[j]
            if (c == '\\') { j += 2; continue }
            if (c == quote) {
                if (quote == '`') return j + 1
                if (!multiline) return j + 1
                // A triple quote closes on three of its kind in a row.
                var k = j
                while (k < n && code[k] == quote) k++
                if (k - j >= 3) return k
                j = k
                continue
            }
            if (c == '\n' && !multiline) return -1
            j++
        }
        return if (multiline) n else -1
    }

    /** A `"key"`-shaped string: the next thing after it is the key colon or equals. */
    fun isKeyString(to: Int): Boolean {
        val keyEnd = if (kind == SyntaxKind.TOML) '=' else ':'
        var j = to
        while (j < n && (code[j] == ' ' || code[j] == '\t')) j++
        return code.getOrNull(j) == keyEnd && code.getOrNull(j + 1) != keyEnd
    }

    /** `key:` on YAML, `key =` on TOML, read from the line's first real name. */
    fun keyEndAt(from: Int): Int {
        val keyEnd = if (kind == SyntaxKind.TOML) '=' else ':'
        var j = from
        while (j < n && (code[j].isLetterOrDigit() || code[j] == '_' || code[j] == '-' || code[j] == '.')) j++
        return if (j > from && code.getOrNull(j) == keyEnd && code.getOrNull(j + 1) != keyEnd) j else -1
    }

    /** A `<<WORD` heredoc mark; `WORD`, `-'WORD'` and friends all name the closing word. */
    fun heredocWordAt(from: Int): Pair<String, Int>? {
        var j = from + 2
        while (code.getOrNull(j) == ' ' || code.getOrNull(j) == '\t') j++
        if (code.getOrNull(j) == '-') j++
        while (code.getOrNull(j) == ' ' || code.getOrNull(j) == '\t') j++
        val quote = code.getOrNull(j)
        if (quote == '\'' || quote == '"') {
            val close = code.indexOf(quote, j + 1)
            return if (close < 0) null else code.substring(j + 1, close) to (close + 1)
        }
        var k = j
        while (k < n && isWord(code[k])) k++
        return if (k == j) null else code.substring(j, k) to k
    }

    while (i < n) {
        val c = code[i]
        // The body of a heredoc is its own text, not code.
        if (heredoc != null && keyOk) {
            val end = lineEnd(i)
            emit(i, end, CodeRole.Comment)
            if (code.substring(i, end).trim() == heredoc) heredoc = null
            i = if (end < n) end + 1 else end
            continue
        }
        when {
            c == '\n' -> { keyOk = true; i++ }
            c == ' ' || c == '\t' || c == '\r' -> i++
            else -> {
                if (lineComment != null && code.startsWith(lineComment, i) &&
                    (!markedComments || keyOk || code.getOrNull(i - 1) == ' ' || code.getOrNull(i - 1) == '\t')
                ) {
                    val end = lineEnd(i)
                    emit(i, end, CodeRole.Comment)
                    i = end
                    continue
                }
                if (blockComment && code.startsWith("/*", i)) {
                    val close = code.indexOf("*/", i + 2)
                    val end = if (close < 0) n else close + 2
                    emit(i, end, CodeRole.Comment)
                    keyOk = false; i = end; continue
                }
                if (triple && (code.startsWith("\"\"\"", i) || code.startsWith("'''", i))) {
                    val quote = if (code.startsWith("\"\"\"", i)) '"' else '\''
                    val end = stringEnd(i, quote, multiline = true)
                    emit(i, end, CodeRole.String)
                    keyOk = false; i = end; continue
                }
                // YAML/TOML keys and sections read as keywords off the line's first name.
                if (kind == SyntaxKind.YAML || kind == SyntaxKind.TOML) {
                    if (kind == SyntaxKind.TOML && c == '[' && keyOk) {
                        var close = code.indexOf(']', i + 1)
                        if (close > i && close < lineEnd(i)) {
                            // `[[table]]` closes on the run of brackets, not the first one.
                            while (code.getOrNull(close + 1) == ']') close++
                            if (code.getOrNull(close + 1)?.let { it != ' ' && it != '\t' && it != '\n' && it != '#' } != true) {
                                emit(i, close + 1, CodeRole.Keyword)
                                keyOk = false; i = close + 1; continue
                            }
                        }
                    }
                    if (keyOk && c == '-' && code.getOrNull(i + 1) == ' ') {
                        i += 1; continue
                    }
                    if (keyOk && c.isLetter()) {
                        val end = keyEndAt(i)
                        if (end > i) {
                            emit(i, end, CodeRole.Keyword)
                            keyOk = false; i = end; continue
                        }
                    }
                }
                if (c == '\'' || c == '"' || (kind == SyntaxKind.JS && c == '`')) {
                    val end = stringEnd(i, c, multiline = c == '`')
                    if (end > i) {
                        val key = (kind == SyntaxKind.JSON || markedComments) && isKeyString(end)
                        emit(i, end, if (key) CodeRole.Keyword else CodeRole.String)
                        keyOk = false; i = end; continue
                    }
                    keyOk = false; i++; continue
                }
                if (kind == SyntaxKind.SHELL && code.startsWith("<<", i) && code.getOrNull(i + 2) != '<') {
                    val (word, end) = heredocWordAt(i) ?: ("" to i + 2)
                    if (word.isNotEmpty()) heredoc = word
                    keyOk = false; i = end; continue
                }
                if (c.isDigit() || (c == '.' && code.getOrNull(i + 1)?.isDigit() == true)) {
                    val end = numberEnd(i)
                    emit(i, end, CodeRole.Number)
                    keyOk = false; i = end; continue
                }
                if (kind == SyntaxKind.SHELL && c == '$') {
                    // A shell expansion names a value; it is not language.
                    var j = i + 1
                    if (code.getOrNull(j) == '{') {
                        while (j < n && code[j] != '}' && code[j] != '\n') j++
                        if (code.getOrNull(j) == '}') j++
                    } else {
                        while (j < n && isWord(code[j])) j++
                    }
                    keyOk = false; i = j; continue
                }
                if (c.isLetter() || c == '_') {
                    var j = i
                    while (j < n && isWord(code[j])) j++
                    val word = code.substring(i, j)
                    val role = when {
                        kind == SyntaxKind.SHELL -> if (word in SHELL_KEYWORDS) CodeRole.Keyword else null
                        kind == SyntaxKind.JSON -> if (word in JSON_KEYWORDS) CodeRole.Keyword else null
                        kind == SyntaxKind.PYTHON -> when {
                            word in PYTHON_KEYWORDS -> CodeRole.Keyword
                            word in PYTHON_TYPES -> CodeRole.Type
                            else -> null
                        }
                        kind == SyntaxKind.JS -> when {
                            word in JS_KEYWORDS -> CodeRole.Keyword
                            word in JS_TYPES -> CodeRole.Type
                            else -> null
                        }
                        kind == SyntaxKind.KOTLIN -> when {
                            word in KOTLIN_KEYWORDS -> CodeRole.Keyword
                            word in KOTLIN_TYPES || word.first().isUpperCase() -> CodeRole.Type
                            else -> null
                        }
                        kind == SyntaxKind.RUST -> when {
                            word in RUST_KEYWORDS -> CodeRole.Keyword
                            word in RUST_TYPES -> CodeRole.Type
                            else -> null
                        }
                        kind == SyntaxKind.TOML -> if (word in TOML_VALUES) CodeRole.Keyword else null
                        else -> if (word == "true" || word == "false" || word == "null") CodeRole.Keyword else null
                    }
                    if (role != null) emit(i, j, role)
                    keyOk = false; i = j; continue
                }
                if (OPERATORS.contains(c) && kind != SyntaxKind.JSON && !markedComments) {
                    emit(i, i + 1, CodeRole.Operator)
                }
                keyOk = false; i++
            }
        }
    }
}

/** One-character operators; a dot is left to numbers and member access. */
private val OPERATORS = "+-*/%=<>!&|^~?:"
