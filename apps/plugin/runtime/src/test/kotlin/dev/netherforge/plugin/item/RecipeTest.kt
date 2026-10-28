package dev.netherforge.plugin.item
import dev.netherforge.format.recipe.Ingredient
import dev.netherforge.format.recipe.RecipeType
import dev.netherforge.format.ref.ResourceRef
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Recipes: the project's files', those scripts register, reloads, and players' recipe books. */
class RecipeTest {
    private val gem = """{ "kind": "minecraft:diamond", "name": "<aqua>Gem" }"""

    private val shaped = """
        { "type": "shaped", "pattern": ["GG", "GG"], "key": { "G": { "item": "gem" } }, "result": { "kind": "minecraft:gold_ingot", "count": 2 } }
    """.trimIndent()

    private val cooked = """
        { "type": "furnace", "ingredient": "minecraft:paper", "result": { "item": "gem" }, "experience": 0.5 }
    """.trimIndent()

    private fun server(module: String = ""): TestServer = TestServer(
        mapOf(
            "items/gem/item.json" to gem,
            "recipes/gem_block.json" to shaped,
            "recipes/baked_gem.json" to cooked,
            "modules/t/init.lua" to module
        ),
        start = false
    ).also {
        it.player("Alex")
        it.start()
    }

    private val TestServer.recipes get() = platform.recipes

    @Test
    fun `the project's recipes are on the server, and players are sent them once a tick`() {
        server().use { server ->
            assertEquals(setOf("baked_gem", "gem_block"), server.recipes.added.keys)
            val block = server.recipes.added.getValue("gem_block").file
            assertEquals(RecipeType.SHAPED, block.type)
            assertEquals(Ingredient(item = ResourceRef("gem")), block.key?.get("G"))
            assertEquals(0, server.recipes.resends)
            server.tick()
            assertEquals(1, server.recipes.resends)
            server.tick()
            assertEquals(1, server.recipes.resends, "nothing changed")
        }
    }

    @Test
    fun `a saved recipe is learnt again, a broken one keeps the last good version, and a deleted one goes`() {
        server().use { server ->
            server.tick()
            server.recipes.changes.clear()
            server.write("recipes/gem_block.json", shaped.replace("\"count\": 2", "\"count\": 3"))
            val saved = server.reload("recipes/gem_block.json").resources.single()
            assertEquals("recipe:gem_block", saved.label)
            assertTrue(saved.ok)
            assertEquals(3, server.recipes.added.getValue("gem_block").file.result.count)
            server.tick()
            assertEquals(2, server.recipes.resends)

            server.write("recipes/gem_block.json", shaped.replace("\"GG\", \"GG\"", "\"GG\", \"G\""))
            val broken = server.reload("recipes/gem_block.json").resources.single()
            assertFalse(broken.ok)
            assertEquals("recipe.pattern", broken.problems.single().code)
            assertEquals(3, server.recipes.added.getValue("gem_block").file.result.count, "the last good version stays")

            server.delete("recipes/gem_block.json")
            assertTrue(server.reload("recipes/gem_block.json").resources.single().ok)
            assertEquals(setOf("baked_gem"), server.recipes.added.keys)
            assertEquals(listOf("remove gem_block", "add gem_block", "remove gem_block"), server.recipes.changes)

            // An item's look may change its kind, which the server matches first: recipes naming it are made again.
            server.recipes.changes.clear()
            server.write("items/gem/item.json", gem.replace("Gem", "Shiny gem"))
            server.reload("items/gem/item.json")
            assertEquals(listOf("add baked_gem"), server.recipes.changes)
        }
    }

    @Test
    fun `scripts register recipes held to the files' rules, and they go with the script`() {
        val module = """
            ${LuaChecks.HELPERS}
            nf.recipes.register("gem_pair", {
              type = "shapeless",
              ingredients = { { item = "gem" }, "#minecraft:planks" },
              result = { kind = "minecraft:paper", count = 4, data = { from = vec3(1, 2, 3) } },
              category = "misc",
            })
            nf.recipes.register("gem_cut", { type = "stonecutting", ingredient = "minecraft:paper", result = { item = "gem" } })
            log("all", table.concat(nf.recipes.all(), ","))
            fails("bad pattern", function()
              nf.recipes.register("x", { type = "shaped", pattern = { "AB" }, key = { A = "minecraft:paper" }, result = { kind = "minecraft:paper" } })
            end, "definition.pattern: \"B\" in the pattern isn't in key")
            fails("camel case", function()
              nf.recipes.register("x", { type = "furnace", ingredient = "minecraft:paper", result = { kind = "minecraft:paper" }, cooking_time = 0 })
            end, "definition.cooking_time: cooking_time must be at least 1 tick")
            fails("unknown key", function()
              nf.recipes.register("x", { type = "furnace", ingredient = "minecraft:paper", result = { kind = "minecraft:paper" }, cookingTime = 5 })
            end, "unknown field 'definition.cookingTime'")
            fails("unknown item", function()
              nf.recipes.register("x", { type = "stonecutting", ingredient = { item = "ghost" }, result = { kind = "minecraft:paper" } })
            end, "definition.ingredient: there's no item \"ghost\"")
            fails("unknown result item", function()
              nf.recipes.register("x", { type = "stonecutting", ingredient = "minecraft:paper", result = { item = "ghost" } })
            end, "definition.result.item: there's no item \"ghost\"")
            fails("no result", function()
              nf.recipes.register("x", { type = "stonecutting", ingredient = "minecraft:paper" })
            end, "a recipe needs a result")
            fails("a file's id", function()
              nf.recipes.register("gem_block", { type = "stonecutting", ingredient = "minecraft:paper", result = { kind = "minecraft:paper" } })
            end, "recipes/gem_block.json's")
            fails("bad id", function()
              nf.recipes.register("Bad Id", { type = "stonecutting", ingredient = "minecraft:paper", result = { kind = "minecraft:paper" } })
            end, "isn't a usable recipe id")
            fails("remove a file's", function() nf.recipes.remove("gem_block") end, "delete the file")
            log("removed", nf.recipes.remove("gem_cut"), nf.recipes.remove("gem_cut"))
            log("done")
        """.trimIndent()
        server(module).use { server ->
            assertEquals(
                listOf("all\tbaked_gem,gem_block,gem_cut,gem_pair", "removed\ttrue\tfalse", "done"),
                server.output()
            )
            val pair = server.recipes.added.getValue("gem_pair").file
            assertEquals(listOf(Ingredient(item = ResourceRef("gem")), Ingredient(tag = "minecraft:planks")), pair.ingredients)
            assertEquals(4, pair.result.count)
            assertTrue(pair.result.data!!.getValue("from").toString().contains("vec3"))

            // A reloaded module registers its recipes again in its body; the old ones went with it.
            server.recipes.changes.clear()
            server.write("modules/t/init.lua", "")
            server.reload("modules/t/init.lua")
            assertEquals(listOf("remove gem_pair"), server.recipes.changes)
            assertEquals(setOf("baked_gem", "gem_block"), server.recipes.added.keys)
        }
    }

    @Test
    fun `a player's recipe book takes the project's recipes by id and anyone's by namespaced id`() {
        val module = """
            nf.commands.register("book", function()
              local alex = nf.players.get("Alex")
              log("discover", alex:discover_recipe("gem_block"), alex:discover_recipe("gem_block"), alex:discover_recipe("minecraft:bread"))
              log("has", alex:has_discovered_recipe("gem_block"), alex:has_discovered_recipe("baked_gem"))
              log("undiscover", alex:undiscover_recipe("gem_block"), alex:has_discovered_recipe("gem_block"))
              local ok, err = pcall(alex.discover_recipe, alex, "gem_blok")
              log("typo", ok, tostring(err):find("the project has no recipe \"gem_blok\"", 1, true) ~= nil)
            end)
        """.trimIndent()
        server(module).use { server ->
            server.platform.commands.runConsole("book")
            assertEquals(
                listOf("discover\ttrue\tfalse\ttrue", "has\ttrue\tfalse", "undiscover\ttrue\tfalse", "typo\tfalse\ttrue"),
                server.output()
            )
        }
    }
}
