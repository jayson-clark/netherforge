package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BlockPos
import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.bridge.LoadedWorld
import dev.netherforge.format.bridge.PlayerPositionParams
import dev.netherforge.format.bridge.SaveStructureParams
import dev.netherforge.format.bridge.SavedWorld
import dev.netherforge.plugin.integration.support.Adapter
import dev.netherforge.plugin.integration.support.Maps
import dev.netherforge.plugin.integration.support.Scenario
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the editor captures from its dev server over the bridge, one step per
 * `/it-capture <step>`: a structure from blocks in the main world, placed back
 * from the project; and a map from a void world a script made,
 * which a script then copies.
 */
class CaptureScenario : Scenario("capture") {
    private lateinit var lobby: SavedWorld

    @Test
    @Order(1)
    fun `use my position with nobody online says so`() {
        // The bot scenario covers a player.
        val (nobody, _) = editor.request(Bridge.playerPosition, PlayerPositionParams())
        assertEquals("nobody is online", nobody.error)
    }

    @Test
    @Order(2)
    fun `a structure comes back over the bridge, and the editor writes it into the project`() {
        editor.run("it-capture prepare")
        editor.logged("prepared", "it_lobby", "minecraft:emerald_block")
        val (_, worlds) = editor.request(Bridge.worlds, Unit)
        assertEquals(LoadedWorld("world", "normal", true), worlds!!.first())
        assertTrue(LoadedWorld("it_lobby", "normal", false) in worlds, "$worlds")

        val (saved, structure) = editor.request(
            Bridge.saveStructure,
            SaveStructureParams("world", BlockPos(4, -59, 4), BlockPos(3, -60, 3), entities = true)
        )
        assertTrue(saved.ok, saved.error)
        assertEquals(BlockPos(2, 2, 2), structure!!.size)
        val bytes = Base64.getDecoder().decode(structure.nbt)
        assertEquals(listOf(0x1f, 0x8b), listOf(bytes[0].toInt() and 0xff, bytes[1].toInt() and 0xff), "gzipped NBT")
        // The example has structures of its own (its terrain's tree): this one isn't among them until the editor writes it.
        assertFalse(Files.exists(project.file("structures/it_cube.nbt")), "the server never writes project files")
        Files.write(project.file("structures/it_cube.nbt"), bytes)
        // The editor's NBT reader is tested against a file captured this way (apps/editor/src/testing/).
        System.getenv("NETHERFORGE_IT_FIXTURES")?.let { Files.write(Path.of(it).resolve("captured.nbt"), bytes) }
        val result = editor.reload("structures/it_cube.nbt")
        assertEquals(listOf("structure:it_cube" to true), result.resources.map { it.label to it.ok })
        editor.run("it-capture place")
        editor.logged("placed", "true", "minecraft:diamond_block", "minecraft:gold_block")
        val (tooBig, _) = editor.request(Bridge.saveStructure, SaveStructureParams("world", BlockPos(0, 0, 0), BlockPos(48, 0, 0)))
        assertFalse(tooBig.ok)
    }

    @Test
    @Order(3)
    fun `a world is saved and flushed, its files named relative to the server's folder`() {
        val main = Maps.save(editor, "world")
        lobby = Maps.save(editor, "it_lobby")
        if (Adapter.worldsAreDimensions) {
            // One storage: every world a dimension beside the level.dat they share.
            assertEquals("world/level.dat" to "world/dimensions/minecraft/overworld", main.level to main.dimension)
            assertEquals(SavedWorld("world/level.dat", "world/dimensions/minecraft/it_lobby", false, lobby.spawn), lobby)
        } else {
            // A folder each, its level.dat inside.
            assertEquals("world/level.dat" to "world", main.level to main.dimension)
            assertEquals(SavedWorld("it_lobby/level.dat", "it_lobby", false, lobby.spawn), lobby)
        }
        assertTrue(main.main)
        val regions = server.folder.resolve(lobby.dimension).resolve("region").toFile().listFiles().orEmpty()
        assertTrue(regions.any { it.name.endsWith(".mca") && it.length() > 0 }, "flushed: ${regions.toList()}")
    }

    @Test
    @Order(4)
    fun `the editor's copy becomes a map, and a world that wasn't the main one comes back as the overworld of its copies`() {
        Maps.capture(server.folder, lobby, project.file("maps/it_captured"))
        assertFalse(Files.exists(project.file("maps/it_captured/dimensions/minecraft/overworld/data/paper")))
        val result = editor.reload("maps/it_captured/level.dat")
        assertEquals(listOf("map:it_captured" to true), result.resources.map { it.label to it.ok })
        editor.run("it-capture copy")
        editor.logged("copied", "it_from_map", "minecraft:emerald_block")
    }
}
