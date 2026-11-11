/* Part of Bellterm, Copyright (c) 2026 Terry Wang, Apache-2.0. Modified for Herdline, 2026: Herdline identity and app changes. */
package io.github.erikfrish.herdline.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.json.JSONArray
import org.json.JSONObject

/**
 * A terminal colour scheme: default colours and the 16 ANSI colours Herdr's
 * named colours index. [selection] is ARGB and app-side only: Herdr never sees it.
 */
data class TerminalScheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val foreground: Long,
    val background: Long,
    val cursor: Long,
    val selection: Long,
    val ansi: List<Long>,
) {
    /** The document `theme.rs` reads. */
    fun toJson(): JSONObject = JSONObject()
        .put("dark", dark)
        .put("foreground", hex(foreground))
        .put("background", hex(background))
        .put("cursor", hex(cursor))
        .put("ansi", JSONArray(ansi.map(::hex)))

    /** This scheme with its cursor in [color], the session's node colour. */
    fun withCursor(color: Color) = copy(cursor = color.toArgb().toLong() and 0xffffff)

    private fun hex(color: Long) = "%06x".format(color and 0xffffff)
}

/** Today's selection wash; the schemes without a published selection colour keep it. */
private const val SELECTION = 0x664a90e2L

private fun scheme(id: String, name: String, dark: Boolean, fg: Long, bg: Long, cursor: Long, selection: Long, vararg ansi: Long) =
    TerminalScheme(id, name, dark, fg, bg, cursor, selection, ansi.toList()).also { require(ansi.size == 16) }

/** Well-known schemes, each in its authors' published colours. */
object TerminalSchemes {
    val all: List<TerminalScheme> = listOf(
        // keep-sorted start
        scheme(
            "herdline-dark", "Herdline Dark", true, 0xd8dee9, 0x14161b, 0x7fd6d2, SELECTION,
            0x1d2027, 0xef6b73, 0x8fc97e, 0xe8c27a, 0x6ab0f3, 0xc595e8, 0x5ec4c8, 0xc8ccd4,
            0x5c6370, 0xff858c, 0xa6e095, 0xf5d491, 0x8cc4ff, 0xd7aef5, 0x7fdade, 0xf2f4f8,
        ),
        scheme(
            "herdline-hc-dark", "Herdline High Contrast Dark", true, 0xffffff, 0x0a0b0e, 0x8fe3df, 0xff3d4f6e,
            0x1d2027, 0xf0747b, 0x8fc97e, 0xe8c27a, 0x6ab0f3, 0xc595e8, 0x5ec4c8, 0xc8ccd4,
            0x5c6370, 0xff858c, 0xa6e095, 0xf5d491, 0x8cc4ff, 0xd7aef5, 0x7fdade, 0xf2f4f8,
        ),
        scheme(
            "herdline-hc-light", "Herdline High Contrast Light", false, 0x000000, 0xffffff, 0x005354, 0xffc2d2e9,
            0x2b2f36, 0xa52a33, 0x2c6322, 0x755100, 0x1b55a9, 0x7c39a6, 0x0e6166, 0x56595c,
            0x6b7079, 0x9e323b, 0x2f6226, 0x745207, 0x22579e, 0x773d9b, 0x116065, 0x565759,
        ),
        scheme(
            "herdline-light", "Herdline Light", false, 0x2b2f36, 0xfbfaf7, 0x00696a, SELECTION,
            // ANSI white 0x8d9198 is 3.03:1 on the page; the scrollbar thumb was measured in the old 0xa0a4ab, 2.40:1 (R14).
            0x2b2f36, 0xc4323d, 0x3d8a2f, 0x9a6b00, 0x1f63c4, 0x8a3fb8, 0x137f86, 0x8d9198,
            0x6b7079, 0xdb4652, 0x4c9e3d, 0xb07d0a, 0x2e75d6, 0x9c50cc, 0x1a9199, 0xd8dade,
        ),
        scheme(
            "herdline-oled", "Herdline OLED", true, 0xd8dee9, 0x000000, 0x7fd6d2, 0xff1e3a5a,
            0x1d2027, 0xef6b73, 0x8fc97e, 0xe8c27a, 0x6ab0f3, 0xc595e8, 0x5ec4c8, 0xc8ccd4,
            0x5c6370, 0xff858c, 0xa6e095, 0xf5d491, 0x8cc4ff, 0xd7aef5, 0x7fdade, 0xf2f4f8,
        ),
        scheme(
            "catppuccin-latte", "Catppuccin Latte", false, 0x4c4f69, 0xeff1f5, 0xdc8a78, 0xffd2d4dc,
            0x5c5f77, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0xea76cb, 0x179299, 0xacb0be,
            0x6c6f85, 0xd20f39, 0x40a02b, 0xdf8e1d, 0x1e66f5, 0xea76cb, 0x179299, 0xbcc0cc,
        ),
        scheme(
            "catppuccin-mocha", "Catppuccin Mocha", true, 0xcdd6f4, 0x1e1e2e, 0xf5e0dc, 0xff414356,
            0x45475a, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xf5c2e7, 0x94e2d5, 0xbac2de,
            0x585b70, 0xf38ba8, 0xa6e3a1, 0xf9e2af, 0x89b4fa, 0xf5c2e7, 0x94e2d5, 0xa6adc8,
        ),
        scheme(
            "dracula", "Dracula", true, 0xf8f8f2, 0x282a36, 0xf8f8f2, SELECTION,
            0x21222c, 0xff5555, 0x50fa7b, 0xf1fa8c, 0xbd93f9, 0xff79c6, 0x8be9fd, 0xf8f8f2,
            0x6272a4, 0xff6e6e, 0x69ff94, 0xffffa5, 0xd6acff, 0xff92df, 0xa4ffff, 0xffffff,
        ),
        scheme(
            "everforest-dark", "Everforest Dark", true, 0xd3c6aa, 0x2d353b, 0xd3c6aa, 0xff543a48,
            0x475258, 0xe67e80, 0xa7c080, 0xdbbc7f, 0x7fbbb3, 0xd699b6, 0x83c092, 0xd3c6aa,
            0x475258, 0xe67e80, 0xa7c080, 0xdbbc7f, 0x7fbbb3, 0xd699b6, 0x83c092, 0xd3c6aa,
        ),
        scheme(
            "everforest-light", "Everforest Light", false, 0x5c6a72, 0xfdf6e3, 0x5c6a72, 0xffeaedc8,
            0x5c6a72, 0xf85552, 0x8da101, 0xdfa000, 0x3a94c5, 0xdf69ba, 0x35a77c, 0xe6e2cc,
            0x5c6a72, 0xf85552, 0x8da101, 0xdfa000, 0x3a94c5, 0xdf69ba, 0x35a77c, 0xe6e2cc,
        ),
        scheme(
            "github-dark", "GitHub Dark", true, 0xf0f6fc, 0x0d1117, 0x4493f8, 0xff14376c,
            0x2f3742, 0xff7b72, 0x3fb950, 0xd29922, 0x58a6ff, 0xbe8fff, 0x39c5cf, 0xf0f6fc,
            0x656c76, 0xffa198, 0x56d364, 0xe3b341, 0x79c0ff, 0xd2a8ff, 0x56d4dd, 0xffffff,
        ),
        scheme(
            "github-light", "GitHub Light", false, 0x1f2328, 0xffffff, 0x0969da, 0xffcee1f8,
            0x24292f, 0xcf222e, 0x116329, 0x4d2d00, 0x0969da, 0x8250df, 0x1b7c83, 0x6e7781,
            0x57606a, 0xa40e26, 0x1a7f37, 0x633c01, 0x218bff, 0xa475f9, 0x3192aa, 0x8c959f,
        ),
        scheme(
            "gruvbox-dark", "Gruvbox Dark", true, 0xebdbb2, 0x282828, 0xebdbb2, 0xff504945,
            0x282828, 0xcc241d, 0x98971a, 0xd79921, 0x458588, 0xb16286, 0x689d6a, 0xa89984,
            0x928374, 0xfb4934, 0xb8bb26, 0xfabd2f, 0x83a598, 0xd3869b, 0x8ec07c, 0xebdbb2,
        ),
        scheme(
            "gruvbox-light", "Gruvbox Light", false, 0x3c3836, 0xfbf1c7, 0x3c3836, 0xffd5c4a1,
            0xfbf1c7, 0xcc241d, 0x98971a, 0xd79921, 0x458588, 0xb16286, 0x689d6a, 0x7c6f64,
            0x928374, 0x9d0006, 0x79740e, 0xb57614, 0x076678, 0x8f3f71, 0x427b58, 0x3c3836,
        ),
        scheme(
            "nord", "Nord", true, 0xd8dee9, 0x2e3440, 0xd8dee9, 0xff434c5e,
            0x3b4252, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x88c0d0, 0xe5e9f0,
            0x4c566a, 0xbf616a, 0xa3be8c, 0xebcb8b, 0x81a1c1, 0xb48ead, 0x8fbcbb, 0xeceff4,
        ),
        scheme(
            "one-dark", "One Dark", true, 0xabb2bf, 0x282c34, 0x528bff, SELECTION,
            0x282c34, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xabb2bf,
            0x5c6370, 0xe06c75, 0x98c379, 0xe5c07b, 0x61afef, 0xc678dd, 0x56b6c2, 0xffffff,
        ),
        scheme(
            "rose-pine", "Rosé Pine", true, 0xe0def4, 0x191724, 0x524f67, 0xff403d52,
            0x26233a, 0xeb6f92, 0x31748f, 0xf6c177, 0x9ccfd8, 0xc4a7e7, 0xebbcba, 0xe0def4,
            0x6e6a86, 0xeb6f92, 0x31748f, 0xf6c177, 0x9ccfd8, 0xc4a7e7, 0xebbcba, 0xe0def4,
        ),
        scheme(
            "rose-pine-dawn", "Rosé Pine Dawn", false, 0x575279, 0xfaf4ed, 0xcecacd, 0xffdfdad9,
            0xf2e9e1, 0xb4637a, 0x286983, 0xea9d34, 0x56949f, 0x907aa9, 0xd7827e, 0x575279,
            0x9893a5, 0xb4637a, 0x286983, 0xea9d34, 0x56949f, 0x907aa9, 0xd7827e, 0x575279,
        ),
        scheme(
            "solarized-dark", "Solarized Dark", true, 0x839496, 0x002b36, 0x93a1a1, 0xff073642,
            0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
            0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
        ),
        // Foreground is base01, not upstream's base00 (4.13:1), to read at 4.99.
        scheme(
            "solarized-light", "Solarized Light", false, 0x586e75, 0xfdf6e3, 0x586e75, 0xffeee8d5,
            0x073642, 0xdc322f, 0x859900, 0xb58900, 0x268bd2, 0xd33682, 0x2aa198, 0xeee8d5,
            0x002b36, 0xcb4b16, 0x586e75, 0x657b83, 0x839496, 0x6c71c4, 0x93a1a1, 0xfdf6e3,
        ),
        scheme(
            "tokyo-night", "Tokyo Night", true, 0xc0caf5, 0x1a1b26, 0xc0caf5, SELECTION,
            0x15161e, 0xf7768e, 0x9ece6a, 0xe0af68, 0x7aa2f7, 0xbb9af7, 0x7dcfff, 0xa9b1d6,
            0x414868, 0xf7768e, 0x9ece6a, 0xe0af68, 0x7aa2f7, 0xbb9af7, 0x7dcfff, 0xc0caf5,
        ),
        // keep-sorted end
    )

    /**
     * The scheme stored as [id] for a [dark] or light terminal. An empty or
     * unknown id is [fallback], the app theme's own pair, else Herdline's.
     */
    fun byId(id: String, dark: Boolean, fallback: String = if (dark) "herdline-dark" else "herdline-light"): TerminalScheme =
        all.firstOrNull { it.id == id } ?: all.firstOrNull { it.id == fallback }
            ?: all.first { it.id == if (dark) "herdline-dark" else "herdline-light" }
}
