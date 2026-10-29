package dev.netherforge.plugin.lua
import dev.netherforge.format.bridge.SourceRef
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What a script can't reach, how far it can run, and where its errors say they happened. */
class SandboxTest {

    private fun module(init: String, vararg more: Pair<String, String>) = mapOf("modules/t/init.lua" to init) + more

    @Test
    fun `everything that reaches the machine is gone`() {
        TestServer(
            module(
                """
                local names = { "io", "os", "package", "load", "loadstring", "dofile", "loadfile", "debug", "java", "collectgarbage", "warn" }
                for _, name in ipairs(names) do
                  log(name .. "=" .. type(_G[name]))
                end
                log("string.dump=" .. type(string.dump))
                """
            )
        ).use { server ->
            assertEquals(
                listOf("io", "os", "package", "load", "loadstring", "dofile", "loadfile", "debug", "java", "collectgarbage", "warn").map {
                    "$it=nil"
                } + "string.dump=nil",
                server.logs
            )
        }
    }

    @Test
    fun `the standard library is there but shared tables are read-only`() {
        TestServer(
            module(
                """
                log(string.format("%d-%s", 4, ("x"):upper()), math.floor(2.5), table.concat({ "a", "b" }, ","), utf8.char(72))
                log(pcall(function() string.upper = nil end))
                log(getmetatable(""))
                """
            )
        ).use { server ->
            assertEquals("4-X\t2\ta,b\tH", server.logs[0])
            assertTrue(server.logs[1].startsWith("false\t"), server.logs[1])
            assertTrue("string is shared by every script" in server.logs[1], server.logs[1])
            assertEquals("false", server.logs[2])
        }
    }

    @Test
    fun `rawset on a library reaches only the scope that did it`() {
        TestServer(
            mapOf(
                "modules/a/init.lua" to "rawset(string, \"format\", function() return \"poisoned\" end)",
                "modules/b/init.lua" to "log(string.format(\"%d\", 1))"
            )
        ).use { server -> assertEquals(listOf("1"), server.logs) }
    }

    @Test
    fun `handles can't be changed or forged`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to """{ "nodes": { "root": {} } }""",
                "menus/menu/menu.json" to """{ "rows": 2, "shared": true }""",
                "modules/t/init.lua" to """
                    local c = nf.centities.spawn("c", vec3(0, 64, 0))
                    log(pcall(function() c.play_animation = function() end end))
                    local fake = setmetatable({}, { __metatable = "Player" })
                    local menu = nf.menus.shared("menu")
                    log(pcall(menu.open_for, menu, fake))
                """
            )
        ).use { server ->
            assertTrue(server.logs[0].startsWith("false\t"), server.logs[0])
            assertTrue("Centity handles can't be changed" in server.logs[0], server.logs[0])
            assertTrue("bad argument 'player' (Player expected" in server.logs[1], server.logs[1])
        }
    }

    /**
     * `rawset` gets past a shared table's `__newindex`, so it refuses the tables every
     * script shares: a handle, an event, a `Vec3` (`vec3.zero` is one table for all).
     * One package could otherwise plant a method another package's code then calls.
     */
    @Test
    fun `rawset refuses the tables every script shares, and works on a script's own`() {
        TestServer(
            mapOf(
                "modules/a/init.lua" to """
                    log(pcall(rawset, nf.worlds.default(), "name", function() return "planted" end))
                    log(pcall(rawset, vec3.zero, 1, 999))
                    nf.on("tick", function(e) log(pcall(rawset, e, "cancel", function() end)) end, { every = 1 })
                    local own = setmetatable({}, { __newindex = function() error("no") end })
                    rawset(own, "x", 1)
                    log(own.x)
                """.trimIndent(),
                "modules/b/init.lua" to """
                    nf.after(1, function() log(nf.worlds.default():name(), vec3.zero.x) end)
                """.trimIndent()
            )
        ).use { server ->
            server.tick(2)
            assertEquals(emptyList(), server.errors.map { it.message })
            assertTrue("rawset: a World is shared by every script and can't be changed" in server.logs[0], server.logs[0])
            assertTrue("rawset: a Vec3 is shared by every script" in server.logs[1], server.logs[1])
            assertEquals("1", server.logs[2])
            assertTrue(server.logs.any { "rawset: an Event is shared by every script" in it }, server.logs.toString())
            assertTrue(server.logs.contains("world\t0"), server.logs.toString())
        }
    }

    @Test
    fun `modules don't share globals`() {
        TestServer(
            mapOf(
                "modules/a/init.lua" to "value = 1",
                "modules/b/init.lua" to "log(tostring(value))"
            )
        ).use { server -> assertEquals(listOf("nil"), server.logs) }
    }

    @Test
    fun `a runaway handler is stopped and reported where it was, and the module keeps running`() {
        TestServer(
            module(
                """
                nf.on("tick", function()
                  while true do
                  end
                end)
                """
            )
        ).use { server ->
            server.tick(3)
            val error = server.errors.single()
            assertTrue("ran past its budget of 200000 instructions" in error.message, error.message)
            assertEquals("modules/t/init.lua", error.source?.file)
            assertTrue(error.source?.line in 2..3, "line ${error.source?.line}")
            // A handler's failure is its own: the module still runs (the same error again is only counted).
            assertEquals(dev.netherforge.plugin.module.Modules.Status.RUNNING, server.runtime.session.modules.status("t"))
        }
    }

    @Test
    fun `catching the overrun doesn't save the script`() {
        TestServer(
            module(
                """
                nf.on("tick", function()
                  pcall(function()
                    while true do
                    end
                  end)
                end)
                """
            )
        ).use { server ->
            server.tick()
            assertTrue("budget" in server.errors.single().message)
        }
    }

    @Test
    fun `work done in nested call-ins counts against the caller`() {
        // Each spawn runs the new centity's script inside the module's call: ~20k
        // instructions apiece, which twenty of add up past the module's 200k.
        TestServer(
            mapOf(
                "centities/c/centity.json" to TestServer.scriptedCentity(),
                "centities/c/script.lua" to """
                    local n = 0
                    for i = 1, 5000 do n = n + i end
                """,
                "modules/t/init.lua" to """
                    for i = 1, 20 do nf.centities.spawn("c", vec3(i * 16, 64, 0)) end
                    log("finished")
                """
            )
        ).use { server ->
            assertTrue(server.errors.any { "budget" in it.message }, "${server.errors}")
            assertTrue("finished" !in server.logs, "${server.logs} ${server.errors}")
        }
    }

    @Test
    fun `coroutines run under the same budget`() {
        TestServer(
            module(
                """
                nf.on("tick", function()
                  local co = coroutine.create(function()
                    while true do
                    end
                  end)
                  coroutine.resume(co)
                end)
                """
            )
        ).use { server ->
            server.tick()
            assertTrue("budget" in server.errors.single().message)
        }
    }

    @Test
    fun `a centity script's budget comes from its file`() {
        TestServer(
            mapOf(
                "centities/c/centity.json" to """{ "nodes": { "root": {} }, "script": { "file": "script.lua", "budget": 2000 } }""",
                "centities/c/script.lua" to """
                    this:on("tick", function()
                      local n = 0
                      for i = 1, 5000 do
                        n = n + i
                      end
                    end)
                """,
                "modules/spawner/init.lua" to """nf.centities.spawn("c", vec3(0, 64, 0))"""
            )
        ).use { server ->
            server.tick()
            assertTrue("budget of 2000" in server.errors.single().message)
        }
    }

    @Test
    fun `nf budget reports what this call has left`() {
        TestServer(module("""log(nf.instructions_left() > 190000, nf.instructions_left() <= 200000)""")).use { server ->
            assertEquals("true\ttrue", server.logs.single())
        }
    }

    @Test
    fun `a syntax error points at its line`() {
        TestServer(module("local x = 1\nlocal y = = 2\n")).use { server ->
            val error = server.errors.single()
            assertEquals(SourceRef("modules/t/init.lua", 2), error.source)
            assertTrue("unexpected symbol" in error.message, error.message)
        }
    }

    @Test
    fun `a runtime error points at the line that failed, even in a required file`() {
        TestServer(
            module(
                "local util = require(\"util\")\nutil.run()\n",
                "modules/t/util.lua" to "local M = {}\n\nfunction M.run()\n  local t = nil\n  return t.field\nend\n\nreturn M\n"
            )
        ).use { server ->
            val error = server.errors.single()
            assertEquals(SourceRef("modules/t/util.lua", 5), error.source)
            assertTrue("attempt to index a nil value" in error.message, error.message)
        }
    }

    @Test
    fun `misusing the API points at the script's line, not the runtime's`() {
        TestServer(
            module("-- registers a handler\n-- for an event that doesn't exist\nnf.on(\"no_such_event\", function() end)\n")
        ).use { server ->
            val error = server.errors.single()
            assertEquals(SourceRef("modules/t/init.lua", 3), error.source)
            assertTrue("no event \"no_such_event\" for nf.on" in error.message, error.message)
        }
    }

    @Test
    fun `a wrong argument type is reported at the caller`() {
        TestServer(module("nf.after(\"soon\", function() end)\n")).use { server ->
            val error = server.errors.single()
            assertEquals(SourceRef("modules/t/init.lua", 1), error.source)
            assertTrue("bad argument 'ticks'" in error.message, error.message)
        }
    }
}
