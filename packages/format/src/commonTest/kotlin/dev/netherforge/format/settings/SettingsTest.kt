package dev.netherforge.format.settings

import dev.netherforge.format.Problem
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ManifestKind
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class SettingsTest {
    private val rounds = IntegerSetting("Rounds a game lasts.", 3, min = 1, max = 10)
    private val ratio = NumberSetting("A share.", 0.5, min = 0.0, max = 1.0)
    private val pvp = BooleanSetting("Players may hurt each other.", false)
    private val motd = StringSetting("The greeting.", "Hi")
    private val size = ChoiceSetting("How big.", "small", listOf("small", "large"))

    private fun ok(read: SettingRead) = assertIs<SettingRead.Ok>(read).json

    private fun bad(read: SettingRead) = assertIs<SettingRead.Bad>(read).message

    @Test
    fun readsEachKindOfValueInItsOneWrittenForm() {
        assertEquals(JsonPrimitive(4L), ok(rounds.read(JsonPrimitive(4))))
        // A whole number written as a fraction is the whole number, as Lua reads 2.0.
        assertEquals(JsonPrimitive(4L), ok(rounds.read(JsonPrimitive(4.0))))
        assertEquals(JsonPrimitive(0.25), ok(ratio.read(JsonPrimitive(0.25))))
        assertEquals(JsonPrimitive(true), ok(pvp.read(JsonPrimitive(true))))
        assertEquals(JsonPrimitive("Hello"), ok(motd.read(JsonPrimitive("Hello"))))
        assertEquals(JsonPrimitive("large"), ok(size.read(JsonPrimitive("large"))))
    }

    @Test
    fun refusesWhatASettingCantBeSayingWhatItExpects() {
        assertEquals("4.5 isn't a whole number from 1 to 10", bad(rounds.read(JsonPrimitive(4.5))))
        assertEquals("11 isn't a whole number from 1 to 10", bad(rounds.read(JsonPrimitive(11))))
        assertEquals("\"4\" isn't a whole number from 1 to 10", bad(rounds.read(JsonPrimitive("4"))))
        assertEquals("2 isn't a number from 0 to 1", bad(ratio.read(JsonPrimitive(2))))
        assertEquals("\"true\" isn't true or false", bad(pvp.read(JsonPrimitive("true"))))
        assertEquals("3 isn't text", bad(motd.read(JsonPrimitive(3))))
        assertEquals("\"huge\" isn't one of small, large", bad(size.read(JsonPrimitive("huge"))))
    }

    @Test
    fun parsesWordsAsTyped() {
        assertEquals(JsonPrimitive(7L), ok(rounds.parse(" 7 ")))
        assertEquals(JsonPrimitive(7L), ok(rounds.parse("7.0")))
        assertEquals("\"seven\" isn't a whole number from 1 to 10", bad(rounds.parse("seven")))
        assertEquals(JsonPrimitive(true), ok(pvp.parse("On")))
        assertEquals(JsonPrimitive(false), ok(pvp.parse("no")))
        assertEquals(JsonPrimitive(" two  words "), ok(motd.parse(" two  words ")))
        assertEquals(JsonPrimitive("small"), ok(size.parse("small")))
        assertEquals("\"Small\" isn't one of small, large", bad(size.parse("Small")))
    }

    @Test
    fun givesScriptsTheirKindOfValue() {
        assertEquals(4L, rounds.value(JsonPrimitive(4L)))
        assertEquals(1.0, ratio.value(JsonPrimitive(1)))
        assertEquals(true, pvp.value(JsonPrimitive(true)))
        assertEquals("Hi", motd.value(JsonPrimitive("Hi")))
        assertEquals("1", ratio.show(JsonPrimitive(1.0)))
        assertEquals("Hi", motd.show(JsonPrimitive("Hi")))
    }

    @Test
    fun readsAValuesFileKeepingWhatDoesntFit() {
        val declared = mapOf("rounds" to rounds, "pvp" to pvp)
        val read = SettingValues.read("""{ "rounds": 4, "pvp": "yes", "gone": 1 }""", declared)!!
        assertEquals(mapOf("rounds" to JsonPrimitive(4L)), read.values)
        assertEquals(setOf("rounds", "pvp", "gone"), read.file.keys)
        assertEquals(
            listOf(
                SettingValues.Problem("pvp", "\"yes\" isn't true or false", unknown = false),
                SettingValues.Problem("gone", "there's no setting \"gone\"", unknown = true)
            ),
            read.problems
        )
        assertNull(SettingValues.read("[1, 2]", declared))
        assertNull(SettingValues.read("not json", declared))
    }

    @Test
    fun writesAValuesFileCanonically() {
        val text = SettingValues.write(mapOf("pvp" to JsonPrimitive(true), "ratio" to JsonPrimitive(0.5), "rounds" to JsonPrimitive(4L)))
        assertEquals("{\n  \"pvp\": true,\n  \"ratio\": 0.5,\n  \"rounds\": 4\n}\n", text)
    }

    @Test
    fun aManifestsSettingsRoundTripCanonically() {
        val text = """
            {
              "${'$'}schema": ".netherforge/schema/netherforge.schema.json",
              "formatVersion": 1,
              "name": "Game",
              "namespace": "game",
              "version": "1.0.0",
              "minecraft": "26.3",
              "settings": {
                "size": {
                  "type": "choice",
                  "description": "How big.",
                  "default": "small",
                  "choices": ["small", "large"]
                },
                "rounds": {
                  "type": "integer",
                  "description": "Rounds a game lasts.",
                  "default": 3,
                  "min": 1,
                  "max": 10
                }
              }
            }
        """.trimIndent()
        val manifest = assertIs<CanonicalJson.Parsed.Ok<*>>(ManifestKind.parse(text, "netherforge.json")).value
        val settings = (manifest as dev.netherforge.format.project.ProjectManifest).settings!!
        assertEquals(rounds, settings["rounds"])
        assertEquals(size, settings["size"])
        // Settings in name order; each with its type first, then its fields as declared.
        val written = ManifestKind.write(manifest)
        assertEquals(written.indexOf("\"rounds\"") < written.indexOf("\"size\""), true)
        assertEquals(manifest, (ManifestKind.parse(written, "netherforge.json") as CanonicalJson.Parsed.Ok).value)
    }
}
