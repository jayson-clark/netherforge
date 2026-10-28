package dev.netherforge.format.terrain

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.TestFiles
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a generator file can do past one terrain: terrain per biome area blended across borders, jittered borders, decorations, area filters, custom blocks everywhere. */
class TerrainAreasTest {
    private val path = "terrain/test.json"

    private fun file(json: String): TerrainFile {
        val parsed = TerrainKind.parse(json, path)
        check(parsed is CanonicalJson.Parsed.Ok) { "didn't parse: $parsed" }
        return parsed.value
    }

    private fun compile(json: String): CompiledTerrain = TerrainCompiler.compile(file(json))

    private fun codes(json: String, height: WorldHeight = WorldHeight.LIMITS): List<Pair<String, String?>> {
        val sink = ProblemSink(path)
        TerrainValidator.validate(file(json), sink, null, height)
        return sink.problems.map { it.code.orEmpty() to it.path }
    }

    private fun label(w: CompiledTerrain, index: Int) = w.palette[index].label

    /** A tree: a trunk of four logs with a 3x3 crown of leaves round its top two, and air round the trunk below. */
    private val tree = StructureTemplate(
        3,
        5,
        3,
        listOf("minecraft:oak_log[axis=y]", "minecraft:oak_leaves[distance=1,persistent=false,waterlogged=false]", "minecraft:air"),
        buildList {
            for (y in 0..3) addAll(listOf(1, y, 1, 0))
            for (y in 3..4) for (x in 0..2) for (z in 0..2) if (!(x == 1 && z == 1 && y == 3)) addAll(listOf(x, y, z, 1))
            for (y in 0..2) addAll(listOf(0, y, 0, 2))
        }.toIntArray()
    )

    /** Two areas: low plains and high, rough hills, split by temperature. */
    private val twoAreas = """{
        "terrain": { "base": 64, "seaLevel": 40, "noises": { "hills": { "noise": { "frequency": 0.01 }, "amplitude": 6 } } },
        "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
        "climate": { "temperature": { "frequency": 0.003 } },
        "biomes": {
            "low": { "biome": "minecraft:plains", "temperature": { "max": 0 }, "terrain": { "base": 50, "scale": 0.5 } },
            "high": { "biome": "minecraft:jagged_peaks", "temperature": { "min": 0 },
                "terrain": { "base": 110, "scale": 2, "noises": { "peaks": { "noise": { "frequency": 0.05 }, "amplitude": 8 } } } }
        }
    }"""

    // ---- validation ----------------------------------------------------------------------------------

    @Test
    fun theNewPartsAreValidated() {
        val found = codes(
            """{ "terrain": { "blend": 100 }, "climate": { "jitter": { "amplitude": -1, "noise": { "octaves": 0 } } },
                "stone": {}, "floor": { "block": "minecraft:bedrock", "customBlock": "ruby" },
                "layers": [{ "thickness": 2 }, { "block": "minecraft:dirt", "customBlock": "ruby" }],
                "caves": { "c": { "biomes": ["nowhere"] } },
                "ores": { "o": { "block": "minecraft:iron_ore", "biomes": ["a", "nowhere"] } },
                "decorations": {
                    "none": {},
                    "two": { "block": "minecraft:poppy", "structure": "tree" },
                    "cave_tree": { "structure": "tree", "placement": "caveFloor" },
                    "turned": { "block": "minecraft:poppy", "rotate": false },
                    "numbers": { "block": "minecraft:poppy", "count": 999, "chance": 2, "threshold": 0.5, "minY": 10, "maxY": 0, "on": ["Not An Id"] },
                    "edge": { "block": "minecraft:poppy", "noise": {}, "threshold": 1 }
                },
                "biomes": { "a": { "biome": "minecraft:plains", "terrain": { "base": 99999, "scale": 20, "noises": { "Bad": {} } } } } }"""
        )
        assertEquals(
            listOf(
                "terrain.border" to "$.terrain.blend",
                "terrain.one-block" to "$.layers[0]",
                "terrain.one-block" to "$.layers[1]",
                "terrain.one-block" to "$.stone",
                "terrain.one-block" to "$.floor",
                "terrain.area" to "$.caves.c.biomes[0]",
                "terrain.area" to "$.ores.o.biomes[1]",
                "terrain.decoration" to "$.decorations.cave_tree.placement",
                "terrain.one-block" to "$.decorations.none",
                "terrain.decoration" to "$.decorations.edge.threshold",
                "terrain.decoration" to "$.decorations.numbers.count",
                "terrain.decoration" to "$.decorations.numbers.chance",
                "terrain.decoration" to "$.decorations.numbers.threshold",
                "terrain.decoration" to "$.decorations.numbers.minY",
                "terrain.block" to "$.decorations.numbers.on[0]",
                "terrain.decoration" to "$.decorations.turned.rotate",
                "terrain.one-block" to "$.decorations.two",
                "terrain.noise" to "$.climate.jitter.noise.octaves",
                "terrain.border" to "$.climate.jitter.amplitude",
                "terrain.height" to "$.biomes.a.terrain.base",
                "terrain.height" to "$.biomes.a.terrain.scale",
                "terrain.name" to "$.biomes.a.terrain.noises.Bad"
            ).sortedBy { it.second },
            found.map { it.first to it.second!! }.sortedBy { it.second }
        )
        assertEquals(emptyList(), codes(twoAreas))
    }

    @Test
    fun heightsAreHeldToTheWorldTheyreCheckedFor() {
        val text = """{ "terrain": { "base": 400 }, "ores": { "o": { "block": "minecraft:iron_ore", "minY": -200 } } }"""
        // Any world can be up to the game's limit, so a file alone is checked against that.
        assertEquals(emptyList(), codes(text))
        // A world that says its own (an overworld, a taller dimension) holds the file to it.
        assertEquals(listOf("terrain.height", "terrain.ore"), codes(text, WorldHeight.OVERWORLD).map { it.first })
        assertEquals(emptyList(), codes(text, WorldHeight(-256, 512)))
    }

    @Test
    fun aCustomBlockAnywhereIsHeldToACube() {
        val files = mapOf(
            "netherforge.json" to """{ "formatVersion": 1, "name": "T", "namespace": "t", "version": "1.0.0", "minecraft": "26.3" }""",
            "blocks/lamp/block.json" to """{ "centity": "lamp" }""",
            "blocks/ruby/block.json" to "{}",
            "terrain/hills.json" to """{ "stone": { "customBlock": "ruby" }, "floor": { "customBlock": "lamp" },
                "layers": [{ "customBlock": "ruby" }], "decorations": { "d": { "customBlock": "lamp" }, "t": { "structure": "gone" } },
                "biomes": { "a": { "biome": "minecraft:plains", "underwater": [{ "customBlock": "lamp" }] } } }"""
        )
        val problems = Projects.load(MapProjectSource(files)).problems.filter { it.file == "terrain/hills.json" }
        assertEquals(
            listOf(
                "terrain.custom-block" to "$.floor.customBlock",
                "terrain.custom-block" to "$.decorations.d.customBlock",
                "terrain.custom-block" to "$.biomes.a.underwater[0].customBlock",
                "reference.structure" to "$.decorations.t.structure"
            ).sortedBy { it.second },
            problems.map { it.code to it.path!! }.sortedBy { it.second }
        )
        assertTrue(problems.first { it.code == "terrain.custom-block" }.related.single().file == BlockKind.pathOf("lamp"))
    }

    @Test
    fun customBlocksBuildTheGround() {
        val w = compile(
            """{ "terrain": { "base": 10, "seaLevel": -60 }, "stone": { "customBlock": "slate" }, "floor": { "customBlock": "core", "thickness": 1 },
                "layers": [{ "customBlock": "moss" }, { "block": "minecraft:dirt" }] }"""
        )
        assertEquals(setOf("slate", "core", "moss"), w.customBlocks.toSet())
        val b = w.bind(1L, -64, 320).generate(0, 0)
        assertEquals(TerrainBlock.Custom("core"), w.palette[b[0, -64, 0]])
        assertEquals(TerrainBlock.Custom("slate"), w.palette[b[0, 0, 0]])
        assertEquals("minecraft:dirt", label(w, b[0, 9, 0]))
        assertEquals(TerrainBlock.Custom("moss"), w.palette[b[0, 10, 0]])
    }

    // ---- terrain per area, blended -------------------------------------------------------------------

    /** The biggest step between two neighbouring columns along a line crossing the world. */
    /** The biggest step between neighbouring columns along z = 77, through one sampler (so its grid is read once). */
    private fun steepest(g: TerrainGenerator): Int {
        val sampler = g.sampler()
        var steepest = 0
        var last = sampler.surfaceAt(-3000, 77)
        for (x in -2999..3000) {
            val here = sampler.surfaceAt(x, 77)
            steepest = maxOf(steepest, abs(here - last))
            last = here
        }
        return steepest
    }

    @Test
    fun anAreasTerrainIsItsOwnAwayFromItsBorderAndBlendedAcrossIt() {
        val blended = compile(twoAreas).bind(5L, -64, 320)
        val cliffs = compile(twoAreas.replace("\"seaLevel\": 40", "\"seaLevel\": 40, \"blend\": 0")).bind(5L, -64, 320)
        val low = cliffs.terrain.areas.indexOfFirst { it.name == "low" }
        val high = cliffs.terrain.areas.indexOfFirst { it.name == "high" }
        // Without blending a column is its own area's height: the low area's is 50 +- 3, the high's 110 +- 20.
        var lows = 0
        var highs = 0
        val cliffSampler = cliffs.sampler()
        for (x in -3000..3000 step 7) {
            val top = cliffSampler.surfaceAt(x, 77)
            when (cliffs.areaAt(x, 77)) {
                low -> assertTrue(top in 47..53, "a low column at $x is $top").also { lows++ }
                high -> assertTrue(top in 82..138, "a high column at $x is $top").also { highs++ }
            }
        }
        assertTrue(lows > 50 && highs > 50, "both areas are crossed ($lows, $highs)")
        // Cliffs where they meet; with blending the ground climbs between them.
        val cliff = steepest(cliffs)
        val slope = steepest(blended)
        assertTrue(cliff > 25, "a cliff: $cliff")
        assertTrue(slope < cliff / 2, "blended: $slope against $cliff")
        // Far from any border, blending changes nothing.
        var same = 0
        for (x in -3000..3000 step 13) {
            // Another area within 40 blocks on either diagonal, looked for every 8 (areas are hundreds of blocks across).
            val near = (-40..40 step 8).any {
                blended.areaAt(x + it, 77 + it) != blended.areaAt(x, 77) ||
                    blended.areaAt(x + it, 77 - it) != blended.areaAt(x, 77)
            }
            if (!near) {
                assertEquals(cliffs.surfaceAt(x, 77), blended.surfaceAt(x, 77), "away from borders at $x")
                same++
            }
        }
        assertTrue(same > 50, "columns away from borders: $same")
    }

    @Test
    fun blendedHeightsDontDependOnWhatWasAskedBefore() {
        val w = compile(twoAreas)
        val a = w.bind(9L, -64, 320)
        val sampler = a.sampler()
        val asked = (-500..500 step 3).map { sampler.surfaceAt(it, it / 2) }
        // A fresh generator, asked one column at a time in another order, answers the same.
        val b = w.bind(9L, -64, 320)
        assertEquals(asked.reversed(), (-500..500 step 3).reversed().map { b.surfaceAt(it, it / 2) })
        // And a chunk's surface is its columns'.
        val chunk = b.generate(3, -2)
        for (lx in 0 until 16 step 5) {
            val x = 48 + lx
            val top = (319 downTo -64).first { chunk[lx, it, 7] != 0 }
            assertEquals(a.surfaceAt(x, -32 + 7), top)
        }
    }

    @Test
    fun jitterMovesBordersButNotTheClimate() {
        val straight = compile(twoAreas.replace("\"climate\": {", "\"climate\": { \"jitter\": { \"amplitude\": 0 },")).bind(3L, -64, 320)
        val jittered = compile(twoAreas).bind(3L, -64, 320)
        val wild = compile(twoAreas.replace("\"climate\": {", "\"climate\": { \"jitter\": { \"amplitude\": 60 },")).bind(3L, -64, 320)
        var moved = 0
        var wildMoved = 0
        var total = 0
        for (x in -2000..2000 step 10) {
            for (z in -2000..2000 step 50) {
                total++
                if (straight.areaAt(x, z) != jittered.areaAt(x, z)) moved++
                if (straight.areaAt(x, z) != wild.areaAt(x, z)) wildMoved++
            }
        }
        assertTrue(moved > 0, "the default jitter moves some borders")
        assertTrue(moved < total / 5, "but most places keep their area ($moved of $total)")
        assertTrue(wildMoved > moved, "more jitter moves more ($wildMoved, $moved)")
    }

    // ---- filters ---------------------------------------------------------------------------------------

    /** "all" is everywhere; "never" has a box no climate falls in, so no column is in it. */
    private fun filtered(part: String) = """{ "terrain": { "base": 70, "seaLevel": 0 }, "layers": [{ "block": "minecraft:grass_block" }],
        $part,
        "biomes": { "all": { "biome": "minecraft:plains" }, "never": { "biome": "minecraft:desert", "temperature": { "min": 1, "max": 1 } } } }"""

    private fun count(json: String, block: String): Int {
        val w = compile(json)
        val index = w.palette.indexOf(TerrainBlock.Vanilla(block))
        val g = w.bind(4L, -64, 320)
        return listOf(0 to 0, 1 to 0, 0 to 1).sumOf { (cx, cz) -> g.generate(cx, cz).blocks.count { it == index } }
    }

    @Test
    fun oresCavesAndDecorationsKeepToTheAreasTheyName() {
        val ore = """"ores": { "o": { "block": "minecraft:gold_ore", "veins": 30, "biomes": ["AREA"] } }"""
        assertTrue(count(filtered(ore.replace("AREA", "all")), "minecraft:gold_ore") > 50)
        assertEquals(0, count(filtered(ore.replace("AREA", "never")), "minecraft:gold_ore"))
        val flowers = """"decorations": { "f": { "block": "minecraft:poppy", "count": 20, "biomes": ["AREA"] } }"""
        assertTrue(count(filtered(flowers.replace("AREA", "all")), "minecraft:poppy") > 20)
        assertEquals(0, count(filtered(flowers.replace("AREA", "never")), "minecraft:poppy"))
        val caves = """"caves": { "c": { "threshold": 0, "biomes": ["AREA"] } }"""
        val carved = count(filtered(caves.replace("AREA", "all")), "minecraft:air")
        val solid = count(filtered(caves.replace("AREA", "never")), "minecraft:air")
        assertTrue(carved > solid + 1000, "caves are carved only in their areas ($carved, $solid)")
    }

    // ---- decorations -----------------------------------------------------------------------------------

    private val decorated = """{
        "terrain": { "base": 62, "seaLevel": 62, "noises": { "hills": { "noise": { "frequency": 0.02 }, "amplitude": 10 } } },
        "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
        "underwater": [{ "block": "minecraft:sand", "thickness": 2 }],
        "caves": { "caverns": { "threshold": 0.2 } },
        "decorations": {
            "flowers": { "block": "minecraft:poppy", "count": 24, "on": ["minecraft:grass_block"] },
            "kelp": { "block": "minecraft:sea_pickle", "placement": "underwater", "count": 24 },
            "gems": { "customBlock": "gem", "placement": "underground", "count": 40, "maxY": 40 },
            "moss": { "block": "minecraft:moss_carpet", "placement": "caveFloor", "count": 60, "maxY": 50 },
            "drips": { "block": "minecraft:pointed_dripstone", "placement": "caveCeiling", "count": 60, "maxY": 50 },
            "patchy": { "block": "minecraft:dandelion", "count": 40, "noise": { "frequency": 0.05 }, "threshold": 0.3 }
        }
    }"""

    @Test
    fun eachPlacementPutsItsBlockWhereItSays() {
        val w = compile(decorated)
        val g = w.bind(11L, -64, 320)
        fun index(label: String) = w.palette.indexOfFirst { it.label == label }
        val grass = index("minecraft:grass_block")
        val water = index("minecraft:water")
        val stone = index("minecraft:stone")
        val found = mutableMapOf<String, Int>()
        for (cx in -2..2) {
            for (cz in -2..2) {
                val b = g.generate(cx, cz)
                for (x in 0 until 16) {
                    for (z in 0 until 16) {
                        for (y in -62 until 318) {
                            val here = w.palette[b[x, y, z]].label
                            val below = b[x, y - 1, z]
                            val above = b[x, y + 1, z]
                            when (here) {
                                "minecraft:poppy" -> assertEquals(grass, below, "a poppy is on grass")
                                "minecraft:sea_pickle" -> assertTrue(
                                    y <= 62 && below != 0 && below != water,
                                    "a sea pickle is on the sea floor"
                                )
                                "gem" -> assertTrue(y <= 40, "gems are low")
                                "minecraft:moss_carpet" -> assertTrue(
                                    below != 0 && below != water && y < g.surfaceAt(cx * 16 + x, cz * 16 + z)
                                )
                                "minecraft:pointed_dripstone" -> assertTrue(
                                    above != 0 && above != water && y < g.surfaceAt(cx * 16 + x, cz * 16 + z)
                                )
                                else -> continue
                            }
                            found[here] = (found[here] ?: 0) + 1
                        }
                    }
                }
            }
        }
        for (block in listOf("minecraft:poppy", "minecraft:sea_pickle", "gem", "minecraft:moss_carpet", "minecraft:pointed_dripstone")) {
            assertTrue((found[block] ?: 0) > 5, "$block is placed: $found")
        }
        assertTrue(stone > 0)
    }

    @Test
    fun aNoiseGathersADecorationIntoPatches() {
        val w = compile(decorated)
        val g = w.bind(11L, -64, 320)
        val dandelion = w.decorations.indexOfFirst { it.name == "patchy" }
        val poppy = w.decorations.indexOfFirst { it.name == "flowers" }
        val sampler = g.sampler()
        // With a threshold of 0.3 most chunks get none; the ones that do get several.
        val perChunk = (-10..10).flatMap { cx -> (-10..10).map { cz -> g.decorationSites(dandelion, cx, cz, sampler).size } }
        val poppies = (-10..10).flatMap { cx -> (-10..10).map { cz -> g.decorationSites(poppy, cx, cz, sampler).size } }
        assertTrue(perChunk.count { it == 0 } > perChunk.size / 3, "most chunks are bare: $perChunk")
        assertTrue(perChunk.any { it >= 5 }, "and some have a patch")
        assertTrue(poppies.count { it == 0 } < poppies.size / 3)
    }

    private val forest = """{
        "terrain": { "base": 70, "seaLevel": 0, "noises": { "hills": { "noise": { "frequency": 0.01 }, "amplitude": 6 } } },
        "layers": [{ "block": "minecraft:grass_block" }, { "block": "minecraft:dirt", "thickness": 3 }],
        "decorations": { "trees": { "structure": "tree", "count": 8 } }
    }"""

    @Test
    fun aStructureDecorationIsPlacedWholeAcrossChunkBorders() {
        // A low world (0 to 128): a generator takes any world's heights, and the test stays quick in JS.
        val w = compile(forest).withStructures(mapOf("tree" to tree))
        val g = w.bind(21L, 0, 128)
        val log = w.palette.indexOf(TerrainBlock.Vanilla("minecraft:oak_log[axis=y]"))
        assertTrue(log > 0, "the template's states join the palette")
        val chunks = HashMap<Pair<Int, Int>, ChunkBuffer>()
        fun at(x: Int, y: Int, z: Int): Int =
            chunks.getOrPut(x.floorDiv(16) to z.floorDiv(16)) { g.generate(x.floorDiv(16), z.floorDiv(16)) }[
                x and
                    15, y, z and 15
            ]
        val sampler = g.sampler()
        val sites = (-1..1).flatMap { cx -> (-1..1).flatMap { cz -> g.decorationSites(0, cx, cz, sampler) } }
        // Trees that overlap overwrite each other (the later in the order wins): look at the ones standing alone.
        val alone = sites.filter { s -> sites.none { it !== s && abs(it.x - s.x) <= 3 && abs(it.z - s.z) <= 3 } }
        var trees = 0
        var crossing = 0
        for (site in alone) {
            trees++
            // The trunk stands on the ground, four logs high, whichever chunk is generated first.
            for (y in 0..3) assertEquals(log, at(site.x, site.y + y, site.z), "trunk of the tree at ${site.x},${site.y},${site.z}")
            assertEquals("minecraft:grass_block", label(w, at(site.x, site.y - 1, site.z)))
            // Its crown is all leaves, even where it reaches into the next chunk.
            for (dx in -1..1) {
                for (dz in -1..1) {
                    val here = label(w, at(site.x + dx, site.y + 4, site.z + dz))
                    assertTrue(here.startsWith("minecraft:oak_leaves"), "crown: $here")
                    if ((site.x + dx).floorDiv(16) != site.x.floorDiv(16) || (site.z + dz).floorDiv(16) != site.z.floorDiv(16)) crossing++
                }
            }
        }
        // A chunk is the same made first or after its neighbours.
        val fresh = w.bind(21L, 0, 128).generate(0, 0)
        assertEquals(chunks.getValue(0 to 0).blocks.toList(), fresh.blocks.toList())
        assertTrue(trees > 8, "trees: $trees")
        assertTrue(crossing > 0, "some crowns cross a chunk border")
    }

    @Test
    fun aStructuresAirIsntPlacedAndOneNotLinkedPlacesNothing() {
        // The template's air isn't placed: buried in the ground, the corner it holds air in stays stone.
        val buried = compile(forest.replace("\"count\": 8", "\"count\": 8, \"placement\": \"underground\", \"rotate\": false"))
            .withStructures(mapOf("tree" to tree))
        val bg = buried.bind(21L, 0, 128)
        val stone = buried.palette.indexOf(TerrainBlock.Vanilla("minecraft:stone"))
        val chunk = bg.generate(0, 0)
        var corners = 0
        for (site in bg.decorationSites(0, 0, 0, bg.sampler())) {
            val (lx, lz) = (site.x - 1) to (site.z - 1)
            if (lx !in 0..15 || lz !in 0..15 || site.y + 2 > bg.surfaceAt(site.x - 1, site.z - 1) - 4) continue
            for (y in 0..2) {
                assertEquals(
                    stone,
                    chunk[lx, site.y + y, lz],
                    "the air of the template at ${site.x - 1},${site.y + y},${site.z - 1}"
                )
            }
            assertEquals(buried.palette.indexOf(TerrainBlock.Vanilla("minecraft:oak_log[axis=y]")), chunk[lx + 1, site.y, lz + 1])
            corners++
        }
        assertTrue(corners > 0)
        // Without the structure linked in, the decoration places nothing.
        val unlinked = compile(forest)
        val bare = unlinked.bind(21L, 0, 128).generate(0, 0)
        assertFalse(bare.blocks.any { unlinked.palette[it].label.startsWith("minecraft:oak") })
    }

    @Test
    fun aTurnedStructureTurnsItsBlocks() {
        assertEquals("minecraft:oak_stairs[facing=east,half=bottom]", StateTurns.turn("minecraft:oak_stairs[facing=north,half=bottom]", 1))
        assertEquals("minecraft:oak_log[axis=x]", StateTurns.turn("minecraft:oak_log[axis=z]", 3))
        assertEquals("minecraft:oak_log[axis=y]", StateTurns.turn("minecraft:oak_log[axis=y]", 1))
        assertEquals("minecraft:oak_sign[rotation=10]", StateTurns.turn("minecraft:oak_sign[rotation=2]", 2))
        assertEquals(
            "minecraft:oak_fence[east=true,north=true,south=false,west=false]",
            StateTurns.turn("minecraft:oak_fence[east=false,north=true,south=false,west=true]", 1)
        )
    }

    // ---- the preview -----------------------------------------------------------------------------------

    @Test
    fun theMapMarksWhereSurfaceDecorationsStartWhenItsClose() {
        val w = compile(forest).withStructures(mapOf("tree" to tree))
        val g = w.bind(21L, -64, 320)
        val close = TerrainPreview.map(g, -48, -48, 48, 2)
        assertTrue(close.decorationsShown)
        assertEquals(listOf("trees"), close.decorationNames)
        val sites = (-3..2).flatMap { cx -> (-3..2).flatMap { cz -> g.decorationSites(0, cx, cz, g.sampler()) } }
            .filter { it.x in -48 until 48 && it.z in -48 until 48 }
        assertEquals(sites.size, close.decorations.size / 2)
        assertTrue(close.decorations.chunked(2).all { it[1] == 0 })
        val far = TerrainPreview.map(g, -50_000, -50_000, 256, 400)
        assertFalse(far.decorationsShown)
        assertEquals(emptyList(), far.decorations)
    }

    // ---- the same on every platform ----------------------------------------------------------------------

    private val everything = """{
        "terrain": { "base": 64, "seaLevel": 60, "blend": 24, "noises": { "hills": { "noise": { "frequency": 0.008, "octaves": 3 }, "amplitude": 14 } } },
        "layers": [{ "block": "minecraft:grass_block" }, { "customBlock": "loam", "thickness": 2 }],
        "underwater": [{ "block": "minecraft:sand", "thickness": 2 }],
        "stone": { "block": "minecraft:deepslate" },
        "floor": { "customBlock": "core", "thickness": 2 },
        "caves": { "caverns": { "threshold": 0.3, "biomes": ["peaks"] } },
        "ores": { "gold": { "block": "minecraft:gold_ore", "veins": 14, "replace": ["minecraft:deepslate"], "biomes": ["meadow"] } },
        "decorations": {
            "trees": { "structure": "tree", "count": 3, "biomes": ["meadow"] },
            "flowers": { "block": "minecraft:poppy", "count": 10, "noise": { "frequency": 0.04 }, "threshold": -0.2, "on": ["minecraft:grass_block"] },
            "drips": { "block": "minecraft:pointed_dripstone", "placement": "caveCeiling", "count": 30 }
        },
        "climate": { "temperature": { "frequency": 0.006 }, "jitter": { "amplitude": 24 } },
        "biomes": {
            "meadow": { "biome": "minecraft:meadow", "temperature": { "max": 0.1 }, "terrain": { "base": 66, "scale": 0.6 } },
            "peaks": { "biome": "minecraft:jagged_peaks", "temperature": { "min": 0.1 },
                "terrain": { "base": 100, "scale": 1.5, "noises": { "crags": { "noise": { "frequency": 0.03, "fractal": "ridged", "octaves": 2 }, "amplitude": 12 } } } }
        }
    }"""

    private fun summary(): List<String> {
        val w = compile(everything).withStructures(mapOf("tree" to tree))
        val lines = mutableListOf("palette ${w.palette.joinToString(",") { it.label }}")
        for (seed in listOf(7L, -123456789012345L)) {
            val g = w.bind(seed, -64, 320)
            lines += "seed $seed heights ${(0 until 8).joinToString(",") { g.surfaceAt(it * 131 - 500, it * -77 + 60).toString() }}"
            lines += "seed $seed areas ${(0 until 8).joinToString(",") { g.areaAt(it * 257 - 900, it * 191 - 300).toString() }}"
            for ((cx, cz) in listOf(0 to 0, -5 to 3, 12 to -9)) {
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
        val golden = "packages/format/testdata/terrain/areas.txt"
        if (TestFiles.updateGolden) {
            TestFiles.write(golden, actual.joinToString("\n") + "\n")
            return
        }
        assertEquals(TestFiles.read(golden)!!.lines().filter { it.isNotBlank() }, actual)
    }
}
