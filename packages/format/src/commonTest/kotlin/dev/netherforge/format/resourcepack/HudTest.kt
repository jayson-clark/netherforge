package dev.netherforge.format.resourcepack

import dev.netherforge.format.Severity
import dev.netherforge.format.block.VanillaFile
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A resource pack's `hud`: its checks, the font it builds, the game's sprites it blanks, and the line a player's HUD draws. */
class HudTest {
    private val pack = """
        {
          "hud": {
            "hide": ["health", "food"],
            "bars": {
              "health": { "full": "hud/heart.png", "half": "hud/heart_half.png", "empty": "hud/heart_empty.png", "segments": 10, "x": -91, "y": 30 },
              "mana": { "full": "hud/mana.png", "empty": "hud/mana_empty.png", "segments": 5, "x": 10, "y": 30, "gap": 1, "reverse": true }
            },
            "icons": { "effects": { "icons": { "poison": "hud/poison.png", "regen": "hud/regen.png" }, "x": -91, "y": 42 } },
            "texts": { "mana_label": { "x": 50, "align": "center" } }
          }
        }
    """.trimIndent()

    private val textures = listOf("heart", "heart_half", "heart_empty", "mana", "mana_empty", "poison", "regen")

    /** Every picture is 9 pixels tall; hearts 9 wide, the rest 7. */
    private val images = textures.associate {
        "resource_packs/ui/textures/hud/$it.png" to ImageInfo(if (it.startsWith("heart")) 9 else 7, 9, if (it.startsWith("heart")) 9 else 7)
    }

    private val sprites = listOf("hud/air", "hud/armor_full", "hud/food_full", "hud/food_half", "hud/heart/container", "hud/heart/full")
    private val vanilla = """{ "minecraft": "26.3", "sprites": [${sprites.joinToString { "\"$it\"" }}] }"""

    private fun project(extra: Map<String, String?> = emptyMap()) =
        mapOf("netherforge.json" to testManifest(), "resource_packs/ui/pack.json" to pack) +
            textures.associate { "resource_packs/ui/textures/hud/$it.png" to null } + extra

    @Test
    fun theHudBuildsItsOwnFontAndBlanksTheGamesSpritesItHides() {
        val snapshot = Projects.load(MapProjectSource(project(mapOf(VanillaFile.FILE to vanilla)), images))
        assertEquals(emptyList(), snapshot.problems)
        val files = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(75, 0), "Test", vanilla = snapshot.vanilla) {
            null
        }
        val font = Json.parseToJsonElement((files.getValue("assets/test/font/ui/hud.json") as PackLayout.Entry.Text).text).jsonObject
        val providers = font.getValue("providers").jsonArray.map { it.jsonObject }
        // Seven pictures, then the moves.
        assertEquals(8, providers.size)
        val heart = providers.first { it.getValue("file").jsonPrimitive.content == "test:ui/hud/heart.png" }
        // 30 pixels above the screen's bottom, 9 tall: an ascent of 30 + 9 - 65.
        assertEquals(-26, heart.getValue("ascent").jsonPrimitive.int)
        assertEquals(9, heart.getValue("height").jsonPrimitive.int)
        assertEquals("space", providers.last().getValue("type").jsonPrimitive.content)
        // The hearts' and hunger's sprites are blank; armour and air stay the game's.
        val blanked = files.keys.filter { it.startsWith("assets/minecraft/textures/gui/sprites/") }.sorted()
        assertEquals(
            listOf("food_full", "food_half", "heart/container", "heart/full").map { "assets/minecraft/textures/gui/sprites/hud/$it.png" },
            blanked
        )
        assertTrue(files.getValue(blanked.first()) == PackLayout.Entry.Bytes(PackLayout.BLANK_PNG))
    }

    @Test
    fun aLineMovesTheCursorNowhereSoEveryElementIsWhereItSays() {
        val hud = Projects.load(MapProjectSource(project(), images)).compiledResourcePacks.getValue("ui").hud!!
        val advances = PackFonts.advances()
        val chars = hud.pieces.associate { it.char to it.advance!! }

        // Every character the line has, moves and pictures, adds up to no width at all.
        fun width(line: String): Int {
            val text = Regex("<[^>]+>").replace(line, "")
            return text.sumOf { c -> advances[c.toString()] ?: chars[c.toString()] ?: error("an unmeasured ${c.code.toString(16)}") }
        }
        val state = HudState(
            bars = mapOf("health" to BarFill(13.0, 20.0), "mana" to BarFill(2.0, 5.0)),
            icons = mapOf("effects" to listOf("regen", "poison"))
        )
        val line = HudLine.compose(hud, state) { null }
        assertEquals(0, width(line))
        assertTrue("<font:test:ui/hud>" in line && "<!shadow>" in line)

        // Six and a half hearts of ten: six full, one half, three empty.
        val health = hud.bars.getValue("health")
        val pieces = HudLine.segments(health, 13.0, 20.0)
        assertEquals(listOf(6, 1, 3), listOf(health.full, health.half, health.empty).map { piece -> pieces.count { it === piece } })
        // A bar without a half picture rounds to whole segments, and a reversed one fills from the right.
        val mana = hud.bars.getValue("mana")
        assertEquals(listOf(mana.empty, mana.empty, mana.empty, mana.full, mana.full), HudLine.segments(mana, 2.0, 5.0))
        assertEquals(List(5) { mana.empty }, HudLine.segments(mana, -3.0, 5.0))
        assertEquals(List(5) { mana.full }, HudLine.segments(mana, 99.0, 5.0))

        // A line of text, measured, centred on its x; one that can't be measured isn't drawn.
        val text = HudState(texts = mapOf("mana_label" to "AB"))
        assertEquals("", HudLine.compose(hud, text) { null })
        val drawn = HudLine.compose(hud, text) { 12 }
        assertTrue("AB<reset>" in drawn, drawn)
        val moves = Regex("<font:test:ui/hud>(.*?)</font>").findAll(drawn).map { m ->
            m.groupValues[1].sumOf { advances.getValue(it.toString()) }
        }.toList()
        assertEquals(listOf(50 - 6, -(50 - 6 + 12)), moves)
    }

    @Test
    fun whatAHudCantBeIsSaidWhereItIs() {
        val bad = """
            {
              "hud": {
                "hide": ["health"],
                "bars": { "dup": { "full": "hud/heart.png", "empty": "hud/gone.png", "segments": 0, "x": 2000, "y": 70, "gap": -1 } },
                "icons": { "dup": { "icons": {}, "x": 0, "y": -1 } }
              }
            }
        """.trimIndent()
        val problems = Projects.load(MapProjectSource(project(mapOf("resource_packs/ui/pack.json" to bad)), images)).problems
        assertEquals(
            listOf(
                "resource_pack.texture-missing" to "$.hud.bars.dup.empty",
                "resource_pack.hud" to "$.hud.bars.dup.x",
                "resource_pack.hud" to "$.hud.bars.dup.y",
                "resource_pack.hud" to "$.hud.bars.dup.segments",
                "resource_pack.hud" to "$.hud.bars.dup.gap",
                "resource_pack.hud" to "$.hud.icons.dup",
                "resource_pack.hud" to "$.hud.icons.dup.y",
                "resource_pack.hud" to "$.hud.icons.dup.icons",
                "resource_pack.hud-hide" to "$.hud.hide"
            ),
            problems.map { it.code to it.path }
        )
    }

    private val replacing = """
        {
          "hud": { "replace": { "hud/hotbar": "gui/hotbar.png", "hud/hotbar_selection": "gui/selection.png" } },
          "textures": { "gui/container/inventory": "gui/inventory.png" }
        }
    """.trimIndent()
    private val replacingVanilla = """
        { "minecraft": "26.3", "sprites": ["hud/hotbar", "hud/hotbar_selection", "hud/heart/full"], "textures": { "gui/container/inventory": [256, 256] } }
    """.trimIndent()
    private val pictures = listOf("gui/hotbar.png", "gui/selection.png", "gui/inventory.png")

    private fun replacingProject(vanilla: String?, pack: String = replacing, size: Int = 256, extra: Map<String, String?> = emptyMap()) =
        Projects.load(
            MapProjectSource(
                mapOf("netherforge.json" to testManifest(), "resource_packs/ui/pack.json" to pack, VanillaFile.FILE to vanilla) +
                    pictures.associate { "resource_packs/ui/textures/$it" to null } + extra,
                pictures.associate { "resource_packs/ui/textures/$it" to ImageInfo(size, size, size) }
            )
        )

    @Test
    fun theBuiltPackDrawsThePacksPicturesOverTheGamesSpritesAndTextures() {
        val snapshot = replacingProject(replacingVanilla, extra = mapOf("resource_packs/ui/textures/gui/selection.png.mcmeta" to "{}"))
        assertEquals(emptyList(), snapshot.problems.filter { it.severity == Severity.ERROR })
        val files = PackLayout.build(snapshot.compiledResourcePacks.values.toList(), listOf(75, 0), "Test", vanilla = snapshot.vanilla) {
            null
        }
        val sprites = "assets/minecraft/textures/gui/sprites/hud"
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/textures/gui/hotbar.png"), files["$sprites/hotbar.png"])
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/textures/gui/selection.png"), files["$sprites/hotbar_selection.png"])
        // Only a picture with a `.mcmeta` beside it brings one.
        assertEquals(
            PackLayout.Entry.Copy("resource_packs/ui/textures/gui/selection.png.mcmeta"),
            files["$sprites/hotbar_selection.png.mcmeta"]
        )
        assertNull(files["$sprites/hotbar.png.mcmeta"])
        assertEquals(
            PackLayout.Entry.Copy("resource_packs/ui/textures/gui/inventory.png"),
            files["assets/minecraft/textures/gui/container/inventory.png"]
        )
        // Not hidden, not replaced: the game's.
        assertNull(files["$sprites/heart/full.png"])
    }

    @Test
    fun aNameTheGameLacksASizeItDoesntHaveAndAWaitingVanillaFileAreSaidWhereTheyAre() {
        fun codes(vanilla: String?, pack: String = replacing, size: Int = 256) = replacingProject(vanilla, pack, size).problems.filter {
            it.code?.startsWith("resource_pack.") == true
        }.map { it.code to it.path }
        // No client facts yet: a warning, and nothing is replaced.
        assertEquals(listOf("resource_pack.vanilla-missing" to "$.hud.replace"), codes(null))
        assertEquals(
            listOf("resource_pack.vanilla-missing" to "$.hud.replace"),
            codes("""{ "minecraft": "26.3", "sprites": ["hud/hotbar"] }""")
        )
        assertEquals(listOf("resource_pack.vanilla-missing" to "$.hud.replace"), codes(replacingVanilla.replace("26.3", "26.2")))
        assertEquals(emptyList(), codes(replacingVanilla))
        // A texture of another size is an error, and so is a name the game doesn't have.
        assertEquals(listOf("resource_pack.replace-size" to "$.textures[\"gui/container/inventory\"]"), codes(replacingVanilla, size = 128))
        val unknown = replacing.replace("hud/hotbar_selection", "hud/nothing").replace("gui/container/inventory", "gui/container/nothing")
        // A texture name the editor hasn't looked up yet waits for it; one it found the game lacks is an error.
        assertEquals(listOf("resource_pack.vanilla-missing" to "$.hud.replace"), codes(replacingVanilla, unknown))
        assertEquals(
            listOf(
                "resource_pack.replace-name" to "$.hud.replace[\"hud/nothing\"]",
                "resource_pack.replace-name" to "$.textures[\"gui/container/nothing\"]"
            ),
            codes(replacingVanilla.replace("[256, 256] } }", "[256, 256] }, \"unknownTextures\": [\"gui/container/nothing\"] }"), unknown)
        )
        // A name that couldn't be a path never gets that far.
        val shape = """{ "hud": { "replace": { "../hud/hotbar": "gui/hotbar.png" } } }"""
        assertEquals("resource_pack.replace-name", replacingProject(replacingVanilla, shape).problems.first().code)
    }

    @Test
    fun twoPacksCantReplaceTheSameTexture() {
        val second = """{ "textures": { "gui/container/inventory": "gui/inventory.png" } }"""
        val snapshot = replacingProject(
            replacingVanilla,
            extra = mapOf("resource_packs/zz/pack.json" to second, "resource_packs/zz/textures/gui/inventory.png" to null)
        )
        assertEquals(
            listOf("resource_pack.replace-name" to "resource_packs/zz/pack.json"),
            snapshot.problems.filter { it.code == "resource_pack.replace-name" }.map { it.code to it.file }
        )
    }

    @Test
    fun alignedBarsAndRowsArePlacedByWhatTheyDraw() {
        val aligned = pack.replace(""""x": -91, "y": 30 }""", """"x": -91, "y": 30, "align": "right" }""")
            .replace(""""x": -91, "y": 42 }""", """"x": -91, "y": 42, "align": "center" }""")
        val snapshot = Projects.load(
            MapProjectSource(
                project(mapOf(VanillaFile.FILE to vanilla)) + ("resource_packs/ui/pack.json" to aligned),
                images
            )
        )
        assertEquals(emptyList(), snapshot.problems)
        val hud = snapshot.compiledResourcePacks.getValue("ui").hud!!
        val advances = PackFonts.advances()

        // Where each run of the font's characters first moves the cursor to: its element's left edge.
        fun lefts(line: String): List<Int> = Regex("<font:test:ui/hud>(.*?)</font>").findAll(line).map { m ->
            m.groupValues[1].takeWhile { it.toString() in advances }.sumOf { advances.getValue(it.toString()) }
        }.toList()
        val health = hud.bars.getValue("health")
        val slot = health.slot!!

        // Four of the ten hearts, their right edge at -91; then the one icon, its middle at -91.
        val line = HudLine.compose(
            hud,
            HudState(bars = mapOf("health" to BarFill(30.0, 40.0, 4)), icons = mapOf("effects" to listOf("poison")))
        ) { null }
        val poison = hud.icons.getValue("effects").icons.getValue("poison").advance!!
        assertEquals(listOf(-91 - 4 * slot, -91 - poison / 2), lefts(line).filterIndexed { i, _ -> i == 0 || i == 1 })
        assertEquals(4, HudLine.segments(health, 30.0, 40.0, 4).size)
        // Three of four full: six halves of eight.
        assertEquals(3, HudLine.segments(health, 30.0, 40.0, 4).count { it === health.full })
        // Never more than the bar has, nor fewer than one.
        assertEquals(10, HudLine.segments(health, 1.0, 2.0, 99).size)
        assertEquals(1, HudLine.segments(health, 1.0, 2.0, 0).size)
    }
}
