package dev.netherforge.plugin.platform

import dev.netherforge.format.terrain.CompiledTerrain
import dev.netherforge.format.terrain.StructureTemplate
import dev.netherforge.format.terrain.TerrainScripts
import java.io.IOException
import java.nio.file.Path
import java.util.UUID

/*
 * Managing worlds rather than using them: making, loading, copying, unloading
 * and deleting worlds ([WorldManagerOps]), borders ([BorderOps]) and
 * structures ([StructureOps]). Where a world's files live is the adapter's
 * business (it changes between Minecraft versions); the runtime only says
 * which world, and which map folder to copy.
 */

/**
 * How a new world is made. [generator] is `normal`, `flat`, `void`,
 * `amplified` or `large_biomes`; [environment] is `normal`, `nether` or `end`.
 * A world a project generator makes has [terrain], that generator's id as
 * [WorldManagerOps.publishGenerators] was given it (and [generator] `normal`).
 * A world of a project dimension type has [dimensionType], the type as the server
 * knows it (`showcase:deep`, one of [WorldManagerOps.dimensionTypes]).
 */
data class WorldSettings(
    val generator: String,
    val environment: String,
    /** Null: a random one. */
    val seed: Long?,
    val structures: Boolean,
    val keepSpawnLoaded: Boolean,
    val terrain: String? = null,
    val dimensionType: String? = null
)

/**
 * A project's terrain as an adapter runs it on the server's chunk
 * threads: the compiled file, the block state (canonical text) each of its
 * [palette][CompiledTerrain.palette] entries is written into a chunk as, and
 * the biome each of its [areas' biomes][CompiledTerrain.biomes] is on the
 * server. A custom block's state is the note block state it's held as, which
 * the project's blocks adopt when the chunk loads; a biome is the server's key
 * for it (`minecraft:plains`, a project biome's `showcase:ruby_grove`). Immutable,
 * so it's shared with every thread.
 *
 * A file with a script has [scripts]: what its Lua runs with (luajava, its
 * files read when it was published, and who hears of its failures), which
 * the adapter hands [CompiledTerrain.bind]. The states it runs in are the
 * bound generator's, one per chunk thread at a time; the adapter closes a
 * replaced generator ([dev.netherforge.format.terrain.TerrainGenerator.close])
 * so they go.
 */
class ProjectGenerator(
    val terrain: CompiledTerrain,
    val states: List<String>,
    val biomes: Map<String, String>,
    val scripts: TerrainScripts? = null
) {
    init {
        require(states.size == terrain.palette.size) { "a state for every block of the palette" }
        require(biomes.keys.containsAll(terrain.biomes)) { "a key for every biome of the areas" }
    }
}

interface WorldManagerOps {
    /** Whether the server has a world saved by this name, loaded or not. */
    fun isSaved(name: String): Boolean

    /**
     * Makes a new world and loads it. False when the server wouldn't. A
     * [WorldSettings.dimension] is kept with the world by the server (its saved
     * generation settings name it), so [load] needn't be told it again.
     */
    fun create(name: String, settings: WorldSettings): Boolean

    /**
     * The dimension types the server has, as it knows them (`minecraft:overworld`, a project's `showcase:deep`): the
     * game's, and those of the datapacks it started with. A world can be made, or loaded, with one of these only.
     */
    fun dimensionTypes(): Set<String>

    /**
     * Loads a world the server has saved. False when there's none, or the
     * server wouldn't. [environment] is what it was made as (the server
     * doesn't keep it: a nether loaded as `normal` would be one no longer):
     * `normal`, `nether` or `end`, and `normal` for one nobody here made.
     * [terrain] is the id of the project generator it was made with (the
     * server doesn't keep that either), null for any other.
     */
    fun load(name: String, environment: String, terrain: String? = null): Boolean

    /**
     * Replaces the project generators worlds are made with, by id (as
     * [WorldSettings.terrain] names them). A world's chunks generated from now
     * on use the new ones; chunks already generated stay as they are. It's
     * what hot reload does to a generator, and it's called from the main
     * thread: the generators run on the server's chunk threads, off it.
     * A world whose generator is no longer published keeps generating with the
     * last one it had.
     */
    fun publishGenerators(generators: Map<String, ProjectGenerator>)

    /**
     * What copying the map folder [map] into a new world [name] takes:
     * file work that puts it where [load] then finds it (without its lock and
     * identity files). Asked on the main thread; the runtime runs the work on
     * a worker, after every copy and deletion asked for before, and then
     * [load]s `normal` on the main thread. The work touches nothing but
     * files, and leaves nothing half-copied when it fails.
     */
    fun copy(map: Path, name: String): FileWork

    /** Unloads a loaded world, saving it first with [save]. False when the server kept it. */
    fun unload(name: String, save: Boolean): Boolean

    /**
     * What deleting an unloaded world's files takes: asked on the main thread
     * (where the server says where they are), run by the runtime on a worker,
     * after every copy and deletion asked for before.
     */
    fun delete(name: String): FileWork

    /**
     * Saves a loaded world and flushes it to disk, so its files can be copied
     * while the server runs (the dev bridge's `save_world`, which makes a
     * map). Null when no world by that name is loaded.
     */
    fun saveFiles(name: String): WorldFiles?
}

/**
 * Where a world's files are, as absolute paths: [level] is the `level.dat`
 * of the storage it lives in, [dimension] the world's own folder (its region
 * files and saved data). A map is the two together, the dimension
 * as the map's overworld.
 */
data class WorldFiles(val level: Path, val dimension: Path)

/**
 * Blocking work on files that an adapter describes and the runtime runs off
 * the main thread (`Workers`), in order with the rest of its kind: a world's
 * files copied or deleted. It may throw [IOException], which is the failure.
 */
fun interface FileWork {
    @Throws(IOException::class)
    fun run()
}

/** Whose border: a world's, or one player's own. */
sealed interface BorderOwner {
    data class World(val name: String) : BorderOwner

    data class Player(val uuid: UUID) : BorderOwner
}

/** Everything a border is. [warningTicks] is how soon a shrinking border warns, in ticks. */
data class BorderState(
    val centerX: Double,
    val centerZ: Double,
    /** Side to side, in blocks, now (mid-way, while it's moving). */
    val size: Double,
    val damageAmount: Double,
    val damageBuffer: Double,
    val warningDistance: Int,
    val warningTicks: Int
)

/**
 * World borders, and borders players see alone. A player's own border is the
 * adapter's to keep (and to show again when the server would draw their
 * world's over it, as it does on a respawn or a change of world) until
 * [reset] or they leave. Everything answers null or false for a world that
 * has gone, a player who's offline, or a player without a border of their own.
 */
interface BorderOps {
    /** The widest a border can be. */
    val maxSize: Double

    /** How far from 0 a border's centre can be, along x or z. */
    val maxCenter: Double

    fun get(owner: BorderOwner): BorderState?

    fun setCenter(owner: BorderOwner, x: Double, z: Double): Boolean

    /** Changes the size at once, or moving there over [ticks] when that's above 0. */
    fun setSize(owner: BorderOwner, size: Double, ticks: Long): Boolean

    fun setDamage(owner: BorderOwner, amount: Double, buffer: Double): Boolean

    fun setWarning(owner: BorderOwner, distance: Int, ticks: Int): Boolean

    /** Whether a point is inside. [world]: the point's world, which must be a world border's own; null for a bare position. */
    fun contains(owner: BorderOwner, world: String?, x: Double, y: Double, z: Double): Boolean

    /** Gives a player a border of their own (a copy of their world's) unless they have one. False when they're offline. */
    fun personal(player: UUID): Boolean

    /** Takes a player's own border away: they see their world's again. False when they had none. */
    fun reset(player: UUID): Boolean
}

/** How a structure is placed: [rotation] clockwise in degrees (0, 90, 180 or 270), [mirror] `none`, `left_right` or `front_back`. */
data class StructurePlacement(val rotation: Int, val mirror: String, val integrity: Double, val entities: Boolean)

/** A block-aligned size or position, in blocks. */
data class BlockVector(val x: Int, val y: Int, val z: Int)

/**
 * Where a structure file's blocks and entities go when what it's part of is placed with its smallest corner at
 * [origin], mirrored by [mirror] then turned [rotation] degrees clockwise about that corner: the game's own
 * arithmetic (its structure template's `transform` with the pivot at the corner), so a gradual placement puts
 * every block where `/place` would. [offset] is where the file sits inside what's placed: a build's part, or
 * nothing for a structure.
 */
class StructureTransform(val origin: BlockVector, val offset: BlockVector, val rotation: Int, val mirror: String) {
    init {
        require(rotation in setOf(0, 90, 180, 270)) { "rotation $rotation" }
        require(mirror in setOf("none", "left_right", "front_back")) { "mirror $mirror" }
    }

    /** Where the block at [x], [y], [z] in the file goes in the world. */
    fun block(x: Int, y: Int, z: Int): BlockVector {
        var bx = offset.x + x
        var bz = offset.z + z
        when (mirror) {
            "left_right" -> bz = -bz
            "front_back" -> bx = -bx
        }
        val (tx, tz) = when (rotation) {
            90 -> -bz to bx
            180 -> -bx to -bz
            270 -> bz to -bx
            else -> bx to bz
        }
        return BlockVector(origin.x + tx, origin.y + offset.y + y, origin.z + tz)
    }

    /** Where an entity at [x], [y], [z] in the file goes in the world: a point, so turning it keeps it inside its block. */
    fun point(x: Double, y: Double, z: Double): Triple<Double, Double, Double> {
        var px = offset.x + x
        var pz = offset.z + z
        when (mirror) {
            "left_right" -> pz = 1.0 - pz
            "front_back" -> px = 1.0 - px
        }
        val (tx, tz) = when (rotation) {
            90 -> 1.0 - pz to px
            180 -> 1.0 - px to 1.0 - pz
            270 -> pz to 1.0 - px
            else -> px to pz
        }
        return Triple(origin.x + tx, origin.y + offset.y + y, origin.z + tz)
    }

    /** The yaw an entity facing [yaw] has once placed: mirrored, then turned (the game's `mirror` and `rotate`). */
    fun yaw(yaw: Float): Float {
        val wrapped = wrapDegrees(yaw)
        val mirrored = when (mirror) {
            "front_back" -> -wrapped
            "left_right" -> 180f - wrapped
            else -> wrapped
        }
        return wrapDegrees(mirrored + rotation)
    }

    /** A horizontal facing (`north`, `east`, `south`, `west`) once placed; any other (`up`, `down`) stays. */
    fun facing(facing: String): String {
        val mirrored = when {
            mirror == "left_right" && facing == "north" -> "south"
            mirror == "left_right" && facing == "south" -> "north"
            mirror == "front_back" && facing == "east" -> "west"
            mirror == "front_back" && facing == "west" -> "east"
            else -> facing
        }
        val at = HORIZONTAL.indexOf(mirrored)
        return if (at < 0) mirrored else HORIZONTAL[(at + rotation / 90) % 4]
    }

    private fun wrapDegrees(degrees: Float): Float {
        val turned = degrees % 360f
        return when {
            turned >= 180f -> turned - 360f
            turned < -180f -> turned + 360f
            else -> turned
        }
    }

    private companion object {
        /** Clockwise, seen from above. */
        val HORIZONTAL = listOf("north", "east", "south", "west")
    }
}

/**
 * One structure file being placed a few blocks at a time ([StructureOps.placer]): its blocks in the file's own
 * order (the game saves whole blocks first, bottom up, then blocks with data, then the rest, so what stands on
 * something goes in after it), then its entities.
 */
interface StructurePlacer {
    /** The file's size, in blocks along x, y and z (before it's turned). */
    val size: BlockVector

    /** Whether the file holds entities (other than NetherForge's own) that [entities] would place. */
    val hasEntities: Boolean

    /** Blocks it hasn't gone through yet. */
    val remaining: Int

    /** Blocks it has placed: not those integrity left out, nor those outside the world's heights. */
    val placed: Long

    /**
     * Goes through up to [budget] more blocks, placing each as it was saved (its data with it, no updates to its
     * neighbours) and loading its chunk if need be; answers how many it went through, or null when the world has gone.
     */
    fun place(budget: Int): Int?

    /** Places its entities (none of NetherForge's own), once every block is in. False when the world has gone. */
    fun entities(): Boolean
}

/**
 * Structures, in Minecraft's structure file format. A file is read once and
 * kept until [forget] (a reload of it); placing loads the chunks it covers.
 */
interface StructureOps {
    /** The size of the structure in [file], or null when it can't be read as one. */
    fun size(file: Path): BlockVector?

    /**
     * The blocks of the structure in [file] as a terrain places them (its first palette, air included, every
     * state in full as the server reads it), or null when it can't be read as one. A decoration of a generator is
     * linked to it ([dev.netherforge.format.terrain.CompiledTerrain.withStructures]).
     */
    fun template(file: Path): StructureTemplate?

    /** Places the structure in [file] with its smallest corner at [at]. False when the world has gone or the file can't be read. */
    fun place(file: Path, world: String, at: BlockVector, placement: StructurePlacement): Boolean

    /**
     * The structure in [file] to place a few blocks at a time where [transform] says, with [placement]'s
     * integrity and entities ([placement]'s rotation and mirror are [transform]'s), integrity's chances drawn from
     * [seed]. Null when the world has gone or the file can't be read. A file not already kept is read for this and
     * let go with it, so a build's parts don't all stay in memory.
     */
    fun placer(file: Path, world: String, transform: StructureTransform, placement: StructurePlacement, seed: Long): StructurePlacer?

    /** Saves the box from [min] to [max] (both included) into [file], replacing it. False when the world has gone or it can't be written. */
    fun save(file: Path, world: String, min: BlockVector, max: BlockVector, entities: Boolean): Boolean

    /** Drops what was read from [file]: it changed. */
    fun forget(file: Path)
}
