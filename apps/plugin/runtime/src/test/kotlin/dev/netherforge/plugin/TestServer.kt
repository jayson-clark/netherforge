package dev.netherforge.plugin

import dev.netherforge.format.bridge.BridgeEvent
import dev.netherforge.format.bridge.BridgeStream
import dev.netherforge.format.bridge.ConsoleEntry
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.ReloadResult
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.Packages
import dev.netherforge.format.text.DefaultFontFile
import dev.netherforge.plugin.binding.VersionGates
import dev.netherforge.plugin.bots.BotsConfig
import dev.netherforge.plugin.bridge.BridgeConfig
import dev.netherforge.plugin.bridge.BridgeOutput
import dev.netherforge.plugin.http.HttpConfig
import dev.netherforge.plugin.lua.SandboxLimits
import dev.netherforge.plugin.network.NetworkConfig
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.schedule.ScheduleConfig
import dev.netherforge.plugin.screen.ScreensConfig
import dev.netherforge.plugin.script.PerformanceConfig
import dev.netherforge.plugin.store.ConnectionConfig
import dev.netherforge.plugin.store.DatabasesConfig
import dev.netherforge.plugin.structure.StructuresConfig
import dev.netherforge.plugin.testkit.FakePlatform
import dev.netherforge.plugin.testkit.FakePlayer
import dev.netherforge.plugin.wasm.WasmConfig
import dev.netherforge.plugin.web.WebConfig
import java.nio.file.Files
import java.nio.file.Path
import javax.sql.DataSource
import kotlin.io.path.createDirectories
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * A project on disk and a runtime running it against a [FakePlatform]: the
 * shape every runtime test takes. Files are written into a temp directory,
 * so reload tests edit real files exactly as the editor would.
 */
class TestServer(
    /** Project path → contents: a String for text, a ByteArray for binary files like textures. */
    files: Map<String, Any>,
    val platform: FakePlatform = FakePlatform(),
    private val root: Path = createTempDirectory("netherforge-test"),
    start: Boolean = true,
    private val bridge: BridgeConfig? = null,
    private val performance: PerformanceConfig = PerformanceConfig(),
    /** What the Lua state times calls with, when not the real clock. */
    private val clock: (() -> Long)? = null,
    /** What `nf.schedule` and `nf.server.unix_time()` read the time from, when not the real clock. */
    private val wallClock: java.time.Clock? = null,
    /** `schedules.time-zone` in `config.yml`, when set. */
    private val scheduleZone: String? = null,
    /** What the sandbox holds calls in to, when not its defaults: a small memory limit, a short time limit. */
    private val sandbox: SandboxLimits = SandboxLimits(),
    /** What needs a newer Minecraft than the fake server's 26.3, when not the spec's own. */
    private val versionGates: VersionGates? = null,
    /** Whether dependencies are found where netherforge.json points, as on a dev server; false is a production server. */
    private val resolvesPackages: Boolean = true,
    /** The editor's package cache, where git packages' checkouts are (`-Dnetherforge.packages`). */
    private val packageCache: Path? = null,
    /** Whether a manifest is written when [files] has none (a bundle has its own layout). */
    writeManifest: Boolean = true,
    /** `http:` in `config.yml`, when not the defaults. */
    private val http: HttpConfig = HttpConfig(),
    /** `web:` in `config.yml`, when not the defaults (web routes off; on for [isShowcase]'s files). */
    private val web: WebConfig = if (isShowcase(files)) WebConfig(enabled = true) else WebConfig(),
    /** `bots:` in `config.yml`, when not the defaults (bots off; on for [isShowcase]'s files, as on a dev server). */
    private val bots: BotsConfig = if (isShowcase(files)) BotsConfig(enabled = true) else BotsConfig(),
    /** `structures:` in `config.yml`, when not the defaults. */
    private val structures: StructuresConfig = StructuresConfig(),
    /** `wasm:` in `config.yml`, when not the defaults. */
    private val wasm: WasmConfig = WasmConfig(),
    /** `screens:` in `config.yml`, when not the defaults. */
    private val screens: ScreensConfig = ScreensConfig(),
    /** `network:` in `config.yml`, when not the defaults (in no network). */
    private val network: NetworkConfig = NetworkConfig(),
    /** `databases:` in `config.yml`: the named connections `nf.db("name")` may open. */
    private val databases: DatabasesConfig = DatabasesConfig(),
    /** How a named connection's pool is made, when not HikariCP: tests give an embedded database. */
    private val connectionSources: ((String, ConnectionConfig) -> DataSource)? = null,
    /**
     * The worlds the server asks NetherForge to generate as it starts (`bukkit.yml`'s `worlds.<name>.generator: NetherForge`):
     * the main world for [isShowcase]'s files, as the editor's dev server asks.
     */
    var startupWorlds: List<String> = if (isShowcase(files)) listOf("world") else emptyList()
) : AutoCloseable {
    val project: Path = root.resolve("project")
    val state: Path = root.resolve("server")

    /** Everything the runtime would have sent the editor: console entries and notifications' params. */
    val sent: MutableList<Any?> = java.util.Collections.synchronizedList(mutableListOf())

    lateinit var runtime: NetherForgeRuntime
        private set

    /** What the runtime answered for each of [startupWorlds] at the last start. */
    var startupGeneratorIds: Map<String, String?> = emptyMap()
        private set

    init {
        project.createDirectories()
        if (writeManifest && MANIFEST !in files) write(MANIFEST, manifest())
        files.forEach { (path, contents) ->
            when (contents) {
                is String -> write(path, contents)
                is ByteArray -> writeBytes(path, contents)
                else -> error("unsupported contents for $path")
            }
        }
        if (start) start()
    }

    fun start() {
        // Before the worlds load, the adapter gives the server the start-up datapack.
        platform.datapacks.bootstrap(project, resolvesPackages)
        runtime =
            NetherForgeRuntime(
                platform,
                RuntimeConfig(
                    project,
                    project.resolve(".netherforge/data"),
                    state,
                    bridge,
                    performance = performance,
                    schedules = ScheduleConfig(scheduleZone),
                    databases = databases,
                    sandbox = sandbox,
                    http = http,
                    web = web,
                    bots = bots,
                    structures = structures,
                    wasm = wasm,
                    screens = screens,
                    network = network,
                    resolvesPackages = resolvesPackages,
                    packageCache = packageCache
                )
            )
        clock?.let { runtime.clock = it }
        connectionSources?.let { runtime.connectionSources = it }
        wallClock?.let { runtime.wallClock = it }
        versionGates?.let { runtime.versionGates = it }
        // What the server asks before it loads its worlds, after the plugin was enabled at STARTUP.
        startupGeneratorIds = startupWorlds.associateWith { runtime.defaultWorldGenerator(it) }
        runtime.log.bridge = object : BridgeOutput {
            override fun console(entry: ConsoleEntry) {
                sent += entry
            }

            override fun <T> stream(stream: BridgeStream<T>, item: T) {
                sent += item
            }

            override fun <P> notify(event: BridgeEvent<P>, params: P) {
                sent += params
            }
        }
        runtime.enable()
    }

    /** Stops the server and starts a fresh runtime on the same project and state, as a restart would. */
    fun restart() {
        runtime.disable()
        start()
    }

    /**
     * The server dying: what the store had been handed is on disk, the session never stops
     * (nobody is put back, nothing is torn down), and a fresh runtime starts on the same state.
     * The old runtime is abandoned, its threads with it.
     */
    fun crash() {
        runtime.store.flush()
        start()
    }

    fun write(path: String, text: String) {
        val file = project.resolve(path)
        file.parent.createDirectories()
        file.writeText(text.trimIndent() + "\n")
    }

    fun writeBytes(path: String, bytes: ByteArray) {
        val file = project.resolve(path)
        file.parent.createDirectories()
        Files.write(file, bytes)
    }

    fun delete(path: String) {
        project.resolve(path).deleteIfExists()
    }

    fun tick(times: Int = 1) = repeat(times) {
        // What scripts started off the main thread is done before the tick, so a test sees it land at a known tick.
        check(runtime.settled(IDLE_MILLIS)) { "the runtime's workers or WebAssembly were still busy after ${IDLE_MILLIS}ms" }
        runtime.tick()
        platform.scheduler.runPending()
        // The server ticks the world (entities, their AI included) after plugins.
        platform.tickWorld()
    }

    /** Runs what other threads handed the main thread (the bridge's requests) and what the fake finishes later (a bot's join), without a tick. */
    fun runMain() {
        runtime.mainThread.run { throw it }
        platform.scheduler.runPending()
    }

    fun reload(vararg paths: String): ReloadResult = runtime.reload(paths.toList())

    val errors: List<ScriptError> get() = sent.filterIsInstance<ScriptError>()

    /** Lines scripts logged (the runtime's own lines carry no source). */
    val logs: List<String> get() = sent.filterIsInstance<Log>().filter { it.source != null }.map { it.message }

    /** Script errors (`ERROR <message>`), then the lines scripts logged: what a [LuaChecks] script is judged by. */
    fun output(): List<String> = errors.map { "ERROR ${it.message}" } + logs

    fun player(name: String = "Alex") = platform.players.add(name)

    /** [player] breaks [block], which would drop [drops]: true when a script cancelled it. */
    fun breaks(player: FakePlayer, block: BlockRef, drops: List<ItemData> = emptyList(), experience: Int = 0): Boolean =
        runtime.events.blockBreak(player.ref, block, { drops }, experience) == null

    override fun close() {
        runtime.disable()
        root.toFile().deleteRecursively()
    }

    companion object {
        const val MANIFEST = "netherforge.json"

        private const val IDLE_MILLIS = 10_000L

        /** A manifest; [requires] and [allow] are the `requires` and `allow` objects' JSON, when there are. */
        fun manifest(
            minecraft: String = "26.3",
            formatVersion: Int = FormatVersion.CURRENT,
            allow: String? = null,
            requires: String? = null
        ): String {
            val schema = "\"${'$'}schema\": \".netherforge/schema/netherforge.schema.json\""
            val more = (requires?.let { ", \"requires\": $it" } ?: "") + (allow?.let { ", \"allow\": $it" } ?: "")
            val identity = """"name": "Test", "namespace": "test", "version": "1.0.0""""
            return """{ $schema, "formatVersion": $formatVersion, $identity, "minecraft": "$minecraft"$more }"""
        }

        /** A centity file with one root node carrying a block display and a hitbox, and a script. */
        fun scriptedCentity(script: String = "script.lua", extra: String = "") = """
            {
              "nodes": {
                "root": {
                  "display": { "type": "block", "block": "minecraft:stone" },
                  "hitbox": {}
                }$extra
              },
              "script": { "file": "$script" }
            }
        """

        /**
         * The example project [name]'s files, and the packages it depends on
         * at their paths from it (`../library/…`), so they land beside the
         * project as they sit beside it in `examples/`. Textures and structures are bytes, everything else text.
         */
        fun example(name: String): Map<String, Any> {
            val files = folder(repo().resolve("examples").resolve(name))
            val manifest = ManifestKind.parse(files.getValue(MANIFEST) as String, MANIFEST) as CanonicalJson.Parsed.Ok
            val packages = manifest.value.dependencies.orEmpty().values.mapNotNull { it.path }.flatMap { at ->
                folder(repo().resolve("examples").resolve(Packages.join(name, at))).map { (path, contents) -> "$at/$path" to contents }
            }
            return files + packages
        }

        /**
         * Whether [files] are examples/showcase's (its manifest names namespace `showcase`), which a server runs as the
         * editor's dev server does: bots and web routes on (it declares both) and the main world generated by NetherForge
         * (it names the expo's plaza for it). Anything else starts with every server default.
         */
        fun isShowcase(files: Map<String, Any>): Boolean = (files[MANIFEST] as? String)?.contains("\"namespace\": \"showcase\"") == true

        private fun folder(dir: Path): Map<String, Any> = Files.walk(dir).use { paths ->
            // Skip what a project loader skips: a dev server run from the editor leaves a whole world in .netherforge/.
            // The default font is skipped too: opening the example writes it from the client, and it isn't committed.
            paths.filter { Files.isRegularFile(it) && !skipped(dir.relativize(it)) }.toList().associate { file ->
                val path = dir.relativize(file).joinToString("/")
                // Text is what reads as UTF-8; anything else (pictures, structures, a converted video) is bytes.
                val bytes = Files.readAllBytes(file)
                path to
                    (runCatching { Charsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString() }.getOrNull() ?: bytes)
            }
        }

        private val SKIPPED = setOf(".git", ".netherforge")

        private fun skipped(path: Path) = path.any { it.toString() in SKIPPED } || path.joinToString("/") == DefaultFontFile.FILE

        fun repo(): Path = Path.of(System.getenv("NETHERFORGE_REPO") ?: error("NETHERFORGE_REPO isn't set; run through Gradle"))
    }
}
