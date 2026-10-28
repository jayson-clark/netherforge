package dev.netherforge.format.particle

import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * What an effect spawns on each tick of its timeline: the one place that
 * decides, shared by the server (which sends the spawns) and the editor's
 * preview (which draws them), so the two can't drift.
 *
 * Randomness comes from [Random] with a seed, the same XorWow generator on the
 * JVM and in JS, so a seed gives the same points everywhere. The server draws
 * a fresh seed per play; the editor uses a fixed one so its preview doesn't
 * shimmer between edits.
 *
 * Everything is in effect space: +Z forward, +Y up (Minecraft's yaw 0, south).
 * [EffectFrame] puts it in the world.
 */
class EffectSampler(effect: CompiledEffect, seed: Long) {
    var effect: CompiledEffect = effect
        private set

    private val random = Random(seed)

    /** Each rate emitter's fraction of a point carried to the next tick, by emitter name. */
    private val accumulators = HashMap<String, Double>()

    /** The timeline tick the next [step] emits, 0-based; wraps when looping. */
    var tick: Int = 0
        private set

    /** True once a non-looping effect has emitted its last tick. */
    var finished: Boolean = false
        private set

    /**
     * The spawns for the current tick, in effect space, then advances. With
     * [loop], the tick after the last is tick 0 again and rate emitters start
     * from nothing; without, the sampler is [finished] and spawns nothing more.
     */
    fun step(loop: Boolean): List<EffectSpawn> {
        if (finished) return emptyList()
        if (tick >= effect.duration) {
            // Only after [redefine] shortened the timeline under a playing effect.
            if (!loop) {
                finished = true
                return emptyList()
            }
            tick %= effect.duration
            accumulators.clear()
        }
        val spawns = ArrayList<EffectSpawn>()
        for (emitter in effect.emitters) emit(emitter, tick, spawns)
        tick++
        if (tick >= effect.duration) {
            if (loop) {
                tick = 0
                accumulators.clear()
            } else {
                finished = true
            }
        }
        return spawns
    }

    /**
     * Swaps the definition on reload. The tick carries over (a non-looping
     * effect now past its duration finishes at its next step, a looping one
     * wraps), and emitters keep their rate accumulators by name.
     */
    fun redefine(effect: CompiledEffect) {
        val names = effect.emitters.map { it.name }.toSet()
        accumulators.keys.retainAll(names)
        this.effect = effect
    }

    private fun emit(emitter: CompiledEmitter, t: Int, out: MutableList<EffectSpawn>) {
        if (t < emitter.start || t >= emitter.end) return
        val time = t.toDouble()
        val n = if (emitter.burst != null) {
            val pulse = t == emitter.start || (emitter.every != null && (t - emitter.start) % emitter.every == 0)
            if (pulse) emitter.burst else 0
        } else {
            val rate = emitter.rateCurve?.at(time) ?: emitter.rate ?: 0.0
            val total = (accumulators[emitter.name] ?: 0.0) + rate
            val whole = floor(total)
            accumulators[emitter.name] = total - whole
            whole.toInt()
        }
        if (n <= 0) return

        val radius = emitter.radiusCurve?.at(time) ?: 0.0
        val speed = emitter.speedCurve.at(time)
        val data = dataOf(emitter, time)
        val spin = if (emitter.spin != 0.0) Matrix4().rotateY(degreesToRadians(emitter.spin * t)) else null
        for (i in 0 until n) {
            var local = place(emitter, i, n, radius)
            if (spin != null) local = spin.transformDirection(local)
            val turned = emitter.rotation.transformDirection(local)
            val position = emitter.offset + turned
            val (count, offset) = when (emitter.motion) {
                Motion.RANDOM -> emitter.count to emitter.spread
                Motion.OUTWARD -> 0 to away(emitter, turned)
                Motion.INWARD -> 0 to away(emitter, turned) * -1.0
                Motion.DIRECTION -> 0 to emitter.rotation.transformDirection(requireNotNull(emitter.direction))
            }
            out += EffectSpawn(emitter.name, emitter.particle, position, count, offset, speed, data, emitter.force)
        }
    }

    /** The unit vector from the shape's centre to a point, or the shape's up when the point is the centre. */
    private fun away(emitter: CompiledEmitter, turned: Vec3): Vec3 {
        val length = sqrt(turned.x * turned.x + turned.y * turned.y + turned.z * turned.z)
        if (length < EPSILON) return emitter.rotation.transformDirection(UP)
        return turned * (1.0 / length)
    }

    /** Point [i] of [n] on the emitter's shape, in shape space (before spin and rotation). */
    private fun place(emitter: CompiledEmitter, i: Int, n: Int, radius: Double): Vec3 {
        val even = emitter.distribution == Distribution.EVEN
        return when (val shape = emitter.shape) {
            PointShape -> Vec3.ZERO
            is LineShape -> shape.to * (
                if (!even) {
                    random.nextDouble()
                } else if (n == 1) {
                    0.5
                } else {
                    i.toDouble() / (n - 1)
                }
                )
            is RingShape -> {
                val angle = if (even) 2 * PI * i / n else random.nextDouble() * 2 * PI
                Vec3(radius * cos(angle), 0.0, radius * sin(angle))
            }
            is DiscShape -> {
                val angle = random.nextDouble() * 2 * PI
                val r = radius * sqrt(random.nextDouble())
                Vec3(r * cos(angle), 0.0, r * sin(angle))
            }
            is SphereShape -> when {
                even -> fibonacci(i, n) * radius
                shape.surface == true -> unitSphere() * radius
                else -> unitSphere() * (radius * cbrt(random.nextDouble()))
            }
            is BoxShape -> if (shape.surface == true) boxSurface(shape.size) else boxVolume(shape.size)
        }
    }

    /** A uniformly random direction. */
    private fun unitSphere(): Vec3 {
        val y = 2 * random.nextDouble() - 1
        val angle = random.nextDouble() * 2 * PI
        val r = sqrt(1 - y * y)
        return Vec3(r * cos(angle), y, r * sin(angle))
    }

    private fun boxVolume(size: Vec3) = Vec3(
        (random.nextDouble() - 0.5) * size.x,
        (random.nextDouble() - 0.5) * size.y,
        (random.nextDouble() - 0.5) * size.z
    )

    /** On the box's faces, uniform by area: pick a pair of faces by their area, a side, then a point on it. */
    private fun boxSurface(size: Vec3): Vec3 {
        val x = size.y * size.z
        val y = size.x * size.z
        val z = size.x * size.y
        val pick = random.nextDouble() * (x + y + z)
        val side = if (random.nextDouble() < 0.5) -0.5 else 0.5
        val u = random.nextDouble() - 0.5
        val v = random.nextDouble() - 0.5
        return when {
            pick < x -> Vec3(side * size.x, u * size.y, v * size.z)
            pick < x + y -> Vec3(u * size.x, side * size.y, v * size.z)
            else -> Vec3(u * size.x, v * size.y, side * size.z)
        }
    }

    private fun dataOf(emitter: CompiledEmitter, time: Double): SpawnData? = when (emitter.kind) {
        ParticleDataKind.NONE, ParticleDataKind.OTHER -> null
        ParticleDataKind.DUST -> DustData(emitter.colorCurve.at(time), emitter.sizeCurve.at(time))
        ParticleDataKind.DUST_TRANSITION -> DustTransitionData(emitter.colorCurve.at(time), emitter.toColor, emitter.sizeCurve.at(time))
        ParticleDataKind.COLOR -> ColorData(emitter.colorCurve.at(time))
        ParticleDataKind.BLOCK -> BlockData(requireNotNull(emitter.blockState).toString())
        ParticleDataKind.ITEM -> ItemData(requireNotNull(emitter.item))
    }

    companion object {
        private const val EPSILON = 1e-9
        private val UP = Vec3(0.0, 1.0, 0.0)

        /** The golden angle, in radians: successive lattice points turn by it. */
        private val GOLDEN_ANGLE = PI * (3 - sqrt(5.0))

        /** Point [i] of a Fibonacci lattice of [n] points on the unit sphere: near-even spacing for any [n]. */
        fun fibonacci(i: Int, n: Int): Vec3 {
            val y = 1 - 2 * (i + 0.5) / n
            val r = sqrt(1 - y * y)
            val angle = GOLDEN_ANGLE * i
            return Vec3(r * cos(angle), y, r * sin(angle))
        }
    }
}

/**
 * One Minecraft particle spawn: what the game's spawn packet carries. With
 * [count] above 0, the game scatters that many particles around [position]
 * with Gaussian [offset] (the spread) and random speed [speed]; with [count]
 * 0 it spawns one moving along [offset] at [speed].
 */
@Serializable
data class EffectSpawn(
    val emitter: String,
    val particle: String,
    val position: Vec3,
    val count: Int,
    /** The spread when [count] > 0, the direction when [count] is 0. */
    val offset: Vec3,
    val speed: Double,
    val data: SpawnData? = null,
    val force: Boolean = false
)

/** The options a spawn carries, by its particle's data kind. Colours are `0xRRGGBB`. */
@Serializable
sealed interface SpawnData

@Serializable
@SerialName("dust")
data class DustData(val color: Int, val size: Double) : SpawnData

@Serializable
@SerialName("dust_transition")
data class DustTransitionData(val color: Int, val toColor: Int, val size: Double) : SpawnData

@Serializable
@SerialName("color")
data class ColorData(val color: Int) : SpawnData

@Serializable
@SerialName("block")
data class BlockData(
    /** Canonical block state. */
    val state: String
) : SpawnData

@Serializable
@SerialName("item")
data class ItemData(val item: ItemDef) : SpawnData

/**
 * Where an effect is in the world: effect space → world is
 * `T(origin) · Ry(-yaw) · Rx(pitch) · S(scale)`; directions skip `T` and `S`.
 *
 * [yaw] and [pitch] are Minecraft's degrees: yaw 0 faces south (+Z), 90 west
 * (−X); positive pitch looks down. [scale] multiplies distances: positions,
 * spreads and speeds, but not dust size, which is a particle option.
 */
data class EffectFrame(val origin: Vec3, val yaw: Double = 0.0, val pitch: Double = 0.0, val scale: Double = 1.0) {
    private val turn = Matrix4().rotateY(degreesToRadians(-yaw)).rotateX(degreesToRadians(pitch))

    fun position(p: Vec3): Vec3 = origin + turn.transformDirection(p * scale)

    fun direction(d: Vec3): Vec3 = turn.transformDirection(d)

    /**
     * [spawn] in world space. A spread stays on the world's axes (the game's
     * Gaussian is per axis, so it can't turn) and scales; a direction turns.
     */
    fun toWorld(spawn: EffectSpawn): EffectSpawn = spawn.copy(
        position = position(spawn.position),
        offset = if (spawn.count > 0) spawn.offset * scale else direction(spawn.offset),
        speed = spawn.speed * scale
    )
}
