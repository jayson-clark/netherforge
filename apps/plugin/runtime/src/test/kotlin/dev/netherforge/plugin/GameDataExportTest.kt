package dev.netherforge.plugin

import dev.netherforge.format.bridge.Bridge
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.plugin.testkit.FakePlatform
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** The game's data written to a file at start, without an editor: `config.yml`'s key, or the property that stops after. */
class GameDataExportTest {
    private val server = createTempDirectory("game-data-export")

    @Test
    fun `the property exports and stops, over the setting, which only exports`() {
        assertNull(GameDataExport.read(emptyMap(), server) { null })
        assertNull(GameDataExport.read(mapOf("export-game-data" to " "), server) { null })
        assertEquals(
            GameDataExport(server.resolve("data/game.json"), thenStop = false),
            GameDataExport.read(mapOf("export-game-data" to "data/game.json"), server) { null }
        )
        assertEquals(
            GameDataExport(server.resolve("/tmp/out.json"), thenStop = true),
            GameDataExport.read(mapOf("export-game-data" to "data/game.json"), server) {
                if (it == GameDataExport.PROPERTY) "/tmp/out.json" else null
            }
        )
    }

    @Test
    fun `it writes the bundle the editor's cache holds, whole`() {
        val platform = FakePlatform()
        val file = server.resolve("out/game-data.json")
        GameDataExport(file, thenStop = false).write(platform)
        val read = Bridge.json.decodeFromString(GameDataBundle.serializer(), file.readText())
        assertEquals(platform.game, read)
        assertEquals(GameDataBundle.SCHEMA, read.schema)
        assertFalse(file.resolveSibling("game-data.json.part").exists(), "no half-written file is left")
        // Written again over the last.
        GameDataExport(file, thenStop = false).write(platform)
        assertEquals(1, Files.list(file.parent).count())
    }
}
