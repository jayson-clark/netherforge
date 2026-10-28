package dev.netherforge.format.text

import dev.netherforge.format.resourcepack.PackFonts

/**
 * Moving the cursor inside MiniMessage text.
 *
 * - `<space:N>` moves it N GUI pixels (negative is left), as the space
 *   characters the pack's default font holds ([PackFonts.offset]). It inserts
 *   them and closes nothing; N is held to ±[PackFonts.MAX_OFFSET].
 * - `<at:N>…</at>` draws what's inside N pixels from the cursor and takes no
 *   width: the server measures the inside ([TextWidth.layout]) and moves the
 *   cursor back after it, so the text after it doesn't shift. Options follow
 *   as `key=value` arguments: `from=cursor|start|center|end` (what N is
 *   counted from: the cursor, or the start, centre or end of the line the tag
 *   is on, measured without anything inside an `<at>`) and
 *   `align=left|center|right` (which edge of the inside N puts there).
 *   N may be any whole number (the moves are as many spaces as it takes).
 *   `<at:-92:from=center:align=right><glyph:ui/head></at>` puts a picture's
 *   right edge 92 pixels left of a centred title's middle, whatever the title.
 *
 * Shared so the server's measure, its expansion and the editor's previews read
 * the same tags.
 */
object SpaceTags {
    const val SPACE = "space"
    const val AT = "at"

    /** What an `<at>`'s N is counted from. */
    enum class From { CURSOR, START, CENTER, END }

    /** Which edge of an `<at>`'s inside lands at N. */
    enum class Align { LEFT, CENTER, RIGHT }

    /** An `<at>` tag read: how far, from what, and aligned how. */
    data class At(val x: Int, val from: From = From.CURSOR, val align: Align = Align.LEFT)

    /** How far `<space:N>` moves, from the tag's arguments (after `space:`); null when they aren't one whole number. */
    fun space(arguments: List<String>): Int? {
        val px = arguments.singleOrNull()?.trim()?.toIntOrNull() ?: return null
        return px.coerceIn(-PackFonts.MAX_OFFSET, PackFonts.MAX_OFFSET)
    }

    /**
     * An `<at>` from its arguments (after `at:`): a whole number, then
     * `from=…` and `align=…` in any order. Null when they don't read, and then
     * the tag only scopes what's inside it (it moves nothing).
     */
    fun at(arguments: List<String>): At? {
        val x = arguments.firstOrNull()?.trim()?.toIntOrNull() ?: return null
        var at = At(x)
        for (option in arguments.drop(1)) {
            val key = option.substringBefore('=').trim().lowercase()
            val value = option.substringAfter('=', "").trim().uppercase()
            at = when (key) {
                "from" -> at.copy(from = From.entries.firstOrNull { it.name == value } ?: return null)
                "align" -> at.copy(align = Align.entries.firstOrNull { it.name == value } ?: return null)
                else -> return null
            }
        }
        return at
    }

    /** The arguments of a tag's inside (`space:-3` → `-3`), split as MiniMessage splits them. */
    fun arguments(inside: String): List<String> {
        val colon = inside.indexOf(':')
        return if (colon < 0) emptyList() else GlyphTags.arguments(inside.substring(colon + 1))
    }

    /** `<space:N>` as written. */
    fun tag(px: Int): String = "<$SPACE:$px>"
}
