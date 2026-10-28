package dev.netherforge.plugin.centity
import dev.netherforge.format.game.Box
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** `world:overlap_box`, `overlap_sphere`, `overlap_cone`, `sweep`, `nearest`, `centity:bounds()` and `touch`. */
class SpatialQueryTest {
    /** A centity one block wide and [height] tall standing on its anchor, with a second box node `top` above it. */
    private fun crate(height: Double = 1.0) = """
        {
          "nodes": {
            "root": { "hitbox": { "boxes": [ { "min": [-0.5, 0, -0.5], "max": [0.5, $height, 0.5] } ] } },
            "top": {
              "parent": "root",
              "transform": { "translation": [0, $height, 0] },
              "hitbox": { "boxes": [ { "min": [-0.25, 0, -0.25], "max": [0.25, 0.5, 0.25] } ] }
            }
          },
          "script": { "file": "script.lua" }
        }
    """

    private fun TestServer.spawn(centity: String, at: Location) = assertNotNull(runtime.session.centities.spawn(centity, at))

    private val files = mapOf(
        "centities/crate/centity.json" to crate(),
        "centities/crate/script.lua" to "",
        "centities/post/centity.json" to crate(3.0),
        "centities/post/script.lua" to ""
    )

    @Test
    fun `the shapes test a box as the docs say`() {
        val box = Box(Vec3(0.0, 0.0, 0.0), Vec3(1.0, 1.0, 1.0))
        assertEquals(QueryShape.Reach(1.0, Vec3(1.0, 0.5, 0.5)), QueryShape.Sphere(Vec3(2.0, 0.5, 0.5), 1.0).test(box))
        assertNull(QueryShape.Sphere(Vec3(2.0, 0.5, 0.5), 0.99).test(box))
        // A cone pointing at it reaches; one pointing away doesn't; one too short doesn't.
        assertNotNull(QueryShape.Cone(Vec3(3.0, 0.5, 0.5), Vec3(-1.0, 0.0, 0.0), 2.5, 10.0).test(box))
        assertNull(QueryShape.Cone(Vec3(3.0, 0.5, 0.5), Vec3(1.0, 0.0, 0.0), 2.5, 10.0).test(box))
        assertNull(QueryShape.Cone(Vec3(3.0, 0.5, 0.5), Vec3(-1.0, 0.0, 0.0), 1.5, 10.0).test(box))
        // Wide enough to take in a box off to the side.
        assertNull(QueryShape.Cone(Vec3(3.0, 3.0, 0.5), Vec3(-1.0, 0.0, 0.0), 5.0, 30.0).test(box))
        assertNotNull(QueryShape.Cone(Vec3(3.0, 3.0, 0.5), Vec3(-1.0, 0.0, 0.0), 5.0, 60.0).test(box))
        // A capsule first touches the box a radius before its face.
        val reach = assertNotNull(QueryShape.Capsule(Vec3(-3.0, 0.5, 0.5), Vec3(3.0, 0.5, 0.5), 0.5).test(box))
        assertEquals(2.5, reach.distance, 1e-6)
        assertEquals(-0.5, reach.position.x, 1e-6)
        // One that passes above, just out of reach, and one that starts inside.
        assertNull(QueryShape.Capsule(Vec3(-3.0, 1.6, 0.5), Vec3(3.0, 1.6, 0.5), 0.5).test(box))
        assertEquals(0.0, assertNotNull(QueryShape.Capsule(Vec3(0.5, 0.5, 0.5), Vec3(3.0, 0.5, 0.5), 0.1).test(box)).distance)
    }

    @Test
    fun `overlaps find centities by their nodes and entities by their boxes, nearest first`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local near = nf.centities.spawn("crate", vec3(2, 64, 0))
            local far = nf.centities.spawn("post", vec3(6, 64, 0))
            local zombie = world:spawn_entity("zombie", vec3(-3, 64, 0))
            local alex = nf.players.get("Alex")

            local hits = world:overlap_sphere(vec3(0, 64.5, 0), 6)
            check("count", #hits, 3)
            check("nearest is the crate", hits[1].centity, near)
            check("its node", hits[1].node, near:node("root"))
            check("where on it", hits[1].position, vec3(1.5, 64.5, 0))
            check("how far", hits[1].distance, 1.5)
            check("then the zombie", hits[2].entity, zombie)
            check("then the post", hits[3].centity, far)

            check("players are found", world:overlap_sphere(vec3(0, 200, 0), 1)[1].entity, alex)
            check("but not when left out", #world:overlap_sphere(vec3(0, 200, 0), 1, { players = false }), 0)
            check("entities left out", #world:overlap_sphere(vec3(0, 64.5, 0), 6, { entities = false }), 2)
            check("centities left out", #world:overlap_sphere(vec3(0, 64.5, 0), 6, { centities = false }), 1)
            check("by kind", world:overlap_sphere(vec3(0, 64.5, 0), 6, { kinds = { "post" } })[1].centity, far)
            check("by entity type", world:overlap_sphere(vec3(0, 64.5, 0), 6, { kinds = { "minecraft:zombie" } })[1].entity, zombie)
            check("excluded", #world:overlap_sphere(vec3(0, 64.5, 0), 6, { exclude = { near, zombie } }), 1)
            check("limited", #world:overlap_sphere(vec3(0, 64.5, 0), 6, { limit = 2 }), 2)
            fails("limit", function() world:overlap_sphere(vec3(0, 64.5, 0), 6, { limit = 0 }) end, "at least 1")

            -- The post's top node sits 3 above its anchor: a box up there finds it by that node.
            local top = world:overlap_box(vec3(5, 67.2, -1), vec3(7, 68, 1))
            check("by its top", #top, 1)
            check("the top node", top[1].node, far:node("top"))
            check("corners either way round", #world:overlap_box(vec3(7, 68, 1), vec3(5, 67.2, -1)), 1)

            local cone = world:overlap_cone(vec3(0, 64.5, 0), vec3(1, 0, 0), 10, 20)
            check("the cone east", #cone, 2)
            check("the cone west", world:overlap_cone(vec3(0, 64.5, 0), vec3(-1, 0, 0), 10, 20)[1].entity, zombie)
            fails("angle", function() world:overlap_cone(vec3(0, 64.5, 0), vec3(-1, 0, 0), 10, 200) end, "0 to 180")

            local nearest = world:nearest(vec3(0, 64.5, 0), 20, { players = false })
            check("nearest", nearest.centity, near)
            check("nothing near", world:nearest(vec3(100, 64.5, 0), 5), nil)

            near:node("root"):set_visible(false)
            near:node("top"):set_visible(false)
            check("hidden nodes aren't found", world:nearest(vec3(0, 64.5, 0), 20, { players = false }).entity, zombie)
            near:remove()
            check("removed isn't", #world:overlap_sphere(vec3(2, 64.5, 0), 1), 0)
            """,
            files,
            setup = { it.platform.players.add("Alex", Location("world", 0.0, 200.0, 0.0)) }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a sweep finds what it passes in order, and stops at a block unless told`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local a = nf.centities.spawn("crate", vec3(3, 64, 0))
            local b = nf.centities.spawn("crate", vec3(6, 64, 0))
            world:set_block(vec3(8, 64, 0), "minecraft:stone")
            local c = nf.centities.spawn("crate", vec3(10, 64, 0))

            local hits = world:sweep(vec3(0, 64.5, 0.5), vec3(12, 64.5, 0.5), { radius = 0.25 })
            check("count", #hits, 3)
            check("first", hits[1].centity, a)
            check("first touch, a radius short of its face", hits[1].position, vec3(2.25, 64.5, 0.5))
            check("distance along", hits[1].distance, 2.25)
            check("second", hits[2].centity, b)
            check("then the wall", hits[3].block:position(), vec3(8, 64, 0))
            check("its face", hits[3].normal, vec3(-1, 0, 0))
            check("nothing past it", hits[4], nil)

            local through = world:sweep(vec3(0, 64.5, 0.5), vec3(12, 64.5, 0.5), { radius = 0.25, blocks = false })
            check("through blocks", #through, 3)
            check("reaches the far one", through[3].centity, c)
            check("misses what's off the line", #world:sweep(vec3(0, 66, 2), vec3(12, 66, 2), { radius = 0.25 }), 0)
            """,
            files
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `line of sight leaves out what's behind a block`() {
        val result = LuaChecks.run(
            """
            local world = nf.worlds.default()
            local hidden = nf.centities.spawn("crate", vec3(4, 64, 0))
            local seen = nf.centities.spawn("crate", vec3(-4, 64, 0))
            world:set_block(vec3(2, 64, -1), "minecraft:stone")
            check("both, without", #world:overlap_sphere(vec3(0.5, 64.5, -0.5), 6), 2)
            local hits = world:overlap_sphere(vec3(0.5, 64.5, -0.5), 6, { line_of_sight = true })
            check("only the one in view", #hits, 1)
            check("which", hits[1].centity, seen)
            check("nearest in view", world:nearest(vec3(0.5, 64.5, -0.5), 6, { line_of_sight = true }).centity, seen)
            """,
            files
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `bounds is the box around the shown hitboxes`() {
        val result = LuaChecks.run(
            """
            local post = nf.centities.spawn("post", vec3(6, 64, 0))
            local box = post:bounds()
            check("min", box.min, vec3(5.5, 64, -0.5))
            check("max", box.max, vec3(6.5, 67.5, 0.5))
            post:node("root"):set_visible(false)
            check("nothing shown", post:bounds(), nil)
            post:remove()
            check("removed", post:bounds(), nil)
            """,
            files
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `touch is heard once when hitboxes meet, and again only after they part`() {
        TestServer(
            files + mapOf(
                "centities/crate/script.lua" to """
                    this:on("touch", function(event)
                      local other = event.other and event.other:kind() or event.entity:kind()
                      log("touch", this:kind(), event.node:name(), other, event.other_node and event.other_node:name() or "-")
                    end)
                """
            )
        ).use { server ->
            server.spawn("crate", Location("world", 0.0, 64.0, 0.0))
            val post = server.spawn("post", Location("world", 5.0, 64.0, 0.0))
            server.tick(2)
            assertEquals(emptyList(), server.logs)
            // The post comes alongside: the crate hears it (the post has no listener).
            server.runtime.session.centities.teleport(post, Location("world", 0.9, 64.0, 0.0))
            server.tick(3)
            assertEquals(listOf("touch\tcrate\troot\tpost\troot"), server.logs)
            // It goes, and comes back: heard again.
            server.runtime.session.centities.teleport(post, Location("world", 5.0, 64.0, 0.0))
            server.tick(1)
            server.runtime.session.centities.teleport(post, Location("world", 0.9, 64.0, 0.0))
            server.tick(1)
            assertEquals(2, server.logs.size)
            // An entity walking into it.
            server.platform.worldEntities.spawn(
                "minecraft:zombie",
                Location("world", -0.6, 64.0, 0.0),
                dev.netherforge.plugin.platform.SpawnSetup()
            )
            server.tick(1)
            assertEquals("touch\tcrate\troot\tminecraft:zombie\t-", server.logs.last())
        }
    }
}
