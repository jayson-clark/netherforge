package dev.netherforge.plugin.loot
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `nf.loot`: the project's loot tables rolled by scripts, the game's through the platform, chests filled, reloads. */
class LootTest {
    private val gem = """{ "kind": "minecraft:diamond", "name": "<aqua>Gem", "maxStackSize": 16 }"""

    private val treasure = """
        { "pools": {
            "gems": { "entries": [{ "type": "item", "item": { "item": "gem" }, "count": 40 }] },
            "junk": { "rolls": 2, "entries": [{ "type": "table", "table": "junk" }] },
            "player": { "conditions": [{ "type": "player" }], "entries": [{ "type": "item", "item": { "kind": "minecraft:bread" } }] },
            "tool": { "entries": [{ "type": "item", "item": { "kind": "minecraft:paper" },
              "conditions": [{ "type": "tool", "tool": { "item": "gem" } }, { "type": "enchantment", "enchantment": "sharpness", "level": 2 }] }] }
        } }
    """.trimIndent()

    private val junk =
        """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "kind": "stick" }, "count": { "min": 1, "max": 3 } }] } } }"""

    private val dungeon = """{ "pools": { "main": { "entries": [{ "type": "vanilla", "table": "minecraft:chests/simple_dungeon" }] } } }"""

    private fun server(module: String): TestServer = TestServer(
        mapOf(
            "items/gem/item.json" to gem,
            "loot/treasure.json" to treasure,
            "loot/junk.json" to junk,
            "loot/dungeon.json" to dungeon,
            "modules/t/init.lua" to CHECK + module
        ),
        start = false
    ).also {
        it.player("Alex")
        it.start()
    }

    @Test
    fun `a roll gives stacks of what it picked, and a seed gives the same every time`() {
        server(
            """
            local items = nf.loot.roll("treasure", { seed = 7 })
            check("gem stacks", #items >= 4, true)
            check("project item", items[1].item, "gem")
            check("its look", items[1].name, "<aqua>Gem")
            check("split at its stack size", items[1].count, 16)
            check("then", items[2].count, 16)
            check("the rest", items[3].count, 8)
            check("then junk", items[4].kind, "minecraft:stick")
            local again = nf.loot.roll("treasure", { seed = 7 })
            check("same seed", nf.json.encode(again), nf.json.encode(items))
            local count = 0
            for _, item in ipairs(items) do
              if item.kind == "minecraft:bread" or item.kind == "minecraft:paper" then count = count + 1 end
            end
            check("no player, no tool", count, 0)
            log("done")
            """
        ).use { server -> assertEquals(LuaChecks.DONE, server.output()) }
    }

    @Test
    fun `conditions ask the context, and mistakes are errors`() {
        server(
            """
            local alex = nf.players.get("Alex")
            local tool = nf.items.create("gem", { enchantments = { ["minecraft:sharpness"] = 2 } })
            local kinds = {}
            for _, item in ipairs(nf.loot.roll("treasure", { player = alex, tool = tool, seed = 1 })) do kinds[item.kind] = true end
            check("player", kinds["minecraft:bread"], true)
            check("tool and enchantment", kinds["minecraft:paper"], true)
            fails("no table", function() nf.loot.roll("nothing") end, "there's no loot table \"nothing\" (loot/nothing.json)")
            fails("nobody's", function() nf.loot.roll("acme:chests/x") end, "neither the project nor the server has a loot table \"acme:chests/x\"")
            fails("game table needs a place", function() nf.loot.roll("dungeon") end, "is rolled at a place")
            fails("mob table needs the mob", function() nf.loot.roll("minecraft:entities/zombie", { player = alex }) end,
              "can't be rolled here: minecraft:entities/zombie needs the entity whose loot it is")
            fails("unknown key", function() nf.loot.roll("junk", { lucky = true }) end, "lucky")
            local game = nf.loot.roll("dungeon", { player = alex, seed = 3 })
            check("the game's table, at the player", game[1].kind, "minecraft:bread")
            check("direct", nf.json.encode(nf.loot.roll("minecraft:chests/simple_dungeon", { player = alex, seed = 3 })) ~= nil, true)
            log("done")
            """
        ).use { server ->
            assertEquals(LuaChecks.DONE, server.output())
            assertEquals(2, server.platform.loot.rolls.size)
        }
    }

    @Test
    fun `fill puts each stack in an empty slot of the chest, and the game's tables roll where it is`() {
        server(
            """
            local world = nf.worlds.default()
            local block = world:block(vec3(4, 64, 4))
            block:set_state("minecraft:chest")
            local chest = block:inventory()
            chest:set_item(0, { kind = "minecraft:paper" })
            check("all fit", nf.loot.fill("dungeon", chest, { seed = 5 }), 0)
            local filled = 0
            for slot, item in pairs(chest:items()) do filled = filled + 1 end
            check("two stacks and the paper", filled, 3)
            check("kept", chest:item(0).kind, "minecraft:paper")
            check("full", nf.loot.fill("treasure", chest, { seed = 5 }) >= 0, true)
            block:set_state("minecraft:stone")
            check("gone", nf.loot.fill("treasure", chest), nil)
            log("done")
            """
        ).use { server -> assertEquals(LuaChecks.DONE, server.output()) }
    }

    @Test
    fun `a saved table is what the next roll uses, and a broken one keeps the last good version`() {
        server("").use { server ->
            server.write("loot/junk.json", junk.replace("stick", "paper"))
            assertTrue(server.reload("loot/junk.json").resources.single().ok)
            assertEquals("minecraft:paper", roll(server))
            server.write("loot/junk.json", junk.replace("\"min\": 1", "\"min\": -1"))
            val broken = server.reload("loot/junk.json").resources.single()
            assertEquals(listOf("loot.range"), broken.problems.map { it.code })
            assertEquals("minecraft:paper", roll(server), "the last good version")
        }
    }

    @Test
    fun `the example's treasure command rolls its loot table into the player's inventory`() {
        TestServer(TestServer.example("showcase")).use { server ->
            val alex = server.player("Alex")
            val told = server.platform.commands.run(alex, "treasure").single()
            val stacks = Regex("<gold>You found (\\d+) stacks of treasure.").matchEntire(told)?.groupValues?.get(1)?.toInt()
            assertTrue(stacks != null && stacks >= 1, told)
            // Stacks of one item may share a slot, as picking them up would.
            assertTrue(alex.inventorySlots.count { it != null } in 1..stacks!!, told)
        }
    }

    @Test
    fun `a package's tables are named as its other resources are, and what they give reads in each package's words`() {
        fun manifest(namespace: String, more: String) =
            """{ "formatVersion": 1, "name": "$namespace", "namespace": "$namespace", "version": "1.0.0", "minecraft": "26.3"$more }"""
        val lib = mapOf(
            "netherforge.json" to manifest("lib", """, "exports": { "loot": ["drops"], "items": ["coin"], "modules": ["api"] }"""),
            "items/coin/item.json" to """{ "kind": "minecraft:paper" }""",
            "items/secret/item.json" to """{ "kind": "minecraft:diamond" }""",
            // Its own names, bare: its secret item and its private table, resolved in the library.
            "loot/drops.json" to
                """{ "pools": { "a": { "entries": [{ "type": "item", "item": { "item": "secret" } }] },
                    "b": { "entries": [{ "type": "table", "table": "hidden" }] } } }""",
            "loot/hidden.json" to """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "item": "coin" } }] } } }""",
            "modules/api/init.lua" to CHECK + """
                local api = {}
                function api.roll()
                  local items = nf.loot.roll("hidden", { seed = 1 })
                  check("lib reads its coin", items[1].item, "coin")
                  fails("the project's", function() nf.loot.roll("test:mine") end,
                    "\"test\" is neither this project's namespace (\"lib\") nor a package's it depends on")
                  return nf.loot.roll("drops", { seed = 1 })
                end
                return api
            """
        )
        val files = mapOf(
            "netherforge.json" to manifest("test", """, "dependencies": { "lib": { "path": "../lib" } }"""),
            "loot/mine.json" to """{ "pools": { "main": { "entries": [{ "type": "table", "table": "lib:drops" }] } } }""",
            "modules/main/init.lua" to CHECK + """
                local api = require("lib:api")
                local items = nf.loot.roll("lib:drops", { seed = 1 })
                check("its private item, the project's words", items[1].item, "lib:secret")
                check("through its private table", items[2].item, "lib:coin")
                check("through the project's table", nf.json.encode(nf.loot.roll("mine", { seed = 1 })), nf.json.encode(items))
                check("bare is the project's own", nf.json.encode(nf.loot.roll("test:mine", { seed = 1 })), nf.json.encode(items))
                local theirs = api.roll()
                -- A table keeps the words it was handed out in (the library's); the API reads it in them, and answers in ours.
                check("handed back by the library", theirs[2].item, "coin")
                check("read by the project", nf.items.id(theirs[2]), "lib:coin")
                fails("not exported", function() nf.loot.roll("lib:hidden") end,
                  "package \"lib\" doesn't export its loot table \"hidden\", so only it can use it")
                fails("not there", function() nf.loot.roll("lib:nope") end, "there's no loot table \"lib:nope\"")
                fails("not the library's", function() nf.loot.roll("hidden") end, "there's no loot table \"hidden\" (loot/hidden.json)")
                local block = nf.worlds.default():block(vec3(4, 64, 4))
                block:set_state("minecraft:chest")
                check("filled", nf.loot.fill("lib:drops", block:inventory(), { seed = 1 }), 0)
                local found = {}
                for _, item in pairs(block:inventory():items()) do found[#found + 1] = item.item end
                table.sort(found)
                check("in the chest", table.concat(found, ","), "lib:coin,lib:secret")
                log("done")
            """
        ) + lib.mapKeys { "../lib/${it.key}" }
        TestServer(files).use { server ->
            assertEquals(emptyList(), server.runtime.currentProblems().map { it.message })
            assertEquals(emptyList(), server.errors.map { it.message })
            assertEquals(LuaChecks.DONE, server.output())
        }
    }

    private fun roll(server: TestServer): String = server.runtime.session.loot.roll(
        "junk",
        dev.netherforge.plugin.loot.LootRoll(),
        1
    ).first().def.kind

    @Test
    fun `a generated container is filled with one roll of the table its terrain named, for whoever opened it`() {
        server("log(\"done\")").use { server ->
            val alex = server.platform.players.byId.keys.single()
            val at = Location("world", 3.0, 64.0, 3.0)
            val items = server.runtime.events.generatedLoot("treasure", alex, at)!!
            assertEquals("minecraft:diamond", items.first().def.kind)
            assertTrue(items.any { it.def.kind == "minecraft:bread" }, "the player's pool, for whoever opened it: $items")
            assertTrue(server.runtime.events.generatedLoot("treasure", null, at)!!.none { it.def.kind == "minecraft:bread" })
            // A table that isn't there leaves it empty, and says so.
            assertEquals(emptyList(), server.runtime.events.generatedLoot("nope", null, at))
        }
    }

    private companion object {
        const val CHECK = """
            function check(label, actual, expected)
              if actual ~= expected then log("FAIL " .. label .. ": " .. tostring(actual) .. " ~= " .. tostring(expected)) end
            end
            function fails(label, fn, message)
              local ok, err = pcall(fn)
              if ok or not tostring(err):find(message, 1, true) then log("FAIL " .. label .. ": " .. tostring(err)) end
            end
        """
    }
}
