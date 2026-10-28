package dev.netherforge.plugin.integration

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.TestProject
import dev.netherforge.plugin.integration.support.eventually
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The project's own biomes on a real server (W5.7): the example's `biomes/ruby_grove.json` is in the start-up datapack
 * in the game's format, a world `netherforge.json` makes with `terrain/ruby_hills.json` has it where the generator's
 * grove area is, the game decorates it with exactly its features (cherry trees and petals, and the boulders its
 * datapack's placed feature makes, nothing of the plains' around it), `/locate biome showcase:ruby_grove` finds it, and changing it asks for a restart.
 */
class BiomeScenario : Scenario("grove") {
    private val seed = 20260714L
    private val file = "terrain/ruby_hills.json"
    private val world = "it_grove"

    /** The example's generator and biomes as they are, in a world of their own; the game's structures off, so only features decorate. */
    override fun prepare(project: TestProject) {
        project.write(file, project.read(file).replace("\"vanilla\": true", "\"vanilla\": false"))
        project.write(
            "netherforge.json",
            project.read(
                "netherforge.json"
            ).replace("\"worlds\": {", "\"worlds\": {\n    \"$world\": { \"terrain\": \"ruby_hills\", \"seed\": $seed },")
        )
    }

    private fun step(command: String, line: String): List<String> {
        editor.run("it-grove $command")
        return editor.line(line)
    }

    /**
     * What grows in a chunk, by block kind. The server's `forceload` generates the chunk first (commands run in order on
     * the main thread), outside the script's time limit: a decorated chunk and its neighbours take a while on a busy machine.
     */
    private fun plants(cx: Int, cz: Int): Map<String, Int> {
        editor.run("execute in minecraft:$world run forceload add ${cx * 16} ${cz * 16}")
        val counts = step("plants $cx $cz", "plants").single().split(',').filter { it.isNotEmpty() }
            .associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        editor.run("execute in minecraft:$world run forceload remove ${cx * 16} ${cz * 16}")
        return counts
    }

    /**
     * The first chunks (nearest the origin first) whose every column, and every column within [MARGIN] of it, the
     * generator puts in the area of [biome], as the file names it: the example's own trees (a decoration of the plains)
     * rooted next door can't reach into it.
     */
    private fun chunksOf(biome: String, count: Int): List<Pair<Int, Int>> {
        val parsed = TerrainKind.parse(project.read(file), file) as CanonicalJson.Parsed.Ok
        val generator = TerrainCompiler.compile(parsed.value).bind(seed, -64, 320)
        val found = mutableListOf<Pair<Int, Int>>()
        for (radius in 0..48) {
            for (cx in -radius..radius) {
                for (cz in -radius..radius) {
                    if (maxOf(kotlin.math.abs(cx), kotlin.math.abs(cz)) != radius) continue
                    val all = (-MARGIN until 16 + MARGIN step 3).all { x ->
                        (-MARGIN until 16 + MARGIN step 3).all { z ->
                            generator.biomeAt(cx * 16 + x, cz * 16 + z) ==
                                biome
                        }
                    }
                    if (all) found += cx to cz
                    if (found.size == count) return found
                }
            }
        }
        fail("the example's generator has no $count chunks all of $biome near the origin for seed $seed")
    }

    @Test
    @Order(1)
    fun `the start-up datapack has the biome in the game's format`() {
        val biome = server.folder.resolve("plugins/NetherForge/datapack/data/showcase/worldgen/biome/ruby_grove.json").readText()
        val json = CanonicalJson.json.parseToJsonElement(biome).toString()
        assertTrue("\"minecraft:trees_cherry\"" in json, json)
        assertTrue("\"carvers\":[]" in json, json)
        assertEquals(listOf("true"), step("made", "made"))
    }

    @Test
    @Order(2)
    fun `the grove is decorated with its own features, and the plains with the game's`() {
        val groves = chunksOf("ruby_grove", 3).map { (cx, cz) -> plants(cx, cz) }
        val grown = groves.flatMap { it.keys }.toSet()
        assertTrue(
            grown.any {
                it.startsWith("minecraft:cherry_")
            } ||
                "minecraft:pink_petals" in grown,
            "cherry trees or petals grow in the grove: $groves"
        )
        // A placed feature of the project's datapack (datapacks/ruby_boulders) is one of them.
        assertTrue(groves.any { "minecraft:red_terracotta" in it }, "the datapack's boulders decorate the grove: $groves")
        // Exactly its features: the plains' oaks and flowers aren't among them.
        for (kind in listOf("minecraft:oak_log", "minecraft:oak_leaves", "minecraft:dandelion", "minecraft:poppy")) {
            assertTrue(kind !in grown, "$kind is the plains', not the grove's: $groves")
        }
        val plains = chunksOf("minecraft:plains", 3).map { (cx, cz) -> plants(cx, cz) }
        assertTrue(plains.any { "minecraft:short_grass" in it }, "the game's plains bring their own decorations: $plains")
    }

    @Test
    @Order(3)
    fun `locate biome finds it`() {
        editor.run("execute in minecraft:$world run locate biome showcase:ruby_grove")
        val log = server.folder.resolve("${javaClass.simpleName}.log")
        val missing = "Could not find a biome of type \"showcase:ruby_grove\""
        val text = eventually("locate's answer about showcase:ruby_grove in $log", poll = { Files.readString(log) }) {
            missing in it || "The nearest showcase:ruby_grove is at" in it
        }
        if (missing in text) fail("locate didn't find the grove")
    }

    @Test
    @Order(4)
    fun `changing the biome asks for a restart`() {
        val path = "biomes/ruby_grove.json"
        val before = project.read(path)
        assertTrue(!editor.reload(path).restart, "saved as it is, it's what the server started with")
        project.write(path, before.replace("\"#f2a7c3\"", "\"#a7c3f2\""))
        val result = editor.reload(path)
        assertEquals(listOf(true), result.resources.map { it.ok })
        assertTrue(result.restart, "the server learns biomes only as it starts")
        restart()
        assertNotNull(
            server.folder.resolve("plugins/NetherForge/datapack/data/showcase/worldgen/biome/ruby_grove.json").readText().takeIf {
                "#a7c3f2" in
                    it
            }
        )
        assertTrue(!editor.reload(path).restart)
    }

    private companion object {
        /** Blocks around a chunk that are in its biome too: wider than the example's tree. */
        const val MARGIN = 8
    }
}
