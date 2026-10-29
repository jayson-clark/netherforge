package dev.netherforge.plugin.testrunner

import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeStream
import dev.netherforge.format.bridge.ConsoleEntry
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.RuntimeConfig
import dev.netherforge.plugin.bridge.BridgeOutput
import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.lua.ScriptFailure
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.script.PerformanceConfig
import dev.netherforge.plugin.testing.TestHarness
import dev.netherforge.plugin.testing.TestOutcome
import dev.netherforge.plugin.testkit.FakeItemViews
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

/**
 * Runs a project's script tests: each `*_test.lua` file declares its tests with `nf.test.case`, and
 * **each test runs on a server of its own**: a fresh [FakePlatform] and a runtime that loads the
 * project from its files (on the real game data of its Minecraft version), scripts started as on a real server, so nothing a test does (a spawned
 * centity, a timer, a joined player, saved data) is there for the next. The file's own code runs
 * once per test, before it.
 *
 * What a test passes or fails on:
 *  - the test's own code raising an error (a failed `assert`): [Status.FAILED], at its line;
 *  - a script of the project erroring while the test ran (or starting): [Status.ERRORED], at the
 *    script's line, since the test can't have meant that;
 *  - the project not loading at all: a [RunFailed]; a test file that doesn't run to its end: an
 *    [Status.ERRORED] result for the file.
 */
class ScriptTestRunner(
    private val project: Path,
    /** The game the project runs on: the real data of its target Minecraft version (see [GameDataFile]), which the fake server answers every id question from. */
    private val game: GameDataBundle,
    /** The editor's package cache, where git packages' checkouts are, when the project has any. */
    private val packageCache: Path? = null,
    /**
     * What the sandbox holds a call into a script to: a test's call includes every tick it advances, so the time
     * limit is generous. The per-tick time budget is off (`maxTickMillis = 0`): it defers a scope's handlers by
     * wall-clock time, which would make a deterministic test's outcome depend on how fast the machine is.
     */
    private val sandbox: SandboxLimits = SandboxLimits(deadlineMillis = 120_000, memoryMegabytes = 1024, maxTickMillis = 0.0)
) {
    /** A project the runtime refused or couldn't load, so no test could run. */
    class Refused(message: String, val problems: List<String> = emptyList()) : RuntimeException(message)

    private val minecraft: String = readMinecraft().also {
        if (game.minecraft != it) throw Refused("the game data is of Minecraft ${game.minecraft}, but the project targets $it")
    }

    /**
     * Runs every test whose "file: name" contains [filter] (all of them without one), reporting each
     * [event][TestEvent] to [report] as it happens. Returns the totals, which [RunFinished] reported.
     * Throws [Refused] when the project can't run at all.
     */
    fun run(filter: String? = null, report: (TestEvent) -> Unit): RunFinished {
        val started = System.nanoTime()
        report(RunStarted(project.toString(), VERSION))
        var passed = 0
        var failed = 0
        var errored = 0
        for (file in testFiles()) {
            runFile(file, filter, report) { result ->
                when (result.status) {
                    Status.PASSED -> passed++
                    Status.FAILED -> failed++
                    Status.ERRORED -> errored++
                }
                report(result)
            }
        }
        return RunFinished(passed, failed, errored, millis(started)).also(report)
    }

    // ---- files and tests ----------------------------------------------------------

    /** The project's own `*_test.lua` files: what a probe server finds. */
    private fun testFiles(): List<String> = Server().use { it.start().session.tests.files() }

    private fun wanted(file: String, name: String, filter: String?) = filter == null || "$file: $name".contains(filter, ignoreCase = true)

    private fun runFile(file: String, filter: String?, report: (TestEvent) -> Unit, count: (TestResult) -> Unit) {
        var server = Server()
        try {
            server.start()
            val loaded = server.load(file)
            val failure = loaded.failure
            if (loaded.names.none { wanted(file, it, filter) } && failure == null) return
            report(FileLoaded(file, loaded.names, failure?.message, failure?.let(::placeOf)))
            if (failure != null) {
                val result =
                    TestResult(
                        file,
                        "(running $file)",
                        Status.ERRORED,
                        0,
                        failure.message,
                        placeOf(failure),
                        trimmed(failure.traceback),
                        server.logs()
                    )
                count(result)
                return
            }
            var first = true
            for (name in loaded.names) {
                if (!wanted(file, name, filter)) continue
                // The first test runs on the server that found the names; each later one gets a fresh one.
                if (!first) {
                    server.close()
                    server = Server()
                    server.start()
                    server.load(file)
                }
                first = false
                count(server.runTest(file, name))
            }
        } finally {
            server.close()
        }
    }

    // ---- one server ---------------------------------------------------------------

    /** A fake server running the project, for one test. */
    private inner class Server : AutoCloseable {
        private val scratch = createTempDirectory("netherforge-test")
        private val platform = FakePlatform(listOf(minecraft), game)
        private val diagnostics = java.util.Collections.synchronizedList(mutableListOf<ConsoleEntry>())
        private var runtime: NetherForgeRuntime? = null

        /** What scripts did wrong while the project started, before any test. */
        private var startErrors: List<ScriptError> = emptyList()

        private val harness = object : TestHarness {
            override fun advance(ticks: Int) {
                repeat(ticks) {
                    val runtime = checkNotNull(runtime)
                    // What scripts started off the main thread is done before the tick, so a test sees it land at a known tick.
                    check(runtime.settled(IDLE_MILLIS)) { "the runtime's workers or WebAssembly were still busy after ${IDLE_MILLIS}ms" }
                    runtime.tick()
                    platform.scheduler.runPending()
                    // The server ticks the world (entities, their AI included) after plugins.
                    platform.tickWorld()
                }
            }

            override fun join(name: String): PlayerRef {
                platform.players.find(name)?.let { return it }
                val firstJoin = platform.players.known(name) == null
                val player = platform.players.add(name)
                platform.raise.playerJoin(GameEvent.PlayerJoin(player.ref, firstJoin, "$name joined the game"))
                return player.ref
            }

            override fun shown(player: UUID, slot: Int?, menu: Boolean): ItemData? {
                val place = when {
                    slot == null -> FakeItemViews.Place.Cursor
                    menu -> FakeItemViews.Place.Menu(platform.menus.viewing(player) ?: return null, slot)
                    else -> FakeItemViews.Place.Inventory(slot)
                }
                return platform.itemViews.shown(player, place)
            }
        }

        fun start(): NetherForgeRuntime {
            val state = scratch.resolve("server").createDirectories()
            // Before the worlds load, the adapter gives the server the start-up datapack.
            platform.datapacks.bootstrap(project, true)
            val runtime = NetherForgeRuntime(
                platform,
                RuntimeConfig(
                    project,
                    state.resolve("data").createDirectories(),
                    state,
                    sandbox = sandbox,
                    // A slow-script warning is the wall clock's, not the test's: ticks run back to back on a fake
                    // server, cold, so a busy machine would turn it into an error the test never meant.
                    performance = PerformanceConfig(warnMillis = 0.0),
                    packageCache = packageCache,
                    testing = harness
                )
            )
            runtime.log.bridge = object : BridgeOutput {
                override fun console(entry: ConsoleEntry) {
                    diagnostics += entry
                }

                override fun <T> stream(stream: BridgeStream<T>, item: T) {}

                override fun <P> notify(event: BridgeEvent<P>, params: P) {}
            }
            this.runtime = runtime
            runtime.enable()
            val session = runtime.session
            if (!session.running) {
                val problems = session.problems().map {
                    "${it.file}${it.line?.let { line -> ":$line" }.orEmpty()}: ${it.severity.name.lowercase()}: ${it.message}"
                }
                throw Refused("the project can't run: ${problems.firstOrNull() ?: "it was refused"}", problems)
            }
            startErrors = diagnostics.filterIsInstance<ScriptError>()
            diagnostics.clear()
            return runtime
        }

        fun load(file: String) = checkNotNull(runtime).session.tests.load(file)

        fun logs(): List<String> = diagnostics.filterIsInstance<Log>().mapNotNull { entry ->
            entry.source?.let { "${it.file}${it.line?.let { line -> ":$line" }.orEmpty()}: ${entry.message}" }
        }

        fun runTest(file: String, name: String): TestResult {
            val started = System.nanoTime()
            val outcome = checkNotNull(runtime).session.tests.run(name)
            val errors = startErrors + diagnostics.filterIsInstance<ScriptError>()
            val logs = logs()
            return when {
                outcome is TestOutcome.Failed ->
                    TestResult(
                        file,
                        name,
                        Status.FAILED,
                        millis(started),
                        outcome.failure.message,
                        placeOf(outcome.failure),
                        trimmed(outcome.failure.traceback),
                        logs
                    )
                errors.isNotEmpty() -> {
                    val first = errors.first()
                    val during = if (first in startErrors) "while the project started" else "while the test ran"
                    val more = if (errors.size > 1) " (and ${errors.size - 1} more)" else ""
                    val place = first.source?.let { Place(it.file, it.line) }
                    TestResult(
                        file,
                        name,
                        Status.ERRORED,
                        millis(started),
                        "a script failed $during: ${first.message}$more",
                        place,
                        trimmed(first.traceback),
                        logs
                    )
                }
                else -> TestResult(file, name, Status.PASSED, millis(started), logs = logs)
            }
        }

        override fun close() {
            runtime?.let {
                try {
                    it.disable()
                } catch (e: Exception) {
                    // A server that stops badly has nothing to add to a result already known.
                }
            }
            runtime = null
            scratch.toFile().deleteRecursively()
        }
    }

    /** A traceback without the runtime's own frames (`nf:guard:483`, the `xpcall` that runs a call), which say nothing about the script. */
    private fun trimmed(traceback: String?): String? =
        traceback?.lines()?.filterNot { it.startsWith("\tnf:") || it.contains("in function 'xpcall'") }?.joinToString("\n")

    private fun placeOf(failure: ScriptFailure) = failure.file?.let { Place(it, failure.line) }

    /** The project's `minecraft`, which the fake server must be one of the versions it supports. */
    private fun readMinecraft(): String {
        val manifest = project.resolve(ProjectManifest.FILE_NAME)
        if (!Files.isRegularFile(manifest)) throw Refused("$project has no ${ProjectManifest.FILE_NAME}")
        return when (val parsed = ManifestKind.parse(manifest.readText(), ProjectManifest.FILE_NAME)) {
            is CanonicalJson.Parsed.Ok -> parsed.value.minecraft
            else -> throw Refused("${ProjectManifest.FILE_NAME} can't be read")
        }
    }

    private fun millis(since: Long) = (System.nanoTime() - since) / 1_000_000

    private companion object {
        const val IDLE_MILLIS = 10_000L
        val VERSION: String = ScriptTestRunner::class.java.`package`?.implementationVersion ?: "dev"
    }
}
