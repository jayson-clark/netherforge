package dev.netherforge.plugin.testkit

import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.DisplayDef
import dev.netherforge.format.centity.ItemDisplay
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.game.has
import dev.netherforge.plugin.platform.DisplayLook
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityOps
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.StructureMarker
import java.util.UUID

// NetherForge's own entities on the fake server (displays, hitboxes): what `EntityOps` makes.

class FakeEntity(val id: UUID, val role: EntityRole, var display: DisplayDef?, var location: Location, val tag: EntityTag) {
    var pose: DisplayPose? = null
    var poses = 0
    var teleports = 0
    var width = 0.0
    var height = 0.0
    var look: DisplayLook? = null

    /** What it rides, if anything: it stands where that stands. */
    var vehicle: UUID? = null

    /** Whether the server would save it with its chunk. */
    var persistent = true

    /** Players it's hidden from right now (the server forgets on quit and unload; see [FakeEntities.forget]). */
    val hiddenFrom = mutableSetOf<UUID>()
}

class FakeEntities(private val platform: FakePlatform) : EntityOps {
    val all = LinkedHashMap<UUID, FakeEntity>()
    var spawned = 0

    val displays get() = all.values.filter { it.role == EntityRole.DISPLAY }
    val hitboxes get() = all.values.filter { it.role == EntityRole.HITBOX }
    val anchors get() = all.values.filter { it.role == EntityRole.ANCHOR }

    /** What rides [vehicle]. */
    fun passengers(vehicle: UUID) = all.values.filter { it.vehicle == vehicle }

    fun anchor(instance: UUID) = all.values.single { it.tag.instance == instance && it.role == EntityRole.ANCHOR }

    fun of(instance: UUID) = all.values.filter { it.tag.instance == instance }

    fun display(instance: UUID, node: String) = all.values.single {
        it.tag.instance == instance &&
            it.tag.node == node &&
            it.role == EntityRole.DISPLAY
    }

    fun hitbox(instance: UUID, node: String) = all.values.single {
        it.tag.instance == instance &&
            it.tag.node == node &&
            it.role == EntityRole.HITBOX
    }

    override fun spawnDisplay(at: Location, display: DisplayDef, pose: DisplayPose, tag: EntityTag): UUID? {
        if (display is BlockDisplay && platform.game.block(display.block.substringBefore('[')) == null) return null
        if (display is ItemDisplay && platform.game.has(RegistryKey.ITEM, display.item) != true) return null
        val entity = FakeEntity(UUID.randomUUID(), EntityRole.DISPLAY, display, at, tag)
        entity.pose = pose
        all[entity.id] = entity
        spawned++
        return entity.id
    }

    override fun spawnHitbox(at: Location, width: Double, height: Double, tag: EntityTag): UUID {
        val entity = FakeEntity(UUID.randomUUID(), EntityRole.HITBOX, null, at, tag)
        entity.width = width
        entity.height = height
        all[entity.id] = entity
        spawned++
        return entity.id
    }

    override fun spawnAnchor(at: Location, tag: EntityTag): UUID {
        val entity = FakeEntity(UUID.randomUUID(), EntityRole.ANCHOR, null, at, tag)
        all[entity.id] = entity
        spawned++
        return entity.id
    }

    /** A vehicle is an entity of ours or a player, as on Paper. */
    override fun mount(vehicle: UUID, passenger: UUID): Boolean {
        if (!isLoaded(vehicle) || !isLoaded(passenger)) return false
        val carrier = locationOf(vehicle) ?: return false
        val rider = all.getValue(passenger)
        rider.vehicle = vehicle
        rider.location = carrier
        platform.playerViews.cameraMoved(passenger, carrier)
        return true
    }

    private fun locationOf(id: UUID) = all[id]?.location ?: platform.players.byId[id]?.location

    override fun dismount(passenger: UUID) {
        all[passenger]?.takeIf { isLoaded(passenger) }?.vehicle = null
    }

    /** As on Paper: an entity is reachable while its chunk's entities are loaded, and a gone one never is. */
    override fun isLoaded(id: UUID) = locationOf(id)?.let { platform.worlds.entitiesLoaded(it) } == true

    override fun updateDisplay(id: UUID, display: DisplayDef): Boolean {
        val entity = all[id] ?: return true
        val current = entity.display ?: return false
        if (current::class != display::class) return false
        entity.display = display
        return true
    }

    /** Entities whose every pose update throws, as a broken adapter would. */
    val broken = mutableSetOf<UUID>()

    override fun setPose(id: UUID, pose: DisplayPose) {
        if (id in broken) throw IllegalStateException("broken entity $id")
        val entity = all[id] ?: return
        entity.pose = pose
        entity.poses++
    }

    /** How far above its vehicle a rider sits: a player's head standing, lower when they sneak (as the game puts it). */
    var ridingHeight = 1.8

    /** Where an entity is: a rider is where its vehicle is, plus the height it rides at. */
    override fun location(id: UUID): Location? {
        val entity = all[id] ?: return null
        val vehicle = entity.vehicle ?: return entity.location
        return locationOf(vehicle)?.offset(0.0, ridingHeight, 0.0) ?: entity.location
    }

    override fun teleport(id: UUID, to: Location) {
        all[id]?.let {
            // As on Paper: a rider stays on its vehicle, and riders come along with it.
            if (it.vehicle != null) return
            it.location = to
            it.teleports++
            for (rider in passengers(id)) {
                rider.location = to
                platform.playerViews.cameraMoved(rider.id, to)
            }
        }
        platform.playerViews.cameraMoved(id, to)
    }

    override fun resizeHitbox(id: UUID, width: Double, height: Double) {
        val entity = all[id] ?: return
        entity.width = width
        entity.height = height
    }

    override fun remove(id: UUID): Boolean {
        markers[id]?.let { marker ->
            if (!platform.worlds.entitiesLoaded(marker.at)) return false
            markers.remove(id)
            return true
        }
        if (!isLoaded(id)) return false
        all.remove(id)
        // Its riders get off where they are.
        for (rider in passengers(id)) rider.vehicle = null
        return true
    }

    override fun setPersistent(id: UUID, persistent: Boolean) {
        all[id]?.takeIf { isLoaded(id) }?.persistent = persistent
    }

    /** The markers structures left in the world, by entity: [placeMarker] makes one, as a structure's template would. */
    val markers = LinkedHashMap<UUID, StructureMarker>()

    fun placeMarker(at: Location, centity: String): StructureMarker =
        StructureMarker(UUID.randomUUID(), centity, at).also { markers[it.id] = it }

    override fun structureMarkers() = markers.values.filter { platform.worlds.entitiesLoaded(it.at) }

    override fun loadedTagged() = all.values.filter { isLoaded(it.id) }.associate { it.id to it.tag }

    override fun setLook(id: UUID, look: DisplayLook) {
        all[id]?.look = look
    }

    override fun setHidden(player: UUID, entity: UUID, hidden: Boolean) {
        if (player !in platform.players.byId) return
        val it = all[entity] ?: return
        if (hidden) it.hiddenFrom += player else it.hiddenFrom -= player
    }

    /** What the server forgets: who each entity is hidden from, for [player] (leaving) or everyone (an unload). */
    fun forget(player: UUID? = null) {
        for (entity in all.values) if (player == null) entity.hiddenFrom.clear() else entity.hiddenFrom -= player
    }
}
