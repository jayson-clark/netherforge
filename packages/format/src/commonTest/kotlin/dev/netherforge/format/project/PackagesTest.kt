package dev.netherforge.format.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.ProblemGoldens
import dev.netherforge.format.TestFiles
import dev.netherforge.format.TestPackages
import dev.netherforge.format.item.ItemFile
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.loadTestProject
import dev.netherforge.format.loot.ItemEntry
import dev.netherforge.format.loot.LootTableKind
import dev.netherforge.format.loot.LootValidator
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.ref.RefTarget
import dev.netherforge.format.ref.ReferenceIndex
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.resourcepack.PackLayout
import dev.netherforge.format.testManifest
import dev.netherforge.format.text.GlyphTags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Dependencies: resolving them, what a project can name of a package, the lock, and how a package's resources run. */
class PackagesTest {

    /**
     * Each case in `packages/format/testdata/packages/<case>/` is a few
     * projects side by side; `app` is the one loaded, and `expected.json`
     * lists the problems loading it (and the packages it depends on) must produce.
     */
    @Test
    fun casesReportExpectedProblems() {
        val cases = TestFiles.dirs(ProblemGoldens.PACKAGES)
        assertTrue(cases.isNotEmpty(), "no package cases found")
        val failures = cases.flatMap { ProblemGoldens.loadPackages(it).failures(mustHaveProblems = false) }
        ProblemGoldens.failAll("package cases", cases.size, failures)
    }

    @Test
    fun pathsJoinRelativeToTheRequirer() {
        assertEquals("../library", Packages.join("", "../library"))
        assertEquals("../economy", Packages.join("../library", "../economy"))
        assertEquals("../../shared/x", Packages.join("../library", "../../shared/./x"))
        assertEquals("deps/a", Packages.join("", "./deps/a/"))
        assertEquals("", Packages.join("deps/a", "../.."))
        assertTrue(Packages.isPath("../economy"))
        assertTrue(Packages.isPath("vendor/econ"))
        assertFalse(Packages.isPath("/abs/econ"))
        assertFalse(Packages.isPath("..\\econ"))
        assertFalse(Packages.isPath("C:/econ"))
        assertFalse(Packages.isPath("a//b"))
        assertFalse(Packages.isPath(""))
    }

    @Test
    fun packagePathsClassifyInTheirPackage() {
        val at = Kinds.classify("library:items/gem/script.lua")!!
        assertEquals("library", at.pkg)
        assertEquals("item", at.kind)
        assertEquals("gem", at.id)
        assertEquals(PathRole.FILE, at.role)
        assertNull(Kinds.classify("items/gem/item.json")!!.pkg)
        assertEquals("library", Kinds.classify("library:netherforge.json")!!.pkg)
        assertEquals("library:items/gem/item.json", ItemKind.pathOf("library:gem"))
        assertEquals("library:modules/greetings/init.lua", ModuleKind.fileOf("library:greetings", "init.lua"))
        assertEquals("items/gem/item.json", ItemKind.pathOf("gem"))
    }

    @Test
    fun theHashCoversWhatAPackageIs() {
        val digests = mapOf(
            "netherforge.json" to "AA",
            "items/gem/item.json" to "bb",
            "items/gem/script.lua" to "cc",
            "README.md" to "dd",
            ".luarc.json" to "ee",
            "netherforge.lock" to "ff",
            "items/gem/.DS_Store" to "00",
            "fonts/default.json" to "11"
        )
        assertEquals(
            "bb  items/gem/item.json\ncc  items/gem/script.lua\naa  netherforge.json\n",
            PackageHash.listing(digests)
        )
        assertEquals("sha256:abc", PackageHash.of("ABC"))
        // The editor writes the default font from this computer's client: bundled for the server, never hashed.
        assertTrue(PackageHash.isBundled("fonts/default.json") && !PackageHash.isContent("fonts/default.json"))
        assertTrue(PackageHash.isBundled("netherforge.json") && !PackageHash.isBundled("README.md"))
    }

    /**
     * `examples/showcase` runs the library's resources beside its own, named
     * `library:<id>`, compiled with every reference written in full: the
     * gem's model is the library's pack's, wherever it's built.
     */
    @Test
    fun aPackagesResourcesRunBesideTheProjects() {
        val snapshot = loadTestProject("examples", "showcase")
        val items = snapshot.running(ItemKind)
        assertEquals(
            setOf(
                "candy", "circlet", "crown", "crystal_glass", "glow_lamp", "glow_pickaxe", "guide", "lantern", "ruby", "ruby_ore",
                "ruby_pick", "ruby_wand", "shard", "library:gem"
            ),
            items.keys
        )
        assertEquals("library:gems/gem", items.getValue("library:gem").itemModel?.text)
        // The project's own are compiled as written.
        assertEquals("ui/ruby", items.getValue("ruby").itemModel?.text)
        assertEquals("gems/gem", snapshot.packages.getValue("library").snapshot.models(ItemKind).getValue("gem").itemModel?.text)
        assertTrue("library:phrases" in snapshot.everywhere(ModuleKind))
        // A package's loot table names its own items bare, and runs naming them in full.
        val gems = LootValidator.entries(snapshot.running(LootTableKind).getValue("library:gems")).first().second as ItemEntry
        assertEquals("library:gem", gems.item.item?.text)
        val pack = snapshot.compiledResourcePacks.getValue("library:gems")
        assertEquals("library" to "gems", pack.namespace to pack.id)
        assertEquals("showcase", snapshot.compiledResourcePacks.getValue("ui").namespace)
        assertEquals("../library", snapshot.packages.getValue("library").location)
        // The one resource pack takes the package's files from the package, into its namespace.
        val layout = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(80, 0), "Basic") { null }
        assertEquals(
            PackLayout.Entry.Copy("library:resource_packs/gems/textures/item/gem.png"),
            layout["assets/library/textures/gems/item/gem.png"]
        )
        assertTrue("assets/library/items/gems/gem.json" in layout)
    }

    @Test
    fun aProjectNamesOnlyWhatAPackageExports() {
        val showcase = loadTestProject("examples", "showcase").references
        assertNull(showcase.check(dev.netherforge.format.ref.RefKind.ITEM, "library:gem"))
        val packPrivate = showcase.check(dev.netherforge.format.ref.RefKind.ITEM_MODEL, "library:gems/gem")!!
        assertEquals(ProblemCodes.REFERENCE_NOT_EXPORTED, packPrivate.code)
        assertEquals(listOf("library"), showcase.packages)
    }

    /** What scripts may name, for every kind of resource, in the words files get. */
    @Test
    fun whatAPackageMayNameOfAnothersIsWhatItExports() {
        val snapshot = loadTestProject("examples", "showcase")
        val item = ItemKind
        assertNull(snapshot.nameable("showcase", item, ResourceKey("library", "gem")))
        assertNull(snapshot.nameable("library", item, ResourceKey("library", "gem")))
        // The library's own module, which it doesn't export.
        val phrases = snapshot.nameable("showcase", ModuleKind, ResourceKey("library", "phrases"))!!
        assertEquals(ProblemCodes.REFERENCE_NOT_EXPORTED, phrases.code)
        assertEquals(
            "Package \"library\" doesn't export its module \"phrases\", so only it can use it (its netherforge.json's exports)",
            phrases.message
        )
        // A package doesn't depend on the project that uses it.
        val project = snapshot.nameable("library", item, ResourceKey("showcase", "ruby"))!!
        assertEquals(ProblemCodes.REFERENCE_NAMESPACE, project.code)
        // One that isn't there is the caller's to say.
        assertNull(snapshot.nameable("showcase", item, ResourceKey("library", "nothing")))
        assertEquals(snapshot.references, snapshot.referencesOf("showcase"))
        assertEquals("library", snapshot.referencesOf("library")!!.home)
        assertNull(snapshot.referencesOf("nobody"))
    }

    @Test
    fun glyphTagsAreRewrittenInPlace() {
        val text = "<gold><glyph:ui/coin> 2 \\<glyph:ui/coin> <glyph:gems/gem>"
        assertEquals(
            "<gold><glyph:showcase:ui/coin> 2 \\<glyph:ui/coin> ",
            GlyphTags.rewrite(text) { if (it.reference == "ui/coin") "showcase:ui/coin" else "" }
        )
        assertNull(GlyphTags.rewrite(text) { null })
        assertNull(GlyphTags.rewrite("no tags") { "x" })
    }

    @Test
    fun aCopiedResourceKeepsNamingThePackagesThings() {
        val text = TestFiles.read("examples/library/items/gem/item.json")!!
        val moved = ReferenceIndex.move(
            ItemKind,
            "items/shiny/item.json",
            text,
            "library",
            "showcase",
            RefTarget.Resource("item", "gem"),
            "shiny"
        )!!
        val file = CanonicalJson.json.decodeFromString(ItemFile.serializer(), moved)
        assertEquals("library:gems/gem", file.itemModel?.text)
        assertEquals(ItemKind.write(file), moved)
    }

    /** A bundle's packages are found by namespace, and it has no lock to check. */
    @Test
    fun aBundleOpensPackagesByNamespace() {
        val lib = MapProjectSource(TestFiles.list("examples/library").associateWith { TestFiles.read("examples/library/$it") })
        val bundle = object : PackageSources {
            override fun open(namespace: String, request: PackageRequest) = if (namespace ==
                "library"
            ) {
                OpenedPackage(lib)
            } else {
                PackageMissing()
            }
            override val checksLock get() = false
        }
        val app = MapProjectSource(mapOf("netherforge.json" to testManifest(""", "dependencies": { "library": { "path": "anywhere" } }""")))
        val snapshot = Projects.load(app, packages = bundle)
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(setOf("library"), snapshot.packages.keys)
    }

    @Test
    fun gitUrlsAndRevsAreOnlyWhatGitCanBeTrustedWith() {
        for (good in listOf(
            "https://github.com/acme/economy.git",
            "ssh://git@host/acme/x",
            "git@github.com:acme/economy.git",
            "file:///tmp/x.git"
        )) {
            assertTrue(Packages.isGitUrl(good), good)
        }
        for (bad in listOf(
            "http://host/x",
            "git://host/x",
            "ext::sh -c touch% /tmp/pwned",
            "-uhttps://x",
            "https://",
            "../economy",
            "host:x",
            "https://a b"
        )) {
            assertFalse(Packages.isGitUrl(bad), bad)
        }
        for (good in listOf("main", "v1.2.0", "release/1.x", "a".repeat(40))) assertTrue(Packages.isGitRev(good), good)
        for (bad in listOf("-x", "a..b", "a:b", "a//b", "a/", "a.lock", ".hidden", "a/.b", "HEAD@{1}", "a b", "")) {
            assertFalse(Packages.isGitRev(bad), bad)
        }
        assertEquals("a".repeat(40), Packages.commitOf("git:" + "a".repeat(40)))
        assertNull(Packages.commitOf("git:abc"))
        assertNull(Packages.commitOf("../library"))
        assertEquals("git:" + "b".repeat(64), Packages.gitLocation("b".repeat(64)))
    }

    /**
     * Without a lock, a git dependency resolves to whatever its `rev` is now,
     * and the lock records that commit; with one, it's that commit until the
     * `rev` (or the URL) changes. Whatever happens, the commit a host opened
     * is what's locked.
     */
    @Test
    fun aGitDependencyIsPinnedByTheLock() {
        val base = "packages/format/testdata/packages/git"
        val manifest = """, "dependencies": { "lib": { "git": "https://example.com/lib.git", "rev": "v1" } }"""
        val asked = mutableListOf<PackageRequest>()
        val sources = object : PackageSources {
            val inner = TestPackages(base, "app")
            override fun open(namespace: String, request: PackageRequest) = inner.open(namespace, request).also { asked += request }
        }
        val fresh = Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest(manifest))), packages = sources)
        assertEquals(PackageRequest.Git("https://example.com/lib.git", "v1", null), asked.single())
        val lib = fresh.packages.getValue("lib")
        assertEquals("git:" + "b".repeat(40), lib.location)
        assertEquals("2.0.0", lib.manifest?.version)
        val lock = fresh.lock()!!
        assertEquals(
            dev.netherforge.format.project.PackageSource.Git("https://example.com/lib.git", "v1", "b".repeat(40)),
            lock.packages.getValue("lib").source
        )
        assertEquals(listOf("lock.missing"), fresh.problems.map { it.code })

        // The lock as it would be written pins b, even after v1 moves on; a lock for another rev doesn't.
        val written = dev.netherforge.format.project.LockKind.write(lock)
        asked.clear()
        val pinned = Projects.load(
            MapProjectSource(mapOf("netherforge.json" to testManifest(manifest), "netherforge.lock" to written)),
            packages = sources
        )
        assertEquals(PackageRequest.Git("https://example.com/lib.git", "v1", "b".repeat(40)), asked.single())
        assertEquals(emptyList(), pinned.problems)
        assertEquals(written, pinned.lock()?.let(dev.netherforge.format.project.LockKind::write))
        asked.clear()
        val moved = manifest.replace("\"v1\"", "\"main\"")
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest(moved), "netherforge.lock" to written)), packages = sources)
        assertEquals(PackageRequest.Git("https://example.com/lib.git", "main", null), asked.single())
    }

    /** A git package that isn't what the lock pinned isn't loaded, and the lock isn't written over. */
    @Test
    fun aGitPackageThatIsntWhatTheLockPinnedIsntLoaded() {
        val snapshot = loadTestProject("packages/format/testdata/packages/git-hash", "app")
        assertEquals(listOf("package.hash"), snapshot.problems.map { it.code })
        assertTrue(snapshot.packages.isEmpty())
        assertNull(snapshot.lock())
    }

    /** Two requirers of one namespace from git agree when they name the same repository and rev. */
    @Test
    fun gitDependenciesConflictUnlessTheyAgree() {
        val repo = "https://example.com/lib.git"
        val lib = MapProjectSource(mapOf("netherforge.json" to testManifest().replace("\"test\"", "\"lib\"")))
        fun mid(rev: String) = MapProjectSource(
            mapOf(
                "netherforge.json" to
                    testManifest(""", "dependencies": { "lib": { "git": "$repo", "rev": "$rev" } }""").replace("\"test\"", "\"mid\"")
            )
        )
        fun load(rev: String) = Projects.load(
            MapProjectSource(
                mapOf(
                    "netherforge.json" to testManifest(
                        """, "dependencies": { "lib": { "git": "$repo", "rev": "v1" }, "mid": { "path": "../mid" } }"""
                    )
                )
            ),
            packages = object : PackageSources {
                override fun open(namespace: String, request: PackageRequest) = when (request) {
                    is PackageRequest.Folder -> OpenedPackage(mid(rev))
                    is PackageRequest.Git -> OpenedPackage(lib, "sha256:1", "c".repeat(40))
                }
            }
        ).problems.map { it.code }
        assertEquals(listOf("lock.missing"), load("v1"))
        assertEquals(listOf("package.conflict", "lock.missing"), load("v2"))
    }
}
