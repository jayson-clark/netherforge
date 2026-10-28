package dev.netherforge.plugin.centity
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Every display rides the instance's anchor entity, and so does a hitbox
 * sitting at the anchor, so moving a centity is one teleport. A hitbox off
 * the anchor stands on its own.
 */
class CentityRidingTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    /** A body with a centred hitbox at the anchor, a head above it with one of its own, and an arm off to the side. */
    private val mob = """
        {
          "nodes": {
            "body": {
              "display": { "type": "block", "block": "minecraft:stone" },
              "hitbox": { "boxes": [{ "min": [-0.5, 0, -0.5], "max": [0.5, 1, 0.5] }] }
            },
            "head": {
              "parent": "body",
              "transform": { "translation": [0, 1, 0] },
              "display": { "type": "block", "block": "minecraft:stone" },
              "hitbox": { "boxes": [{ "min": [-0.25, 0, -0.25], "max": [0.25, 0.5, 0.25] }] }
            },
            "arm": { "parent": "body", "transform": { "translation": [1, 0.5, 0] }, "display": { "type": "block", "block": "minecraft:stone" } }
          },
          "script": { "file": "script.lua" }
        }
    """

    private fun server(script: String = "") = TestServer(mapOf("centities/mob/centity.json" to mob, "centities/mob/script.lua" to script))

    private fun TestServer.spawn() = assertNotNull(runtime.session.centities.spawn("mob", at))

    @Test
    fun `displays and a hitbox at the anchor ride the anchor entity, a hitbox off it doesn't`() {
        server().use { server ->
            val instance = server.spawn()
            val entities = server.platform.entities
            val anchor = entities.anchor(instance.id)
            assertEquals(anchor.id, instance.anchorEntity)
            assertEquals(at, anchor.location)
            for (node in listOf("body", "head", "arm")) assertEquals(anchor.id, entities.display(instance.id, node).vehicle, node)
            assertEquals(anchor.id, entities.hitbox(instance.id, "body").vehicle, "centred at the anchor")
            assertNull(entities.hitbox(instance.id, "head").vehicle, "a block up: on its own")
        }
    }

    @Test
    fun `moving a centity teleports the anchor entity and the hitboxes off it, nothing else`() {
        server().use { server ->
            val instance = server.spawn()
            server.tick()
            val entities = server.platform.entities
            val before = entities.of(instance.id).associate { it.id to it.teleports }
            val to = at.copy(x = 10.0)
            server.runtime.session.centities.teleport(instance, to)
            val moved = entities.of(instance.id).filter { it.teleports != before[it.id] }.map { it.role to it.tag.node }.toSet()
            assertEquals(setOf(EntityRole.ANCHOR to "", EntityRole.HITBOX to "head"), moved)
            // The riders came along.
            assertEquals(to, entities.display(instance.id, "arm").location)
            assertEquals(to, entities.hitbox(instance.id, "body").location)
            assertEquals(to.offset(0.0, 1.0, 0.0), entities.hitbox(instance.id, "head").location)
        }
    }

    @Test
    fun `a hitbox moved off the anchor gets off, and back on when it returns`() {
        server(
            """
            local body = this:node("body")
            nf.after(1, function() body:set_translation(vec3(0, 0.5, 0)) end)
            nf.after(3, function() body:set_translation(vec3(0, 0, 0)) end)
            """
        ).use { server ->
            val instance = server.spawn()
            val entities = server.platform.entities
            val hitbox = entities.hitbox(instance.id, "body")
            server.tick(2)
            assertNull(hitbox.vehicle)
            assertEquals(at.offset(0.0, 0.5, 0.0), hitbox.location)
            server.tick(2)
            assertEquals(instance.anchorEntity, hitbox.vehicle)
            assertEquals(at, hitbox.location)
        }
    }

    @Test
    fun `an anchor entity killed by something else comes back and picks its riders up`() {
        server().use { server ->
            val instance = server.spawn()
            server.tick()
            val entities = server.platform.entities
            val killed = entities.anchor(instance.id).id
            entities.remove(killed)
            server.tick()
            val anchor = entities.anchor(instance.id)
            assertNotEquals(killed, anchor.id)
            assertTrue(entities.passengers(anchor.id).map { it.tag.node }.containsAll(listOf("body", "head", "arm")))
        }
    }

    @Test
    fun `the anchor entity comes back after a restart, and isn't spawned again`() {
        server().use { server ->
            val instance = server.spawn()
            server.tick()
            val anchor = instance.anchorEntity
            val spawned = server.platform.entities.spawned
            server.restart()
            server.tick()
            val back = server.runtime.session.centities.all().single()
            assertEquals(anchor, back.anchorEntity)
            assertEquals(spawned, server.platform.entities.spawned)
            server.runtime.session.centities.teleport(back, at.copy(z = 5.0))
            assertEquals(at.copy(z = 5.0), server.platform.entities.display(back.id, "body").location)
        }
    }

    @Test
    fun `hiding a centity hides its anchor entity too, and removing it removes it`() {
        server().use { server ->
            val instance = server.spawn()
            val player = server.platform.players.add("alex", at)
            server.runtime.session.centities.setHidden(instance, player.ref.uuid, true)
            assertTrue(player.ref.uuid in server.platform.entities.anchor(instance.id).hiddenFrom)
            server.runtime.session.centities.remove(instance)
            assertTrue(server.platform.entities.anchors.isEmpty())
        }
    }
}
