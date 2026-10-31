package dev.netherforge.plugin

import dev.netherforge.format.Problem
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.HelloParams
import dev.netherforge.format.bridge.InstanceInfo
import dev.netherforge.format.bridge.ReloadResult
import dev.netherforge.format.bridge.ReloadedResource
import dev.netherforge.format.bridge.ServerSettings
import dev.netherforge.format.bridge.Status
import dev.netherforge.format.project.DefaultFontKind
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.LockKind
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.VanillaKind
import dev.netherforge.format.resourcepack.PackFonts
import dev.netherforge.format.resourcepack.ResourcePackFile
import dev.netherforge.format.resourcepack.ResourcePackKind
import dev.netherforge.plugin.admin.AdminCommand
import dev.netherforge.plugin.async.Completions
import dev.netherforge.plugin.async.MainThread
import dev.netherforge.plugin.async.Workers
import dev.netherforge.plugin.binding.VersionGates
import dev.netherforge.plugin.bridge.BridgeService
import dev.netherforge.plugin.centity.Instance
import dev.netherforge.plugin.datapack.StartupDatapackCheck
import dev.netherforge.plugin.debug.Debugger
import dev.netherforge.plugin.events.ServerEvents
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.network.NetworkLink
import dev.netherforge.plugin.pack.Packs
import dev.netherforge.plugin.particle.ActiveEffect
import dev.netherforge.plugin.particle.ParticleEffects
import dev.netherforge.plugin.platform.ArgumentSyntax
import dev.netherforge.plugin.platform.ArgumentType
import dev.netherforge.plugin.platform.CommandSpec
import dev.netherforge.plugin.platform.CommandSyntax
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.profile.Profiler
import dev.netherforge.plugin.project.ProjectFiles
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.ScriptFiles
import dev.netherforge.plugin.session.Failures
import dev.netherforge.plugin.session.Handover
import dev.netherforge.plugin.session.ProjectSession
import dev.netherforge.plugin.store.ConnectionConfig
import dev.netherforge.plugin.store.Database
import dev.netherforge.plugin.store.HikariSources
import dev.netherforge.plugin.store.PackageDatabases
import dev.netherforge.plugin.store.RemoteDatabases
import dev.netherforge.plugin.store.Store
import dev.netherforge.plugin.terrain.StartupGenerators
import dev.netherforge.plugin.web.WebGate
import javax.sql.DataSource
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * NetherForge on one server, for as long as the plugin is enabled: the
 * composition root. It binds the [Platform] (the adapter calls in through
 * [events]), runs the dev bridge and the `/nf` command, and runs the project
 * as a [ProjectSession], which it builds on start and disposes whole on a
 * full reload or when the plugin stops. Everything the project runs is the
 * session's; what lives here is only what outlives one: the binding, the
 * bridge, the admin command, the resource pack's delivery, the store,
 * the worker pool and the tick count.
 *
 * The adapter's whole job is to construct this with a platform and a config,
 * call [enable] and [disable], and tick it. Everything runs on the main thread
 * but what it hands to [workers]; the bridge's threads hand work back through
 * [mainThread], and work for the session through [completions].
 *
 * A project the runtime can't run (no manifest, a format it can't read, a
 * Minecraft version this adapter isn't for) is **refused**: its session
 * reports its problems and runs nothing, and spawned centities are kept,
 * untouched, until it's fixed.
 */
class NetherForgeRuntime(val platform: Platform, val config: RuntimeConfig) {
    val log = RuntimeLog(platform.log)

    /** `nf.files.get`'s sandboxed directory. */
    val files = ScriptFiles(config.dataDirectory, config.files)

    /** The project's files and its packages', wherever they are (a dev server's folders, a bundle). */
    val source = ProjectFiles(config.project, config.resolvesPackages, config.packageCache)

    /** The resource pack: the last good build, the server that serves it, and what each player was sent. */
    val packs = Packs(config.pack, config.stateDirectory, log, platform, source::readBytes)

    /**
     * This server's place in its network (`nf.network`), for the plugin's life: the connection to the hub (a dev server
     * not in a network, and a test run, are a network of their own, in this JVM) and what it said of the network.
     * Connected in [enable], closed in [disable].
     */
    val network = NetworkLink(
        config.network,
        alone = when {
            config.testing != null -> "test"
            config.bridge != null -> "dev"
            else -> null
        },
        kick = { uuid, reason -> platform.players.kick(uuid, reason) },
        joinHere = config.testing?.let { harness -> { name -> harness.join(name) } }
    )

    /** Web routes' door, the listener's half (`nf.web`): served on the pack's listener once [enable] runs, if the owner turned them on. */
    val web = WebGate(config.web)

    /**
     * Where the world reaches web routes, without a slash at the end: `web.public-url`, or the
     * listener's own address; null while they're off. In a test run, `http://localhost`.
     */
    fun webBase(): String? = when {
        config.testing != null -> config.web.publicUrl ?: "http://localhost"
        !config.web.enabled -> null
        else -> config.web.publicUrl ?: packs.listener()?.let { "http://${it.host}:${it.port}" }
    }

    /** Whether the server must restart for what it learns only at start (the start-up datapack) to be what the files say. */
    val datapack = StartupDatapackCheck(platform, source, log)

    /** The generators the server took for the worlds it loads as it starts (its main world), which only a restart changes. */
    val startupGenerators = StartupGenerators(platform, source, log)

    /** What the server tells the runtime, raised to the session's scripts: what the adapter calls. */
    val events = ServerEvents(this)

    /**
     * The one worker pool, for the plugin's life (made in [enable], closed in
     * [disable]): sessions come and go, threads and their lanes don't.
     */
    val workers: Workers get() = pool ?: error("NetherForge isn't enabled")
    private var pool: Workers? = null

    /**
     * The runtime's state on this server (`netherforge.db` in the plugin's
     * folder), for the plugin's life (opened in [enable], closed in
     * [disable]): what services stage in a tick is committed at its end.
     */
    val store: Store get() = opened ?: error("NetherForge isn't enabled")
    private var opened: Store? = null

    /** Each package's own database (`nf.db()`), opened as it's asked for and closed in [disable]. */
    val databases = PackageDatabases(
        config.stateDirectory.resolve("databases"),
        { workers },
        object : Database.Report {
            override fun error(message: String, cause: Throwable?) = mainThread.execute { log.error(message, cause) }

            override fun info(message: String) = mainThread.execute { log.info(message) }
        }
    )

    /**
     * The MySQL and PostgreSQL connections the owner named (`nf.db("network")`, `databases:` in `config.yml`): made in [enable],
     * their pools opened as scripts ask and closed in [disable].
     */
    val remoteDatabases: RemoteDatabases get() = remote ?: error("NetherForge isn't enabled")
    private var remote: RemoteDatabases? = null

    /** How a named connection's pool is made; tests give an embedded database before [enable]. */
    internal var connectionSources: (String, ConnectionConfig) -> DataSource = HikariSources::pool

    /** What finished off the main thread for a session, drained in its [dev.netherforge.plugin.session.TickPhase.ASYNC]. */
    val completions = Completions()

    /** What the runtime's own threads (the bridge's) hand to the main thread: run at the start of every tick, outside any session's. */
    val mainThread = MainThread()

    /** Script failures while a reload runs, across the sessions a full reload spans. */
    internal val failures = Failures()

    /** Nanoseconds from any fixed point; tests set their own before [enable]. What [time] counts from. */
    internal var clock: () -> Long = System::nanoTime

    /**
     * What the Lua state times each call in with (see `ScriptCosts`), its time
     * limit counts, and the profiler times each step of the tick with: [clock]
     * without the time the debugger held the server paused, so a call stopped
     * at a breakpoint isn't charged for it, or stopped for running long.
     */
    val time: () -> Long = { clock() - (debugger?.pausedNanos ?: 0L) }

    /** Breakpoints, stepping and variables over the bridge's DAP channel: a dev server's only (made with the bridge in [enable]). */
    var debugger: Debugger? = null
        private set

    /** What `nf.schedule` reads the time from; tests set their own before [enable]. */
    internal var wallClock: java.time.Clock = java.time.Clock.systemUTC().let {
        if (config.wallClockRate == 1.0) it else dev.netherforge.plugin.schedule.ScaledClock(it, config.wallClockRate)
    }

    /** What the spec says needs a newer Minecraft than some servers run; tests set their own before [enable]. */
    internal var versionGates: VersionGates = VersionGates.SPEC

    /** Exact time per tick step, scope and function: always measuring on a dev server, on production while `/nf profile` records. */
    val profiler = Profiler(
        dev = config.bridge != null,
        log = log,
        directory = config.stateDirectory.resolve("profiles"),
        // Reports are written on a lane of the workers, and said on the main thread.
        writer = { workers.lane("profiles") },
        onMain = mainThread,
        server = { "Minecraft ${platform.info.minecraftVersion}, NetherForge ${platform.info.pluginVersion}" },
        // Parts are timed as the steps are, without the time the debugger held the server.
        clock = { time() }
    )

    private val admin = AdminCommand(this)
    private var bridge: BridgeService? = null

    /** The project's run now; any thread may ask (the platform builds stacks off the main thread). */
    @Volatile
    private var current: ProjectSession? = null

    /** The project's run now: every service, its scripts and its Lua state. */
    val session: ProjectSession get() = current ?: error("NetherForge isn't enabled")

    /**
     * Waits (up to [millis] each) until what scripts started off the main thread has gone as far as it can on its
     * own: the workers idle, and the session's WebAssembly settled (calls done, programs waiting for input). What a
     * test run's tick waits on, so a test sees work land at a known tick. False if it didn't in time.
     */
    fun settled(millis: Long): Boolean = workers.idle(millis) && current?.wasm?.settle(millis) != false

    /** Server ticks since enable; what `nf.server.tick()` reads. */
    var ticks: Long = 0
        private set

    private var lastStatus: Status? = null

    // ---- lifecycle -------------------------------------------------------------

    /**
     * Before [enable], as the server starts: it's about to load world [world]
     * (its main world), whose generator it asked NetherForge for, and this is
     * which of the project's generators makes it (its id, which the adapter's
     * chunk generator for the world reads the published generator by), or
     * null when the project names none.
     */
    fun defaultWorldGenerator(world: String): String? = startupGenerators.generatorFor(world)

    fun enable() {
        // Screens draw text and pictures with AWT, into pictures: a server has no screen. Set before anything of AWT
        // loads (it reads this once), and only when the server's owner didn't say otherwise.
        if (System.getProperty(HEADLESS) == null) System.setProperty(HEADLESS, "true")
        val workers = Workers(uncaught = { e -> mainThread.execute { log.error("NetherForge's background work failed", e) } })
        pool = workers
        opened = Store.open(
            config.stateDirectory,
            workers.lane(STORE_LANE),
            object : Database.Report {
                override fun error(message: String, cause: Throwable?) = mainThread.execute { log.error(message, cause) }

                override fun info(message: String) = mainThread.execute { log.info(message) }
            }
        )
        remote = RemoteDatabases(
            config.databases,
            { workers },
            object : Database.Report {
                override fun error(message: String, cause: Throwable?) = mainThread.execute { log.error(message, cause) }

                override fun info(message: String) = mainThread.execute { log.info(message) }
            },
            connectionSources
        ).also { remote ->
            remote.invalid().forEach { (name, why) -> log.error("config.yml's databases.$name can't be used: $why", null) }
            if (remote.names().isNotEmpty()) log.info("Database connections: " + remote.describe().joinToString("; "))
            if (connectionSources === HikariSources::pool) {
                config.databases.connections.values.map { it.type }.distinct().forEach { type ->
                    HikariSources.missing(type)?.let { log.error("config.yml's databases name a ${type.id} server, but $it", null) }
                }
            }
        }
        platform.bind(
            events,
            packs::textGlyph,
            { current?.items?.look(it) },
            { current?.customBlocks?.stateOf(it) },
            { current?.namespace.orEmpty() }
        )
        platform.bindText({ px -> if (packs.built != null) PackFonts.offset(px) else null }) { text ->
            current?.layoutText(text) ?: text
        }
        if (config.web.enabled && config.testing == null) {
            val listener = packs.listener()
            if (listener != null) {
                listener.web = { exchange -> web.accept(exchange, listener::execute) }
                log.info("Web routes are on, at ${webBase()}")
            }
        }
        network.start { log.info(it) }
        config.bridge?.let { settings ->
            val connection = BridgeService(this, settings)
            debugger = Debugger(this, connection::dap)
            bridge = connection
            log.bridge = connection
            connection.start()
        }
        platform.commands.register(
            CommandSpec(
                "netherforge",
                listOf("nf"),
                CommandSyntax(
                    description = "NetherForge: spawn, list, find, kill and reload centities; script costs; pack status",
                    permission = PERMISSION,
                    // Free-form: the admin command reads its own words and completes them itself.
                    arguments = listOf(ArgumentSyntax(AdminCommand.ARGUMENTS, ArgumentType.TEXT, optional = true, completes = true))
                )
            ),
            admin
        )
        open(Handover(store.instances.all(), emptyMap()))
        // Entities in chunks loaded before we were: no load event will announce them.
        session.entitiesLoaded(platform.entities.loadedTagged(), emptyList())
    }

    fun disable() {
        current?.stop()
        current = null
        // What the session staged as it stopped is written before the pool goes.
        try {
            opened?.close()
        } catch (e: Exception) {
            log.error("NetherForge couldn't close its store", e)
        }
        opened = null
        try {
            databases.close()
        } catch (e: Exception) {
            log.error("NetherForge couldn't close the packages' databases", e)
        }
        try {
            remote?.close()
        } catch (e: Exception) {
            log.error("NetherForge couldn't close its database connections", e)
        }
        remote = null
        network.stop()
        // Work under way (files being copied, reports written) finishes; what it reported is logged.
        pool?.close()
        pool = null
        mainThread.run(::tickFailed)
        packs.stop()
        platform.commands.unregister("netherforge")
        log.bridge = null
        bridge?.stop()
        bridge = null
        debugger = null
    }

    /** Builds a session from [handover] and starts it. It's [session] before it starts, so whatever it raises meanwhile reaches it. */
    private fun open(handover: Handover) {
        val next = ProjectSession(this, handover)
        current = next
        next.start()
    }

    // ---- reload ----------------------------------------------------------------

    /**
     * Reloads the resources that own [paths], each once (the session's
     * [ProjectSession.reload]). The manifest, the lock, or anything while the
     * project isn't running starts a new session ([reloadAll]).
     */
    fun reload(paths: List<String>): ReloadResult {
        val found = paths.mapNotNull { Kinds.classify(it) }
        val resources = paths.mapNotNull { Resource.of(it) }.distinct()
        // The manifest, or the lock the editor rewrote: a dependency changed, so everything starts again.
        val project = found.any { it.document == ManifestKind.id || it.document == LockKind.id }
        // The default font's advances place skins on centred titles, and the game's looks of the blocks custom
        // blocks are held in draw their other states in the pack: the services built from them hear of a change.
        val documents = found.mapNotNull { it.document }.filter { it in FOLLOWED_DOCUMENTS }.toSet()
        if (resources.isEmpty() && !project) {
            if (documents.isNotEmpty() && session.running) session.reload(emptyList(), documents)
            return ReloadResult(emptyList())
        }
        if (project || !session.running) return reloadAll()
        platform.particles.forget()
        return session.reload(resources, documents)
    }

    /** Disposes the session and starts a new one on the project read afresh. Spawned centities reattach to the new definitions. */
    fun reloadAll(): ReloadResult {
        val failed = failures.collect { open(session.stop()) }
        val session = session
        if (session.running) session.data.saveAll()
        val wasStale = datapack.stale
        // The new session checked the main world's generator as it started, against the manifest it read.
        val restart = datapack.check() || startupGenerators.stale
        // The session reported its problems as it started, before the check.
        if (restart != wasStale) session.problemsChanged()
        // The whole package: no kind, no id.
        return ReloadResult(
            listOf(
                ReloadedResource(
                    session.namespace,
                    ok = session.running && failed.isEmpty(),
                    reattached = session.centities.all().size,
                    problems = (session.problems() + failed).distinct()
                )
            ),
            restart
        )
    }

    // ---- the tick ----------------------------------------------------------------

    /** One server tick: the session's, what it changed committed to the store, then the editor's status now and then. */
    fun tick() {
        ticks++
        mainThread.run(::tickFailed)
        // What the editor set since (breakpoints, a pause), before any script of this tick runs.
        debugger?.let { debugger -> session.scripts.host?.let(debugger::sync) }
        session.tick()
        // A script asked for the resource packs read again (`nf.packs.rebuild`): after the tick, out of every script's way.
        if (session.packsWanted) {
            session.packsWanted = false
            if (session.running) reload(session.snapshot.everywhere(ResourcePackKind).keys.map { packFile(it) })
        }
        store.commit()
        if (ticks % STATUS_EVERY == 0L) {
            try {
                sendStatus()
            } catch (e: Exception) {
                log.error("NetherForge's tick failed at status", e)
            }
        }
    }

    private fun tickFailed(e: Throwable) = log.error("NetherForge couldn't run work handed to the main thread", e)

    /** Resource pack [id]'s `pack.json`, as a reload names it: the project's, or a package's by its package path. */
    private fun packFile(id: String): String = session.snapshot.placeOf(ResourcePackKind, id).file(ResourcePackFile.FILE_NAME)

    // ---- what the bridge and /nf ask -------------------------------------------

    /**
     * Records the next [seconds] of ticks and writes a report into the
     * plugin's `profiles` folder; [done] hears where (on the main thread).
     * Refused while one is recording.
     */
    fun profile(seconds: Int, done: (String) -> Unit) {
        // What was measured before isn't the recording's: the session hands it over first, into the batch flushed now.
        session.profile.drain()
        profiler.record(ticks, seconds, done)
        session.profile.sync()
    }

    /** The project's problems and those of scripts still running, as the editor's problems list shows them. */
    fun currentProblems(): List<Problem> = session.problems()

    /**
     * Saves every `data()` table that changed and every centity's place and
     * facing, as the world saving would, and waits until the store has them.
     */
    fun saveData() {
        session.data.saveAll()
        session.centities.savePlacements()
        store.flush()
    }

    fun hello(nonce: String) =
        HelloParams(nonce, Bridge.PROTOCOL, platform.info.pluginVersion, platform.info.minecraftVersion, config.project.toString())

    fun status() = Status(platform.players.online().map { it.name }.sorted(), session.centities.count())

    fun instances(): List<InstanceInfo> = session.centities.info()

    /** Spawns [centity] in front of [player] (or the first player online, or at the world spawn). */
    fun spawnNear(centity: String, player: PlayerRef?): Instance {
        val session = session
        check(session.running) { "the project isn't running" + session.problems().firstOrNull()?.let { ": ${it.message}" }.orEmpty() }
        requireNotNull(session.centities.definition(centity)) { "no centity \"$centity\" in this project" }
        val who = player ?: platform.players.online().firstOrNull()
        val at = who?.let { platform.players.location(it.uuid) }?.let(::inFront)
            ?: platform.worlds.spawnLocation(platform.worlds.defaultWorld())
            ?: error("there's no world to spawn in")
        return session.centities.spawn(centity, at) ?: error("couldn't spawn $centity at $at")
    }

    /**
     * Plays [effect] for the editor ("Play on server"): three blocks in front of
     * [player]'s eyes (or the first player online's), facing them; else at the
     * default world's spawn. It belongs to the session, not a script, and ends
     * when the project restarts or [stopParticleEffects] is called.
     */
    fun playParticleEffect(effect: String, player: PlayerRef?, loop: Boolean?): ActiveEffect {
        val session = session
        check(session.running) { "the project isn't running" + session.problems().firstOrNull()?.let { ": ${it.message}" }.orEmpty() }
        val who = player ?: platform.players.online().firstOrNull()
        val at = who?.let { platform.players.eye(it.uuid) }?.let { eye ->
            val length = Math.sqrt(eye.dx * eye.dx + eye.dy * eye.dy + eye.dz * eye.dz).takeIf { it > 0 } ?: 1.0
            val facing = platform.players.location(who.uuid)
            Location(
                eye.world,
                eye.x + eye.dx / length * EFFECT_DISTANCE,
                eye.y + eye.dy / length * EFFECT_DISTANCE,
                eye.z + eye.dz / length * EFFECT_DISTANCE,
                // Facing them: turned around from where they look.
                yaw = (facing?.yaw ?: 0.0) + 180.0
            )
        } ?: platform.worlds.spawnLocation(platform.worlds.defaultWorld()) ?: error("there's no world to play it in")
        val played = try {
            session.particles.play(effect, at, owner = null, ParticleEffects.PlayOptions(loop = loop))
        } catch (e: LuaApiException) {
            throw IllegalArgumentException(e.message)
        } ?: error("the world at $at isn't loaded")
        log.info("Playing particle effect $effect")
        return played
    }

    /** Ends every effect the editor played; returns how many. */
    fun stopParticleEffects(): Int = session.particles.stopUnowned()

    /** Two blocks ahead of where [location] faces, on the block grid. */
    private fun inFront(location: Location): Location {
        val yaw = Math.toRadians(location.yaw)
        return location.copy(
            x = floor(location.x - sin(yaw) * 2),
            y = floor(location.y),
            z = floor(location.z + cos(yaw) * 2),
            yaw = 0.0,
            pitch = 0.0
        )
    }

    /**
     * Every package's server-owner settings as the editor shows them, each
     * file's path relative to the server's folder (where the editor reads).
     */
    fun settings(): ServerSettings {
        val server = config.bridge?.serverDirectory
        return session.settings.state { file ->
            server?.let { runCatching { it.relativize(file).joinToString("/") }.getOrNull() } ?: file.toString()
        }
    }

    /** Tells the editor the settings changed. */
    internal fun settingsChanged() = log.notify(Bridge.settingsChanged, settings())

    /** Sends everything an editor that just connected needs to catch up. */
    internal fun sendState() {
        session.problemsChanged()
        settingsChanged()
        sendStatus(force = true)
    }

    private fun sendStatus(force: Boolean = false) {
        val status = status()
        if (force || status != lastStatus) {
            lastStatus = status
            log.notify(Bridge.status, status)
        }
    }

    companion object {
        /** The project documents that aren't resources whose saving some service follows. */
        private val FOLLOWED_DOCUMENTS = setOf(DefaultFontKind.id, VanillaKind.id)

        const val PERMISSION = "netherforge.admin"

        private const val STATUS_EVERY = 20L

        /** The workers' lane the store is written and read on. */
        private const val STORE_LANE = "store"

        /** AWT's switch for drawing with no screen. */
        private const val HEADLESS = "java.awt.headless"

        /** How far in front of a player's eyes the editor's "Play on server" plays an effect, in blocks. */
        private const val EFFECT_DISTANCE = 3.0
    }
}
