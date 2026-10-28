package dev.netherforge.plugin.structure
import dev.netherforge.format.project.KindSpec
import dev.netherforge.format.project.Names
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.world.BuildGrid
import dev.netherforge.format.world.BuildKind
import dev.netherforge.format.world.Builds
import dev.netherforge.format.world.StructureKind
import dev.netherforge.plugin.binding.notInProject
import dev.netherforge.plugin.lua.LuaApiException
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.Platform
import dev.netherforge.plugin.platform.StructureOps
import dev.netherforge.plugin.platform.StructurePlacement
import dev.netherforge.plugin.project.Resource
import dev.netherforge.plugin.session.ReloadBatch
import dev.netherforge.plugin.session.RuntimeService
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.math.abs

/**
 * Where structures come from: the project's (`structures/<id>.nbt`, read
 * from the project folder), its builds (`builds/<id>/`, a grid of structure
 * files placed only gradually, [pieces]) and the ones scripts save at runtime.
 * An id names one of them: a build can't take a structure's (format's
 * `build.id-taken`) and a save can't take either's.
 *
 * Saves go to `<data>/.nf/structures/<id>.nbt`, never into the project: a
 * project's files change only when a person (or the editor, through format)
 * edits them, so a production server saving an arena can't leave a deployed
 * project dirty or race the editor's watcher. A saved structure can't take a
 * project structure's id, so an id always means one file. Saved ones survive
 * restarts and reloads; the project's are reread when they're saved
 * ([reload], which drops what the adapter kept of the old file).
 */
internal class StructureStore(
    private val platform: Platform,
    /** The project's own folder (the configured one, or the project's inside a bundle). */
    private val project: () -> Path,
    private val dataDirectory: Path,
    /** A structure with its entities was placed: the centities its markers ask for ([StructureSpawns]) can spawn. */
    private val placedWithEntities: () -> Unit,
    private val snapshot: () -> ProjectSnapshot?
) : RuntimeService {
    override val name get() = "structures"

    override val reloads: Set<KindSpec<*, *>> get() = setOf(StructureKind, BuildKind)

    /** Nothing runs on a structure or a build, so nothing restarts; the next placement reads the files again. */
    override fun reload(kind: KindSpec<*, *>, ids: Set<String>, batch: ReloadBatch) {
        for (id in ids) {
            if (kind == BuildKind) forgetBuild(id) else reload(id)
            batch.data(Resource(kind, id))
        }
    }

    private val saved: Path = dataDirectory.resolve(".nf").resolve("structures")

    private val ops: StructureOps get() = platform.structures

    private fun projectFile(id: String): Path? = projectPath(id)?.let { project().resolve(it) }

    /** Where the project's structure [id] is, in its group folders; null when the project has none. */
    private fun projectPath(id: String): String? = snapshot()?.takeIf { id in it[StructureKind] }?.placeOf(StructureKind, id)?.path

    private fun savedFile(id: String): Path = saved.resolve("$id${StructureKind.EXTENSION}")

    /** The file holding structure [id], or null when there's none. */
    fun file(id: String): Path? {
        projectFile(id)?.let { return it }
        if (!Names.isId(id)) return null
        return savedFile(id).takeIf { it.isRegularFile() }
    }

    /** A build of the project's: where its folder is (and how the project names it), and its grid. */
    class Build(val id: String, val folder: Path, val location: String, val grid: BuildGrid) {
        fun part(x: Int, y: Int, z: Int): Path = folder.resolve(Builds.fileOf(x, y, z))

        fun shown(x: Int, y: Int, z: Int): String = "$location/${Builds.fileOf(x, y, z)}"
    }

    /** The project's build [id], or null when it has none (a structure, a saved one, or nothing). */
    fun build(id: String): Build? {
        val snapshot = snapshot() ?: return null
        val file = snapshot.models(BuildKind)[id] ?: return null
        val location = snapshot.placeOf(BuildKind, id).location
        return Build(id, project().resolve(location), location, file.grid)
    }

    fun exists(id: String): Boolean = file(id) != null || build(id) != null

    /** [id]'s file, or an error naming what there is. */
    private fun required(id: String): Path = file(id) ?: missing(id)

    private fun missing(id: String): Nothing = throw LuaApiException(
        notInProject("structure", id, allIds()) +
            " (the project's are structures/<id>.nbt and builds/<id>/; scripts add more with world:save_structure)"
    )

    /** Every id there is something by, for an error naming what there is. */
    private fun allIds(): List<String> =
        snapshot()?.let { it.models(StructureKind).keys + it.models(BuildKind).keys }.orEmpty().sorted() + savedIds()

    private fun savedIds(): List<String> = runCatching {
        Files.list(saved).use { files ->
            files.map {
                it.fileName.toString()
            }.filter { it.endsWith(StructureKind.EXTENSION) }.map { it.removeSuffix(StructureKind.EXTENSION) }.toList()
        }
    }.getOrDefault(emptyList())

    private fun unreadable(id: String, file: Path): Nothing {
        val shown = projectPath(id) ?: "the structure saved as \"$id\""
        throw LuaApiException("$shown can't be read as a structure${if (file.isRegularFile()) "" else " (it's gone)"}")
    }

    fun size(id: String): BlockVector? {
        val file = file(id) ?: return build(id)?.let(::buildSize)
        return ops.size(file) ?: unreadable(id, file)
    }

    /** A build's size: its whole parts, and what its last part along each axis holds. */
    private fun buildSize(build: Build): BlockVector {
        val grid = build.grid
        val last = build.part(grid.x - 1, grid.y - 1, grid.z - 1)
        val tail = ops.size(last) ?: throw LuaApiException(
            "${build.shown(
                grid.x - 1,
                grid.y - 1,
                grid.z - 1
            )} can't be read as a structure${if (last.isRegularFile()) "" else " (it's gone)"}"
        )
        return BlockVector(
            (grid.x - 1) * Builds.PART_SIDE + tail.x,
            (grid.y - 1) * Builds.PART_SIDE + tail.y,
            (grid.z - 1) * Builds.PART_SIDE + tail.z
        )
    }

    /**
     * What `world:place_gradually` goes through to place [id]: a structure's one file, or a build's parts from the
     * bottom up, each with where it sits in the build and the size its place in the grid says it has (whole parts
     * [Builds.PART_SIDE] a side, the last along each axis what the last part holds). An error when there's nothing
     * by that id, or a build's last part can't be read.
     */
    fun pieces(id: String): List<Piece> {
        file(id)?.let { file ->
            return listOf(Piece(file, projectPath(id) ?: "the structure saved as \"$id\"", BlockVector(0, 0, 0)))
        }
        val build = build(id) ?: missing(id)
        val size = buildSize(build)
        val grid = build.grid
        fun side(part: Int, parts: Int, whole: Int) = if (part < parts - 1) Builds.PART_SIDE else whole - (parts - 1) * Builds.PART_SIDE
        return grid.parts.map { part ->
            val expected = BlockVector(side(part.x, grid.x, size.x), side(part.y, grid.y, size.y), side(part.z, grid.z, size.z))
            val shown = build.shown(part.x, part.y, part.z)
            Piece(build.part(part.x, part.y, part.z), shown, BlockVector(part.originX, part.originY, part.originZ)) { actual ->
                if (actual == expected) {
                    null
                } else {
                    "$shown is ${actual.x} × ${actual.y} × ${actual.z} blocks, but its place in the build's grid makes it " +
                        "${expected.x} × ${expected.y} × ${expected.z} (every part is ${Builds.PART_SIDE} a side but the last along each axis)"
                }
            }
        }
    }

    fun place(id: String, world: String, at: BlockVector, rotation: Long, mirror: String, integrity: Double, entities: Boolean): Boolean {
        if (rotation % 90 != 0L) throw LuaApiException("rotation must be a multiple of 90, not $rotation")
        if (integrity !in 0.0..1.0) throw LuaApiException("integrity must be between 0 and 1, not $integrity")
        if (file(id) == null) {
            build(id)?.let { build ->
                throw LuaApiException(
                    "\"$id\" is a build (${build.location}/), too big to place in one tick: place it with world:place_gradually"
                )
            }
        }
        val file = required(id)
        val ops = ops
        if (!platform.worlds.exists(world)) return false
        ops.size(file) ?: unreadable(id, file)
        val placement = StructurePlacement(Math.floorMod(rotation, 360L).toInt(), mirror, integrity, entities)
        val placed = ops.place(file, world, at, placement)
        if (placed && entities) placedWithEntities()
        return placed
    }

    fun save(id: String, world: String, from: BlockVector, to: BlockVector, entities: Boolean): Boolean {
        if (!Names.isId(id)) throw LuaApiException("\"$id\" can't name a structure (${Names.ID_RULE})")
        projectPath(id)?.let { path ->
            throw LuaApiException("\"$id\" is one of the project's structures ($path): save under another id")
        }
        build(id)?.let { build ->
            throw LuaApiException("\"$id\" is one of the project's builds (${build.location}/): save under another id")
        }
        checkSides(from, to)
        val ops = ops
        if (!platform.worlds.exists(world)) return false
        val (min, max) = corners(from, to)
        val file = savedFile(id)
        runCatching { Files.createDirectories(saved) }
        val ok = ops.save(file, world, min, max, entities)
        // What the adapter read of the last file under this id is stale now.
        ops.forget(file)
        return ok
    }

    /**
     * Saves the box from [from] to [to] as a structure file and answers its
     * bytes and size, for the editor to write into the project (the dev
     * bridge's `save_structure`). The file is made in the data folder and
     * removed at once: the server never writes project files. Same limits as
     * [save]; throws [IllegalArgumentException] for a world that isn't loaded.
     */
    fun capture(world: String, from: BlockVector, to: BlockVector, entities: Boolean): Pair<ByteArray, BlockVector> {
        checkSides(from, to)
        val ops = ops
        require(platform.worlds.exists(world)) { "no world \"$world\" is loaded" }
        val (min, max) = corners(from, to)
        val folder = dataDirectory.resolve(".nf").resolve("captures")
        Files.createDirectories(folder)
        val file = Files.createTempFile(folder, "capture", StructureKind.EXTENSION)
        try {
            check(ops.save(file, world, min, max, entities)) { "the server couldn't save that box" }
            return Files.readAllBytes(file) to BlockVector(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1)
        } finally {
            ops.forget(file)
            Files.deleteIfExists(file)
        }
    }

    private fun checkSides(from: BlockVector, to: BlockVector) {
        val sides = listOf(abs(from.x - to.x), abs(from.y - to.y), abs(from.z - to.z)).map { it + 1 }
        if (sides.any { it > MAX_SIDE }) {
            throw LuaApiException("that box is ${sides.joinToString("×")} blocks; a structure is at most $MAX_SIDE along each side")
        }
    }

    private fun corners(from: BlockVector, to: BlockVector) = BlockVector(minOf(from.x, to.x), minOf(from.y, to.y), minOf(from.z, to.z)) to
        BlockVector(maxOf(from.x, to.x), maxOf(from.y, to.y), maxOf(from.z, to.z))

    /** The project's build [id] changed on disk (or went): the next placement or size reads its parts again. */
    private fun forgetBuild(id: String) {
        val folder = project().resolve(snapshot()?.placeOf(BuildKind, id)?.location ?: BuildKind.locationOf(id))
        runCatching { folder.listDirectoryEntries("*${Builds.EXTENSION}") }.getOrDefault(emptyList()).forEach(ops::forget)
    }

    /** The project's structure [id] changed on disk (or went): the next placement reads it again. */
    private fun reload(id: String) {
        ops.forget(project().resolve(snapshot()?.placeOf(StructureKind, id)?.path ?: StructureKind.pathOf(id)))
    }

    companion object {
        /** The longest side a saved structure may have: what a structure block saves. */
        const val MAX_SIDE = 48
    }
}
