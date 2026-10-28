package dev.netherforge.plugin.paper

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.event.HoverEvent
import net.kyori.adventure.text.format.NamedTextColor
import org.bukkit.Bukkit
import org.bukkit.NamespacedKey
import org.bukkit.Registry
import org.bukkit.block.Biome
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerCommandPreprocessEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.sqrt

/**
 * A player's `/locate biome <biome>` in a world a project terrain generates is looked for here instead of by the
 * game: the game asks every place on the main thread, and a terrain's Lua stages can take minutes over the
 * hundreds of thousands it asks, which the watchdog takes for a hung server. This looks at the same places in the
 * same order ([PaperWorldGenerators.Locator]) on threads of its own and answers as the game does, with its own
 * messages. One search a player at a time: a new one stops the last. A biome tag, the console, a command block or
 * `/execute` is still the game's.
 */
class PaperLocate(private val plugin: JavaPlugin, private val generators: PaperWorldGenerators) : Listener {
    private val threads = maxOf(1, Runtime.getRuntime().availableProcessors() - 1)
    private val count = AtomicInteger()

    /** The threads columns are looked at on; a search's own (which waits for them) isn't one. */
    private val pool: ExecutorService = Executors.newFixedThreadPool(threads) { task ->
        Thread(task, "NetherForge locate ${count.incrementAndGet()}").apply { isDaemon = true }
    }

    private val searches: ExecutorService = Executors.newCachedThreadPool { task ->
        Thread(task, "NetherForge locate search").apply { isDaemon = true }
    }

    /** Each player's search, stopped by setting it. */
    private val running = ConcurrentHashMap<UUID, AtomicBoolean>()

    // After scripts (which listen at HIGH) had their say, and only if they let it through.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun onCommand(event: PlayerCommandPreprocessEvent) {
        val parts = event.message.removePrefix("/").trim().split(WHITESPACE)
        if (parts.size != 3 || parts[0].lowercase() !in LABELS || parts[1] != "biome" || parts[2].startsWith("#")) return
        val player = event.player
        if (!player.hasPermission(PERMISSION)) return
        // A biome the server doesn't have is the game's to say so.
        val key = NamespacedKey.fromString(parts[2].lowercase()) ?: return
        val biome = Registry.BIOME.get(key) ?: return
        val locator = generators.locator(player.world, biome) ?: return
        event.isCancelled = true
        val stop = AtomicBoolean()
        running.put(player.uniqueId, stop)?.set(true)
        val from = player.location
        val x = from.blockX
        val y = from.blockY
        val z = from.blockZ
        player.sendMessage(
            Component.translatable("commands.locate.biome.searching", "Looking for %s...", name(biome)).color(NamedTextColor.GRAY)
        )
        val started = System.nanoTime()
        searches.execute {
            val result = try {
                locator.search(x, y, z, pool, threads) { stop.get() || !plugin.isEnabled }
            } catch (e: Exception) {
                plugin.logger.warning("Locating ${key.asString()} failed: $e")
                null
            }
            val millis = (System.nanoTime() - started) / 1_000_000
            if (!plugin.isEnabled) return@execute
            Bukkit.getScheduler().runTask(plugin, Runnable { answer(player.uniqueId, stop, biome, key, x, y, z, result, millis) })
        }
    }

    private fun answer(
        id: UUID,
        stop: AtomicBoolean,
        biome: Biome,
        key: NamespacedKey,
        x: Int,
        y: Int,
        z: Int,
        result: PaperWorldGenerators.Located?,
        millis: Long
    ) {
        running.remove(id, stop)
        val player = Bukkit.getPlayer(id) ?: return
        when {
            result == null -> player.sendMessage(
                Component.text("Couldn't look for that biome: see the server's log").color(NamedTextColor.RED)
            )
            result.stopped -> if (!stop.get()) player.sendMessage(stopped(biome))
            result.at == null -> player.sendMessage(
                Component.translatable("commands.locate.biome.not_found", name(biome)).color(NamedTextColor.RED)
            )
            else -> {
                val (fx, fy, fz) = result.at
                plugin.logger.info("Locating element ${key.asString()} took $millis ms")
                val dx = (fx - x).toDouble()
                val dy = (fy - y).toDouble()
                val dz = (fz - z).toDouble()
                val distance = sqrt(dx * dx + dy * dy + dz * dz).toInt()
                val coordinates = Component.translatable(
                    "chat.square_brackets",
                    Component.translatable("chat.coordinates", Component.text(fx), Component.text(fy), Component.text(fz))
                )
                    .color(NamedTextColor.GREEN)
                    .clickEvent(ClickEvent.suggestCommand("/tp @s $fx $fy $fz"))
                    .hoverEvent(HoverEvent.showText(Component.translatable("chat.coordinates.tooltip")))
                player.sendMessage(
                    Component.translatable("commands.locate.biome.success", name(biome), coordinates, Component.text(distance))
                )
            }
        }
    }

    /** A search a reload ended (the terrain it asked was replaced): it's for the player to ask again. */
    private fun stopped(biome: Biome) = Component.text(
        "The terrain changed while looking for "
    ).append(name(biome)).append(Component.text(": try again")).color(NamedTextColor.RED)

    private fun name(biome: Biome): Component = Component.translatable(biome.translationKey())

    @EventHandler
    fun onQuit(event: PlayerQuitEvent) {
        running.remove(event.player.uniqueId)?.set(true)
    }

    /** The plugin is being disabled: searches stop, and their threads go. */
    fun close() {
        for (stop in running.values) stop.set(true)
        running.clear()
        searches.shutdownNow()
        pool.shutdownNow()
        pool.awaitTermination(5, TimeUnit.SECONDS)
    }

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val LABELS = setOf("locate", "minecraft:locate")

        /** What the game's `/locate` asks of a player. */
        const val PERMISSION = "minecraft.command.locate"
    }
}
