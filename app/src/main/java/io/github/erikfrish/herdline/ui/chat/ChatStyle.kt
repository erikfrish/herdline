package io.github.erikfrish.herdline.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.erikfrish.herdline.ui.theme.ChatTokens
import io.github.erikfrish.herdline.ui.theme.LocalThemeTokens
import io.github.erikfrish.herdline.ui.theme.contrast
import io.github.erikfrish.herdline.ui.theme.ensureContrast

/**
 * Chat's colour roles, from the palette's
 * [ChatTokens]. Only [accent] and [accentText] follow the session accent.
 */
@Immutable
data class ChatColors(
    val bg: Color,
    val surf: Color,
    val userBubble: Color,
    val codeChip: Color,
    val codeBg: Color,
    val border: Color,
    val rule: Color,
    val text: Color,
    val muted: Color,
    val dim: Color,
    val accent: Color,
    val accentText: Color,
    val working: Color,
    val blocked: Color,
    val done: Color,
    val err: Color,
    val warnBg: Color,
    val errBg: Color,
    /**
     * The user bubble's outline: [border] where the bubble's fill alone
     * barely parts from [bg] (under [BUBBLE_EDGE_BELOW]:1, the light
     * palettes), else none, so a 1.07:1 fill still reads as a bubble.
     */
    val bubbleEdge: Color,
)

/** The fill-to-page contrast under which the user bubble gets its outline. */
const val BUBBLE_EDGE_BELOW = 1.15f

/** The roles for the current palette and mode, with [accent] as the session's accent. */
@Composable
fun chatColors(accent: Color): ChatColors {
    val tokens = LocalThemeTokens.current.chat
    return remember(tokens, accent) { chatPalette(tokens, accent) }
}

/**
 * The roles for [tokens]. Every text role keeps WCAG AA (4.5:1) on bg and
 * surf, and status bars keep 3:1; the neutral [ChatColors.border] and
 * [ChatColors.rule] hairlines are decorative and carry no meaning alone.
 */
fun chatPalette(tokens: ChatTokens, accent: Color): ChatColors = ChatColors(
    bg = tokens.bg,
    surf = tokens.surf,
    userBubble = tokens.userBubble,
    codeChip = tokens.codeChip,
    codeBg = tokens.codeBg,
    border = tokens.border,
    rule = tokens.rule,
    text = tokens.text,
    muted = tokens.muted,
    dim = tokens.dim,
    accent = accent,
    // A session accent the palette does not own is lifted until it reads at AA.
    accentText = ensureContrast(
        lerp(accent, tokens.accentTextToward, tokens.accentTextMix),
        listOf(tokens.bg, tokens.surf, tokens.userBubble, tokens.codeChip),
        4.5f,
        tokens.accentTextToward,
    ),
    working = tokens.working,
    blocked = tokens.blocked,
    done = tokens.done,
    err = tokens.err,
    warnBg = tokens.warnBg,
    errBg = tokens.errBg,
    bubbleEdge = if (contrast(tokens.userBubble, tokens.bg) < BUBBLE_EDGE_BELOW) tokens.border else Color.Transparent,
)

/** Chat's type scale (§16.2): system sans and mono, no font assets. */
object ChatType {
    /** Chat's one-line texts outside a reply: states, a question, a placeholder. */
    val body = TextStyle(fontSize = 15.sp, lineHeight = 22.sp)

    /**
     * A reply's prose: 15/24, or [body]'s 15/22 in a [dense][ChatView] list,
     * whose three lines must still fit J2's 101 dp Side budget at 1.45.
     */
    fun prose(dense: Boolean): TextStyle = if (dense) body else roomyProse
    private val roomyProse = body.copy(lineHeight = 24.sp)
    val heading1 = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight(650))
    val heading2 = TextStyle(fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight(650))
    val label = TextStyle(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val meta = TextStyle(fontSize = 12.sp, lineHeight = 16.sp)
    val inlineCode = 13.sp
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 20.sp)
    val codeBar = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
    val action = TextStyle(fontSize = 11.5.sp, lineHeight = 14.sp, fontWeight = FontWeight.Medium)
    val quote = TextStyle(fontSize = 14.5.sp, lineHeight = 21.sp, fontStyle = FontStyle.Italic)
    val table = TextStyle(fontSize = 13.sp, lineHeight = 18.sp)
    val toolName = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold)
    val toolArg = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
    val toolOutput = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp)
    val aside = TextStyle(fontSize = 12.5.sp, lineHeight = 18.sp)
    val button = TextStyle(fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold)
}

/** Gaps of the two-level rhythm (§16.1). */
object ChatSpace {
    val turn = 18.dp
    val block = 8.dp
    val tool = 6.dp
}
