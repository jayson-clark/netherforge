package dev.netherforge.format.particle

import dev.netherforge.format.centity.Easing
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Vec3
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `particles/<id>/effect.json`: a timeline of particle spawns, played at a
 * place by scripts.
 *
 * An effect is data only: no script, nothing else in its folder. Its
 * [emitters] are keyed by name, so names are unique by construction and two
 * branches that each add one merge cleanly. They run in key order, which only
 * matters for which spawns survive the server's per-tick budget.
 *
 * Minecraft particles are fire-and-forget: the client owns their lifetime,
 * gravity and fade, so nothing here changes a particle once it's spawned.
 * "Over time" means [Curves] across successive spawns. The timing and
 * placement maths are [EffectSampler]'s, shared by the server and the
 * editor's preview.
 */
@Serializable
data class ParticleEffectFile(
    @SerialName("\$schema") val schema: String? = null,
    /** How long the timeline is, in ticks (`1..72000`). */
    val duration: Int,
    /** Restart at tick 0 after the last tick, until stopped. Default false. */
    val loop: Boolean? = null,
    val emitters: Map<String, EmitterDef> = emptyMap()
) {
    companion object {
        const val FILE_NAME = "effect.json"
        const val SCHEMA = "../../.netherforge/schema/particle_effect.schema.json"

        const val MIN_DURATION = 1
        const val MAX_DURATION = 72000

        /** Points one effect may spawn in a tick, at worst, summed over its emitters. */
        const val MAX_POINTS_PER_TICK = 256
    }
}

/**
 * One source of points on the effect's timeline. Each active tick it decides
 * how many points to spawn (a [burst] or a [rate]), places each on its
 * [shape], and sends one Minecraft particle spawn per point.
 *
 * The data fields ([color], [toColor], [size], [blockState], [item]) are only
 * for particles that take them; which ones a particle takes is a server fact
 * (`GameData.particle`).
 */
@Serializable
data class EmitterDef(
    /** A namespaced particle id: `minecraft:end_rod`, or `end_rod`. */
    val particle: String,
    /** The first tick it's active. Default 0. */
    val start: Int? = null,
    /** The tick it stops at, exclusive. Default the effect's duration. */
    val end: Int? = null,
    /** Points per burst (`1..256`). Exactly one of [burst] and [rate]. */
    val burst: Int? = null,
    /** Repeat the burst every this many ticks from [start] while before [end]. Absent: one burst at [start]. */
    val every: Int? = null,
    /** Points per tick (`> 0`, `<= 256`). Fractions accumulate: 0.25 is one point every 4 ticks. */
    val rate: Double? = null,
    /** Where points go. Default a single point. */
    val shape: EmitterShape? = null,
    /** How points spread over the shape. Default random. */
    val distribution: Distribution? = null,
    /** Degrees per tick the shape turns about its own up axis as the effect plays. Only for ring, disc and line. */
    val spin: Double? = null,
    /** The emitter's origin in effect space, blocks. */
    val offset: Vec3? = null,
    /** Euler degrees, X then Y then Z (as in `centity.json`). Turns the shape and directions. */
    val rotation: Vec3? = null,
    /** How each point's particle moves. Default random. */
    val motion: Motion? = null,
    /** Which way particles fly with `motion: "direction"`; normalised when compiled. */
    val direction: Vec3? = null,
    /** Minecraft particles per point (`1..100`), with `motion: "random"` only. Default 1. */
    val count: Int? = null,
    /** Minecraft's per-axis Gaussian spread around each point, blocks, with `motion: "random"` only. */
    val spread: Vec3? = null,
    /** Minecraft's `extra`: random speed with `motion: "random"`, velocity along the direction otherwise. Default 0. */
    val speed: Double? = null,
    /** Shown past the client's particle setting and up to 128 blocks away. Default false. */
    val force: Boolean? = null,
    /** `#rrggbb`, for dust, dust transitions and coloured particles. Default white. */
    val color: String? = null,
    /** `#rrggbb` a dust transition fades to. Default white. */
    val toColor: String? = null,
    /** Dust size (`0.01..4`). Default 1. */
    val size: Double? = null,
    /** The block a block particle shows: `minecraft:stone`, with properties if wanted. */
    val blockState: String? = null,
    /** The item an item particle shows. */
    val item: ItemDef? = null,
    /** Properties that change over the effect's timeline. */
    val curves: Curves? = null
) {
    companion object {
        const val MAX_BURST = 256
        const val MAX_RATE = 256.0
        const val MAX_COUNT = 100
        const val MIN_SIZE = 0.01
        const val MAX_SIZE = 4.0

        const val DEFAULT_COUNT = 1
        const val DEFAULT_SIZE = 1.0
        const val DEFAULT_COLOR = "#ffffff"
    }
}

/**
 * Where an emitter puts its points, in emitter space: centred on the
 * emitter's `offset`, turned by its `rotation`, up is +Y.
 */
@Serializable
sealed interface EmitterShape

@Serializable
@SerialName("point")
data object PointShape : EmitterShape

/** The segment from the origin to [to]. */
@Serializable
@SerialName("line")
data class LineShape(val to: Vec3) : EmitterShape

/** On the circle in the XZ plane. [radius] is required unless `curves.radius` drives it. */
@Serializable
@SerialName("ring")
data class RingShape(val radius: Double? = null) : EmitterShape

/** Inside the circle in the XZ plane, uniform by area. */
@Serializable
@SerialName("disc")
data class DiscShape(val radius: Double? = null) : EmitterShape

/** In the ball, uniform by volume, or on its shell with [surface]. */
@Serializable
@SerialName("sphere")
data class SphereShape(val radius: Double? = null, val surface: Boolean? = null) : EmitterShape

/** In the box, centred on the origin, or on its faces with [surface]. */
@Serializable
@SerialName("box")
data class BoxShape(val size: Vec3, val surface: Boolean? = null) : EmitterShape

@Serializable
enum class Distribution {
    @SerialName("random")
    RANDOM,

    /** Evenly spaced: for line, ring, and sphere with `surface`. */
    @SerialName("even")
    EVEN
}

/** How a point's particle moves, which maps exactly onto the two numbers a Minecraft spawn carries. */
@Serializable
enum class Motion {
    /** The game's own scatter: `count` particles, Gaussian `spread`, random drift at `speed`. */
    @SerialName("random")
    RANDOM,

    /** Away from the shape's centre at `speed`. */
    @SerialName("outward")
    OUTWARD,

    /** Towards the shape's centre at `speed`. */
    @SerialName("inward")
    INWARD,

    /** Along `direction` at `speed`. */
    @SerialName("direction")
    DIRECTION
}

/**
 * Keyframed channels, on the effect's one timeline (not per emitter). A
 * channel replaces its constant: having both is an error. A channel with no
 * keys is the same as no channel.
 */
@Serializable
data class Curves(
    /** Points per tick, `0..256`. Only for an emitter that uses `rate`. */
    val rate: List<NumberKey>? = null,
    /** Dust size, `0.01..4`. Only for a particle that takes a size. */
    val size: List<NumberKey>? = null,
    /** `>= 0`. */
    val speed: List<NumberKey>? = null,
    /** `> 0`. Only for ring, disc and sphere shapes. */
    val radius: List<NumberKey>? = null,
    /** Only for a particle that takes a colour. */
    val color: List<ColorKey>? = null
)

/** A value at an effect tick. [easing] shapes the segment leaving this key. */
@Serializable
data class NumberKey(val time: Int, val value: Double, val easing: Easing? = null)

/** A `#rrggbb` colour at an effect tick. [easing] shapes the segment leaving this key. */
@Serializable
data class ColorKey(val time: Int, val color: String, val easing: Easing? = null)

/**
 * Which emitter data fields each particle data kind takes, by their keys in
 * the file. The validator, the compiler and the editor's inspector all read
 * this, so they agree on what a particle can be given.
 */
object ParticleOptions {
    const val COLOR = "color"
    const val TO_COLOR = "toColor"
    const val SIZE = "size"
    const val BLOCK_STATE = "blockState"
    const val ITEM = "item"

    /** Every data field, in the file's order. */
    val ALL = listOf(COLOR, TO_COLOR, SIZE, BLOCK_STATE, ITEM)

    /** The data fields a particle of [kind] takes. */
    fun takes(kind: ParticleDataKind): List<String> = when (kind) {
        ParticleDataKind.DUST -> listOf(COLOR, SIZE)
        ParticleDataKind.DUST_TRANSITION -> listOf(COLOR, TO_COLOR, SIZE)
        ParticleDataKind.COLOR -> listOf(COLOR)
        ParticleDataKind.BLOCK -> listOf(BLOCK_STATE)
        ParticleDataKind.ITEM -> listOf(ITEM)
        ParticleDataKind.NONE, ParticleDataKind.OTHER -> emptyList()
    }

    /** The data field a particle of [kind] can't be spawned without, if any. */
    fun required(kind: ParticleDataKind): String? = when (kind) {
        ParticleDataKind.BLOCK -> BLOCK_STATE
        ParticleDataKind.ITEM -> ITEM
        else -> null
    }
}
