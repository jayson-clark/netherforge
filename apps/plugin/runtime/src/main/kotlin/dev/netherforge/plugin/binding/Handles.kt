package dev.netherforge.plugin.binding
import dev.netherforge.plugin.platform.EntityCategory
import dev.netherforge.plugin.platform.EntityInfo
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.session.ProjectSession
import java.util.UUID

/**
 * What a handle is a part of, when it only exists while that does: a node of
 * its centity, a slot of its menu window, a button of its dialog. What's
 * listening to the parts goes when the whole does (`Scripts.dropTarget`).
 */
internal fun LuaHandle.partOf(): LuaHandle? = when (this) {
    is LuaHandle.Node -> LuaHandle.Centity(centity)
    is LuaHandle.Slot -> LuaHandle.Menu(menu)
    is LuaHandle.Button -> LuaHandle.Dialog(dialog)
    else -> null
}

/**
 * The handle for the entity [id], as the class it is: a `Bot` for a bot online, a `Player` for
 * anyone who has played here (online or not), a `Mob`, a `Mannequin`, a `Living`, a
 * `DroppedItem`, or a plain `Entity` for anything else and for an entity that isn't in the world
 * now (its handle becomes its class when it's back).
 */
internal fun ProjectSession.entityHandle(id: UUID): LuaHandle.Entity {
    platform.worldEntities.info(id)?.let { return entityHandle(it) }
    if (platform.players.get(id) != null || platform.players.known(id.toString()) != null) return LuaHandle.Player(id.toString())
    return LuaHandle.Entity(id.toString())
}

/** The handle for what [info] describes, as the class it is. */
internal fun ProjectSession.entityHandle(info: EntityInfo): LuaHandle.Entity {
    val id = info.id.toString()
    return when {
        info.player != null -> if (platform.bots.isBot(info.id)) LuaHandle.Bot(id) else LuaHandle.Player(id)
        info.category == EntityCategory.MOB -> LuaHandle.Mob(id)
        info.category == EntityCategory.MANNEQUIN -> LuaHandle.Mannequin(id)
        info.category == EntityCategory.LIVING -> LuaHandle.Living(id)
        info.category == EntityCategory.DROPPED_ITEM -> LuaHandle.DroppedItem(id)
        else -> LuaHandle.Entity(id)
    }
}

/**
 * The handle for [id], which an event says is living (it died, it healed): the class it is,
 * or a `Living` when there's no more to know.
 */
internal fun ProjectSession.livingHandle(id: UUID): LuaHandle.Living =
    entityHandle(id) as? LuaHandle.Living ?: LuaHandle.Living(id.toString())

/** The handle for [id], which an event says is a mob (it picked a target): a `Mob`. */
internal fun ProjectSession.mobHandle(id: UUID): LuaHandle.Mob = entityHandle(id) as? LuaHandle.Mob ?: LuaHandle.Mob(id.toString())

/** Where a platform location's block is, for events that hand out a `Block`. */
internal fun blockHandle(world: String, x: Int, y: Int, z: Int) = LuaHandle.Block(world, BlockPosition.pack(x, y, z))

/**
 * An `Inventory` handle's one key value, where the inventory is:
 * `player/<uuid>`, `ender_chest/<uuid>`, `storage/<uuid>/<size>/<ns:name>`,
 * `entity/<uuid>` or `block/<x>/<y>/<z>/<world>` (the world last, so its name
 * may hold anything).
 */
internal object InventoryKeys {
    fun of(ref: InventoryRef): LuaHandle.Inventory = LuaHandle.Inventory(
        when (ref) {
            is InventoryRef.Player -> "player/${ref.player}"
            is InventoryRef.EnderChest -> "ender_chest/${ref.player}"
            is InventoryRef.Storage -> "storage/${ref.player}/${ref.size}/${ref.name}"
            is InventoryRef.Entity -> "entity/${ref.entity}"
            is InventoryRef.Block -> "block/${ref.x}/${ref.y}/${ref.z}/${ref.world}"
        }
    )

    fun ref(handle: LuaHandle.Inventory): InventoryRef? {
        val parts = handle.holder.split('/', limit = 5)
        fun uuid() = parts.getOrNull(1)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
        return when (parts[0]) {
            "player" -> uuid()?.let { InventoryRef.Player(it) }
            "ender_chest" -> uuid()?.let { InventoryRef.EnderChest(it) }
            "storage" -> {
                val size = parts.getOrNull(2)?.toIntOrNull()
                val name = handle.holder.split('/', limit = 4).getOrNull(3)
                if (size == null || name == null) null else uuid()?.let { InventoryRef.Storage(it, name, size) }
            }
            "entity" -> uuid()?.let { InventoryRef.Entity(it) }
            "block" -> if (parts.size == 5) {
                val (x, y, z) = parts.subList(1, 4).map { it.toIntOrNull() ?: return null }
                InventoryRef.Block(parts[4], x, y, z)
            } else {
                null
            }
            else -> null
        }
    }
}

/** The id a [LuaHandle.Sender] has when it's the console (or anything else that isn't a player). */
internal const val CONSOLE_SENDER = "console"
