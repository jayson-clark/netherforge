package dev.netherforge.plugin.paper

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.plugin.platform.Platform
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writing the game's data (what the dev server exports to the editor over the bridge) to a file as the server starts,
 * for machines with no editor: `netherforge game-data` and CI. Either the owner's `export-game-data: <file>` in
 * `config.yml` (written at every start, the server runs on), or `-Dnetherforge.export-game-data=<file>` (written once,
 * then the server stops: what `netherforge game-data` starts it with). Neither needs a project.
 *
 * The file is the editor's cache's shape (`<data>/minecraft/<version>/server/game-data.json`), so `netherforge check`
 * and `netherforge test` read it as they read the editor's.
 */
data class GameDataExport(
    /** Where it's written. */
    val file: Path,
    /** Whether the server stops once it's written. */
    val thenStop: Boolean
) {
    /** Writes [platform]'s game data to [file], whole or not at all. Called on the main thread, once the server has loaded. */
    fun write(platform: Platform): GameDataBundle {
        val bundle = platform.exportGameData()
        val text = Bridge.json.encodeToString(GameDataBundle.serializer(), bundle)
        val target = file.toAbsolutePath()
        target.parent?.let(Files::createDirectories)
        val part = target.resolveSibling("${target.fileName}.part")
        Files.writeString(part, text)
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        return bundle
    }

    companion object {
        /** `-Dnetherforge.export-game-data=<file>`: export, then stop. */
        const val PROPERTY = "netherforge.export-game-data"

        /** `config.yml`'s key: export at every start. */
        const val SETTING = "export-game-data"

        /**
         * The export this start asks for, or null: the property's (which stops the server after) over `config.yml`'s.
         * A relative path is against [serverFolder].
         */
        fun read(settings: Map<String, Any?>, serverFolder: Path, properties: (String) -> String? = System::getProperty): GameDataExport? {
            val property = properties(PROPERTY)?.trim()
            if (!property.isNullOrEmpty()) return GameDataExport(serverFolder.resolve(property), thenStop = true)
            val setting = (settings[SETTING] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return GameDataExport(serverFolder.resolve(setting), thenStop = false)
        }
    }
}
