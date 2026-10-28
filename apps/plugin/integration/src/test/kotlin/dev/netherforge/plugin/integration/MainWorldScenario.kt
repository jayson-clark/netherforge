package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.Problems
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.terrain.TerrainBlock
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainGenerator
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.TestProject
import dev.netherforge.plugin.testkit.StructureFiles
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * A project generator making the server's main world (W5.4): `bukkit.yml` asks NetherForge for the main world's generator
 * (as the editor's dev server does) and `netherforge.json` names the example's `ruby_hills` for it, so the world the server
 * makes as it starts has the heights, layers and ores format's generator makes for the server's `level-seed`, its spawn is
 * the generator's, a saved file shapes the chunks generated after it, and naming another generator takes a restart, after
 * which new chunks are the new one's.
 */
class MainWorldScenario : Scenario("mainworld") {
    override val server = PaperServer(levelSeed = SEED, mainWorldGenerator = true)

    private val file = "terrain/ruby_hills.json"
    private val plateau = "terrain/it_plateau.json"

    /** The example's generator without the game's structures, and another for the main world to change to. */
    override fun prepare(project: TestProject) {
        // Its areas are the fixture's biomes without features, so the ground compares exactly (as in `TerrainScenario`).
        project.write(
            file,
            project.read(file)
                .replace("\"vanilla\": true", "\"vanilla\": false")
                .replace("\"biome\": \"ruby_grove\"", "\"biome\": \"it_bare_warm\"")
                .replace("\"biome\": \"minecraft:plains\"", "\"biome\": \"it_bare\"")
                .replace("\"biome\": \"minecraft:snowy_plains\"", "\"biome\": \"it_bare_cold\"")
        )
        project.write(plateau, project.read(file).replace("\"base\": 66", "\"base\": 120"))
        project.setWorld("world", """{ "terrain": "ruby_hills" }""")
    }

    /**
     * The generator of [path] as it is on disk now (or as [text] says), with the structures its decorations place (the
     * example's trees, which the main world gets as any world does), for the main world's seed and height.
     */
    private fun generator(path: String = file, text: String = project.read(path)): TerrainGenerator {
        val parsed = TerrainKind.parse(text, path) as CanonicalJson.Parsed.Ok
        val compiled = TerrainCompiler.compile(parsed.value)
        val structures = compiled.structures.associateWith { StructureFiles.template(project.file("structures/$it.nbt")) }
        return compiled.withStructures(structures).bind(SEED, -64, 320)
    }

    private fun step(command: String, line: String): List<String> {
        editor.run("it-mainworld $command")
        return editor.line(line)
    }

    private fun assertColumns(generator: TerrainGenerator, columns: List<Pair<Int, Int>>) {
        for ((x, z) in columns) {
            val buffer = generator.generate(x shr 4, z shr 4)
            val y = (buffer.maxY - 1 downTo buffer.minY).first { buffer[x and 15, it, z and 15] != 0 }
            val kind = generator.terrain.palette[buffer[x and 15, y, z and 15]].label
            assertEquals(listOf("$y", kind.substringBefore('[')), step("column $x $z", "column"), "the top of $x,$z")
        }
    }

    @Test
    @Order(1)
    fun `the main world is the generator's for the server's seed, its spawn and its ores too`() {
        val generator = generator()
        assertColumns(generator, listOf(0 to 0, 3 to 4, -40 to 25, 100 to -77, 17 to 16, -5 to -150))
        // The spawn is where the generator says: the first dry column near the origin, on its surface.
        val (x, z) = generator.dryColumnNear(0, 0)!!
        assertEquals(listOf("$x", "${generator.surfaceAt(x, z) + 1}", "$z"), step("spawn", "spawn"))
        // An ore of the project's own block, generated before the project ran, adopted as the block.
        val ruby = generator.terrain.palette.indexOf(TerrainBlock.Custom("ruby_ore"))
        val places = listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1).flatMap { (cx, cz) ->
            val buffer = generator.generate(cx, cz)
            (0 until 16).flatMap { lx ->
                (0 until 16).flatMap { lz ->
                    (buffer.minY until buffer.maxY).filter { buffer[lx, it, lz] == ruby }.map { Triple(cx * 16 + lx, it, cz * 16 + lz) }
                }
            }
        }
        assertTrue(places.isNotEmpty(), "the generator puts ruby ore around the origin")
        for ((bx, by, bz) in places.take(4)) {
            assertEquals(listOf("minecraft:note_block", "ruby_ore"), step("block $bx $by $bz", "block"), "ruby ore at $bx,$by,$bz")
        }
    }

    @Test
    @Order(2)
    fun `saving the file shapes the chunks generated after it, without a restart`() {
        val before = generator()
        project.write(file, project.read(file).replace("\"base\": 66", "\"base\": 90"))
        val result = editor.reload(file)
        assertTrue(result.resources.single().ok, "${result.resources}")
        assertFalse(result.restart, "the main world's generator is data the chunk threads read")
        val after = generator()
        assertNotEquals(before.surfaceAt(900, 900), after.surfaceAt(900, 900))
        assertColumns(before, listOf(3 to 4, -40 to 25))
        assertColumns(after, listOf(900 to 900, 905 to 912))
    }

    @Test
    @Order(3)
    fun `naming another generator for the main world says to restart, and the restart takes it`() {
        project.write(
            "netherforge.json",
            project.read("netherforge.json").replace("\"terrain\": \"ruby_hills\"", "\"terrain\": \"it_plateau\"")
        )
        val result = editor.reload("netherforge.json")
        assertTrue(result.restart, "which generator the main world has is the server's to take as it starts")
        val problem = result.resources.single().problems.single { it.code == "runtime.restart" }
        assertTrue("it_plateau" in problem.message, problem.message)
        // Until then, new chunks are still the generator it started with.
        assertColumns(generator(), listOf(-900 to -900))

        restart()
        assertEquals(emptyList(), (editor.next { it is Problems } as Problems).problems)
        assertColumns(generator(plateau), listOf(1500 to -1500, 1503 to -1489))
        // Chunks made before are as they were made.
        assertColumns(generator(text = project.read(file).replace("\"base\": 90", "\"base\": 66")), listOf(3 to 4, -40 to 25))
        assertColumns(generator(), listOf(900 to 900, -900 to -900))
    }

    private companion object {
        const val SEED = 20261006L
    }
}
