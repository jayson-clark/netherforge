package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.plugin.contract.PlatformContract
import dev.netherforge.plugin.platform.InventoryRef
import org.bukkit.Bukkit
import org.bukkit.Instrument
import org.bukkit.Material
import org.bukkit.block.data.type.NoteBlock
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.NotePlayEvent
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The vanilla note blocks' part, which the adapter plays itself once the server has been asked to leave note
 * blocks alone (custom blocks are held as note block states): tuning, playing, instruments from what's around,
 * and a custom block's state taking no part of it. On Paper alone: what a real server's note blocks do is
 * what's being kept, and only it can say.
 */
class PaperNoteBlocksTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    /** What sounded: the instrument and note a note block played, after the adapter had its say. */
    private class Heard : Listener {
        val notes = CopyOnWriteArrayList<Pair<Instrument, Int>>()

        @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
        fun onNote(event: NotePlayEvent) {
            notes += event.instrument to event.note.id.toInt()
        }
    }

    private fun listening(): Heard {
        val heard = Heard()
        val plugin = Bukkit.getPluginManager().getPlugin("NetherForgeContract")!!
        main { Bukkit.getPluginManager().registerEvents(heard, plugin) }
        afterwards { HandlerList.unregisterAll(heard) }
        return heard
    }

    private fun frozen() = main { assertTrue(platform.blocks.freeze(dev.netherforge.format.block.BlockCarrier.NOTE_BLOCK)) }

    private fun noteAt(x: Int, y: Int, z: Int) = main { Bukkit.getWorld(world)!!.getBlockAt(x, y, z).blockData as NoteBlock }

    private fun give(player: dev.netherforge.plugin.platform.PlayerRef, kind: String) {
        main { assertTrue(platform.inventories.setItem(InventoryRef.Player(player.uuid), 0, item(kind, 4))) }
    }

    @Test
    fun `a player's note block is tuned by a right click, and sounds with the instrument of what's below it`() {
        frozen()
        val heard = listening()
        val player = join()
        val (x, y, z) = block(at(3, 0, 2))
        main {
            place(at(3, -1, 2), "minecraft:stone")
            place(at(3, 0, 2), "minecraft:note_block")
        }
        act(player, BotAction.UseBlock(x, y, z, "north"))
        eventually("tuned and played") { heard.notes.isNotEmpty() }
        // Up one from the default, on a drum, since stone is under it.
        assertEquals(Instrument.BASS_DRUM to 1, heard.notes.last())
        assertEquals(1, noteAt(x, y, z).note.id.toInt())
        // The state itself stays what the server placed: its instrument is always the default's.
        assertEquals(Instrument.PIANO, noteAt(x, y, z).instrument)

        // Another material below it, another instrument, worked out when it sounds.
        main { place(at(3, -1, 2), "minecraft:oak_planks") }
        act(player, BotAction.UseBlock(x, y, z, "north"))
        eventually("tuned again") { heard.notes.size >= 2 }
        assertEquals(Instrument.BASS_GUITAR to 2, heard.notes.last())

        // A mob head on top sounds its own, which the game's own playNote wouldn't under a block.
        main { place(at(3, 1, 2), "minecraft:zombie_head") }
        act(player, BotAction.UseBlock(x, y, z, "north"))
        eventually("tuned under a head") { heard.notes.size >= 3 }
        assertEquals(Instrument.ZOMBIE to 3, heard.notes.last())
    }

    @Test
    fun `the notes go round from the highest to the lowest`() {
        frozen()
        val heard = listening()
        val player = join()
        val (x, y, z) = block(at(-3, 0, 2))
        main {
            place(at(-3, 0, 2), "minecraft:note_block[instrument=harp,note=24,powered=false]")
        }
        act(player, BotAction.UseBlock(x, y, z, "north"))
        eventually("tuned") { heard.notes.isNotEmpty() }
        assertEquals(0, noteAt(x, y, z).note.id.toInt())
        assertEquals(0, heard.notes.last().second)
    }

    @Test
    fun `a redstone signal sounds it, once, and powered follows the signal`() {
        frozen()
        val heard = listening()
        val (x, y, z) = block(at(7, 0, 2))
        main {
            place(at(7, -1, 2), "minecraft:stone")
            place(at(7, 0, 2), "minecraft:note_block")
            place(at(8, 0, 2), "minecraft:air")
            afterwards { platform.blocks.set(world, x + 1, y, z, "minecraft:air", false) }
            // The neighbour's arrival is what the server tells the note block about.
            platform.blocks.set(world, x + 1, y, z, "minecraft:redstone_block", true)
        }
        eventually("sounded by redstone") { heard.notes.isNotEmpty() }
        assertEquals(Instrument.BASS_DRUM, heard.notes.first().first)
        assertTrue(noteAt(x, y, z).isPowered)
        ticks(3)
        assertEquals(1, heard.notes.size, "once")
        main { platform.blocks.set(world, x + 1, y, z, "minecraft:air", true) }
        eventually("unpowered") { !noteAt(x, y, z).isPowered }
        assertEquals(1, heard.notes.size, "a signal going away isn't a note")
    }

    @Test
    fun `a custom block's state never sounds, isn't tuned, keeps its instrument, and what's in hand goes against it`() {
        frozen()
        val heard = listening()
        val player = join()
        val state = "minecraft:note_block[instrument=zombie,note=5,powered=true]"
        val (x, y, z) = block(at(-3, 0, -2))
        main {
            place(at(-3, 0, -2), state)
            // Something placed beside it doesn't change it, as it would any note block the game works out.
            place(at(-3, 1, -2), "minecraft:air")
            platform.blocks.set(world, x, y + 1, z, "minecraft:stone", true)
        }
        ticks(2)
        assertEquals("zombie", main { platform.blocks.get(world, x, y, z)!!.state.substringAfter("instrument=").substringBefore(',') })
        main { platform.blocks.set(world, x, y + 1, z, "minecraft:air", true) }
        act(player, BotAction.UseBlock(x, y, z, "north"))
        ticks(5)
        assertEquals(emptyList(), heard.notes)
        assertEquals(state, main { platform.blocks.get(world, x, y, z)!!.state })
        // Planks in hand, right click on its side: they're placed beside it, not used on it.
        give(player, "minecraft:oak_planks")
        act(player, BotAction.UseBlock(x, y, z, "east"))
        eventually("placed beside it") { main { platform.blocks.get(world, x + 1, y, z)?.state == "minecraft:oak_planks" } }
        afterwards { platform.blocks.set(world, x + 1, y, z, "minecraft:air", false) }
        assertEquals(emptyList(), heard.notes)
        assertEquals(state, main { platform.blocks.get(world, x, y, z)!!.state })
    }

    @Test
    fun `a note block placed by a player is in the default state whatever's around`() {
        frozen()
        val player = join()
        val (x, y, z) = block(at(3, 0, -2))
        main { place(at(3, -1, -2), "minecraft:stone") }
        give(player, "minecraft:note_block")
        act(player, BotAction.UseBlock(x, y - 1, z, "up"))
        eventually("placed") { main { platform.blocks.get(world, x, y, z)?.state?.startsWith("minecraft:note_block") == true } }
        afterwards { platform.blocks.set(world, x, y, z, "minecraft:air", false) }
        // The game would have given a note block over stone a drum; frozen, it's the default state.
        assertEquals(Instrument.PIANO, noteAt(x, y, z).instrument)
        assertEquals(Material.NOTE_BLOCK, main { Bukkit.getWorld(world)!!.getBlockAt(x, y, z).type })
    }
}
