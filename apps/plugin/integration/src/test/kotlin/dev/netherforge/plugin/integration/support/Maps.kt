package dev.netherforge.plugin.integration.support

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.SaveWorldParams
import dev.netherforge.format.bridge.SavedWorld
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue

/** Maps, made the way the editor makes them. */
object Maps {
    /** Saves world [world] through the bridge (`save_world`), which names its files relative to the server's folder. */
    fun save(editor: Editor, world: String): SavedWorld {
        val (response, saved) = editor.request(Bridge.saveWorld, SaveWorldParams(world))
        assertTrue(response.ok, response.error)
        return saved!!
    }

    /**
     * What the editor's backend does with a saved world (`fs/map.rs`): its
     * level.dat at the top, its own folder as the map's overworld, without
     * what the server keeps for itself.
     */
    fun capture(server: Path, saved: SavedWorld, map: Path) {
        val level = server.resolve(saved.level)
        val dimension = server.resolve(saved.dimension)
        val overworld = map.resolve("dimensions/minecraft/overworld")
        Files.createDirectories(overworld)
        Files.copy(level, map.resolve("level.dat"))
        Files.walk(dimension).use { paths ->
            for (file in paths.filter { Files.isRegularFile(it) }.toList()) {
                val relative = dimension.relativize(file).joinToString("/")
                val skipped = file.fileName.toString() in setOf("session.lock", "uid.dat", "level.dat_old", "paper-world.yml")
                val players = relative.split('/').dropLast(1).any { it in setOf("players", "playerdata", "stats", "advancements") }
                if (skipped || players || relative.startsWith("data/paper/")) continue
                val target = overworld.resolve(relative)
                Files.createDirectories(target.parent)
                Files.copy(file, target)
            }
        }
    }
}
