package dev.netherforge.format.sky

import dev.netherforge.format.datapack.DatapackEntry
import dev.netherforge.format.datapack.GameDatapack
import dev.netherforge.format.datapack.StartupDatapack
import dev.netherforge.format.datapack.TextJson
import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.ProjectSnapshot
import dev.netherforge.format.project.Projects
import dev.netherforge.format.ref.ResourceKey
import dev.netherforge.format.testManifest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `skies/<id>.json`: its checks, and its twin biomes in the start-up datapack, the game's and the project's. */
class SkyTest {
    private val text = TextJson { _, _ -> buildJsonObject {} }

    private fun load(vararg files: Pair<String, String?>): ProjectSnapshot =
        Projects.load(MapProjectSource(mapOf("netherforge.json" to testManifest()) + files), null)

    /** The game's plains as 26.3 writes it: its sky its own, its fog the dimension's. */
    private val plains = CanonicalJson.json.parseToJsonElement(
        """
        { "attributes": { "minecraft:gameplay/natural_mob_spawns": { "argument": {} }, "minecraft:visual/sky_color": "#78a7ff" },
          "carvers": ["minecraft:cave"], "downfall": 0.4, "effects": { "water_color": "#3f76e4", "grass_color": "#91bd59" },
          "features": [["minecraft:lake_lava_underground"], [], ["minecraft:flower_plains"]], "has_precipitation": true, "temperature": 0.8 }
        """
    ).jsonObject

    private val overworld = CanonicalJson.json.parseToJsonElement(
        """{ "attributes": { "minecraft:visual/fog_color": "#c0d8ff", "minecraft:visual/sky_color": "#78a7ff" } }"""
    ).jsonObject

    private val game = GameDatapack(mapOf(ResourceKey("minecraft", "plains") to plains), overworld)

    @Test
    fun aSkyIsCheckedOnItsOwn() {
        val snapshot = load(
            "skies/good.json" to """{ "sky": "#5a0a0a", "fade": 2 }""",
            "skies/empty.json" to "{}",
            "skies/bad.json" to """{ "sky": "red", "fade": 9 }""",
            // The clouds may have an alpha; nothing else may.
            "skies/clouds.json" to """{ "clouds": "#805a0a0a", "fog": "#3a0606" }""",
            "skies/alpha.json" to """{ "clouds": "#5a0a0a0", "sky": "#ff5a0a0a" }"""
        )
        val codes = snapshot.problems.map { "${it.file} ${it.code} ${it.path}" }.sorted()
        assertEquals(
            listOf(
                "skies/alpha.json sky.color $.clouds",
                "skies/alpha.json sky.color $.sky",
                "skies/bad.json sky.color $.sky",
                "skies/bad.json sky.fade $.fade",
                "skies/empty.json sky.empty $"
            ),
            codes
        )
    }

    @Test
    fun aTwinsCloudsAreAlwaysWrittenWithTheirAlphaAndFadeIt() {
        // The game's own clouds, a little see-through, as the dimension type gives them.
        val clouded = JsonObject(
            overworld +
                (
                    "attributes" to
                        JsonObject(
                            overworld.getValue("attributes").jsonObject + ("minecraft:visual/cloud_color" to JsonPrimitive("#ccffffff"))
                        )
                    )
        )
        val defaults = SkyTwins.defaultsOf(clouded)
        fun clouds(sky: SkyFile, step: Int) = SkyTwins.twin(
            plains,
            sky,
            step,
            defaults
        ).getValue("attributes").jsonObject.getValue("minecraft:visual/cloud_color").jsonPrimitive.content
        // #rrggbb is opaque: written with its alpha, which the game needs, and faded to from the clouds' own.
        val opaque = SkyFile(clouds = "#FF0000", fade = 2)
        assertEquals("#ffff0000", clouds(opaque, 2))
        assertEquals("#e6ff8080", clouds(opaque, 1))
        // #aarrggbb as it's given, its alpha faded too.
        val faint = SkyFile(clouds = "#40000000", fade = 2)
        assertEquals("#40000000", clouds(faint, 2))
        assertEquals("#86808080", clouds(faint, 1))
        // Without the dimension's, from the sky's own.
        assertEquals(
            "#ffff0000",
            SkyTwins.twin(
                plains,
                opaque,
                1,
                emptyMap()
            ).getValue("attributes").jsonObject.getValue("minecraft:visual/cloud_color").jsonPrimitive.content
        )
        assertEquals("#80808080", SkyTwins.mix("#00000000", "#ffffffff", 0.5))
    }

    @Test
    fun aTwinIsTheBiomeWithTheSkysColoursPartOfTheWayAndNothingToGenerate() {
        val sky = SkyFile(sky = "#000000", fog = "#ff0000", water = "#000000", fade = 2)
        val defaults = SkyTwins.defaultsOf(overworld)
        val half = SkyTwins.twin(plains, sky, 1, defaults)
        val attributes = half.getValue("attributes").jsonObject
        // Half way from the biome's own sky, and from the dimension's fog where the biome has none.
        assertEquals(SkyTwins.mix("#78a7ff", "#000000", 0.5), attributes.getValue("minecraft:visual/sky_color").jsonPrimitive.content)
        assertEquals(SkyTwins.mix("#c0d8ff", "#ff0000", 0.5), attributes.getValue("minecraft:visual/fog_color").jsonPrimitive.content)
        assertTrue("minecraft:gameplay/natural_mob_spawns" !in attributes, "a twin spawns nothing")
        assertEquals(listOf(0, 0, 0), half.getValue("features").jsonArray.map { it.jsonArray.size })
        assertEquals(0, half.getValue("carvers").jsonArray.size)
        // A nether biome's carvers are a tag: none, all the same.
        val nether = JsonObject(plains + ("carvers" to JsonPrimitive("#minecraft:nether_carvers")))
        assertEquals(0, SkyTwins.twin(nether, sky, 1, defaults).getValue("carvers").jsonArray.size)
        // Everything else is the biome's.
        assertEquals("#91bd59", half.getValue("effects").jsonObject.getValue("grass_color").jsonPrimitive.content)
        assertEquals(0.8, half.getValue("temperature").jsonPrimitive.content.toDouble())
        // The last step is the sky's own colours.
        val full = SkyTwins.twin(plains, sky, 2, defaults)
        assertEquals("#000000", full.getValue("attributes").jsonObject.getValue("minecraft:visual/sky_color").jsonPrimitive.content)
        assertEquals("#000000", full.getValue("effects").jsonObject.getValue("water_color").jsonPrimitive.content)
        assertEquals("#808080", SkyTwins.mix("#ffffff", "#000000", 0.5))
    }

    @Test
    fun eachSkyHasATwinOfEveryBiomeAtEveryStepInTheDatapack() {
        val snapshot = load(
            "skies/blood_moon.json" to """{ "sky": "#5a0a0a", "fade": 2 }""",
            "biomes/grove.json" to """{ "colors": { "sky": "#f2a7c3" } }"""
        )
        assertEquals(emptyList(), snapshot.problems)
        val pack = StartupDatapack.build(snapshot, listOf(121, 0), text, game = game)
        val twins = pack.keys.filter { "/worldgen/biome/sky/" in it }.sorted()
        assertEquals(
            listOf(
                "data/test/worldgen/biome/sky/test/blood_moon/1/minecraft/plains.json",
                "data/test/worldgen/biome/sky/test/blood_moon/1/test/grove.json",
                "data/test/worldgen/biome/sky/test/blood_moon/2/minecraft/plains.json",
                "data/test/worldgen/biome/sky/test/blood_moon/2/test/grove.json"
            ),
            twins
        )
        assertEquals(
            ResourceKey("test", "sky/test/blood_moon/2/minecraft/plains"),
            SkyTwins.keyOf("test", ResourceKey("test", "blood_moon"), 2, ResourceKey("minecraft", "plains"))
        )
        val grove = CanonicalJson.json.parseToJsonElement(
            (pack.getValue("data/test/worldgen/biome/sky/test/blood_moon/2/test/grove.json") as DatapackEntry.Text).text
        ).jsonObject
        assertEquals("#5a0a0a", grove.getValue("attributes").jsonObject.getValue("minecraft:visual/sky_color").jsonPrimitive.content)
        // Without the server's biomes (the editor's check), only the project's have twins; without a sky, none.
        assertTrue(StartupDatapack.build(snapshot, listOf(121, 0), text).keys.none { "minecraft/plains" in it })
        assertTrue(StartupDatapack.build(load("biomes/grove.json" to "{}"), listOf(121, 0), text, game = game).keys.none { "/sky/" in it })
    }

    @Test
    fun aSkyThatDoesntDarkenNeedsClocksAndSomethingToHold() {
        val snapshot = load(
            "skies/held.json" to """{ "sky": "#5a0a0a", "darkens": false }""",
            "skies/nothing.json" to """{ "water": "#8a1010", "darkens": false }""",
            "skies/lit.json" to """{ "light": "#ff8080" }""",
            "skies/badlight.json" to """{ "light": "#ccff8080" }"""
        )
        assertEquals(
            listOf("skies/badlight.json sky.color $.light", "skies/nothing.json sky.night $.darkens"),
            snapshot.problems.map { "${it.file} ${it.code} ${it.path}" }.sorted()
        )
        // Before 26.1 there are no world clocks to hold it with.
        val old = Projects.load(
            MapProjectSource(
                mapOf(
                    "netherforge.json" to testManifest().replace("\"26.3\"", "\"1.21.11\""),
                    "skies/held.json" to """{ "sky": "#5a0a0a", "darkens": false }"""
                )
            ),
            null
        )
        assertEquals(listOf("project.feature $.darkens"), old.problems.map { "${it.code} ${it.path}" })
    }

    @Test
    fun aTwinTintsTheLightOnTheLandFromTheGamesWhite() {
        val sky = SkyFile(light = "#ff0000", fade = 2)
        val half = SkyTwins.twin(plains, sky, 1, SkyTwins.defaultsOf(overworld))
        // Neither plains nor the overworld gives one: the game's white.
        assertEquals("#ff8080", half.getValue("attributes").jsonObject.getValue("minecraft:visual/sky_light_color").jsonPrimitive.content)
    }

    @Test
    fun aSkyHeldAtNightIsAClockAndATimelineAfterTheDays() {
        val snapshot = load(
            "skies/blood_moon.json" to """{ "sky": "#5A0A0A", "fog": "#3a0606", "clouds": "#ff0000", "darkens": false, "fade": 2 }""",
            "skies/eclipse.json" to """{ "sky": "#000000" }"""
        )
        val pack = StartupDatapack.build(snapshot, listOf(121, 0), text, game = game)
        fun json(path: String) = CanonicalJson.json.parseToJsonElement((pack.getValue(path) as DatapackEntry.Text).text).jsonObject
        assertEquals(JsonObject(emptyMap()), json("data/test/world_clock/sky/test/blood_moon.json"))
        assertTrue(pack.keys.none { "/eclipse" in it && ("world_clock" in it || "/timeline/" in it) }, "a sky that darkens has neither")
        val timeline = json("data/test/timeline/sky/test/blood_moon.json")
        assertEquals("test:sky/test/blood_moon", timeline.getValue("clock").jsonPrimitive.content)
        val tracks = timeline.getValue("tracks").jsonObject
        // The sky and the fog, not the clouds: unseen at tick 0, all there at the last step.
        assertEquals(listOf("minecraft:visual/fog_color", "minecraft:visual/sky_color"), tracks.keys.sorted())
        val sky = tracks.getValue("minecraft:visual/sky_color").jsonObject
        assertEquals("alpha_blend", sky.getValue("modifier").jsonPrimitive.content)
        assertEquals(
            listOf("0 #005a0a0a", "2 #ff5a0a0a"),
            sky.getValue("keyframes").jsonArray.map {
                "${it.jsonObject.getValue("ticks").jsonPrimitive.content} ${it.jsonObject.getValue("value").jsonPrimitive.content}"
            }
        )
        for (tag in listOf("in_overworld", "in_nether", "in_end")) {
            assertEquals(
                listOf("test:sky/test/blood_moon"),
                json("data/minecraft/tags/timeline/$tag.json").getValue("values").jsonArray.map { it.jsonPrimitive.content }
            )
        }
        assertEquals("test:sky/test/blood_moon", SkyNight.clockIn("data/test/world_clock/sky/test/blood_moon.json"))
        assertEquals(null, SkyNight.clockIn("data/test/timeline/sky/test/blood_moon.json"))
        // A server before 26.1 (no world clocks) gets the twins alone.
        val old = StartupDatapack.build(snapshot, listOf(94, 0), text, game = game)
        assertTrue(old.keys.none { "world_clock" in it || "/timeline" in it }, "${old.keys}")
        assertTrue(old.keys.any { "/worldgen/biome/sky/test/blood_moon/" in it })
    }
}
