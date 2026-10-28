package dev.netherforge.plugin.centity
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.Location
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A centity following a player: its anchor entity rides them, so the client
 * moves its displays with them, and it turns with their body. The anchor is
 * never teleported while it rides them: that would get it off.
 */
class CentityFollowTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    private val lantern = """
        {
          "nodes": {
            "body": { "display": { "type": "block", "block": "minecraft:stone" } }
          },
          "script": { "file": "script.lua" }
        }
    """

    private fun server() = TestServer(mapOf("centities/lantern/centity.json" to lantern, "centities/lantern/script.lua" to ""))

    @Test
    fun `following mounts the anchor entity on the player and puts the centity at them`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()
            val player = server.platform.players.add("alex", at.copy(x = 5.0))
            val anchor = server.platform.entities.anchor(instance.id)

            assertTrue(centities.follow(instance, player.ref.uuid))
            assertEquals(player.ref.uuid, anchor.vehicle)
            assertEquals(at.copy(x = 5.0), instance.anchor)
        }
    }

    @Test
    fun `a followed centity moves and turns with its player, its anchor entity never teleported`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()
            val player = server.platform.players.add("alex", at)
            centities.follow(instance, player.ref.uuid)
            val anchor = server.platform.entities.anchor(instance.id)
            val teleports = anchor.teleports

            server.platform.players.move(player, at.copy(x = 8.0, z = 2.0))
            player.bodyYaw = 90.0
            server.tick()

            assertEquals(at.copy(x = 8.0, z = 2.0), instance.anchor)
            assertEquals(90.0, instance.yaw)
            assertEquals(teleports, anchor.teleports)
            assertEquals(player.ref.uuid, anchor.vehicle)
        }
    }

    @Test
    fun `it turns with the player's body, not their look`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()
            val player = server.platform.players.add("alex", at)
            player.bodyYaw = 30.0
            centities.follow(instance, player.ref.uuid)
            assertEquals(30.0, instance.yaw)

            // They look far to the side; the body hasn't followed their head yet.
            server.platform.players.move(player, at.copy(yaw = 120.0))
            server.tick()
            assertEquals(30.0, instance.yaw)
        }
    }

    @Test
    fun `stop_following gets the anchor off the player, where it is`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()
            val player = server.platform.players.add("alex", at)
            centities.follow(instance, player.ref.uuid)
            val anchor = server.platform.entities.anchor(instance.id)

            server.platform.players.move(player, at.copy(x = 8.0))
            server.tick()
            assertTrue(centities.unfollow(instance))
            assertNull(anchor.vehicle)
            assertFalse(centities.unfollow(instance))

            server.platform.players.move(player, at.copy(x = 20.0))
            server.tick()
            assertNull(anchor.vehicle)
            assertEquals(at.copy(x = 8.0), instance.anchor)
        }
    }

    @Test
    fun `a teleport ends a follow`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()
            val player = server.platform.players.add("alex", at)
            centities.follow(instance, player.ref.uuid)
            val anchor = server.platform.entities.anchor(instance.id)

            centities.teleport(instance, at.copy(z = 9.0))
            server.tick()

            assertNull(anchor.vehicle)
            assertEquals(at.copy(z = 9.0), instance.anchor)
        }
    }

    @Test
    fun `a player who isn't online can't be followed`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()

            assertFalse(centities.follow(instance, UUID.randomUUID()))
            assertNull(server.platform.entities.anchor(instance.id).vehicle)
        }
    }

    @Test
    fun `a mounted follower is drawn where it is, not at the height its rider sits`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()
            val player = server.platform.players.add("alex", at)
            centities.follow(instance, player.ref.uuid)
            server.tick()

            val body = server.platform.entities.of(instance.id).first { it.role == EntityRole.DISPLAY }
            assertEquals(-server.platform.entities.ridingHeight, body.pose!!.matrix.translation().y, 1e-6)
        }
    }

    @Test
    fun `sneaking lowers the rider, and the displays follow it down`() {
        server().use { server ->
            val centities = server.runtime.session.centities
            val instance = assertNotNull(centities.spawn("lantern", at))
            server.tick()
            val player = server.platform.players.add("alex", at)
            centities.follow(instance, player.ref.uuid)
            server.tick()

            server.platform.entities.ridingHeight = 1.5
            server.tick()

            val body = server.platform.entities.of(instance.id).first { it.role == EntityRole.DISPLAY }
            assertEquals(-1.5, body.pose!!.matrix.translation().y, 1e-6)
        }
    }
}
