package dev.netherforge.plugin.integration

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.terrain.TerrainCompiler
import dev.netherforge.format.terrain.TerrainKind
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.TestProject
import dev.netherforge.plugin.testkit.StructureFiles
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The project's dimension types on a real server (W5.12), with a fresh main world (`PaperServer.prepare` starts from
 * none): `netherforge.json` names the example's `deep` (-128 to 319) and `ruby_hills` for the main world, so the
 * start-up datapack replaces the game's overworld type with it and the server makes its main world that deep, the
 * generator filling it from -128; a script makes another world of the type, sets blocks at both ends of its limits and
 * deletes it; and taking the dimension out of the manifest asks for a restart.
 */
class DimensionTypeScenario : Scenario("mainworld", "dimension_type") {
    override val server = PaperServer(levelSeed = SEED, mainWorldGenerator = true)

    private val file = "terrain/ruby_hills.json"

    override fun prepare(project: TestProject) {
        // The fixture's featureless biomes, so the ground compares exactly (as `MainWorldScenario`'s).
        project.write(
            file,
            project.read(file)
                .replace("\"vanilla\": true", "\"vanilla\": false")
                .replace("\"biome\": \"ruby_grove\"", "\"biome\": \"it_bare_warm\"")
                .replace("\"biome\": \"minecraft:plains\"", "\"biome\": \"it_bare\"")
                .replace("\"biome\": \"minecraft:snowy_plains\"", "\"biome\": \"it_bare_cold\"")
        )
        project.setWorld("world", """{ "terrain": "ruby_hills", "dimensionType": "deep" }""")
    }

    private fun step(command: String, line: String): List<String> {
        editor.run("it-dimension $command")
        return editor.line(line)
    }

    @Test
    @Order(1)
    fun `the main world has the dimension's limits, and the generator fills them from its bottom`() {
        val datapack = server.folder.resolve("plugins/NetherForge/datapack/data")
        val overworld = datapack.resolve("minecraft/dimension_type/overworld.json").toFile().readText()
        assertEquals(overworld, datapack.resolve("showcase/dimension_type/deep.json").toFile().readText())
        assertEquals(listOf("-128", "320"), step("heights", "heights"))
        val parsed = TerrainKind.parse(project.read(file), file) as CanonicalJson.Parsed.Ok
        val compiled = TerrainCompiler.compile(parsed.value)
        val structures = compiled.structures.associateWith { StructureFiles.template(project.file("structures/$it.nbt")) }
        val generator = compiled.withStructures(structures).bind(SEED, -128, 320)
        val buffer = generator.generate(0, 0)
        for (y in listOf(-128, -127, -100, -70, -65)) {
            val want = generator.terrain.palette[buffer[3, y, 5]].label.substringBefore('[')
            assertEquals(listOf(want), step("block 3 $y 5", "block"), "the main world's block at y $y")
        }
        assertEquals("minecraft:bedrock", step("block 3 -128 5", "block").single())
    }

    @Test
    @Order(2)
    fun `a script makes a world of the type, with blocks at both ends of its limits`() {
        assertEquals(listOf("-128", "320", "true", "true", "minecraft:gold_block"), step("mine", "mine"))
    }

    @Test
    @Order(3)
    fun `taking the main world's dimension out asks for a restart`() {
        project.write("netherforge.json", project.read("netherforge.json").replace(", \"dimensionType\": \"deep\"", ""))
        val result = editor.reload("netherforge.json")
        assertTrue(result.restart, "the overworld type is the start-up datapack's")
    }

    private companion object {
        const val SEED = 20261007L
    }
}
