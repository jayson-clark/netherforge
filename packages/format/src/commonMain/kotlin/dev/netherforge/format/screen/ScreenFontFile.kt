package dev.netherforge.format.screen

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.project.Names
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `screen_fonts/<id>/font.json`: a font screens write text in, drawn by the server into maps' pixels (players
 * never download it). One of three [type]s:
 *
 * - [ScreenFontType.PIXELS]: its characters drawn right here, in [glyphs], a row of `#` (drawn) and `.` (not) at a
 *   time: a pixel-art font, no other file;
 * - [ScreenFontType.BITMAP]: a picture of its characters in a grid ([file], a PNG; [chars] is the grid's rows), as
 *   the game's own bitmap fonts are drawn, each character as wide as its opaque columns;
 * - [ScreenFontType.TRUETYPE]: a TrueType or OpenType [file] the server draws at [size] pixels.
 *
 * The game's own map font is always there as `default`, and needs no file.
 */
@Serializable
data class ScreenFontFile(
    @SerialName("\$schema") val schema: String? = null,
    val type: ScreenFontType,
    /** A bitmap font's picture (`.png`) or a TrueType font's file (`.ttf`, `.otf`), in the font's folder. */
    val file: String? = null,
    /** A bitmap font's characters, one string a row of its grid, every row as long; `\u0000` leaves a cell empty. */
    val chars: List<String>? = null,
    /**
     * A pixels font's characters, each one character long, drawn as rows of `#` (a pixel) and `.` (none), every
     * row of a character as long and every character as tall.
     */
    val glyphs: Map<String, List<String>>? = null,
    /** A TrueType font's size, in pixels (4 to [MAX_SIZE], default [DEFAULT_SIZE]). */
    val size: Int? = null,
    /** Whether a TrueType font's edges are blended into what's behind them (default false: crisp pixels). */
    val antialias: Boolean? = null,
    /** Pixels between one character and the next (0 to [MAX_SPACING], default 1). */
    val spacing: Int? = null,
    /** How wide a space is, in pixels; absent: a third of a character's height, as the game's fonts have. */
    val spaceWidth: Int? = null,
    /** Pixels from one line's top to the next's; absent: the characters' height and 1. */
    val lineHeight: Int? = null
) {
    val sizeOrDefault: Int get() = size ?: DEFAULT_SIZE
    val spacingOrDefault: Int get() = spacing ?: 1
    val antialiasOrDefault: Boolean get() = antialias ?: false

    companion object {
        const val FILE_NAME = "font.json"
        const val SCHEMA = "../../.netherforge/schema/screen_font.schema.json"

        /** The game's own map font, which every project has: no file names it. */
        const val DEFAULT = "default"

        const val DEFAULT_SIZE = 16
        const val MAX_SIZE = 128
        const val MAX_SPACING = 16
        const val MAX_LINE_HEIGHT = 256

        /** In a pixels font's rows: a pixel drawn, and one not. */
        const val INK = '#'
        const val BLANK = '.'
    }
}

@Serializable
enum class ScreenFontType(val extensions: List<String>) {
    @SerialName("pixels")
    PIXELS(emptyList()),

    @SerialName("bitmap")
    BITMAP(listOf(".png")),

    @SerialName("truetype")
    TRUETYPE(listOf(".ttf", ".otf"))
}

object ScreenFontValidator {
    fun validate(file: ScreenFontFile, sink: ProblemSink, files: Set<String>?) {
        val name = typeName(file.type)
        if (file.type == ScreenFontType.PIXELS) {
            if (file.file !=
                null
            ) {
                sink.report(
                    ProblemCodes.SCREEN_FONT_FIELD,
                    "file is a bitmap or TrueType font's: a pixels font draws its glyphs",
                    "$.file"
                )
            }
        } else {
            val path = file.file
            val shape = file.type.extensions.joinToString(" or ")
            if (path == null || !Names.isRelativeFile(path) || file.type.extensions.none { path.lowercase().endsWith(it) }) {
                sink.report(ProblemCodes.SCREEN_FONT_FILE, "A $name font's file is a $shape file in its folder", "$.file")
            } else if (files != null && path !in files) {
                sink.report(ProblemCodes.SCREEN_FONT_MISSING, "\"$path\" isn't in the font's folder", "$.file")
            }
        }
        if (file.type != ScreenFontType.BITMAP &&
            file.chars != null
        ) {
            sink.report(ProblemCodes.SCREEN_FONT_FIELD, "chars is a bitmap font's", "$.chars")
        }
        if (file.type != ScreenFontType.PIXELS &&
            file.glyphs != null
        ) {
            sink.report(ProblemCodes.SCREEN_FONT_FIELD, "glyphs is a pixels font's", "$.glyphs")
        }
        if (file.type != ScreenFontType.TRUETYPE) {
            if (file.size != null) sink.report(ProblemCodes.SCREEN_FONT_FIELD, "size is a TrueType font's", "$.size")
            if (file.antialias != null) sink.report(ProblemCodes.SCREEN_FONT_FIELD, "antialias is a TrueType font's", "$.antialias")
        }
        when (file.type) {
            ScreenFontType.PIXELS -> glyphs(file, sink)
            ScreenFontType.BITMAP -> {
                val chars = file.chars
                if (chars.isNullOrEmpty() || chars.all { it.isEmpty() }) {
                    sink.report(
                        ProblemCodes.SCREEN_FONT_CHARS,
                        "A bitmap font lists its characters in chars, a row of its grid each",
                        "$.chars"
                    )
                } else {
                    val width = chars.first().codePointCount()
                    chars.forEachIndexed { row, line ->
                        if (line.codePointCount() != width) {
                            sink.report(
                                ProblemCodes.SCREEN_FONT_CHARS,
                                "Every row of chars is as long as the first ($width characters), but this one has ${line.codePointCount()}",
                                "$.chars[$row]"
                            )
                        }
                    }
                }
            }
            ScreenFontType.TRUETYPE -> file.size?.let {
                if (it !in 4..ScreenFontFile.MAX_SIZE) {
                    sink.report(ProblemCodes.SCREEN_FONT_SIZE, "size is 4 to ${ScreenFontFile.MAX_SIZE} pixels, not $it", "$.size")
                }
            }
        }
        file.spacing?.let {
            if (it !in 0..ScreenFontFile.MAX_SPACING) {
                sink.report(ProblemCodes.SCREEN_FONT_SIZE, "spacing is 0 to ${ScreenFontFile.MAX_SPACING} pixels, not $it", "$.spacing")
            }
        }
        file.spaceWidth?.let {
            if (it !in 0..ScreenFontFile.MAX_SIZE) {
                sink.report(ProblemCodes.SCREEN_FONT_SIZE, "spaceWidth is 0 to ${ScreenFontFile.MAX_SIZE} pixels, not $it", "$.spaceWidth")
            }
        }
        file.lineHeight?.let {
            if (it !in 1..ScreenFontFile.MAX_LINE_HEIGHT) {
                sink.report(
                    ProblemCodes.SCREEN_FONT_SIZE,
                    "lineHeight is 1 to ${ScreenFontFile.MAX_LINE_HEIGHT} pixels, not $it",
                    "$.lineHeight"
                )
            }
        }
    }

    /** A pixels font's glyphs: one character each, rows of `#` and `.`, each as wide, all as tall. */
    private fun glyphs(file: ScreenFontFile, sink: ProblemSink) {
        val glyphs = file.glyphs
        if (glyphs.isNullOrEmpty()) {
            sink.report(
                ProblemCodes.SCREEN_FONT_GLYPH,
                "A pixels font draws its characters in glyphs, like { \"A\": [\".#.\", \"#.#\"] }",
                "$.glyphs"
            )
            return
        }
        val height = glyphs.values.first().size
        for ((char, rows) in glyphs) {
            val at = dev.netherforge.format.json.CanonicalJson.childPath("$.glyphs", char)
            if (char.codePointCount() != 1) {
                sink.report(ProblemCodes.SCREEN_FONT_GLYPH, "\"$char\" isn't one character", at)
                continue
            }
            if (rows.isEmpty() || rows.size > ScreenFontFile.MAX_SIZE) {
                sink.report(ProblemCodes.SCREEN_FONT_GLYPH, "A character is 1 to ${ScreenFontFile.MAX_SIZE} rows tall", at)
                continue
            }
            if (rows.size != height) {
                sink.report(
                    ProblemCodes.SCREEN_FONT_GLYPH,
                    "Every character is as tall ($height row${if (height == 1) "" else "s"}), but this one has ${rows.size}",
                    at
                )
            }
            val width = rows.first().length
            rows.forEachIndexed { row, line ->
                if (line.length != width || line.length > ScreenFontFile.MAX_SIZE) {
                    sink.report(
                        ProblemCodes.SCREEN_FONT_GLYPH,
                        "Every row of a character is as wide (at most ${ScreenFontFile.MAX_SIZE})",
                        "$at[$row]"
                    )
                } else if (line.any { it != ScreenFontFile.INK && it != ScreenFontFile.BLANK }) {
                    sink.report(ProblemCodes.SCREEN_FONT_GLYPH, "A row is # for a pixel and . for none, not \"$line\"", "$at[$row]")
                }
            }
        }
    }

    /** A bitmap font's grid: how many characters across and rows down, when [ScreenFontFile.chars] says. */
    fun grid(file: ScreenFontFile): Pair<Int, Int>? {
        val chars = file.chars?.takeIf { it.isNotEmpty() } ?: return null
        val across = chars.first().codePointCount()
        return if (across == 0) null else across to chars.size
    }

    private fun typeName(type: ScreenFontType) = when (type) {
        ScreenFontType.PIXELS -> "pixels"
        ScreenFontType.BITMAP -> "bitmap"
        ScreenFontType.TRUETYPE -> "TrueType"
    }

    /** How many characters (code points, not UTF-16 units) [this] holds. */
    fun String.codePointCount(): Int {
        var count = 0
        var i = 0
        while (i < length) {
            i += if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) 2 else 1
            count++
        }
        return count
    }

    /** [this] as code points, in order. */
    fun String.codePointList(): List<Int> {
        val out = ArrayList<Int>(length)
        var i = 0
        while (i < length) {
            val c = this[i]
            if (c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
                out += ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
                i += 2
            } else {
                out += c.code
                i++
            }
        }
        return out
    }
}
