package dev.netherforge.plugin.screen
import dev.netherforge.format.screen.MapScreen

/**
 * `screens:` in `config.yml`, read on dev servers too: at most [bytesPerTick] of screens' pixels go to each player a
 * tick (a whole map is 16 KiB and a packet's worth more). What doesn't fit waits for the next tick, merged with what changes meanwhile, so a
 * video someone can't keep up with skips frames for them rather than falling behind.
 */
data class ScreensConfig(val bytesPerTick: Int = 64 * 1024) {
    companion object {
        /** What a map packet costs besides its pixels, against a player's budget. */
        const val PACKET_COST = 32

        /** A whole map, packet and all, has to fit in a tick: anything less and a new viewer would never get one. */
        const val MIN_BYTES_PER_TICK = MapScreen.TILE * MapScreen.TILE + PACKET_COST
        const val MAX_BYTES_PER_TICK = 4 * 1024 * 1024
    }
}
