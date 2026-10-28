package dev.netherforge.plugin.cutscene
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.EntityFlag
import dev.netherforge.plugin.platform.GameEvent
import dev.netherforge.plugin.platform.InventoryRef
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.testkit.FakePlayer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `nf.cutscenes` and `Cutscene` against the fake server: what a play does to
 * the player and puts back however it ends, how the camera moves, who hears
 * what, and the mistakes that are errors.
 */
class CutsceneTest {
    /** One second (20 ticks): 20 blocks east, turning a quarter and tilting, a cue with an event and text halfway. */
    private val intro = """
        {
          "camera": {
            "position": [
              { "time": 0, "value": [0, 64, 0] },
              { "time": 1, "value": [20, 64, 0] }
            ],
            "rotation": [
              { "time": 0, "yaw": 0, "pitch": 0 },
              { "time": 1, "yaw": 90, "pitch": 10 }
            ]
          },
          "cues": [{ "time": 0.5, "event": "half", "text": "<gold>Halfway", "duration": 0.25 }]
        }
    """

    private val home = Location("world", 100.5, 70.0, 100.5, 45.0, 5.0)

    /** A server with Alex online as a flying creative player at [home], and [module] as the one module's script. */
    private fun server(
        module: String,
        files: Map<String, Any> = emptyMap(),
        prepare: (FakePlayer) -> Unit = {
            it.gameMode = "creative"
            it.flags[EntityFlag.CAN_FLY] = true
            it.flags[EntityFlag.FLYING] = true
        }
    ): TestServer {
        val server = TestServer(mapOf("cutscenes/intro.json" to intro, "modules/t/init.lua" to "-- written below") + files, start = false)
        val alex = server.player("Alex")
        alex.location = home
        prepare(alex)
        server.write("modules/t/init.lua", module)
        server.start()
        return server
    }

    private val TestServer.alex get() = platform.players.byId.values.single { it.ref.name == "Alex" }

    private val TestServer.camera get() = platform.entities.all.values.singleOrNull { it.tag.node == "cutscene:camera" }

    private fun TestServer.run(command: String) = platform.commands.runConsole(command)

    /** `go` plays the intro for Alex and tells what happens to it. */
    private val play = """
        local scene
        nf.commands.register("go", function()
          scene = nf.cutscenes.play(nf.players.get("Alex"), "intro")
          scene:on("end", function(event)
            log("end " .. event.reason .. " active=" .. tostring(event.cutscene:is_active()) .. " mode=" .. tostring(event.player:game_mode()))
          end)
          scene:on("cue", function(event) log("cue " .. event.cue .. " at " .. tostring(scene:time())) end)
        end)
        nf.commands.register("stop", function() log("stop " .. tostring(scene:stop())) end)
        nf.commands.register("state", function()
          log("state " .. tostring(scene:is_active()) .. " " .. tostring(scene:time()) .. " " .. tostring(scene:player() ~= nil))
        end)
    """

    @Test
    fun `playing puts the player in spectator mode looking through a camera at the path's start`() = server(play).use { server ->
        server.run("go")
        val camera = assertNotNull(server.camera, "a camera was made")
        assertEquals("spectator", server.platform.players.gameMode(server.alex.ref.uuid))
        assertEquals(camera.id, server.platform.playerViews.cameras[server.alex.ref.uuid])
        assertEquals(Location("world", 0.0, 64.0, 0.0, 0.0, 0.0), camera.location)
        assertEquals(1, camera.look?.teleportTicks, "each move is tweened over a tick, by the client")
        assertEquals(false, camera.persistent, "a crash can't leave it behind")
        // The server moves a spectator to what they look through.
        assertEquals(camera.location, server.alex.location)
        assertEquals(emptyList(), server.errors.map { it.message })
    }

    @Test
    fun `the camera moves each tick along the path, the player with it, and the cue fires halfway`() = server(play).use { server ->
        server.run("go")
        server.tick()
        assertEquals(1.0, server.camera!!.location.x, 1e-9)
        assertEquals(4.5, server.camera!!.location.yaw, 1e-9)
        server.tick(9)
        val camera = server.camera!!
        assertEquals(10.0, camera.location.x, 1e-9)
        assertEquals(45.0, camera.location.yaw, 1e-9)
        assertEquals(5.0, camera.location.pitch, 1e-9)
        assertEquals(camera.location, server.alex.location)
        assertEquals(listOf("cue half at 0.5"), server.logs)
        // The cue's text is the subtitle for its duration, then cleared.
        assertEquals(listOf("", "<gold>Halfway", 0, 5, 10), server.alex.titles.single())
        server.tick(5)
        assertEquals(listOf(listOf("", "<gold>Halfway", 0, 5, 10), emptyList<Any>()), server.alex.titles)
    }

    @Test
    fun `at the end the player is put back as they were, and then the end handlers hear it`() = server(play).use { server ->
        val alex = server.alex
        server.run("go")
        server.tick(20)
        assertEquals(20.0, server.camera!!.location.x, 1e-9, "the last pose, drawn for a tick")
        assertEquals(emptyList(), server.logs.filter { it.startsWith("end") })
        server.tick()
        assertEquals(listOf("cue half at 0.5", "end finished active=true mode=creative"), server.logs)
        assertNull(server.camera)
        assertEquals("creative", server.platform.players.gameMode(alex.ref.uuid))
        assertEquals(home, alex.location)
        assertEquals(true, server.platform.worldEntities.flag(alex.ref.uuid, EntityFlag.FLYING))
        assertNull(server.platform.playerViews.cameras[alex.ref.uuid])
        server.run("state")
        assertEquals("state false nil false", server.logs.last())
        assertEquals(emptyList(), server.errors.map { it.message })
    }

    @Test
    fun `stop ends it with reason stopped and puts the player back at once`() = server(play).use { server ->
        server.run("go")
        server.tick(3)
        server.run("stop")
        assertEquals(listOf("end stopped active=true mode=creative", "stop true"), server.logs)
        assertNull(server.camera)
        assertEquals(home, server.alex.location)
        assertEquals("creative", server.platform.players.gameMode(server.alex.ref.uuid))
        server.tick(30)
        assertEquals(home, server.alex.location, "nothing moves them after")
    }

    @Test
    fun `a survival player who could not fly is put back unable to`() = server(play, prepare = {}).use { server ->
        server.run("go")
        assertEquals("spectator", server.platform.players.gameMode(server.alex.ref.uuid))
        server.tick(21)
        assertEquals("survival", server.platform.players.gameMode(server.alex.ref.uuid))
        assertEquals(false, server.platform.worldEntities.flag(server.alex.ref.uuid, EntityFlag.CAN_FLY))
        assertEquals(false, server.platform.worldEntities.flag(server.alex.ref.uuid, EntityFlag.FLYING))
    }

    @Test
    fun `a player who can't skip is held, and sneaking out of the camera puts them back in it`() = server(play).use { server ->
        server.run("go")
        server.tick(2)
        val uuid = server.alex.ref.uuid
        // What the server does when the player presses sneak.
        server.platform.playerViews.cameras.remove(uuid)
        server.tick()
        assertEquals(server.camera!!.id, server.platform.playerViews.cameras[uuid])
        assertEquals(emptyList(), server.logs)
    }

    @Test
    fun `a skippable cutscene ends skipped when the player sneaks`() = server(
        """
        nf.commands.register("go", function()
          local scene = nf.cutscenes.play(nf.players.get("Alex"), "intro", { skippable = true })
          scene:on("end", function(event) log("end " .. event.reason) end)
        end)
        """
    ).use { server ->
        server.run("go")
        server.tick(2)
        server.platform.playerViews.cameras.remove(server.alex.ref.uuid)
        server.tick()
        assertEquals(listOf("end skipped"), server.logs)
        assertEquals(home, server.alex.location)
        assertEquals("creative", server.platform.players.gameMode(server.alex.ref.uuid))
    }

    @Test
    fun `the file can make it skippable, and the script's option wins`() = server(
        """
        nf.commands.register("file", function()
          nf.cutscenes.play(nf.players.get("Alex"), "skippy"):on("end", function(event) log("file " .. event.reason) end)
        end)
        nf.commands.register("option", function()
          nf.cutscenes.play(nf.players.get("Alex"), "skippy", { skippable = false }):on("end", function(event) log("option " .. event.reason) end)
        end)
        """,
        mapOf("cutscenes/skippy.json" to intro.replace("{\n          \"camera\"", "{ \"skippable\": true,\n          \"camera\""))
    ).use { server ->
        server.run("file")
        server.platform.playerViews.cameras.remove(server.alex.ref.uuid)
        server.tick()
        assertEquals(listOf("file skipped"), server.logs)
        server.run("option")
        server.platform.playerViews.cameras.remove(server.alex.ref.uuid)
        server.tick()
        assertEquals(listOf("file skipped"), server.logs, "held")
    }

    @Test
    fun `a player who quits mid-cutscene ends it, player_left, and its camera goes`() = server(play).use { server ->
        server.run("go")
        server.tick(3)
        server.platform.players.quit(server.alex)
        assertEquals(listOf("end player_left active=true mode=nil"), server.logs)
        assertNull(server.camera)
        server.tick(30)
        assertEquals(emptyList(), server.errors.map { it.message })
    }

    @Test
    fun `a crash mid-cutscene is put right when the player next joins, before scripts hear the join`() = server(
        play + """
            nf.on("player_join", function(event)
              log("join mode=" .. tostring(event.player:game_mode()))
            end)
        """
    ).use { server ->
        val alex = server.alex
        server.run("go")
        server.tick(3)
        assertEquals("spectator", server.platform.players.gameMode(alex.ref.uuid))
        // What a crash leaves: the camera was never saved, the player's mode was.
        server.platform.entities.all.remove(server.camera!!.id)
        server.platform.playerViews.cameras.remove(alex.ref.uuid)
        server.crash()
        assertEquals("spectator", server.platform.players.gameMode(alex.ref.uuid), "nobody is put back before they join")

        server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
        assertEquals("creative", server.platform.players.gameMode(alex.ref.uuid))
        assertEquals(home, alex.location)
        assertEquals(true, server.platform.worldEntities.flag(alex.ref.uuid, EntityFlag.CAN_FLY))
        assertEquals(true, server.platform.worldEntities.flag(alex.ref.uuid, EntityFlag.FLYING))
        assertEquals(listOf("join mode=creative"), server.logs)
        assertEquals(emptyList(), server.errors.map { it.message })

        // Once is enough: the next start finds nothing to put back.
        server.restart()
        alex.gameMode = "survival"
        server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
        assertEquals("survival", server.platform.players.gameMode(alex.ref.uuid))
    }

    @Test
    fun `a cutscene that ended leaves nothing to put back after a crash`() = server(play).use { server ->
        val alex = server.alex
        server.run("go")
        server.tick(3)
        server.run("stop")
        alex.gameMode = "survival"
        server.crash()
        assertEquals(emptySet(), server.runtime.store.cutsceneStates.all().keys)
        server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
        assertEquals("survival", server.platform.players.gameMode(alex.ref.uuid))
    }

    @Test
    fun `a chain of cutscenes keeps the first's state for a crash to put back`() = server(play).use { server ->
        val alex = server.alex
        // The second cutscene would capture the camera's state; the first's is what a crash puts back.
        server.run("go")
        server.tick(2)
        server.run("go")
        server.tick(2)
        server.platform.entities.all.remove(server.camera!!.id)
        server.platform.playerViews.cameras.remove(alex.ref.uuid)
        assertEquals(setOf(alex.ref.uuid), server.runtime.store.cutsceneStates.all().keys)
        server.crash()
        assertEquals(setOf(alex.ref.uuid), server.runtime.store.cutsceneStates.all().keys)
        server.platform.raise.playerJoin(GameEvent.PlayerJoin(alex.ref, false, null))
        assertEquals("creative", server.platform.players.gameMode(alex.ref.uuid))
        assertEquals(home, alex.location)
    }

    @Test
    fun `a player who isn't there at a tick ends it as left too`() = server(play).use { server ->
        server.run("go")
        server.tick(2)
        // Gone without a quit the runtime heard.
        server.platform.players.byId.remove(server.alex.ref.uuid)
        server.tick()
        assertEquals(1, server.logs.count { it.startsWith("end player_left") })
        assertNull(server.camera)
    }

    @Test
    fun `the server stopping puts every player back`() = server(play).use { server ->
        server.run("go")
        server.tick(3)
        val alex = server.alex
        server.runtime.disable()
        assertEquals(home, alex.location)
        assertEquals("creative", server.platform.players.gameMode(alex.ref.uuid))
        assertNull(server.camera)
        server.start()
    }

    @Test
    fun `a script that unloads ends what it played, unloaded, and the player is put back`() = server(
        """
        nf.commands.register("go", function()
          nf.cutscenes.play(nf.players.get("Alex"), "intro")
        end)
        """,
        mapOf("modules/watcher/init.lua" to "nf.on('tick', function() end)")
    ).use { server ->
        server.run("go")
        server.tick(2)
        assertEquals("spectator", server.platform.players.gameMode(server.alex.ref.uuid))
        server.write("modules/t/init.lua", "-- nothing now")
        server.reload("modules/t/init.lua")
        assertNull(server.camera)
        assertEquals("creative", server.platform.players.gameMode(server.alex.ref.uuid))
        assertEquals(home, server.alex.location)
    }

    @Test
    fun `playing over a cutscene replaces it without putting the player back between`() = server(
        """
        nf.commands.register("go", function()
          local alex = nf.players.get("Alex")
          local first = nf.cutscenes.play(alex, "intro")
          first:on("end", function(event) log("first " .. event.reason .. " mode=" .. tostring(event.player:game_mode())) end)
          local second = nf.cutscenes.play(alex, "intro")
          second:on("end", function(event) log("second " .. event.reason) end)
          log("current is second: " .. tostring(nf.cutscenes.current(alex) == second) .. " first active: " .. tostring(first:is_active()))
        end)
        """
    ).use { server ->
        server.run("go")
        assertEquals(listOf("first replaced mode=spectator", "current is second: true first active: false"), server.logs)
        assertEquals(1, server.platform.entities.all.values.count { it.tag.node == "cutscene:camera" })
        server.tick(21)
        assertEquals(listOf("first replaced mode=spectator", "current is second: true first active: false", "second finished"), server.logs)
        assertEquals(home, server.alex.location)
        assertEquals("creative", server.platform.players.gameMode(server.alex.ref.uuid), "the state from before the first")
    }

    @Test
    fun `nf cutscenes stop and current work on a player's cutscene`() = server(
        """
        nf.commands.register("go", function()
          local alex = nf.players.get("Alex")
          log("before " .. tostring(nf.cutscenes.current(alex)) .. " " .. tostring(nf.cutscenes.stop(alex)))
          local scene = nf.cutscenes.play(alex, "intro")
          log("during " .. tostring(nf.cutscenes.current(alex) == scene) .. " " .. scene:kind() .. " " .. scene:length())
          log("stop " .. tostring(nf.cutscenes.stop(alex)) .. " " .. tostring(nf.cutscenes.current(alex)))
        end)
        """
    ).use { server ->
        server.run("go")
        assertEquals(listOf("before nil false", "during true intro 1.0", "stop true nil"), server.logs)
        assertEquals(home, server.alex.location)
    }

    @Test
    fun `origin moves the whole path, and a location puts it in its world`() = server(
        """
        nf.commands.register("go", function()
          nf.cutscenes.play(nf.players.get("Alex"), "intro", { origin = vec3(1000, 10, -50) })
        end)
        nf.commands.register("there", function()
          nf.cutscenes.play(nf.players.get("Alex"), "intro", { origin = nf.worlds.get("nether"):location(vec3(0, 0, 0)) })
        end)
        """
    ).use { server ->
        server.run("go")
        assertEquals(Location("world", 1000.0, 74.0, -50.0, 0.0, 0.0), server.camera!!.location)
        server.tick(10)
        assertEquals(1010.0, server.camera!!.location.x, 1e-9)
        server.run("there")
        assertEquals("nether", server.camera!!.location.world)
        assertEquals(64.0, server.camera!!.location.y, 1e-9)
        assertEquals("nether", server.alex.location.world)
        server.tick(21)
        assertEquals(home, server.alex.location, "back in their own world")
    }

    @Test
    fun `the camera's chunk is held while it's there, wherever that is, and let go when it ends`() = server(
        """nf.commands.register("go", function() nf.cutscenes.play(nf.players.get("Alex"), "intro", { origin = vec3(1000, 10, -50) }) end)"""
    ).use { server ->
        val worlds = server.platform.worlds
        // The path starts at 1000, 74, -50 (chunk 62, -4), far from Alex: nothing else has it loaded.
        worlds.unloaded += Triple("world", 62, -4)
        server.run("go")
        assertTrue(worlds.isChunkLoaded("world", 62, -4), "a camera in an unloaded chunk couldn't be reached")
        assertEquals(mapOf(Triple("world", 62, -4) to 1), worlds.held)
        assertEquals(server.camera!!.id, server.platform.playerViews.cameras[server.alex.ref.uuid])
        // Moving 10 blocks along x over the second, it crosses into chunk 63 and the hold goes with it.
        server.tick(10)
        assertEquals(mapOf(Triple("world", 63, -4) to 1), worlds.held)
        server.tick(21)
        assertEquals(emptyMap(), worlds.held, "nothing held once it's over")
    }

    @Test
    fun `a player who was riding is dismounted for the camera`() = server(
        """
        nf.commands.register("go", function()
          local alex = nf.players.get("Alex")
          local cart = nf.worlds.default():spawn_entity("minecraft:chest_minecart", alex:location().position)
          cart:add_passenger(alex)
          log("riding " .. tostring(alex:vehicle() ~= nil))
          nf.cutscenes.play(alex, "intro")
          log("riding " .. tostring(alex:vehicle() ~= nil))
        end)
        """
    ).use { server ->
        server.run("go")
        assertEquals(listOf("riding true", "riding false"), server.logs)
    }

    @Test
    fun `an open window is closed`() = server(play).use { server ->
        val uuid = server.alex.ref.uuid
        server.platform.inventories.open[uuid] = InventoryRef.Player(uuid)
        server.run("go")
        assertNull(server.platform.inventories.open[uuid])
    }

    @Test
    fun `someone changing their game mode takes them out, and the state is put back`() = server(play).use { server ->
        server.run("go")
        server.tick(2)
        server.platform.players.setGameMode(server.alex.ref.uuid, "adventure")
        server.tick()
        assertEquals(listOf("end stopped active=true mode=creative"), server.logs)
        assertNull(server.camera)
    }

    @Test
    fun `a cutscene may be stopped from its own cue, and every cue is heard once`() = server(
        """
        nf.commands.register("go", function()
          local scene = nf.cutscenes.play(nf.players.get("Alex"), "intro")
          scene:on("cue", function(event)
            log("cue " .. event.cue)
            scene:stop()
          end)
          scene:on("end", function(event) log("end " .. event.reason) end)
        end)
        """
    ).use { server ->
        server.run("go")
        server.tick(30)
        assertEquals(listOf("cue half", "end stopped"), server.logs)
        assertEquals(home, server.alex.location)
    }

    @Test
    fun `a player who is offline gets nil, and an unknown cutscene is an error`() = server(
        """
        local ghost = nf.players.get("Alex")
        nf.commands.register("late", function()
          log("late " .. tostring(nf.cutscenes.play(ghost, "intro")))
        end)
        nf.commands.register("unknown", function()
          local ok, err = pcall(nf.cutscenes.play, ghost, "nope")
          log(tostring(ok) .. " " .. tostring(err))
        end)
        """
    ).use { server ->
        server.platform.players.quit(server.alex)
        server.run("late")
        server.run("unknown")
        assertEquals("late nil", server.logs.first())
        assertTrue(
            server.logs.last().startsWith("false") && "no cutscene \"nope\" in this project" in server.logs.last(),
            server.logs.last()
        )
        assertNull(server.camera)
    }

    @Test
    fun `a cutscene whose file has errors can't be played`() = server(
        """
        nf.commands.register("broken", function()
          local ok, err = pcall(nf.cutscenes.play, nf.players.get("Alex"), "broken")
          log(tostring(ok) .. " " .. tostring(err))
        end)
        nf.commands.register("option", function()
          local ok, err = pcall(nf.cutscenes.play, nf.players.get("Alex"), "intro", { speed = 2 })
          log(tostring(ok) .. " " .. tostring(err))
        end)
        """,
        mapOf("cutscenes/broken.json" to """{ "length": 2, "camera": { "position": [{ "time": 0, "value": [0, 0, 0] }] } }""")
    ).use { server ->
        server.run("broken")
        assertTrue("has errors" in server.logs.single() && "rotation keys" in server.logs.single(), server.logs.single())
        server.run("option")
        assertTrue("speed" in server.logs.last(), server.logs.last())
        assertNull(server.camera)
    }

    @Test
    fun `a reload of the file leaves one playing as it was, and the next play takes the new`() = server(play).use { server ->
        server.run("go")
        server.tick(2)
        server.write("cutscenes/intro.json", intro.replace("[20, 64, 0]", "[40, 64, 0]"))
        server.reload("cutscenes/intro.json")
        server.tick()
        assertEquals(3.0, server.camera!!.location.x, 1e-9, "the version it started with")
        server.run("stop")
        server.run("go")
        server.tick(20)
        assertEquals(40.0, server.camera!!.location.x, 1e-9)
    }
}
