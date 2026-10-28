package dev.netherforge.plugin.paper

import dev.netherforge.format.game.BlockState
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.WatchedEvent
import io.papermc.paper.event.player.PlayerCustomClickEvent
import io.papermc.paper.event.player.PlayerPickBlockEvent
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.block.Block
import org.bukkit.block.data.BlockData
import org.bukkit.entity.AbstractArrow
import org.bukkit.entity.Arrow
import org.bukkit.entity.Display
import org.bukkit.entity.Interaction
import org.bukkit.entity.ItemFrame
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockDamageAbortEvent
import org.bukkit.event.entity.EntityDamageByEntityEvent
import org.bukkit.event.entity.EntityDamageEvent
import org.bukkit.event.entity.EntityDeathEvent
import org.bukkit.event.entity.EntityPickupItemEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.PlayerDeathEvent
import org.bukkit.event.hanging.HangingBreakEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.DragType
import org.bukkit.event.inventory.InventoryAction
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.player.PlayerDropItemEvent
import org.bukkit.event.player.PlayerInteractAtEntityEvent
import org.bukkit.event.player.PlayerInteractEntityEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.event.player.PlayerItemConsumeEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerResourcePackStatusEvent
import org.bukkit.event.server.PluginDisableEvent
import org.bukkit.event.server.PluginEnableEvent
import org.bukkit.event.server.ServiceRegisterEvent
import org.bukkit.event.server.ServiceUnregisterEvent
import org.bukkit.event.world.EntitiesLoadEvent
import org.bukkit.event.world.EntitiesUnloadEvent
import org.bukkit.event.world.WorldSaveEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID

/** A player's hotbar: the first 9 of their slots. */
private const val HOTBAR = 9

/** The slots a picked block's item is looked for in: the hotbar and the rest of the inventory, not the armour. */
private const val PICKABLE = 36

/**
 * Translates the server's events the runtime does more with than raise them
 * to scripts into [PlatformEvents] calls, and applies the answers; every other
 * event scripts hear is [PaperGameEvents]'s, mapped to its generated payload.
 * Cancellable events listen at HIGH so protection plugins (which usually
 * decide at NORMAL or LOW) have had their say, and skip events something
 * already cancelled.
 */
class PaperEvents(
    private val plugin: JavaPlugin,
    private val platform: PaperPlatform,
    private val runtime: PlatformEvents,
    private val version: PaperVersion
) : Listener {
    /** Players whose draw of a bow with no ammo the server started ([PaperVersion.startUsingItem]): their shot is fired by [onLetGoOfDraw]. */
    private val drawing = HashSet<UUID>()

    /**
     * Players whose last right click on a block the runtime took (it placed a block from an item, or a script
     * cancelled it): the tick and the hand.
     */
    private val consumed = HashMap<UUID, Pair<Int, EquipmentSlot>>()

    /**
     * The interact event being raised, when it's the one Paper raises for a right click that used the item (its
     * ray cast hit a block) rather than for a click on a block: it starts with the block not to be used.
     */
    private var aimed: PlayerInteractEvent? = null

    /** The game's other events, generated from the API spec: always listened to but for the watched ones. */
    private val game = PaperGameEvents(plugin, platform, runtime.game).also { plugin.server.pluginManager.registerEvents(it, plugin) }

    private val watching = Watching(plugin, game)

    /** Starts or stops delivering [event] (`Platform.watch`). */
    fun watch(event: WatchedEvent, listening: Boolean) = watching.watch(event, listening)

    private fun ref(event: org.bukkit.event.player.PlayerEvent) = platform.players.ref(event.player)

    private fun block(block: Block) = blockRef(block)

    @EventHandler
    fun onCustomClick(event: PlayerCustomClickEvent) = platform.dialogs.customClicked(event)

    /** Before any plugin has a say: whether this is Paper's own event for a right click that used the item ([aimed]). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    fun onInteractRaised(event: PlayerInteractEvent) {
        aimed = event.takeIf {
            it.action == Action.RIGHT_CLICK_BLOCK && it.useInteractedBlock() == org.bukkit.event.Event.Result.DENY
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    fun onInteract(event: PlayerInteractEvent) {
        val slot = event.hand ?: return
        // A click on a block the runtime took (a custom block placed) the player's game can't foresee: it thinks
        // nothing happened and uses the item too, which Paper raises again at whatever the player looks at now (the
        // block just placed). That's the same click, and it's been had.
        val tick = Bukkit.getCurrentTick()
        if (event === aimed) {
            val last = consumed[event.player.uniqueId]
            if (last != null && last.second == slot && tick - last.first <= 1) {
                event.isCancelled = true
                return
            }
        }
        if (event.action == Action.PHYSICAL) return
        if (event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY &&
            event.useItemInHand() == org.bukkit.event.Event.Result.DENY
        ) {
            return
        }
        val hand = if (slot == EquipmentSlot.OFF_HAND) "off_hand" else "main_hand"
        val button = if (event.action.isLeftClick) ClickButton.LEFT else ClickButton.RIGHT
        val clicked = event.clickedBlock
        val face = event.blockFace.takeIf { clicked != null && it in FACES }?.name?.lowercase()
        val item = PaperItems.toItem(event.item)
        if (runtime.playerInteract(ref(event), button, clicked?.let(::block), face, item, hand)) {
            event.isCancelled = true
            if (event.action == Action.RIGHT_CLICK_BLOCK && event !== aimed) consumed[event.player.uniqueId] = tick to slot
            return
        }
        // Using the item comes after the click, and only stops the item.
        if (button == ClickButton.RIGHT && item != null && event.useItemInHand() != org.bukkit.event.Event.Result.DENY) {
            if (runtime.playerUseItem(ref(event), item, hand)) {
                event.setUseItemInHand(org.bukkit.event.Event.Result.DENY)
            } else if (runtime.drawsWithoutAmmo(item) && !hasArrows(event.player) && event.player.gameMode != GameMode.CREATIVE) {
                // The game won't draw a bow with no arrows, so the draw is started here, and the shot fired on release.
                drawing.add(event.player.uniqueId)
                version.startUsingItem(event.player, slot)
            }
        }
    }

    /**
     * Fires the shot of a bow drawn with no ammo, as the game would have: the force its draw gives, and a shot the
     * `entity_shoot_bow` handlers can cancel, which takes the arrow back. Nothing is used up.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onLetGoOfDraw(event: PlayerStopUsingItemEvent) {
        if (!drawing.remove(event.player.uniqueId)) return
        val player = event.player
        // The game's force for a draw this long (BowItem.getPowerForTime), and no shot below a tenth of it.
        val seconds = event.ticksHeldFor / 20.0
        val force = minOf(1.0, (seconds * seconds + seconds * 2.0) / 3.0)
        if (force < 0.1) return
        val arrow = player.world.spawn(player.eyeLocation, Arrow::class.java) {
            it.shooter = player
            it.velocity = player.eyeLocation.direction.multiply(force * 3.0)
            it.isCritical = force >= 1.0
            // Like a shot from a bow with no ammo, it's not an arrow to pick back up for free.
            it.pickupStatus = AbstractArrow.PickupStatus.CREATIVE_ONLY
        }
        val hand = if (player.inventory.itemInMainHand.isSimilar(event.item)) EquipmentSlot.HAND else EquipmentSlot.OFF_HAND
        val shot = EntityShootBowEvent(player, event.item, null, arrow, hand, force.toFloat(), false)
        plugin.server.pluginManager.callEvent(shot)
        if (shot.isCancelled) arrow.remove()
    }

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        drawing.remove(event.player.uniqueId)
        consumed.remove(event.player.uniqueId)
    }

    /** Whether [player] has any arrow (arrow, tipped or spectral: what a bow takes as ammo). */
    private fun hasArrows(player: Player): Boolean = player.inventory.contents.any { it != null && it.type.name.endsWith("ARROW") }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDropItem(event: PlayerDropItemEvent) {
        val item = PaperItems.toItem(event.itemDrop.itemStack) ?: return
        if (runtime.playerDropItem(ref(event), item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickupItem(event: EntityPickupItemEvent) {
        val player = event.entity as? Player ?: return
        val item = PaperItems.toItem(event.item.itemStack) ?: return
        if (runtime.playerPickupItem(platform.players.ref(player), item, event.item.uniqueId)) event.isCancelled = true
    }

    /** A `hold` item never finishes: held its whole (day-long) use, it isn't eaten, and no script hears a consume. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    fun onHoldEnd(event: PlayerItemConsumeEvent) {
        if (PaperItems.isHold(event.item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onConsume(event: PlayerItemConsumeEvent) {
        val item = PaperItems.toItem(event.item) ?: return
        if (runtime.playerConsumeItem(ref(event), item)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldSave(event: WorldSaveEvent) {
        runtime.worldSaving(event.world.name)
    }

    // What packages `require` of other plugins (Vault, PlaceholderAPI): they enable in the server's order, an economy
    // registers with Vault whenever it likes, and the runtime looks again on its next tick, when the state is settled.
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginEnable(event: PluginEnableEvent) = runtime.pluginsChanged()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onPluginDisable(event: PluginDisableEvent) = runtime.pluginsChanged()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onServiceRegister(event: ServiceRegisterEvent) = runtime.pluginsChanged()

    @EventHandler(priority = EventPriority.MONITOR)
    fun onServiceUnregister(event: ServiceUnregisterEvent) = runtime.pluginsChanged()

    /**
     * A break whose drops scripts changed drops nothing of its own; what they
     * said drops where the block was, as the block's own drops would.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBreak(event: BlockBreakEvent) {
        val broken = event.block
        val drops = { broken.getDrops(event.player.inventory.itemInMainHand, event.player).mapNotNull(PaperItems::toItem) }
        val answer = runtime.blockBreak(platform.players.ref(event.player), block(broken), drops, event.expToDrop)
        if (answer == null) {
            event.isCancelled = true
            return
        }
        if (answer.experience != event.expToDrop) event.expToDrop = answer.experience
        val changed = answer.drops ?: return
        event.isDropItems = false
        val at = broken.location.add(0.5, 0.5, 0.5)
        for (stack in stacks(changed)) broken.world.dropItemNaturally(at, stack)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onStopBreak(event: BlockDamageAbortEvent) {
        runtime.blockStopBreak(platform.players.ref(event.player), block(event.block))
    }

    /**
     * A middle click on a block. What the runtime answers is picked as the game picks a block's item: one in the
     * hotbar is selected, one elsewhere is swapped into a hotbar slot (an empty one, else one without enchantments,
     * from the selected one on), and a new one goes into that slot, what was there moved to a free one.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPickBlock(event: PlayerPickBlockEvent) {
        val player = event.player
        val inventory = player.inventory
        val held = (0 until PICKABLE).map { slot -> inventory.getItem(slot)?.takeUnless { it.isEmpty }?.let(PaperItems::toItem) }
        val answer = runtime.pickBlock(platform.players.ref(player), block(event.block), held) ?: return
        event.isCancelled = true
        val source = answer.slot
        if (source != null) {
            if (source < HOTBAR) {
                inventory.heldItemSlot = source
                return
            }
            val target = hotbarSlot(inventory)
            val was = inventory.getItem(target)
            inventory.setItem(target, inventory.getItem(source))
            inventory.setItem(source, was)
            inventory.heldItemSlot = target
            return
        }
        val stack = answer.item?.let(PaperItems::toStack) ?: return
        val target = hotbarSlot(inventory)
        val was = inventory.getItem(target)
        if (was != null && !was.isEmpty) {
            val free = inventory.storageContents.indexOfFirst { it == null || it.isEmpty }
            if (free >= 0) inventory.setItem(free, was)
        }
        inventory.setItem(target, stack)
        inventory.heldItemSlot = target
    }

    /** Where the game puts a picked item: the first empty hotbar slot from the held one on, else one without enchantments, else the held one. */
    private fun hotbarSlot(inventory: org.bukkit.inventory.PlayerInventory): Int {
        val held = inventory.heldItemSlot
        val order = (0 until HOTBAR).map { (held + it) % HOTBAR }
        return order.firstOrNull { inventory.getItem(it).let { stack -> stack == null || stack.isEmpty } }
            ?: order.firstOrNull { inventory.getItem(it)?.enchantments.isNullOrEmpty() }
            ?: held
    }

    private fun stacks(items: List<ItemData>): List<ItemStack> = items.mapNotNull(PaperItems::toStack)

    /**
     * A right click on an entity. Since 26.1 the client sends one packet per
     * hand, always with the point it hit, and the server raises only
     * `PlayerInteractAtEntityEvent` for it (the plain event it subclasses is
     * never raised), so that's the click. A 1.21.x client sends the point it
     * hit first and then a plain interaction, which raises the plain event
     * as well: that one only follows what was decided here
     * ([onPlainRightClickEntity]), so a click is heard once either way.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onRightClickEntity(event: PlayerInteractAtEntityEvent) {
        val entity = event.rightClicked
        lastRightClick = null
        if (platform.entities.tagOf(entity) == null) {
            // Anything else: `player_interact_entity`, once per hand.
            val hand = if (event.hand == EquipmentSlot.OFF_HAND) "off_hand" else "main_hand"
            if (runtime.playerInteractEntity(ref(event), entity.uniqueId, hand)) cancelRightClick(event)
            return
        }
        cancelRightClick(event)
        if (event.hand != EquipmentSlot.HAND) return
        runtime.entityClicked(entity.uniqueId, ref(event), ClickButton.RIGHT, PaperPlatform.sight(event.player))
    }

    /** The right click on an entity cancelled last, for the plain interaction a 1.21.x client sends after it. */
    private var lastRightClick: Triple<UUID, UUID, EquipmentSlot>? = null

    private fun cancelRightClick(event: PlayerInteractAtEntityEvent) {
        event.isCancelled = true
        lastRightClick = Triple(event.player.uniqueId, event.rightClicked.uniqueId, event.hand)
    }

    /** A 1.21.x client's plain interaction after the point it hit: cancelled when that was. Never raised since 26.1. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlainRightClickEntity(event: PlayerInteractEntityEvent) {
        // Bukkit hands this handler the point-hit event too (it's a subclass): that one is onRightClickEntity's.
        if (event is PlayerInteractAtEntityEvent) return
        if (lastRightClick == Triple(event.player.uniqueId, event.rightClicked.uniqueId, event.hand)) event.isCancelled = true
        lastRightClick = null
    }

    /** A screen's frame is never broken: not by a player, an explosion, a projectile, or the wall behind it going. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHangingBreak(event: HangingBreakEvent) {
        if (platform.entities.tagOf(event.entity) != null) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLeftClickEntity(event: PrePlayerAttackEntityEvent) {
        val entity = event.attacked
        if (platform.entities.tagOf(entity) == null) return
        event.isCancelled = true
        runtime.entityClicked(entity.uniqueId, ref(event), ClickButton.LEFT, PaperPlatform.sight(event.player))
    }

    /**
     * A click while one of our windows is open: in it or in the player's own
     * inventory below. Cancelled when the runtime says so (a script took it,
     * or the window is locked and it would move items in or out).
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryClick(event: InventoryClickEvent) {
        val window = platform.menus.holderOf(event.view.topInventory)?.window ?: return
        val player = event.whoClicked as? Player ?: return
        val top = event.clickedInventory != null && event.clickedInventory === event.view.topInventory
        val slot = when {
            event.clickedInventory == null -> null
            top -> event.rawSlot
            else -> event.slot
        }
        val moves = top || event.action == InventoryAction.MOVE_TO_OTHER_INVENTORY || event.action == InventoryAction.COLLECT_TO_CURSOR
        val click = MenuClick(
            platform.players.ref(player),
            slot,
            top,
            clickName(event.click),
            event.hotbarButton.takeIf { it >= 0 },
            PaperItems.toItem(event.currentItem),
            PaperItems.toItem(event.cursor),
            moves
        )
        platform.menus.inClick = true
        try {
            if (runtime.menuClicked(window, click)) event.isCancelled = true
        } finally {
            platform.menus.inClick = false
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onInventoryDrag(event: InventoryDragEvent) {
        val window = platform.menus.holderOf(event.view.topInventory)?.window ?: return
        val player = event.whoClicked as? Player ?: return
        val (slots, own) = dragSlots(event)
        platform.menus.inClick = true
        try {
            val cursor = PaperItems.toItem(event.oldCursor)
            if (runtime.menuDragged(window, platform.players.ref(player), slots, cursor, own, dragName(event.type))) {
                event.isCancelled = true
            }
        } finally {
            platform.menus.inClick = false
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onInventoryClose(event: InventoryCloseEvent) {
        val window = platform.menus.holderOf(event.inventory)?.window ?: return
        if (window in platform.menus.rebuilding) return
        val player = event.player as? Player ?: return
        runtime.menuClosed(window, platform.players.ref(player))
    }

    /** Something hurts an entity that isn't ours: scripts may change how much, or stop it. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDamage(event: EntityDamageEvent) {
        val entity = event.entity
        if (platform.entities.tagOf(entity) != null) {
            // A screen's frame: nothing knocks it down or its map out.
            if (entity is ItemFrame) event.isCancelled = true
            return
        }
        val attacker = event.damageSource.causingEntity ?: (event as? EntityDamageByEntityEvent)?.damager
        val amount = event.damage
        val answer = runtime.entityDamaged(entity.uniqueId, amount, event.cause.name.lowercase(), attacker?.uniqueId)
        when {
            answer == null -> event.isCancelled = true
            answer != amount -> event.damage = answer
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onDeath(event: EntityDeathEvent) {
        val entity = event.entity
        if (platform.entities.tagOf(entity) != null) return
        val killer = entity.killer ?: event.damageSource.causingEntity
        if (event is PlayerDeathEvent) return playerDied(event, killer?.uniqueId)
        val answer = runtime.entityDied(entity.uniqueId, killer?.uniqueId, { event.drops.mapNotNull(PaperItems::toItem) }, event.droppedExp)
        if (answer.experience != event.droppedExp) event.droppedExp = answer.experience
        answer.drops?.let { drops(event, it) }
    }

    private fun playerDied(event: PlayerDeathEvent, killer: java.util.UUID?) {
        val message = event.deathMessage()?.let(PaperText.mini::serialize)
        val cause = event.player.lastDamageCause?.cause?.name?.lowercase() ?: "custom"
        val answer = runtime.playerDied(
            ref(event.player),
            killer,
            cause,
            message,
            event.keepInventory,
            event.drops.mapNotNull(PaperItems::toItem),
            event.droppedExp
        )
        if (answer.message != message) event.deathMessage(answer.message?.let(PaperText.mini::deserialize))
        if (answer.keepInventory != event.keepInventory) event.keepInventory = answer.keepInventory
        if (answer.experience != event.droppedExp) event.droppedExp = answer.experience
        answer.drops?.let { drops(event, it) }
    }

    private fun ref(player: Player) = platform.players.ref(player)

    private fun drops(event: EntityDeathEvent, items: List<ItemData>) {
        event.drops.clear()
        event.drops.addAll(stacks(items))
    }

    /** While their chunk can still be written: entities' script data goes into them. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesUnload(event: EntitiesUnloadEvent) {
        val ids = event.entities.filter { platform.entities.tagOf(it) == null }.map { it.uniqueId }
        if (ids.isNotEmpty()) runtime.entitiesUnloading(ids)
    }

    /** What a player's game said about a resource pack. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onPackStatus(event: PlayerResourcePackStatusEvent) {
        val status = when (event.status) {
            PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED -> "loaded"
            PlayerResourcePackStatusEvent.Status.DECLINED -> "declined"
            PlayerResourcePackStatusEvent.Status.FAILED_DOWNLOAD,
            PlayerResourcePackStatusEvent.Status.INVALID_URL,
            PlayerResourcePackStatusEvent.Status.FAILED_RELOAD -> "failed"
            PlayerResourcePackStatusEvent.Status.ACCEPTED, PlayerResourcePackStatusEvent.Status.DOWNLOADED -> "pending"
            else -> null
        }
        runtime.resourcePackStatus(event.player.uniqueId, event.id, status)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onEntitiesLoad(event: EntitiesLoadEvent) {
        val tagged = HashMap<UUID, EntityTag>()
        val untagged = ArrayList<UUID>()
        for (entity in event.entities) {
            val tag = if (entity is Display || entity is Interaction || entity is ItemFrame) platform.entities.tagOf(entity) else null
            if (tag != null) tagged[entity.uniqueId] = tag else untagged += entity.uniqueId
        }
        if (tagged.isNotEmpty() || untagged.isNotEmpty()) runtime.entitiesLoaded(tagged, untagged)
        val markers = event.entities.mapNotNull(platform.entities::markerOf)
        // Next tick: a marker is taken out of the world, which the server doesn't want done while it adds the chunk's entities.
        if (markers.isNotEmpty()) Bukkit.getScheduler().runTask(plugin, Runnable { runtime.structureMarkersLoaded(markers) })
    }
}

/** A block state, canonical. */
internal fun stateOf(data: BlockData): String = BlockState.parse(data.asString)?.toString() ?: data.asString

/** A click as scripts name it (`MenuClickEvent.click`, `PlayerClickInventoryEvent.click`). */
internal fun clickName(click: ClickType): String = when (click) {
    ClickType.LEFT -> "left"
    ClickType.RIGHT -> "right"
    ClickType.SHIFT_LEFT -> "shift_left"
    ClickType.SHIFT_RIGHT -> "shift_right"
    ClickType.MIDDLE -> "middle"
    ClickType.NUMBER_KEY -> "number_key"
    ClickType.SWAP_OFFHAND -> "swap_offhand"
    ClickType.DOUBLE_CLICK -> "double"
    ClickType.DROP -> "drop"
    ClickType.CONTROL_DROP -> "control_drop"
    else -> "other"
}

/** A drag's slots in the inventory above, and in the player's own (as their inventory numbers them), each in order. */
internal fun dragSlots(event: InventoryDragEvent): Pair<List<Int>, List<Int>> {
    val size = event.view.topInventory.size
    val (top, own) = event.rawSlots.sorted().partition { it < size }
    return top to own.map(event.view::convertSlot)
}

/** A drag's button as scripts name it (`MenuDragEvent.click`, `PlayerDragInventoryEvent.click`). */
internal fun dragName(type: DragType): String = if (type == DragType.SINGLE) "right" else "left"
