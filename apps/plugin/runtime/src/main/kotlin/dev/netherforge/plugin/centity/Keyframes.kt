package dev.netherforge.plugin.centity

import dev.netherforge.format.math.Matrix4
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Clips as keyframes the client eases between, rather than a pose every tick.
 *
 * A display eases to each new transformation over its interpolation
 * duration on the client: translation and scale in a straight line,
 * rotation along the shortest arc. So while only clips move a node, sync
 * can look ahead, send the pose some ticks on with that many ticks to get
 * there, and send nothing more until it arrives, as long as the client's
 * straight line passes within [TRANSLATION_TOLERANCE] of where the clip
 * really puts the node at every tick between. [reach] finds how far that
 * is: the length of a linear segment (to [MAX_TICKS]), a few ticks along an
 * eased one, one tick across a step or anything the client couldn't follow.
 *
 * The model of the client: Paper decomposes the matrix it's given into
 * translation, rotation, scale and a second rotation, and the client eases
 * each. For a matrix with no shear whose scale is uniform, or the same at
 * both ends, that second rotation is the same at both ends, so easing is
 * exactly [between]. Anything else (shear from a parent's uneven scale, a
 * mirror, a collapsed node, an uneven scale that changes) isn't planned.
 */
internal object Keyframes {
    /** The furthest ahead a move is planned: a second. */
    const val MAX_TICKS = 20

    /** How far off the client may be, in blocks, at any tick of a planned move. */
    const val TRANSLATION_TOLERANCE = 0.01

    /** How far off each of a matrix's axes may be, per block of the node's scale. */
    const val AXIS_TOLERANCE = 0.01

    private const val EPSILON = 1e-6

    /** A matrix as the client eases it: translation, rotation (x, y, z, w) and scale. */
    class Parts(val translation: DoubleArray, val rotation: DoubleArray, val scale: DoubleArray)

    /**
     * How many ticks ahead a display can be sent from [now]: the most `h`
     * (at least 1, at most [max]) for which easing from `now` to `ahead(h)`
     * over `h` ticks passes `ahead(k)` at each tick `k` between. [ahead] is
     * the node's matrix that many ticks on ([Instance.placedAhead]), asked
     * for in order, and no further than needed.
     */
    fun reach(now: Matrix4, max: Int, ahead: (Int) -> Matrix4): Int {
        val start = decompose(now)
        var best = 1
        for (h in 2..max) {
            if (!follows(now, start, ahead, h)) break
            best = h
        }
        return best
    }

    /** Whether easing from [now] to `ahead(h)` over [h] ticks stays within tolerance of every tick between. */
    private fun follows(now: Matrix4, start: Parts?, ahead: (Int) -> Matrix4, h: Int): Boolean {
        val target = ahead(h)
        if (same(now, target)) return (1 until h).all { same(now, ahead(it)) }
        val end = decompose(target) ?: return false
        if (start == null || !easable(start, end)) return false
        val tolerance = AXIS_TOLERANCE * max(1.0, max(largest(start.scale), largest(end.scale)))
        val predicted = Matrix4()
        for (k in 1 until h) {
            between(start, end, k.toDouble() / h, predicted)
            if (!close(predicted, ahead(k), tolerance)) return false
        }
        return true
    }

    /** A scale the client eases the way [between] does: the same at both ends, or even at both. */
    private fun easable(a: Parts, b: Parts): Boolean = sameScale(a.scale, b.scale) || (uniform(a.scale) && uniform(b.scale))

    private fun sameScale(a: DoubleArray, b: DoubleArray) = (0..2).all { abs(a[it] - b[it]) <= EPSILON * max(1.0, abs(a[it])) }

    private fun uniform(s: DoubleArray) = abs(s[0] - s[1]) <= EPSILON * s[0] && abs(s[0] - s[2]) <= EPSILON * s[0]

    private fun largest(s: DoubleArray) = max(s[0], max(s[1], s[2]))

    private fun same(a: Matrix4, b: Matrix4): Boolean = a.values.contentEquals(b.values)

    private fun close(a: Matrix4, b: Matrix4, axis: Double): Boolean {
        for (column in 0..2) {
            for (row in 0..2) {
                if (abs(a.values[column * 4 + row] - b.values[column * 4 + row]) > axis) return false
            }
        }
        for (row in 0..2) if (abs(a.values[12 + row] - b.values[12 + row]) > TRANSLATION_TOLERANCE) return false
        return true
    }

    /**
     * [matrix] as translation, rotation and scale, or null when the client
     * wouldn't ease it as such: a collapsed axis, shear or a mirror.
     */
    fun decompose(matrix: Matrix4): Parts? {
        val v = matrix.values
        val scale = DoubleArray(3) { column ->
            val at = column * 4
            sqrt(v[at] * v[at] + v[at + 1] * v[at + 1] + v[at + 2] * v[at + 2])
        }
        if (scale.any { it < EPSILON }) return null
        // The rotation's columns: the axes, each its own length.
        val r = Array(3) { column -> DoubleArray(3) { row -> v[column * 4 + row] / scale[column] } }
        fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
        if (abs(dot(r[0], r[1])) > 1e-4 || abs(dot(r[0], r[2])) > 1e-4 || abs(dot(r[1], r[2])) > 1e-4) return null
        val determinant = r[0][0] * (r[1][1] * r[2][2] - r[2][1] * r[1][2]) -
            r[1][0] * (r[0][1] * r[2][2] - r[2][1] * r[0][2]) +
            r[2][0] * (r[0][1] * r[1][2] - r[1][1] * r[0][2])
        if (determinant < 0) return null
        return Parts(doubleArrayOf(v[12], v[13], v[14]), quaternion(r), scale)
    }

    /** The rotation whose matrix has columns [c], as a unit quaternion (x, y, z, w). */
    private fun quaternion(c: Array<DoubleArray>): DoubleArray {
        // m(row, column)
        val m00 = c[0][0]
        val m11 = c[1][1]
        val m22 = c[2][2]
        val trace = m00 + m11 + m22
        val q = when {
            trace > 0 -> {
                val s = sqrt(trace + 1.0) * 2
                doubleArrayOf((c[1][2] - c[2][1]) / s, (c[2][0] - c[0][2]) / s, (c[0][1] - c[1][0]) / s, 0.25 * s)
            }
            m00 > m11 && m00 > m22 -> {
                val s = sqrt(1.0 + m00 - m11 - m22) * 2
                doubleArrayOf(0.25 * s, (c[1][0] + c[0][1]) / s, (c[2][0] + c[0][2]) / s, (c[1][2] - c[2][1]) / s)
            }
            m11 > m22 -> {
                val s = sqrt(1.0 + m11 - m00 - m22) * 2
                doubleArrayOf((c[1][0] + c[0][1]) / s, 0.25 * s, (c[2][1] + c[1][2]) / s, (c[2][0] - c[0][2]) / s)
            }
            else -> {
                val s = sqrt(1.0 + m22 - m00 - m11) * 2
                doubleArrayOf((c[2][0] + c[0][2]) / s, (c[2][1] + c[1][2]) / s, 0.25 * s, (c[0][1] - c[1][0]) / s)
            }
        }
        val length = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        for (i in 0..3) q[i] /= length
        return q
    }

    /** What the client shows [t] of the way from [a] to [b], into [dest]. */
    fun between(a: Parts, b: Parts, t: Double, dest: Matrix4): Matrix4 {
        val q = slerp(a.rotation, b.rotation, t)
        val s = DoubleArray(3) { a.scale[it] + (b.scale[it] - a.scale[it]) * t }
        val (x, y, z, w) = listOf(q[0], q[1], q[2], q[3])
        // The rotation's columns, each times its scale.
        val columns = arrayOf(
            doubleArrayOf(1 - 2 * (y * y + z * z), 2 * (x * y + w * z), 2 * (x * z - w * y)),
            doubleArrayOf(2 * (x * y - w * z), 1 - 2 * (x * x + z * z), 2 * (y * z + w * x)),
            doubleArrayOf(2 * (x * z + w * y), 2 * (y * z - w * x), 1 - 2 * (x * x + y * y))
        )
        val v = dest.values
        for (column in 0..2) {
            for (row in 0..2) v[column * 4 + row] = columns[column][row] * s[column]
            v[column * 4 + 3] = 0.0
        }
        for (row in 0..2) v[12 + row] = a.translation[row] + (b.translation[row] - a.translation[row]) * t
        v[15] = 1.0
        return dest
    }

    /** Along the shortest arc, as the client's quaternions go. */
    private fun slerp(a: DoubleArray, b: DoubleArray, t: Double): DoubleArray {
        var cos = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
        val sign = if (cos < 0) -1.0 else 1.0
        cos *= sign
        val (wa, wb) = if (1 - cos > 1e-6) {
            val angle = acos(cos.coerceAtMost(1.0))
            val s = sin(angle)
            sin((1 - t) * angle) / s to sin(t * angle) / s
        } else {
            (1 - t) to t
        }
        val q = DoubleArray(4) { wa * a[it] + wb * sign * b[it] }
        val length = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        for (i in 0..3) q[i] /= length
        return q
    }
}
