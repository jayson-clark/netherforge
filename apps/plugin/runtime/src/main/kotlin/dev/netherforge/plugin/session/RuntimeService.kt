package dev.netherforge.plugin.session

import dev.netherforge.format.Problem
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Loaded
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.StructureMarker
import dev.netherforge.plugin.platform.WatchedEvent
import dev.netherforge.plugin.script.Scope
import java.util.UUID
import kotlin.reflect.KClass

/**
 * One part of a [ProjectSession]: centities, menus, modules, boss bars, the
 * saved tables… Each takes the hooks it needs and leaves the rest; the
 * session calls every service's hook in the order the services were
 * registered ([ProjectSession]'s one list), except [stop], which runs in
 * reverse, so what started last stops first.
 *
 * A new subsystem is one class implementing this, registered in that list:
 * nothing else in the runtime names it to start it, stop it, tick it, tell
 * it who joined or reload its kind.
 */
interface RuntimeService {
    /** How the tick names a step of this service that failed. */
    val name: String

    /**
     * Takes the project's resources, before any script runs (the Lua state
     * doesn't exist yet). A project the runtime refuses defines nothing:
     * [project] is then [SessionProject.refused] and runs nothing.
     */
    fun define(project: SessionProject) {}

    /** Starts what runs: scripts' bodies, shared windows. The Lua state exists (unless the project was refused). */
    fun start() {}

    /** Stops what runs, the session ending: in reverse registration order, before the Lua state closes. */
    fun stop() {}

    /** One step of the server's tick: called for every [TickPhase], in order, and acting on those it has work in. */
    fun tick(phase: TickPhase) {}

    /**
     * A scope stopped for good (it closed, or its body failed and it was
     * disabled), its own subscriptions already gone: what it made goes now.
     */
    fun scopeReleased(scope: Scope) {}

    fun playerJoined(player: PlayerRef) {}

    fun playerQuit(player: PlayerRef) {}

    /** An entity died or was removed for good. */
    fun entityGone(id: UUID) {}

    /** Entities came into the world with their chunk: NetherForge's own by [tagged], the rest [untagged]. */
    fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {}

    /** Structure markers came into the world with their chunk, or are still waiting after a reload: [markers] ask for centities. */
    fun structureMarkers(markers: List<StructureMarker>) {}

    /** Entities are about to unload with their chunk, while they can still be written. */
    fun entitiesUnloading(entities: Set<UUID>) {}

    /** A chunk came into memory (loaded, or [generated] for the first time), with its blocks readable. */
    fun chunkLoaded(world: String, chunkX: Int, chunkZ: Int, generated: Boolean) {}

    /**
     * The server events this service needs the adapter to deliver even when no
     * script listens ([chunkLoaded]): a watched event costs to watch, so it
     * is only asked for by who needs it.
     */
    val needsWatched: Set<WatchedEvent> get() = emptySet()

    /** A chunk is about to unload, while its blocks can still be written. */
    fun chunkUnloading(world: String, chunkX: Int, chunkZ: Int) {}

    /** A world was loaded or created (by anyone: the server, another plugin, a script). */
    fun worldLoaded(world: String) {}

    /** A world is being saved (an autosave, `/save-all`). */
    fun worldSaving(world: String) {}

    /** Another plugin was enabled or disabled, or a service provider (an economy) registered or went: look again at what the server has. */
    fun pluginsChanged() {}

    /** What's wrong that this service knows about, for the editor's problems list. */
    fun problems(): List<Problem> = emptyList()

    /** Things a scope owns here, counted per scope for `/nf scripts`, by the word it shows them as (`"effects"`). */
    fun costs(): Map<String, (Scope) -> Int> = emptyMap()

    /** How the event core tells whether the thing a handle names is still there to listen to, for the handle classes this service keeps. */
    fun liveness(): List<Liveness<*>> = emptyList()

    /** The kinds this service reloads ([reload]): every kind in format's registry has exactly one service that does. */
    val reloads: Set<KindSpec<*, *>> get() = emptySet()

    /** Reloads resources of one of [reloads]: [ids], each once, as one step of [batch]. */
    fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {}

    /**
     * The kinds (by [KindSpec.id], or a project document's id) this service
     * is built from: [followed] is called right after a reload of one of
     * them, in that kind's turn.
     */
    val follows: Set<String> get() = emptySet()

    /** Resources of [kind], one of [follows], were reloaded ([ids]; none for a project document). */
    fun followed(kind: String, ids: Set<String>, batch: ReloadBatch) {}
}

/** The steps of a server tick, in order. Within one, services run in registration order; each step on its own, so one failing skips nothing else. */
enum class TickPhase {
    /** Timers that are due: `nf.after`, `nf.every`, a task's wait. */
    TIMERS,

    /**
     * What finished off the main thread is carried on with: services' next
     * steps, then the scripts that waited for it (callbacks, tasks woken with
     * `value, err`), in the order it finished (`AsyncWork`, `Completions`).
     */
    ASYNC,

    /** `nf.on("tick")`. */
    EVENTS,

    /** The world moves: centities (animate, path, physics, `tick`, sync), mobs' walks and goals. */
    WORLD,

    /** What follows the world: particle effects play where centities are now. */
    EFFECTS,

    /** Windows, items and recipes catch up with what changed. */
    UPKEEP,

    /** What the tick cost and what Lua let go of. */
    ACCOUNTS,

    /** What's written down: the instance index, saved tables at autosave. */
    SAVE;

    /** How the profiler names it: `timers`, `world`. */
    val label: String get() = name.lowercase()
}

/** Whether the thing a handle of class [type] names is still there (a centity, a window, a playing effect). */
class Liveness<H : LuaHandle>(val type: KClass<H>, private val check: (H) -> Boolean) {
    @Suppress("UNCHECKED_CAST")
    fun alive(handle: LuaHandle): Boolean = check(handle as H)
}

/** A [Liveness] for handles of class [H]. */
inline fun <reified H : LuaHandle> liveness(noinline check: (H) -> Boolean) = Liveness(H::class, check)

/** The project a session runs, as services see it: what's running of each kind, or nothing when it was refused. */
class SessionProject internal constructor(val snapshot: ProjectSnapshot, val refused: Boolean) {
    /** What the server runs of [kind], packages' included (`ProjectSnapshot.running`); nothing for a refused project. */
    fun <C> running(kind: KindSpec<*, C>): Map<String, C> = if (refused) emptyMap() else snapshot.running(kind)

    /** Every resource of [kind] that read, packages' included, errors or not (`ProjectSnapshot.everywhere`); nothing for a refused project. */
    fun <T, C> everywhere(kind: KindSpec<T, C>): Map<String, Loaded<T, C>> = if (refused) emptyMap() else snapshot.everywhere(kind)
}
