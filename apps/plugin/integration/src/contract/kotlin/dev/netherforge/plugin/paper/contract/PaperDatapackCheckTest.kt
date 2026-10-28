package dev.netherforge.plugin.paper.contract

import dev.netherforge.format.datapack.DatapackKind
import dev.netherforge.format.datapack.DatapackLayout
import dev.netherforge.format.datapack.DatapackValidator
import dev.netherforge.format.datapack.WorldgenReferences
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.FormatVersion
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.plugin.contract.PlatformContract
import org.bukkit.Bukkit
import org.junit.jupiter.api.Test
import java.io.File
import java.net.URI
import java.util.jar.JarFile
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What format checks of a project's datapacks ([DatapackValidator], [WorldgenReferences]) against the game's own worldgen,
 * on every supported server: the game's own files (its jar's `data/minecraft/worldgen/` and the worldgen tags), passed
 * through as a project's datapack replacing them, are clean with the server's own registries, and every reference the
 * rules find in them is an entry (or tag) of the registry the rule says. A rule that's wrong for a version (a field that
 * moved, a registry renamed) fails here, on that version. On Paper alone: only a real server has the game's files.
 */
class PaperDatapackCheckTest : PlatformContract() {
    override fun connect() = PaperContractServer.current

    /** The game's own worldgen files and tags, by path in a datapack (`data/minecraft/worldgen/biome/plains.json`). */
    private val vanilla: Map<String, String> by lazy {
        val url = assertNotNull(Bukkit.getServer().javaClass.classLoader.getResource(PROBE), "the server's jar has $PROBE")
        assertEquals("jar", url.protocol, "$url")
        val jar = File(URI(url.path.substringBefore("!/")))
        JarFile(jar).use { file ->
            file.entries().asSequence()
                .filter { !it.isDirectory && it.name.endsWith(".json") }
                .filter { it.name.startsWith(WORLDGEN) || it.name.startsWith(TAGS) }
                .associate { it.name to file.getInputStream(it).use { input -> input.readBytes().decodeToString() } }
        }
    }

    @Test
    fun `the game's own worldgen, passed through as a datapack, is clean`() {
        val format = assertNotNull(platform.datapacks.format)
        val game = platform.game
        assertTrue(vanilla.size > 1000, "the game's worldgen files: ${vanilla.size}")
        val manifest =
            """{ "formatVersion": ${FormatVersion.CURRENT}, "name": "Contract", "namespace": "contract", "version": "1.0.0", "minecraft": "${game.minecraftVersion}" }"""
        val files = mapOf(
            "netherforge.json" to manifest,
            DatapackKind.fileOf("vanilla", "pack.mcmeta") to
                """{ "pack": { "min_format": [${format[0]}, ${format[1]}], "max_format": [${format[0]}, ${format[1]}] } }"""
        ) + vanilla.mapKeys { (path, _) -> DatapackKind.fileOf("vanilla", path) }
        val snapshot = Projects.load(MapProjectSource(files), game)
        assertEquals(emptyList(), snapshot.problems.map { "${it.code} ${it.file} ${it.path ?: ""}: ${it.message}" })
    }

    @Test
    fun `every reference the rules find in the game's files is an entry of the registry they say`() {
        val game = platform.game
        val found = mutableMapOf<String, Int>()
        val wrong = mutableListOf<String>()
        for ((file, text) in vanilla.entries.sortedBy { it.key }) {
            val path = (DatapackLayout.place(file, emptySet()) as? DatapackLayout.Placed.Ok)?.path ?: continue
            val json = (
                CanonicalJson.parse(
                    kotlinx.serialization.json.JsonElement.serializer(),
                    text,
                    file
                ) as CanonicalJson.Parsed.Ok
                ).value
            val references =
                if (path.tag) {
                    WorldgenReferences.tagValues(
                        path.registry,
                        json
                    ) ?: error("$file isn't a tag")
                } else {
                    WorldgenReferences.find(path.registry, json)
                }
            for (reference in references) {
                val registry = reference.targets.firstOrNull { game.registry(DatapackValidator.registryKey(it)) != null }
                if (registry == null) {
                    wrong += "$file ${reference.path}: none of ${reference.targets} is a registry of this server"
                    continue
                }
                found[registry] = (found[registry] ?: 0) + 1
                val tag = reference.text.startsWith("#")
                val id = reference.text.removePrefix("#").let { if (':' in it) it else "minecraft:$it" }
                val key = DatapackValidator.registryKey(registry)
                val has = if (tag) game.tag(key, id) != null else game.registry(key)?.contains(id) == true
                if (!has) wrong += "$file ${reference.path}: \"${reference.text}\" isn't in $registry"
            }
        }
        assertEquals(emptyList(), wrong)
        // The rules find what they're for: every kind of reference the game's own files make.
        for (registry in listOf(
            "worldgen/biome",
            "worldgen/placed_feature",
            "worldgen/noise",
            "worldgen/structure",
            "worldgen/template_pool"
        )) {
            assertTrue((found[registry] ?: 0) > 0, "references to $registry: $found")
        }
    }

    private companion object {
        const val WORLDGEN = "data/minecraft/worldgen/"
        const val TAGS = "data/minecraft/tags/worldgen/"
        const val PROBE = "data/minecraft/worldgen/noise_settings/overworld.json"
    }
}
