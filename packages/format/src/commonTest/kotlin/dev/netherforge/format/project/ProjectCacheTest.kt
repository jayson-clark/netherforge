package dev.netherforge.format.project

import dev.netherforge.format.TestFiles
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loading through a [ProjectCache] reads and validates only what changed
 * since the last load, and finds exactly what a load from scratch finds,
 * the checks across files included.
 */
class ProjectCacheTest {
    private val manifest = "netherforge.json" to testManifest()
    private val item = "items/ruby/item.json" to """{ "kind": "minecraft:emerald" }"""
    private val recipe = "recipes/ruby.json" to
        """{ "type": "shapeless", "ingredients": ["minecraft:stick"], "result": { "item": "ruby", "kind": "minecraft:emerald" } }"""
    private val menu = "menus/shop/menu.json" to """{ "title": "Shop" }"""
    private val project = mapOf(manifest, item, recipe, menu)

    private fun load(files: Map<String, String?>, cache: ProjectCache = ProjectCache(), game: GameDataBundle? = null) =
        Projects.load(MapProjectSource(files), game, cache = cache).problems

    @Test
    fun aLoadAfterAnEditValidatesOnlyTheFileThatChanged() {
        val cache = ProjectCache()
        assertEquals(emptyList(), load(project, cache))
        assertEquals(listOf("netherforge.json", "items/ruby/item.json", "recipes/ruby.json", "menus/shop/menu.json"), cache.validated)

        load(project, cache)
        assertEquals(emptyList(), cache.validated, "nothing changed, so nothing is validated again")

        val edited = project + ("menus/shop/menu.json" to """{ "title": "Market" }""")
        assertEquals(load(edited), load(edited, cache))
        assertEquals(listOf("menus/shop/menu.json"), cache.validated)
    }

    @Test
    fun checksAcrossFilesFollowAFileThatDidntChange() {
        val cache = ProjectCache()
        load(project, cache)

        // The recipe is kept, but its stack's kind is checked against the item's again.
        val diamond = project + ("items/ruby/item.json" to """{ "kind": "minecraft:diamond" }""")
        val problems = load(diamond, cache)
        assertEquals(listOf("items/ruby/item.json"), cache.validated)
        assertEquals(listOf("item.kind-mismatch"), problems.map { it.code })
        assertEquals(load(diamond), problems)

        // And its reference, once the item is gone.
        val gone = project - "items/ruby/item.json"
        assertEquals(listOf("reference.item"), load(gone, cache).map { it.code })
        assertEquals(emptyList(), cache.validated)

        assertEquals(emptyList(), load(project, cache))
        assertEquals(listOf("items/ruby/item.json"), cache.validated, "a file that came back is read again")
    }

    @Test
    fun aResourceIsValidatedAgainWhenWhatItDependsOnChanges() {
        val cache = ProjectCache()
        load(project, cache)

        load(project + ("menus/shop/main.lua" to null), cache)
        assertEquals(listOf("menus/shop/menu.json"), cache.validated, "the files in its folder")

        load(project, cache, GameDataBundle(minecraft = "26.3"))
        assertEquals(listOf("items/ruby/item.json", "recipes/ruby.json", "menus/shop/menu.json"), cache.validated, "the game data")

        load(project + ("netherforge.json" to testManifest().replace("\"26.3\"", "\"26.2\"")), cache, GameDataBundle(minecraft = "26.3"))
        assertEquals(
            listOf("netherforge.json", "items/ruby/item.json", "recipes/ruby.json", "menus/shop/menu.json"),
            cache.validated,
            "the target version"
        )
    }

    /** Every example and every invalid case finds the same problems through a cache, loaded fresh or kept. */
    @Test
    fun aKeptLoadFindsWhatAFreshOneDoes() {
        val projects = TestFiles.dirs("examples").map { "examples/$it" } +
            TestFiles.dirs("packages/format/testdata/invalid").map { "packages/format/testdata/invalid/$it" }
        for (base in projects) {
            val files = TestFiles.list(base).filter { it != "expected.json" }.associateWith { TestFiles.read("$base/$it") }
            val fresh = Projects.load(MapProjectSource(files))
            val cache = ProjectCache()
            assertEquals(fresh.problems, Projects.load(MapProjectSource(files), cache = cache).problems, base)
            val kept = Projects.load(MapProjectSource(files), cache = cache)
            assertEquals(emptyList(), cache.validated, base)
            assertEquals(fresh.problems, kept.problems, base)
            assertEquals(fresh.references.uses, kept.references.uses, base)
            assertTrue(fresh.resources.keys == kept.resources.keys, base)
        }
    }

    @Test
    fun aPackagesFilesAreKeptApartFromTheProjects() {
        val gem = "items/gem/item.json"
        var library = mapOf(
            "netherforge.json" to testManifest().replace("\"test\"", "\"library\""),
            gem to """{ "kind": "minecraft:emerald" }"""
        )
        val packages = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest) =
                if (request == PackageRequest.Folder("../library")) OpenedPackage(MapProjectSource(library)) else PackageMissing()
        }
        val app = project + ("netherforge.json" to testManifest(""", "dependencies": { "library": { "path": "../library" } }"""))
        fun load(cache: ProjectCache) = Projects.load(MapProjectSource(app), null, packages, cache).problems

        val cache = ProjectCache()
        val fresh = load(ProjectCache())
        assertEquals(fresh, load(cache))
        assertEquals(listOf("netherforge.json", gem), cache.scope("library").validated)

        assertEquals(fresh, load(cache))
        assertEquals(emptyList(), cache.validated)
        assertEquals(emptyList(), cache.scope("library").validated)

        library = library + (gem to """{ "kind": "minecraft:diamond" }""")
        load(cache)
        assertEquals(emptyList(), cache.validated, "the project's files are its own")
        assertEquals(listOf(gem), cache.scope("library").validated)
    }
}
