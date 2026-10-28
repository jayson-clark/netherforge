package dev.netherforge.plugin.screen

import dev.netherforge.plugin.platform.Platform
import java.util.UUID

/**
 * What each player is sent of the screens drawn for them, once a tick: every change marks the rectangle of each map
 * it touched ([Canvas]), each [Viewer] gathers those of the canvas it sees, and is sent each map's changed rectangle,
 * at most [ScreensConfig.bytesPerTick] a tick across every screen; what doesn't fit waits, merged with what changes
 * meanwhile, so a video someone can't keep up with skips frames for them rather than falling behind.
 */
internal class MapSending(private val platform: Platform, private val config: ScreensConfig) {
    /** Sends every viewer of [all] what changed of the screens they see, each up to the tick's budget. */
    fun flush(all: List<ScreenState>, tick: Long) {
        if (all.isEmpty()) return
        val budget = HashMap<UUID, Int>()
        // Each tick starts with another screen, so one big screen can't always have a player's budget first.
        val first = (tick % all.size).toInt()
        for (i in all.indices) {
            val screen = all[(first + i) % all.size]
            gather(screen.canvas, screen) { it !in screen.personal }
            for ((player, canvas) in screen.personal) gather(canvas, screen) { it == player }
            for (viewer in screen.viewers.values) send(screen, viewer, budget, tick)
        }
    }

    /** Hands each changed map of [canvas] to the viewers of [screen] who see it ([sees]). */
    private fun gather(canvas: Canvas, screen: ScreenState, sees: (UUID) -> Boolean) {
        if (!canvas.changed) return
        for (tile in 0 until screen.tiles) {
            val rect = canvas.take(tile) ?: continue
            for (viewer in screen.viewers.values) if (sees(viewer.player)) viewer.add(tile, rect)
        }
        canvas.settle()
    }

    private fun send(screen: ScreenState, viewer: Viewer, budget: HashMap<UUID, Int>, tick: Long) {
        var left = budget[viewer.player] ?: config.bytesPerTick
        if (left <= 0) return
        val canvas = screen.canvasFor(viewer.player)
        val pending = viewer.pending
        val start = (tick % pending.size).toInt()
        for (i in pending.indices) {
            val tile = (start + i) % pending.size
            val rect = pending[tile] ?: continue
            val cost = rect.area + ScreensConfig.PACKET_COST
            if (cost > left) break
            val sent = platform.screens.sendMap(
                viewer.player,
                screen.maps[tile],
                rect.left,
                rect.top,
                rect.width,
                rect.height,
                canvas.region(tile, rect)
            )
            if (!sent) break
            pending[tile] = null
            left -= cost
        }
        budget[viewer.player] = left
    }
}
