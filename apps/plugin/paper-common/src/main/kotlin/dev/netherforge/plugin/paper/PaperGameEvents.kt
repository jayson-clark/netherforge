package dev.netherforge.plugin.paper

import com.destroystokyo.paper.event.player.PlayerJumpEvent
import com.destroystokyo.paper.event.server.PaperServerListPingEvent
import dev.netherforge.plugin.platform.BlockRef
import dev.netherforge.plugin.platform.DEFAULT_CHAT_FORMAT
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.GameEvents
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.WatchedEvent
import io.papermc.paper.chat.ChatRenderer
import io.papermc.paper.event.player.AsyncChatEvent
import io.papermc.paper.event.player.PlayerArmSwingEvent
import io.papermc.paper.event.player.PlayerStopUsingItemEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Keyed
import org.bukkit.block.Block
import org.bukkit.block.BlockFace
import org.bukkit.block.DoubleChest
import org.bukkit.block.data.Directional
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.BlockBurnEvent
import org.bukkit.event.block.BlockDamageEvent
import org.bukkit.event.block.BlockDropItemEvent
import org.bukkit.event.block.BlockExplodeEvent
import org.bukkit.event.block.BlockFadeEvent
import org.bukkit.event.block.BlockFormEvent
import org.bukkit.event.block.BlockFromToEvent
import org.bukkit.event.block.BlockGrowEvent
import org.bukkit.event.block.BlockIgniteEvent
import org.bukkit.event.block.BlockPistonEvent
import org.bukkit.event.block.BlockPistonExtendEvent
import org.bukkit.event.block.BlockPistonRetractEvent
import org.bukkit.event.block.BlockPlaceEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.block.BlockSpreadEvent
import org.bukkit.event.block.EntityBlockFormEvent
import org.bukkit.event.block.LeavesDecayEvent
import org.bukkit.event.block.SignChangeEvent
import org.bukkit.event.enchantment.EnchantItemEvent
import org.bukkit.event.entity.EntityCombustByBlockEvent
import org.bukkit.event.entity.EntityCombustByEntityEvent
import org.bukkit.event.entity.EntityCombustEvent
import org.bukkit.event.entity.EntityDismountEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.entity.EntityMountEvent
import org.bukkit.event.entity.EntityPotionEffectEvent
import org.bukkit.event.entity.EntityRegainHealthEvent
import org.bukkit.event.entity.EntityShootBowEvent
import org.bukkit.event.entity.EntitySpawnEvent
import org.bukkit.event.entity.EntityTargetEvent
import org.bukkit.event.entity.FoodLevelChangeEvent
import org.bukkit.event.entity.ProjectileHitEvent
import org.bukkit.event.entity.ProjectileLaunchEvent
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.CraftItemEvent
import org.bukkit.event.inventory.FurnaceSmeltEvent
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryCreativeEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.event.inventory.InventoryOpenEvent
import org.bukkit.event.inventory.InventoryType
import org.bukkit.event.inventory.PrepareAnvilEvent
import org.bukkit.event.inventory.PrepareGrindstoneEvent
import org.bukkit.event.inventory.PrepareItemCraftEvent
import org.bukkit.event.inventory.PrepareSmithingEvent
import org.bukkit.event.player.PlayerAdvancementDoneEvent
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerExpChangeEvent
import org.bukkit.event.player.PlayerGameModeChangeEvent
import org.bukkit.event.player.PlayerInputEvent
import org.bukkit.event.player.PlayerItemBreakEvent
import org.bukkit.event.player.PlayerItemHeldEvent
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerKickEvent
import org.bukkit.event.player.PlayerLevelChangeEvent
import org.bukkit.event.player.PlayerMoveEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.event.player.PlayerSwapHandItemsEvent
import org.bukkit.event.player.PlayerTeleportEvent
import org.bukkit.event.player.PlayerToggleFlightEvent
import org.bukkit.event.player.PlayerToggleSneakEvent
import org.bukkit.event.player.PlayerToggleSprintEvent
import org.bukkit.event.vehicle.VehicleDamageEvent
import org.bukkit.event.vehicle.VehicleDestroyEvent
import org.bukkit.event.vehicle.VehicleMoveEvent
import org.bukkit.event.weather.LightningStrikeEvent
import org.bukkit.event.weather.ThunderChangeEvent
import org.bukkit.event.weather.WeatherChangeEvent
import org.bukkit.event.world.ChunkLoadEvent
import org.bukkit.event.world.ChunkUnloadEvent
import org.bukkit.event.world.WorldLoadEvent
import org.bukkit.event.world.WorldUnloadEvent
import org.bukkit.inventory.BlockInventoryHolder
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryView
import org.bukkit.inventory.PlayerInventory
import org.bukkit.inventory.Recipe
import org.bukkit.plugin.java.JavaPlugin
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt
import org.bukkit.Location as BukkitLocation
import org.bukkit.event.player.PlayerEvent as BukkitPlayerEvent

/**
 * The server's events scripts hear, each mapped to its generated payload
 * ([GameEvent]) and handed to [game], with what came back applied: a
 * cancelled one cancelled, a writable field a handler changed set on the
 * server's event. Cancellable events at HIGH, so protection plugins (which
 * usually decide at NORMAL or LOW) have had their say, skipping what something
 * already cancelled; what only reports at MONITOR. Entities NetherForge draws
 * centities with are never reported. A watched event's handler is
 * [Watched] in place of `@EventHandler`: [Watching] registers it only while a
 * script listens.
 */
class PaperGameEvents(private val plugin: JavaPlugin, private val platform: PaperPlatform, private val game: GameEvents) : Listener {
    private val plain = PlainTextComponentSerializer.plainText()

    // ---- A player arriving and leaving ----

    /**
     * Joins and leaves at HIGH, not MONITOR: scripts may change the message
     * everyone sees, which is applied only when they did, so an untouched
     * message keeps whatever it was built from.
     */
    @EventHandler(priority = EventPriority.HIGH)
    fun onJoin(event: PlayerJoinEvent) {
        val message = event.joinMessage()?.let(PaperText.mini::serialize)
        val join = GameEvent.PlayerJoin(ref(event), !event.player.hasPlayedBefore(), message)
        game.playerJoin(join)
        if (join.message != message) event.joinMessage(join.message?.let(PaperText.mini::deserialize))
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onQuit(event: PlayerQuitEvent) {
        val message = event.quitMessage()?.let(PaperText.mini::serialize)
        val quit = GameEvent.PlayerQuit(ref(event), message)
        game.playerQuit(quit)
        if (quit.message != message) event.quitMessage(quit.message?.let(PaperText.mini::deserialize))
    }

    // ---- Where a player is ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onTeleport(event: PlayerTeleportEvent) {
        val to = PaperPlatform.location(event.to)
        val teleport = GameEvent.PlayerTeleport(ref(event), PaperPlatform.location(event.from), to, event.cause.name.lowercase())
        when {
            game.playerTeleport(teleport) -> event.isCancelled = true
            teleport.to != to -> bukkit(teleport.to)?.let { event.setTo(it) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) {
        game.playerChangeWorld(GameEvent.PlayerChangeWorld(ref(event), event.from.name, event.player.world.name))
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onRespawn(event: PlayerRespawnEvent) {
        val at = PaperPlatform.location(event.respawnLocation)
        val respawn = GameEvent.PlayerRespawn(ref(event), at)
        game.playerRespawn(respawn)
        if (respawn.location != at) bukkit(respawn.location)?.let { event.respawnLocation = it }
    }

    /** A location scripts gave, on this server: null for a world it doesn't have. */
    private fun bukkit(location: dev.netherforge.plugin.platform.Location): BukkitLocation? {
        val world = Bukkit.getWorld(location.world) ?: return null
        return BukkitLocation(world, location.x, location.y, location.z, location.yaw.toFloat(), location.pitch.toFloat())
    }

    // ---- A player's state and input ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwapHands(event: PlayerSwapHandItemsEvent) {
        // Paper's items are where each ends up: the off hand's is what leaves the main hand.
        val swap = GameEvent.PlayerSwapHands(ref(event), PaperItems.toItem(event.offHandItem), PaperItems.toItem(event.mainHandItem), false)
        if (game.playerSwapHands(swap)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSneak(event: PlayerToggleSneakEvent) {
        game.playerSneak(GameEvent.PlayerSneak(ref(event), event.isSneaking))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        if (game.playerCommand(GameEvent.PlayerCommand(ref(event), event.message.removePrefix("/")))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onChangeSlot(event: PlayerItemHeldEvent) {
        if (game.playerChangeSlot(GameEvent.PlayerChangeSlot(ref(event), event.previousSlot, event.newSlot))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSwing(event: PlayerArmSwingEvent) {
        if (game.playerSwing(GameEvent.PlayerSwing(ref(event), hand(event.hand)))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onSprint(event: PlayerToggleSprintEvent) {
        game.playerSprint(GameEvent.PlayerSprint(ref(event), event.isSprinting))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFly(event: PlayerToggleFlightEvent) {
        if (game.playerFly(GameEvent.PlayerFly(ref(event), event.isFlying))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onJump(event: PlayerJumpEvent) {
        val jump = GameEvent.PlayerJump(ref(event), PaperPlatform.location(event.from), PaperPlatform.location(event.to))
        if (game.playerJump(jump)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onGameMode(event: PlayerGameModeChangeEvent) {
        val change = GameEvent.PlayerChangeGameMode(ref(event), event.newGameMode.name.lowercase(), event.cause.name.lowercase())
        if (game.playerChangeGameMode(change)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onFood(event: FoodLevelChangeEvent) {
        val player = event.entity as? Player ?: return
        val change = GameEvent.PlayerChangeFood(ref(player), event.foodLevel, PaperItems.toItem(event.item))
        when {
            game.playerChangeFood(change) -> event.isCancelled = true
            change.food != event.foodLevel -> event.foodLevel = change.food
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onHeal(event: EntityRegainHealthEvent) {
        if (ours(event.entity)) return
        val heal = GameEvent.EntityHeal(event.entity.uniqueId, event.amount, event.regainReason.name.lowercase())
        when {
            game.entityHeal(heal) -> event.isCancelled = true
            heal.amount != event.amount -> event.amount = heal.amount
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onExperience(event: PlayerExpChangeEvent) {
        val gain = GameEvent.PlayerGainExperience(ref(event), event.amount)
        game.playerGainExperience(gain)
        if (gain.amount != event.amount) event.amount = gain.amount
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onLevel(event: PlayerLevelChangeEvent) {
        game.playerChangeLevel(GameEvent.PlayerChangeLevel(ref(event), event.oldLevel, event.newLevel))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onKick(event: PlayerKickEvent) {
        val text = PaperText.mini.serialize(event.reason())
        val message = event.leaveMessage()?.let(PaperText.mini::serialize)?.takeIf { it.isNotEmpty() }
        val kick = GameEvent.PlayerKick(ref(event), text, message, event.cause.name.lowercase())
        if (game.playerKick(kick)) {
            event.isCancelled = true
            return
        }
        if (kick.text != text) event.reason(PaperText.mini.deserialize(kick.text))
        if (kick.message != message) event.leaveMessage(kick.message?.let(PaperText.mini::deserialize) ?: Component.empty())
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onAdvancement(event: PlayerAdvancementDoneEvent) {
        val message = event.message()?.let(PaperText.mini::serialize)
        val done = GameEvent.PlayerAdvancement(ref(event), event.advancement.key.toString(), message)
        game.playerCompleteAdvancement(done)
        if (done.message != message) event.message(done.message?.let(PaperText.mini::deserialize))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onStopUsing(event: PlayerStopUsingItemEvent) {
        val item = PaperItems.toItem(event.item) ?: return
        game.playerStopUsingItem(GameEvent.PlayerStopUsingItem(ref(event), item, event.ticksHeldFor))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onItemBreak(event: PlayerItemBreakEvent) {
        val item = PaperItems.toItem(event.brokenItem) ?: return
        game.playerBreakItem(GameEvent.PlayerItem(ref(event), item))
    }

    // ---- Combat and projectiles ----

    /**
     * An entity being added to a world. It isn't in the world's lookup yet,
     * so it's kept where [PaperWorldEntities] finds it while scripts hear it.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSpawn(event: EntitySpawnEvent) {
        val entity = event.entity
        if (ours(entity)) return
        val cause = entity.entitySpawnReason?.name?.lowercase() ?: "custom"
        if (spawning(entity) { game.entitySpawn(GameEvent.EntitySpawn(entity.uniqueId, entity.world.name, cause)) }) {
            event.isCancelled =
                true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLaunch(event: ProjectileLaunchEvent) {
        val projectile = event.entity
        val launch = GameEvent.ProjectileLaunch(projectile.uniqueId, id(projectile.shooter as? Entity))
        if (spawning(projectile) { game.projectileLaunch(launch) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onProjectileHit(event: ProjectileHitEvent) {
        val projectile = event.entity
        val hit = event.hitEntity
        if (hit != null && ours(hit)) return
        val block = event.hitBlock
        val face = if (block != null) face(event.hitBlockFace) else null
        val report = GameEvent.ProjectileHit(projectile.uniqueId, id(projectile.shooter as? Entity), id(hit), block?.let(::blockRef), face)
        if (game.projectileHit(report)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onShootBow(event: EntityShootBowEvent) {
        if (ours(event.entity)) return
        val projectile = event.projectile
        val shot = GameEvent.EntityShootBow(
            event.entity.uniqueId,
            PaperItems.toItem(event.bow),
            projectile.uniqueId,
            event.force.toDouble(),
            hand(event.hand)
        )
        if (spawning(projectile) { game.entityShootBow(shot) }) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onExplode(event: EntityExplodeEvent) {
        if (ours(event.entity)) return
        val explosion = GameEvent.Explode(
            entity = event.entity.uniqueId,
            block = null,
            state = null,
            location = PaperPlatform.location(event.location),
            blocks = { event.blockList().map(::blockRef) },
            breaksBlocks = true,
            yield = event.yield.toDouble()
        )
        if (game.entityExplode(explosion)) return run { event.isCancelled = true }
        explosion.apply(event.blockList(), event.yield) { event.yield = it }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBlockExplode(event: BlockExplodeEvent) {
        val block = blockRef(event.explodedBlockState)
        val explosion = GameEvent.Explode(
            entity = null,
            block = block,
            state = block.state,
            location = PaperPlatform.location(event.block.location.add(0.5, 0.5, 0.5)),
            blocks = { event.blockList().map(::blockRef) },
            breaksBlocks = true,
            yield = event.yield.toDouble()
        )
        if (game.blockExplode(explosion)) return run { event.isCancelled = true }
        explosion.apply(event.blockList(), event.yield) { event.yield = it }
    }

    /** Applies what scripts made of an explosion that went ahead: its blocks kept, or another yield. */
    private inline fun GameEvent.Explode.apply(blocks: MutableList<Block>, was: Float, setYield: (Float) -> Unit) {
        if (!breaksBlocks) blocks.clear()
        if (yield.toFloat() != was) setYield(yield.toFloat())
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCombust(event: EntityCombustEvent) {
        if (ours(event.entity)) return
        val ticks = (event.duration * TICKS_PER_SECOND).roundToInt()
        val source = (event as? EntityCombustByEntityEvent)?.combuster
        val block = (event as? EntityCombustByBlockEvent)?.combuster
        val combust = GameEvent.EntityCombust(event.entity.uniqueId, ticks, id(source), block?.let(::blockRef))
        when {
            game.entityCombust(combust) -> event.isCancelled = true
            combust.ticks != ticks -> event.duration = combust.ticks / TICKS_PER_SECOND
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEffect(event: EntityPotionEffectEvent) {
        if (ours(event.entity)) return
        val change = GameEvent.EntityChangeEffect(
            event.entity.uniqueId,
            event.modifiedType.key.toString(),
            event.action.name.lowercase(),
            event.cause.name.lowercase(),
            event.oldEffect?.let(::effectData),
            event.newEffect?.let(::effectData)
        )
        if (game.entityChangeEffect(change)) event.isCancelled = true
    }

    // ---- The world simulating itself ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPlace(event: BlockPlaceEvent) {
        val placed = blockRef(event.block)
        val place = GameEvent.BlockPlace(ref(event.player), placed, placed.state, blockRef(event.blockAgainst))
        if (game.blockPlace(place)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onIgnite(event: BlockIgniteEvent) {
        val ignite = GameEvent.BlockIgnite(
            blockRef(event.block),
            event.cause.name.lowercase(),
            event.player?.let(::ref),
            id(event.ignitingEntity),
            event.ignitingBlock?.let(::blockRef)
        )
        if (game.blockIgnite(ignite)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onBurn(event: BlockBurnEvent) {
        if (game.blockBurn(GameEvent.BlockBurn(blockRef(event.block), event.ignitingBlock?.let(::blockRef)))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonExtend(event: BlockPistonExtendEvent) {
        if (game.pistonExtend(piston(event, event.blocks))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onPistonRetract(event: BlockPistonRetractEvent) {
        if (game.pistonRetract(piston(event, event.blocks))) event.isCancelled = true
    }

    /** The way a piston faces, from its block: what scripts are told, whichever way it moves. */
    private fun piston(event: BlockPistonEvent, blocks: List<Block>): GameEvent.Piston {
        val facing = (event.block.blockData as? Directional)?.facing ?: event.direction
        return GameEvent.Piston(blockRef(event.block), facing.name.lowercase(), event.isSticky, blocks.map(::blockRef))
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSignChange(event: SignChangeEvent) {
        val lines = event.lines().map { it?.let(plain::serialize) ?: "" }
        val sign = GameEvent.SignChange(ref(event.player), blockRef(event.block), event.side.name.lowercase(), lines)
        if (game.signChange(sign)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onStartBreak(event: BlockDamageEvent) {
        val start = GameEvent.BlockStartBreak(
            ref(event.player),
            blockRef(event.block),
            PaperItems.toItem(event.itemInHand),
            event.instaBreak
        )
        when {
            game.blockStartBreak(start) -> event.isCancelled = true
            start.instant != event.instaBreak -> event.instaBreak = start.instant
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDropItem(event: BlockDropItemEvent) {
        val block = blockRef(event.blockState)
        val drop = GameEvent.BlockDropItem(ref(event.player), block, block.state) {
            event.items.mapNotNull { PaperItems.toItem(it.itemStack) }
        }
        if (game.blockDropItem(drop)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onWeather(event: WeatherChangeEvent) {
        val change = GameEvent.WeatherChange(event.world.name, event.toWeatherState(), event.cause.name.lowercase())
        if (game.weatherChange(change)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onThunder(event: ThunderChangeEvent) {
        val change = GameEvent.ThunderChange(event.world.name, event.toThunderState(), event.cause.name.lowercase())
        if (game.thunderChange(change)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onLightning(event: LightningStrikeEvent) {
        val bolt = event.lightning
        val strike = GameEvent.LightningStrike(
            bolt.world.name,
            PaperPlatform.location(bolt.location),
            bolt.uniqueId,
            event.cause.name.lowercase()
        )
        if (spawning(bolt) { game.lightningStrike(strike) }) event.isCancelled = true
    }

    /** While the chunk is still there to write to: blocks' script data goes into it once its scripts have had it. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun onChunkUnload(event: ChunkUnloadEvent) {
        game.chunkUnload(GameEvent.Chunk(event.world.name, event.chunk.x, event.chunk.z))
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onWorldLoad(event: WorldLoadEvent) {
        game.worldLoad(GameEvent.World(event.world.name))
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onWorldUnload(event: WorldUnloadEvent) {
        game.worldUnload(GameEvent.World(event.world.name))
    }

    // ---- Mounts and vehicles ----

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onMount(event: EntityMountEvent) {
        if (ours(event.entity) || ours(event.mount)) return
        if (game.entityMount(GameEvent.Mount(event.entity.uniqueId, event.mount.uniqueId))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDismount(event: EntityDismountEvent) {
        if (ours(event.entity) || ours(event.dismounted)) return
        if (game.entityDismount(GameEvent.Mount(event.entity.uniqueId, event.dismounted.uniqueId)) && event.isCancellable) {
            event.isCancelled = true
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVehicleDamage(event: VehicleDamageEvent) {
        if (ours(event.vehicle)) return
        val damage = GameEvent.VehicleDamage(event.vehicle.uniqueId, id(event.attacker), event.damage)
        when {
            game.vehicleDamage(damage) -> event.isCancelled = true
            damage.amount != event.damage -> event.damage = damage.amount
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onVehicleDestroy(event: VehicleDestroyEvent) {
        if (ours(event.vehicle)) return
        if (game.vehicleDestroy(GameEvent.VehicleDestroy(event.vehicle.uniqueId, id(event.attacker)))) event.isCancelled = true
    }

    // ---- Vanilla inventories ----

    /** [view]'s top inventory as scripts see it, or null for one of NetherForge's menus (those have their own events) or the player's own. */
    private fun opened(view: InventoryView, player: Player): GameEvent.PlayerInventory? {
        val top = view.topInventory
        if (platform.menus.holderOf(top) != null || top.type == InventoryType.CRAFTING) return null
        val holder = top.getHolder(false)
        val location = top.location
        val block = location?.takeIf { holder !is Entity }?.block?.let(::blockRef)
        return GameEvent.PlayerInventory(ref(player), top.type.name.lowercase(), inventoryRef(top, player), block)
    }

    /** [inventory] as an `Inventory` handle finds it again, or null for one with nothing kept in it. */
    private fun inventoryRef(inventory: Inventory, player: Player): InventoryRef? {
        val holder = inventory.getHolder(false)
        val location = inventory.location
        return when {
            inventory.type == InventoryType.ENDER_CHEST -> InventoryRef.EnderChest(player.uniqueId)
            inventory is PlayerInventory && holder is Player -> InventoryRef.Player(holder.uniqueId)
            holder is Entity && holder !is Player -> InventoryRef.Entity(holder.uniqueId)
            (holder is BlockInventoryHolder || holder is DoubleChest) && location != null ->
                InventoryRef.Block(location.world.name, location.blockX, location.blockY, location.blockZ)
            else -> null
        }
    }

    /**
     * A click in their own inventory or a vanilla one. One of NetherForge's menus open takes its clicks, those
     * below it too (`PaperEvents.onInventoryClick`); a creative player's screen sends stacks, not clicks.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onClickInventory(event: InventoryClickEvent) {
        if (event is InventoryCreativeEvent) return
        val player = event.whoClicked as? Player ?: return
        val top = event.view.topInventory
        if (platform.menus.holderOf(top) != null) return
        val clicked = event.clickedInventory
        val click = GameEvent.PlayerClickInventory(
            ref(player),
            top.type.name.lowercase(),
            clicked?.let { inventoryRef(it, player) },
            clicked is PlayerInventory && clicked.holder == player,
            event.slot.takeIf { clicked != null },
            clickName(event.click),
            event.hotbarButton.takeIf { it >= 0 },
            PaperItems.toItem(event.currentItem),
            PaperItems.toItem(event.cursor)
        )
        if (game.playerClickInventory(click)) event.isCancelled = true
    }

    /**
     * The swap-hands key over a slot with a screen open: a click, which the server never raises as a swap of hands.
     * Heard once the click's own handlers (this listener's and a project menu's) have let it through.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onSwapInInventory(event: InventoryClickEvent) {
        if (event.click != ClickType.SWAP_OFFHAND || event is InventoryCreativeEvent) return
        val player = event.whoClicked as? Player ?: return
        val swap = GameEvent.PlayerSwapHands(
            ref(player),
            PaperItems.toItem(event.currentItem),
            PaperItems.toItem(player.inventory.itemInOffHand),
            true
        )
        if (game.playerSwapHands(swap)) event.isCancelled = true
    }

    /** A drag in their own inventory or a vanilla one; one of NetherForge's menus open takes its drags (`PaperEvents.onInventoryDrag`). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onDragInventory(event: InventoryDragEvent) {
        val player = event.whoClicked as? Player ?: return
        val top = event.view.topInventory
        if (platform.menus.holderOf(top) != null) return
        val (slots, own) = dragSlots(event)
        val drag = GameEvent.PlayerDragInventory(
            ref(player),
            top.type.name.lowercase(),
            inventoryRef(top, player),
            slots,
            own,
            dragName(event.type),
            PaperItems.toItem(event.oldCursor)
        )
        if (game.playerDragInventory(drag)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onOpenInventory(event: InventoryOpenEvent) {
        val player = event.player as? Player ?: return
        val opened = opened(event.view, player) ?: return
        if (game.playerOpenInventory(opened)) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onCloseInventory(event: InventoryCloseEvent) {
        val player = event.player as? Player ?: return
        game.playerCloseInventory(opened(event.view, player) ?: return)
    }

    private fun recipe(recipe: Recipe?) = (recipe as? Keyed)?.key?.let(PaperRecipes::idOf)

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onCraft(event: CraftItemEvent) {
        val player = event.whoClicked as? Player ?: return
        val item = PaperItems.toItem(event.inventory.result) ?: return
        if (game.playerCraft(GameEvent.PlayerCraft(ref(player), recipe(event.recipe), item, event.isShiftClick))) event.isCancelled = true
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareCraft(event: PrepareItemCraftEvent) {
        val player = event.view.player as? Player ?: return
        val inventory = event.inventory
        val ingredients = inventory.matrix.withIndex().mapNotNull { (slot, stack) -> PaperItems.toItem(stack)?.let { slot to it } }.toMap()
        val result = PaperItems.toItem(inventory.result)
        val prepare = GameEvent.PlayerPrepareCraft(ref(player), recipe(event.recipe), ingredients, result, event.isRepair)
        game.playerPrepareCraft(prepare)
        if (prepare.result != result) inventory.result = prepare.result?.let(PaperItems::toStack)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareAnvil(event: PrepareAnvilEvent) {
        val player = event.view.player as? Player ?: return
        val view = event.view
        val inventory = event.inventory
        val result = PaperItems.toItem(event.result)
        val prepare = GameEvent.PlayerPrepareAnvil(
            ref(player),
            PaperItems.toItem(inventory.firstItem),
            PaperItems.toItem(inventory.secondItem),
            view.renameText?.takeIf { it.isNotEmpty() },
            result,
            view.repairCost
        )
        game.playerPrepareAnvil(prepare)
        if (prepare.result != result) event.result = prepare.result?.let(PaperItems::toStack)
        if (prepare.cost != view.repairCost) view.repairCost = prepare.cost
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareSmithing(event: PrepareSmithingEvent) {
        val player = event.view.player as? Player ?: return
        val inventory = event.inventory
        val result = PaperItems.toItem(event.result)
        val prepare = GameEvent.PlayerPrepareSmithing(
            ref(player),
            PaperItems.toItem(inventory.inputTemplate),
            PaperItems.toItem(inventory.inputEquipment),
            PaperItems.toItem(inventory.inputMineral),
            result
        )
        game.playerPrepareSmithing(prepare)
        if (prepare.result != result) event.result = prepare.result?.let(PaperItems::toStack)
    }

    @EventHandler(priority = EventPriority.HIGH)
    fun onPrepareGrindstone(event: PrepareGrindstoneEvent) {
        val player = event.view.player as? Player ?: return
        val inventory = event.inventory
        val result = PaperItems.toItem(event.result)
        val prepare = GameEvent.PlayerPrepareGrindstone(
            ref(player),
            PaperItems.toItem(inventory.upperItem),
            PaperItems.toItem(inventory.lowerItem),
            result
        )
        game.playerPrepareGrindstone(prepare)
        if (prepare.result != result) event.result = prepare.result?.let(PaperItems::toStack)
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onEnchant(event: EnchantItemEvent) {
        val item = PaperItems.toItem(event.item) ?: return
        val enchantments = event.enchantsToAdd.entries.associate { (enchantment, level) -> enchantment.key.toString() to level }
        val enchant = GameEvent.PlayerEnchantItem(
            ref(event.enchanter),
            blockRef(event.enchantBlock),
            item,
            enchantments,
            event.expLevelCost
        )
        when {
            game.playerEnchantItem(enchant) -> event.isCancelled = true
            enchant.cost != event.expLevelCost -> event.expLevelCost = enchant.cost
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    fun onSmelt(event: FurnaceSmeltEvent) {
        val source = PaperItems.toItem(event.source) ?: return
        val result = PaperItems.toItem(event.result) ?: return
        val smelt = GameEvent.FurnaceSmelt(blockRef(event.block), source, result)
        when {
            game.furnaceSmelt(smelt) -> event.isCancelled = true
            smelt.result != result -> PaperItems.toStack(smelt.result)?.let { event.result = it }
        }
    }

    // ---- What every mapping uses ----

    private fun ref(event: BukkitPlayerEvent) = platform.players.ref(event.player)

    private fun ref(player: Player) = platform.players.ref(player)

    private fun ours(entity: Entity) = platform.entities.tagOf(entity) != null

    /** [entity]'s id, or null for one of ours: scripts never see the entities centities are drawn with. */
    private fun id(entity: Entity?) = entity?.takeUnless(::ours)?.uniqueId

    /**
     * Runs [report] with [entity], which isn't in its world yet (a projectile
     * being launched, an entity spawning), where [PaperWorldEntities] finds
     * it, so its methods work for scripts.
     */
    private inline fun <T> spawning(entity: Entity, report: () -> T): T {
        val spawning = platform.worldEntities.spawning
        val fresh = spawning.put(entity.uniqueId, entity) == null
        try {
            return report()
        } finally {
            if (fresh) spawning.remove(entity.uniqueId)
        }
    }

    private fun face(face: BlockFace?) = face?.takeIf { it in FACES }?.name?.lowercase()

    private fun hand(slot: EquipmentSlot?) = if (slot == EquipmentSlot.OFF_HAND) "off_hand" else "main_hand"

    // ---- The watched events: registered only while a script listens ([Watching]) ----

    /**
     * Chat arrives off the main thread, and scripts may cancel or change it, so
     * the chat thread waits for the main thread's answer (at most
     * [CHAT_WAIT_SECONDS]; past that the line goes out as it was).
     */
    @Watched(WatchedEvent.PLAYER_CHAT, EventPriority.HIGH, ignoreCancelled = true)
    fun onChat(event: AsyncChatEvent) {
        val player = ref(event.player)
        val message = plain.serialize(event.message())
        val chat = GameEvent.PlayerChat(player, message, DEFAULT_CHAT_FORMAT)
        val cancelled = onMainThread("Chat from ${player.name} went out", CHAT_WAIT_SECONDS) { game.playerChat(chat) } ?: return
        if (cancelled) {
            event.isCancelled = true
            return
        }
        if (chat.message != message) event.message(Component.text(chat.message))
        if (chat.format != DEFAULT_CHAT_FORMAT) {
            val format = chat.format
            event.renderer(
                ChatRenderer { _, name, said, _ ->
                    PaperText.mini.deserialize(format, Placeholder.component("player", name), Placeholder.component("message", said))
                }
            )
        }
    }

    /**
     * A server list ping arrives off the main thread and scripts may change the
     * answer, so it waits for the main thread's, as chat does (at most
     * [PING_WAIT_SECONDS]; past that it's answered as it was).
     */
    @Watched(WatchedEvent.SERVER_LIST_PING, EventPriority.HIGH, ignoreCancelled = true)
    fun onPing(event: PaperServerListPingEvent) {
        val description = PaperText.mini.serialize(event.motd())
        val ping = GameEvent.ServerListPing(
            event.client.address.address.hostAddress,
            description,
            event.numPlayers,
            event.maxPlayers,
            event.shouldHidePlayers()
        )
        val cancelled = onMainThread("A server list ping was answered", PING_WAIT_SECONDS) { game.serverListPing(ping) } ?: return
        if (cancelled) {
            event.isCancelled = true
            return
        }
        if (ping.description != description) event.motd(PaperText.mini.deserialize(ping.description))
        if (ping.online != event.numPlayers) event.numPlayers = ping.online
        if (ping.maxPlayers != event.maxPlayers) event.maxPlayers = ping.maxPlayers
        if (ping.hidePlayers != event.shouldHidePlayers()) event.setHidePlayers(ping.hidePlayers)
    }

    /** [hear] on the main thread, waiting at most [seconds] for it: null, logged as [what] without scripts hearing it, past that. */
    private fun onMainThread(what: String, seconds: Long, hear: () -> Boolean): Boolean? {
        if (Bukkit.isPrimaryThread()) return hear()
        return try {
            Bukkit.getScheduler().callSyncMethod(plugin, hear).get(seconds, TimeUnit.SECONDS)
        } catch (e: Exception) {
            plugin.logger.warning("$what without scripts hearing it: ${e.javaClass.simpleName}")
            null
        }
    }

    /**
     * Moves: the server raises one for nearly every step and turn of the head of
     * every player, so only a move into another block reaches the runtime.
     * Teleports are [PlayerTeleportEvent], which has its own handler list and
     * never arrives here.
     */
    @Watched(WatchedEvent.PLAYER_MOVE, EventPriority.HIGH, ignoreCancelled = true)
    fun onMove(event: PlayerMoveEvent) {
        if (!event.hasChangedBlock()) return
        if (game.playerMove(GameEvent.PlayerMove(ref(event), PaperPlatform.location(event.from), PaperPlatform.location(event.to)))) {
            event.isCancelled = true
        }
    }

    /** A player's movement keys: every press and release of every player. */
    @Watched(WatchedEvent.PLAYER_INPUT, EventPriority.MONITOR)
    fun onInput(event: PlayerInputEvent) {
        val input = event.input
        game.playerInput(
            GameEvent.PlayerInput(
                ref(event),
                input.isForward,
                input.isBackward,
                input.isLeft,
                input.isRight,
                input.isJump,
                input.isSneak,
                input.isSprint
            )
        )
    }

    /** Mobs picking targets: every mob looks for one every few ticks. */
    @Watched(WatchedEvent.ENTITY_TARGET, EventPriority.HIGH, ignoreCancelled = true)
    fun onTarget(event: EntityTargetEvent) {
        if (ours(event.entity)) return
        if (game.entityTarget(GameEvent.EntityTarget(event.entity.uniqueId, id(event.target), event.reason.name.lowercase()))) {
            event.isCancelled =
                true
        }
    }

    @Watched(WatchedEvent.BLOCK_SPREAD, EventPriority.HIGH, ignoreCancelled = true)
    fun onSpread(event: BlockSpreadEvent) {
        val spread = GameEvent.BlockChange(blockRef(event.block), stateOf(event.newState.blockData), blockRef(event.source), null)
        if (game.blockSpread(spread)) event.isCancelled = true
    }

    @Watched(WatchedEvent.BLOCK_FLOW, EventPriority.HIGH, ignoreCancelled = true)
    fun onFlow(event: BlockFromToEvent) {
        val face = face(event.face) ?: return
        if (game.blockFlow(GameEvent.BlockFlow(blockRef(event.block), blockRef(event.toBlock), face))) event.isCancelled = true
    }

    @Watched(WatchedEvent.BLOCK_GROW, EventPriority.HIGH, ignoreCancelled = true)
    fun onGrow(event: BlockGrowEvent) {
        if (game.blockGrow(GameEvent.BlockChange(blockRef(event.block), stateOf(event.newState.blockData), null, null))) {
            event.isCancelled =
                true
        }
    }

    @Watched(WatchedEvent.BLOCK_FADE, EventPriority.HIGH, ignoreCancelled = true)
    fun onFade(event: BlockFadeEvent) {
        if (game.blockFade(GameEvent.BlockChange(blockRef(event.block), stateOf(event.newState.blockData), null, null))) {
            event.isCancelled =
                true
        }
    }

    @Watched(WatchedEvent.BLOCK_FORM, EventPriority.HIGH, ignoreCancelled = true)
    fun onForm(event: BlockFormEvent) {
        val entity = id((event as? EntityBlockFormEvent)?.entity)
        if (game.blockForm(GameEvent.BlockChange(blockRef(event.block), stateOf(event.newState.blockData), null, entity))) {
            event.isCancelled =
                true
        }
    }

    @Watched(WatchedEvent.LEAVES_DECAY, EventPriority.HIGH, ignoreCancelled = true)
    fun onDecay(event: LeavesDecayEvent) {
        if (game.leavesDecay(GameEvent.Block(blockRef(event.block)))) event.isCancelled = true
    }

    @Watched(WatchedEvent.BLOCK_REDSTONE, EventPriority.HIGH)
    fun onRedstone(event: BlockRedstoneEvent) {
        val power = GameEvent.BlockRedstone(blockRef(event.block), event.oldCurrent, event.newCurrent)
        game.blockRedstone(power)
        if (power.to != event.newCurrent) event.newCurrent = power.to
    }

    @Watched(WatchedEvent.CHUNK_LOAD, EventPriority.MONITOR)
    fun onChunkLoad(event: ChunkLoadEvent) {
        game.chunkLoad(GameEvent.ChunkLoad(event.world.name, event.chunk.x, event.chunk.z, event.isNewChunk))
    }

    /**
     * A chunk generated, heard as it's loaded the first time (`isNewChunk`): once it's whole, on the main
     * thread, with what the game's generation put in it. Its own listener, so watching it costs a cheap
     * check per chunk load and nothing more. Not at `MONITOR`, since its handlers change the world.
     */
    @Watched(WatchedEvent.CHUNK_GENERATED)
    fun onChunkGenerated(event: ChunkLoadEvent) {
        if (event.isNewChunk) game.chunkGenerated(GameEvent.Chunk(event.world.name, event.chunk.x, event.chunk.z))
    }

    /** Boats and minecarts moving: only a move into another block reaches the runtime. */
    @Watched(WatchedEvent.VEHICLE_MOVE, EventPriority.MONITOR)
    fun onVehicleMove(event: VehicleMoveEvent) {
        val from = event.from
        val to = event.to
        if (from.blockX == to.blockX && from.blockY == to.blockY && from.blockZ == to.blockZ && from.world == to.world) return
        if (ours(event.vehicle)) return
        game.vehicleMove(GameEvent.VehicleMove(event.vehicle.uniqueId, PaperPlatform.location(from), PaperPlatform.location(to)))
    }

    private companion object {
        const val TICKS_PER_SECOND = 20f

        /** How long a chat line waits for the main thread's scripts before going out as it was. */
        const val CHAT_WAIT_SECONDS = 5L

        /** How long a ping waits for the main thread's scripts before it's answered as it was. */
        const val PING_WAIT_SECONDS = 2L
    }
}

/** The faces a block event can be towards; Paper's `BlockFace` has diagonals too. */
internal val FACES = setOf(BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST)

/** A block as events carry it: `id` namespaced, `state` the full canonical block state. */
internal fun blockRef(block: Block) = BlockRef(
    block.world.name,
    block.x,
    block.y,
    block.z,
    block.type.key.toString(),
    stateOf(block.blockData)
)

/** A block as it was ([state] is a snapshot: a block that has gone already). */
internal fun blockRef(state: org.bukkit.block.BlockState) = BlockRef(
    state.world.name,
    state.x,
    state.y,
    state.z,
    state.type.key.toString(),
    stateOf(state.blockData)
)
