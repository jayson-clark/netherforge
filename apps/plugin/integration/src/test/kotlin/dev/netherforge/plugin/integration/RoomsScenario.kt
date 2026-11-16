package dev.netherforge.plugin.integration

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.bridge.BotEvent
import dev.netherforge.format.bridge.BotJoinParams
import dev.netherforge.format.bridge.BotState
import dev.netherforge.format.bridge.BotsExtension
import dev.netherforge.format.bridge.Log
import dev.netherforge.plugin.integration.support.Adapter
import dev.netherforge.plugin.integration.support.Bots
import dev.netherforge.plugin.integration.support.Maps
import dev.netherforge.plugin.integration.support.PaperServer
import dev.netherforge.plugin.integration.support.Scenario
import dev.netherforge.plugin.integration.support.eventually
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Two rooms beside the main one on one server (`fixtures/rooms`): r1, a match (opened without a key), and b, keyed and
 * kept.
 * Each copies the main world's map as its own "track" and four bots play in them, two a room. What each room's players
 * see (the player list, chat, boss bars, teams) and what its scripts hear (players, events, worlds) stays in it; a
 * player sent on arrives in the other; closing the match unloads its world and deletes it, and the kept room's stays on
 * disk for the next time it opens.
 */
class RoomsScenario : Scenario("rooms") {
    override val server = PaperServer(onlineMode = true, maxPlayers = 4)

    private val bots by lazy { Bots(editor) }
    private val folder: Path get() = server.folder

    /** Where world [name]'s own data is on this version. */
    private fun dimension(name: String): Path =
        if (Adapter.worldsAreDimensions) folder.resolve("world/dimensions/minecraft/$name") else folder.resolve(name)

    private val r1 = listOf("Alice", "Bob")
    private val b = listOf("Carol", "Dave")

    /** The players [bot]'s client lists (the tab list), sorted. */
    private fun listed(state: BotState): List<String> = state.playerList.filter { it.listed }.map { it.name }.sorted()

    /** The world a bot is in, as the server names it (a 26.x world is a dimension: `minecraft:track__r1`). */
    private fun world(state: BotState): String = state.world.substringAfter(':')

    @Test
    @Order(1)
    fun `the main world becomes a map each room copies as its own track`() {
        // A game rule of the main world's: the map carries it, so every copy has it (and the main world goes back).
        editor.run("gamerule max_entity_cramming 31")
        Maps.capture(folder, Maps.save(editor, "world"), project.file("maps/it_track"))
        editor.run("gamerule max_entity_cramming 24")
        val result = editor.reload("maps/it_track/level.dat")
        assertEquals(listOf("map:it_track" to true), result.resources.map { it.label to it.ok })
        editor.run("difficulty peaceful")
        editor.run("it-rooms open")
        editor.logged("opened", "1", "opening", "2", "b", "false", "true")
        // Each room's "track" is its own world, the server's track__r1 and track__b; each lists only its own.
        editor.logged("track", "r1", "track", "nil", "track", "31")
        editor.logged("track", "b", "track", "nil", "track", "31")
        editor.logged("world_load", "r1", "track")
        editor.logged("world_load", "b", "track")
        editor.assertNone("a room heard another's world load") {
            it is Log && it.message.startsWith("world_load\tmain\ttrack")
        }
        assertTrue(Files.isDirectory(dimension("track__r1")), "r1's copy")
        assertTrue(Files.isDirectory(dimension("track__b")), "b's copy")
    }

    @Test
    @Order(2)
    fun `players sent to a room arrive in it, on its track, among its players only`() {
        for (name in r1 + b) {
            val (joined, _) = editor.request(BotsExtension.join, BotJoinParams(name))
            assertTrue(joined.ok, joined.error)
        }
        editor.logged("join", "main", "Dave", "Alice,Bob,Carol,Dave")
        editor.run("it-rooms send")
        editor.logged("sent", "true,true,true,true")
        // Each room hears its own two arrive, and nobody else; the main room hears all four go (online still as they
        // go, as when a player quits the server).
        editor.logged("join", "r1", "Bob", "Alice,Bob")
        editor.logged("join", "b", "Dave", "Carol,Dave")
        editor.logged("quit", "main", "Dave", "Dave")
        for (name in r1) bots.eventually(name) { world(it) == "track__r1" }
        for (name in b) bots.eventually(name) { world(it) == "track__b" }
        // What a script in each room sees: its players and worlds, never another room's (Carol isn't in r1).
        bots.act("Alice", BotAction.Command("/it-rooms who"))
        editor.logged("who", "r1", "Alice", "Alice,Bob", "track", "Alice,Bob", "false")
        bots.act("Carol", BotAction.Command("/it-rooms who"))
        editor.logged("who", "b", "Carol", "Carol,Dave", "track", "Carol,Dave", "true")
        // The main room: nobody now, and every world but the rooms' tracks.
        editor.run("it-rooms who")
        val main = editor.line("who")
        assertEquals(listOf("main", "console", "", "", "false"), main.filterIndexed { i, _ -> i != 3 })
        val worlds = main[3].split(',')
        assertTrue("world" in worlds && worlds.none { it.startsWith("track") }, "the main room's worlds: $worlds")
    }

    @Test
    @Order(3)
    fun `the player list, boss bars and teams are the room's`() {
        for ((room, players) in listOf("r1" to r1, "b" to b)) {
            for (name in players) {
                val state = bots.eventually(name) { listed(it) == players && it.bossBars.size == 1 }
                assertEquals(listOf("Room $room"), state.bossBars.map { it.name }, "$name's boss bars")
                // The room's team has its own players. Teams are the server scoreboard's, so a client knows every
                // room's (as nf.<room>.<name>), though it never sees another room's players to show them on.
                val own = state.teams.single { it.displayName == "racers" && it.prefix == "[$room] " }
                assertEquals(players, own.members.sorted(), "$name's teams: ${state.teams}")
            }
        }
    }

    @Test
    @Order(4)
    fun `chat and a block broken stay in the room`() {
        val since = bots.state("Carol").events
        bots.act("Alice", BotAction.Chat("hi from r1"))
        editor.logged("chat", "r1", "Alice", "hi from r1", "Alice,Bob")
        bots.heard("Bob", 0, BotEvent.Chat::class.java) { "hi from r1" in it.text }
        bots.act("Alice", BotAction.Command("/it-rooms mark"))
        val marked = editor.line("marked").drop(1).map(String::toInt)
        bots.act("Alice", BotAction.BreakBlock(marked[0], marked[1], marked[2]))
        editor.logged("break", "r1", "track", "Alice")
        editor.settle()
        val carol = bots.events("Carol", since).filterIsInstance<BotEvent.Chat>()
        assertTrue(carol.none { "hi from r1" in it.text }, "Carol, in b, saw r1's chat: $carol")
        editor.assertNone("another room heard r1's chat or break") { frame ->
            val fields = (frame as? Log)?.message?.split('\t').orEmpty()
            fields.firstOrNull() in setOf("chat", "break") && fields.getOrNull(1) != "r1"
        }
    }

    @Test
    @Order(5)
    fun `a player sent on from one room arrives in the other`() {
        bots.act("Alice", BotAction.Command("/it-rooms go b"))
        editor.logged("go", "r1", "Alice", "b", "true")
        editor.logged("quit", "r1", "Alice", "Alice,Bob")
        editor.logged("join", "b", "Alice", "Alice,Carol,Dave")
        val alice = bots.eventually("Alice") { world(it) == "track__b" && listed(it) == listOf("Alice", "Carol", "Dave") }
        assertEquals(listOf("Room b"), alice.bossBars.map { it.name })
        bots.eventually("Bob") { listed(it) == listOf("Bob") }
    }

    @Test
    @Order(6)
    fun `closing the match sends its player back and deletes its world, and the kept room's world is kept`() {
        editor.run("it-rooms close r1")
        editor.logged("close", "r1", "true", "true")
        editor.logged("closing", "r1", "Bob")
        editor.logged("join", "main", "Bob", "Bob")
        val bob = bots.eventually("Bob") { world(it) == "overworld" || world(it) == "world" }
        assertEquals(listOf("Bob"), listed(bob))
        assertEquals(listOf("Room main"), bob.bossBars.map { it.name })
        eventually("r1's track deleted", poll = { Files.exists(dimension("track__r1")) }) { exists -> !exists }
        editor.run("it-rooms close b")
        editor.logged("closing", "b", "Alice,Carol,Dave")
        for (name in b + "Alice") {
            val back = bots.eventually(name) { world(it) == "overworld" || world(it) == "world" }
            assertEquals(listOf("Room main"), bots.eventually(name) { it.bossBars.size == 1 }.bossBars.map { it.name }, "$name, $back")
        }
        bots.eventually("Bob") { listed(it) == listOf("Alice", "Bob", "Carol", "Dave") }
        // Unloaded (the main room, which holds every world no room does, has neither track), but kept on disk: a room
        // keyed b, kept, finds it again.
        editor.run("it-rooms who")
        val worlds = editor.line("who")[3].split(',')
        assertTrue(worlds.none { it.startsWith("track") }, "the main room's worlds: $worlds")
        assertTrue(Files.isDirectory(dimension("track__b")), "b's track kept")
    }
}
