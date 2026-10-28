package dev.netherforge.plugin.platform

import dev.netherforge.format.math.Vec3
import java.util.UUID

/**
 * Mobs' own navigation (`mob:move_to`): the path the game finds and walks
 * them along, whoever started it (a script, or the mob's AI). Everything
 * answers null or false for an entity that isn't a mob, or isn't there now.
 * Paper has no event for a path ending, so the runtime watches [hasPath]
 * each tick for the walks scripts started (`mob/MobPaths.kt`).
 */
interface PathfindingOps {
    /**
     * Finds a path to [to], in the mob's own world, and starts walking it at
     * [speed] times its walking speed, replacing the path it was on. False
     * when there's no path from where it is.
     */
    fun moveTo(id: UUID, to: Vec3, speed: Double): Boolean

    /** Stops it walking. */
    fun stop(id: UUID): Boolean

    /** Whether it's walking a path now. */
    fun hasPath(id: UUID): Boolean

    /** Where the path it's walking ends, or null when it isn't walking one. */
    fun pathEnd(id: UUID): Vec3?
}
