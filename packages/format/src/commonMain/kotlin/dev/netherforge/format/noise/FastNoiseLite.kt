// MIT License
//
// Copyright(c) 2023 Jordan Peck (jordan.me2@gmail.com)
// Copyright(c) 2023 Contributors
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files(the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and / or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions :
//
// The above copyright notice and this permission notice shall be included in all
// copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
// SOFTWARE.

package dev.netherforge.format.noise

import dev.netherforge.format.noise.NoiseTables.gradients2D
import dev.netherforge.format.noise.NoiseTables.gradients3D
import dev.netherforge.format.noise.NoiseTables.randVecs2D
import dev.netherforge.format.noise.NoiseTables.randVecs3D
import kotlin.math.sqrt

/*
 * A port of FastNoiseLite (https://github.com/Auburn/FastNoiseLite, the Java version) to common Kotlin, so the server and the
 * editor's preview run the same code. What changed from the reference:
 *
 *  - Doubles everywhere. Kotlin/JS has no 32-bit floats, so the reference's floats would give different digits on the JVM and in JS;
 *    a double's arithmetic (+ - * / and sqrt) is the same on both, which is what makes a seed's noise identical.
 *  - The tables are written out as doubles (NoiseTables), and nothing here calls a function whose result may differ in the last
 *    digit between the JVM and a JS engine (no sin, cos, pow, exp).
 *  - Names are Kotlin's (setSeed, getNoise), and the setters keep the reference's order and effects.
 *
 * Configure one, then share it: getNoise and domainWarp read the settings and change nothing, so a configured instance is safe to
 * use from many threads.
 */
@Suppress("LargeClass", "TooManyFunctions")
class FastNoiseLite(seed: Int = 1337) {
    enum class NoiseType {
        OpenSimplex2,
        OpenSimplex2S,
        Cellular,
        Perlin,
        ValueCubic,
        Value
    }

    enum class RotationType3D {
        None,
        ImproveXYPlanes,
        ImproveXZPlanes
    }

    enum class FractalType {
        None,
        FBm,
        Ridged,
        PingPong,
        DomainWarpProgressive,
        DomainWarpIndependent
    }

    enum class CellularDistanceFunction {
        Euclidean,
        EuclideanSq,
        Manhattan,
        Hybrid
    }

    enum class CellularReturnType {
        CellValue,
        Distance,
        Distance2,
        Distance2Add,
        Distance2Sub,
        Distance2Mul,
        Distance2Div
    }

    enum class DomainWarpType {
        OpenSimplex2,
        OpenSimplex2Reduced,
        BasicGrid
    }

    private enum class TransformType3D {
        None,
        ImproveXYPlanes,
        ImproveXZPlanes,
        DefaultOpenSimplex2
    }

    class Vector2(var x: Double, var y: Double)

    class Vector3(var x: Double, var y: Double, var z: Double)

    private var mSeed = seed
    private var mFrequency = 0.01
    private var mNoiseType = NoiseType.OpenSimplex2
    private var mRotationType3D = RotationType3D.None
    private var mTransformType3D = TransformType3D.DefaultOpenSimplex2

    private var mFractalType = FractalType.None
    private var mOctaves = 3
    private var mLacunarity = 2.0
    private var mGain = 0.5
    private var mWeightedStrength = 0.0
    private var mPingPongStrength = 2.0

    private var mFractalBounding = 1 / 1.75

    private var mCellularDistanceFunction = CellularDistanceFunction.EuclideanSq
    private var mCellularReturnType = CellularReturnType.Distance
    private var mCellularJitterModifier = 1.0

    private var mDomainWarpType = DomainWarpType.OpenSimplex2
    private var mWarpTransformType3D = TransformType3D.DefaultOpenSimplex2
    private var mDomainWarpAmp = 1.0

    /** Seed used for all noise types. Default 1337. */
    fun setSeed(seed: Int) {
        mSeed = seed
    }

    /** Frequency for all noise types. Default 0.01. */
    fun setFrequency(frequency: Double) {
        mFrequency = frequency
    }

    /** The noise algorithm used by getNoise. Default OpenSimplex2. */
    fun setNoiseType(noiseType: NoiseType) {
        mNoiseType = noiseType
        updateTransformType3D()
    }

    /** Domain rotation for 3D noise and 3D domain warp, to reduce directional artifacts when sampling a 2D plane in 3D. Default None. */
    fun setRotationType3D(rotationType3D: RotationType3D) {
        mRotationType3D = rotationType3D
        updateTransformType3D()
        updateWarpTransformType3D()
    }

    /** How octaves combine in all fractal noise types. Default None. The DomainWarp fractal types only affect domainWarp. */
    fun setFractalType(fractalType: FractalType) {
        mFractalType = fractalType
    }

    /** Octave count for all fractal noise types. Default 3. */
    fun setFractalOctaves(octaves: Int) {
        mOctaves = octaves
        calculateFractalBounding()
    }

    /** Octave lacunarity for all fractal noise types. Default 2.0. */
    fun setFractalLacunarity(lacunarity: Double) {
        mLacunarity = lacunarity
    }

    /** Octave gain for all fractal noise types. Default 0.5. */
    fun setFractalGain(gain: Double) {
        mGain = gain
        calculateFractalBounding()
    }

    /** Octave weighting for the fractal types that aren't DomainWarp. Default 0.0; keep it between 0 and 1 to keep the -1 to 1 bounds. */
    fun setFractalWeightedStrength(weightedStrength: Double) {
        mWeightedStrength = weightedStrength
    }

    /** Strength of the ping pong effect. Default 2.0. */
    fun setFractalPingPongStrength(pingPongStrength: Double) {
        mPingPongStrength = pingPongStrength
    }

    /** The distance function of cellular noise. Default EuclideanSq. */
    fun setCellularDistanceFunction(cellularDistanceFunction: CellularDistanceFunction) {
        mCellularDistanceFunction = cellularDistanceFunction
    }

    /** What cellular noise returns. Default Distance. */
    fun setCellularReturnType(cellularReturnType: CellularReturnType) {
        mCellularReturnType = cellularReturnType
    }

    /** The farthest a cellular point moves from its grid position. Default 1.0; more than 1 gives artifacts. */
    fun setCellularJitter(cellularJitter: Double) {
        mCellularJitterModifier = cellularJitter
    }

    /** The warp algorithm of domainWarp. Default OpenSimplex2. */
    fun setDomainWarpType(domainWarpType: DomainWarpType) {
        mDomainWarpType = domainWarpType
        updateWarpTransformType3D()
    }

    /** The farthest domainWarp moves a position. Default 1.0. */
    fun setDomainWarpAmp(domainWarpAmp: Double) {
        mDomainWarpAmp = domainWarpAmp
    }

    /** 2D noise at a position with the current settings, between -1 and 1. */
    fun getNoise(x: Double, y: Double): Double {
        var x = x * mFrequency
        var y = y * mFrequency

        when (mNoiseType) {
            NoiseType.OpenSimplex2, NoiseType.OpenSimplex2S -> {
                val sqrt3 = 1.7320508075688772935274463415059
                val f2 = 0.5 * (sqrt3 - 1)
                val t = (x + y) * f2
                x += t
                y += t
            }
            else -> {}
        }

        return when (mFractalType) {
            FractalType.FBm -> genFractalFBm(x, y)
            FractalType.Ridged -> genFractalRidged(x, y)
            FractalType.PingPong -> genFractalPingPong(x, y)
            else -> genNoiseSingle(mSeed, x, y)
        }
    }

    /** 3D noise at a position with the current settings, between -1 and 1. */
    fun getNoise(x: Double, y: Double, z: Double): Double {
        var x = x * mFrequency
        var y = y * mFrequency
        var z = z * mFrequency

        when (mTransformType3D) {
            TransformType3D.ImproveXYPlanes -> {
                val xy = x + y
                val s2 = xy * -0.211324865405187
                z *= 0.577350269189626
                x += s2 - z
                y = y + s2 - z
                z += xy * 0.577350269189626
            }
            TransformType3D.ImproveXZPlanes -> {
                val xz = x + z
                val s2 = xz * -0.211324865405187
                y *= 0.577350269189626
                x += s2 - y
                z += s2 - y
                y += xz * 0.577350269189626
            }
            TransformType3D.DefaultOpenSimplex2 -> {
                val r3 = 2.0 / 3.0
                val r = (x + y + z) * r3 // Rotation, not skew
                x = r - x
                y = r - y
                z = r - z
            }
            else -> {}
        }

        return when (mFractalType) {
            FractalType.FBm -> genFractalFBm(x, y, z)
            FractalType.Ridged -> genFractalRidged(x, y, z)
            FractalType.PingPong -> genFractalPingPong(x, y, z)
            else -> genNoiseSingle(mSeed, x, y, z)
        }
    }

    /** Warps a position in place with the current domain warp settings; then sample getNoise at it. */
    fun domainWarp(coord: Vector2) {
        when (mFractalType) {
            FractalType.DomainWarpProgressive -> domainWarpFractalProgressive(coord)
            FractalType.DomainWarpIndependent -> domainWarpFractalIndependent(coord)
            else -> domainWarpSingle(coord)
        }
    }

    /** Warps a 3D position in place with the current domain warp settings. */
    fun domainWarp(coord: Vector3) {
        when (mFractalType) {
            FractalType.DomainWarpProgressive -> domainWarpFractalProgressive(coord)
            FractalType.DomainWarpIndependent -> domainWarpFractalIndependent(coord)
            else -> domainWarpSingle(coord)
        }
    }

    private fun fastMin(a: Double, b: Double): Double = if (a < b) a else b

    private fun fastMax(a: Double, b: Double): Double = if (a > b) a else b

    private fun fastAbs(f: Double): Double = if (f < 0) -f else f

    private fun fastSqrt(f: Double): Double = sqrt(f)

    private fun fastFloor(f: Double): Int = if (f >= 0) f.toInt() else f.toInt() - 1

    private fun fastRound(f: Double): Int = if (f >= 0) (f + 0.5).toInt() else (f - 0.5).toInt()

    private fun lerp(a: Double, b: Double, t: Double): Double = a + t * (b - a)

    private fun interpHermite(t: Double): Double = t * t * (3 - 2 * t)

    private fun interpQuintic(t: Double): Double = t * t * t * (t * (t * 6 - 15) + 10)

    private fun cubicLerp(a: Double, b: Double, c: Double, d: Double, t: Double): Double {
        val p: Double = (d - c) - (a - b)
        return t * t * t * p + t * t * ((a - b) - p) + t * (c - a) + b
    }

    private fun pingPong(t: Double): Double {
        val u = t - (t * 0.5).toInt() * 2
        return if (u < 1) u else 2 - u
    }

    private fun calculateFractalBounding() {
        val gain: Double = fastAbs(mGain)
        var amp: Double = gain
        var ampFractal: Double = 1.0
        for (i in 1 until mOctaves) {
            ampFractal += amp
            amp *= gain
        }
        mFractalBounding = 1 / ampFractal
    }

    // Hashing
    private companion object {
        const val PRIME_X = 501125321
        const val PRIME_Y = 1136930381
        const val PRIME_Z = 1720413743
        const val FLOAT_MAX = 3.4028234663852886E38
    }

    private fun hash(seed: Int, xPrimed: Int, yPrimed: Int): Int {
        var hash = seed xor xPrimed xor yPrimed

        hash *= 0x27d4eb2d
        return hash
    }

    private fun hash(seed: Int, xPrimed: Int, yPrimed: Int, zPrimed: Int): Int {
        var hash = seed xor xPrimed xor yPrimed xor zPrimed

        hash *= 0x27d4eb2d
        return hash
    }

    private fun valCoord(seed: Int, xPrimed: Int, yPrimed: Int): Double {
        var hash = hash(seed, xPrimed, yPrimed)

        hash *= hash
        hash = hash xor (hash shl 19)
        return hash * (1 / 2147483648.0)
    }

    private fun valCoord(seed: Int, xPrimed: Int, yPrimed: Int, zPrimed: Int): Double {
        var hash = hash(seed, xPrimed, yPrimed, zPrimed)

        hash *= hash
        hash = hash xor (hash shl 19)
        return hash * (1 / 2147483648.0)
    }

    private fun gradCoord(seed: Int, xPrimed: Int, yPrimed: Int, xd: Double, yd: Double): Double {
        var hash = hash(seed, xPrimed, yPrimed)
        hash = hash xor (hash shr 15)
        hash = hash and (127 shl 1)

        val xg: Double = gradients2D[hash]
        val yg: Double = gradients2D[hash or 1]

        return xd * xg + yd * yg
    }

    private fun gradCoord(seed: Int, xPrimed: Int, yPrimed: Int, zPrimed: Int, xd: Double, yd: Double, zd: Double): Double {
        var hash = hash(seed, xPrimed, yPrimed, zPrimed)
        hash = hash xor (hash shr 15)
        hash = hash and (63 shl 2)

        val xg: Double = gradients3D[hash]
        val yg: Double = gradients3D[hash or 1]
        val zg: Double = gradients3D[hash or 2]

        return xd * xg + yd * yg + zd * zg
    }

    // Generic noise gen

    private fun genNoiseSingle(seed: Int, x: Double, y: Double): Double {
        when (mNoiseType) {
            NoiseType.OpenSimplex2 -> {
                return singleSimplex(seed, x, y)
            }
            NoiseType.OpenSimplex2S -> {
                return singleOpenSimplex2S(seed, x, y)
            }
            NoiseType.Cellular -> {
                return singleCellular(seed, x, y)
            }
            NoiseType.Perlin -> {
                return singlePerlin(seed, x, y)
            }
            NoiseType.ValueCubic -> {
                return singleValueCubic(seed, x, y)
            }
            NoiseType.Value -> {
                return singleValue(seed, x, y)
            }
        }
    }

    private fun genNoiseSingle(seed: Int, x: Double, y: Double, z: Double): Double {
        when (mNoiseType) {
            NoiseType.OpenSimplex2 -> {
                return singleOpenSimplex2(seed, x, y, z)
            }
            NoiseType.OpenSimplex2S -> {
                return singleOpenSimplex2S(seed, x, y, z)
            }
            NoiseType.Cellular -> {
                return singleCellular(seed, x, y, z)
            }
            NoiseType.Perlin -> {
                return singlePerlin(seed, x, y, z)
            }
            NoiseType.ValueCubic -> {
                return singleValueCubic(seed, x, y, z)
            }
            NoiseType.Value -> {
                return singleValue(seed, x, y, z)
            }
        }
    }

    // Noise Coordinate Transforms (frequency, and possible skew or rotation)

    private fun updateTransformType3D() {
        when (mRotationType3D) {
            RotationType3D.ImproveXYPlanes -> {
                mTransformType3D = TransformType3D.ImproveXYPlanes
            }
            RotationType3D.ImproveXZPlanes -> {
                mTransformType3D = TransformType3D.ImproveXZPlanes
            }
            else -> {
                when (mNoiseType) {
                    NoiseType.OpenSimplex2, NoiseType.OpenSimplex2S -> {
                        mTransformType3D = TransformType3D.DefaultOpenSimplex2
                    }
                    else -> {
                        mTransformType3D = TransformType3D.None
                    }
                }
            }
        }
    }

    private fun updateWarpTransformType3D() {
        when (mRotationType3D) {
            RotationType3D.ImproveXYPlanes -> {
                mWarpTransformType3D = TransformType3D.ImproveXYPlanes
            }
            RotationType3D.ImproveXZPlanes -> {
                mWarpTransformType3D = TransformType3D.ImproveXZPlanes
            }
            else -> {
                when (mDomainWarpType) {
                    DomainWarpType.OpenSimplex2, DomainWarpType.OpenSimplex2Reduced -> {
                        mWarpTransformType3D = TransformType3D.DefaultOpenSimplex2
                    }
                    else -> {
                        mWarpTransformType3D = TransformType3D.None
                    }
                }
            }
        }
    }

    // Fractal FBm

    private fun genFractalFBm(xArg: Double, yArg: Double): Double {
        var x = xArg
        var y = yArg
        var seed = mSeed
        var sum: Double = 0.0
        var amp: Double = mFractalBounding

        for (i in 0 until mOctaves) {
            val noise: Double = genNoiseSingle(seed++, x, y)
            sum += noise * amp
            amp *= lerp(1.0, fastMin(noise + 1, 2.0) * 0.5, mWeightedStrength)

            x *= mLacunarity
            y *= mLacunarity
            amp *= mGain
        }

        return sum
    }

    private fun genFractalFBm(xArg: Double, yArg: Double, zArg: Double): Double {
        var x = xArg
        var y = yArg
        var z = zArg
        var seed = mSeed
        var sum: Double = 0.0
        var amp: Double = mFractalBounding

        for (i in 0 until mOctaves) {
            val noise: Double = genNoiseSingle(seed++, x, y, z)
            sum += noise * amp
            amp *= lerp(1.0, (noise + 1) * 0.5, mWeightedStrength)

            x *= mLacunarity
            y *= mLacunarity
            z *= mLacunarity
            amp *= mGain
        }

        return sum
    }

    // Fractal Ridged

    private fun genFractalRidged(xArg: Double, yArg: Double): Double {
        var x = xArg
        var y = yArg
        var seed = mSeed
        var sum: Double = 0.0
        var amp: Double = mFractalBounding

        for (i in 0 until mOctaves) {
            val noise: Double = fastAbs(genNoiseSingle(seed++, x, y))
            sum += (noise * -2 + 1) * amp
            amp *= lerp(1.0, 1 - noise, mWeightedStrength)

            x *= mLacunarity
            y *= mLacunarity
            amp *= mGain
        }

        return sum
    }

    private fun genFractalRidged(xArg: Double, yArg: Double, zArg: Double): Double {
        var x = xArg
        var y = yArg
        var z = zArg
        var seed = mSeed
        var sum: Double = 0.0
        var amp: Double = mFractalBounding

        for (i in 0 until mOctaves) {
            val noise: Double = fastAbs(genNoiseSingle(seed++, x, y, z))
            sum += (noise * -2 + 1) * amp
            amp *= lerp(1.0, 1 - noise, mWeightedStrength)

            x *= mLacunarity
            y *= mLacunarity
            z *= mLacunarity
            amp *= mGain
        }

        return sum
    }

    // Fractal PingPong

    private fun genFractalPingPong(xArg: Double, yArg: Double): Double {
        var x = xArg
        var y = yArg
        var seed = mSeed
        var sum: Double = 0.0
        var amp: Double = mFractalBounding

        for (i in 0 until mOctaves) {
            val noise: Double = pingPong((genNoiseSingle(seed++, x, y) + 1) * mPingPongStrength)
            sum += (noise - 0.5) * 2 * amp
            amp *= lerp(1.0, noise, mWeightedStrength)

            x *= mLacunarity
            y *= mLacunarity
            amp *= mGain
        }

        return sum
    }

    private fun genFractalPingPong(xArg: Double, yArg: Double, zArg: Double): Double {
        var x = xArg
        var y = yArg
        var z = zArg
        var seed = mSeed
        var sum: Double = 0.0
        var amp: Double = mFractalBounding

        for (i in 0 until mOctaves) {
            val noise: Double = pingPong((genNoiseSingle(seed++, x, y, z) + 1) * mPingPongStrength)
            sum += (noise - 0.5) * 2 * amp
            amp *= lerp(1.0, noise, mWeightedStrength)

            x *= mLacunarity
            y *= mLacunarity
            z *= mLacunarity
            amp *= mGain
        }

        return sum
    }

    // Simplex/OpenSimplex2 Noise

    private fun singleSimplex(seed: Int, x: Double, y: Double): Double {
        // 2D OpenSimplex2 case uses the same algorithm as ordinary Simplex.

        val sqrt3: Double = 1.7320508075688772935274463415059
        val g2: Double = (3 - sqrt3) / 6

        var i = fastFloor(x)
        var j = fastFloor(y)
        val xi: Double = (x - i)
        val yi: Double = (y - j)

        val t: Double = (xi + yi) * g2
        val x0: Double = (xi - t)
        val y0: Double = (yi - t)

        i *= PRIME_X
        j *= PRIME_Y

        var n0: Double = 0.0

        var n1: Double = 0.0

        var n2: Double = 0.0
        val a: Double = 0.5 - x0 * x0 - y0 * y0
        if (a <= 0) {
            n0 = 0.0
        } else {
            n0 = (a * a) * (a * a) * gradCoord(seed, i, j, x0, y0)
        }

        val c: Double = (2 * (1 - 2 * g2) * (1 / g2 - 2)) * t + ((-2 * (1 - 2 * g2) * (1 - 2 * g2)) + a)
        if (c <= 0) {
            n2 = 0.0
        } else {
            val x2: Double = x0 + (2 * g2 - 1)
            val y2: Double = y0 + (2 * g2 - 1)
            n2 = (c * c) * (c * c) * gradCoord(seed, i + PRIME_X, j + PRIME_Y, x2, y2)
        }

        if (y0 > x0) {
            val x1: Double = x0 + g2
            val y1: Double = y0 + (g2 - 1)
            val b: Double = 0.5 - x1 * x1 - y1 * y1
            if (b <= 0) {
                n1 = 0.0
            } else {
                n1 = (b * b) * (b * b) * gradCoord(seed, i, j + PRIME_Y, x1, y1)
            }
        } else {
            val x1: Double = x0 + (g2 - 1)
            val y1: Double = y0 + g2
            val b: Double = 0.5 - x1 * x1 - y1 * y1
            if (b <= 0) {
                n1 = 0.0
            } else {
                n1 = (b * b) * (b * b) * gradCoord(seed, i + PRIME_X, j, x1, y1)
            }
        }

        return (n0 + n1 + n2) * 99.83685446303647
    }

    private fun singleOpenSimplex2(seedArg: Int, x: Double, y: Double, z: Double): Double {
        var seed = seedArg
        // 3D OpenSimplex2 case uses two offset rotated cube grids.

        var i = fastRound(x)
        var j = fastRound(y)
        var k = fastRound(z)
        var x0: Double = (x - i)
        var y0: Double = (y - j)
        var z0: Double = (z - k)

        var xNSign = (-1.0 - x0).toInt() or 1
        var yNSign = (-1.0 - y0).toInt() or 1
        var zNSign = (-1.0 - z0).toInt() or 1

        var ax0: Double = xNSign * -x0
        var ay0: Double = yNSign * -y0
        var az0: Double = zNSign * -z0

        i *= PRIME_X
        j *= PRIME_Y
        k *= PRIME_Z

        var value: Double = 0.0
        var a: Double = (0.6 - x0 * x0) - (y0 * y0 + z0 * z0)

        for (l in 0..1) {
            if (a > 0) {
                value += (a * a) * (a * a) * gradCoord(seed, i, j, k, x0, y0, z0)
            }

            if (ax0 >= ay0 && ax0 >= az0) {
                var b: Double = a + ax0 + ax0
                if (b > 1) {
                    b -= 1
                    value += (b * b) * (b * b) * gradCoord(seed, i - xNSign * PRIME_X, j, k, x0 + xNSign, y0, z0)
                }
            } else if (ay0 > ax0 && ay0 >= az0) {
                var b: Double = a + ay0 + ay0
                if (b > 1) {
                    b -= 1
                    value += (b * b) * (b * b) * gradCoord(seed, i, j - yNSign * PRIME_Y, k, x0, y0 + yNSign, z0)
                }
            } else {
                var b: Double = a + az0 + az0
                if (b > 1) {
                    b -= 1
                    value += (b * b) * (b * b) * gradCoord(seed, i, j, k - zNSign * PRIME_Z, x0, y0, z0 + zNSign)
                }
            }

            if (l == 1) break

            ax0 = 0.5 - ax0
            ay0 = 0.5 - ay0
            az0 = 0.5 - az0

            x0 = xNSign * ax0
            y0 = yNSign * ay0
            z0 = zNSign * az0

            a += (0.75 - ax0) - (ay0 + az0)

            i += ((xNSign shr 1)) and PRIME_X
            j += ((yNSign shr 1)) and PRIME_Y
            k += ((zNSign shr 1)) and PRIME_Z

            xNSign = -xNSign
            yNSign = -yNSign
            zNSign = -zNSign

            seed = seed.inv()
        }

        return value * 32.69428253173828125
    }

    // OpenSimplex2S Noise

    private fun singleOpenSimplex2S(seed: Int, x: Double, y: Double): Double {
        // 2D OpenSimplex2S case is a modified 2D simplex noise.

        val sqrt3: Double = 1.7320508075688772935274463415059
        val g2: Double = (3 - sqrt3) / 6

        var i = fastFloor(x)
        var j = fastFloor(y)
        val xi: Double = (x - i)
        val yi: Double = (y - j)

        i *= PRIME_X
        j *= PRIME_Y
        val i1 = i + PRIME_X
        val j1 = j + PRIME_Y

        val t: Double = (xi + yi) * g2
        val x0: Double = xi - t
        val y0: Double = yi - t

        val a0: Double = (2.0 / 3.0) - x0 * x0 - y0 * y0
        var value: Double = (a0 * a0) * (a0 * a0) * gradCoord(seed, i, j, x0, y0)

        val a1: Double = (2 * (1 - 2 * g2) * (1 / g2 - 2)) * t + ((-2 * (1 - 2 * g2) * (1 - 2 * g2)) + a0)
        val x1: Double = x0 - (1 - 2 * g2)
        val y1: Double = y0 - (1 - 2 * g2)
        value += (a1 * a1) * (a1 * a1) * gradCoord(seed, i1, j1, x1, y1)

        // Nested conditionals were faster than compact bit logic/arithmetic.
        val xmyi: Double = xi - yi
        if (t > g2) {
            if (xi + xmyi > 1) {
                val x2: Double = x0 + (3 * g2 - 2)
                val y2: Double = y0 + (3 * g2 - 1)
                val a2: Double = (2.0 / 3.0) - x2 * x2 - y2 * y2
                if (a2 > 0) {
                    value += (a2 * a2) * (a2 * a2) * gradCoord(seed, i + ((PRIME_X shl 1)), j + PRIME_Y, x2, y2)
                }
            } else {
                val x2: Double = x0 + g2
                val y2: Double = y0 + (g2 - 1)
                val a2: Double = (2.0 / 3.0) - x2 * x2 - y2 * y2
                if (a2 > 0) {
                    value += (a2 * a2) * (a2 * a2) * gradCoord(seed, i, j + PRIME_Y, x2, y2)
                }
            }

            if (yi - xmyi > 1) {
                val x3: Double = x0 + (3 * g2 - 1)
                val y3: Double = y0 + (3 * g2 - 2)
                val a3: Double = (2.0 / 3.0) - x3 * x3 - y3 * y3
                if (a3 > 0) {
                    value += (a3 * a3) * (a3 * a3) * gradCoord(seed, i + PRIME_X, j + ((PRIME_Y shl 1)), x3, y3)
                }
            } else {
                val x3: Double = x0 + (g2 - 1)
                val y3: Double = y0 + g2
                val a3: Double = (2.0 / 3.0) - x3 * x3 - y3 * y3
                if (a3 > 0) {
                    value += (a3 * a3) * (a3 * a3) * gradCoord(seed, i + PRIME_X, j, x3, y3)
                }
            }
        } else {
            if (xi + xmyi < 0) {
                val x2: Double = x0 + (1 - g2)
                val y2: Double = y0 - g2
                val a2: Double = (2.0 / 3.0) - x2 * x2 - y2 * y2
                if (a2 > 0) {
                    value += (a2 * a2) * (a2 * a2) * gradCoord(seed, i - PRIME_X, j, x2, y2)
                }
            } else {
                val x2: Double = x0 + (g2 - 1)
                val y2: Double = y0 + g2
                val a2: Double = (2.0 / 3.0) - x2 * x2 - y2 * y2
                if (a2 > 0) {
                    value += (a2 * a2) * (a2 * a2) * gradCoord(seed, i + PRIME_X, j, x2, y2)
                }
            }

            if (yi < xmyi) {
                val x2: Double = x0 - g2
                val y2: Double = y0 - (g2 - 1)
                val a2: Double = (2.0 / 3.0) - x2 * x2 - y2 * y2
                if (a2 > 0) {
                    value += (a2 * a2) * (a2 * a2) * gradCoord(seed, i, j - PRIME_Y, x2, y2)
                }
            } else {
                val x2: Double = x0 + g2
                val y2: Double = y0 + (g2 - 1)
                val a2: Double = (2.0 / 3.0) - x2 * x2 - y2 * y2
                if (a2 > 0) {
                    value += (a2 * a2) * (a2 * a2) * gradCoord(seed, i, j + PRIME_Y, x2, y2)
                }
            }
        }

        return value * 18.24196194486065
    }

    private fun singleOpenSimplex2S(seed: Int, x: Double, y: Double, z: Double): Double {
        // 3D OpenSimplex2S case uses two offset rotated cube grids.

        var i = fastFloor(x)
        var j = fastFloor(y)
        var k = fastFloor(z)
        val xi: Double = (x - i)
        val yi: Double = (y - j)
        val zi: Double = (z - k)

        i *= PRIME_X
        j *= PRIME_Y
        k *= PRIME_Z
        val seed2 = seed + 1293373

        val xNMask = (-0.5 - xi).toInt()
        val yNMask = (-0.5 - yi).toInt()
        val zNMask = (-0.5 - zi).toInt()

        val x0: Double = xi + xNMask
        val y0: Double = yi + yNMask
        val z0: Double = zi + zNMask
        val a0: Double = 0.75 - x0 * x0 - y0 * y0 - z0 * z0
        var value: Double = (a0 * a0) * (a0 * a0) * gradCoord(
            seed,
            i + (xNMask and PRIME_X),
            j + (yNMask and PRIME_Y),
            k + (zNMask and PRIME_Z),
            x0,
            y0,
            z0
        )

        val x1: Double = xi - 0.5
        val y1: Double = yi - 0.5
        val z1: Double = zi - 0.5
        val a1: Double = 0.75 - x1 * x1 - y1 * y1 - z1 * z1
        value += (a1 * a1) * (a1 * a1) * gradCoord(seed2, i + PRIME_X, j + PRIME_Y, k + PRIME_Z, x1, y1, z1)

        val xAFlipMask0: Double = (((xNMask or 1) shl 1)) * x1
        val yAFlipMask0: Double = (((yNMask or 1) shl 1)) * y1
        val zAFlipMask0: Double = (((zNMask or 1) shl 1)) * z1
        val xAFlipMask1: Double = (-2 - ((xNMask shl 2))) * x1 - 1.0
        val yAFlipMask1: Double = (-2 - ((yNMask shl 2))) * y1 - 1.0
        val zAFlipMask1: Double = (-2 - ((zNMask shl 2))) * z1 - 1.0

        var skip5 = false
        val a2: Double = xAFlipMask0 + a0
        if (a2 > 0) {
            val x2: Double = x0 - (xNMask or 1)
            val y2: Double = y0
            val z2: Double = z0
            value += (a2 * a2) * (a2 * a2) * gradCoord(
                seed,
                i + (xNMask.inv() and PRIME_X),
                j + (yNMask and PRIME_Y),
                k + (zNMask and PRIME_Z),
                x2,
                y2,
                z2
            )
        } else {
            val a3: Double = yAFlipMask0 + zAFlipMask0 + a0
            if (a3 > 0) {
                val x3: Double = x0
                val y3: Double = y0 - (yNMask or 1)
                val z3: Double = z0 - (zNMask or 1)
                value += (a3 * a3) * (a3 * a3) * gradCoord(
                    seed,
                    i + (xNMask and PRIME_X),
                    j + (yNMask.inv() and PRIME_Y),
                    k + (zNMask.inv() and PRIME_Z),
                    x3,
                    y3,
                    z3
                )
            }

            val a4: Double = xAFlipMask1 + a1
            if (a4 > 0) {
                val x4: Double = (xNMask or 1) + x1
                val y4: Double = y1
                val z4: Double = z1
                value += (a4 * a4) * (a4 * a4) * gradCoord(seed2, i + (xNMask and (PRIME_X * 2)), j + PRIME_Y, k + PRIME_Z, x4, y4, z4)
                skip5 = true
            }
        }

        var skip9 = false
        val a6: Double = yAFlipMask0 + a0
        if (a6 > 0) {
            val x6: Double = x0
            val y6: Double = y0 - (yNMask or 1)
            val z6: Double = z0
            value += (a6 * a6) * (a6 * a6) * gradCoord(
                seed,
                i + (xNMask and PRIME_X),
                j + (yNMask.inv() and PRIME_Y),
                k + (zNMask and PRIME_Z),
                x6,
                y6,
                z6
            )
        } else {
            val a7: Double = xAFlipMask0 + zAFlipMask0 + a0
            if (a7 > 0) {
                val x7: Double = x0 - (xNMask or 1)
                val y7: Double = y0
                val z7: Double = z0 - (zNMask or 1)
                value += (a7 * a7) * (a7 * a7) * gradCoord(
                    seed,
                    i + (xNMask.inv() and PRIME_X),
                    j + (yNMask and PRIME_Y),
                    k + (zNMask.inv() and PRIME_Z),
                    x7,
                    y7,
                    z7
                )
            }

            val a8: Double = yAFlipMask1 + a1
            if (a8 > 0) {
                val x8: Double = x1
                val y8: Double = (yNMask or 1) + y1
                val z8: Double = z1
                value += (a8 * a8) * (a8 * a8) * gradCoord(seed2, i + PRIME_X, j + (yNMask and ((PRIME_Y shl 1))), k + PRIME_Z, x8, y8, z8)
                skip9 = true
            }
        }

        var skipD = false
        val aA: Double = zAFlipMask0 + a0
        if (aA > 0) {
            val xA: Double = x0
            val yA: Double = y0
            val zA: Double = z0 - (zNMask or 1)
            value += (aA * aA) * (aA * aA) * gradCoord(
                seed,
                i + (xNMask and PRIME_X),
                j + (yNMask and PRIME_Y),
                k + (zNMask.inv() and PRIME_Z),
                xA,
                yA,
                zA
            )
        } else {
            val aB: Double = xAFlipMask0 + yAFlipMask0 + a0
            if (aB > 0) {
                val xB: Double = x0 - (xNMask or 1)
                val yB: Double = y0 - (yNMask or 1)
                val zB: Double = z0
                value += (aB * aB) * (aB * aB) * gradCoord(
                    seed,
                    i + (xNMask.inv() and PRIME_X),
                    j + (yNMask.inv() and PRIME_Y),
                    k + (zNMask and PRIME_Z),
                    xB,
                    yB,
                    zB
                )
            }

            val aC: Double = zAFlipMask1 + a1
            if (aC > 0) {
                val xC: Double = x1
                val yC: Double = y1
                val zC: Double = (zNMask or 1) + z1
                value += (aC * aC) * (aC * aC) * gradCoord(seed2, i + PRIME_X, j + PRIME_Y, k + (zNMask and ((PRIME_Z shl 1))), xC, yC, zC)
                skipD = true
            }
        }

        if (!skip5) {
            val a5: Double = yAFlipMask1 + zAFlipMask1 + a1
            if (a5 > 0) {
                val x5: Double = x1
                val y5: Double = (yNMask or 1) + y1
                val z5: Double = (zNMask or 1) + z1
                value += (a5 * a5) * (a5 * a5) * gradCoord(
                    seed2,
                    i + PRIME_X,
                    j + (yNMask and ((PRIME_Y shl 1))),
                    k + (zNMask and ((PRIME_Z shl 1))),
                    x5,
                    y5,
                    z5
                )
            }
        }

        if (!skip9) {
            val a9: Double = xAFlipMask1 + zAFlipMask1 + a1
            if (a9 > 0) {
                val x9: Double = (xNMask or 1) + x1
                val y9: Double = y1
                val z9: Double = (zNMask or 1) + z1
                value += (a9 * a9) * (a9 * a9) * gradCoord(
                    seed2,
                    i + (xNMask and (PRIME_X * 2)),
                    j + PRIME_Y,
                    k + (zNMask and ((PRIME_Z shl 1))),
                    x9,
                    y9,
                    z9
                )
            }
        }

        if (!skipD) {
            val aD: Double = xAFlipMask1 + yAFlipMask1 + a1
            if (aD > 0) {
                val xD: Double = (xNMask or 1) + x1
                val yD: Double = (yNMask or 1) + y1
                val zD: Double = z1
                value += (aD * aD) * (aD * aD) * gradCoord(
                    seed2,
                    i + (xNMask and ((PRIME_X shl 1))),
                    j + (yNMask and ((PRIME_Y shl 1))),
                    k + PRIME_Z,
                    xD,
                    yD,
                    zD
                )
            }
        }

        return value * 9.046026385208288
    }

    // Cellular Noise

    private fun singleCellular(seed: Int, x: Double, y: Double): Double {
        val xr = fastRound(x)
        val yr = fastRound(y)

        var distance0: Double = FLOAT_MAX
        var distance1: Double = FLOAT_MAX
        var closestHash = 0

        val cellularJitter: Double = 0.43701595 * mCellularJitterModifier

        var xPrimed = (xr - 1) * PRIME_X
        val yPrimedBase = (yr - 1) * PRIME_Y

        when (mCellularDistanceFunction) {
            CellularDistanceFunction.Manhattan -> {
                for (xi in xr - 1..xr + 1) {
                    var yPrimed = yPrimedBase

                    for (yi in yr - 1..yr + 1) {
                        val hash = hash(seed, xPrimed, yPrimed)
                        val idx = hash and ((255 shl 1))

                        val vecX: Double = (xi - x) + randVecs2D[idx] * cellularJitter
                        val vecY: Double = (yi - y) + randVecs2D[idx or 1] * cellularJitter

                        val newDistance: Double = fastAbs(vecX) + fastAbs(vecY)

                        distance1 = fastMax(fastMin(distance1, newDistance), distance0)
                        if (newDistance < distance0) {
                            distance0 = newDistance
                            closestHash = hash
                        }
                        yPrimed += PRIME_Y
                    }
                    xPrimed += PRIME_X
                }
            }
            CellularDistanceFunction.Hybrid -> {
                for (xi in xr - 1..xr + 1) {
                    var yPrimed = yPrimedBase

                    for (yi in yr - 1..yr + 1) {
                        var hash = hash(seed, xPrimed, yPrimed)
                        var idx = hash and ((255 shl 1))

                        val vecX: Double = (xi - x) + randVecs2D[idx] * cellularJitter
                        val vecY: Double = (yi - y) + randVecs2D[idx or 1] * cellularJitter

                        val newDistance: Double = (fastAbs(vecX) + fastAbs(vecY)) + (vecX * vecX + vecY * vecY)

                        distance1 = fastMax(fastMin(distance1, newDistance), distance0)
                        if (newDistance < distance0) {
                            distance0 = newDistance
                            closestHash = hash
                        }
                        yPrimed += PRIME_Y
                    }
                    xPrimed += PRIME_X
                }
            }
            else -> {
                for (xi in xr - 1..xr + 1) {
                    var yPrimed = yPrimedBase

                    for (yi in yr - 1..yr + 1) {
                        var hash = hash(seed, xPrimed, yPrimed)
                        var idx = hash and ((255 shl 1))

                        val vecX: Double = (xi - x) + randVecs2D[idx] * cellularJitter
                        val vecY: Double = (yi - y) + randVecs2D[idx or 1] * cellularJitter

                        val newDistance: Double = vecX * vecX + vecY * vecY

                        distance1 = fastMax(fastMin(distance1, newDistance), distance0)
                        if (newDistance < distance0) {
                            distance0 = newDistance
                            closestHash = hash
                        }
                        yPrimed += PRIME_Y
                    }
                    xPrimed += PRIME_X
                }
            }
        }

        if (mCellularDistanceFunction == CellularDistanceFunction.Euclidean && mCellularReturnType != CellularReturnType.CellValue) {
            distance0 = fastSqrt(distance0)

            if (mCellularReturnType != CellularReturnType.Distance) {
                distance1 = fastSqrt(distance1)
            }
        }

        when (mCellularReturnType) {
            CellularReturnType.CellValue -> {
                return closestHash * (1 / 2147483648.0)
            }
            CellularReturnType.Distance -> {
                return distance0 - 1
            }
            CellularReturnType.Distance2 -> {
                return distance1 - 1
            }
            CellularReturnType.Distance2Add -> {
                return (distance1 + distance0) * 0.5 - 1
            }
            CellularReturnType.Distance2Sub -> {
                return distance1 - distance0 - 1
            }
            CellularReturnType.Distance2Mul -> {
                return distance1 * distance0 * 0.5 - 1
            }
            CellularReturnType.Distance2Div -> {
                return distance0 / distance1 - 1
            }
        }
    }

    private fun singleCellular(seed: Int, x: Double, y: Double, z: Double): Double {
        val xr = fastRound(x)
        val yr = fastRound(y)
        val zr = fastRound(z)

        var distance0: Double = FLOAT_MAX
        var distance1: Double = FLOAT_MAX
        var closestHash = 0

        val cellularJitter: Double = 0.39614353 * mCellularJitterModifier

        var xPrimed = (xr - 1) * PRIME_X
        val yPrimedBase = (yr - 1) * PRIME_Y
        val zPrimedBase = (zr - 1) * PRIME_Z

        when (mCellularDistanceFunction) {
            CellularDistanceFunction.Euclidean, CellularDistanceFunction.EuclideanSq -> {
                for (xi in xr - 1..xr + 1) {
                    var yPrimed = yPrimedBase

                    for (yi in yr - 1..yr + 1) {
                        var zPrimed = zPrimedBase

                        for (zi in zr - 1..zr + 1) {
                            val hash = hash(seed, xPrimed, yPrimed, zPrimed)
                            val idx = hash and ((255 shl 2))

                            val vecX: Double = (xi - x) + randVecs3D[idx] * cellularJitter
                            val vecY: Double = (yi - y) + randVecs3D[idx or 1] * cellularJitter
                            val vecZ: Double = (zi - z) + randVecs3D[idx or 2] * cellularJitter

                            val newDistance: Double = vecX * vecX + vecY * vecY + vecZ * vecZ

                            distance1 = fastMax(fastMin(distance1, newDistance), distance0)
                            if (newDistance < distance0) {
                                distance0 = newDistance
                                closestHash = hash
                            }
                            zPrimed += PRIME_Z
                        }
                        yPrimed += PRIME_Y
                    }
                    xPrimed += PRIME_X
                }
            }
            CellularDistanceFunction.Manhattan -> {
                for (xi in xr - 1..xr + 1) {
                    var yPrimed = yPrimedBase

                    for (yi in yr - 1..yr + 1) {
                        var zPrimed = zPrimedBase

                        for (zi in zr - 1..zr + 1) {
                            var hash = hash(seed, xPrimed, yPrimed, zPrimed)
                            var idx = hash and ((255 shl 2))

                            val vecX: Double = (xi - x) + randVecs3D[idx] * cellularJitter
                            val vecY: Double = (yi - y) + randVecs3D[idx or 1] * cellularJitter
                            val vecZ: Double = (zi - z) + randVecs3D[idx or 2] * cellularJitter

                            val newDistance: Double = fastAbs(vecX) + fastAbs(vecY) + fastAbs(vecZ)

                            distance1 = fastMax(fastMin(distance1, newDistance), distance0)
                            if (newDistance < distance0) {
                                distance0 = newDistance
                                closestHash = hash
                            }
                            zPrimed += PRIME_Z
                        }
                        yPrimed += PRIME_Y
                    }
                    xPrimed += PRIME_X
                }
            }
            CellularDistanceFunction.Hybrid -> {
                for (xi in xr - 1..xr + 1) {
                    var yPrimed = yPrimedBase

                    for (yi in yr - 1..yr + 1) {
                        var zPrimed = zPrimedBase

                        for (zi in zr - 1..zr + 1) {
                            var hash = hash(seed, xPrimed, yPrimed, zPrimed)
                            var idx = hash and ((255 shl 2))

                            val vecX: Double = (xi - x) + randVecs3D[idx] * cellularJitter
                            val vecY: Double = (yi - y) + randVecs3D[idx or 1] * cellularJitter
                            val vecZ: Double = (zi - z) + randVecs3D[idx or 2] * cellularJitter

                            val newDistance: Double =
                                (fastAbs(vecX) + fastAbs(vecY) + fastAbs(vecZ)) + (vecX * vecX + vecY * vecY + vecZ * vecZ)

                            distance1 = fastMax(fastMin(distance1, newDistance), distance0)
                            if (newDistance < distance0) {
                                distance0 = newDistance
                                closestHash = hash
                            }
                            zPrimed += PRIME_Z
                        }
                        yPrimed += PRIME_Y
                    }
                    xPrimed += PRIME_X
                }
            }
        }

        if (mCellularDistanceFunction == CellularDistanceFunction.Euclidean && mCellularReturnType != CellularReturnType.CellValue) {
            distance0 = fastSqrt(distance0)

            if (mCellularReturnType != CellularReturnType.Distance) {
                distance1 = fastSqrt(distance1)
            }
        }

        when (mCellularReturnType) {
            CellularReturnType.CellValue -> {
                return closestHash * (1 / 2147483648.0)
            }
            CellularReturnType.Distance -> {
                return distance0 - 1
            }
            CellularReturnType.Distance2 -> {
                return distance1 - 1
            }
            CellularReturnType.Distance2Add -> {
                return (distance1 + distance0) * 0.5 - 1
            }
            CellularReturnType.Distance2Sub -> {
                return distance1 - distance0 - 1
            }
            CellularReturnType.Distance2Mul -> {
                return distance1 * distance0 * 0.5 - 1
            }
            CellularReturnType.Distance2Div -> {
                return distance0 / distance1 - 1
            }
        }
    }

    // Perlin Noise

    private fun singlePerlin(seed: Int, x: Double, y: Double): Double {
        var x0 = fastFloor(x)
        var y0 = fastFloor(y)

        val xd0: Double = (x - x0)
        val yd0: Double = (y - y0)
        val xd1: Double = xd0 - 1
        val yd1: Double = yd0 - 1

        val xs: Double = interpQuintic(xd0)
        val ys: Double = interpQuintic(yd0)

        x0 *= PRIME_X
        y0 *= PRIME_Y
        val x1 = x0 + PRIME_X
        val y1 = y0 + PRIME_Y

        val xf0: Double = lerp(gradCoord(seed, x0, y0, xd0, yd0), gradCoord(seed, x1, y0, xd1, yd0), xs)
        val xf1: Double = lerp(gradCoord(seed, x0, y1, xd0, yd1), gradCoord(seed, x1, y1, xd1, yd1), xs)

        return lerp(xf0, xf1, ys) * 1.4247691104677813
    }

    private fun singlePerlin(seed: Int, x: Double, y: Double, z: Double): Double {
        var x0 = fastFloor(x)
        var y0 = fastFloor(y)
        var z0 = fastFloor(z)

        val xd0: Double = (x - x0)
        val yd0: Double = (y - y0)
        val zd0: Double = (z - z0)
        val xd1: Double = xd0 - 1
        val yd1: Double = yd0 - 1
        val zd1: Double = zd0 - 1

        val xs: Double = interpQuintic(xd0)
        val ys: Double = interpQuintic(yd0)
        val zs: Double = interpQuintic(zd0)

        x0 *= PRIME_X
        y0 *= PRIME_Y
        z0 *= PRIME_Z
        val x1 = x0 + PRIME_X
        val y1 = y0 + PRIME_Y
        val z1 = z0 + PRIME_Z

        val xf00: Double = lerp(gradCoord(seed, x0, y0, z0, xd0, yd0, zd0), gradCoord(seed, x1, y0, z0, xd1, yd0, zd0), xs)
        val xf10: Double = lerp(gradCoord(seed, x0, y1, z0, xd0, yd1, zd0), gradCoord(seed, x1, y1, z0, xd1, yd1, zd0), xs)
        val xf01: Double = lerp(gradCoord(seed, x0, y0, z1, xd0, yd0, zd1), gradCoord(seed, x1, y0, z1, xd1, yd0, zd1), xs)
        val xf11: Double = lerp(gradCoord(seed, x0, y1, z1, xd0, yd1, zd1), gradCoord(seed, x1, y1, z1, xd1, yd1, zd1), xs)

        val yf0: Double = lerp(xf00, xf10, ys)
        val yf1: Double = lerp(xf01, xf11, ys)

        return lerp(yf0, yf1, zs) * 0.964921414852142333984375
    }

    // Value Cubic Noise

    private fun singleValueCubic(seed: Int, x: Double, y: Double): Double {
        var x1 = fastFloor(x)
        var y1 = fastFloor(y)

        val xs: Double = (x - x1)
        val ys: Double = (y - y1)

        x1 *= PRIME_X
        y1 *= PRIME_Y
        val x0 = x1 - PRIME_X
        val y0 = y1 - PRIME_Y
        val x2 = x1 + PRIME_X
        val y2 = y1 + PRIME_Y
        val x3 = x1 + ((PRIME_X shl 1))
        val y3 = y1 + ((PRIME_Y shl 1))

        return cubicLerp(
            cubicLerp(
                valCoord(seed, x0, y0),
                valCoord(seed, x1, y0),
                valCoord(seed, x2, y0),
                valCoord(seed, x3, y0),
                xs
            ),
            cubicLerp(
                valCoord(seed, x0, y1),
                valCoord(seed, x1, y1),
                valCoord(seed, x2, y1),
                valCoord(seed, x3, y1),
                xs
            ),
            cubicLerp(
                valCoord(seed, x0, y2),
                valCoord(seed, x1, y2),
                valCoord(seed, x2, y2),
                valCoord(seed, x3, y2),
                xs
            ),
            cubicLerp(
                valCoord(seed, x0, y3),
                valCoord(seed, x1, y3),
                valCoord(seed, x2, y3),
                valCoord(seed, x3, y3),
                xs
            ),
            ys
        ) * (1 / (1.5 * 1.5))
    }

    private fun singleValueCubic(seed: Int, x: Double, y: Double, z: Double): Double {
        var x1 = fastFloor(x)
        var y1 = fastFloor(y)
        var z1 = fastFloor(z)

        val xs: Double = (x - x1)
        val ys: Double = (y - y1)
        val zs: Double = (z - z1)

        x1 *= PRIME_X
        y1 *= PRIME_Y
        z1 *= PRIME_Z

        val x0 = x1 - PRIME_X
        val y0 = y1 - PRIME_Y
        val z0 = z1 - PRIME_Z
        val x2 = x1 + PRIME_X
        val y2 = y1 + PRIME_Y
        val z2 = z1 + PRIME_Z
        val x3 = x1 + ((PRIME_X shl 1))
        val y3 = y1 + ((PRIME_Y shl 1))
        val z3 = z1 + ((PRIME_Z shl 1))

        return cubicLerp(
            cubicLerp(
                cubicLerp(
                    valCoord(seed, x0, y0, z0),
                    valCoord(seed, x1, y0, z0),
                    valCoord(seed, x2, y0, z0),
                    valCoord(seed, x3, y0, z0),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y1, z0),
                    valCoord(seed, x1, y1, z0),
                    valCoord(seed, x2, y1, z0),
                    valCoord(seed, x3, y1, z0),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y2, z0),
                    valCoord(seed, x1, y2, z0),
                    valCoord(seed, x2, y2, z0),
                    valCoord(seed, x3, y2, z0),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y3, z0),
                    valCoord(seed, x1, y3, z0),
                    valCoord(seed, x2, y3, z0),
                    valCoord(seed, x3, y3, z0),
                    xs
                ),
                ys
            ),
            cubicLerp(
                cubicLerp(
                    valCoord(seed, x0, y0, z1),
                    valCoord(seed, x1, y0, z1),
                    valCoord(seed, x2, y0, z1),
                    valCoord(seed, x3, y0, z1),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y1, z1),
                    valCoord(seed, x1, y1, z1),
                    valCoord(seed, x2, y1, z1),
                    valCoord(seed, x3, y1, z1),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y2, z1),
                    valCoord(seed, x1, y2, z1),
                    valCoord(seed, x2, y2, z1),
                    valCoord(seed, x3, y2, z1),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y3, z1),
                    valCoord(seed, x1, y3, z1),
                    valCoord(seed, x2, y3, z1),
                    valCoord(seed, x3, y3, z1),
                    xs
                ),
                ys
            ),
            cubicLerp(
                cubicLerp(
                    valCoord(seed, x0, y0, z2),
                    valCoord(seed, x1, y0, z2),
                    valCoord(seed, x2, y0, z2),
                    valCoord(seed, x3, y0, z2),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y1, z2),
                    valCoord(seed, x1, y1, z2),
                    valCoord(seed, x2, y1, z2),
                    valCoord(seed, x3, y1, z2),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y2, z2),
                    valCoord(seed, x1, y2, z2),
                    valCoord(seed, x2, y2, z2),
                    valCoord(seed, x3, y2, z2),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y3, z2),
                    valCoord(seed, x1, y3, z2),
                    valCoord(seed, x2, y3, z2),
                    valCoord(seed, x3, y3, z2),
                    xs
                ),
                ys
            ),
            cubicLerp(
                cubicLerp(
                    valCoord(seed, x0, y0, z3),
                    valCoord(seed, x1, y0, z3),
                    valCoord(seed, x2, y0, z3),
                    valCoord(seed, x3, y0, z3),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y1, z3),
                    valCoord(seed, x1, y1, z3),
                    valCoord(seed, x2, y1, z3),
                    valCoord(seed, x3, y1, z3),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y2, z3),
                    valCoord(seed, x1, y2, z3),
                    valCoord(seed, x2, y2, z3),
                    valCoord(seed, x3, y2, z3),
                    xs
                ),
                cubicLerp(
                    valCoord(seed, x0, y3, z3),
                    valCoord(seed, x1, y3, z3),
                    valCoord(seed, x2, y3, z3),
                    valCoord(seed, x3, y3, z3),
                    xs
                ),
                ys
            ),
            zs
        ) * (1 / (1.5 * 1.5 * 1.5))
    }

    // Value Noise

    private fun singleValue(seed: Int, x: Double, y: Double): Double {
        var x0 = fastFloor(x)
        var y0 = fastFloor(y)

        val xs: Double = interpHermite((x - x0))
        val ys: Double = interpHermite((y - y0))

        x0 *= PRIME_X
        y0 *= PRIME_Y
        val x1 = x0 + PRIME_X
        val y1 = y0 + PRIME_Y

        val xf0: Double = lerp(valCoord(seed, x0, y0), valCoord(seed, x1, y0), xs)
        val xf1: Double = lerp(valCoord(seed, x0, y1), valCoord(seed, x1, y1), xs)

        return lerp(xf0, xf1, ys)
    }

    private fun singleValue(seed: Int, x: Double, y: Double, z: Double): Double {
        var x0 = fastFloor(x)
        var y0 = fastFloor(y)
        var z0 = fastFloor(z)

        val xs: Double = interpHermite((x - x0))
        val ys: Double = interpHermite((y - y0))
        val zs: Double = interpHermite((z - z0))

        x0 *= PRIME_X
        y0 *= PRIME_Y
        z0 *= PRIME_Z
        val x1 = x0 + PRIME_X
        val y1 = y0 + PRIME_Y
        val z1 = z0 + PRIME_Z

        val xf00: Double = lerp(valCoord(seed, x0, y0, z0), valCoord(seed, x1, y0, z0), xs)
        val xf10: Double = lerp(valCoord(seed, x0, y1, z0), valCoord(seed, x1, y1, z0), xs)
        val xf01: Double = lerp(valCoord(seed, x0, y0, z1), valCoord(seed, x1, y0, z1), xs)
        val xf11: Double = lerp(valCoord(seed, x0, y1, z1), valCoord(seed, x1, y1, z1), xs)

        val yf0: Double = lerp(xf00, xf10, ys)
        val yf1: Double = lerp(xf01, xf11, ys)

        return lerp(yf0, yf1, zs)
    }

    // Domain Warp

    private fun doSingleDomainWarp(seed: Int, amp: Double, freq: Double, x: Double, y: Double, coord: Vector2) {
        when (mDomainWarpType) {
            DomainWarpType.OpenSimplex2 -> {
                singleDomainWarpSimplexGradient(seed, amp * 38.283687591552734375, freq, x, y, coord, false)
            }
            DomainWarpType.OpenSimplex2Reduced -> {
                singleDomainWarpSimplexGradient(seed, amp * 16.0, freq, x, y, coord, true)
            }
            DomainWarpType.BasicGrid -> {
                singleDomainWarpBasicGrid(seed, amp, freq, x, y, coord)
            }
        }
    }

    private fun doSingleDomainWarp(seed: Int, amp: Double, freq: Double, x: Double, y: Double, z: Double, coord: Vector3) {
        when (mDomainWarpType) {
            DomainWarpType.OpenSimplex2 -> {
                singleDomainWarpOpenSimplex2Gradient(seed, amp * 32.69428253173828125, freq, x, y, z, coord, false)
            }
            DomainWarpType.OpenSimplex2Reduced -> {
                singleDomainWarpOpenSimplex2Gradient(seed, amp * 7.71604938271605, freq, x, y, z, coord, true)
            }
            DomainWarpType.BasicGrid -> {
                singleDomainWarpBasicGrid(seed, amp, freq, x, y, z, coord)
            }
        }
    }

    // Domain Warp Single Wrapper

    private fun domainWarpSingle(coord: Vector2) {
        val seed = mSeed
        val amp: Double = mDomainWarpAmp * mFractalBounding
        val freq: Double = mFrequency

        var xs: Double = coord.x
        var ys: Double = coord.y
        when (mDomainWarpType) {
            DomainWarpType.OpenSimplex2, DomainWarpType.OpenSimplex2Reduced -> {
                val sqrt3: Double = 1.7320508075688772935274463415059
                val f2: Double = 0.5 * (sqrt3 - 1)
                val t: Double = (xs + ys) * f2
                xs += t
                ys += t
            }
            else -> {
            }
        }

        doSingleDomainWarp(seed, amp, freq, xs, ys, coord)
    }

    private fun domainWarpSingle(coord: Vector3) {
        val seed = mSeed
        val amp: Double = mDomainWarpAmp * mFractalBounding
        val freq: Double = mFrequency

        var xs: Double = coord.x
        var ys: Double = coord.y
        var zs: Double = coord.z
        when (mWarpTransformType3D) {
            TransformType3D.ImproveXYPlanes -> {
                val xy: Double = xs + ys
                val s2: Double = xy * -0.211324865405187
                zs *= 0.577350269189626
                xs += s2 - zs
                ys = ys + s2 - zs
                zs += xy * 0.577350269189626
            }
            TransformType3D.ImproveXZPlanes -> {
                val xz: Double = xs + zs
                val s2: Double = xz * -0.211324865405187
                ys *= 0.577350269189626
                xs += s2 - ys
                zs += s2 - ys
                ys += xz * 0.577350269189626
            }
            TransformType3D.DefaultOpenSimplex2 -> {
                val r3: Double = (2.0 / 3.0)
                val r: Double = (xs + ys + zs) * r3; // Rotation, not skew
                xs = r - xs
                ys = r - ys
                zs = r - zs
            }
            else -> {
            }
        }

        doSingleDomainWarp(seed, amp, freq, xs, ys, zs, coord)
    }

    // Domain Warp Fractal Progressive

    private fun domainWarpFractalProgressive(coord: Vector2) {
        var seed = mSeed
        var amp: Double = mDomainWarpAmp * mFractalBounding
        var freq: Double = mFrequency

        for (i in 0 until mOctaves) {
            var xs: Double = coord.x
            var ys: Double = coord.y
            when (mDomainWarpType) {
                DomainWarpType.OpenSimplex2, DomainWarpType.OpenSimplex2Reduced -> {
                    val sqrt3: Double = 1.7320508075688772935274463415059
                    val f2: Double = 0.5 * (sqrt3 - 1)
                    val t: Double = (xs + ys) * f2
                    xs += t
                    ys += t
                }
                else -> {
                }
            }

            doSingleDomainWarp(seed, amp, freq, xs, ys, coord)

            seed++
            amp *= mGain
            freq *= mLacunarity
        }
    }

    private fun domainWarpFractalProgressive(coord: Vector3) {
        var seed = mSeed
        var amp: Double = mDomainWarpAmp * mFractalBounding
        var freq: Double = mFrequency

        for (i in 0 until mOctaves) {
            var xs: Double = coord.x
            var ys: Double = coord.y
            var zs: Double = coord.z
            when (mWarpTransformType3D) {
                TransformType3D.ImproveXYPlanes -> {
                    val xy: Double = xs + ys
                    val s2: Double = xy * -0.211324865405187
                    zs *= 0.577350269189626
                    xs += s2 - zs
                    ys = ys + s2 - zs
                    zs += xy * 0.577350269189626
                }
                TransformType3D.ImproveXZPlanes -> {
                    val xz: Double = xs + zs
                    val s2: Double = xz * -0.211324865405187
                    ys *= 0.577350269189626
                    xs += s2 - ys
                    zs += s2 - ys
                    ys += xz * 0.577350269189626
                }
                TransformType3D.DefaultOpenSimplex2 -> {
                    val r3: Double = (2.0 / 3.0)
                    val r: Double = (xs + ys + zs) * r3; // Rotation, not skew
                    xs = r - xs
                    ys = r - ys
                    zs = r - zs
                }
                else -> {
                }
            }

            doSingleDomainWarp(seed, amp, freq, xs, ys, zs, coord)

            seed++
            amp *= mGain
            freq *= mLacunarity
        }
    }

    // Domain Warp Fractal Independant
    private fun domainWarpFractalIndependent(coord: Vector2) {
        var xs: Double = coord.x
        var ys: Double = coord.y
        when (mDomainWarpType) {
            DomainWarpType.OpenSimplex2, DomainWarpType.OpenSimplex2Reduced -> {
                val sqrt3: Double = 1.7320508075688772935274463415059
                val f2: Double = 0.5 * (sqrt3 - 1)
                val t: Double = (xs + ys) * f2
                xs += t
                ys += t
            }
            else -> {
            }
        }

        var seed = mSeed
        var amp: Double = mDomainWarpAmp * mFractalBounding
        var freq: Double = mFrequency

        for (i in 0 until mOctaves) {
            doSingleDomainWarp(seed, amp, freq, xs, ys, coord)

            seed++
            amp *= mGain
            freq *= mLacunarity
        }
    }

    private fun domainWarpFractalIndependent(coord: Vector3) {
        var xs: Double = coord.x
        var ys: Double = coord.y
        var zs: Double = coord.z
        when (mWarpTransformType3D) {
            TransformType3D.ImproveXYPlanes -> {
                val xy: Double = xs + ys
                val s2: Double = xy * -0.211324865405187
                zs *= 0.577350269189626
                xs += s2 - zs
                ys = ys + s2 - zs
                zs += xy * 0.577350269189626
            }
            TransformType3D.ImproveXZPlanes -> {
                val xz: Double = xs + zs
                val s2: Double = xz * -0.211324865405187
                ys *= 0.577350269189626
                xs += s2 - ys
                zs += s2 - ys
                ys += xz * 0.577350269189626
            }
            TransformType3D.DefaultOpenSimplex2 -> {
                val r3: Double = (2.0 / 3.0)
                val r: Double = (xs + ys + zs) * r3; // Rotation, not skew
                xs = r - xs
                ys = r - ys
                zs = r - zs
            }
            else -> {
            }
        }

        var seed = mSeed
        var amp: Double = mDomainWarpAmp * mFractalBounding
        var freq: Double = mFrequency

        for (i in 0 until mOctaves) {
            doSingleDomainWarp(seed, amp, freq, xs, ys, zs, coord)

            seed++
            amp *= mGain
            freq *= mLacunarity
        }
    }

    // Domain Warp Basic Grid

    private fun singleDomainWarpBasicGrid(seed: Int, warpAmp: Double, frequency: Double, x: Double, y: Double, coord: Vector2) {
        val xf: Double = x * frequency
        val yf: Double = y * frequency

        var x0 = fastFloor(xf)
        var y0 = fastFloor(yf)

        val xs: Double = interpHermite((xf - x0))
        val ys: Double = interpHermite((yf - y0))

        x0 *= PRIME_X
        y0 *= PRIME_Y
        val x1 = x0 + PRIME_X
        val y1 = y0 + PRIME_Y

        var hash0 = hash(seed, x0, y0) and ((255 shl 1))
        var hash1 = hash(seed, x1, y0) and ((255 shl 1))

        val lx0x: Double = lerp(randVecs2D[hash0], randVecs2D[hash1], xs)
        val ly0x: Double = lerp(randVecs2D[hash0 or 1], randVecs2D[hash1 or 1], xs)

        hash0 = hash(seed, x0, y1) and ((255 shl 1))
        hash1 = hash(seed, x1, y1) and ((255 shl 1))

        val lx1x: Double = lerp(randVecs2D[hash0], randVecs2D[hash1], xs)
        val ly1x: Double = lerp(randVecs2D[hash0 or 1], randVecs2D[hash1 or 1], xs)

        coord.x += lerp(lx0x, lx1x, ys) * warpAmp
        coord.y += lerp(ly0x, ly1x, ys) * warpAmp
    }

    private fun singleDomainWarpBasicGrid(seed: Int, warpAmp: Double, frequency: Double, x: Double, y: Double, z: Double, coord: Vector3) {
        val xf: Double = x * frequency
        val yf: Double = y * frequency
        val zf: Double = z * frequency

        var x0 = fastFloor(xf)
        var y0 = fastFloor(yf)
        var z0 = fastFloor(zf)

        val xs: Double = interpHermite((xf - x0))
        val ys: Double = interpHermite((yf - y0))
        val zs: Double = interpHermite((zf - z0))

        x0 *= PRIME_X
        y0 *= PRIME_Y
        z0 *= PRIME_Z
        val x1 = x0 + PRIME_X
        val y1 = y0 + PRIME_Y
        val z1 = z0 + PRIME_Z

        var hash0 = hash(seed, x0, y0, z0) and ((255 shl 2))
        var hash1 = hash(seed, x1, y0, z0) and ((255 shl 2))

        var lx0x: Double = lerp(randVecs3D[hash0], randVecs3D[hash1], xs)
        var ly0x: Double = lerp(randVecs3D[hash0 or 1], randVecs3D[hash1 or 1], xs)
        var lz0x: Double = lerp(randVecs3D[hash0 or 2], randVecs3D[hash1 or 2], xs)

        hash0 = hash(seed, x0, y1, z0) and ((255 shl 2))
        hash1 = hash(seed, x1, y1, z0) and ((255 shl 2))

        var lx1x: Double = lerp(randVecs3D[hash0], randVecs3D[hash1], xs)
        var ly1x: Double = lerp(randVecs3D[hash0 or 1], randVecs3D[hash1 or 1], xs)
        var lz1x: Double = lerp(randVecs3D[hash0 or 2], randVecs3D[hash1 or 2], xs)

        val lx0y: Double = lerp(lx0x, lx1x, ys)
        val ly0y: Double = lerp(ly0x, ly1x, ys)
        val lz0y: Double = lerp(lz0x, lz1x, ys)

        hash0 = hash(seed, x0, y0, z1) and ((255 shl 2))
        hash1 = hash(seed, x1, y0, z1) and ((255 shl 2))

        lx0x = lerp(randVecs3D[hash0], randVecs3D[hash1], xs)
        ly0x = lerp(randVecs3D[hash0 or 1], randVecs3D[hash1 or 1], xs)
        lz0x = lerp(randVecs3D[hash0 or 2], randVecs3D[hash1 or 2], xs)

        hash0 = hash(seed, x0, y1, z1) and ((255 shl 2))
        hash1 = hash(seed, x1, y1, z1) and ((255 shl 2))

        lx1x = lerp(randVecs3D[hash0], randVecs3D[hash1], xs)
        ly1x = lerp(randVecs3D[hash0 or 1], randVecs3D[hash1 or 1], xs)
        lz1x = lerp(randVecs3D[hash0 or 2], randVecs3D[hash1 or 2], xs)

        coord.x += lerp(lx0y, lerp(lx0x, lx1x, ys), zs) * warpAmp
        coord.y += lerp(ly0y, lerp(ly0x, ly1x, ys), zs) * warpAmp
        coord.z += lerp(lz0y, lerp(lz0x, lz1x, ys), zs) * warpAmp
    }

    // Domain Warp Simplex/OpenSimplex2
    private fun singleDomainWarpSimplexGradient(
        seed: Int,
        warpAmp: Double,
        frequency: Double,
        xArg: Double,
        yArg: Double,
        coord: Vector2,
        outGradOnly: Boolean
    ) {
        var x = xArg
        var y = yArg
        val sqrt3: Double = 1.7320508075688772935274463415059
        val g2: Double = (3 - sqrt3) / 6

        x *= frequency
        y *= frequency

        var i = fastFloor(x)
        var j = fastFloor(y)
        val xi: Double = (x - i)
        val yi: Double = (y - j)

        val t: Double = (xi + yi) * g2
        val x0: Double = (xi - t)
        val y0: Double = (yi - t)

        i *= PRIME_X
        j *= PRIME_Y

        var vx: Double = 0.0
        var vy: Double = 0.0

        val a: Double = 0.5 - x0 * x0 - y0 * y0
        if (a > 0) {
            val aaaa: Double = (a * a) * (a * a)
            var xo: Double = 0.0
            var yo: Double = 0.0
            if (outGradOnly) {
                val hash = hash(seed, i, j) and ((255 shl 1))
                xo = randVecs2D[hash]
                yo = randVecs2D[hash or 1]
            } else {
                var hash = hash(seed, i, j)
                val index1 = hash and ((127 shl 1))
                val index2 = ((hash shr 7)) and ((255 shl 1))
                val xg: Double = gradients2D[index1]
                val yg: Double = gradients2D[index1 or 1]
                val value: Double = x0 * xg + y0 * yg
                val xgo: Double = randVecs2D[index2]
                val ygo: Double = randVecs2D[index2 or 1]
                xo = value * xgo
                yo = value * ygo
            }
            vx += aaaa * xo
            vy += aaaa * yo
        }

        val c: Double = (2 * (1 - 2 * g2) * (1 / g2 - 2)) * t + ((-2 * (1 - 2 * g2) * (1 - 2 * g2)) + a)
        if (c > 0) {
            val x2: Double = x0 + (2 * g2 - 1)
            val y2: Double = y0 + (2 * g2 - 1)
            val cccc: Double = (c * c) * (c * c)
            var xo: Double = 0.0
            var yo: Double = 0.0
            if (outGradOnly) {
                var hash = hash(seed, i + PRIME_X, j + PRIME_Y) and ((255 shl 1))
                xo = randVecs2D[hash]
                yo = randVecs2D[hash or 1]
            } else {
                var hash = hash(seed, i + PRIME_X, j + PRIME_Y)
                var index1 = hash and ((127 shl 1))
                var index2 = ((hash shr 7)) and ((255 shl 1))
                val xg: Double = gradients2D[index1]
                val yg: Double = gradients2D[index1 or 1]
                val value: Double = x2 * xg + y2 * yg
                val xgo: Double = randVecs2D[index2]
                val ygo: Double = randVecs2D[index2 or 1]
                xo = value * xgo
                yo = value * ygo
            }
            vx += cccc * xo
            vy += cccc * yo
        }

        if (y0 > x0) {
            val x1: Double = x0 + g2
            val y1: Double = y0 + (g2 - 1)
            val b: Double = 0.5 - x1 * x1 - y1 * y1
            if (b > 0) {
                val bbbb: Double = (b * b) * (b * b)
                var xo: Double = 0.0
                var yo: Double = 0.0
                if (outGradOnly) {
                    var hash = hash(seed, i, j + PRIME_Y) and ((255 shl 1))
                    xo = randVecs2D[hash]
                    yo = randVecs2D[hash or 1]
                } else {
                    var hash = hash(seed, i, j + PRIME_Y)
                    var index1 = hash and ((127 shl 1))
                    var index2 = ((hash shr 7)) and ((255 shl 1))
                    val xg: Double = gradients2D[index1]
                    val yg: Double = gradients2D[index1 or 1]
                    val value: Double = x1 * xg + y1 * yg
                    val xgo: Double = randVecs2D[index2]
                    val ygo: Double = randVecs2D[index2 or 1]
                    xo = value * xgo
                    yo = value * ygo
                }
                vx += bbbb * xo
                vy += bbbb * yo
            }
        } else {
            val x1: Double = x0 + (g2 - 1)
            val y1: Double = y0 + g2
            val b: Double = 0.5 - x1 * x1 - y1 * y1
            if (b > 0) {
                val bbbb: Double = (b * b) * (b * b)
                var xo: Double = 0.0
                var yo: Double = 0.0
                if (outGradOnly) {
                    var hash = hash(seed, i + PRIME_X, j) and ((255 shl 1))
                    xo = randVecs2D[hash]
                    yo = randVecs2D[hash or 1]
                } else {
                    var hash = hash(seed, i + PRIME_X, j)
                    var index1 = hash and ((127 shl 1))
                    var index2 = ((hash shr 7)) and ((255 shl 1))
                    val xg: Double = gradients2D[index1]
                    val yg: Double = gradients2D[index1 or 1]
                    val value: Double = x1 * xg + y1 * yg
                    val xgo: Double = randVecs2D[index2]
                    val ygo: Double = randVecs2D[index2 or 1]
                    xo = value * xgo
                    yo = value * ygo
                }
                vx += bbbb * xo
                vy += bbbb * yo
            }
        }

        coord.x += vx * warpAmp
        coord.y += vy * warpAmp
    }

    private fun singleDomainWarpOpenSimplex2Gradient(
        seedArg: Int,
        warpAmp: Double,
        frequency: Double,
        xArg: Double,
        yArg: Double,
        zArg: Double,
        coord: Vector3,
        outGradOnly: Boolean
    ) {
        var seed = seedArg
        var x = xArg
        var y = yArg
        var z = zArg
        x *= frequency
        y *= frequency
        z *= frequency

        var i = fastRound(x)
        var j = fastRound(y)
        var k = fastRound(z)
        var x0: Double = x - i
        var y0: Double = y - j
        var z0: Double = z - k

        var xNSign = (-x0 - 1.0).toInt() or 1
        var yNSign = (-y0 - 1.0).toInt() or 1
        var zNSign = (-z0 - 1.0).toInt() or 1

        var ax0: Double = xNSign * -x0
        var ay0: Double = yNSign * -y0
        var az0: Double = zNSign * -z0

        i *= PRIME_X
        j *= PRIME_Y
        k *= PRIME_Z

        var vx: Double = 0.0

        var vy: Double = 0.0

        var vz: Double = 0.0

        var a: Double = (0.6 - x0 * x0) - (y0 * y0 + z0 * z0)
        for (l in 0..1) {
            if (a > 0) {
                val aaaa: Double = (a * a) * (a * a)
                var xo: Double = 0.0
                var yo: Double = 0.0
                var zo: Double = 0.0
                if (outGradOnly) {
                    val hash = hash(seed, i, j, k) and ((255 shl 2))
                    xo = randVecs3D[hash]
                    yo = randVecs3D[hash or 1]
                    zo = randVecs3D[hash or 2]
                } else {
                    var hash = hash(seed, i, j, k)
                    val index1 = hash and ((63 shl 2))
                    val index2 = ((hash shr 6)) and ((255 shl 2))
                    val xg: Double = gradients3D[index1]
                    val yg: Double = gradients3D[index1 or 1]
                    val zg: Double = gradients3D[index1 or 2]
                    val value: Double = x0 * xg + y0 * yg + z0 * zg
                    val xgo: Double = randVecs3D[index2]
                    val ygo: Double = randVecs3D[index2 or 1]
                    val zgo: Double = randVecs3D[index2 or 2]
                    xo = value * xgo
                    yo = value * ygo
                    zo = value * zgo
                }
                vx += aaaa * xo
                vy += aaaa * yo
                vz += aaaa * zo
            }

            var b: Double = a
            var i1 = i
            var j1 = j
            var k1 = k
            var x1: Double = x0
            var y1: Double = y0
            var z1: Double = z0

            if (ax0 >= ay0 && ax0 >= az0) {
                x1 += xNSign
                b = b + ax0 + ax0
                i1 -= xNSign * PRIME_X
            } else if (ay0 > ax0 && ay0 >= az0) {
                y1 += yNSign
                b = b + ay0 + ay0
                j1 -= yNSign * PRIME_Y
            } else {
                z1 += zNSign
                b = b + az0 + az0
                k1 -= zNSign * PRIME_Z
            }

            if (b > 1) {
                b -= 1
                val bbbb: Double = (b * b) * (b * b)
                var xo: Double = 0.0
                var yo: Double = 0.0
                var zo: Double = 0.0
                if (outGradOnly) {
                    var hash = hash(seed, i1, j1, k1) and ((255 shl 2))
                    xo = randVecs3D[hash]
                    yo = randVecs3D[hash or 1]
                    zo = randVecs3D[hash or 2]
                } else {
                    var hash = hash(seed, i1, j1, k1)
                    var index1 = hash and ((63 shl 2))
                    var index2 = ((hash shr 6)) and ((255 shl 2))
                    val xg: Double = gradients3D[index1]
                    val yg: Double = gradients3D[index1 or 1]
                    val zg: Double = gradients3D[index1 or 2]
                    val value: Double = x1 * xg + y1 * yg + z1 * zg
                    val xgo: Double = randVecs3D[index2]
                    val ygo: Double = randVecs3D[index2 or 1]
                    val zgo: Double = randVecs3D[index2 or 2]
                    xo = value * xgo
                    yo = value * ygo
                    zo = value * zgo
                }
                vx += bbbb * xo
                vy += bbbb * yo
                vz += bbbb * zo
            }

            if (l == 1) break

            ax0 = 0.5 - ax0
            ay0 = 0.5 - ay0
            az0 = 0.5 - az0

            x0 = xNSign * ax0
            y0 = yNSign * ay0
            z0 = zNSign * az0

            a += (0.75 - ax0) - (ay0 + az0)

            i += ((xNSign shr 1)) and PRIME_X
            j += ((yNSign shr 1)) and PRIME_Y
            k += ((zNSign shr 1)) and PRIME_Z

            xNSign = -xNSign
            yNSign = -yNSign
            zNSign = -zNSign

            seed += 1293373
        }

        coord.x += vx * warpAmp
        coord.y += vy * warpAmp
        coord.z += vz * warpAmp
    }
}
