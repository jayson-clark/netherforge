package dev.netherforge.plugin.centity

import dev.netherforge.format.bridge.InstanceInfo
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.centity.CompiledCentity
import dev.netherforge.format.centity.ModelDisplay
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.ResourcePlace
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.RuntimeLog
import dev.netherforge.plugin.binding.AnimationEvent
import dev.netherforge.plugin.binding.CentityBumpEvent
import dev.netherforge.plugin.binding.CentityEvent
import dev.netherforge.plugin.binding.CentityLandEvent
import dev.netherforge.plugin.binding.CentityPathEndEvent
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.TickEvent
import dev.netherforge.plugin.binding.TouchEvent
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DisplayLook
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.profile.TickParts
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.script.Scope
import dev.netherforge.plugin.script.ScopeOwner
import dev.netherforge.plugin.script.Scripts
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import dev.netherforge.plugin.session.SessionProject
import dev.netherforge.plugin.session.TickPhase
import dev.netherforge.plugin.session.liveness
import dev.netherforge.plugin.store.Store
import java.util.UUID
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * Every live centity instance, and the per-tick pipeline that drives them:
 *
 *  1. **animate**: playing clips advance and pose the nodes; then a walk
 *     `move_to` started moves the whole centity a step (see
 *     [CentityPaths]); then **physics** moves the bodies (see [Physics]);
 *  2. **scripts**: `animation_end` handlers, then `tick` handlers (only for
 *     an instance something listens to `tick` on); a script's write lands on
 *     top of the animated pose;
 *  3. **sync**: compose world matrices and push only what changed to the
 *     entities.
 *
 * Only sync touches the world, so an instance nothing changed costs nothing,
 * and an instance whose chunk isn't loaded is skipped entirely until it is.
 *
 * Every display rides the instance's anchor entity (an invisible vehicle at
 * the anchor), and so does every hitbox sitting at the anchor, so moving a
 * centity is one teleport. A hitbox off the anchor can't ride (an
 * interaction entity has no transform to carry an offset in) and is moved
 * on its own. While only clips move a node, its display is sent poses ahead
 * for the client to ease to ([Keyframes]), not one every tick.
 *
 * The centity's events are raised from here (and from [Physics] for a
 * node's body): `spawn` after a new instance's script has run, `remove`
 * before it unloads, `chunk_load` and `chunk_unload` as its anchor's chunk
 * comes and goes, and clicks along their path from node to `nf`.
 *
 * Instances whose definition is gone (deleted, or never loaded) are kept as
 * **inert** records: their entities stay where they are and they come back to
 * life when the definition does, rather than being destroyed by a typo.
 *
 * The instances outlive the session: it starts from the last one's [records]
 * (or the store's, on enable). The store hears of an instance's identity
 * (spawned, removed, its entities, its centity) at the end of the tick that
 * changed it. Its place and facing change all the time (a turret aiming, a
 * body falling, a walk) and are kept in memory; the store gets them when
 * they'd be saved anyway: when the world saves, when the anchor's chunk
 * unloads and when the session stops. So the store's anchors are as old as
 * the world's own entities after a crash, and a centity turning every tick
 * writes nothing.
 */
class Centities(
    private val platform: Platform,
    private val scripts: Scripts,
    private val log: RuntimeLog,
    private val readSource: (String) -> String?,
    /** Where a resource is now (in its group folders), by kind and id: where its script is read from. */
    private val placeOf: (KindSpec<*, *>, String) -> ResourcePlace,
    /** Where instances are kept. */
    private val store: Store.Instances,
    /** The instances the session starts with, reattached as it defines the centities. */
    private val restored: List<InstanceRecord>,
    /** The project's namespace: records name centities in full, the registry as the project does. */
    private val home: () -> String,
    /** Where the profiler takes what each step of an instance's tick costs (`world/centities/sync`). */
    private val parts: TickParts = TickParts.OFF,
    /** The handle an entity is given in an event (a `Player`, a `Mob`...): the session's, which asks the server what it is. */
    private val entityHandle: (UUID) -> LuaHandle.Entity = { LuaHandle.Entity(it.toString()) },
    /** An instance was removed for good (or its inert record was): what's kept for it elsewhere goes too. */
    private val onRemoved: (UUID) -> Unit = {}
) : RuntimeService {
    override val name get() = "centities"

    /** Instances whose identity changed since the store last heard: written at the end of the tick. */
    private val dirty = LinkedHashSet<UUID>()

    /** Each instance's place and facing as the store has them. */
    private val stored = HashMap<UUID, Placement>()

    private data class Placement(val anchor: Location, val yaw: Double)

    private val definitions = LinkedHashMap<String, CompiledCentity>()
    private val instances = LinkedHashMap<UUID, Instance>()
    private val inert = LinkedHashMap<UUID, InstanceRecord>()

    /** Entity → the instance and node owning it. */
    private val owners = HashMap<UUID, Pair<Instance, String>>()

    val physics = Physics(platform, this)

    /** Centities' `body`: walking, falling and jumping against the blocks. */
    internal val movers = Movers(platform)

    /** Centities walking where `move_to` sent them. */
    val paths = CentityPaths(platform, this)

    fun definition(id: String): CompiledCentity? = definitions[id]

    fun definitionIds(): List<String> = definitions.keys.sorted()

    fun all(): List<Instance> = instances.values.toList()

    /** Every instance's hitboxes by where they are, for physics and the spatial queries. */
    internal val obstacles = ObstacleGrid(::boxesOf)

    /** `world:overlap_*`, `sweep`, `nearest`: hitboxes here and the server's entities' boxes. */
    internal val spatial = SpatialQueries(platform, this)

    /** Centities' health, damage and death, and their bars. */
    internal val healths = Healths(scripts, this)

    /** Centities flying as projectiles (`launch`). */
    /** Clicks on centities' hitboxes, and rays against them. */
    private val clicks = CentityClicks(scripts, { owners[it] }, obstacles, ::boxesOf, ::loaded, ::keep)

    internal val flights = Flights(platform, scripts, spatial, entityHandle, ::remove)

    fun find(id: UUID): Instance? = instances[id]

    fun find(id: String): Instance? = runCatching { UUID.fromString(id) }.getOrNull()?.let { instances[it] }

    fun count(): Int = instances.size + inert.size

    /** Takes every definition and rebuilds the instances from [restored] (inert where their centity is gone), without starting their scripts. */
    override fun define(project: SessionProject) {
        definitions.clear()
        definitions.putAll(project.running(CentityKind))
        for (record in restored) restoreOne(record)
    }

    override fun start() = startAll()

    /** Scripts stop; the store gets what changed, places and facings included. */
    override fun stop() {
        // A temporary instance belongs to scripts that are stopping: nothing of it is kept.
        for (instance in instances.values.toList()) if (instance.temporary) remove(instance)
        stopAll()
        flush()
        savePlacements { true }
    }

    override fun tick(phase: TickPhase) {
        when (phase) {
            TickPhase.WORLD -> pass()
            TickPhase.SAVE -> flush()
            else -> {}
        }
    }

    /** The world is saving its entities: the store gets where its instances stand now, as of the same moment. */
    override fun worldSaving(world: String) = savePlacements { it.anchor.world == world }

    /** The chunk goes, and the entities standing at its instances' anchors with it: their places are saved now. */
    override fun chunkUnloading(world: String, chunkX: Int, chunkZ: Int) = savePlacements {
        it.anchor.world == world && chunk(it.anchor.x) == chunkX && chunk(it.anchor.z) == chunkZ
    }

    /** Every place and facing that changed, for `/nf data` and tests: as the world saving would. */
    fun savePlacements() = savePlacements { true }

    private fun savePlacements(which: (Instance) -> Boolean) {
        for (instance in instances.values) {
            if (!instance.transient && which(instance) && stored[instance.id] != placement(instance)) put(record(instance))
        }
    }

    private fun chunk(coordinate: Double) = Math.floorDiv(kotlin.math.floor(coordinate).toLong(), 16L).toInt()

    private fun placement(instance: Instance) = Placement(instance.anchor, instance.yaw)

    override fun liveness() = listOf(
        liveness<LuaHandle.Centity> { find(it.id)?.takeIf { instance -> !instance.removed } != null },
        liveness<LuaHandle.Node> { node -> find(node.centity)?.takeIf { !it.removed }?.indexOf(node.name) != null }
    )

    override val reloads: Set<KindSpec<*, *>> get() = setOf(CentityKind)

    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            batch.resource(Resource(CentityKind, id), batch.snapshot.running(CentityKind)[id]) { reload(id, it) }
        }
    }

    /** [id]'s identity changed (it was spawned or removed, went inert or came back, its entities changed): the store hears at the end of the tick. */
    private fun changed(id: UUID) {
        dirty += id
    }

    /** Stages what changed for the store: each instance (or inert record) as it is now, or its removal. */
    private fun flush() {
        if (dirty.isEmpty()) return
        for (id in dirty) {
            val instance = instances[id]
            // A temporary instance is never in the store: the store knows an instance only once `keep` makes it a normal one.
            if (instance?.transient == true) continue
            val record = instance?.let(::record) ?: inert[id]
            if (record != null) {
                put(record)
            } else if (stored.remove(id) != null) {
                store.delete(id)
            }
        }
        dirty.clear()
    }

    private fun put(record: InstanceRecord) {
        stored[record.id] = Placement(record.anchor, record.yaw)
        store.put(record)
    }

    private fun restoreOne(record: InstanceRecord): Instance? {
        val id = record.id
        if (!record.natural) stored[id] = Placement(record.anchor, record.yaw)
        val definition = ResourceRef(record.centity).nameIn(home())?.let { definitions[it] }
        if (definition == null) {
            // A temporary one has nothing worth keeping inert: its entities go.
            if (record.natural) discard(record) else inert[id] = record
            return null
        }
        inert.remove(id)
        val instance = Instance(id, definition, record.anchor, record.yaw)
        instance.natural = record.natural
        instance.displays.putAll(record.displays)
        instance.hitboxes.putAll(record.hitboxes)
        instance.anchorEntity = record.anchorEntity
        instance.orphans.addAll(record.orphans)
        instances[id] = instance
        obstacles.add(instance)
        own(instance)
        healths.adopt(instance)
        autoplay(instance)
        return instance
    }

    /** A temporary instance whose centity is gone: its entities are removed, as an inert one's are when it's removed. */
    private fun discard(record: InstanceRecord) {
        entitiesOf(record).forEach { platform.entities.remove(it) }
        onRemoved(record.id)
    }

    private fun entitiesOf(record: InstanceRecord) =
        listOfNotNull(record.anchorEntity) + record.displays.values + record.hitboxes.values + record.orphans

    private fun own(instance: Instance) {
        instance.anchorEntity?.let { owners[it] = instance to ANCHOR_NODE }
        instance.displays.forEach { (node, entity) -> owners[entity] = instance to node }
        instance.hitboxes.forEach { (node, entity) -> owners[entity] = instance to node }
    }

    private fun autoplay(instance: Instance) {
        for (clip in instance.definition.autoplay) instance.animations.play(clip)
        instance.animate()
    }

    /**
     * Starts every restored instance's script, without `spawn`. One a module
     * spawned while starting already runs its own.
     */
    private fun startAll() {
        for (instance in instances.values.toList()) {
            if (instance.scope == null && !instance.removed) startScripts(instance, fresh = false)
        }
    }

    /**
     * Puts a new instance of [centity] into the world at [at], facing [yaw]
     * (the location's own facing is ignored). Its entities exist, its
     * script's body has run and `spawn` has been heard by the time this
     * returns. Null when there's no such centity or no such world. A
     * [natural] one is temporary (see [Instance.natural]): the spawner makes those. A [temporary] one
     * (see [Instance.temporary]) goes with the scope [owner] and, given [lifetime], that many ticks from now.
     */
    fun spawn(
        centity: String,
        at: Location,
        yaw: Double = 0.0,
        natural: Boolean = false,
        temporary: Boolean = false,
        owner: Int? = null,
        lifetime: Long? = null
    ): Instance? {
        val definition = definitions[centity] ?: return null
        if (!platform.worlds.exists(at.world)) return null
        val instance = Instance(UUID.randomUUID(), definition, at.copy(yaw = 0.0, pitch = 0.0), yaw)
        // Before anything is made: its entities are made temporary as they're claimed, and its script's `spawn` can ask.
        instance.natural = natural
        instance.temporary = temporary || lifetime != null
        if (instance.temporary) {
            instance.owner = owner
            instance.expires = lifetime?.let { scripts.now + it }
        }
        instances[instance.id] = instance
        obstacles.add(instance)
        healths.adopt(instance)
        autoplay(instance)
        sync(instance)
        changed(instance.id)
        startScripts(instance, fresh = true)
        return instance
    }

    /** Runs the centity's script's body; then, for a new instance, `spawn`. */
    private fun startScripts(instance: Instance, fresh: Boolean) {
        val definition = instance.definition
        instance.chunkLoaded = loaded(instance)
        val script = definition.script
        if (script != null) {
            val path = placeOf(CentityKind, definition.id).file(script.file)
            val source = readSource(path)
            if (source != null) {
                instance.scope = scripts.start(ScopeOwner.CentityScript(instance.id, definition.id, path), script, source).first
                if (instance.removed) return
            }
        }
        if (fresh) emit(Events.CENTITY_SPAWN, instance, CentityEvent(handle(instance)))
    }

    /** Ends an instance's script (its `unload` handlers run) but leaves it in the world. */
    fun stopScripts(instance: Instance) {
        instance.scope?.let(scripts::close)
        instance.scope = null
    }

    private fun stopAll() {
        for (instance in instances.values.toList()) stopScripts(instance)
    }

    /**
     * Takes an instance out of the world for good: `remove`, then its script
     * unload, then every handler on it and its nodes goes, then its entities.
     */
    fun remove(instance: Instance) {
        if (instance.removed || instance.removing) return
        // Still itself while `remove` handlers run; a second remove from one of them does nothing.
        instance.removing = true
        emit(Events.CENTITY_REMOVE, instance, CentityEvent(handle(instance)))
        instance.removed = true
        paths.shutAll(instance)
        stopScripts(instance)
        scripts.dropTarget(handle(instance))
        for (entity in instance.entityIds()) {
            owners.remove(entity)
            // An entity in an unloaded chunk is cleaned up as a stray when it loads.
            platform.entities.remove(entity)
        }
        instances.remove(instance.id)
        obstacles.remove(instance)
        onRemoved(instance.id)
        changed(instance.id)
    }

    /** Removes an inert record and whatever of its entities is reachable. */
    fun removeInert(id: UUID): Boolean {
        val record = inert.remove(id) ?: return false
        entitiesOf(record).forEach { platform.entities.remove(it) }
        onRemoved(id)
        changed(id)
        return true
    }

    fun inertRecords(): List<InstanceRecord> = inert.values.toList()

    /** The temporary instances: those that appeared by themselves and haven't been kept. */
    fun naturals(): List<Instance> = instances.values.filter { it.natural && !it.removed }

    /**
     * Makes a natural or temporary instance a normal one: its entities are
     * saved with their chunks and the store hears of it at the end of the
     * tick, so it survives a restart. False when it was neither.
     */
    fun keep(instance: Instance): Boolean {
        if (instance.removed || !instance.transient) return false
        instance.natural = false
        instance.temporary = false
        instance.owner = null
        instance.expires = null
        for (entity in instance.entityIds()) platform.entities.setPersistent(entity, true)
        changed(instance.id)
        return true
    }

    /** Moves the anchor alone (its nodes already moved back by as much): the store gets it when the world saves. */
    internal fun moveAnchor(instance: Instance, to: Location) {
        instance.anchor = to
    }

    /**
     * Moves the anchor to [to] and, when [yaw] isn't null, turns the centity
     * to face it (the location's own facing is ignored). The store gets it
     * when the world saves.
     */
    fun teleport(instance: Instance, to: Location, yaw: Double? = null): Boolean {
        if (instance.removed || !platform.worlds.exists(to.world)) return false
        // A teleport ends a walk, as `stop_pathing` does: no `path_end`. It ends a follow too, and the doors it
        // opened shut, since it has gone from them.
        instance.walk = null
        paths.shutAll(instance)
        unfollow(instance)
        instance.anchor = to.copy(yaw = 0.0, pitch = 0.0)
        if (yaw != null) instance.turnTo(yaw)
        sync(instance)
        return true
    }

    /**
     * Makes [instance] ride [player]: its anchor entity mounts them, and each
     * tick it moves to their place and turns to their body ([followAt]).
     * False when they're offline, or it has physics bodies (not tested
     * together yet). A walk it was on ends.
     */
    fun follow(instance: Instance, player: UUID): Boolean {
        if (instance.removed || instance.hasBodies) return false
        val at = platform.players.location(player) ?: return false
        instance.walk = null
        instance.follower = player
        followAt(instance, at)
        return true
    }

    /**
     * Stops following, where the centity is now: the anchor entity gets off
     * the player and stays put. False when it wasn't following.
     */
    fun unfollow(instance: Instance): Boolean {
        if (instance.follower == null) return false
        if (instance.followMounted) instance.anchorEntity?.let(platform.entities::dismount)
        instance.follower = null
        instance.followMounted = false
        return true
    }

    /** Moves a following centity to its player (each tick, before its sync): the anchor, and the facing with their body. */
    private fun followTick(instance: Instance) {
        val player = instance.follower ?: return
        val at = platform.players.location(player)
        if (at == null) {
            // Offline: the anchor is left where it was, as after `stop_following`.
            instance.follower = null
            instance.followMounted = false
            return
        }
        followAt(instance, at)
    }

    /**
     * Puts the anchor at [at] and turns the centity to the player's body (not
     * their look: their body lags their head and follows their walking, so a
     * centity worn on them turns as they do), then mounts the anchor entity on
     * the player: that's idempotent, so it also puts it back after something got
     * it off. A player in another world can't carry it, so until they're in its
     * world the anchor is teleported instead.
     */
    private fun followAt(instance: Instance, at: Location) {
        val anchor = at.copy(yaw = 0.0, pitch = 0.0)
        if (instance.anchor != anchor) instance.anchor = anchor
        val player = instance.follower
        instance.turnTo(player?.let { platform.worldEntities.bodyYaw(it) } ?: at.yaw)
        val vehicle = instance.anchorEntity
        instance.followMounted = vehicle != null && player != null && platform.entities.mount(player, vehicle)
    }

    /**
     * Adds a script's node to [instance] under [parent] (a root when null; the
     * caller has checked the name is free and the parent is there). Its
     * entities are spawned by the next sync, in the same tick.
     */
    fun addNode(instance: Instance, node: CompiledCentity.Node) {
        instance.addNode(node)
    }

    /**
     * Removes the script-added node [name] and its descendants from
     * [instance]: their handlers go with them and their entities at the next
     * sync. False when there's no such node.
     */
    fun removeNode(instance: Instance, name: String): Boolean {
        val index = instance.indexOf(name) ?: return false
        val id = instance.id.toString()
        for (gone in instance.removeNode(index)) scripts.dropTarget(LuaHandle.Node(id, gone))
        return true
    }

    /** Turns the centity to face [yaw]: the store gets it when the world saves, so aiming every tick writes nothing. */
    fun turn(instance: Instance, yaw: Double) {
        if (instance.removed) return
        instance.turnTo(yaw)
    }

    /**
     * Moves every instance of [id] onto [next] and restarts its script, or
     * makes them inert when [next] is null (the centity was deleted).
     * Returns how many instances now run the new definition.
     */
    fun reload(id: String, next: CompiledCentity?): Int {
        val live = instances.values.filter { it.centity == id }
        for (instance in live) stopScripts(instance)
        if (next == null) {
            definitions.remove(id)
            for (instance in live) {
                instances.remove(instance.id)
                obstacles.remove(instance)
                instance.entityIds().forEach { owners.remove(it) }
                if (instance.transient) {
                    // Nothing to come back to life for: it was never saved.
                    discard(record(instance))
                    scripts.dropTarget(handle(instance))
                    continue
                }
                inert[instance.id] = record(instance)
                // Inert is not in the world: handles to it answer nil, and its handlers go.
                scripts.dropTarget(handle(instance))
                changed(instance.id)
            }
            return 0
        }
        definitions[id] = next
        for (instance in live) {
            // A node the new definition doesn't have is gone, and so are the handlers on it.
            for (name in instance.nodeNames()) {
                if (next.indexOf(name) == null) scripts.dropTarget(LuaHandle.Node(instance.id.toString(), name))
            }
            instance.redefine(next)
            healths.adopt(instance)
            autoplay(instance)
        }
        val revived = inert.values.filter { ResourceRef(it.centity).nameIn(home()) == id }.mapNotNull { restoreOne(it) }
        val attached = live + revived
        for (instance in attached) {
            sync(instance)
            startScripts(instance, fresh = false)
            changed(instance.id)
        }
        return attached.size
    }

    // ---- per tick --------------------------------------------------------------

    /** Instances whose last tick threw: reported once, not every tick. */
    private val failing = HashSet<UUID>()

    /** A scope that spawned temporary instances is gone: so are they. */
    override fun scopeReleased(scope: Scope) {
        for (instance in instances.values.toList()) {
            if (instance.temporary && instance.owner == scope.id && !instance.removed) remove(instance)
        }
    }

    /**
     * Gives [instance] [ticks] more before it's removed (null: no end). One that wasn't temporary becomes so,
     * going by its lifetime alone (no scope spawned it temporary); one that was keeps going with its scope too.
     */
    fun setLifetime(instance: Instance, ticks: Long?) {
        if (instance.removed) return
        if (!instance.temporary) {
            instance.temporary = true
            for (entity in instance.entityIds()) platform.entities.setPersistent(entity, false)
            // The store forgets it at the end of the tick: it isn't saved any more.
            changed(instance.id)
            stored.remove(instance.id)?.let { store.delete(instance.id) }
        }
        instance.expires = ticks?.let { scripts.now + it }
    }

    private fun pass() {
        paths.beginTick()
        // Lifetimes run out whether or not their chunk is loaded.
        for (instance in instances.values.toList()) {
            val expires = instance.expires
            if (expires != null && scripts.now >= expires && !instance.removed) remove(instance)
        }
        for (instance in instances.values.toList()) {
            if (instance.removed) continue
            val loaded = parts.time(LOADED) { loaded(instance) }
            val was = instance.chunkLoaded
            if (was != null && was != loaded) {
                instance.chunkLoaded = loaded
                emit(if (loaded) Events.CENTITY_CHUNK_LOAD else Events.CENTITY_CHUNK_UNLOAD, instance, CentityEvent(handle(instance)))
            }
            if (instance.removed || !loaded) continue
            // One instance's bug (ours, or an adapter's) mustn't stop the others.
            try {
                tick(instance)
                failing.remove(instance.id)
            } catch (e: Exception) {
                if (failing.add(instance.id)) log.error("Centity ${instance.centity} (${instance.id}) failed to tick", e)
            }
        }
        touches()
    }

    /** What a body's move did, to whoever listens: `land`, then a `bump` for each face it ran into. */
    private fun moved(instance: Instance, moved: Mover.Moved) {
        val landed = moved.landed
        if (landed != null && !instance.removed) emit(Events.CENTITY_LAND, instance, CentityLandEvent(handle(instance), landed))
        for (bump in moved.bumps) {
            if (instance.removed) break
            emit(Events.CENTITY_BUMP, instance, CentityBumpEvent(handle(instance), bump.normal, bump.speed))
        }
    }

    /**
     * `touch`: after every instance has moved this tick, each one something listens to `touch` on hears
     * what its hitboxes started touching since the last tick. One nobody listens to keeps no contacts.
     */
    private fun touches() {
        for (instance in instances.values.toList()) {
            if (instance.removed) continue
            if (!scripts.listening(handle(instance), Events.CENTITY_TOUCH) || !loaded(instance)) {
                instance.touching = emptySet()
                continue
            }
            val now = parts.time(TOUCH) { spatial.contacts(instance) }
            val before = instance.touching
            instance.touching = now.keys
            val id = instance.id.toString()
            for ((other, contact) in now) {
                if (other in before) continue
                if (instance.removed) break
                val nodes = instance.definition.nodes
                emit(
                    Events.CENTITY_TOUCH,
                    instance,
                    TouchEvent(
                        centity = handle(instance),
                        node = LuaHandle.Node(id, nodes[contact.node].name),
                        other = contact.other?.let(::handle),
                        otherNode = contact.other?.let { LuaHandle.Node(it.id.toString(), it.definition.nodes[contact.otherNode].name) },
                        entity = contact.entity?.let(entityHandle)
                    )
                )
            }
        }
    }

    private fun tick(instance: Instance) {
        instance.age++
        // Tweens move the rest pose, which the clips then play over.
        if (instance.tweens.isNotEmpty()) instance.stepTweens()
        if (instance.animations.active) {
            val ended = parts.time(ANIMATE) {
                instance.animations.advance(SECONDS_PER_TICK).also { instance.animate() }
            }
            for (clip in ended) {
                if (instance.removed) break
                emit(Events.CENTITY_ANIMATION_END, instance, AnimationEvent(handle(instance), clip))
            }
        }
        // A flight moves the whole centity, and nothing else does while it flies.
        if (instance.flight != null && !instance.removed) parts.time(FLIGHT) { flights.step(instance) }
        if (instance.health?.shownUntil != null) healths.hideBar(instance, scripts.now)
        // A follow moves it to its player first, as a walk does; it never has both (a walk ends it).
        if (instance.follower != null && !instance.removed) parts.time(FOLLOW) { followTick(instance) }
        // Walking moves the whole centity first, so physics settles its bodies where it has been walked to.
        if (instance.walk != null && !instance.removed) parts.time(WALK) { paths.step(instance) }
        // Doors a walk opened and didn't get to shut (it was stopped or replaced on the way) shut once it's out of them.
        if (instance.walk == null && instance.openedDoors.isNotEmpty() && !instance.removed) paths.shutBehind(instance)
        // Its body moves the whole centity, before physics settles node bodies where it has gone; a walk moves it instead.
        if (instance.mover != null && instance.walk == null && instance.flight == null && !instance.removed) {
            val moved = parts.time(BODY) { movers.step(instance) }
            if (moved != null) moved(instance, moved)
        }
        if (instance.hasBodies && !instance.removed) parts.time(PHYSICS) { physics.step(instance, SECONDS_PER_TICK) }
        // Rates are handler options (`every`): only a tick some handler is due on is raised, and none when nobody listens.
        if (!instance.removed && scripts.due(handle(instance), Events.CENTITY_TICK, scripts.now)) {
            parts.time(SCRIPTS) { emit(Events.CENTITY_TICK, instance, TickEvent(scripts.now)) }
        }
        if (instance.ropes.isNotEmpty() && !instance.removed) layRopes(instance)
        if (!instance.removed) parts.time(SYNC) { sync(instance) }
    }

    /** Lays each strung rope of [instance] between where its ends are now; one whose end has gone is let go. */
    private fun layRopes(instance: Instance) {
        val world = instance.anchor.world
        for ((name, rope) in instance.ropes.entries.toList()) {
            val index = instance.indexOf(name)
            val from = where(rope.from, world)
            val to = where(rope.to, world)
            if (index == null || from == null || to == null) {
                instance.ropes.remove(name)
                continue
            }
            Ropes.lay(instance, index, from, to, rope.length)
        }
    }

    /** Where a rope's end is in [world] now; null when it's gone or in another world. */
    private fun where(end: RopeEnd, world: String): Vec3? = when (end) {
        is RopeEnd.Point -> end.at
        is RopeEnd.OfNode -> find(end.centity)?.takeIf { !it.removed && it.anchor.world == world }?.let { other ->
            other.indexOf(end.node)?.let { other.worldMatrix(it).translation() }
        }
        is RopeEnd.OfEntity -> platform.worldEntities.info(end.entity)?.location?.takeIf { it.world == world }?.let {
            Vec3(it.x, it.y + if (platform.players.get(end.entity) != null) PLAYER_HANDS else ENTITY_MIDDLE, it.z)
        }
    }

    /**
     * Whether the instance is in the world right now: the entities of its
     * anchor's chunk are loaded (its displays stand at the anchor, so then
     * they're either reachable or gone; see [dropGone]). One with no entities
     * at all (just spawned, or every one of them gone) always is, so
     * [reconcile] can put them in the world, wherever that is.
     */
    fun loaded(instance: Instance): Boolean =
        (instance.anchorEntity == null && instance.displays.isEmpty() && instance.hitboxes.isEmpty()) ||
            platform.worlds.entitiesLoaded(instance.anchor)

    // ---- sync ------------------------------------------------------------------

    /**
     * How far a mounted follower's anchor sits from where it should be: a rider
     * sits on its vehicle's attachment point (a player's head, lower when they
     * sneak), so the game puts the anchor that far off the centity. Null when
     * nothing is mounted, so nothing moves.
     */
    private fun riderShift(instance: Instance, vehicle: UUID?): Vec3? {
        if (!instance.followMounted || vehicle == null) return null
        val actual = platform.entities.location(vehicle) ?: return null
        val wanted = instance.anchor
        return Vec3(wanted.x - actual.x, wanted.y - actual.y, wanted.z - actual.z)
    }

    /** The poses moved by [shift] in the world: what the riders are drawn at, from where the game puts the anchor. */
    private fun shiftedBy(poses: List<Matrix4>, shift: Vec3?): List<Matrix4> {
        if (shift == null) return poses
        val move = Matrix4().identity().translate(shift.x, shift.y, shift.z)
        return poses.map { Matrix4().set(move).mul(it) }
    }

    /** Brings the instance's entities in line with its state, writing only what changed. */
    fun sync(instance: Instance) {
        if (instance.removed || !loaded(instance)) return
        parts.time(SYNC_GONE) {
            dropGone(instance)
            if (instance.needsReconcile) reconcile(instance)
            sweepOrphans(instance)
        }
        // A mounted follower is redrawn every tick: the game's place for its rider moves with a sneak, not only a walk.
        if (!instance.poseDirty && !instance.forceSync && !instance.moved && !instance.followMounted) return

        val anchor = instance.anchor
        val entities = platform.entities
        val vehicle = parts.time(SYNC_ANCHOR) { syncAnchor(instance) }
        val world = parts.time(SYNC_PLACE) { shiftedBy(instance.placed(), riderShift(instance, vehicle)) }
        val cull = cullSize(world)
        val ahead = Lookahead(instance)
        for ((index, node) in instance.definition.nodes.withIndex()) {
            val shown = instance.shown(index)
            instance.displays[node.name]?.let { entity ->
                val from = if (parts.active) parts.now() else 0L
                val display = instance.display(index)
                val contentChanged = instance.contentChanged(index)
                if (contentChanged && display != null && !entities.updateDisplay(entity, display)) {
                    instance.needsReconcile = true
                }
                // After the content, which a look overrides (a billboard, an item), and before a teleport it eases.
                val look = instance.shownLook(index)
                if (instance.forceSync || contentChanged || instance.pushedLooks[entity] != look) {
                    entities.setLook(entity, look)
                    instance.pushedLooks[entity] = look
                }
                if (instance.billboarded(index)) {
                    // A billboard stands on its own at its node: riding, it could only be drawn at the anchor.
                    if (entity in instance.riders) {
                        entities.dismount(entity)
                        instance.riders -= entity
                    }
                    val spot = standsAt(instance, index)
                    if (instance.forceSync || instance.pushedSpots[entity] != spot) {
                        entities.teleport(entity, spot)
                        instance.pushedSpots[entity] = spot
                    }
                } else {
                    // Back on the anchor entity when it stops being a billboard.
                    val wasStanding = instance.pushedSpots.remove(entity) != null
                    if ((instance.forceSync || wasStanding) && vehicle != null) ride(instance, vehicle, entity)
                    // One that couldn't get on the anchor entity is moved on its own.
                    if ((instance.moved || instance.forceSync || wasStanding) && entity !in instance.riders) {
                        entities.teleport(entity, anchor)
                    }
                }
                if (parts.active) parts.add(SYNC_DISPLAYS, parts.now() - from)
                parts.time(SYNC_POSES) { syncPose(instance, index, entity, if (shown) world[index] else HIDDEN, shown, cull, ahead) }
            }
            instance.hitboxes[node.name]?.let { entity ->
                val from = if (parts.active) parts.now() else 0L
                // An unclickable hitbox is collapsed like a hidden one, so clicks pass it by.
                val bounds = if (shown && instance.clickable(index)) {
                    Hitboxes.bounds(boxesOf(instance, index), world[index])
                } else {
                    val origin = world[index].translation()
                    Hitboxes.Bounds(origin.x, origin.y, origin.z, Hitboxes.MIN_SIZE, Hitboxes.MIN_SIZE)
                }
                // At the anchor it rides the anchor entity; anywhere else it stands on its own.
                val wasRiding = entity in instance.riders
                if (vehicle != null && bounds.atAnchor()) {
                    if (instance.forceSync || !wasRiding) ride(instance, vehicle, entity)
                } else if (wasRiding || instance.forceSync) {
                    entities.dismount(entity)
                    instance.riders -= entity
                }
                val riding = entity in instance.riders
                val reshaped = instance.forceSync || instance.pushedHitboxes[entity] != bounds
                if (riding) {
                    if (reshaped || !wasRiding) entities.resizeHitbox(entity, bounds.width, bounds.height)
                } else if (reshaped || instance.moved || wasRiding) {
                    entities.teleport(entity, anchor.offset(bounds.centerX, bounds.bottomY, bounds.centerZ))
                    entities.resizeHitbox(entity, bounds.width, bounds.height)
                }
                instance.pushedHitboxes[entity] = bounds
                if (parts.active) parts.add(SYNC_HITBOXES, parts.now() - from)
            }
        }
        instance.markSynced()
        // A display that turned out to be the wrong kind is respawned on the next pass.
        if (instance.needsReconcile) sync(instance)
    }

    /**
     * The anchor entity, moved and eased as the instance says: what its
     * riders follow. Null when it has none (it couldn't be spawned).
     */
    private fun syncAnchor(instance: Instance): UUID? {
        val vehicle = instance.anchorEntity ?: return null
        val look = DisplayLook(viewRange = instance.viewRange / Instance.DEFAULT_VIEW_RANGE, teleportTicks = instance.moveTicks())
        if (instance.forceSync || instance.pushedLooks[vehicle] != look) {
            platform.entities.setLook(vehicle, look)
            instance.pushedLooks[vehicle] = look
        }
        // Riding a followed player, it moves with them: a teleport would get it off.
        if (instance.moved && !instance.followMounted) platform.entities.teleport(vehicle, instance.anchor)
        return vehicle
    }

    private fun ride(instance: Instance, vehicle: UUID, entity: UUID) {
        if (platform.entities.mount(vehicle, entity)) instance.riders += entity else instance.riders -= entity
    }

    /**
     * Shows node [index]'s display at [matrix] (its pose now, collapsed when
     * hidden). While only clips move it, the pose is sent ahead instead: as
     * far as the client's easing follows the clips ([Keyframes.reach]), and
     * then nothing until it gets there. Anything else (a script, physics, a
     * new clip, the first sync) sends the pose now, eased as the node says.
     */
    private fun syncPose(instance: Instance, index: Int, entity: UUID, matrix: Matrix4, shown: Boolean, cull: Double, ahead: Lookahead) {
        val billboarded = instance.billboarded(index)
        val planned = !instance.forceSync &&
            !billboarded &&
            shown &&
            instance.animations.active &&
            instance.interpolation(index) > 0 &&
            !instance.edited(index)
        val (target, ticks) = if (planned) {
            // Still on its way to what it was sent.
            if (instance.age < instance.due[index]) return
            val reach = Keyframes.reach(matrix, Keyframes.MAX_TICKS) { ahead.at(it)[index] }
            instance.due[index] = instance.age + reach
            // The client shows the pose now; it eases on to the pose that many ticks on.
            ahead.at(reach)[index] to reach
        } else {
            instance.due[index] = instance.age
            (if (billboarded) billboardPose(matrix) else matrix) to if (instance.forceSync) 0 else instance.interpolation(index)
        }
        val pushed = Instance.PushedPose(target.values.toList(), cull)
        if (instance.forceSync || instance.pushedPoses[entity] != pushed) {
            platform.entities.setPose(entity, DisplayPose(drawn(instance, index, target), ticks, cull))
            instance.pushedPoses[entity] = pushed
        }
    }

    /**
     * Where node [index]'s display entity stands: at the anchor, its offset in
     * its matrix; a billboard at its node, since the client turns a billboard
     * to the camera before its transformation, which would swing an offset
     * round the anchor and turn the text away by the centity's yaw.
     */
    private fun standsAt(instance: Instance, index: Int): Location {
        if (!instance.billboarded(index)) return instance.anchor
        val at = instance.placed()[index].translation()
        return instance.anchor.offset(at.x, at.y, at.z)
    }

    /**
     * A billboard's matrix: its node's scale only. Its place is the entity's
     * ([standsAt]), and the client chooses its turn, so the node's rotation
     * and the centity's yaw are left out.
     */
    private fun billboardPose(matrix: Matrix4): Matrix4 {
        val scale = matrix.scale()
        return Matrix4().scale(scale.x, scale.y, scale.z)
    }

    /**
     * What a display is drawn at, given its node's [matrix] in the world. A model
     * look is authored nose on +Z, as the docs say, but the client draws it turned
     * a half turn about its own Y, so it's turned back here to put its nose where
     * the node's +Z points. Blocks and the rest are drawn as they are.
     */
    private fun drawn(instance: Instance, index: Int, matrix: Matrix4): Matrix4 =
        if (instance.display(index) is ModelDisplay) matrix.copy().rotateY(PI) else matrix

    /** [Instance.placedAhead] for 1, 2, … ticks on, each worked out once, the first time a display asks. */
    private class Lookahead(private val instance: Instance) {
        private val ticks = ArrayList<List<Matrix4>>()

        fun at(tick: Int): List<Matrix4> {
            while (ticks.size < tick) ticks += instance.placedAhead(ticks.size + 1)
            return ticks[tick - 1]
        }
    }

    /**
     * Makes the entities match the definition: removes ones whose node or
     * component is gone, updates every display's content (respawning one
     * whose kind changed), spawns what's missing.
     */
    private fun reconcile(instance: Instance) {
        val definition = instance.definition
        for ((name, entity) in instance.displays.toList()) {
            val index = definition.indexOf(name)
            if (index == null || instance.display(index) == null) {
                instance.displays.remove(name)
                retire(instance, entity)
            }
        }
        for ((name, entity) in instance.hitboxes.toList()) {
            if (definition.node(name)?.hitbox == null) {
                instance.hitboxes.remove(name)
                retire(instance, entity)
            }
        }

        val world = instance.placed()
        val cull = cullSize(world)
        val anchor = instance.anchor
        val entities = platform.entities
        if (instance.anchorEntity == null) {
            entities.spawnAnchor(anchor, EntityTag(instance.id, ANCHOR_NODE, EntityRole.ANCHOR))?.let { entity ->
                instance.anchorEntity = entity
                owners[entity] = instance to ANCHOR_NODE
                if (instance.transient) entities.setPersistent(entity, false)
                for (player in instance.hiddenFrom) entities.setHidden(player, entity, true)
            }
        }
        for ((index, node) in definition.nodes.withIndex()) {
            val display = instance.display(index)
            if (display != null) {
                val existing = instance.displays[node.name]
                if (existing != null && !entities.updateDisplay(existing, display)) {
                    instance.displays.remove(node.name)
                    retire(instance, existing)
                }
                if (instance.displays[node.name] == null) {
                    val shown = if (instance.shown(index)) world[index] else HIDDEN
                    val matrix = if (instance.billboarded(index)) billboardPose(shown) else shown
                    entities.spawnDisplay(
                        standsAt(instance, index),
                        display,
                        DisplayPose(drawn(instance, index, matrix), 0, cull),
                        EntityTag(instance.id, node.name, EntityRole.DISPLAY)
                    )
                        ?.let { claim(instance, node.name, it, instance.displays) }
                }
            }
            if (node.hitbox != null && instance.hitboxes[node.name] == null) {
                val bounds = Hitboxes.bounds(boxesOf(instance, index), world[index])
                entities.spawnHitbox(
                    anchor.offset(bounds.centerX, bounds.bottomY, bounds.centerZ),
                    bounds.width,
                    bounds.height,
                    EntityTag(instance.id, node.name, EntityRole.HITBOX)
                )?.let { claim(instance, node.name, it, instance.hitboxes) }
            }
        }
        instance.needsReconcile = false
        instance.forceSync = true
        changed(instance.id)
    }

    private fun claim(instance: Instance, node: String, entity: UUID, into: MutableMap<String, UUID>) {
        into[node] = entity
        owners[entity] = instance to node
        // A temporary instance's entities aren't saved with their chunk, so a crash can't leave them behind.
        if (instance.transient) platform.entities.setPersistent(entity, false)
        for (player in instance.hiddenFrom) platform.entities.setHidden(player, entity, true)
    }

    // ---- per-player visibility ---------------------------------------------------

    /**
     * Hides [instance] from [player] (every display and hitbox), or shows it
     * again. The record is ours: the server forgets when the player leaves or
     * the chunk unloads, and [playerJoined] and [entitiesLoaded] say it again.
     */
    fun setHidden(instance: Instance, player: UUID, hidden: Boolean) {
        if (instance.removed) return
        val changed = if (hidden) instance.hiddenFrom.add(player) else instance.hiddenFrom.remove(player)
        if (!changed) return
        for (entity in instance.shownEntities()) platform.entities.setHidden(player, entity, hidden)
    }

    /** A player joined: everything hidden from them is hidden again, since the server forgot. */
    override fun playerJoined(player: PlayerRef) {
        for (instance in instances.values) {
            if (player.uuid !in instance.hiddenFrom) continue
            for (entity in instance.shownEntities()) platform.entities.setHidden(player.uuid, entity, true)
        }
    }

    private fun retire(instance: Instance, entity: UUID) {
        forget(entity, instance)
        if (!platform.entities.remove(entity)) instance.orphans += entity
    }

    /**
     * Forgets entities that can't be reached although their chunk's entities
     * are loaded: something else killed or removed them. [reconcile] spawns
     * them again. (A display stands at the anchor; a billboard or a hitbox where
     * it was last put, which may be the next chunk over.)
     */
    private fun dropGone(instance: Instance) {
        val entities = platform.entities
        fun gone(entity: UUID, at: Location) = !entities.isLoaded(entity) && platform.worlds.entitiesLoaded(at)
        val anchor = instance.anchor
        val goneDisplays = instance.displays.filterValues { gone(it, instance.pushedSpots[it] ?: anchor) }
        val goneHitboxes = instance.hitboxes.filterValues { entity ->
            val at = instance.pushedHitboxes[entity]?.let { anchor.offset(it.centerX, it.bottomY, it.centerZ) } ?: anchor
            gone(entity, at)
        }
        val goneAnchor = instance.anchorEntity?.takeIf { gone(it, anchor) }
        if (goneDisplays.isEmpty() && goneHitboxes.isEmpty() && goneAnchor == null) return
        if (goneAnchor != null) {
            // Its riders are left standing where they were: the new one picks them up.
            instance.anchorEntity = null
            instance.riders.clear()
            forget(goneAnchor, instance)
        }
        for ((name, entity) in goneDisplays) {
            instance.displays.remove(name)
            forget(entity, instance)
        }
        for ((name, entity) in goneHitboxes) {
            instance.hitboxes.remove(name)
            forget(entity, instance)
        }
        instance.needsReconcile = true
        changed(instance.id)
    }

    private fun forget(entity: UUID, instance: Instance) {
        owners.remove(entity)
        instance.riders.remove(entity)
        instance.pushedPoses.remove(entity)
        instance.pushedHitboxes.remove(entity)
        instance.pushedLooks.remove(entity)
        instance.pushedSpots.remove(entity)
    }

    /**
     * Orphans (entities of ours we couldn't remove when they stopped being
     * wanted) are tried once the instance is loaded, then forgotten: one that
     * still can't be reached is gone, or in an unloaded neighbouring chunk,
     * where [entitiesLoaded] removes it as a stray when that chunk loads.
     */
    private fun sweepOrphans(instance: Instance) {
        if (instance.orphans.isEmpty()) return
        instance.orphans.forEach { platform.entities.remove(it) }
        instance.orphans.clear()
        changed(instance.id)
    }

    /** The boxes a node's hitbox is made of right now, in node space. */
    fun boxesOf(instance: Instance, index: Int): List<Box> {
        val hitbox = instance.definition.nodes[index].hitbox ?: return Hitboxes.UNIT_CUBE
        hitbox.boxes?.let { return it }
        if (hitbox.followsCollision) {
            val state = (instance.display(index) as? BlockDisplay)?.let { BlockState.parse(it.block) }
            if (state != null) {
                val game = platform.game
                val full = game.block(state.id)?.let { state.withDefaults(it) } ?: state
                game.collisionBoxes(full)?.takeIf { it.isNotEmpty() }?.let { return it }
            }
        }
        return Hitboxes.UNIT_CUBE
    }

    /** Twice the furthest node offset, rounded up so a moving centity doesn't resend it every tick. */
    private fun cullSize(world: List<Matrix4>): Double {
        var furthest = 1.0
        for (matrix in world) {
            val t = matrix.translation()
            furthest = maxOf(furthest, sqrt(t.x * t.x + t.y * t.y + t.z * t.z))
        }
        return ceil(furthest + 2) * 2
    }

    // ---- world events ----------------------------------------------------------

    /**
     * Entities came back with their chunk. Ours are resynced in full (the
     * client may hold anything); tagged ones nobody owns are strays from a
     * removed instance or a lost record, and go.
     */
    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) {
        for ((entity, tag) in tagged) {
            // A screen's frame is the screens' to keep or take down.
            if (tag.role == EntityRole.SCREEN) continue
            val owner = owners[entity]
            if (owner != null) {
                owner.first.forceSync = true
                // The server forgets who an entity was hidden from when it unloads.
                for (player in owner.first.hiddenFrom) platform.entities.setHidden(player, entity, true)
                continue
            }
            val known = instances[tag.instance]?.orphans?.contains(entity) == true || tag.instance in inert
            if (!known) platform.entities.remove(entity)
        }
    }

    /**
     * Routes a click on one of our entities ([CentityClicks.click]). True when the entity is ours, whether or not
     * any script wanted the click.
     */
    fun click(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean = clicks.click(entity, player, button, sight)

    /** Whose hitbox [entity] is, and the node a click there by [player] along [sight] would reach ([CentityClicks.clickTarget]). */
    fun clickTarget(entity: UUID, player: UUID, sight: Ray?): Pair<Instance, String?>? = clicks.clickTarget(entity, player, sight)

    /** Where a player would aim to click [instance]'s node [node] ([CentityClicks.aim]). */
    fun aim(instance: Instance, node: String?): Pair<UUID, Vec3>? = clicks.aim(instance, node)

    /** The first centity hitbox a ray hits ([CentityClicks.raycast]). */
    fun raycast(world: String, origin: Vec3, direction: Vec3, maxDistance: Double): CentityClicks.HitboxHit? =
        clicks.raycast(world, origin, direction, maxDistance)

    // ---- events ----------------------------------------------------------------

    private fun handle(instance: Instance) = LuaHandle.Centity(instance.id.toString())

    /** Raises one of a centity's own events on it. */
    private fun <P : LuaEvent> emit(event: EventType<P>, instance: Instance, payload: P) {
        scripts.emit(event, handle(instance), payload)
    }

    /** `play_animation` started [animation] on [instance]. */
    fun animationStarted(instance: Instance, animation: String) {
        emit(Events.CENTITY_ANIMATION_START, instance, AnimationEvent(handle(instance), animation))
    }

    /** [instance]'s walk ended: [CentityPaths] has already let go of it, so a handler can send it off again. */
    internal fun pathEnded(instance: Instance, reached: Boolean) {
        emit(Events.CENTITY_PATH_END, instance, CentityPathEndEvent(handle(instance), reached))
    }

    /** Whether anything listens for [event] on node [index] of [instance]: physics asks before building a payload. */
    fun listening(instance: Instance, index: Int, event: EventType<*>): Boolean =
        scripts.listening(LuaHandle.Node(instance.id.toString(), instance.definition.nodes[index].name), event)

    /** Raises one of a node's events, for [Physics]. */
    fun <P : LuaEvent> emitNode(event: EventType<P>, instance: Instance, index: Int, payload: P) {
        scripts.emit(event, LuaHandle.Node(instance.id.toString(), instance.definition.nodes[index].name), payload)
    }

    // ---- records ---------------------------------------------------------------

    /** [instance] as the store keeps it: its centity named in full. */
    fun record(instance: Instance) = InstanceRecord(
        id = instance.id,
        centity = ResourceRef(instance.centity).resolve(home())?.toString() ?: instance.centity,
        anchor = instance.anchor,
        yaw = instance.yaw,
        displays = instance.displays.toMap(),
        hitboxes = instance.hitboxes.toMap(),
        anchorEntity = instance.anchorEntity,
        orphans = instance.orphans.toList(),
        natural = instance.natural
    )

    fun records(): List<InstanceRecord> = instances.values.map(::record) + inert.values

    /** The centity an inert record names, as the project names it (bare for its own). */
    fun centityOf(record: InstanceRecord): String = ResourceRef(record.centity).nameIn(home()) ?: record.centity

    fun info(): List<InstanceInfo> = instances.values.map(::info) + inert.values.map {
        InstanceInfo(it.id.toString(), centityOf(it), it.anchor.world, it.anchor.x, it.anchor.y, it.anchor.z)
    }

    fun info(instance: Instance): InstanceInfo = with(instance.anchor) {
        InstanceInfo(instance.id.toString(), instance.centity, world, x, y, z)
    }

    companion object {
        /** How far above a player's feet a rope tied to them is: at their hands, standing. */
        const val PLAYER_HANDS = 1.1

        /** How far above any other entity's feet a rope tied to it is. */
        const val ENTITY_MIDDLE = 0.5

        const val SECONDS_PER_TICK = 0.05

        // What the profiler names the parts of an instance's tick (`get_profile`'s `parts`).
        private const val PART = "world/centities"
        private const val LOADED = "$PART/loaded"
        private const val ANIMATE = "$PART/animate"
        private const val FOLLOW = "$PART/follow"
        private const val WALK = "$PART/walk"
        private const val PHYSICS = "$PART/physics"
        private const val SCRIPTS = "$PART/scripts"
        private const val SYNC = "$PART/sync"
        private const val TOUCH = "$PART/touch"
        private const val BODY = "$PART/body"
        private const val FLIGHT = "$PART/flight"
        private const val SYNC_GONE = "$SYNC/reconcile"
        private const val SYNC_ANCHOR = "$SYNC/anchor"
        private const val SYNC_PLACE = "$SYNC/place"
        private const val SYNC_DISPLAYS = "$SYNC/displays"
        private const val SYNC_POSES = "$SYNC/poses"
        private const val SYNC_HITBOXES = "$SYNC/hitboxes"

        /** The node an anchor entity's tag names: none. */
        const val ANCHOR_NODE = ""

        /** Displays can't be hidden, only collapsed. */
        private val HIDDEN = Matrix4().scale(0.0, 0.0, 0.0)
    }
}
