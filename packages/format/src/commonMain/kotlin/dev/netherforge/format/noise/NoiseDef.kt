package dev.netherforge.format.noise

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A noise field a `terrain/<id>.json` shapes terrain, climate and caves
 * with: a [FastNoiseLite] noise of one [type], optionally layered into
 * [octaves]. Values are between -1 and 1.
 *
 * The seed isn't here: each use of a field takes its own from the world's
 * seed and its role (see [TerrainSeeds]), so two fields of one file are
 * never the same pattern and one world's terrain is always the same.
 */
@Serializable
data class NoiseDef(
    /** The noise algorithm. Default `openSimplex2`. */
    val type: NoiseType? = null,
    /** How tight the pattern is, per block: 0.01 changes over about a hundred blocks. Default [DEFAULT_FREQUENCY]. */
    val frequency: Double? = null,
    /** How octaves combine: `fbm` (default) adds finer detail; `ridged` makes sharp ridges; `pingPong` folds into bands; `none` is the plain noise. Only used with more than one octave. */
    val fractal: NoiseFractal? = null,
    /** Layers of detail, 1 to [MAX_OCTAVES]. Default 1. */
    val octaves: Int? = null,
    /** How much finer each octave is than the one before (1 to 4). Default [DEFAULT_LACUNARITY]. */
    val lacunarity: Double? = null,
    /** How much weaker each octave is than the one before (0 to 1). Default [DEFAULT_GAIN]. */
    val gain: Double? = null
) {
    val typeOrDefault: NoiseType get() = type ?: NoiseType.OPEN_SIMPLEX2
    val frequencyOrDefault: Double get() = frequency ?: DEFAULT_FREQUENCY
    val octavesOrDefault: Int get() = octaves ?: 1
    val lacunarityOrDefault: Double get() = lacunarity ?: DEFAULT_LACUNARITY
    val gainOrDefault: Double get() = gain ?: DEFAULT_GAIN
    val fractalOrDefault: NoiseFractal get() = fractal ?: NoiseFractal.FBM

    /**
     * A noise generator for this field with [seed]: configured once, then
     * safe to ask from any number of threads ([FastNoiseLite] reads its
     * settings and changes nothing).
     */
    fun build(seed: Int): FastNoiseLite {
        val noise = FastNoiseLite(seed)
        noise.setNoiseType(typeOrDefault.fnl)
        noise.setFrequency(frequencyOrDefault)
        if (octavesOrDefault > 1) {
            noise.setFractalType(fractalOrDefault.fnl)
            noise.setFractalOctaves(octavesOrDefault)
            noise.setFractalLacunarity(lacunarityOrDefault)
            noise.setFractalGain(gainOrDefault)
        }
        return noise
    }

    companion object {
        const val DEFAULT_FREQUENCY = 0.01
        const val DEFAULT_LACUNARITY = 2.0
        const val DEFAULT_GAIN = 0.5
        const val MAX_OCTAVES = 8

        /** The tightest pattern allowed: a feature a couple of blocks wide. */
        const val MAX_FREQUENCY = 1.0
    }
}

/** The algorithms [NoiseDef.type] chooses between. */
@Serializable
enum class NoiseType(val fnl: FastNoiseLite.NoiseType) {
    /** Smooth, with few directional artifacts: the usual choice. */
    @SerialName("openSimplex2")
    OPEN_SIMPLEX2(FastNoiseLite.NoiseType.OpenSimplex2),

    /** Like `openSimplex2`, rounder and a little slower. */
    @SerialName("openSimplex2s")
    OPEN_SIMPLEX2S(FastNoiseLite.NoiseType.OpenSimplex2S),

    /** Classic gradient noise. */
    @SerialName("perlin")
    PERLIN(FastNoiseLite.NoiseType.Perlin),

    /** Blocky random values, smoothly joined. */
    @SerialName("value")
    VALUE(FastNoiseLite.NoiseType.Value),

    /** `value` joined with cubic curves instead of straight lines. */
    @SerialName("valueCubic")
    VALUE_CUBIC(FastNoiseLite.NoiseType.ValueCubic),

    /** Distance to the nearest of many random points: cells and cracks. */
    @SerialName("cellular")
    CELLULAR(FastNoiseLite.NoiseType.Cellular)
}

/** How a [NoiseDef]'s octaves are combined. */
@Serializable
enum class NoiseFractal(val fnl: FastNoiseLite.FractalType) {
    @SerialName("none")
    NONE(FastNoiseLite.FractalType.None),

    @SerialName("fbm")
    FBM(FastNoiseLite.FractalType.FBm),

    @SerialName("ridged")
    RIDGED(FastNoiseLite.FractalType.Ridged),

    @SerialName("pingPong")
    PING_PONG(FastNoiseLite.FractalType.PingPong)
}

/**
 * Every random choice of a generated world comes from its seed alone, and these
 * make the integer each part of it starts from, with arithmetic that is the
 * same on the JVM and in JS (32-bit integers only), so the server and the
 * editor's preview draw the same world.
 */
object TerrainSeeds {
    /** The 32 bits a world's [seed] stands for. */
    fun fold(seed: Long): Int = (seed xor (seed ushr 32)).toInt()

    /** The seed of the part of a world called [role] (`height:hills`, `cave:caverns`), apart from every other part's. */
    fun forRole(seed: Long, role: String): Int {
        var h = mix(fold(seed) xor 0x2545F491)
        for (c in role) h = mix(h xor c.code) * 0x01000193
        return mix(h)
    }

    /** A well-mixed integer from [x], a murmur3 finaliser. */
    fun mix(x: Int): Int {
        var h = x
        h = h xor (h ushr 16)
        h *= -0x7a143595
        h = h xor (h ushr 13)
        h *= -0x3d4d51cb
        h = h xor (h ushr 16)
        return h
    }

    /** A number from 0 up to but not including 1 for the cell (x, y, z) of a world, the same on both platforms. */
    fun unit(seed: Int, x: Int, y: Int, z: Int): Double {
        var h = mix(seed xor (x * 0x1B873593))
        h = mix(h xor (z * 0x2C1B3C6D))
        h = mix(h xor (y * 0x297A2D39))
        return (h ushr 8) * (1.0 / 16777216.0)
    }
}
