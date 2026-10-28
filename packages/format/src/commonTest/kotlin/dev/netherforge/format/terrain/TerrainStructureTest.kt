package dev.netherforge.format.terrain

import dev.netherforge.format.LuaTest
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.lua.LuaPlatform
import dev.netherforge.format.withLua
import kotlin.test.Test
import kotlin.test.assertEquals

/** A terrain script's shapes and structures: `chunk:ellipsoid`, `terrain.structure`, `chunk:place_structure`, `terrain.scatter`. */
class TerrainStructureTest {
    private val script = "terrain/t.lua"

    private val compiled: CompiledTerrain = run {
        val parsed = TerrainKind.parse(
            """{
                "terrain": { "base": 64, "seaLevel": 40 },
                "layers": [{ "block": "minecraft:grass_block" }],
                "script": { "budget": 2000000, "blocks": ["minecraft:gold_block", "minecraft:glass"] }
            }""",
            "terrain/t.json"
        )
        check(parsed is CanonicalJson.Parsed.Ok) { "didn't parse: $parsed" }
        TerrainCompiler.compile(parsed.value, script)
    }

    /** The chunks [chunks] made with [source]'s stages, and what failed. */
    private fun generate(lua: LuaPlatform, source: String, vararg chunks: Pair<Int, Int>): Pair<List<ChunkBuffer>, List<String>> {
        val failures = mutableListOf<TerrainScriptFailure>()
        val generator = compiled.bind(7L, -64, 320, TerrainScripts(lua, mapOf(script to source)) { failures += it })
        val buffers = chunks.map { (x, z) -> generator.generate(x, z) }
        generator.close()
        return buffers to failures.map { it.message }
    }

    private fun ChunkBuffer.label(x: Int, y: Int, z: Int): String = compiled.palette[this[x and 15, y, z and 15]].label

    @Test
    fun anEllipsoidIsRoundSolidOrHollow(): LuaTest = withLua { lua ->
        val (buffers, failures) = generate(
            lua,
            """
            return {
              decorate = function(chunk)
                chunk:ellipsoid(8, 100, 8, 2, 2, 2, "minecraft:gold_block")
                chunk:ellipsoid(8, 120, 8, 3, 3, 3, "minecraft:gold_block", { shell = 1 })
                chunk:fill(0, 140, 0, 15, 140, 15, "minecraft:glass")
                chunk:ellipsoid(8, 140, 8, 3, 0, 3, "minecraft:gold_block", { only = { "minecraft:air" } })
                assert(not pcall(function() chunk:ellipsoid(8, 100, 8, -1, 2, 2, "minecraft:gold_block") end))
                assert(not pcall(function() chunk:ellipsoid(8, 100, 8, 1, 1, 1, "minecraft:gold_block", { hollow = 1 }) end))
              end,
            }
            """,
            0 to 0
        )
        assertEquals(emptyList(), failures)
        val chunk = buffers.single()
        assertEquals("minecraft:gold_block", chunk.label(8, 100, 8))
        assertEquals("minecraft:gold_block", chunk.label(10, 100, 8), "its radius")
        assertEquals("minecraft:air", chunk.label(11, 100, 8), "past it")
        assertEquals("minecraft:air", chunk.label(10, 102, 10), "rounded off at the corners")
        assertEquals("minecraft:air", chunk.label(8, 120, 8), "a hollow one's middle")
        assertEquals("minecraft:gold_block", chunk.label(11, 120, 8), "its shell")
        assertEquals("minecraft:glass", chunk.label(8, 140, 8), "only over air: the glass stays")
    }

    @Test
    fun aStructureCrossesChunksAndIsTheSameInEach(): LuaTest = withLua { lua ->
        val (buffers, failures) = generate(
            lua,
            """
            local terrain = ...
            local wall = terrain.structure()
              :fill(10, 100, 4, 20, 104, 4, "minecraft:glass")
              :sprinkle(10, 100, 4, 20, 104, 4, "minecraft:gold_block", 20, { only = { "minecraft:glass" } })
              :ellipsoid(16, 110, 4, 2, 2, 2, "minecraft:gold_block")
            local x1, y1, z1, x2, y2, z2 = wall:bounds()
            assert(x1 == 10 and y1 == 100 and z1 == 2 and x2 == 20 and y2 == 112 and z2 == 6, table.concat({ wall:bounds() }, ","))
            assert(terrain.structure():bounds() == nil)
            local post = terrain.structure():fill(0, 0, 0, 0, 2, 0, "minecraft:gold_block")
            return {
              decorate = function(chunk)
                chunk:place_structure(wall)
                chunk:place_structure(post, 15, 130, 15)
                chunk:place_structure(post, 16, 130, 15)
              end,
            }
            """,
            0 to 0,
            1 to 0
        )
        assertEquals(emptyList(), failures)
        val (left, right) = buffers
        fun stone(x: Int, y: Int) = (if (x < 16) left else right).label(x, y, 4)
        // The wall runs on into the next chunk, and the ball on the border is in both.
        assertEquals(setOf("minecraft:glass", "minecraft:gold_block"), setOf(stone(15, 102), stone(16, 102), stone(20, 104)))
        assertEquals("minecraft:gold_block", stone(15, 110))
        assertEquals("minecraft:gold_block", stone(17, 110))
        val gold = (100..104).sumOf { y -> (10..20).count { x -> stone(x, y) == "minecraft:gold_block" } }
        assertEquals(true, gold in 10..20, "sprinkled over the glass, never outside it: $gold")
        // A structure placed by an offset: a post in each chunk.
        assertEquals("minecraft:gold_block", left.label(15, 132, 15))
        assertEquals("minecraft:gold_block", right.label(16, 130, 15))
    }

    @Test
    fun aScatterDrawsWhatReachesTheChunkFromTheCellsAround(): LuaTest = withLua { lua ->
        val (buffers, failures) = generate(
            lua,
            """
            local terrain = ...
            local towers = terrain.scatter("towers", 32, function(cell_x, cell_z)
              if cell_x ~= 0 or cell_z ~= 0 then return nil end
              return terrain.structure():fill(28, 100, 8, 36, 100, 8, "minecraft:gold_block")
            end)
            assert(towers:get(0, 0) ~= nil and towers:get(1, 0) == nil)
            assert(towers:at(40, 8) == nil and select(2, towers:at(40, 8)) == 1)
            assert(not pcall(terrain.scatter, "late", 16, function() end, 9))
            return {
              decorate = function(chunk) towers:place(chunk) end,
            }
            """,
            2 to 0
        )
        assertEquals(emptyList(), failures)
        // Chunk 2 is x 32 to 47, in cell 1: the tower of cell 0 reaches into it.
        assertEquals("minecraft:gold_block", buffers.single().label(36, 100, 8))
        assertEquals("minecraft:air", buffers.single().label(37, 100, 8))
    }

    @Test
    fun theBlocksAScriptNamesInFullArePlacedWithoutBeingListed(): LuaTest = withLua { lua ->
        val source = """
            local SHAPES = { lamp = "minecraft:sea_lantern", stairs = "minecraft:oak_stairs[facing=east]" }
            return {
              decorate = function(chunk)
                chunk:set(1, 100, 1, SHAPES.lamp)
                chunk:set(2, 100, 1, SHAPES.stairs)
                chunk:set(3, 100, 1, "ruby_ore")
                assert(not pcall(function() chunk:set(4, 100, 1, "minecraft:" .. "glowstone") end))
              end,
            }
        """
        val scripted = compiled.withScriptBlocks(listOf(source), { it != "minecraft:diamond" }) { it == "ruby_ore" }
        assertEquals(compiled.palette, scripted.palette.take(compiled.palette.size), "the file's own keep their places")
        val failures = mutableListOf<TerrainScriptFailure>()
        val generator = scripted.bind(7L, -64, 320, TerrainScripts(lua, mapOf(script to source)) { failures += it })
        val chunk = generator.generate(0, 0)
        generator.close()
        assertEquals(emptyList(), failures.map { it.message })
        assertEquals("minecraft:sea_lantern", scripted.palette[chunk[1, 100, 1]].label)
        assertEquals("minecraft:oak_stairs[facing=east]", scripted.palette[chunk[2, 100, 1]].label)
        assertEquals("ruby_ore", scripted.palette[chunk[3, 100, 1]].label)
    }

    @Test
    fun vec3AndNfMathAreThereAsInOtherScripts(): LuaTest = withLua { lua ->
        val (_, failures) = generate(
            lua,
            """
            local v = vec3(1, 2, 2)
            assert(v:length() == 3 and v.x == 1 and tostring(v) == "vec3(1, 2, 2)", tostring(v))
            assert((v + vec3.one) == vec3(2, 3, 3) and (v * 2).z == 4 and vec3.up:cross(vec3.east) == vec3(0, 0, -1))
            assert(vec3(0, -3, 0):reflect(vec3.up) == vec3(0, 3, 0) and vec3(5, 0, 0):clamp_length(2) == vec3(2, 0, 0))
            assert(not pcall(function() v.x = 3 end))
            assert(nf.math.clamp(5, 0, 2) == 2 and nf.math.lerp(0, 10, 0.5) == 5 and nf.math.remap(5, 0, 10, 0, 1) == 0.5)
            assert(nf.math.ease("ease_in", 0.5) == 0.25 and not pcall(nf.math.ease, "bouncy", 0.5))
            assert(nf.players == nil and nf.random == nil)
            return {
              decorate = function(chunk)
                local p = vec3(chunk:min_x(), 100, chunk:min_z()):floor()
                chunk:set(p.x, p.y, p.z, "minecraft:gold_block")
              end,
            }
            """,
            0 to 0
        )
        assertEquals(emptyList(), failures)
    }
}
