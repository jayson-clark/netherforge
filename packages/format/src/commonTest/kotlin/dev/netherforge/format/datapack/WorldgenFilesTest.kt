package dev.netherforge.format.datapack

import dev.netherforge.format.datapack.WorldgenReferences.Found
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.ref.ResourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Where a passed-through datapack's files are ([DatapackLayout]) and what they name ([WorldgenReferences]), on their
 * own: `DatapackTest` and the `datapack-semantics` golden check them through a whole project, and the Paper contract
 * test runs the rules over each server's own files.
 */
class WorldgenFilesTest {
    private fun place(file: String, overlays: Set<String> = emptySet()) = DatapackLayout.place(file, overlays)

    private fun ok(file: String, overlays: Set<String> = emptySet()): DatapackPath =
        assertIs<DatapackLayout.Placed.Ok>(place(file, overlays)).path

    private fun wrong(file: String, overlays: Set<String> = emptySet()): String =
        assertIs<DatapackLayout.Placed.Wrong>(place(file, overlays)).message

    @Test
    fun anEntryIsItsNamespaceRegistryAndPath() {
        val path = ok("data/case/worldgen/placed_feature/trees/oak.json")
        assertEquals(
            DatapackPath("data/case/worldgen/placed_feature/trees/oak.json", null, "case", "worldgen/placed_feature", "trees/oak", false),
            path
        )
        assertEquals(ResourceKey("case", "trees/oak"), path.key)
        assertEquals("data/case/worldgen/placed_feature/trees/oak.json", path.target)
    }

    @Test
    fun aTagIsUnderTags() {
        val path = ok("data/minecraft/tags/worldgen/biome/is_forest.json")
        assertTrue(path.tag)
        assertEquals("worldgen/biome", path.registry)
        assertEquals("is_forest", path.path)
        assertEquals("data/minecraft/tags/worldgen/biome/is_forest.json", path.target)
    }

    @Test
    fun anOverlaysFileTargetsWhatItReplaces() {
        val path = ok("v26_3/data/case/worldgen/feature/rock.json", setOf("v26_3"))
        assertEquals("v26_3", path.overlay)
        assertEquals("data/case/worldgen/feature/rock.json", path.target)
        // A folder pack.mcmeta doesn't list as an overlay isn't one.
        assertTrue("Only data/ and the overlays pack.mcmeta lists hold" in wrong("v26_3/data/case/worldgen/feature/rock.json"))
        assertTrue("(a, b)" in wrong("c/data/x/worldgen/noise/n.json", setOf("b", "a")))
    }

    @Test
    fun onlyWorldgenJsonWithGameIdsPassesThrough() {
        assertTrue("Only worldgen passes through" in wrong("data/case/loot_table/chest.json"))
        assertTrue("Only worldgen passes through" in wrong("data/case/worldgen/noise.json"))
        assertTrue("Only worldgen passes through" in wrong("data/case/tags/item/logs.json"))
        assertEquals("A datapack's files are .json", wrong("data/case/worldgen/noise/n.txt"))
        assertEquals("A datapack's files are .json", wrong("data/case/worldgen/noise/.json"))
        assertTrue("isn't a namespace" in wrong("data/Case/worldgen/noise/n.json"))
        assertTrue("isn't an id the game takes" in wrong("data/case/worldgen/noise/Wobble.json"))
        assertTrue("isn't an id the game takes" in wrong("data/case/worldgen/noise/a//b.json"))
    }

    @Test
    fun entriesAreEveryPlacedFileButTheMeta() {
        val meta = CanonicalJson.json.decodeFromString(
            PackMeta.serializer(),
            """{ "pack": { "description": "x", "min_format": 94, "max_format": 121 },
                "overlays": { "entries": [{ "min_format": 121, "max_format": 121, "directory": "new" }] } }"""
        )
        assertEquals(setOf("new"), DatapackLayout.overlaysOf(meta))
        val entries = DatapackLayout.entries(
            meta,
            listOf(
                "pack.mcmeta",
                "data/a/worldgen/noise/n.json",
                "new/data/a/worldgen/noise/n.json",
                "notes.txt",
                "old/data/a/worldgen/noise/n.json"
            )
        )
        assertEquals(listOf(null to "worldgen/noise", "new" to "worldgen/noise"), entries.map { it.overlay to it.registry })
    }

    private fun find(registry: String, json: String) = WorldgenReferences.find(registry, CanonicalJson.json.parseToJsonElement(json))

    @Test
    fun aRuleFindsStringsAndListsWhereItLooks() {
        assertEquals(
            listOf(Found("$.feature", "case:rock", listOf("worldgen/configured_feature", "worldgen/feature"))),
            find("worldgen/placed_feature", """{ "feature": "case:rock", "placement": [] }""")
        )
        // `features.*`: each step's list, every string in it; an inline object names nothing.
        assertEquals(
            listOf(
                Found("$.features[0][0]", "minecraft:trees", listOf("worldgen/placed_feature")),
                Found("$.features[1][1]", "case:rocks", listOf("worldgen/placed_feature"))
            ),
            find("worldgen/biome", """{ "features": [["minecraft:trees"], [{ "feature": "x" }, "case:rocks"]] }""").filter {
                it.path.startsWith("$.features")
            }
        )
        // A tag is found as written, `#` and all.
        assertEquals(listOf("#minecraft:is_forest"), find("worldgen/structure", """{ "biomes": "#minecraft:is_forest" }""").map { it.text })
    }

    @Test
    fun deepRulesLookAtEveryLevelOnce() {
        val found = find(
            "worldgen/density_function",
            """{ "type": "add", "argument1": { "type": "noise", "noise": "case:a" }, "argument2": { "nested": [{ "noise": "case:b" }] }, "noise": "case:c" }"""
        )
        assertEquals(setOf("case:a", "case:b", "case:c"), found.map { it.text }.toSet())
        assertEquals(found.size, found.distinct().size)
        assertEquals(setOf("worldgen/noise"), found.flatMap { it.targets }.toSet())
    }

    @Test
    fun aRegistryWithoutRulesNamesNothing() {
        assertEquals(emptyList(), find("worldgen/noise", """{ "firstOctave": -7, "amplitudes": [1] }"""))
        assertEquals(emptyList(), find("worldgen/placed_feature", """{ "feature": { "type": "minecraft:tree" } }"""))
    }

    @Test
    fun aTagsValuesAreItsOwnRegistrysOrNullWhenItIsntATag() {
        fun values(json: String) = WorldgenReferences.tagValues("worldgen/biome", CanonicalJson.json.parseToJsonElement(json))
        assertEquals(
            listOf(
                Found("$.values[0]", "minecraft:plains", listOf("worldgen/biome")),
                Found("$.values[1].id", "#case:odd", listOf("worldgen/biome"))
            ),
            values("""{ "replace": false, "values": ["minecraft:plains", { "id": "#case:odd", "required": false }] }""")
        )
        assertNull(values("""{ "values": "minecraft:plains" }"""))
        assertNull(values("""{ "values": [], "extra": 1 }"""))
        assertNull(values("""{ "values": [], "replace": "yes" }"""))
        assertNull(values("""{ "values": [{ "id": 3 }] }"""))
        assertNull(values("""{ "values": [{ "id": "a", "weight": 2 }] }"""))
        assertNull(values("""{ "values": [7] }"""))
    }
}
