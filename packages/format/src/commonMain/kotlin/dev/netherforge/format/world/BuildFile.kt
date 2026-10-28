package dev.netherforge.format.world

/**
 * A build: `builds/<id>/`, a structure too big for one structure file, kept as a grid of them. Each part is
 * `<x>_<y>_<z>.nbt` (its place in the grid, counted from 0), in Minecraft's structure format, [Builds.PART_SIDE]
 * blocks along each side but the last part along an axis, which holds what's left. Like a structure it's binary
 * and only read by what places or draws it (the server, the editor, the CLI): format checks the parts' names and
 * that the grid is whole. [grid] reaches one past the furthest part along each axis (a missing part is a problem).
 */
data class BuildFile(val id: String, val grid: BuildGrid)

/** How many parts a build has along x, y and z. */
data class BuildGrid(val x: Int, val y: Int, val z: Int) {
    /** Every part, x fastest, then z, then y (from the bottom up, as a build is placed). */
    val parts: List<BuildPart>
        get() = buildList {
            for (j in 0 until y) for (k in 0 until z) for (i in 0 until x) add(BuildPart(i, j, k))
        }
}

/** One part of a build, by its place in the grid. */
data class BuildPart(val x: Int, val y: Int, val z: Int) {
    /** Its file in the build's folder. */
    val file: String get() = Builds.fileOf(x, y, z)

    /** Its smallest corner in the build, in blocks. */
    val originX: Int get() = x * Builds.PART_SIDE
    val originY: Int get() = y * Builds.PART_SIDE
    val originZ: Int get() = z * Builds.PART_SIDE
}

/** The rules of a build's parts: their names, their size, and how many there may be. */
object Builds {
    /** The side of a whole part, in blocks: what one structure file holds (a structure block's limit). */
    const val PART_SIDE = 48

    /** The most parts along any axis: 1056 blocks. */
    const val MAX_PARTS = 22

    const val EXTENSION = ".nbt"

    private val PART = Regex("""^(0|[1-9][0-9]{0,3})_(0|[1-9][0-9]{0,3})_(0|[1-9][0-9]{0,3})\.nbt$""")

    /** The file of the part at [x], [y], [z] in the grid. */
    fun fileOf(x: Int, y: Int, z: Int): String = "${x}_${y}_${z}$EXTENSION"

    /** The part a file in a build's folder is, or null when its name isn't one's. */
    fun partOf(file: String): BuildPart? {
        val match = PART.matchEntire(file) ?: return null
        val (x, y, z) = match.destructured
        return BuildPart(x.toInt(), y.toInt(), z.toInt())
    }

    /** The grid [files] (a build's, relative to its folder) make: one past the furthest part along each axis, at least one. */
    fun gridOf(files: Collection<String>): BuildGrid {
        val parts = files.mapNotNull(::partOf)
        return BuildGrid(
            (
                parts.maxOfOrNull {
                    it.x
                } ?: 0
                ) + 1,
            (parts.maxOfOrNull { it.y } ?: 0) + 1,
            (parts.maxOfOrNull { it.z } ?: 0) + 1
        )
    }

    /** The first part: the file that makes a folder in `builds/` a build. */
    val MAIN: String = fileOf(0, 0, 0)

    /** The grid a box [x] by [y] by [z] blocks is cut into. */
    fun gridFor(x: Int, y: Int, z: Int): BuildGrid = BuildGrid(parts(x), parts(y), parts(z))

    private fun parts(blocks: Int): Int = (blocks + PART_SIDE - 1) / PART_SIDE
}

/** Blocks a structure file holds that mean "nothing here". */
object EmptyBlocks {
    /**
     * The ids: what a terrain's decoration leaves as the world has it, and what a picture of a structure or build
     * draws as empty. (A placement still puts air where the file has it, which is what clears the space.)
     */
    val IDS: Set<String> = setOf("minecraft:air", "minecraft:cave_air", "minecraft:void_air", "minecraft:structure_void")
}
