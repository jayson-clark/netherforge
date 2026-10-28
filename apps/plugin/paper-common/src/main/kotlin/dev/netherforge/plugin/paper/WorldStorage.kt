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

        /** What the server writes for itself and a copy mustn't carry: a copied uid.dat would make two worlds one. */
        private val SKIPPED = setOf("session.lock", "uid.dat")

        internal fun copyTree(from: Path, to: Path) {
            Files.walkFileTree(
                from,
                object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        Files.createDirectories(to.resolve(from.relativize(dir).toString()))
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                        if (file.fileName.toString() !in SKIPPED) {
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
 * shares. A world folder in the old layout at `<world container>/<name>/` (a
 * `level.dat` at its top: a singleplayer save or a map) is imported
 * into that storage by Paper's own legacy-world migration when `createWorld`
 * loads it, and the folder is removed; so installing a map is copying it
 * there as it is.
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
        val target = legacy(name)
        if (Files.exists(target)) throw IOException("$target is already there")
        try {
            WorldStorage.copyTree(map, target)
        } catch (e: IOException) {
            WorldStorage.deleteTree(target)
            throw e
        }
    }

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
