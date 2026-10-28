package dev.netherforge.format.json

import dev.netherforge.format.centity.CentityKind
import dev.netherforge.format.dialog.DialogKind
import dev.netherforge.format.menu.MenuKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CanonicalJsonTest {

    @Test
    fun numbersPrintTheSameOnEveryPlatform() {
        val cases = mapOf(
            1.0 to "1",
            -2.0 to "-2",
            0.5 to "0.5",
            0.1 to "0.1",
            123.456 to "123.456",
            0.00001 to "0.00001",
            1e-7 to "1e-7",
            1e22 to "1e+22",
            1e21 to "1e+21",
            1e20 to "100000000000000000000",
            0.000001 to "0.000001",
            100.0 to "100",
            -0.25 to "-0.25"
        )
        for ((value, expected) in cases) {
            assertEquals(expected, CanonicalJson.formatNumber(value.toString()), "formatting $value")
        }
    }

    @Test
    fun unknownKeyIsAnErrorWithPathAndPosition() {
        val text = "{\n  \"nodes\": {\n    \"root\": { \"colour\": 1 }\n  }\n}\n"
        val parsed = CentityKind.parse(text, "centities/a/centity.json")
        assertIs<CanonicalJson.Parsed.Failed>(parsed)
        assertEquals("Unknown key \"colour\"", parsed.problem.message)
        assertEquals("$.nodes.root", parsed.problem.path)
        assertEquals(3, parsed.problem.line)
    }

    @Test
    fun missingRequiredKeyIsNamed() {
        val parsed = CentityKind.parse("""{"nodes":{"a":{"display":{"type":"block"}}}}""", "x")
        assertIs<CanonicalJson.Parsed.Failed>(parsed)
        assertEquals("Missing required key \"block\"", parsed.problem.message)
    }

    @Test
    fun writingSortsMapsAndKeyframes() {
        val text = """{"nodes":{"b":{"parent":"a"},"a":{}},""" +
            """"animations":{"x":{"tracks":{"b":{"scale":[{"time":1,"value":[1,1,1]},{"time":0,"value":[2,2,2]}]}}}}}"""
        val parsed = CentityKind.parse(text, "x") as CanonicalJson.Parsed.Ok
        val written = CentityKind.write(parsed.value)
        assertEquals(
            """
            {
              "${'$'}schema": "../../.netherforge/schema/centity.schema.json",
              "nodes": {
                "a": {},
                "b": {
                  "parent": "a"
                }
              },
              "animations": {
                "x": {
                  "tracks": {
                    "b": {
                      "scale": [
                        {
                          "time": 0,
                          "value": [2, 2, 2]
                        },
                        {
                          "time": 1,
                          "value": [1, 1, 1]
                        }
                      ]
                    }
                  }
                }
              }
            }

            """.trimIndent(),
            written
        )
    }

    @Test
    fun writingSortsMapsNestedAnywhere() {
        // Enchantments sit inside items inside slots, and a dialog body: no kind sorts them itself.
        val menu = """{"rows":1,"slots":{"13":{"item":{"kind":"stick",""" +
            """"enchantments":{"minecraft:unbreaking":1,"minecraft:efficiency":2}}},"2":{}}}"""
        val parsed = MenuKind.parse(menu, "x") as CanonicalJson.Parsed.Ok
        val written = MenuKind.write(parsed.value)
        assertTrue(written.indexOf("\"2\"") < written.indexOf("\"13\""), written)
        assertTrue(written.indexOf("minecraft:efficiency") < written.indexOf("minecraft:unbreaking"), written)

        val dialog = """{"title":"t","body":[{"type":"item","item":{"kind":"stick","enchantments":{"b:x":1,"a:x":1}}}]}"""
        val dialogWritten = DialogKind.write((DialogKind.parse(dialog, "x") as CanonicalJson.Parsed.Ok).value)
        assertTrue(dialogWritten.indexOf("a:x") < dialogWritten.indexOf("b:x"), dialogWritten)
    }

    @Test
    fun itemScriptDataRoundTripsInKeyOrder() {
        // Script data is free-form JSON: kept exactly, objects in key order however deep.
        val menu = """{"slots":{"0":{"item":{"kind":"gold_nugget","data":{"z":[2,{"b":1,"a":true}],"coin":true}}}}}"""
        val parsed = MenuKind.parse(menu, "x") as CanonicalJson.Parsed.Ok
        val written = MenuKind.write(parsed.value)
        assertTrue(written.indexOf("\"coin\"") < written.indexOf("\"z\""), written)
        assertTrue(written.indexOf("\"a\"") < written.indexOf("\"b\""), written)
        val again = MenuKind.parse(written, "x") as CanonicalJson.Parsed.Ok
        assertEquals(parsed.value.slots.getValue("0").item?.data, again.value.slots.getValue("0").item?.data)
        assertEquals(written, MenuKind.write(again.value))
    }

    @Test
    fun stringsEscapeControlCharacters() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", CanonicalJson.quote("a\"b\\c\n\u0001"))
    }
}
