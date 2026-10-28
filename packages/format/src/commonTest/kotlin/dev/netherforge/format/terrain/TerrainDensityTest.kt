package dev.netherforge.format.terrain

import dev.netherforge.format.LuaTest
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.withLua
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 3D terrain: a file's `terrain.density` makes overhangs and floating islands, everything after the terrain
 * follows the solid blocks it makes (layers from every surface, the sea, caves, decorations on every top, the
 * column's surfaces), its areas blend across borders, the script's `density` stage changes it, and the same seed
 * makes the same blocks on the JVM and in JS.
 */
class TerrainDensityTest {
    private val path = "terrain/test.json"

    private fun file(json: String): TerrainFile {
        val parsed = TerrainKind.parse(json, path)
        check(parsed is CanonicalJson.Parsed.Ok) { "didn't parse: $parsed" }
        return parsed.value
    }

    private fun compile(json: String, script: String? = null): CompiledTerrain = TerrainCompiler.compile(file(json), script)

    private fun fixture(): CompiledTerrain = compile(TestFiles.read("packages/format/testdata/terrain/density.json")!!)

    private fun codes(json: String): List<Pair<String, String?>> {
        val sink = ProblemSink(path)
        TerrainValidator.validate(file(json), sink, null)
        return sink.problems.map { it.code.orEmpty() to it.path }
    }

    private fun index(w: CompiledTerrain, label: String) = w.palette.indexOfFirst { it.label == label }

    private fun solid(w: CompiledTerrain, block: Int) = block != 0 && block != w.fluid

    /** The y of each top surface of a column of a chunk's blocks: a solid block with no solid one on it, from the top. */
    private fun tops(w: CompiledTerrain, buffer: ChunkBuffer, lx: Int, lz: Int): List<Int> =
        (buffer.maxY - 1 downTo buffer.minY).filter { y ->
            solid(w, buffer[lx, y, lz]) && (y == buffer.maxY - 1 || !solid(w, buffer[lx, y + 1, lz]))
        }

    /** Ground, overhangs and islands, nothing else: no caves, ores, floor or decorations to tell apart. */
    private val bare = """{
        "terrain": { "base": 70, "seaLevel": 62, "noises": { "hills": { "noise": { "frequency": 0.006, "octaves": 3 }, "amplitude": 16 } },
            "density": {
                "noises": { "overhangs": { "noise": { "frequency": 0.03, "octaves": 2 }, "amplitude": 12, "squash": 2 } },
                "islands": { "y": 150, "thickness": 28, "threshold": 0.3 }
            } },
        "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }]
    }"""

    // ---- the shape ---------------------------------------------------------------------------------------

    @Test
    fun overhangsAndIslandsMakeColumnsWithSeveralSurfaces() {
        val w = compile(bare)
        val g = w.bind(5L, -64, 320)
        val grass = index(w, "minecraft:grass_block")
        var overhangs = 0
        var islands = 0
        val sampler = g.sampler()
        for (cx in -2..2) {
            for (cz in -2..2) {
                val buffer = g.generate(cx, cz)
                for (lx in 0 until 16) {
                    for (lz in 0 until 16) {
                        val tops = tops(w, buffer, lx, lz)
                        // The chunk's surfaces are the column's, as the column answers them alone (what decorations and the map ask).
                        assertEquals(
                            tops,
                            sampler.surfacesAt(cx * 16 + lx, cz * 16 + lz).toList(),
                            "the surfaces at ${cx * 16 + lx},${cz * 16 + lz}"
                        )
                        // A lone column (a fresh sampler each time) answers the same; a quarter of them is plenty to show it.
                        if (lx % 2 == 0 && lz % 2 == 0) assertEquals(tops.firstOrNull() ?: -65, g.surfaceAt(cx * 16 + lx, cz * 16 + lz))
                        if (tops.size < 2) continue
                        if (tops[0] >= 120) islands++ else overhangs++
                        // Every dry surface is topped with the layers, the ground under an overhang too.
                        for (top in tops) if (top >= 62) assertEquals(grass, buffer[lx, top, lz], "the top at $top")
                    }
                }
            }
        }
        assertTrue(overhangs > 5, "columns with ground under an overhang: $overhangs")
        assertTrue(islands > 20, "columns under an island: $islands")
    }

    @Test
    fun withoutNoisesADensityMakesTheHeightmapsBlocks() {
        val heights = """{
            "terrain": { "base": 64, "seaLevel": 60, "noises": { "hills": { "noise": { "frequency": 0.01, "octaves": 2 }, "amplitude": 14 } } DENSITY },
            "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 2 }],
            "underwater": [{ "block": "minecraft:sand", "thickness": 2 }],
            "floor": { "block": "minecraft:bedrock" },
            "caves": { "caverns": { "threshold": 0.3 }, "tunnels": { "type": "spaghetti", "depth": 0 } },
            "ores": { "iron": { "block": "minecraft:iron_ore", "veins": 20 } },
            "decorations": {
                "flowers": { "block": "minecraft:poppy", "count": 20 },
                "drips": { "block": "minecraft:pointed_dripstone", "placement": "caveCeiling", "count": 20 },
                "weed": { "block": "minecraft:seagrass", "placement": "underwater", "count": 10 }
            },
            "biomes": {
                "a": { "biome": "minecraft:plains", "temperature": { "max": 0 }, "terrain": { "base": 70 } },
                "b": { "biome": "minecraft:desert", "temperature": { "min": 0 }, "terrain": { "base": 55, "scale": 2 } }
            }
        }"""
        val plain = compile(heights.replace("DENSITY", "")).bind(9L, -64, 320)
        val flat = compile(heights.replace("DENSITY", ", \"density\": {}")).bind(9L, -64, 320)
        for ((cx, cz) in listOf(0 to 0, 7 to -3, -20 to 11, 40 to 40)) {
            assertContentEquals(plain.generate(cx, cz).blocks, flat.generate(cx, cz).blocks, "chunk $cx,$cz")
        }
        for (x in -300..300 step 37) assertEquals(plain.surfaceAt(x, x / 3), flat.surfaceAt(x, x / 3))
        assertEquals(plain.dryColumnNear(0, 0), flat.dryColumnNear(0, 0))
    }

    @Test
    fun theSeaFillsEverySpaceBelowItsLevel() {
        val w = compile(
            """{ "terrain": { "base": 50, "seaLevel": 62, "density": { "noises": { "o": { "noise": { "frequency": 0.04 }, "amplitude": 20, "squash": 3 } } } },
                "layers": [{ "block": "minecraft:grass_block" }], "underwater": [{ "block": "minecraft:sand" }] }"""
        )
        val g = w.bind(3L, -64, 320)
        val sand = index(w, "minecraft:sand")
        var floors = 0
        for (cx in 0..3) {
            val buffer = g.generate(cx, 0)
            for (lx in 0 until 16) {
                for (lz in 0 until 16) {
                    for (y in -63..62) if (buffer[lx, y, lz] == 0) error("air below the sea at ${cx * 16 + lx},$y,$lz")
                    for (top in tops(w, buffer, lx, lz)) {
                        if (top < 62) {
                            assertEquals(sand, buffer[lx, top, lz])
                            assertEquals(w.fluid, buffer[lx, top + 1, lz])
                            floors++
                        }
                    }
                }
            }
        }
        assertTrue(floors > 256)
    }

    @Test
    fun cavesKeepTheirDepthUnderEverySurface() {
        val w = compile(
            bare.replace(
                "\"layers\"",
                "\"caves\": { \"caverns\": { \"threshold\": -0.2, \"depth\": 6, \"minY\": -60, \"maxY\": 300 } }, \"layers\""
            )
        )
        val uncarved = compile(bare).bind(5L, -64, 320)
        val g = w.bind(5L, -64, 320)
        var carved = 0
        for (cx in 0..2) {
            val before = uncarved.generate(cx, 1)
            val after = g.generate(cx, 1)
            for (lx in 0 until 16) {
                for (lz in 0 until 16) {
                    for (y in -63..300) {
                        if (solid(w, before[lx, y, lz]) && after[lx, y, lz] == 0) {
                            carved++
                            for (d in 1..6) assertTrue(solid(w, before[lx, y + d, lz]), "a cave within 6 of a surface at $lx,$y,$lz")
                        }
                    }
                }
            }
        }
        assertTrue(carved > 1000, "carved $carved")
    }

    @Test
    fun surfaceDecorationsGrowOnIslandsAndUnderOverhangs() {
        val w =
            compile(
                bare.replace(
                    "\"layers\"",
                    "\"decorations\": { \"flowers\": { \"block\": \"minecraft:poppy\", \"count\": 160 } }, \"layers\""
                )
            )
        val g = w.bind(5L, -64, 320)
        val poppy = index(w, "minecraft:poppy")
        val grass = index(w, "minecraft:grass_block")
        var high = 0
        var shaded = 0
        for (cx in -1..1) {
            for (cz in -1..1) {
                val buffer = g.generate(cx, cz)
                for (lx in 0 until 16) {
                    for (lz in 0 until 16) {
                        for (y in -63 until 319) {
                            if (buffer[lx, y, lz] != poppy) continue
                            assertEquals(grass, buffer[lx, y - 1, lz])
                            if (y > 120) high++
                            if ((y + 1 until 320).any { solid(w, buffer[lx, it, lz]) && buffer[lx, it, lz] != poppy }) shaded++
                        }
                    }
                }
            }
        }
        assertTrue(high > 10, "flowers on islands: $high")
        assertTrue(shaded > 10, "flowers under something: $shaded")
    }

    // ---- areas and their borders ------------------------------------------------------------------------

    /** Two areas whose ground differs a lot: a low and a high base, and 3D noises only in the high one. */
    private fun twoAreas(blend: Int) = """{
        "terrain": { "base": 64, "seaLevel": -64, "blend": $blend, "density": {} },
        "climate": { "temperature": { "frequency": 0.004 } },
        "biomes": {
            "low": { "biome": "minecraft:plains", "temperature": { "max": 0 }, "terrain": { "base": 50 } },
            "high": { "biome": "minecraft:jagged_peaks", "temperature": { "min": 0 },
                "terrain": { "base": 100, "density": { "noises": { "n": { "noise": { "frequency": 0.04 }, "amplitude": 4 } } } } }
        }
    }"""

    @Test
    fun theGroundBlendsAcrossBorders() {
        val blended = compile(twoAreas(24)).bind(11L, -64, 320)
        val cliffs = compile(twoAreas(0)).bind(11L, -64, 320)
        fun steepest(g: TerrainGenerator): Int {
            val sampler = g.sampler()
            var last = sampler.surfaceAt(-2000, 40)
            var most = 0
            for (x in -1999..2000) {
                val here = sampler.surfaceAt(x, 40)
                most = maxOf(most, abs(here - last))
                last = here
            }
            return most
        }
        val areas = (-2000..2000 step 50).map { blended.areaAt(it, 40) }.toSet()
        assertEquals(setOf(0, 1), areas, "the line crosses both areas")
        assertTrue(steepest(cliffs) >= 30, "a cliff where blend is 0: ${steepest(cliffs)}")
        assertTrue(steepest(blended) <= 6, "a slope where it's 24: ${steepest(blended)}")
        // Far from a border each area is its own: the low one exactly its height, with no 3D noise.
        val low = blended.terrain.areas.indexOfFirst { it.name == "low" }
        var far = 0
        val sampler = blended.sampler()
        for (x in -2000..2000 step 10) {
            // Low ground every 16 blocks for 48 round it: far from any border (the blend is 24 wide).
            if ((-48..48 step 16).all { dx -> (-48..48 step 16).all { dz -> blended.areaAt(x + dx, 40 + dz) == low } }) {
                far++
                assertEquals(sampler.baseHeight(x, 40), blended.surfaceAt(x, 40))
            }
        }
        assertTrue(far > 20)
    }

    @Test
    fun islandsKeepToTheirAreasAndFadeAtTheBorder() {
        val json = """{
            "terrain": { "base": 40, "seaLevel": -64, "blend": 32,
                "density": { "islands": { "y": 150, "thickness": 30, "threshold": 0, "biomes": ["sky"] } } },
            "climate": { "temperature": { "frequency": 0.003 } },
            "biomes": {
                "sky": { "biome": "minecraft:plains", "temperature": { "min": 0 } },
                "ground": { "biome": "minecraft:desert", "temperature": { "max": 0 } }
            }
        }"""
        val g = compile(json).bind(2L, -64, 320)
        val sky = g.terrain.areas.indexOfFirst { it.name == "sky" }
        var inSky = 0
        for (x in -1500..1500 step 3) {
            val area = g.areaAt(x, 0)
            val island = g.sampler().surfacesAt(x, 0).any { it > 100 }
            val nearSky = (-48..48 step 4).any { dx -> (-48..48 step 4).any { dz -> g.areaAt(x + dx, dz) == sky } }
            if (island) assertTrue(nearSky, "an island far from the sky area at $x")
            if (island && area == sky) inSky++
        }
        assertTrue(inSky > 50, "islands over the sky area: $inSky")
    }

    @Test
    fun aChunkIsTheSameWhicheverOrderItsMadeIn() {
        val w = fixture()
        val a = w.bind(77L, -64, 320)
        val first = a.generate(3, -2).blocks
        a.generate(4, -2)
        a.generate(3, -1)
        assertContentEquals(first, a.generate(3, -2).blocks)
        assertContentEquals(first, w.bind(77L, -64, 320).generate(3, -2).blocks)
    }

    // ---- what the rest of the world asks ------------------------------------------------------------------

    @Test
    fun theMapAndASpawnSeeTheTopmostGround() {
        val w = compile(bare)
        val g = w.bind(5L, -64, 320)
        val map = TerrainPreview.map(g, -64, -64, 32, 4)
        for (row in 0 until 32) {
            for (col in 0 until 32) {
                assertEquals(g.surfaceAt(-64 + col * 4, -64 + row * 4), map.heights[row * 32 + col])
            }
        }
        val (x, z) = g.dryColumnNear(0, 0)!!
        assertTrue(g.surfaceAt(x, z) >= 62)
        val slice = TerrainPreview.slice(g, true, 7, -32, 64)
        val buffer = g.generate(-2, 0)
        // A slice's column is the chunk's blocks, runs of them from the bottom.
        val runs = slice.columns[0]
        var y = -64
        for (i in runs.indices step 2) {
            repeat(runs[i + 1]) { assertEquals(runs[i], buffer[0, y++, 7]) }
        }
    }

    @Test
    fun aWorldOfOtherHeightsKeepsItsDensityInside() {
        val g = compile(bare).bind(5L, 0, 128)
        val buffer = g.generate(0, 0)
        assertEquals(0, buffer.minY)
        for (lx in 0 until 16) for (lz in 0 until 16) assertTrue(g.surfaceAt(lx, lz) in -1..127)
    }

    // ---- validation -------------------------------------------------------------------------------------

    @Test
    fun theDensityIsValidated() {
        val found = codes(
            """{ "terrain": { "density": {
                    "noises": { "Bad Name": {}, "n": { "squash": 0, "amplitude": 5000, "noise": { "octaves": 0 } } },
                    "islands": { "y": 9000, "thickness": 1, "threshold": 1, "biomes": ["nowhere"] } } },
                "biomes": { "a": { "biome": "minecraft:plains", "terrain": { "density": { "scale": -1, "noises": { "m": { "squash": 20 } } } } } } }"""
        )
        assertEquals(
            setOf(
                "terrain.name" to "$.terrain.density.noises[\"Bad Name\"]",
                "terrain.density" to "$.terrain.density.noises.n.squash",
                "terrain.height" to "$.terrain.density.noises.n.amplitude",
                "terrain.noise" to "$.terrain.density.noises.n.noise.octaves",
                "terrain.density" to "$.terrain.density.islands.y",
                "terrain.density" to "$.terrain.density.islands.thickness",
                "terrain.density" to "$.terrain.density.islands.threshold",
                "terrain.area" to "$.terrain.density.islands.biomes[0]",
                "terrain.density" to "$.biomes.a.terrain.density.scale",
                "terrain.density" to "$.biomes.a.terrain.density.noises.m.squash"
            ),
            found.toSet()
        )
        assertEquals(
            listOf("terrain.density" to "$.biomes.a.terrain.density"),
            codes("""{ "biomes": { "a": { "biome": "minecraft:plains", "terrain": { "density": {} } } } }""")
        )
    }

    // ---- the script's density stage ------------------------------------------------------------------------

    private class Failures : ArrayList<TerrainScriptFailure>()

    @Test
    fun aScriptsDensityStageChangesTheGround(): LuaTest = withLua { lua ->
        val json = bare.replace("\"layers\"", "\"script\": {}, \"layers\"")
        val w = compile(json, "terrain/t.lua")
        // A floating slab at 200 to 215 over x 0 to 31, and every point's value checked against the file's own.
        val source = """
            return {
              density = function(x, y, z, value)
                assert(math.type(x) == "integer" and math.type(y) == "integer" and math.type(value) == "float")
                assert(x % 4 == 0 and z % 4 == 0 and y % 8 == 0)
                if x >= 0 and x <= 32 and y >= 200 and y <= 216 then return 1 + math.random() * 0.1 end
                return value
              end,
            }
        """
        val failures = Failures()
        val scripted = w.bind(5L, -64, 320, TerrainScripts(lua, mapOf("terrain/t.lua" to source)) { failures += it })
        val plain = compile(bare).bind(5L, -64, 320)
        val buffer = scripted.generate(0, 0)
        val same = plain.generate(0, 0)
        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                assertTrue((200..216).all { buffer[lx, it, lz] != 0 }, "the slab at $lx,$lz")
                assertEquals(216, scripted.surfaceAt(lx, lz))
                for (y in -64 until 190) assertEquals(same[lx, y, lz], buffer[lx, y, lz], "below the slab at $lx,$y,$lz")
            }
        }
        // Outside it, the file's own ground.
        assertContentEquals(plain.generate(-5, 3).blocks, scripted.generate(-5, 3).blocks)
        assertEquals(emptyList(), failures.toList())
        scripted.close()
    }

    @Test
    fun aDensityStageThatFailsLeavesTheFilesGround(): LuaTest = withLua { lua ->
        val json = bare.replace("\"layers\"", "\"script\": { \"budget\": 5000 }, \"layers\"")
        val w = compile(json, "terrain/t.lua")
        val source = """
            return {
              density = function(x, y, z, value)
                if y > 100 then error("too high") end
                if y < -40 then while true do end end
                return value
              end,
            }
        """
        val failures = Failures()
        val scripted = w.bind(5L, -64, 320, TerrainScripts(lua, mapOf("terrain/t.lua" to source)) { failures += it })
        assertContentEquals(compile(bare).bind(5L, -64, 320).generate(1, 1).blocks, scripted.generate(1, 1).blocks)
        assertTrue(failures.any { it.stage == "density" && it.message.contains("too high") && it.location == ("terrain/t.lua" to 4) })
        assertTrue(failures.any { it.stage == "density" && it.message.contains("budget") })
        scripted.close()
        // A density stage in a file with no density never runs, and says so as the script loads.
        val heightsOnly = compile("""{ "terrain": { "base": 60 }, "script": {} }""", "terrain/t.lua")
        val said = TerrainScripts(lua, mapOf("terrain/t.lua" to source)) {}.check(heightsOnly)
        assertEquals("load", said?.stage)
        assertTrue(said!!.message.contains("terrain.density"), said.message)
    }

    // ---- the same on every platform ----------------------------------------------------------------------

    private fun summary(): List<String> {
        val w = fixture()
        val lines = mutableListOf("palette ${w.palette.joinToString(",") { it.label }}")
        for (seed in listOf(7L, -123456789012345L)) {
            val g = w.bind(seed, -64, 320)
            val sampler = g.sampler()
            lines += "seed $seed heights ${(0 until 8).joinToString(",") { g.surfaceAt(it * 131 - 500, it * -77 + 60).toString() }}"
            lines += "seed $seed surfaces ${(0 until 8).joinToString(";") { sampler.surfacesAt(it * 53 - 200, it * 31).joinToString(",") }}"
            for ((cx, cz) in listOf(0 to 0, 0 to -6, 4 to -4, -6 to 10, -10 to 4, 30 to 2)) {
                val b = g.generate(cx, cz)
                var hash = 0x811C9DC5.toInt()
                val counts = IntArray(w.palette.size)
                for (index in b.blocks) {
                    hash = (hash xor index) * 0x01000193
                    counts[index]++
                }
                lines += "seed $seed chunk $cx,$cz hash $hash counts ${counts.joinToString(",")}"
            }
        }
        return lines
    }

    @Test
    fun aSeedMakesTheSameWorldOnEveryPlatform() {
        val actual = summary()
        val golden = "packages/format/testdata/terrain/density.txt"
        if (TestFiles.updateGolden) {
            TestFiles.write(golden, actual.joinToString("\n") + "\n")
            return
        }
        assertEquals(TestFiles.read(golden)!!.lines().filter { it.isNotBlank() }, actual)
    }
}
