package dev.netherforge.format.particle

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.centity.Easing
import dev.netherforge.format.game.BlockState
import dev.netherforge.format.game.GameData
import dev.netherforge.format.game.GameIds
import dev.netherforge.format.game.ParticleDataKind
import dev.netherforge.format.hasErrors
import dev.netherforge.format.item.ItemDef
import dev.netherforge.format.math.Matrix4
import dev.netherforge.format.math.Vec3
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An effect ready to sample: ids normalised, colours as `0xRRGGBB` ints,
 * directions normalised, curves as sorted arrays, block states parsed, and
 * each emitter's data kind decided. Emitters are in key order.
 *
 * Built only from a file that validated without errors, by [ParticleEffectCompiler].
 */
class CompiledEffect(val id: String, val duration: Int, val loop: Boolean, val emitters: List<CompiledEmitter>)

class CompiledEmitter(
    val name: String,
    /** Namespaced. */
    val particle: String,
    /** What the spawn carries; always one effects can play. */
    val kind: ParticleDataKind,
    val start: Int,
    /** Exclusive. */
    val end: Int,
    val burst: Int?,
    val every: Int?,
    /** The rate's constant, or null for a burst emitter. A rate curve, when there is one, wins over it. */
    val rate: Double?,
    val shape: EmitterShape,
    val distribution: Distribution,
    val spin: Double,
    val offset: Vec3,
    /** The emitter's rotation as a matrix; turns points and directions. */
    val rotation: Matrix4,
    val motion: Motion,
    /** Unit length, with `motion: "direction"`. */
    val direction: Vec3?,
    val count: Int,
    val spread: Vec3,
    val force: Boolean,
    val toColor: Int,
    val blockState: BlockState?,
    val item: ItemDef?,
    val rateCurve: NumberCurve?,
    /** A constant's curve is one key: every property is sampled the same way. */
    val sizeCurve: NumberCurve,
    val speedCurve: NumberCurve,
    /** Null for shapes without a radius. */
    val radiusCurve: NumberCurve?,
    val colorCurve: ColorCurve
)

/** Keys sorted by tick, never empty. Holds the first value before the first key and the last after the last. */
class NumberCurve(private val times: IntArray, private val values: DoubleArray, private val easings: Array<Easing>) {

    fun at(tick: Double): Double {
        val i = segment(times, tick)
        if (i < 0) return values[0]
        if (i >= times.size - 1) return values[times.size - 1]
        val t = easings[i].apply((tick - times[i]) / (times[i + 1] - times[i]))
        return values[i] + (values[i + 1] - values[i]) * t
    }

    /** The largest value anywhere on the curve. */
    val max: Double get() = values.max()

    companion object {
        fun constant(value: Double) = NumberCurve(intArrayOf(0), doubleArrayOf(value), arrayOf(Easing.LINEAR))

        fun of(keys: List<NumberKey>): NumberCurve {
            val sorted = keys.sortedBy { it.time }
            return NumberCurve(
                sorted.map { it.time }.toIntArray(),
                sorted.map { it.value }.toDoubleArray(),
                sorted.map { it.easing ?: Easing.LINEAR }.toTypedArray()
            )
        }
    }
}

/** Colours as `0xRRGGBB`, interpolated per channel in sRGB. Otherwise like [NumberCurve]. */
class ColorCurve(private val times: IntArray, private val colors: IntArray, private val easings: Array<Easing>) {

    fun at(tick: Double): Int {
        val i = segment(times, tick)
        if (i < 0) return colors[0]
        if (i >= times.size - 1) return colors[times.size - 1]
        val t = easings[i].apply((tick - times[i]) / (times[i + 1] - times[i]))
        val from = colors[i]
        val to = colors[i + 1]
        var out = 0
        for (shift in intArrayOf(16, 8, 0)) {
            val a = (from shr shift) and 0xff
            val b = (to shr shift) and 0xff
            out = out or ((a + (b - a) * t).roundToInt().coerceIn(0, 255) shl shift)
        }
        return out
    }

    companion object {
        fun constant(color: Int) = ColorCurve(intArrayOf(0), intArrayOf(color), arrayOf(Easing.LINEAR))

        fun of(keys: List<ColorKey>): ColorCurve {
            val sorted = keys.sortedBy { it.time }
            return ColorCurve(
                sorted.map { it.time }.toIntArray(),
                sorted.map { parseColor(it.color) }.toIntArray(),
                sorted.map { it.easing ?: Easing.LINEAR }.toTypedArray()
            )
        }
    }
}

/**
 * The index of the key whose segment [tick] falls in: -1 before the first
 * key, `size - 1` at or after the last.
 */
private fun segment(times: IntArray, tick: Double): Int {
    if (tick < times[0]) return -1
    var i = 0
    while (i < times.size - 1 && times[i + 1] <= tick) i++
    return i
}

/** `#rrggbb` → `0xRRGGBB`. */
fun parseColor(text: String): Int = text.removePrefix("#").toInt(16)

/** `0xRRGGBB` → `#rrggbb`. */
fun formatColor(color: Int): String = "#" + (color and 0xffffff).toString(16).padStart(6, '0')

object ParticleEffectCompiler {

    /**
     * Compiles a valid effect. [game] decides each emitter's data kind; without
     * it (the editor's preview has none) the kind is read off which data fields
     * the emitter sets, which is right for every particle that takes them.
     */
    fun compile(id: String, file: ParticleEffectFile, game: GameData? = null): CompiledEffect {
        val emitters = file.emitters.entries.sortedBy { it.key }.map { (name, emitter) -> compileEmitter(name, emitter, file, game) }
        return CompiledEffect(id, file.duration, file.loop ?: false, emitters)
    }

    /** Validates then compiles: the effect, or the problems that stop it. */
    fun compileChecked(id: String, file: ParticleEffectFile, path: String, game: GameData?): Pair<CompiledEffect?, List<Problem>> {
        val sink = ProblemSink(path)
        ParticleEffectValidator.validate(file, sink, game)
        return if (sink.problems.hasErrors) null to sink.problems else compile(id, file, game) to sink.problems
    }

    private fun compileEmitter(name: String, emitter: EmitterDef, file: ParticleEffectFile, game: GameData?): CompiledEmitter {
        val kind = ParticleEffectValidator.kindOf(emitter, game) ?: guessKind(emitter)
        require(kind != ParticleDataKind.OTHER) { "emitter $name: particle ${emitter.particle} can't be played" }
        val shape = emitter.shape ?: PointShape
        val curves = emitter.curves ?: Curves()
        val radiusCurve = when {
            !ParticleEffectValidator.hasRadius(shape) -> null
            !curves.radius.isNullOrEmpty() -> NumberCurve.of(curves.radius)
            else -> NumberCurve.constant(requireNotNull(ParticleEffectValidator.radiusOf(shape)) { "emitter $name has no radius" })
        }
        return CompiledEmitter(
            name = name,
            particle = GameIds.normalize(emitter.particle),
            kind = kind,
            start = emitter.start ?: 0,
            end = emitter.end ?: file.duration,
            burst = emitter.burst,
            every = emitter.every,
            rate = emitter.rate,
            shape = shape,
            distribution = emitter.distribution ?: Distribution.RANDOM,
            spin = emitter.spin ?: 0.0,
            offset = emitter.offset ?: Vec3.ZERO,
            rotation = Matrix4.fromTrs(Vec3.ZERO, emitter.rotation ?: Vec3.ZERO, Vec3.ONE),
            motion = emitter.motion ?: Motion.RANDOM,
            direction = emitter.direction?.let { normalize(it) },
            count = emitter.count ?: EmitterDef.DEFAULT_COUNT,
            spread = emitter.spread ?: Vec3.ZERO,
            force = emitter.force ?: false,
            toColor = parseColor(emitter.toColor ?: EmitterDef.DEFAULT_COLOR),
            blockState = emitter.blockState?.let { requireNotNull(BlockState.parse(it)) { "emitter $name: bad block state" } },
            item = emitter.item,
            rateCurve = curves.rate?.takeIf { it.isNotEmpty() }?.let { NumberCurve.of(it) },
            sizeCurve = curves.size?.takeIf { it.isNotEmpty() }?.let { NumberCurve.of(it) }
                ?: NumberCurve.constant(emitter.size ?: EmitterDef.DEFAULT_SIZE),
            speedCurve = curves.speed?.takeIf { it.isNotEmpty() }?.let { NumberCurve.of(it) } ?: NumberCurve.constant(emitter.speed ?: 0.0),
            radiusCurve = radiusCurve,
            colorCurve = curves.color?.takeIf { it.isNotEmpty() }?.let { ColorCurve.of(it) }
                ?: ColorCurve.constant(parseColor(emitter.color ?: EmitterDef.DEFAULT_COLOR))
        )
    }

    /** Without game data: what the emitter's data fields say it is. */
    private fun guessKind(emitter: EmitterDef): ParticleDataKind = when {
        emitter.blockState != null -> ParticleDataKind.BLOCK
        emitter.item != null -> ParticleDataKind.ITEM
        emitter.toColor != null -> ParticleDataKind.DUST_TRANSITION
        emitter.size != null || !emitter.curves?.size.isNullOrEmpty() -> ParticleDataKind.DUST
        emitter.color != null || !emitter.curves?.color.isNullOrEmpty() -> ParticleDataKind.COLOR
        else -> ParticleDataKind.NONE
    }

    private fun normalize(v: Vec3): Vec3 {
        val length = sqrt(v.x * v.x + v.y * v.y + v.z * v.z)
        return Vec3(v.x / length, v.y / length, v.z / length)
    }
}
