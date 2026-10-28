package dev.netherforge.format.biome

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.TestFiles
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.datapack.TextJson
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.format.testManifest
import dev.netherforge.format.world.StructureGenerationKind
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** `biomes/<id>.json`: the model, its checks, the game's datapack format per version, and naming one as a biome anywhere. */
class BiomeTest {
    private val text = TextJson { _, _ -> buildJsonObject {} }

    /** 1.21.11's data pack format, and 26.3's, which keeps a biome's mobs as an attribute. */
    private val older = listOf(94, 1)
    private val newer = listOf(121, 0)

    private fun load(vararg files: Pair<String, String?>, game: GameDataBundle? = null): ProjectSnapshot =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files), game)

    private fun parse(json: String): BiomeFile = (BiomeKind.parse(json, "biomes/x.json") as CanonicalJson.Parsed.Ok).value

    private fun json(pack: Map<String, DatapackEntry>, path: String): JsonObject =
        CanonicalJson.json.parseToJsonElement((pack.getValue(path) as DatapackEntry.Text).text).jsonObject

    private val grove = """
        { "climate": { "temperature": 0.7, "downfall": 0.8, "temperatureModifier": "frozen" },
          "colors": { "sky": "#F2A7C3", "fog": "#f7d3e0", "waterFog": "#5a1028", "grass": "#c2406a", "foliage": "#e0587f",
                      "dryFoliage": "#8a4b2a", "grassModifier": "dark_forest" },
          "particle": { "particle": "minecraft:cherry_leaves", "probability": 0.01 },
          "sounds": { "ambient": "minecraft:ambient.cave",
                      "mood": { "sound": "minecraft:ambient.cave" },
                      "additions": { "sound": "minecraft:ambient.cave", "chance": 0.01 },
                      "music": { "sound": "minecraft:music.overworld.cherry_grove", "minDelay": 600 } },
          "spawns": { "animal": [{ "entity": "minecraft:sheep", "weight": 12, "group": { "min": 2, "max": 4 } }],
                      "monster": [{ "entity": "zombie" }] },
          "spawnCosts": { "minecraft:enderman": { "charge": 0.7, "energyBudget": 0.15 } },
          "features": { "vegetal_decoration": ["minecraft:flower_cherry", "minecraft:trees_cherry"],
                        "lakes": ["minecraft:lake_lava_surface"] } }
    """.trimIndent()

    @Test
    fun aBiomeReadsAndWritesBackCanonically() {
        val written = BiomeKind.write(parse(grove))
        assertTrue("\"\$schema\": \"../.netherforge/schema/biome.schema.json\"" in written, written)
        assertEquals(written, BiomeKind.write(parse(written)))
        // The categories and steps are written in their own order, whatever order the file had them in.
        assertTrue(written.indexOf("\"monster\"") < written.indexOf("\"animal\""), written)
        assertTrue(written.indexOf("\"lakes\"") < written.indexOf("\"vegetal_decoration\""), written)
        val snapshot = load("biomes/grove.json" to grove)
        assertEquals(emptyList(), snapshot.problems)
    }

    @Test
    fun withGameDataItsIdsAreTheGames() {
        val game = GameDataBundle(
            minecraft = "26.3",
            registries = mapOf(
                RegistryKey.PARTICLE_TYPE.id to listOf("minecraft:cherry_leaves", "minecraft:dust"),
                RegistryKey.SOUND_EVENT.id to listOf("minecraft:ambient.cave"),
                RegistryKey.ENTITY_TYPE.id to listOf("minecraft:sheep"),
                RegistryKey.PLACED_FEATURE.id to listOf("minecraft:flower_cherry")
            ),
            particles = mapOf("minecraft:cherry_leaves" to ParticleDataKind.NONE, "minecraft:dust" to ParticleDataKind.DUST)
        )
        val sink = ProblemSink("biomes/grove.json")
        BiomeValidator.validate(parse(grove), sink, game)
        assertEquals(
            listOf(
                "biome.sound" to "$.sounds.music.sound",
                "biome.spawn" to "$.spawns.monster[0].entity",
                "biome.spawn-cost" to "$.spawnCosts[\"minecraft:enderman\"]",
                "biome.feature" to "$.features.vegetal_decoration[1]",
                "biome.feature" to "$.features.lakes[0]"
            ),
            sink.problems.map { it.code to it.path }
        )
        // A particle that takes options can't be a biome's: it would have none to take.
        val dust = ProblemSink("biomes/x.json")
        BiomeValidator.validate(parse("""{ "particle": { "particle": "minecraft:dust", "probability": 0.1 } }"""), dust, game)
        assertEquals(listOf("biome.particle"), dust.problems.map { it.code })
        // Without game data, only shapes are checked.
        val none = ProblemSink("biomes/grove.json")
        BiomeValidator.validate(parse(grove), none, null)
        assertEquals(emptyList(), none.problems)
    }

    /**
     * The datapack, for the data pack format of the oldest supported version and of 26.3, which moved a biome's mobs
     * into an attribute: `testdata/datapack/biomes.json` (`UPDATE_GOLDEN=1` rewrites it).
     */
    @Test
    fun theDatapackIsTheGamesFormatForTheServersVersion() {
        val snapshot = load("biomes/grove.json" to grove, "biomes/bare.json" to "{}")
        val actual = CanonicalJson.print(
            buildJsonObject {
                for (format in listOf(older, newer)) {
                    put(
                        format.joinToString("."),
                        buildJsonObject {
                            for ((path, entry) in StartupDatapack.build(snapshot, format, text)) {
                                if (path.endsWith(".json") &&
                                    "/worldgen/biome/" in path
                                ) {
                                    put(path, JsonPrimitive((entry as DatapackEntry.Text).text))
                                }
                            }
                        }
                    )
                }
            }
        ) + "\n"
        val golden = "packages/format/testdata/datapack/biomes.json"
        if (actual != TestFiles.read(golden)) {
            if (!TestFiles.updateGolden) fail("$golden differs. Run with UPDATE_GOLDEN=1 to accept.\n$actual")
            TestFiles.write(golden, actual)
        }
        val old = json(StartupDatapack.build(snapshot, older, text), "data/test/worldgen/biome/grove.json")
        val new = json(StartupDatapack.build(snapshot, newer, text), "data/test/worldgen/biome/grove.json")
        assertTrue("spawners" in old && "spawn_costs" in old && "spawners" !in new && "spawn_costs" !in new)
        val attributes = new.getValue("attributes").jsonObject
        assertTrue("minecraft:gameplay/natural_mob_spawns" in attributes)
        assertTrue("minecraft:gameplay/natural_mob_spawns" !in old.getValue("attributes").jsonObject)
        // Every step is there, in the game's order, and only the listed features: no carvers either.
        val features = new.getValue("features").jsonArray
        assertEquals(11, features.size)
        assertEquals("minecraft:lake_lava_surface", features[1].jsonArray.single().jsonPrimitive.content)
        assertEquals(0, new.getValue("carvers").jsonArray.size)
        assertEquals("#f2a7c3", attributes.getValue("minecraft:visual/sky_color").jsonPrimitive.content)
    }

    @Test
    fun aProjectBiomeIsNamedLikeAGameOneWhereverABiomeIs() {
        val area = """{ "biomes": { "grove": { "biome": "grove" },
            "plains": { "biome": "minecraft:plains", "temperature": { "max": 0 } } } }"""
        val centity = """{ "nodes": { "root": {} }, "spawning": { "biomes": ["grove", "minecraft:plains", "#minecraft:is_forest"] } }"""
        val structure = """{ "biomes": ["grove", "minecraft:plains"] }"""
        val snapshot = load(
            "biomes/grove.json" to grove,
            "terrain/hills.json" to area,
            "centities/deer/centity.json" to centity,
            "structures/hut.nbt" to null,
            "structures/hut.json" to structure
        )
        assertEquals(emptyList(), snapshot.problems)
        // The game's are never the project's: find usages names only the project biome's uses, the structure's included.
        val usages = snapshot.references.usagesOf(RefTarget.Resource("biome", "grove"))
        assertEquals(
            listOf(
                "centities/deer/centity.json" to "$.spawning.biomes[0]",
                "structures/hut.json" to "$.biomes[0]",
                "terrain/hills.json" to "$.biomes.grove.biome"
            ),
            usages.map { it.file to it.path }.sortedBy { it.first }
        )
        // The structure generates in the biome as the game knows it.
        val pack = StartupDatapack.build(snapshot, newer, text)
        assertEquals(
            listOf("test:grove", "minecraft:plains"),
            json(pack, "data/test/worldgen/structure/hut.json").getValue("biomes").jsonArray.map { it.jsonPrimitive.content }
        )
        // The compiled generator names it as the file does; the server resolves it in the project's namespace.
        assertEquals(listOf("grove", "minecraft:plains"), snapshot.compiled(TerrainKind).getValue("hills").biomes)
        // A rename follows it everywhere and leaves the game's alone.
        val target = RefTarget.Resource("biome", "grove")
        val terrain = ReferenceIndex.rename(TerrainKind, "terrain/hills.json", area, "test", target, "meadow")!!
        assertTrue("\"biome\": \"meadow\"" in terrain && "\"biome\": \"minecraft:plains\"" in terrain, terrain)
        val spawning = ReferenceIndex.rename(CentityKind, "centities/deer/centity.json", centity, "test", target, "meadow")!!
        assertTrue("[\"#minecraft:is_forest\", \"meadow\", \"minecraft:plains\"]" in spawning, spawning)
        val generation = ReferenceIndex.rename(StructureGenerationKind, "structures/hut.json", structure, "test", target, "meadow")!!
        assertTrue("[\"meadow\", \"minecraft:plains\"]" in generation, generation)
    }

    @Test
    fun aBareIdIsTheProjectsAndAMissingOneSaysHowTheGamesAreWritten() {
        val snapshot = load("terrain/hills.json" to """{ "biomes": { "a": { "biome": "desert" } } }""")
        val problem = snapshot.problems.single()
        assertEquals("reference.biome", problem.code)
        assertEquals("$.biomes.a.biome", problem.path)
        assertTrue("minecraft:desert" in problem.message, problem.message)
        assertTrue(RefKind.BIOME.isGame("minecraft:desert") && RefKind.BIOME.isGame("#minecraft:is_forest"))
        assertFalse(RefKind.BIOME.isGame("desert") || RefKind.BIOME.isGame("acme:desert"))
    }

    @Test
    fun aStructureWaitsForItsProjectBiomes() {
        val snapshot = load(
            "biomes/grove.json" to """{ "climate": { "downfall": 9 } }""",
            "structures/hut.nbt" to null,
            "structures/hut.json" to """{ "biomes": ["grove"] }"""
        )
        assertEquals(listOf("biome.climate"), snapshot.problems.map { it.code })
        // The game would refuse the whole pack over a structure in a biome that isn't in it.
        assertEquals(emptyMap(), StartupDatapack.build(snapshot, newer, text))
    }

    @Test
    fun twoBiomesListingFeaturesInOppositeOrdersAreRefused() {
        val snapshot = load(
            "biomes/a.json" to """{ "features": { "vegetal_decoration": ["minecraft:x", "minecraft:y", "minecraft:z"] } }""",
            "biomes/b.json" to
                """{ "features": { "vegetal_decoration": ["minecraft:z", "minecraft:x"], "lakes": ["minecraft:y", "minecraft:x"] } }""",
            "biomes/c.json" to """{ "features": { "vegetal_decoration": ["minecraft:x", "minecraft:z"] } }"""
        )
        assertEquals(
            listOf("biomes/a.json", "biomes/b.json", "biomes/c.json"),
            snapshot.problems.filter { it.code == "biome.feature-order" }.map { it.file }
        )
    }
}
