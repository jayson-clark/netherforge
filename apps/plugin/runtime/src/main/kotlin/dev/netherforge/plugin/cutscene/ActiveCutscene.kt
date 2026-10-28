package dev.netherforge.plugin.cutscene

import dev.netherforge.format.cutscene.CompiledCutscene
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.platform.Location
import java.util.UUID

/** Why a cutscene ended, as `CutsceneEndEvent.reason` says it. */
enum class EndReason(val luaName: String) {
    FINISHED("finished"),
    STOPPED("stopped"),
    SKIPPED("skipped"),
    REPLACED("replaced"),
    PLAYER_LEFT("player_left"),
    UNLOADED("unloaded")
}

/**
 * What a cutscene changes about a player and puts back when it ends: where
 * they were, their game mode and what they could do with it. (What they
 * were riding isn't put back: they're dismounted for the camera.)
 */
class PlayerState(
    val location: Location,
    val gameMode: String,
    val canFly: Boolean,
    val flying: Boolean,
    /** The entity they were spectating through, when they were in spectator mode and looking through one. */
    val spectating: UUID?
)

/** One cutscene playing for one player. */
class ActiveCutscene internal constructor(
    /** The handle's key: unique for the runtime's life, never reused. */
    val number: Long,
    /** The cutscene's id (its file under `cutscenes/`), as the project that defined it names it. */
    val kind: String,
    /** The scope that played it, or null for one the runtime played. */
    val owner: Int?,
    val player: UUID,
    val definition: CompiledCutscene,
    val world: String,
    /** Added to every position of the path. */
    val origin: Vec3,
    val skippable: Boolean,
    /** What to put back when it ends: the player's, from before the first of a run of cutscenes. */
    val saved: PlayerState,
    /** The display the player looks through. */
    val camera: UUID,
    /** The camera entity's tag instance: this run. */
    val id: UUID
) {
    /** Server ticks played. */
    var tick = 0
        internal set

    /** Index of the next cue to fire. */
    internal var nextCue = 0

    /** The tick a cue's text stops showing, or -1 when none is up. */
    internal var textUntil = -1

    /** The chunk (x, z) of [world] held for the camera, where it is now, until it ends. */
    internal var held: Pair<Int, Int>? = null

    /** Set once it has ended, for any reason. */
    var ended = false
        internal set

    /** Seconds played so far, at most the cutscene's length. */
    val time: Double get() = minOf(tick / TICKS_PER_SECOND, definition.length)

    val handle: LuaHandle.Cutscene get() = LuaHandle.Cutscene(number, kind)

    companion object {
        const val TICKS_PER_SECOND = 20.0
    }
}
