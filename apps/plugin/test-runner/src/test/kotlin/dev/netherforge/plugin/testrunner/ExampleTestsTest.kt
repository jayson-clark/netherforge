package dev.netherforge.plugin.testrunner

import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The examples run on the testkit's small game-data fixture (CI has no real game data, which only a live server exports),
 * so every block, item and entity type they name is in it.
 *
 * `examples/showcase`'s own tests (`tests/`) pass: the example (every booth of its expo, built on the fake server) is
 * exercised by the runner, and shows how a project tests itself. So do the project templates' (`examples/template_*`,
 * what the editor's "Add template" offers): each is a package whose tests use the newer APIs, so an API change that
 * breaks what users learn from breaks this. `examples/network`'s run on the runner's fake network, the other servers
 * played by `nf.test.network_*`.
 */
class ExampleTestsTest {
    private val repo = Path.of(System.getenv("NETHERFORGE_REPO") ?: error("NETHERFORGE_REPO isn't set; run through Gradle"))

    @Test
    fun `examples-showcase's tests pass`() {
        // The project and the package it depends on, side by side as `../library` finds it.
        assertTestsPass("showcase", listOf("showcase", "library"), minimum = 14)
    }

    @Test
    fun `examples-network's tests pass`() = assertTestsPass("network", minimum = 10)

    @Test
    fun `the minigame template's tests pass`() = assertTestsPass("template_minigame", minimum = 5)

    @Test
    fun `the shop template's tests pass`() = assertTestsPass("template_shop", minimum = 5)

    @Test
    fun `the rpg mob template's tests pass`() = assertTestsPass("template_rpg_mob", minimum = 5)

    /** Copies the examples [names] into a temp folder, side by side, and runs [project]'s tests: every one passes, and at least [minimum] ran. */
    private fun assertTestsPass(project: String, names: List<String> = listOf(project), minimum: Int) {
        val temp = Files.createTempDirectory("netherforge-example")
        try {
            for (name in names) {
                val from = repo.resolve("examples").resolve(name)
                Files.walk(from).use { paths ->
                    paths.filter { path -> from.relativize(path).none { it.toString() in SKIPPED } }.forEach { path ->
                        val target = temp.resolve(name).resolve(from.relativize(path).toString())
                        if (Files.isDirectory(path)) Files.createDirectories(target) else Files.copy(path, target)
                    }
                }
            }
            val results = mutableListOf<TestResult>()
            val end = ScriptTestRunner(temp.resolve(project), FakePlatform.GAME).run { if (it is TestResult) results += it }
            assertEquals(emptyList(), results.filter { it.status != Status.PASSED }.map { "${it.file}: ${it.name}: ${it.message}" })
            assertTrue(results.size >= minimum, "only ${results.size} tests ran")
            assertTrue(end.ok)
        } finally {
            temp.toFile().deleteRecursively()
        }
    }

    private companion object {
        /** What a project's own copy leaves out, as the editor's loader does. */
        val SKIPPED = setOf(".git", ".netherforge")
    }
}
