package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.plugin.integration.support.Bots
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.eventually
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two owners' rooms on one server (`fixtures/owner_rooms`), driven through the control endpoint as Kosmoi's agent
 * drives them: each opened kept and keyed by its owner, its world made and marked and a placard spawned in it; saved
 * with its writes paused and copied out (each world as a map, its tables as data.json) as the agent copies them to its
 * world storage; closed with its files deleted; and opened again from the copies, its world, its nf.data and its
 * placard (the same instance, with its record and its table, which travel in the world's folder) as they were.
 */
class OwnerRoomsScenario : Scenario("owner_rooms") {
    private val control: Path = Files.createTempDirectory("nfc").resolve("control.sock")
    private val imports: Path = Files.createTempDirectory("nf-imports")

    override val server = PaperServer(
        onlineMode = true,
        maxPlayers = 2,
        config = "control:\n  socket: $control\nworlds:\n  import-root: $imports\n"
    )

    private val bots by lazy { Bots(editor) }
    private val owners = mapOf("player_alice" to "Alice", "player_bob" to "Bob")
    private val marks = mapOf("player_alice" to "minecraft:gold_block", "player_bob" to "minecraft:diamond_block")

    /** The world a bot is in, as the server names it (a 26.x world is a dimension: `minecraft:home__player_alice`). */
    private fun world(state: BotState): String = state.world.substringAfter(':')

    /** `method path` with [body] to the control endpoint (no token on a Unix socket): the status and the JSON. */
    private fun ask(method: String, path: String, body: String = ""): Pair<Int, JsonObject> {
        SocketChannel.open(UnixDomainSocketAddress.of(control)).use { channel ->
            val bytes = body.toByteArray()
            val head = "$method $path HTTP/1.1\r\nHost: x\r\nContent-Length: ${bytes.size}\r\n\r\n"
            channel.write(ByteBuffer.wrap(head.toByteArray() + bytes))
            val answer = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8192)
            while (channel.read(buffer) >= 0) {
                answer.write(buffer.array(), 0, buffer.position())
                buffer.clear()
            }
            val text = answer.toString(Charsets.UTF_8)
            val status = text.substring(9, 12).toInt()
            return status to Json.parseToJsonElement(text.substringAfter("\r\n\r\n")).jsonObject
        }
    }

    private fun JsonElement.text(): String = jsonPrimitive.content

    @Test
    @Order(1)
    fun `two owners' rooms open kept, each making its world`() {
        for (key in owners.keys) {
            val (status, opened) = ask("POST", "/rooms/open", """{"key": "$key", "keep": true}""")
            assertEquals(200, status, "$opened")
            editor.logged("placard", key, "1", key)
            editor.logged("owner", key, "home", "true", marks.getValue(key), "1", "true")
        }
        val (_, state) = ask("GET", "/state")
        val rooms = state.getValue("rooms").jsonArray.associate {
            it.jsonObject.getValue("key").text() to it.jsonObject.getValue("worlds").jsonArray.map { w -> w.text() }
        }
        assertEquals(mapOf("player_alice" to listOf("home__player_alice"), "player_bob" to listOf("home__player_bob")), rooms)
    }

    @Test
    @Order(2)
    fun `each owner goes into their own room's world`() {
        for (name in owners.values) {
            val (joined, _) = editor.request(BotsExtension.join, BotJoinParams(name))
            assertTrue(joined.ok, joined.error)
        }
        for ((key, name) in owners) {
            editor.run("it-owner $key $name")
            editor.logged("send", key, name, "true")
            editor.logged("home", key, name)
            bots.eventually(name) { world(it) == "home__$key" }
        }
    }

    @Test
    @Order(3)
    fun `saved with its writes paused, each room says what to copy, and it's copied as the agent copies it`() {
        for (key in owners.keys) {
            val (status, saved) = ask("POST", "/save", """{"room": "$key", "pause": true}""")
            assertEquals(200, status, "$saved")
            val worlds = saved.getValue("worlds").jsonArray
            assertEquals(listOf("home"), worlds.map { it.jsonObject.getValue("name").text() })
            val home = worlds.single().jsonObject
            val map = imports.resolve(key).resolve("worlds/home")
            copyMap(server.folder.resolve(home.getValue("level").text()), server.folder.resolve(home.getValue("folder").text()), map)
            val tables = saved.getValue("data").jsonObject.getValue("showcase").jsonObject
            assertEquals("""{"opened":1}""", tables.getValue("notes").toString())
            // The same tables, in the file the server keeps them in until it stops (read again after a SIGTERM).
            val kept = Json.parseToJsonElement(Path.of(saved.getValue("data_file").text()).readText())
            assertEquals(saved.getValue("data"), kept)
            imports.resolve(key).resolve("data.json").writeText(saved.getValue("data").toString())
            assertEquals(200, ask("POST", "/resume", """{"room": "$key"}""").first)
        }
    }

    @Test
    @Order(4)
    fun `closed with delete, each room's players go to the main room and its world's files go`() {
        for ((key, name) in owners) {
            val (status, closed) = ask("POST", "/rooms/close", """{"key": "$key", "delete": true}""")
            assertEquals(200, status, "$closed")
            editor.logged("closing", key)
            bots.eventually(name) { world(it) == "overworld" || world(it) == "world" }
            val dimension = server.folder.resolve("world/dimensions/minecraft/home__$key")
            eventually("$key's world deleted", poll = { Files.exists(dimension) }) { exists -> !exists }
        }
        assertEquals(404, ask("POST", "/save", """{"room": "player_alice"}""").first)
    }

    @Test
    @Order(5)
    fun `opened again from the copies, each room has its world and its data back`() {
        for ((key, name) in owners) {
            val (status, opened) = ask("POST", "/rooms/open", """{"key": "$key", "keep": true, "folder": "$key"}""")
            assertEquals(200, status, "$opened")
            assertEquals(JsonArray(listOf(Json.parseToJsonElement("\"home\""))), opened.getValue("worlds"))
            // Not made again: the copy, its block where it was, its placard and its count of openings carried on.
            editor.logged("placard", key, "1", key)
            editor.logged("owner", key, "home", "false", marks.getValue(key), "2", "true")
            editor.run("it-owner $key $name")
            editor.logged("home", key, name)
            bots.eventually(name) { world(it) == "home__$key" }
        }
    }

    /** What Kosmoi's agent saves of a world: its level.dat at the top and its folder as the map's overworld. */
    private fun copyMap(level: Path, folder: Path, map: Path) {
        val overworld = map.resolve("dimensions/minecraft/overworld")
        Files.createDirectories(overworld)
        Files.copy(level, map.resolve("level.dat"))
        Files.walk(folder).use { paths ->
            for (file in paths.filter { Files.isRegularFile(it) }.toList()) {
                val relative = folder.relativize(file).joinToString("/")
                val skipped = file.fileName.toString() in setOf("session.lock", "uid.dat", "level.dat_old", "paper-world.yml")
                val players = relative.split('/').dropLast(1).any { it in setOf("players", "playerdata", "stats", "advancements") }
                if (skipped || players || relative.startsWith("data/paper/")) continue
                val target = overworld.resolve(relative)
                Files.createDirectories(target.parent)
                Files.copy(file, target)
            }
        }
    }
}
