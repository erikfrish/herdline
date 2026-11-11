package io.github.erikfrish.herdline.core

/**
 * A terminal-supplied title or label as a name to show, the one sanitizer for
 * every title Herdline displays. Icon-font glyphs (private use code points,
 * drawn as boxes without the font) and spinner frames (which change while an
 * agent works) are dropped and whitespace is collapsed. OMP's "π" prefix and
 * the status mark after it ("π >" idle, "π !" waiting, a spinner while it
 * works, which is dropped first) are cut when [agent] is "omp" or unknown (a
 * pane label carries the terminal title before Herdr recognises the agent);
 * another known program keeps it as its own text. Null when nothing is left.
 */
fun readableTitle(title: String?, agent: String? = null): String? {
    val text = buildString {
        title.orEmpty().codePoints().forEach { if (!decorative(it)) appendCodePoint(it) }
    }.replace(Regex("\\s+"), " ").trim()
    val named = if (agent == null || agent.equals("omp", ignoreCase = true)) {
        OMP_PROMPT.replace(text, "")
    } else {
        text
    }
    return named.takeIf { it.isNotEmpty() }
}

private fun decorative(codePoint: Int): Boolean =
    Character.getType(codePoint) == Character.PRIVATE_USE.toInt() || codePoint in BRAILLE || codePoint in SPINNERS

/** "π", then its status marks, then a space or the end: "πx" is a word, not a prefix. */
private val OMP_PROMPT = Regex("^π(\\s*[>!?*•·]+\\s*|\\s+|$)")

private val BRAILLE = 0x2800..0x28FF

/** Frames of the star and circle spinners agents put in their titles. */
private val SPINNERS = setOf(0x2722, 0x2733, 0x2736, 0x273B, 0x273D, 0x25D0, 0x25D1, 0x25D2, 0x25D3, 0x25F4, 0x25F5, 0x25F6, 0x25F7)
