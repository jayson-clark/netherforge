package dev.netherforge.plugin.centity
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.platform.Ray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CentityTest {

    private fun TestServer.spawn(centity: String, at: Location = Location("world", 10.0, 64.0, 10.0)) =
        assertNotNull(runtime.session.centities.spawn(centity, at), "spawned $centity")

    @Test
    fun `the example tower spawns a display per drawn node and a hitbox per clickable one`() {
        TestServer(TestServer.example("showcase")).use { server ->
            assertTrue(server.runtime.currentProblems().isEmpty(), "${server.runtime.currentProblems()}")
            val alex = server.player("Alex")
            val tower = server.runtime.spawnNear("tower", alex.ref)
            val entities = server.platform.entities.of(tower.id)
            // The turret is an empty pivot: nothing drawn, nothing to click.
            val displays = entities.filter { it.role == EntityRole.DISPLAY }.map { it.tag.node }.toSet()
            assertEquals(setOf("base", "keep", "cap", "flag"), displays)
            assertEquals(setOf("base", "keep", "cap"), entities.filter { it.role == EntityRole.HITBOX }.map { it.tag.node }.toSet())
            // Two blocks in front of a player facing south (+z), on the grid.
            assertEquals(Location("world", 0.0, 64.0, 2.0), tower.anchor)
            // Displays stand at the anchor and carry their offset in the matrix: the cap's own, and its turret's above it.
            val cap = server.platform.entities.display(tower.id, "cap")
            assertEquals(tower.anchor, cap.location)
            assertEquals(Vec3(-0.5, 2.0, -0.5), cap.pose!!.matrix.translation())
        }
    }

    @Test
    fun `clicking the tower's cap bubbles to the centity's script, which spins its turret`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            val tower = server.runtime.spawnNear("tower", alex.ref)
            val top = server.platform.entities.display(tower.id, "cap")
            val hitbox = server.platform.entities.hitbox(tower.id, "cap")

            assertTrue(server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null))
            assertEquals(listOf("<gold>The tower turns.</gold> <gray>Turns of this tower: 1"), alex.actionbars)
            assertTrue(tower.animations.isPlaying("spin"))
            assertEquals(listOf("sparkle"), server.runtime.session.particles.all().map { it.kind }, "with a sparkle")

            server.tick(10)
            // Half a second in: turned about the Y axis, so the X basis column has swung away from +X.
            assertNotEquals(1.0, top.pose!!.matrix.values[0], 0.1)
            server.tick(30)
            assertFalse(tower.animations.isPlaying("spin"), "a once clip ends")
            // Back at rest: no rotation.
            assertEquals(1.0, top.pose!!.matrix.values[0], 1e-9)
        }
    }

    @Test
    fun `the lamp's autoplay clip moves it without any script`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val lamp = server.spawn("lamp")
            val light = server.platform.entities.display(lamp.id, "light")
            val start = light.pose!!.matrix.translation().y
            server.tick(10)
            assertTrue(light.pose!!.matrix.translation().y > start + 0.05, "bobbed up")
            assertTrue(lamp.animations.isPlaying("bob"))
        }
    }

    private val tree = """
        {
          "nodes": {
            "root": {},
            "arm": { "parent": "root", "display": { "type": "block", "block": "minecraft:stone" }, "hitbox": {} },
            "hand": {
              "parent": "arm",
              "transform": { "translation": [0, 1, 0] },
              "display": { "type": "block", "block": "minecraft:oak_stairs[facing=east]" },
              "hitbox": { "shape": "collision" }
            },
            "label": { "parent": "root", "display": { "type": "text", "text": "hi" } }
          },
          "animations": {
            "wave": { "tracks": { "arm": { "rotation": [{ "time": 0, "value": [0, 0, 0] }, { "time": 0.25, "value": [0, 0, 45] }] } } }
          },
          "script": { "file": "script.lua" }
        }
    """

    @Test
    fun `node handles read and write transforms, visibility and content`() {
        TestServer(
            mapOf(
                "centities/robot/centity.json" to tree,
                "centities/robot/script.lua" to """
                    do
                      local arm = this:node("arm")
                      arm:set_translation(vec3(1, 2, 3))
                      log("translation", arm:translation())
                      log("parent", arm:parent():name(), "children", #arm:children(), arm:children()[1]:name())
                      log("nodes", #this:nodes(), this:nodes()[1]:name(), this:kind())
                      log("missing", tostring(this:node("nope")))
                      log("same handle", this:node("arm") == arm, arm:centity() == this)
                      this:node("label"):set_display_text("<red>changed")
                      log("block", this:node("hand"):set_display_block("minecraft:oak_slab"), this:node("hand"):set_display_block("minecraft:not_a_block"))
                      log("text on a block", this:node("hand"):set_display_text("x"))
                      this:node("hand"):set_visible(false)
                      log("visible", this:node("hand"):is_visible())
                    end
                """
            )
        ).use { server ->
            val robot = server.spawn("robot")
            assertEquals(
                listOf(
                    "translation\tvec3(1, 2, 3)",
                    "parent\troot\tchildren\t1\thand",
                    "nodes\t4\troot\trobot",
                    "missing\tnil",
                    "same handle\ttrue\ttrue",
                    "block\ttrue\tfalse",
                    "text on a block\tfalse",
                    "visible\tfalse"
                ),
                server.logs
            )
            server.tick()
            val entities = server.platform.entities
            assertEquals(Vec3(1.0, 2.0, 3.0), entities.display(robot.id, "arm").pose!!.matrix.translation())
            assertEquals("<red>changed", (entities.display(robot.id, "label").display as TextDisplay).text)
            val hand = entities.display(robot.id, "hand")
            assertEquals("minecraft:oak_slab", (hand.display as BlockDisplay).block)
            // Hidden: collapsed to nothing, and its hitbox shrunk out of reach.
            assertEquals(0.0, hand.pose!!.matrix.scale().x)
            assertEquals(0.01, entities.hitbox(robot.id, "hand").width)
        }
    }

    @Test
    fun `a collision-shaped hitbox follows the block live`() {
        TestServer(
            mapOf(
                "centities/robot/centity.json" to tree,
                "centities/robot/script.lua" to """
                    this:node("hand"):on("click", function()
                      this:node("hand"):set_display_block("minecraft:oak_slab")
                    end)
                """
            )
        ).use { server ->
            val robot = server.spawn("robot")
            val hitbox = server.platform.entities.hitbox(robot.id, "hand")
            // The stair's two collision boxes fill the cube between them.
            assertEquals(1.0, hitbox.height, 1e-9)
            server.runtime.events.entityClicked(hitbox.id, server.player().ref, ClickButton.RIGHT, null)
            server.tick()
            assertEquals(0.5, hitbox.height, 1e-9)
        }
    }

    @Test
    fun `the body runs first, then spawn, and remove comes before unload`() {
        TestServer(
            mapOf(
                "centities/robot/centity.json" to tree,
                "centities/robot/script.lua" to """
                    local ticks = 0
                    log("body")
                    this:on("spawn", function(event) log("spawn", event.centity == this, event.name) end)
                    this:on("tick", function() ticks = ticks + 1 end)
                    this:on("animation_start", function(event) log("started " .. event.animation) end)
                    this:on("animation_end", function(event) log("ended " .. event.animation .. " after " .. ticks .. " ticks") end)
                    this:on("remove", function() log("remove", this:exists()) end)
                    nf.on("unload", function() log("unload") end)
                    this:play_animation("wave")
                """
            )
        ).use { server ->
            val robot = server.spawn("robot")
            server.tick(10)
            server.runtime.session.centities.remove(robot)
            assertEquals(
                listOf("body", "started wave", "spawn\ttrue\tspawn", "ended wave after 4 ticks", "remove\ttrue", "unload"),
                server.logs
            )
            assertTrue(server.platform.entities.of(robot.id).isEmpty(), "its entities are gone")
        }
    }

    @Test
    fun `a centity nobody listens to tick on isn't ticked`() {
        TestServer(
            mapOf(
                "centities/robot/centity.json" to tree,
                "centities/robot/script.lua" to """
                    local count = 0
                    local subscription = this:on("tick", function(event)
                      count = count + 1
                      log("tick", event.tick, count)
                    end)
                    nf.after(3, function()
                      subscription:cancel()
                      log("active", subscription:is_active())
                    end)
                """
            )
        ).use { server ->
            server.spawn("robot")
            server.tick(6)
            // Every tick, until it was cancelled after the timer at tick 3.
            assertEquals(listOf("tick\t1\t1", "tick\t2\t2", "active\tfalse"), server.logs)
        }
    }

    @Test
    fun `a click bubbles from the node through its ancestors and the centity to nf, until something stops it`() {
        TestServer(
            mapOf(
                "centities/robot/centity.json" to tree,
                "centities/robot/script.lua" to """
                    this:node("hand"):on("click", function(event)
                      log("hand", event.target:name(), event.current:name(), event.click, event.player:name(), event.name)
                      if event.click == "left" then
                        event:stop()
                      end
                    end)
                    this:node("arm"):on("click", function(event)
                      log("arm", event.target:name(), event.current:name())
                    end)
                    this:on("click", function(event)
                      log("centity", event.target:name(), event.current == this, event.name)
                    end)
                """,
                "modules/watch/init.lua" to """
                    nf.on("centity_click", function(event)
                      log("module", event.target:centity():kind(), event.target:name(), event.name, tostring(event.current))
                    end)
                """
            )
        ).use { server ->
            val robot = server.spawn("robot")
            val alex = server.player("Alex")
            val hand = server.platform.entities.hitbox(robot.id, "hand").id
            server.runtime.events.entityClicked(hand, alex.ref, ClickButton.LEFT, null)
            server.runtime.events.entityClicked(hand, alex.ref, ClickButton.RIGHT, null)
            assertEquals(
                listOf(
                    "hand\thand\thand\tleft\tAlex\tclick",
                    "hand\thand\thand\tright\tAlex\tclick",
                    "arm\thand\tarm",
                    "centity\thand\ttrue\tclick",
                    "module\trobot\thand\tcentity_click\tnil"
                ),
                server.logs
            )
        }
    }

    @Test
    fun `a click knows where the line of sight met the hitbox`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to """
                    this:node("root"):on("click", function(event)
                      log(tostring(event.hit_position), tostring(event.hit_normal))
                    end)
                """
            )
        ).use { server ->
            val c = server.spawn("c", Location("world", 0.0, 64.0, 0.0))
            val hitbox = server.platform.entities.hitbox(c.id, "root")
            val alex = server.player()
            // From the north, looking south at the middle of its north face.
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, Ray("world", 0.5, 64.5, -2.0, 0.0, 0.0, 1.0))
            // From above, looking down.
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, Ray("world", 0.25, 66.0, 0.75, 0.0, -1.0, 0.0))
            // No line of sight to go by.
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, null)
            assertEquals(
                listOf("vec3(0.5, 64.5, 0)\tvec3(0, 0, -1)", "vec3(0.25, 65, 0.75)\tvec3(0, 1, 0)", "nil\tnil"),
                server.logs
            )
        }
    }

    @Test
    fun `a raycast hitbox ignores clicks through its padding`() {
        val centity = """
            {
              "nodes": {
                "root": {
                  "hitbox": { "boxes": [{ "min": [0, 0, 0], "max": [1, 0.25, 1] }, { "min": [0, 0.75, 0], "max": [1, 1, 1] }] }
                }
              },
              "script": { "file": "script.lua" }
            }
        """
        TestServer(
            mapOf(
                "centities/shelf/centity.json" to centity,
                "centities/shelf/script.lua" to "this:node('root'):on('click', function() log('clicked') end)"
            )
        ).use { server ->
            val shelf = server.spawn("shelf", Location("world", 0.0, 64.0, 0.0))
            val hitbox = server.platform.entities.hitbox(shelf.id, "root")
            assertEquals(1.0, hitbox.height, 1e-9)
            val alex = server.player()
            // Straight through the gap between the two shelves: nothing.
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, Ray("world", 0.5, 64.5, -2.0, 0.0, 0.0, 1.0))
            assertEquals(emptyList(), server.logs)
            // Into the top shelf.
            server.runtime.events.entityClicked(hitbox.id, alex.ref, ClickButton.RIGHT, Ray("world", 0.5, 64.9, -2.0, 0.0, 0.0, 1.0))
            assertEquals(listOf("clicked"), server.logs)
        }
    }

    @Test
    fun `playing an animation the centity doesn't have is an error at the call`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to "local animation = \"spn\"\nthis:play_animation(animation)\n"
            )
        ).use { server ->
            server.spawn("c")
            val error = server.errors.single()
            assertEquals("centities/c/script.lua", error.source?.file)
            assertEquals(2, error.source?.line)
            assertTrue("c has no animation \"spn\"" in error.message, error.message)
        }
    }

    @Test
    fun `moving a centity moves every entity with it`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val tower = server.spawn("tower")
            server.runtime.session.centities.teleport(tower, Location("world", 100.0, 70.0, 100.0))
            val entities = server.platform.entities.of(tower.id)
            assertTrue(entities.filter { it.role == EntityRole.DISPLAY }.all { it.location == Location("world", 100.0, 70.0, 100.0) })
            assertTrue(entities.filter { it.role == EntityRole.HITBOX }.all { it.location.x in 100.0..101.0 })
        }
    }

    @Test
    fun `nothing is pushed for an instance that didn't change`() {
        TestServer(TestServer.example("showcase")).use { server ->
            // The wisp: nothing animates it and no script moves it (the tower's flag flutters by itself).
            val tower = server.spawn("wisp")
            server.tick()
            val before = server.platform.entities.of(tower.id).sumOf { it.poses }
            server.tick(20)
            assertEquals(before, server.platform.entities.of(tower.id).sumOf { it.poses })
        }
    }
}
