package dev.netherforge.plugin.centity
import dev.netherforge.format.centity.BlockDisplay
import dev.netherforge.format.math.Vec3
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Nodes a script adds to a running centity (`add_node`, `add_child`, `Node:remove`). */
class CentityNodesTest {
    private val at = Location("world", 10.0, 64.0, 10.0)

    private fun scripted(script: String, extra: String = "") = mapOf(
        "centities/c/centity.json" to TestServer.scriptedCentity(extra = extra),
        "centities/c/script.lua" to script
    )

    private fun TestServer.spawn() = assertNotNull(runtime.session.centities.spawn("c", at))

    private fun TestServer.nodes(id: java.util.UUID, role: EntityRole) =
        platform.entities.of(id).filter { it.role == role }.map { it.tag.node }.toSet()

    @Test
    fun `an added node gets a display and a hitbox, and follows its parent`() {
        TestServer(
            scripted(
                """
                local lamp = this:add_node("lamp", {
                  transform = { translation = vec3(0, 2, 0) },
                  display = { type = "block", block = "minecraft:sea_lantern" },
                  hitbox = {},
                })
                local glass = lamp:add_child("glass", {
                  transform = { translation = vec3(0, 1, 0), scale = vec3(2, 2, 2) },
                  display = { type = "text", text = "<red>hi", billboard = "fixed" },
                })
                log(lamp:name(), glass:parent() == lamp, #lamp:children(), #this:nodes(), #this:roots())
                lamp:set_translation(vec3(0, 3, 0))
                log(glass:world_position())
                """
            )
        ).use { server ->
            val instance = server.spawn()
            assertEquals(listOf("lamp\ttrue\t1\t3\t2", "vec3(10, 68, 10)"), server.logs)
            server.tick()
            assertEquals(setOf("root", "lamp", "glass"), server.nodes(instance.id, EntityRole.DISPLAY))
            assertEquals(setOf("root", "lamp"), server.nodes(instance.id, EntityRole.HITBOX))
            val entities = server.platform.entities
            assertEquals("minecraft:sea_lantern", (entities.display(instance.id, "lamp").display as BlockDisplay).block)
            assertEquals(Vec3(0.0, 3.0, 0.0), entities.display(instance.id, "lamp").pose!!.matrix.translation())
            assertEquals(Vec3(0.0, 4.0, 0.0), entities.display(instance.id, "glass").pose!!.matrix.translation())
            assertEquals(2.0, entities.display(instance.id, "glass").pose!!.matrix.scale().x)

            // The parent turns, and the child with it.
            instance.set(instance.indexOf("lamp")!!, dev.netherforge.format.centity.Channel.ROTATION, Vec3(0.0, 0.0, 90.0))
            server.tick()
            assertEquals(-1.0, entities.display(instance.id, "glass").pose!!.matrix.translation().x, 1e-9)
        }
    }

    @Test
    fun `a click on an added node's hitbox bubbles through its parents`() {
        TestServer(
            scripted(
                """
                local lamp = this:add_node("lamp", { display = { type = "block", block = "minecraft:stone" } })
                local button = lamp:add_child("button", { hitbox = {} })
                button:on("click", function(event) log("button", event.target:name()) end)
                lamp:on("click", function(event) log("lamp", event.target:name()) end)
                this:on("click", function(event) log("centity", event.target:name()) end)
                """
            )
        ).use { server ->
            val instance = server.spawn()
            server.tick()
            val hitbox = server.platform.entities.hitbox(instance.id, "button")
            server.sent.clear()
            assertTrue(server.runtime.events.entityClicked(hitbox.id, server.player().ref, ClickButton.RIGHT, null))
            assertEquals(listOf("button\tbutton", "lamp\tbutton", "centity\tbutton"), server.logs)
        }
    }

    @Test
    fun `an added node with physics falls and rests like a declared one`() {
        TestServer(
            scripted(
                """
                this:add_node("ball", {
                  transform = { translation = vec3(5, 3, 0) },
                  display = { type = "block", block = "minecraft:stone" },
                  hitbox = {},
                  physics = {},
                })
                """
            )
        ).use { server ->
            val instance = server.spawn()
            server.tick(10)
            val index = instance.indexOf("ball")!!
            assertTrue(instance.get(index, dev.netherforge.format.centity.Channel.TRANSLATION).y < 3.0, "falling")
            server.tick(100)
            assertEquals(0.0, instance.get(index, dev.netherforge.format.centity.Channel.TRANSLATION).y, 0.02)
            assertTrue(instance.body(index)!!.asleep)
            assertEquals(0.0, server.platform.entities.display(instance.id, "ball").pose!!.matrix.translation().y, 0.02)
        }
    }

    @Test
    fun `removing a node takes its subtree, handlers and entities, and only added nodes can go`() {
        TestServer(
            scripted(
                """
                local lamp = this:add_node("lamp", { display = { type = "block", block = "minecraft:stone" }, hitbox = {} })
                local glass = lamp:add_child("glass", { display = { type = "block", block = "minecraft:glass" } })
                local other = this:add_node("other", { display = { type = "block", block = "minecraft:oak_slab" } })
                lamp:on("click", function() log("never") end)
                this:on("click", function()
                  log(pcall(this:node("root").remove, this:node("root")))
                  log(lamp:remove(), lamp:remove(), glass:translation(), glass:parent(), tostring(lamp:add_child("x")))
                  log(#this:nodes(), other:translation())
                  other:set_translation(vec3(0, 5, 0))
                end)
                """
            )
        ).use { server ->
            val instance = server.spawn()
            server.tick()
            assertEquals(setOf("root", "lamp", "glass", "other"), server.nodes(instance.id, EntityRole.DISPLAY))
            server.runtime.events.entityClicked(
                server.platform.entities.hitbox(instance.id, "root").id,
                server.player().ref,
                ClickButton.RIGHT,
                null
            )
            val logs = server.logs
            assertTrue(logs[0].startsWith("false") && "is declared by centity.json" in logs[0], logs[0])
            // Removed (and again): true then false; every handle of the subtree answers nil.
            assertEquals("true\tfalse\tnil\tnil\tnil", logs[1])
            assertEquals("2\tvec3(0, 0, 0)", logs[2])

            server.tick()
            assertEquals(setOf("root", "other"), server.nodes(instance.id, EntityRole.DISPLAY))
            assertEquals(setOf("root"), server.nodes(instance.id, EntityRole.HITBOX))
            // The survivor moved to a new index and still answers to its name.
            assertEquals(Vec3(0.0, 5.0, 0.0), server.platform.entities.display(instance.id, "other").pose!!.matrix.translation())
            assertNull(instance.indexOf("lamp"))
        }
    }

    @Test
    fun `removing the centity removes its added nodes' entities`() {
        TestServer(
            scripted("this:add_node('lamp', { display = { type = 'block', block = 'minecraft:stone' }, hitbox = {} })")
        ).use { server ->
            val instance = server.spawn()
            server.tick()
            assertTrue(server.platform.entities.of(instance.id).size >= 4)
            server.runtime.session.centities.remove(instance)
            assertTrue(server.platform.entities.of(instance.id).isEmpty())
        }
    }

    @Test
    fun `bad names and definitions are errors at the call`() {
        TestServer(
            scripted(
                """
                local function try(...)
                  local ok, err = pcall(...)
                  log(ok, ok or err:gsub("^.-:%d+: ", ""))
                end
                try(this.add_node, this, "root")
                try(this.add_node, this, "bad name")
                try(this.add_node, this, "a", { display = { type = "block", block = "minecraft:not_a_block" } })
                try(this.add_node, this, "b", { display = { type = "block", block = "minecraft:stone" }, colour = 1 })
                try(this.add_node, this, "c", { parent = "root" })
                try(this.add_node, this, "d", { hitbox = { boxes = { { min = vec3(0, 0, 0), max = vec3(0, 1, 1) } } } })
                try(this.add_node, this, "e", { hitbox = { shape = "collision" } })
                try(this.add_node, this, "f", { transform = { translation = 3 } })
                try(this.add_node, this, "g", { physics = { mass = -1 } })
                try(this.add_node, this, "h", { display = { type = "text", text = "x", line_width = 0 } })
                log(#this:nodes())
                """
            )
        ).use { server ->
            server.spawn()
            val logs = server.logs
            assertTrue("already has a node called \"root\"" in logs[0], logs[0])
            assertTrue("isn't a usable node name" in logs[1], logs[1])
            assertTrue("definition.display.block" in logs[2] && "not_a_block" in logs[2], logs[2])
            assertTrue("definition" in logs[3] && "colour" in logs[3], logs[3])
            assertTrue("definition.parent" in logs[4], logs[4])
            assertTrue("definition.hitbox.boxes[1]" in logs[5], logs[5])
            assertTrue("definition.hitbox.shape" in logs[6], logs[6])
            assertTrue(logs[7].startsWith("false"), logs.joinToString("|"))
            assertTrue("definition.physics.mass" in logs[8], logs[8])
            assertTrue("definition.display.line_width" in logs[9], logs[9])
            assertEquals("1", logs[10], "nothing was added")
        }
    }

    @Test
    fun `added nodes are runtime state, so a reload drops them and the script adds them again`() {
        TestServer(
            scripted(
                """
                this:add_node("lamp", { display = { type = "block", block = "minecraft:stone" }, hitbox = {} })
                this:on("click", function() this:add_node("late", {}) end)
                log("load", #this:nodes())
                """
            )
        ).use { server ->
            val instance = server.spawn()
            server.tick()
            val before = server.platform.entities.of(instance.id).map { it.id }.toSet()
            instance.let {
                server.runtime.session.centities.click(
                    server.platform.entities.hitbox(it.id, "root").id,
                    server.player().ref,
                    ClickButton.RIGHT,
                    null
                )
            }
            assertEquals(3, instance.nodeNames().size)

            server.write(
                "centities/c/script.lua",
                "this:add_node('lamp2', { display = { type = 'block', block = 'minecraft:oak_slab' } })\nlog('v2', #this:nodes())"
            )
            val result = server.reload("centities/c/script.lua")
            assertTrue(result.resources.single().ok)
            assertEquals(listOf("v2\t2"), server.logs.drop(1), server.logs.toString() + server.errors)
            server.tick()
            assertEquals(listOf("root", "lamp2"), instance.nodeNames())
            assertEquals(setOf("root", "lamp2"), server.nodes(instance.id, EntityRole.DISPLAY))
            assertEquals(setOf("root"), server.nodes(instance.id, EntityRole.HITBOX))
            assertTrue(before.any { it !in server.platform.entities.of(instance.id).map { e -> e.id } }, "the dropped node's entities went")

            // A reload of the centity file itself too.
            server.write("centities/c/centity.json", TestServer.scriptedCentity())
            server.reload("centities/c/centity.json")
            server.tick()
            assertEquals(listOf("root", "lamp2"), instance.nodeNames())
            assertFalse(instance.isAdded(0))
            assertTrue(instance.isAdded(1))
        }
    }

    @Test
    fun `added nodes aren't saved across a restart`() {
        TestServer(
            scripted(
                "if not this:data().seen then this:data().seen = true this:add_node('lamp', { display = { type = 'block', block = 'minecraft:stone' } }) end"
            )
        ).use { server ->
            val instance = server.spawn()
            server.tick()
            assertEquals(2, instance.nodeNames().size)
            server.restart()
            val back = server.runtime.session.centities.all().single()
            server.tick()
            // Not saved: the restarted instance has only the file's nodes (its script chose not to add again).
            assertEquals(listOf("root"), back.nodeNames())
            assertEquals(setOf("root"), server.nodes(back.id, EntityRole.DISPLAY))
        }
    }
}
