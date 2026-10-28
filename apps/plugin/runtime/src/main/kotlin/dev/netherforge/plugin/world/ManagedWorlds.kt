package dev.netherforge.plugin.world

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.dimensiontype.DimensionTypeKind
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.ProjectManifest
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.WorldConfig
import dev.netherforge.format.world.MapKind
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.async.AsyncWork
import dev.netherforge.plugin.async.WorkFailed
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.WorldManagerOps
import dev.netherforge.plugin.platform.WorldSettings
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.store.Store
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CompletionStage

/**
 * The worlds a project makes and may take away (`nf.worlds.create`, `copy`,
 * `world:unload`).
 *
 * Any script may find and load any world, but only a **managed** one can be
 * unloaded or deleted: one the project's scripts created, or one named in
 * `netherforge.json`'s `managedWorlds`, and never the server's main world.
 * So a script can't delete the main world, or another plugin's.
 *
 * The worlds scripts created are kept in the store under the project's
 * namespace, so they stay the project's across reloads and restarts; a world
 * deleted through `unload` is forgotten. The store keeps how each was made:
 * the server doesn't remember a plugin's generator, and a world made with a
 * project dimension type isn't loaded while the server lacks the type (the
 * server would make it the overworld's height, and lose what's outside). The worlds themselves are the
 * server's: a module restarting or a project reload leaves them loaded, and
 * scripts find them again by name.
 *
 * A copy's files, and a deleted world's, are the runtime's workers' to deal
 * with, in one lane, so they happen in the order they were asked for; the
 * adapter only says what the file work is. A copy is then loaded on the main
 * thread as this session's next step ([AsyncWork.mainThread]), and its world
 * goes to the script that asked (its callback, or its task) through
 * [AsyncWork]: a script that stops first isn't called, and the world is made
 * and kept anyway. A session that ends first (a full reload) leaves the
 * copy's files in place, unloaded and still the project's: `nf.worlds.load`
 * loads it.
 */
internal class ManagedWorlds(
    private val platform: Platform,
    private val async: AsyncWork,
    private val log: RuntimeLog,
    private val store: Store.Worlds,
    /** Whose worlds these are: the project's namespace. */
    private val namespace: String,
    /** The project's own folder (the configured one, or the project's inside a bundle). */
    private val project: () -> Path,
    private val snapshot: () -> ProjectSnapshot?
) : RuntimeService {
    override val name get() = "managed worlds"

    /** Worlds the project made (its scripts, or its `netherforge.json`), by name, with how each was made. */
    private val created = LinkedHashMap(store.of(namespace))

    /** The new worlds' names of the copies being made. */
    private val copying = HashSet<String>()

    /** Copies and deletions, one at a time in the order asked for: a world deleted and copied again under its name is never mixed up. */
    private val files = async.workers.lane(FILES)

    private val ops: WorldManagerOps get() = platform.worldManager

    override val reloads: Set<KindSpec<*, *>> get() = setOf(MapKind, DimensionTypeKind)

    /**
     * A map is read only when a world is copied from it: later copies see the change, nothing else does. A
     * dimension type is the server's from its start (the start-up datapack's check says a change needs a restart).
     */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) batch.data(Resource(kind, id))
    }

    /** Whether the project may unload [name]: created by its scripts or named in `managedWorlds`, and not the main world. */
    fun isManaged(name: String): Boolean {
        if (name == platform.worlds.defaultWorld()) return false
        return name in created || name in snapshot()?.manifest?.managedWorlds.orEmpty()
    }

    private fun checkNewName(name: String) {
        if (!Names.isId(name)) throw LuaApiException("\"$name\" can't be a new world's name (${Names.ID_RULE})")
        // Before the files: a copy's files can be in place while it's still being loaded.
        if (name in copying) throw LuaApiException("a world named \"$name\" is being copied already")
        if (platform.worlds.exists(name) || ops.isSaved(name)) {
            throw LuaApiException("there's already a world named \"$name\" (nf.worlds.load loads a saved one)")
        }
    }

    /**
     * Makes a world: true when the server made it, and it's the project's from now on. A dimension type the server
     * doesn't have is an error: it learns them only as it starts.
     */
    fun create(name: String, settings: WorldSettings): Boolean {
        checkNewName(name)
        val dimension = settings.dimensionType
        if (dimension != null && dimension !in ops.dimensionTypes()) {
            throw LuaApiException("the server has no dimension type \"$dimension\": $RESTART")
        }
        if (!ops.create(name, settings)) return false
        own(name, Store.OwnedWorld(settings.environment, settings.terrain, settings.dimensionType))
        return true
    }

    /**
     * The worlds `netherforge.json` makes (its `worlds` that name a terrain or a dimension type), as
     * the project starts: each made when the server has none by that name, and loaded with its
     * generator when the server has it saved and not loaded; then the project's, like one a script
     * made. [generatorOf] names a reference as the generators are named, [dimensionOf] as the
     * server knows a dimension type (null: it names none). The server's main world is loaded
     * already, with the generator the server took as it started ([StartupGenerators]) and the
     * dimension the start-up datapack gave the overworld; any world that's loaded is left as it
     * is. What a world can't have is a `runtime.dimension-type` problem: a dimension type the server
     * doesn't have (the world isn't made or loaded), or another than the one it was made with (it
     * keeps that).
     */
    fun ensureMade(configs: Map<String, WorldConfig>, generatorOf: (String) -> String?, dimensionOf: (String) -> String?): List<Problem> {
        val problems = mutableListOf<Problem>()
        val main = platform.worlds.defaultWorld()
        for ((name, config) in configs) {
            val id = config.terrain?.let { generatorOf(it.text) }
            val dimension = config.dimensionType?.let { dimensionOf(it.text) }
            if (id == null && dimension == null) continue
            val at = CanonicalJson.childPath(CanonicalJson.childPath("$.worlds", name), "dimensionType")
            fun problem(message: String) {
                log.warn(message)
                problems += ProblemCodes.RUNTIME_DIMENSION_TYPE.at(ProjectManifest.FILE_NAME, message, at)
            }
            val owned = created[name]
            when {
                name == main -> {}
                platform.worlds.exists(name) || ops.isSaved(name) -> {
                    val made = owned?.dimensionType
                    if (dimension != null && made != dimension) {
                        val how = if (made != null) "with dimension type \"$made\"" else "without a dimension type of the project's"
                        problem(
                            "World \"$name\" was made $how, and keeps it: netherforge.json's \"$dimension\" is for a world " +
                                "made afresh (delete this one to have it made so)"
                        )
                    }
                    if (platform.worlds.exists(name)) continue
                    if (made != null && made !in ops.dimensionTypes()) {
                        problem(
                            "World \"$name\" isn't loaded: it was made with dimension type \"$made\", which the server doesn't have ($RESTART)"
                        )
                        continue
                    }
                    if (ops.load(name, NORMAL, id)) {
                        own(name, Store.OwnedWorld(NORMAL, id, made))
                    } else {
                        log.warn("Couldn't load world \"$name\"")
                    }
                }
                dimension != null && dimension !in ops.dimensionTypes() -> problem(
                    "World \"$name\" isn't made: the server has no dimension type \"$dimension\" ($RESTART)"
                )
                else -> {
                    val settings = WorldSettings(
                        "normal",
                        NORMAL,
                        config.seed,
                        structures = true,
                        keepSpawnLoaded = false,
                        terrain = id,
                        dimensionType = dimension
                    )
                    val made = ops.create(name, settings)
                    if (made) own(name, Store.OwnedWorld(NORMAL, id, dimension)) else log.warn("Couldn't make world \"$name\"")
                }
            }
        }
        return problems
    }

    /**
     * Loads a saved world; true when it's loaded (it may have been already). One the project made with a dimension
     * type the server doesn't have (now) is an error rather than a world loaded at the overworld's height.
     */
    fun load(name: String): Boolean {
        if (!Names.isWorldName(name)) throw LuaApiException("\"$name\" isn't a world's name (${Names.WORLD_NAME_RULE})")
        if (platform.worlds.exists(name)) return true
        if (name in copying) return false
        val ops = ops
        val owned = created[name]
        if (!ops.isSaved(name)) return false
        val dimension = owned?.dimensionType
        if (dimension != null && dimension !in ops.dimensionTypes()) {
            throw LuaApiException("world \"$name\" was made with dimension type \"$dimension\", which the server doesn't have: $RESTART")
        }
        return ops.load(name, owned?.environment ?: NORMAL, owned?.terrain)
    }

    /**
     * Copies map [map] into a new world [name], the project's from
     * now on: its files on a worker, then loaded on the main thread. The world
     * once it's loaded, or a [WorkFailed] saying why there's none (the log
     * says so too). A mistake (no such map, a name that's taken) throws
     * at once, before anything starts.
     */
    fun copy(map: String, name: String): CompletionStage<LuaHandle.World> {
        val known = snapshot()?.models(MapKind).orEmpty()
        if (map !in known) throw LuaApiException(notInProject("map", map, known.keys.toList()))
        checkNewName(name)
        val work = ops.copy(project().resolve(snapshot()!!.placeOf(MapKind, map).location).normalize(), name)
        copying += name
        own(name, Store.OwnedWorld(NORMAL))
        return async.submit(files) { work.run() }.handleAsync({ _, failure ->
            copying -= name
            val why = when {
                failure != null -> "couldn't copy its files (${failure.message})"
                !ops.load(name, NORMAL) -> "the server couldn't load the copy"
                else -> return@handleAsync LuaHandle.World(name)
            }
            forget(name)
            log.warn("Couldn't copy map \"$map\" into world \"$name\": $why")
            throw WorkFailed("couldn't copy map \"$map\" into world \"$name\": $why")
        }, async.mainThread)
    }

    /**
     * Unloads [name]: its players go to [moveTo]'s spawn first. With [delete]
     * its files go too (on a worker), and the project forgets it. False when
     * it had gone or the server kept it.
     */
    fun unload(name: String, save: Boolean, delete: Boolean, moveTo: String?): Boolean {
        if (name == platform.worlds.defaultWorld()) throw LuaApiException("the server's main world can't be unloaded")
        if (!isManaged(name)) {
            throw LuaApiException(
                "\"$name\" isn't this project's to unload: only worlds its scripts created, or that netherforge.json names in managedWorlds, can be"
            )
        }
        if (moveTo == name) throw LuaApiException("move_players_to is the world being unloaded")
        val ops = ops
        if (!platform.worlds.exists(name)) return false
        val destination = moveTo ?: platform.worlds.defaultWorld()
        val spawn =
            platform.worlds.spawnLocation(destination)
                ?: throw LuaApiException("there's no world named \"$destination\" to move players to")
        for (player in platform.players.online()) {
            if (platform.players.location(player.uuid)?.world == name) platform.players.teleport(player.uuid, spawn)
        }
        if (!ops.unload(name, save && !delete)) return false
        if (delete) {
            val work = ops.delete(name)
            files.execute {
                try {
                    work.run()
                } catch (e: IOException) {
                    // Nobody waits for it, so a failure is the log's alone.
                    async.mainThread.execute { log.warn("Couldn't delete all of world \"$name\"'s files: ${e.message}") }
                }
            }
            forget(name)
        }
        return true
    }

    private fun own(name: String, owned: Store.OwnedWorld) {
        created[name] = owned
        store.own(namespace, name, owned)
    }

    /** [name] is gone (deleted, or its copy failed): nobody's any more. */
    private fun forget(name: String) {
        created.remove(name)
        store.forget(name)
    }

    private companion object {
        /** The environment of a world nobody here made, and of every copy of a map. */
        const val NORMAL = "normal"

        /** The workers' lane for worlds' files. */
        const val FILES = "world files"

        /** Why the server lacks a dimension type the project has. */
        const val RESTART = "it learns them only as it starts, so one added or fixed since needs a restart"
    }
}
