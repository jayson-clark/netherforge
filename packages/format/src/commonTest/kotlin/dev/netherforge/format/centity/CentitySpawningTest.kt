package dev.netherforge.format.centity

import dev.netherforge.format.ProblemSink
import dev.netherforge.format.game.BlockInfo
import dev.netherforge.format.game.GameDataBundle
import dev.netherforge.format.game.RegistryKey
import dev.netherforge.format.json.CanonicalJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The `spawning` block of `centity.json`: defaults, canonical form and what the validator refuses. */
class CentitySpawningTest {
    private val nodes = mapOf("root" to NodeDef(display = BlockDisplay("minecraft:stone")))

    private val game = GameDataBundle(
        minecraft = "26.3",
        blocks = mapOf("minecraft:stone" to BlockInfo()),
        registries = mapOf(
            RegistryKey.BIOME.id to listOf("minecraft:plains", "minecraft:the_end"),
            RegistryKey.BLOCK.id to listOf("minecraft:end_stone", "minecraft:grass_block")
        ),
        tags = mapOf(
            RegistryKey.BIOME.id to mapOf("minecraft:is_end" to listOf("minecraft:the_end")),
            RegistryKey.BLOCK.id to mapOf("minecraft:dirt" to listOf("minecraft:grass_block"))
        )
    )

    private fun problems(spawning: SpawningDef, game: GameDataBundle? = this.game): List<Pair<String?, String?>> {
        val sink = ProblemSink("c")
        CentityValidator.validate(CentityFile(nodes = nodes, spawning = spawning), sink, emptySet(), game)
        return sink.problems.map { it.code to it.path }
    }

    @Test
    fun aBlockWithNothingMeansTheDefaults() {
        val spawning = SpawningDef()
        assertEquals(10, spawning.weightOrDefault)
        assertEquals(4, spawning.capOrDefault)
        assertEquals(96, spawning.despawnDistanceOrDefault)
        assertEquals(1, spawning.groupMin)
        assertEquals(1, spawning.groupMax)
        assertEquals(0, spawning.lightMin)
        assertEquals(15, spawning.lightMax)
        assertTrue(problems(spawning).isEmpty())
    }

    @Test
    fun aValidBlockHasNoProblemsAndCompilesIn() {
        val spawning = SpawningDef(
            worlds = listOf("world_the_end"),
            biomes = listOf("#minecraft:is_end", "minecraft:plains"),
            blocks = listOf("end_stone", "#minecraft:dirt"),
            light = SpawnRange(max = 7),
            height = SpawnRange(min = 40),
            weight = 4,
            group = SpawnRange(min = 1, max = 3),
            cap = 6,
            despawnDistance = 80
        )
        assertTrue(problems(spawning).isEmpty(), "${problems(spawning)}")
        val compiled = CentityCompiler.compile("wisp", CentityFile(nodes = nodes, spawning = spawning))
        assertEquals(spawning, compiled.spawning)
        assertNull(CentityCompiler.compile("plain", CentityFile(nodes = nodes)).spawning)
    }

    @Test
    fun idsAreCheckedAgainstTheGame() {
        val bad = SpawningDef(
            biomes = listOf("minecraft:nope", "#minecraft:nothing", "minecraft:Not A Biome"),
            blocks = listOf("minecraft:nope", "#minecraft:nothing")
        )
        assertEquals(
            listOf(
                "centity.spawning-biome" to "$.spawning.biomes[0]",
                "centity.spawning-biome" to "$.spawning.biomes[1]",
                "centity.spawning-biome" to "$.spawning.biomes[2]",
                "centity.spawning-block" to "$.spawning.blocks[0]",
                "centity.spawning-block" to "$.spawning.blocks[1]"
            ),
            problems(bad)
        )
        // Without game data only the shape can be wrong.
        assertEquals(listOf("centity.spawning-biome" to "$.spawning.biomes[2]"), problems(bad.copy(blocks = null), game = null))
    }

    @Test
    fun aDespawnDistanceWithinReachIsWarnedAbout() {
        assertEquals(
            listOf("centity.spawning-despawn" to "$.spawning.despawnDistance"),
            problems(SpawningDef(despawnDistance = SpawningDef.MAX_DISTANCE))
        )
        assertTrue(problems(SpawningDef(despawnDistance = SpawningDef.MAX_DISTANCE + 1)).isEmpty())
    }

    @Test
    fun theCanonicalFormSortsAndDeduplicatesItsLists() {
        val file = CentityFile(
            nodes = nodes,
            spawning = SpawningDef(worlds = listOf("b", "a", "b"), biomes = listOf("z", "y"), blocks = listOf("x"), weight = 2)
        )
        val text = CentityKind.write(file)
        assertTrue("\"worlds\": [\"a\", \"b\"]" in text || "\"a\"" in text && text.indexOf("\"a\"") < text.indexOf("\"b\""), text)
        val parsed = CentityKind.parse(text, "centities/c/centity.json") as CanonicalJson.Parsed.Ok
        assertEquals(listOf("a", "b"), parsed.value.spawning?.worlds)
        assertEquals(listOf("y", "z"), parsed.value.spawning?.biomes)
        assertEquals(text, CentityKind.write(parsed.value))
    }

    @Test
    fun keepOnInteractIsABooleanThatDefaultsToFalseAndRoundTrips() {
        assertEquals(false, SpawningDef().keepOnInteractOrDefault)
        assertTrue(problems(SpawningDef(keepOnInteract = true)).isEmpty())
        val file = CentityFile(nodes = nodes, spawning = SpawningDef(keepOnInteract = true))
        val text = CentityKind.write(file)
        assertTrue("\"keepOnInteract\": true" in text, text)
        val parsed = CentityKind.parse(text, "centities/c/centity.json") as CanonicalJson.Parsed.Ok
        assertEquals(true, parsed.value.spawning?.keepOnInteract)
        assertEquals(text, CentityKind.write(parsed.value))
        assertEquals(true, CentityCompiler.compile("c", file).spawning?.keepOnInteract)
        // Absent stays out of the file.
        assertTrue("keepOnInteract" !in CentityKind.write(CentityFile(nodes = nodes, spawning = SpawningDef())))
    }

    private fun parse(spawning: String): CanonicalJson.Parsed<CentityFile> =
        CentityKind.parse("""{ "nodes": { "root": {} }, "spawning": $spawning }""", "centities/c/centity.json")

    @Test
    fun timeIsADayHalfOrARangeOfTicksThatMayRunThroughMidnight() {
        val night = (parse("""{ "time": "night" }""") as CanonicalJson.Parsed.Ok).value.spawning!!.time!!
        assertEquals(DayPeriod.NIGHT, night.period)
        assertTrue(13_000 in night && 22_999 in night)
        assertFalse(23_000 in night || 12_999 in night || 6000 in night)
        val day = SpawnTime(period = DayPeriod.DAY)
        assertTrue(23_000 in day && 0 in day && 12_999 in day, "day runs through midnight, from sunrise")
        assertFalse(13_000 in day)
        // Every tick is in exactly one half.
        assertTrue((0..SpawnTime.LAST_TICK).all { (it in day) != (it in night) })

        val late = (parse("""{ "time": { "min": 22000, "max": 2000 } }""") as CanonicalJson.Parsed.Ok).value.spawning!!.time!!
        assertTrue(22_000 in late && 23_999 in late && 0 in late && 2000 in late)
        assertFalse(2001 in late || 21_999 in late)
        val evening = SpawnTime(range = SpawnRange(min = 12_000))
        assertTrue(12_000 in evening && SpawnTime.LAST_TICK in evening)
        assertFalse(0 in evening)

        // Written back as it was read.
        for (time in listOf("\"day\"", "\"night\"", "{ \"min\": 22000, \"max\": 2000 }", "{ \"max\": 500 }")) {
            val parsed = parse("""{ "time": $time }""") as CanonicalJson.Parsed.Ok
            val text = CentityKind.write(parsed.value)
            assertEquals(
                parsed.value.spawning,
                (CentityKind.parse(text, "centities/c/centity.json") as CanonicalJson.Parsed.Ok).value.spawning
            )
        }
        assertTrue("\"time\": \"night\"" in CentityKind.write(CentityFile(nodes = nodes, spawning = SpawningDef(time = night))))

        for (bad in listOf("\"dusk\"", "1000", "{ \"min\": 1, \"start\": 2 }", "[\"day\"]")) {
            assertTrue(parse("""{ "time": $bad }""") is CanonicalJson.Parsed.Failed, bad)
        }
    }

    @Test
    fun ticksOutsideTheDayAndConditionsThatArentNamesAreProblems() {
        assertEquals(
            listOf("centity.spawning-time" to "$.spawning.time.min", "centity.spawning-time" to "$.spawning.time.max"),
            problems(SpawningDef(time = SpawnTime(range = SpawnRange(min = -1, max = 24_000))))
        )
        assertTrue(problems(SpawningDef(time = SpawnTime(range = SpawnRange(min = 23_999, max = 0)))).isEmpty())
        for (good in listOf("blood_moon", "acme:blood_moon")) assertTrue(problems(SpawningDef(condition = good)).isEmpty(), good)
        for (bad in listOf("Blood Moon", "a:b:c", ":x", "")) {
            assertEquals(listOf("centity.spawning-when" to "$.spawning.when"), problems(SpawningDef(condition = bad)), bad)
        }
    }

    @Test
    fun moonAndWeatherAreSortedListsAndWhenIsWrittenAsWhen() {
        val file = CentityFile(
            nodes = nodes,
            spawning = SpawningDef(
                moonPhase = listOf(MoonPhase.NEW_MOON, MoonPhase.FULL_MOON, MoonPhase.NEW_MOON),
                weather = listOf(SpawnWeather.THUNDER, SpawnWeather.CLEAR),
                condition = "blood_moon"
            )
        )
        val text = CentityKind.write(file)
        assertTrue("\"moonPhase\": [\"full_moon\", \"new_moon\"]" in text, text)
        assertTrue("\"weather\": [\"clear\", \"thunder\"]" in text, text)
        assertTrue("\"when\": \"blood_moon\"" in text, text)
        assertEquals("blood_moon", (CentityKind.parse(text, "c") as CanonicalJson.Parsed.Ok).value.spawning?.condition)
    }
}
