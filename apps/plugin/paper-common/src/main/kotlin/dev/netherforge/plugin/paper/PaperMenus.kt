package dev.netherforge.plugin.paper

import dev.netherforge.format.menu.MenuType
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.MenuOps
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.WindowSpec
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.inventory.InventoryType
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * The holder every NetherForge window is made with: how an inventory event finds
 * its way back to a window, without comparing titles or sizes.
 */
class WindowHolder(val window: UUID) : InventoryHolder {
    internal var backing: Inventory? = null

    override fun getInventory(): Inventory = backing ?: error("window used before it was made")
}

/**
 * [MenuOps] with Bukkit inventories.
 *
 * Minecraft can't retitle an open window, so [retitle] builds a new inventory
 * with the same contents and reopens it for every viewer; the closes that
 * causes are swallowed ([rebuilding]) so scripts don't hear a viewer leave.
 *
 * Opening and closing a window from inside a click handler is unsafe in
 * Bukkit (the click is still modifying the view), so while one is being
 * handled ([PaperEvents] sets [inClick]) those are deferred to the next tick.
 *
 * Every viewer's view of a window is watched ([PaperVersion.watchSlots]): what
 * the game changes in it for a click or a drag reaches the runtime
 * ([PlatformEvents.menuChanged]) as the game sends it to them, straight after
 * it applies it, so a slot bound to a player's storage is saved with it.
 */
class PaperMenus(private val plugin: Plugin, private val version: PaperVersion) : MenuOps {
    /** Where what the game changes in a window goes; set when the runtime binds. */
    var events: PlatformEvents? = null

    private val mini = PaperText.mini
    private val windows = HashMap<UUID, Inventory>()

    /** Set while a click event is being dispatched. */
    var inClick = false

    /** Windows being rebuilt: their close events aren't a viewer leaving. */
    val rebuilding = HashSet<UUID>()

    fun holderOf(inventory: Inventory?): WindowHolder? = inventory?.getHolder(false) as? WindowHolder

    private fun player(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

    private fun later(task: () -> Unit) {
        if (inClick) Bukkit.getScheduler().runTask(plugin, Runnable(task)) else task()
    }

    /** Shows [inventory] (the window [window]) to [who], and watches what the game changes in it for them. */
    private fun show(who: Player, window: UUID, inventory: Inventory) {
        who.openInventory(inventory)
        // Another plugin may have refused it.
        if (who.openInventory.topInventory !== inventory) return
        version.watchSlots(who, inventory.size) { slots -> events?.menuChanged(window, slots) }
    }

    private fun make(window: UUID, spec: WindowSpec): Inventory {
        val holder = WindowHolder(window)
        val title = mini.deserialize(spec.title)
        val inventory = if (spec.type == MenuType.CHEST) {
            Bukkit.createInventory(holder, spec.size, title)
        } else {
            Bukkit.createInventory(holder, bukkitType(spec.type), title)
        }
        holder.backing = inventory
        return inventory
    }

    private fun bukkitType(type: MenuType): InventoryType = when (type) {
        MenuType.CHEST -> InventoryType.CHEST
        MenuType.BARREL -> InventoryType.BARREL
        MenuType.SHULKER_BOX -> InventoryType.SHULKER_BOX
        MenuType.HOPPER -> InventoryType.HOPPER
        MenuType.DISPENSER -> InventoryType.DISPENSER
        MenuType.DROPPER -> InventoryType.DROPPER
        MenuType.CRAFTER -> InventoryType.CRAFTER
    }

    private val specs = HashMap<UUID, WindowSpec>()

    override fun create(window: UUID, spec: WindowSpec) {
        specs[window] = spec
        windows[window] = make(window, spec)
    }

    override fun destroy(window: UUID) {
        val inventory = windows.remove(window) ?: return
        specs.remove(window)
        val viewers = inventory.viewers.toList()
        later {
            rebuilding += window
            try {
                viewers.filter { it.openInventory.topInventory === inventory }.forEach { it.closeInventory() }
            } finally {
                rebuilding -= window
            }
        }
    }

    override fun retitle(window: UUID, title: String) {
        val previous = windows[window] ?: return
        val spec = specs.getValue(window).copy(title = title)
        specs[window] = spec
        val next = make(window, spec)
        next.contents = previous.contents
        windows[window] = next
        val viewers = previous.viewers.filterIsInstance<Player>()
        later {
            rebuilding += window
            try {
                for (viewer in viewers) show(viewer, window, next)
            } finally {
                rebuilding -= window
            }
        }
    }

    override fun item(window: UUID, slot: Int): ItemData? = windows[window]?.getItem(slot)?.let(PaperItems::toItem)

    override fun setItem(window: UUID, slot: Int, item: ItemData?): Boolean {
        val inventory = windows[window] ?: return false
        if (slot !in 0 until inventory.size) return false
        if (item == null) {
            inventory.setItem(slot, null)
            return true
        }
        val stack = PaperItems.toStack(item) ?: return false
        inventory.setItem(slot, stack)
        return true
    }

    override fun add(window: UUID, item: ItemData): Int {
        val inventory = windows[window] ?: return item.def.count ?: 1
        val stack = PaperItems.toStack(item) ?: return item.def.count ?: 1
        return inventory.addItem(stack).values.sumOf { it.amount }
    }

    override fun room(window: UUID, slot: Int, item: ItemData): Int {
        val inventory = windows[window] ?: return 0
        if (slot !in 0 until inventory.size) return 0
        val stack = PaperItems.toStack(item) ?: return 0
        val held = inventory.getItem(slot)
        return when {
            held == null || held.type.isAir -> stack.maxStackSize
            held.isSimilar(stack) -> (held.maxStackSize - held.amount).coerceAtLeast(0)
            else -> 0
        }
    }

    override fun open(window: UUID, player: UUID): Boolean {
        val inventory = windows[window] ?: return false
        val who = player(player) ?: return false
        later { show(who, window, inventory) }
        return true
    }

    override fun close(window: UUID, player: UUID?) {
        val inventory = windows[window] ?: return
        val closing = if (player == null) inventory.viewers.toList() else inventory.viewers.filter { it.uniqueId == player }
        later { closing.filter { it.openInventory.topInventory === inventory }.forEach { it.closeInventory() } }
    }

    override fun viewers(window: UUID): List<UUID> = windows[window]?.viewers?.map { it.uniqueId }.orEmpty()

    override fun closeAny(player: UUID): Boolean {
        val who = player(player) ?: return false
        later { who.closeInventory() }
        return true
    }

    override fun viewing(player: UUID): UUID? = player(player)?.openInventory?.topInventory?.let(::holderOf)?.window

    /** [player], when they're looking at [window]. */
    private fun viewer(window: UUID, player: UUID): Player? {
        val inventory = windows[window] ?: return null
        return player(player)?.takeIf { it.openInventory.topInventory === inventory }
    }

    override fun cursor(window: UUID, player: UUID): ItemData? = viewer(window, player)?.itemOnCursor?.let(PaperItems::toItem)

    override fun setCursor(window: UUID, player: UUID, item: ItemData?): Boolean {
        val who = viewer(window, player) ?: return false
        val stack = item?.let { PaperItems.toStack(it) ?: return false }
        who.setItemOnCursor(stack)
        return true
    }

    override fun refresh(window: UUID) {
        val inventory = windows[window] ?: return
        later { inventory.viewers.filterIsInstance<Player>().forEach { it.updateInventory() } }
    }
}
