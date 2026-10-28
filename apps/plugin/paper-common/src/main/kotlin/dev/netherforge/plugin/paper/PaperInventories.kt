package dev.netherforge.plugin.paper

import dev.netherforge.plugin.platform.BossBarLook
import dev.netherforge.plugin.platform.BossBarOps
import dev.netherforge.plugin.platform.InventoryOps
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.SidebarOps
import dev.netherforge.plugin.platform.StackFilter
import io.papermc.paper.scoreboard.numbers.NumberFormat
import net.kyori.adventure.bossbar.BossBar
import org.bukkit.Bukkit
import org.bukkit.block.Container
import org.bukkit.entity.Player
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.plugin.Plugin
import org.bukkit.scheduler.BukkitTask
import org.bukkit.scoreboard.Criteria
import org.bukkit.scoreboard.DisplaySlot
import org.bukkit.scoreboard.Scoreboard
import java.util.UUID

/** [InventoryOps] with Bukkit inventories, found again from where they are on every call. */
class PaperInventories(private val entities: PaperWorldEntities) : InventoryOps {
    fun inventory(ref: InventoryRef): Inventory? = when (ref) {
        is InventoryRef.Player -> Bukkit.getPlayer(ref.player)?.inventory
        is InventoryRef.EnderChest -> Bukkit.getPlayer(ref.player)?.enderChest
        is InventoryRef.Entity -> (entities.entity(ref.entity)?.takeIf { it !is Player } as? InventoryHolder)?.inventory
        is InventoryRef.Block -> {
            val world = Bukkit.getWorld(ref.world)
            if (world == null || !world.isChunkLoaded(ref.x shr 4, ref.z shr 4)) {
                null
            } else {
                // The live block, not a snapshot: its inventory is the one players see.
                (world.getBlockAt(ref.x, ref.y, ref.z).getState(false) as? Container)?.inventory
            }
        }
        // Not a Bukkit inventory: the player's persistent data.
        is InventoryRef.Storage -> null
    }

    override fun kind(ref: InventoryRef): String? =
        if (ref is InventoryRef.Storage) PaperStorage.kind(ref) else inventory(ref)?.type?.name?.lowercase()

    override fun size(ref: InventoryRef): Int? = if (ref is InventoryRef.Storage) PaperStorage.size(ref) else inventory(ref)?.size

    // Reading a stack in full (`PaperItems.toItem`) is what an inventory call costs, so each reads only what it
    // must, and a slot whose stack hasn't changed since it was last read answers from that read.
    override fun contents(ref: InventoryRef): List<ItemData?>? {
        if (ref is InventoryRef.Storage) return PaperStorage.contents(ref)
        return inventory(ref)?.contents?.mapIndexed { slot, stack -> PaperItems.toItem(stack, SlotOf(ref, slot)) }
    }

    override fun item(ref: InventoryRef, slot: Int): ItemData? {
        if (ref is InventoryRef.Storage) return PaperStorage.item(ref, slot)
        return inventory(ref)?.let { PaperItems.toItem(it.getItem(slot), SlotOf(ref, slot)) }
    }

    override fun contents(ref: InventoryRef, filter: StackFilter): List<ItemData?>? {
        if (ref is InventoryRef.Storage) return PaperStorage.contents(ref, filter)
        return inventory(ref)?.contents?.mapIndexed { slot, stack ->
            stack?.takeIf { PaperItems.admits(it, filter) }?.let { PaperItems.toItem(it, SlotOf(ref, slot)) }
        }
    }

    /** Where a stack read is remembered: a slot of an inventory. */
    private data class SlotOf(val ref: InventoryRef, val slot: Int)

    override fun setItem(ref: InventoryRef, slot: Int, item: ItemData?): Boolean {
        if (ref is InventoryRef.Storage) return PaperStorage.setItem(ref, slot, item)
        val inventory = inventory(ref) ?: return false
        if (slot !in 0 until inventory.size) return false
        if (item == null) {
            inventory.setItem(slot, null)
            return true
        }
        inventory.setItem(slot, PaperItems.toStack(item) ?: return false)
        return true
    }

    override fun add(ref: InventoryRef, item: ItemData): Int? {
        if (ref is InventoryRef.Storage) return PaperStorage.add(ref, item)
        val inventory = inventory(ref) ?: return null
        val stack = PaperItems.toStack(item) ?: return item.def.count ?: 1
        return inventory.addItem(stack).values.sumOf { it.amount }
    }

    override fun clear(ref: InventoryRef): Boolean {
        if (ref is InventoryRef.Storage) return PaperStorage.clear(ref)
        val inventory = inventory(ref) ?: return false
        inventory.clear()
        return true
    }

    override fun viewers(ref: InventoryRef): List<UUID> = inventory(ref)?.viewers?.map { it.uniqueId }.orEmpty()

    override fun open(ref: InventoryRef, player: UUID): Boolean {
        val inventory = inventory(ref) ?: return false
        val who = Bukkit.getPlayer(player) ?: return false
        return who.openInventory(inventory) != null
    }
}

/** [BossBarOps] with Adventure boss bars. */
class PaperBossBars : BossBarOps {
    private val bars = HashMap<Int, BossBar>()

    private fun color(name: String) = BossBar.Color.valueOf(name.uppercase())

    private fun overlay(name: String) = BossBar.Overlay.valueOf(name.uppercase())

    override fun create(id: Int, look: BossBarLook) {
        bars[id] = BossBar.bossBar(PaperText.mini.deserialize(look.text), look.progress.toFloat(), color(look.color), overlay(look.style))
    }

    override fun update(id: Int, look: BossBarLook) {
        val bar = bars[id] ?: return
        bar.name(PaperText.mini.deserialize(look.text))
        bar.progress(look.progress.toFloat())
        bar.color(color(look.color))
        bar.overlay(overlay(look.style))
    }

    override fun show(id: Int, player: UUID): Boolean {
        val bar = bars[id] ?: return false
        Bukkit.getPlayer(player)?.showBossBar(bar) ?: return false
        return true
    }

    override fun hide(id: Int, player: UUID) {
        val bar = bars[id] ?: return
        Bukkit.getPlayer(player)?.hideBossBar(bar)
    }

    override fun remove(id: Int) {
        val bar = bars.remove(id) ?: return
        for (viewer in bar.viewers().toList()) (viewer as? Player)?.hideBossBar(bar)
    }
}

/**
 * [SidebarOps] with a scoreboard per player. Showing a
 * sidebar gives the player a scoreboard of their own with one objective in
 * the sidebar slot, its scores hidden (`NumberFormat.blank()`) and each line
 * a score's custom name; it copies the main scoreboard's teams and lines
 * under name tags ([MainBoard.copyTo]), so team colours and name tags still
 * work. NetherForge's own changes are copied on the next tick
 * ([copySoon]); anything else (`/team`, another plugin) within a second.
 * Hiding it gives them the main scoreboard back.
 */
class PaperSidebars(private val plugin: Plugin) : SidebarOps {
    private val boards = HashMap<UUID, Scoreboard>()
    private var sync: BukkitTask? = null
    private var soon: BukkitTask? = null

    override fun show(player: UUID, title: String, lines: List<String>): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        val board = boards.getOrPut(player) {
            val made = Bukkit.getScoreboardManager().newScoreboard
            MainBoard.copyTo(made)
            made
        }
        val objective =
            board.getObjective(OBJECTIVE) ?: board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, PaperText.mini.deserialize(title)).also {
                it.displaySlot = DisplaySlot.SIDEBAR
                it.numberFormat(NumberFormat.blank())
            }
        objective.displayName(PaperText.mini.deserialize(title))
        for ((index, entry) in ENTRIES.withIndex()) {
            if (index < lines.size) {
                val score = objective.getScore(entry)
                score.score = lines.size - index
                score.customName(PaperText.mini.deserialize(lines[index]))
            } else {
                board.resetScores(entry)
            }
        }
        if (who.scoreboard !== board) who.scoreboard = board
        if (sync == null) sync = Bukkit.getScheduler().runTaskTimer(plugin, Runnable(::syncAll), 20L, 20L)
        return true
    }

    override fun hide(player: UUID): Boolean {
        boards.remove(player)
        if (boards.isEmpty()) {
            sync?.cancel()
            sync = null
        }
        val who = Bukkit.getPlayer(player) ?: return false
        who.scoreboard = Bukkit.getScoreboardManager().mainScoreboard
        return true
    }

    /** Copies the main scoreboard onto every player's own on the next tick (once, however many changes asked). */
    fun copySoon() {
        // A disabled plugin can't schedule (the runtime's stop removes teams while the plugin disables).
        if (boards.isEmpty() || soon != null || !plugin.isEnabled) return
        soon = Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                soon = null
                syncAll()
            }
        )
    }

    private fun syncAll() {
        boards.keys.retainAll { Bukkit.getPlayer(it) != null }
        for (board in boards.values) MainBoard.copyTo(board)
    }

    private companion object {
        const val OBJECTIVE = "netherforge"

        /** 15 distinct entries nobody sees (each line's text is its score's custom name). */
        val ENTRIES = (0 until 15).map { "§" + "0123456789abcde"[it] }
    }
}
