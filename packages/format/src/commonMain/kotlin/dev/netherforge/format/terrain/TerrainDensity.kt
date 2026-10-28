package dev.netherforge.format.terrain

import dev.netherforge.format.noise.FastNoiseLite
import dev.netherforge.format.noise.TerrainSeeds
import kotlin.math.abs
import kotlin.math.max

/**
 * The 3D terrain of a generator whose file has a `terrain.density` ([CompiledDensity]), bound to a world's seed and
 * heights: its noises built once and only read after, so one serves every thread, like the generator.
 *
 * A block's density, in blocks, is the ground's, `height + 0.5 - y + ground(x, y, z)`, or the islands' where that's
 * higher, plus what the script's `density` stage changes; it's solid where that's above 0. `height` is the column's
 * own (the file's 2D terrain and the script's `height` stage, exactly per column); everything else is read at the
 * points of a grid [CELL_XZ] blocks apart across and [CELL_Y] up (world-aligned, so a point is the same for every
 * chunk that reads it) and interpolated trilinearly between them, which is what makes it cheap: a chunk reads 25
 * columns of points rather than every block. With no 3D noise, a column is solid exactly up to its height, as
 * without a density.
 *
 * Every 3D noise is kept within -1 to 1, so the ground's offset is never more than [reach] blocks either way: a
 * block further below its column's height than that is solid, one further above it is air, and nothing there is read
 * (unless the script's stage can change it). The islands likewise never reach past the grid levels round their band.
 */
internal class DensityField(
    terrain: CompiledTerrain,
    val density: CompiledDensity,
    seed: Long,
    val minY: Int,
    val maxY: Int,
    scripted: () -> Boolean
) {
    private val areas = terrain.areas
    private val fileNoises: List<FastNoiseLite> = density.noises.map { it.noise.build(TerrainSeeds.forRole(seed, "density:${it.name}")) }
    private val areaNoises: List<List<FastNoiseLite>> = areas.map { area ->
        area.terrain.densityNoises.map { it.noise.build(TerrainSeeds.forRole(seed, "area:${area.name}:density:${it.name}")) }
    }
    val islands: CompiledIslands? = density.islands
    private val islandsNoise: FastNoiseLite? = islands?.let { it.noise.build(TerrainSeeds.forRole(seed, "islands")) }
    private val islandsMask: BooleanArray? = islands?.areas?.takeIf { it.isNotEmpty() }?.let { indexes ->
        BooleanArray(areas.size).also { mask -> indexes.forEach { mask[it] = true } }
    }
    private val half: Double = (islands?.thickness ?: 0) / 2.0

    /** Whether the script has a `density` stage, which can change any point: then every block is read. */
    val scripted: Boolean by lazy(scripted)

    /** Every area's 3D noises are the same: a point's ground doesn't depend on its area. */
    val oneDensity: Boolean = areas.all {
        it.terrain.densityScale == areas[0].terrain.densityScale && it.terrain.densityNoises == areas[0].terrain.densityNoises
    }

    /** Whether a grid column needs the areas' weights: when the ground differs between areas, or islands keep to some. */
    val weighed: Boolean = areas.size > 1 && (!oneDensity || islandsMask != null)

    /** The most the ground's 3D noises move it, in blocks, anywhere. */
    val reach: Double = run {
        val file = density.noises.sumOf { abs(it.amplitude) }
        areas.maxOf { abs(it.terrain.densityScale) * file + it.terrain.densityNoises.sumOf { n -> abs(n.amplitude) } }
    }

    /** The grid's lowest level (its y over [CELL_Y]) and how many there are: one past the world's top, so every block has one above. */
    val lowestLevel: Int = minY.floorDiv(CELL_Y)
    val levels: Int = (maxY - 1).floorDiv(CELL_Y) + 2 - lowestLevel

    /** The highest block an island can be in, or below the world when there are none. */
    val islandsTop: Int = run {
        var top = minY - 1
        for (i in 0 until levels) if (inBand(i) || (i + 1 < levels && inBand(i + 1))) top = (lowestLevel + i) * CELL_Y + CELL_Y - 1
        top
    }

    fun yOf(level: Int): Int = (lowestLevel + level) * CELL_Y

    /** Whether the points of grid level [level] (an index from [lowestLevel]) are inside the islands' band. */
    fun inBand(level: Int): Boolean {
        val band = islands ?: return false
        return abs((yOf(level) - band.y).toDouble()) < half
    }

    /** The ground's offset at a point: the 3D noises of each area, weighed by [weights] (null: there's one ground). */
    fun ground(weights: DoubleArray?, x: Double, y: Double, z: Double): Double {
        var file = 0.0
        for ((i, n) in density.noises.withIndex()) file += unit(fileNoises[i].getNoise(x, y * n.squash, z)) * n.amplitude
        if (weights == null || oneDensity) return areaGround(0, file, x, y, z)
        var sum = 0.0
        for (a in areas.indices) {
            val w = weights[a]
            if (w > 0.0) sum += w * areaGround(a, file, x, y, z)
        }
        return sum
    }

    private fun areaGround(area: Int, file: Double, x: Double, y: Double, z: Double): Double {
        val terrain = areas[area].terrain
        var value = terrain.densityScale * file
        val own = areaNoises[area]
        for (i in own.indices) {
            val n = terrain.densityNoises[i]
            value += unit(own[i].getNoise(x, y * n.squash, z)) * n.amplitude
        }
        return value
    }

    /** How much of a point is in the islands' areas, 0 to 1, from its areas' [weights] (null: all of it in one area, [area]). */
    fun islandsWeight(weights: DoubleArray?, area: Int): Double {
        val mask = islandsMask ?: return 1.0
        if (weights == null) return if (mask[area]) 1.0 else 0.0
        var sum = 0.0
        for (a in areas.indices) if (mask[a]) sum += weights[a]
        return sum
    }

    /**
     * The islands' density at a point, in blocks: above 0 where the noise is above the threshold, by more the nearer
     * the band's middle. Out of the islands' areas ([weight] 0) the threshold rises to 1, so they fade across a border.
     */
    fun islands(weight: Double, x: Double, y: Double, z: Double): Double {
        val band = islands!!
        val threshold = band.threshold + (1.0 - weight) * (1.0 - band.threshold)
        val n = unit(islandsNoise!!.getNoise(x, y, z))
        return (n - threshold) / (1.0 - band.threshold) * half - abs(y - band.y)
    }

    private fun unit(value: Double): Double = if (value > 1.0) {
        1.0
    } else if (value < -1.0) {
        -1.0
    } else {
        value
    }

    companion object {
        const val CELL_XZ = 4
        const val CELL_Y = 8
        const val GROUND = 0
        const val ISLANDS = 1
        const val DELTA = 2
    }
}

/**
 * One column of the density grid's points, at ([x], [z]): the ground's offset, the islands' density and the script's
 * change at each level, each worked out the first time it's read. [base] is the column's height (for the value the
 * script is given) and [script] the script's `density` stage, null when it has none.
 */
internal class CornerColumn(
    private val field: DensityField,
    val x: Int,
    val z: Int,
    private val weights: DoubleArray?,
    private val islandsWeight: Double,
    private val base: () -> Int,
    private val script: ((Int, Int, Int, Double) -> Double?)?
) {
    private val ground = DoubleArray(field.levels) { Double.NaN }
    private val islands = if (field.islands != null) DoubleArray(field.levels) { Double.NaN } else null
    private val delta = if (script != null) DoubleArray(field.levels) { Double.NaN } else null
    private var height: Int? = null

    fun value(which: Int, level: Int): Double = when (which) {
        DensityField.GROUND -> ground(level)
        DensityField.ISLANDS -> islands(level)
        else -> delta(level)
    }

    private fun ground(level: Int): Double {
        val known = ground[level]
        if (!known.isNaN()) return known
        val value = field.ground(weights, x.toDouble(), field.yOf(level).toDouble(), z.toDouble())
        ground[level] = value
        return value
    }

    private fun islands(level: Int): Double {
        val array = islands!!
        val known = array[level]
        if (!known.isNaN()) return known
        val value = field.islands(islandsWeight, x.toDouble(), field.yOf(level).toDouble(), z.toDouble())
        array[level] = value
        return value
    }

    /** What the script's stage changed of the file's density at a point: 0 where it failed. */
    private fun delta(level: Int): Double {
        val array = delta!!
        val known = array[level]
        if (!known.isNaN()) return known
        val y = field.yOf(level)
        val h = height ?: base().also { height = it }
        val ground = h + 0.5 - y + ground(level)
        val value = if (islands != null) max(ground, islands(level)) else ground
        val changed = script!!(x, y, z, value)?.let { it - value }?.takeIf { it.isFinite() } ?: 0.0
        array[level] = changed
        return changed
    }
}

/**
 * A column of blocks' density: its own height [height] and the four grid columns round it, whose points are
 * interpolated. [solid] is the one answer every part asks (the chunk's blocks, the column's surfaces, the map), so
 * they can't disagree.
 */
internal class DensityColumn(private val field: DensityField, val height: Int, x: Int, z: Int, corner: (Int, Int) -> CornerColumn) {
    private val gx = x.floorDiv(DensityField.CELL_XZ)
    private val gz = z.floorDiv(DensityField.CELL_XZ)
    private val fx = (x - gx * DensityField.CELL_XZ).toDouble() / DensityField.CELL_XZ
    private val fz = (z - gz * DensityField.CELL_XZ).toDouble() / DensityField.CELL_XZ
    private val c00 = corner(gx, gz)
    private val c10 = if (fx > 0.0) corner(gx + 1, gz) else c00
    private val c01 = if (fz > 0.0) corner(gx, gz + 1) else c00
    private val c11 = if (fx > 0.0 && fz > 0.0) {
        corner(gx + 1, gz + 1)
    } else if (fx > 0.0) {
        c10
    } else {
        c01
    }

    /** Whether the block at [y] is solid; above the world's top, nothing is. */
    fun solid(y: Int): Boolean {
        if (y >= field.maxY) return false
        val ground = height + 0.5 - y
        val level = y.floorDiv(DensityField.CELL_Y)
        val i = level - field.lowestLevel
        val fy = (y - level * DensityField.CELL_Y).toDouble() / DensityField.CELL_Y
        val islands = field.islands != null
        if (field.scripted) {
            var value = ground + interpolate(DensityField.GROUND, i, fy)
            if (islands) value = max(value, interpolate(DensityField.ISLANDS, i, fy))
            return value + interpolate(DensityField.DELTA, i, fy) > 0.0
        }
        if (ground > field.reach) return true
        if (islands && (field.inBand(i) || field.inBand(i + 1)) && interpolate(DensityField.ISLANDS, i, fy) > 0.0) return true
        if (ground < -field.reach) return false
        return ground + interpolate(DensityField.GROUND, i, fy) > 0.0
    }

    /** The highest block that can be solid: everything above it is air. */
    fun highest(): Int {
        if (field.scripted) return field.maxY - 1
        val ground = kotlin.math.floor(height + 0.5 + field.reach).toInt()
        return minOf(field.maxY - 1, maxOf(ground, field.islandsTop))
    }

    /** Whether every block from [y] down is solid, so there's nothing more to look for below it. */
    fun solidBelow(y: Int): Boolean = !field.scripted && height + 0.5 - y > field.reach

    /** The y of the topmost solid block, or one below the world's bottom when there's none. */
    fun top(): Int {
        var y = highest()
        while (y >= field.minY) {
            if (solid(y)) return y
            y--
        }
        return field.minY - 1
    }

    /** Every top surface (a solid block with no solid block on it), from the highest down. */
    fun surfaces(): IntArray {
        val out = ArrayList<Int>()
        var above = false
        var y = highest()
        while (y >= field.minY) {
            val solid = solid(y)
            if (solid && !above) out += y
            if (solid && solidBelow(y)) break
            above = solid
            y--
        }
        return out.toIntArray()
    }

    private fun interpolate(which: Int, level: Int, fy: Double): Double {
        val low = across(which, level)
        if (fy == 0.0) return low
        val high = across(which, level + 1)
        return low + (high - low) * fy
    }

    private fun across(which: Int, level: Int): Double {
        val v00 = c00.value(which, level)
        val a = if (fx > 0.0) v00 + (c10.value(which, level) - v00) * fx else v00
        if (fz == 0.0) return a
        val v01 = c01.value(which, level)
        val b = if (fx > 0.0) v01 + (c11.value(which, level) - v01) * fx else v01
        return a + (b - a) * fz
    }
}
