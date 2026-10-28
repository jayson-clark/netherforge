package dev.netherforge.plugin.testkit

import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.screen.ScreenFacing
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.MapFont
import dev.netherforge.plugin.platform.MapGlyph
import dev.netherforge.plugin.platform.ScreenOps
import java.util.UUID

/**
 * [ScreenOps] on the fake: what each player's game holds of every map it was sent ([seen], applied patch by
 * patch, as a client applies them), every patch in order ([sent]), and the frames hung, as entities of
 * [FakeEntities] (so they load, unload and go as any entity does) with what each holds in [frames].
 */
class FakeScreens(private val platform: FakePlatform) : ScreenOps {
    /** One map packet: [player] sent the rectangle at ([x], [y]), [width] by [height], of map [map]. */
    data class Sent(val player: UUID, val map: Int, val x: Int, val y: Int, val width: Int, val height: Int)

    /** A frame hung: where it faces, the map it holds, whether it glows. */
    data class Frame(val facing: ScreenFacing, val map: Int, val glowing: Boolean)

    val sent = mutableListOf<Sent>()
    val frames = LinkedHashMap<UUID, Frame>()

    /** Each player's maps as their game draws them: 128 by 128 colours, all 0 until sent. */
    private val seen = HashMap<UUID, HashMap<Int, ByteArray>>()

    /** The colour map [map] shows [player] at ([x], [y]). */
    fun pixel(player: UUID, map: Int, x: Int, y: Int): Int = (seen[player]?.get(map)?.get(y * SIDE + x)?.toInt() ?: 0) and 0xFF

    /** How many bytes of pixels [player] has been sent in all. */
    fun bytesTo(player: UUID): Int = sent.filter { it.player == player }.sumOf { it.width * it.height }

    override fun sendMap(player: UUID, map: Int, x: Int, y: Int, width: Int, height: Int, colors: ByteArray): Boolean {
        if (platform.players.get(player) == null) return false
        require(x >= 0 && y >= 0 && x + width <= SIDE && y + height <= SIDE) { "a patch past the map: $x,$y $width by $height" }
        require(colors.size == width * height) { "${colors.size} colours for $width by $height" }
        val pixels = seen.getOrPut(player) { HashMap() }.getOrPut(map) { ByteArray(SIDE * SIDE) }
        for (row in 0 until height) colors.copyInto(pixels, (y + row) * SIDE + x, row * width, row * width + width)
        sent += Sent(player, map, x, y, width, height)
        return true
    }

    override fun hang(at: Location, facing: ScreenFacing, map: Int, glowing: Boolean, tag: EntityTag): UUID? {
        if (!platform.worlds.exists(at.world)) return null
        require(tag.role == EntityRole.SCREEN) { "a frame is tagged as a screen's" }
        val entity = FakeEntity(UUID.randomUUID(), EntityRole.SCREEN, null, at, tag)
        // Never saved with its chunk, as on Paper.
        entity.persistent = false
        platform.entities.all[entity.id] = entity
        frames[entity.id] = Frame(facing, map, glowing)
        return entity.id
    }

    override fun mapItem(map: Int): ItemData = ItemData(ItemDef(kind = FILLED_MAP, map = map))

    /** A made-up font (no game's): every printable ASCII character a 5 by 7 box, a space 3 wide. */
    override fun font(): MapFont {
        val glyphs = HashMap<Int, MapGlyph>()
        for (code in 0x21..0x7E) {
            glyphs[code] = MapGlyph(5, 7, BooleanArray(35) { i -> i % 5 == 0 || i % 5 == 4 || i / 5 == 0 || i / 5 == 6 })
        }
        glyphs[' '.code] = MapGlyph(3, 7, BooleanArray(21))
        return MapFont(7, glyphs)
    }

    companion object {
        const val SIDE = 128
        const val FILLED_MAP = "minecraft:filled_map"
    }
}
