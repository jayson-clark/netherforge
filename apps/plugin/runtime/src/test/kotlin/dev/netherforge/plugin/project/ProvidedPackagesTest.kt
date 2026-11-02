package dev.netherforge.plugin.project

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.project.BundleKind
import dev.netherforge.format.project.BundleManifest
import dev.netherforge.format.project.BundledPackage
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.plugin.TestServer
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Packages the server provides (`packages.provided`): loaded in place of the project's copy, held to its range, and never carried by a bundle. */
class ProvidedPackagesTest {
    private fun manifest(namespace: String, version: String = "1.0.0", more: String = "") =
        """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "$namespace", "namespace": "$namespace", "version": "$version", "minecraft": "26.3"$more }"""

    /** A `bridge` package whose one exported module says whose copy it is. */
    private fun bridge(who: String, version: String = "1.0.0") = mapOf(
        "netherforge.json" to manifest("bridge", version, """, "exports": { "modules": ["api"] }"""),
        "modules/api/init.lua" to """return { who = function() return "$who" end }"""
    )

    private fun project(range: String = "^1.0.0") = mapOf(
        "netherforge.json" to manifest(
            "test",
            more = """, "dependencies": { "bridge": { "path": "../bridge", "version": "$range", "provided": true } }"""
        ),
        "modules/main/init.lua" to """log(require("bridge:api").who())"""
    )

    /** The project beside its stand-in, and the server's own copy in `server/bridge`. */
    private fun tree(range: String = "^1.0.0", serverVersion: String = "1.4.0") =
        project(range) + bridge("stand-in").mapKeys { "../bridge/${it.key}" } +
            bridge("the server's", serverVersion).mapKeys { "../server/bridge/${it.key}" }

    @Test
    fun `a project depending on a provided package gets the server's copy, and the server says so`() {
        TestServer(tree(), resolvesPackages = false, provided = listOf("../server/bridge")).use { server ->
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            assertEquals(listOf("the server's"), server.logs)
            val grant = server.runtime.session.requirements.grants().single()
            assertTrue(grant.startsWith("package bridge 1.4.0 (from ") && "server/bridge" in grant, grant)
        }
        // The dev server resolves it as the editor does: the stand-in.
        TestServer(tree()).use { server -> assertEquals(listOf("stand-in"), server.logs) }
    }

    @Test
    fun `a provided package outside the range the project takes is refused, both versions named`() {
        TestServer(tree(range = "^2.0.0"), resolvesPackages = false, provided = listOf("../server/bridge")).use { server ->
            assertFalse(server.runtime.session.running)
            val problem = server.runtime.currentProblems().single { it.code == ProblemCodes.PACKAGE_VERSION.code }
            assertEquals("$.dependencies.bridge.version", problem.path)
            assertTrue(
                "^2.0.0" in problem.message && "1.4.0" in problem.message && "the server's, from" in problem.message,
                problem.message
            )
        }
    }

    @Test
    fun `a bundle built without the provided package runs the server's, and one carrying its own copy is refused`() {
        val files = project().mapKeys { "test/${it.key}" } + bridge("bundled").mapKeys { "bridge/${it.key}" } +
            bridge("the server's", "1.4.0").mapKeys { "../server/bridge/${it.key}" }
        TestServer(
            files,
            start = false,
            resolvesPackages = false,
            writeManifest = false,
            provided = listOf("../server/bridge")
        ).use { server ->
            fun bundle(vararg names: String, provided: List<String>? = null) {
                val hashes = names.associateWith { ProjectFiles.hash(DiskProjectSource(server.project.resolve(it))) }
                val manifest =
                    BundleManifest(
                        FormatVersion.CURRENT,
                        "test",
                        hashes.mapValues {
                            BundledPackage("1.0.0", it.value)
                        },
                        provided = provided
                    )
                server.project.resolve(BundleManifest.FILE_NAME).writeText(BundleKind.write(manifest))
            }
            bundle("test", provided = listOf("bridge"))
            server.start()
            assertTrue(server.runtime.session.running, "${server.runtime.currentProblems()}")
            assertEquals(listOf("the server's"), server.logs)

            // Built against its own copy: refused rather than silently overridden.
            bundle("test", "bridge")
            server.runtime.reloadAll()
            assertFalse(server.runtime.session.running)
            val problem = server.runtime.currentProblems().single { it.code == ProblemCodes.RUNTIME_PROVIDED_PACKAGE.code }
            assertEquals("$.packages.bridge", problem.path)
            assertTrue("--exclude bridge" in problem.message, problem.message)
        }
    }

    @Test
    fun `a bundle that leaves a package for a server that doesn't provide it says so`() {
        val files = project().mapKeys { "test/${it.key}" }
        TestServer(files, start = false, resolvesPackages = false, writeManifest = false).use { server ->
            val hash = ProjectFiles.hash(DiskProjectSource(server.project.resolve("test")))
            val manifest =
                BundleManifest(FormatVersion.CURRENT, "test", mapOf("test" to BundledPackage("1.0.0", hash)), provided = listOf("bridge"))
            server.project.resolve(BundleManifest.FILE_NAME).writeText(BundleKind.write(manifest))
            server.start()
            assertFalse(server.runtime.session.running)
            val problem = server.runtime.currentProblems().single { it.code == ProblemCodes.PACKAGE_MISSING.code }
            assertTrue("for the server to provide" in problem.message && "packages.provided" in problem.message, problem.message)
        }
    }

    @Test
    fun `a provided folder that isn't a package refuses the project, naming it`() {
        TestServer(
            project() + bridge("stand-in").mapKeys {
                "../bridge/${it.key}"
            },
            resolvesPackages = false,
            provided = listOf("../nowhere")
        )
            .use { server ->
                assertFalse(server.runtime.session.running)
                val problem = server.runtime.currentProblems().single { it.code == ProblemCodes.RUNTIME_PROVIDED_PACKAGE.code }
                assertTrue("nowhere" in problem.message, problem.message)
            }
    }
}
