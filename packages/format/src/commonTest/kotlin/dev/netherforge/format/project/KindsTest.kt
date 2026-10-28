package dev.netherforge.format.project

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The registry is the one list of kinds: every kind in it is found by
 * [Kinds.classify], made from its template and loaded by [Projects.load]
 * with nothing else to wire up. A kind that needed a list of its own
 * somewhere would fail here first.
 */
class KindsTest {
    private val manifest = mapOf(
        "netherforge.json" to testManifest()
    )

    /** Game data a template may ask for: a datapack's format. */
    private val game = GameDataBundle(minecraft = "26.3", dataPackFormat = listOf(121, 0))

    /** A new resource of [kind]: its template, or (for a kind made some other way) its main file, which format never reads. */
    private fun sample(kind: KindSpec<*, *>): Map<String, String?> = kind.template(kind.sampleId, game)
        ?: mapOf(kind.pathOf(kind.sampleId) to null)

    @Test
    fun everyKindIsClassifiedMadeAndLoaded() {
        for (kind in Kinds.all) {
            val files = sample(kind)
            for (path in files.keys) {
                val found = assertNotNull(Kinds.classify(path), path)
                assertEquals(kind.id, found.kind, path)
                assertEquals(kind.sampleId, found.id, path)
            }
            val main = Kinds.classify(kind.pathOf(kind.sampleId))!!
            assertEquals(if (kind.mainFile == null && kind.layout is Layout.Folder) PathRole.FOLDER else PathRole.MAIN, main.role)
            assertEquals((kind as? DocumentResourceKind<*, *>)?.id, main.document, "${kind.id}'s main file is its document")

            val snapshot = Projects.load(MapProjectSource(manifest + files))
            assertEquals(emptyList(), snapshot.problems, kind.id)
            assertEquals(listOf(kind.sampleId), snapshot[kind].keys.toList(), kind.id)
            assertEquals(listOf(kind.sampleId), snapshot.compiled(kind).keys.toList(), kind.id)
            if (kind.script != null) Templates.script(kind, kind.sampleId)
        }
    }

    @Test
    fun pathsAreClassifiedByTheirKindsLayout() {
        fun at(path: String) = Kinds.classify(path)?.let { listOf(it.kind, it.id, it.role, it.document, it.rest) }

        assertEquals(listOf("centity", "tower", PathRole.MAIN, "centity", "centity.json"), at("centities/tower/centity.json"))
        assertEquals(listOf("centity", "tower", PathRole.FILE, null, "lib/util.lua"), at("centities/tower/lib/util.lua"))
        assertEquals(listOf("centity", "tower", PathRole.FILE, null, "extra/centity.json"), at("centities/tower/extra/centity.json"))
        assertEquals(listOf("menu", "shop", PathRole.FOLDER, null, ""), at("menus/shop"))
        assertEquals(listOf("recipe", "ruby", PathRole.MAIN, "recipe", null), at("recipes/ruby.json"))
        assertEquals(listOf("structure", "house", PathRole.MAIN, null, "house.nbt"), at("structures/house.nbt"))
        assertEquals(listOf("structure", "house", PathRole.FILE, "structure_generation", "house.json"), at("structures/house.json"))
        assertEquals(listOf("map", "arena", PathRole.MAIN, null, "level.dat"), at("maps/arena/level.dat"))
        assertEquals(listOf("module", "greeter", PathRole.FILE, null, "init.lua"), at("modules/greeter/init.lua"))
        assertEquals(
            listOf("resource_pack", "ui", PathRole.FILE, null, "textures/gui/shop.png"),
            at("resource_packs/ui/textures/gui/shop.png")
        )
        assertEquals(listOf(null, null, PathRole.PROJECT, "netherforge", null), at("netherforge.json"))
        assertEquals(listOf(null, null, PathRole.PROJECT, "default_font", null), at("fonts/default.json"))
        // Backslashes and a leading slash, as a path may come from Windows or a careless caller.
        assertEquals(listOf("menu", "shop", PathRole.MAIN, "menu", "menu.json"), at("\\menus\\shop\\menu.json"))

        // Ids aren't checked: loading reports them.
        assertEquals("Bad-Name", Kinds.classify("centities/Bad-Name/centity.json")?.id)
        for (nobody in listOf(
            "README.md",
            "centities",
            "centities/.gitkeep",
            "recipes/.json",
            "recipes/swords/ruby.json",
            "recipes/notes.txt",
            "structures/a/b.nbt",
            "fonts/other.json",
            ".netherforge/schema/centity.schema.json"
        )) {
            assertNull(Kinds.classify(nobody), nobody)
        }
    }

    @Test
    fun aStrayFileInAKindsFolderIsReportedAndAHiddenOneIsNot() {
        val snapshot = Projects.load(
            MapProjectSource(manifest + mapOf("centities/notes.txt" to null, "centities/.gitkeep" to "", "recipes/.DS_Store" to null))
        )
        assertEquals(listOf("centities/notes.txt"), snapshot.problems.map { it.file })
        assertEquals("project.stray-file", snapshot.problems.single().code)
    }
}
