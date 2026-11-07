package dev.netherforge.plugin.contract

import dev.netherforge.format.bridge.BotAction
import dev.netherforge.format.centity.TextDisplay
import dev.netherforge.format.math.Matrix4
import dev.netherforge.plugin.platform.ClickButton
import dev.netherforge.plugin.platform.ClientEntityOps
import dev.netherforge.plugin.platform.DisplayPose
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.PlayerRef
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ClientEntityOps]: entities only their viewers' clients have. What a client has is what its bot was sent
 * (`BotState.entities`), so these check the packets, not the runtime's records.
 */
abstract class ClientEntityOpsContract : PlatformContract() {
    private val clients: ClientEntityOps get() = platform.clientEntities
    private val pose = DisplayPose(Matrix4(), 0, 2.0)

    private fun tag(node: String, role: EntityRole) = EntityTag(INSTANCE, node, role)

    /** A client entity, removed when the test ends. */
    private fun made(id: UUID?): UUID {
        assertNotNull(id, "the entity was made")
        afterwards { main { clients.remove(id) } }
        return id
    }

    private fun box(): UUID = main { made(clients.spawnHitbox(at(2), 1.0, 1.0, tag("box", EntityRole.HITBOX))) }

    private fun has(player: PlayerRef, id: UUID) = screen(player).entities.any { it.uuid == id.toString() }

    @Test
    fun `one is in no world, and only its viewers are sent it`() {
        val viewer = join()
        val other = join()
        val box = box()
        main {
            assertTrue(clients.isLoaded(box))
            assertNull(platform.worldEntities.info(box), "no world has it")
            assertTrue(platform.worldEntities.list(world).none { it.id == box })
            assertEquals(emptyMap(), clients.loadedTagged(), "nothing of these is saved, so there's nothing to clean up")
            assertEquals(emptyList(), clients.structureMarkers())
        }
        settled()
        assertFalse(has(viewer, box), "shown to nobody until it has viewers")
        main { clients.setViewers(box, setOf(viewer.uuid)) }
        eventually("sent to its viewer") { has(viewer, box) }
        settled()
        assertFalse(has(other, box))
    }

    @Test
    fun `a viewer's click on its hitbox is heard as a click on it, with either button`() {
        val player = join()
        val box = box()
        main { clients.setViewers(box, setOf(player.uuid)) }
        eventually("sent to them") { has(player, box) }
        settled()
        act(player, BotAction.Interact(entity = box.toString()))
        act(player, BotAction.Attack(entity = box.toString()))
        eventually("both clicks heard") { events.of("entityClicked").size == 2 }
        val clicks = events.of("entityClicked")
        assertEquals(listOf<Any?>(box to ClickButton.RIGHT, box to ClickButton.LEFT), clicks.map { it[0] to it[2] })
        assertTrue(clicks.all { it[1] == player && it[3] != null }, "by them, with where they looked: $clicks")
    }

    @Test
    fun `a viewer taken off forgets it, and a removed one is gone from every client`() {
        val one = join()
        val two = join()
        val box = box()
        main { clients.setViewers(box, setOf(one.uuid, two.uuid)) }
        eventually("sent to both") { has(one, box) && has(two, box) }
        main { clients.setHidden(two.uuid, box, true) }
        eventually("the one taken off forgets it") { !has(two, box) }
        assertTrue(has(one, box))
        main {
            assertTrue(clients.remove(box))
            assertFalse(clients.isLoaded(box))
            assertFalse(clients.remove(box), "only once")
        }
        eventually("gone") { !has(one, box) }
    }

    @Test
    fun `a viewer who comes back is sent it again`() {
        val viewer = join()
        val box = box()
        main { clients.setViewers(box, setOf(viewer.uuid)) }
        eventually("sent") { has(viewer, box) }
        leave(viewer)
        val again = join(name = viewer.name)
        assertEquals(viewer.uuid, again.uuid)
        eventually("sent again") { has(again, box) }
    }

    @Test
    fun `riders go where their vehicle goes, and only these carry one`() {
        val (anchor, rider) = main {
            val anchor = made(clients.spawnAnchor(at(3, 2), tag("", EntityRole.ANCHOR)))
            val rider = made(clients.spawnDisplay(at(3, 2), TextDisplay("hi"), pose, tag("rider", EntityRole.DISPLAY)))
            assertTrue(clients.mount(anchor, rider))
            assertTrue(clients.mount(anchor, rider), "already on it")
            assertFalse(clients.mount(anchor, UUID.randomUUID()))
            assertFalse(clients.mount(rider, anchor), "not round in a circle")
            anchor to rider
        }
        val pig = spawn("minecraft:pig", at(5), ai = false)
        main {
            assertFalse(clients.mount(pig, rider), "the world's entities carry none of these")
            clients.teleport(anchor, at(6, 2))
            assertEquals(at(6, 2).x, clients.location(rider)?.x)
            clients.teleport(rider, at(0, 2))
            assertEquals(at(6, 2).x, clients.location(rider)?.x, "a rider told to move alone stays on")
            clients.dismount(rider)
            clients.teleport(rider, at(0, 2))
            assertEquals(at(0, 2).x, clients.location(rider)?.x)
        }
    }

    private companion object {
        val INSTANCE: UUID = UUID.fromString("00000000-0000-4000-8000-00000000c11e")
    }
}
