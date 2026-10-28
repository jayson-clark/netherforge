package dev.netherforge.plugin.paper

import dev.netherforge.plugin.platform.ItemViewOps
import dev.netherforge.plugin.platform.PlatformEvents
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.inventory.ItemStack
import java.util.UUID

/**
 * [ItemViewOps] on Paper: the version's [ItemViewHooks] send every stack a player's game is sent through
 * [view], which asks the runtime ([PlatformEvents.itemViewing], then [PlatformEvents.itemViewed]) and puts the
 * name and lore it answers on a copy. Each player is hooked as they join (and those online when the runtime
 * binds), so their screen is in step with the server's handlers from their first stack.
 *
 * A game in creative mode sends whole stacks back (each slot it changes in its inventory, a stack it drops),
 * as it was shown them: [creative] puts the stack the server holds in their place, so a look shown is never
 * kept. (A game in survival only names slots and hashes; anvil renaming takes typed text, as it always could.)
 */
class PaperItemViews(version: PaperVersion) :
    ItemViewOps,
    Listener {
    var events: PlatformEvents? = null

    private val hooks = version.itemViews(::view)

    /** Hooks everyone online: the runtime has just bound. */
    fun start() {
        for (player in Bukkit.getOnlinePlayers()) hooks.install(player)
    }

    /** Unhooks everyone online, sending them the real stacks again: the plugin is being disabled. */
    fun stop() {
        for (player in Bukkit.getOnlinePlayers()) hooks.uninstall(player)
    }

    override fun refresh(player: UUID): Boolean {
        val online = Bukkit.getPlayer(player) ?: return false
        hooks.refresh(online)
        return true
    }

    private fun view(player: Player, stack: ItemStack): ItemStack? {
        val events = events ?: return null
        if (!events.itemViewing(PaperItems.projectItem(stack))) return null
        val item = PaperItems.toItem(stack) ?: return null
        val view = events.itemViewed(player.uniqueId, item) ?: return null
        return PaperItems.viewed(stack, view)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun join(event: PlayerJoinEvent) {
        hooks.install(event.player)
    }

    @EventHandler(priority = EventPriority.LOWEST)
    fun creative(event: InventoryCreativeEvent) {
        val player = event.whoClicked as? Player ?: return
        event.cursor = hooks.realOf(player, event.cursor) ?: return
    }
}
