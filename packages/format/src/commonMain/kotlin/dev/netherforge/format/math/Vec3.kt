package dev.netherforge.format.math

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Three numbers, written in files as `[x, y, z]`.
 *
 * Doubles rather than floats: Kotlin/JS has no 32-bit float, so a model in
 * floats would round differently in the editor than on the server. In doubles
 * both platforms do the same IEEE arithmetic.
 */
@Serializable(with = Vec3Serializer::class)
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(other: Vec3) = Vec3(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3) = Vec3(x - other.x, y - other.y, z - other.z)
    operator fun times(factor: Double) = Vec3(x * factor, y * factor, z * factor)

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val ONE = Vec3(1.0, 1.0, 1.0)
    }
}

/** The descriptor name the contract generator matches to emit a fixed-length tuple. */
const val VEC3_SERIAL_NAME = "dev.netherforge.format.math.Vec3"

object Vec3Serializer : KSerializer<Vec3> {
    private val delegate = ListSerializer(Double.serializer())

    override val descriptor: SerialDescriptor = SerialDescriptor(VEC3_SERIAL_NAME, delegate.descriptor)

    override fun serialize(encoder: Encoder, value: Vec3) {
        encoder.encodeSerializableValue(delegate, listOf(value.x, value.y, value.z))
    }

    override fun deserialize(decoder: Decoder): Vec3 {
        val values = decoder.decodeSerializableValue(delegate)
        if (values.size != 3) {
            throw SerializationException("A vector needs exactly 3 numbers, got ${values.size}")
        }
        return Vec3(values[0], values[1], values[2])
    }
}
