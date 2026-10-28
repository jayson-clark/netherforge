package dev.netherforge.format.text

import kotlinx.serialization.Serializable

/**
 * How a stretch of text looks: only what a tag set. Null is "as the text
 * around it" (a preview's own default, say a tooltip's grey); `false` is a
 * decoration turned off (`<!italic>`, `<italic:false>`).
 */
@Serializable
data class TextStyle(
    /** `#rrggbb`, lower case. */
    val color: String? = null,
    val bold: Boolean? = null,
    val italic: Boolean? = null,
    val underlined: Boolean? = null,
    val strikethrough: Boolean? = null,
    val obfuscated: Boolean? = null
)

/**
 * MiniMessage as the editor's previews read it: one pass that says what each
 * character and glyph tag draws, in which style, and where lines break.
 * [TextMetrics] measures these pieces and the editor draws them (the
 * `styleText` export), so a preview draws exactly the characters the layout
 * wrapped.
 *
 * Every `<…>` is a tag and draws nothing, except `<glyph:ui/coin>`, which is
 * one picture and opens nothing, `<space:N>`, a move, and `<at:N>`, which
 * draws what it holds over the line ([Overlay]); `\n`, `<newline>` and `<br>` break the line.
 * Tags this knows style what follows (colours, decorations, `<reset>`); the
 * rest balance on the same stack and change nothing, so an unknown pair
 * can't leak a style. A gradient is drawn in its first colour, a rainbow
 * red. Each piece keeps where it is in the source, so a line's range (from
 * [TextMetrics.lines]) cuts the same pieces.
 *
 * Not [TextWidth]'s rules: that one is the server's exact measure, where a tag
 * MiniMessage doesn't know is drawn as written.
 */
object MiniMessagePass {
    sealed interface Piece {
        /** Where it starts in the source. */
        val at: Int

        /** Where the next piece may start: after the character, or the tag's `>`. */
        val end: Int
    }

    /** One code point, drawn: [text] is it as written (two UTF-16 units past the BMP). */
    class Char(val code: Int, val text: String, override val at: Int, override val end: Int, val style: TextStyle) : Piece

    /** A `<glyph:…>` tag: the reference it names (`ui/coin`, `acme:ui/coin`). */
    class Glyph(val reference: String, override val at: Int, override val end: Int, val style: TextStyle) : Piece

    /** A line break; the next line starts at [end]. */
    class Break(override val at: Int, override val end: Int) : Piece

    /** A `<space:N>` tag: the cursor moves [px] pixels. */
    class Move(val px: Int, override val at: Int, override val end: Int) : Piece

    /** An `<at>` tag that moves ([SpaceTags]): the pieces up to its [OverlayEnd] draw over the line and take no width. */
    class Overlay(val spec: SpaceTags.At, override val at: Int, override val end: Int) : Piece

    /** Where an [Overlay] closes (its closing tag, a tag closing it, `<reset>`, or the text's end). */
    class OverlayEnd(override val at: Int, override val end: Int) : Piece

    /** Minecraft's sixteen named colours, and the `grey` spellings. */
    val NAMED_COLORS: Map<String, String> = mapOf(
        "black" to "#000000",
        "dark_blue" to "#0000aa",
        "dark_green" to "#00aa00",
        "dark_aqua" to "#00aaaa",
        "dark_red" to "#aa0000",
        "dark_purple" to "#aa00aa",
        "gold" to "#ffaa00",
        "gray" to "#aaaaaa",
        "grey" to "#aaaaaa",
        "dark_gray" to "#555555",
        "dark_grey" to "#555555",
        "blue" to "#5555ff",
        "green" to "#55ff55",
        "aqua" to "#55ffff",
        "red" to "#ff5555",
        "light_purple" to "#ff55ff",
        "yellow" to "#ffff55",
        "white" to "#ffffff"
    )

    private val DECORATIONS: Map<String, (TextStyle, Boolean) -> TextStyle> = mapOf(
        "bold" to { s, on -> s.copy(bold = on) },
        "b" to { s, on -> s.copy(bold = on) },
        "italic" to { s, on -> s.copy(italic = on) },
        "i" to { s, on -> s.copy(italic = on) },
        "em" to { s, on -> s.copy(italic = on) },
        "underlined" to { s, on -> s.copy(underlined = on) },
        "u" to { s, on -> s.copy(underlined = on) },
        "strikethrough" to { s, on -> s.copy(strikethrough = on) },
        "st" to { s, on -> s.copy(strikethrough = on) },
        "obfuscated" to { s, on -> s.copy(obfuscated = on) },
        "obf" to { s, on -> s.copy(obfuscated = on) }
    )

    private val HEX = Regex("^#[0-9a-fA-F]{6}$")

    private fun colorOf(token: String?): String? = when {
        token == null -> null
        HEX.matches(token) -> token.lowercase()
        else -> NAMED_COLORS[token.lowercase()]
    }

    /** [style] after the opening tag [tag] (its inside, `color:gold`); unchanged for a tag that styles nothing. */
    private fun styled(style: TextStyle, tag: String): TextStyle {
        val parts = tag.split(':')
        val name = parts[0].lowercase()
        val first = parts.getOrNull(1)
        val color = when (name) {
            "color", "colour", "c", "gradient" -> colorOf(first)
            "rainbow" -> NAMED_COLORS.getValue("red")
            else -> colorOf(name)
        }
        if (color != null) return style.copy(color = color)
        DECORATIONS[name]?.let { return it(style, first != "false") }
        if (name.startsWith("!")) DECORATIONS[name.substring(1)]?.let { return it(style, false) }
        return style
    }

    /** The pieces of MiniMessage [text], in order. */
    fun pieces(text: String): List<Piece> {
        val out = mutableListOf<Piece>()
        val stack = mutableListOf(TextStyle())
        // Parallel to the stack: whether each entry is an `<at>` that moves, which ends an overlay when it closes.
        val overlays = mutableListOf(false)
        fun pop(down: Int, at: Int, end: Int) {
            while (stack.size > down) {
                stack.removeAt(stack.lastIndex)
                if (overlays.removeAt(overlays.lastIndex)) out += OverlayEnd(at, end)
            }
        }
        var cursor = 0
        while (cursor < text.length) {
            val c = text[cursor]
            if (c == '\n') {
                out += Break(cursor, cursor + 1)
                cursor++
                continue
            }
            if (c == '<') {
                val close = text.indexOf('>', cursor)
                if (close > cursor) {
                    val tag = text.substring(cursor + 1, close)
                    val closing = tag.startsWith("/")
                    val name = tag.removePrefix("/").substringBefore(':').lowercase()
                    val glyph = if (closing) null else GlyphTags.reference(tag)
                    when {
                        name == "newline" || name == "br" -> out += Break(cursor, close + 1)
                        glyph != null -> out += Glyph(glyph, cursor, close + 1, stack.last())
                        // A stray </glyph> or </space> closes nothing.
                        closing && (name == GlyphTags.NAME || name == SpaceTags.SPACE) -> Unit
                        name == SpaceTags.SPACE -> SpaceTags.space(SpaceTags.arguments(tag))?.let { out += Move(it, cursor, close + 1) }
                        name == "reset" -> pop(1, cursor, close + 1)
                        closing -> if (stack.size > 1) pop(stack.size - 1, cursor, close + 1)
                        name == SpaceTags.AT -> {
                            val spec = SpaceTags.at(SpaceTags.arguments(tag))
                            if (spec != null) out += Overlay(spec, cursor, close + 1)
                            stack += stack.last()
                            overlays += spec != null
                        }
                        else -> {
                            stack += styled(stack.last(), tag)
                            overlays += false
                        }
                    }
                    cursor = close + 1
                    continue
                }
            }
            val code = if (c.isHighSurrogate() && cursor + 1 < text.length && text[cursor + 1].isLowSurrogate()) {
                0x10000 + ((c.code - 0xD800) shl 10) + (text[cursor + 1].code - 0xDC00)
            } else {
                c.code
            }
            val size = if (code >= 0x10000) 2 else 1
            out += Char(code, text.substring(cursor, cursor + size), cursor, cursor + size, stack.last())
            cursor += size
        }
        pop(1, text.length, text.length)
        return out
    }
}
