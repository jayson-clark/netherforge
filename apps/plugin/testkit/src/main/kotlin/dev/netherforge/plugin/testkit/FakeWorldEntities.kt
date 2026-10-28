package dev.netherforge.plugin.testkit

import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.platform.EntityBox
import dev.netherforge.plugin.platform.EntityCategory
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.EntityHit
import dev.netherforge.plugin.platform.EntityInfo
import dev.netherforge.plugin.platform.EntityNumber
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.MannequinLook
import dev.netherforge.plugin.platform.MannequinOps
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.SpawnSetup
import dev.netherforge.plugin.platform.StatusEffectData
import dev.netherforge.plugin.platform.WorldEntityOps
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

// Vanilla entities on the fake server (mobs, items, players' bodies), by UUID.

/**
 * Anything in a world scripts handle as an `Entity`: a mob, a dropped item,
 * a minecart, and (as [FakePlayer]) a player. Its state is plain fields a
 * test can read and set.
 */
open class FakeBody(val id: UUID, val kind: String, var location: Location) {
    private val type = FakePlatform.KINDS[kind] ?: error("the fake server has no entity type $kind")

    /** Something with health: a mob, an armour stand, a player. */
    val living get() = type.health != null

    /** Which way a living body faces: a test sets it apart from the look, as a walking player's body lags. */
    var bodyYaw: Double = location.yaw

    /** Has AI: a mob, not an armour stand or a player. */
    val mob get() = type.mob

    /** A mannequin's own look; null for anything else. */
    var mannequin: MannequinLook? = if (type.mannequin) MannequinLook(description = FakeMannequins.DEFAULT_DESCRIPTION) else null

    /** Which of the API's classes it is. */
    val category
        get() = when {
            type.mob -> EntityCategory.MOB
            type.mannequin -> EntityCategory.MANNEQUIN
            type.health != null -> EntityCategory.LIVING
            type.droppedItem -> EntityCategory.DROPPED_ITEM
            else -> EntityCategory.OTHER
        }

    /** Its health when it's well. */
    val maxHealth get() = type.health

    var velocity = Vec3.ZERO
    var customName: String? = null
    val tags = LinkedHashSet<String>()
    var data: String? = null
    val flags = mutableMapOf(
        EntityFlag.ON_GROUND to true,
        EntityFlag.CUSTOM_NAME_VISIBLE to false,
        EntityFlag.GLOWING to false,
        EntityFlag.VISIBLE to true,
        EntityFlag.SILENT to false,
        EntityFlag.GRAVITY to true,
        EntityFlag.INVULNERABLE to false
    )
    val numbers = mutableMapOf<EntityNumber, Double>()
    val effects = mutableListOf<StatusEffectData>()
    val equipment = mutableMapOf<String, ItemData>()
    val passengers = mutableListOf<UUID>()
    var vehicle: UUID? = null
    var target: UUID? = null

    /** A dropped item's stack. */
    var item: ItemData? = null

    /** What it drops when it dies. */
    val loot = mutableListOf<ItemData>()

    /** What it carries, for an entity with an inventory. */
    var inventory: Array<ItemData?>? = null
    val hiddenFrom = mutableSetOf<UUID>()

    /** Its box, for rays: its kind's size. */
    val width get() = type.width
    val height get() = type.height
    open val eyeHeight get() = height * 0.85
}

class FakeWorldEntities(private val platform: FakePlatform) : WorldEntityOps {
    /** Every entity that isn't a player, by UUID. */
    val mobs = LinkedHashMap<UUID, FakeBody>()

    /** What this fake server can spawn: every kind it knows ([KINDS]) but players and dropped items. */
    val spawnableKinds = FakePlatform.KINDS.keys - setOf("minecraft:player", "minecraft:item")

    val knownEffects = setOf("minecraft:speed", "minecraft:poison", "minecraft:regeneration")

    /** A mob, or a player, while its chunk is loaded. */
    fun body(id: UUID): FakeBody? = platform.players.byId[id] ?: mobs[id]?.takeIf { platform.worlds.entitiesLoaded(it.location) }

    /** Anything but a player. */
    private fun notPlayer(id: UUID): FakeBody? = body(id)?.takeIf { it !is FakePlayer }

    /** Something with AI. */
    private fun mob(id: UUID): FakeBody? = body(id)?.takeIf { it.mob }

    override fun info(id: UUID): EntityInfo? = body(id)?.let(::infoOf)

    private fun infoOf(body: FakeBody) =
        EntityInfo(body.id, body.kind, body.location, body.category, (body as? FakePlayer)?.ref, body.tags.toSet())

    override fun list(world: String): List<EntityInfo> = (
        platform.players.byId.values + mobs.values.filter {
            platform.worlds.entitiesLoaded(it.location)
        }
        ).filter { it.location.world == world }.map(::infoOf)

    override fun eye(id: UUID): Ray? {
        val body = body(id) ?: return null
        val l = body.location
        val yaw = Math.toRadians(l.yaw)
        val pitch = Math.toRadians(l.pitch)
        return Ray(l.world, l.x, l.y + body.eyeHeight, l.z, -sin(yaw) * cos(pitch), -sin(pitch), cos(yaw) * cos(pitch))
    }

    override fun velocity(id: UUID) = body(id)?.velocity

    override fun bodyYaw(id: UUID): Double? = body(id)?.takeIf { it.living }?.bodyYaw

    override fun setVelocity(id: UUID, velocity: Vec3): Boolean {
        val body = body(id) ?: return false
        body.velocity = velocity
        return true
    }

    override fun teleport(id: UUID, to: Location): Boolean {
        val body = body(id) ?: return false
        if (!platform.worlds.exists(to.world)) return false
        // A player's teleport is heard first, as on the server.
        if (body is FakePlayer) return platform.players.teleport(body, to, "plugin")
        body.location = to
        return true
    }

    override fun setRotation(id: UUID, yaw: Double, pitch: Double): Boolean {
        val body = body(id) ?: return false
        body.location = body.location.copy(yaw = yaw, pitch = pitch)
        return true
    }

    override fun remove(id: UUID): Boolean = notPlayer(id)?.let { mobs.remove(it.id) } != null

    private fun applies(body: FakeBody, flag: EntityFlag): Boolean = when (flag) {
        EntityFlag.AI -> body.mob
        EntityFlag.SNEAKING, EntityFlag.SPRINTING, EntityFlag.FLYING, EntityFlag.CAN_FLY -> body is FakePlayer
        else -> true
    }

    override fun flag(id: UUID, flag: EntityFlag): Boolean? {
        val body = body(id)?.takeIf { applies(it, flag) } ?: return null
        return body.flags[flag] ?: (flag == EntityFlag.AI)
    }

    override fun setFlag(id: UUID, flag: EntityFlag, value: Boolean): Boolean {
        val body = body(id)?.takeIf { applies(it, flag) && flag.settable } ?: return false
        if (flag == EntityFlag.FLYING && value && body.flags[EntityFlag.CAN_FLY] != true) return false
        body.flags[flag] = value
        return true
    }

    private fun applies(body: FakeBody, number: EntityNumber): Boolean = when (number) {
        EntityNumber.HEALTH, EntityNumber.MAX_HEALTH -> body.living
        EntityNumber.PICKUP_DELAY -> body.item != null
        else -> body is FakePlayer
    }

    private fun default(number: EntityNumber): Double = when (number) {
        EntityNumber.FOOD -> 20.0
        EntityNumber.SATURATION -> 5.0
        EntityNumber.WALK_SPEED -> 0.2
        EntityNumber.FLY_SPEED -> 0.1
        EntityNumber.PING -> 42.0
        EntityNumber.PICKUP_DELAY -> 10.0
        else -> 0.0
    }

    override fun number(id: UUID, number: EntityNumber): Double? {
        val body = body(id)?.takeIf { applies(it, number) } ?: return null
        if (number == EntityNumber.MAX_HEALTH) return body.maxHealth
        if (number == EntityNumber.HEALTH) return body.numbers[number] ?: body.maxHealth
        return body.numbers[number] ?: default(number)
    }

    override fun setNumber(id: UUID, number: EntityNumber, value: Double): Boolean {
        val body = body(id)?.takeIf { applies(it, number) && number.settable } ?: return false
        body.numbers[number] = value
        if (number == EntityNumber.HEALTH && value <= 0.0) die(body, null)
        return true
    }

    override fun name(id: UUID): String? {
        val body = body(id) ?: return null
        if (body is FakePlayer) return body.ref.name
        // Its kind's name in the player's language, as the game sends it: a translatable component.
        return body.customName ?: "<lang:entity.${body.kind.replace(':', '.')}>"
    }

    override fun customName(id: UUID) = body(id)?.customName

    override fun setCustomName(id: UUID, miniMessage: String?): Boolean {
        val body = body(id) ?: return false
        body.customName = miniMessage
        return true
    }

    override fun addTag(id: UUID, tag: String) = body(id)?.tags?.add(tag) == true

    override fun removeTag(id: UUID, tag: String) = body(id)?.tags?.remove(tag) == true

    override fun data(id: UUID) = body(id)?.data

    override fun setData(id: UUID, json: String?): Boolean {
        val body = body(id) ?: return false
        body.data = json
        return true
    }

    override fun hide(viewer: UUID, id: UUID): Boolean {
        if (viewer !in platform.players.byId) return false
        return body(id)?.hiddenFrom?.add(viewer) != null
    }

    override fun show(viewer: UUID, id: UUID): Boolean {
        if (viewer !in platform.players.byId) return false
        return body(id)?.hiddenFrom?.remove(viewer) != null
    }

    override fun passengers(id: UUID) = body(id)?.passengers?.toList()

    override fun addPassenger(id: UUID, passenger: UUID): Boolean {
        val body = body(id) ?: return false
        val rider = body(passenger) ?: return false
        if (rider.vehicle != null || body.vehicle == passenger) return false
        body.passengers += passenger
        rider.vehicle = id
        return true
    }

    override fun removePassenger(id: UUID, passenger: UUID): Boolean {
        val body = body(id) ?: return false
        if (!body.passengers.remove(passenger)) return false
        body(passenger)?.vehicle = null
        return true
    }

    override fun vehicle(id: UUID) = body(id)?.vehicle

    /** Hurts it as the server would: its damage event first, which may change or cancel it. */
    override fun damage(id: UUID, amount: Double, source: UUID?): Boolean {
        val body = body(id)?.takeIf { it.living } ?: return false
        hurt(body, amount, if (source != null) "entity_attack" else "custom", source)
        return true
    }

    /** Something hurts [body]; true when it took the damage. */
    fun hurt(body: FakeBody, amount: Double, cause: String, attacker: UUID? = null): Boolean {
        val dealt = platform.events.let { if (it == null) amount else it.entityDamaged(body.id, amount, cause, attacker) } ?: return false
        if (body.flags[EntityFlag.INVULNERABLE] == true) return false
        val health = (body.numbers[EntityNumber.HEALTH] ?: body.maxHealth ?: 0.0) - dealt
        body.numbers[EntityNumber.HEALTH] = health.coerceAtLeast(0.0)
        if (health <= 0.0) die(body, attacker, cause)
        return true
    }

    /** Every death so far: the entity, and the experience it dropped. */
    val deaths = mutableListOf<Pair<UUID, Int>>()

    /** What each death dropped, in the same order as [deaths]. */
    val drops = mutableListOf<List<ItemData>>()

    /** Every player death's message (null for none) and whether they kept their inventory. */
    val playerDeaths = mutableListOf<Pair<String?, Boolean>>()

    /** Dies as on the server: a player through their death event (then everyone's), anything else its own. */
    fun die(body: FakeBody, killer: UUID?, cause: String = "custom") {
        if (body is FakePlayer) {
            val carried = body.inventorySlots.filterNotNull()
            // What the game drops of a player's experience: seven points a level, at most a hundred.
            val experience = minOf((body.numbers[EntityNumber.LEVEL] ?: 0.0).toInt() * 7, 100)
            body.dead = true
            body.using = null
            val answer = platform.events?.playerDied(body.ref, killer, cause, "${body.ref.name} died", false, carried, experience)
            deaths += body.id to (answer?.experience ?: experience)
            drops += answer?.drops ?: if (answer?.keepInventory == true) emptyList() else carried
            playerDeaths += (answer?.message ?: "${body.ref.name} died") to (answer?.keepInventory ?: false)
            if (answer?.keepInventory != true) body.inventorySlots.fill(null)
            return
        }
        val answer = platform.events?.entityDied(body.id, killer, { body.loot.toList() }, 5)
        deaths += body.id to (answer?.experience ?: 5)
        drops += answer?.drops ?: body.loot.toList()
        mobs.remove(body.id)
    }

    override fun effects(id: UUID) = body(id)?.takeIf { it.living }?.effects?.toList()

    override fun addEffect(id: UUID, effect: StatusEffectData): Boolean {
        val body = body(id)?.takeIf { it.living } ?: return false
        body.effects.removeAll { it.effect == effect.effect }
        body.effects += effect
        return true
    }

    override fun removeEffect(id: UUID, effect: String) = body(id)?.effects?.removeAll { it.effect == effect } == true

    override fun effectExists(effect: String) = effect in knownEffects

    override fun equipment(id: UUID, slot: String): ItemData? {
        val body = body(id)?.takeIf { it.living } ?: return null
        if (body is FakePlayer) return playerSlot(body, slot)?.let { body.inventorySlots[it] }
        return body.equipment[slot]
    }

    private fun playerSlot(player: FakePlayer, slot: String): Int? = when (slot) {
        "main_hand" -> (player.numbers[EntityNumber.HELD_SLOT] ?: 0.0).toInt()
        "off_hand" -> 40
        "feet" -> 36
        "legs" -> 37
        "chest" -> 38
        "head" -> 39
        "body" -> 41
        else -> null
    }

    override fun setEquipment(id: UUID, slot: String, item: ItemData?): Boolean {
        val body = body(id)?.takeIf { it.living } ?: return false
        val stack = item?.let { platform.stack(it) ?: return false }
        if (body is FakePlayer) {
            val index = playerSlot(body, slot) ?: return false
            body.inventorySlots[index] = stack
            return true
        }
        if (stack == null) body.equipment.remove(slot) else body.equipment[slot] = stack
        return true
    }

    override fun target(id: UUID) = mob(id)?.target

    override fun setTarget(id: UUID, target: UUID?): Boolean {
        val body = mob(id) ?: return false
        body.target = target
        return true
    }

    override fun item(id: UUID) = body(id)?.item

    override fun setItem(id: UUID, item: ItemData): Boolean {
        val body = body(id)?.takeIf { it.item != null } ?: return false
        body.item = platform.stack(item) ?: return false
        return true
    }

    override fun spawnable(kind: String) = kind in spawnableKinds

    /** Spawns as the server does: its spawn event first, which may cancel it. */
    override fun spawn(kind: String, at: Location, setup: SpawnSetup): UUID? {
        if (!platform.worlds.exists(at.world) || !platform.worlds.entitiesLoaded(at)) return null
        val body = FakeBody(UUID.randomUUID(), kind, at)
        if (kind == "minecraft:chest_minecart") body.inventory = arrayOfNulls(27)
        body.customName = setup.customName
        body.tags += setup.tags
        body.data = setup.data
        setup.velocity?.let { body.velocity = it }
        return add(body)
    }

    override fun spawnItem(world: String, position: Vec3, item: ItemData): UUID? {
        val at = Location(world, position.x, position.y, position.z)
        if (!platform.worlds.exists(world) || !platform.worlds.entitiesLoaded(at)) return null
        val body = FakeBody(UUID.randomUUID(), "minecraft:item", at)
        body.item = platform.stack(item) ?: return null
        return add(body)
    }

    private fun add(body: FakeBody): UUID? {
        mobs[body.id] = body
        if (platform.raise.entitySpawn(GameEvent.EntitySpawn(body.id, body.location.world, "custom"))) {
            mobs.remove(body.id)
            return null
        }
        return body.id
    }

    override fun raycast(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, ignore: Set<UUID>): EntityHit? {
        var best: Pair<Double, EntityHit>? = null
        for (body in platform.players.byId.values + mobs.values.filter { platform.worlds.entitiesLoaded(it.location) }) {
            if (body.location.world != world || body.id in ignore) continue
            val l = body.location
            val half = body.width / 2
            val box = Box(Vec3(l.x - half, l.y, l.z - half), Vec3(l.x + half, l.y + body.height, l.z + half))
            val (distance, normal) = FakePlatform.slab(box, origin, direction, maxDistance) ?: continue
            if (best == null || distance < best.first) best = distance to EntityHit(body.id, origin + direction * distance, normal)
        }
        return best?.second
    }

    override fun boxes(world: String, region: Box): List<EntityBox> {
        val found = mutableListOf<EntityBox>()
        for (body in platform.players.byId.values + mobs.values.filter { platform.worlds.entitiesLoaded(it.location) }) {
            if (body.location.world != world) continue
            if (body is FakePlayer && body.gameMode == "spectator") continue
            val l = body.location
            val half = body.width / 2
            val box = Box(Vec3(l.x - half, l.y, l.z - half), Vec3(l.x + half, l.y + body.height, l.z + half))
            val apart = box.max.x < region.min.x ||
                box.min.x > region.max.x ||
                box.max.y < region.min.y ||
                box.min.y > region.max.y ||
                box.max.z < region.min.z ||
                box.min.z > region.max.z
            if (apart) continue
            found += EntityBox(body.id, body.kind, body is FakePlayer, box)
        }
        return found
    }

    /**
     * Dropped items whose delay is up go to the first player (alive) whose
     * box, grown by a block sideways and half a block up and down, they're
     * in: heard first (cancelled, it stays), then as much as fits goes
     * into their inventory, as the server's pickup does.
     */
    fun pickUp() {
        for (body in mobs.values.toList()) {
            val stack = body.item ?: continue
            val delay = body.numbers[EntityNumber.PICKUP_DELAY] ?: default(EntityNumber.PICKUP_DELAY)
            if (delay > 0) {
                body.numbers[EntityNumber.PICKUP_DELAY] = delay - 1
                continue
            }
            if (!platform.worlds.entitiesLoaded(body.location)) continue
            val player = platform.players.byId.values.firstOrNull { !it.dead && reaches(it, body) } ?: continue
            if (platform.events?.playerPickupItem(player.ref, stack, body.id) == true) continue
            val count = stack.def.count ?: 1
            val left = platform.inventories.add(InventoryRef.Player(player.ref.uuid), stack) ?: continue
            when {
                left <= 0 -> mobs.remove(body.id)
                left < count -> body.item = stack.copy(def = stack.def.copy(count = left))
            }
        }
    }

    private fun reaches(player: FakePlayer, item: FakeBody): Boolean {
        val p = player.location
        val i = item.location
        val sideways = player.width / 2 + 1 + item.width / 2
        return p.world == i.world &&
            abs(p.x - i.x) <= sideways &&
            abs(p.z - i.z) <= sideways &&
            i.y + item.height >= p.y - 0.5 &&
            i.y <= p.y + player.height + 0.5
    }

    /** A player right-clicks [entity]; true when it was cancelled. */
    fun interact(player: FakePlayer, entity: UUID, hand: String = "main_hand") =
        platform.events!!.playerInteractEntity(player.ref, entity, hand)
}

/** Mannequins on the fake server: each one's [FakeBody.mannequin]. */
class FakeMannequins(private val platform: FakePlatform) : MannequinOps {
    override fun look(id: UUID): MannequinLook? = platform.worldEntities.body(id)?.mannequin

    override fun setLook(id: UUID, look: MannequinLook): Boolean {
        val body = platform.worldEntities.body(id)?.takeIf { it.mannequin != null } ?: return false
        body.mannequin = look
        return true
    }

    companion object {
        /** What a new mannequin shows under its name, as Paper hands it over: the game's "NPC". */
        const val DEFAULT_DESCRIPTION = "<lang:entity.minecraft.mannequin.label>"
    }
}
