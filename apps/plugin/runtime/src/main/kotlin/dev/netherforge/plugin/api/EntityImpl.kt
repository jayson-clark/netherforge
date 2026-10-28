package dev.netherforge.plugin.api

import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.DroppedItemApi
import dev.netherforge.plugin.binding.EntityApi
import dev.netherforge.plugin.binding.InventoryKeys
import dev.netherforge.plugin.binding.LocationOrVec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaLocation
import dev.netherforge.plugin.binding.RaycastOptions
import dev.netherforge.plugin.binding.blockHandle
import dev.netherforge.plugin.binding.directionOf
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityInfo
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.sqrt

/** A handle's UUID, or null for one that doesn't parse (which never names anything). */
internal fun LuaHandle.Entity.uuidOrNull(): UUID? = runCatching { UUID.fromString(id) }.getOrNull()

/** What the platform knows of [self]'s entity, or null while it isn't in the world. */
internal fun ProjectSession.entityInfo(self: LuaHandle.Entity): EntityInfo? = self.uuidOrNull()?.let(platform.worldEntities::info)

internal fun ProjectSession.entityFlag(self: LuaHandle.Entity, flag: EntityFlag): Boolean =
    self.uuidOrNull()?.let { platform.worldEntities.flag(it, flag) } == true

internal fun ProjectSession.setEntityFlag(self: LuaHandle.Entity, flag: EntityFlag, value: Boolean): Boolean =
    self.uuidOrNull()?.let { platform.worldEntities.setFlag(it, flag, value) } == true

internal fun ProjectSession.entityNumber(self: LuaHandle.Entity, number: EntityNumber): Double? =
    self.uuidOrNull()?.let { platform.worldEntities.number(it, number) }

/** How far the `target_*` raycasts reach when the script doesn't say: `TARGET_DISTANCE` in `spec/constants.ts`. */
internal const val TARGET_DISTANCE = 20.0

/**
 * `Entity` (and every method the classes below it inherit): a vanilla entity
 * by UUID. Everything answers nil or false while it isn't in the world (gone,
 * or its chunk unloaded). Mistakes (an unknown tag, a value out of range)
 * are [LuaApiException]s.
 */
internal class EntityImpl(private val session: ProjectSession) : EntityApi {
    private val entities get() = session.platform.worldEntities

    private fun info(self: LuaHandle.Entity): EntityInfo? = session.entityInfo(self)

    private fun flag(self: LuaHandle.Entity, flag: EntityFlag): Boolean = session.entityFlag(self, flag)

    private fun setFlag(self: LuaHandle.Entity, flag: EntityFlag, value: Boolean): Boolean = session.setEntityFlag(self, flag, value)

    private fun number(self: LuaHandle.Entity, number: EntityNumber): Double? = session.entityNumber(self, number)

    override fun id(self: LuaHandle.Entity): String = self.id

    private fun here(self: LuaHandle.Entity): Location? = info(self)?.location

    override fun kind(self: LuaHandle.Entity): String? = info(self)?.kind

    override fun exists(self: LuaHandle.Entity): Boolean = info(self) != null

    override fun location(self: LuaHandle.Entity): LuaLocation? = here(self)?.let(LuaLocation::of)

    override fun position(self: LuaHandle.Entity): Vec3? = here(self)?.let { Vec3(it.x, it.y, it.z) }

    override fun world(self: LuaHandle.Entity): LuaHandle.World? = here(self)?.let { LuaHandle.World(it.world) }

    override fun eyePosition(self: LuaHandle.Entity): Vec3? = self.uuidOrNull()?.let(entities::eye)?.let { Vec3(it.x, it.y, it.z) }

    override fun yaw(self: LuaHandle.Entity): Double? = here(self)?.yaw

    override fun pitch(self: LuaHandle.Entity): Double? = here(self)?.pitch

    override fun direction(self: LuaHandle.Entity): Vec3? = here(self)?.let { directionOf(it.yaw, it.pitch) }

    override fun setYaw(self: LuaHandle.Entity, degrees: Double): Boolean {
        val at = here(self) ?: return false
        return entities.setRotation(self.uuidOrNull()!!, degrees, at.pitch)
    }

    override fun setPitch(self: LuaHandle.Entity, degrees: Double): Boolean {
        val at = here(self) ?: return false
        return entities.setRotation(self.uuidOrNull()!!, at.yaw, degrees.coerceIn(-90.0, 90.0))
    }

    override fun lookAt(self: LuaHandle.Entity, point: Vec3): Boolean {
        val id = self.uuidOrNull() ?: return false
        val eye = entities.eye(id) ?: return false
        val dx = point.x - eye.x
        val dy = point.y - eye.y
        val dz = point.z - eye.z
        val flat = sqrt(dx * dx + dz * dz)
        if (flat == 0.0 && dy == 0.0) return true
        // Minecraft's angles: yaw 0 faces +z and grows towards -x; pitch 90 faces down.
        val yaw = Math.toDegrees(atan2(-dx, dz))
        val pitch = Math.toDegrees(-atan2(dy, flat))
        return entities.setRotation(id, yaw, pitch)
    }

    override fun teleport(self: LuaHandle.Entity, locationOrPosition: LocationOrVec3): Boolean {
        val id = self.uuidOrNull() ?: return false
        val at = entities.info(id)?.location ?: return false
        return entities.teleport(id, locationOrPosition.place.resolve(at.world, facing = at))
    }

    override fun velocity(self: LuaHandle.Entity): Vec3? = self.uuidOrNull()?.let(entities::velocity)

    override fun setVelocity(self: LuaHandle.Entity, velocity: Vec3): Boolean =
        self.uuidOrNull()?.let { entities.setVelocity(it, velocity) } == true

    override fun addVelocity(self: LuaHandle.Entity, velocity: Vec3): Boolean {
        val id = self.uuidOrNull() ?: return false
        val now = entities.velocity(id) ?: return false
        return entities.setVelocity(id, now + velocity)
    }

    override fun isOnGround(self: LuaHandle.Entity): Boolean = flag(self, EntityFlag.ON_GROUND)

    override fun remove(self: LuaHandle.Entity): Boolean {
        val id = self.uuidOrNull() ?: return false
        if (session.platform.players.get(id) != null) return false
        if (!entities.remove(id)) return false
        session.entityGone(id)
        return true
    }

    override fun name(self: LuaHandle.Entity): String? = self.uuidOrNull()?.let(entities::name)

    override fun customName(self: LuaHandle.Entity): String? = self.uuidOrNull()?.let(entities::customName)

    override fun setCustomName(self: LuaHandle.Entity, text: String?): Boolean =
        self.uuidOrNull()?.let { entities.setCustomName(it, text) } == true

    override fun isCustomNameVisible(self: LuaHandle.Entity): Boolean = flag(self, EntityFlag.CUSTOM_NAME_VISIBLE)

    override fun setCustomNameVisible(self: LuaHandle.Entity, visible: Boolean): Boolean =
        setFlag(self, EntityFlag.CUSTOM_NAME_VISIBLE, visible)

    override fun tags(self: LuaHandle.Entity): List<String> = info(self)?.tags.orEmpty().sorted()

    override fun hasTag(self: LuaHandle.Entity, tag: String): Boolean = info(self)?.tags?.contains(tag) == true

    override fun addTag(self: LuaHandle.Entity, tag: String): Boolean {
        checkTag(tag)
        return self.uuidOrNull()?.let { entities.addTag(it, tag) } == true
    }

    override fun removeTag(self: LuaHandle.Entity, tag: String): Boolean = self.uuidOrNull()?.let { entities.removeTag(it, tag) } == true

    override fun data(self: LuaHandle.Entity): Any? = self.uuidOrNull()?.let { session.entityData.table(it) }

    override fun isGlowing(self: LuaHandle.Entity): Boolean = flag(self, EntityFlag.GLOWING)

    override fun setGlowing(self: LuaHandle.Entity, glowing: Boolean): Boolean = setFlag(self, EntityFlag.GLOWING, glowing)

    override fun isVisible(self: LuaHandle.Entity): Boolean = flag(self, EntityFlag.VISIBLE)

    override fun setVisible(self: LuaHandle.Entity, visible: Boolean): Boolean = setFlag(self, EntityFlag.VISIBLE, visible)

    override fun hideFrom(self: LuaHandle.Entity, player: LuaHandle.Player): Boolean {
        val id = info(self)?.id ?: return false
        val viewer = player.uuidOrNull()?.takeIf { session.platform.players.get(it) != null } ?: return false
        return session.hiddenEntities.hide(id, viewer)
    }

    override fun showTo(self: LuaHandle.Entity, player: LuaHandle.Player): Boolean {
        val id = info(self)?.id ?: return false
        val viewer = player.uuidOrNull()?.takeIf { session.platform.players.get(it) != null } ?: return false
        return session.hiddenEntities.show(id, viewer)
    }

    override fun isHiddenFrom(self: LuaHandle.Entity, player: LuaHandle.Player): Boolean {
        val id = self.uuidOrNull() ?: return false
        val viewer = player.uuidOrNull() ?: return false
        return session.hiddenEntities.isHidden(id, viewer)
    }

    override fun team(self: LuaHandle.Entity): LuaHandle.Team? {
        val entry = session.scoreboardEntry(self, present = true) ?: return null
        return session.teams.of(entry)?.let { LuaHandle.Team(it.name) }
    }

    override fun isSilent(self: LuaHandle.Entity): Boolean = flag(self, EntityFlag.SILENT)

    override fun setSilent(self: LuaHandle.Entity, silent: Boolean): Boolean = setFlag(self, EntityFlag.SILENT, silent)

    override fun hasGravity(self: LuaHandle.Entity): Boolean = flag(self, EntityFlag.GRAVITY)

    override fun setGravity(self: LuaHandle.Entity, gravity: Boolean): Boolean = setFlag(self, EntityFlag.GRAVITY, gravity)

    override fun isInvulnerable(self: LuaHandle.Entity): Boolean = flag(self, EntityFlag.INVULNERABLE)

    override fun setInvulnerable(self: LuaHandle.Entity, invulnerable: Boolean): Boolean =
        setFlag(self, EntityFlag.INVULNERABLE, invulnerable)

    override fun passengers(self: LuaHandle.Entity): List<LuaHandle.Entity> =
        self.uuidOrNull()?.let(entities::passengers).orEmpty().map { session.entityHandle(it) }

    override fun addPassenger(self: LuaHandle.Entity, entity: LuaHandle.Entity): Boolean {
        val id = self.uuidOrNull() ?: return false
        val rider = entity.uuidOrNull() ?: return false
        if (id == rider) return false
        return entities.addPassenger(id, rider)
    }

    override fun removePassenger(self: LuaHandle.Entity, entity: LuaHandle.Entity): Boolean {
        val id = self.uuidOrNull() ?: return false
        val rider = entity.uuidOrNull() ?: return false
        return entities.removePassenger(id, rider)
    }

    override fun vehicle(self: LuaHandle.Entity): LuaHandle.Entity? = self.uuidOrNull()?.let(entities::vehicle)?.let {
        session.entityHandle(it)
    }

    override fun inventory(self: LuaHandle.Entity): LuaHandle.Inventory? {
        val info = info(self) ?: return null
        val ref = if (info.player != null) InventoryRef.Player(info.id) else InventoryRef.Entity(info.id)
        return ref.takeIf { session.platform.inventories.kind(it) != null }?.let(InventoryKeys::of)
    }

    /** A ray from the eyes along where it looks, as far as [maxDistance], or null while it isn't in the world. */
    private fun sight(self: LuaHandle.Entity, maxDistance: Double?): Triple<String, Vec3, Vec3>? {
        val distance = maxDistance ?: TARGET_DISTANCE
        if (distance < 0 || !distance.isFinite()) throw LuaApiException("max_distance can't be below 0")
        val eye = self.uuidOrNull()?.let(entities::eye) ?: return null
        return Triple(eye.world, Vec3(eye.x, eye.y, eye.z), Vec3(eye.dx, eye.dy, eye.dz))
    }

    override fun targetBlock(self: LuaHandle.Entity, maxDistance: Double?): LuaHandle.Block? {
        val (world, from, direction) = sight(self, maxDistance) ?: return null
        val hit = session.platform.worlds.raycastBlocks(world, from, direction, maxDistance ?: TARGET_DISTANCE, false) ?: return null
        return blockHandle(world, hit.x, hit.y, hit.z)
    }

    override fun targetEntity(self: LuaHandle.Entity, maxDistance: Double?): LuaHandle.Entity? {
        val (world, from, direction) = sight(self, maxDistance) ?: return null
        val hit = session.raycast(
            world,
            from,
            direction,
            maxDistance ?: TARGET_DISTANCE,
            RaycastOptions(centities = false, ignore = listOf(self))
        )
        return hit?.entity
    }

    override fun targetCentity(self: LuaHandle.Entity, maxDistance: Double?): LuaHandle.Centity? {
        val (world, from, direction) = sight(self, maxDistance) ?: return null
        val hit = session.raycast(world, from, direction, maxDistance ?: TARGET_DISTANCE, RaycastOptions(entities = false))
        return hit?.centity
    }

    private fun checkTag(tag: String) {
        if (!TAG.matches(tag)) throw LuaApiException("\"$tag\" isn't a tag: letters, digits, _, -, . and +")
    }

    private companion object {
        val TAG = Regex("[A-Za-z0-9_.+-]+")
    }
}

/** [id] in the project's namespace (`slow_zone` is `shop:slow_zone`); another namespace's is the script's mistake. */
internal fun ProjectSession.ownId(id: String, what: String): String {
    val namespace = namespace
    if (id.substringBefore(':', namespace) != namespace) {
        throw LuaApiException("\"$id\" isn't one of the project's $what ids: \"<name>\" or \"$namespace:<name>\"")
    }
    return "$namespace:${id.substringAfter(':')}"
}

/** `DroppedItem`: an item on the ground. */
internal class DroppedItemImpl(private val session: ProjectSession) : DroppedItemApi {
    private val entities get() = session.platform.worldEntities

    private fun number(self: LuaHandle.Entity, number: EntityNumber): Double? = session.entityNumber(self, number)

    override fun item(self: LuaHandle.DroppedItem): ItemData? = self.uuidOrNull()?.let(entities::item)

    override fun setItem(self: LuaHandle.DroppedItem, item: ItemData): Boolean =
        self.uuidOrNull()?.let { entities.setItem(it, item) } == true

    override fun pickupDelay(self: LuaHandle.DroppedItem): Long? = number(self, EntityNumber.PICKUP_DELAY)?.toLong()

    override fun setPickupDelay(self: LuaHandle.DroppedItem, ticks: Long): Boolean {
        if (ticks !in 0..32767) throw LuaApiException("ticks must be from 0 to 32767, not $ticks")
        val id = self.uuidOrNull() ?: return false
        return entities.setNumber(id, EntityNumber.PICKUP_DELAY, ticks.toDouble())
    }
}
