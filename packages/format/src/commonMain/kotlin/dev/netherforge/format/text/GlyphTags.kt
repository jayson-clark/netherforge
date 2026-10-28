package dev.netherforge.format.text

/**
 * `<glyph:ui/coin>`: a pack glyph inside MiniMessage text, named like any
 * reference (`<pack>/<key>`, or `<namespace>:<pack>/<key>` for a package's).
 *
 * MiniMessage splits a tag's arguments at `:` (an argument in `'…'` or `"…"`
 * may hold colons), so `<glyph:acme:ui/coin>` arrives as `acme`, `ui/coin`
 * and `<glyph:'acme:ui/coin'>` as `acme:ui/coin`; both name the glyph
 * `acme:ui/coin`, the arguments joined with `:`. The tag inserts the glyph
 * and closes nothing.
 *
 * Shared so the validator, text measuring and the plugin's tag resolver read
 * the same references out of the same text.
 */
object GlyphTags {
    const val NAME = "glyph"

    /** One `<glyph:…>` in a text: the reference it names and where the tag is (`start` at `<`, `end` after `>`). */
    data class Found(val reference: String, val start: Int, val end: Int)

    /**
     * The reference a tag names, from the tag's inside without the angle
     * brackets (`glyph:ui/coin`), or null when it isn't a glyph tag. A glyph
     * tag with no arguments names `""`.
     */
    fun reference(tag: String): String? {
        val colon = tag.indexOf(':')
        val name = if (colon < 0) tag else tag.substring(0, colon)
        if (!name.equals(NAME, ignoreCase = true)) return null
        if (colon < 0) return ""
        return arguments(tag.substring(colon + 1)).joinToString(":")
    }

    /** MiniMessage's argument split: at `:`, except inside quotes; quotes removed, `\` escapes the next character in quotes. */
    fun arguments(text: String): List<String> {
        val out = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quote != null && c == '\\' && i + 1 < text.length -> {
                    current.append(text[i + 1])
                    i++
                }
                quote != null && c == quote -> quote = null
                quote == null && (c == '\'' || c == '"') && current.isEmpty() -> quote = c
                quote == null && c == ':' -> {
                    out += current.toString()
                    current.clear()
                }
                else -> current.append(c)
            }
            i++
        }
        out += current.toString()
        return out
    }

    /** Every glyph tag in MiniMessage [text], in order. A tag escaped with `\<` isn't one. */
    fun find(text: String): List<Found> {
        val out = mutableListOf<Found>()
        var cursor = 0
        while (cursor < text.length) {
            val open = text.indexOf('<', cursor)
            if (open < 0) break
            if (open > 0 && text[open - 1] == '\\') {
                cursor = open + 1
                continue
            }
            val close = text.indexOf('>', open)
            if (close < 0) break
            reference(text.substring(open + 1, close))?.let { out += Found(it, open, close + 1) }
            cursor = close + 1
        }
        return out
    }

    /**
     * [text] with each glyph tag rewritten as [replace] answers for it: new
     * text for its reference (the tag becomes `<glyph:new>`), `""` to drop
     * the tag, or null to leave it as it was. Null when nothing changed.
     */
    fun rewrite(text: String, replace: (Found) -> String?): String? {
        if ('<' !in text) return null
        var changed = false
        val out = StringBuilder()
        var cursor = 0
        for (tag in find(text)) {
            val next = replace(tag) ?: continue
            out.append(text, cursor, tag.start)
            if (next.isNotEmpty()) out.append("<$NAME:$next>")
            cursor = tag.end
            changed = true
        }
        if (!changed) return null
        return out.append(text, cursor, text.length).toString()
    }
}
