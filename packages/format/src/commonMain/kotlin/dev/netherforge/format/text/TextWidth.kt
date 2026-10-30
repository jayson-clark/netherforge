package dev.netherforge.format.text

import dev.netherforge.format.resourcepack.PackFonts

/**
 * The pixel width MiniMessage text draws at in the default font, exactly or
 * not at all: for the server, which lays out menus and text displays with it
 * (`nf.text.width`) and must never guess.
 *
 * Unlike [TextMetrics] (the editor's previews, which estimate what they don't
 * know), anything this can't measure exactly makes the whole answer null: a
 * character the advances don't cover, a glyph tag whose advance is unknown, a
 * tag that inserts text only the client resolves (`<lang>`, `<key>`,
 * `<score>`…), or a font other than the default.
 *
 * Shared by the plugin and the editor, so both measure alike. It's deliberately
 * not built on [TextMetrics] or [MiniMessagePass]: see [TextMetrics] for why
 * the two measurers stay apart.
 */
class TextWidth(
    /** A character's advance by code point, gap included (from [DefaultFontFile]); null when unknown. */
    private val advance: (codePoint: Int) -> Int?,
    /**
     * A pack glyph's bare character's advance by code point (what `nf.text.glyph` gives, which takes the text's
     * colour where the tag doesn't), or a wide glyph's piece's; null for a character that isn't a glyph's.
     */
    private val glyphChar: (codePoint: Int) -> Int? = { null },
    /** A pack glyph's advance by reference (`ui:coin`), for `<glyph:ui:coin>`; null when unknown. */
    private val glyph: (reference: String) -> Int? = { null }
) {
    constructor(
        font: DefaultFontFile,
        glyphChar: (codePoint: Int) -> Int? = { null },
        glyph: (reference: String) -> Int? = { null }
    ) : this(font::advance, glyphChar, glyph)

    /**
     * The width of [text] in pixels, or null when it can't be measured
     * exactly. Tags draw nothing, except `<bold>` (a pixel more per character,
     * as the game draws bold), `<glyph:pack:key>` (the glyph's advance),
     * `<space:N>` (N, bold or not) and `<at:N>…</at>` (nothing: what it
     * holds is drawn over the line, [SpaceTags]). The pack's space characters
     * ([PackFonts.offset]) measure as the moves they are, and a glyph's bare
     * characters as the glyph (bold like any character, as the game draws them). Text with line
     * breaks (`\n`, `<newline>`, `<br>`) measures as its widest line. A tag
     * MiniMessage doesn't know is drawn as written, so it's measured as text;
     * `\<` is a literal `<`.
     */
    fun measure(text: String): Int? {
        val lines = walk(text).lines
        if (lines.any { !it.known }) return null
        return maxOf(0, lines.maxOf { it.width })
    }

    /** How many lines [text] draws as when nothing wraps it: one more than its line breaks. */
    fun lineCount(text: String): Int = walk(text).lines.size

    /**
     * [text] with every `<at>` turned into the moves that place it: a
     * `<space>` before the tag (from the cursor to where the inside starts)
     * and one where it closes (back to where the cursor was), which is what
     * the server parses. An `<at>` that can't be placed exactly (its inside,
     * or the line it's counted from, can't be measured) is left as it is,
     * which draws its inside in line, and is listed in [Layout.unplaced].
     */
    fun layout(text: String): Layout {
        if (!text.contains("<${SpaceTags.AT}", ignoreCase = true)) return Layout(text, emptyList())
        val inserts = mutableListOf<Pair<Int, String>>()
        val unplaced = mutableListOf<String>()
        for (at in walk(text).ats) {
            val lead = lead(at)
            val inner = at.inner
            if (lead == null || inner == null || !at.known) {
                unplaced += at.tag
                continue
            }
            inserts += at.openAt to moves(lead)
            inserts += at.closeAt to moves(-(lead + inner))
        }
        if (inserts.isEmpty()) return Layout(text, unplaced)
        val out = StringBuilder()
        var cursor = 0
        for ((at, moves) in inserts.sortedBy { it.first }) {
            out.append(text, cursor, at).append(moves)
            cursor = at
        }
        return Layout(out.append(text, cursor, text.length).toString(), unplaced)
    }

    /** What [layout] made of a text: the MiniMessage to parse, and the `<at>` tags it couldn't place. */
    class Layout(val text: String, val unplaced: List<String>)

    /** How far the cursor moves to where [at]'s inside starts; null when that can't be known. */
    private fun lead(at: Frame): Int? {
        val inner = at.inner ?: return null
        val line = at.line
        val anchor = when (at.spec.from) {
            SpaceTags.From.CURSOR -> return at.spec.x - shift(at.spec.align, inner)
            SpaceTags.From.START -> 0
            // The game centres on the whole line's width, halved in integers.
            SpaceTags.From.CENTER -> if (line.known) line.width / 2 else return null
            SpaceTags.From.END -> if (line.known) line.width else return null
        }
        if (!at.cursorKnown) return null
        return anchor + at.spec.x - shift(at.spec.align, inner) - at.cursor
    }

    private fun shift(align: SpaceTags.Align, inner: Int) = when (align) {
        SpaceTags.Align.LEFT -> 0
        SpaceTags.Align.CENTER -> inner / 2
        SpaceTags.Align.RIGHT -> inner
    }

    /** `<space>` tags moving [px], as many as it takes past [PackFonts.MAX_OFFSET]; empty for zero. */
    private fun moves(px: Int): String = buildString {
        var left = px
        while (left != 0) {
            val step = left.coerceIn(-PackFonts.MAX_OFFSET, PackFonts.MAX_OFFSET)
            append(SpaceTags.tag(step))
            left -= step
        }
    }

    /** One line as walked: its width so far (at the end, its width), and whether every part of it measured. */
    private class Line {
        var width = 0
        var known = true
    }

    /**
     * An `<at>` as walked: what it says, where its tag starts (`openAt`), the
     * tag as written, the line it's on and the cursor there; once it closes,
     * where (`closeAt`: the closing tag's `<`, or the text's end) and its
     * inside's width. [known] is false once anything inside it couldn't be
     * measured.
     */
    private class Frame(
        val spec: SpaceTags.At,
        val openAt: Int,
        val tag: String,
        val line: Line,
        val cursor: Int,
        val cursorKnown: Boolean
    ) {
        var closeAt = -1
        var inner: Int? = null
        var known = true
    }

    /** An open tag: its name, whether it leaves text bold, and the `<at>` it is, if it's one that moves. */
    private class Open(val name: String, val bold: Boolean, val frame: Frame?)

    private class Walk(val lines: List<Line>, val ats: List<Frame>)

    /** Every line of [text] measured, and every `<at>` in it, with where it closes and how wide its inside is. */
    private fun walk(text: String): Walk {
        val open = mutableListOf<Open>()
        val bold = { open.lastOrNull()?.bold ?: false }
        var line = Line()
        val lines = mutableListOf(line)
        val ats = mutableListOf<Frame>()
        fun unknown() {
            line.known = false
            for (it in open) it.frame?.known = false
        }
        fun char(code: Int) {
            val width = PackFonts.spaceAdvance(code) ?: glyphChar(code) ?: advance(code) ?: return unknown()
            line.width += if (bold()) width + 1 else width
        }

        // Closes the tags open from [from] on, innermost first: an `<at>` gives its inside's width back.
        fun close(from: Int, at: Int) {
            for (index in open.lastIndex downTo from) {
                val frame = open.removeAt(index).frame ?: continue
                frame.closeAt = at
                if (frame.line !== line) {
                    frame.known = false
                    continue
                }
                frame.inner = line.width - frame.cursor
                line.width = frame.cursor
            }
        }

        var cursor = 0
        while (cursor < text.length) {
            val c = text[cursor]
            if (c == '\\' && cursor + 1 < text.length && (text[cursor + 1] == '<' || text[cursor + 1] == '\\')) {
                char(text[cursor + 1].code)
                cursor += 2
                continue
            }
            if (c == '\n') {
                line = Line().also { lines += it }
                cursor++
                continue
            }
            if (c == '<') {
                val end = text.indexOf('>', cursor)
                val inside = if (end > cursor) text.substring(cursor + 1, end) else null
                val tag = inside?.let(Tag::parse)
                val kind = if (inside != null && tag != null) tag.kind(inside) else Kind.LITERAL
                if (inside != null && tag != null && kind != Kind.LITERAL) {
                    when (kind) {
                        Kind.BREAK -> line = Line().also { lines += it }
                        // A pack glyph is never drawn bold: it's a picture, maybe in pieces ([PackFonts.picture]).
                        Kind.GLYPH -> {
                            val width = GlyphTags.reference(inside)?.let(glyph)
                            if (width == null) unknown() else line.width += width
                        }
                        Kind.SPACE -> SpaceTags.space(SpaceTags.arguments(inside))?.let { line.width += it }
                        Kind.AT -> {
                            val frame = SpaceTags.at(SpaceTags.arguments(inside))?.let {
                                Frame(it, cursor, "<$inside>", line, line.width, line.known).also(ats::add)
                            }
                            open += Open(tag.name, bold(), frame)
                        }
                        Kind.UNMEASURABLE -> unknown()
                        Kind.RESET -> close(0, cursor)
                        Kind.STYLE -> if (tag.closing) {
                            // A closing tag closes the latest one of its name and everything opened inside it.
                            val at = if (tag.name.isEmpty()) open.lastIndex else open.indexOfLast { it.name == tag.name }
                            if (at >= 0) close(at, cursor)
                        } else {
                            open += Open(tag.name, tag.bold ?: bold(), null)
                        }
                        Kind.LITERAL -> Unit
                    }
                    cursor = end + 1
                    continue
                }
            }
            val code = if (c.isHighSurrogate() && cursor + 1 < text.length && text[cursor + 1].isLowSurrogate()) {
                0x10000 + ((c.code - 0xD800) shl 10) + (text[cursor + 1].code - 0xDC00)
            } else {
                c.code
            }
            char(code)
            cursor += if (code >= 0x10000) 2 else 1
        }
        close(0, text.length)
        return Walk(lines, ats)
    }

    private enum class Kind { STYLE, BREAK, GLYPH, SPACE, AT, RESET, UNMEASURABLE, LITERAL }

    /** A tag's name (lowercase, without `/` or `!`), whether it closes, and whether it turns bold on or off. */
    private class Tag(val name: String, val closing: Boolean, val bold: Boolean?) {
        fun kind(inside: String): Kind = when {
            closing -> if (name.isEmpty() || name in STYLES || name.startsWith("#") || name in OWN) Kind.STYLE else Kind.LITERAL
            name == "newline" || name == "br" -> Kind.BREAK
            name == GlyphTags.NAME -> Kind.GLYPH
            name == SpaceTags.SPACE -> Kind.SPACE
            name == SpaceTags.AT -> Kind.AT
            name == "reset" -> Kind.RESET
            name == "font" -> if (GlyphTags.arguments(inside.substringAfter(':', "")).joinToString(":") in
                DEFAULT_FONTS
            ) {
                Kind.STYLE
            } else {
                Kind.UNMEASURABLE
            }
            name in INSERTS -> Kind.UNMEASURABLE
            name in STYLES || HEX.matches(name) -> Kind.STYLE
            else -> Kind.LITERAL
        }

        companion object {
            private val NAME = Regex("^[a-z0-9_#-]+$")
            private val HEX = Regex("^#[0-9a-f]{6}$")

            fun parse(inside: String): Tag? {
                var rest = inside
                val closing = rest.startsWith("/")
                if (closing) rest = rest.substring(1)
                val negated = !closing && rest.startsWith("!")
                if (negated) rest = rest.substring(1)
                val name = rest.substringBefore(':').lowercase()
                if (name.isEmpty()) return if (closing && rest.isEmpty()) Tag("", true, null) else null
                if (!NAME.matches(name)) return null
                val bold = when {
                    name != "bold" && name != "b" -> null
                    negated -> false
                    else -> rest.substringAfter(':', "true").lowercase() != "false"
                }
                return Tag(name, closing, bold)
            }
        }
    }

    companion object {
        /** Tags that only style, open and close, and draw nothing of their own. */
        private val STYLES = setOf(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple", "gold", "gray", "grey", "dark_gray",
            "dark_grey", "blue", "green", "aqua", "red", "light_purple", "yellow", "white",
            "color", "colour", "c", "shadow",
            "bold", "b", "italic", "em", "i", "underlined", "u", "strikethrough", "st", "obfuscated", "obf",
            "click", "hover", "insert", "insertion", "rainbow", "gradient", "transition", "pride", "font"
        )

        /** Tags whose text only the client knows (a translation, a key binding, a score…): nothing to measure. */
        private val INSERTS = setOf(
            "key", "lang", "tr", "translate", "lang_or", "tr_or", "translate_or", "selector", "sel", "score", "nbt", "data",
            "sprite", "head"
        )

        private val DEFAULT_FONTS = setOf("default", "minecraft:default")

        /** NetherForge's own tags, which a closing tag of the same name closes. */
        private val OWN = setOf(GlyphTags.NAME, SpaceTags.SPACE, SpaceTags.AT)
    }
}
