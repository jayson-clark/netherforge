package dev.netherforge.plugin.data
import dev.netherforge.format.bridge.Log
import dev.netherforge.format.bridge.LogLevel
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.GameEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `centity:data()`, `player:data()`, `nf.data(name)` and `Item.data`. */
class DataTest {
    private val crate = mapOf(
        "centities/crate/centity.json" to TestServer.scriptedCentity(),
        "centities/crate/script.lua" to """
            local data = this:data()
            data.starts = (data.starts or 0) + 1
            log("crate start", data.starts)
        """
    )

    private fun module(init: String) = crate + ("modules/m/init.lua" to init)

    /** What the store has for [owner], once everything staged is written. */
    private fun TestServer.saved(owner: ScriptData.Owner): String? = runtime.store.tables.read(owner)

    private fun TestServer.savedNamed(name: String): String? = saved(ScriptData.Owner.Named("test", name))

    private val TestServer.warnings: List<String>
        get() = sent.filterIsInstance<Log>().filter { it.level == LogLevel.WARN }.map { it.message }

    @Test
    fun `player data keeps every kind of value it may hold, typed, across a restart`() {
        TestServer(
            module(
                """
                nf.commands.register("save", function(event)
                  local p = event.player
                  local d = p:data()
                  d.greeter = { welcomed = true, visits = 3, ratio = 0.5, whole = 2.0, name = "Al \"the\" great\n" }
                  d.spot = vec3(1, 2.5, -3)
                  d.home = p:location()
                  d.owner = p
                  d.crate = nf.centities.spawn("crate", vec3(0, 64, 0))
                  d.list = { "a", "b", { nested = true } }
                  d.item = { kind = "minecraft:diamond", count = 2, data = { at = vec3(0, 1, 0), by = p } }
                  d["${'$'}price"] = 5
                  d["${'$'}vec3"] = 6
                  d.empty = {}
                end)
                nf.commands.register("show", function(event)
                  local p = event.player
                  local d = p:data()
                  local g = d.greeter
                  log(g.welcomed, g.visits, math.type(g.visits), g.ratio, math.type(g.whole), g.name)
                  log(tostring(d.spot), tostring(d.home), d.home == p:location())
                  log(d.owner == p, d.owner:exists(), d.crate:exists(), d.crate:kind())
                  log(#d.list, d.list[3].nested, d.item.kind, tostring(d.item.data.at), d.item.data.by == p)
                  log(d["${'$'}price"], d["${'$'}vec3"], next(d.empty), p:data() == d)
                end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "save")
            server.platform.commands.run(alex, "show")
            val before = server.logs.drop(1)
            server.restart()
            server.platform.commands.run(alex, "show")
            val after = server.logs.drop(before.size + 2)
            assertEquals(
                listOf(
                    "true\t3\tinteger\t0.5\tfloat\tAl \"the\" great\n",
                    "vec3(1, 2.5, -3)\tlocation(\"world\", vec3(0.5, 64, 0.5), 0, 0)\ttrue",
                    "true\ttrue\ttrue\tcrate",
                    "3\ttrue\tminecraft:diamond\tvec3(0, 1, 0)\ttrue",
                    "5\t6\tnil\ttrue"
                ),
                before
            )
            assertEquals(before, after, "the same after a restart")
            val saved = server.saved(ScriptData.Owner.Player(alex.ref.uuid))!!
            assertTrue("\"spot\":{\"${'$'}vec3\":[1,2.5,-3]}" in saved, saved)
            // A player is saved as the entity they are: by UUID alone, their name looked up when it's asked.
            assertTrue("\"owner\":{\"${'$'}entity\":[\"${alex.ref.uuid}\"]}" in saved, saved)
            // Only a key that would read as a tag is escaped; any other `$` key is written as it is.
            assertTrue("\"${'$'}price\":5" in saved, saved)
            assertTrue("\"${'$'}${'$'}vec3\":6" in saved, saved)
            assertTrue("\"whole\":2.0" in saved, saved)
            assertEquals(emptyList(), server.errors.map { it.message })
        }
    }

    @Test
    fun `what can't be saved is logged with its key path and skipped, once while it stays the same`() {
        TestServer(
            module(
                """
                local d = nf.data("junk")
                d.fine = 1
                d.fn = function() end
                d.list = { 1, function() end, 3 }
                d.co = coroutine.create(function() end)
                d.sub = nf.on("tick", function() end)
                d.mixed = { 1, 2, name = "x" }
                d.self = d
                d.huge = math.huge
                d.deep = { menu = { inside = { print } } }
                """
            )
        ).use { server ->
            server.runtime.saveData()
            server.runtime.saveData()
            val prefix = "Saving nf.data(\"junk\")'s data: "
            assertEquals(
                listOf(
                    "data.co: a coroutine can't be saved",
                    "data.deep.menu.inside[1]: a function can't be saved",
                    "data.fn: a function can't be saved",
                    "data.huge: inf can't be saved",
                    "data.list[2]: a function can't be saved",
                    "data.mixed[1]: a number key is saved only in a list numbered from 1",
                    "data.mixed[2]: a number key is saved only in a list numbered from 1",
                    "data.self: a table inside itself can't be saved",
                    "data.sub: a Subscription handle can't be saved"
                ).map { prefix + it },
                server.warnings.filter { it.startsWith(prefix) }
            )
            val saved = Json.parseToJsonElement(server.savedNamed("junk")!!)
            assertEquals(
                Json.parseToJsonElement("""{"deep":{"menu":{"inside":[null]}},"fine":1,"list":[1,null,3],"mixed":{"name":"x"}}"""),
                saved
            )
        }
    }

    @Test
    fun `a centity's table is kept with the instance and goes when it's removed`() {
        TestServer(
            module(
                """
                nf.commands.register("spawn", function() nf.centities.spawn("crate", vec3(0, 64, 0)) end)
                nf.commands.register("kill", function()
                  local crate = nf.centities.all()[1]
                  crate:data().note = "bye"
                  crate:remove()
                  log("after remove", crate:data())
                end)
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "spawn")
            val id = server.runtime.session.centities.all().single().id
            val owner = ScriptData.Owner.Centity(id)
            server.restart()
            assertEquals("""{"starts":1}""", server.saved(owner))
            server.platform.commands.run(alex, "kill")
            server.runtime.saveData()
            assertEquals(null, server.saved(owner), "removed with the instance")
            assertEquals(listOf("crate start\t1", "crate start\t2", "after remove\tnil"), server.logs)
        }
    }

    @Test
    fun `tables live in memory across hot reloads, and a full reload keeps what could be saved`() {
        TestServer(
            module(
                """
                local d = nf.data("memory")
                d.loads = (d.loads or 0) + 1
                local loads = d.loads
                -- A function can't be saved, so only memory keeps it.
                d.fn = d.fn or function() return "made at load " .. loads end
                log("loads", d.loads, d.fn())
                """
            )
        ).use { server ->
            server.runtime.saveData()
            // What the store has, changed behind the runtime's back, isn't read again by a reload.
            val owner = ScriptData.Owner.Named("test", "memory")
            server.runtime.store.tables.put(owner, """{"loads":100}""")
            server.runtime.store.flush()
            server.reload("modules/m/init.lua")
            server.reload("netherforge.json")
            assertEquals(listOf("loads\t1\tmade at load 1", "loads\t2\tmade at load 1", "loads\t3\tmade at load 3"), server.logs)
            assertEquals("""{"loads":3}""", server.saved(owner), "the full reload saved what it could")
        }
    }

    @Test
    fun `a table past 1 MiB isn't saved, and its last save stands`() {
        TestServer(
            module(
                """
                local d = nf.data("big")
                d.a = string.rep("x", 600 * 1024)
                nf.commands.register("grow", function() d.b = string.rep("y", 600 * 1024) end)
                """
            )
        ).use { server ->
            server.runtime.saveData()
            val first = server.savedNamed("big")
            assertTrue(first!!.length > 600 * 1024)
            server.platform.commands.run(server.player(), "grow")
            server.runtime.saveData()
            assertEquals(first, server.savedNamed("big"))
            assertEquals(
                listOf("nf.data(\"big\")'s data takes 1.2 MiB, more than the 1 MiB a table may, so it wasn't saved: the last save stands"),
                server.warnings
            )
        }
    }

    @Test
    fun `block and entity tables past 1 MiB aren't saved either, and their last save stands`() {
        TestServer(
            mapOf(
                "modules/m/init.lua" to """
                    local block = nf.worlds.default():block(vec3(1, 64, 2))
                    local pig = nf.worlds.default():spawn_entity("pig", vec3(0, 64, 0))
                    block:data().a = string.rep("x", 600 * 1024)
                    pig:data().a = string.rep("x", 600 * 1024)
                    nf.commands.register("grow", function()
                      block:data().b = string.rep("y", 600 * 1024)
                      pig:data().b = string.rep("y", 600 * 1024)
                    end)
                """
            )
        ).use { server ->
            server.runtime.events.worldSaving("world")
            val block = server.platform.blocks.data.values.single()
            val pig = server.platform.worldEntities.mobs.values.single()
            val entity = pig.data
            server.platform.commands.runConsole("grow")
            server.runtime.events.worldSaving("world")
            server.runtime.events.worldSaving("world")
            assertEquals(block, server.platform.blocks.data.values.single())
            assertEquals(entity, pig.data)
            val warnings = server.platform.log.lines.filter { "more than the 1 MiB a table may" in it }
            assertEquals(2, warnings.size, warnings.toString())
            assertTrue(warnings.any { "block:data() at world 1 64 2 takes 1.2 MiB" in it }, warnings.toString())
            assertTrue(warnings.any { "entity:data() of ${pig.id} takes 1.2 MiB" in it }, warnings.toString())
        }
    }

    @Test
    fun `item data past 1 MiB is an error`() {
        TestServer(
            mapOf(
                "modules/m/init.lua" to """
                    nf.commands.register("give", function(event)
                      event.player:give_item({ kind = "minecraft:paper", data = { text = string.rep("x", 1100 * 1024) } })
                    end)
                """
            )
        ).use { server ->
            server.platform.commands.run(server.player(), "give")
            val error = server.errors.single().message
            assertTrue("item.data takes 1.1 MiB, more than the 1 MiB it may" in error, error)
        }
    }

    @Test
    fun `player data is saved when they leave, and every table at autosave`() {
        TestServer(
            module(
                """
                nf.commands.register("visit", function(event) event.player:data().visited = true end)
                nf.data("shop").open = true
                """
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "visit")
            server.platform.raise.playerQuit(GameEvent.PlayerQuit(alex.ref, null))
            assertEquals("""{"visited":true}""", server.saved(ScriptData.Owner.Player(alex.ref.uuid)))

            server.tick(ScriptData.AUTOSAVE_EVERY.toInt() - 1)
            assertEquals(null, server.savedNamed("shop"))
            server.tick()
            assertEquals("""{"open":true}""", server.savedNamed("shop"))
        }
    }

    @Test
    fun `a data table name that isn't an id is an error`() {
        TestServer(module("nf.data(\"Shop Items\")")).use { server ->
            val error = server.errors.single()
            assertTrue("\"Shop Items\" isn't a data table name" in error.message, error.message)
            assertEquals(1, error.source?.line)
        }
    }

    @Test
    fun `a saved value that isn't a table starts empty, and says so`() {
        TestServer(module("""nf.commands.register("peek", function() log(next(nf.data("broken"))) end)""")).use { server ->
            server.runtime.store.tables.put(ScriptData.Owner.Named("test", "broken"), "not json")
            server.platform.commands.run(server.player(), "peek")
            assertEquals(listOf("nil"), server.logs)
            assertEquals(listOf("What the store has for nf.data(\"broken\")'s data isn't a saved table; it starts empty"), server.warnings)
        }
    }

    @Test
    fun `item data keeps typed values on the stack and gives them back typed`() {
        TestServer(
            mapOf(
                "menus/menu/menu.json" to """{ "title": "Menu", "rows": 1, "script": { "file": "script.lua" } }""",
                "menus/menu/script.lua" to """
                    this:on("open", function(event)
                      local p = event.player
                      this:set_item(0, { kind = "minecraft:diamond", data = { at = vec3(1, 2, 3), by = p, ["${'$'}x"] = 1, ["${'$'}vec3"] = 2, where = p:location() } })
                      local back = this:item(0).data
                      log(back.at == vec3(1, 2, 3), back.by == p, back["${'$'}x"], back["${'$'}vec3"], back.where == p:location())
                    end)
                """,
                "modules/m/init.lua" to """nf.commands.register("menu", function(event) event.player:open_menu("menu") end)"""
            )
        ).use { server ->
            val alex = server.player("Alex")
            server.platform.commands.run(alex, "menu")
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(listOf("true\ttrue\t1\t2\ttrue"), server.logs)
            val data = server.platform.menus.of(alex)!!.slots[0]!!.def.data!!
            assertEquals(Json.parseToJsonElement("[1,2,3]"), (data.getValue("at") as JsonObject)["${'$'}vec3"])
            assertEquals(Json.parseToJsonElement("1"), data.getValue("${'$'}x"))
            assertEquals(Json.parseToJsonElement("2"), data.getValue("${'$'}${'$'}vec3"))
            assertTrue(data.getValue("by").jsonObject.containsKey("${'$'}entity"))
        }
    }
}
