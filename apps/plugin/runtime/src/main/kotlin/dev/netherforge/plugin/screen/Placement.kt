package dev.netherforge.plugin.screen

import dev.netherforge.format.screen.ScreenFacing
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Ray

/**
 * Where a screen hangs: [world], the block its top left map is in ([x], [y], [z]: the air in front of the wall),
 * and the way it faces. Its other maps go to the right of a player facing it and down: map ([column], [row]) is in
 * [blockOf]. The picture lies on the frames' faces, a sixteenth of a block out from the wall.
 */
data class Placement(val world: String, val x: Int, val y: Int, val z: Int, val facing: ScreenFacing) {
    /** The block map ([column], [row]) hangs in. */
    fun blockOf(column: Int, row: Int): Triple<Int, Int, Int> = Triple(x + facing.rightX * column, y - row, z + facing.rightZ * column)

    fun locationOf(column: Int, row: Int): Location {
        val (bx, by, bz) = blockOf(column, row)
        return Location(world, bx.toDouble(), by.toDouble(), bz.toDouble())
    }

    /** The picture's top left corner in the world. */
    private val cornerX = x + 0.5 - facing.normalX * SURFACE - facing.rightX * 0.5
    private val cornerY = y + 1.0
    private val cornerZ = z + 0.5 - facing.normalZ * SURFACE - facing.rightZ * 0.5

    /** The middle of a [columns] by [rows] screen, for who's near it. */
    fun middle(columns: Int, rows: Int): Triple<Double, Double, Double> = Triple(
        cornerX + facing.rightX * columns / 2.0,
        cornerY - rows / 2.0,
        cornerZ + facing.rightZ * columns / 2.0
    )

    /**
     * Where [ray] meets the picture of a [columns] by [rows] screen within [reach] blocks, from its front: the
     * pixel and how far along the ray. Null when it misses, comes from behind, or is too far.
     */
    fun hit(ray: Ray, columns: Int, rows: Int, reach: Double): Hit? {
        if (ray.world != world) return null
        val nx = facing.normalX.toDouble()
        val nz = facing.normalZ.toDouble()
        val toward = ray.dx * nx + ray.dz * nz
        // Looking at its face means looking against the way it faces.
        if (toward >= -1e-9) return null
        val t = ((cornerX - ray.x) * nx + (cornerZ - ray.z) * nz) / toward
        if (t < 0 || t > reach) return null
        val hx = ray.x + ray.dx * t
        val hy = ray.y + ray.dy * t
        val hz = ray.z + ray.dz * t
        val across = (hx - cornerX) * facing.rightX + (hz - cornerZ) * facing.rightZ
        val down = cornerY - hy
        val px = kotlin.math.floor(across * Canvas.TILE).toInt()
        val py = kotlin.math.floor(down * Canvas.TILE).toInt()
        if (px < 0 || py < 0 || px >= columns * Canvas.TILE || py >= rows * Canvas.TILE) return null
        return Hit(px, py, t)
    }

    /** A pixel a ray reached, [distance] blocks along it. */
    data class Hit(val x: Int, val y: Int, val distance: Double)

    companion object {
        /** How far in front of the wall the picture is: an item frame's thickness. */
        private const val SURFACE = 0.5 - 1.0 / 16
    }
}
