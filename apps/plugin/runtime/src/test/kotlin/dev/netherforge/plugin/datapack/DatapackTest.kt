package dev.netherforge.plugin.datapack
import dev.netherforge.format.ProblemCodes
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.project.ProjectFiles
import dev.netherforge.plugin.testkit.FakePlatform
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Datapacks passed through (`datapacks/<id>/`) on the fake server: in the start-up datapack for its format, a change
 * asking for a restart, and a refusal on record (the server refused them as it last started) making it start without
 * them, with the game's report as problems where it points, until they change.
 */
class DatapackTest {
    private val pack = "datapacks/rocks/pack.mcmeta"
    private val placed = "datapacks/rocks/data/test/worldgen/placed_feature/rocks.json"
    private val biome = "biomes/stony.json"

    private fun files() = mapOf(
        pack to """{ "pack": { "min_format": 94, "max_format": 121 } }""",
        placed to """{ "feature": "test:rock", "placement": [] }""",
        "datapacks/rocks/data/test/worldgen/feature/rock.json" to """{ "type": "minecraft:block_blob" }""",
        biome to """{ "features": { "local_modifications": ["rocks"] } }"""
    )

    /** The game's report of the placed feature it couldn't read, as 26.3 logs it. */
    private val report = """
        > Errors in registry minecraft:worldgen/placed_feature:
        >> Errors in element test:rocks:
        java.lang.IllegalStateException: Failed to parse test:rocks from pack file/netherforge: No key placement in MapLike[{"feature":"test:rock"}]
        	at net.minecraft.resources.RegistryDataLoader.loadElementFromResource(RegistryDataLoader.java:301)
        > Errors in registry minecraft:worldgen/noise:
        >> Errors in element minecraft:gone:
        java.lang.IllegalStateException: Missing referenced element
    """.trimIndent()

    @Test
    fun `a datapack is in the start-up datapack, and a change to it asks for a restart`() {
        TestServer(files()).use { server ->
            val started = server.platform.datapacks.started
            assertTrue("data/test/worldgen/placed_feature/rocks.json" in started, "${started.keys}")
            // The biome naming its feature is there with it.
            assertTrue("data/test/worldgen/biome/stony.json" in started)
            assertEquals(emptyList(), server.runtime.currentProblems().filter { it.code == ProblemCodes.RUNTIME_DATAPACK.code })

            assertFalse(server.reload(placed).restart)
            server.write(placed, """{ "feature": "test:rock", "placement": [{ "type": "minecraft:in_square" }] }""")
            assertTrue(server.reload(placed).restart)
        }
    }

    @Test
    fun `a refused datapack is left out until it changes, its report as problems`() {
        val platform = FakePlatform()
        TestServer(files(), platform = platform, start = false).use { server ->
            // What the server refused: exactly these files, as the adapter keeps it.
            val source = ProjectFiles(server.project, true)
            val whole = StartupDatapackFiles.forStart(
                source.load(null).snapshot,
                platform.datapacks.format!!,
                platform.datapacks::textJson,
                platform.datapacks.mainWorld,
                source::readBytes,
                null
            )
            assertTrue(whole.canLeaveOut)
            platform.datapacks.refusal = DatapackRefusal(whole.hash, report)
            server.start()

            val started = platform.datapacks.started
            assertNotNull(platform.datapacks.refused)
            assertFalse("data/test/worldgen/placed_feature/rocks.json" in started, "${started.keys}")
            // What names what it defines waits with it.
            assertFalse("data/test/worldgen/biome/stony.json" in started)
            val problems = server.runtime.currentProblems().filter { it.code == ProblemCodes.RUNTIME_DATAPACK.code }
            assertEquals(listOf(placed, "netherforge.json"), problems.map { it.file })
            assertTrue(
                "test:rocks in minecraft:worldgen/placed_feature: Failed to parse test:rocks" in problems[0].message,
                problems[0].message
            )
            assertTrue("minecraft:gone" in problems[1].message)
            // Running as it started is no reason to restart.
            assertFalse(server.reload(placed).restart)

            // Changed, it's tried again.
            server.write(placed, """{ "feature": "test:rock", "placement": [{ "type": "minecraft:in_square" }] }""")
            assertTrue(server.reload(placed).restart)
            server.restart()
            assertNull(platform.datapacks.refused)
            assertTrue("data/test/worldgen/placed_feature/rocks.json" in platform.datapacks.started)
            assertEquals(emptyList(), server.runtime.currentProblems().filter { it.code == ProblemCodes.RUNTIME_DATAPACK.code })
        }
    }

    @Test
    fun `a refusal is kept beside the plugin and read back`() {
        val folder = createTempDirectory("refusal")
        try {
            assertNull(DatapackRefusal.read(folder))
            val refusal = DatapackRefusal("abc", report)
            DatapackRefusal.write(folder, refusal)
            assertEquals(refusal, DatapackRefusal.read(folder))
            DatapackRefusal.delete(folder)
            assertNull(DatapackRefusal.read(folder))
            assertEquals(
                listOf("minecraft:worldgen/placed_feature" to "test:rocks", "minecraft:worldgen/noise" to "minecraft:gone"),
                refusal.entries().map { it.first to it.second }
            )
            assertEquals("Missing referenced element", refusal.entries()[1].third)
        } finally {
            folder.toFile().deleteRecursively()
        }
        // The same files are the same hash, whatever order they're given in; other bytes aren't.
        val a = mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(2))
        assertEquals(DatapackRefusal.hashOf(a), DatapackRefusal.hashOf(linkedMapOf("b" to byteArrayOf(2), "a" to byteArrayOf(1))))
        assertFalse(DatapackRefusal.hashOf(a) == DatapackRefusal.hashOf(mapOf("a" to byteArrayOf(1), "b" to byteArrayOf(3))))
    }
}
