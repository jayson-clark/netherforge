package dev.netherforge.plugin.structure
import dev.netherforge.format.bridge.Log
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Centities in structures: the markers a template holds become the centities
 * they name, on the main thread, once and only once.
 */
class StructureSpawnsTest {
    private val project = mapOf(
        "centities/guard/centity.json" to TestServer.scriptedCentity(),
        "centities/guard/script.lua" to "this:on(\"spawn\", function() log(\"guard\") end)",
        "structures/ruins.nbt" to "size 1 1 1\n0 0 0 minecraft:stone"
    )

    private val at = Location("world", 100.5, 64.0, -20.5, yaw = 90.0)

    private fun TestServer.centities() = runtime.session.centities.all()

    private fun TestServer.warnings() = sent.filterIsInstance<Log>().filter { it.source == null }.map { it.message }

    @Test
    fun `a generated structure's marker becomes its centity where it stood, and the marker goes`() {
        TestServer(project).use { server ->
            val marker = server.platform.entities.placeMarker(at, "guard")
            server.runtime.events.structureMarkersLoaded(listOf(marker))
            val guard = server.centities().single()
            assertEquals("guard", guard.definition.id)
            assertEquals(at.copy(yaw = 0.0), guard.anchor)
            assertEquals(90.0, guard.yaw)
            assertTrue(server.platform.entities.markers.isEmpty(), "taken, so it can't spawn again")
            assertEquals(listOf("guard"), server.logs, "its script's spawn ran")
        }
    }

    @Test
    fun `a marker is spawned from once across a restart and a chunk loading again`() {
        TestServer(project).use { server ->
            val marker = server.platform.entities.placeMarker(at, "guard")
            server.runtime.events.structureMarkersLoaded(listOf(marker))
            val guard = server.centities().single()

            // The chunk loads again with what it saved: the marker is gone, so nothing more spawns.
            server.runtime.events.structureMarkersLoaded(server.platform.entities.structureMarkers())
            server.restart()
            server.runtime.events.structureMarkersLoaded(server.platform.entities.structureMarkers())
            assertEquals(listOf(guard.id), server.centities().map { it.id })
        }
    }

    @Test
    fun `markers left while the plugin wasn't running spawn when it starts`() {
        TestServer(project).use { server ->
            server.platform.entities.placeMarker(at, "guard")
            server.platform.entities.placeMarker(at.offset(5.0, 0.0, 0.0), "guard")
            server.restart()
            assertEquals(2, server.centities().size)
            assertTrue(server.platform.entities.markers.isEmpty())
        }
    }

    @Test
    fun `a marker for a centity the project lacks stays, says so once, and spawns when the centity comes`() {
        TestServer(project).use { server ->
            val marker = server.platform.entities.placeMarker(at, "late")
            server.runtime.events.structureMarkersLoaded(listOf(marker))
            server.runtime.events.structureMarkersLoaded(listOf(marker))
            assertEquals(emptyList(), server.centities())
            assertEquals(listOf(marker.id), server.platform.entities.markers.keys.toList())
            assertEquals(1, server.warnings().count { "\"late\"" in it }, "once")

            server.write("centities/late/centity.json", TestServer.scriptedCentity())
            server.write("centities/late/script.lua", "")
            server.reload("centities/late/centity.json")
            assertEquals(listOf("late"), server.centities().map { it.definition.id })
            assertTrue(server.platform.entities.markers.isEmpty())
        }
    }

    @Test
    fun `a marker in a chunk that can't be reached stays for when it can`() {
        TestServer(project).use { server ->
            val marker = server.platform.entities.placeMarker(at, "guard")
            val chunk = server.platform.worlds.chunkOf(at)
            server.platform.worlds.unloaded += chunk
            server.runtime.events.structureMarkersLoaded(listOf(marker))
            assertEquals(emptyList(), server.centities())

            server.platform.worlds.unloaded -= chunk
            server.runtime.events.structureMarkersLoaded(server.platform.entities.structureMarkers())
            assertEquals(1, server.centities().size)
        }
    }

    @Test
    fun `a package's centity is named with its namespace, the project's own without`() {
        TestServer(project).use { server ->
            val own = server.platform.entities.placeMarker(at, "test:guard")
            server.runtime.events.structureMarkersLoaded(listOf(own))
            assertEquals(listOf("guard"), server.centities().map { it.definition.id })
        }
    }

    @Test
    fun `placing a structure with its entities spawns what its markers ask for`() {
        TestServer(
            project + mapOf(
                "modules/t/init.lua" to
                    """
                    nf.worlds.default():place_structure("ruins", vec3(0, 64, 0), { entities = true })
                    log("done")
                    """
            )
        ).use { server ->
            server.platform.entities.placeMarker(at, "guard")
            server.reload("modules/t/init.lua")
            assertEquals(1, server.centities().size)
            assertTrue(server.platform.entities.markers.isEmpty())
        }
    }
}
