package dev.netherforge.plugin.project
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.BundleKind
import dev.netherforge.format.project.BundleManifest
import dev.netherforge.format.project.BundledPackage
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.LockFile
import dev.netherforge.format.project.LockKind
import dev.netherforge.format.project.LockedPackage
import dev.netherforge.format.project.PackageSource
import dev.netherforge.format.project.Packages
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.data.ScriptData
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A project and the packages it depends on, run together: their resources, their modules, `require` across them, bundles. */
class PackagesTest {

    private fun manifest(namespace: String, more: String = "") =
        """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3"$more }"""

    /** `lib`, beside the project: an exported module and item, and a module of its own. */
    private val lib = mapOf(
        "netherforge.json" to manifest("lib", """, "exports": { "items": ["coin"], "modules": ["api"] }"""),
        "modules/api/init.lua" to """
            -- A bare name is the package's own module.
            local secret = require("secret")
            local api = {}
            function api.greet(name) return secret.word .. ", " .. name end
            return api
        """,
        "modules/secret/init.lua" to """
            log("secret loaded")
            return { word = "Hail" }
        """,
        "items/coin/item.json" to """{ "kind": "minecraft:paper", "name": "Coin", "script": { "file": "script.lua" } }""",
        "items/coin/script.lua" to """log("coin script runs")"""
    )

    private val project = mapOf(
        "netherforge.json" to manifest("test", """, "dependencies": { "lib": { "path": "../lib" } }"""),
        "modules/main/init.lua" to """
            local api = require("lib:api")
            log(api.greet("Alex"))
            local _, private = pcall(require, "lib:secret")
            log(string.find(tostring(private), "doesn't export its module \"secret\"", 1, true) and "private" or tostring(private))
            local _, stranger = pcall(require, "elsewhere:api")
            log(string.find(tostring(stranger), "\"elsewhere\" is neither", 1, true) and "stranger" or tostring(stranger))
            local coin = nf.items.create("lib:coin")
            log(coin.item .. " " .. coin.name)
        """
    )

    private fun tree(): Map<String, Any> = project + lib.mapKeys { "../lib/${it.key}" }

    @Test
    fun `a package's resources run beside the project's and its exported modules are required by namespace`() {
        TestServer(tree()).use { server ->
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            assertEquals(emptyList(), server.errors.map { it.message } + server.runtime.currentProblems().map { it.toString() })
            assertEquals(listOf("secret loaded", "Hail, Alex", "private", "stranger", "lib:coin Coin", "coin script runs"), server.logs)
            assertEquals(listOf("lib:api", "lib:secret", "main"), server.runtime.session.modules.ids())
            assertTrue(server.runtime.session.items.has("lib:coin"))
        }
    }

    @Test
    fun `a function that needs something declared holds each package to its own manifest`() {
        val files = tree() + mapOf(
            // The project declares moderation; the package it depends on doesn't.
            "netherforge.json" to manifest(
                "test",
                """, "dependencies": { "lib": { "path": "../lib" } }, "requires": { "moderation": true }"""
            ),
            "modules/main/init.lua" to """
                local api = require("lib:api")
                nf.server.set_motd("from the project")
                log(nf.server.motd())
                log(select(2, pcall(api.motd)))
            """,
            "../lib/modules/api/init.lua" to """
                local api = {}
                function api.motd() nf.server.set_motd("from the package") end
                return api
            """
        )
        TestServer(files).use { server ->
            assertEquals(
                listOf(
                    "from the project",
                    "nf.server.set_motd needs moderation, which package \"lib\" hasn't declared: " +
                        "add \"requires\": { \"moderation\": true } to its netherforge.json"
                ),
                // Without what the package's own modules and items log as they start.
                server.logs.filter { "motd" in it || "from" in it }
            )
        }
    }

    @Test
    fun `a bare id a package's script passes the API names the package's own, and its data tables are its own`() {
        val files = tree() + mapOf(
            // The project has a coin of its own: the package's bare "coin" mustn't reach it.
            "items/coin/item.json" to """{ "kind": "minecraft:stick", "name": "Stick coin" }""",
            "modules/main/init.lua" to """
                local api = require("lib:api")
                nf.data("shared").who = "project"
                log("project", nf.items.create("coin").name, api.coin().name, nf.items.id({ item = "coin" }), api.id())
                log("project data", nf.data("shared").who, api.data())
            """,
            "../lib/modules/api/init.lua" to """
                local api = {}
                -- Called from the project's script, still resolved in the library: the code is the library's.
                function api.coin() return nf.items.create("coin") end
                -- An item table's project item, too.
                function api.id() return nf.items.id({ item = "coin" }) end
                nf.data("shared").who = "library"
                function api.data() return nf.data("shared").who end
                return api
            """
        )
        TestServer(files).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(
                listOf("project\tStick coin\tCoin\tcoin\tcoin", "project data\tproject\tlibrary"),
                server.logs.filter { it.startsWith("project") }
            )
            server.runtime.saveData()
            assertEquals("""{"who":"library"}""", server.runtime.store.tables.read(ScriptData.Owner.Named("lib", "shared")))
            assertEquals("""{"who":"project"}""", server.runtime.store.tables.read(ScriptData.Owner.Named("test", "shared")))
        }
    }

    @Test
    fun `a package's centity runs its own script, which spawns and finds the package's centities by their bare names`() {
        val files = tree() + mapOf(
            "../lib/netherforge.json" to manifest("lib", """, "exports": { "centities": ["board"], "modules": ["api"] }"""),
            "../lib/centities/board/centity.json" to TestServer.scriptedCentity(),
            "../lib/centities/board/script.lua" to """
                log("board script", this:kind())
                -- Not exported: only the package's own code can spawn it.
                nf.centities.spawn("piece", this:position(), { temporary = true })
            """,
            "../lib/centities/piece/centity.json" to TestServer.scriptedCentity(),
            "../lib/centities/piece/script.lua" to """log("piece script", this:kind())""",
            "../lib/modules/api/init.lua" to """
                local api = {}
                function api.spawn(at) return nf.centities.spawn("board", at) end
                function api.boards() return #nf.centities.all({ kind = "board" }) end
                return api
            """,
            "modules/main/init.lua" to """
                local api = require("lib:api")
                local board = api.spawn(vec3(0.5, 64, 0.5))
                log("project", board:kind(), api.boards(), #nf.centities.all({ kind = "lib:board" }), #nf.centities.all())
            """
        )
        TestServer(files).use { server ->
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(
                listOf("board script\tboard", "piece script\tpiece", "project\tlib:board\t1\t1\t2"),
                // Without what the package's other modules and items log as they start.
                server.logs.filter { "script\t" in it || it.startsWith("project") }
            )
            assertEquals(listOf("lib:board", "lib:piece"), server.runtime.session.centities.all().map { it.centity })
        }
    }

    @Test
    fun `an error in a package's script is reported at its package path`() {
        val files = tree() + ("../lib/modules/secret/init.lua" to "error(\"boom\")")
        TestServer(files).use { server ->
            val error = server.errors.first()
            assertEquals("lib:modules/secret/init.lua", error.source?.file)
            assertEquals(1, error.source?.line)
        }
    }

    @Test
    fun `a production server refuses a project with dependencies that isn't a bundle`() {
        TestServer(tree(), resolvesPackages = false).use { server ->
            assertFalse(server.runtime.session.running)
            assertEquals(
                listOf(ProblemCodes.RUNTIME_UNBUNDLED.code),
                server.runtime.currentProblems().filter {
                    it.code?.startsWith("runtime.") ==
                        true
                }.map { it.code }
            )
            assertEquals(emptyList(), server.logs)
        }
    }

    @Test
    fun `a bundle runs as it was built, and not once it's changed`() {
        val files = project.mapKeys { "test/${it.key}" } + lib.mapKeys { "lib/${it.key}" }
        TestServer(files, start = false, resolvesPackages = false, writeManifest = false).use { server ->
            val hashes = listOf("test", "lib").associateWith { ProjectFiles.hash(DiskProjectSource(server.project.resolve(it))) }
            val bundle = BundleManifest(FormatVersion.CURRENT, "test", hashes.mapValues { BundledPackage("1.0.0", it.value) })
            server.project.resolve(BundleManifest.FILE_NAME).writeText(BundleKind.write(bundle))
            server.start()
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            assertEquals(listOf("secret loaded", "Hail, Alex", "private", "stranger", "lib:coin Coin", "coin script runs"), server.logs)

            // Someone edits the package on the server: the bundle isn't what was built, so nothing of it runs.
            server.write("lib/modules/secret/init.lua", "return { word = \"Hi\" }")
            server.runtime.reloadAll()
            assertFalse(server.runtime.session.running)
            val problem = server.runtime.currentProblems().single { it.code == ProblemCodes.RUNTIME_BUNDLE_HASH.code }
            assertEquals("$.packages.lib", problem.path)
        }
    }

    /**
     * A git package runs from the editor's package cache at the commit the
     * lock pins, hashed as it's loaded: a changed checkout isn't run, and a
     * dependency the lock doesn't pin yet isn't fetched by the server.
     */
    @Test
    fun `a git package runs from the cache at the commit the lock pins, and not once it's changed`() {
        val commit = "c".repeat(40)
        val cache = createTempDirectory("netherforge-packages")
        val checkout = cache.resolve(Packages.gitCheckout(commit))
        for ((path, text) in lib) checkout.resolve(path).also { it.parent.createDirectories() }.writeText(text)
        val url = "https://example.com/lib.git"
        val lock = LockFile(
            LockFile.SCHEMA,
            mapOf("lib" to LockedPackage("1.0.0", PackageSource.Git(url, "v1", commit), ProjectFiles.hash(DiskProjectSource(checkout))))
        )
        val files = project + mapOf(
            "netherforge.json" to manifest("test", """, "dependencies": { "lib": { "git": "$url", "rev": "v1" } }"""),
            "netherforge.lock" to LockKind.write(lock)
        )
        TestServer(files, packageCache = cache).use { server ->
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            assertEquals(listOf("secret loaded", "Hail, Alex", "private", "stranger", "lib:coin Coin", "coin script runs"), server.logs)
            assertEquals("git:$commit", server.runtime.session.snapshot.packages.getValue("lib").location)

            checkout.resolve("modules/secret/init.lua").writeText("return { word = \"Hi\" }")
            server.runtime.reloadAll()
            val packageProblems = server.runtime.currentProblems().filter { it.code?.startsWith("package.") == true }
            assertEquals(listOf(ProblemCodes.PACKAGE_HASH.code), packageProblems.map { it.code })
        }
        TestServer(files - "netherforge.lock", packageCache = cache).use { server ->
            val problem = server.runtime.currentProblems().single { it.code == ProblemCodes.PACKAGE_GIT.code }
            assertTrue("never fetches" in problem.message, problem.message)
        }
    }

    @Test
    fun `a rewritten lock restarts everything, as the manifest does`() {
        TestServer(tree()).use { server ->
            val result = server.reload("netherforge.lock")
            // The whole package: no kind, no id.
            assertEquals(listOf(Triple("test", null, null)), result.resources.map { Triple(it.pkg, it.kind, it.id) })
        }
    }
}
