package dev.netherforge.plugin.particle
import dev.netherforge.format.bridge.ScriptError
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `nf.particles.play` and `Effect` against the fake server: what each pass
 * sends, to whom, where; how effects end and who hears it; and the mistakes
 * that are errors.
 */
class ParticleEffectTest {
    /** One flame at the origin each tick for [duration] ticks, one block in front of the effect (+Z). */
    private fun pulse(duration: Int = 5, loop: Boolean = false, force: Boolean = false) = """
        {
          "duration": $duration,
          ${if (loop) "\"loop\": true," else ""}
          "emitters": {
            "flame": { "particle": "minecraft:flame", "burst": 1, "every": 1, "offset": [0, 0, 1]${if (force) ", \"force\": true" else ""} }
          }
        }
    """

    private fun server(module: String, extra: Map<String, Any> = emptyMap(), players: Boolean = true): TestServer {
        val server = TestServer(
            mapOf("particles/pulse/effect.json" to pulse(), "modules/t/init.lua" to "-- written below") + extra,
            start = false
        )
        if (players) server.player("Alex")
        server.write("modules/t/init.lua", module)
        server.start()
        return server
    }

    private val TestServer.spawned get() = platform.particles.sent

    private fun TestServer.positions(): List<Vec3> = spawned.flatMap { (_, spawns, _) -> spawns.map { it.position } }

    @Test
    fun `play spawns tick 0 in the next pass, and the effect says what it is`() = server(
        """
        effect = nf.particles.play("pulse", vec3(10, 64, 0))
        log(effect:kind(), tostring(effect:is_active()), tostring(effect:location().position))
        """
    ).use { server ->
        assertEquals(listOf("pulse\ttrue\tvec3(10, 64, 0)"), server.logs)
        assertTrue(server.spawned.isEmpty(), "played between passes: nothing until the next")
        server.tick()
        val (world, spawns, viewers) = server.spawned.single()
        assertEquals("world", world)
        assertEquals("minecraft:flame", spawns.single().particle)
        assertEquals(Vec3(10.0, 64.0, 1.0), spawns.single().position)
        assertEquals(listOf("Alex"), viewers.map { it.name })
        assertEquals(emptyList(), server.errors.map { it.message })
    }

    @Test
    fun `an effect finishes after its duration and its end handlers hear why`() = server(
        """
        local effect = nf.particles.play("pulse", vec3(0, 64, 0))
        effect:on("end", function(event)
          local at = event.effect:location()
          log("end " .. event.reason .. " " .. event.effect:kind() .. " " .. tostring(event.effect:is_active()) .. " " .. tostring(at and at.position))
        end)
        nf.commands.register("check", function()
          log("active " .. tostring(effect:is_active()) .. " " .. tostring(effect:location()) .. " " .. effect:kind())
          log("stop " .. tostring(effect:stop()))
        end)
        """
    ).use { server ->
        server.tick(4)
        assertEquals(emptyList(), server.logs)
        server.tick()
        assertEquals(5, server.spawned.size)
        assertEquals(listOf("end finished pulse true vec3(0, 64, 0)"), server.logs)
        server.tick(3)
        assertEquals(5, server.spawned.size, "nothing after it ended")
        server.platform.commands.runConsole("check")
        assertEquals(listOf("end finished pulse true vec3(0, 64, 0)", "active false nil pulse", "stop false"), server.logs)
    }

    @Test
    fun `stop ends it with reason stopped, and loop overrides the file`() = server(
        """
        local effect = nf.particles.play("pulse", vec3(0, 64, 0), { loop = true })
        effect:on("end", function(event) log("end " .. event.reason) end)
        nf.commands.register("stop", function()
          log("stopped " .. tostring(effect:stop()) .. " " .. tostring(effect:is_active()))
        end)
        """
    ).use { server ->
        server.tick(12)
        assertEquals(12, server.spawned.size, "a looping effect keeps going past its duration")
        assertEquals(emptyList(), server.logs)
        server.platform.commands.runConsole("stop")
        assertEquals(listOf("end stopped", "stopped true false"), server.logs)
        server.tick()
        assertEquals(12, server.spawned.size)
    }

    @Test
    fun `teleport moves it, a location turning it, and a vector keeping its world and facing`() = server(
        """
        local world = nf.worlds.default()
        local effect = nf.particles.play("pulse", world:location(vec3(0, 64, 0), 90, 0), { loop = true })
        nf.commands.register("move", function()
          log(tostring(effect:teleport(vec3(5, 64, 5))), tostring(effect:location().yaw))
        end)
        nf.commands.register("turn", function()
          effect:teleport(world:location(vec3(5, 64, 5)))
        end)
        """
    ).use { server ->
        server.tick()
        // Yaw 90 faces west: the effect's +Z is the world's -X.
        assertVec(Vec3(-1.0, 64.0, 0.0), server.positions().last())
        server.platform.commands.runConsole("move")
        server.tick()
        assertVec(Vec3(4.0, 64.0, 5.0), server.positions().last())
        assertEquals(listOf("true\t90.0"), server.logs)
        server.platform.commands.runConsole("turn")
        server.tick()
        // A location without a yaw faces 0 (south, +Z).
        assertVec(Vec3(5.0, 64.0, 6.0), server.positions().last())
    }

    @Test
    fun `follow tracks a centity and a player with their yaw, and ends when the target goes`() = server(
        """
        local crate = nf.centities.spawn("c", nf.worlds.default():location(vec3(20, 64, 0), 90))
        local alex = nf.players.get("Alex")
        local a = nf.particles.play("pulse", vec3(0, 64, 0), { follow = crate, offset = vec3(0, 2, 0), loop = true })
        local b = nf.particles.play("pulse", vec3(0, 64, 0), { loop = true })
        b:follow(alex)
        a:on("end", function(event) log("a " .. event.reason) end)
        b:on("end", function(event) log("b " .. event.reason) end)
        nf.commands.register("move", function()
          crate:teleport(vec3(30, 64, 0))
        end)
        nf.commands.register("remove", function()
          crate:remove()
        end)
        """,
        mapOf("centities/c/centity.json" to TestServer.scriptedCentity(), "centities/c/script.lua" to "-- nothing")
    ).use { server ->
        server.platform.players.find("Alex")!!.let {
            server.platform.players.teleport(it.uuid, Location("world", 3.0, 64.0, 3.0, 180.0, 0.0))
        }
        server.tick()
        val (a, b) = server.spawned.map { it.second.single().position }
        // Above the centity, then one block along its facing (west).
        assertVec(Vec3(19.0, 66.0, 0.0), a)
        // Alex faces north (yaw 180): one block north of them.
        assertVec(Vec3(3.0, 64.0, 2.0), b)
        server.platform.commands.runConsole("move")
        server.tick()
        assertTrue(
            server.positions().drop(2).any {
                kotlin.math.abs(it.x - 29.0) < 1e-9 && kotlin.math.abs(it.y - 66.0) < 1e-9
            },
            "moved with the centity"
        )
        server.platform.commands.runConsole("remove")
        server.tick()
        assertEquals(listOf("a target_gone"), server.logs)
        server.platform.players.byId.clear()
        server.tick()
        assertEquals(listOf("a target_gone", "b target_gone"), server.logs)
    }

    @Test
    fun `follow tracks a vanilla entity with its yaw, and ends when the entity goes`() = server(
        """
        local world = nf.worlds.default()
        local pig = world:spawn_entity("pig", world:location(vec3(10, 64, 0), 90, 0))
        local a = nf.particles.play("pulse", vec3(0, 64, 0), { follow = pig, loop = true })
        a:on("end", function(event) log("a " .. event.reason) end)
        nf.commands.register("remove", function()
          pig:remove()
        end)
        """
    ).use { server ->
        server.platform.players.find("Alex")!!.let {
            server.platform.players.teleport(it.uuid, Location("world", 3.0, 64.0, 3.0, 180.0, 0.0))
        }
        server.tick()
        // One block along the pig's facing (west).
        assertVec(Vec3(9.0, 64.0, 0.0), server.spawned.single().second.single().position)
        server.platform.commands.runConsole("remove")
        server.tick()
        assertEquals(listOf("a target_gone"), server.logs)
    }

    @Test
    fun `an effect ends with its script, and other scripts' handlers hear unloaded`() {
        TestServer(
            mapOf(
                "particles/pulse/effect.json" to pulse(loop = true),
                "modules/player/init.lua" to "effect = nf.particles.play(\"pulse\", vec3(0, 64, 0))\nreturn effect",
                "modules/watcher/init.lua" to """
                    local effect = require("player")
                    if type(effect) == "table" then
                      effect:on("end", function(event) log("watcher heard " .. event.reason) end)
                    end
                """
            )
        ).use { server ->
            server.tick(2)
            server.write("modules/player/init.lua", "-- plays nothing now")
            server.reload("modules/player/init.lua")
            assertTrue("watcher heard unloaded" in server.logs, server.logs.toString())
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `a task can wait for an effect to end`() = server(
        """
        nf.task(function()
          local effect = nf.particles.play("pulse", vec3(0, 64, 0))
          local event = nf.wait_for(effect, "end")
          log("waited: " .. event.reason)
        end)
        """
    ).use { server ->
        server.tick(5)
        assertEquals(listOf("waited: finished"), server.logs)
    }

    @Test
    fun `mistakes are errors at the script's line, and an unloaded world is nil`() {
        val module = """
            ${LuaChecks.HELPERS}
            local nether = nf.worlds.get("nether")
            nf.commands.register("run", function()
              fails("unknown", function() nf.particles.play("plse", vec3(0, 64, 0)) end, 'no particle effect "plse" in this project (did you mean "pulse"?)')
              fails("typo", function() nf.particles.play("pulse", vec3(0, 64, 0), { scael = 2 }) end, "unknown field 'options.scael'")
              fails("offset alone", function() nf.particles.play("pulse", vec3(0, 64, 0), { offset = vec3(0, 1, 0) }) end, "options.offset only goes with options.follow")
              fails("scale", function() nf.particles.play("pulse", vec3(0, 64, 0), { scale = 17 }) end, "options.scale must be more than 0 and at most 16")
              fails("zero scale", function() nf.particles.play("pulse", vec3(0, 64, 0), { scale = 0 }) end, "options.scale")
              fails("follow a world", function() nf.particles.play("pulse", vec3(0, 64, 0), { follow = nf.worlds.default() }) end, "Centity, Node, Player or Entity or nil expected")
              fails("broken", function() nf.particles.play("broken", vec3(0, 64, 0)) end, 'particle effect "broken" has errors')
              local effect = nf.particles.play("pulse", vec3(0, 64, 0))
              fails("follow nothing", function() effect:follow("Alex") end, "bad argument 'target'")
              log("unloaded " .. tostring(nf.particles.play("pulse", nether:location(vec3(0, 64, 0)))))
              log("done")
            end)
        """
        server(module, mapOf("particles/broken/effect.json" to """{ "duration": 0 }""")).use { server ->
            server.platform.worlds.worldNames.remove("nether")
            server.platform.commands.runConsole("run")
            assertEquals(listOf("unloaded nil", "done"), server.logs)
        }
    }

    @Test
    fun `only viewers in range see it, forced emitters further`() = TestServer(
        mapOf(
            "particles/near/effect.json" to pulse(loop = true),
            "particles/far/effect.json" to pulse(loop = true, force = true),
            "modules/t/init.lua" to "-- written below"
        ),
        start = false
    ).use { server ->
        val alex = server.player("Alex")
        server.platform.players.add("Sam", Location("world", 0.5, 64.0, 40.0))
        server.platform.players.add("Kim", Location("world", 0.5, 64.0, 100.0))
        server.platform.players.add("Ash", Location("nether", 0.5, 64.0, 0.5))
        server.write(
            "modules/t/init.lua",
            """
            nf.particles.play("near", vec3(0, 64, 0))
            nf.particles.play("far", vec3(0, 64, 0))
            nf.particles.play("near", vec3(0, 64, 0), { viewers = { nf.players.get("Sam") } })
            nf.particles.play("near", vec3(0, 64, 0), { viewers = { nf.players.get("${alex.ref.name}") } })
            """
        )
        server.start()
        server.tick()
        val seen = server.spawned.map { it.third.map { player -> player.name }.sorted() }
        // Near: only Alex within 32. Far (forced): Sam at 40 and Kim at 100 too. Sam's own: out of range. Alex's own: Alex.
        assertEquals(listOf(listOf("Alex"), listOf("Alex", "Kim", "Sam"), listOf("Alex")), seen)
    }

    @Test
    fun `the pass keeps to its budget, skipping a different effect each tick, and caps active effects`() {
        val big = """{ "duration": 20, "loop": true, "emitters": { "e": { "particle": "minecraft:flame", "burst": 256, "every": 1 } } }"""
        server(
            """
            for i = 1, 17 do
              nf.particles.play("big", vec3(i, 64, 0))
            end
            nf.commands.register("one_more", function()
              local ok, err = pcall(nf.particles.play, "pulse", vec3(0, 64, 0))
              log(tostring(ok) .. " " .. tostring(err))
            end)
            """,
            mapOf("particles/big/effect.json" to big)
        ).use { server ->
            fun skippedAt(): Set<Double> {
                val before = server.spawned.size
                server.tick()
                val seen = server.spawned.drop(before).map { it.second.first().position.x }.toSet()
                return (1..17).map { it.toDouble() }.toSet() - seen
            }
            val first = skippedAt()
            val second = skippedAt()
            assertEquals(1, first.size)
            assertEquals(1, second.size)
            assertTrue(first != second, "the skipped effect rotates")
            assertEquals(17, server.runtime.session.particles.all().size)
            // Said like a slow script: in the console, as a script error on the effect's file, and in the problems.
            val warning = server.sent.filterIsInstance<ScriptError>().single { it.message.startsWith("particle budget exceeded") }
            assertTrue(warning.message.startsWith("particle budget exceeded: 1 effects skipped"), warning.message)
            assertEquals("particles/big/effect.json", warning.source?.file)
            assertTrue(server.platform.log.lines.any { it.contains("particle budget exceeded") })
            val problem = server.runtime.currentProblems().single { it.code == "particles.budget" }
            assertEquals("particles/big/effect.json", problem.file)
            server.tick(ParticleEffects.BUDGET_WARNING_TICKS.toInt() - 2)
            assertEquals(
                1,
                server.sent.filterIsInstance<ScriptError>().count {
                    it.message.startsWith("particle budget exceeded")
                },
                "once in ten seconds"
            )
            // Filled from Kotlin: a thousand calls from one command would outrun its instruction budget.
            repeat(ParticleEffects.MAX_ACTIVE - 17) {
                server.runtime.session.particles.play("pulse", Location("world", 0.0, 64.0, 0.0), owner = null)
            }
            server.platform.commands.runConsole("one_more")
            val last = server.logs.last()
            assertTrue(last.startsWith("false") && last.contains("too many particle effects playing (1024)"), last)
            // Once nothing's skipped for ten seconds, the problem goes.
            server.runtime.session.particles.stopUnowned()
            server.runtime.session.particles.all().forEach { server.runtime.session.particles.stop(it) }
            server.tick(ParticleEffects.BUDGET_WARNING_TICKS.toInt())
            assertEquals(emptyList(), server.runtime.currentProblems().filter { it.code == "particles.budget" })
        }
    }

    private fun assertVec(expected: Vec3, actual: Vec3) {
        val close = { a: Double, b: Double -> kotlin.math.abs(a - b) < 1e-9 }
        assertTrue(
            close(expected.x, actual.x) && close(expected.y, actual.y) && close(expected.z, actual.z),
            "expected $expected, got $actual"
        )
    }
}
