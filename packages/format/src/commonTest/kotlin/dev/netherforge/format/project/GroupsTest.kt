package dev.netherforge.format.project

import dev.netherforge.format.Location
import dev.netherforge.format.block.BlockFile
import dev.netherforge.format.block.BlockKind
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.format.testManifest
import dev.netherforge.format.world.StructureKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Group folders, `(weapons)`, sort a kind's resources without changing what
 * they're called: every kind reads from one, an id stays the project's whole,
 * and everything that needs a resource's files finds them where it is.
 */
class GroupsTest {
    private val manifest = "netherforge.json" to testManifest()
    private val game = GameDataBundle(minecraft = "26.3", dataPackFormat = listOf(121, 0))

    private fun load(vararg files: Pair<String, String?>) = Projects.load(MapProjectSource(mapOf(manifest, *files)))

    @Test
    fun pathsInGroupsAreClassifiedWithTheirGroup() {
        fun at(path: String) = Kinds.classify(path)?.let { listOf(it.kind, it.id, it.role, it.rest, it.group) }

        assertEquals(
            listOf("item", "ruby_wand", PathRole.MAIN, "item.json", "(weapons)"),
            at("items/(weapons)/ruby_wand/item.json")
        )
        assertEquals(
            listOf("item", "ruby_wand", PathRole.FILE, "lib/(not_a_group)/util.lua", "(weapons)/(wands)"),
            at("items/(weapons)/(wands)/ruby_wand/lib/(not_a_group)/util.lua")
        )
        assertEquals(listOf("item", "ruby_wand", PathRole.FOLDER, "", "(weapons)"), at("items/(weapons)/ruby_wand"))
        assertEquals(listOf("item", null, PathRole.GROUP, null, "(weapons)/(wands)"), at("items/(weapons)/(wands)"))
        assertEquals(listOf("recipe", "ruby", PathRole.MAIN, null, "(smelting)"), at("recipes/(smelting)/ruby.json"))
        assertEquals(listOf("structure", "house", PathRole.FILE, "house.json", "(town)"), at("structures/(town)/house.json"))
        assertEquals(listOf("module", "shop", PathRole.FILE, "init.lua", "(economy)"), at("modules/(economy)/shop/init.lua"))
        // A package's files the same way, and nothing hidden in a group.
        assertEquals("(gems)", Kinds.classify("acme:items/(gems)/coin/item.json")?.group)
        assertEquals(null, Kinds.classify("items/(weapons)/.DS_Store"))
        assertEquals(null, Kinds.classify("recipes/(smelting)/old/ruby.json"))
    }

    @Test
    fun everyKindButAResourcePackIsMadeAndLoadedInAGroup() {
        for (kind in Kinds.all.filter { it.groupable }) {
            val made = kind.template(kind.sampleId, game) ?: mapOf(kind.pathOf(kind.sampleId) to null)
            val files = if (kind.template(kind.sampleId, game) != null) {
                Templates.newResource(kind, kind.sampleId, game, "(stuff)/(more)")
            } else {
                made.mapKeys { (path, _) -> "${kind.folder}/(stuff)/(more)/" + path.removePrefix("${kind.folder}/") }
            }
            for (path in files.keys) {
                val found = assertNotNull(Kinds.classify(path), path)
                assertEquals(listOf(kind.id, kind.sampleId, "(stuff)/(more)"), listOf(found.kind, found.id, found.group), path)
            }
            val snapshot = Projects.load(MapProjectSource(mapOf(manifest) + files))
            assertEquals(emptyList(), snapshot.problems, kind.id)
            assertEquals(listOf(kind.sampleId), snapshot.compiled(kind).keys.toList(), kind.id)
            val place = snapshot.placeOf(kind, kind.sampleId)
            assertEquals("(stuff)/(more)", place.group, kind.id)
            assertEquals(kind.pathOf(kind.sampleId, "(stuff)/(more)"), place.path, kind.id)
            assertEquals("${kind.folder}/(stuff)/(more)/" + kind.locationOf(kind.sampleId).removePrefix("${kind.folder}/"), place.location)
        }
    }

    @Test
    fun aDocumentInAGroupPointsAtItsSchemaFromWhereItIs() {
        fun schemaOf(text: String) = Regex("\"\\\$schema\": \"([^\"]+)\"").find(text)?.groupValues?.get(1)

        val grouped = "blocks/(objects)/(small)/lamp/block.json"
        val schema = "../../../../.netherforge/schema/block.schema.json"
        assertEquals(schema, BlockKind.schemaAt(grouped))
        assertEquals(BlockKind.schemaRef, BlockKind.schemaAt(BlockKind.pathOf("lamp")))
        assertEquals(schema, BlockKind.schemaAt("acme:$grouped"))
        assertEquals(ProjectManifest.SCHEMA, ManifestKind.schemaAt("netherforge.json"))
        assertEquals(schema, schemaOf(BlockKind.write(BlockFile(), grouped)))

        // Every kind's template, made in a group, reaches out of it.
        for (kind in Kinds.all.filter { it.groupable && it is DocumentResourceKind<*, *> }) {
            val files = Templates.newResource(kind, kind.sampleId, game, "(stuff)/(more)")
            val main = kind.pathOf(kind.sampleId, "(stuff)/(more)")
            val written = schemaOf(assertNotNull(files[main], main))
            assertEquals("../".repeat(main.count { it == '/' }), written?.substringBefore(".netherforge"), main)
        }
    }

    @Test
    fun aGroupThatIsntAnIdInParenthesesIsIgnoredWhole() {
        val snapshot = load(
            "items/(Weapons)/ruby_wand/item.json" to """{ "kind": "minecraft:stick" }""",
            "items/(Weapons)/ruby_staff/item.json" to """{ "kind": "minecraft:stick" }""",
            "items/(ok)/()/gem/item.json" to """{ "kind": "minecraft:emerald" }""",
            "items/(ok)/ruby/item.json" to """{ "kind": "minecraft:emerald" }"""
        )
        // Each said once, at the first folder that isn't a group.
        assertEquals(
            listOf("project.group" to "items/(Weapons)", "project.group" to "items/(ok)/()"),
            snapshot.problems.map { it.code to it.file }
        )
        assertEquals(listOf("ruby"), snapshot[ItemKind].keys.toList())
    }

    @Test
    fun anEmptyGroupKeptByAHiddenFileIsNothingToFormat() {
        val snapshot = load(
            "items/(armour)/.gitkeep" to null,
            "items/(armour)/(helmets)/.gitkeep" to null,
            "recipes/(smelting)/.gitkeep" to null
        )
        assertEquals(emptyList(), snapshot.problems)
    }

    @Test
    fun anIdIsTheProjectsWholeWhateverGroupItsIn() {
        val snapshot = load(
            "items/(weapons)/ruby/item.json" to """{ "kind": "minecraft:stick" }""",
            "items/ruby/item.json" to """{ "kind": "minecraft:emerald" }""",
            "items/(gems)/ruby/item.json" to """{ "kind": "minecraft:emerald" }"""
        )
        // The one in the kind's own folder comes first in path order and is the one read.
        assertEquals("", snapshot.placeOf(ItemKind, "ruby").group)
        assertEquals(
            listOf("items/(gems)/ruby", "items/(weapons)/ruby"),
            snapshot.problems.filter { it.code == "project.duplicate-id" }.map { it.file }
        )
        assertEquals(listOf(Location("items/ruby")), snapshot.problems.first().related)
    }

    @Test
    fun referencesNameResourcesInGroupsByIdAlone() {
        val snapshot = load(
            "resource_packs/ui/pack.json" to """{ "glyphs": { "coin": { "texture": "coin.png" } } }""",
            "resource_packs/ui/textures/coin.png" to null,
            "items/(gems)/ruby/item.json" to """{ "kind": "minecraft:paper", "name": "<glyph:ui/coin> <glyph:ui/gem>" }""",
            "recipes/(smelting)/ruby.json" to
                """{ "type": "shapeless", "ingredients": [{ "item": "ruby" }], "result": { "item": "ruby", "count": 2 } }"""
        )
        // The one broken glyph points at the pack where it is.
        assertEquals(
            listOf(
                Triple(
                    "items/(gems)/ruby/item.json",
                    "reference.resource-pack-key",
                    listOf(Location("resource_packs/ui/pack.json", "$.glyphs"))
                )
            ),
            snapshot.problems.map { Triple(it.file, it.code, it.related) }
        )
        val references = snapshot.references
        assertEquals(
            listOf("recipes/(smelting)/ruby.json", "recipes/(smelting)/ruby.json"),
            references.usagesOf(RefTarget.Resource("item", "ruby")).map { it.file }
        )
        assertEquals(
            listOf("items/(gems)/ruby/item.json", "items/(gems)/ruby/item.json"),
            references.usagesOf(RefTarget.Resource("resource_pack", "ui")).map { it.file }
        )
    }

    @Test
    fun aResourcePackStaysInItsKindsFolder() {
        val snapshot = load(
            "resource_packs/(art)/ui/pack.json" to "{}",
            "resource_packs/(art)/more/pack.json" to "{}"
        )
        assertEquals(listOf("project.group" to "resource_packs/(art)"), snapshot.problems.map { it.code to it.file })
        assertEquals(emptySet(), snapshot[ResourcePackKind].keys)
    }

    @Test
    fun aFileInsideAResourceIsFollowedWhereverItsResourceIs() {
        val snapshot = load(
            "centities/(towers)/tower/centity.json" to
                """{ "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" } } }, "script": { "file": "script.lua" } }""",
            "centities/(towers)/tower/script.lua" to "-- hi"
        )
        assertEquals(emptyList(), snapshot.problems)
        val use = snapshot.references.usesIn("centities/(towers)/tower/centity.json").single()
        assertEquals("centities/(towers)/tower/script.lua", use.target)
    }

    @Test
    fun companionsAndScriptsAreBesideTheirResourceInItsGroup() {
        val snapshot = load(
            "structures/(town)/house.nbt" to null,
            "structures/(town)/house.json" to """{ "biomes": ["minecraft:plains"] }""",
            "terrain/(worlds)/islands.json" to TerrainKind.template("islands").values.single(),
            "terrain/(worlds)/islands.lua" to "return {}\n"
        )
        assertEquals(listOf("terrain.script-unused" to "terrain/(worlds)/islands.lua"), snapshot.problems.map { it.code to it.file })
        assertEquals("structures/(town)/house.json", snapshot.placeOf(StructureKind, "house").companionPath)
        assertEquals(setOf("house"), snapshot[StructureKind].keys)
    }

    @Test
    fun movingAResourceIntoAGroupIsReadAgainAtItsNewPlace() {
        val cache = ProjectCache()
        val item = """{ "kind": "minecraft:emerald" }"""
        val before = Projects.load(MapProjectSource(mapOf(manifest, "items/ruby/item.json" to item)), cache = cache)
        assertEquals("items/ruby/item.json", before.placeOf(ItemKind, "ruby").path)
        val after = Projects.load(MapProjectSource(mapOf(manifest, "items/(gems)/ruby/item.json" to item)), cache = cache)
        assertEquals(emptyList(), after.problems)
        assertEquals("items/(gems)/ruby/item.json", after.placeOf(ItemKind, "ruby").path)
        assertEquals(listOf("items/(gems)/ruby/item.json"), cache.validated)
    }
}
