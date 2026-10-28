package dev.netherforge.plugin.paper

import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.sky.SkyNight
import dev.netherforge.plugin.platform.BotOps
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.ItemData
import dev.netherforge.plugin.platform.ItemLook
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.PackOffer
import dev.netherforge.plugin.platform.PauseOps
import dev.netherforge.plugin.platform.PerformanceOps
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.PlatformEvents
import dev.netherforge.plugin.platform.PlatformLog
import dev.netherforge.plugin.platform.PlayerOps
import dev.netherforge.plugin.platform.PlayerRef
import dev.netherforge.plugin.platform.ProjectItemOps
import dev.netherforge.plugin.platform.Ray
import dev.netherforge.plugin.platform.ResourcePackOps
import dev.netherforge.plugin.platform.ServerInfo
import dev.netherforge.plugin.platform.TextOps
import dev.netherforge.plugin.platform.WatchedEvent
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.title.Title
import org.bukkit.Bukkit
import org.bukkit.GameMode
import org.bukkit.entity.Player
import org.bukkit.plugin.java.JavaPlugin
import java.time.Duration
import java.util.UUID
import java.util.logging.Level
import org.bukkit.Location as BukkitLocation

/**
 * [Platform] on Paper's API, for every supported Minecraft version: what a
 * version does its own way, and what reaches into the server itself, is
 * [version] (the adapter's own).
 */
class PaperPlatform(private val plugin: JavaPlugin, private val version: PaperVersion) : Platform {
    override val info = ServerInfo(
        minecraftVersion = Bukkit.getMinecraftVersion(),
        pluginVersion = plugin.pluginMeta.version,
        // The build writes the adapter's version into api-version (build.gradle.kts).
        supportedTargets = listOfNotNull(plugin.pluginMeta.apiVersion)
    )

    private val gameData = PaperGameData(version.registries, version::mapColors)
    override val game get() = gameData

    override val log = object : PlatformLog {
        override fun info(message: String) = plugin.logger.info(message)

        override fun warn(message: String) = plugin.logger.warning(message)

        override fun error(message: String, cause: Throwable?) = plugin.logger.log(Level.SEVERE, message, cause)
    }

    override val worlds = PaperWorlds(plugin)

    /** The vanilla note blocks' part, taken over once the project has blocks that are held as note block states. */
    private val noteBlocks = PaperNoteBlocks(plugin, version)
    private val carriers = PaperCarriers(plugin, version, noteBlocks)
    override val blocks = PaperBlocks(plugin, carriers, version)
    override val particles = PaperParticles()
    override val sounds = PaperSounds()

    override val entities = PaperEntities(plugin)
    override val screens = PaperScreens(entities, version)
    override val worldEntities = PaperWorldEntities(plugin, entities)
    override val mannequins = PaperMannequins(worldEntities)
    override val inventories = PaperInventories(worldEntities)
    override val attributes = PaperAttributes(worldEntities)
    override val pathfinding = PaperPathfinding(worldEntities)
    override val mobGoals = PaperMobGoals(plugin, worldEntities, version)
    override val bossBars = PaperBossBars()
    override val sidebars = PaperSidebars(plugin)
    override val teams = PaperTeams(sidebars)
    override val playerList = PaperPlayerList()
    override val players = Players()
    override val commands = PaperCommands(plugin)
    override val worldManager = PaperWorldManager(plugin, version)
    override val borders = PaperBorders(plugin)
    override val structures = PaperStructures(entities)
    override val playerViews = PaperPlayerViews(worldEntities)
    override val serverAdmin = PaperServerAdmin()
    override val permissions = PaperPermissions(plugin)
    override val advancements = PaperAdvancements()
    override val datapacks = PaperDatapacks()

    override val skies = PaperSkies(plugin, version)
    override val plugins = PaperPlugins()

    // Made when first used, which the runtime does only once PlaceholderAPI is enabled: the class names its types.
    override val placeholders by lazy { PaperPlaceholders(plugin) }

    override fun economy() = PaperEconomy.lookup(plugins)

    override val pause = object : PauseOps {
        override fun hold(message: String) {
            // The watchdog is the server's own (WatchdogThread, past the API): the adapter's to reach.
            version.holdWatchdog()
            // Any packet keeps a client's connection from timing out; this one also says why nothing moves.
            val text = Component.text(message)
            for (player in Bukkit.getOnlinePlayers()) player.sendActionBar(text)
        }
    }

    override val performance = object : PerformanceOps {
        override fun ticksPerSecond(): Double = Bukkit.getTPS()[0]

        override fun tickMilliseconds(): Double = Bukkit.getAverageTickTime()
    }

    override val text = object : TextOps {
        override fun escape(text: String): String = PaperText.mini.escapeTags(text)

        override fun strip(miniMessage: String): String = PaperText.mini.stripTags(miniMessage)
    }
    override val menus = PaperMenus(plugin, version)
    override val dialogs = PaperDialogs(plugin, plugin.logger)

    private var events: PaperEvents? = null

    override val projectItems = object : ProjectItemOps {
        override fun refresh(inventory: InventoryRef): Int {
            // A player's storages are read again, restyled as their inventory is, when next asked for.
            if (inventory is InventoryRef.Player) PaperStorage.forget(inventory.player)
            val found = inventories.inventory(inventory) ?: return 0
            var changed = 0
            for (slot in 0 until found.size) {
                val restyled = found.getItem(slot)?.let(PaperItems::restyled) ?: continue
                found.setItem(slot, restyled)
                changed++
            }
            return changed
        }
    }

    override val itemViews = PaperItemViews(version)

    override val recipes = PaperRecipes(version)
    override val loot = PaperLoot()

    override fun bindText(space: (px: Int) -> String?, layout: (text: String) -> String) {
        PaperText.spaces = space
        PaperText.layout = layout
    }

    override fun bind(
        events: PlatformEvents,
        glyphs: (reference: String) -> String?,
        items: (reference: String) -> ItemLook?,
        blocks: (name: String) -> String?,
        namespace: () -> String
    ) {
        PaperText.glyphs = glyphs
        PaperItems.looks = items
        PaperItems.render = events::itemRendered
        PaperItems.forgetReads()
        PaperItems.blockStates = blocks
        PaperItems.namespace = namespace
        plugin.server.pluginManager.registerEvents(recipes, plugin)
        itemViews.events = events
        plugin.server.pluginManager.registerEvents(itemViews, plugin)
        itemViews.start()
        dialogs.events = events
        menus.events = events
        this.blocks.gameBlocks.events = events
        this.events = PaperEvents(plugin, this, events, version).also { plugin.server.pluginManager.registerEvents(it, plugin) }
        plugin.server.pluginManager.registerEvents(borders, plugin)
        plugin.server.pluginManager.registerEvents(noteBlocks, plugin)
        plugin.server.pluginManager.registerEvents(PaperGeneratedLoot(worldManager.generators, events, plugin.logger), plugin)
        plugin.server.pluginManager.registerEvents(locate, plugin)
        plugin.server.pluginManager.registerEvents(skies, plugin)
        // The clocks of skies held at night run from the start unless stopped, and a player is told what time they are.
        skies.stopClocks(PaperDatapacks.built.keys.mapNotNull(SkyNight::clockIn))
    }

    /** `/locate biome` in a project terrain's world, off the main thread. */
    private val locate by lazy { PaperLocate(plugin, worldManager.generators) }

    /** The plugin is being disabled: what it changed in the server's own settings is put back, and its searches stop. */
    fun close() {
        itemViews.stop()
        carriers.release()
        blocks.gameBlocks.release()
        locate.close()
    }

    override fun watch(event: WatchedEvent, listening: Boolean) {
        events?.watch(event, listening)
    }

    /** Made by the version: no API makes a player without a client, so they reach into the server itself. */
    override val bots: BotOps = version.bots(plugin)

    override val resourcePacks = object : ResourcePackOps {
        private val miniMessage = PaperText.mini

        override fun send(player: UUID, pack: PackOffer): Boolean {
            val who = Bukkit.getPlayer(player) ?: return false
            val hash = pack.sha1.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            who.setResourcePack(pack.id, pack.url, hash, pack.prompt?.let(miniMessage::deserialize), pack.required)
            return true
        }
    }

    override fun exportGameData(): GameDataBundle = gameData.export()

    override fun shutdownServer(reason: String) {
        plugin.logger.info("Shutting down: $reason")
        Bukkit.shutdown()
    }

    inner class Players : PlayerOps {
        private val miniMessage = PaperText.mini

        private fun player(uuid: UUID): Player? = Bukkit.getPlayer(uuid)

        fun ref(player: Player) = PlayerRef(player.uniqueId, player.name)

        override fun online(): List<PlayerRef> = Bukkit.getOnlinePlayers().map(::ref)

        override fun find(nameOrUuid: String): PlayerRef? {
            val byId = runCatching { UUID.fromString(nameOrUuid) }.getOrNull()?.let { Bukkit.getPlayer(it) }
            return (byId ?: Bukkit.getPlayerExact(nameOrUuid) ?: Bukkit.getOnlinePlayers().firstOrNull { it.name.equals(nameOrUuid, true) })
                ?.let(::ref)
        }

        override fun known(nameOrUuid: String): PlayerRef? {
            find(nameOrUuid)?.let { return it }
            val id = runCatching { UUID.fromString(nameOrUuid) }.getOrNull()
            // Only the server's own records: never a lookup on Mojang's servers, which would block the main thread.
            val offline = if (id != null) Bukkit.getOfflinePlayer(id) else Bukkit.getOfflinePlayerIfCached(nameOrUuid)
            if (offline == null || !offline.hasPlayedBefore()) return null
            return PlayerRef(offline.uniqueId, offline.name ?: return null)
        }

        override fun get(uuid: UUID): PlayerRef? = player(uuid)?.let(::ref)

        override fun location(uuid: UUID): Location? = player(uuid)?.location?.let(::location)

        override fun eye(uuid: UUID): Ray? = player(uuid)?.let(::sight)

        override fun message(uuid: UUID, miniMessage: String): Boolean {
            val player = player(uuid) ?: return false
            player.sendMessage(this.miniMessage.deserialize(miniMessage))
            return true
        }

        override fun teleport(uuid: UUID, to: Location): Boolean {
            val player = player(uuid) ?: return false
            val world = Bukkit.getWorld(to.world) ?: return false
            return player.teleport(BukkitLocation(world, to.x, to.y, to.z, to.yaw.toFloat(), to.pitch.toFloat()))
        }

        override fun runCommand(uuid: UUID, line: String): Boolean = player(uuid)?.let { PaperCommands.dispatch(it, line, plugin) } ?: false

        override fun hasPermission(uuid: UUID, permission: String): Boolean = player(uuid)?.hasPermission(permission) ?: false

        override fun isOperator(uuid: UUID): Boolean = Bukkit.getOfflinePlayer(uuid).isOp

        override fun displayName(uuid: UUID): String? = player(uuid)?.displayName()?.let(miniMessage::serialize)

        override fun setDisplayName(uuid: UUID, miniMessage: String): Boolean {
            player(uuid)?.displayName(this.miniMessage.deserialize(miniMessage)) ?: return false
            return true
        }

        override fun actionbar(uuid: UUID, miniMessage: String): Boolean {
            player(uuid)?.sendActionBar(this.miniMessage.deserialize(miniMessage)) ?: return false
            return true
        }

        override fun title(uuid: UUID, title: String, subtitle: String, fadeIn: Int, stay: Int, fadeOut: Int): Boolean {
            val player = player(uuid) ?: return false
            fun ticks(n: Int) = Duration.ofMillis(n * 50L)
            player.showTitle(
                Title.title(
                    this.miniMessage.deserialize(title),
                    this.miniMessage.deserialize(subtitle),
                    Title.Times.times(ticks(fadeIn), ticks(stay), ticks(fadeOut))
                )
            )
            return true
        }

        override fun clearTitle(uuid: UUID): Boolean {
            player(uuid)?.clearTitle() ?: return false
            return true
        }

        override fun gameMode(uuid: UUID): String? = player(uuid)?.gameMode?.name?.lowercase()

        override fun setGameMode(uuid: UUID, gameMode: String): Boolean {
            player(uuid)?.gameMode = GameMode.valueOf(gameMode.uppercase())
            return player(uuid) != null
        }

        override fun giveExperience(uuid: UUID, points: Int): Boolean {
            player(uuid)?.giveExp(points) ?: return false
            return true
        }

        override fun locale(uuid: UUID): String? = player(uuid)?.locale()?.toString()?.lowercase()

        override fun kick(uuid: UUID, reason: String?): Boolean {
            val player = player(uuid) ?: return false
            player.kick(reason?.let(this.miniMessage::deserialize))
            return true
        }

        override fun cooldown(uuid: UUID, key: String): Int? = player(uuid)?.getCooldown(Key.key(key))

        override fun setCooldown(uuid: UUID, key: String, ticks: Int): Boolean {
            player(uuid)?.setCooldown(Key.key(key), ticks) ?: return false
            return true
        }

        override fun tabText(uuid: UUID, footer: Boolean): String? {
            val player = player(uuid) ?: return null
            val text = if (footer) player.playerListFooter() else player.playerListHeader()
            return text?.let(miniMessage::serialize)?.takeIf { it.isNotEmpty() }
        }

        override fun setTabText(uuid: UUID, footer: Boolean, miniMessage: String): Boolean {
            val player = player(uuid) ?: return false
            val text = this.miniMessage.deserialize(miniMessage)
            if (footer) player.sendPlayerListFooter(text) else player.sendPlayerListHeader(text)
            return true
        }

        override fun closeInventory(uuid: UUID): Boolean {
            player(uuid)?.closeInventory() ?: return false
            return true
        }

        override fun cursor(uuid: UUID): ItemData? = player(uuid)?.itemOnCursor?.let(PaperItems::toItem)

        override fun setCursor(uuid: UUID, item: ItemData?): Boolean {
            val player = player(uuid) ?: return false
            val stack = if (item == null) null else PaperItems.toStack(item) ?: return false
            player.setItemOnCursor(stack)
            return true
        }

        override fun broadcast(miniMessage: String) {
            Bukkit.broadcast(this.miniMessage.deserialize(miniMessage))
        }

        override fun messageConsole(miniMessage: String) {
            Bukkit.getConsoleSender().sendMessage(this.miniMessage.deserialize(miniMessage))
        }
    }

    companion object {
        fun location(location: BukkitLocation) = Location(
            location.world.name,
            location.x,
            location.y,
            location.z,
            location.yaw.toDouble(),
            location.pitch.toDouble()
        )

        fun sight(player: Player): Ray {
            val eye = player.eyeLocation
            val direction = eye.direction
            return Ray(eye.world.name, eye.x, eye.y, eye.z, direction.x, direction.y, direction.z)
        }
    }
}
