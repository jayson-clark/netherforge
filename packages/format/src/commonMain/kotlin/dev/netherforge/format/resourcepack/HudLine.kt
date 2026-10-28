package dev.netherforge.format.resourcepack

import dev.netherforge.format.project.ImageInfo
import kotlin.math.round

/**
 * A pack's HUD elements with their characters settled: each picture a character of the pack's own font
 * ([font], `<ns>:<pack>/hud`), with how tall it's drawn, its ascent (what puts it at its height on the screen)
 * and its advance (how far it moves the cursor, from its pixels; null when they couldn't be read).
 */
class CompiledHud(
    val font: String,
    val hide: Set<HudElement>,
    val bars: Map<String, Bar>,
    val icons: Map<String, Icons>,
    val texts: Map<String, HudTextDef>
) {
    /**
     * One picture: what draws it (its character, or for a picture wider than a font sheet its pieces side by side,
     * whose characters are [cells]: [PackFonts.picture]), the texture it draws, how tall, its ascent and its advance.
     */
    class Piece(val char: String, val texture: String, val height: Int, val ascent: Int, val advance: Int?, val cells: String = char)

    class Bar(val def: HudBarDef, val full: Piece, val empty: Piece, val half: Piece?) {
        /** The width each segment takes: the widest picture's, and the gap. Null when a picture's width isn't known. */
        val slot: Int? get() = listOfNotNull(full, empty, half).map { it.advance ?: return null }.max() + (def.gap ?: 0)
    }

    class Icons(val def: HudIconsDef, val icons: Map<String, Piece>)

    /** Every picture, in the order the font lists them. */
    val pieces: List<Piece> get() = bars.values.flatMap { listOfNotNull(it.full, it.empty, it.half) } +
        icons.values.flatMap { it.icons.values }

    companion object {
        /** How tall a picture whose pixels couldn't be read is drawn: a line of text's. */
        const val DEFAULT_HEIGHT = 9

        /**
         * [def] of pack [id] in [namespace], its pictures numbered from the start of the private-use block in the
         * pack's own HUD font; [image] answers a texture's pixel facts by its `textures/` path, or null.
         */
        fun compile(namespace: String, id: String, def: HudDef, image: (texture: String) -> ImageInfo?): CompiledHud {
            var next = 0
            // A picture cut into pieces takes a character per further piece, after every picture's own.
            var nextPiece = def.bars.values.sumOf { if (it.half != null) 3 else 2 } + def.icons.values.sumOf { it.icons.size }
            val extra = { nextPiece.takeIf { it < PackFonts.PUA_COUNT }?.let { PackFonts.charAt(nextPiece++) } }
            fun piece(texture: String, y: Int): Piece {
                val facts = image(texture)?.takeIf { it.height > 0 }
                // Drawn at its own size: a HUD picture's pixels are the screen's.
                val height = facts?.height ?: DEFAULT_HEIGHT
                val drawn = PackFonts.picture(PackFonts.charAt(next++), facts, height, extra)
                return Piece(drawn.text, texture, height, y + height - HudDef.BASELINE, drawn.advance, drawn.cells)
            }
            val bars = def.bars.entries.sortedBy { it.key }.associate { (key, bar) ->
                key to Bar(bar, piece(bar.full, bar.y), piece(bar.empty, bar.y), bar.half?.let { piece(it, bar.y) })
            }
            val icons = def.icons.entries.sortedBy { it.key }.associate { (key, row) ->
                key to Icons(row, row.icons.entries.sortedBy { it.key }.associate { (icon, texture) -> icon to piece(texture, row.y) })
            }
            return CompiledHud(
                "$namespace:$id/$FONT",
                def.hide.orEmpty().toSet(),
                bars,
                icons,
                def.texts.entries.sortedBy { it.key }.associate { it.key to it.value }
            )
        }

        /** The font a pack's HUD elements are in: `<namespace>:<pack>/hud`. */
        const val FONT = "hud"
    }
}

/**
 * What one player's HUD shows of the pack's elements, by key: a bar's fill, the icons a row shows (in order), a
 * line's text (MiniMessage). An element it doesn't have isn't drawn.
 */
data class HudState(
    val bars: Map<String, BarFill> = emptyMap(),
    val icons: Map<String, List<String>> = emptyMap(),
    val texts: Map<String, String> = emptyMap()
) {
    val isEmpty: Boolean get() = bars.isEmpty() && icons.isEmpty() && texts.isEmpty()
}

/** A bar's [value] out of [max], over [segments] of its segments (all of them when null). */
data class BarFill(val value: Double, val max: Double, val segments: Int? = null)

/**
 * The action bar line that draws a player's HUD: MiniMessage, every element in key order (bars, then rows of
 * icons, then lines of text), each moving the cursor to its place, drawing, and moving it back, so the line is no
 * wide at all and the game's centring leaves every element at its `x`. Pictures are drawn untinted (white) and
 * without the text shadow the action bar would give them (`<!shadow>`).
 */
object HudLine {
    /**
     * [state] drawn with [hud]; [width] measures a line of text as the game's font draws it (`TextWidth`), null when
     * it can't, and a line it can't measure isn't drawn (it would move every element by half its width). Empty when
     * nothing is drawn.
     */
    fun compose(hud: CompiledHud, state: HudState, width: (String) -> Int?): String {
        val out = StringBuilder()

        // One run in the pack's font, untinted (white) and with no shadow, closed so nothing of it reaches what follows.
        fun run(chars: String) {
            if (chars.isNotEmpty()) out.append("<white><!shadow><font:${hud.font}>").append(chars).append("</font></!shadow></white>")
        }
        for ((key, bar) in hud.bars) {
            val fill = state.bars[key] ?: continue
            val slot = bar.slot ?: continue
            val pieces = segments(bar, fill.value, fill.max, fill.segments ?: bar.def.segments)
            val left = left(bar.def.x, bar.def.align, slot * pieces.size - (bar.def.gap ?: 0))
            val chars = StringBuilder(PackFonts.offset(left))
            for (piece in pieces) chars.append(piece.char).append(PackFonts.offset(slot - piece.advance!!))
            chars.append(PackFonts.offset(-(left + slot * pieces.size)))
            run(chars.toString())
        }
        for ((key, row) in hud.icons) {
            val shown = state.icons[key]?.mapNotNull { row.icons[it] }?.takeIf { it.isNotEmpty() } ?: continue
            if (shown.any { it.advance == null }) continue
            val gap = row.def.gap ?: DEFAULT_ICON_GAP
            val moved = shown.sumOf { it.advance!! + gap }
            val left = left(row.def.x, row.def.align, moved - gap)
            val chars = StringBuilder(PackFonts.offset(left))
            for (piece in shown) chars.append(piece.char).append(PackFonts.offset(gap))
            chars.append(PackFonts.offset(-(left + moved)))
            run(chars.toString())
        }
        for ((key, def) in hud.texts) {
            val text = state.texts[key]?.takeIf { it.isNotEmpty() } ?: continue
            val wide = width(text) ?: continue
            val left = left(def.x, def.align, wide)
            run(PackFonts.offset(left))
            // Its own colours and its own shadow, in the game's font; reset after, so no style of it (bold widens every
            // character, the moves too) reaches the elements after it.
            out.append(text).append("<reset>")
            run(PackFonts.offset(-(left + wide)))
        }
        return out.toString()
    }

    /** Where something [wide] pixels across has its left edge, when [align] puts that part of it at [x]. */
    private fun left(x: Int, align: HudAlign?, wide: Int): Int = when (align ?: HudAlign.LEFT) {
        HudAlign.LEFT -> x
        HudAlign.CENTER -> x - wide / 2
        HudAlign.RIGHT -> x - wide
    }

    /**
     * The pictures of [bar] for [value] out of [max] over [shown] of its segments (1 to its own), left to right:
     * the whole segments, a half one, the rest empty.
     */
    fun segments(bar: CompiledHud.Bar, value: Double, max: Double, shown: Int = bar.def.segments): List<CompiledHud.Piece> {
        val count = shown.coerceIn(1, bar.def.segments)
        val fraction = if (max > 0 && value.isFinite()) (value / max).coerceIn(0.0, 1.0) else 0.0
        val halves = if (bar.half != null) round(fraction * count * 2).toInt() else round(fraction * count).toInt() * 2
        val full = halves / 2
        val half = halves % 2
        val pieces = List(full) { bar.full } + List(half) { bar.half!! } + List(count - full - half) { bar.empty }
        return if (bar.def.reverse == true) pieces.reversed() else pieces
    }

    /** Pixels between icons, unless a row says. */
    const val DEFAULT_ICON_GAP = 1
}
