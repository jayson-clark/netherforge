package dev.netherforge.plugin.structure
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.LuaChecks.runChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.StructureTransform
import dev.netherforge.plugin.testkit.BlockAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `world:place_gradually` and `world:fill_gradually` against the fake server: work spread over ticks under the
 * owner's share, builds placed part by part, what's an error at once and what fails later, and the transform
 * they share with the game.
 */
class GradualTest {
    /** A fake structure file: [size], then a block per line. */
    private fun structure(x: Int, y: Int, z: Int, vararg blocks: String) = (listOf("size $x $y $z") + blocks).joinToString("\n")

    /** A row of [length] stone blocks along x, one high, one deep. */
    private fun row(length: Int) = structure(length, 1, 1, *Array(length) { "$it 0 0 minecraft:stone" })

    private fun TestServer.state(x: Int, y: Int, z: Int) = platform.worlds.blocks[BlockAt("world", x, y, z)]

    private fun TestServer.placedAt(y: Int) = platform.worlds.blocks.keys.count { it.world == "world" && it.y == y }

    @Test
    fun `a structure goes in a share of blocks a tick, and its value is how many it placed`() {
        TestServer(
            mapOf(
                "structures/wall.nbt" to row(8),
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    nf.task(function()
                      local placed, err = nf.worlds.default():place_gradually("wall", vec3(10.5, 70, 10))
                      log("placed " .. tostring(placed) .. " " .. tostring(err))
                    end)
                    """
                )
            ),
            structures = StructuresConfig(blocksPerTick = 3)
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            assertEquals(0, server.placedAt(70), "nothing during the call")
            server.tick()
            assertEquals(3, server.placedAt(70))
            server.tick()
            assertEquals(6, server.placedAt(70))
            server.tick(2)
            assertEquals(listOf("done", "placed 8 nil"), server.output())
            assertEquals("minecraft:stone", server.state(17, 70, 10))
        }
    }

    @Test
    fun `jobs share the tick's blocks, and per_tick holds one back`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    world:fill_gradually(vec3(0, 70, 0), vec3(9, 70, 0), "minecraft:stone", nil, function() end)
                    world:fill_gradually(vec3(0, 71, 0), vec3(9, 71, 0), "minecraft:dirt", nil, function() end)
                    world:fill_gradually(vec3(0, 72, 0), vec3(9, 72, 0), "minecraft:sand", { per_tick = 1 }, function() end)
                    """
                )
            ),
            structures = StructuresConfig(blocksPerTick = 9)
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            // The slow one takes its 1; the other two split the rest.
            assertEquals(listOf(4, 4, 1), listOf(server.placedAt(70), server.placedAt(71), server.placedAt(72)))
            server.tick()
            assertEquals(listOf(8, 8, 2), listOf(server.placedAt(70), server.placedAt(71), server.placedAt(72)))
        }
    }

    @Test
    fun `a build goes part by part, each where the grid puts it, turned as a whole`() {
        TestServer(
            mapOf(
                "builds/city/0_0_0.nbt" to structure(48, 1, 1, "0 0 0 minecraft:stone", "47 0 0 minecraft:dirt"),
                "builds/city/1_0_0.nbt" to structure(2, 1, 1, "1 0 0 minecraft:gold_block"),
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    check("exists", nf.structures.exists("city"), true)
                    check("size", nf.structures.size("city"), vec3(50, 1, 1))
                    fails("in one tick", function() world:place_structure("city", vec3(0, 0, 0)) end, "\"city\" is a build (builds/city/)")
                    fails("saved over", function() world:save_structure("city", vec3(0, 0, 0), vec3(1, 1, 1)) end, "is one of the project's builds")
                    world:place_gradually("city", vec3(0, 70, 0), nil, function(placed, err) log("flat " .. tostring(placed) .. " " .. tostring(err)) end)
                    world:place_gradually("city", vec3(0, 80, 0), { rotation = 90 }, function(placed) log("turned " .. placed) end)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            assertEquals("minecraft:dirt", server.state(47, 70, 0))
            // Done in that tick, heard at the next: what finishes off the scripts' turn is handed back with the rest.
            server.tick()
            assertEquals(listOf("done", "flat 3 nil", "turned 3"), server.output())
            assertEquals("minecraft:dirt", server.state(47, 70, 0))
            assertEquals("minecraft:gold_block", server.state(49, 70, 0))
            // Turned clockwise about the corner, x becomes z.
            assertEquals("minecraft:gold_block", server.state(0, 80, 49))
            assertEquals(
                listOf(
                    "0_0_0.nbt world origin=0,70,0 offset=0,0,0 rotation=0 mirror=none",
                    "1_0_0.nbt world origin=0,70,0 offset=48,0,0 rotation=0 mirror=none",
                    "0_0_0.nbt world origin=0,80,0 offset=0,0,0 rotation=90 mirror=none",
                    "1_0_0.nbt world origin=0,80,0 offset=48,0,0 rotation=90 mirror=none"
                ),
                server.platform.structures.placers
            )
        }
    }

    @Test
    fun `a part the wrong size for its place stops the build, keeping what went in`() {
        TestServer(
            mapOf(
                "builds/city/0_0_0.nbt" to row(2),
                "builds/city/0_1_0.nbt" to row(2),
                "builds/city/1_0_0.nbt" to row(2),
                "builds/city/1_1_0.nbt" to row(2),
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    nf.worlds.default():place_gradually("city", vec3(0, 70, 0), nil, function(placed, err) log(tostring(placed) .. " " .. err) end)
                    """
                )
            )
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.tick(2)
            assertEquals(
                listOf(
                    "done",
                    "nil builds/city/0_0_0.nbt is 2 × 1 × 1 blocks, but its place in the build's grid makes it 48 × 48 × 1 " +
                        "(every part is 48 a side but the last along each axis)"
                ),
                server.output()
            )
        }
    }

    @Test
    fun `a fill goes a chunk column at a time and loads the chunks it reaches`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    fails("too big", function() world:fill_gradually(vec3(0, 0, 0), vec3(256, 255, 255), "minecraft:air", nil, print) end, "at most 16777216")
                    fails("per_tick", function() world:fill_gradually(vec3(0, 0, 0), vec3(1, 1, 1), "minecraft:air", { per_tick = 0 }, print) end, "per_tick must be 1 or more")
                    fails("a callback or a task", function() world:fill_gradually(vec3(0, 0, 0), vec3(1, 1, 1), "minecraft:air") end, "task")
                    world:fill_gradually(vec3(14, 70, 0), vec3(17, 71, 0), "minecraft:stone", { update = false }, function(changed, err)
                      log("filled " .. changed .. " " .. tostring(err))
                    end)
                    """
                )
            ),
            structures = StructuresConfig(blocksPerTick = 4)
        ).use { server ->
            server.platform.worlds.unloaded += Triple("world", 1, 0)
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            // The first column, both layers, before the next chunk is touched.
            assertEquals(setOf(14 to 70, 15 to 70, 14 to 71, 15 to 71), server.platform.worlds.blocks.keys.map { it.x to it.y }.toSet())
            server.tick()
            assertTrue(server.platform.worlds.isChunkLoaded("world", 1, 0), "loaded as it reached it")
            server.tick()
            assertEquals(listOf("done", "filled 8 nil"), server.output())
            assertTrue(server.platform.blocks.changes.all { it.endsWith("(no update)") })
        }
    }

    @Test
    fun `a package has a few going at most, and a script that stops stops its own`() {
        TestServer(
            mapOf(
                "modules/t/init.lua" to LuaChecks.command(
                    """
                    local world = nf.worlds.default()
                    for i = 1, 3 do
                      world:fill_gradually(vec3(0, 60 + i, 0), vec3(99, 60 + i, 0), "minecraft:stone", nil, function(changed, err)
                        log(i .. ": " .. tostring(changed) .. " " .. tostring(err))
                      end)
                    end
                    """
                )
            ),
            structures = StructuresConfig(blocksPerTick = 10, maxJobsPerPackage = 2)
        ).use { server ->
            assertEquals(listOf("done"), server.runChecks())
            server.tick()
            assertEquals(
                listOf(
                    "done",
                    "3: nil package \"test\" already has 2 gradual placements and fills going, the server's limit " +
                        "(structures.max-jobs-per-package in config.yml)"
                ),
                server.output()
            )
            server.write("modules/t/init.lua", LuaChecks.command("log('reloaded')"))
            server.reload("modules/t/init.lua")
            val before = server.platform.worlds.blocks.size
            server.tick(3)
            assertEquals(before, server.platform.worlds.blocks.size, "stopped with its script")
            assertFalse(server.output().any { it.startsWith("1:") || it.startsWith("2:") }, "nobody's left to call")
        }
    }

    @Test
    fun `blocks, points, yaws and facings turn as the game turns them`() {
        fun at(rotation: Int, mirror: String = "none", offset: BlockVector = BlockVector(0, 0, 0)) =
            StructureTransform(BlockVector(100, 64, 200), offset, rotation, mirror)
        // The game's transform about the corner: a quarter turn clockwise, seen from above, takes +x to +z.
        assertEquals(BlockVector(101, 64, 200), at(0).block(1, 0, 0))
        assertEquals(BlockVector(100, 64, 201), at(90).block(1, 0, 0))
        assertEquals(BlockVector(99, 64, 200), at(90).block(0, 0, 1))
        assertEquals(BlockVector(99, 64, 199), at(180).block(1, 0, 1))
        assertEquals(BlockVector(100, 64, 199), at(270).block(1, 0, 0))
        assertEquals(BlockVector(100, 64, 199), at(0, "left_right").block(0, 0, 1))
        assertEquals(BlockVector(99, 64, 200), at(0, "front_back").block(1, 0, 0))
        // Mirrored first, then turned; a part's offset is inside what's turned.
        assertEquals(BlockVector(101, 64, 200), at(90, "left_right").block(0, 0, 1))
        assertEquals(BlockVector(100, 64, 248), at(90, offset = BlockVector(48, 0, 0)).block(0, 0, 0))
        assertEquals(BlockVector(101, 65, 200), at(0, offset = BlockVector(1, 1, 0)).block(0, 0, 0))
        // A point in a block stays in the block it turns to.
        assertEquals(Triple(100.5, 64.0, 201.5), at(90).point(1.5, 0.0, 0.5))
        assertEquals(Triple(99.5, 64.0, 200.5), at(90).point(0.5, 0.0, 1.5))
        assertEquals(Triple(100.5, 64.0, 199.5), at(0, "left_right").point(0.5, 0.0, 1.5))
        // Yaw: mirrored, then turned, wrapped to -180..180.
        assertEquals(90f, at(90).yaw(0f))
        assertEquals(-90f, at(180).yaw(90f))
        assertEquals(-180f, at(0, "left_right").yaw(0f))
        assertEquals(-90f, at(0, "front_back").yaw(90f))
        assertEquals("east", at(90).facing("north"))
        assertEquals("west", at(270).facing("north"))
        assertEquals("south", at(0, "left_right").facing("north"))
        assertEquals("west", at(90, "left_right").facing("north"))
        assertEquals("up", at(90).facing("up"))
    }
}
