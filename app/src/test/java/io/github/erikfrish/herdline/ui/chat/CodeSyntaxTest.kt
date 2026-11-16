package io.github.erikfrish.herdline.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scanner's uncertain edges: whatever it colours, the characters a
 * selection or Copy takes away must stay exactly what the agent wrote, and
 * a run the scanner misreads is skipped whole instead of guessing.
 */
class CodeSyntaxTest {
    private val roles = CodeRole.values().associateWith { SpanStyle(color = Color.Red) }

    /** Every byte survives [highlight], whatever role lands on it. */
    private fun assertBytesSurvive(code: String, language: String) {
        assertEquals(code, highlight(code, language, roles).text)
    }

    @Test
    fun exact_bytes_survive_every_language_and_edge() {
        val samples = listOf(
            "echo\n" to "bash",
            "echo hi" to "bash",
            "val s = \"a\\\"b\" // c\n" to "kotlin",
            "{\"a\": \"值\", \"b\": 1.5e3, \"c\": [true]}\n" to "json",
            "don't panic\nit's fine\n" to "shell",
            "x = 1\ndef f():\n    return '🔑'\r\n" to "python",
            "---\nkey: value\nlist:\n- a\n" to "yaml",
            "@@ -1,2 +1,2 @@\n-a\n+b\n" to "diff",
            "" to "python",
            "\n\n\u0000tail " to "rust",
        )
        samples.forEach { (code, language) -> assertBytesSurvive(code, language) }
    }

    @Test
    fun a_lone_apostrophe_swallows_nothing() {
        assertBytesSurvive("don't\nif True:\n    pass", "python")
        // The "if" after the apostrophe line is still keyword-shaped text to find.
        val tokens = codeTokens("don't\nif True:\n    pass", "python")
        assertTrue(tokens.none { it.end > "don't\n".length && it.role == CodeRole.String })
    }

    @Test
    fun code_inside_a_string_is_never_a_keyword() {
        val code = "let x = \"if while return\"; // function let\n"
        val tokens = codeTokens(code, "js")
        val inString = "let x = \"".length until "let x = \"if while return\"".length
        assertTrue(tokens.none { it.start >= inString.first && it.end <= inString.last && it.role != CodeRole.String })
        assertBytesSurvive(code, "js")
    }
    @Test
    fun a_heredoc_body_is_the_words_own_text() {
        val code = "cat <<EOF\nif return 42\necho done\nEOF\necho after\n"
        val bodyFrom = code.indexOf('\n') + 1
        val bodyTo = code.lastIndexOf("EOF\n") + 3
        assertFalse(codeTokens(code, "shell").filter { it.start in bodyFrom until bodyTo }.any { it.role != CodeRole.Comment })
        assertBytesSurvive(code, "shell")
    }

    @Test
    fun tokens_are_sorted_never_overlap_and_stay_inside_the_code() {
        listOf(
            "def f(x):\n    return 0x1 + 1.5e3\n" to "python",
            "{\"k\": [1, 2], \"s\": \"}\"}\n" to "json",
            "[[sec]]\n\"k\" = 'v'\n" to "toml",
            "fn main() { let s = \"}}\"; }\n" to "rust",
            "var x: Int = 1 // c\n" to "kotlin",
            "a: 1\nb:\n  c: true\n" to "yaml",
        ).forEach { (code, language) ->
            var keyOk: Int? = null
            for (token in codeTokens(code, language)) {
                assertTrue("token $token out of range", token.start >= 0 && token.end <= code.length && token.end > token.start)
                val first = keyOk
                if (first != null) assertTrue("overlapping $token after $first", token.start >= first)
                keyOk = token.end
            }
            assertBytesSurvive(code, language)
        }
    }

    @Test
    fun an_unknown_or_absent_label_stays_plain() {
        assertTrue(codeTokens("let x = 1", "brainfuck").isEmpty())
        assertTrue(codeTokens("let x = 1", "").isEmpty())
        assertBytesSurvive("let x = 1", "")
    }

    @Test
    fun past_the_scan_limit_the_block_reads_plain_but_whole() {
        val code = "if return 42 }\n".repeat(CODE_SCAN_LIMIT / 8 + 1)
        assertTrue(codeTokens(code, "bash").isEmpty())
        assertBytesSurvive(code, "bash")
    }

    @Test
    fun a_label_with_extra_fence_text_still_names_its_grammar() {
        assertTrue(codeTokens("echo hi", "bash\n").isNotEmpty())
        assertTrue(codeTokens("echo hi", " shell").isNotEmpty())
    }
}
