package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.plugin.integration.support.Bots
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The project's blocks on a real server (W4.1): drawn by the resource pack as note block states, placed by a script
 * and by a player with an item, mined with a pickaxe for what the block's loot table drops, drawn by a centity,
 * kept across a restart with their data, and the vanilla note blocks working beside them: tuned and sounding the
 * instrument of what's below.
 */
class BlockScenario : Scenario("blocks") {
    override val server = PaperServer(onlineMode = true)

    private val bots get() = Bots(editor)

    /** Runs one of the fixture module's steps and answers the log line it wrote, its fields after the first. */
    private fun step(name: String, x: Int = 0, z: Int = 0, line: String = name): List<String> {
        editor.run("it-blocks $name $x $z")
        return editor.line(line)
    }

    /** The files of the zip at [url], by path. */
    private fun download(url: String): Map<String, ByteArray> {
        val response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI(url)).build(), HttpResponse.BodyHandlers.ofByteArray())
        assertEquals(200, response.statusCode(), "GET $url")
        val files = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(response.body())).use { zip ->
            generateSequence { zip.nextEntry }.forEach { files[it.name] = zip.readBytes() }
        }
        return files
    }

    @Test
    @Order(1)
    fun `the resource pack draws the project's blocks as note block states and every other state as the game does`() {
        val pack = editor.reload("resource_packs/ui/pack.json").resources.single().also { assertTrue(it.ok, "${it.problems}") }.pack!!
        val files = download(pack.url!!)
        val states = Json.parseToJsonElement(files.getValue("assets/minecraft/blockstates/note_block.json").decodeToString())
            .jsonObject.getValue("variants").jsonObject
        val models = states.values.map { it.jsonObject.getValue("model").jsonPrimitive.content }
        // Four blocks (the ore, crystal glass, the glow lamp, and the lamp a centity draws, which shows nothing of its
        // own), each in a state of its own.
        assertEquals(1, models.count { it == "showcase:block/ui/ruby_ore" })
        assertEquals(1, models.count { it == "showcase:block/ui/crystal_glass" })
        assertEquals(1, models.count { it == "showcase:block/ui/glow_lamp" })
        assertEquals(1, models.count { it == "showcase:block/hidden" })
        assertEquals(states.size - 4, models.count { it == "minecraft:block/note_block" })
        // Every state of the note block is there, whatever the game version has.
        assertEquals(0, states.size % 50)
        val model = files.getValue("assets/showcase/models/block/ui/ruby_ore.json").decodeToString()
        assertTrue("\"parent\": \"minecraft:block/cube\"" in model && "\"up\": \"showcase:ui/block/ruby_ore\"" in model, model)
        assertTrue("assets/showcase/textures/ui/block/ruby_ore.png" in files)
    }

    @Test
    @Order(2)
    fun `a script places one, which is a note block to the server and the project's block to scripts`() {
        assertEquals(listOf("ruby_ore", "minecraft:note_block"), step("place_ore", 3, 0, line = "placed"))
        assertEquals(listOf("ruby_ore", "minecraft:note_block", "it"), step("describe", 3, 0))
    }

    @Test
    @Order(3)
    fun `a player places one with its item, mines it with a pickaxe, and what its loot table rolled drops`() {
        editor.run("difficulty peaceful")
        val (joined, _) = editor.request(BotsExtension.join, BotJoinParams("Tester"))
        assertTrue(joined.ok, joined.error)
        editor.run("tp Tester 0 -62 0")
        assertEquals(listOf("ruby_ore"), step("give", line = "gave"))
        // The item is in the first slot, which a new bot holds: right-click the ground beside where it stands.
        val (x, z) = 1 to 2
        val ground = step("where", x, z)[0].toInt()
        bots.act("Tester", BotAction.UseBlock(x, ground, z, "up"))
        assertEquals(listOf("ruby_ore", "minecraft:note_block", "-"), step("describe", x, z))
        assertEquals(listOf("ruby_ore", "1"), step("held"))

        // With a pickaxe it breaks, and drops what the table rolled, which the example's script doubles for a diamond one.
        editor.run("give Tester minecraft:diamond_pickaxe")
        bots.act("Tester", BotAction.SelectSlot(1))
        bots.act("Tester", BotAction.BreakBlock(x, ground + 1, z, "up"))
        val broke = editor.line("broke")
        val (count, drop, each) = broke
        assertEquals("ruby", drop)
        assertTrue(count.toInt() >= 1 && each.toInt() in 2..6, "$broke")
        val gone = step("describe", x, z)
        assertEquals("none", gone[0])
        assertTrue(gone[1] != "minecraft:note_block", "$gone")
    }

    @Test
    @Order(4)
    fun `a player's note block is still tuned by a right click`() {
        assertEquals("0", step("note", -2, 2).single())
        val at = step("where", -2, 2)[0].toInt()
        bots.act("Tester", BotAction.SelectSlot(5))
        bots.act("Tester", BotAction.UseBlock(-2, at, 2, "north"))
        val note = step("note_state", -2, 2)
        assertEquals("1", note[0])
        // The state's own instrument stays the default's: the one the game gives what's below is worked out each time it sounds.
        assertEquals("harp", note[1])
    }

    @Test
    @Order(5)
    fun `a block drawn by a centity brings it, and takes it away`() {
        assertEquals(listOf("floating_lamp", "1"), step("lamp", -3, 0))
        assertEquals("0", step("lamp_break", -3, 0, line = "lamp gone")[0])
    }

    @Test
    @Order(6)
    fun `a restart keeps the project's blocks, with their data`() {
        step("place_ore", -3, 3, line = "placed")
        restart()
        assertEquals(listOf("ruby_ore", "minecraft:note_block", "it"), step("describe", -3, 3))
    }
}
