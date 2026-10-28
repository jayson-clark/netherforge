package dev.netherforge.format.dialog

import dev.netherforge.format.Severity
import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.datapack.TextJson
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `pauseMenu` and `quickActions`: the dialogs in the player's own menus, through the start-up datapack. */
class DialogMenusTest {
    private val text = TextJson { mini, _ -> buildJsonObject { put("mini", mini) } }

    private fun build(vararg dialogs: Pair<String, String>): Map<String, DatapackEntry> {
        val files = dialogs.associate { (id, json) -> "dialogs/$id/dialog.json" to json } + ("netherforge.json" to testManifest())
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(emptyList(), snapshot.problems.filter { it.severity == Severity.ERROR })
        return StartupDatapack.build(snapshot, listOf(94, 1), text)
    }

    private fun json(entries: Map<String, DatapackEntry>, path: String): JsonObject =
        CanonicalJson.json.parseToJsonElement((entries.getValue(path) as DatapackEntry.Text).text).jsonObject

    private fun values(entries: Map<String, DatapackEntry>, tag: String) =
        json(entries, tag)["values"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    private fun JsonObject.obj(key: String) = getValue(key).jsonObject

    @Test
    fun theFlagsAreInTheFileAndWriteBackCanonically() {
        val file = """{"title":"t","pauseMenu":true,"quickActions":false}"""
        val parsed = (DialogKind.parse(file, "x") as CanonicalJson.Parsed.Ok).value
        assertEquals(true, parsed.pauseMenu)
        assertEquals(false, parsed.quickActions)
        val written = DialogKind.write(parsed)
        assertTrue("\"pauseMenu\": true" in written && "\"quickActions\": false" in written, written)
        assertEquals(written, DialogKind.write((DialogKind.parse(written, "x") as CanonicalJson.Parsed.Ok).value))
    }

    @Test
    fun aDialogOnNoMenuPutsNothingInTheDatapack() {
        assertEquals(emptyMap(), build("plain" to """{"title":"t"}"""))
    }

    @Test
    fun theTagsListTheMenusDialogsAndTheirFilesAreTheGamesFormat() {
        val datapack = build(
            "settings" to
                """{"title":"<gold>Settings","type":"multi_action","pauseMenu":true,"columns":3,
                    "body":[{"type":"message","text":"Hi","width":150}],
                    "inputs":[{"type":"text","key":"name","maxLength":8,"lines":3},
                              {"type":"boolean","key":"loud","onTrue":"yes"},
                              {"type":"single_option","key":"mode","options":[{"id":"a"},{"id":"b","label":"B","initial":true}]},
                              {"type":"number_range","key":"n","start":0,"end":10,"step":2}],
                    "buttons":[{"key":"save","label":"Save","tooltip":"Keep it","width":80},{"key":"reset"}]}""",
            "help" to """{"title":"Help","quickActions":true}""",
            "both" to """{"title":"Both","pauseMenu":true,"quickActions":true}""",
            "other" to """{"title":"Not on a menu"}"""
        )
        assertEquals(
            listOf(
                "data/minecraft/tags/dialog/pause_screen_additions.json",
                "data/minecraft/tags/dialog/quick_actions.json",
                "data/test/dialog/both.json",
                "data/test/dialog/help.json",
                "data/test/dialog/settings.json",
                "pack.mcmeta"
            ),
            datapack.keys.toList()
        )
        assertEquals(listOf("test:both", "test:settings"), values(datapack, DialogJson.PAUSE_TAG))
        assertEquals(listOf("test:both", "test:help"), values(datapack, DialogJson.QUICK_ACTIONS_TAG))

        val settings = json(datapack, "data/test/dialog/settings.json")
        assertEquals("minecraft:multi_action", settings.str("type"))
        assertEquals("3", settings.str("columns"))
        assertEquals("false", settings.str("pause"))
        assertEquals("<gold>Settings", settings.obj("title").str("mini"))
        val actions = settings.getValue("actions").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("test:dialog/settings/press/0", "test:dialog/settings/press/1"),
            actions.map { it.obj("action").str("id") }
        )
        assertEquals("minecraft:dynamic/custom", actions[0].obj("action").str("type"))
        assertEquals("Keep it", actions[0].obj("tooltip").str("mini"))
        assertEquals("test:dialog/settings/exit", settings.obj("exit_action").obj("action").str("id"))
        val inputs = settings.getValue("inputs").jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("minecraft:text", "minecraft:boolean", "minecraft:single_option", "minecraft:number_range"),
            inputs.map { it.str("type") }
        )
        assertEquals("3", inputs[0].obj("multiline").str("max_lines"))
        assertEquals("yes", inputs[1].str("on_true"))
        assertEquals("true", inputs[2].getValue("options").jsonArray[1].jsonObject.str("initial"))
        // A notice with no button gets the exit button in its place.
        val help = json(datapack, "data/test/dialog/help.json")
        assertEquals("minecraft:notice", help.str("type"))
        assertEquals("gui.ok", help.obj("action").obj("label").str("translate"))
        assertEquals("test:dialog/help/exit", help.obj("action").obj("action").str("id"))
    }

    @Test
    fun aMultiActionWithNoButtonsHasItsExitOneAsTheGameWantsAnAction() {
        val datapack = build("empty" to """{"title":"Empty","type":"multi_action","quickActions":true}""")
        val actions = json(datapack, "data/test/dialog/empty.json").getValue("actions").jsonArray
        assertEquals(listOf("test:dialog/empty/exit"), actions.map { it.jsonObject.obj("action").str("id") })
    }

    @Test
    fun aListOnAMenuBringsTheDialogsItListsIntoTheRegistry() {
        val datapack = build(
            "hub" to
                """{"title":"Hub","type":"dialog_list","pauseMenu":true,"dialogs":["rules"],"buttons":[{"key":"back","label":"Back"}]}""",
            "rules" to """{"title":"Rules"}"""
        )
        assertTrue("data/test/dialog/rules.json" in datapack)
        val hub = json(datapack, "data/test/dialog/hub.json")
        assertEquals(listOf(JsonPrimitive("test:rules")), hub.getValue("dialogs").jsonArray.toList())
        assertEquals("test:dialog/hub/exit", hub.obj("exit_action").obj("action").str("id"))
        // The listed one isn't on a tag.
        assertEquals(listOf("test:hub"), values(datapack, DialogJson.PAUSE_TAG))
    }

    @Test
    fun aClickIdSaysWhichDialogAndButton() {
        val key = ResourceKey("test", "shop/main")
        assertEquals(DialogJson.Click.Press("shop/main", 2), DialogJson.click(DialogJson.pressId(key, 2), "test"))
        assertEquals(DialogJson.Click.Exit("shop/main"), DialogJson.click(DialogJson.exitId(key), "test"))
        // A package's dialog is named as the server names it.
        assertEquals(DialogJson.Click.Press("acme:help", 0), DialogJson.click("acme:dialog/help/press/0", "test"))
        assertNull(DialogJson.click("paper:click/1234", "test"))
        assertNull(DialogJson.click("test:dialog/help/press/x", "test"))
        assertNull(DialogJson.click("test:other/help/press/0", "test"))
    }

    @Test
    fun theSameFilesGiveTheSameBytesAndAChangeGivesOtherOnes() {
        val files = arrayOf("a" to """{"title":"A","pauseMenu":true}""", "b" to """{"title":"B","quickActions":true}""")
        assertEquals(build(*files), build(*files))
        assertTrue(build(*files) != build("a" to """{"title":"A2","pauseMenu":true}""", files[1]))
    }
}
