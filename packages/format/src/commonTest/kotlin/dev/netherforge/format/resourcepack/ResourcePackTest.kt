package dev.netherforge.format.resourcepack

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.TestFiles
import dev.netherforge.format.project.ImageInfo
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.RefKind
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourcePackTest {

    private fun advanceOf(text: String): Int {
        val table = PackFonts.advances()
        return text.sumOf { table.getValue(it.toString()) }
    }

    @Test
    fun offsetsComposeFromPowersOfTwo() {
        assertEquals("", PackFonts.offset(0))
        for (px in listOf(-8, -1, 1, 13, -176, 2047, -2047)) {
            assertEquals(px, advanceOf(PackFonts.offset(px)), "offset $px")
        }
        assertEquals(2047, advanceOf(PackFonts.offset(5000)), "clamped")
    }

    @Test
    fun glyphCharactersAreUniqueAcrossPacksAndSkinsPerPack() {
        val packs = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "b" to ResourcePackFile(glyphs = mapOf("x" to GlyphDef("x.png")), skins = mapOf("s" to SkinDef("s.png"))),
                "a" to
                    ResourcePackFile(
                        glyphs = mapOf("y" to GlyphDef("y.png"), "z" to GlyphDef("z.png")),
                        skins = mapOf(
                            "s" to SkinDef("s.png")
                        )
                    )
            )
        )
        val glyphChars = packs.values.flatMap { pack -> pack.glyphs.values.map { it.char } }
        assertEquals(3, glyphChars.toSet().size)
        assertEquals(packs.getValue("a").skins.getValue("s").char, packs.getValue("b").skins.getValue("s").char)
        assertEquals(listOf("y", "z"), packs.getValue("a").glyphs.keys.toList())
        assertEquals(PackFonts.charAt(2), packs.getValue("b").glyphs.getValue("x").char)
    }

    @Test
    fun layoutBuildsEveryFile() {
        val pack = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "ui" to ResourcePackFile(
                    skins = mapOf("shop" to SkinDef("gui/shop.png", height = 166)),
                    glyphs = mapOf("coin" to GlyphDef("glyph/coin.png")),
                    items = mapOf("ruby" to ItemModelDef("item/ruby.png", guiTexture = "item/ruby_gui.png")),
                    tooltips = mapOf("fancy" to TooltipDef(frame = "tooltip/frame.png"))
                )
            )
        ).values.toList()
        val files = PackLayout.build(pack, listOf(75, 0), "Test") { 24 to 24 }
        assertEquals(
            listOf(
                "assets/minecraft/atlases/items.json",
                "assets/minecraft/font/default.json",
                "assets/shop/font/ui/gui.json",
                "assets/shop/items/ui/ruby.json",
                "assets/shop/models/item/ui/ruby.json",
                "assets/shop/models/item/ui/ruby_gui.json",
                "assets/shop/textures/gui/sprites/tooltip/ui/fancy_frame.png",
                "assets/shop/textures/gui/sprites/tooltip/ui/fancy_frame.png.mcmeta",
                "assets/shop/textures/ui/glyph/coin.png",
                "assets/shop/textures/ui/gui/shop.png",
                "assets/shop/textures/ui/item/ruby.png",
                "assets/shop/textures/ui/item/ruby_gui.png",
                "assets/shop/textures/ui/tooltip/frame.png",
                "pack.mcmeta"
            ),
            files.keys.toList()
        )
        val mcmeta = (files.getValue("pack.mcmeta") as PackLayout.Entry.Text).text
        assertTrue("\"min_format\": [75, 0]" in mcmeta, mcmeta)
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/textures/gui/shop.png"), files["assets/shop/textures/ui/gui/shop.png"])
        assertEquals("shop:ui/gui", pack.single().guiFont)
        val gui = (files.getValue("assets/shop/font/ui/gui.json") as PackLayout.Entry.Text).text
        assertTrue("\"file\": \"shop:ui/gui/shop.png\"" in gui && "\"height\": 166" in gui && "\"type\": \"space\"" in gui, gui)
        // The default font holds the glyphs, then the space characters any text may move the cursor with (<space:N>).
        val default = (files.getValue("assets/minecraft/font/default.json") as PackLayout.Entry.Text).text
        assertTrue("\"file\": \"shop:ui/glyph/coin.png\"" in default, default)
        assertTrue(default.trimEnd().indexOf("\"type\": \"space\"") > default.indexOf("coin.png"), default)
        for ((char, px) in PackFonts.advances()) assertEquals(px, PackFonts.spaceAdvance(char.single().code))
        // The item model `ui/ruby` is the client's `shop:ui/ruby`: the definition at assets/shop/items/ui/ruby.json.
        val item = (files.getValue("assets/shop/items/ui/ruby.json") as PackLayout.Entry.Text).text
        assertTrue("minecraft:display_context" in item && "shop:item/ui/ruby_gui" in item, item)
        val model = (files.getValue("assets/shop/models/item/ui/ruby.json") as PackLayout.Entry.Text).text
        assertTrue("\"layer0\": \"shop:ui/item/ruby\"" in model, model)
        // The game's items atlas only stitches textures/item/: the pack's item textures are added to it, or they'd draw missing.
        val items = Json.parseToJsonElement(
            (files.getValue("assets/minecraft/atlases/items.json") as PackLayout.Entry.Text).text
        ).jsonObject
        assertEquals(
            listOf("shop:ui/item/ruby", "shop:ui/item/ruby_gui"),
            items.getValue("sources").jsonArray.map {
                assertEquals("minecraft:single", it.jsonObject.getValue("type").jsonPrimitive.content)
                it.jsonObject.getValue("resource").jsonPrimitive.content
            }
        )
        val nineSlice = (files.getValue("assets/shop/textures/gui/sprites/tooltip/ui/fancy_frame.png.mcmeta") as PackLayout.Entry.Text).text
        assertTrue("\"nine_slice\"" in nineSlice && "\"width\": 24" in nineSlice, nineSlice)
    }

    @Test
    fun drawnStagesAreModelsAndTheLookWrapsThemInAUseDurationSelector() {
        val blank = PackModels.Model(JsonObject(emptyMap()), emptyMap())
        val pack = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "ui" to ResourcePackFile(
                    items = mapOf("bow" to ItemModelDef(model = "bow.json", drawn = listOf("blank.json", "blank.json", "blank.json")))
                )
            ),
            models = mapOf("ui" to mapOf("bow.json" to blank, "blank.json" to blank))
        ).values.toList()
        val files = PackLayout.build(pack, listOf(75, 0), "Test") { 24 to 24 }
        for (stage in 0..2) assertTrue("assets/shop/models/item/ui/bow_drawn$stage.json" in files, files.keys.toString())
        // At rest the item shows its look; in use, the stages, the last from a full draw (a second, at 0.05 per tick).
        val definition = Json.parseToJsonElement((files.getValue("assets/shop/items/ui/bow.json") as PackLayout.Entry.Text).text).jsonObject
        val condition = definition.getValue("model").jsonObject
        assertEquals("minecraft:condition", condition.getValue("type").jsonPrimitive.content)
        assertEquals("minecraft:using_item", condition.getValue("property").jsonPrimitive.content)
        assertEquals("shop:item/ui/bow", condition.getValue("on_false").jsonObject.getValue("model").jsonPrimitive.content)
        val dispatch = condition.getValue("on_true").jsonObject
        assertEquals("minecraft:use_duration", dispatch.getValue("property").jsonPrimitive.content)
        assertEquals(0.05, dispatch.getValue("scale").jsonPrimitive.double)
        assertEquals("shop:item/ui/bow_drawn0", dispatch.getValue("fallback").jsonObject.getValue("model").jsonPrimitive.content)
        val entries = dispatch.getValue("entries").jsonArray.map { it.jsonObject }
        assertEquals(listOf(0.5, 1.0), entries.map { it.getValue("threshold").jsonPrimitive.double })
        assertEquals(
            listOf("shop:item/ui/bow_drawn1", "shop:item/ui/bow_drawn2"),
            entries.map { it.getValue("model").jsonObject.getValue("model").jsonPrimitive.content }
        )
        // A look with no drawn stages is the plain definition: no selector.
        val plain = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "ui" to ResourcePackFile(items = mapOf("gem" to ItemModelDef("item/gem.png")))
            )
        )
            .values.toList()
        val gem = PackLayout.build(plain, listOf(75, 0), "Test") { 24 to 24 }
        assertTrue("using_item" !in (gem.getValue("assets/shop/items/ui/gem.json") as PackLayout.Entry.Text).text)
    }

    @Test
    fun drawnStagesAreCheckedAsModelFilesAndNeedAtLeastOne() {
        fun problems(drawn: List<String>?): List<Pair<String?, String?>> {
            val file = ResourcePackFile(items = mapOf("bow" to ItemModelDef(model = "bow.json", drawn = drawn)))
            return ProblemSink("resource_packs/ui/pack.json").also {
                ResourcePackValidator.validate(file, it, emptySet(), emptySet(), setOf("bow.json", "blank.json"))
            }.problems.map { it.code to it.path }
        }
        assertEquals(emptyList(), problems(listOf("blank.json")))
        assertEquals(listOf("resource_pack.model-file" to "$.items.bow.drawn[1]"), problems(listOf("blank.json", "missing.json")))
        assertEquals(listOf("resource_pack.item-look" to "$.items.bow.drawn"), problems(emptyList()))
    }

    @Test
    fun equipmentLooksBuildTheAssetAndEachLayersTexture() {
        val pack = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "gear" to ResourcePackFile(
                    equipment = mapOf(
                        "ruby" to EquipmentAssetDef(humanoid = "armor/ruby.png", humanoidLeggings = "armor/ruby_legs.png"),
                        "wings" to EquipmentAssetDef(wings = "armor/wings.png", horseBody = "armor/horse.png", wolfBody = "armor/wolf.png")
                    )
                )
            )
        ).values.toList()
        val files = PackLayout.build(pack, listOf(75, 0), "Test") { null }
        // The asset `gear/ruby` is the client's `shop:gear/ruby`: assets/shop/equipment/gear/ruby.json, its layers'
        // textures under textures/entity/equipment/<layer>/gear/ruby.png. Nothing is copied twice into textures/gear/.
        // The default font is always there, holding the space characters any text may move the cursor with.
        assertEquals(
            listOf(
                "assets/minecraft/font/default.json",
                "assets/shop/equipment/gear/ruby.json",
                "assets/shop/equipment/gear/wings.json",
                "assets/shop/textures/entity/equipment/horse_body/gear/wings.png",
                "assets/shop/textures/entity/equipment/humanoid/gear/ruby.png",
                "assets/shop/textures/entity/equipment/humanoid_leggings/gear/ruby.png",
                "assets/shop/textures/entity/equipment/wings/gear/wings.png",
                "assets/shop/textures/entity/equipment/wolf_body/gear/wings.png",
                "pack.mcmeta"
            ),
            files.keys.toList()
        )
        assertEquals(
            PackLayout.Entry.Copy("resource_packs/gear/textures/armor/ruby_legs.png"),
            files["assets/shop/textures/entity/equipment/humanoid_leggings/gear/ruby.png"]
        )
        val ruby = (files.getValue("assets/shop/equipment/gear/ruby.json") as PackLayout.Entry.Text).text
        assertEquals(
            Json.parseToJsonElement(
                """{ "layers": { "humanoid": [{ "texture": "shop:gear/ruby" }], "humanoid_leggings": [{ "texture": "shop:gear/ruby" }] } }"""
            ),
            Json.parseToJsonElement(ruby)
        )
        // Deterministic: same input, same files in the same order.
        assertEquals(files, PackLayout.build(pack, listOf(75, 0), "Test") { null })
    }

    @Test
    fun bitmapAdvanceFollowsTheGame() {
        // Opaque 3 of a 2-tall image drawn 4 tall: 6, plus a pixel of gap.
        assertEquals(7, PackFonts.bitmapAdvance(opaqueWidth = 3, imageHeight = 2, drawnHeight = 4))
        // Nothing opaque: just the gap.
        assertEquals(1, PackFonts.bitmapAdvance(0, 2, 8))
        // Rounds half up: 5 × 8 / 16 = 2.5 → 3.
        assertEquals(4, PackFonts.bitmapAdvance(5, 16, 8))
        // A 176-wide window background drawn at its own height.
        assertEquals(177, PackFonts.bitmapAdvance(176, 168, 168))
    }

    /** The table the editor's fonts.rs (which measures the client's own font) is held to as well. */
    @Test
    fun bitmapAdvanceMatchesTheSharedTable() {
        val table = Json.parseToJsonElement(TestFiles.read("packages/format/testdata/text/bitmap-advances.json")!!)
        for (case in table.jsonObject.getValue("cases").jsonArray.map { it.jsonObject }) {
            fun int(key: String) = case.getValue(key).jsonPrimitive.int
            assertEquals(int("advance"), PackFonts.bitmapAdvance(int("opaqueWidth"), int("imageHeight"), int("drawnHeight")), "$case")
        }
    }

    @Test
    fun skinTitlePrefixDrawsThenReturnsToWhereTheTitleStarts() {
        val packs = CompiledResourcePack.compileAll(
            "shop",
            mapOf("ui" to ResourcePackFile(skins = mapOf("s" to SkinDef("s.png", height = 168), "t" to SkinDef("t.png", offset = 5))))
        ) { path -> if (path == "resource_packs/ui/textures/s.png") ImageInfo(176, 168, 176) else null }
        val skin = packs.getValue("ui").skins.getValue("s")
        assertEquals(177, skin.advance)
        val (before, after) = skin.titlePrefix.split(skin.char)
        assertEquals(SkinDef.DEFAULT_OFFSET, advanceOf(before))
        // The spaces and the picture's own advance net to nothing: the words start where the title does.
        assertEquals(0, advanceOf(before) + skin.advance!! + advanceOf(after))

        // A picture nobody could read: no way back.
        val unknown = packs.getValue("ui").skins.getValue("t")
        assertNull(unknown.advance)
        assertEquals(PackFonts.offset(5) + unknown.char, unknown.titlePrefix)
    }

    @Test
    fun glyphsKnowTheirAdvance() {
        val glyph = CompiledResourcePack.compileAll(
            "shop",
            mapOf("ui" to ResourcePackFile(glyphs = mapOf("coin" to GlyphDef("coin.png"))))
        ) {
            ImageInfo(16, 16, 14)
        }.getValue("ui").glyphs.getValue("coin")
        // 14 × 8 / 16 = 7, + 1.
        assertEquals(8, glyph.advance)
    }

    @Test
    fun aPictureCutsIntoTheFewestEvenPiecesThatFitAFontSheet() {
        assertEquals(1, PackFonts.pieces(256, 38, 19))
        assertEquals(2, PackFonts.pieces(424, 38, 19))
        // No even cut: the game's grid would drop what's left over.
        assertNull(PackFonts.pieces(257, 38, 19))
        assertEquals(258, PackFonts.evenWidth(257))
        // Four pieces would be 160 wide, half a pixel drawn 1 of 64 tall; five meet without a seam.
        assertEquals(5, PackFonts.pieces(640, 64, 1))
        assertEquals(4, PackFonts.pieces(640, 64, 2))
    }

    @Test
    fun aGlyphTooWideForAFontSheetIsDrawnInPiecesSideBySide() {
        // The plate's left piece has 12 transparent columns at its right edge; the right piece 4.
        val columns = List(424) { it !in 200 until 212 && it < 420 }
        val packs = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "ui" to ResourcePackFile(
                    glyphs = mapOf("coin" to GlyphDef("coin.png"), "plate" to GlyphDef("plate.png", height = 19, ascent = 9)),
                    skins = mapOf("chest" to SkinDef("chest.png", height = 222))
                )
            )
        ) { path ->
            when (path) {
                "resource_packs/ui/textures/plate.png" -> ImageInfo(424, 38, 420, columns)
                "resource_packs/ui/textures/chest.png" -> ImageInfo(512, 222, 512)
                else -> ImageInfo(16, 16, 14)
            }
        }
        val ui = packs.getValue("ui")
        // Every glyph keeps its own character; the plate's second piece comes after them all.
        assertEquals(PackFonts.charAt(0), ui.glyphs.getValue("coin").char)
        val plate = ui.glyphs.getValue("plate")
        assertEquals(PackFonts.charAt(1) + PackFonts.charAt(2), plate.cells)
        // As one picture: 420 × 19 / 38 = 210, + 1.
        assertEquals(211, plate.advance)
        // Pieces 212 wide, drawn 106: the left one advances 200 / 2 + 1 = 101, so 5 more puts the right one at 106;
        // it advances 208 / 2 + 1 = 105, which ends where the whole picture would.
        assertEquals(PackFonts.charAt(1) + PackFonts.offset(5) + PackFonts.charAt(2), plate.char)

        val layout = PackLayout.build(packs.values.toList(), listOf(75, 0), "Test") { null }
        val font = Json.parseToJsonElement((layout.getValue("assets/minecraft/font/default.json") as PackLayout.Entry.Text).text)
        val provider = font.jsonObject.getValue("providers").jsonArray.map { it.jsonObject }.single {
            it["file"]?.jsonPrimitive?.content == "shop:ui/plate.png"
        }
        assertEquals(plate.cells, provider.getValue("chars").jsonArray.single().jsonPrimitive.content)

        // A skin is cut the same way, in its own font, and its title prefix still nets to nothing.
        val chest = ui.skins.getValue("chest")
        assertEquals(PackFonts.charAt(0) + PackFonts.charAt(1), chest.cells)
        val (before, after) = chest.titlePrefix.split(chest.char)
        assertEquals(0, advanceOf(before) + chest.advance!! + advanceOf(after))
    }

    @Test
    fun aPictureTooTallOrThatWontCutEvenlyIsAnError() {
        val files = mapOf(
            "netherforge.json" to testManifest(),
            "resource_packs/ui/pack.json" to
                """{ "glyphs": { "odd": { "texture": "odd.png" }, "plate": { "texture": "plate.png" }, "tall": { "texture": "tall.png" } } }""",
            "resource_packs/ui/textures/odd.png" to null,
            "resource_packs/ui/textures/plate.png" to null,
            "resource_packs/ui/textures/tall.png" to null
        )
        val sizes = mapOf(
            "resource_packs/ui/textures/odd.png" to listOf(257, 38),
            "resource_packs/ui/textures/plate.png" to listOf(424, 38),
            "resource_packs/ui/textures/tall.png" to listOf(16, 300)
        )
        val problems = Projects.load(MapProjectSource(files, sizes = sizes)).problems
        assertEquals(
            listOf("resource_pack.picture-size" to "$.glyphs.odd.texture", "resource_pack.picture-size" to "$.glyphs.tall.texture"),
            problems.map { it.code to it.path }
        )
        assertTrue("Pad it with transparent columns to 258 pixels wide" in problems[0].message, problems[0].message)
        assertTrue(problems[1].message.startsWith("Glyph tall is 16×300 pixels"), problems[1].message)
    }

    @Test
    fun aSkinWhosePictureCantBeReadIsWarnedAbout() {
        val files = mapOf(
            "netherforge.json" to
                testManifest(),
            "resource_packs/ui/pack.json" to """{ "skins": { "a": { "texture": "a.png", "height": 168 }, "b": { "texture": "b.png" } } }""",
            "resource_packs/ui/textures/a.png" to null,
            "resource_packs/ui/textures/b.png" to null
        )
        val readable = mapOf("resource_packs/ui/textures/a.png" to ImageInfo(176, 168, 176))
        val snapshot = Projects.load(MapProjectSource(files, readable))
        assertEquals(listOf("resource_pack.image" to "$.skins.b.texture"), snapshot.problems.map { it.code to it.path })
        assertEquals(177, snapshot.compiledResourcePacks.getValue("ui").skins.getValue("a").advance)
        // A source that can't read pixels at all says nothing.
        assertEquals(emptyList(), Projects.load(MapProjectSource(files)).problems)
    }

    @Test
    fun tooManyGlyphsAcrossPacksIsAProblemNotACrash() {
        // Glyphs share one font, so two packs that fit alone can overflow together.
        val half = PackFonts.PUA_COUNT / 2 + 1
        fun pack(count: Int) = (0 until count).joinToString(",", """{ "glyphs": {""", "} }") { """"g$it": { "texture": "a.png" }""" }
        val files = mapOf(
            "netherforge.json" to
                testManifest(),
            "resource_packs/a/pack.json" to pack(half),
            "resource_packs/a/textures/a.png" to null,
            "resource_packs/b/pack.json" to pack(half),
            "resource_packs/b/textures/a.png" to null
        )
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(listOf("resource_packs/b/pack.json" to "resource_pack.too-many-glyphs"), snapshot.problems.map { it.file to it.code })
        assertTrue(snapshot.compiledResourcePacks.isEmpty())
    }

    /** Thousands of skins are too many for a golden file; the limit is the private-use characters a pack's skin font has. */
    @Test
    fun aPackHoldsAtMostAFontsWorthOfSkins() {
        fun problems(count: Int): List<Pair<String?, String?>> {
            val file = ResourcePackFile(skins = (0 until count).associate { "s$it" to SkinDef(texture = "a.png") })
            return ProblemSink("resource_packs/ui/pack.json").also {
                ResourcePackValidator.validate(file, it, setOf("a.png"))
            }.problems.map {
                it.code to it.path
            }
        }
        assertEquals(emptyList(), problems(PackFonts.PUA_COUNT))
        assertEquals(listOf("resource_pack.too-many" to "$.skins"), problems(PackFonts.PUA_COUNT + 1))
    }

    @Test
    fun soundFilesAreSoundEventsInSoundsJson() {
        val pack = CompiledResourcePack.compileAll(
            "shop",
            mapOf(
                "ui" to ResourcePackFile(
                    sounds = mapOf(
                        "menu/open" to SoundDef(volume = 0.5, subtitle = "Menu opens"),
                        "step" to SoundDef(files = listOf("step/a.ogg", "step/b.ogg"), pitch = 1.5)
                    )
                )
            ),
            soundFiles = mapOf("ui" to setOf("click.ogg", "menu/open.ogg", "step/a.ogg", "step/b.ogg"))
        ).getValue("ui")
        assertEquals(listOf("click", "menu/open", "step", "step/a", "step/b"), pack.sounds.keys.toList())

        val files = PackLayout.build(listOf(pack), listOf(75, 0), "Test") { null }
        assertEquals(PackLayout.Entry.Copy("resource_packs/ui/sounds/menu/open.ogg"), files["assets/shop/sounds/ui/menu/open.ogg"])
        assertEquals(
            listOf(
                "assets/shop/sounds.json",
                "assets/shop/sounds/ui/click.ogg",
                "assets/shop/sounds/ui/menu/open.ogg",
                "assets/shop/sounds/ui/step/a.ogg",
                "assets/shop/sounds/ui/step/b.ogg"
            ),
            files.keys.filter { it.startsWith("assets/shop/sounds") }
        )
        // Events are the sound ids `shop:ui/<key>`; their files where the game looks, under sounds/.
        val json = Json.parseToJsonElement((files.getValue("assets/shop/sounds.json") as PackLayout.Entry.Text).text).jsonObject
        assertEquals(Json.parseToJsonElement("""{ "sounds": ["shop:ui/click"] }"""), json.getValue("ui/click"))
        assertEquals(
            Json.parseToJsonElement("""{ "sounds": [{ "name": "shop:ui/menu/open", "volume": 0.5 }], "subtitle": "Menu opens" }"""),
            json.getValue("ui/menu/open")
        )
        assertEquals(
            Json.parseToJsonElement(
                """{ "sounds": [{ "name": "shop:ui/step/a", "pitch": 1.5 }, { "name": "shop:ui/step/b", "pitch": 1.5 }] }"""
            ),
            json.getValue("ui/step")
        )
    }

    @Test
    fun theProjectKnowsEveryPackSound() {
        val files = mapOf(
            "netherforge.json" to
                testManifest(),
            "resource_packs/ui/pack.json" to """{ "sounds": { "chime": { "files": ["bell.ogg"] } } }""",
            "resource_packs/ui/sounds/bell.ogg" to null,
            "resource_packs/ui/sounds/menu/open.ogg" to null,
            "resource_packs/ui/sounds/.DS_Store" to null,
            "resource_packs/fx/pack.json" to "{}",
            "resource_packs/fx/sounds/boom.ogg" to null
        )
        val snapshot = Projects.load(MapProjectSource(files))
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(listOf("fx/boom", "ui/bell", "ui/chime", "ui/menu/open"), snapshot.references.names(RefKind.SOUND))
        assertEquals(setOf("bell.ogg", "menu/open.ogg"), snapshot.compiledResourcePacks.getValue("ui").soundFiles())
    }

    @Test
    fun pngSizeReadsTheHeader() {
        val header = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10, 0, 0, 0, 13, 73, 72, 68, 82, 0, 0, 0, -80, 0, 0, 0, -90)
        assertEquals(176 to 166, pngSize(header))
        assertNull(pngSize(byteArrayOf(1, 2, 3)))
    }
}
