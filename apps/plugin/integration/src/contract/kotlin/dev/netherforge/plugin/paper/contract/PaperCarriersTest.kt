package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.block.BlockCarrier
import dev.netherforge.plugin.contract.PlatformContract
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Paper only: tripwire and leaves held still once custom blocks are held in them ([BlockCarrier]), what the fake can't
 * show, since it's the server's own working out of the states that's stopped.
 */
class PaperCarriersTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    private fun stateAt(x: Int, y: Int, z: Int) = main { platform.blocks.get(world, x, y, z)?.state }

    @Test
    fun `a frozen tripwire keeps its state when its neighbours change`() {
        main { assertTrue(platform.blocks.freeze(BlockCarrier.TRIPWIRE)) }
        val carried = "minecraft:tripwire[attached=false,disarmed=true,east=false,north=false,powered=true,south=false,west=false]"
        val (x, y, z) = block(at(5, 0, -4))
        main {
            place(at(5, -1, -4), "minecraft:stone")
            place(at(5, 0, -4), carried)
            // Another string beside it, which a tripwire that works itself out would join.
            place(at(6, 0, -4), "minecraft:air")
            platform.blocks.set(world, x + 1, y, z, "minecraft:tripwire", true)
            afterwards { platform.blocks.set(world, x + 1, y, z, "minecraft:air", false) }
        }
        ticks(5)
        assertEquals(carried, stateAt(x, y, z))
    }

    @Test
    fun `a persistent leaves block keeps its distance, while natural leaves still work theirs out`() {
        main { assertTrue(platform.blocks.freeze(BlockCarrier.LEAVES)) }
        val carried = "minecraft:oak_leaves[distance=1,persistent=true,waterlogged=false]"
        val (x, y, z) = block(at(-5, 1, -4))
        main {
            place(at(-5, 0, -4), "minecraft:oak_log")
            place(at(-5, 1, -4), carried)
            place(at(-4, 0, -4), "minecraft:oak_log")
            place(at(-4, 1, -4), "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]")
        }
        // The logs go: a leaf that works its distance out moves away from 1.
        main {
            platform.blocks.set(world, x, y - 1, z, "minecraft:stone", true)
            platform.blocks.set(world, x + 1, y - 1, z, "minecraft:stone", true)
        }
        eventually("the natural leaf works its distance out") { stateAt(x + 1, y, z)?.contains("distance=1") != true }
        assertEquals(carried, stateAt(x, y, z), "the custom block's state stays")
    }
}
