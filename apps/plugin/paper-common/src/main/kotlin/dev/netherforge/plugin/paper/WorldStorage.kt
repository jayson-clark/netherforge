package dev.netherforge.plugin.paper

import org.bukkit.Bukkit
import org.bukkit.WorldCreator
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes

/**
 * Where a world's files are, which changed with Minecraft 26.1: each
 * version's [PaperVersion] says which of these its server keeps
 * ([DimensionStorage] or [WorldFolders]).
 *
 * A map in a project is always 26.x-shaped, whatever the target:
 * `level.dat` at the top and the world's own data in
 * `dimensions/minecraft/overworld/` (what the editor writes when it captures
 * one, see the tauri-backend skill's `fs/map.rs`).
 */
interface WorldStorage {
    /** Where world [name]'s own data (its regions) is, loaded or not. */
    fun dimension(name: String): Path

    /** The `level.dat` world [name] runs with. */
    fun level(name: String): Path

    /** Whether a world [name] is saved here, so the server can load it. */
    fun isSaved(name: String): Boolean

    /**
     * Copies map [map] so that `createWorld(WorldCreator(name))` loads it.
     * Off the main thread; throws when the files can't be written.
     */
    fun install(map: Path, name: String)

    /** Deletes world [name]'s files, given where its data was when it was loaded ([dimension]). Off the main thread. */
    fun delete(name: String, dimension: Path)

    companion object {
        const val LEVEL = "level.dat"

        /** Where a map keeps the world's own data. */
        const val MAP_DIMENSION = "dimensions/minecraft/overworld"

        /**
         * What the server writes for itself and a copy mustn't carry, by its path in a world's folder: its lock and
         * its identity (a copied uid.dat, or Paper's metadata.dat from 26.1, would make two worlds one).
         */
        private val SKIPPED = setOf("session.lock", "uid.dat", "data/paper/metadata.dat")

        /** Copies the tree [from] into [to], all but what's [SKIPPED]. */
        internal fun copyTree(from: Path, to: Path) {
            Files.walkFileTree(
                from,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val relative = from.relativize(file).joinToString("/")
                        if (relative !in SKIPPED && file.fileName.toString() !in SKIPPED) {
                            Files.copy(file, to.resolve(from.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES)
                        }
                        return FileVisitResult.CONTINUE
                    }
                }
            )
        }

        internal fun deleteTree(root: Path) {
            if (!Files.exists(root)) return
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

/**
 * Minecraft 26.1 on: a world is a dimension inside the main world's storage,
 * `world/dimensions/<namespace>/<path>/`, its key `minecraft:<name>` for a
 * world made with `WorldCreator(name)`, beside the one `level.dat` every world
 * shares. A map's overworld is the world's whole folder (its regions and
 * entities, and its `data/`: the game's saved data of the world, its game
 * rules, clock, weather, border, raids, Paper's spawn and persistent data, and
 * whatever else a plugin keeps there, NetherForge's centities' records
 * among them), so installing one is copying that folder in as the world's
 * dimension, all of it but what makes it this world ([WorldStorage.copyTree]):
 * `createWorld` loads it as it is and gives it a new UUID.
 *
 * Not through Paper's own import of a world folder in the old layout at
 * `<world container>/<name>/`, which `createWorld` runs when it finds one:
 * that moves only `region/`, `entities/` and `poi/`, copies the game's saved
 * data by a list of its own, and makes Paper's (the spawn) from the map's
 * `level.dat`, the main world's of the server it came from, so the rest of a
 * world's `data/` never arrived. A map from before 26.1 (no
 * `data/minecraft/world_gen_settings.dat` in its overworld: its saved data
 * is in the old places and its `level.dat`) still goes that way, as Paper's
 * import upgrades it.
 */
object DimensionStorage : WorldStorage {
    /** The main world's storage: its overworld is `<storage>/dimensions/minecraft/overworld`. */
    private val storage: Path get() = Bukkit.getWorlds().first().worldPath.parent.parent.parent

    private fun legacy(name: String): Path = Bukkit.getWorldContainer().toPath().resolve(name)

    override fun dimension(name: String): Path {
        val key = WorldCreator(name).key()
        return storage.resolve("dimensions").resolve(key.namespace).resolve(key.key)
    }

    override fun level(name: String): Path = storage.resolve(WorldStorage.LEVEL)

    override fun isSaved(name: String): Boolean =
        Files.isDirectory(dimension(name)) || Files.isRegularFile(legacy(name).resolve(WorldStorage.LEVEL))

    override fun install(map: Path, name: String) {
        val overworld = map.resolve(WorldStorage.MAP_DIMENSION)
        val current = Files.isRegularFile(overworld.resolve(GEN_SETTINGS))
        val target = if (current) dimension(name) else legacy(name)
        if (Files.exists(target)) throw IOException("$target is already there")
        try {
            WorldStorage.copyTree(if (current) overworld else map, target)
        } catch (e: IOException) {
            WorldStorage.deleteTree(target)
            throw e
        }
    }

    /** A 26.x world's own saved data that every world has: a map's overworld with it is in the current layout. */
    private const val GEN_SETTINGS = "data/minecraft/world_gen_settings.dat"

    override fun delete(name: String, dimension: Path) {
        WorldStorage.deleteTree(dimension)
        val legacy = legacy(name)
        if (Files.isRegularFile(legacy.resolve(WorldStorage.LEVEL))) WorldStorage.deleteTree(legacy)
    }
}

/**
 * Up to Minecraft 1.21.x: each world is a folder of its own in the world
 * container, `<name>/`, with its `level.dat` at the top beside its data. A
 * map is installed by putting its `level.dat` there and its overworld's
 * data beside it.
 */
object WorldFolders : WorldStorage {
    private fun folder(name: String): Path = Bukkit.getWorldContainer().toPath().resolve(name)

    override fun dimension(name: String): Path = folder(name)

    override fun level(name: String): Path = folder(name).resolve(WorldStorage.LEVEL)

    override fun isSaved(name: String): Boolean = Files.isRegularFile(level(name))

    override fun install(map: Path, name: String) {
        val target = folder(name)
        if (Files.exists(target)) throw IOException("$target is already there")
        try {
            WorldStorage.copyTree(map.resolve(WorldStorage.MAP_DIMENSION), target)
            // A map captured from a 1.21.x server carries the world folder's own level.dat in its overworld too.
            Files.copy(map.resolve(WorldStorage.LEVEL), target.resolve(WorldStorage.LEVEL), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            WorldStorage.deleteTree(target)
            throw e
        }
    }

    override fun delete(name: String, dimension: Path) = WorldStorage.deleteTree(dimension)
}
