package dev.netherforge.format.datapack

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loadTestProject
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.ProjectCache
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.project.TemplateNeedsGame
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.terrain.GeneratedLoot
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `datapacks/<id>/`: the game's own datapacks passed through. Which files a server of each format gets (overlays as
 * the game applies them), what's checked against the game's data and what against the project, and naming what one
 * defines from the project's own files. The cases without game data are `invalid/datapack-semantics` and
 * `packages/datapacks`.
 */
class DatapackTest {
    private val text = TextJson { _, _ -> buildJsonObject {} }

    private val minecraft1211 = listOf(94, 1)
    private val minecraft2612 = listOf(101, 1)
    private val minecraft262 = listOf(107, 1)
    private val minecraft263 = listOf(121, 0)

    private fun load(
        vararg files: Pair<String, String?>,
        game: GameDataBundle? = null,
        cache: ProjectCache = ProjectCache()
    ): ProjectSnapshot = Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files), game, cache = cache)

    /** 26.3's game data, with the registries these cases name. */
    private val game = GameDataBundle(
        minecraft = "26.3",
        registries = mapOf(
            "minecraft:worldgen/placed_feature" to listOf("minecraft:trees_plains"),
            "minecraft:worldgen/feature" to listOf("minecraft:trees_plains"),
            "minecraft:worldgen/biome" to listOf("minecraft:plains"),
            "minecraft:worldgen/noise_settings" to listOf("minecraft:overworld"),
            "minecraft:worldgen/noise" to listOf("minecraft:surface")
        ),
        tags = mapOf("minecraft:worldgen/biome" to mapOf("minecraft:is_forest" to listOf("minecraft:plains"))),
        dataPackFormat = minecraft263
    )

    private fun copies(pack: Map<String, DatapackEntry>): Map<String, String> =
        pack.filterValues { it is DatapackEntry.Copy }.mapValues { (it.value as DatapackEntry.Copy).projectPath }

    @Test
    fun aNewDatapackIsForTheServersFormatAndNeedsTheGameToKnowIt() {
        val files = DatapackKind.template("trees", game)
        val meta = files.getValue("datapacks/trees/pack.mcmeta")
        assertTrue("\"min_format\": 121" in meta && "\"max_format\": 121" in meta, meta)
        assertEquals(emptyList(), load(*files.toList().toTypedArray(), game = game).problems)
        assertFailsWith<TemplateNeedsGame> { DatapackKind.template("trees", null) }
    }

    @Test
    fun blockTagsPassThroughBesideWorldgen() {
        val placed = DatapackLayout.place("data/basic/tags/block/tools/soft.json", emptySet()) as DatapackLayout.Placed.Ok
        assertEquals("block", placed.path.registry)
        assertEquals("tools/soft", placed.path.path)
        assertTrue(placed.path.tag)
        assertEquals("data/basic/tags/block/tools/soft.json", placed.path.target)
        // A block itself isn't the datapack's to add, and other registries' tags don't pass.
        assertTrue(DatapackLayout.place("data/basic/block/soft.json", emptySet()) is DatapackLayout.Placed.Wrong)
        assertTrue(DatapackLayout.place("data/basic/tags/item/soft.json", emptySet()) is DatapackLayout.Placed.Wrong)
    }

    @Test
    fun formatsAreReadAsTheGameReadsThem() {
        assertEquals(PackFormat(94, 0), PackFormat.read(JsonPrimitive(94), upper = false))
        // A whole number as the upper end is every minor of it.
        assertEquals(PackFormat(94, Int.MAX_VALUE), PackFormat.read(JsonPrimitive(94), upper = true))
        assertEquals(
            PackFormat(94, 1),
            PackFormat.read(
                buildJsonArray {
                    add(JsonPrimitive(94))
                    add(JsonPrimitive(1))
                },
                upper = true
            )
        )
        assertEquals(PackFormat(94, Int.MAX_VALUE), PackFormat.read(buildJsonArray { add(JsonPrimitive(94)) }, upper = true))
        assertNull(PackFormat.read(JsonPrimitive("94"), upper = false))
        assertNull(PackFormat.read(JsonPrimitive(-1), upper = false))
        assertNull(PackFormat.range(JsonPrimitive(121), JsonPrimitive(94)))
        assertTrue(PackFormat.of(minecraft1211) in PackFormat.range(JsonPrimitive(94), JsonPrimitive(94))!!)
    }

    /** The example's boulders: the placed feature in `data/`, each version's configured feature in an overlay of its own. */
    @Test
    fun aServerGetsTheFilesForItsFormat() {
        val snapshot = loadTestProject("examples", "showcase")
        val pack = "datapacks/ruby_boulders"
        val placed = "data/showcase/worldgen/placed_feature/ruby_boulders.json"
        val feature = "data/showcase/worldgen/feature/ruby_boulder.json"
        val configured = "data/showcase/worldgen/configured_feature/ruby_boulder.json"

        // The datapack's files alone (the example's generating structures are copied in too).
        fun boulders(built: Map<String, DatapackEntry>) = copies(built).filterValues { it.startsWith(pack) }

        assertEquals(
            mapOf(configured to "$pack/mc1_21/$configured", placed to "$pack/$placed"),
            boulders(StartupDatapack.build(snapshot, minecraft1211, text))
        )
        for (format in listOf(minecraft2612, minecraft262)) {
            assertEquals(
                mapOf(configured to "$pack/mc26_1/$configured", placed to "$pack/$placed"),
                boulders(StartupDatapack.build(snapshot, format, text))
            )
        }
        assertEquals(
            mapOf(feature to "$pack/mc26_3/$feature", placed to "$pack/$placed"),
            boulders(StartupDatapack.build(snapshot, minecraft263, text))
        )
        // A server it isn't written for gets none of it, nor one that refused it.
        assertEquals(emptyMap(), boulders(StartupDatapack.build(snapshot, listOf(130, 0), text)))
        assertEquals(emptyMap(), boulders(StartupDatapack.build(snapshot, minecraft263, text, passThrough = false)))
    }

    @Test
    fun aTerrainThatFillsContainersBringsTheEmptyTableTheGameUnpacksThemWith() {
        val loot = """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "kind": "minecraft:stick" } }] } } }"""
        val without = load(
            "loot/junk.json" to loot,
            "terrain/hills.json" to """{ "decorations": { "b": { "block": "minecraft:barrel" } } }"""
        )
        assertEquals(emptyList(), without.problems)
        assertFalse(GeneratedLoot.DATAPACK_PATH in StartupDatapack.build(without, minecraft263, text))
        val with = load(
            "loot/junk.json" to loot,
            "terrain/hills.json" to """{ "decorations": { "b": { "block": "minecraft:barrel", "loot": "junk" } } }"""
        )
        assertEquals(emptyList(), with.problems)
        val entry = StartupDatapack.build(with, minecraft263, text).getValue(GeneratedLoot.DATAPACK_PATH)
        assertEquals("{\"type\":\"minecraft:chest\",\"pools\":[]}", (entry as DatapackEntry.Text).text.replace(Regex("\\s"), ""))
        // A table a terrain names is a reference like any.
        val missing = load("terrain/hills.json" to """{ "decorations": { "b": { "block": "minecraft:barrel", "loot": "nope" } } }""")
        assertEquals(listOf("reference.loot-table"), missing.problems.map { it.code })
    }

    @Test
    fun aBiomeNamesADatapacksFeatureAsItsOwnAndWaitsForIt() {
        val snapshot = load(
            "datapacks/rocks/pack.mcmeta" to """{ "pack": { "min_format": 121, "max_format": 121 } }""",
            "datapacks/rocks/data/test/worldgen/placed_feature/rocks.json" to """{ "feature": "test:rock", "placement": [] }""",
            "datapacks/rocks/data/test/worldgen/feature/rock.json" to """{ "type": "minecraft:block_blob" }""",
            "biomes/stony.json" to """{ "features": { "local_modifications": ["rocks", "minecraft:trees_plains"] } }""",
            "terrain/hills.json" to """{ "biomes": { "a": { "biome": "stony" } } }"""
        )
        assertEquals(emptyList(), snapshot.problems)
        val use = snapshot.references.uses.single { it.kind == RefKind.PLACED_FEATURE && it.text == "rocks" }
        assertEquals("test:rocks", use.target)

        val pack = StartupDatapack.build(snapshot, minecraft263, text)
        val biome = CanonicalJson.json.parseToJsonElement((pack.getValue("data/test/worldgen/biome/stony.json") as DatapackEntry.Text).text)
        assertEquals(
            listOf("test:rocks", "minecraft:trees_plains"),
            biome.jsonObject.getValue("features").jsonArray[2].jsonArray.map { (it as JsonPrimitive).content }
        )
        // Without the datapack (refused, or not for this format), the game would refuse the biome: it waits.
        assertFalse("data/test/worldgen/biome/stony.json" in StartupDatapack.build(snapshot, minecraft263, text, passThrough = false))
        assertFalse("data/test/worldgen/biome/stony.json" in StartupDatapack.build(snapshot, minecraft1211, text))
    }

    @Test
    fun theGamesDataChecksTheFilesTheTargetReads() {
        val snapshot = load(
            "datapacks/main/pack.mcmeta" to
                """{ "pack": { "min_format": 94, "max_format": 121 },
                     "overlays": { "entries": [{ "min_format": 94, "max_format": 107, "directory": "old" }] } }""",
            // Replaces one of the game's: fine. Replaces nothing: refused.
            "datapacks/main/data/minecraft/worldgen/noise_settings/overworld.json" to
                """{ "noise_router": { "x": { "noise": "minecraft:surface" } } }""",
            "datapacks/main/data/minecraft/worldgen/noise_settings/flat_world.json" to "{}",
            "datapacks/main/data/minecraft/tags/worldgen/biome/is_forest.json" to """{ "values": ["test:glade"] }""",
            "datapacks/main/data/minecraft/tags/worldgen/biome/is_glade.json" to """{ "values": [] }""",
            "datapacks/main/data/test/worldgen/biome/glade.json" to """{ "features": [["minecraft:nope"], ["#minecraft:is_forest"]] }""",
            "datapacks/main/data/test/worldgen/placed_feature/rocks.json" to """{ "feature": "minecraft:boulder" }""",
            "datapacks/main/data/test/worldgen/nonsense/x.json" to "{}",
            // For older servers: the target's data says nothing about it (26.3 has no configured_feature).
            "datapacks/main/old/data/test/worldgen/configured_feature/rock.json" to "{}",
            game = game
        )
        val problems = snapshot.problems.map { listOf(it.code, it.file.removePrefix("datapacks/main/"), it.path) }
        assertEquals(
            listOf(
                listOf("datapack.override", "data/minecraft/tags/worldgen/biome/is_glade.json", null),
                listOf("datapack.override", "data/minecraft/worldgen/noise_settings/flat_world.json", null),
                listOf("datapack.reference", "data/test/worldgen/biome/glade.json", "$.features[0][0]"),
                listOf("datapack.reference", "data/test/worldgen/biome/glade.json", "$.features[1][0]"),
                listOf("datapack.registry", "data/test/worldgen/nonsense/x.json", null),
                listOf("datapack.reference", "data/test/worldgen/placed_feature/rocks.json", "$.feature")
            ),
            problems,
            snapshot.problems.joinToString("\n")
        )
        assertTrue(snapshot.problems.any { "placed feature \"minecraft:nope\"" in it.message })
        assertTrue(snapshot.problems.any { "feature \"minecraft:boulder\"" in it.message })
    }

    /** A missing entry of the project's is said in the target's words: 26.3 calls a configured feature `worldgen/feature`. */
    @Test
    fun aMissingEntryIsNamedAsTheTargetNamesItsRegistry() {
        val files = arrayOf(
            "datapacks/main/pack.mcmeta" to """{ "pack": { "min_format": 121, "max_format": 121 } }""",
            "datapacks/main/data/test/worldgen/placed_feature/rocks.json" to """{ "feature": "test:missing", "placement": [] }"""
        )
        val without = load(*files).problems.single()
        assertTrue("configured feature \"test:missing\"" in without.message, without.message)
        val with = load(*files, game = game).problems.single()
        assertEquals("datapack.reference", with.code)
        assertTrue("data/test/worldgen/feature/missing.json" in with.message, with.message)
    }

    @Test
    fun aDatapackForAnotherFormatIsAProblemForItsTarget() {
        val snapshot = load("datapacks/old/pack.mcmeta" to """{ "pack": { "min_format": 94, "max_format": [107, 1] } }""", game = game)
        val problem = snapshot.problems.single()
        assertEquals("datapack.version", problem.code)
        assertEquals("$.pack", problem.path)
        assertTrue("94.0 to 107.1" in problem.message && "Minecraft 26.3 reads 121.0" in problem.message, problem.message)
        assertTrue(DatapackKind.pathOf("old") == problem.file)
    }

    /** Its files are read and parsed once while they're the same; one edited is read again. */
    @Test
    fun aCachedDatapackRereadsOnlyWhenAFileChanges() {
        val cache = ProjectCache()
        val mcmeta = "datapacks/a/pack.mcmeta" to """{ "pack": { "min_format": 121, "max_format": 121 } }"""
        val file = "datapacks/a/data/test/worldgen/noise/n.json"
        load(mcmeta, file to "{}", cache = cache)
        assertTrue(DatapackKind.pathOf("a") in cache.validated)
        load(mcmeta, file to "{}", cache = cache)
        assertFalse(DatapackKind.pathOf("a") in cache.validated)
        val broken = load(mcmeta, file to "{", cache = cache)
        assertEquals(listOf("parse"), broken.problems.map { it.code })
        assertEquals(file, broken.problems.single().file)
    }
}
