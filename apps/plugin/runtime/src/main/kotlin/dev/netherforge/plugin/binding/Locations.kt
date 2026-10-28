package dev.netherforge.plugin.binding
import dev.netherforge.format.math.Vec3
import dev.netherforge.format.math.degreesToRadians
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.Location
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * A Lua `Location` as it crosses the generated bindings: a world, a position,
 * and a facing that may be missing (a centity has no pitch).
 */
data class LuaLocation(val world: LuaHandle.World, val position: Vec3, val yaw: Double? = null, val pitch: Double? = null) {
    companion object {
        /** A platform location, facing included. */
        fun of(location: Location) =
            LuaLocation(LuaHandle.World(location.world), Vec3(location.x, location.y, location.z), location.yaw, location.pitch)
    }
}

/**
 * Where to put something, from a `Location|Vec3` parameter ([LocationOrVec3.place]).
 * A bare `Vec3` has no [world] and no facing, meaning "the same world as
 * whatever is asking": the handle's own, or a default the function documents.
 */
data class LuaPlace(val world: LuaHandle.World?, val position: Vec3, val yaw: Double?, val pitch: Double?) {
    companion object {
        fun of(location: LuaLocation) = LuaPlace(location.world, location.position, location.yaw, location.pitch)
    }

    /** As a platform location in [world] when it names none, taking any missing facing from [facing]. */
    fun resolve(world: String, facing: Location? = null) = Location(
        this.world?.name ?: world,
        position.x,
        position.y,
        position.z,
        yaw ?: facing?.yaw ?: 0.0,
        pitch ?: facing?.pitch ?: 0.0
    )
}

/** A `Location|Vec3` parameter as a place to put something. */
val LocationOrVec3.place: LuaPlace
    get() = when (this) {
        is LocationOrVec3.Location -> LuaPlace.of(value)
        is LocationOrVec3.Vec3 -> LuaPlace(null, value, null, null)
    }

/** The unit direction a yaw and pitch face, in Minecraft's convention: the same maths as `vec3.from_yaw_pitch`. */
internal fun directionOf(yaw: Double, pitch: Double): Vec3 {
    val y = degreesToRadians(yaw)
    val p = degreesToRadians(pitch)
    return Vec3(-sin(y) * cos(p), -sin(p), cos(y) * cos(p))
}

/**
 * A block position as a `Block` handle carries it: x, y and z packed into one
 * integer the way Minecraft packs one (26 bits of x, 26 of z, 12 of y), so a
 * handle is a world and a number.
 */
internal data class BlockPosition(val x: Int, val y: Int, val z: Int) {
    val packed: Long get() = pack(x, y, z)

    /** Its corner with the smallest coordinates. */
    fun corner() = Vec3(x.toDouble(), y.toDouble(), z.toDouble())

    companion object {
        /** Further out than any world's border, and the y range a packed position holds. */
        private const val HORIZONTAL = 33_554_431.0
        private val VERTICAL = -2048.0..2047.0

        fun pack(x: Int, y: Int, z: Int): Long =
            ((x.toLong() and 0x3FFFFFF) shl 38) or ((z.toLong() and 0x3FFFFFF) shl 12) or (y.toLong() and 0xFFF)

        fun unpack(packed: Long) = BlockPosition(
            (packed shr 38).toInt(),
            (packed shl 52 shr 52).toInt(),
            (packed shl 26 shr 38).toInt()
        )

        /** The block a point is in; a point no world can have is an error. */
        fun of(point: Vec3): BlockPosition {
            val x = floor(point.x)
            val y = floor(point.y)
            val z = floor(point.z)
            if (x !in -HORIZONTAL..HORIZONTAL || z !in -HORIZONTAL..HORIZONTAL || y !in VERTICAL) {
                throw LuaApiException("the position (${point.x}, ${point.y}, ${point.z}) is outside any world")
            }
            return BlockPosition(x.toInt(), y.toInt(), z.toInt())
        }
    }
}
