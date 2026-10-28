package dev.netherforge.plugin.item
import dev.netherforge.plugin.TestServer
import dev.netherforge.plugin.platform.InventoryRef
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An item table's `data`, read in Kotlin rather than by the prelude's
 * Lua↔JSON codec (so handing one over costs a script next to nothing:
 * `ApiCostTest`), is the codec's very text, as the stack keeps it, and comes
 * back as it went in.
 */
class ItemDataTextTest {
    private fun server(module: String): TestServer = TestServer(
        mapOf(
            "items/ruby/item.json" to """{ "kind": "minecraft:emerald", "name": "Ruby" }""",
            "modules/t/init.lua" to """
                local player = nf.players.get("Alex")
                local inventory = player:inventory()
                $module
            """.trimIndent()
        ),
        start = false
    ).also {
        it.player("Alex")
        it.start()
    }

    @Test
    fun `data read in Kotlin is the codec's very text`() {
        val tables = listOf(
            """{ coin = true, n = 3 }""",
            """{ B = 1, a = 2, _x = 3, Z9 = 4, ["two words"] = 5, ["9"] = 6, [""] = 7 }""",
            """{ nested = { z = { 1, 2, { y = "x", b = -7 } }, a = {} }, big = math.maxinteger, small = math.mininteger }""",
            """{ text = "quote \" back \\ line \n tab \t bell \7 é ✓" }""",
            """{ f = 0.1, g = 1e20, h = 3.0, i = -0.0, j = 1 / 3 }""",
            """{ at = vec3(1, 2.5, 3), who = player, where = player:location(), list = { vec3(0, -1, 1e20) }, world = nf.worlds.default() }""",
            // What the codec writes itself: tag-like keys, holes, keys past ASCII.
            """{ ["${'$'}vec3"] = { 1, 2, 3 }, ["${'$'}ref"] = "x" }""",
            """{ holes = { 1, nil, 3 } }""",
            """{ ["é"] = 1, e = 2 }"""
        )
        val module = tables.withIndex().joinToString("\n") { (i, table) ->
            // A key starting with "$" beside the data has the codec itself write it, not Kotlin.
            "do local data = $table; inventory:set_item($i, { kind = \"minecraft:bread\", data = data }); " +
                "log(nf.json.encode({ d = data, [\"${'$'}codec\"] = true })) end"
        }
        server(module).use { server ->
            assertEquals(emptyList(), server.errors)
            val slots = server.platform.inventories.slots(InventoryRef.Player(server.platform.players.byId.values.first().ref.uuid))!!
            assertEquals(tables.size, server.logs.size)
            for ((i, codec) in server.logs.withIndex()) {
                assertEquals(codec, "{\"${'$'}codec\":true,\"d\":${JsonObject(slots[i]!!.def.data!!)}}", "data ${tables[i]}")
            }
        }
    }

    @Test
    fun `a stack's data comes back as it went in`() {
        val module = """
            inventory:set_item(0, { kind = "minecraft:bread", data = { n = 3, list = { "a", { b = true } }, at = vec3(1, 2.5, 3), ["${'$'}vec3"] = 1 } })
            local data = inventory:item(0).data
            log(data.n, data.list[1], data.list[2].b, data.at.y, data["${'$'}vec3"], math.type(data.n))
        """
        server(module).use { server ->
            assertEquals(emptyList(), server.errors)
            assertEquals(listOf("3\ta\ttrue\t2.5\t1\tinteger"), server.logs)
        }
    }
}
