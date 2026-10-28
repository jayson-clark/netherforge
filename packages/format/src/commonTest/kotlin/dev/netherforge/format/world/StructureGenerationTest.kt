package dev.netherforge.format.world

import dev.netherforge.format.TestFiles
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.datapack.TextJson
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.PathRole
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** `structures/<id>.json`: where a structure generates, and the datapack it becomes. */
class StructureGenerationTest {
    private val text = TextJson { _, _ -> buildJsonObject {} }

    private fun load(vararg files: Pair<String, String?>, game: GameDataBundle? = null): ProjectSnapshot =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files), game)

    private fun datapack(snapshot: ProjectSnapshot) = StartupDatapack.build(snapshot, listOf(94, 1), text)

    private fun json(pack: Map<String, DatapackEntry>, path: String): JsonObject =
        CanonicalJson.json.parseToJsonElement((pack.getValue(path) as DatapackEntry.Text).text).jsonObject

    private val ruins = """{ "biomes": ["minecraft:plains", "minecraft:desert"] }"""

    @Test
    fun theDocumentIsPartOfTheStructureAndAKindOfItsOwn() {
        fun at(path: String) = Kinds.classify(path)!!.let { listOf(it.kind, it.id, it.role, it.document) }
        assertEquals(listOf("structure", "ruins", PathRole.MAIN, null), at("structures/ruins.nbt"))
        assertEquals(listOf("structure", "ruins", PathRole.FILE, "structure_generation"), at("structures/ruins.json"))
        assertEquals(listOf("structure", "ruins", PathRole.FILE, "structure_generation"), at("acme:structures/ruins.json"))
        assertNull(Kinds.classify("structures/ruins.txt"))
        assertNull(Kinds.classify("structures/a/b.json"))
        assertNotNull(Kinds.document("structure_generation"))
    }

    @Test
    fun theStructureReadsItsGenerationAndWritesItBackCanonically() {
        val snapshot = load("structures/ruins.nbt" to null, "structures/ruins.json" to ruins)
        assertEquals(emptyList(), snapshot.problems)
        val generation = snapshot.models(StructureKind).getValue("ruins").generation
        assertEquals(listOf("minecraft:plains", "minecraft:desert"), generation?.biomes)
        val written = StructureGenerationKind.write(generation!!)
        assertTrue("\"\$schema\": \"../.netherforge/schema/structure_generation.schema.json\"" in written, written)
        assertEquals(written, StructureGenerationKind.write((StructureGenerationKind.parse(written, "x") as CanonicalJson.Parsed.Ok).value))
    }

    @Test
    fun aStructureWithoutOneOnlyScriptsPlace() {
        val snapshot = load("structures/arena.nbt" to null)
        assertNull(snapshot.models(StructureKind).getValue("arena").generation)
        assertEquals(emptyMap(), datapack(snapshot))
    }

    @Test
    fun aDocumentWithoutItsTemplateIsIgnoredWithAWarning() {
        val snapshot = load("structures/ruins.json" to ruins)
        assertEquals(emptyMap(), snapshot.models(StructureKind))
        val problem = snapshot.problems.single()
        assertEquals("project.missing-file", problem.code)
        assertEquals("structures/ruins.json", problem.file)
    }

    @Test
    fun aDocumentThatDoesntParseIsReportedAndTheStructureStillPlaces() {
        val snapshot = load("structures/ruins.nbt" to null, "structures/ruins.json" to """{ "biomes": [], "bogus": 1 }""")
        assertEquals("structures/ruins.json", snapshot.problems.single().file)
        assertNull(snapshot.models(StructureKind).getValue("ruins").generation)
    }

    @Test
    fun generationIsCheckedAtItsOwnFile() {
        val snapshot = load(
            "structures/a.nbt" to null,
            "structures/a.json" to """{ "biomes": ["#minecraft:is_forest", "minecraft:plains"] }""",
            "structures/b.nbt" to null,
            "structures/b.json" to """{ "biomes": ["minecraft:x"], "spacing": 4, "separation": 4, "depth": 21, "salt": -1 }""",
            "structures/c.nbt" to null,
            "structures/c.json" to
                """{ "biomes": ["minecraft:plains"], "pools": {
                    "start": { "elements": [{ "structure": "c" }] },
                    "empty": { "elements": [] },
                    "Bad Name": { "elements": [{ "structure": "c", "weight": 0 }] },
                    "ghost": { "elements": [{ "structure": "nope" }] } } }"""
        )
        // Each rule's problem is pinned by testdata/invalid/structure-generation; here, that they land at the
        // generation file, and what isn't one.
        val found = snapshot.problems.map { Triple(it.code, it.file, it.path) }
        assertTrue(found.isNotEmpty() && found.all { it.second.endsWith(".json") }, "$found")
        assertTrue(Triple("structure.biomes", "structures/b.json", "$.biomes[0]") !in found, "minecraft:x is a fine id: $found")
        // A structure with errors isn't running, so nothing of it is in the datapack.
        assertEquals(emptyMap(), datapack(snapshot))
    }

    @Test
    fun biomesAreCheckedAgainstTheGame() {
        val game = GameDataBundle(
            "26.3",
            registries = mapOf("minecraft:worldgen/biome" to listOf("minecraft:desert", "minecraft:plains")),
            tags = mapOf("minecraft:worldgen/biome" to mapOf("minecraft:is_forest" to listOf("minecraft:forest")))
        )
        val snapshot = load(
            "structures/a.nbt" to null,
            "structures/a.json" to """{ "biomes": ["minecraft:plains", "minecraft:jungle"] }""",
            "structures/b.nbt" to null,
            "structures/b.json" to """{ "biomes": ["#minecraft:is_forest"] }""",
            "structures/c.nbt" to null,
            "structures/c.json" to """{ "biomes": ["#minecraft:is_nothing"] }""",
            game = game
        )
        val found = snapshot.problems.map { it.code to "${it.file} ${it.path}" }
        assertEquals(
            listOf(
                "structure.unknown-biome" to "structures/a.json $.biomes[1]",
                "structure.unknown-biome" to "structures/c.json $.biomes[0]"
            ),
            found
        )
    }

    @Test
    fun oneStructureIsAJigsawStructureOfOnePiece() {
        val pack = datapack(load("structures/ruins.nbt" to null, "structures/ruins.json" to ruins))
        assertEquals(
            listOf(
                "data/test/structure/ruins.nbt",
                "data/test/worldgen/structure/ruins.json",
                "data/test/worldgen/structure_set/ruins.json",
                "data/test/worldgen/template_pool/ruins/start.json",
                "pack.mcmeta"
            ),
            pack.keys.toList()
        )
        assertEquals(DatapackEntry.Copy("structures/ruins.nbt"), pack["data/test/structure/ruins.nbt"])
        val structure = json(pack, "data/test/worldgen/structure/ruins.json")
        assertEquals("minecraft:jigsaw", structure.getValue("type").jsonPrimitive.content)
        assertEquals(
            listOf("minecraft:plains", "minecraft:desert"),
            structure.getValue("biomes").jsonArray.map {
                it.jsonPrimitive.content
            }
        )
        assertEquals("surface_structures", structure.getValue("step").jsonPrimitive.content)
        assertEquals("none", structure.getValue("terrain_adaptation").jsonPrimitive.content)
        assertEquals("test:ruins/start", structure.getValue("start_pool").jsonPrimitive.content)
        assertEquals("1", structure.getValue("size").jsonPrimitive.content)
        assertEquals("WORLD_SURFACE_WG", structure.getValue("project_start_to_heightmap").jsonPrimitive.content)
        assertEquals("80", structure.getValue("max_distance_from_center").jsonPrimitive.content)
        val placement = json(pack, "data/test/worldgen/structure_set/ruins.json").getValue("placement").jsonObject
        assertEquals("minecraft:random_spread", placement.getValue("type").jsonPrimitive.content)
        assertEquals("32", placement.getValue("spacing").jsonPrimitive.content)
        assertEquals("8", placement.getValue("separation").jsonPrimitive.content)
        assertEquals(StructureJson.saltOf(ResourceKey("test", "ruins")).toString(), placement.getValue("salt").jsonPrimitive.content)
        val element = json(pack, "data/test/worldgen/template_pool/ruins/start.json")
            .getValue("elements").jsonArray.single().jsonObject.getValue("element").jsonObject
        assertEquals("test:ruins", element.getValue("location").jsonPrimitive.content)
        assertEquals("rigid", element.getValue("projection").jsonPrimitive.content)
        // The game refuses a single piece without a processor list (found loading it on a real server).
        assertEquals("minecraft:empty", element.getValue("processors").jsonPrimitive.content)
    }

    @Test
    fun theSaltIsStableAndNonNegativeAndTheSameForTheSameName() {
        val a = StructureJson.saltOf(ResourceKey("basic", "ruins"))
        assertEquals(a, StructureJson.saltOf(ResourceKey("basic", "ruins")))
        assertTrue(a >= 0)
        assertTrue(a != StructureJson.saltOf(ResourceKey("basic", "tower")))
        // Pinned: the value must not change between platforms or releases, or every world's structures would move.
        assertEquals(1812306308, StructureJson.saltOf(ResourceKey("basic", "ruins")))
    }

    @Test
    fun aTagStandsAloneAndEverySettingIsCarriedOver() {
        val pack = datapack(
            load(
                "structures/shrine.nbt" to null,
                "structures/shrine.json" to
                    """{ "biomes": ["#minecraft:is_forest"], "spacing": 20, "separation": 5, "salt": 77, "step": "underground_structures",
                       "terrainAdaptation": "bury", "heightmap": "none", "startHeight": -30, "maxDistance": 50 }"""
            )
        )
        val structure = json(pack, "data/test/worldgen/structure/shrine.json")
        assertEquals("#minecraft:is_forest", structure.getValue("biomes").jsonPrimitive.content)
        assertEquals("underground_structures", structure.getValue("step").jsonPrimitive.content)
        assertEquals("bury", structure.getValue("terrain_adaptation").jsonPrimitive.content)
        assertNull(structure["project_start_to_heightmap"])
        assertEquals("-30", structure.getValue("start_height").jsonObject.getValue("absolute").jsonPrimitive.content)
        assertEquals("50", structure.getValue("max_distance_from_center").jsonPrimitive.content)
        val placement = json(pack, "data/test/worldgen/structure_set/shrine.json").getValue("placement").jsonObject
        assertEquals(listOf("20", "5", "77"), listOf("spacing", "separation", "salt").map { placement.getValue(it).jsonPrimitive.content })
    }

    @Test
    fun poolsMakeMorePiecesAndTheirTemplatesGoInToo() {
        val pack = datapack(
            load(
                "structures/village.nbt" to null,
                "structures/village.json" to
                    """{ "biomes": ["minecraft:plains"], "pools": {
                        "houses": { "elements": [{ "structure": "house" }, { "structure": "hut", "weight": 3 }] },
                        "roads": { "projection": "terrain_matching", "elements": [{ "structure": "road" }] } } }""",
                "structures/house.nbt" to null,
                "structures/hut.nbt" to null,
                "structures/road.nbt" to null,
                "structures/unused.nbt" to null
            )
        )
        // The pieces' templates go in; a structure nothing generates or picks stays out.
        assertEquals(
            listOf("house", "hut", "road", "village"),
            pack.keys.filter { it.startsWith("data/test/structure/") }.map { it.removePrefix("data/test/structure/").removeSuffix(".nbt") }
        )
        assertEquals("7", json(pack, "data/test/worldgen/structure/village.json").getValue("size").jsonPrimitive.content)
        val houses = json(pack, "data/test/worldgen/template_pool/village/houses.json").getValue("elements").jsonArray
        assertEquals(
            listOf("test:house" to "1", "test:hut" to "3"),
            houses.map {
                it.jsonObject.getValue("element").jsonObject.getValue("location").jsonPrimitive.content to
                    it.jsonObject.getValue("weight").jsonPrimitive.content
            }
        )
        val road = json(pack, "data/test/worldgen/template_pool/village/roads.json").getValue("elements").jsonArray.single()
        assertEquals("terrain_matching", road.jsonObject.getValue("element").jsonObject.getValue("projection").jsonPrimitive.content)
    }

    @Test
    fun aStructureWaitsForAPieceThatHasErrors() {
        val snapshot = load(
            "structures/village.nbt" to null,
            "structures/village.json" to
                """{ "biomes": ["minecraft:plains"], "pools": { "houses": { "elements": [{ "structure": "house" }] } } }""",
            "structures/house.nbt" to null,
            "structures/house.json" to """{ "biomes": [] }"""
        )
        assertTrue(snapshot.problems.any { it.code == "structure.biomes" })
        assertEquals(emptyMap(), datapack(snapshot))
    }

    /** A village of pieces and a lone shrine as the game gets them: `testdata/datapack/structures.json` (`UPDATE_GOLDEN=1` rewrites it). */
    @Test
    fun theDatapackIsTheGamesFormat() {
        val pack = datapack(
            load(
                "structures/village.nbt" to null,
                "structures/village.json" to
                    """{ "biomes": ["minecraft:plains", "minecraft:desert"], "spacing": 24, "separation": 6, "terrainAdaptation": "beard_thin",
                       "pools": { "houses": { "elements": [{ "structure": "house" }, { "structure": "hut", "weight": 3 }] },
                                  "roads": { "projection": "terrain_matching", "elements": [{ "structure": "road" }] } } }""",
                "structures/house.nbt" to null,
                "structures/hut.nbt" to null,
                "structures/road.nbt" to null,
                "structures/shrine.nbt" to null,
                "structures/shrine.json" to
                    """{ "biomes": ["#minecraft:is_forest"], "step": "underground_structures", "heightmap": "none", "startHeight": -20 }"""
            )
        )
        val actual = CanonicalJson.print(
            buildJsonObject {
                for ((path, entry) in pack) {
                    put(
                        path,
                        JsonPrimitive(
                            if (entry is DatapackEntry.Text) entry.text else "copy of ${(entry as DatapackEntry.Copy).projectPath}"
                        )
                    )
                }
            }
        ) + "\n"
        val golden = "packages/format/testdata/datapack/structures.json"
        if (actual != TestFiles.read(golden)) {
            if (TestFiles.updateGolden) {
                TestFiles.write(
                    golden,
                    actual
                )
            } else {
                fail("$golden differs. Run with UPDATE_GOLDEN=1 to accept.\n$actual")
            }
        }
    }

    @Test
    fun theDatapackIsTheSameBytesEveryTime() {
        val files = arrayOf("structures/ruins.nbt" to null, "structures/ruins.json" to ruins)
        assertEquals(datapack(load(*files)), datapack(load(*files)))
    }
}
