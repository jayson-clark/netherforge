package dev.netherforge.plugin.paper

import dev.netherforge.format.terrain.StructureTemplate
import dev.netherforge.plugin.platform.BlockVector
import dev.netherforge.plugin.platform.BorderOps
import dev.netherforge.plugin.platform.BorderOwner
import dev.netherforge.plugin.platform.BorderState
import dev.netherforge.plugin.platform.FileWork
import dev.netherforge.plugin.platform.ProjectGenerator
import dev.netherforge.plugin.platform.StructureOps
import dev.netherforge.plugin.platform.StructurePlacement
import dev.netherforge.plugin.platform.StructurePlacer
import dev.netherforge.plugin.platform.StructureTransform
import dev.netherforge.plugin.platform.WorldFiles
import dev.netherforge.plugin.platform.WorldManagerOps
import dev.netherforge.plugin.platform.WorldSettings
import net.kyori.adventure.util.TriState
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.World
import org.bukkit.WorldBorder
import org.bukkit.WorldCreator
import org.bukkit.WorldType
import org.bukkit.block.BlockFace
import org.bukkit.block.BlockState
import org.bukkit.block.TileState
import org.bukkit.block.structure.Mirror
import org.bukkit.block.structure.StructureRotation
import org.bukkit.entity.Hanging
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.player.PlayerChangedWorldEvent
import org.bukkit.event.player.PlayerQuitEvent
import org.bukkit.event.player.PlayerRespawnEvent
import org.bukkit.generator.ChunkGenerator
import org.bukkit.plugin.java.JavaPlugin
import org.bukkit.structure.Structure
import org.bukkit.util.EntityTransformer
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import java.util.UUID
import java.util.logging.Level
import org.bukkit.util.BlockVector as BukkitBlockVector

/**
 * [WorldManagerOps] on Paper. Where a world's files are is [storage]'s (the
 * version's own, see [WorldStorage]): a copy installs the template there (file
 * work the runtime runs off the main thread, in order with deletions), then
 * `createWorld` loads it on the main thread ([load]).
 */
class PaperWorldManager(private val plugin: JavaPlugin, private val version: PaperVersion) : WorldManagerOps {
    private val storage: WorldStorage get() = version.worlds

    /** The project's terrains as the server's chunk generators. */
    val generators = PaperWorldGenerators(plugin.logger)

    /** Each unloaded world's data, kept from when it was loaded, for [delete]. */
    private val paths = HashMap<String, Path>()

    override fun isSaved(name: String): Boolean = Bukkit.getWorld(name) != null || storage.isSaved(name)

    override fun create(name: String, settings: WorldSettings): Boolean {
        val creator = WorldCreator(name)
            .environment(environment(settings.environment))
            .generateStructures(settings.structures)
            .keepSpawnLoaded(TriState.byBoolean(settings.keepSpawnLoaded))
        settings.seed?.let(creator::seed)
        settings.terrain?.let { withProjectGenerator(creator, it) }
        when (settings.generator) {
            "flat" -> creator.type(WorldType.FLAT)
            "amplified" -> creator.type(WorldType.AMPLIFIED)
            "large_biomes" -> creator.type(WorldType.LARGE_BIOMES)
            // A superflat with no layers: nothing generates. Kept in the world's generation settings, so it loads the same.
            "void" -> creator.type(WorldType.FLAT).generatorSettings(VOID)
        }
        settings.dimensionType?.let { type ->
            if (isSaved(name)) return false
            try {
                version.prepareDimension(creator, type)
            } catch (e: Exception) {
                plugin.logger.log(Level.WARNING, "Couldn't give world \"$name\" dimension type $type", e)
                storage.delete(name, storage.dimension(name))
                return false
            }
        }
        val world = createWorld(creator)
        // A world the server wouldn't make leaves nothing behind: the settings saved for it would make its name taken.
        if (world == null && settings.dimensionType != null) storage.delete(name, storage.dimension(name))
        return world != null
    }

    override fun dimensionTypes(): Set<String> = version.registries.ids(DIMENSION_TYPE).orEmpty()

    // createWorld makes a new world where none is saved: that's create's, not load's.
    override fun load(name: String, environment: String, terrain: String?): Boolean {
        if (!isSaved(name)) return false
        val creator = WorldCreator(name).environment(environment(environment))
        terrain?.let { withProjectGenerator(creator, it) }
        return createWorld(creator) != null
    }

    override fun publishGenerators(generators: Map<String, ProjectGenerator>) = this.generators.publish(generators)

    /**
     * The chunk generator of a world the server loads itself with the project generator [id] (its main world, which it
     * asks the plugin for before loading it); the world's biomes come with it, from its default biome provider.
     */
    fun startupGenerator(id: String): ChunkGenerator = generators.forWorld(id).first

    /** The world's terrain and biomes are the project generator's: its chunks are made on the server's chunk threads. */
    private fun withProjectGenerator(creator: WorldCreator, id: String) {
        val (chunks, biomes) = generators.forWorld(id)
        creator.generator(chunks).biomeProvider(biomes)
    }

    private fun createWorld(creator: WorldCreator): World? = try {
        Bukkit.createWorld(creator)
    } catch (e: Exception) {
        plugin.logger.log(Level.WARNING, "Couldn't load world \"${creator.name()}\"", e)
        null
    }

    // Only the files, off the main thread: the runtime then loads the copy.
    override fun copy(map: Path, name: String): FileWork = FileWork { storage.install(map, name) }

    override fun unload(name: String, save: Boolean): Boolean {
        val world = Bukkit.getWorld(name) ?: return false
        val path = world.worldPath
        if (!Bukkit.unloadWorld(world, save)) return false
        paths[name] = path
        return true
    }

    // Where its files are is asked now, on the main thread; deleting them is the runtime's to run off it.
    override fun delete(name: String): FileWork {
        val dimension = paths.remove(name) ?: storage.dimension(name)
        return FileWork { storage.delete(name, dimension) }
    }

    override fun saveFiles(name: String): WorldFiles? {
        val world = Bukkit.getWorld(name) ?: return null
        // Writes every chunk and the world's saved data, and waits for the region files to be on disk.
        world.save(true)
        return WorldFiles(storage.level(name), world.worldPath)
    }

    private fun environment(name: String) = when (name) {
        "nether" -> World.Environment.NETHER
        "end" -> World.Environment.THE_END
        else -> World.Environment.NORMAL
    }

    private companion object {
        val DIMENSION_TYPE = dev.netherforge.format.game.RegistryKey.DIMENSION_TYPE

        /** Superflat settings with no layers and no features: an empty world. */
        const val VOID = """{"layers":[],"biome":"minecraft:the_void","features":false,"lakes":false}"""
    }
}

/**
 * [BorderOps] on Paper: a world's own border, and players' own, made
 * with `Bukkit.createWorldBorder()` and kept here. The server sends a
 * player their world's border whenever they respawn or change world, so a
 * player's own is sent again a tick later; it's forgotten when they leave.
 */
class PaperBorders(private val plugin: JavaPlugin) :
    BorderOps,
    Listener {
    private val own = HashMap<UUID, WorldBorder>()

    private val sample: WorldBorder get() = Bukkit.getWorlds().first().worldBorder

    override val maxSize: Double get() = sample.maxSize

    override val maxCenter: Double get() = sample.maxCenterCoordinate

    private fun border(owner: BorderOwner): WorldBorder? = when (owner) {
        is BorderOwner.World -> Bukkit.getWorld(owner.name)?.worldBorder
        is BorderOwner.Player -> own[owner.uuid]?.takeIf { Bukkit.getPlayer(owner.uuid) != null }
    }

    override fun get(owner: BorderOwner): BorderState? = border(owner)?.let {
        BorderState(it.center.x, it.center.z, it.size, it.damageAmount, it.damageBuffer, it.warningDistance, it.warningTimeTicks)
    }

    private inline fun change(owner: BorderOwner, block: (WorldBorder) -> Unit): Boolean {
        block(border(owner) ?: return false)
        return true
    }

    override fun setCenter(owner: BorderOwner, x: Double, z: Double) = change(owner) { it.setCenter(x, z) }

    override fun setSize(owner: BorderOwner, size: Double, ticks: Long) = change(owner) { it.changeSize(size, ticks) }

    override fun setDamage(owner: BorderOwner, amount: Double, buffer: Double) = change(owner) {
        it.damageAmount = amount
        it.damageBuffer = buffer
    }

    override fun setWarning(owner: BorderOwner, distance: Int, ticks: Int) = change(owner) {
        it.warningDistance = distance
        it.warningTimeTicks = ticks
    }

    override fun contains(owner: BorderOwner, world: String?, x: Double, y: Double, z: Double): Boolean {
        val border = border(owner) ?: return false
        val place = when (owner) {
            is BorderOwner.World -> {
                if (world != null && world != owner.name) return false
                Location(Bukkit.getWorld(owner.name), x, y, z)
            }
            // A player's own border has no world: only the position counts.
            is BorderOwner.Player -> Location(null, x, y, z)
        }
        return border.isInside(place)
    }

    override fun personal(player: UUID): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        if (player in own) return true
        val from = who.world.worldBorder
        val border = Bukkit.createWorldBorder().apply {
            setCenter(from.center.x, from.center.z)
            size = from.size
            damageAmount = from.damageAmount
            damageBuffer = from.damageBuffer
            warningDistance = from.warningDistance
            warningTimeTicks = from.warningTimeTicks
        }
        own[player] = border
        who.worldBorder = border
        return true
    }

    override fun reset(player: UUID): Boolean {
        val who = Bukkit.getPlayer(player) ?: return false
        own.remove(player) ?: return false
        who.worldBorder = null
        return true
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onQuit(event: PlayerQuitEvent) {
        own.remove(event.player.uniqueId)
    }

    @EventHandler(priority = EventPriority.MONITOR)
    fun onChangedWorld(event: PlayerChangedWorldEvent) = showAgain(event.player.uniqueId)

    @EventHandler(priority = EventPriority.MONITOR)
    fun onRespawn(event: PlayerRespawnEvent) = showAgain(event.player.uniqueId)

    private fun showAgain(player: UUID) {
        if (player !in own) return
        Bukkit.getScheduler().runTask(
            plugin,
            Runnable {
                val border = own[player] ?: return@Runnable
                Bukkit.getPlayer(player)?.worldBorder = border
            }
        )
    }
}

/**
 * [StructureOps] with Paper's structure manager: files in Minecraft's
 * structure format, read once and kept until forgotten. Entities NetherForge
 * draws centities with are never placed from a structure (they'd come back
 * as strays nobody owns).
 */
class PaperStructures(private val entities: PaperEntities) : StructureOps {
    private val cache = HashMap<Path, Structure>()
    private val manager get() = Bukkit.getStructureManager()

    private fun read(file: Path): Structure? = cache[file] ?: try {
        manager.loadStructure(file.toFile())
    } catch (_: Exception) {
        null
    }?.also { cache[file] = it }

    override fun size(file: Path): BlockVector? = read(file)?.size?.let { BlockVector(it.blockX, it.blockY, it.blockZ) }

    override fun template(file: Path): StructureTemplate? {
        val structure = read(file) ?: return null
        val size = structure.size
        val palette = LinkedHashMap<String, Int>()
        val blocks = structure.palettes.firstOrNull()?.blocks.orEmpty()
        val out = IntArray(blocks.size * 4)
        // States that hold a block entity (a chest, a barrel): where a decoration's loot can go.
        val withEntity = HashSet<Int>()
        for ((i, block) in blocks.withIndex()) {
            out[i * 4] = block.x
            out[i * 4 + 1] = block.y
            out[i * 4 + 2] = block.z
            val entry = palette.getOrPut(block.blockData.asString) { palette.size }
            out[i * 4 + 3] = entry
            if (block is TileState) withEntity += entry
        }
        return StructureTemplate(size.blockX, size.blockY, size.blockZ, palette.keys.toList(), out, withEntity)
    }

    override fun place(file: Path, world: String, at: BlockVector, placement: StructurePlacement): Boolean {
        val w = Bukkit.getWorld(world) ?: return false
        val structure = read(file) ?: return false
        val notOurs = EntityTransformer { _, _, _, _, entity, allowed -> allowed && entities.tagOf(entity) == null }
        structure.place(
            Location(w, at.x.toDouble(), at.y.toDouble(), at.z.toDouble()),
            placement.entities,
            when (placement.rotation) {
                90 -> StructureRotation.CLOCKWISE_90
                180 -> StructureRotation.CLOCKWISE_180
                270 -> StructureRotation.COUNTERCLOCKWISE_90
                else -> StructureRotation.NONE
            },
            when (placement.mirror) {
                "left_right" -> Mirror.LEFT_RIGHT
                "front_back" -> Mirror.FRONT_BACK
                else -> Mirror.NONE
            },
            // A random palette, as /place does: a structure saved here has one.
            -1,
            placement.integrity.toFloat(),
            Random(),
            emptyList(),
            listOf(notOurs)
        )
        return true
    }

    override fun placer(
        file: Path,
        world: String,
        transform: StructureTransform,
        placement: StructurePlacement,
        seed: Long
    ): StructurePlacer? {
        val w = Bukkit.getWorld(world) ?: return null
        // A build's parts are read for this alone and let go with it; a structure already kept is used as kept.
        val structure = cache[file] ?: try {
            manager.loadStructure(file.toFile())
        } catch (_: Exception) {
            null
        } ?: return null
        return PaperPlacer(w, structure, transform, placement, Random(seed))
    }

    /**
     * Places [structure] a few blocks at a time, as the game's template would all at once: each block's data
     * mirrored then turned, its block entity's data with it, no updates to neighbours (`/place` sets them the
     * same way); then its entities, copied where the template would put them and turned as it turns them.
     */
    private inner class PaperPlacer(
        private val world: World,
        private val structure: Structure,
        private val transform: StructureTransform,
        private val placement: StructurePlacement,
        private val random: Random
    ) : StructurePlacer {
        // A random palette, as /place picks one: a structure saved here has one.
        private val blocks: List<BlockState> = structure.palettes.let {
            if (it.isEmpty()) emptyList() else it[random.nextInt(it.size)].blocks
        }
        private var next = 0
        private val rotation = when (placement.rotation) {
            90 -> StructureRotation.CLOCKWISE_90
            180 -> StructureRotation.CLOCKWISE_180
            270 -> StructureRotation.COUNTERCLOCKWISE_90
            else -> StructureRotation.NONE
        }
        private val mirror = when (placement.mirror) {
            "left_right" -> Mirror.LEFT_RIGHT
            "front_back" -> Mirror.FRONT_BACK
            else -> Mirror.NONE
        }

        override val size: BlockVector = structure.size.let { BlockVector(it.blockX, it.blockY, it.blockZ) }
        override val hasEntities: Boolean get() = structure.entityCount > 0
        override val remaining: Int get() = blocks.size - next
        override var placed = 0L
            private set

        private fun live(): Boolean = Bukkit.getWorld(world.uid) === world

        override fun place(budget: Int): Int? {
            if (!live()) return null
            val bottom = world.minHeight
            val top = world.maxHeight
            var went = 0
            while (went < budget && next < blocks.size) {
                val saved = blocks[next++]
                went++
                if (placement.integrity < 1.0 && random.nextFloat() > placement.integrity) continue
                val at = transform.block(saved.x, saved.y, saved.z)
                if (at.y < bottom || at.y >= top) continue
                val data = saved.blockData.clone()
                data.mirror(mirror)
                data.rotate(rotation)
                val state = saved.copy(Location(world, at.x.toDouble(), at.y.toDouble(), at.z.toDouble()))
                state.blockData = data
                state.update(true, false)
                placed++
            }
            return went
        }

        override fun entities(): Boolean {
            if (!live()) return false
            for (entity in structure.entities) {
                // What centities are drawn with would come back as strays nobody owns.
                if (this@PaperStructures.entities.tagOf(entity) != null) continue
                val from = entity.location
                val (x, y, z) = transform.point(from.x, from.y, from.z)
                val copy = entity.copy(Location(world, x, y, z, transform.yaw(from.yaw), from.pitch))
                if (copy is Hanging) {
                    val facing = BlockFace.valueOf(transform.facing(copy.facing.name.lowercase()).uppercase())
                    copy.setFacingDirection(facing, true)
                }
            }
            return true
        }
    }

    override fun save(file: Path, world: String, min: BlockVector, max: BlockVector, entities: Boolean): Boolean {
        val w = Bukkit.getWorld(world) ?: return false
        val structure = manager.createStructure()
        val size = BukkitBlockVector(max.x - min.x + 1, max.y - min.y + 1, max.z - min.z + 1)
        structure.fill(Location(w, min.x.toDouble(), min.y.toDouble(), min.z.toDouble()), size, entities)
        return try {
            Files.createDirectories(file.parent)
            manager.saveStructure(file.toFile(), structure)
            true
        } catch (_: IOException) {
            false
        }
    }

    override fun forget(file: Path) {
        cache.remove(file)
    }
}
