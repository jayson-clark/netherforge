package dev.netherforge.plugin.paper

import dev.netherforge.format.terrain.GeneratedLoot
import dev.netherforge.plugin.platform.PlatformEvents
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.World
import org.bukkit.block.BlockState
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.LootGenerateEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.InventoryHolder
import org.bukkit.loot.LootTable
import org.bukkit.loot.Lootable
import org.bukkit.persistence.PersistentDataType
import java.util.UUID
import java.util.logging.Logger

/**
 * Containers a project terrain generates with loot ([GeneratedLoot]). When a generated chunk first loads whole, each
 * container its terrain marked ([PaperWorldGenerators.takeLoot]) is given the project's table (in its persistent
 * data) and the game's empty [GeneratedLoot.KEY] table, so the game unpacks it as it does its own chests: on the first
 * open, break or hopper. When it rolls [GeneratedLoot.KEY], the runtime rolls the project's table instead
 * ([PlatformEvents.generatedLoot]).
 *
 * A server that started without that table in its datapack (a terrain gained loot since) can't do that until it
 * restarts: the container is filled when a player first opens it instead.
 */
class PaperGeneratedLoot(private val generators: PaperWorldGenerators, private val runtime: PlatformEvents, private val logger: Logger) :
    Listener {
    private val tableKey = NamespacedKey("netherforge", "loot")
    private val placeholderKey = requireNotNull(NamespacedKey.fromString(GeneratedLoot.KEY))
    private var warned = false

    private fun placeholder(): LootTable? = Bukkit.getLootTable(placeholderKey)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        if (!event.isNewChunk) return
        val world = event.world
        val places = generators.takeLoot(world, event.chunk.x, event.chunk.z)
        if (places.isEmpty()) return
        val placeholder = placeholder()
        if (placeholder == null && !warned) {
            warned = true
            logger.warning(
                "A terrain fills containers from loot tables, and this server started without NetherForge's table for them: " +
                    "they're filled when first opened until it restarts (hoppers and breaking find them empty)"
            )
        }
        for (place in places) {
            val state = world.getBlockAt(place.x, place.y, place.z).state
            if (state !is Container || state !is Lootable) continue
            state.persistentDataContainer.set(tableKey, PersistentDataType.STRING, place.table)
            if (placeholder != null) state.setLootTable(placeholder, seed(world, place))
            state.update(true, false)
        }
    }

    /** The game rolling the empty table of a generated container: one roll of the project's table instead. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLootGenerate(event: LootGenerateEvent) {
        if (event.lootTable.key != placeholderKey) return
        val holder = container(event.inventoryHolder) ?: return
        val table = holder.persistentDataContainer.get(tableKey, PersistentDataType.STRING) ?: return
        val items = fill(holder, table, (event.entity as? Player)?.uniqueId) ?: return
        event.setLoot(items)
    }

    /** Without the game's table (a server that started before a terrain had loot): filled as a player first opens it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onOpen(event: InventoryOpenEvent) {
        val holder = event.inventory.holder as? Container ?: return
        if (holder !is Lootable || holder.lootTable != null) return
        val table = holder.persistentDataContainer.get(tableKey, PersistentDataType.STRING) ?: return
        val items = fill(holder, table, event.player.uniqueId) ?: return
        holder.persistentDataContainer.remove(tableKey)
        holder.update(true, false)
        val inventory = event.inventory
        for (item in items) inventory.addItem(item)
    }

    /** The container an inventory is of: its state, or (a holder that only knows its block) its block's. */
    private fun container(holder: InventoryHolder?): Container? =
        holder as? Container ?: (holder as? BlockInventoryHolder)?.block?.state as? Container

    private fun fill(holder: BlockState, table: String, player: UUID?) =
        runtime.generatedLoot(table, player, PaperPlatform.location(holder.location))?.mapNotNull(PaperItems::toStack)

    /** The game's own seed for a container's roll: the world's and its place, so it's the same in every world with that seed. */
    private fun seed(world: World, place: PaperWorldGenerators.LootPlace): Long =
        world.seed xor (place.x.toLong() * 341873128712L) xor (place.y.toLong() * 132897987541L) xor place.z.toLong()
}
