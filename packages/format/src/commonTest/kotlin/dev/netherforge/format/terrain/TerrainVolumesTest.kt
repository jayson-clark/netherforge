package dev.netherforge.format.terrain

import dev.netherforge.format.LuaTest
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.TestFiles
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.lua.LuaPlatform
import dev.netherforge.format.withLua
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Biomes that change with height (volume areas, the script's `biome` stage), the file's own climate values, the
 * script's `area` stage, plans, and containers a terrain fills from loot tables. The script cases run on both
 * platforms ([withLua]).
 */
class TerrainVolumesTest {
    private fun file(json: String): TerrainFile {
        val parsed = TerrainKind.parse(json, "terrain/t.json")
        check(parsed is CanonicalJson.Parsed.Ok) { "didn't parse: $parsed" }
        return parsed.value
    }

    private fun compile(json: String): CompiledTerrain = TerrainCompiler.compile(file(json), "terrain/t.lua")

    private fun problems(json: String): List<Pair<String, String>> {
        val sink = ProblemSink("terrain/t.json")
        TerrainValidator.validate(file(json), sink, null)
        return sink.problems.map { it.code!! to it.path!! }
    }

    private val caves = """{
        "terrain": { "base": 64, "seaLevel": 40 },
        "biomes": {
          "plains": { "biome": "minecraft:plains" },
          "deep": { "biome": "minecraft:lush_caves", "depth": { "min": 20 } },
          "sky": { "biome": "minecraft:the_void", "y": { "min": 200 } }
        }
    }"""

    // ---- volumes ------------------------------------------------------------------------------------------

    @Test
    fun aVolumeIsTheBiomeOfThePlacesItsRangesHold() {
        val generator = compile(caves).bind(1, -64, 320)
        // The ground is flat at 64: 20 blocks below it is 44, and the cell's corner is what's asked.
        assertEquals("minecraft:plains", generator.biomeAt(5, 64, 5))
        assertEquals("minecraft:plains", generator.biomeAt(5, 48, 5))
        assertEquals("minecraft:lush_caves", generator.biomeAt(5, 44, 5))
        assertEquals("minecraft:lush_caves", generator.biomeAt(5, -60, 5))
        assertEquals("minecraft:the_void", generator.biomeAt(5, 203, 5))
        // A column's own area never is a volume.
        assertEquals("minecraft:plains", generator.biomeAt(5, 5))
        assertEquals(listOf("minecraft:lush_caves", "minecraft:plains", "minecraft:the_void"), compile(caves).biomes)
    }

    @Test
    fun aFileWithoutVolumesHasOneAreaPerColumn() {
        val generator = compile("""{ "biomes": { "a": { "biome": "minecraft:plains" } } }""").bind(1, -64, 320)
        assertEquals(false, generator.pointAreas)
        assertEquals(generator.areaAt(3, 9), generator.pointAreaAt(3, -50, 9))
    }

    @Test
    fun filtersSeeAPlacesOwnArea() {
        val json = """{
            "terrain": { "base": 64 },
            "biomes": {
              "plains": { "biome": "minecraft:plains" },
              "deep": { "biome": "minecraft:lush_caves", "y": { "max": 0 } }
            },
            "caves": { "rooms": { "threshold": -1, "minY": -60, "maxY": 60, "depth": 0, "biomes": ["deep"] } }
        }"""
        val compiled = compile(json)
        val buffer = compiled.bind(1, -64, 320).generate(0, 0)
        val stone = compiled.stone
        // Every place of the volume is carved (a threshold of -1 is everywhere), and nothing above it: the cell at 0
        // is the volume's (its corner is), so to 3.
        for (y in -60..3) assertEquals(0, buffer[3, y, 3], "carved at $y")
        for (y in 4..60) assertEquals(stone, buffer[3, y, 3], "stone at $y")
    }

    @Test
    fun aVolumesClimateMustFitExactly() {
        val json = """{
            "climate": { "noises": { "evil": { "frequency": 0.01 } } },
            "biomes": {
              "plains": { "biome": "minecraft:plains" },
              "corrupt_caves": { "biome": "minecraft:deep_dark", "depth": { "min": 10 }, "climate": { "evil": { "min": 0.3 } } }
            }
        }"""
        val generator = compile(json).bind(5, -64, 320)
        var inside = 0
        var outside = 0
        for (i in 0 until 400) {
            val x = i * 37
            val evil = generator.climateAt(x and -4, 0)[2]
            val biome = generator.biomeAt(x, -20, 0)
            if (evil >= 0.3) {
                inside++
                assertEquals("minecraft:deep_dark", biome, "evil $evil at $x")
            } else {
                outside++
                assertEquals("minecraft:plains", biome, "evil $evil at $x")
            }
        }
        assertTrue(inside > 0 && outside > 0, "both: $inside, $outside")
    }

    @Test
    fun aClimateValuePicksColumnsToo() {
        val json = """{
            "climate": { "noises": { "evil": { "frequency": 0.01 } } },
            "biomes": {
              "forest": { "biome": "minecraft:forest", "climate": { "evil": { "max": 0 } } },
              "corrupt": { "biome": "minecraft:badlands", "climate": { "evil": { "min": 0 } } }
            }
        }"""
        val generator = compile(json).bind(5, -64, 320)
        for (i in 0 until 200) {
            val x = i * 53
            val evil = generator.climateAt(x, 7)[2]
            assertEquals(if (evil > 0) "minecraft:badlands" else "minecraft:forest", generator.biomeAt(x, 7), "evil $evil")
        }
    }

    @Test
    fun volumesAreCheckedForWhatTheyCantHave() {
        val bad = """{
            "terrain": { "density": { "islands": { "biomes": ["deep"] } } },
            "biomes": {
              "plains": { "biome": "minecraft:plains", "climate": { "nope": { "min": 0 } } },
              "deep": { "biome": "minecraft:lush_caves", "depth": { "min": 30, "max": 10 }, "layers": [{ "block": "minecraft:dirt" }] }
            }
        }"""
        val found = problems(bad)
        assertTrue(ProblemCodes.TERRAIN_VOLUME.code to "$.biomes.deep.depth.min" in found, "$found")
        assertTrue(ProblemCodes.TERRAIN_VOLUME.code to "$.biomes.deep.layers" in found, "$found")
        assertTrue(ProblemCodes.TERRAIN_VOLUME.code to "$.terrain.density.islands.biomes[0]" in found, "$found")
        assertTrue(ProblemCodes.TERRAIN_CLIMATE.code to "$.biomes.plains.climate.nope" in found, "$found")
        val allVolumes = problems("""{ "biomes": { "deep": { "biome": "minecraft:lush_caves", "y": { "max": 0 } } } }""")
        assertEquals(listOf(ProblemCodes.TERRAIN_VOLUME.code to "$.biomes"), allVolumes)
        assertEquals(emptyList(), problems(caves))
    }

    // ---- loot -----------------------------------------------------------------------------------------------

    @Test
    fun aStructuresContainersAreMarkedWithItsLootTable() {
        val json = """{
            "terrain": { "base": 64 },
            "decorations": {
              "shrine": { "structure": "shrine", "count": 1, "loot": "shrine_chest", "rotate": false },
              "barrel": { "block": "minecraft:barrel", "count": 1, "loot": "barrel_junk" }
            }
        }"""
        val shrine = StructureTemplate(
            3,
            2,
            3,
            listOf("minecraft:stone", "minecraft:chest[facing=north]"),
            intArrayOf(0, 0, 0, 0, 1, 0, 1, 0, 1, 1, 1, 1),
            withEntity = setOf(1)
        )
        val compiled = compile(json).withStructures(mapOf("shrine" to shrine))
        assertEquals(listOf("barrel_junk", "shrine_chest"), compiled.loot)
        val generator = compiled.bind(9, -64, 320)
        val chest = compiled.palette.indexOfFirst { it.label == "minecraft:chest[facing=north]" }
        val barrel = compiled.palette.indexOfFirst { it.label == "minecraft:barrel" }
        var chests = 0
        var barrels = 0
        for (cx in -3..3) {
            for (cz in -3..3) {
                val buffer = generator.generate(cx, cz)
                for (place in buffer.lootPlaces(cx, cz)) {
                    val block = buffer[place.x - cx * 16, place.y, place.z - cz * 16]
                    when (compiled.loot[place.table]) {
                        "shrine_chest" -> {
                            assertEquals(chest, block)
                            chests++
                        }
                        else -> {
                            assertEquals(barrel, block)
                            barrels++
                        }
                    }
                }
                // Every chest the shrines placed is marked: nothing else of theirs is.
                assertEquals(buffer.blocks.count { it == chest }, buffer.loot.count { compiled.loot[it.value] == "shrine_chest" })
            }
        }
        assertTrue(chests > 0 && barrels > 0, "$chests chests, $barrels barrels")
    }

    // ---- the script's stages ------------------------------------------------------------------------------

    private class Run(val failures: MutableList<TerrainScriptFailure> = mutableListOf())

    private fun scripts(lua: LuaPlatform, source: String, run: Run) = TerrainScripts(lua, mapOf("terrain/t.lua" to source)) {
        run.failures +=
            it
    }

    private fun scripted(extra: String = "") = """{
        "terrain": { "base": 64, "seaLevel": 40 },
        "biomes": {
          "east": { "biome": "minecraft:plains" },
          "west": { "biome": "minecraft:desert", "terrain": { "base": 80 } },
          "deep": { "biome": "minecraft:lush_caves", "depth": { "min": 20 } }
        },
        "script": { "blocks": ["minecraft:chest"], "loot": ["treasure"] }$extra
    }"""

    @Test
    fun theAreaStageChoosesColumns(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """
            local terrain = ...
            return {
              area = function(x, z, area)
                assert(area == "east" or area == "west")
                if x < 0 then return "west" end
                return "east"
              end,
            }
        """
        val generator = compile(scripted()).bind(1, -64, 320, scripts(lua, source, run))
        assertEquals("minecraft:desert", generator.biomeAt(-100, 0))
        assertEquals("minecraft:plains", generator.biomeAt(100, 0))
        // The area shapes the ground: the west's own base.
        assertEquals(80, generator.surfaceAt(-500, 0))
        assertEquals(64, generator.surfaceAt(500, 0))
        generator.close()
        assertEquals(emptyList(), run.failures)
    }

    @Test
    fun anAreaStageCantReturnAVolume(): LuaTest = withLua { lua ->
        val run = Run()
        val source = "return { area = function(x, z, area) return \"deep\" end }"
        val generator = compile(scripted()).bind(1, -64, 320, scripts(lua, source, run))
        assertTrue(generator.areaAt(0, 0) in listOf(0, 1, 2).filter { compile(scripted()).areas[it].volume == null })
        generator.close()
        assertTrue(
            run.failures.isNotEmpty() &&
                run.failures.all {
                    it.stage == "area" && "isn't limited by height" in it.message
                },
            "${run.failures}"
        )
    }

    @Test
    fun theBiomeStageChoosesPlacesAndTheChunkAgrees(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """
            local terrain = ...
            return {
              biome = function(x, y, z, area)
                if y > terrain.height(x, z) + 50 then return "west" end
                return area
              end,
              decorate = function(chunk)
                local x, z = chunk:min_x(), chunk:min_z()
                assert(terrain.area(x, 200, z) == "west", terrain.area(x, 200, z))
                assert(terrain.biome(x, -40, z) == "minecraft:lush_caves")
                assert(terrain.area(x, z) ~= "deep")
              end,
            }
        """
        val compiled = compile(scripted())
        val generator = compiled.bind(1, -64, 320, scripts(lua, source, run))
        assertEquals("minecraft:desert", generator.biomeAt(8, 200, 8))
        assertEquals("minecraft:lush_caves", generator.biomeAt(8, -40, 8))
        generator.generate(0, 0)
        generator.close()
        assertEquals(emptyList(), run.failures)
    }

    // ---- looking for a biome ------------------------------------------------------------------------------

    @Test
    fun aLocateRingIsItsSquaresEdgeInTheGamesOrder() {
        assertEquals(listOf(0 to 0), TerrainGenerator.locateRing(0, 32))
        val ring = TerrainGenerator.locateRing(64, 32)
        assertEquals(16, ring.size)
        assertEquals(16, ring.toSet().size)
        assertTrue(ring.all { (dx, dz) -> maxOf(abs(dx), abs(dz)) == 64 })
        // The west side first, south to north, then each x's two ends, then the east side.
        assertEquals(listOf(-64 to -64, -64 to -32, -64 to 0, -64 to 32, -64 to 64, -32 to -64, -32 to 64), ring.take(7))
        assertEquals(64 to 64, ring.last())
    }

    @Test
    fun aColumnIsLookedAtFromTheAskersHeightOutwards() {
        val generator = compile(caves).bind(1, -64, 320)
        assertContentEquals(intArrayOf(64, 128, 0, 192, -64, 256), generator.locateHeights(64, 64))
        // A height outside the world is the nearest inside it.
        assertEquals(319, generator.locateHeights(1000, 64).first())
    }

    @Test
    fun aVolumeIsFoundAtTheFirstHeightOfAColumnItHolds() {
        val generator = compile(caves).bind(1, -64, 320)
        val deep = generator.areasWhere { it == "minecraft:lush_caves" }
        val plains = generator.areasWhere { it == "minecraft:plains" }
        generator.sampling { sampler ->
            assertEquals(0, sampler.firstWhere(5, 5, generator.locateHeights(64, 64), deep))
            assertEquals(64, sampler.firstWhere(5, 5, generator.locateHeights(64, 64), plains))
            assertEquals(null, sampler.firstWhere(5, 5, intArrayOf(64, 128), deep))
        }
    }

    @Test
    fun theBiomeStageIsWhatALocateFinds(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """
            local terrain = ...
            return {
              biome = function(x, y, z, area)
                if x >= 64 and y > terrain.height(x, z) + 50 then return "west" end
                return area
              end,
            }
        """
        val generator = compile(scripted()).bind(1, -64, 320, scripts(lua, source, run))
        val desert = generator.areasWhere { it == "minecraft:desert" }
        val ys = generator.locateHeights(64, 64)
        generator.sampling { sampler ->
            assertEquals(null, sampler.firstWhere(0, 0, ys, desert))
            assertEquals(128, sampler.firstWhere(64, 0, ys, desert))
        }
        generator.close()
        assertEquals(emptyList(), run.failures)
    }

    @Test
    fun stagesCantAskWhatDependsOnThem(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """
            local terrain = ...
            return {
              area = function(x, z, area) terrain.height(x, z) return area end,
              height = function(x, z, h) terrain.area(x, 10, z) return h end,
            }
        """
        val generator = compile(scripted()).bind(1, -64, 320, scripts(lua, source, run))
        generator.surfaceAt(0, 0)
        generator.close()
        val messages = run.failures.map { "${it.stage}: ${it.message}" }
        assertTrue(messages.any { it.startsWith("area:") && "terrain.height can't be asked from the area stage" in it }, "$messages")
        assertTrue(messages.any { it.startsWith("height:") && "can't be asked from the height stage" in it }, "$messages")
    }

    @Test
    fun aPlanIsMadeOncePerCellAndTheSameEverywhere(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """
            local terrain = ...
            local made = 0
            local spots = terrain.plan("spot", 64, function(cell_x, cell_z)
              made = made + 1
              return { x = cell_x * 64 + math.random(0, 63), z = cell_z * 64 + math.random(0, 63), made = made }
            end)
            return {
              decorate = function(chunk)
                local before = math.random(1, 1000000)
                local spot = spots:at(chunk:min_x(), chunk:min_z())
                assert(spots:get(chunk:min_x() // 64, chunk:min_z() // 64) == spot)
                assert(spots:size() == 64)
                chunk:set(spot.x, 100, spot.z, "minecraft:chest")
                chunk:set_loot(spot.x, 100, spot.z, "treasure")
                -- How many plans this state made: the first chunk of each cell makes one, the rest reuse it.
                chunk:set(chunk:min_x(), 120 + spot.made, chunk:min_z(), "minecraft:chest")
                -- The stage's own numbers don't move whether the plan was made now or before.
                chunk:set(chunk:min_x() + 1, 0, chunk:min_z(), before % 2 == 0 and "minecraft:chest" or "minecraft:air")
              end,
            }
        """
        val compiled = compile(scripted())
        val a = compiled.bind(4, -64, 320, scripts(lua, source, run))
        val b = compiled.bind(4, -64, 320, scripts(lua, source, run))
        val chest = compiled.palette.indexOfFirst { it.label == "minecraft:chest" }
        // Every chunk of a 64-block cell, generated in another order by each.
        val chunks = (0 until 4).flatMap { cx -> (0 until 4).map { cz -> cx to cz } }
        val first = chunks.associateWith { (cx, cz) -> a.generate(cx, cz) }
        val second = chunks.reversed().associateWith { (cx, cz) -> b.generate(cx, cz) }
        val lootPlaces = first.entries.flatMap { (at, buffer) -> buffer.lootPlaces(at.first, at.second) }
        assertEquals(1, lootPlaces.size, "one spot in the cell: $lootPlaces")
        assertEquals(compiled.loot.indexOf("treasure"), lootPlaces.single().table)
        for (at in chunks) {
            val one = first.getValue(at)
            val other = second.getValue(at)
            // The spot is the same in both, and each made the cell's plan once (made is 1 in every chunk).
            assertEquals(chest, one[0, 121, 0])
            assertEquals(chest, other[0, 121, 0])
            assertEquals(one[1, 0, 0], other[1, 0, 0])
            assertEquals(one.loot, other.loot)
        }
        a.close()
        b.close()
        assertEquals(emptyList(), run.failures)
    }

    @Test
    fun aPlansNumbersAreTheSameOnEveryPlatform(): LuaTest = withLua { lua ->
        val run = Run()
        val source = """
            local terrain = ...
            local plan = terrain.plan("numbers", 16, function(cell_x, cell_z)
              return { math.random(), math.random(1, 6), math.random(-1000000, 1000000), math.random(1 << 40) }
            end)
            return {
              terrain = function(chunk)
                local p = plan:get(chunk:x(), chunk:z())
                local text = string.format("%.17g %d %d %d", p[1], p[2], p[3], p[4])
                -- Its numbers, as blocks: each digit of the text is a block's height.
                for i = 1, #text do
                  local digit = tonumber(text:sub(i, i))
                  if digit then chunk:set(chunk:min_x() + i % 16, 100 + digit, chunk:min_z() + i // 16, "minecraft:chest") end
                end
              end,
            }
        """
        val compiled = compile(scripted())
        val lines = mutableListOf<String>()
        for (seed in listOf(7L, -123456789012345L)) {
            val generator = compiled.bind(seed, -64, 320, scripts(lua, source, run))
            for ((cx, cz) in listOf(0 to 0, -3 to 5)) {
                val buffer = generator.generate(cx, cz)
                var hash = 0x811C9DC5.toInt()
                for (index in buffer.blocks) hash = (hash xor index) * 0x01000193
                lines += "seed $seed chunk $cx,$cz hash $hash"
            }
            generator.close()
        }
        assertEquals(emptyList(), run.failures)
        val golden = "packages/format/testdata/terrain/plans.txt"
        if (TestFiles.updateGolden) {
            TestFiles.write(golden, lines.joinToString("\n") + "\n")
        } else {
            assertEquals(TestFiles.read(golden)!!.lines().filter { it.isNotBlank() }, lines)
        }
    }

    @Test
    fun setLootTakesOnlyTheScriptsTables(): LuaTest = withLua { lua ->
        val run = Run()
        val source = "return { decorate = function(chunk) chunk:set_loot(chunk:min_x(), 70, chunk:min_z(), \"nope\") end }"
        val compiled = compile(scripted())
        val generator = compiled.bind(1, -64, 320, scripts(lua, source, run))
        val buffer = generator.generate(0, 0)
        generator.close()
        assertTrue(buffer.loot.isEmpty())
        assertTrue(run.failures.single().message.contains("isn't a loot table this generator fills"), "${run.failures}")
    }

    @Test
    fun theChunkCellsAreWhatAPlaceAnswers(): LuaTest = withLua { lua ->
        val source = "return { biome = function(x, y, z, area) if (x // 4 + y // 4) % 3 == 0 then return \"west\" end return area end }"
        val compiled = compile(scripted())
        val generator = compiled.bind(2, -64, 320, scripts(lua, source, Run()))
        val sampler = generator.sampler()
        val expected = (0 until 16 step 4).flatMap { x ->
            (-64 until 320 step 4).map { y -> generator.pointAreaAt(16 + x, y, 32, sampler) }
        }
        val again = (0 until 16 step 4).flatMap { x ->
            (-64 until 320 step 4).map { y -> generator.pointAreaAt(16 + x + 3, y + 3, 32 + 2) }
        }
        assertContentEquals(expected, again)
        generator.close()
    }
}
