package dev.netherforge.plugin.paper

import dev.netherforge.format.datapack.GameDatapack
import dev.netherforge.format.ref.ResourceKey
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.net.URI
import java.nio.file.FileSystemNotFoundException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

/**
 * The game's own data pack, as the server's jar has it: every biome of the game (`data/minecraft/worldgen/biome/`)
 * and the overworld's dimension type, which a sky's twin biomes are made from ([GameDatapack]). Read once, from the
 * class path the server's own classes come from, so it's that very version's, before any registry exists.
 */
object PaperGameDatapack {
    private const val BIOMES = "data/minecraft/worldgen/biome"
    private const val OVERWORLD = "data/minecraft/dimension_type/overworld.json"

    val read: GameDatapack by lazy { load() ?: GameDatapack.NONE }

    private fun load(): GameDatapack? {
        val loader = PaperGameDatapack::class.java.classLoader
        val overworld = loader.getResourceAsStream(OVERWORLD)?.use {
            Json.parseToJsonElement(it.readBytes().decodeToString()) as? JsonObject
        }
        val folder = loader.getResource(BIOMES)?.toURI() ?: return null
        val biomes = sortedMapOf<String, JsonObject>()
        withPath(folder) { root ->
            Files.list(root).use { files ->
                for (file in files) {
                    val name = file.fileName.toString().removeSuffix("/")
                    if (!name.endsWith(".json")) continue
                    val json = Json.parseToJsonElement(Files.readString(file)) as? JsonObject ?: continue
                    biomes[name.removeSuffix(".json")] = json
                }
            }
        }
        return GameDatapack(biomes.entries.associate { (path, json) -> ResourceKey("minecraft", path) to json }, overworld)
    }

    /** [uri] as a path, inside its jar when it's in one (whose file system is opened for the call if nobody has it open). */
    private fun withPath(uri: URI, use: (Path) -> Unit) {
        if (uri.scheme != "jar") return use(Path.of(uri))
        val existing = try {
            FileSystems.getFileSystem(uri)
        } catch (_: FileSystemNotFoundException) {
            null
        }
        if (existing != null) return use(existing.provider().getPath(uri))
        FileSystems.newFileSystem(uri, emptyMap<String, Any>()).use { system -> use(system.provider().getPath(uri)) }
    }
}
