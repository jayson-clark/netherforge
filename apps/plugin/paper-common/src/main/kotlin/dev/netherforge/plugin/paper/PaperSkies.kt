package dev.netherforge.plugin.paper

import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.sky.SkyTwins
import dev.netherforge.plugin.platform.SkyOps
import dev.netherforge.plugin.platform.SkyView
import io.papermc.paper.event.packet.PlayerChunkLoadEvent
import io.papermc.paper.event.world.WorldGameRuleChangeEvent
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.plugin.Plugin
import java.util.UUID

/**
 * [SkyOps] on Paper: a player under a sky is sent each chunk they have again with its biomes as the sky's twins,
 * and so is every chunk they're sent after (the game's chunk-biomes packet, right after the chunk's own, which the
 * client draws from then on). Showing the world's own biomes again is the same with nothing swapped.
 *
 * A sky held at night has a world clock too ([SkyView.clock]), which the server keeps stopped at 0 ([stopClocks]):
 * the player is told it's at their step, and at 0 again when they're shown anything else. The server tells a player
 * every clock's time again as they respawn or change worlds, and everyone as the `advance_time` rule changes, so
 * each is told theirs again a tick after.
 */
class PaperSkies(private val plugin: Plugin, private val version: PaperVersion) :
    SkyOps,
    Listener {
    private val views = HashMap<UUID, SkyView>()

    override fun show(player: UUID, sky: SkyView?) {
        val online = Bukkit.getPlayer(player) ?: return
        val was = if (sky == null) views.remove(player) ?: return else views.put(player, sky)
        val chunks = online.sentChunkKeys.map { key -> (key and 0xffffffffL).toInt() to (key ushr 32).toInt() }
        version.sendBiomes(online, chunks, twinOf(sky))
        was?.clock?.takeIf { it != sky?.clock }?.let { version.sendClock(online, it, 0) }
        sky?.clock?.let { version.sendClock(online, it, sky.step.toLong()) }
    }

    /** Stops the clocks of the skies held at night the server started with, by key: what each player is then told otherwise. */
    fun stopClocks(clocks: Collection<String>) {
        if (clocks.isNotEmpty()) version.stopClocks(clocks)
    }

    /** Tells [player] their sky's clock again, a tick after the server told them every clock's own. */
    private fun retell(player: Player) {
        Bukkit.getScheduler().runTask(plugin) { _ ->
            val view = views[player.uniqueId] ?: return@runTask
            if (player.isOnline) view.clock?.let { version.sendClock(player, it, view.step.toLong()) }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun respawned(event: PlayerRespawnEvent) = retell(event.player)

    @EventHandler(priority = EventPriority.MONITOR)
    fun changedWorld(event: PlayerChangedWorldEvent) = retell(event.player)

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun gameRule(event: WorldGameRuleChangeEvent) {
        for (player in Bukkit.getOnlinePlayers()) retell(player)
    }

    /** A chunk sent to a player under a sky: its biomes again, as the sky's. */
    @EventHandler(priority = EventPriority.MONITOR)
    fun loaded(event: PlayerChunkLoadEvent) {
        val view = views[event.player.uniqueId] ?: return
        version.sendBiomes(event.player, listOf(event.chunk.x to event.chunk.z), twinOf(view))
    }

    @EventHandler
    fun quit(event: PlayerQuitEvent) {
        views.remove(event.player.uniqueId)
    }

    private fun twinOf(view: SkyView?): (String) -> String? {
        if (view == null) return { null }
        val sky = ResourceKey.parse(view.sky) ?: return { null }
        return { biome -> ResourceKey.parse(biome)?.let { SkyTwins.keyOf(view.home, sky, view.step, it).toString() } }
    }
}
