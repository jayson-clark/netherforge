package dev.netherforge.plugin.testkit

import dev.netherforge.format.terrain.StructureTemplate
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.BorderOps
import dev.netherforge.plugin.platform.BorderOwner
import dev.netherforge.plugin.platform.BorderState
import dev.netherforge.plugin.platform.FileWork
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.ProjectGenerator
import dev.netherforge.plugin.platform.StructureOps
import dev.netherforge.plugin.platform.StructurePlacement
import dev.netherforge.plugin.platform.StructurePlacer
import dev.netherforge.plugin.platform.StructureTransform
import dev.netherforge.plugin.platform.WorldFiles
import dev.netherforge.plugin.platform.WorldManagerOps
import dev.netherforge.plugin.platform.WorldSettings
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.UUID
import kotlin.io.path.isRegularFile
import kotlin.io.path.readLines
import kotlin.io.path.writeLines

/*
 * The fake server's world management, borders and structures (`platform/WorldAdmin.kt`),
 * kept beside FakePlatform so its worlds, players and blocks are the ones these act on.
 */

/**
 * Worlds made, loaded, copied, unloaded and deleted, as the fake's
 * [FakeWorlds.worldNames]. A copy's file work makes the world
 * saved (with no files) on whichever thread runs it, for [load] to load, as
 * Paper's imports the copied folder.
 */
class FakeWorldManager(private val platform: FakePlatform) : WorldManagerOps {
    private companion object {
        val DIMENSION_TYPE = Regex("^data/([^/]+)/dimension_type/(.+)\\.json$")
    }

    /** Worlds the server has saved but not loaded; a copy's work, on a worker, adds to it. */
    val saved: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())

    /** How each world scripts made was made. */
    val created = LinkedHashMap<String, WorldSettings>()

    /** Every copy asked for: the map folder and the new world's name. */
    val copies = mutableListOf<Pair<Path, String>>()

    /** Every unload, as `"<world> save=<bool>"`, and every deletion. */
    val unloads = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    /** When set, copies fail with this. */
    var copyFailure: String? = null

    /** Worlds the server refuses to unload (another plugin keeps them). */
    val kept = mutableSetOf<String>()

    private val names get() = platform.worlds.worldNames

    override fun isSaved(name: String) = name in names || name in saved

    /** The game's own dimension types, which every server has. */
    val vanillaDimensionTypes = setOf("minecraft:overworld", "minecraft:overworld_caves", "minecraft:the_nether", "minecraft:the_end")

    /** The game's own and those the start-up datapack the fake server started with has (`data/<ns>/dimension_type/<id>.json`). */
    override fun dimensionTypes(): Set<String> = vanillaDimensionTypes + platform.datapacks.started.keys.mapNotNull { path ->
        DIMENSION_TYPE.matchEntire(path)?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }
    }

    /**
     * World [name]'s heights (min and max, exclusive) as a server gives them: its dimension type's, read from the
     * started datapack's file, as the server reads it. A world of the overworld's type (the main world, one made
     * without a dimension) has the datapack's `minecraft:overworld` when it replaces it, else -64 to 320.
     */
    fun heightsOf(name: String): Pair<Int, Int> {
        val type = created[name]?.dimensionType ?: dimensionOfSaved[name] ?: "minecraft:overworld"
        val (namespace, path) = type.split(':', limit = 2)
        val bytes = platform.datapacks.started["data/$namespace/dimension_type/$path.json"] ?: return -64 to 320
        val json = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
        val minY = json.getValue("min_y").jsonPrimitive.int
        return minY to minY + json.getValue("height").jsonPrimitive.int
    }

    /** The dimension type each saved world (one a test put there) was made with, as the server keeps it. */
    val dimensionOfSaved = mutableMapOf<String, String>()

    override fun create(name: String, settings: WorldSettings): Boolean {
        // As Paper: a dimension type the server lacks isn't something it can make a world of.
        if (settings.dimensionType != null && settings.dimensionType !in dimensionTypes()) return false
        created[name] = settings
        names += name
        platform.raise.worldLoad(GameEvent.World(name))
        return true
    }

    /** Every load, as `"<world> <environment>"`, and `" <generator>"` more for a project generator's. */
    val loads = mutableListOf<String>()

    override fun load(name: String, environment: String, terrain: String?): Boolean {
        loads += "$name $environment" + (terrain?.let { " $it" } ?: "")
        if (!saved.remove(name)) return false
        names += name
        platform.raise.worldLoad(GameEvent.World(name))
        return true
    }

    /** The project generators last published (by id), and how many times they were. */
    var generators: Map<String, ProjectGenerator> = emptyMap()
        private set

    var publications = 0
        private set

    override fun publishGenerators(generators: Map<String, ProjectGenerator>) {
        this.generators = generators
        publications++
    }

    override fun copy(map: Path, name: String): FileWork {
        copies += map to name
        val failure = copyFailure
        // Run on a worker: it only makes the world saved, as the files would, for load to find.
        return FileWork {
            if (failure != null) throw IOException(failure)
            if (!map.resolve("level.dat").isRegularFile()) throw IOException("$map has no level.dat")
            saved += name
        }
    }

    override fun unload(name: String, save: Boolean): Boolean {
        if (name in kept || name !in names) return false
        // As Paper: a world with players in it stays.
        if (platform.players.byId.values.any { it.location.world == name }) return false
        // Heard while it's still there, as the server's unload event is.
        platform.raise.worldUnload(GameEvent.World(name))
        names -= name
        saved += name
        unloads += "$name save=$save"
        return true
    }

    override fun delete(name: String): FileWork {
        saved -= name
        deleted += name
        return FileWork {}
    }

    /**
     * Where the fake's world storage is, as on 26.x: `level.dat` and
     * `dimensions/minecraft/<world>/`; the main world's is `overworld`. A
     * temporary directory unless a test says where.
     */
    var storage: Path
        get() = chosen ?: Files.createTempDirectory("netherforge-fake-worlds").also { chosen = it }
        set(value) {
            chosen = value
        }

    private var chosen: Path? = null

    /** Every world saved for the editor. */
    val savedFiles = mutableListOf<String>()

    override fun saveFiles(name: String): WorldFiles? {
        if (name !in names) return null
        savedFiles += name
        val dimension = if (name == platform.worlds.defaultWorld()) "overworld" else name
        val files = WorldFiles(storage.resolve("level.dat"), storage.resolve("dimensions/minecraft/$dimension"))
        // As the server writes them: the storage's level.dat and the world's own folder.
        Files.createDirectories(files.dimension)
        if (!Files.exists(files.level)) Files.write(files.level, ByteArray(0))
        return files
    }
}

/** Borders as numbers: each world's (vanilla's defaults until changed) and players' own. */
class FakeBorders(private val platform: FakePlatform) : BorderOps {
    override val maxSize = 59_999_968.0
    override val maxCenter = 29_999_984.0

    val worlds = LinkedHashMap<String, BorderState>()
    val players = LinkedHashMap<UUID, BorderState>()

    /** Each size change, as `"<owner> <size> over <ticks>"`. */
    val sizes = mutableListOf<String>()

    private fun state(owner: BorderOwner): BorderState? = when (owner) {
        is BorderOwner.World -> if (platform.worlds.exists(owner.name)) worlds.getOrPut(owner.name) { DEFAULT } else null
        is BorderOwner.Player -> players[owner.uuid]?.takeIf { owner.uuid in platform.players.byId }
    }

    private fun update(owner: BorderOwner, change: (BorderState) -> BorderState): Boolean {
        val now = state(owner) ?: return false
        when (owner) {
            is BorderOwner.World -> worlds[owner.name] = change(now)
            is BorderOwner.Player -> players[owner.uuid] = change(now)
        }
        return true
    }

    override fun get(owner: BorderOwner) = state(owner)

    override fun setCenter(owner: BorderOwner, x: Double, z: Double) = update(owner) { it.copy(centerX = x, centerZ = z) }

    override fun setSize(owner: BorderOwner, size: Double, ticks: Long) = update(owner) {
        sizes += "$owner $size over $ticks"
        it.copy(size = size)
    }

    override fun setDamage(owner: BorderOwner, amount: Double, buffer: Double) =
        update(owner) { it.copy(damageAmount = amount, damageBuffer = buffer) }

    override fun setWarning(owner: BorderOwner, distance: Int, ticks: Int) =
        update(owner) { it.copy(warningDistance = distance, warningTicks = ticks) }

    override fun contains(owner: BorderOwner, world: String?, x: Double, y: Double, z: Double): Boolean {
        val border = state(owner) ?: return false
        if (owner is BorderOwner.World && world != null && world != owner.name) return false
        val half = border.size / 2
        return x >= border.centerX - half && x < border.centerX + half && z >= border.centerZ - half && z < border.centerZ + half
    }

    override fun personal(player: UUID): Boolean {
        val where = platform.players.byId[player]?.location?.world ?: return false
        if (player !in players) players[player] = state(BorderOwner.World(where)) ?: DEFAULT
        return true
    }

    override fun reset(player: UUID): Boolean = player in platform.players.byId && players.remove(player) != null

    companion object {
        val DEFAULT = BorderState(0.0, 0.0, 59_999_968.0, 0.2, 5.0, 5, 300)
    }
}

/**
 * Structures in a text file of the fake's own: `size x y z`, then one
 * `x y z state` line per block that isn't air, relative to the smallest
 * corner. Placing writes those blocks back at rotation 0 without a mirror
 * (anything else is only recorded in [placed]).
 */
class FakeStructures(private val platform: FakePlatform) : StructureOps {
    /** Every placement, as `"<file name> <world> x y z rotation=<r> mirror=<m> integrity=<i> entities=<e>"`. */
    val placed = mutableListOf<String>()

    /** Files read and kept, as Paper's adapter keeps them until forgotten. */
    val cache = LinkedHashMap<Path, List<String>>()
    val forgotten = mutableListOf<Path>()

    private fun lines(file: Path): List<String>? = cache[file] ?: runCatching { file.readLines() }.getOrNull()
        ?.takeIf { it.firstOrNull()?.startsWith("size ") == true }
        ?.also { cache[file] = it }

    override fun size(file: Path): BlockVector? {
        val (x, y, z) = lines(file)?.first()?.split(' ')?.drop(1)?.map { it.toInt() } ?: return null
        return BlockVector(x, y, z)
    }

    /**
     * Its blocks as saved (air left out, as the fake saves none; the server's own read lists air too), or a real
     * structure file's (a project's `.nbt`) as [StructureFiles] reads it.
     */
    override fun template(file: Path): StructureTemplate? {
        if (runCatching {
                StructureFiles.isGzip(file)
            }.getOrDefault(false)
        ) {
            return runCatching { StructureFiles.template(file) }.getOrNull()
        }
        val lines = lines(file) ?: return null
        val (x, y, z) = lines.first().split(' ').drop(1).map { it.toInt() }
        val palette = LinkedHashMap<String, Int>()
        val blocks = lines.drop(1).flatMap { line ->
            val parts = line.split(' ')
            parts.take(3).map { it.toInt() } + palette.getOrPut(parts[3]) { palette.size }
        }
        return StructureTemplate(x, y, z, palette.keys.toList(), blocks.toIntArray())
    }

    override fun place(file: Path, world: String, at: BlockVector, placement: StructurePlacement): Boolean {
        if (!platform.worlds.exists(world)) return false
        val lines = lines(file) ?: return false
        placed += "${file.fileName} $world ${at.x} ${at.y} ${at.z} rotation=${placement.rotation} mirror=${placement.mirror} " +
            "integrity=${placement.integrity} entities=${placement.entities}"
        if (placement.rotation == 0 && placement.mirror == "none") {
            for (line in lines.drop(1)) {
                val parts = line.split(' ')
                val (x, y, z) = parts.take(3).map { it.toInt() }
                platform.worlds.blocks[BlockAt(world, at.x + x, at.y + y, at.z + z)] = parts[3]
            }
        }
        return true
    }

    /** Gradual placements opened, as `"<file name> <world> origin=x,y,z offset=x,y,z rotation=<r> mirror=<m>"`. */
    val placers = mutableListOf<String>()

    /**
     * Its blocks (the fake's text, or a real file's, air and all) put where [transform] says, a few at a time; a
     * block's state is written as saved (the fake doesn't turn states). It holds no entities.
     */
    override fun placer(
        file: Path,
        world: String,
        transform: StructureTransform,
        placement: StructurePlacement,
        seed: Long
    ): StructurePlacer? {
        if (!platform.worlds.exists(world)) return null
        val template = template(file) ?: return null
        val states = template.palette
        val blocks = template.blocks
        placers += "${file.fileName} $world origin=${transform.origin.x},${transform.origin.y},${transform.origin.z} " +
            "offset=${transform.offset.x},${transform.offset.y},${transform.offset.z} rotation=${placement.rotation} mirror=${placement.mirror}"
        val random = java.util.Random(seed)
        return object : StructurePlacer {
            private var next = 0
            override val size = BlockVector(template.sizeX, template.sizeY, template.sizeZ)
            override val hasEntities = false
            override val remaining get() = blocks.size / 4 - next
            override var placed = 0L

            override fun place(budget: Int): Int? {
                if (!platform.worlds.exists(world)) return null
                val (bottom, top) = platform.worldManager.heightsOf(world)
                var went = 0
                while (went < budget && next < blocks.size / 4) {
                    val i = next++ * 4
                    went++
                    if (placement.integrity < 1.0 && random.nextFloat() > placement.integrity) continue
                    val at = transform.block(blocks[i], blocks[i + 1], blocks[i + 2])
                    if (at.y < bottom || at.y >= top) continue
                    platform.worlds.blocks[BlockAt(world, at.x, at.y, at.z)] = states[blocks[i + 3]]
                    placed++
                }
                return went
            }

            override fun entities(): Boolean = platform.worlds.exists(world)
        }
    }

    override fun save(file: Path, world: String, min: BlockVector, max: BlockVector, entities: Boolean): Boolean {
        if (!platform.worlds.exists(world)) return false
        val out = mutableListOf("size ${max.x - min.x + 1} ${max.y - min.y + 1} ${max.z - min.z + 1}")
        for (x in min.x..max.x) {
            for (y in min.y..max.y) {
                for (z in min.z..max.z) {
                    val state = platform.worlds.state(world, x, y, z)
                    if (state != "minecraft:air") out += "${x - min.x} ${y - min.y} ${z - min.z} $state"
                }
            }
        }
        Files.createDirectories(file.parent)
        file.writeLines(out)
        return true
    }

    override fun forget(file: Path) {
        cache.remove(file)
        forgotten.add(file)
    }
}
