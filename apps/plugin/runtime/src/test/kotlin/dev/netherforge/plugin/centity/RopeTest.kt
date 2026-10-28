package dev.netherforge.plugin.centity
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals

/** Rope nodes strung between two ends (`node:rope_between`), and `node:stretch_between`. */
class RopeTest {
    private val files = mapOf(
        "centities/flail/centity.json" to """
            {
              "nodes": {
                "root": {},
                "beam": { "parent": "root", "display": { "type": "item", "item": "minecraft:stick" } },
                "chain": { "parent": "root", "display": { "type": "item", "item": "minecraft:stick" }, "rope": { "links": 8, "linkLength": 0.5 } }
              }
            }
        """.trimIndent()
    )

    @Test
    fun `a rope hangs by its slack, and shows only the links it needs`() {
        assertEquals(0.0, Ropes.sag(null, 3.0))
        assertEquals(0.0, Ropes.sag(2.0, 3.0), "taut: shorter than the gap")
        assertEquals(2.0, Ropes.sag(5.0, 3.0), 1e-9)
        val points = Ropes.points(Vec3(0.0, 0.0, 0.0), Vec3(4.0, 0.0, 0.0), 2.0, 4)
        assertEquals(5, points.size)
        assertEquals(-2.0, points[2].y, 1e-9, "the middle dips by the sag")
        assertEquals(Vec3(4.0, 0.0, 0.0), points.last())
        assertEquals(4, Ropes.shown(8, 0.5, null, 2.0))
        assertEquals(8, Ropes.shown(8, 0.5, 6.0, 2.0), "its length's worth, as many as it has")
        assertEquals(1, Ropes.shown(8, 0.5, null, 0.0))
    }

    @Test
    fun `stretch_between lays a node from one point to the other`() {
        val result = LuaChecks.run(
            """
            local flail = nf.centities.spawn("flail", vec3(0, 64, 0))
            local beam = flail:node("beam")
            check("laid", beam:stretch_between(vec3(1, 65, 0), vec3(1, 65, 4), { thickness = 0.2 }), true)
            local at = beam:world_position()
            check("halfway", string.format("%.2f %.2f %.2f", at.x, at.y, at.z), "1.00 65.00 2.00")
            local scale = beam:scale()
            check("its length and thickness", string.format("%.2f %.2f %.2f", scale.x, scale.y, scale.z), "0.20 0.20 4.00")
            check("pointing along", string.format("%.2f", beam:to_world(vec3(0, 0, 0.5)).z), "4.00")
            check("not a rope", beam:is_rope(), false)
            check("a rope", flail:node("chain"):is_rope(), true)
            fails("not one to string", function() beam:rope_between(vec3.zero, vec3.one) end, "isn't a rope")
            fails("a script's rope", function() flail:add_node("more", { rope = { links = 2 } }) end, "only centity.json")
            """,
            files
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a strung rope follows its ends each tick, and lets go when asked`() {
        TestServer(
            files + (
                "modules/t/init.lua" to """
                local function show(v) return string.format("%.2f,%.2f,%.2f", v.x, v.y, v.z) end
                nf.commands.register("go", function()
                  local flail = nf.centities.spawn("flail", vec3(0, 64, 0))
                  local chain = flail:node("chain")
                  log("strung", tostring(chain:rope_between(vec3(0, 70, 0), vec3(2, 70, 0))))
                  nf.after(2, function()
                    log("taut", show(flail:node("chain[1]"):world_position()), tostring(flail:node("chain[4]"):is_visible()),
                      tostring(flail:node("chain[5]"):is_visible()))
                    chain:rope_between(vec3(0, 70, 0), vec3(2, 70, 0), { length = 4 })
                  end)
                  nf.after(4, function()
                    local link = flail:node("chain[4]")
                    log("sagging", tostring(flail:node("chain[8]"):is_visible()), string.format("%.2f", link:to_world(vec3(0, 0, 0.5)).y))
                    chain:rope_between(nf.players.get("Alex"), vec3(0, 64, 0))
                  end)
                  nf.after(6, function() log("to a player", show(flail:node("chain[1]"):to_world(vec3(0, 0, -0.5)))) end)
                  nf.after(8, function()
                    chain:release_rope()
                    local before = show(flail:node("chain[1]"):world_position())
                    nf.players.get("Alex"):teleport(vec3(9, 64, 9))
                    nf.after(2, function() log("let go", tostring(before == show(flail:node("chain[1]"):world_position()))) end)
                  end)
                end)
                """.trimIndent()
                )
        ).use { server ->
            val alex = server.player("Alex")
            alex.location = Location("world", 3.0, 64.0, 0.0)
            server.platform.commands.runConsole("go")
            server.tick(12)
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(
                listOf(
                    "strung\ttrue",
                    // Taut, two blocks: four links of half a block, the first centred a quarter of the way along.
                    "taut\t0.25,70.00,0.00\ttrue\tfalse",
                    // Four blocks of rope over two: all eight links, the middle sagging sqrt(12) / 2 below.
                    "sagging\ttrue\t68.27",
                    // From the player's hands (1.1 above their feet).
                    "to a player\t3.00,65.10,0.00",
                    "let go\ttrue"
                ),
                server.logs
            )
        }
    }
}
