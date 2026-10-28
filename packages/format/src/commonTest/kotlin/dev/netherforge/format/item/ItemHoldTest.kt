package dev.netherforge.format.item

import dev.netherforge.format.Problem
import dev.netherforge.format.ProblemSink
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.validate.Rules
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** An item's `hold`: `true` as it always was, or `{ "animation": … }`, which poses the player and changes nothing else. */
class ItemHoldTest {
    private fun read(hold: String): ItemDef {
        val parsed = CanonicalJson.parse(ItemDef.serializer(), """{ "kind": "minecraft:stick", "hold": $hold }""", "item.json")
        return assertIs<CanonicalJson.Parsed.Ok<ItemDef>>(parsed, "$parsed").value
    }

    private fun written(def: ItemDef): String =
        Json.parseToJsonElement(CanonicalJson.write(ItemDef.serializer(), def)).jsonObject.getValue("hold").toString()

    @Test
    fun `true and false read and write as they always did`() {
        assertEquals(HoldDef(), read("true").hold)
        assertEquals(HoldDef(on = false), read("false").hold)
        assertEquals("true", written(read("true")))
        assertEquals("false", written(read("false")))
    }

    @Test
    fun `an object names the animation, and one with none is true again`() {
        assertEquals(HoldDef(animation = UseAnimation("trident")), read("""{ "animation": "trident" }""").hold)
        assertEquals("""{"animation":"trident"}""", written(read("""{ "animation": "trident" }""")))
        assertEquals(HoldDef(), read("{}").hold)
        assertEquals("true", written(read("{}")))
    }

    @Test
    fun `anything else is a parse error`() {
        for (hold in listOf("\"yes\"", "1", """{ "pose": "bow" }""", """{ "animation": 3 }""", "[]")) {
            val parsed = CanonicalJson.parse(ItemDef.serializer(), """{ "kind": "minecraft:stick", "hold": $hold }""", "item.json")
            assertIs<CanonicalJson.Parsed.Failed>(parsed, hold)
        }
    }

    @Test
    fun `a project item's hold passes to its stacks whole`() {
        val file = ItemFile(kind = "minecraft:stick", hold = HoldDef(animation = UseAnimation("bow")))
        assertEquals(HoldDef(animation = UseAnimation("bow")), file.look().hold)
    }

    private fun problems(def: ItemDef): List<Problem> = ProblemSink("items/knife/item.json").also {
        Rules.item(def, "$", it, null)
    }.problems

    @Test
    fun `every animation the game has is one, and others are item hold-animation with the near miss`() {
        for (animation in UseAnimation.entries) {
            assertEquals(emptyList(), problems(ItemDef(kind = "minecraft:stick", hold = HoldDef(animation = animation))), "$animation")
        }
        fun message(animation: String) =
            problems(ItemDef(kind = "minecraft:stick", hold = HoldDef(animation = UseAnimation(animation)))).single().let {
                assertEquals("item.hold-animation", it.code)
                assertEquals("$.hold.animation", it.path)
                it.message
            }
        assertEquals("\"tridnet\" isn't a use animation: did you mean \"trident\"?", message("tridnet"))
        assertEquals("\"Toot-Horn\" isn't a use animation: did you mean \"toot_horn\"?", message("Toot-Horn"))
        assertEquals("\"minecraft:bow\" isn't a use animation: did you mean \"bow\"?", message("minecraft:bow"))
        assertEquals(
            "\"throw\" isn't a use animation: it's one of ${UseAnimation.ALL.joinToString(", ")}",
            message("throw")
        )
    }

    @Test
    fun `a hold of either form can't be food, and a hold turned off can`() {
        val food = dev.netherforge.format.item.FoodDef(nutrition = 1, saturation = 0.0)
        for (hold in listOf(HoldDef(), HoldDef(animation = UseAnimation("eat")))) {
            assertEquals(listOf("item.hold-food"), problems(ItemDef(kind = "minecraft:stick", hold = hold, food = food)).map { it.code })
        }
        assertEquals(emptyList(), problems(ItemDef(kind = "minecraft:stick", hold = HoldDef(on = false), food = food)))
    }
}
