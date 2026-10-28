package dev.netherforge.plugin.centity
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.EntityRole
import dev.netherforge.plugin.platform.EntityTag
import dev.netherforge.plugin.platform.Location
import dev.netherforge.plugin.store.Store
import dev.netherforge.plugin.testkit.FakeEntity
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PersistenceTest {
    private val at = Location("world", 0.0, 64.0, 0.0)

    private val project = mapOf(
        "centities/c/centity.json" to TestServer.scriptedCentity(),
        "centities/c/script.lua" to """
            log("load")
            this:on("spawn", function() log("spawn") end)
            this:on("tick", function() log("tick") end)
        """
    )

    @Test
    fun `spawned centities come back after a restart with the same entities`() {
        TestServer(project).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            server.tick()
            val spawned = server.platform.entities.spawned

            server.restart()
            val back = server.runtime.session.centities.all().single()
            assertEquals(instance.id, back.id)
            assertEquals(at, back.anchor)
            server.tick()
            assertEquals(spawned, server.platform.entities.spawned, "reattached, not respawned")
            assertEquals(listOf("load", "spawn", "tick", "load", "tick"), server.logs)
        }
    }

    @Test
    fun `an instance in an unloaded chunk waits, then catches up with edits made meanwhile`() {
        TestServer(project).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            val chunk = server.platform.worlds.chunkOf(at)
            server.platform.worlds.unloaded += chunk
            server.tick(3)
            assertEquals(listOf("load", "spawn"), server.logs, "no ticks while unloaded")

            server.write(
                "centities/c/centity.json",
                TestServer.scriptedCentity(
                    extra = """, "lamp": { "parent": "root", "display": { "type": "block", "block": "minecraft:sea_lantern" } }"""
                )
            )
            server.reload("centities/c/centity.json")
            assertFalse(server.platform.entities.of(instance.id).any { it.tag.node == "lamp" }, "can't spawn into an unloaded chunk")

            server.platform.worlds.unloaded -= chunk
            server.runtime.events.entitiesLoaded(server.platform.entities.of(instance.id).associate { it.id to it.tag }, emptyList())
            server.tick()
            assertTrue(server.platform.entities.of(instance.id).any { it.tag.node == "lamp" && it.role == EntityRole.DISPLAY })
        }
    }

    @Test
    fun `spawning where nothing is loaded still puts the entities there`() {
        TestServer(project).use { server ->
            server.platform.worlds.unloaded += server.platform.worlds.chunkOf(at)
            val instance = server.runtime.session.centities.spawn("c", at)!!
            assertEquals(3, server.platform.entities.of(instance.id).size, "an anchor, a display and a hitbox")
        }
    }

    @Test
    fun `entities killed by something else come back, and the instance keeps running`() {
        TestServer(project).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            server.tick()
            // `/kill`, another plugin: the entities are gone while their chunk is loaded.
            val killed = server.platform.entities.of(instance.id).map { it.id }.toSet()
            killed.forEach { server.platform.entities.all.remove(it) }

            server.tick(2)
            val now = server.platform.entities.of(instance.id)
            assertEquals(killed.size, now.size, "respawned")
            assertTrue(now.none { it.id in killed })
            assertEquals(listOf("load", "spawn", "tick", "tick", "tick"), server.logs, "still ticking")
        }
    }

    @Test
    fun `one instance failing to tick doesn't stop the others`() {
        TestServer(project).use { server ->
            val bad = server.runtime.session.centities.spawn("c", at)!!
            server.runtime.session.centities.spawn("c", at.copy(x = 100.0))!!
            server.platform.entities.broken += server.platform.entities.of(bad.id).map { it.id }
            // A pose change makes sync push to every display, the broken one included.
            server.runtime.session.centities.all().forEach { it.forceSync = true }

            server.tick(3)
            // Scripts run before sync, so both log; then the bad one's sync throws, every tick.
            assertEquals(6, server.logs.count { it == "tick" })
            val good = server.runtime.session.centities.all().single { it.id != bad.id }
            assertTrue(
                server.platform.entities.of(good.id).filter {
                    it.role == EntityRole.DISPLAY
                }.all { it.poses > 0 },
                "the good one still synced"
            )
            assertEquals(1, server.platform.log.lines.count { it.startsWith("ERROR Centity c") }, "reported once")
        }
    }

    @Test
    fun `tagged entities nobody owns are removed when they load`() {
        TestServer(project).use { server ->
            val kept = server.runtime.session.centities.spawn("c", at)!!
            val stray = UUID.randomUUID()
            val tag = EntityTag(UUID.randomUUID(), "root", EntityRole.DISPLAY)
            server.platform.entities.all[stray] = FakeEntity(stray, EntityRole.DISPLAY, null, at, tag)

            server.runtime.events.entitiesLoaded(
                mapOf(stray to tag) + server.platform.entities.of(kept.id).associate {
                    it.id to it.tag
                },
                emptyList()
            )
            assertFalse(stray in server.platform.entities.all)
            assertEquals(3, server.platform.entities.of(kept.id).size)
        }
    }

    @Test
    fun `a store that isn't a database is set aside, not overwritten`() {
        TestServer(project, start = false).use { server ->
            server.state.toFile().mkdirs()
            server.state.resolve(Store.FILE).toFile().writeText("{ not a database")
            server.start()
            server.runMain()
            assertEquals("{ not a database", server.state.resolve("${Store.FILE}.unreadable").toFile().readText())
            assertTrue(server.runtime.session.centities.all().isEmpty())
            assertTrue(server.platform.log.lines.any { "isn't a database NetherForge can read" in it }, "${server.platform.log.lines}")
            // The new one works.
            server.runtime.session.centities.spawn("c", at)!!
            server.restart()
            assertEquals(1, server.runtime.session.centities.all().size)
        }
    }

    private val turret = mapOf(
        "centities/turret/centity.json" to TestServer.scriptedCentity(),
        "centities/turret/script.lua" to """
            local degrees = 0
            this:on("tick", function()
              degrees = degrees + 7
              this:set_yaw(degrees)
            end)
        """
    )

    @Test
    fun `a turret aiming every tick writes nothing, until the world saves`() {
        TestServer(turret).use { server ->
            val instance = server.runtime.session.centities.spawn("turret", at)!!
            server.tick()
            server.runtime.store.flush()
            val written = server.runtime.store.written
            server.tick(100)
            server.runtime.store.flush()
            assertEquals(written, server.runtime.store.written, "turning is kept in memory")
            assertEquals(707.0 - 720.0, instance.yaw)

            // The world saves its entities, and the store gets the instance's facing as of then, once.
            server.runtime.events.worldSaving("world")
            server.tick()
            server.runtime.store.flush()
            assertEquals(written + 1, server.runtime.store.written)
            assertEquals(-13.0, server.runtime.store.instances.all().single().yaw, "as it faced when the world saved")
        }
    }

    @Test
    fun `a moved centity's place is saved when its chunk unloads and when the server stops`() {
        TestServer(project).use { server ->
            val instance = server.runtime.session.centities.spawn("c", at)!!
            server.tick()
            val far = at.copy(x = 100.0)
            server.runtime.session.centities.teleport(instance, far, 30.0)
            server.tick()
            assertEquals(at, server.runtime.store.instances.all().single().anchor, "not yet")

            server.runtime.session.chunkUnloading("world", 6, 0)
            server.tick()
            assertEquals(far, server.runtime.store.instances.all().single().anchor, "its chunk unloaded")

            server.runtime.session.centities.teleport(instance, at, 60.0)
            server.restart()
            val back = server.runtime.session.centities.all().single()
            assertEquals(at, back.anchor)
            assertEquals(60.0, back.yaw)
        }
    }

    @Test
    fun `instances name their centity in full, so a package's comes back as the package's`() {
        TestServer(project).use { server ->
            server.runtime.session.centities.spawn("c", at)!!
            server.tick()
            assertEquals("test:c", server.runtime.store.instances.all().single().centity)
            assertEquals("c", server.runtime.session.centities.info().single().centity)
        }
    }
}
