package dev.netherforge.plugin.testrunner

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.plugin.ffi.Native
import dev.netherforge.plugin.ffi.NativeTests
import dev.netherforge.plugin.ffi.RustRuntime
import dev.netherforge.plugin.ffi.TestServers
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.testkit.FakeItemViews
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText

/**
 * Runs a project's script tests: each `*_test.lua` file declares its tests with `nf.test.case`, and **each test runs on
 * a server of its own**: a fresh [FakePlatform] (on the real game data of the project's Minecraft version) and the
 * Rust runtime loading the project from its files, scripts started as on a real server, so nothing a test does (a
 * spawned centity, a timer, a joined player, saved data) is there for the next. The runner itself is the runtime's
 * (`crates/runtime/src/testing/runner.rs`: what passes, fails and errors, and the reports); this makes its servers.
 */
class ScriptTestRunner(
    private val project: Path,
    /** The game the project runs on: the real data of its target Minecraft version (see [GameDataFile]). */
    private val game: GameDataBundle,
    /** The editor's package cache, where git packages' checkouts are, when the project has any. */
    private val packageCache: Path? = null
) {
    /** A project the runner can't run at all, before any test: it says why. */
    class Refused(message: String) : RuntimeException(message)

    private val minecraft: String = readMinecraft().also {
        if (game.minecraft != it) throw Refused("the game data is of Minecraft ${game.minecraft}, but the project targets $it")
    }

    /**
     * Runs every test whose "file: name" contains [filter] (all of them without one), printing the report to [out]: one
     * JSON event a line with [json] (what the editor reads), else text; JUnit XML into [junit] too. The exit code: 0 when
     * every test passed, 1 when one failed or errored, 2 when the tests couldn't run.
     */
    fun run(filter: String? = null, json: Boolean = false, junit: Path? = null, out: (String) -> Unit): Int {
        val scratch = createTempDirectory("netherforge-test")
        Native.load(RustRuntime.library(nativeFolder()))
        try {
            return NativeTests.run(Servers(out), project, packageCache, filter, json, junit, scratch, VERSION)
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    /** The fake servers the runtime asks for, by id. */
    private inner class Servers(private val out: (String) -> Unit) : TestServers {
        private val servers = HashMap<Long, FakePlatform>()
        private var next = 1L

        override fun start(): Pair<Platform, Long> {
            val platform = FakePlatform(listOf(minecraft), game)
            // Before the worlds load, the adapter gives the server the start-up datapack.
            platform.datapacks.bootstrap(project, true)
            val id = next++
            servers[id] = platform
            return platform to id
        }

        override fun afterTick(id: Long) {
            val platform = servers.getValue(id)
            platform.scheduler.runPending()
            // The server ticks the world (entities, their AI included) after plugins.
            platform.tickWorld()
        }

        override fun join(id: Long, name: String): PlayerRef {
            val platform = servers.getValue(id)
            platform.players.find(name)?.let { return it }
            val firstJoin = platform.players.known(name) == null
            val player = platform.players.add(name)
            platform.raise.playerJoin(GameEvent.PlayerJoin(player.ref, firstJoin, "$name joined the game"))
            return player.ref
        }

        override fun shown(id: Long, player: UUID, slot: Int?, menu: Boolean): ItemData? {
            val platform = servers.getValue(id)
            val place = when {
                slot == null -> FakeItemViews.Place.Cursor
                menu -> FakeItemViews.Place.Menu(platform.menus.viewing(player) ?: return null, slot)
                else -> FakeItemViews.Place.Inventory(slot)
            }
            return platform.itemViews.shown(player, place)
        }

        override fun close(id: Long) {
            servers.remove(id)
        }

        override fun print(line: String) = out(line)
    }

    /** The project's `minecraft`, which the fake server must be one of the versions it supports. */
    private fun readMinecraft(): String {
        val manifest = project.resolve(ProjectManifest.FILE_NAME)
        if (!Files.isRegularFile(manifest)) throw Refused("$project has no ${ProjectManifest.FILE_NAME}")
        return when (val parsed = ManifestKind.parse(manifest.readText(), ProjectManifest.FILE_NAME)) {
            is CanonicalJson.Parsed.Ok -> parsed.value.minecraft
            else -> throw Refused("${ProjectManifest.FILE_NAME} can't be read")
        }
    }

    private companion object {
        val VERSION: String = ScriptTestRunner::class.java.`package`?.implementationVersion ?: "dev"

        /** Where the runtime's library is unpacked: kept between runs, under its hash. */
        fun nativeFolder(): Path = Path.of(System.getProperty("java.io.tmpdir")).resolve("netherforge-test-runner")
    }
}
