package dev.netherforge.plugin.session

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.Severity
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.Problems
import dev.netherforge.format.bridge.ReloadResult
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.game.MinecraftVersion
import dev.netherforge.format.item.ItemKind
import dev.netherforge.format.menu.MenuKind
import dev.netherforge.format.module.ModuleKind
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Kinds
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.text.TextWidth
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.advancement.Advancements
import dev.netherforge.plugin.api.RuntimeApi
import dev.netherforge.plugin.async.AsyncWork
import dev.netherforge.plugin.binding.EntityPathEndEvent
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.GameEventDispatch
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.mobHandle
import dev.netherforge.plugin.block.CustomBlocks
import dev.netherforge.plugin.bots.ScriptBots
import dev.netherforge.plugin.centity.Centities
import dev.netherforge.plugin.centity.InstanceRecord
import dev.netherforge.plugin.centity.NaturalSpawner
import dev.netherforge.plugin.command.ArgumentValues
import dev.netherforge.plugin.command.ProjectCommands
import dev.netherforge.plugin.cutscene.Cutscenes
import dev.netherforge.plugin.data.PlayerPermissions
import dev.netherforge.plugin.data.ScriptData
import dev.netherforge.plugin.data.SessionTables
import dev.netherforge.plugin.dialog.Dialogs
import dev.netherforge.plugin.http.Connections
import dev.netherforge.plugin.http.HttpClient
import dev.netherforge.plugin.hud.Huds
import dev.netherforge.plugin.interop.Placeholders
import dev.netherforge.plugin.interop.Plugins
import dev.netherforge.plugin.item.Items
import dev.netherforge.plugin.item.Recipes
import dev.netherforge.plugin.loot.LootTables
import dev.netherforge.plugin.lua.LuaHost
import dev.netherforge.plugin.menu.Menus
import dev.netherforge.plugin.mob.MobGoals
import dev.netherforge.plugin.mob.MobPaths
import dev.netherforge.plugin.module.Modules
import dev.netherforge.plugin.network.Network
import dev.netherforge.plugin.pack.PackService
import dev.netherforge.plugin.particle.ParticleEffects
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.StructureMarker
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.profile.ScriptProfile
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.schedule.Schedules
import dev.netherforge.plugin.scoreboard.BossBars
import dev.netherforge.plugin.scoreboard.PlayerListings
import dev.netherforge.plugin.scoreboard.Sidebars
import dev.netherforge.plugin.scoreboard.Teams
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScriptCosts
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.settings.OwnerSettings
import dev.netherforge.plugin.store.PackageSchemas
import dev.netherforge.plugin.structure.GradualJobs
import dev.netherforge.plugin.structure.StructureSpawns
import dev.netherforge.plugin.structure.StructureStore
import dev.netherforge.plugin.terrain.WorldGenerators
import dev.netherforge.plugin.testing.ScriptTests
import dev.netherforge.plugin.wasm.WasmModules
import dev.netherforge.plugin.web.OAuthFlows
import dev.netherforge.plugin.web.WebRoutes
import dev.netherforge.plugin.world.BlockData
import dev.netherforge.plugin.world.Daylight
import dev.netherforge.plugin.world.Effects
import dev.netherforge.plugin.world.EntityData
import dev.netherforge.plugin.world.HiddenEntities
import dev.netherforge.plugin.world.ManagedWorlds
import dev.netherforge.plugin.world.WorldSpawnRates
import java.util.EnumMap
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.reflect.KClass

/**
 * One run of the project: its Lua state, its scripts and every service that
 * holds something of the project's, built when the project starts and
 * disposed whole when it stops (a full reload, the server stopping). The
 * plugin outlives it ([NetherForgeRuntime]: the platform binding, the bridge,
 * the admin command, and the resource pack's delivery); nothing a session
 * keeps reaches the next one but its [Handover].
 *
 * The project is several packages (the project and the packages it depends
 * on, by namespace): every scope is one package's ([Scope.namespace]), a
 * package's `nf.data(name)` tables are its own, and a bare id a package's
 * script passes the API names that package's resource ([names]).
 *
 * **Services.** Everything else is a [RuntimeService] in [services], in the
 * one list below: registration order is the order they define, start and
 * hear every hook in (stop is the reverse), and a kind of resource reloads
 * through the one service that says it [RuntimeService.reloads] it. A new
 * subsystem is a class registered there; nothing else names it.
 */
class ProjectSession internal constructor(
    /** The plugin: its platform, config and log, and what outlives a session. */
    val runtime: NetherForgeRuntime,
    handover: Handover
) {
    val platform get() = runtime.platform
    val config get() = runtime.config
    val log get() = runtime.log

    /** The project's files and its packages' (package paths). */
    val source get() = runtime.source

    /** `nf.files.get`'s sandboxed directory. */
    val files get() = runtime.files

    /** The resource pack, which outlives the session (see [PackService]). */
    val packs get() = runtime.packs

    /** Server ticks since the plugin enabled: what `nf.server.tick()` reads. */
    val ticks: Long get() = runtime.ticks

    /** What the spec says needs a newer Minecraft than some servers run. */
    internal val versionGates get() = runtime.versionGates

    internal val failures get() = runtime.failures

    /** The project as it read last (every reload reads it again). */
    var snapshot: ProjectSnapshot
        private set

    /** Every file the project and its packages have, by project and package path. */
    var projectFiles: Set<String> = emptySet()
        private set

    /** What the load found wrong besides the format's findings: a bundle that doesn't hash right, dependencies left unbundled. */
    private var loadProblems: List<Problem> = emptyList()

    /**
     * The project's namespace (`netherforge.json`'s): what its references
     * resolve in, and what everything it registers on the server or stamps on
     * a stack is named under.
     */
    val namespace: String get() = snapshot.namespace

    /** Where the resource [id] of [kind] is now, in its group folders: the project's, or a package's named `ns:id`. */
    fun placeOf(kind: KindSpec<*, *>, id: String): ResourcePlace = snapshot.placeOf(kind, id)

    /** How names cross between the packages: what a script names, resolved in its package, and what it reads back, spelled for it. */
    val names = PackageNames(this)

    /**
     * Which session this is, counting from 1 in this JVM: what everything it
     * queues for the main thread carries ([Completions]), so a result that
     * arrives after it has ended is dropped rather than handed to the next one.
     */
    val generation: Int = GENERATIONS.incrementAndGet()

    /** Whether the project was accepted and its scripts are running. */
    var running: Boolean = false
        private set

    init {
        snapshot = load()
    }

    /** Why the project can't run at all, with the runtime problem to report if the format didn't already. */
    private val refusal: Pair<String, Problem?>? = refusal(snapshot)

    // ---- the services: the one place a subsystem is registered ------------------

    private val services = ArrayList<RuntimeService>()

    private fun <S : RuntimeService> service(service: S): S = service.also { services += it }

    /** Every scope and what it registered; owns the Lua state. Callbacks are late-bound: the services they reach come below. */
    val scripts = service(
        Scripts(
            onFailure = { scope, failure, context -> reports.failed(scope, failure, context) },
            onError = { scope, failure, context, gaveUp -> reports.errored(scope, failure, context, gaveUp) },
            onWarning = { log.warn(it) },
            onRelease = { scope -> for (service in services) service.scopeReleased(scope) },
            home = { namespace },
            ticks = { ticks },
            memoryMegabytes = config.sandbox.memoryMegabytes
        )
    )

    /** Work off the main thread, and how its results come back: to services, and to the scripts that wait. */
    val async = service(AsyncWork(generation, runtime.completions, runtime.workers, scripts, log))

    /** Each package's own database (`nf.db()`): its migrations applied before any script runs. */
    internal val schemas = service(PackageSchemas(runtime.databases, log, { namespace }, source::read, ::problemsChanged))

    /** `centity:data()`, `player:data()` and each package's `nf.data(name)`. */
    val data = service(
        ScriptData(runtime.store.tables, { scripts.host }, { log.warn(it) }, handover.data, { ticks })
    )

    /** `player:session()`: each online player's table, never saved, dropped when they leave. */
    val sessionTables = service(SessionTables { scripts.host })

    /** Server-owner settings: what each package declares, what the owner set, and who read them (`nf.config`). */
    val settings = service(
        OwnerSettings(
            config.stateDirectory.resolve("settings"),
            scripts,
            log,
            restart = { resources -> reload(resources, emptySet()) },
            changed = { runtime.settingsChanged() },
            problemsChanged = ::problemsChanged,
            home = { namespace }
        )
    )

    /** What each package declared it needs (`requires`), the check every capability goes through, and the sum whoever runs the server sees. */
    val requirements = service(Requirements(log, { snapshot }, { names.calling() }) { names.callingHidden() })

    /** `nf.http`'s client and its connections' transport: closed with the session, so reloads leave no threads or sockets behind. */
    val http = service(HttpClient(config.testing != null) { config.http })

    /** The streams and WebSockets scripts hold open, each its script's (after [http], so they end before it closes). */
    val connections = service(Connections(scripts, { config.http }, { http.connections }, { ticks }, keepHistory = config.testing != null))

    /** The web routes scripts answer (`nf.web.route`) and the requests waiting on them. */
    val web = service(WebRoutes(scripts, config.web, { runtime.web }, { ticks }, runtime::webBase) { snapshot })

    /** "Log in with…" flows (`nf.web.oauth`), each with a route of its own (after [web], so they end before it). */
    val oauth = service(
        OAuthFlows(scripts, web, async, { http.fetcher }, { ticks }, testing = config.testing != null) { player ->
            platform.players.online().firstOrNull { it.uuid.toString() == player.id }?.name
        }
    )

    /** The project's WebAssembly modules, compiled as it loads, and the instances and programs scripts run of them. */
    val wasm = service(
        WasmModules(
            config.wasm,
            scripts,
            log,
            names,
            async.mainThread,
            { source.readBytes(it) },
            { source.resolve(it) },
            ::placeOf,
            ::problemsChanged
        )
    )

    /**
     * `nf.network`: the channels and maps scripts named, what waits on the network's hub, and what came from it, raised
     * as events. The connection itself is [NetherForgeRuntime.network]'s, for the plugin's life.
     */
    val network = service(
        Network(
            scripts,
            config.network,
            { runtime.network },
            { ticks },
            { platform.players.get(it) },
            { snapshot },
            log
        )
    )

    /** The other plugins packages declared, whether the server has them (a problem for each it doesn't), and the check before a call into one. */
    /** The bots scripts join (`nf.bots`): they leave with the scope that joined them. */
    internal val bots = service(ScriptBots(platform, config.bots) { snapshot })

    internal val plugins = service(Plugins(platform, log, ::problemsChanged) { namespace })

    /** The `%<namespace>_<key>%` placeholders scripts offer PlaceholderAPI, each its script's. */
    internal val placeholders = service(Placeholders(platform, scripts, log))
    internal val huds = service(
        Huds(
            platform,
            { snapshot.compiledResourcePacks.mapNotNull { (pack, compiled) -> compiled.hud?.let { pack to it } }.toMap() },
            ::measureText,
            { ticks }
        )
    )

    /** The tables `block:data()` hands out, saved with their chunks. */
    internal val blockData = service(BlockData(platform, log) { scripts.host })

    /** The tables `entity:data()` hands out, saved on their entities. */
    internal val entityData = service(EntityData(platform, log) { scripts.host })

    /**
     * The permission nodes scripts set on players, as far as a `netherforge.json` in the tree
     * (the project's or a package's) allows them; on before any script runs.
     */
    internal val permissions = service(
        PlayerPermissions(platform, runtime.store.permissions, namespace) { node ->
            (listOf(snapshot.namespace) + snapshot.packages.keys).any { snapshot.manifestOf(it)?.allow?.allowsPermission(node) == true }
        }
    )

    /** Particles and sounds, checked against the server and the project's packs. */
    internal val effects = service(Effects(platform, names::packSound))

    /** Builds and sends the resource pack: first, since menus' skins and scripts' glyphs read the build. */
    private val packService = service(PackService(packs, log))

    /** What scripts did wrong: the console, the editor and the problems list. */
    internal val reports = service(ScriptReports(this))

    /** The project's structures and the ones scripts saved. */
    internal val structures =
        service(StructureStore(platform, { source.rootFolder }, config.dataDirectory, { structureSpawns.scan() }) { snapshot })

    /** `world:place_gradually` and `world:fill_gradually`: placements and fills spread over ticks, each its scope's. */
    internal val gradual = service(
        GradualJobs(platform, { config.structures }, { structureSpawns.scan() }) { world, x, y, z, state ->
            customBlocks.replaced(world, x, y, z, state)
        }
    )

    /** The worlds the project made and may unload. */
    internal val managedWorlds =
        service(ManagedWorlds(platform, async, log, runtime.store.worlds, namespace, { source.rootFolder }) { snapshot })

    /** Worlds' `dusk` and `dawn`: their clocks, looked at while a script listens. */
    internal val daylight = service(Daylight(platform, scripts))

    /** The spawn rates `netherforge.json` sets for the worlds it names. */
    internal val spawnRates = service(WorldSpawnRates(platform, { log.warn(it) }) { snapshot.manifest?.worlds })

    /** The boss bars scripts made, each its script's. */
    internal val bossBars = service(
        BossBars(platform) { id ->
            centities.find(id)?.takeIf { !it.removed }?.let { instance ->
                val a = instance.anchor
                BossBars.Located(a.world, a.x, a.y, a.z, instance.health?.let { it.current / it.max })
            }
        }
    )

    /** Each online player's sidebar. */
    internal val sidebars = service(Sidebars(platform))

    /** The teams scripts made, each its script's, and the lines under players' name tags. */
    internal val teams = service(Teams(platform))

    /** Whose player list `player:set_listed_for` took each player out of. */
    internal val playerListings = service(PlayerListings(platform))

    /** Which players `entity:hide_from` hid each entity from. */
    internal val hiddenEntities = service(HiddenEntities(platform))

    /** The walks `mob:move_to` started, and their `path_end`. */
    internal val mobPaths = service(
        MobPaths(platform) { mob, reached ->
            val handle = mobHandle(mob)
            scripts.emit(Events.MOB_PATH_END, handle, EntityPathEndEvent(handle, reached))
        }
    )

    /** The commands modules declare (`nf.commands.register`), each gone with the module that declared it. */
    internal val commands = service(ProjectCommands(platform.commands, scripts, ArgumentValues(this)))

    /** The functions `nf.schedule` runs at a time of day, each its script's, and when they last ran. */
    internal val schedules = service(Schedules(scripts, runtime.store.scheduleRuns, { runtime.wallClock }, config.schedules.timeZone, log))

    /** Mobs' goals, and the goals scripts wrote in Lua (`mob:add_goal`), each its script's. */
    internal val mobGoals = service(MobGoals(platform, scripts) { log.warn(it) })

    /** What each scope's code costs the server, for `/nf scripts` and slow-script warnings. */
    val costs = service(
        ScriptCosts(
            scripts,
            config.performance,
            { reports.slow(it) },
            { services.fold(LinkedHashMap()) { all, service -> all.apply { putAll(service.costs()) } } },
            { ticks }
        )
    )

    /** Hands the profiler what scripts cost, named; turns the Lua state's per-function times on while it measures. */
    val profile = service(ScriptProfile(scripts, costs, runtime.profiler) { ticks })

    /** The project's particle effects and every one playing; stopped after every script, so their `unload` may still play. */
    val particles = service(ParticleEffects(platform, scripts, log, { centities }, { projectFiles }, ::problemsChanged))

    /** The project's cutscenes and every one playing; stopped like the particle effects, after every script, so `unload` may still play one. */
    val cutscenes = service(Cutscenes(platform, scripts, log, runtime.store.cutsceneStates) { projectFiles })

    /** The project's modules, the packages' included; stopped after every resource's script, which may require them. */
    val modules = service(Modules(scripts, { projectFiles }, { namespace }) { snapshot.manifestOf(it) })

    /** The project's items: the looks stacks of them take (before any window builds one), and their scripts. */
    val items = service(Items(platform, scripts, source::read, ::placeOf) { namespace })

    /** The recipes the project adds to the server, after the items they're built from. */
    val recipes = service(Recipes(platform, log, ::placeOf, items::idOf))

    /** The loot tables scripts roll (and, later, blocks' and mobs' drops), the packages' included. */
    val loot = service(LootTables(platform, items, { namespace }) { snapshot.packages.keys + namespace })

    /** The advancements the project's files have; the server learns them at start, from the start-up datapack. */
    val advancements = service(Advancements { namespace })

    /** The project's skies over worlds and players: what each player is shown of them, a step of a fade at a time. */
    val skies = service(dev.netherforge.plugin.sky.Skies(platform) { namespace })

    /** Players' and centities' stats: the modifiers scripts set and the buffs they give, counted down. */
    val stats = service(
        dev.netherforge.plugin.stats.StatSheets(
            scripts,
            { holder ->
                when (holder) {
                    is dev.netherforge.plugin.stats.StatSheets.Holder.Player ->
                        if (platform.players.get(holder.id) !=
                            null
                        ) {
                            dev.netherforge.plugin.stats.StatSheets.Presence.ACTIVE
                        } else {
                            dev.netherforge.plugin.stats.StatSheets.Presence.WAITING
                        }
                    is dev.netherforge.plugin.stats.StatSheets.Holder.Centity ->
                        if (centities.find(holder.id)?.removed ==
                            false
                        ) {
                            dev.netherforge.plugin.stats.StatSheets.Presence.ACTIVE
                        } else {
                            dev.netherforge.plugin.stats.StatSheets.Presence.GONE
                        }
                }
            }
        ) { names.spell(it) }
    )

    /** Menu windows and their scripts: shared ones exist before any module's body runs. */
    val menus = service(
        Menus(platform.menus, platform.inventories, scripts, source::read, ::placeOf, packs::skinTitle, ::measureText, wardrobe())
    )

    /** What players wear, for menu slots bound to it. */
    private fun wardrobe() = object : Menus.Wardrobe {
        override fun worn(player: UUID, slot: String) = platform.worldEntities.equipment(player, slot)

        override fun wear(player: UUID, slot: String, item: dev.netherforge.plugin.platform.ItemData?) =
            platform.worldEntities.setEquipment(player, slot, item)

        override fun online(player: UUID) = platform.players.get(player) != null
    }

    val dialogs = service(Dialogs(platform.dialogs, scripts, source::read, ::placeOf, { namespace }) { log.warn(it) })

    /** Screens (walls of maps), their fonts and the videos they play: after dialogs, before centities, whose scripts may draw on them. */
    val screens = service(
        dev.netherforge.plugin.screen.Screens(
            platform,
            scripts,
            log,
            runtime.store.screenMaps,
            config.screens,
            runtime.workers.lane("videos"),
            source::read,
            source::readBytes,
            source::resolve,
            ::placeOf
        ) { namespace }
    )

    /** Every centity instance: started after everything a script may use, stopped before it. */
    val centities = service(
        Centities(
            platform,
            scripts,
            log,
            source::read,
            ::placeOf,
            runtime.store.instances,
            handover.instances,
            { namespace },
            runtime.profiler.parts,
            { entityHandle(it) }
        ) {
            data.remove(ScriptData.Owner.Centity(it))
        }
    )

    /** The project's script tests, under a test run (`netherforge test`): the scope a test file runs in, and the tests it declared. */
    val tests = service(ScriptTests(scripts, { projectFiles }, source::read, config.testing))

    /** Centities that appear by themselves near players, from their `spawning` rules; after the instances it asks to spawn. */
    val naturalSpawner = service(NaturalSpawner(platform, scripts, log, { centities }, { it }, { namespace }))

    /** The centities the project's structures hold markers for, spawned once the structure is in the world. */
    internal val structureSpawns = service(StructureSpawns(platform, { centities }, log) { namespace })

    /** The project's blocks and every one placed in a world, held as note block states the resource pack draws; after the centities some are drawn by. */
    internal val customBlocks = service(
        CustomBlocks(
            platform, scripts, log, source::read, ::placeOf, { namespace }, { ticks },
            { items }, { loot }, { centities }, { effects }, { blockData }, ::problemsChanged, runtime.profiler.parts
        )
    )

    /** The project's terrains, given to the adapter's chunk threads; after the blocks whose states ores are written as. */
    internal val worldGenerators = service(
        WorldGenerators(
            platform,
            log,
            { customBlocks },
            { managedWorlds },
            { snapshot },
            { source.resolve(it) },
            { projectFiles },
            source::read,
            runtime.startupGenerators,
            ::problemsChanged
        )
    )

    // ---- what the services declare, gathered once -------------------------------

    /** Each handle class's liveness check, from the service that keeps that class's things. */
    private val liveness: Map<KClass<out LuaHandle>, Liveness<*>> = buildMap {
        for (service in services) {
            for (check in service.liveness()) {
                check(put(check.type, check) == null) { "two services say whether a ${check.type.simpleName} is alive" }
            }
        }
    }

    /** The service that reloads each kind: every kind in format's registry has exactly one. */
    private val reloaders: Map<KindSpec<*, *>, RuntimeService> = buildMap {
        for (service in services) {
            for (kind in service.reloads) check(put(kind, service) == null) { "two services reload ${kind.id}" }
        }
        val missing = Kinds.all.filterNot(::containsKey)
        check(missing.isEmpty()) { "a kind has no reload: ${missing.map { it.id }}" }
    }

    /** The services built from each kind (or project document), told after it reloads. */
    private val followers: Map<String, List<RuntimeService>> =
        services.flatMap { service -> service.follows.map { it to service } }.groupBy({ it.first }, { it.second })

    private val api = RuntimeApi(this)

    /** Which watched events a service needs delivered even when no script listens, and what the adapter was last told of each. */
    private fun needed(): Set<WatchedEvent> = services.flatMap { it.needsWatched }.toSet()

    private val watching = EnumMap<WatchedEvent, Boolean>(WatchedEvent::class.java)
    private var started = false

    init {
        // Events the server raises all the time: the adapter delivers them only while a script (or a service) listens.
        for ((watched, events) in GameEventDispatch.WATCHED) scripts.watch(*events.toTypedArray()) { watch(watched, it) }
    }

    /** Tells the adapter whether [event] is wanted: by a script, or by a service while the session runs. Never twice in a row the same. */
    private fun watch(event: WatchedEvent, scriptsListen: Boolean) {
        val now = scriptsListen || (started && event in needed())
        if ((watching[event] ?: false) == now) return
        watching[event] = now
        platform.watch(event, now)
    }

    // ---- lifecycle ----------------------------------------------------------------

    /**
     * Runs the project: every service takes its resources, then (unless the
     * project is refused) the Lua state starts and every service starts what
     * runs, in registration order.
     */
    internal fun start() {
        platform.particles.forget()
        val project = SessionProject(snapshot, refused = refusal != null)
        for (service in services) service.define(project)
        problemsChanged()
        for (problem in projectProblems.filter { it.severity == Severity.ERROR }) {
            platform.log.warn("${problem.file}${problem.line?.let { ":$it" }.orEmpty()}: ${problem.message}")
        }
        if (refusal != null) {
            log.error("Not running ${config.project}: ${refusal.first}")
            return
        }
        // Files kept from before each package had a folder of its own are the project's (once).
        files.adopt(namespace)
        // A dev server's debugger stops scripts through primitives of its own, and has its breakpoints in place before any runs.
        val debugging = runtime.debugger?.primitives { requireNotNull(scripts.host) { "no Lua state" } }.orEmpty()
        val host = LuaHost(api.primitives() + debugging, api, runtime.time, config.sandbox).also { it.gates = api.gates() }
        scripts.attach(host)
        runtime.debugger?.sync(host)
        running = true
        started = true
        for (event in needed()) watch(event, false)
        for (service in services) service.start()
        log.info(
            "Running \"${snapshot.manifest?.name}\": ${snapshot.running(CentityKind).size} centities, " +
                "${snapshot.everywhere(ModuleKind).size} modules, ${snapshot.running(MenuKind).size} menus, " +
                "${snapshot.running(DialogKind).size} dialogs, ${snapshot.running(ItemKind).size} items, " +
                "${snapshot.compiledResourcePacks.size} resource packs, ${centities.count()} spawned"
        )
    }

    /**
     * Stops everything, in reverse registration order, then closes the Lua
     * state: the session is over. What the next one starts from is the
     * [Handover]: the instances, and each saved table as it ended.
     */
    internal fun stop(): Handover {
        for (service in services.asReversed()) {
            try {
                service.stop()
            } catch (e: Exception) {
                log.error("NetherForge couldn't stop ${service.name}", e)
            }
        }
        scripts.detach()
        running = false
        started = false
        for (event in WatchedEvent.entries) if (watching[event] == true) watch(event, false)
        return Handover(centities.records(), data.kept())
    }

    /** Steps whose last run threw: reported once, not every tick. */
    private val failingSteps = HashSet<String>()

    /**
     * One server tick: every phase in order, each service's step on its own,
     * so one failing must not skip the rest. While the profiler measures, each
     * phase is timed with the runtime's clock (the one calls into Lua are
     * timed with, which leaves out time the debugger held the server paused)
     * and the tick is handed to it.
     */
    internal fun tick() {
        profile.sync()
        val profiler = runtime.profiler
        val clock = runtime.time
        val timed = profiler.active
        val started = if (timed) clock() else 0L
        val phases = if (timed) LinkedHashMap<String, Long>() else null
        val parts = profiler.parts
        parts.active = timed
        for (phase in TickPhase.entries) {
            val from = if (timed) clock() else 0L
            for (service in services) {
                val step = "${service.name} (${phase.name.lowercase()})"
                val serviceFrom = if (timed) clock() else 0L
                try {
                    service.tick(phase)
                    failingSteps.remove(step)
                } catch (e: Exception) {
                    if (failingSteps.add(step)) log.error("NetherForge's tick failed at $step", e)
                }
                // Each service's share of the step, when it took any time worth reading.
                if (timed) {
                    val took = clock() - serviceFrom
                    if (took >= PART_FLOOR_NANOS) parts.add("${phase.label}/${service.name}", took)
                }
            }
            phases?.put(phase.label, clock() - from)
        }
        if (phases != null) profiler.tick(ticks, clock() - started, phases)
    }

    // ---- hooks: what the server tells every service ---------------------------------

    internal fun playerJoined(player: PlayerRef) {
        for (service in services) service.playerJoined(player)
    }

    internal fun playerQuit(player: PlayerRef) {
        for (service in services) service.playerQuit(player)
    }

    /** An entity has gone for good (died, removed): what's kept for it goes, and its handlers. */
    internal fun entityGone(id: UUID) {
        for (service in services) service.entityGone(id)
        val handle = LuaHandle.Entity(id.toString())
        // Entities die all the time; only one a script listens to has anything to drop.
        if (scripts.listenedTo(handle)) scripts.dropTarget(handle)
    }

    internal fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {
        for (service in services) service.entitiesLoaded(tagged, untagged)
    }

    internal fun structureMarkers(markers: List<StructureMarker>) {
        for (service in services) service.structureMarkers(markers)
    }

    internal fun entitiesUnloading(entities: Set<UUID>) {
        for (service in services) service.entitiesUnloading(entities)
    }

    internal fun chunkLoaded(world: String, chunkX: Int, chunkZ: Int, generated: Boolean) {
        for (service in services) service.chunkLoaded(world, chunkX, chunkZ, generated)
    }

    internal fun chunkUnloading(world: String, chunkX: Int, chunkZ: Int) {
        for (service in services) service.chunkUnloading(world, chunkX, chunkZ)
    }

    internal fun worldLoaded(world: String) {
        for (service in services) service.worldLoaded(world)
    }

    internal fun worldSaving(world: String) {
        for (service in services) service.worldSaving(world)
    }

    internal fun pluginsChanged() {
        for (service in services) service.pluginsChanged()
    }

    /**
     * Whether the thing a handle names is there to listen to, for `:on` on a
     * handle: asked of the service that keeps its class; anything no service
     * keeps (a player, a world) is.
     */
    internal fun alive(handle: LuaHandle): Boolean = liveness[handle::class]?.alive(handle) ?: true

    // ---- reload -----------------------------------------------------------------

    /**
     * Reloads [resources], each once, kind by kind in the registry's order
     * ([Kinds.all]), each kind by the service that reloads it and then told to
     * those that follow it. [documents]: the project documents that were
     * saved (the default font, the game's block looks), which services built
     * from them hear first. The project is read again either way.
     */
    internal fun reload(resources: List<Resource>, documents: Set<String>): ReloadResult {
        snapshot = load()
        val batch = ReloadBatch(snapshot, this)
        for (document in documents) for (service in followers[document].orEmpty()) service.followed(document, emptySet(), batch)
        if (resources.isEmpty()) return ReloadResult(emptyList())
        for (resource in resources) batch.add(resource)
        var startup = false
        for (kind in Kinds.all) {
            val ids = batch.take(kind)
            if (ids.isEmpty()) continue
            reloaders.getValue(kind).reload(kind, ids, batch)
            for (service in followers[kind.id].orEmpty()) service.followed(kind.id, ids, batch)
            if (kind in StartupDatapack.kinds) startup = true
        }
        // What the server learns only as it starts: whether it still is what the files say.
        val restart = startup && runtime.datapack.check()
        // Scripts that restarted took their runtime problems with them.
        problemsChanged()
        data.saveAll()
        return ReloadResult(batch.results, restart)
    }

    // ---- problems ---------------------------------------------------------------

    /** What's wrong with the project as it reads: the format's findings and the runtime's own (a refusal). */
    val projectProblems: List<Problem> get() = snapshot.problems + loadProblems + listOfNotNull(refusal?.second)

    /** Everything wrong now, as the editor's problems list shows it: the project's, then every service's. */
    fun problems(): List<Problem> = projectProblems + runtime.datapack.problems() + services.flatMap { it.problems() }

    /** Tells the editor the problems changed. */
    fun problemsChanged() = log.notify(Bridge.problems, Problems(problems()))

    // ---- helpers ----------------------------------------------------------------

    /**
     * The width MiniMessage text draws at in the default font, from the
     * project's `fonts/default.json` and the packs' glyphs; null when it
     * can't be measured exactly (no font file, a character it doesn't cover).
     */
    internal fun measureText(text: String): Int? {
        val font = snapshot.defaultFont ?: return null
        return TextWidth(font, packs::glyphAdvance).measure(text)
    }

    /** `<at>` tags already reported as unplaceable, so text sent every tick warns once. */
    private val unplacedAts: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * MiniMessage with each `<at>` turned into the moves that place it
     * ([TextWidth.layout]), measured like [measureText]. Without
     * `fonts/default.json` only insides and lines that need no character
     * widths (glyphs, spaces) can be placed. Any other `<at>` draws in line,
     * and is warned about once.
     */
    internal fun layoutText(text: String): String {
        if (!text.contains("<at", ignoreCase = true)) return text
        val font = snapshot.defaultFont
        val width = if (font != null) TextWidth(font, packs::glyphAdvance) else TextWidth({ null }, packs::glyphAdvance)
        val layout = width.layout(text)
        for (tag in layout.unplaced) {
            if (unplacedAts.size < MAX_REPORTED_ATS && unplacedAts.add(tag)) {
                log.warn(
                    "$tag draws what it holds in line: it, or the line it's counted from, can't be measured " +
                        "(fonts/default.json is missing or doesn't cover a character, or a glyph's width is unknown)"
                )
            }
        }
        return layout.text
    }

    /** Lua shortens long chunk names to `...tail.lua`; this finds the project file it meant. */
    internal fun projectPath(file: String): String {
        if (!file.startsWith("...")) return file
        val tail = file.removePrefix("...")
        return projectFiles.firstOrNull { it.endsWith(tail) } ?: file
    }

    internal fun scriptLog(scope: Scope?, message: String, file: String?, line: Int?) {
        log.script(scope?.owner?.label ?: "script", message, file?.let { SourceRef(projectPath(it), line) })
    }

    /** Loads the project (or bundle) and the packages it depends on. */
    private fun load(): ProjectSnapshot {
        val load = source.load(platform.game)
        projectFiles = load.files
        loadProblems = load.problems
        return load.snapshot
    }

    private fun refusal(snapshot: ProjectSnapshot): Pair<String, Problem?>? {
        // A bundle that isn't what it was built as, or dependencies a production server won't resolve.
        loadProblems.firstOrNull { it.severity == Severity.ERROR }?.let { return "${it.file}: ${it.message}" to null }
        // An older or newer format among them: its message says which version the project is in.
        snapshot.problems.firstOrNull { it.file == ProjectManifest.FILE_NAME && it.severity == Severity.ERROR }?.let {
            return "${ProjectManifest.FILE_NAME}: ${it.message}" to null
        }
        val manifest = snapshot.manifest ?: return "there's no readable ${ProjectManifest.FILE_NAME}" to null
        val target = MinecraftVersion.parse(manifest.minecraft)
        val supported = platform.info.supportedTargets
        if (target == null || supported.none { MinecraftVersion.parse(it) == target }) {
            val message = "This project targets Minecraft ${manifest.minecraft}, but this server runs NetherForge for Minecraft " +
                "${supported.joinToString(" or ")}. Run it on a matching server, or change \"minecraft\" in ${ProjectManifest.FILE_NAME}."
            return message to
                ProblemCodes.RUNTIME_MINECRAFT_UNSUPPORTED.at(ProjectManifest.FILE_NAME, message, "$.minecraft")
        }
        return null
    }

    /** The services, in registration order: for the structural test that nothing is wired outside this list. */
    internal fun services(): List<RuntimeService> = services.toList()
}

/**
 * What one session leaves the next: what outlives a project's Lua state by
 * design. The spawned centities (so they reattach), and each saved table's
 * encoding as it ended (so a full reload doesn't go back to the disk).
 */
/** Sessions made in this JVM, for [ProjectSession.generation]. */
private val GENERATIONS = AtomicInteger()

class Handover(val instances: List<InstanceRecord>, val data: Map<ScriptData.Owner, ScriptData.Kept>)

/** A service's share of a step below this (a microsecond) isn't worth a part of its own in the profile. */
private const val PART_FLOOR_NANOS = 1_000L

/** How many unplaceable `<at>` tags a session warns about before it stops. */
private const val MAX_REPORTED_ATS = 50
