package dev.netherforge.plugin.centity
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `nf.centities.spawn`'s `temporary` and `lifetime`, `is_temporary`, `lifetime`, `set_lifetime` and `keep`. */
class TemporaryCentityTest {
    private val shot = """
        { "nodes": { "root": { "display": { "type": "block", "block": "minecraft:stone" } } }, "script": { "file": "script.lua" } }
    """

    private fun files(module: String) = mapOf(
        "centities/shot/centity.json" to shot,
        "centities/shot/script.lua" to """this:on("remove", function() log("removed " .. tostring(this:is_temporary())) end)""",
        "modules/guns/init.lua" to module
    )

    private fun TestServer.centities() = runtime.session.centities.all().filter { !it.removed }

    @Test
    fun `a temporary centity is never saved and goes with the script that spawned it`() {
        TestServer(
            files(
                """
                local shot = nf.centities.spawn("shot", vec3(0, 64, 0), { temporary = true })
                log("spawned", tostring(shot:is_temporary()), tostring(shot:is_natural()), tostring(shot:lifetime()))
                nf.centities.spawn("shot", vec3(4, 64, 0))
                """
            )
        ).use { server ->
            assertEquals(listOf("spawned\ttrue\tfalse\tnil"), server.logs)
            val (temporary, normal) = server.centities().sortedBy { it.anchor.x }
            assertTrue(temporary.temporary)
            assertTrue(server.platform.entities.of(temporary.id).all { !it.persistent }, "its entities aren't kept with the chunk")
            assertTrue(server.platform.entities.of(normal.id).all { it.persistent })
            server.tick(2)
            server.runtime.saveData()
            assertEquals(listOf(normal.id), server.runtime.store.instances.all().map { it.id }, "only the normal one is in the store")

            // The module reloads: its temporary shot goes (its handlers hear it), the normal one stays.
            server.write("modules/guns/init.lua", "")
            server.reload("modules/guns/init.lua")
            assertEquals(listOf(normal.id), server.centities().map { it.id })
            assertTrue("removed true" in server.logs, "${server.logs}")
        }
    }

    @Test
    fun `a lifetime removes it that many ticks later, loaded or not`() {
        TestServer(
            files(
                """
                nf.commands.register("fire", function()
                  local shot = nf.centities.spawn("shot", vec3(0, 64, 0), { lifetime = 5 })
                  log("left", tostring(shot:lifetime()), tostring(shot:is_temporary()))
                  local far = nf.centities.spawn("shot", vec3(5000, 64, 0), { lifetime = 3 })
                  nf.after(2, function() log("still", tostring(shot:lifetime())) end)
                end)
                """
            )
        ).use { server ->
            server.platform.worlds.unloaded += Triple("world", 312, 0)
            server.platform.commands.runConsole("fire")
            server.tick(2)
            assertEquals(listOf("left\t5\ttrue", "still\t3"), server.logs.take(2))
            assertEquals(2, server.centities().size)
            server.tick(1)
            assertEquals(1, server.centities().size, "the one in an unloaded chunk went too")
            server.tick(2)
            assertEquals(emptyList(), server.centities())
        }
    }

    @Test
    fun `keep makes one normal, and set_lifetime makes a normal one temporary`() {
        TestServer(
            files(
                """
                nf.commands.register("go", function()
                  local a = nf.centities.spawn("shot", vec3(0, 64, 0), { temporary = true })
                  log("kept", tostring(a:keep()), tostring(a:is_temporary()), tostring(a:keep()))
                  local b = nf.centities.spawn("shot", vec3(2, 64, 0))
                  log("set", tostring(b:set_lifetime(4)), tostring(b:is_temporary()), tostring(b:lifetime()))
                  local ok = pcall(function() b:set_lifetime(0) end)
                  log("zero", tostring(ok))
                end)
                """
            )
        ).use { server ->
            server.platform.commands.runConsole("go")
            server.tick(1)
            server.runtime.saveData()
            assertEquals(listOf("kept\ttrue\tfalse\tfalse", "set\ttrue\ttrue\t4", "zero\tfalse"), server.logs.take(3))
            val (kept, timed) = server.centities().sortedBy { it.anchor.x }
            assertTrue(server.platform.entities.of(kept.id).all { it.persistent }, "kept with its chunk now")
            assertTrue(server.platform.entities.of(timed.id).all { !it.persistent }, "no longer kept with its chunk")
            assertEquals(listOf(kept.id), server.runtime.store.instances.all().map { it.id }, "the store forgot the timed one")
            server.tick(4)
            assertNull(server.runtime.session.centities.find(timed.id))
            assertNotNull(server.runtime.session.centities.find(kept.id))
        }
    }

    @Test
    fun `a restart or a full reload leaves nothing temporary behind`() {
        TestServer(files("""nf.centities.spawn("shot", vec3(0, 64, 0), { temporary = true })""")).use { server ->
            assertEquals(1, server.centities().size)
            server.restart()
            // The module's body ran again on the new start: one new shot, none left from before.
            assertEquals(1, server.centities().size)
            val before = server.centities().single().id
            server.runtime.reloadAll()
            val after = server.centities()
            assertEquals(1, after.size)
            assertFalse(after.single().id == before, "the old one went with its script")
        }
    }

    @Test
    fun `a lifetime must be at least a tick`() {
        val result = LuaChecks.run(
            """
            fails("zero", function() nf.centities.spawn("shot", vec3(0, 64, 0), { lifetime = 0 }) end, "at least 1")
            fails("a key", function() nf.centities.spawn("shot", vec3(0, 64, 0), { temp = true }) end, "temp")
            """,
            files("")
        )
        assertEquals(listOf("done"), result)
    }
}
