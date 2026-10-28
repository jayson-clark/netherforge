package dev.netherforge.plugin.paper

import com.destroystokyo.paper.event.block.BlockDestroyEvent
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.PlatformEvents
import io.papermc.paper.event.block.BlockBreakBlockEvent
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.entity.FallingBlock
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockPhysicsEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.entity.EntityChangeBlockEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.EnumSet

/**
 * What the runtime hears of blocks besides players breaking them ([dev.netherforge.plugin.platform.BlockOps.watch]):
 *
 * - the world breaking one of the game's blocks the project gives drops to, whose drops the runtime then says
 *   ([PlatformEvents.worldBroke]): Paper's `BlockDestroyEvent` (what held it up went, a mob, a command; the game's
 *   `destroyBlock`), `BlockBreakBlockEvent` (a piston or a fluid breaking it) and leaves decaying, which no event lets
 *   change the drops of, so the decay is done here instead;
 * - a neighbour of a project block that falls changing ([PlatformEvents.blockUpdated]), and a falling block of one
 *   landing ([PlatformEvents.fallingLanded]).
 *
 * Nothing is listened to while the runtime wants neither: physics events are the server's busiest.
 */
class PaperGameBlocks(private val plugin: JavaPlugin) : Listener {
    /** The running session's events, once the platform is bound. */
    var events: PlatformEvents? = null

    /** The block types whose world breaks the runtime says the drops of. */
    private var drops: Set<Material> = emptySet()

    /** The states that fall, and their block types for the cheap check first. */
    private var fallingStates: Set<String> = emptySet()
    private var fallingTypes: Set<Material> = emptySet()

    private var listening = false

    fun watch(drops: Set<String>, falling: Set<String>) {
        this.drops = materials(drops)
        fallingStates = falling
        fallingTypes = materials(falling.map { it.substringBefore('[') })
        val wanted = this.drops.isNotEmpty() || fallingTypes.isNotEmpty()
        if (wanted && !listening) plugin.server.pluginManager.registerEvents(this, plugin)
        if (!wanted && listening) HandlerList.unregisterAll(this)
        listening = wanted
    }

    private fun materials(ids: Collection<String>): Set<Material> {
        val out = EnumSet.noneOf(Material::class.java)
        for (id in ids) Material.matchMaterial(id)?.takeIf { it.isBlock }?.let(out::add)
        return out
    }

    /** The drops the runtime says for [block] broken by the world, or null for the game's. */
    private fun dropsOf(block: Block): List<ItemData>? {
        if (block.type !in drops) return null
        return events?.worldBroke(blockRef(block))
    }

    private fun drop(block: Block, items: List<ItemData>) {
        val at = block.location.add(0.5, 0.5, 0.5)
        for (stack in items.mapNotNull(PaperItems::toStack)) block.world.dropItemNaturally(at, stack)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDestroy(event: BlockDestroyEvent) {
        if (!event.willDrop()) return
        val items = dropsOf(event.block) ?: return
        event.setWillDrop(false)
        drop(event.block, items)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreakBlock(event: BlockBreakBlockEvent) {
        val items = dropsOf(event.block) ?: return
        event.drops.clear()
        event.drops.addAll(items.mapNotNull(PaperItems::toStack))
    }

    /** Leaves decaying drop what the runtime says: the game's decay is cancelled and done here. */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onDecay(event: LeavesDecayEvent) {
        val block = event.block
        val items = dropsOf(block) ?: return
        event.isCancelled = true
        block.setType(Material.AIR, true)
        drop(block, items)
    }

    /**
     * Heard even when cancelled: the leaves carrier's holder ([PaperLeaves]) cancels a neighbour's update of
     * persistent leaves, which is what a leaves block's support going is. Only looked at, never changed.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPhysics(event: BlockPhysicsEvent) {
        val block = event.block
        if (block.type !in fallingTypes || block == event.sourceBlock) return
        val ref = blockRef(block)
        if (ref.state in fallingStates) events?.blockUpdated(ref)
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onLand(event: EntityChangeBlockEvent) {
        val falling = event.entity as? FallingBlock ?: return
        if (event.to !in fallingTypes) return
        val state = stateOf(event.blockData)
        if (state !in fallingStates) return
        val block = event.block
        events?.fallingLanded(
            falling.uniqueId,
            BlockRef(block.world.name, block.x, block.y, block.z, event.to.key.toString(), state)
        )
    }

    fun release() {
        HandlerList.unregisterAll(this)
        listening = false
    }
}
