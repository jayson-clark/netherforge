package dev.netherforge.plugin.testrunner

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The built jar run the way `netherforge test` runs it (`java -jar`): the shadow jar carries the runtime, the fake platform, format,
 * Lua's natives and SQLite, with a manifest that allows native access, so what the unit tests can't see (a service file lost in the
 * merge, a missing driver) shows here. Also what the exit code and the output formats are.
 */
class RunnerJarTest {
    private val jar = Path.of(System.getProperty("netherforge.runner.jar") ?: error("netherforge.runner.jar isn't set; run through Gradle"))

    private fun project(test: String): Path {
        val root = Files.createTempDirectory("netherforge-jar")
        root.resolve("netherforge.json").writeText(
            """{ "${'$'}schema": ".netherforge/schema/netherforge.schema.json", "formatVersion": ${FormatVersion.CURRENT}, """ +
                """"name": "Jar", "namespace": "jar", "version": "1.0.0", "minecraft": "26.3" }""" + "\n"
        )
        root.resolve("tests").createDirectories().resolve("a_test.lua").writeText(test)
        return root
    }

    /** The fixture's game as the dev server's export file, which the runner is given with `--game-data`. */
    private fun gameData(root: Path, game: GameDataBundle = FakePlatform.GAME): String {
        val file = root.resolve("game-data.json")
        file.writeText(Bridge.json.encodeToString(GameDataBundle.serializer(), game))
        return file.toString()
    }

    private fun run(vararg args: String): Pair<Int, String> {
        val java = ProcessHandle.current().info().command().orElse("java")
        val process = ProcessBuilder(listOf(java, "-jar", jar.toString()) + args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertTrue(process.waitFor(5, TimeUnit.MINUTES), "the runner didn't finish")
        return process.exitValue() to output
    }

    @Test
    fun `a passing project exits 0 and prints a text report`() {
        val root = project("nf.test.case('works', function() nf.test.advance(2) end)")
        val (code, output) = run(root.toString(), "--game-data", gameData(root))
        assertEquals(0, code, output)
        assertTrue("PASS  works" in output && "1 passed, 0 failed" in output, output)
        assertTrue("WARNING" !in output && "SLF4J" !in output, output)
    }

    @Test
    fun `a failing test exits 1, with JSON lines and JUnit XML on request`() {
        val root = project("nf.test.case('breaks', function()\n  assert(false, 'nope')\nend)")
        val junit = root.resolve("out/junit.xml")
        val (code, output) = run(root.toString(), "--game-data", gameData(root), "--json", "--junit", junit.toString())
        assertEquals(1, code, output)
        val events = output.lines().filter { it.startsWith("{") }.map { EventJson.decodeFromString(TestEvent.serializer(), it) }
        val result = events.filterIsInstance<TestResult>().single()
        assertEquals(Status.FAILED, result.status)
        assertEquals(Place("tests/a_test.lua", 2), result.source)
        assertTrue("""<failure message="nope"""" in junit.readText(), junit.readText())
    }

    @Test
    fun `a project that can't run exits 2, and so do bad arguments`() {
        val root = project("nf.test.case('x', function() end)")
        root.resolve("netherforge.json").writeText("""{ "minecraft": "26.3" }""")
        val (code, output) = run(root.toString(), "--game-data", gameData(root))
        assertEquals(2, code, output)
        assertTrue("Can't run the project's tests" in output, output)
        assertEquals(2, run("--nope").first)
    }

    @Test
    fun `without usable game data the run is refused with how to get it`() {
        val root = project("nf.test.case('x', function() end)")
        val (missing, missingOutput) = run(root.toString())
        assertEquals(2, missing, missingOutput)
        assertTrue("--game-data" in missingOutput, missingOutput)

        val (absent, absentOutput) = run(root.toString(), "--game-data", root.resolve("nope.json").toString())
        assertEquals(2, absent, absentOutput)
        assertTrue("there is no game data" in absentOutput && "Start the dev server" in absentOutput, absentOutput)

        val another = gameData(root, FakePlatform.GAME.copy(schema = GameDataBundle.SCHEMA + 1))
        val (old, oldOutput) = run(root.toString(), "--game-data", another)
        assertEquals(2, old, oldOutput)
        assertTrue("schema" in oldOutput, oldOutput)
    }
}
