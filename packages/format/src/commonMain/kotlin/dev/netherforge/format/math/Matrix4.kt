package dev.netherforge.format.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The 4x4 transform maths composition needs, shared so the editor's preview and
 * the server place every node identically.
 *
 * Conventions (JOML's, which Paper exposes):
 *  - column-major storage, `element(row, col) == values[col * 4 + row]`
 *  - builders post-multiply: `translate` then `scale` yields `T * S`
 *  - [rotateXYZ] applies X, then Y, then Z
 *
 * Mutable, builders return `this`: composition runs for every node of every
 * instance every tick.
 */
class Matrix4 {
    val values = DoubleArray(16)

    init {
        identity()
    }

    fun identity(): Matrix4 {
        values.fill(0.0)
        values[0] = 1.0
        values[5] = 1.0
        values[10] = 1.0
        values[15] = 1.0
        return this
    }

    fun set(other: Matrix4): Matrix4 {
        other.values.copyInto(values)
        return this
    }

    /** `dest = this * right`. Any argument may alias any other. */
    fun mul(right: Matrix4, dest: Matrix4 = this): Matrix4 {
        val a = values
        val b = right.values
        val out = DoubleArray(16)
        for (col in 0 until 4) {
            val b0 = b[col * 4]
            val b1 = b[col * 4 + 1]
            val b2 = b[col * 4 + 2]
            val b3 = b[col * 4 + 3]
            for (row in 0 until 4) {
                out[col * 4 + row] = a[row] * b0 + a[4 + row] * b1 + a[8 + row] * b2 + a[12 + row] * b3
            }
        }
        out.copyInto(dest.values)
        return dest
    }

    fun translate(x: Double, y: Double, z: Double): Matrix4 {
        for (row in 0 until 4) {
            values[12 + row] = values[row] * x + values[4 + row] * y + values[8 + row] * z + values[12 + row]
        }
        return this
    }

    fun scale(x: Double, y: Double, z: Double): Matrix4 {
        for (row in 0 until 4) {
            values[row] *= x
            values[4 + row] *= y
            values[8 + row] *= z
        }
        return this
    }

    fun rotateX(radians: Double): Matrix4 {
        val c = cos(radians)
        val s = sin(radians)
        for (row in 0 until 4) {
            val col1 = values[4 + row]
            val col2 = values[8 + row]
            values[4 + row] = col1 * c + col2 * s
            values[8 + row] = col1 * -s + col2 * c
        }
        return this
    }

    fun rotateY(radians: Double): Matrix4 {
        val c = cos(radians)
        val s = sin(radians)
        for (row in 0 until 4) {
            val col0 = values[row]
            val col2 = values[8 + row]
            values[row] = col0 * c + col2 * -s
            values[8 + row] = col0 * s + col2 * c
        }
        return this
    }

    fun rotateZ(radians: Double): Matrix4 {
        val c = cos(radians)
        val s = sin(radians)
        for (row in 0 until 4) {
            val col0 = values[row]
            val col1 = values[4 + row]
            values[row] = col0 * c + col1 * s
            values[4 + row] = col0 * -s + col1 * c
        }
        return this
    }

    fun rotateXYZ(x: Double, y: Double, z: Double): Matrix4 {
        if (x != 0.0) rotateX(x)
        if (y != 0.0) rotateY(y)
        if (z != 0.0) rotateZ(z)
        return this
    }

    fun transformPosition(p: Vec3): Vec3 = Vec3(
        values[0] * p.x + values[4] * p.y + values[8] * p.z + values[12],
        values[1] * p.x + values[5] * p.y + values[9] * p.z + values[13],
        values[2] * p.x + values[6] * p.y + values[10] * p.z + values[14]
    )

    fun transformDirection(d: Vec3): Vec3 = Vec3(
        values[0] * d.x + values[4] * d.y + values[8] * d.z,
        values[1] * d.x + values[5] * d.y + values[9] * d.z,
        values[2] * d.x + values[6] * d.y + values[10] * d.z
    )

    /**
     * The inverse of this affine matrix, or null when an axis has collapsed to
     * zero scale (a script can do that at any time). Reads everything before
     * writing, so [dest] may alias this.
     */
    fun invertAffine(dest: Matrix4 = Matrix4()): Matrix4? {
        val a = values[0]
        val d = values[1]
        val g = values[2]
        val b = values[4]
        val e = values[5]
        val h = values[6]
        val c = values[8]
        val f = values[9]
        val i = values[10]
        val tx = values[12]
        val ty = values[13]
        val tz = values[14]

        val det = a * (e * i - f * h) - b * (d * i - f * g) + c * (d * h - e * g)
        if (det > -SINGULAR && det < SINGULAR) return null
        val s = 1.0 / det

        val i00 = (e * i - f * h) * s
        val i01 = (c * h - b * i) * s
        val i02 = (b * f - c * e) * s
        val i10 = (f * g - d * i) * s
        val i11 = (a * i - c * g) * s
        val i12 = (c * d - a * f) * s
        val i20 = (d * h - e * g) * s
        val i21 = (b * g - a * h) * s
        val i22 = (a * e - b * d) * s

        val out = dest.values
        out[0] = i00
        out[1] = i10
        out[2] = i20
        out[3] = 0.0
        out[4] = i01
        out[5] = i11
        out[6] = i21
        out[7] = 0.0
        out[8] = i02
        out[9] = i12
        out[10] = i22
        out[11] = 0.0
        out[12] = -(i00 * tx + i01 * ty + i02 * tz)
        out[13] = -(i10 * tx + i11 * ty + i12 * tz)
        out[14] = -(i20 * tx + i21 * ty + i22 * tz)
        out[15] = 1.0
        return dest
    }

    fun translation(): Vec3 = Vec3(values[12], values[13], values[14])

    /** The length of each basis column; the scale, up to any shear a chain introduced. */
    fun scale(): Vec3 = Vec3(
        sqrt(values[0] * values[0] + values[1] * values[1] + values[2] * values[2]),
        sqrt(values[4] * values[4] + values[5] * values[5] + values[6] * values[6]),
        sqrt(values[8] * values[8] + values[9] * values[9] + values[10] * values[10])
    )

    fun copy(): Matrix4 = Matrix4().set(this)

    companion object {
        private const val SINGULAR = 1e-12

        /** The local matrix of a TRS transform: `T * Rx * Ry * Rz * S`. */
        fun fromTrs(translation: Vec3, rotationDegrees: Vec3, scale: Vec3, dest: Matrix4 = Matrix4()): Matrix4 {
            dest.identity()
            dest.translate(translation.x, translation.y, translation.z)
            dest.rotateXYZ(
                degreesToRadians(rotationDegrees.x),
                degreesToRadians(rotationDegrees.y),
                degreesToRadians(rotationDegrees.z)
            )
            dest.scale(scale.x, scale.y, scale.z)
            return dest
        }
    }
}

fun degreesToRadians(degrees: Double): Double = degrees * (PI / 180.0)

fun radiansToDegrees(radians: Double): Double = radians * (180.0 / PI)
