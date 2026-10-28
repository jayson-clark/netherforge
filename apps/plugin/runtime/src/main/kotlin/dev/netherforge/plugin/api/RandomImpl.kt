package dev.netherforge.plugin.api

import dev.netherforge.plugin.binding.Caller
import dev.netherforge.plugin.binding.NfRandomApi
import dev.netherforge.plugin.binding.NoiseOptions
import dev.netherforge.plugin.lua.LuaApiException
import java.util.SplittableRandom
import java.util.UUID
import kotlin.math.floor

/**
 * `nf.random`'s noise and ids. Its generators (`nf.random.new`, `Random`) are
 * the prelude's: they're plain Lua state, collected with the script's tables.
 */
internal class NfRandomImpl : NfRandomApi {
    override fun noise2(caller: Caller, x: Double, z: Double, options: NoiseOptions?): Double {
        val scale = scale(options)
        return SimplexNoise.of(options?.seed ?: 0).noise2(finite(x, "x") * scale, finite(z, "z") * scale)
    }

    override fun noise3(caller: Caller, x: Double, y: Double, z: Double, options: NoiseOptions?): Double {
        val scale = scale(options)
        return SimplexNoise.of(options?.seed ?: 0)
            .noise3(finite(x, "x") * scale, finite(y, "y") * scale, finite(z, "z") * scale)
    }

    override fun uuid(caller: Caller): String = UUID.randomUUID().toString()

    private fun scale(options: NoiseOptions?): Double = finite(options?.scale ?: 1.0, "options.scale")

    private fun finite(value: Double, name: String): Double {
        if (!value.isFinite()) throw LuaApiException("$name must be a finite number, not $value")
        return value
    }
}

/**
 * Simplex noise in 2D and 3D (after Stefan Gustavson's public domain
 * implementation), over a permutation that a seed shuffles, so each seed is
 * its own pattern and the same seed is the same pattern on every server.
 * Answers are scaled to about -1 to 1 and clamped there.
 */
internal class SimplexNoise private constructor(seed: Long) {
    private val perm = IntArray(512)
    private val permMod12 = IntArray(512)

    init {
        val values = IntArray(256) { it }
        val random = SplittableRandom(seed)
        for (i in 255 downTo 1) {
            val j = random.nextInt(i + 1)
            values[i] = values[j].also { values[j] = values[i] }
        }
        for (i in 0 until 512) {
            perm[i] = values[i and 255]
            permMod12[i] = perm[i] % 12
        }
    }

    fun noise2(xin: Double, yin: Double): Double {
        val s = (xin + yin) * F2
        val i = floor(xin + s)
        val j = floor(yin + s)
        val t = (i + j) * G2
        val x0 = xin - (i - t)
        val y0 = yin - (j - t)
        val (i1, j1) = if (x0 > y0) 1 to 0 else 0 to 1
        val x1 = x0 - i1 + G2
        val y1 = y0 - j1 + G2
        val x2 = x0 - 1 + 2 * G2
        val y2 = y0 - 1 + 2 * G2
        val ii = wrap(i)
        val jj = wrap(j)
        val n0 = corner2(permMod12[ii + perm[jj]], x0, y0)
        val n1 = corner2(permMod12[ii + i1 + perm[jj + j1]], x1, y1)
        val n2 = corner2(permMod12[ii + 1 + perm[jj + 1]], x2, y2)
        return (70 * (n0 + n1 + n2)).coerceIn(-1.0, 1.0)
    }

    fun noise3(xin: Double, yin: Double, zin: Double): Double {
        val s = (xin + yin + zin) * F3
        val i = floor(xin + s)
        val j = floor(yin + s)
        val k = floor(zin + s)
        val t = (i + j + k) * G3
        val x0 = xin - (i - t)
        val y0 = yin - (j - t)
        val z0 = zin - (k - t)
        // Which of the six tetrahedra in the cube the point is in: the order of x0, y0 and z0.
        val corners = when {
            x0 >= y0 && y0 >= z0 -> intArrayOf(1, 0, 0, 1, 1, 0)
            x0 >= y0 && x0 >= z0 -> intArrayOf(1, 0, 0, 1, 0, 1)
            x0 >= y0 -> intArrayOf(0, 0, 1, 1, 0, 1)
            y0 < z0 -> intArrayOf(0, 0, 1, 0, 1, 1)
            x0 < z0 -> intArrayOf(0, 1, 0, 0, 1, 1)
            else -> intArrayOf(0, 1, 0, 1, 1, 0)
        }
        val (i1, j1, k1) = Triple(corners[0], corners[1], corners[2])
        val (i2, j2, k2) = Triple(corners[3], corners[4], corners[5])
        val x1 = x0 - i1 + G3
        val y1 = y0 - j1 + G3
        val z1 = z0 - k1 + G3
        val x2 = x0 - i2 + 2 * G3
        val y2 = y0 - j2 + 2 * G3
        val z2 = z0 - k2 + 2 * G3
        val x3 = x0 - 1 + 3 * G3
        val y3 = y0 - 1 + 3 * G3
        val z3 = z0 - 1 + 3 * G3
        val ii = wrap(i)
        val jj = wrap(j)
        val kk = wrap(k)
        val n0 = corner3(permMod12[ii + perm[jj + perm[kk]]], x0, y0, z0)
        val n1 = corner3(permMod12[ii + i1 + perm[jj + j1 + perm[kk + k1]]], x1, y1, z1)
        val n2 = corner3(permMod12[ii + i2 + perm[jj + j2 + perm[kk + k2]]], x2, y2, z2)
        val n3 = corner3(permMod12[ii + 1 + perm[jj + 1 + perm[kk + 1]]], x3, y3, z3)
        return (32 * (n0 + n1 + n2 + n3)).coerceIn(-1.0, 1.0)
    }

    private fun corner2(gradient: Int, x: Double, y: Double): Double {
        var t = 0.5 - x * x - y * y
        if (t < 0) return 0.0
        t *= t
        return t * t * (GRADIENTS[gradient][0] * x + GRADIENTS[gradient][1] * y)
    }

    private fun corner3(gradient: Int, x: Double, y: Double, z: Double): Double {
        var t = 0.6 - x * x - y * y - z * z
        if (t < 0) return 0.0
        t *= t
        val g = GRADIENTS[gradient]
        return t * t * (g[0] * x + g[1] * y + g[2] * z)
    }

    companion object {
        private val F2 = 0.5 * (Math.sqrt(3.0) - 1)
        private val G2 = (3 - Math.sqrt(3.0)) / 6
        private const val F3 = 1.0 / 3
        private const val G3 = 1.0 / 6

        /** The cube's twelve edge midpoints, which 2D noise reads as their x and y. */
        private val GRADIENTS = arrayOf(
            doubleArrayOf(1.0, 1.0, 0.0), doubleArrayOf(-1.0, 1.0, 0.0), doubleArrayOf(1.0, -1.0, 0.0),
            doubleArrayOf(-1.0, -1.0, 0.0), doubleArrayOf(1.0, 0.0, 1.0), doubleArrayOf(-1.0, 0.0, 1.0),
            doubleArrayOf(1.0, 0.0, -1.0), doubleArrayOf(-1.0, 0.0, -1.0), doubleArrayOf(0.0, 1.0, 1.0),
            doubleArrayOf(0.0, -1.0, 1.0), doubleArrayOf(0.0, 1.0, -1.0), doubleArrayOf(0.0, -1.0, -1.0)
        )

        /** A lattice coordinate's place in the permutation. Through a Long, so coordinates past Int's range still wrap. */
        private fun wrap(cell: Double): Int = (cell.toLong() and 255).toInt()

        /** Recent seeds' tables: a script asks for the same seed again and again, a cell at a time. */
        private const val KEPT = 16
        private val tables = object : LinkedHashMap<Long, SimplexNoise>(KEPT, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, SimplexNoise>) = size > KEPT
        }

        fun of(seed: Long): SimplexNoise = synchronized(tables) { tables.getOrPut(seed) { SimplexNoise(seed) } }
    }
}
