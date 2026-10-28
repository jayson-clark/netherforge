package dev.netherforge.format

import dev.netherforge.format.editor.CutsceneFailed
import dev.netherforge.format.editor.CutsceneResult
import dev.netherforge.format.editor.CutsceneShot
import dev.netherforge.format.editor.CutsceneTrail
import dev.netherforge.format.editor.EffectStepFailed
import dev.netherforge.format.editor.EffectStepResult
import dev.netherforge.format.editor.EffectStepped
import dev.netherforge.format.editor.PoseFailed
import dev.netherforge.format.editor.PoseResult
import dev.netherforge.format.editor.Posed
import dev.netherforge.format.editor.TerrainFailed
import dev.netherforge.format.editor.TerrainMap
import dev.netherforge.format.editor.TerrainPreviewResult
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.math.Vec3
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The JS facade (jsMain's `*Exports.kt`) as the editor and the CLI call it: JSON strings in, JSON strings out, each answer's
 * shape (the `type` of a sealed result, which fields are there, `null` for "not one of these") and how a failure is
 * reported (problems in the answer, not a throw). What each answer means is tested in `commonTest`; this is the
 * contract across the boundary.
 */
class ExportsTest {
    /** An answer as JSON. Format's JSON leaves nulls out, so "none" is a missing key. */
    private fun obj(json: String): JsonObject = CanonicalJson.json.parseToJsonElement(json).jsonObject

    private fun <T> decode(serializer: KSerializer<T>, json: String): T = CanonicalJson.json.decodeFromString(serializer, json)

    private fun files(vararg files: Pair<String, String?>): String =
        JsonObject(files.associate { (path, text) -> path to (text?.let(::JsonPrimitive) ?: JsonNull) }).toString()

    private fun codes(problems: JsonElement?): List<String> = problems!!.jsonArray.map {
        it.jsonObject.getValue("code").jsonPrimitive.content
    }

    private val manifest = testManifest()

    // ---- documents --------------------------------------------------------------------------------------

    @Test
    fun canonicalizeWritesTheCanonicalFormOrSaysWhyNot() {
        val ok = obj(canonicalize("recipe", "recipes/r.json", """{"type":"shapeless","result":{"kind":"stone"},"ingredients":["dirt"]}"""))
        val text = ok.getValue("text").jsonPrimitive.content
        assertTrue(text.startsWith("{\n  \"\$schema\": "), text)
        assertEquals(emptyList<JsonElement>(), ok.getValue("problems").jsonArray.toList())

        val broken = obj(canonicalize("recipe", "recipes/r.json", "{ nope"))
        assertNull(broken["text"], "nulls are left out")
        assertEquals(listOf("parse"), codes(broken["problems"]))
        assertEquals("recipes/r.json", broken.getValue("problems").jsonArray.single().jsonObject.getValue("file").jsonPrimitive.content)

        val unknown = obj(canonicalize("nonsense", "x.json", "{}"))
        assertNull(unknown["text"])
        assertEquals(
            "Unknown document kind \"nonsense\"",
            unknown.getValue("problems").jsonArray.single().jsonObject.getValue("message").jsonPrimitive.content
        )
    }

    @Test
    fun classifyAnswersAPathsRoleOrNull() {
        val centity = obj(classify("centities/door/centity.json"))
        assertEquals("centity", centity.getValue("kind").jsonPrimitive.content)
        assertEquals("door", centity.getValue("id").jsonPrimitive.content)
        assertEquals("centity", centity.getValue("document").jsonPrimitive.content)
        assertEquals("null", classify("notes/todo.txt"))
    }

    @Test
    fun newFilesAreTextByPath() {
        val project = obj(newProjectFiles("My Thing", "26.3"))
        assertTrue("netherforge.json" in project, project.keys.toString())
        assertTrue(project.values.all { it is JsonPrimitive && it.isString })
        assertEquals(setOf("AGENTS.md", "CLAUDE.md"), obj(newAgentFiles("My Thing")).keys)

        val recipe = obj(newResourceFiles("recipe", "bread", null, null))
        assertTrue("recipes/bread.json" in recipe.getValue("files").jsonObject, recipe.toString())
        val grouped = obj(newResourceFiles("centity", "door", null, "(doors)"))
        assertEquals(setOf("centities/(doors)/door/centity.json"), grouped.getValue("files").jsonObject.keys)
        assertFailsWith<IllegalArgumentException> { newResourceFiles("nonsense", "x", null, null) }
        assertTrue(newScript("centity", "door").isNotBlank())
        assertTrue(newTerrainScript().isNotBlank())
    }

    @Test
    fun projectRefusalNamesAnotherFormatVersionOnly() {
        assertNull(projectRefusal(manifest))
        assertNull(projectRefusal("{ not json"))
        val refusal = projectRefusal(manifest.replace("\"formatVersion\": 1", "\"formatVersion\": 99"))
        assertNotNull(refusal)
        assertTrue("99" in refusal, refusal)
    }

    // ---- projects ---------------------------------------------------------------------------------------

    @Test
    fun loadProjectOutlinesTheProjectWithItsProblems() {
        val outline = obj(
            loadProject(
                files(
                    "netherforge.json" to manifest,
                    "recipes/bread.json" to """{"type":"shapeless","result":{"kind":"bread"},"ingredients":["wheat"]}""",
                    "recipes/broken.json" to "{",
                    "modules/util/init.lua" to null
                ),
                null,
                null,
                null,
                null
            )
        )
        assertEquals("test", outline.getValue("namespace").jsonPrimitive.content)
        assertEquals(
            listOf<JsonElement>(JsonPrimitive("bread")),
            outline.getValue("resources").jsonObject.getValue("recipe").jsonArray.toList()
        )
        assertEquals(listOf("parse"), codes(outline["problems"]))
        assertEquals(
            listOf<JsonElement>(JsonPrimitive("init.lua")),
            outline.getValue("modules").jsonObject.getValue("util").jsonArray.toList()
        )
        // Every kind is in resources, empty or not, so the editor can index it without checking.
        assertTrue(outline.getValue("resources").jsonObject.keys.containsAll(listOf("centity", "menu", "dialog", "terrain")))
    }

    @Test
    fun packagesNeededAsksForWhatItHasntGotUntilItHasIt() {
        val app = files(
            "netherforge.json" to
                testManifest(""", "dependencies": { "lib": { "path": "../lib" }, "remote": { "git": "https://example.com/r.git" } }""")
        )
        val first = obj(packagesNeeded(app, null))
        assertEquals(listOf<JsonElement>(JsonPrimitive("../lib")), first.getValue("locations").jsonArray.toList())
        val fetch = first.getValue("git").jsonArray.single().jsonObject
        assertEquals("https://example.com/r.git", fetch.getValue("url").jsonPrimitive.content)
        val key = fetch.getValue("key").jsonPrimitive.content

        val lib = testManifest().replace("\"test\"", "\"lib\"")
        val inputs = """{ "folders": { "../lib": { "files": ${files(
            "netherforge.json" to lib
        )} } }, "git": { ${JsonPrimitive(key)}: { "error": "offline" } } }"""
        val second = obj(packagesNeeded(app, inputs))
        assertEquals(emptyList<JsonElement>(), second.getValue("locations").jsonArray.toList())
        assertEquals(emptyList<JsonElement>(), second.getValue("git").jsonArray.toList())
        // A fetch that failed is the problem it gives, at the dependency.
        val outline = obj(loadProject(app, inputs, null, null, null))
        assertTrue("package.git" in codes(outline["problems"]), outline["problems"].toString())
        assertTrue("lib" in outline.getValue("packages").jsonObject)
    }

    @Test
    fun aProjectValidatorValidatesOnlyWhatChanged() {
        val validator = ProjectValidator()
        validator.setFile("netherforge.json", manifest)
        validator.setFile("recipes/a.json", """{"type":"shapeless","result":{"kind":"bread"},"ingredients":["wheat"]}""")
        validator.setFile("recipes/b.json", """{"type":"shapeless","result":{"kind":"bread"},"ingredients":["wheat"]}""")
        fun validated() = obj(validator.validate()).getValue("validated").jsonArray.map { it.jsonPrimitive.content }.toSet()
        assertTrue(validated().containsAll(setOf("recipes/a.json", "recipes/b.json")))
        assertEquals(emptySet(), validated(), "nothing changed")
        validator.setFile("recipes/b.json", "{")
        assertEquals(setOf("recipes/b.json"), validated())
        assertEquals(listOf("parse"), codes(obj(validator.validate()).getValue("outline").jsonObject["problems"]))
        validator.deleteFile("recipes/b.json")
        val outline = obj(validator.validate()).getValue("outline").jsonObject
        assertEquals(emptyList<JsonElement>(), outline.getValue("problems").jsonArray.toList())
        assertEquals(
            listOf<JsonElement>(JsonPrimitive("a")),
            outline.getValue("resources").jsonObject.getValue("recipe").jsonArray.toList()
        )
        // Game data changes what's checked: every file is validated again against it.
        validator.setGameData("""{ "minecraft": "26.3", "registries": { "minecraft:item": ["minecraft:bread"] } }""")
        val again = obj(validator.validate())
        assertTrue("recipes/a.json" in again.getValue("validated").toString())
        assertEquals(listOf("recipe.unknown-item"), codes(again.getValue("outline").jsonObject["problems"]))
    }

    @Test
    fun packageHashingIsTheHostsSha256OfFormatsListing() {
        val listing = packageListing("""{ "netherforge.json": "aa", "items/gem/item.json": "bb", ".git/HEAD": "cc" }""")
        assertTrue("netherforge.json" in listing && "items/gem/item.json" in listing, listing)
        assertTrue(".git" !in listing, "only the package's content is hashed: $listing")
        assertEquals("sha256:" + "0".repeat(64), packageHash("0".repeat(64)))
        assertTrue(isPackageContent("netherforge.json"))
        assertTrue(!isPackageContent(".netherforge/cache.json"))
        assertTrue(isPackageBundled("fonts/default.json") && !isPackageContent("fonts/default.json"))
        assertEquals("git/checkouts/" + "a".repeat(40), gitCheckout("git:" + "a".repeat(40)))
        assertNull(gitCheckout("../lib"))
    }

    // ---- references -------------------------------------------------------------------------------------

    @Test
    fun referencesAreResolvedFoundRenamedAndQualified() {
        assertEquals("shop:ui/coin", resolveReference("glyph", "ui/coin", "shop"))
        assertEquals("acme:ui/coin", resolveReference("glyph", "acme:ui/coin", "shop"))
        assertNull(resolveReference("glyph", "Not A Key", "shop"))

        val menu = """{"rows":1,"skin":"ui/frame"}"""
        val project = files(
            "netherforge.json" to manifest,
            "resource_packs/ui/pack.json" to """{"skins":{"frame":{"texture":"frame.png"}}}""",
            "resource_packs/ui/textures/frame.png" to null,
            "menus/shop/menu.json" to menu
        )
        val target = """{"type":"resource","kind":"resource_pack","id":"ui"}"""
        val uses = obj(findUsages(project, target)).getValue("uses").jsonArray
        assertEquals(listOf("menus/shop/menu.json"), uses.map { it.jsonObject.getValue("file").jsonPrimitive.content })

        val renamed = renameRefs("menus/shop/menu.json", menu, "test", target, "gui")
        assertNotNull(renamed)
        assertTrue("\"skin\": \"gui/frame\"" in renamed, renamed)
        assertNull(renameRefs("menus/shop/menu.json", menu, "test", """{"type":"resource","kind":"resource_pack","id":"other"}""", "x"))
        assertNull(renameRefs("modules/a/init.lua", "return {}", "test", target, "gui"), "not a document")

        assertEquals("test:ui/frame", obj(qualifyRefs("menus/shop/menu.json", menu, "test")!!).getValue("skin").jsonPrimitive.content)
        assertNull(qualifyRefs("menus/shop/menu.json", "{ nope", "test"))
        assertNull(qualifyRefs("notes.txt", menu, "test"))

        val moved = moveRefs("menus/shop/menu.json", menu, "lib", "test", """{"type":"resource","kind":"menu","id":"shop"}""", "shop")
        assertNotNull(moved)
        assertTrue("\"skin\": \"lib:ui/frame\"" in moved, moved)
        assertNull(moveRefs("menus/shop/menu.json", menu, "lib", "test", """{"type":"file","path":"x"}""", "shop"), "only a resource moves")
    }

    // ---- previews -------------------------------------------------------------------------------------

    private val centity = """{
        "nodes": { "root": {}, "arm": { "parent": "root", "transform": { "translation": [1, 0, 0] } } },
        "animations": { "lift": { "length": 1, "tracks": { "arm": { "translation": [{ "time": 0, "value": [1, 0, 0] }, { "time": 1, "value": [1, 2, 0] }] } } } }
    }"""

    @Test
    fun aCentityPoserPosesOrSaysWhatStopsIt() {
        val poser = centityPoser("arm", centity)
        val base = assertIs<Posed>(decode(PoseResult.serializer(), poser.pose(null, 0.0)))
        assertEquals(listOf("root" to null, "arm" to "root"), base.nodes.map { it.name to it.parent })
        assertEquals(16, base.nodes[1].matrix.size)
        assertEquals(listOf(1.0, 0.0, 0.0), base.nodes[1].matrix.slice(12..14))
        assertEquals(listOf("lift"), base.animations.map { it.name })
        val lifted = assertIs<Posed>(decode(PoseResult.serializer(), poser.pose("lift", 0.5)))
        assertEquals(listOf(1.0, 1.0, 0.0), lifted.nodes[1].matrix.slice(12..14))
        // An animation it doesn't have is the base pose.
        assertEquals(base.nodes[1].matrix, assertIs<Posed>(decode(PoseResult.serializer(), poser.pose("nope", 0.5))).nodes[1].matrix)
        assertEquals(poseCentity("arm", centity, "lift", 0.5), poser.pose("lift", 0.5))

        assertEquals("failed", obj(centityPoser("x", "{").pose(null, 0.0)).getValue("type").jsonPrimitive.content)
        val invalid = assertIs<PoseFailed>(decode(PoseResult.serializer(), centityPoser("x", """{"nodes":{}}""").pose(null, 0.0)))
        assertEquals(listOf("centity.no-nodes"), invalid.problems.map { it.code })
    }

    @Test
    fun aCutsceneDirectorSamplesTheServersCameraPath() {
        val text = """{ "camera": { "position": [{ "time": 0, "value": [0, 0, 0] }, { "time": 2, "value": [10, 0, 0] }],
            "rotation": [{ "time": 0, "yaw": 350, "pitch": 0 }, { "time": 2, "yaw": 10, "pitch": 20 }] } }"""
        val director = cutsceneDirector("intro", text)
        val shot = assertIs<CutsceneShot>(decode(CutsceneResult.serializer(), director.shot(1.0)))
        assertEquals(2.0, shot.length)
        assertEquals(Vec3(5.0, 0.0, 0.0), shot.position)
        assertEquals(10.0, shot.pitch)
        assertEquals(0.0, shot.yaw % 360.0, "the short way round, through 0: ${shot.yaw}")
        assertEquals("shot", obj(director.shot(1.0)).getValue("type").jsonPrimitive.content)
        val trail = assertIs<CutsceneTrail>(decode(CutsceneResult.serializer(), director.trail(1)))
        assertEquals(listOf(Vec3.ZERO, Vec3(10.0, 0.0, 0.0)), trail.points, "at least the two ends")
        assertIs<CutsceneFailed>(decode(CutsceneResult.serializer(), cutsceneDirector("x", "{}").trail(5)))
    }

    @Test
    fun aParticleSamplerStepsAndReplaysTheSamePoints() {
        val sampler =
            particleEffectSampler(
                "puff",
                """{ "duration": 2, "emitters": { "a": { "particle": "minecraft:flame", "burst": 3, "shape": { "type": "sphere", "radius": 1 } } } }""",
                7
            )
        val first = assertIs<EffectStepped>(decode(EffectStepResult.serializer(), sampler.step(false)))
        assertEquals(0, first.tick)
        assertEquals(3, first.spawns.size)
        assertIs<EffectStepped>(decode(EffectStepResult.serializer(), sampler.step(false)))
        sampler.reset()
        assertEquals(first.spawns, assertIs<EffectStepped>(decode(EffectStepResult.serializer(), sampler.step(false))).spawns)
        val failed =
            assertIs<EffectStepFailed>(
                decode(EffectStepResult.serializer(), particleEffectSampler("x", """{ "duration": 0 }""", 1).step(true))
            )
        assertTrue(failed.problems.isNotEmpty())
    }

    @Test
    fun aCurvesValueIsJsonOrNull() {
        assertEquals("null", particleCurveAt("[]", false, 3.0))
        assertEquals("null", particleCurveAt("[]", true, 3.0))
        assertEquals(
            1.5,
            CanonicalJson.json.parseToJsonElement(
                particleCurveAt("""[{"time":0,"value":1},{"time":10,"value":2}]""", false, 5.0)
            ).jsonPrimitive.double
        )
        assertEquals("\"#ffffff\"", particleCurveAt("""[{"time":0,"color":"#ffffff"}]""", true, 5.0))
    }

    @Test
    fun aTerrainPreviewerDrawsOrSaysWhatStopsIt() {
        val previewer =
            terrainPreviewer(
                "flat",
                """{ "terrain": { "base": 64 }, "layers": [{ "block": "minecraft:grass_block" }] }""",
                null,
                null,
                null
            )
        val map = assertIs<TerrainMap>(decode(TerrainPreviewResult.serializer(), previewer.map("7", 0, 0, 4, 4, -64, 320)))
        assertEquals("map", obj(previewer.map("7", 0, 0, 4, 4, -64, 320)).getValue("type").jsonPrimitive.content)
        assertTrue(map.toString().isNotEmpty())
        previewer.close()
        val failed =
            assertIs<TerrainFailed>(
                decode(TerrainPreviewResult.serializer(), terrainPreviewer("x", "{", null, null, null).map("1", 0, 0, 2, 1, -64, 320))
            )
        assertEquals(listOf("parse"), failed.problems.map { it.code })
    }

    @Test
    fun aLootRollIsDropsOrAnError() {
        val tables = """{ "test:treasure": ${JsonPrimitive(
            """{ "pools": { "main": { "entries": [{ "type": "item", "item": { "kind": "minecraft:gold_ingot" } }] } } }"""
        )} }"""
        val roll = obj(rollLoot(tables, "test:treasure", 1))
        val drop = roll.getValue("drops").jsonArray.single().jsonObject
        assertEquals("minecraft:gold_ingot", drop.getValue("item").jsonObject.getValue("kind").jsonPrimitive.content)
        assertNull(roll["error"])
        assertTrue("isn't a loot table's full name" in obj(rollLoot(tables, "treasure!", 1)).getValue("error").jsonPrimitive.content)
        assertTrue("doesn't read" in obj(rollLoot(tables, "test:other", 1)).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun aSettingIsReadFromJsonOrFromWhatWasTyped() {
        val integer = """{ "type": "integer", "description": "Rounds", "default": 3, "min": 1, "max": 10 }"""
        assertEquals(JsonPrimitive(4), obj(readSetting(integer, "4", typed = true))["value"])
        assertEquals(JsonPrimitive(4), obj(readSetting(integer, "4", typed = false))["value"])
        assertNotNull(obj(readSetting(integer, "40", typed = true))["error"])
        assertEquals("{ isn't JSON", obj(readSetting(integer, "{", typed = false)).getValue("error").jsonPrimitive.content)
    }

    @Test
    fun resourcePacksPreviewWhatTheServerPlaces() {
        val packs = """{ "ui": ${JsonPrimitive(
            """{ "skins": { "frame": { "texture": "frame.png" } }, "glyphs": { "coin": { "texture": "coin.png" } } }"""
        )} }"""
        val preview =
            obj(
                compileResourcePacks(
                    "shop",
                    packs,
                    """{ "resource_packs/ui/textures/coin.png": { "width": 8, "height": 8, "opaqueWidth": 7 } }"""
                )
            )
        assertEquals(emptyList<JsonElement>(), preview.getValue("problems").jsonArray.toList())
        val ui = preview.getValue("resourcePacks").jsonObject.getValue("ui").jsonObject
        // A picture the editor couldn't measure has no advance, as on a server that can't read it.
        assertNull(ui.getValue("skins").jsonObject.getValue("frame").jsonObject["advance"])
        assertTrue(ui.getValue("glyphs").jsonObject.getValue("coin").jsonObject.getValue("advance") is JsonPrimitive)
        val broken = obj(compileResourcePacks("shop", """{ "ui": "{" }""", null))
        assertEquals(listOf("parse"), codes(broken["problems"]))
        assertEquals(JsonObject(emptyMap()), broken.getValue("resourcePacks"))
    }

    @Test
    fun textIsLaidOutStyledAndFittedAsTheGameDoes() {
        val advances = """{ "97": 6, "32": 4 }"""
        val layout = obj(layoutText("aa aa", 16, advances, null)).getValue("lines").jsonArray.map { it.jsonObject }
        assertEquals(listOf(12, 12), layout.map { it.getValue("width").jsonPrimitive.content.toInt() })
        assertEquals(
            listOf(0 to 2, 3 to 5),
            layout.map {
                it.getValue("start").jsonPrimitive.content.toInt() to
                    it.getValue("end").jsonPrimitive.content.toInt()
            }
        )

        val styled = obj(styleText("<red>a<glyph:ui/coin>")).getValue("chars").jsonArray.map { it.jsonObject }
        assertEquals(2, styled.size)
        assertEquals("#ff5555", styled[0].getValue("style").jsonObject.getValue("color").jsonPrimitive.content)
        assertEquals("ui/coin", styled[1].getValue("glyph").jsonPrimitive.content)

        val text = """{ "type": "text", "text": "aa", "billboard": "fixed" }"""
        assertTrue(obj(fitTextHitbox(text, advances, null)).getValue("box") is JsonObject)
        assertNull(obj(fitTextHitbox("""{ "type": "text", "text": "aa" }""", advances, null))["box"], "only fixed text has one shape")
        assertNull(obj(fitTextHitbox("""{ "type": "block", "block": "stone" }""", null, null))["box"])

        assertEquals("text:aa", fitKey(text))
        assertNull(fitKey("""{ "type": "item", "item": "apple" }"""))
        assertEquals("minecraft:oak_log[axis=x]", canonicalBlockState("oak_log[ axis=x ]".replace(" ", "")))
        assertNull(canonicalBlockState("Not A Block"))
    }

    @Test
    fun theJsonInIsTheModelsJson() {
        // A wrong shape for an input is the caller's bug: it throws, rather than answering as if it were empty.
        assertFailsWith<Exception> { findUsages("[]", """{"type":"file","path":"x"}""") }
        assertFailsWith<Exception> { readSetting("{}", "1", typed = true) }
        assertNull(obj(classify("netherforge.json"))["kind"], "a project file is no resource kind")
        assertTrue(
            obj(loadProject(files(), null, null, null, null)).getValue("problems").jsonArray.isNotEmpty(),
            "no manifest is a problem, not a throw"
        )
    }

    @Test
    fun loadProjectHoldsAReplacedGameTextureToTheSizesItIsGiven() {
        val project = files(
            "netherforge.json" to manifest,
            "client/vanilla.json" to """{"minecraft":"26.3","textures":{"gui/container/inventory":[256,256]}}""",
            "resource_packs/skin/pack.json" to """{"textures":{"gui/container/inventory":"gui/inventory.png"}}""",
            "resource_packs/skin/textures/gui/inventory.png" to null
        )
        fun problemsFor(sizes: String?) = codes(obj(loadProject(project, null, null, sizes, null))["problems"])
        assertEquals(listOf("resource_pack.replace-size"), problemsFor("""{"resource_packs/skin/textures/gui/inventory.png":[128,128]}"""))
        assertEquals(emptyList(), problemsFor("""{"resource_packs/skin/textures/gui/inventory.png":[256,256]}"""))
        // Sizes the host didn't measure aren't a problem the host can be told about.
        assertEquals(emptyList(), problemsFor(null))
    }

    @Test
    fun readWasmGivesAModulesFactsWhichLoadProjectChecks() {
        val junk = readWasm("not wasm".encodeToByteArray())
        assertTrue(obj(junk).getValue("error").jsonPrimitive.content.startsWith("it isn't a WebAssembly module"))
        val project = files("netherforge.json" to manifest, "wasm/engine/module.wasm" to null)
        fun problemsFor(wasm: String?) = codes(obj(loadProject(project, null, null, null, wasm))["problems"])
        assertEquals(listOf("wasm.invalid"), problemsFor("""{"wasm/engine/module.wasm":$junk}"""))
        // A module no one read (the editor's case) is the dev server's to check.
        assertEquals(emptyList(), problemsFor(null))
    }
}
