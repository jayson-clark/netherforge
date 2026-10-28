package dev.netherforge.plugin.item
import dev.netherforge.format.item.TooltipComponent
import dev.netherforge.plugin.LuaChecks
import dev.netherforge.plugin.TestServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * An item's `hidden_tooltip_components` from a script: every line an item can hide, as the format lists them,
 * written and read back beside the components they hide, and a value that isn't one refused at the script's line.
 */
class HiddenTooltipLinesTest {
    private val files = mapOf(
        "items/pick/item.json" to
            """{ "kind": "minecraft:iron_pickaxe", "unbreakable": true, "hiddenTooltipComponents": ["unbreakable", "attribute_modifiers"] }"""
    )

    @Test
    fun `a script hides any line an item can hide, and reads it back with the component it hides`() {
        val every = TooltipComponent.ALL.joinToString(", ") { "\"$it\"" }
        val result = LuaChecks.run(
            """
            local inventory = nf.players.get("Alex"):inventory()
            inventory:set_item(0, {
              kind = "minecraft:iron_pickaxe",
              unbreakable = true,
              attribute_modifiers = { { attribute = "minecraft:block_break_speed", amount = 0.5, operation = "add_multiplied_base" } },
              hidden_tooltip_components = { "unbreakable", "attribute_modifiers", "can_break" },
            })
            local item = inventory:item(0)
            check("lines", table.concat(item.hidden_tooltip_components, ","), "unbreakable,attribute_modifiers,can_break")
            check("still unbreakable", item.unbreakable, true)
            check("still faster", item.attribute_modifiers[1].attribute, "minecraft:block_break_speed")
            inventory:set_item(1, { kind = "minecraft:paper", hidden_tooltip_components = { $every } })
            check("every line", #inventory:item(1).hidden_tooltip_components, ${TooltipComponent.ALL.size})
            check("a project item's", table.concat(nf.items.create("pick").hidden_tooltip_components, ","), "unbreakable,attribute_modifiers")
            inventory:set_item(2, { item = "pick", hidden_tooltip_components = {} })
            check("shown again", #inventory:item(2).hidden_tooltip_components, 0)
            fails("not a line", function() inventory:set_item(3, { kind = "minecraft:paper", hidden_tooltip_components = { "tool" } }) end,
              "\"tool\" isn't a tooltip line hidden_tooltip_components can hide")
            fails("spelled otherwise", function() inventory:set_item(3, { kind = "minecraft:paper", hidden_tooltip_components = { "painting_variant" } }) end,
              "did you mean \"painting/variant\"?")
            fails("not a string", function() inventory:set_item(3, { kind = "minecraft:paper", hidden_tooltip_components = { 1 } }) end,
              "item.hidden_tooltip_components must be a list of strings")
            """.trimIndent(),
            files,
            setup = { it.platform.players.add("Alex") }
        )
        assertEquals(LuaChecks.DONE, result)
    }

    @Test
    fun `the Lua API's type lists exactly the lines the format does`() {
        val spec = Json.parseToJsonElement(TestServer.repo().resolve("packages/api/generated/api.json").readText()).jsonObject
        val types = spec.getValue("shapes").jsonArray.map { it.jsonObject }
            .flatMap { shape -> shape["fields"]?.jsonArray.orEmpty().map { it.jsonObject } }
            .filter { it.getValue("name").jsonPrimitive.content == "hidden_tooltip_components" }
            .map { it.getValue("type").jsonPrimitive.content }
        assertEquals(true, types.isNotEmpty(), "the spec has the field")
        for (type in types) assertEquals(TooltipComponent.ALL, Regex("\"([^\"]+)\"").findAll(type).map { it.groupValues[1] }.toList(), type)
    }
}
