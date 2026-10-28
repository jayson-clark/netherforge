package dev.netherforge.plugin.paper

import dev.netherforge.plugin.platform.GameLootContext
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.LootOps
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.NamespacedKey
import org.bukkit.loot.LootContext
import java.util.Random

/**
 * [LootOps] with Bukkit's `LootTable`: the server's own tables (vanilla's
 * and datapacks'), rolled with a `java.util.Random` seeded per roll, which
 * the server draws everything from.
 */
class PaperLoot : LootOps {
    private fun table(id: String) = NamespacedKey.fromString(id)?.let(Bukkit::getLootTable)

    override fun exists(table: String): Boolean = table(table) != null

    override fun roll(table: String, context: GameLootContext, seed: Long): List<ItemData>? {
        val found = table(table) ?: return null
        val at = context.location
        val world = requireNotNull(Bukkit.getWorld(at.world)) { "there's no world \"${at.world}\"" }
        val builder = LootContext.Builder(Location(world, at.x, at.y, at.z)).luck(context.luck.toFloat())
        context.player?.let { Bukkit.getPlayer(it) }?.let { builder.killer(it) }
        context.looted?.let(Bukkit::getEntity)?.let(builder::lootedEntity)
        // Paper says which parameter a table lacks with an IllegalArgumentException, as LootOps promises.
        return found.populateLoot(Random(seed), builder.build()).mapNotNull(PaperItems::toItem)
    }
}
