package dev.netherforge.plugin.platform

import dev.netherforge.format.game.Box
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.math.Vec3
import java.util.UUID

/*
 * Entities as scripts handle them (vanilla mobs, dropped items, players), real
 * inventories, boss bars and sidebars. The entities centities are drawn with
 * are [EntityOps]'; everything here leaves them out: an entity carrying an
 * [EntityTag] is never listed, hit by a ray, or found by UUID.
 */

/**
 * What kind of entity it is, for what can be done with it: the API's classes
 * below `Entity` (a player is [LIVING], with [EntityInfo.player] set).
 */
enum class EntityCategory {
    /** Anything else: an arrow, a minecart, a lightning bolt. */
    OTHER,

    /** An item lying on the ground. */
    DROPPED_ITEM,

    /** Something with health that isn't a mob: an armour stand, a player. */
    LIVING,

    /** Something with health and AI: a zombie, a cow, a bat. */
    MOB,

    /** A figure that looks like a player (`minecraft:mannequin`): living, with no AI. */
    MANNEQUIN
}

/** What an entity is now, for finding and filtering it. [player] is set for a player. */
data class EntityInfo(
    val id: UUID,
    /** Namespaced: `minecraft:zombie`. */
    val kind: String,
    /** Where its feet are, with its facing. */
    val location: Location,
    val category: EntityCategory,
    val player: PlayerRef?,
    /** Its scoreboard tags. */
    val tags: Set<String>
) {
    /** A mob, an animal or a player: something with health. */
    val living: Boolean get() = category == EntityCategory.LIVING || category == EntityCategory.MOB || category == EntityCategory.MANNEQUIN
}

/** An entity's yes-or-no properties. Some only some entities have; asking another answers null. */
enum class EntityFlag(val settable: Boolean) {
    ON_GROUND(false),
    CUSTOM_NAME_VISIBLE(true),
    GLOWING(true),

    /** Shown to players by default (`setVisibleByDefault`). */
    VISIBLE(true),
    SILENT(true),
    GRAVITY(true),
    INVULNERABLE(true),

    /** Mobs only. */
    AI(true),

    // Players only.
    SNEAKING(false),
    SPRINTING(false),
    FLYING(true),
    CAN_FLY(true)
}

/** An entity's numbers. Some only some entities have; asking another answers null. */
enum class EntityNumber(val settable: Boolean) {
    // Living entities.
    HEALTH(true),
    MAX_HEALTH(false),

    // Players.
    FOOD(true),
    SATURATION(true),
    LEVEL(true),
    EXPERIENCE_PROGRESS(true),
    WALK_SPEED(true),
    FLY_SPEED(true),

    /** The selected hotbar slot, 0 to 8. */
    HELD_SLOT(true),

    /** Milliseconds, as the server estimates it. */
    PING(false),

    /** Dropped items: ticks until it can be picked up. */
    PICKUP_DELAY(true)
}

/** One status effect: [ticks] -1 for ever; [amplifier] 0 is level I. */
data class StatusEffectData(
    val effect: String,
    val ticks: Int,
    val amplifier: Int = 0,
    val ambient: Boolean = false,
    val particles: Boolean = true,
    val icon: Boolean = true
)

/** How a new entity is set up before anything (an `entity_spawn` handler included) sees it. */
data class SpawnSetup(
    val customName: String? = null,
    val tags: List<String> = emptyList(),
    /** Its script data, as `setData` takes it. */
    val data: String? = null,
    val velocity: Vec3? = null
)

/** What a ray hit: the entity, where it entered its box, and that face's normal. */
data class EntityHit(val id: UUID, val position: Vec3, val normal: Vec3)

/** An entity's bounding box in the world: [kind] namespaced (`minecraft:zombie`), [player] whether it's a player. */
data class EntityBox(val id: UUID, val kind: String, val player: Boolean, val box: Box)

/**
 * Entities by UUID. Everything answers null or false for an entity that isn't
 * there now: gone, in an unloaded chunk, or one of NetherForge's own.
 */
interface WorldEntityOps {
    fun info(id: UUID): EntityInfo?

    /** Every entity in a world's loaded chunks, players included; empty for a world that doesn't exist. */
    fun list(world: String): List<EntityInfo>

    /** Where it looks from and which way, with a normalised direction. */
    fun eye(id: UUID): Ray?

    fun velocity(id: UUID): Vec3?

    fun setVelocity(id: UUID, velocity: Vec3): Boolean

    /** Moves it, passengers and all. */
    fun teleport(id: UUID, to: Location): Boolean

    fun setRotation(id: UUID, yaw: Double, pitch: Double): Boolean

    /**
     * Which way a living entity's body faces, in Minecraft degrees: not its look
     * (a player's body follows their walking and turns only after their head has
     * gone far enough). Null for anything not living, or gone.
     */
    fun bodyYaw(id: UUID): Double?

    /** Takes it out of the world, without dying. Never a player. */
    fun remove(id: UUID): Boolean

    fun flag(id: UUID, flag: EntityFlag): Boolean?

    fun setFlag(id: UUID, flag: EntityFlag, value: Boolean): Boolean

    fun number(id: UUID, number: EntityNumber): Double?

    /** The runtime has checked [value]'s range. */
    fun setNumber(id: UUID, number: EntityNumber, value: Double): Boolean

    /** What players see it called, as MiniMessage: its custom name or its kind's name. */
    fun name(id: UUID): String?

    fun customName(id: UUID): String?

    fun setCustomName(id: UUID, miniMessage: String?): Boolean

    /** False when it already had it. */
    fun addTag(id: UUID, tag: String): Boolean

    /** False when it didn't have it. */
    fun removeTag(id: UUID, tag: String): Boolean

    /** The JSON a script keeps on it (`entity:data()`), in its persistent data; null when there's none. */
    fun data(id: UUID): String?

    /** Stores (or with null, clears) its JSON. */
    fun setData(id: UUID, json: String?): Boolean

    /** Hides it from one online player, as `Player#hideEntity` does. */
    fun hide(viewer: UUID, id: UUID): Boolean

    fun show(viewer: UUID, id: UUID): Boolean

    fun passengers(id: UUID): List<UUID>?

    fun addPassenger(id: UUID, passenger: UUID): Boolean

    fun removePassenger(id: UUID, passenger: UUID): Boolean

    fun vehicle(id: UUID): UUID?

    /** Hurts a living entity as an attack would, by [source] when given. */
    fun damage(id: UUID, amount: Double, source: UUID?): Boolean

    /** Null for an entity that isn't living. */
    fun effects(id: UUID): List<StatusEffectData>?

    fun addEffect(id: UUID, effect: StatusEffectData): Boolean

    /** False when it didn't have it. */
    fun removeEffect(id: UUID, effect: String): Boolean

    /** Whether the server has a status effect with this namespaced id. */
    fun effectExists(effect: String): Boolean

    /** [slot] is the Lua name: `main_hand`, `off_hand`, `head`, `chest`, `legs`, `feet`, `body`. */
    fun equipment(id: UUID, slot: String): ItemData?

    fun setEquipment(id: UUID, slot: String, item: ItemData?): Boolean

    /** What a mob is after. */
    fun target(id: UUID): UUID?

    /** False for an entity that isn't a mob. */
    fun setTarget(id: UUID, target: UUID?): Boolean

    /** A dropped item's stack. */
    fun item(id: UUID): ItemData?

    fun setItem(id: UUID, item: ItemData): Boolean

    /** Whether [kind] (namespaced) is an entity type scripts can spawn: not a player, not something only the game makes. */
    fun spawnable(kind: String): Boolean

    /** Spawns one, set up as [setup] says before it's added; null when it couldn't be (or something cancelled it). */
    fun spawn(kind: String, at: Location, setup: SpawnSetup): UUID?

    /** Drops an item stack; null when it couldn't be. */
    fun spawnItem(world: String, position: Vec3, item: ItemData): UUID?

    /** The first entity's box a ray hits within [maxDistance], leaving out [ignore]. [direction] is a unit vector. */
    fun raycast(world: String, origin: Vec3, direction: Vec3, maxDistance: Double, ignore: Set<UUID>): EntityHit?

    /**
     * Every entity whose bounding box reaches into [region] (world coordinates) in [world], players
     * included, as the server measures it now: only in loaded chunks, never one of ours (tagged) or a
     * player in spectator mode, which nothing can touch. What the spatial queries (`world:overlap_*`,
     * `sweep`, `nearest`) and centities' `touch` test.
     */
    fun boxes(world: String, region: Box): List<EntityBox>
}

/** Where a real inventory is. */
sealed interface InventoryRef {
    data class Player(val player: UUID) : InventoryRef

    data class EnderChest(val player: UUID) : InventoryRef

    data class Entity(val entity: UUID) : InventoryRef

    data class Block(val world: String, val x: Int, val y: Int, val z: Int) : InventoryRef

    /**
     * A storage of the project's that [player] keeps (`player:storage`), [name] namespaced (`showcase:trinkets`),
     * kept in their own save data, so it's saved with their inventory in the one write. [size] slots of it are
     * this inventory; a slot past it keeps its item.
     */
    data class Storage(val player: UUID, val name: String, val size: Int) : InventoryRef {
        /** Another size of the same storage is the same items. */
        fun same(other: Storage) = player == other.player && name == other.name

        companion object {
            /** The most slots a storage has. */
            const val MAX_SIZE = 256
        }
    }
}

/**
 * What a stack must be to be worth reading in full for an [ItemMatch][dev.netherforge.plugin.item.ItemMatch]:
 * its kind (`minecraft:paper`, as [GameIds] normalizes it) and the project
 * item it's a stack of (as the project's files name it: `ruby`, `acme:ruby`), each only when given. A stack that
 * fails either can't match, whatever else the match says.
 */
data class StackFilter(val kind: String?, val item: String?) {
    fun admits(stack: ItemData): Boolean = (kind == null || GameIds.normalize(stack.def.kind) == kind) &&
        (item == null || stack.def.item?.text == item)

    companion object {
        val ANY = StackFilter(null, null)
    }
}

/**
 * Real inventories. Everything answers null or false for one that isn't
 * there: a player offline, a block that isn't a container (or in an unloaded
 * chunk), an entity without one. A [InventoryRef.Storage] is never on anyone's
 * screen by itself ([viewers] none, [open] false): menus show it, slot by slot.
 */
interface InventoryOps {
    /** `player`, `ender_chest`, `chest`, `barrel`, …: the inventory's type, lowercase. */
    fun kind(ref: InventoryRef): String?

    fun size(ref: InventoryRef): Int?

    /** Every slot, in order, null for an empty one. */
    fun contents(ref: InventoryRef): List<ItemData?>?

    /**
     * One slot (the runtime has checked it's in range), null for an empty
     * one: only that stack is read, where [contents] reads them all.
     */
    fun item(ref: InventoryRef, slot: Int): ItemData? = contents(ref)?.getOrNull(slot)

    /**
     * Every slot, as [contents], but a stack [filter] turns away reads as
     * empty and is never read in full: what counting and finding a match
     * look through, so the stacks that can't match cost next to nothing.
     */
    fun contents(ref: InventoryRef, filter: StackFilter): List<ItemData?>? = contents(ref)?.map { item -> item?.takeIf(filter::admits) }

    /** Writes a slot (the runtime has checked it's in range); null empties it. False when the item can't be built. */
    fun setItem(ref: InventoryRef, slot: Int, item: ItemData?): Boolean

    /** Puts [item] wherever it fits, topping up matching stacks first: how many didn't fit, or null when it isn't there. */
    fun add(ref: InventoryRef, item: ItemData): Int?

    fun clear(ref: InventoryRef): Boolean

    /** Who has it open on their screen. */
    fun viewers(ref: InventoryRef): List<UUID>

    /** Shows it to an online player. */
    fun open(ref: InventoryRef, player: UUID): Boolean
}

/** What a boss bar shows. [color] and [style] are the Lua names (`pink`, `notched_6`). */
data class BossBarLook(val text: String, val progress: Double, val color: String, val style: String)

/** Boss bars, by an id the runtime picks. */
interface BossBarOps {
    fun create(id: Int, look: BossBarLook)

    fun update(id: Int, look: BossBarLook)

    /** False when the player is offline. */
    fun show(id: Int, player: UUID): Boolean

    fun hide(id: Int, player: UUID)

    /** Off every screen, forgotten. */
    fun remove(id: Int)
}

/**
 * Per-player sidebars. Showing one gives the player a scoreboard of their
 * own that mirrors the main scoreboard's teams (the adapter keeps them in
 * step); hiding it gives them the main scoreboard back.
 */
interface SidebarOps {
    /** Shows (or updates) a player's sidebar: MiniMessage title and at most 15 lines, top first, without score numbers. */
    fun show(player: UUID, title: String, lines: List<String>): Boolean

    fun hide(player: UUID): Boolean
}
