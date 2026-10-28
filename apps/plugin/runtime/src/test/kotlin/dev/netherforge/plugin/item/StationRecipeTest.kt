package dev.netherforge.plugin.item
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.platform.Location
import kotlin.test.Test
import kotlin.test.assertEquals

/** `station` recipes: listed by what a player holds and what stands near them, and made by `nf.recipes.craft`. */
class StationRecipeTest {
    private val files = mapOf(
        "items/gel/item.json" to """{ "kind": "minecraft:paper", "name": "Gel" }""",
        "items/torch/item.json" to """{ "kind": "minecraft:paper", "name": "Torch" }""",
        "items/bench/item.json" to """{ "kind": "minecraft:paper", "name": "Work Bench" }""",
        "blocks/work_bench/block.json" to """{ "tool": "axe" }""",
        "recipes/torch.json" to """
            {
              "type": "station",
              "materials": [
                { "ingredient": { "item": "gel" }, "count": 1 },
                { "ingredient": "#minecraft:planks", "count": 1 }
              ],
              "result": { "item": "torch", "count": 3 }
            }
        """,
        "recipes/bench.json" to """
            {
              "type": "station",
              "materials": [ { "ingredient": "minecraft:oak_planks", "count": 10 } ],
              "stations": [ "minecraft:anvil|work_bench" ],
              "stationRange": 3,
              "group": "furniture",
              "result": { "item": "bench" }
            }
        """,
        "recipes/sticks.json" to """
            { "type": "shapeless", "ingredients": [ "minecraft:oak_planks" ], "result": { "kind": "minecraft:stick", "count": 4 } }
        """
    )

    @Test
    fun `available lists what a player can make here, and craft makes it`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            local world = alex:world()
            alex:give_item(nf.items.create("gel", { count = 2 }))
            alex:give_item({ kind = "minecraft:oak_planks", count = 12 })

            local list = nf.recipes.available(alex)
            check("only the torch: no bench is near", #list, 1)
            check("its id", list[1].id, "torch")
            check("twice over: two gel", list[1].times, 2)
            check("its result", list[1].result.item, "torch")
            check("its result's count", list[1].result.count, 3)
            check("a project item material", list[1].materials[1].item, "gel")
            check("a tag material", list[1].materials[2].tag, "minecraft:planks")

            local all = nf.recipes.available(alex, { all = true })
            check("every station recipe, not the game's", #all, 2)
            check("the bench, not makeable", all[1].id .. " " .. all[1].times, "bench 0")
            check("a station counted as near", nf.recipes.available(alex, { stations = { "work_bench" }, group = "furniture" })[1].times, 1)

            -- A work bench three blocks off: in range.
            local bench = nf.blocks.get("work_bench")
            check("placed", bench:place(world:location(vec3(3, 64, 0))) ~= nil, true)
            check("now the bench too", #nf.recipes.available(alex), 2)
            check("but not from further off", #nf.recipes.available(alex, { near = vec3(10, 64, 0) }), 1)

            check("made twice", nf.recipes.craft(alex, "torch", { times = 5 }), 2)
            local torches, gel, planks = 0, 0, 0
            for _, stack in pairs(alex:inventory():items()) do
              if stack.item == "torch" then torches = torches + stack.count end
              if stack.item == "gel" then gel = gel + stack.count end
              if stack.kind == "minecraft:oak_planks" and not stack.item then planks = planks + stack.count end
            end
            check("torches given", torches, 6)
            check("gel taken", gel, 0)
            check("planks taken", planks, 10)
            check("nothing left to make it from", nf.recipes.craft(alex, "torch"), 0)
            check("the bench, from the ten planks left", nf.recipes.craft(alex, "bench"), 1)

            check("get", nf.recipes.get("bench").stations[1], "minecraft:anvil|work_bench")
            check("get's range", nf.recipes.get("bench").station_range, 3)
            check("no times from get", nf.recipes.get("bench").times, nil)
            check("a game recipe isn't one", nf.recipes.get("sticks"), nil)
            fails("craft a game recipe", function() nf.recipes.craft(alex, "sticks") end, "isn't a station recipe")
            fails("an unknown one", function() nf.recipes.craft(alex, "nope") end, "no recipe")
            fails("times", function() nf.recipes.craft(alex, "torch", { times = 0 }) end, "at least 1")
            """,
            files,
            setup = { it.platform.players.add("Alex", Location("world", 0.5, 64.0, 0.5)) }
        )
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `a station recipe never reaches the game, and scripts register them too`() {
        val result = LuaChecks.run(
            """
            nf.recipes.register("mine", {
              type = "station",
              materials = { { ingredient = "minecraft:stick", count = 2 } },
              stations = { "minecraft:stone" },
              station_range = 2,
              result = { kind = "minecraft:stick" },
            })
            check("listed", nf.recipes.get("mine").station_range, 2)
            """,
            files
        ) { server ->
            assertEquals(listOf("sticks"), server.platform.recipes.added.keys.sorted(), "only the game's recipe went to the server")
        }
        assertEquals(listOf("done"), result)
    }

    @Test
    fun `recipe_craft hears each making and can change what it gives`() {
        val result = LuaChecks.run(
            """
            local alex = nf.players.get("Alex")
            alex:give_item(nf.items.create("gel", { count = 2 }))
            alex:give_item({ kind = "minecraft:oak_planks", count = 2 })
            local n = 0
            nf.on("recipe_craft", function(event)
              n = n + 1
              check("whose", event.player:name() .. " " .. event.recipe, "Alex torch")
              local torch = event.result
              torch.data = { batch = n }
              torch.count = n
              event.result = torch
            end)
            check("made", nf.recipes.craft(alex, "torch", { times = 2 }), 2)
            local counts = {}
            for _, stack in pairs(alex:inventory():items()) do
              if stack.item == "torch" then counts[stack.data.batch] = stack.count end
            end
            check("each its own", tostring(counts[1]) .. " " .. tostring(counts[2]), "1 2")
            """,
            files,
            setup = { it.platform.players.add("Alex", Location("world", 0.5, 64.0, 0.5)) }
        )
        assertEquals(listOf("done"), result)
    }
}
