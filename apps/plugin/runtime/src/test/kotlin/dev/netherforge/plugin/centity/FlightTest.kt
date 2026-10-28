package dev.netherforge.plugin.centity
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `centity:launch`: flying, hitting, piercing, bouncing, resting, sticking, homing, aiming and ending. */
class FlightTest {
    private val files = mapOf(
        "centities/shot/centity.json" to """
            { "nodes": { "aim": {}, "tip": { "parent": "aim", "display": { "type": "block", "block": "minecraft:stone" } } } }
        """,
        "centities/crate/centity.json" to """
            { "nodes": { "root": { "hitbox": { "boxes": [ { "min": [-0.5, 0, -0.5], "max": [0.5, 1, 0.5] } ] } } } }
        """
    )

    /** A server whose `/go` runs [body] (with `shot`, a fresh shot at [from], and `world`), logging its flight's events. */
    private fun flight(
        body: String,
        from: String = "vec3(0.5, 70.5, 0.5)",
        ticks: Int = 40,
        setup: (TestServer) -> Unit = {},
        check: (TestServer) -> Unit
    ) {
        val module = """
            local function f(n) return string.format("%.2f", n) end
            local function where(v) return f(v.x) .. "," .. f(v.y) .. "," .. f(v.z) end
            nf.commands.register("go", function()
              local world = nf.worlds.default()
              local shot = assert(nf.centities.spawn("shot", $from))
              shot:on("hit", function(event)
                log("hit", event.target and event.target:kind() or event.entity:kind(), where(event.position))
              end)
              shot:on("bounce", function(event) log("bounce", where(event.normal), f(event.speed)) end)
              shot:on("stick", function(event) log("stick", where(event.position)) end)
              shot:on("flight_end", function(event)
                log("end", event.reason, where(event.position), tostring(event.target and event.target:kind() or event.entity and event.entity:kind()))
              end)
              shot:on("remove", function() log("removed") end)
              $body
            end)
        """
        TestServer(files + ("modules/t/init.lua" to module), start = false).use { server ->
            server.platform.players.add("Alex", Location("world", 0.5, 64.0, 30.5))
            setup(server)
            server.start()
            server.platform.commands.runConsole("go")
            server.tick(ticks)
            check(server)
        }
    }

    @Test
    fun `it flies straight into a wall and ends there`() = flight(
        """
        world:set_block(vec3(5, 70, 0), "minecraft:stone")
        shot:launch({ velocity = vec3(20, 0, 0), aim = "aim" })
        nf.after(1, function() log("aim", f(shot:node("aim"):rotation().y), tostring(shot:is_launched())) end)
        shot:on("flight_end", function(event) log("normal", where(event.normal)) end)
        """
    ) { server ->
        assertEquals(
            listOf("aim\t90.00\ttrue", "end\tblock\t5.00,70.50,0.50\tnil", "normal\t-1.00,0.00,0.00", "removed"),
            server.logs
        )
    }

    @Test
    fun `gravity brings it down to the ground, and drag slows it`() = flight(
        """shot:launch({ velocity = vec3(4, 0, 0), gravity = 32, drag = 0.5 })"""
    ) { server ->
        val end = server.logs.first { it.startsWith("end") }.split("\t")
        assertEquals("block", end[1])
        assertEquals("64.00", end[2].split(",")[1], "on the ground's top")
    }

    @Test
    fun `pierce lets it through hits, and a cancelled hit doesn't use one up`() = flight(
        """
        nf.centities.spawn("crate", vec3(3.5, 70, 0.5))
        nf.centities.spawn("crate", vec3(6.5, 70, 0.5))
        local third = nf.centities.spawn("crate", vec3(9.5, 70, 0.5))
        shot:on("hit", function(event)
          if event.target == third then return end
          if event.position.x < 4 then event:cancel() end
        end)
        shot:launch({ velocity = vec3(20, 0, 0), radius = 0.2, pierce = 1 })
        """
    ) { server ->
        assertEquals(
            listOf(
                "hit\tcrate\t2.80,70.50,0.50",
                "hit\tcrate\t5.80,70.50,0.50",
                "hit\tcrate\t8.80,70.50,0.50",
                "end\thit\t8.80,70.50,0.50\tcrate",
                "removed"
            ),
            server.logs
        )
    }

    @Test
    fun `it bounces off walls until it has made its bounces`() = flight(
        """
        for y = 69, 72 do
          world:set_block(vec3(4, y, 0), "minecraft:stone")
          world:set_block(vec3(-3, y, 0), "minecraft:stone")
        end
        shot:launch({ velocity = vec3(20, 0, 0), bounce = 0.5, max_bounces = 2 })
        """
    ) { server ->
        assertEquals(
            listOf("bounce\t-1.00,0.00,0.00\t20.00", "bounce\t1.00,0.00,0.00\t10.00", "end\tblock\t4.00,70.50,0.50\tnil", "removed"),
            server.logs
        )
    }

    @Test
    fun `a bouncing one comes to rest on the floor and waits out its lifetime`() = flight(
        """
        shot:launch({ velocity = vec3(0, -6, 0), gravity = 32, bounce = 0.2, rest_speed = 3, lifetime = 30, remove_on_end = false })
        nf.after(25, function() log("resting", tostring(shot:is_launched()), where(shot:position()), where(shot:velocity())) end)
        nf.after(35, function() log("after", tostring(shot:exists()), tostring(shot:is_launched())) end)
        """
    ) { server ->
        val logs = server.logs
        assertTrue(logs.first().startsWith("bounce\t0.00,1.00,0.00"), "$logs")
        assertTrue("resting\ttrue\t0.50,64.01,0.50\t0.00,0.00,0.00" in logs, "$logs")
        assertTrue(logs.any { it.startsWith("end\tlifetime") }, "$logs")
        assertTrue("after\ttrue\tfalse" in logs, "kept, its flight over: $logs")
    }

    @Test
    fun `it sticks in a block and stays until its lifetime is up`() = flight(
        """
        world:set_block(vec3(5, 70, 0), "minecraft:stone")
        shot:launch({ velocity = vec3(20, 0, 0), stick = true, lifetime = 20 })
        """
    ) { server ->
        assertEquals(listOf("stick\t5.00,70.50,0.50", "end\tlifetime\t5.00,70.50,0.50\tnil", "removed"), server.logs)
    }

    @Test
    fun `homing turns it onto a target off to the side`() = flight(
        """
        nf.centities.spawn("crate", vec3(8.5, 70, 6.5))
        shot:launch({ velocity = vec3(10, 0, 0), homing = { range = 12, turn = 360 }, radius = 0.2 })
        """
    ) { server ->
        assertTrue(server.logs.any { it.startsWith("end\thit") && it.endsWith("crate") }, "${server.logs}")
    }

    @Test
    fun `it hits a player in its way, but never its owner`() = flight(
        """
        local alex = nf.players.get("Alex")
        alex:teleport(vec3(3.5, 70, 0.5))
        local other = assert(nf.centities.spawn("shot", vec3(0.5, 70.5, 0.5)))
        other:on("flight_end", function(event) log("other", event.reason, tostring(event.entity)) end)
        other:launch({ velocity = vec3(20, 0, 0), owner = alex, lifetime = 10 })
        shot:launch({ velocity = vec3(20, 0, 0), hits = { centities = false } })
        """
    ) { server ->
        assertTrue(server.logs.any { it.startsWith("end\thit") && it.endsWith("minecraft:player") }, "${server.logs}")
        assertTrue(server.logs.any { it.startsWith("other\tlifetime") }, "the owner is never hit: ${server.logs}")
    }

    @Test
    fun `stop_flight ends it, and a bad option is an error`() {
        val result = LuaChecks.run(
            """
            local shot = nf.centities.spawn("shot", vec3(0.5, 70.5, 0.5))
            check("not yet", shot:is_launched(), false)
            check("stop nothing", shot:stop_flight(), false)
            fails("velocity", function() shot:launch({}) end, "velocity")
            fails("bounce", function() shot:launch({ velocity = vec3(1, 0, 0), bounce = 2 }) end, "0 to 1")
            fails("aim", function() shot:launch({ velocity = vec3(1, 0, 0), aim = "nope" }) end, "no node")
            check("launched", shot:launch({ velocity = vec3(1, 0, 0) }), true)
            check("flying", shot:is_launched(), true)
            check("its velocity", shot:velocity(), vec3(1, 0, 0))
            local reason
            shot:on("flight_end", function(event) reason = event.reason end)
            check("stopped", shot:stop_flight(), true)
            check("why", reason, "stopped")
            check("removed", shot:exists(), false)
            """,
            files
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a flight into an unloaded chunk ends there`() = flight(
        """shot:launch({ velocity = vec3(20, 0, 0) })""",
        ticks = 30,
        setup = { it.platform.worlds.unloaded += Triple("world", 1, 0) }
    ) { server ->
        assertTrue(server.logs.any { it.startsWith("end\tunloaded\t15") }, "it stops at the chunk's edge: ${server.logs}")
    }
}
