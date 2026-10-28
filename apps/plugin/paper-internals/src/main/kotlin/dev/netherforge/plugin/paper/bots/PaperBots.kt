package dev.netherforge.plugin.paper.bots

import com.google.common.collect.ImmutableMultimap
import com.mojang.authlib.GameProfile
import com.mojang.authlib.properties.Property
import com.mojang.authlib.properties.PropertyMap
import dev.netherforge.format.bridge.BotActResult
import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotEvents
import dev.netherforge.format.bridge.BotInfo
import dev.netherforge.format.bridge.BotState
import dev.netherforge.plugin.platform.BotAim
import dev.netherforge.plugin.platform.BotJoin
import dev.netherforge.plugin.platform.BotOps
import dev.netherforge.plugin.platform.ScriptBot
import net.minecraft.core.UUIDUtil
import net.minecraft.server.MinecraftServer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.metadata.FixedMetadataValue
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.scheduler.BukkitTask
import java.util.UUID
import java.util.logging.Level

/**
 * The server's bots ([Bot]): made on request, ticked once a server tick
 * while there are any, forgotten when they're gone. The part of the adapter
 * that reaches furthest past the Paper API into the server itself, since no
 * API makes a player without a client.
 *
 * A script's bot ([ScriptBot]) is made a new player each time, and is set up
 * as one when the server places it ([placed]), before any other plugin hears
 * it join.
 */
class PaperBots(private val plugin: JavaPlugin) : BotOps {
    private val bots = LinkedHashMap<String, Bot>()
    private var ticker: BukkitTask? = null

    /** The script bots' UUIDs, from asking to join until they've left. */
    private val scripted = HashSet<UUID>()

    private val listener = object : Listener {
        /** First of anyone: other plugins hear a script's bot join already marked as an NPC. */
        @EventHandler(priority = EventPriority.LOWEST)
        fun onJoin(event: PlayerJoinEvent) {
            if (event.player.uniqueId in scripted) placed(event.player)
        }

        @EventHandler(priority = EventPriority.MONITOR)
        fun onQuit(event: PlayerQuitEvent) {
            if (scripted.remove(event.player.uniqueId)) event.player.removeMetadata(NPC, plugin)
        }
    }
    private var listening = false

    override fun join(join: BotJoin, done: (Result<BotInfo>) -> Unit) {
        val name = join.name
        require(NAME.matches(name)) { "a bot's name is 3 to 16 letters, digits or _, not \"$name\"" }
        require(Bukkit.getPlayerExact(name) == null && key(name) !in bots) { "$name is already online" }
        val location = join.at?.let {
            val world = it.world?.let { world -> Bukkit.getWorld(world) ?: throw IllegalArgumentException("no world \"$world\"") }
                ?: Bukkit.getWorlds().first()
            Location(world, it.x, it.y, it.z, (it.yaw ?: 0.0).toFloat(), (it.pitch ?: 0.0).toFloat())
        }
        val profile = profile(name, join.script)
        if (join.script != null) {
            if (!listening) {
                plugin.server.pluginManager.registerEvents(listener, plugin)
                listening = true
            }
            scripted += profile.id()
        }
        val bot = Bot(profile, MinecraftServer.getServer(), plugin, join.pack, location) { joined ->
            if (joined.isFailure) scripted -= profile.id()
            done(joined)
        }
        bots[key(name)] = bot
        if (ticker == null) ticker = Bukkit.getScheduler().runTaskTimer(plugin, Runnable { tick() }, 1L, 1L)
        bot.start()
    }

    /**
     * The bridge's bot is the same player every time (an offline-mode UUID from
     * its name, saved like anyone's); a script's is a new one, wearing its skin.
     */
    private fun profile(name: String, script: ScriptBot?): GameProfile {
        if (script == null) return GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name)
        val skin = script.skin ?: return GameProfile(UUID.randomUUID(), name)
        val textures = ImmutableMultimap.of(TEXTURES, Property(TEXTURES, skin.value, skin.signature))
        return GameProfile(UUID.randomUUID(), name, PropertyMap(textures))
    }

    /** A script's bot, just placed in the world: never saved, an NPC to other plugins, loading little. */
    private fun placed(player: org.bukkit.entity.Player) {
        player.isPersistent = false
        player.setMetadata(NPC, FixedMetadataValue(plugin, true))
        player.viewDistance = ScriptBot.VIEW_DISTANCE
    }

    private fun tick() {
        for (bot in bots.values.toList()) {
            try {
                bot.tick()
            } catch (e: Exception) {
                plugin.logger.log(Level.SEVERE, "Bot ${bot.name} failed its tick; it leaves", e)
                runCatching { bot.leave() }
            }
            if (bot.gone) bots.remove(key(bot.name))
        }
        if (bots.isEmpty()) {
            ticker?.cancel()
            ticker = null
        }
    }

    override fun leave(name: String) {
        bot(name).leave()
        bots.remove(key(name))
    }

    override fun leaveAll() {
        for (bot in bots.values.toList()) runCatching { bot.leave() }
        bots.clear()
        ticker?.cancel()
        ticker = null
        if (listening) {
            HandlerList.unregisterAll(listener)
            listening = false
        }
        scripted.clear()
    }

    override fun list(): List<BotInfo> = bots.values.filter { it.joined }.map { it.info() }

    override fun isBot(id: UUID): Boolean = bots.values.any { it.uuid == id && !it.gone }

    override fun act(name: String, action: BotAction, aim: BotAim?, done: (Result<BotActResult>) -> Unit) = bot(name).act(action, aim, done)

    override fun state(name: String): BotState = bot(name).state()

    override fun events(name: String, since: Int): BotEvents = bot(name).events(since)

    private fun bot(name: String): Bot = bots[key(name)]?.takeUnless { it.gone } ?: throw IllegalArgumentException("no bot named \"$name\"")

    private fun key(name: String) = name.lowercase()

    private companion object {
        val NAME = Regex("[A-Za-z0-9_]{3,16}")

        /** The profile property a skin is: what Mojang's session servers sign. */
        const val TEXTURES = "textures"

        /** The metadata Citizens put on its NPCs, which plugins check to leave fake players alone. */
        const val NPC = "NPC"
    }
}
