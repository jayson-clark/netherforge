package dev.netherforge.plugin.paper

import dev.netherforge.format.screen.ScreenFacing
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.MapFont
import dev.netherforge.plugin.platform.MapGlyph
import dev.netherforge.plugin.platform.ScreenOps
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.MapId
import org.bukkit.Bukkit
import org.bukkit.Material
import org.bukkit.block.BlockFace
import org.bukkit.inventory.ItemStack
import org.bukkit.map.MinecraftFont
import java.util.UUID

/**
 * Screens' maps on Paper: their pixels go out in the game's map packet ([PaperVersion.sendMap]) for map numbers
 * the server has no saved data for, so it never sends anything of them itself; their frames are fixed, invisible
 * item frames hung past Bukkit's wall check ([PaperVersion.hangFrame]), tagged as any entity of ours is and never
 * saved with their chunk. What protects them (nothing breaks, turns or empties one) is [PaperEvents]'.
 */
class PaperScreens(private val entities: PaperEntities, private val version: PaperVersion) : ScreenOps {
    override fun sendMap(player: UUID, map: Int, x: Int, y: Int, width: Int, height: Int, colors: ByteArray): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        version.sendMap(who, map, x, y, width, height, colors)
        return true
    }

    override fun hang(at: Location, facing: ScreenFacing, map: Int, glowing: Boolean, tag: EntityTag): UUID? {
        val world = Bukkit.getWorld(at.world) ?: return null
        val face = when (facing) {
            ScreenFacing.NORTH -> BlockFace.NORTH
            ScreenFacing.SOUTH -> BlockFace.SOUTH
            ScreenFacing.EAST -> BlockFace.EAST
            ScreenFacing.WEST -> BlockFace.WEST
        }
        val frame = version.hangFrame(world, at.x.toInt(), at.y.toInt(), at.z.toInt(), face, glowing, stack(map)) {
            entities.stamp(it, tag)
            it.isPersistent = false
            it.isSilent = true
            it.isInvulnerable = true
        }
        return frame?.uniqueId
    }

    override fun mapItem(map: Int): ItemData = PaperItems.toItem(stack(map)) ?: error("a filled map didn't read back")

    private fun stack(map: Int): ItemStack = ItemStack(Material.FILLED_MAP).apply {
        setData(DataComponentTypes.MAP_ID, MapId.mapId(map))
    }

    /** The game's map font, as Bukkit's [MinecraftFont] has it from the server. */
    override fun font(): MapFont {
        val font = MinecraftFont.Font
        val glyphs = HashMap<Int, MapGlyph>()
        for (code in 0 until FONT_CHARS) {
            val char = code.toChar()
            if (!font.isValid(char.toString())) continue
            val sprite = font.getChar(char) ?: continue
            glyphs[code] =
                MapGlyph(
                    sprite.width,
                    sprite.height,
                    BooleanArray(sprite.width * sprite.height) {
                        sprite.get(
                            it / sprite.width,
                            it % sprite.width
                        )
                    }
                )
        }
        return MapFont(font.height, glyphs)
    }

    private companion object {
        /** The characters Bukkit's map font may have: the first 256 code points. */
        const val FONT_CHARS = 256
    }
}
