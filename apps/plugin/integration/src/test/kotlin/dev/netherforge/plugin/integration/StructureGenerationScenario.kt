package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BlockPos
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.SaveStructureParams
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.eventually
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.Base64
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A project structure that generates by itself, on a real server: captured
 * from the dev server with a marker for a centity in it, given a
 * `structures/<id>.json`, brought in by the restart the datapack asks for
 * (the server refuses to start on a registry it can't load), generated in
 * a new world, and the centity spawned from its marker once and only once
 * (the marker is taken, so a restart and a reload leave the count where it is).
 */
class StructureGenerationScenario : Scenario("structures") {
    private fun datapack(path: String) = server.folder.resolve("plugins/NetherForge/datapack").resolve(path).readText()

    /**
     * How many it_guard centities there are, counted by the fixture a few server ticks after whatever just loaded
     * chunks (a marker becomes its centity on the tick after its chunk loads): ticks, not a sleep, so a loaded
     * machine counts as late as its ticks come.
     */
    private fun guards(): Int {
        editor.run("it-structures count")
        return editor.line("guards").single().toInt()
    }

    @Test
    @Order(1)
    fun `a ruin with a marker is captured and told where to generate`() {
        editor.run("it-structures prepare")
        editor.logged("prepared", "true")
        val (saved, structure) = editor.request(
            Bridge.saveStructure,
            SaveStructureParams("world", BlockPos(0, -60, 0), BlockPos(2, -59, 2), entities = true)
        )
        assertTrue(saved.ok, saved.error)
        assertEquals(BlockPos(3, 2, 3), structure!!.size)
        Files.createDirectories(project.file("structures"))
        Files.write(project.file("structures/it_ruins.nbt"), Base64.getDecoder().decode(structure.nbt))
        // Every second chunk, anywhere in the overworld: found at once.
        project.write(
            "structures/it_ruins.json",
            """{ "biomes": ["#minecraft:is_overworld"], "spacing": 2, "separation": 0, "terrainAdaptation": "beard_thin" }""" + "\n"
        )
        // The structure is only placed by scripts until the server restarts with the datapack that makes it generate.
        val result = editor.reload("structures/it_ruins.nbt", "structures/it_ruins.json")
        assertTrue(result.restart, "a structure that generates changes the start-up datapack")
        assertTrue(result.resources.all { it.ok }, "${result.resources}")
    }

    @Test
    @Order(2)
    fun `the server starts with it in its registries`() {
        restart()
        val structure = datapack("data/showcase/worldgen/structure/it_ruins.json")
        assertTrue("\"type\": \"minecraft:jigsaw\"" in structure, structure)
        assertTrue(Files.exists(server.folder.resolve("plugins/NetherForge/datapack/data/showcase/structure/it_ruins.nbt")))
        assertTrue("showcase:it_ruins" in datapack("data/showcase/worldgen/structure_set/it_ruins.json"))
        assertFalse(editor.reload("structures/it_ruins.json").restart, "the datapack is what the server started with")
    }

    @Test
    @Order(3)
    fun `the world generates it and its marker becomes the centity, once`() {
        editor.run("it-structures generate")
        editor.logged("generated", "it_gen")
        // The server found it (a datapack structure it can't use never generates one).
        val first = eventually("a guard spawned from a generated ruin", poll = ::guards) { it > 0 }
        assertEquals(first, guards(), "counting changes nothing")
        // A structure with its markers doesn't spawn from the same marker again when its chunks load once more.
        editor.run("it-structures generate")
        editor.logged("generated", "it_gen")
        assertEquals(first, guards())
    }

    @Test
    @Order(4)
    fun `a restart leaves the count where it was`() {
        val before = guards()
        restart()
        editor.run("it-structures generate")
        editor.logged("generated", "it_gen")
        assertEquals(before, guards())
    }
}
