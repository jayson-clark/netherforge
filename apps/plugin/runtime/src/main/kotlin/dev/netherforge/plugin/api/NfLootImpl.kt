package dev.netherforge.plugin.api

import dev.netherforge.format.loot.LootTableKind
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.InventoryKeys
import dev.netherforge.plugin.binding.LootContext
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.LuaPlace
import dev.netherforge.plugin.binding.NfLootApi
import dev.netherforge.plugin.binding.place
import dev.netherforge.plugin.loot.LootRoll
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.session.ProjectSession
import kotlin.random.Random

/**
 * `nf.loot`: rolling the project's loot tables and the game's. A table is
 * named as any resource a script names ([dev.netherforge.plugin.session.PackageNames.resource]):
 * a bare id is the calling package's, another package's only where it's
 * exported. A namespace that's no package's is the game's (`minecraft:…`, a
 * datapack's). What a roll gives is handed back spelled for the caller, as
 * every stack is.
 */
internal class NfLootImpl(private val session: ProjectSession) : NfLootApi {
    private val loot get() = session.loot

    override fun roll(caller: Caller, table: String, context: LootContext?): List<ItemData> =
        loot.roll(named(table), roll(context, null), seedOf(context))

    override fun fill(caller: Caller, table: String, inventory: LuaHandle.Inventory, context: LootContext?): Long? {
        val inventories = session.platform.inventories
        val ref = InventoryKeys.ref(inventory) ?: return null
        val contents = inventories.contents(ref) ?: return null
        val name = named(table)
        val seed = seedOf(context)
        val items = loot.roll(name, roll(context, placeOf(ref)), seed)
        // A player's armour, offhand and the rest never take loot: their hotbar and main inventory do.
        val usable = if (ref is InventoryRef.Player) contents.indices.take(PLAYER_MAIN) else contents.indices
        val empty = usable.filter { contents[it] == null }.shuffled(Random(seed.inv())).toMutableList()
        var left = 0L
        for (item in items) {
            val slot = empty.removeFirstOrNull()
            if (slot == null || !inventories.setItem(ref, slot, item)) left += item.def.count ?: 1
        }
        return left
    }

    /** [table] as the server names it: a project or package table that's there, or a game table's id. */
    private fun named(table: String): String {
        val namespace = ResourceRef(table).namespace
        if (namespace != null && namespace != session.namespace && namespace !in session.snapshot.packages) return table
        val name = session.names.resource(LootTableKind, table)
        return loot.idOf(name) ?: throw LuaApiException("there's no loot table \"$table\" (${LootTableKind.pathOf(name)})")
    }

    private fun seedOf(context: LootContext?): Long = context?.seed ?: Random.nextLong()

    /** What the script said, with [place] where a game table is rolled when it says nowhere. */
    private fun roll(context: LootContext?, place: Location?) = LootRoll(
        player = context?.player?.uuidOrNull(),
        tool = context?.tool,
        luck = context?.luck ?: 0.0,
        looted = context?.looted?.uuidOrNull(),
        location = context?.location?.let { LuaPlace.of(it).resolve(it.world.name) } ?: place
    )

    /** Where an inventory is: its block's middle, or its holder's place. */
    private fun placeOf(ref: InventoryRef): Location? = when (ref) {
        is InventoryRef.Block -> Location(ref.world, ref.x + 0.5, ref.y + 0.5, ref.z + 0.5)
        is InventoryRef.Player -> session.platform.players.location(ref.player)
        is InventoryRef.EnderChest -> session.platform.players.location(ref.player)
        is InventoryRef.Storage -> session.platform.players.location(ref.player)
        is InventoryRef.Entity -> session.platform.worldEntities.info(ref.entity)?.location
    }

    private companion object {
        /** A player's hotbar and main inventory: slots 0 to 35. */
        const val PLAYER_MAIN = 36
    }
}
