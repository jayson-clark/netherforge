package dev.netherforge.plugin.events
import dev.netherforge.plugin.NetherForgeRuntime
import dev.netherforge.plugin.binding.BlockBreakEvent
import dev.netherforge.plugin.binding.EntityDamageEvent
import dev.netherforge.plugin.binding.EntityDeathEvent
import dev.netherforge.plugin.binding.EventType
import dev.netherforge.plugin.binding.Events
import dev.netherforge.plugin.binding.LuaEvent
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.binding.PlayerDeathEvent
import dev.netherforge.plugin.binding.PlayerInteractEntityEvent
import dev.netherforge.plugin.binding.PlayerInteractEvent
import dev.netherforge.plugin.binding.PlayerItemEvent
import dev.netherforge.plugin.binding.PlayerPickupItemEvent
import dev.netherforge.plugin.binding.PlayerUseItemEvent
import dev.netherforge.plugin.binding.entityHandle
import dev.netherforge.plugin.binding.livingHandle
import dev.netherforge.plugin.loot.LootRoll
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.DeathAnswer
import dev.netherforge.plugin.platform.DialogAnswers
import dev.netherforge.plugin.platform.DropsAnswer
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.GameEvents
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemView
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.MenuClick
import dev.netherforge.plugin.platform.PickAnswer
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.StructureMarker
import dev.netherforge.plugin.session.block
import dev.netherforge.plugin.session.changed
import dev.netherforge.plugin.session.handle
import dev.netherforge.plugin.session.listening
import dev.netherforge.plugin.session.playerEvent
import java.util.UUID
import kotlin.random.Random

/**
 * What the server tells the runtime ([PlatformEvents]), for as long as the
 * plugin is enabled: each event goes to the session running now, to its
 * services' hooks (what unloaded, who clicked a centity or a window) and to
 * its scripts, along a path the runtime decides (a project item's handlers
 * first, a player's death then `entity_death`). A project that isn't running
 * hears nothing but what keeps the world in order (a centity's entities
 * loading, a click on one). Every other event scripts hear is [game]'s,
 * generated from the API spec.
 */
class ServerEvents internal constructor(private val runtime: NetherForgeRuntime) : PlatformEvents {
    private val session get() = runtime.session
    private val scripts get() = session.scripts
    private val items get() = session.items
    private val running get() = session.running

    override val game: GameEvents = RuntimeGameEvents(runtime)

    override fun tick() = runtime.tick()

    override fun itemRendered(stack: ItemData): ItemData = items.render(stack)

    override fun itemViewing(reference: String?): Boolean = running && items.viewing(reference)

    override fun itemViewed(player: UUID, stack: ItemData): ItemView? = if (running) items.view(player, stack) else null

    /** A player's event with [item]: heard first by its project item's [itemEvent] handlers, when it's a stack of one. */
    private fun <P : LuaEvent> itemEvent(
        itemEvent: EventType<P>,
        item: ItemData?,
        own: EventType<P>,
        nf: EventType<P>,
        player: PlayerRef,
        payload: P
    ): Boolean = scripts.emit(items.stage(itemEvent, item) + listOf(own to session.handle(player), nf to null), payload)

    private fun handle(player: PlayerRef) = session.handle(player)

    private fun <P : LuaEvent> playerEvent(own: EventType<P>, nf: EventType<P>, player: PlayerRef, payload: P): Boolean =
        session.playerEvent(own, nf, player, payload)

    override fun playerInteract(
        player: PlayerRef,
        button: ClickButton,
        block: BlockRef?,
        face: String?,
        item: ItemData?,
        hand: String
    ): Boolean = running &&
        (
            // A click aimed at a screen within its click range is the screen's, before anything in the world hears it.
            session.screens.swing(player, button) ||
                itemEvent(
                    Events.PROJECTITEM_INTERACT,
                    item,
                    Events.PLAYER_INTERACT,
                    Events.NF_PLAYER_INTERACT,
                    player,
                    PlayerInteractEvent(handle(player), button.luaName, block?.let(session::block), face, item, hand)
                ) ||
                // Then the block's own: a click on one of the project's blocks, or on any block with an item that places one.
                session.customBlocks.interact(player, button, block, face, item, hand)
            )

    override fun playerUseItem(player: PlayerRef, item: ItemData, hand: String): Boolean = running &&
        itemEvent(
            Events.PROJECTITEM_USE,
            item,
            Events.PLAYER_USE_ITEM,
            Events.NF_PLAYER_USE_ITEM,
            player,
            PlayerUseItemEvent(handle(player), item, hand)
        )

    override fun drawsWithoutAmmo(item: ItemData): Boolean = running && items.drawsWithoutAmmo(item)

    override fun blockBreak(player: PlayerRef, block: BlockRef, drops: () -> List<ItemData>, experience: Int): DropsAnswer? {
        val unchanged = DropsAnswer(null, experience)
        if (!running) return unchanged
        // One of the project's blocks is broken by the runtime (its drops, its sounds, its scripts' say): the server's own break is cancelled.
        if (session.customBlocks.breaking(player, block, experience)) return null
        // One of the game's with rules of the project's drops what its rule says instead of the game's drops.
        val ruled = session.customBlocks.gameDrops(player, block)
        val world = LuaHandle.World(block.world)
        val stages = items.heldStage(Events.PROJECTITEM_BREAK_BLOCK, player.uuid, MAIN_HAND) +
            listOf(Events.WORLD_BLOCK_BREAK to world, Events.NF_BLOCK_BREAK to null)
        if (stages.none { (event, stage) -> scripts.listening(stage, event) }) return DropsAnswer(ruled, experience)
        val before = ruled ?: drops()
        val event = BlockBreakEvent(handle(player), session.block(block), block.state, before, experience.toLong())
        if (scripts.emit(stages, event)) return null
        return DropsAnswer(
            event.drops.takeIf { ruled != null || session.changed(it, before) },
            event.experience.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        )
    }

    override fun worldBroke(block: BlockRef): List<ItemData>? = if (running) session.customBlocks.worldBroke(block) else null

    override fun blockUpdated(block: BlockRef) {
        if (running) session.customBlocks.blockUpdated(block)
    }

    override fun fallingLanded(entity: UUID, block: BlockRef) {
        if (running) session.customBlocks.fallingLanded(entity, block)
    }

    override fun blockStopBreak(player: PlayerRef, block: BlockRef) {
        if (running) session.customBlocks.stoppedBreaking(player, block)
    }

    override fun pickBlock(player: PlayerRef, block: BlockRef, inventory: List<ItemData?>): PickAnswer? =
        if (running) session.customBlocks.picking(player, block, inventory) else null

    override fun playerDied(
        player: PlayerRef,
        killer: UUID?,
        cause: String,
        message: String?,
        keepInventory: Boolean,
        drops: List<ItemData>,
        experience: Int
    ): DeathAnswer {
        if (!running) return DeathAnswer(message, keepInventory, null, experience)
        val death = PlayerDeathEvent(handle(player), killer?.let { session.entityHandle(it) }, cause, message, keepInventory, drops)
        playerEvent(Events.PLAYER_DEATH, Events.NF_PLAYER_DEATH, player, death)
        // Keeping the inventory means nothing from it drops, unless a handler said what does.
        if (death.keepInventory && !keepInventory && !session.changed(death.drops, drops)) death.drops = emptyList()
        val entity = EntityDeathEvent(handle(player), death.killer, death.drops, experience.toLong())
        scripts.emit(Events.NF_ENTITY_DEATH, null, entity)
        return DeathAnswer(
            death.message,
            death.keepInventory,
            entity.drops.takeIf { session.changed(it, drops) },
            entity.experience.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        )
    }

    override fun playerDropItem(player: PlayerRef, item: ItemData): Boolean = running &&
        itemEvent(
            Events.PROJECTITEM_DROP,
            item,
            Events.PLAYER_DROP_ITEM,
            Events.NF_PLAYER_DROP_ITEM,
            player,
            PlayerItemEvent(handle(player), item)
        )

    override fun playerPickupItem(player: PlayerRef, item: ItemData, entity: UUID): Boolean {
        if (!running) return false
        val cancelled = itemEvent(
            Events.PROJECTITEM_PICKUP,
            item,
            Events.PLAYER_PICKUP_ITEM,
            Events.NF_PLAYER_PICKUP_ITEM,
            player,
            PlayerPickupItemEvent(handle(player), item, LuaHandle.DroppedItem(entity.toString()))
        )
        // What they picked up may be a stack from before an item changed: it's in their inventory by the next tick.
        if (!cancelled) items.refreshSoon(player.uuid)
        return cancelled
    }

    override fun playerConsumeItem(player: PlayerRef, item: ItemData): Boolean = running &&
        itemEvent(
            Events.PROJECTITEM_CONSUME,
            item,
            Events.PLAYER_CONSUME_ITEM,
            Events.NF_PLAYER_CONSUME_ITEM,
            player,
            PlayerItemEvent(handle(player), item)
        )

    override fun worldSaving(world: String) = session.worldSaving(world)

    override fun pluginsChanged() = session.pluginsChanged()

    override fun entitiesUnloading(entities: List<UUID>) = session.entitiesUnloading(entities.toHashSet())

    override fun entityDamaged(entity: UUID, amount: Double, cause: String, attacker: UUID?): Double? {
        if (!running) return amount
        val target = session.entityHandle(entity)
        // A player's attack with a project item in hand: the item's `hit` first.
        val weapon = if (attacker != null && cause in ATTACKS) items.heldStage(Events.PROJECTITEM_HIT, attacker, MAIN_HAND) else emptyList()
        val stages = weapon + listOf(Events.ENTITY_DAMAGE to target, Events.NF_ENTITY_DAMAGE to null)
        if (stages.none { (event, stage) -> scripts.listening(stage, event) }) return amount
        val event = EntityDamageEvent(target, amount, cause, attacker?.let { session.entityHandle(it) })
        val cancelled = scripts.emit(stages, event)
        return if (cancelled) null else event.amount.coerceAtLeast(0.0)
    }

    override fun entityDied(entity: UUID, killer: UUID?, drops: () -> List<ItemData>, experience: Int): DropsAnswer {
        if (!running) return DropsAnswer(null, experience)
        // A player's own `death` is `player_death`, so a player's handle isn't a stage here (`Scripts.emit`).
        val target = session.livingHandle(entity)
        val stages = listOf(Events.LIVING_DEATH to target, Events.NF_ENTITY_DEATH to null)
        var answer = DropsAnswer(null, experience)
        if (stages.any { (event, stage) -> scripts.listening(stage, event) }) {
            val before = drops()
            val event = EntityDeathEvent(target, killer?.let { session.entityHandle(it) }, before, experience.toLong())
            scripts.emit(stages, event)
            answer = DropsAnswer(
                event.drops.takeIf { session.changed(it, before) },
                event.experience.coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
            )
        }
        if (target !is LuaHandle.Player) session.entityGone(entity)
        return answer
    }

    override fun playerInteractEntity(player: PlayerRef, entity: UUID, hand: String): Boolean {
        if (!running) return false
        val event = PlayerInteractEntityEvent(handle(player), session.entityHandle(entity), hand)
        val stages = listOf(Events.ENTITY_INTERACT to session.entityHandle(entity)) +
            items.heldStage(Events.PROJECTITEM_INTERACT_ENTITY, player.uuid, hand) +
            (Events.PLAYER_INTERACT_ENTITY to handle(player)) +
            (Events.NF_PLAYER_INTERACT_ENTITY to null)
        return scripts.emit(stages, event)
    }

    override fun resourcePackStatus(player: UUID, pack: UUID, status: String?) = runtime.packs.statusChanged(player, pack, status)

    override fun entityClicked(entity: UUID, player: PlayerRef, button: ClickButton, sight: Ray?): Boolean =
        session.centities.click(entity, player, button, sight) || session.screens.click(entity, player, button, sight)

    override fun entitiesLoaded(tagged: Map<UUID, EntityTag>, untagged: List<UUID>) = session.entitiesLoaded(tagged, untagged)

    override fun generatedLoot(table: String, player: UUID?, location: Location): List<ItemData>? {
        if (!running) return null
        val loot = session.loot
        val id = loot.idOf(table)
        if (id == null) {
            runtime.log.warn(
                "A generated container at ${location.world} ${location.x.toInt()} ${location.y.toInt()} ${location.z.toInt()} names the loot table \"$table\", which isn't running: it's left empty"
            )
            return emptyList()
        }
        return try {
            loot.roll(id, LootRoll(player = player, location = location), Random.nextLong())
        } catch (e: LuaApiException) {
            runtime.log.warn("Rolling the loot table \"$table\" for a generated container failed: ${e.message}")
            emptyList()
        }
    }

    override fun structureMarkersLoaded(markers: List<StructureMarker>) {
        if (running) session.structureMarkers(markers)
    }

    override fun menuClicked(window: UUID, click: MenuClick): Boolean = running && session.menus.click(window, click)

    override fun menuDragged(
        window: UUID,
        player: PlayerRef,
        slots: List<Int>,
        cursor: ItemData?,
        ownSlots: List<Int>,
        click: String
    ): Boolean = running && session.menus.drag(window, player, slots, cursor, ownSlots, click)

    override fun menuClosed(window: UUID, player: PlayerRef) {
        if (running) session.menus.closed(window, player)
    }

    override fun menuChanged(window: UUID, slots: Set<Int>) {
        if (running) session.menus.changed(window, slots)
    }

    override fun dialogPressed(player: PlayerRef, dialog: String, button: String, values: Map<String, Any>) {
        if (running) session.dialogs.pressed(player, dialog, button, values)
    }

    override fun dialogClosed(player: PlayerRef, dialog: String) {
        if (running) session.dialogs.closed(player, dialog)
    }

    override fun customClicked(player: PlayerRef, id: String, answers: DialogAnswers): Boolean =
        running && session.dialogs.registryClicked(player, id, answers)

    private companion object {
        /** The hand a swing and a block break are made with, in the Lua spelling. */
        const val MAIN_HAND = "main_hand"

        /** The damage causes that are a melee attack, which a held project item's `hit` hears. */
        val ATTACKS = setOf("entity_attack", "entity_sweep_attack")
    }
}
