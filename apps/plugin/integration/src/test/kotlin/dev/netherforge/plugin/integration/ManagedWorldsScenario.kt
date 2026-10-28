package dev.netherforge.plugin.integration

import dev.netherforge.plugin.integration.support.Adapter
import dev.netherforge.plugin.integration.support.Maps
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.eventually
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Worlds scripts manage, one step per `/it-worlds <step>`: blocks and a
 * structure saved in the main world (which the test turns into a map
 * and a project structure), copies of the map, a void world with a
 * border and the structure placed in it, and unloading (deleting the copy,
 * keeping the void world to load it back). Where the files go is the
 * version's own layout (the adapter's `WorldStorage`).
 */
class ManagedWorldsScenario : Scenario("worlds") {
    private val folder: Path get() = server.folder

    /** Where world [name]'s own data is on this version. */
    private fun dimension(name: String): Path =
        if (Adapter.worldsAreDimensions) folder.resolve("world/dimensions/minecraft/$name") else folder.resolve(name)

    @Test
    @Order(1)
    fun `blocks and a structure are saved in the main world, which a project may never unload`() {
        editor.run("it-worlds prepare")
        editor.logged("prepared", "true", "vec3(2, 2, 2)", "false", "false")
        assertTrue(Files.isRegularFile(folder.resolve("plugins/NetherForge/data/.nf/structures/it_cube.nbt")), "saved in the data folder")
    }

    @Test
    @Order(2)
    fun `the main world becomes a map and the saved structure a project one`() {
        Maps.capture(folder, Maps.save(editor, "world"), project.file("maps/it_arena"))
        val structure = project.file("structures/it_house.nbt")
        Files.createDirectories(structure.parent)
        Files.copy(folder.resolve("plugins/NetherForge/data/.nf/structures/it_cube.nbt"), structure)
        val result = editor.reload("maps/it_arena/level.dat", "structures/it_house.nbt")
        assertEquals(
            listOf("map:it_arena" to true, "structure:it_house" to true),
            result.resources.map { it.label to it.ok }.sortedBy { it.first }
        )
    }

    @Test
    @Order(3)
    fun `a copy of the map carries its blocks, waited for in a task or called back`() {
        editor.run("it-worlds copy")
        editor.logged("copied", "it_copy", "true", "minecraft:diamond_block", "minecraft:gold_block")
        editor.logged("copied by callback", "it_copy2")
    }

    @Test
    @Order(4)
    fun `a void world has the structure placed in it, turned too, and a border`() {
        editor.run("it-worlds void")
        editor.logged("void", "minecraft:air", "true", "true", "minecraft:diamond_block", "minecraft:gold_block", "minecraft:air")
        editor.logged("border", "30.0", "vec3(5, 0, 5)", "1.0", "2.0", "4", "40", "true", "false")
        editor.logged("shrinking", "true")
    }

    @Test
    @Order(5)
    fun `unloading deletes the copy and saves the void world, which loads back as it was`() {
        editor.run("it-worlds unload")
        editor.logged("unloaded", "true", "false", "false", "true", "false", "true")
        editor.logged("loaded back", "it_void", "minecraft:diamond_block", "vec3(5, 0, 5)")
    }

    @Test
    @Order(6)
    fun `on disk, the deleted copy's files go off the main thread and the others stay`() {
        eventually("the deleted copy's files gone", poll = { Files.exists(dimension("it_copy")) }) { exists -> !exists }
        assertTrue(Files.isDirectory(dimension("it_void")))
        assertTrue(Files.isDirectory(dimension("it_copy2")))
        if (Adapter.worldsAreDimensions) {
            assertFalse(
                Files.exists(folder.resolve("it_copy2")),
                "Paper imported the copied folder and removed it"
            )
        }
        val owned = server.stored("SELECT world FROM worlds").map { it["world"] }
        assertTrue("it_void" in owned && "it_copy2" in owned && "it_copy" !in owned, "$owned")
    }
}
