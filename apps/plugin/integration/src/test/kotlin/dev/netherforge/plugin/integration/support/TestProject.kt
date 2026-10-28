package dev.netherforge.plugin.integration.support

import dev.netherforge.format.game.FeatureTable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.io.path.writeText

/**
 * A copy of an example (`examples/showcase`, by default, and the
 * [dependencies] it names by path, beside it: `examples/library`) in a temp
 * folder, with fixtures (folders of `src/test/fixtures`, laid over it file
 * by file) added. Scenarios change it as the editor would, then reload over
 * the bridge.
 */
class TestProject(example: String, dependencies: List<String>, fixtures: List<String>) : AutoCloseable {
    /** `examples/showcase` with `examples/library`, and [fixtures]. */
    constructor(vararg fixtures: String) : this("showcase", listOf("library"), fixtures.toList())

    private val work: Path = createTempDirectory("netherforge-it")
    val root: Path = work.resolve(example)

    init {
        copyExample(Adapter.example.resolveSibling(example), root)
        // Beside it, where its netherforge.json finds them.
        val copies =
            listOf(root) + dependencies.map { name -> work.resolve(name).also { copyExample(Adapter.example.resolveSibling(name), it) } }
        // The examples target the newest version; these copies target the adapter's, as projects for it would.
        for (manifest in copies.map { it.resolve("netherforge.json") }) {
            manifest.writeText(manifest.readText().replace(Regex("\"minecraft\": \"[^\"]*\""), "\"minecraft\": \"${Adapter.minecraft}\""))
        }
        // What a version doesn't have is left out of the copy for it, as a project written for that version would:
        // a sky held through the night (`darkens: false`) needs world clocks, which arrived in 26.1.
        if (!FeatureTable.CURRENT.has(Adapter.minecraft, "sky-night")) copies.forEach(::withoutSkyNight)
        fixtures.forEach(::add)
        // The showcase's main world is its expo's plaza, which the server generates only when bukkit.yml routes the main
        // world to NetherForge (as the editor's dev server does). A scenario that does names a terrain for it itself
        // ([setWorld]); the rest run on the server's own world, as it comes, without the restart warning a terrain no one
        // routes is.
        if (example == "showcase") setWorld("world", "{}")
    }

    /** Makes [json] world [name]'s entry in `netherforge.json`'s `worlds`, in place of the one it has, if any. */
    fun setWorld(name: String, json: String) {
        val text = read("netherforge.json")
        val worlds = text.indexOf("\"worlds\": {")
        check(worlds >= 0) { "netherforge.json names no worlds" }
        val key = text.indexOf("\"$name\": {", worlds)
        if (key < 0) {
            val at = worlds + "\"worlds\": {".length
            write("netherforge.json", text.substring(0, at) + "\n    \"$name\": $json," + text.substring(at))
            return
        }
        // The entry's object runs from its opening brace to the brace that closes it.
        val open = text.indexOf('{', key)
        var depth = 0
        var end = open
        while (true) {
            when (text[end]) {
                '{' -> depth++
                '}' -> if (--depth == 0) break
            }
            end++
        }
        write("netherforge.json", text.substring(0, open) + json + text.substring(end + 1))
    }

    companion object {
        /** The Minecraft version `examples/<example>` targets, as its manifest says. */
        fun target(example: String): String {
            val manifest = Adapter.example.resolveSibling(example).resolve("netherforge.json").readText()
            return Regex("\"minecraft\": \"([^\"]*)\"").find(manifest)!!.groupValues[1]
        }
    }

    /**
     * Copies an example as the repo holds it: without what its `.gitignore`
     * keeps out, which is what the editor generates on this computer (the
     * default font's advances from the client imported here, for the version
     * it was imported for; agent files with this editor's token). A run then
     * starts from the same files on any machine, and a copy retargeted to
     * another version carries nothing generated for the first.
     */
    private fun copyExample(example: Path, to: Path) {
        example.toFile().copyRecursively(to.toFile())
        val ignored = example.resolve(".gitignore").takeIf(Files::exists)?.readText()?.lines().orEmpty()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
        for (pattern in ignored) {
            check(pattern.none { it in "*?[!" }) { "$example/.gitignore: \"$pattern\" isn't a plain path, which this copy can't follow" }
            to.resolve(pattern.trim('/')).toFile().deleteRecursively()
        }
    }

    /** Takes `darkens` out of every sky of the project at [root]. */
    private fun withoutSkyNight(root: Path) {
        val skies = root.resolve("skies").toFile().listFiles { file -> file.extension == "json" }.orEmpty()
        for (sky in skies) {
            val json = Json.parseToJsonElement(sky.readText()).jsonObject
            if ("darkens" in json) sky.writeText(Json.encodeToString(JsonObject.serializer(), JsonObject(json - "darkens")))
        }
    }

    /** Lays fixture [name] over the project. */
    fun add(name: String) {
        val fixture = Adapter.fixtures.resolve(name)
        check(Files.isDirectory(fixture)) { "no fixture $fixture" }
        fixture.toFile().copyRecursively(root.toFile(), overwrite = true)
    }

    fun file(path: String): Path = root.resolve(path)

    fun read(path: String): String = file(path).readText()

    fun write(path: String, text: String) {
        Files.createDirectories(file(path).parent)
        file(path).writeText(text)
    }

    /** A fixture file's text: `edits/tower.lua`. */
    fun fixture(path: String): String = Adapter.fixtures.resolve(path).readText()

    override fun close() {
        work.toFile().deleteRecursively()
    }
}
