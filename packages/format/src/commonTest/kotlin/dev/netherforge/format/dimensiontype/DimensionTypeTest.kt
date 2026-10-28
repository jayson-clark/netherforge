package dev.netherforge.format.dimensiontype

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.Severity
import dev.netherforge.format.TestFiles
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.datapack.TextJson
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.terrain.WorldHeight
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** `dimension_types/<id>.json`: the model, its checks, the game's datapack format per version, the main world's override, and the heights of a world that names one. */
class DimensionTypeTest {
    private val text = TextJson { _, _ -> buildJsonObject {} }

    /** 1.21.11's data pack format, 26.1's (clocks) and 26.3's (straw beds, `destroy_on_use`). */
    private val formats = listOf(listOf(94, 1), listOf(101, 1), listOf(121, 0))

    private fun load(vararg files: Pair<String, String?>, manifest: String = ""): ProjectSnapshot =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest(manifest)) + files))

    private fun parse(json: String): DimensionTypeFile =
        (DimensionTypeKind.parse(json, "dimension_types/x.json") as CanonicalJson.Parsed.Ok).value

    private fun json(pack: Map<String, DatapackEntry>, path: String): JsonObject =
        CanonicalJson.json.parseToJsonElement((pack.getValue(path) as DatapackEntry.Text).text).jsonObject

    private val deep = """
        { "minY": -128, "height": 512, "logicalHeight": 256, "skyLight": false, "ceiling": true, "ambientLight": 0.1,
          "fixedTime": true, "sky": "none", "colors": { "sky": "#102030", "fog": "#405060", "clouds": "#C0FFFFFF" },
          "cloudHeight": 300, "bedWorks": false, "respawnAnchorWorks": true, "piglinSafe": true, "raids": false,
          "ultrawarm": true, "monsterSpawnLight": { "min": 7, "max": 7 }, "monsterSpawnBlockLight": 15,
          "infiniburn": "#minecraft:infiniburn_nether", "coordinateScale": 8 }
    """.trimIndent()

    @Test
    fun aDimensionReadsAndWritesBackCanonically() {
        val written = DimensionTypeKind.write(parse(deep))
        assertTrue("\"\$schema\": \"../.netherforge/schema/dimension_type.schema.json\"" in written, written)
        assertEquals(written, DimensionTypeKind.write(parse(written)))
        assertEquals(emptyList(), load("dimension_types/deep.json" to deep).problems)
        assertEquals(WorldHeight(-128, 384), parse(deep).worldHeight)
        // Every field is optional: an empty file is the overworld.
        val empty = parse("{}")
        assertEquals(WorldHeight.OVERWORLD, empty.worldHeight)
        assertEquals(384, empty.logicalHeightOrDefault)
    }

    @Test
    fun theGamesRulesForHeightsHold() {
        fun codes(json: String) =
            ProblemSink("dimension_types/x.json").also { DimensionTypeValidator.validate(parse(json), it, null) }.problems
                .map { it.code to it.path }
        assertEquals(listOf("dimension-type.height" to "$.minY"), codes("""{ "minY": -8 }"""))
        assertEquals(listOf("dimension-type.height" to "$.height"), codes("""{ "height": 8 }"""))
        assertEquals(listOf("dimension-type.height" to "$.minY"), codes("""{ "minY": -2048 }"""))
        // The top is at most 2032, whichever end says so.
        assertEquals(listOf("dimension-type.height" to "$.height"), codes("""{ "minY": 0, "height": 2048 }"""))
        assertEquals(listOf("dimension-type.height" to "$.minY"), codes("""{ "minY": 1696 }"""))
        assertEquals(emptyList(), codes("""{ "minY": -2032, "height": 4064 }"""))
        assertEquals(listOf("dimension-type.logical-height" to "$.logicalHeight"), codes("""{ "height": 64, "logicalHeight": 80 }"""))
        // A logical height up to the default height is fine.
        assertEquals(emptyList(), codes("""{ "logicalHeight": 384 }"""))
    }

    @Test
    fun withGameDataTheInfiniburnTagIsTheGames() {
        val game = GameDataBundle(
            minecraft = "26.3",
            registries = mapOf(RegistryKey.BLOCK.id to listOf("minecraft:netherrack")),
            tags = mapOf(RegistryKey.BLOCK.id to mapOf("minecraft:infiniburn_nether" to listOf("minecraft:netherrack")))
        )
        val sink = ProblemSink("dimension_types/deep.json")
        DimensionTypeValidator.validate(parse(deep), sink, game)
        assertEquals(emptyList(), sink.problems)
        val missing = ProblemSink("dimension_types/x.json")
        DimensionTypeValidator.validate(parse("""{ "infiniburn": "#minecraft:infiniburn_moon" }"""), missing, game)
        assertEquals(listOf("dimension-type.infiniburn-tag"), missing.problems.map { it.code })
        assertEquals(Severity.WARNING, missing.problems.single().severity)
    }

    /**
     * The datapack for the data pack formats the game's dimension type JSON moved at: `testdata/datapack/dimensions.json`
     * (`UPDATE_GOLDEN=1` rewrites it).
     */
    @Test
    fun theDatapackIsTheGamesFormatForTheServersVersion() {
        val snapshot = load("dimension_types/deep.json" to deep, "dimension_types/plain.json" to "{}")
        val actual = CanonicalJson.print(
            buildJsonObject {
                for (format in formats) {
                    put(
                        format.joinToString("."),
                        buildJsonObject {
                            for ((path, entry) in StartupDatapack.build(snapshot, format, text)) {
                                if ("/dimension_type/" in path) put(path, JsonPrimitive((entry as DatapackEntry.Text).text))
                            }
                        }
                    )
                }
            }
        ) + "\n"
        val golden = "packages/format/testdata/datapack/dimensions.json"
        if (actual != TestFiles.read(golden)) {
            if (!TestFiles.updateGolden) fail("$golden differs. Run with UPDATE_GOLDEN=1 to accept.\n$actual")
            TestFiles.write(golden, actual)
        }
        val (old, clocks, straw) = formats.map { json(StartupDatapack.build(snapshot, it, text), "data/test/dimension_type/deep.json") }
        assertEquals(-128, old.getValue("min_y").jsonPrimitive.int)
        assertEquals(512, old.getValue("height").jsonPrimitive.int)
        // 26.1 requires has_ender_dragon_fight and gives a type a clock; 1.21.11 has neither.
        assertFalse("has_ender_dragon_fight" in old || "default_clock" in old)
        assertTrue("has_ender_dragon_fight" in clocks && "default_clock" in clocks)
        // A bed that doesn't work blows up: `explodes` until 26.3, `destroy_on_use` from it, with the straw bed's rule.
        val bed = { it: JsonObject -> it.getValue("attributes").jsonObject.getValue("minecraft:gameplay/bed_rule").jsonObject }
        assertTrue("explodes" in bed(old) && "explodes" in bed(clocks) && "destroy_on_use" in bed(straw))
        assertFalse("minecraft:gameplay/straw_bed_rule" in clocks.getValue("attributes").jsonObject)
        assertTrue("minecraft:gameplay/straw_bed_rule" in straw.getValue("attributes").jsonObject)
        // The same light at both ends is the game's plain number.
        assertEquals(7, straw.getValue("monster_spawn_light_level").jsonPrimitive.int)
        assertEquals("#c0ffffff", straw.getValue("attributes").jsonObject.getValue("minecraft:visual/cloud_color").jsonPrimitive.content)
        // Without a main world named, the overworld is the game's own.
        assertTrue(StartupDatapack.build(snapshot, formats.last(), text).keys.none { it.startsWith("data/minecraft/") })
    }

    @Test
    fun theMainWorldsDimensionReplacesTheOverworldType() {
        val snapshot = load(
            "dimension_types/deep.json" to deep,
            manifest = """, "worlds": { "world": { "dimensionType": "deep" }, "realm": { "dimensionType": "deep" } }"""
        )
        assertEquals(emptyList(), snapshot.problems)
        val format = formats.last()
        val pack = StartupDatapack.build(snapshot, format, text, mainWorld = "world")
        assertEquals(json(pack, "data/test/dimension_type/deep.json"), json(pack, "data/minecraft/dimension_type/overworld.json"))
        // Only the main world's: another world's dimension is made with it by the plugin, and a server whose main world
        // the manifest doesn't name keeps the game's overworld.
        assertFalse("data/minecraft/dimension_type/overworld.json" in StartupDatapack.build(snapshot, format, text, mainWorld = "lobby"))
        assertFalse("data/minecraft/dimension_type/overworld.json" in StartupDatapack.build(snapshot, format, text))
        // A dimension that has errors isn't in the pack, and so isn't the main world's either.
        val broken =
            load("dimension_types/deep.json" to """{ "minY": -7 }""", manifest = """, "worlds": { "world": { "dimensionType": "deep" } }""")
        assertEquals(emptyMap(), StartupDatapack.build(broken, format, text, mainWorld = "world"))
    }

    @Test
    fun aTerrainIsHeldToTheHeightsOfTheWorldThatNamesItsDimension() {
        val hills = """{ "terrain": { "base": 40 }, "ores": { "deep": { "block": "minecraft:iron_ore", "minY": -100, "maxY": 0 } },
            "caves": { "low": { "minY": -120, "maxY": 30 } } }"""
        val deepOk = load(
            "dimension_types/deep.json" to """{ "minY": -128, "height": 256 }""",
            "terrain/hills.json" to hills,
            manifest = """, "worlds": { "below": { "terrain": "hills", "dimensionType": "deep" } }"""
        )
        assertEquals(emptyList(), deepOk.problems)
        val shallow = load(
            "dimension_types/flat.json" to """{ "minY": 0, "height": 64 }""",
            "terrain/hills.json" to hills,
            manifest = """, "worlds": { "below": { "terrain": "hills", "dimensionType": "flat" } }"""
        )
        assertEquals(
            setOf("$.ores.deep.minY", "$.caves.low.minY"),
            shallow.problems.map {
                assertEquals("project.world-height", it.code)
                assertEquals("netherforge.json", it.file)
                assertEquals("$.worlds.below.terrain", it.path)
                it.related.single().also { related -> assertEquals("terrain/hills.json", related.file) }.path
            }.toSet()
        )
        assertTrue("from y 0 to 63" in shallow.problems.first().message, shallow.problems.first().message)
        // The base itself (40) is inside, and a world naming only one of the two isn't checked.
        val alone = load(
            "dimension_types/flat.json" to """{ "minY": 0, "height": 64 }""",
            "terrain/hills.json" to hills,
            manifest = """, "worlds": { "a": { "terrain": "hills" }, "b": { "dimensionType": "flat" } }"""
        )
        assertEquals(emptyList(), alone.problems)
    }

    @Test
    fun aDimensionNamedByAWorldIsAReferenceRenamesFollow() {
        val manifest = """, "worlds": { "below": { "dimensionType": "deep" } }"""
        assertEquals(emptyList(), load("dimension_types/deep.json" to "{}", manifest = manifest).problems)
        val missing = load(manifest = manifest).problems.single()
        assertEquals("reference.dimension-type", missing.code)
        assertEquals("$.worlds.below.dimensionType", missing.path)
        val renamed = ReferenceIndex.rename(
            ManifestKind,
            "netherforge.json",
            testManifest(manifest),
            "test",
            RefTarget.Resource("dimension_type", "deep"),
            "deeper"
        )
        assertTrue(renamed != null && "\"dimensionType\": \"deeper\"" in renamed, renamed)
        assertNull(
            DimensionTypeKind.template("x")["dimension_types/x.json"]?.let { t ->
                load("dimension_types/x.json" to t).problems.firstOrNull()
            }
        )
    }
}
