package dev.netherforge.plugin.platform

import dev.netherforge.format.screen.ScreenFacing
import java.util.UUID

/**
 * Maps as screens (`screen/Screens.kt`): sending a player a map's pixels, the item frames a screen hangs in, a
 * map item to hand out, and the game's own map font.
 *
 * A screen's maps are numbers the server never made: it has no saved data for them, so it never sends a player
 * anything of them itself, and what a player's game shows for one is exactly what the runtime sent them.
 */
interface ScreenOps {
    /**
     * Sends [player] part of map [map]: the rectangle at ([x], [y]), [width] by [height] pixels (within 128 by
     * 128), whose map colours [colors] holds row by row. Their game keeps the rest of the map as it was. False when
     * they're offline.
     */
    fun sendMap(player: UUID, map: Int, x: Int, y: Int, width: Int, height: Int, colors: ByteArray): Boolean

    /**
     * Hangs an invisible, fixed item frame (a glowing one with [glowing]) holding map [map] in the block at [at],
     * facing [facing], stamped with [tag]: nothing can break, turn or empty it, and it needs nothing behind it.
     * It isn't saved with its chunk (the runtime hangs it again when the chunk comes back). Null when it can't
     * (the world isn't loaded).
     */
    fun hang(at: Location, facing: ScreenFacing, map: Int, glowing: Boolean, tag: EntityTag): UUID?

    /** A filled map item showing map [map]. */
    fun mapItem(map: Int): ItemData

    /** The game's own map font: what `default` draws text in. */
    fun font(): MapFont
}

/**
 * A bitmap font: [height] pixels a line, each character's picture by code point. A character it has no picture
 * for draws nothing and takes no room.
 */
class MapFont(val height: Int, val glyphs: Map<Int, MapGlyph>)

/** One character of a [MapFont]: [width] by [height] pixels, set or not, row by row. */
class MapGlyph(val width: Int, val height: Int, val pixels: BooleanArray) {
    fun at(x: Int, y: Int): Boolean = pixels[y * width + x]
}
