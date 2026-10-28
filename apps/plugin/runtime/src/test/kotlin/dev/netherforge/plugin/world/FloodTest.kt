package dev.netherforge.plugin.world
import dev.netherforge.plugin.LuaChecks
import kotlin.test.Test
import kotlin.test.assertEquals

/** `world:flood`: rooms, trees, limits, bounds, unloaded chunks and the block names it takes. */
class FloodTest {
    @Test
    fun `positions pack and unpack, negative ones too`() {
        for ((x, y, z) in listOf(Triple(0, 0, 0), Triple(-1, -64, -1), Triple(30_000_000, 2000, -30_000_000), Triple(-5, 319, 17))) {
            val p = Flood.pack(x, y, z)
            assertEquals(Triple(x, y, z), Triple(Flood.unpackX(p), Flood.unpackY(p), Flood.unpackZ(p)))
        }
    }

    @Test
    fun `a closed room is its air, walled in by its edges`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            -- A hollow box of stone: 3 × 2 × 3 of air inside, floor at y = 64.
            world:fill_blocks(vec3(0, 64, 0), vec3(4, 67, 4), "minecraft:stone")
            world:fill_blocks(vec3(1, 65, 1), vec3(3, 66, 3), "minecraft:air")
            world:set_block(vec3(2, 65, 2), "minecraft:glass")
            local room = world:flood(vec3(1.5, 65, 1.5), { through = { "passable" }, kinds = true })
            check("its air", #room.positions, 17)
            check("the start first", room.positions[1], vec3(1, 65, 1))
            check("finished", room.stopped, nil)
            check("kinds", room.kinds[1], "minecraft:air")
            local glass = 0
            for _, k in ipairs(room.edge_kinds) do
              if k == "minecraft:glass" then glass = glass + 1 end
            end
            check("the glass is an edge, once", glass, 1)
            check("no kinds unless asked", world:flood(vec3(1.5, 65, 1.5), { through = { "passable" } }).kinds, nil)

            -- A hole in the roof to the sky: it pours out and stops at the world's top.
            world:set_block(vec3(2, 67, 2), "minecraft:air")
            local open = world:flood(vec3(1.5, 65, 1.5), { through = { "passable" }, limit = 32768 })
            check("open to the sky", open.stopped, "limit")
            local small = world:flood(vec3(1.5, 65, 1.5), { through = { "passable" }, limit = 10 })
            check("at the limit", #small.positions, 10)
            check("limited", small.stopped, "limit")
            local boxed = world:flood(vec3(1.5, 65, 1.5), { through = { "passable" }, bounds = { min = vec3(1, 65, 1), max = vec3(3, 66, 3) } })
            check("bounded", boxed.stopped, "bounds")
            check("inside the bounds", #boxed.positions, 17)

            -- Glass is let through when named, and an exception keeps it out again.
            world:set_block(vec3(2, 67, 2), "minecraft:stone")
            check("through glass too", #world:flood(vec3(1.5, 65, 1.5), { through = { "passable", "minecraft:glass" } }).positions, 18)
            check("except", #world:flood(vec3(1.5, 65, 1.5), { through = { "passable", "minecraft:glass" }, except = { "minecraft:glass" } }).positions, 17)

            local wall = world:flood(vec3(0, 64, 0), { through = { "passable" } })
            check("a start it can't fill", #wall.positions, 0)
            check("is the edge", wall.edges[1], vec3(0, 64, 0))
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a tree is its logs, up and sideways, by a tag or an id`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            for y = 64, 68 do world:set_block(vec3(10, y, 10), "minecraft:dark_oak_log[axis=y]") end
            world:set_block(vec3(11, 69, 10), "minecraft:dark_oak_log[axis=y]")
            world:set_block(vec3(10, 63, 10), "minecraft:dark_oak_log[axis=y]")
            local up = {}
            for dx = -1, 1 do
              for dy = 0, 1 do
                for dz = -1, 1 do
                  if not (dx == 0 and dy == 0 and dz == 0) then up[#up + 1] = vec3(dx, dy, dz) end
                end
              end
            end
            local tree = world:flood(vec3(10, 64, 10), { through = { "minecraft:dark_oak_log" }, directions = up })
            check("the trunk and the branch, not below", #tree.positions, 6)
            check("a whole tree", tree.stopped, nil)
            check("both ways, the faces", #world:flood(vec3(10, 64, 10), { through = { "minecraft:dark_oak_log" } }).positions, 6)
            world:set_block(vec3(0, 64, 30), "minecraft:oak_planks")
            world:set_block(vec3(1, 64, 30), "minecraft:oak_planks")
            check("by a tag", #world:flood(vec3(0, 64, 30), { through = { "#minecraft:planks" } }).positions, 2)
            fails("an unknown tag", function() world:flood(vec3(0, 64, 0), { through = { "#minecraft:nope" } }) end, "isn't a block tag")
            fails("an unknown block", function() world:flood(vec3(0, 64, 0), { through = { "minecraft:nope" } }) end, "isn't a block")
            fails("nothing", function() world:flood(vec3(0, 64, 0), { through = {} }) end, "at least one")
            fails("a direction", function() world:flood(vec3(0, 64, 0), { through = { "solid" }, directions = { vec3(0.5, 0, 0) } }) end, "whole numbers")
            fails("a limit", function() world:flood(vec3(0, 64, 0), { through = { "solid" }, limit = 0 }) end, "1 to 32768")
            """
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a chunk that isn't loaded stops it`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local fill = world:flood(vec3(14, 64, 0), { through = { "minecraft:stone_bricks" } })
            check("nothing to fill", #fill.positions, 0)
            world:fill_blocks(vec3(12, 70, 0), vec3(20, 70, 0), "minecraft:stone_bricks")
            local line = world:flood(vec3(12, 70, 0), { through = { "minecraft:stone_bricks" } })
            check("stopped where the chunk isn't loaded", line.stopped, "unloaded")
            check("up to it", #line.positions, 4)
            """,
            setup = { it.platform.worlds.unloaded += Triple("world", 1, 0) }
        )
        assertEquals(listOf("done"), result)
    }
}
