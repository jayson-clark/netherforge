package dev.netherforge.format.world

import dev.netherforge.format.json.CanonicalJson
import dev.netherforge.format.project.ManifestKind
import dev.netherforge.format.project.MapProjectSource
import dev.netherforge.format.project.Projects
import dev.netherforge.format.testManifest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Maps, structures and managed worlds: what's binary is checked by name and place only. */
class WorldResourcesTest {
    private val manifest = testManifest()

    @Test
    fun mapsAndStructuresAreFoundWithoutReadingThem() {
        // Binary files come in as null: format never reads them.
        val snapshot = Projects.load(
            MapProjectSource(
                mapOf(
                    "netherforge.json" to manifest,
                    "maps/arena/level.dat" to null,
                    "maps/arena/dimensions/minecraft/overworld/region/r.0.0.mca" to null,
                    "structures/arena_reset.nbt" to null
                )
            )
        )
        assertEquals(emptyList(), snapshot.problems)
        assertEquals(listOf("arena"), snapshot.models(MapKind).keys.toList())
        assertEquals("maps/arena", snapshot.placeOf(MapKind, "arena").location)
        assertEquals(listOf("arena_reset"), snapshot.models(StructureKind).keys.toList())
        assertEquals("structures/arena_reset.nbt", snapshot.placeOf(StructureKind, "arena_reset").path)
    }

    @Test
    fun managedWorldsAreWrittenAsASortedSet() {
        val parsed = ManifestKind.parse(
            testManifest(""", "managedWorlds": ["lobby", "arena", "lobby"]"""),
            "netherforge.json"
        )
        assertTrue(parsed is CanonicalJson.Parsed.Ok)
        val written = ManifestKind.write(parsed.value)
        assertTrue("\"managedWorlds\": [\"arena\", \"lobby\"]" in written, written)
    }

    @Test
    fun worldSettingsAreWrittenByWorldAndCategoryInOrder() {
        val parsed = ManifestKind.parse(
            testManifest(
                """, "worlds": { "lobby": { "spawnIntervals": { "ambient": 4, "monster": 2 }, "spawnLimits": { "axolotl": 1, "monster": 0 } },
                   "arena": { "spawnLimits": { "water_underground_creature": 3, "animal": 70 } }, "empty": {} }"""
            ),
            "netherforge.json"
        )
        assertTrue(parsed is CanonicalJson.Parsed.Ok)
        val written = ManifestKind.write(parsed.value)
        val expected = """
            |  "worlds": {
            |    "arena": {
            |      "spawnLimits": {
            |        "animal": 70,
            |        "water_underground_creature": 3
            |      }
            |    },
            |    "lobby": {
            |      "spawnLimits": {
            |        "monster": 0,
            |        "axolotl": 1
            |      },
            |      "spawnIntervals": {
            |        "monster": 2,
            |        "ambient": 4
            |      }
            |    }
            |  }
        """.trimMargin()
        assertTrue(expected in written, written)
        val again = ManifestKind.parse(written, "netherforge.json")
        assertTrue(again is CanonicalJson.Parsed.Ok)
        assertEquals(written, ManifestKind.write(again.value))
    }

    @Test
    fun anUnknownSpawnCategoryIsAParseError() {
        val parsed = ManifestKind.parse(testManifest(""", "worlds": { "lobby": { "spawnLimits": { "misc": 1 } } }"""), "netherforge.json")
        assertTrue(parsed is CanonicalJson.Parsed.Failed)
    }
}
