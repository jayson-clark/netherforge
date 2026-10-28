package dev.netherforge.plugin.paper

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.EntityBox
import dev.netherforge.plugin.platform.EntityCategory
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityHit
import dev.netherforge.plugin.platform.EntityInfo
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.platform.WorldEntityOps
import io.papermc.paper.entity.TeleportFlag
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.attribute.Attribute
import org.bukkit.entity.Entity
import org.bukkit.entity.EntityType
import org.bukkit.entity.Item
import org.bukkit.entity.LivingEntity
import org.bukkit.entity.Mannequin
import org.bukkit.entity.Mob
import org.bukkit.entity.Player
import org.bukkit.event.entity.CreatureSpawnEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin
import org.bukkit.potion.PotionEffect
import org.bukkit.util.BoundingBox
import org.bukkit.util.Vector
import java.util.UUID
import org.bukkit.Location as BukkitLocation

/**
 * [WorldEntityOps] with the Paper API: vanilla entities by UUID, never the
 * display and interaction entities centities are drawn with (those carry a
 * tag, [PaperEntities.tagOf]).
 *
 * An entity that is being spawned isn't in the world's lookup yet, so while
 * its spawn event is handled it's kept in [spawning]: an `entity_spawn`
 * handler can name it or tag it.
 */
class PaperWorldEntities(private val plugin: Plugin, private val tagged: PaperEntities) : WorldEntityOps {
    private val mini = PaperText.mini

    /** Entities in the middle of being spawned, by UUID. */
    val spawning = HashMap<UUID, Entity>()

    fun entity(id: UUID): Entity? = (spawning[id] ?: Bukkit.getEntity(id))?.takeIf { tagged.tagOf(it) == null }

    private fun living(id: UUID) = entity(id) as? LivingEntity

    private fun player(id: UUID) = entity(id) as? Player

    private fun info(entity: Entity) = EntityInfo(
        entity.uniqueId,
        entity.type.key.toString(),
        PaperPlatform.location(entity.location),
        when (entity) {
            is Mob -> EntityCategory.MOB
            is Mannequin -> EntityCategory.MANNEQUIN
            is LivingEntity -> EntityCategory.LIVING
            is Item -> EntityCategory.DROPPED_ITEM
            else -> EntityCategory.OTHER
        },
        (entity as? Player)?.let { dev.netherforge.plugin.platform.PlayerRef(it.uniqueId, it.name) },
        entity.scoreboardTags.toSet()
    )

    override fun info(id: UUID): EntityInfo? = entity(id)?.let(::info)

    override fun list(world: String): List<EntityInfo> =
        Bukkit.getWorld(world)?.entities.orEmpty().filter { tagged.tagOf(it) == null }.map(::info)

    override fun eye(id: UUID): Ray? {
        val entity = entity(id) ?: return null
        val eye = (entity as? LivingEntity)?.eyeLocation ?: entity.location
        val direction = eye.direction
        return Ray(eye.world.name, eye.x, eye.y, eye.z, direction.x, direction.y, direction.z)
    }

    override fun velocity(id: UUID): Vec3? = entity(id)?.velocity?.let { Vec3(it.x, it.y, it.z) }

    override fun setVelocity(id: UUID, velocity: Vec3): Boolean {
        val entity = entity(id) ?: return false
        entity.velocity = Vector(velocity.x, velocity.y, velocity.z)
        return true
    }

    private fun bukkit(to: Location): BukkitLocation? =
        Bukkit.getWorld(to.world)?.let { BukkitLocation(it, to.x, to.y, to.z, to.yaw.toFloat(), to.pitch.toFloat()) }

    override fun teleport(id: UUID, to: Location): Boolean {
        val entity = entity(id) ?: return false
        val location = bukkit(to) ?: return false
        return entity.teleport(location, TeleportFlag.EntityState.RETAIN_PASSENGERS)
    }

    override fun bodyYaw(id: UUID): Double? = living(id)?.bodyYaw?.toDouble()

    override fun setRotation(id: UUID, yaw: Double, pitch: Double): Boolean {
        val entity = entity(id) ?: return false
        if (entity is Player) {
            // A player's facing only changes by a teleport to where they stand.
            val at = entity.location.clone().apply {
                this.yaw = yaw.toFloat()
                this.pitch = pitch.toFloat()
            }
            return entity.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS, TeleportFlag.EntityState.RETAIN_VEHICLE)
        }
        entity.setRotation(yaw.toFloat(), pitch.toFloat())
        return true
    }

    override fun remove(id: UUID): Boolean {
        val entity = entity(id)?.takeIf { it !is Player } ?: return false
        entity.remove()
        return true
    }

    override fun flag(id: UUID, flag: EntityFlag): Boolean? {
        val entity = entity(id) ?: return null
        return when (flag) {
            EntityFlag.ON_GROUND -> entity.isOnGround
            EntityFlag.CUSTOM_NAME_VISIBLE -> entity.isCustomNameVisible
            EntityFlag.GLOWING -> entity.isGlowing
            EntityFlag.VISIBLE -> entity.isVisibleByDefault
            EntityFlag.SILENT -> entity.isSilent
            EntityFlag.GRAVITY -> entity.hasGravity()
            EntityFlag.INVULNERABLE -> entity.isInvulnerable
            EntityFlag.AI -> (entity as? Mob)?.hasAI()
            EntityFlag.SNEAKING -> (entity as? Player)?.isSneaking
            EntityFlag.SPRINTING -> (entity as? Player)?.isSprinting
            EntityFlag.FLYING -> (entity as? Player)?.isFlying
            EntityFlag.CAN_FLY -> (entity as? Player)?.allowFlight
        }
    }

    override fun setFlag(id: UUID, flag: EntityFlag, value: Boolean): Boolean {
        val entity = entity(id) ?: return false
        when (flag) {
            EntityFlag.ON_GROUND, EntityFlag.SNEAKING, EntityFlag.SPRINTING -> return false
            EntityFlag.CUSTOM_NAME_VISIBLE -> entity.isCustomNameVisible = value
            EntityFlag.GLOWING -> entity.isGlowing = value
            EntityFlag.VISIBLE -> entity.isVisibleByDefault = value
            EntityFlag.SILENT -> entity.isSilent = value
            EntityFlag.GRAVITY -> entity.setGravity(value)
            EntityFlag.INVULNERABLE -> entity.isInvulnerable = value
            EntityFlag.AI -> (entity as? Mob)?.setAI(value) ?: return false
            EntityFlag.FLYING -> {
                val player = entity as? Player ?: return false
                if (value && !player.allowFlight) return false
                player.isFlying = value
            }
            EntityFlag.CAN_FLY -> (entity as? Player ?: return false).allowFlight = value
        }
        return true
    }

    override fun number(id: UUID, number: EntityNumber): Double? {
        val entity = entity(id) ?: return null
        val player = entity as? Player
        return when (number) {
            EntityNumber.HEALTH -> (entity as? LivingEntity)?.health
            EntityNumber.MAX_HEALTH -> (entity as? LivingEntity)?.getAttribute(Attribute.MAX_HEALTH)?.value
            EntityNumber.FOOD -> player?.foodLevel?.toDouble()
            EntityNumber.SATURATION -> player?.saturation?.toDouble()
            EntityNumber.LEVEL -> player?.level?.toDouble()
            EntityNumber.EXPERIENCE_PROGRESS -> player?.exp?.toDouble()
            EntityNumber.WALK_SPEED -> player?.walkSpeed?.toDouble()
            EntityNumber.FLY_SPEED -> player?.flySpeed?.toDouble()
            EntityNumber.HELD_SLOT -> player?.inventory?.heldItemSlot?.toDouble()
            EntityNumber.PING -> player?.ping?.toDouble()
            EntityNumber.PICKUP_DELAY -> (entity as? Item)?.pickupDelay?.toDouble()
        }
    }

    override fun setNumber(id: UUID, number: EntityNumber, value: Double): Boolean {
        val entity = entity(id) ?: return false
        val player = entity as? Player
        when (number) {
            EntityNumber.HEALTH -> (entity as? LivingEntity)?.health = value
            EntityNumber.FOOD -> player?.foodLevel = value.toInt()
            EntityNumber.SATURATION -> player?.saturation = value.toFloat()
            EntityNumber.LEVEL -> player?.level = value.toInt()
            EntityNumber.EXPERIENCE_PROGRESS -> player?.exp = value.toFloat()
            EntityNumber.WALK_SPEED -> player?.walkSpeed = value.toFloat()
            EntityNumber.FLY_SPEED -> player?.flySpeed = value.toFloat()
            EntityNumber.HELD_SLOT -> player?.inventory?.heldItemSlot = value.toInt()
            EntityNumber.PICKUP_DELAY -> (entity as? Item)?.pickupDelay = value.toInt()
            EntityNumber.MAX_HEALTH, EntityNumber.PING -> return false
        }
        return when (number) {
            EntityNumber.HEALTH -> entity is LivingEntity
            EntityNumber.PICKUP_DELAY -> entity is Item
            else -> player != null
        }
    }

    override fun name(id: UUID): String? {
        val entity = entity(id) ?: return null
        if (entity is Player) return entity.name
        return mini.serialize(entity.customName() ?: entity.name())
    }

    override fun customName(id: UUID): String? = entity(id)?.customName()?.let(mini::serialize)

    override fun setCustomName(id: UUID, miniMessage: String?): Boolean {
        val entity = entity(id) ?: return false
        entity.customName(miniMessage?.let(mini::deserialize))
        return true
    }

    override fun addTag(id: UUID, tag: String): Boolean = entity(id)?.addScoreboardTag(tag) == true

    override fun removeTag(id: UUID, tag: String): Boolean = entity(id)?.removeScoreboardTag(tag) == true

    override fun data(id: UUID): String? = entity(id)?.persistentDataContainer?.get(DATA_KEY, PersistentDataType.STRING)

    override fun setData(id: UUID, json: String?): Boolean {
        val entity = entity(id) ?: return false
        setData(entity, json)
        return true
    }

    private fun setData(entity: Entity, json: String?) {
        val container = entity.persistentDataContainer
        if (json == null) container.remove(DATA_KEY) else container.set(DATA_KEY, PersistentDataType.STRING, json)
    }

    override fun hide(viewer: UUID, id: UUID): Boolean {
        val player = Bukkit.getPlayer(viewer) ?: return false
        player.hideEntity(plugin, entity(id) ?: return false)
        return true
    }

    override fun show(viewer: UUID, id: UUID): Boolean {
        val player = Bukkit.getPlayer(viewer) ?: return false
        player.showEntity(plugin, entity(id) ?: return false)
        return true
    }

    override fun passengers(id: UUID): List<UUID>? = entity(id)?.passengers?.filter { tagged.tagOf(it) == null }?.map { it.uniqueId }

    override fun addPassenger(id: UUID, passenger: UUID): Boolean {
        val entity = entity(id) ?: return false
        return entity.addPassenger(entity(passenger) ?: return false)
    }

    override fun removePassenger(id: UUID, passenger: UUID): Boolean {
        val entity = entity(id) ?: return false
        val rider = entity(passenger) ?: return false
        if (rider !in entity.passengers) return false
        return entity.removePassenger(rider)
    }

    override fun vehicle(id: UUID): UUID? = entity(id)?.vehicle?.takeIf { tagged.tagOf(it) == null }?.uniqueId

    override fun damage(id: UUID, amount: Double, source: UUID?): Boolean {
        val entity = living(id) ?: return false
        val by = source?.let(::entity)
        if (by != null) entity.damage(amount, by) else entity.damage(amount)
        return true
    }

    override fun effects(id: UUID): List<StatusEffectData>? = living(id)?.activePotionEffects?.map(::effectData)

    private fun effectType(effect: String) = NamespacedKey.fromString(effect)?.let { Registry.MOB_EFFECT.get(it) }

    override fun addEffect(id: UUID, effect: StatusEffectData): Boolean {
        val entity = living(id) ?: return false
        val type = effectType(effect.effect) ?: return false
        val ticks = if (effect.ticks < 0) PotionEffect.INFINITE_DURATION else effect.ticks
        entity.removePotionEffect(type)
        return entity.addPotionEffect(PotionEffect(type, ticks, effect.amplifier, effect.ambient, effect.particles, effect.icon))
    }

    override fun removeEffect(id: UUID, effect: String): Boolean {
        val entity = living(id) ?: return false
        val type = effectType(effect) ?: return false
        if (!entity.hasPotionEffect(type)) return false
        entity.removePotionEffect(type)
        return true
    }

    override fun effectExists(effect: String): Boolean = effectType(effect) != null

    override fun equipment(id: UUID, slot: String): ItemData? {
        val equipment = living(id)?.equipment ?: return null
        val which = slot(slot) ?: return null
        return PaperItems.toItem(runCatching { equipment.getItem(which) }.getOrNull(), EquipmentOf(id, which))
    }

    override fun setEquipment(id: UUID, slot: String, item: ItemData?): Boolean {
        val equipment = living(id)?.equipment ?: return false
        val which = slot(slot) ?: return false
        val stack = item?.let { PaperItems.toStack(it) ?: return false }
        return runCatching { equipment.setItem(which, stack) }.isSuccess
    }

    override fun target(id: UUID): UUID? = (entity(id) as? Mob)?.target?.uniqueId

    override fun setTarget(id: UUID, target: UUID?): Boolean {
        val mob = entity(id) as? Mob ?: return false
        mob.target = target?.let { entity(it) as? LivingEntity }
        return true
    }

    override fun item(id: UUID): ItemData? = (entity(id) as? Item)?.itemStack?.let(PaperItems::toItem)

    override fun setItem(id: UUID, item: ItemData): Boolean {
        val entity = entity(id) as? Item ?: return false
        entity.itemStack = PaperItems.toStack(item) ?: return false
        return true
    }

    private fun type(kind: String): EntityType? = NamespacedKey.fromString(kind)?.let { Registry.ENTITY_TYPE.get(it) }

    override fun spawnable(kind: String): Boolean {
        val type = type(kind) ?: return false
        return type.isSpawnable && type != EntityType.PLAYER && type != EntityType.ITEM && type.entityClass != null
    }

    override fun spawn(kind: String, at: Location, setup: SpawnSetup): UUID? {
        val type = type(kind) ?: return null
        val location = bukkit(at) ?: return null
        if (!location.world.isChunkLoaded(location.blockX shr 4, location.blockZ shr 4)) return null
        var id: UUID? = null
        val entity = location.world.spawn(location, type.entityClass!!, CreatureSpawnEvent.SpawnReason.CUSTOM) { entity ->
            id = entity.uniqueId
            spawning[entity.uniqueId] = entity
            setup.customName?.let { entity.customName(mini.deserialize(it)) }
            for (tag in setup.tags) entity.addScoreboardTag(tag)
            setup.data?.let { setData(entity, it) }
            setup.velocity?.let { entity.velocity = Vector(it.x, it.y, it.z) }
        }
        id?.let(spawning::remove)
        return entity.takeIf { it.isValid }?.uniqueId
    }

    override fun spawnItem(world: String, position: Vec3, item: ItemData): UUID? {
        val w = Bukkit.getWorld(world) ?: return null
        val stack = PaperItems.toStack(item) ?: return null
        if (!w.isChunkLoaded(kotlin.math.floor(position.x).toInt() shr 4, kotlin.math.floor(position.z).toInt() shr 4)) return null
        var id: UUID? = null
        val entity = w.dropItem(BukkitLocation(w, position.x, position.y, position.z), stack) {
            id = it.uniqueId
            spawning[it.uniqueId] = it
        }
        id?.let(spawning::remove)
        return entity.takeIf { it.isValid }?.uniqueId
    }

    override fun raycast(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, ignore: Set<UUID>): EntityHit? {
        val w = Bukkit.getWorld(world) ?: return null
        if (maxDistance <= 0) return null
        val result = w.rayTraceEntities(
            BukkitLocation(w, origin.x, origin.y, origin.z),
            Vector(direction.x, direction.y, direction.z),
            maxDistance,
            0.0
        ) { it.uniqueId !in ignore && tagged.tagOf(it) == null && !(it is Player && it.gameMode == org.bukkit.GameMode.SPECTATOR) }
            ?: return null
        val entity = result.hitEntity ?: return null
        val at = result.hitPosition
        val face = result.hitBlockFace?.direction ?: Vector(-direction.x, -direction.y, -direction.z)
        return EntityHit(entity.uniqueId, Vec3(at.x, at.y, at.z), Vec3(face.x, face.y, face.z))
    }

    override fun boxes(world: String, region: Box): List<EntityBox> {
        val w = Bukkit.getWorld(world) ?: return emptyList()
        // Only loaded chunks' entities are in the world's lists, so this never loads one.
        val area = BoundingBox(region.min.x, region.min.y, region.min.z, region.max.x, region.max.y, region.max.z)
        return w.getNearbyEntities(area) { tagged.tagOf(it) == null && !(it is Player && it.gameMode == org.bukkit.GameMode.SPECTATOR) }
            .map { entity ->
                val b = entity.boundingBox
                EntityBox(
                    entity.uniqueId,
                    entity.type.key.toString(),
                    entity is Player,
                    Box(Vec3(b.minX, b.minY, b.minZ), Vec3(b.maxX, b.maxY, b.maxZ))
                )
            }
    }

    companion object {
        /** Where a script's data lives on an entity: the same key an item's script data uses. */
        val DATA_KEY = NamespacedKey("netherforge", "data")

        /** An equipment slot by the name scripts use for it. */
        fun slot(name: String): EquipmentSlot? = when (name) {
            "main_hand" -> EquipmentSlot.HAND
            "off_hand" -> EquipmentSlot.OFF_HAND
            "head" -> EquipmentSlot.HEAD
            "chest" -> EquipmentSlot.CHEST
            "legs" -> EquipmentSlot.LEGS
            "feet" -> EquipmentSlot.FEET
            "body" -> EquipmentSlot.BODY
            else -> null
        }
    }
}

/** A status effect as the runtime has it. */
internal fun effectData(effect: PotionEffect) = StatusEffectData(
    effect.type.key.toString(),
    if (effect.isInfinite) -1 else effect.duration,
    effect.amplifier,
    effect.isAmbient,
    effect.hasParticles(),
    effect.hasIcon()
)

/** Where an equipment read is remembered ([PaperItems.toItem]): an entity's slot. */
private data class EquipmentOf(val entity: UUID, val slot: EquipmentSlot)
