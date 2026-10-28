package dev.netherforge.plugin.paper

import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.StackFilter
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataContainer
import org.bukkit.persistence.PersistentDataType
import java.lang.ref.WeakReference
import java.util.UUID

/**
 * Players' storages ([InventoryRef.Storage], `player:storage`), in their persistent data: `netherforge:storage` holds
 * a container per storage, by its namespaced name, of each filled slot's stack as the server serializes it
 * (`netherforge:<slot>`), which it upgrades on a new version as it does an inventory's. The server saves a player's
 * persistent data in their file, with their inventory, in the one write, so an item moved between the two is in
 * exactly one of them in whatever was last saved. It's on the player, so it lasts through dying, and only an online
 * player's can be read or changed.
 */
internal object PaperStorage {
    private val KEY = NamespacedKey("netherforge", "storage")

    private fun player(ref: InventoryRef.Storage): Player? = Bukkit.getPlayer(ref.player)

    private fun name(ref: InventoryRef.Storage): NamespacedKey =
        NamespacedKey.fromString(ref.name) ?: error("not a storage name: ${ref.name}")

    private fun slotKey(slot: Int) = NamespacedKey("netherforge", slot.toString())

    /**
     * A storage as last read or written, with each slot's item as a script reads it, once something asked: the
     * stacks are deserialized and read once, not on every call. Only NetherForge writes a storage, so it's current
     * until it writes it ([write]); a player who left and came back is a new [player] (read again), and a project
     * item's look changing refreshes the player's inventory, which forgets their storages ([forget]).
     */
    private class Loaded(val player: WeakReference<Player>, val slots: MutableMap<Int, ItemStack>) {
        val items = HashMap<Int, ItemData>()
    }

    private val loaded = HashMap<Pair<UUID, String>, Loaded>()

    /** Forgets what's loaded of [player]'s storages: they're read again, restyled, when next asked for. */
    fun forget(player: UUID) {
        loaded.keys.removeAll { it.first == player }
    }

    private fun load(ref: InventoryRef.Storage): Loaded? {
        val key = ref.player to ref.name
        val player = player(ref) ?: return null.also { loaded.remove(key) }
        loaded[key]?.takeIf { it.player.get() === player }?.let { return it }
        return Loaded(WeakReference(player), read(player, ref)).also { loaded[key] = it }
    }

    /** Every filled slot of the storage, past its size too, stale project items restyled. Null when they're offline. */
    private fun read(ref: InventoryRef.Storage): MutableMap<Int, ItemStack>? = load(ref)?.slots

    private fun read(player: Player, ref: InventoryRef.Storage): MutableMap<Int, ItemStack> {
        val storage = player.persistentDataContainer.get(KEY, PersistentDataType.TAG_CONTAINER)
            ?.get(name(ref), PersistentDataType.TAG_CONTAINER)
            ?: return HashMap()
        val slots = HashMap<Int, ItemStack>()
        for (key in storage.keys) {
            val slot = key.key.toIntOrNull()?.takeIf { key.namespace == "netherforge" && it >= 0 } ?: continue
            val bytes = storage.get(key, PersistentDataType.BYTE_ARRAY) ?: continue
            val stack = runCatching { ItemStack.deserializeBytes(bytes) }.getOrNull()?.takeIf { !it.isEmpty } ?: continue
            slots[slot] = PaperItems.restyled(stack) ?: stack
        }
        return slots
    }

    /** Slot [slot]'s item as a script reads it, read once while the storage is unchanged. */
    private fun itemOf(storage: Loaded, slot: Int): ItemData? {
        storage.items[slot]?.let { return it }
        val stack = storage.slots[slot] ?: return null
        return PaperItems.toItem(stack)?.also { storage.items[slot] = it }
    }

    /** Writes the storage's slots ([slots] is every filled one). False when they're offline. */
    private fun write(ref: InventoryRef.Storage, slots: MutableMap<Int, ItemStack>): Boolean {
        val player = player(ref) ?: return false
        // What's loaded is these slots now; their items are read again when asked for.
        loaded[ref.player to ref.name] = Loaded(WeakReference(player), slots)
        val data = player.persistentDataContainer
        val all = data.get(KEY, PersistentDataType.TAG_CONTAINER) ?: data.adapterContext.newPersistentDataContainer()
        if (slots.isEmpty()) {
            all.remove(name(ref))
        } else {
            val storage: PersistentDataContainer = all.adapterContext.newPersistentDataContainer()
            for ((slot, stack) in slots) {
                if (!stack.isEmpty) storage.set(slotKey(slot), PersistentDataType.BYTE_ARRAY, stack.serializeAsBytes())
            }
            all.set(name(ref), PersistentDataType.TAG_CONTAINER, storage)
        }
        if (all.isEmpty) data.remove(KEY) else data.set(KEY, PersistentDataType.TAG_CONTAINER, all)
        return true
    }

    fun kind(ref: InventoryRef.Storage): String? = player(ref)?.let { "storage" }

    fun size(ref: InventoryRef.Storage): Int? = player(ref)?.let { ref.size }

    fun contents(ref: InventoryRef.Storage): List<ItemData?>? {
        val storage = load(ref) ?: return null
        return List(ref.size) { itemOf(storage, it) }
    }

    fun contents(ref: InventoryRef.Storage, filter: StackFilter): List<ItemData?>? {
        val storage = load(ref) ?: return null
        return List(ref.size) { slot ->
            val stack = storage.slots[slot]
            if (stack == null || !PaperItems.admits(stack, filter)) null else itemOf(storage, slot)
        }
    }

    fun item(ref: InventoryRef.Storage, slot: Int): ItemData? = load(ref)?.let { itemOf(it, slot) }

    fun setItem(ref: InventoryRef.Storage, slot: Int, item: ItemData?): Boolean {
        if (slot !in 0 until ref.size) return false
        val stack = item?.let { PaperItems.toStack(it) ?: return false }
        val slots = read(ref) ?: return false
        if (stack == null) slots.remove(slot) else slots[slot] = stack
        return write(ref, slots)
    }

    /** Tops up matching stacks first, then fills empty slots, within its size. How many didn't fit. */
    fun add(ref: InventoryRef.Storage, item: ItemData): Int? {
        val slots = read(ref) ?: return null
        val stack = PaperItems.toStack(item) ?: return item.def.count ?: 1
        var left = stack.amount
        for (slot in 0 until ref.size) {
            val held = slots[slot] ?: continue
            if (!held.isSimilar(stack)) continue
            val moved = minOf(held.maxStackSize - held.amount, left)
            if (moved <= 0) continue
            held.amount += moved
            left -= moved
        }
        for (slot in 0 until ref.size) {
            if (left <= 0) break
            if (slots[slot] != null) continue
            val moved = minOf(stack.maxStackSize, left)
            slots[slot] = stack.clone().also { it.amount = moved }
            left -= moved
        }
        write(ref, slots)
        return left
    }

    /** Empties the slots within its size; one past it keeps its item. */
    fun clear(ref: InventoryRef.Storage): Boolean {
        val slots = read(ref) ?: return false
        slots.keys.removeAll { it < ref.size }
        return write(ref, slots)
    }
}
