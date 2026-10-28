package dev.netherforge.plugin.lua
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.binding.LuaHandle
import dev.netherforge.plugin.script.ScopeOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Handles as Lua holds them: one opaque key into the host's handle table, a
 * table per thing (whatever class it is), the class the runtime knows it to
 * be, and nothing kept that could go stale.
 */
class HandleTest {
    private fun module(body: String) = mapOf(
        "modules/t/init.lua" to """
            ${LuaChecks.HELPERS}
            $body
        """.trimIndent()
    )

    @Test
    fun `a handle Lua lets go of leaves the handle table`() {
        TestServer(
            module(
                """
                nf.commands.register("blocks", function()
                  local world = nf.worlds.default()
                  for i = 1, 500 do
                    world:block(vec3(i, 0, 0))
                  end
                  log("made")
                end)
                kept = nf.worlds.default():block(vec3(0, 0, 0))
                """
            )
        ).use { server ->
            val host = server.runtime.session.scripts.host!!
            server.platform.commands.runConsole("blocks")
            assertEquals(listOf("made"), server.logs)
            assertTrue(host.handles.size >= 500, "${host.handles.size} handles")
            host.collectGarbage()
            host.sweepHandles()
            assertTrue(host.handles.size < 50, "${host.handles.size} handles left")
            // One still held is still there, and still the same table.
            val scope = server.runtime.session.scripts.scopes().single { it.owner is ScopeOwner.Module }.id
            val kept = host.at(listOf("kept"), scope) { call, index -> call.host.handleAt(call.lua, index) }
            assertEquals(LuaHandle.Block("world", (kept as LuaHandle.Block).position), kept)
            assertTrue(host.handles.find(kept) != null)
        }
    }

    @Test
    fun `a handle handed out while its entity was unloaded becomes the class it is once it's back`() {
        TestServer(
            module(
                """
                local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
                saved = nf.json.encode(pig)
                nf.commands.register("decode", function()
                  far = nf.json.decode(saved)
                  log(getmetatable(far), tostring(far.set_ai), tostring(far:is_mob()))
                end)
                nf.commands.register("use", function()
                  check("its own method", far:set_ai(false), true)
                  log(getmetatable(far), tostring(far:has_ai()), tostring(far:is_mob()))
                end)
                """
            )
        ).use { server ->
            val pig = server.platform.worldEntities.mobs.values.single()
            // Nothing holds the handle the module had (a held one would still know it's a Mob).
            val host = server.runtime.session.scripts.host!!
            host.collectGarbage()
            host.sweepHandles()
            assertTrue(host.handles.find(LuaHandle.Entity(pig.id.toString())) == null)
            // Its chunk unloads, and a script finds it by its saved UUID: only an Entity, for now.
            server.platform.worlds.unloaded += server.platform.worlds.chunkOf(pig.location)
            server.platform.commands.runConsole("decode")
            server.platform.worlds.unloaded.clear()
            // Back: a mob's method finds it's a Mob, and the same table is one from now on.
            server.platform.commands.runConsole("use")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("Entity\tnil\tfalse", "Mob\tfalse\ttrue"), server.logs)
        }
    }

    @Test
    fun `a value taken as a class below its handle's asks what it is now`() {
        TestServer(
            module(
                """
                local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
                saved = nf.json.encode(pig)
                nf.commands.register("decode", function()
                  far = nf.json.decode(saved)
                end)
                nf.commands.register("class", function()
                  log(getmetatable(far))
                end)
                """
            )
        ).use { server ->
            val pig = server.platform.worldEntities.mobs.values.single()
            val host = server.runtime.session.scripts.host!!
            host.collectGarbage()
            host.sweepHandles()
            server.platform.worlds.unloaded += server.platform.worlds.chunkOf(pig.location)
            server.platform.commands.runConsole("decode")
            server.platform.commands.runConsole("class")
            server.platform.worlds.unloaded.clear()
            val scope = server.runtime.session.scripts.scopes().single { it.owner is ScopeOwner.Module }.id
            val read = host.at(listOf("far"), scope) { call, index -> LuaHandle.Mob.Codec.read(call, index, "far") }
            assertEquals(LuaHandle.Entity(pig.id.toString()), read)
            assertEquals("Mob", read.luaClass)
            server.platform.commands.runConsole("class")
            assertEquals(listOf("Entity", "Mob"), server.logs)
        }
    }

    @Test
    fun `a player's name is looked up, not kept`() {
        TestServer(
            module(
                """
                nf.commands.register("hello", function(event)
                  alex = event.player
                  log(alex:name(), event.sender:name(), tostring(event.sender:is_player()))
                end)
                nf.commands.register("name", function(event)
                  log(alex:name(), event.sender:name(), tostring(event.sender:is_console()))
                end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "hello")
            // They leave, and change their name before they come back: the handle a script kept says it.
            server.platform.players.quit(alex)
            server.platform.players.known[alex.ref.uuid] = alex.ref.copy(name = "Alexandra")
            server.platform.commands.runConsole("name")
            assertEquals(listOf("Alex\tAlex\ttrue", "Alexandra\tCONSOLE\ttrue"), server.logs)
        }
    }
}
