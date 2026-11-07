package dev.netherforge.plugin.testrunner

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """Usage: java -jar NetherForgeTest.jar <project> [--json] [--junit <file>] [--filter <text>] [--packages <dir>] --game-data <file>

Runs the project's *_test.lua files on a fake server.
  --json            print one JSON event per line (what the editor reads) instead of text
  --junit <file>    also write the results as JUnit XML
  --filter <text>   only the tests whose "file: name" contains the text (case-insensitive)
  --packages <dir>  the editor's package cache, for a project with git dependencies
  --game-data <file> the game data of the project's Minecraft version: the dev server's export, which the editor
                    caches at <data>/minecraft/<version>/server/game-data.json (`netherforge test` finds it itself)

Exit code: 0 when every test passed, 1 when one failed or errored, 2 when the tests could not run."""

/** The arguments of a run. */
internal class Arguments(
    val project: Path,
    val json: Boolean,
    val junit: Path?,
    val filter: String?,
    val packages: Path?,
    val gameData: Path
) {
    companion object {
        /** Reads [args], or null (after saying why) when they're not usable. */
        fun parse(args: List<String>, err: (String) -> Unit): Arguments? {
            var project: String? = null
            var json = false
            var junit: String? = null
            var filter: String? = null
            var packages: String? = null
            var gameData: String? = null
            val rest = args.iterator()
            while (rest.hasNext()) {
                val arg = rest.next()
                fun value(): String? = if (rest.hasNext()) rest.next() else null.also { err("$arg needs a value") }
                when {
                    arg == "--json" -> json = true
                    arg == "--junit" -> junit = value() ?: return null
                    arg == "--filter" -> filter = value() ?: return null
                    arg == "--packages" -> packages = value() ?: return null
                    arg == "--game-data" -> gameData = value() ?: return null
                    arg.startsWith("--") -> return null.also { err("unknown option $arg") }
                    project == null -> project = arg
                    else -> return null.also { err("unexpected argument $arg") }
                }
            }
            return Arguments(
                Path.of(project ?: return null.also { err("no project given") }).toAbsolutePath().normalize(),
                json,
                junit?.let { Path.of(it) },
                filter,
                packages?.let { Path.of(it).toAbsolutePath().normalize() },
                Path.of(
                    gameData
                        ?: return null.also { err("no --game-data given: a project is tested on the game data of its Minecraft version") }
                )
                    .toAbsolutePath().normalize()
            )
        }
    }
}

/** Runs the arguments' tests, printing to [out]; the process's exit code. */
internal fun run(args: Arguments, out: (String) -> Unit): Int = try {
    ScriptTestRunner(args.project, GameDataFile.read(args.gameData), args.packages).run(args.filter, args.json, args.junit, out)
} catch (e: ScriptTestRunner.Refused) {
    val message = e.message.orEmpty()
    out(
        if (args.json) {
            buildJsonObject {
                put("event", "error")
                put("message", message)
                putJsonArray("problems") {}
            }.toString()
        } else {
            "Can't run the project's tests: $message"
        }
    )
    2
}

fun main(args: Array<String>) {
    val arguments = Arguments.parse(args.toList()) { System.err.println(it) }
    if (arguments == null) {
        System.err.println(USAGE)
        exitProcess(2)
    }
    val code = run(arguments) { println(it) }
    System.out.flush()
    // The runtime's threads can outlive main: the exit code is the result.
    exitProcess(code)
}
