package dev.netherforge.plugin.centity
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A centity's `body`: falling, landing, walking, stepping, jumping, bouncing, liquids, and the Lua API. */
class CentityBodyTest {
    /** The fake world is stone below y = 64 everywhere: its ground's top is y = 64. */
    private val ground = 64.0

    private fun files(body: String = "{}", script: String = "") = mapOf(
        "centities/walker/centity.json" to """
            {
              "nodes": { "root": { "hitbox": {} } },
              "body": $body,
              "script": { "file": "script.lua" }
            }
        """,
        "centities/walker/script.lua" to script
    )

    private fun TestServer.spawn(at: Location = Location("world", 0.5, ground + 5, 0.5)): Instance =
        assertNotNull(runtime.session.centities.spawn("walker", at))

    private fun near(want: Double, got: Double, tolerance: Double = 1e-6, what: String = "") =
        assertTrue(kotlin.math.abs(want - got) <= tolerance, "$what: want $want, got $got")

    @Test
    fun `it falls under gravity and lands on the ground, once`() {
        TestServer(
            files(
                script = """
                    this:on("land", function(event) log("land", string.format("%.1f", event.speed)) end)
                """
            )
        ).use { server ->
            val walker = server.spawn()
            server.tick(1)
            val mover = assertNotNull(walker.mover)
            assertTrue(walker.anchor.y < ground + 5, "it started falling")
            assertFalse(mover.onGround)
            server.tick(60)
            near(ground, walker.anchor.y, what = "standing on the ground")
            assertTrue(mover.onGround)
            assertEquals(0.0, mover.velocity.y)
            assertEquals(1, server.logs.size, "landed once: ${server.logs}")
            assertTrue(server.logs.single().startsWith("land\t"))
            server.tick(20)
            assertEquals(1, server.logs.size, "standing still isn't landing")
        }
    }

    @Test
    fun `it walks at the velocity it's given, stops at a wall and hears the bump`() {
        TestServer(
            files(
                body = """{ "stepHeight": 0 }""",
                script = """
                    this:on("bump", function(event) log("bump", tostring(event.normal)) end)
                """
            )
        ).use { server ->
            val walker = server.spawn(Location("world", 0.5, ground, 0.5))
            val mover = assertNotNull(walker.mover)
            // A wall across its way at x = 12.
            server.runtime.session.platform.blocks.set("world", 12, 64, 0, "minecraft:stone", false)
            server.runtime.session.platform.blocks.set("world", 12, 65, 0, "minecraft:stone", false)
            server.tick(2)
            mover.walk = Vec3(4.0, 0.0, 0.0)
            server.tick(20)
            // It reaches its walk at the acceleration's rate (20 b/s², so a fifth of a second), then keeps to it.
            near(4.0, mover.velocity.x, what = "walking speed")
            val x = walker.anchor.x
            server.tick(10)
            near(x + 2.0, walker.anchor.x, 1e-6, "half a second at 4 b/s")
            assertTrue(server.logs.isEmpty(), "nothing in its way yet")
            // It stops flush against the wall (0.3 is half its width).
            server.tick(60)
            near(12.0 - 0.3, walker.anchor.x, 1e-6, "flush against the wall")
            assertEquals(Vec3(-1.0, 0.0, 0.0), mover.wall)
            assertTrue(server.logs.isNotEmpty() && server.logs.all { it == "bump\tvec3(-1, 0, 0)" }, "${server.logs}")
            // Not walking: it slows to a stop by its friction.
            mover.walk = null
            mover.velocity = Vec3(-3.0, 0.0, 0.0)
            server.tick(10)
            assertEquals(0.0, mover.velocity.x)
        }
    }

    @Test
    fun `it steps up what its step height allows, and not more`() {
        TestServer(files(body = """{ "stepHeight": 0.6 }""")).use { server ->
            val walker = server.spawn(Location("world", 0.5, ground, 0.5))
            val mover = assertNotNull(walker.mover)
            val blocks = server.runtime.session.platform.blocks
            for (x in 3..5) blocks.set("world", x, 64, 0, "minecraft:oak_slab[type=bottom,waterlogged=false]", false)
            // Two blocks: a whole block above the slab's top, past a 0.6 step.
            blocks.set("world", 6, 64, 0, "minecraft:stone", false)
            blocks.set("world", 6, 65, 0, "minecraft:stone", false)
            server.tick(2)
            mover.walk = Vec3(4.0, 0.0, 0.0)
            server.tick(30)
            // Up onto the slab, then stopped by the wall.
            near(ground + 0.5, walker.anchor.y, 1e-6, "on the slab")
            near(6.0 - 0.3, walker.anchor.x, 1e-6, "against the block")
        }
    }

    @Test
    fun `a jump rises the height asked, worked out from its gravity and drag`() {
        TestServer(files()).use { server ->
            val walker = server.spawn(Location("world", 0.5, ground, 0.5))
            val mover = assertNotNull(walker.mover)
            server.tick(2)
            mover.velocity = Vec3(0.0, mover.jumpSpeed(1.25), 0.0)
            mover.onGround = false
            var top = walker.anchor.y
            repeat(40) {
                server.tick(1)
                top = maxOf(top, walker.anchor.y)
            }
            near(ground + 1.25, top, 0.02, "the top of the jump")
            assertTrue(mover.onGround)
        }
    }

    @Test
    fun `a flying body bounces off what it hits, and floats with no gravity`() {
        TestServer(files(body = """{ "fly": true, "bounce": 0.5, "drag": 0 }""")).use { server ->
            val walker = server.spawn(Location("world", 0.5, ground + 3, 0.5))
            val mover = assertNotNull(walker.mover)
            server.tick(10)
            near(ground + 3, walker.anchor.y, what = "no gravity")
            mover.velocity = Vec3(0.0, -10.0, 0.0)
            server.tick(10)
            assertTrue(mover.velocity.y > 0, "bounced up: ${mover.velocity}")
            near(5.0, mover.velocity.y, 1e-6, "at half the speed")
        }
    }

    @Test
    fun `water slows it and pulls it down less`() {
        TestServer(files()).use { server ->
            val walker = server.spawn(Location("world", 0.5, ground + 2, 0.5))
            val mover = assertNotNull(walker.mover)
            val blocks = server.runtime.session.platform.blocks
            for (y in 64..70) blocks.set("world", 0, y, 0, "minecraft:water", false)
            server.tick(1)
            assertTrue(mover.inLiquid)
            server.tick(10)
            // In air it would have fallen at 32 b/s² for half a second; in water far slower.
            assertTrue(mover.velocity.y > -4.0, "sinking slowly: ${mover.velocity}")
        }
    }

    @Test
    fun `one inside blocks rises out of them`() {
        TestServer(files()).use { server ->
            val walker = server.spawn(Location("world", 0.5, ground - 2, 0.5))
            server.tick(40)
            near(ground, walker.anchor.y, 1e-6, "back on top")
        }
    }

    @Test
    fun `scripts steer it, read it and change its settings`() {
        val result = LuaChecks.run(
            """
            local walker = nf.centities.spawn("walker", vec3(0.5, 64, 0.5))
            check("has a body", walker:has_body(), true)
            check("its file's settings, filled in", walker:body().width, 0.9)
            check("defaults", walker:body().gravity, 32)
            check("still", walker:velocity(), vec3(0, 0, 0))
            check("not walking", walker:walk_velocity(), nil)
            check("walk", walker:set_walk_velocity(vec3(2, 0, 0)), true)
            check("walking", walker:walk_velocity(), vec3(2, 0, 0))
            check("stop", walker:set_walk_velocity(), true)
            check("stopped", walker:walk_velocity(), nil)
            check("set", walker:set_velocity(vec3(1, 2, 3)), true)
            check("add", walker:add_velocity(vec3(1, 0, 0)), true)
            check("velocity", walker:velocity(), vec3(2, 2, 3))
            check("jump", walker:jump(1), true)
            check("jumped", walker:velocity().y > 6, true)
            fails("jump down", function() walker:jump(-1) end, "at least 0")
            check("set_body", walker:set_body({ fly = true, drag = 2 }), true)
            check("flies", walker:body().fly, true)
            check("clamped", walker:body().drag, 1)
            check("kept the rest", walker:body().width, 0.9)
            fails("no size", function() walker:set_body({ width = 0 }) end, "more than 0")
            check("on the ground", walker:is_on_ground(), false)
            check("in a liquid", walker:is_in_liquid(), false)
            check("no wall", walker:wall(), nil)

            local plain = nf.centities.spawn("plain", vec3(5.5, 64, 0.5))
            check("no body", plain:has_body(), false)
            check("its velocity", plain:velocity(), vec3(0, 0, 0))
            check("can't walk", plain:set_walk_velocity(vec3(1, 0, 0)), false)
            check("can't jump", plain:jump(1), false)
            check("no settings", plain:body(), nil)
            check("given one", plain:set_body({ height = 2 }), true)
            check("now it has", plain:has_body(), true)
            check("with the defaults", plain:body().width, 0.6)
            plain:remove()
            check("gone", plain:velocity(), nil)
            check("gone body", plain:has_body(), false)
            """,
            files(body = """{ "width": 0.9 }""") + mapOf(
                "centities/plain/centity.json" to """{ "nodes": { "root": {} } }"""
            )
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a reload starts the body again from the file`() {
        TestServer(files(body = """{ "width": 0.9 }""")).use { server ->
            val walker = server.spawn(Location("world", 0.5, ground, 0.5))
            assertNotNull(walker.mover).walk = Vec3(1.0, 0.0, 0.0)
            server.write(
                "centities/walker/centity.json",
                """{ "nodes": { "root": {} }, "body": { "width": 0.5 }, "script": { "file": "script.lua" } }"""
            )
            server.reload("centities/walker/centity.json")
            val mover = assertNotNull(walker.mover)
            assertEquals(0.5, mover.settings.width)
            assertNull(mover.walk)
            server.write("centities/walker/centity.json", """{ "nodes": { "root": {} }, "script": { "file": "script.lua" } }""")
            server.reload("centities/walker/centity.json")
            assertNull(walker.mover)
        }
    }
}
