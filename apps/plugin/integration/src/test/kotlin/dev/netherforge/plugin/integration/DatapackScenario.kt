package dev.netherforge.plugin.integration

import dev.netherforge.format.ProblemCodes
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.Problems
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.plugin.integration.support.Adapter
import dev.netherforge.plugin.integration.support.Scenario
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The game's own worldgen passed through (W5.5), on a real server of each supported version: the fixture's datapack
 * replaces `minecraft:overworld`'s noise settings (its shape for 1.21.11 to 26.2 in one overlay, 26.3's in another)
 * with a shelf of smooth basalt whose density is a project density function, so a world the game generates as usual
 * (`nf.worlds.create`'s `"normal"`) has the datapack's terrain. The example's own datapack is there too: its placed
 * feature decorates the project biome that lists it (BiomeScenario looks at the grove). A file the server can't read
 * makes it refuse to start; the next start leaves the project's datapacks out and says why, until they change.
 */
class DatapackScenario : Scenario("datapacks") {
    private val pack get() = server.folder.resolve("plugins/NetherForge/datapack")

    /** Whether the server reads 26.3's worldgen (data pack format 121 on), as the start-up datapack says. */
    private val readsMinecraft263: Boolean get() {
        val meta = CanonicalJson.json.parseToJsonElement(pack.resolve("pack.mcmeta").readText()).jsonObject
        return meta.getValue("pack").jsonObject.getValue("min_format").jsonArray[0].jsonPrimitive.int >= 121
    }

    /** What the fixture writes for this server: 26.3's noise settings, or the earlier versions'. */
    private val overlay get() = if (readsMinecraft263) "mc26_3" else "until26_2"

    private fun columns(): List<List<String>> {
        editor.run("it-datapack columns")
        val log = editor.next(180, "the columns' log line") { it is Log && it.message.startsWith("columns\t") } as Log
        return log.message.substringAfter('\t').split(';').map { it.split(',') }
    }

    @Test
    @Order(1)
    fun `the start-up datapack has the files for this server's format`() {
        val settings = "data/minecraft/worldgen/noise_settings/overworld.json"
        assertEquals(
            project.read("datapacks/it_terrain/$overlay/$settings"),
            pack.resolve(settings).readText(),
            "the overlay for ${Adapter.minecraft}'s format replaces the overworld's noise settings"
        )
        assertTrue(pack.resolve("data/showcase/worldgen/density_function/it_shelf.json").exists())
        assertEquals(readsMinecraft263, pack.resolve("data/showcase/worldgen/material_rule/it_basalt.json").exists())
        // The example's boulders: the placed feature, and the configured feature in this version's own folder.
        assertTrue(pack.resolve("data/showcase/worldgen/placed_feature/ruby_boulders.json").exists())
        val feature = if (readsMinecraft263) "feature" else "configured_feature"
        assertTrue(pack.resolve("data/showcase/worldgen/$feature/ruby_boulder.json").exists())
    }

    @Test
    @Order(2)
    fun `a world the game generates as usual has the replaced terrain`() {
        val columns = columns()
        assertEquals(9, columns.size, "$columns")
        // The project's density function is solid below y 64 and empty above: basalt, air, and the shelf's top there.
        assertTrue(columns.count { it[0] == "minecraft:smooth_basalt" } >= 7, "basalt at y 40: $columns")
        assertTrue(columns.count { it[1] == "minecraft:air" } >= 8, "air at y 100: $columns")
        assertTrue(columns.count { it[2].toInt() in 60..66 } >= 7, "the shelf's top near y 64: $columns")
    }

    @Test
    @Order(3)
    fun `a file the server refuses keeps the project's datapacks out until it changes`() {
        val broken = "datapacks/it_terrain/data/showcase/worldgen/placed_feature/it_broken.json"
        // The game requires a placed feature's placement: only the server knows that.
        project.write(broken, "{ \"feature\": \"minecraft:forest_rock\" }\n")
        val result = editor.reload(broken)
        assertEquals(listOf(true), result.resources.map { it.ok }, "format finds nothing wrong with it")
        assertTrue(result.restart, "the server learns worldgen only as it starts")

        restart {
            server.refuses(project.root, "DatapackScenario-refused.log")
            assertTrue(server.folder.resolve("plugins/NetherForge/datapack-refused.json").exists(), "the refusal is on record")
        }
        // Started again, without them: the game's report is a problem on the file it named.
        val problems = (editor.next { it is Problems } as Problems).problems.filter { it.code == ProblemCodes.RUNTIME_DATAPACK.code }
        assertEquals(listOf(broken), problems.map { it.file }.distinct(), "$problems")
        assertTrue("showcase:it_broken" in problems.first().message, problems.first().message)
        assertTrue(!pack.resolve("data/minecraft/worldgen/noise_settings/overworld.json").exists(), "the datapacks are left out")
        assertTrue(!editor.reload(broken).restart, "running as it started is no reason to restart")

        // Fixed, they're tried again.
        Files.delete(project.file(broken))
        assertTrue(editor.reload(broken).restart)
        restart()
        assertEquals(emptyList(), (editor.next { it is Problems } as Problems).problems)
        assertTrue(pack.resolve("data/minecraft/worldgen/noise_settings/overworld.json").exists())
        assertTrue(!server.folder.resolve("plugins/NetherForge/datapack-refused.json").exists(), "nothing on record any more")
    }

    @Test
    @Order(4)
    fun `the world goes`() {
        editor.run("it-datapack drop")
        editor.logged("dropped", "true")
    }
}
